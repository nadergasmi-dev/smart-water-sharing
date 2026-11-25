package com.smartwater.dashboard;

import com.smartwater.Config;
import com.smartwater.communication.Message;
import com.smartwater.communication.NodeMqttClient;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Client MQTT du dashboard : lecture seule (compte DASHBOARD, contrat section 2.1)
 * @author Houssine Ferjani (Binôme 2)
 */
public class DashboardMqttClient {

    public static final String CLIENT_ID = "DASHBOARD";

    private final String brokerUrl;
    private final String password;
    private final Consumer<Message> consumer;
    private final AtomicInteger spoofed = new AtomicInteger();
    private final Set<String> seen = Collections.newSetFromMap(Collections.synchronizedMap(
            new LinkedHashMap<String, Boolean>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
                    return size() > 2000;
                }
            }));
    private volatile Consumer<String> onStatus = s -> { };
    private MqttClient client;

    // Broker et mot de passe de Config ; sans mot de passe configuré : connexion anonyme
    public DashboardMqttClient(Consumer<Message> consumer) {
        this(Config.BROKER_URL, passwordOrNull(), consumer);
    }

    public DashboardMqttClient(String brokerUrl, String password, Consumer<Message> consumer) {
        this.brokerUrl = Objects.requireNonNull(brokerUrl);
        this.password = password;
        this.consumer = Objects.requireNonNull(consumer);
    }

    private static String passwordOrNull() {
        try {
            return Config.mqttPassword(CLIENT_ID);
        } catch (IllegalStateException e) {
            return null;
        }
    }

    // Messages d'état de la connexion (pour la barre d'état de la fenêtre)
    public void setStatusListener(Consumer<String> listener) {
        this.onStatus = listener == null ? s -> { } : listener;
    }

    public synchronized void connect() throws MqttException {
        client = new MqttClient(brokerUrl, CLIENT_ID + "-" + ProcessHandle.current().pid(), new MemoryPersistence());
        MqttConnectOptions opts = new MqttConnectOptions();
        if (password != null) {
            opts.setUserName(CLIENT_ID);
            opts.setPassword(password.toCharArray());
        }
        opts.setCleanSession(true);
        opts.setAutomaticReconnect(true);
        opts.setConnectionTimeout(10);
        client.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                try {
                    client.subscribe(NodeMqttClient.TOPIC_FROM_ALL, NodeMqttClient.QOS);
                    onStatus.accept((reconnect ? "reconnecté à " : "connecté à ") + serverURI
                            + (password == null ? " (anonyme)" : " (compte DASHBOARD)"));
                } catch (MqttException e) {
                    onStatus.accept("échec de l'abonnement : " + e.getMessage());
                }
            }

            @Override
            public void connectionLost(Throwable cause) {
                onStatus.accept("connexion perdue : " + cause.getMessage());
            }

            @Override
            public void messageArrived(String topic, MqttMessage msg) {
                onRaw(topic, new String(msg.getPayload(), StandardCharsets.UTF_8));
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) { }
        });
        client.connect(opts);
    }

    // Réception brute, testable sans broker
    void onRaw(String topic, String json) {
        if (!topic.startsWith(NodeMqttClient.TOPIC_FROM_PREFIX)) {
            return;
        }
        String sender = topic.substring(NodeMqttClient.TOPIC_FROM_PREFIX.length());
        Message m;
        try {
            m = Message.fromJson(json);
        } catch (RuntimeException e) {
            return;
        }
        if (m == null || m.getType() == null || m.getFrom() == null || m.getMsgId() == null) {
            return;
        }
        if (!sender.equals(m.getFrom())) {
            spoofed.incrementAndGet();
            return;
        }
        if (!seen.add(m.getMsgId())) {
            return;
        }
        consumer.accept(m);
    }

    public int getSpoofedCount() {
        return spoofed.get();
    }

    public synchronized boolean isConnected() {
        return client != null && client.isConnected();
    }

    public synchronized void disconnect() {
        if (client == null) {
            return;
        }
        try {
            if (client.isConnected()) {
                client.disconnect();
            }
            client.close();
        } catch (MqttException e) {
            onStatus.accept("déconnexion : " + e.getMessage());
        }
    }
}
