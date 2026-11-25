package com.smartwater.communication;

import com.smartwater.Config;
import com.smartwater.algorithms.LamportClock;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class NodeMqttClient implements MessageSender {

    // Un topic par expéditeur : water/from/{id}
    public static final String TOPIC_FROM_PREFIX = "water/from/";
    // Abonnement aux messages de tous les expéditeurs
    public static final String TOPIC_FROM_ALL = TOPIC_FROM_PREFIX + "+";
    public static final int QOS = 2;

    private final String nodeId;
    private final LamportClock clock;
    private final String topicOut;
    // Messages jetés car leur champ from ne correspondait pas au topic (usurpation)
    private final AtomicInteger spoofedCount = new AtomicInteger();
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
    // Mot de passe MQTT du nœud ; null = connexion anonyme (broker non sécurisé)
    private final String password;

    // Se connecte à BROKER_URL avec username = nodeId et le mot de passe de mqttPassword
    public NodeMqttClient(String nodeId, LamportClock clock) {
        this(nodeId, clock, Config.BROKER_URL, configuredPasswordOrNull(nodeId));
    }

    // Variante explicite (broker et mot de passe fournis) ; password == null = connexion anonyme
    public NodeMqttClient(String nodeId, LamportClock clock, String brokerUrl, String password) {
        this.nodeId = nodeId;
        this.clock = clock;
        this.topicOut = TOPIC_FROM_PREFIX + nodeId;
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

    // Branche le récepteur des messages (MessageHandler, étape 6)
    public void setIncomingHandler(java.util.function.Consumer<Message> handler) {
        this.incomingHandler = handler;
    }
    // Réessaie la connexion initiale (Paho ne réessaie pas tout seul le 1er connect)
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

    // Attend une éventuelle reconnexion automatique
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
        client.subscribe(TOPIC_FROM_ALL, QOS);
    }

    // Réception brute
    private void onRawMessage(String topic, MqttMessage mqttMessage) {
        // L'expéditeur réel est celui du topic : l'ACL du broker interdit d'écrire sous le nom d'un autre.
        if (!topic.startsWith(TOPIC_FROM_PREFIX)) {
            System.err.println("[" + nodeId + "] message reçu sur un topic inattendu, ignoré : " + topic);
            return;
        }
        String sender = topic.substring(TOPIC_FROM_PREFIX.length());

        String json = new String(mqttMessage.getPayload(), java.nio.charset.StandardCharsets.UTF_8);
        Message m;
        try {
            m = Message.fromJson(json);
        } catch (Exception e) {
            System.err.println("[" + nodeId + "] message illisible sur " + topic + " : " + json);
            return;
        }

        // Message mal formé (champs obligatoires absents)
        if (m == null || m.getType() == null || m.getFrom() == null || m.getTo() == null
                || m.getMsgId() == null) {
            System.err.println("[" + nodeId + "] message incomplet ignoré : " + json);
            return;
        }

        // 1) Usurpation : le champ from doit être l'expéditeur du topic
        if (!sender.equals(m.getFrom())) {
            spoofedCount.incrementAndGet();
            System.err.println("[" + nodeId + "] USURPATION détectée : message publié par « " + sender
                    + " » qui se déclare « " + m.getFrom() + " » — jeté");
            return;
        }

        // 2) Ignorer ses propres messages (on est abonné à water/from/+, donc à son propre topic)
        if (nodeId.equals(sender)) {
            return;
        }

        // 3) Message destiné à un autre nœud : pas pour nous (le tri se fait ici, plus par topic)
        if (!m.isBroadcast() && !nodeId.equals(m.getTo())) {
            return;
        }

        // 4) Déduplication : add() renvoie false si le msgId est déjà connu
        if (!seenMsgIds.add(m.getMsgId())) {
            System.out.println("[" + nodeId + "] doublon ignoré : " + m.getMsgId());
            return;
        }

        if (m.getType() != MessageType.NODE_STATUS) {      // les balises (chaîne complète) inonderaient la console
            String text = String.valueOf(m);
            System.out.println("[" + nodeId + "] reçu sur " + topic + " : "
                    + (text.length() > 220 ? text.substring(0, 220) + "…" : text));
        }
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

    // Nombre de messages jetés car leur champ from ne correspondait pas à leur topic
    public int getSpoofedMessageCount() { return spoofedCount.get(); }

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

        // toujours sur son propre topic ; le destinataire est dans le champ "to"
        try {
            MqttMessage mqttMessage = new MqttMessage(
                    m.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            mqttMessage.setQos(QOS);
            client.publish(topicOut, mqttMessage);
        } catch (MqttException e) {
            throw new RuntimeException("[" + nodeId + "] échec publication : " + e.getMessage(), e);
        }
        return ts;
    }
}
