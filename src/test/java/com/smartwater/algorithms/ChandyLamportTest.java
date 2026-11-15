package com.smartwater.algorithms;

import com.google.gson.JsonObject;
import com.smartwater.Config;
import com.smartwater.blockchain.Blockchain;
import com.smartwater.communication.Message;
import com.smartwater.communication.MessageSender;
import com.smartwater.communication.MessageType;
import com.smartwater.node.LocalState;
import com.smartwater.security.KeyGenerator;
import com.smartwater.snapshot.GlobalSnapshot;
import com.smartwater.snapshot.LocalSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.PublicKey;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Chandy-Lamport sur un réseau simulé où CHAQUE CANAL (expéditeur → destinataire) est une file FIFO livrée
 * au choix du test. Application de test : des « jetons » (le champ quotaRemaining) que les nœuds
 * s'envoient avec IRRIGATION_EVENT { amount }. Propriété vérifiée : le total des états enregistrés + les
 * jetons en transit enregistrés = le total réel. Rien n'est perdu ni compté deux fois.
 */
class ChandyLamportTest {

    static final List<String> IDS = List.of("A", "B", "C");

    /** Un nœud de test : un solde de jetons + son Chandy-Lamport. */
    class Node {
        final String id;
        int tokens = 100;
        ChandyLamport cl;

        Node(String id) { this.id = id; }

        LocalState state() {
            return new LocalState(id, 50.0, tokens, "RELEASED", List.of());
        }

        void send(String to, int amount) {
            synchronized (senderFor(id)) {   // comme l'application : état modifié et envoi d'un seul bloc
                tokens -= amount;
                JsonObject p = new JsonObject();
                p.addProperty("amount", amount);
                senderFor(id).publish(new Message(MessageType.IRRIGATION_EVENT, to, p));
            }
        }

        /** Pipeline de réception, comme MessageHandler : snapshot d'abord, puis l'application. */
        void receive(Message m) {
            cl.onMessage(m);
            if (m.getType() == MessageType.IRRIGATION_EVENT) {
                tokens += m.getPayload().get("amount").getAsInt();
            }
        }
    }

    final Map<String, Node> nodes = new LinkedHashMap<>();
    final Map<String, Deque<Message>> channels = new LinkedHashMap<>();   // "A->B" -> file FIFO
    final Map<String, MessageSender> senders = new LinkedHashMap<>();
    ChandyLamport dashboard;

    MessageSender senderFor(String id) {
        return senders.computeIfAbsent(id, x -> m -> {
            m.setFrom(x);
            m.setMsgId(UUID.randomUUID().toString());
            Message wire = Message.fromJson(m.toJson());
            if (wire.getType() == MessageType.SNAPSHOT_STATE && dashboard != null) {
                dashboard.onMessage(wire);   // le dashboard lit water/from/+
            }
            for (String to : IDS) {
                if (to.equals(x)) continue;
                if (wire.isBroadcast() || to.equals(wire.getTo())) {
                    channels.get(x + "->" + to).add(wire);
                }
            }
            return 0;
        });
    }

    @BeforeEach
    void network() throws Exception {
        Map<String, PublicKey> keys = new LinkedHashMap<>();
        for (String id : IDS) keys.put(id, KeyGenerator.generateKeyPair().getPublic());
        for (String from : IDS) for (String to : IDS) if (!from.equals(to)) channels.put(from + "->" + to, new ArrayDeque<>());
        for (String id : IDS) {
            Node n = new Node(id);
            Blockchain ledger = new Blockchain(null, keys, Config.initialQuotas(), Config.RESERVOIR_INITIAL);
            n.cl = new ChandyLamport(id, n::state, ledger, senderFor(id), Runnable::run);
            nodes.put(id, n);
        }
    }

    /** Livre le prochain message du canal from->to ; renvoie son type. */
    MessageType deliver(String from, String to) {
        Message m = channels.get(from + "->" + to).poll();
        assertNotNull(m, "canal " + from + "->" + to + " vide");
        nodes.get(to).receive(m);
        return m.getType();
    }

    void deliverAll() {
        boolean progress = true;
        while (progress) {
            progress = false;
            for (Map.Entry<String, Deque<Message>> e : channels.entrySet()) {
                if (!e.getValue().isEmpty()) {
                    String[] ends = e.getKey().split("->");
                    deliver(ends[0], ends[1]);
                    progress = true;
                }
            }
        }
    }

