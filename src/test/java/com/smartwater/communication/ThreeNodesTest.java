/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.smartwater.communication;

/**
 *
 * @author nader
 */
import com.smartwater.algorithms.LamportClock;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class ThreeNodesTest {

    static class TestNode {
        final String id;
        final LamportClock clock = new LamportClock();
        final NodeMqttClient mqtt;
        final MessageHandler handler;
        final List<Message> received = new CopyOnWriteArrayList<>();

        TestNode(String id) throws Exception {
            this.id = id;
            this.mqtt = new NodeMqttClient(id, clock);
            this.handler = new MessageHandler(id, clock);
            handler.register(received::add, MessageType.values());
            mqtt.setIncomingHandler(handler);
            mqtt.connectWithRetry(3, 1000);
        }

        long count(MessageType t) {
            return received.stream().filter(m -> m.getType() == t).count();
        }

        void close() throws Exception {
            mqtt.disconnect();
            handler.shutdown();
        }
    }

    static TestNode a, b, c;

    @BeforeAll
    static void setUp() throws Exception {
        a = new TestNode("A");
        b = new TestNode("B");
        c = new TestNode("C");
        Thread.sleep(500); // abonnements
    }

    @AfterAll
    static void tearDown() throws Exception {
        a.close(); b.close(); c.close();
    }

    @BeforeEach
    void clear() throws Exception {
        Thread.sleep(300);
        a.received.clear(); b.received.clear(); c.received.clear();
    }

    @Test
    void broadcastReachesOthersButNotSender() throws Exception {
        a.mqtt.publish(new Message(MessageType.REQUEST, Message.ALL, null));
        Thread.sleep(1000);

        assertEquals(1, b.count(MessageType.REQUEST));
        assertEquals(1, c.count(MessageType.REQUEST));
        assertEquals(0, a.count(MessageType.REQUEST));
    }

    @Test
    void targetedReplyReachesOnlyItsRecipient() throws Exception {
        b.mqtt.publish(new Message(MessageType.REPLY, "A", null));
        Thread.sleep(1000);

        assertEquals(1, a.count(MessageType.REPLY));
        assertEquals(0, c.count(MessageType.REPLY));
        assertEquals(0, b.count(MessageType.REPLY));
    }

        @Test
    void replyFromInsideOnMessageDoesNotDeadlock() throws Exception {
        // B et C répondent automatiquement à tout REQUEST (comme Ricart-Agrawala)
        for (TestNode n : new TestNode[] { b, c }) {
            n.handler.register(m -> {
                n.received.add(m);
                n.mqtt.publish(new Message(MessageType.REPLY, m.getFrom(), null));
            }, MessageType.REQUEST);
        }

        try {
            a.mqtt.publish(new Message(MessageType.REQUEST, Message.ALL, null));
            Thread.sleep(2000);

            assertEquals(2, a.count(MessageType.REPLY)); // un REPLY de B, un de C
        } finally {
            // on restaure le comportement normal pour ne pas polluer les autres tests
            for (TestNode n : new TestNode[] { b, c }) {
                n.handler.register(n.received::add, MessageType.REQUEST);
            }
        }
    }

    @Test
    void lamportClocksStayConsistent() throws Exception {
        int ts = a.mqtt.publish(new Message(MessageType.RELEASE, Message.ALL, null));
        Thread.sleep(1000);

        assertTrue(b.clock.get() > ts);
        assertTrue(c.clock.get() > ts);
    }
}