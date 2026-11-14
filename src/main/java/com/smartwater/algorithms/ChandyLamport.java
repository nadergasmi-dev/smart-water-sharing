package com.smartwater.algorithms;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.smartwater.Config;
import com.smartwater.blockchain.Block;
import com.smartwater.blockchain.Ledger;
import com.smartwater.communication.Message;
import com.smartwater.communication.MessageSender;
import com.smartwater.communication.MessageType;
import com.smartwater.node.LocalState;
import com.smartwater.node.LocalStateProvider;
import com.smartwater.snapshot.GlobalSnapshot;
import com.smartwater.snapshot.LocalSnapshot;
import com.smartwater.snapshot.SnapshotService;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Snapshot global cohérent (Chandy-Lamport), contrat section 4.3.
 *
 * <ol>
 *   <li>L'initiateur enregistre son état local et diffuse un MARKER.</li>
 *   <li>Au <b>premier</b> MARKER reçu, un nœud enregistre son état, diffuse un MARKER, et commence à
 *       enregistrer ses canaux entrants (sauf celui d'où vient ce MARKER, vide par définition).</li>
 *   <li>Chaque MARKER suivant arrête l'enregistrement du canal d'où il vient.</li>
 *   <li>Un nœud a fini quand il a reçu un MARKER de chaque autre nœud ; il diffuse alors son instantané
 *       local (SNAPSHOT_STATE). L'état global est complet quand les 3 instantanés locaux sont connus.</li>
 * </ol>
 *
 * Hypothèse : canaux FIFO, garantis par un topic MQTT par expéditeur (contrat v3.1).
 * Un seul snapshot à la fois : un MARKER d'un autre snapshot reçu pendant qu'un snapshot est en cours est ignoré.
 *
 * Tous les messages sont traités sur le thread unique du MessageHandler ; {@link #startSnapshot()} passe
 * aussi par ce thread (Executor fourni), pour ne jamais enregistrer l'état au milieu d'un message.
 */
public class ChandyLamport implements SnapshotService {

    private static final Gson GSON = new Gson();

    private final String nodeId;
    private final List<String> peers;        // les autres nœuds (canaux entrants)
    private final List<String> allNodes;     // A, B, C
    private final LocalStateProvider stateProvider;
    private final Ledger ledger;
    private final MessageSender sender;      // null = observateur (dashboard)
    private final Executor executor;

    private int counter;
    // ---- snapshot courant (accès sous verrou « this »)
    private String snapshotId;
    private String initiator;
    private LocalState recordedState;
    private int ledgerLength;
    private String ledgerLastHash;
    private long recordedAt;
    private final Map<String, List<Message>> channels = new LinkedHashMap<>();
    private final Set<String> recording = new HashSet<>();
    private final Set<String> markersFrom = new HashSet<>();
    private boolean localDone;
    private final Map<String, LocalSnapshot> collected = new LinkedHashMap<>();

    /**
     * Nœud participant.
     *
     * @param executor exécute une tâche entre deux messages : en production
     *                 {@code messageHandler::execute} ; dans les tests {@code Runnable::run}
     */
    public ChandyLamport(String nodeId, LocalStateProvider stateProvider, Ledger ledger,
                         MessageSender sender, Executor executor) {
        this.nodeId = Objects.requireNonNull(nodeId);
        this.stateProvider = Objects.requireNonNull(stateProvider);
        this.ledger = Objects.requireNonNull(ledger);
        this.sender = Objects.requireNonNull(sender);
        this.executor = Objects.requireNonNull(executor);
        this.allNodes = Config.NODE_IDS;
        this.peers = allNodes.stream().filter(id -> !id.equals(nodeId)).toList();
    }

    private ChandyLamport() {
        this.nodeId = "DASHBOARD";
        this.stateProvider = null;
        this.ledger = null;
        this.sender = null;
        this.executor = Runnable::run;
        this.allNodes = Config.NODE_IDS;
        this.peers = List.of();
    }

    /**
     * Observateur (dashboard) : ne participe pas et n'envoie rien ; il assemble l'état global à partir des
     * SNAPSHOT_STATE qu'il lit sur {@code water/from/+} (lui passer ces messages avec {@link #onMessage}).
     */
    public static ChandyLamport observer() {
        return new ChandyLamport();
    }

    // ---------------------------------------------------------------- déclenchement

    @Override
    public void startSnapshot() {
        if (sender == null) {
            throw new IllegalStateException("Observateur (dashboard) : il ne peut pas déclencher de snapshot "
                    + "(lecture seule) ; le déclenchement se fait depuis un nœud");
        }
        executor.execute(() -> {
            synchronized (this) {
                if (inProgress()) {
                    log("snapshot " + snapshotId + " déjà en cours, demande ignorée");
                    return;
                }
                begin(nodeId + "-" + (++counter), nodeId, null);
            }
        });
    }

    // ---------------------------------------------------------------- réception

    @Override
    public synchronized void onMessage(Message m) {
        if (m.getType() == MessageType.MARKER) {
            onMarker(m);
        } else if (m.getType() == MessageType.SNAPSHOT_STATE) {
            onSnapshotState(m);
        } else if (recording.contains(m.getFrom())) {
            // reçu après mon enregistrement et avant le MARKER de cet expéditeur : en transit
            channels.get(m.getFrom()).add(m);
        }
    }

    private void onMarker(Message m) {
        if (sender == null) return;   // l'observateur ne participe pas
        String id = string(m.getPayload(), "snapshotId");
        String init = string(m.getPayload(), "initiator");
        String from = m.getFrom();
        if (id == null || !peers.contains(from)) {
            log("MARKER invalide ignoré (de " + from + ")");
            return;
        }
        if (!id.equals(snapshotId)) {
            if (inProgress()) {
                log("MARKER de « " + id + " » ignoré : snapshot " + snapshotId + " en cours (un seul à la fois)");
                return;
            }
            begin(id, init, from);          // premier MARKER de ce snapshot
        } else {
            recording.remove(from);         // MARKER suivant : fin du canal « from »
            markersFrom.add(from);
        }
        finishLocalIfReady();
    }

    private void onSnapshotState(Message m) {
        JsonObject p = m.getPayload();
        String id = string(p, "snapshotId");
        if (id == null || p.get("local") == null || !p.get("local").isJsonObject()) return;
        LocalSnapshot local;
        try {
            local = GSON.fromJson(p.get("local"), LocalSnapshot.class);
        } catch (RuntimeException e) {
            log("SNAPSHOT_STATE illisible ignoré (de " + m.getFrom() + ")");
            return;
        }
        if (local == null || !m.getFrom().equals(local.nodeId())) {
            log("SNAPSHOT_STATE ignoré : publié par " + m.getFrom() + " au nom d'un autre nœud");
            return;
        }
        if (!id.equals(snapshotId)) {
            if (sender != null) return;     // un nœud ne collecte que pour son snapshot courant
            snapshotId = id;                // l'observateur suit le dernier snapshot annoncé
            initiator = string(p, "initiator");
            collected.clear();
        }
        collected.put(local.nodeId(), local);
    }

    // ---------------------------------------------------------------- étapes de l'algorithme

    /** Enregistre l'état local, diffuse le MARKER, prépare l'enregistrement des canaux. */
    private void begin(String id, String init, String firstMarkerFrom) {
        snapshotId = id;
        initiator = init;
        channels.clear();
        recording.clear();
        markersFrom.clear();
        collected.clear();
        localDone = false;

        for (String peer : peers) {
            channels.put(peer, new ArrayList<>());
            if (peer.equals(firstMarkerFrom)) {
                markersFrom.add(peer);      // canal d'où vient le 1er MARKER : vide
            } else {
                recording.add(peer);
            }
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("snapshotId", id);
        payload.addProperty("initiator", init);
        // État enregistré et MARKER envoyé d'un seul bloc : aucun autre message de ce nœud ne peut partir
        // entre les deux (NodeMqttClient.publish est synchronisé sur le client lui-même).
        synchronized (sender) {
            recordedState = stateProvider.getLocalState();
            List<Block> chain = ledger.getChain();
            ledgerLength = chain.size();
            ledgerLastHash = chain.get(chain.size() - 1).getHash();
            recordedAt = System.currentTimeMillis();
            sender.publish(new Message(MessageType.MARKER, Message.ALL, payload));
        }
        log("snapshot " + id + " : état enregistré, MARKER diffusé"
                + (firstMarkerFrom == null ? " (initiateur)" : " (1er MARKER reçu de " + firstMarkerFrom + ")"));
    }

    /** Fin locale quand un MARKER a été reçu de chaque autre nœud : diffuse l'instantané local. */
    private void finishLocalIfReady() {
        if (localDone || !markersFrom.containsAll(peers)) return;
        localDone = true;
        LocalSnapshot local = new LocalSnapshot(nodeId, recordedState, ledgerLength, ledgerLastHash,
                channels, recordedAt);
        collected.put(nodeId, local);

        JsonObject payload = new JsonObject();
        payload.addProperty("snapshotId", snapshotId);
        payload.addProperty("initiator", initiator);
        payload.add("local", GSON.toJsonTree(local));
        sender.publish(new Message(MessageType.SNAPSHOT_STATE, Message.ALL, payload));

        int inTransit = local.channels().values().stream().mapToInt(List::size).sum();
        log("snapshot " + snapshotId + " : terminé localement, " + inTransit + " message(s) en transit enregistré(s)");
    }

    // ---------------------------------------------------------------- résultat

    @Override
    public synchronized boolean isComplete() {
        return snapshotId != null && collected.keySet().containsAll(allNodes);
    }

    @Override
    public synchronized GlobalSnapshot getResult() {
        if (snapshotId == null) return null;
        return new GlobalSnapshot(snapshotId, initiator, collected, allNodes);
    }

    /** true quand CE nœud a fini sa partie (MARKER reçu de chaque autre nœud). */
    public synchronized boolean isLocalComplete() {
        return localDone;
    }

    public synchronized String getSnapshotId() {
        return snapshotId;
    }

    private boolean inProgress() {
        return snapshotId != null && !localDone && sender != null;
    }

    private static String string(JsonObject p, String key) {
        return p != null && p.has(key) && !p.get(key).isJsonNull() ? p.get(key).getAsString() : null;
    }

    private void log(String text) {
        System.out.println("[" + nodeId + "] chandy-lamport : " + text);
    }
}