    static int recordedTotal(GlobalSnapshot g) {
        int total = 0;
        for (LocalSnapshot local : g.nodes().values()) total += local.state().quotaRemaining();
        for (Message m : g.messagesInTransit()) total += m.getPayload().get("amount").getAsInt();
        return total;
    }

    // ---------------------------------------------------------------- cas de base

    @Test
    void quietSystemGivesCompleteSnapshotWithoutMessagesInTransit() {
        nodes.get("A").cl.startSnapshot();
        deliverAll();

        for (String id : IDS) {
            assertTrue(nodes.get(id).cl.isComplete(), "état global complet chez " + id);
            GlobalSnapshot g = nodes.get(id).cl.getResult();
            assertEquals("A-1", g.snapshotId());
            assertEquals("A", g.initiator());
            assertTrue(g.messagesInTransit().isEmpty());
            assertEquals(300, recordedTotal(g));
            assertTrue(g.sameLedgerEverywhere());
        }
    }

    @Test
    void messageInTransitIsCapturedInTheChannel() {
        nodes.get("B").send("C", 10);          // T1 : B -> C, pas encore livré
        nodes.get("A").cl.startSnapshot();     // A enregistre 100 et diffuse MARKER

        deliver("A", "C");                     // C reçoit le MARKER de A : enregistre 100, enregistre le canal B->C
        assertEquals(MessageType.IRRIGATION_EVENT, deliver("B", "C"));   // T1 arrive APRÈS l'état de C
        deliverAll();

        GlobalSnapshot g = nodes.get("A").cl.getResult();
        assertTrue(g.isComplete());
        List<Message> inTransit = g.messagesInTransit();
        assertEquals(1, inTransit.size(), "T1 était en transit");
        assertEquals("B", inTransit.get(0).getFrom());
        assertEquals(10, inTransit.get(0).getPayload().get("amount").getAsInt());
        assertEquals(1, g.nodes().get("C").channels().get("B").size(), "enregistré sur le canal B->C");
        assertEquals(90, g.nodes().get("B").state().quotaRemaining());
        assertEquals(100, g.nodes().get("C").state().quotaRemaining(), "T1 n'est pas dans l'état de C");
        assertEquals(300, recordedTotal(g), "ni perdu, ni compté deux fois");
    }

    @Test
    void messageReceivedBeforeTheSnapshotIsInTheStateNotInAChannel() {
        nodes.get("B").send("C", 10);
        deliver("B", "C");                     // T1 livré avant tout snapshot
        nodes.get("A").cl.startSnapshot();
        deliverAll();

        GlobalSnapshot g = nodes.get("C").cl.getResult();
        assertTrue(g.messagesInTransit().isEmpty());
        assertEquals(110, g.nodes().get("C").state().quotaRemaining());
        assertEquals(300, recordedTotal(g));
    }

    @Test
    void messageSentAfterTheSnapshotIsNeitherInStatesNorInChannels() {
        nodes.get("A").cl.startSnapshot();
        deliver("A", "B");                     // B enregistre son état (100) et diffuse son MARKER
        nodes.get("B").send("C", 10);          // envoyé APRÈS l'état de B : derrière son MARKER sur B->C
        deliverAll();

        GlobalSnapshot g = nodes.get("A").cl.getResult();
        assertTrue(g.messagesInTransit().isEmpty(), "le MARKER de B arrive avant ce message (FIFO)");
        assertEquals(100, g.nodes().get("B").state().quotaRemaining());
        assertEquals(300, recordedTotal(g));
        assertEquals(110, nodes.get("C").tokens, "le message est quand même traité normalement");
    }

    @Test
    void severalMessagesInTransitOnSeveralChannelsAreAllCaptured() {
        nodes.get("B").send("C", 10);
        nodes.get("C").send("A", 5);
        nodes.get("A").send("B", 7);
        nodes.get("C").cl.startSnapshot();     // cette fois C est l'initiateur
        // C ne livre rien avant ses MARKER ; les messages applicatifs déjà en file passent avant (FIFO)
        deliver("C", "A");                     // T(C->A, 5) arrive à A AVANT qu'A ait un état : dans l'état de A
        deliver("C", "A");                     // MARKER de C : A enregistre
        deliver("B", "C");                     // T(B->C, 10) arrive à C, qui enregistre B->C : en transit
        deliverAll();

        GlobalSnapshot g = nodes.get("B").cl.getResult();
        assertTrue(g.isComplete());
        assertEquals("C-1", g.snapshotId());
        assertEquals(300, recordedTotal(g));
    }

