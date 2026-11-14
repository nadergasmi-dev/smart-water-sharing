package com.smartwater.blockchain;

import com.smartwater.Config;
import com.smartwater.algorithms.LamportClock;
import com.smartwater.communication.MessageHandler;
import com.smartwater.communication.NodeMqttClient;
import com.smartwater.security.KeyGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Réplication de bout en bout à travers le broker local (tcp://localhost:1883, comme ThreeNodesTest) :
 * vrai NodeMqttClient, vrai MessageHandler, blocs sérialisés en JSON dans les messages.
 */
class ReplicationOverMqttTest {

    static Map<String, KeyPair> pairs;
    static Map<String, PublicKey> publicKeys;

    @BeforeAll
    static void keys() {
        pairs = Map.of("A", KeyGenerator.generateKeyPair(),
                       "B", KeyGenerator.generateKeyPair(),
                       "C", KeyGenerator.generateKeyPair());
        publicKeys = Map.of("A", pairs.get("A").getPublic(),
                            "B", pairs.get("B").getPublic(),
                            "C", pairs.get("C").getPublic());
    }

    /** Un nœud complet côté registre : transport + handler + registre + réplicateur. */
    static class Node {
        final String id;
        final NodeMqttClient mqtt;
        final MessageHandler handler;
        final Blockchain ledger;
        final LedgerReplicator replicator;

        Node(String id) throws Exception {
            this.id = id;
            LamportClock clock = new LamportClock();
            mqtt = new NodeMqttClient(id, clock);
            handler = new MessageHandler(id, clock);
            ledger = new Blockchain(pairs.get(id).getPrivate(), publicKeys, Config.initialQuotas(), Config.RESERVOIR_INITIAL);
            replicator = new LedgerReplicator(id, ledger, mqtt);
            handler.register(replicator, LedgerReplicator.TYPES);
            mqtt.setIncomingHandler(handler);
            mqtt.connectWithRetry(3, 1000);
        }

        Block irrigate(int liters) throws InvalidBlockException {
            Block b = ledger.addBlock(id, liters, ledger.getQuota(id) - liters);
            replicator.broadcast(b);
            return b;
        }

        void close() throws Exception {
            mqtt.disconnect();
            handler.shutdown();
        }
    }

    final List<Node> nodes = new ArrayList<>();

    Node start(String id) throws Exception {
        Node n = new Node(id);
        nodes.add(n);
        Thread.sleep(300);   // abonnement
        return n;
    }

    @AfterEach
    void stop() throws Exception {
        for (Node n : nodes) n.close();
        Thread.sleep(300);
    }

    static void waitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < end) Thread.sleep(50);
    }

    static boolean sameChain(Node x, Node y) {
        List<Block> a = x.ledger.getChain(), b = y.ledger.getChain();
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).getHash().equals(b.get(i).getHash())) return false;
        }
        return true;
    }

    @Test
    void blocksReplicateToIdenticalCopiesThroughTheBroker() throws Exception {
        Node a = start("A"), b = start("B"), c = start("C");

        a.irrigate(40);
        waitUntil(() -> b.ledger.getChain().size() == 2 && c.ledger.getChain().size() == 2, 3000);
        b.irrigate(50);
        waitUntil(() -> a.ledger.getChain().size() == 3 && c.ledger.getChain().size() == 3, 3000);
        c.irrigate(30);
        waitUntil(() -> a.ledger.getChain().size() == 4 && b.ledger.getChain().size() == 4, 3000);

        assertTrue(sameChain(a, b) && sameChain(a, c), "les 3 copies doivent être identiques");
        assertTrue(a.ledger.validate() && b.ledger.validate() && c.ledger.validate());
        for (Node n : nodes) assertTrue(n.replicator.getDivergences().isEmpty(), "divergence chez " + n.id);
    }

    @Test
    void lateNodeCatchesUpThroughTheBroker() throws Exception {
        Node a = start("A"), b = start("B");
        a.irrigate(40);
        waitUntil(() -> b.ledger.getChain().size() == 2, 3000);
        b.irrigate(50);
        waitUntil(() -> a.ledger.getChain().size() == 3, 3000);

        Node c = start("C");                 // démarre en retard : a raté 2 blocs
        c.replicator.requestSync();
        waitUntil(() -> c.ledger.getChain().size() == 3, 4000);

        assertTrue(sameChain(a, c), "C a rattrapé A et B");
        assertTrue(c.replicator.getDivergences().isEmpty());
    }
}
