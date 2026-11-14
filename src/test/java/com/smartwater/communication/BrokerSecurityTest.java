package com.smartwater.communication;

import com.smartwater.algorithms.LamportClock;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttSecurityException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Sécurité du broker (contrat, section 2.1) : authentification + ACL.
 *
 * Le test lance SON PROPRE Mosquitto sur un port libre, avec une copie de {@code mosquitto/acl}
 * et des comptes temporaires : le broker habituel (port 1883) n'est pas touché.
 * Ignoré si {@code mosquitto} / {@code mosquitto_passwd} ne sont pas dans le PATH.
 */
class BrokerSecurityTest {

    static final Map<String, String> PASSWORDS = Map.of(
            "A", "pw-A-test", "B", "pw-B-test", "C", "pw-C-test", "DASHBOARD", "pw-DASH-test");

    @TempDir
    static Path tmp;

    static Process broker;
    static String url;

    final List<MqttClient> clients = new ArrayList<>();
    final List<NodeMqttClient> nodes = new ArrayList<>();

    // ---------------------------------------------------------------- broker de test

    @BeforeAll
    static void startBroker() throws Exception {
        Path passwd = tmp.resolve("passwd");
        try {
            buildPasswd(passwd);
        } catch (IOException e) {
            assumeTrue(false, "mosquitto_passwd introuvable dans le PATH : test ignoré");
            return;
        }

        Path acl = tmp.resolve("acl");
        Path projectAcl = Path.of("mosquitto", "acl");
        assertTrue(Files.exists(projectAcl), "mosquitto/acl introuvable (lancer les tests depuis la racine du projet)");
        Files.copy(projectAcl, acl);

        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        Path conf = tmp.resolve("mosquitto.conf");
        Files.writeString(conf, "listener " + port + " 127.0.0.1\n"
                + "allow_anonymous false\n"
                + "password_file " + slash(passwd) + "\n"
                + "acl_file " + slash(acl) + "\n");

        try {
            broker = new ProcessBuilder("mosquitto", "-c", conf.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(tmp.resolve("broker.log").toFile())
                    .start();
        } catch (IOException e) {
            assumeTrue(false, "mosquitto introuvable dans le PATH : test ignoré");
            return;
        }
        waitForPort(port);
        url = "tcp://127.0.0.1:" + port;
    }

    /** Crée le fichier de comptes avec mosquitto_passwd : le premier compte avec -c (création), les autres ajoutés. */
    private static void buildPasswd(Path passwd) throws IOException, InterruptedException {
        boolean first = true;
        for (Map.Entry<String, String> account : PASSWORDS.entrySet()) {
            List<String> cmd = new ArrayList<>(List.of("mosquitto_passwd", "-b"));
            if (first) cmd.add("-c");
            cmd.addAll(List.of(passwd.toString(), account.getKey(), account.getValue()));
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            assertEquals(0, p.waitFor(), "mosquitto_passwd a échoué pour " + account.getKey());
            first = false;
        }
    }

    private static String slash(Path p) {
        return p.toString().replace('\\', '/');
    }

    private static void waitForPort(int port) throws Exception {
        long end = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < end) {
            if (!broker.isAlive()) {
                fail("Mosquitto s'est arrêté au démarrage :\n" + Files.readString(tmp.resolve("broker.log")));
            }
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return;
            } catch (IOException notYet) {
                Thread.sleep(100);
            }
        }
        fail("Mosquitto n'écoute pas sur le port " + port + " après 10 s :\n"
                + Files.readString(tmp.resolve("broker.log")));
    }

    @AfterAll
    static void stopBroker() throws Exception {
        if (broker != null) {
            broker.destroy();
            if (!broker.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                broker.destroyForcibly();
            }
        }
    }

    @AfterEach
    void cleanUp() {
        for (MqttClient c : clients) {
            try { if (c.isConnected()) c.disconnect(); c.close(); } catch (Exception ignored) { }
        }
        for (NodeMqttClient n : nodes) {
            try { n.disconnect(); } catch (Exception ignored) { }
        }
    }

    // ---------------------------------------------------------------- outils

    /** Client Paho brut, connecté avec les identifiants donnés (null = anonyme). */
    MqttClient raw(String clientId, String user, String pass) throws MqttException {
        MqttClient c = new MqttClient(url, clientId, new MemoryPersistence());
        clients.add(c);
        MqttConnectOptions o = new MqttConnectOptions();
        o.setCleanSession(true);
        if (user != null) {
            o.setUserName(user);
            o.setPassword(pass.toCharArray());
        }
        c.connect(o);
        return c;
    }

    MqttClient asUser(String user) throws MqttException {
        return raw("test-" + user, user, PASSWORDS.get(user));
    }

    /** S'abonne et collecte les messages reçus ; un refus explicite d'abonnement (SUBACK 0x80) est toléré. */
    List<String> listen(MqttClient c, String topic) {
        List<String> received = new CopyOnWriteArrayList<>();
        try {
            c.subscribe(topic, 1, (t, m) -> received.add(new String(m.getPayload(), StandardCharsets.UTF_8)));
        } catch (MqttException refused) {
            // l'ACL peut refuser l'abonnement explicitement ; dans les deux cas rien ne doit arriver
        }
        return received;
    }

    void publish(MqttClient c, String topic, String text) throws MqttException {
        MqttMessage m = new MqttMessage(text.getBytes(StandardCharsets.UTF_8));
        m.setQos(1);
        c.publish(topic, m);
    }

    static void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < end) {
            Thread.sleep(50);
        }
    }

    // ---------------------------------------------------------------- authentification

    @Test
    void anonymousConnectionIsRefused() {
        assertThrows(MqttSecurityException.class, () -> raw("anon", null, null));
    }

    @Test
    void wrongPasswordAndUnknownUserAreRefused() {
        assertThrows(MqttSecurityException.class, () -> raw("A", "A", "mauvais-mot-de-passe"));
        assertThrows(MqttSecurityException.class, () -> raw("intrus", "D", "peu-importe"));
    }

    @Test
    void nodeClientConnectsWithItsOwnCredentials() throws Exception {
        NodeMqttClient a = new NodeMqttClient("A", new LamportClock(), url, PASSWORDS.get("A"));
        nodes.add(a);
        a.connect();
        assertTrue(a.isConnected());
    }

    @Test
    void nodeClientWithWrongPasswordFailsFastWithoutRetrying() {
        NodeMqttClient a = new NodeMqttClient("A", new LamportClock(), url, "mauvais");
        nodes.add(a);
        long start = System.currentTimeMillis();
        assertThrows(MqttSecurityException.class, () -> a.connectWithRetry(3, 2000));
        assertTrue(System.currentTimeMillis() - start < 1500, "aucune nouvelle tentative attendue");
        assertFalse(a.isConnected());
    }

    @Test
    void anonymousNodeClientIsRefusedBySecuredBroker() {
        NodeMqttClient a = new NodeMqttClient("A", new LamportClock(), url, null);
        nodes.add(a);
        assertThrows(MqttSecurityException.class, a::connect);
    }

    // ---------------------------------------------------------------- ACL

    @Test
    void dashboardCannotPublishButCanRead() throws Exception {
        MqttClient a = asUser("A");
        MqttClient dashboard = asUser("DASHBOARD");
        List<String> seenByA = listen(a, "water/all");
        List<String> seenByDashboard = listen(dashboard, "water/all");

        publish(dashboard, "water/all", "intrusion-du-dashboard");   // refusé par l'ACL (écarté silencieusement)
        Thread.sleep(1000);
        assertTrue(seenByA.isEmpty(), "le dashboard ne doit rien pouvoir publier : " + seenByA);

        // témoin : un vrai nœud, lui, peut diffuser, et le dashboard le lit
        MqttClient b = asUser("B");
        publish(b, "water/all", "diffusion-de-B");
        waitUntil(() -> !seenByA.isEmpty() && !seenByDashboard.isEmpty(), 3000);
        assertEquals(List.of("diffusion-de-B"), seenByA);
        assertEquals(List.of("diffusion-de-B"), seenByDashboard);
    }

    @Test
    void nodeDoesNotReceiveMessagesAddressedToAnotherNode() throws Exception {
        MqttClient a = asUser("A");
        MqttClient b = asUser("B");
        MqttClient c = asUser("C");
        List<String> aSpiesOnB = listen(a, "water/node/B");   // A tente d'écouter le topic de B
        List<String> bOwn = listen(b, "water/node/B");

        publish(c, "water/node/B", "reply-de-C-pour-B");     // C a le droit d'écrire vers n'importe quel nœud
        waitUntil(() -> !bOwn.isEmpty(), 3000);
        Thread.sleep(500);

        assertEquals(List.of("reply-de-C-pour-B"), bOwn, "B reçoit son propre topic");
        assertTrue(aSpiesOnB.isEmpty(), "A ne doit pas lire water/node/B : " + aSpiesOnB);
    }

    @Test
    void nodeCannotPublishOutsideTheWaterTopics() throws Exception {
        MqttClient a = asUser("A");
        MqttClient dashboard = asUser("DASHBOARD");
        List<String> seen = new CopyOnWriteArrayList<>();
        try {
            dashboard.subscribe("#", 1, (t, m) -> seen.add(t));   // le dashboard n'a le droit de lire que water/all
        } catch (MqttException ignored) { }

        publish(a, "autre/sujet", "hors-perimetre");
        publish(a, "water/all", "dans-le-perimetre");
        waitUntil(() -> seen.contains("water/all"), 3000);
        Thread.sleep(500);

        assertTrue(seen.contains("water/all"));
        assertFalse(seen.contains("autre/sujet"), "A ne peut écrire que sous water/ : " + seen);
    }

    // ---------------------------------------------------------------- pile complète via le broker sécurisé

    @Test
    void fullStackWorksThroughTheSecuredBroker() throws Exception {
        List<Message> atA = new CopyOnWriteArrayList<>();
        List<Message> atB = new CopyOnWriteArrayList<>();
        List<Message> atC = new CopyOnWriteArrayList<>();
        NodeMqttClient a = secureNode("A", atA);
        NodeMqttClient b = secureNode("B", atB);
        secureNode("C", atC);
        Thread.sleep(500);   // abonnements

        a.publish(new Message(MessageType.REQUEST, Message.ALL, null));
        b.publish(new Message(MessageType.REPLY, "A", null));
        waitUntil(() -> atB.size() == 1 && atC.size() == 1 && atA.size() == 1, 4000);

        assertEquals(MessageType.REQUEST, atB.get(0).getType());
        assertEquals(MessageType.REQUEST, atC.get(0).getType());
        assertEquals(1, atA.size());
        assertEquals(MessageType.REPLY, atA.get(0).getType(), "le REPLY ciblé n'arrive qu'à A");
        Thread.sleep(500);
        assertEquals(1, atB.size());
        assertEquals(1, atC.size());
    }

    private NodeMqttClient secureNode(String id, List<Message> inbox) throws Exception {
        LamportClock clock = new LamportClock();
        NodeMqttClient node = new NodeMqttClient(id, clock, url, PASSWORDS.get(id));
        MessageHandler handler = new MessageHandler(id, clock);
        handler.register(inbox::add, MessageType.values());
        node.setIncomingHandler(handler);
        nodes.add(node);
        node.connectWithRetry(3, 500);
        return node;
    }
}