    // ---------------------------------------------------------------- règles de l'algorithme

    @Test
    void localPartEndsOnlyAfterAMarkerFromEveryOtherNode() {
        nodes.get("A").cl.startSnapshot();
        deliver("A", "B");                     // B a son état, mais pas encore le MARKER de C
        assertFalse(nodes.get("B").cl.isLocalComplete());
        deliverAll();
        assertTrue(nodes.get("B").cl.isLocalComplete());
    }

    @Test
    void secondSnapshotDuringTheFirstIsIgnoredThenANewOneWorks() {
        nodes.get("A").cl.startSnapshot();
        nodes.get("A").cl.startSnapshot();     // ignoré : A-1 en cours
        deliverAll();
        assertEquals("A-1", nodes.get("B").cl.getSnapshotId());

        nodes.get("A").cl.startSnapshot();     // A-2 après la fin de A-1
        deliverAll();
        assertEquals("A-2", nodes.get("C").cl.getResult().snapshotId());
        assertTrue(nodes.get("C").cl.isComplete());
    }

    @Test
    void snapshotStateSentInAnotherNodesNameIsIgnored() {
        nodes.get("A").cl.startSnapshot();
        deliverAll();
        GlobalSnapshot before = nodes.get("A").cl.getResult();

        // C publie un instantané au nom de B
        LocalSnapshot fake = new LocalSnapshot("B", new LocalState("B", 0, 999, "HELD", List.of()), 1, "x", Map.of(), 0);
        JsonObject p = new JsonObject();
        p.addProperty("snapshotId", "A-1");
        p.add("local", new com.google.gson.Gson().toJsonTree(fake));
        senderFor("C").publish(new Message(MessageType.SNAPSHOT_STATE, Message.ALL, p));
        deliverAll();

        assertEquals(before.nodes().get("B").state().quotaRemaining(),
                nodes.get("A").cl.getResult().nodes().get("B").state().quotaRemaining());
    }

    // ---------------------------------------------------------------- dashboard

    @Test
    void dashboardObserverAssemblesTheGlobalSnapshotButCannotStartOne() {
        dashboard = ChandyLamport.observer();
        assertThrows(IllegalStateException.class, dashboard::startSnapshot);

        nodes.get("B").send("C", 10);
        nodes.get("A").cl.startSnapshot();
        deliver("A", "C");
        deliver("B", "C");
        deliverAll();

        assertTrue(dashboard.isComplete());
        GlobalSnapshot g = dashboard.getResult();
        assertEquals("A-1", g.snapshotId());
        assertEquals(300, recordedTotal(g));
        assertEquals(1, g.messagesInTransit().size());
    }

    @Test
    void localSnapshotIsImmutable() {
        nodes.get("A").cl.startSnapshot();
        deliverAll();
        LocalSnapshot a = nodes.get("A").cl.getResult().nodes().get("A");
        assertThrows(UnsupportedOperationException.class, () -> a.channels().put("X", new ArrayList<>()));
        assertThrows(UnsupportedOperationException.class, () -> a.channels().get("B").add(null));
    }

    @Test
    void stateBeaconsAreNeverRecordedAsMessagesInTransit() {
        // B diffuse une balise d'état, puis A lance un snapshot ; la balise arrive chez C APRÈS son enregistrement
        senderFor("B").publish(new Message(MessageType.NODE_STATUS, Message.ALL, new JsonObject()));
        nodes.get("A").cl.startSnapshot();
        deliver("A", "C");                     // C enregistre son état et écoute le canal B->C
        assertEquals(MessageType.NODE_STATUS, deliver("B", "C"));
        deliverAll();

        GlobalSnapshot g = nodes.get("A").cl.getResult();
        assertTrue(g.isComplete());
        assertTrue(g.messagesInTransit().isEmpty(), "la supervision n'est pas un message applicatif");
        assertEquals(300, recordedTotal(g));
    }
}
