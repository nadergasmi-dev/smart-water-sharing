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
        List<String> seenByA = listen(a, "water/from/+");
        List<String> seenByDashboard = listen(dashboard, "water/from/+");

        // refusés par l'ACL (écartés silencieusement) : sous son propre nom, et sous celui d'un nœud
        publish(dashboard, "water/from/DASHBOARD", "intrusion-du-dashboard");
        publish(dashboard, "water/from/A", "le-dashboard-se-fait-passer-pour-A");
        Thread.sleep(1000);
        assertTrue(seenByA.isEmpty(), "le dashboard ne doit rien pouvoir publier : " + seenByA);

        // témoin : un vrai nœud, lui, peut publier, et le dashboard le lit
        MqttClient b = asUser("B");
        publish(b, "water/from/B", "message-de-B");
        waitUntil(() -> !seenByA.isEmpty() && !seenByDashboard.isEmpty(), 3000);
        assertEquals(List.of("message-de-B"), seenByA);
        assertEquals(List.of("message-de-B"), seenByDashboard);
    }

    @Test
    void nodeCannotPublishUnderAnotherNodesName() throws Exception {
        MqttClient a = asUser("A");
        MqttClient c = asUser("C");
        List<String> seenByA = listen(a, "water/from/+");

        publish(c, "water/from/B", "usurpation-de-B-par-C");   // C écrit sous le nom de B : refusé par l'ACL
        publish(c, "water/from/A", "usurpation-de-A-par-C");   // idem pour A
        publish(c, "water/from/C", "message-legitime-de-C");   // sous son propre nom : accepté
        waitUntil(() -> !seenByA.isEmpty(), 3000);
        Thread.sleep(500);

        assertEquals(List.of("message-legitime-de-C"), seenByA,
                "seul ce que C publie sous son propre nom doit arriver");
    }

    @Test
    void everyNodeCanReadEverySenderTopic() throws Exception {
        MqttClient a = asUser("A");
        MqttClient b = asUser("B");
        MqttClient c = asUser("C");
        List<String> seenByC = listen(c, "water/from/+");

        publish(a, "water/from/A", "de-A");
        publish(b, "water/from/B", "de-B");
        waitUntil(() -> seenByC.size() == 2, 3000);

        assertTrue(seenByC.containsAll(List.of("de-A", "de-B")), "C lit les topics de A et de B : " + seenByC);
    }

    // ---------------------------------------------------------------- usurpation du champ « from »

    /** Enveloppe JSON minimale d'un message (le champ from est volontairement choisi par l'appelant). */
    static String json(String msgId, String from, String to) {
        return "{\"msgId\":\"" + msgId + "\",\"type\":\"REQUEST\",\"from\":\"" + from
                + "\",\"to\":\"" + to + "\",\"lamport\":1,\"payload\":{}}";
    }

    @Test
    void fromFieldNotMatchingTheTopicIsDroppedAndCounted() throws Exception {
        List<Message> atA = new CopyOnWriteArrayList<>();
        List<Message> atB = new CopyOnWriteArrayList<>();
        NodeMqttClient a = secureNode("A", atA);
        NodeMqttClient b = secureNode("B", atB);
        Thread.sleep(500);   // abonnements

        // C publie sous SON nom (autorisé) mais écrit "from":"B" dans le JSON : usurpation
        MqttClient c = asUser("C");
        publish(c, "water/from/C", json("faux-1", "B", "ALL"));
        waitUntil(() -> a.getSpoofedMessageCount() == 1 && b.getSpoofedMessageCount() == 1, 3000);

        assertEquals(1, a.getSpoofedMessageCount(), "A détecte l'usurpation");
        assertEquals(1, b.getSpoofedMessageCount(), "B aussi (on se fait passer pour lui)");
        assertTrue(atA.isEmpty() && atB.isEmpty(), "le message usurpé n'est livré à aucun module");

        // témoin : le même message avec un from honnête est livré normalement
        publish(c, "water/from/C", json("vrai-1", "C", "ALL"));
        waitUntil(() -> atA.size() == 1 && atB.size() == 1, 3000);
        assertEquals("C", atA.get(0).getFrom());
        assertEquals("C", atB.get(0).getFrom());
        assertEquals(1, a.getSpoofedMessageCount(), "pas de fausse alerte sur un message légitime");
    }

    @Test
    void forgingOnAnotherNodesTopicNeverReachesTheReceivers() throws Exception {
        List<Message> atA = new CopyOnWriteArrayList<>();
        NodeMqttClient a = secureNode("A", atA);
        Thread.sleep(500);

        // C écrit directement sur le topic de B, avec un from « B » parfaitement cohérent : l'ACL l'arrête
        MqttClient c = asUser("C");
        publish(c, "water/from/B", json("faux-2", "B", "ALL"));
        Thread.sleep(1000);

        assertTrue(atA.isEmpty(), "le broker ne doit pas livrer un message écrit sous le nom de B par C");
        assertEquals(0, a.getSpoofedMessageCount(), "il n'est même pas arrivé jusqu'à A");
    }

    @Test
    void messageAddressedToAnotherNodeIsNotDelivered() throws Exception {
        List<Message> atA = new CopyOnWriteArrayList<>();
        List<Message> atB = new CopyOnWriteArrayList<>();
        secureNode("A", atA);
        secureNode("B", atB);
        Thread.sleep(500);

        // C envoie un message légitime destiné à B seul : A le reçoit du broker mais l'ignore (to = B)
        MqttClient c = asUser("C");
        publish(c, "water/from/C", json("pour-b", "C", "B"));
        waitUntil(() -> atB.size() == 1, 3000);
        Thread.sleep(500);

        assertEquals(1, atB.size(), "B est le destinataire");
        assertTrue(atA.isEmpty(), "A n'est pas le destinataire : message ignoré");
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
