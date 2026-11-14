/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.smartwater.communication;

/**
 *
 * @author nader
 */
import com.smartwater.Config;
import com.smartwater.algorithms.LamportClock;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class NodeMqttClient implements MessageSender {

    public static final String TOPIC_ALL = "water/all";
    public static final String TOPIC_NODE_PREFIX = "water/node/";
    public static final int QOS = 2;

    private final String nodeId;
    private final LamportClock clock;
    private final String topicNode;
    private MqttClient client;
        // Déduplication : on retient les 1000 derniers msgId vus
    private static final int DEDUP_CAPACITY = 1000;
    private final Set<String> seenMsgIds = Collections.newSetFromMap(
            Collections.synchronizedMap(new LinkedHashMap<String, Boolean>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > DEDUP_CAPACITY;
                }
            }));

    // Rempli à l'étape 6 (MessageHandler)
    private volatile java.util.function.Consumer<Message> incomingHandler;

    private final String brokerUrl;
    /** Mot de passe MQTT du nœud ; null = connexion anonyme (broker non sécurisé). */
    private final String password;

    /**
     * Se connecte à {@link Config#BROKER_URL} avec {@code username = nodeId} et le mot de passe de
     * {@link Config#mqttPassword(String)}. Si aucun mot de passe n'est configuré, la connexion est
     * anonyme : elle ne fonctionne que sur un broker non sécurisé.
     */
    public NodeMqttClient(String nodeId, LamportClock clock) {
        this(nodeId, clock, Config.BROKER_URL, configuredPasswordOrNull(nodeId));
    }

    /** Variante explicite (broker et mot de passe fournis) ; {@code password == null} = connexion anonyme. */
    public NodeMqttClient(String nodeId, LamportClock clock, String brokerUrl, String password) {
        this.nodeId = nodeId;
        this.clock = clock;
        this.topicNode = TOPIC_NODE_PREFIX + nodeId;
        this.brokerUrl = brokerUrl;
        this.password = password;
    }

    private static String configuredPasswordOrNull(String nodeId) {
        try {
            return Config.mqttPassword(nodeId);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return null;   // aucun mot de passe configuré
        }
    }

    /** Branche le récepteur des messages (MessageHandler, étape 6). */
    public void setIncomingHandler(java.util.function.Consumer<Message> handler) {
        this.incomingHandler = handler;
    }
    /** Réessaie la connexion initiale (Paho ne réessaie pas tout seul le 1er connect). */
    public void connectWithRetry(int maxAttempts, long delayMs) throws MqttException, InterruptedException {
        MqttException last = null;
        for (int i = 1; i <= maxAttempts; i++) {
            try {
                connect();
                return;
            } catch (MqttSecurityException e) {
                throw e;   // identifiants ou droits refusés : réessayer ne servirait à rien
            } catch (MqttException e) {
                last = e;
                System.err.println("[" + nodeId + "] connexion échouée (" + i + "/" + maxAttempts
                        + ") : " + e.getMessage());
                Thread.sleep(delayMs);
            }
        }
        throw last;
    }

    public boolean isConnected() {
        return client != null && client.isConnected();
    }

    /** Attend une éventuelle reconnexion automatique. */
    private boolean waitConnected(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (!isConnected()) {
            if (System.currentTimeMillis() > end) return false;
            try { Thread.sleep(100); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return true;
    }
    public void connect() throws MqttException {
            if (client != null) {
            try { client.close(); } catch (Exception ignored) { }
        }
        client = new MqttClient(brokerUrl, nodeId, new MemoryPersistence());

        MqttConnectOptions opts = new MqttConnectOptions();
        if (password != null) {
            opts.setUserName(nodeId);
            opts.setPassword(password.toCharArray());
        } else {
            System.out.println("[" + nodeId + "] aucun mot de passe MQTT configuré : connexion anonyme");
        }
        opts.setCleanSession(true);
        opts.setAutomaticReconnect(true);   // reconnexion : détaillée à l'étape 7
        opts.setConnectionTimeout(10);
        opts.setKeepAliveInterval(30);

        client.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                // Appelé à la connexion ET à chaque reconnexion automatique
                try {
                    subscribeAll();
                    System.out.println("[" + nodeId + "] " + (reconnect ? "reconnecté" : "connecté")
                            + " à " + serverURI);
                } catch (MqttException e) {
                    System.err.println("[" + nodeId + "] échec abonnement : " + e.getMessage());
                }
            }

            @Override
            public void connectionLost(Throwable cause) {
                System.err.println("[" + nodeId + "] connexion perdue : " + cause.getMessage());
            }

            @Override
            public void messageArrived(String topic, MqttMessage mqttMessage) {
                onRawMessage(topic, mqttMessage);
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) { }
        });

        try {
            client.connect(opts);
        } catch (MqttSecurityException e) {
            System.err.println("[" + nodeId + "] connexion REFUSÉE par le broker (code " + e.getReasonCode() + ") : "
                    + (password == null
                        ? "connexion anonyme non autorisée, configurer le mot de passe de « " + nodeId
                          + " » (variable SMARTWATER_MQTT_PASSWORD_" + nodeId
                          + " ou config/mqtt-passwords.properties)"
                        : "utilisateur « " + nodeId + " » ou mot de passe incorrect"
                          + " (vérifier config/mqtt-passwords.properties et mosquitto/passwd)"));
            throw e;
        }
    }

    private void subscribeAll() throws MqttException {
        client.subscribe(new String[] { TOPIC_ALL, topicNode }, new int[] { QOS, QOS });
    }

    /** Réception brute : JSON -> Message. Le filtrage et la déduplication arrivent à l'étape 5. */
     private void onRawMessage(String topic, MqttMessage mqttMessage) {
        String json = new String(mqttMessage.getPayload(), java.nio.charset.StandardCharsets.UTF_8);
        Message m;
        try {
            m = Message.fromJson(json);
        } catch (Exception e) {
            System.err.println("[" + nodeId + "] message illisible sur " + topic + " : " + json);
            return;
        }

        // Message mal formé (champs obligatoires absents)
        if (m == null || m.getType() == null || m.getFrom() == null || m.getMsgId() == null) {
            System.err.println("[" + nodeId + "] message incomplet ignoré : " + json);
            return;
        }

        // 1) Ignorer ses propres messages (reçus sur water/all)
        if (nodeId.equals(m.getFrom())) {
            return;
        }

        // 2) Déduplication : add() renvoie false si le msgId est déjà connu
        if (!seenMsgIds.add(m.getMsgId())) {
            System.out.println("[" + nodeId + "] doublon ignoré : " + m.getMsgId());
            return;
        }

        System.out.println("[" + nodeId + "] reçu sur " + topic + " : " + m);
        java.util.function.Consumer<Message> h = incomingHandler;
        if (h != null) h.accept(m);
    }

    public void disconnect() throws MqttException {
        if (client != null && client.isConnected()) {
            client.disconnect();
            client.close();
        }
    }

    public String getNodeId() { return nodeId; }

    // publish() : étape 5
    @Override
    public synchronized int publish(Message m) {
                       if (!waitConnected(5000)) {
            throw new IllegalStateException("[" + nodeId + "] non connecté au broker (5 s)");
        }

        // tick + remplissage des champs gérés par le transport
        int ts = clock.tick();
        m.setLamport(ts);
        m.setMsgId(UUID.randomUUID().toString());
        m.setFrom(nodeId);

        // topic déduit du champ "to"
        String topic = m.isBroadcast() ? TOPIC_ALL : TOPIC_NODE_PREFIX + m.getTo();

        try {
            MqttMessage mqttMessage = new MqttMessage(
                    m.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            mqttMessage.setQos(QOS);
            client.publish(topic, mqttMessage);
        } catch (MqttException e) {
            throw new RuntimeException("[" + nodeId + "] échec publication : " + e.getMessage(), e);
        }
        return ts;
    }
}
