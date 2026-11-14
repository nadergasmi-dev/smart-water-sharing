package com.smartwater.dashboard;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.smartwater.Config;
import com.smartwater.algorithms.ChandyLamport;
import com.smartwater.algorithms.LamportClock;
import com.smartwater.blockchain.Block;
import com.smartwater.blockchain.Blockchain;
import com.smartwater.blockchain.LedgerReplicator;
import com.smartwater.blockchain.RejectedBlock;
import com.smartwater.communication.Message;
import com.smartwater.communication.MessageType;
import com.smartwater.node.LocalState;
import com.smartwater.security.BlockSigner;
import com.smartwater.security.KeyGenerator;
import com.smartwater.snapshot.GlobalSnapshot;
import com.smartwater.snapshot.LocalSnapshot;

import java.nio.file.Path;
import java.security.PublicKey;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Tout ce qu'affiche le dashboard, calculé à partir des seuls messages lus sur {@code water/from/+}.
 * Aucune dépendance à JavaFX : la fenêtre lit une copie immuable ({@link #view()}) à intervalle régulier,
 * et la logique se teste sans interface.
 *
 * <ul>
 *   <li><b>Pompe (Ricart-Agrawala)</b> : l'état de chaque nœud est <i>reconstitué</i> à partir des messages
 *       observés. Après sa k-ième demande, un nœud est {@code WANTED} ; il passe {@code HELD} quand il a reçu
 *       k × (N − 1) {@code REPLY} au total ; il revient à {@code RELEASED} avec son k-ième {@code RELEASE}.
 *       Compter les totaux (plutôt que suivre une suite d'événements) rend le calcul insensible à l'ordre
 *       d'arrivée entre topics différents : un {@code REPLY} de B à A peut arriver au dashboard avant le
 *       {@code REQUEST} de A, puisqu'ils passent par deux topics distincts.</li>
 *   <li><b>Registre</b> : copie observatrice ({@link Blockchain#observer()}) tenue à jour par un réplicateur
 *       passif ; elle revalide chaque bloc (V1 à V7) et garde les blocs refusés avec la raison.</li>
 *   <li><b>Quotas et réservoir</b> : recalculés depuis cette copie (la chaîne fait foi).</li>
 *   <li><b>Snapshot</b> : état global assemblé par {@link ChandyLamport#observer()} à partir des
 *       {@code SNAPSHOT_STATE} ; c'est aussi la seule source de l'humidité des parcelles.</li>
 * </ul>
 *
 * @author Houssine Ferjani (Binôme 2)
 */
public class DashboardModel implements Consumer<Message> {

    /** Nombre d'événements gardés pour le journal. */
    public static final int MAX_EVENTS = 300;

    public record FarmerView(String nodeId, String pumpState, long requests, long entries, int lastRequestLamport,
                             int quota, int litersUsed, Double humidity, String snapshotPumpState,
                             long lastSeen) { }

    public record BlockView(int index, long timestamp, String nodeId, int liters, int quotaRemaining,
                            String hash, String previousHash, Boolean signatureOk) { }

    public record EventView(long time, String type, String from, String to, int lamport, String text) { }

    public record View(List<FarmerView> farmers, int reservoir, int capacity, List<String> holders,
                       List<String> waiting, List<BlockView> chain, boolean chainValid, List<RejectedBlock> rejected,
                       Map<String, LedgerReplicator.PeerHead> peerHeads, List<LedgerReplicator.Divergence> divergences,
                       GlobalSnapshot snapshot, List<EventView> events, long messages) {

        /** Plus d'un nœud tient la pompe d'après les messages : exclusion mutuelle violée. */
        public boolean mutualExclusionViolated() {
            return holders.size() > 1;
        }
    }

    /** Compteurs Ricart-Agrawala d'un nœud, tels qu'observés. */
    private static final class PumpCounters {
        long requests;
        long releases;
        long repliesReceived;
        int lastRequestLamport = -1;
        long lastSeen;
    }

    private final List<String> nodes;
    private final Blockchain ledger;
    private final LedgerReplicator replicator;
    private final ChandyLamport snapshot = ChandyLamport.observer();
    private final Map<String, PublicKey> publicKeys;
    private final Map<String, Boolean> signatureCache = new HashMap<>();
    private final Map<String, PumpCounters> counters = new LinkedHashMap<>();
    private final Deque<EventView> events = new ArrayDeque<>();
    private long messages;

    /** Clés publiques de {@code keys/}. */
    public DashboardModel() {
        this(Path.of(Config.KEYS_DIR));
    }

    public DashboardModel(Path keysDir) {
        this.nodes = Config.NODE_IDS;
        this.publicKeys = KeyGenerator.loadAllPublicKeys(keysDir);
        this.ledger = Blockchain.observer(keysDir);
        this.replicator = new LedgerReplicator("DASHBOARD", ledger, null);   // passif : n'envoie rien
        for (String id : nodes) {
            counters.put(id, new PumpCounters());
        }
    }

    // ------------------------------------------------------------------ réception

    @Override
    public void accept(Message m) {
        if (m == null || m.getType() == null) {
            return;
        }
        // Registre et snapshot : objets de Binôme 1, synchronisés de leur côté
        switch (m.getType()) {
            case LEDGER_UPDATE, LEDGER_REQUEST, LEDGER_SYNC -> safely(() -> replicator.onMessage(m));
            case SNAPSHOT_STATE, MARKER -> safely(() -> snapshot.onMessage(m));
            default -> { }
        }
        synchronized (this) {
            messages++;
            PumpCounters c = counters.get(m.getFrom());
            if (c != null) {
                c.lastSeen = System.currentTimeMillis();
                switch (m.getType()) {
                    case REQUEST -> {
                        c.requests++;
                        c.lastRequestLamport = m.getLamport();
                    }
                    case RELEASE -> c.releases++;
                    case REPLY -> {
                        PumpCounters target = counters.get(m.getTo());
                        if (target != null) {
                            target.repliesReceived++;
                        }
                    }
                    default -> { }
                }
            }
            events.addFirst(new EventView(System.currentTimeMillis(), m.getType().name(), m.getFrom(), m.getTo(),
                    m.getLamport(), describe(m)));
            while (events.size() > MAX_EVENTS) {
                events.removeLast();
            }
        }
    }

    private static void safely(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            System.err.println("[DASHBOARD] message ignoré : " + e.getMessage());
        }
    }

    /** État Ricart-Agrawala reconstitué (voir la documentation de la classe). */
    private String pumpState(PumpCounters c) {
        if (c.requests <= c.releases) {
            return "RELEASED";
        }
        return c.repliesReceived >= c.requests * (nodes.size() - 1) ? "HELD" : "WANTED";
    }

    // ------------------------------------------------------------------ lecture

    /** Copie immuable et cohérente de tout ce qu'il faut afficher. */
    public synchronized View view() {
        GlobalSnapshot snap = snapshot.getResult();
        List<Block> blocks = ledger.getChain();

        List<FarmerView> farmers = new ArrayList<>();
        List<String> holders = new ArrayList<>();
        List<String[]> wanting = new ArrayList<>();
        for (Map.Entry<String, PumpCounters> e : counters.entrySet()) {
            String id = e.getKey();
            PumpCounters c = e.getValue();
            String state = pumpState(c);
            if (state.equals("HELD")) {
                holders.add(id);
            } else if (state.equals("WANTED")) {
                wanting.add(new String[] {id, String.valueOf(c.lastRequestLamport)});
            }
            long entries = Math.min(c.requests, c.repliesReceived / Math.max(1, nodes.size() - 1));
            int quota = ledger.getQuota(id);
            Double humidity = null;
            String snapPump = null;
            if (snap != null && snap.nodes().get(id) != null) {
                LocalState ls = snap.nodes().get(id).state();
                humidity = ls.humidity();
                snapPump = ls.pumpState();
            }
            farmers.add(new FarmerView(id, state, c.requests, entries, c.lastRequestLamport, quota,
                    Config.QUOTA_INITIAL - quota, humidity, snapPump, c.lastSeen));
        }
        // File d'attente dans l'ordre de priorité de Ricart-Agrawala : (estampille, identifiant)
        wanting.sort((a, b) -> LamportClock.compare(Integer.parseInt(a[1]), a[0], Integer.parseInt(b[1]), b[0]));
        List<String> waiting = wanting.stream().map(a -> a[0]).toList();

        List<BlockView> chain = new ArrayList<>();
        for (Block b : blocks) {
            chain.add(new BlockView(b.getIndex(), b.getTimestamp(), b.getNodeId(), b.getLiters(),
                    b.getQuotaRemaining(), b.getHash(), b.getPreviousHash(), signatureOk(b)));
        }
        return new View(farmers, ledger.getReservoirRemaining(), Config.RESERVOIR_INITIAL, holders, waiting,
                chain, ledger.validate(), ledger.getRejected(), Map.copyOf(replicator.getPeerHeads()),
                replicator.getDivergences(), snap, List.copyOf(events), messages);
    }

    /** ✓ / ✗ de la signature ; {@code null} pour le genesis (non signé). Résultat mis en cache par hash. */
    private Boolean signatureOk(Block b) {
        if (b.getIndex() == 0) {
            return null;
        }
        return signatureCache.computeIfAbsent(b.getHash(), h -> BlockSigner.verify(b, publicKeys.get(b.getNodeId())));
    }

    public Blockchain ledger() {
        return ledger;
    }

    // ------------------------------------------------------------------ journal

    /** Une phrase lisible par type de message, pour le journal du dashboard. */
    static String describe(Message m) {
        JsonObject p = m.getPayload();
        return switch (m.getType()) {
            case REQUEST -> "demande la pompe (H=" + m.getLamport() + ")";
            case REPLY -> "autorise " + m.getTo() + " à prendre la pompe";
            case RELEASE -> "libère la pompe";
            case IRRIGATION_EVENT -> "irrigue " + num(p, "liters") + " L (quota restant " + num(p, "quotaRemaining")
                    + " L, réservoir " + num(p, "reservoirLevel") + " L)";
            case LEDGER_UPDATE -> {
                JsonElement b = p == null ? null : p.get("block");
                yield "diffuse le bloc #" + (b != null && b.isJsonObject() ? num(b.getAsJsonObject(), "index") : "?");
            }
            case LEDGER_REQUEST -> "demande un rattrapage à partir du bloc #" + num(p, "fromIndex");
            case LEDGER_SYNC -> {
                JsonElement b = p == null ? null : p.get("blocks");
                yield "envoie " + (b != null && b.isJsonArray() ? b.getAsJsonArray().size() : 0)
                        + " bloc(s) à " + m.getTo();
            }
            case MARKER -> "marqueur du snapshot " + str(p, "snapshotId");
            case SNAPSHOT_STATE -> "état local pour le snapshot " + str(p, "snapshotId");
        };
    }

    private static String num(JsonObject p, String key) {
        return p != null && p.has(key) && p.get(key).isJsonPrimitive() ? p.get(key).getAsString() : "?";
    }

    private static String str(JsonObject p, String key) {
        return num(p, key);
    }

    /** Résumé texte d'un état global, pour les logs et la capture. */
    public static String summarize(GlobalSnapshot s) {
        if (s == null) {
            return "aucun snapshot reçu";
        }
        StringBuilder sb = new StringBuilder("snapshot " + s.snapshotId() + " (initié par " + s.initiator() + ") : "
                + (s.isComplete() ? "complet" : "incomplet"));
        for (LocalSnapshot ls : s.nodes().values()) {
            sb.append(" | ").append(ls.nodeId()).append(" ").append(ls.state().pumpState())
                    .append(" quota=").append(ls.state().quotaRemaining());
        }
        return sb.toString();
    }
}
