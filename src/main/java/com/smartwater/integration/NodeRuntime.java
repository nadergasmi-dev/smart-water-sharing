package com.smartwater.integration;

import com.smartwater.Config;
import com.smartwater.algorithms.ChandyLamport;
import com.smartwater.algorithms.LamportClock;
import com.smartwater.blockchain.Blockchain;
import com.smartwater.blockchain.LedgerReplicator;
import com.smartwater.communication.MessageHandler;
import com.smartwater.communication.NodeMqttClient;
import com.smartwater.irrigation.PumpAccess;
import com.google.gson.JsonObject;
import com.smartwater.blockchain.Block;
import com.smartwater.blockchain.RejectedBlock;
import com.smartwater.communication.Message;
import com.smartwater.communication.MessageType;
import com.smartwater.node.LocalStateProvider;
import com.smartwater.node.NodeStatus;
import org.eclipse.paho.client.mqttv3.MqttException;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Tout ce que Binôme 1 fournit à un nœud, assemblé et branché :
 * horloge, client MQTT, MessageHandler, registre (clés de {@code keys/}), réplicateur, Chandy-Lamport.
 *
 * <pre>
 * // dans FarmerNode (Binôme 2)
 * NodeRuntime rt = new NodeRuntime("A", this);                 // this = LocalStateProvider
 * RicartAgrawala ra = new RicartAgrawala("A", rt.clock(), rt.sender());
 * rt.handler().register(ra, MessageType.REQUEST, MessageType.REPLY, MessageType.RELEASE);
 * rt.start();                                                   // connexion + rattrapage du registre
 * IrrigationSequence seq = rt.irrigationSequence(ra);
 * seq.irrigate((quota, reservoir) -> Math.min(besoin, Math.min(quota, reservoir)), listener);
 * rt.snapshot().startSnapshot();                                // déclencher un snapshot depuis ce nœud
 * </pre>
 */
public class NodeRuntime {

    /** Nombre de blocs refusés récents joints à chaque balise. */
    public static final int MAX_REJECTED_IN_BEACON = 20;

    /** Attente maximale d'un rattrapage du registre avant de créer un bloc. */
    public static final long SYNC_TIMEOUT_MS = 3000;

    private final String nodeId;
    private final LamportClock clock;
    private final NodeMqttClient mqtt;
    private final MessageHandler handler;
    private final Blockchain ledger;
    private final LedgerReplicator replicator;
    private final ChandyLamport snapshot;
    private final LocalStateProvider stateProvider;
    private volatile Supplier<JsonObject> statsSupplier = JsonObject::new;
    private ScheduledExecutorService beacons;
    private boolean beaconErrorLogged;

    /** Nœud standard : broker et mot de passe de {@link Config}, clés dans {@code keys/}. */
    public NodeRuntime(String nodeId, LocalStateProvider stateProvider) {
        this(nodeId, stateProvider, Path.of(Config.KEYS_DIR));
    }

    /** Variante avec un dossier de clés (tests). */
    public NodeRuntime(String nodeId, LocalStateProvider stateProvider, Path keysDir) {
        this.nodeId = nodeId;
        this.stateProvider = stateProvider;
        this.clock = new LamportClock();
        this.mqtt = new NodeMqttClient(nodeId, clock);
        this.handler = new MessageHandler(nodeId, clock);
        this.ledger = Blockchain.forNode(nodeId, keysDir);
        this.replicator = new LedgerReplicator(nodeId, ledger, mqtt);
        this.snapshot = new ChandyLamport(nodeId, stateProvider, ledger, mqtt, handler::execute);

        handler.register(replicator, LedgerReplicator.TYPES);
        handler.setSnapshotService(snapshot);
        mqtt.setIncomingHandler(handler);
    }

    /** Connexion au broker, puis demande de rattrapage du registre (un nœud qui démarre peut être en retard). */
    public void start() throws MqttException, InterruptedException {
        mqtt.connectWithRetry(5, 1000);
        replicator.requestSync();
        startBeacons(Config.STATUS_PERIOD_MS);
    }

    /** Compteurs propres au nœud, joints à chaque balise d'état (demandes, accès…). Facultatif. */
    public void setStatsSupplier(Supplier<JsonObject> supplier) {
        this.statsSupplier = supplier == null ? JsonObject::new : supplier;
    }

    /** Démarre les balises d'état {@code NODE_STATUS} ({@code periodMs <= 0} : aucune balise). */
    public synchronized void startBeacons(long periodMs) {
        if (periodMs <= 0 || beacons != null) {
            return;
        }
        beacons = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "beacon-" + nodeId);
            t.setDaemon(true);
            return t;
        });
        beacons.scheduleWithFixedDelay(() -> {
            try {
                publishStatus();
            } catch (RuntimeException e) {
                if (!beaconErrorLogged) {          // une seule fois : ne pas inonder la console
                    beaconErrorLogged = true;
                    System.err.println("[" + nodeId + "] balise d'état non envoyée : " + e.getMessage());
                }
            }
        }, periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Diffuse maintenant l'état du nœud : état local, compteurs, chaîne complète (sans genesis).
     * Lu sous le verrou du client MQTT, comme le snapshot et la séquence d'irrigation : l'état publié est
     * toujours cohérent (jamais un bloc ajouté mais des compteurs pas encore mis à jour).
     */
    public void publishStatus() {
        if (!mqtt.isConnected()) {
            return;
        }
        synchronized (mqtt) {
            List<Block> chain = ledger.getChain();
            List<RejectedBlock> refused = ledger.getRejected();
            NodeStatus status = new NodeStatus(stateProvider.getLocalState(), statsSupplier.get(),
                    chain.subList(1, chain.size()),
                    refused.subList(Math.max(0, refused.size() - MAX_REJECTED_IN_BEACON), refused.size()));
            mqtt.publish(new Message(MessageType.NODE_STATUS, Message.ALL, status.toPayload()));
        }
    }

    public void stop() {
        synchronized (this) {
            if (beacons != null) {
                beacons.shutdownNow();
                beacons = null;
            }
        }
        try {
            mqtt.disconnect();
        } catch (MqttException e) {
            System.err.println("[" + nodeId + "] déconnexion : " + e.getMessage());
        }
        handler.shutdown();
    }

    /** La séquence d'irrigation du contrat (section 8), avec la pompe fournie par Binôme 2. */
    public IrrigationSequence irrigationSequence(PumpAccess pump) {
        return new IrrigationSequence(nodeId, pump, ledger, replicator, mqtt, SYNC_TIMEOUT_MS);
    }

    public String nodeId()                 { return nodeId; }
    public LamportClock clock()            { return clock; }
    public NodeMqttClient sender()         { return mqtt; }
    public MessageHandler handler()        { return handler; }
    public Blockchain ledger()             { return ledger; }
    public LedgerReplicator replicator()   { return replicator; }
    public ChandyLamport snapshot()        { return snapshot; }
}
