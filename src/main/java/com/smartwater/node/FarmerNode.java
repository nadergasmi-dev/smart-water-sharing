package com.smartwater.node;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.smartwater.Config;
import com.smartwater.algorithms.RicartAgrawala;
import com.smartwater.blockchain.Block;
import com.smartwater.communication.Message;
import com.smartwater.communication.MessageListener;
import com.smartwater.communication.MessageType;
import com.smartwater.integration.IrrigationSequence;
import com.smartwater.integration.NodeRuntime;
import com.smartwater.irrigation.PumpResource;
import com.smartwater.irrigation.WaterAllocator;
import com.smartwater.snapshot.GlobalSnapshot;
import org.eclipse.paho.client.mqttv3.MqttException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Un agriculteur (A, B ou C) : un nœud indépendant du système réparti.
 *
 * <p>Il assemble :</p>
 * <ul>
 *   <li>sa parcelle simulée ({@link SoilSimulator}) et son état métier ({@link FarmerState}) ;</li>
 *   <li>tout ce que fournit Binôme 1, déjà branché ({@link NodeRuntime} : horloge de Lamport, MQTT,
 *       registre, réplication, Chandy-Lamport) ;</li>
 *   <li>la pompe distribuée ({@link RicartAgrawala}) et la vue locale du réservoir ({@link PumpResource}).</li>
 * </ul>
 *
 * <p><b>Cycle</b> ({@link #step()}) : le sol s'assèche ; si l'humidité passe sous le seuil <b>et</b> qu'il
 * reste du quota, l'agriculteur demande la pompe pour remonter jusqu'à l'humidité visée. Sinon, il ne
 * diffuse <b>aucun</b> {@code REQUEST} (contrat, section 9). L'irrigation suit la séquence imposée
 * ({@link IrrigationSequence}, contrat section 8) : l'allocation {@code min(besoin, quota, réservoir)} est
 * calculée dans la section critique, et les compteurs ne bougent qu'après le succès de {@code addBlock}.</p>
 *
 * <p><b>État local pour Chandy-Lamport</b> ({@link #getLocalState()}) : humidité, quota, état de la pompe
 * et REPLY différés, lus de façon cohérente. Chandy-Lamport l'appelle en tenant le verrou du client MQTT ;
 * la mise à jour des compteurs après une irrigation prend le même verrou, donc un snapshot ne voit jamais
 * une irrigation à moitié comptée.</p>
 *
 * <pre>
 * FarmerNode a = new FarmerNode("A");
 * a.start();              // connexion au broker + rattrapage du registre
 * a.startLoop(1000);      // un cycle par seconde
 * a.triggerSnapshot();    // état global (Chandy-Lamport) depuis ce nœud
 * </pre>
 *
 * @author Houssine Ferjani (Binôme 2)
 */
public class FarmerNode implements LocalStateProvider, MessageListener {

    /** Résultat d'un cycle. */
    public enum Outcome {
        /** Humidité suffisante : pas de demande. */
        NO_NEED,
        /** Sol sec mais quota épuisé : pas de demande. */
        NO_QUOTA,
        IRRIGATED,
        /** Entré dans la section critique, mais réservoir vide : pas de bloc. */
        NOTHING_ALLOCATED,
        /** Bloc refusé par le registre : compteurs inchangés. */
        REFUSED,
        LEDGER_NOT_UP_TO_DATE
    }

    /**
     * @param humidity humidité au moment de la décision (après assèchement)
     * @param need     litres demandés (0 si pas de demande)
     * @param liters   litres effectivement reçus
     */
    public record StepResult(Outcome outcome, double humidity, int need, int liters) { }

    private final String nodeId;
    private final SoilSimulator soil;
    private final FarmerState state;
    private final PumpResource pump;
    private final NodeRuntime rt;
    private final RicartAgrawala ra;
    private final IrrigationSequence sequence;

    /** Un seul cycle à la fois : Ricart-Agrawala n'est pas réentrant. */
    private final Object stepLock = new Object();
    private volatile Thread loop;
    private boolean quotaExhaustedLogged;            // protégé par stepLock

    /** Nœud standard : clés dans {@code keys/}, sol et seuils par défaut. */
    public FarmerNode(String nodeId) {
        this(nodeId, Path.of(Config.KEYS_DIR), new SoilSimulator(nodeId.hashCode()), new FarmerState(nodeId));
    }

    public FarmerNode(String nodeId, Path keysDir, SoilSimulator soil, FarmerState state) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        if (!nodeId.equals(state.getNodeId())) {
            throw new IllegalArgumentException("FarmerState de " + state.getNodeId() + " pour le nœud " + nodeId);
        }
        // Champs utilisés par getLocalState() initialisés AVANT de donner « this » à NodeRuntime
        this.soil = Objects.requireNonNull(soil, "soil");
        this.state = state;
        this.pump = new PumpResource();

        this.rt = new NodeRuntime(nodeId, this, keysDir);
        this.ra = new RicartAgrawala(nodeId, rt.clock(), rt.sender());
        rt.handler().register(ra, RicartAgrawala.TYPES);
        rt.handler().register(this, MessageType.IRRIGATION_EVENT);
        this.sequence = rt.irrigationSequence(ra);
        rt.setStatsSupplier(this::statsJson);          // compteurs joints à chaque balise d'état (supervision)
    }

    /**
     * Compteurs propres au nœud, joints aux balises d'état {@code NODE_STATUS} : le dashboard les affiche tels quels
     * (il ne peut pas les déduire s'il démarre après le nœud). Appelée sous le verrou du client MQTT.
     */
    private JsonObject statsJson() {
        RicartAgrawala.View v = ra.view();
        JsonObject o = new JsonObject();
        o.addProperty("requests", state.getRequests());
        o.addProperty("irrigations", state.getIrrigations());
        o.addProperty("entries", ra.getEntries());
        o.addProperty("requestLamport", v.requestTimestamp());
        JsonArray awaiting = new JsonArray();
        v.awaitingReplies().forEach(awaiting::add);
        o.add("awaiting", awaiting);
        return o;
    }

    // ------------------------------------------------------------------ cycle de vie

    /** Connexion au broker, puis rattrapage du registre. */
    public void start() throws MqttException, InterruptedException {
        rt.start();
        log("démarré : humidité " + fmt(soil.getHumidity()) + " %, seuil " + fmt(state.getThreshold())
                + " %, quota " + state.getQuotaRemaining() + " L");
    }

    /** Arrête la boucle automatique puis se déconnecte. */
    public void stop() {
        stopLoop();
        rt.stop();
    }

    /** Lance un cycle toutes les {@code periodMs} ms sur un thread dédié. */
    public synchronized void startLoop(long periodMs) {
        if (loop != null) {
            throw new IllegalStateException("boucle déjà lancée");
        }
        Thread t = new Thread(() -> {
            try {
                // Arrêt normal : stopLoop() remet « loop » à null, le cycle en cours se termine proprement
                // (pas d'interruption au milieu d'une section critique ou d'un envoi MQTT).
                while (loop == Thread.currentThread()) {
                    step();
                    Thread.sleep(periodMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                // ex. requestPump() interrompu : la demande a été abandonnée proprement par Ricart-Agrawala
                log("boucle arrêtée : " + e.getMessage());
            }
        }, "farmer-" + nodeId);
        t.setDaemon(true);
        loop = t;
        t.start();
    }

    /**
     * Arrête la boucle : le cycle en cours se termine, puis le thread s'arrête. S'il est encore bloqué
     * après 10 s (pompe jamais accordée), il est interrompu : Ricart-Agrawala abandonne alors la demande.
     */
    public void stopLoop() {
        Thread t;
        synchronized (this) {
            t = loop;
            loop = null;
        }
        if (t == null || t == Thread.currentThread()) {
            return;
        }
        try {
            t.join(10_000);
            if (t.isAlive()) {
                t.interrupt();
                t.join(2000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** @return vrai si la boucle automatique tourne */
    public boolean isLooping() {
        Thread t = loop;
        return t != null && t.isAlive();
    }

    // ------------------------------------------------------------------ comportement de l'agriculteur

    /**
     * Un pas de temps : le sol s'assèche, puis demande de la pompe si l'humidité est sous le seuil et
     * qu'il reste du quota. Bloquant pendant l'attente de la pompe.
     */
    public StepResult step() {
        synchronized (stepLock) {
            double humidity = soil.dry();
            state.syncQuota(rt.ledger().getQuota(nodeId));          // la chaîne fait foi
            if (!state.shouldRequest(humidity)) {
                Outcome o = humidity >= state.getThreshold() ? Outcome.NO_NEED : Outcome.NO_QUOTA;
                if (o == Outcome.NO_QUOTA && !quotaExhaustedLogged) {
                    quotaExhaustedLogged = true;                     // une seule fois, pas à chaque cycle
                    log("sol sec (" + fmt(humidity) + " %) mais quota épuisé : plus aucun REQUEST");
                }
                return new StepResult(o, humidity, 0, 0);
            }
            int need = soil.litersToReach(state.getTarget());
            return irrigate(humidity, need);
        }
    }

    /** Demande la pompe et irrigue (séquence du contrat, section 8). Appelé avec {@code stepLock}. */
    private StepResult irrigate(double humidity, int need) {
        state.recordRequest();
        log("humidité " + fmt(humidity) + " % < " + fmt(state.getThreshold()) + " % : demande de "
                + need + " L (quota " + state.getQuotaRemaining() + " L)");

        IrrigationSequence.Result r = sequence.irrigate(WaterAllocator.forNeed(need), new IrrigationSequence.Listener() {
            @Override
            public void onIrrigated(Block block) {
                // Étape 4 du contrat : compteurs, après le succès de addBlock. Sous le verrou du client MQTT
                // pour qu'un snapshot ne voie jamais une irrigation à moitié comptée.
                synchronized (rt.sender()) {
                    state.recordIrrigation(block.getLiters(), block.getQuotaRemaining());
                    pump.recordOwnIrrigation(nodeId, block.getLiters(), rt.ledger().getReservoirRemaining());
                    soil.irrigate(block.getLiters());
                }
            }

            @Override
            public void onNothingAllocated() {
                state.recordNothingAllocated();
                pump.observeLevel(rt.ledger().getReservoirRemaining());
            }

            @Override
            public void onRefused(String reason) {
                state.recordRefused();                               // seul compteur touché : une statistique
            }
        });

        Outcome o = switch (r.outcome()) {
            case IRRIGATED -> Outcome.IRRIGATED;
            case NOTHING_ALLOCATED -> Outcome.NOTHING_ALLOCATED;
            case REFUSED -> Outcome.REFUSED;
            case LEDGER_NOT_UP_TO_DATE -> Outcome.LEDGER_NOT_UP_TO_DATE;
        };
        int liters = o == Outcome.IRRIGATED ? r.liters() : 0;
        if (o == Outcome.IRRIGATED) {
            log("+" + liters + " L : humidité " + fmt(soil.getHumidity()) + " %, quota "
                    + state.getQuotaRemaining() + " L, réservoir " + pump.getLevel() + " L");
        }
        return new StepResult(o, humidity, need, liters);
    }

    /** Irrigation d'un autre nœud : met à jour la vue locale du réservoir. */
    @Override
    public void onMessage(Message m) {
        if (m == null || m.getType() != MessageType.IRRIGATION_EVENT || m.getPayload() == null) {
            return;
        }
        JsonObject p = m.getPayload();
        if (p.has("reservoirLevel") && p.get("reservoirLevel").isJsonPrimitive()) {
            pump.recordOtherIrrigation(m.getFrom(), p.get("reservoirLevel").getAsInt());
        }
    }

    // ------------------------------------------------------------------ Chandy-Lamport

    /**
     * Copie cohérente de l'état local (contrat, section 7). Appelée par Chandy-Lamport en tenant le
     * verrou du client MQTT ; l'état de la pompe est lu d'un seul coup ({@link RicartAgrawala#view()}).
     */
    @Override
    public LocalState getLocalState() {
        RicartAgrawala.View v = ra.view();
        return new LocalState(nodeId, soil.getHumidity(), state.getQuotaRemaining(), v.state().name(),
                v.deferredReplies());
    }

    /** Déclenche un snapshot Chandy-Lamport depuis ce nœud (ignoré si un snapshot est déjà en cours). */
    public void triggerSnapshot() {
        rt.snapshot().startSnapshot();
    }

    /** Dernier état global connu (peut être incomplet si le snapshot est en cours), ou {@code null}. */
    public GlobalSnapshot lastSnapshot() {
        return rt.snapshot().getResult();
    }

    // ------------------------------------------------------------------ accès

    public String getNodeId()       { return nodeId; }
    public SoilSimulator soil()     { return soil; }
    public FarmerState state()      { return state; }
    public PumpResource pump()      { return pump; }
    public RicartAgrawala ra()      { return ra; }
    public NodeRuntime runtime()    { return rt; }

    @Override
    public String toString() {
        return "[" + nodeId + "] humidité " + fmt(soil.getHumidity()) + " %, " + state + ", " + pump
                + ", pompe " + ra.getState() + ", registre " + rt.ledger().getChain().size() + " blocs";
    }

    private void log(String text) {
        System.out.println("[" + nodeId + "] agriculteur : " + text);
    }

    private static String fmt(double d) {
        return String.format(java.util.Locale.ROOT, "%.1f", d);
    }

    // ------------------------------------------------------------------ lancement en console

    /**
     * Lance un agriculteur en console : {@code FarmerNode <A|B|C> [périodeMs]}.
     * Commandes : {@code s} snapshot, {@code e} état, {@code q} quitter.
     * Nécessite le broker et les clés de {@code keys/} (clé privée du nœud).
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || !Config.NODE_IDS.contains(args[0])) {
            System.err.println("usage : FarmerNode <A|B|C> [périodeMs]");
            System.exit(2);
        }
        long period = args.length > 1 ? Long.parseLong(args[1]) : 1000;
        FarmerNode node = new FarmerNode(args[0]);
        node.start();
        node.startLoop(period);
        System.out.println("Commandes : s = snapshot, e = état, q = quitter");
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            switch (line.trim()) {
                case "s" -> {
                    node.triggerSnapshot();
                    Thread.sleep(1000);
                    System.out.println(node.lastSnapshot());
                }
                case "e" -> System.out.println(node);
                case "q" -> {
                    node.stop();
                    return;
                }
                default -> { }
            }
        }
        node.stop();
    }
}
