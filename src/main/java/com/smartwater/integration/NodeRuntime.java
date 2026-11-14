package com.smartwater.integration;

import com.smartwater.Config;
import com.smartwater.algorithms.ChandyLamport;
import com.smartwater.algorithms.LamportClock;
import com.smartwater.blockchain.Blockchain;
import com.smartwater.blockchain.LedgerReplicator;
import com.smartwater.communication.MessageHandler;
import com.smartwater.communication.NodeMqttClient;
import com.smartwater.irrigation.PumpAccess;
import com.smartwater.node.LocalStateProvider;
import org.eclipse.paho.client.mqttv3.MqttException;

import java.nio.file.Path;

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

    /** Attente maximale d'un rattrapage du registre avant de créer un bloc. */
    public static final long SYNC_TIMEOUT_MS = 3000;

    private final String nodeId;
    private final LamportClock clock;
    private final NodeMqttClient mqtt;
    private final MessageHandler handler;
    private final Blockchain ledger;
    private final LedgerReplicator replicator;
    private final ChandyLamport snapshot;

    /** Nœud standard : broker et mot de passe de {@link Config}, clés dans {@code keys/}. */
    public NodeRuntime(String nodeId, LocalStateProvider stateProvider) {
        this(nodeId, stateProvider, Path.of(Config.KEYS_DIR));
    }

    /** Variante avec un dossier de clés (tests). */
    public NodeRuntime(String nodeId, LocalStateProvider stateProvider, Path keysDir) {
        this.nodeId = nodeId;
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
    }

    public void stop() {
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
