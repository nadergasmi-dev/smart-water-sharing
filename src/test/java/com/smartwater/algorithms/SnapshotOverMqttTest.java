package com.smartwater.algorithms;

import com.smartwater.Config;
import com.smartwater.blockchain.Blockchain;
import com.smartwater.communication.Message;
import com.smartwater.communication.MessageHandler;
import com.smartwater.communication.MessageType;
import com.smartwater.communication.NodeMqttClient;
import com.smartwater.node.LocalState;
import com.smartwater.security.KeyGenerator;
import com.smartwater.snapshot.GlobalSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.security.PublicKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Snapshot de bout en bout à travers le broker local (tcp://localhost:1883, comme ThreeNodesTest) :
 * vrai NodeMqttClient, vrai MessageHandler (snapshot avant dispatch, startSnapshot sur son thread).
 */
class SnapshotOverMqttTest {

    record Node(String id, NodeMqttClient mqtt, MessageHandler handler, ChandyLamport cl) { }

    final List<Node> nodes = new ArrayList<>();

    @AfterEach
    void stop() throws Exception {
        for (Node n : nodes) {
            n.mqtt().disconnect();
            n.handler().shutdown();
        }
        Thread.sleep(300);
    }

    @Test
    void snapshotCompletesOnAllThreeNodesThroughTheBroker() throws Exception {
        Map<String, PublicKey> keys = new LinkedHashMap<>();
        for (String id : Config.NODE_IDS) keys.put(id, KeyGenerator.generateKeyPair().getPublic());

        for (String id : Config.NODE_IDS) {
            LamportClock clock = new LamportClock();
            NodeMqttClient mqtt = new NodeMqttClient(id, clock);
            MessageHandler handler = new MessageHandler(id, clock);
            Blockchain ledger = new Blockchain(null, keys, Config.initialQuotas(), Config.RESERVOIR_INITIAL);
            int quota = 200 - 10 * nodes.size();
            ChandyLamport cl = new ChandyLamport(id, () -> new LocalState(id, 40.0, quota, "RELEASED", List.of()),
                    ledger, mqtt, handler::execute);
            handler.setSnapshotService(cl);
            handler.register(m -> { }, MessageType.REQUEST);   // un module applicatif quelconque
            mqtt.setIncomingHandler(handler);
            mqtt.connectWithRetry(3, 1000);
            nodes.add(new Node(id, mqtt, handler, cl));
        }
        Thread.sleep(500);   // abonnements

        // un peu de trafic applicatif, puis le snapshot
        nodes.get(1).mqtt().publish(new Message(MessageType.REQUEST, Message.ALL, null));
        nodes.get(0).cl().startSnapshot();

        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end && !nodes.stream().allMatch(n -> n.cl().isComplete())) {
            Thread.sleep(50);
        }

        for (Node n : nodes) {
            assertTrue(n.cl().isComplete(), "état global complet chez " + n.id());
            GlobalSnapshot g = n.cl().getResult();
            assertEquals("A-1", g.snapshotId());
            assertEquals(3, g.nodes().size());
            assertEquals(200, g.nodes().get("A").state().quotaRemaining());
            assertEquals(190, g.nodes().get("B").state().quotaRemaining());
            assertEquals(180, g.nodes().get("C").state().quotaRemaining());
            assertTrue(g.sameLedgerEverywhere());
        }
    }
}
