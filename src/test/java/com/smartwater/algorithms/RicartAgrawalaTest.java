package com.smartwater.algorithms;

import com.smartwater.communication.Message;
import com.smartwater.communication.MessageSender;
import com.smartwater.communication.MessageType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests de Ricart-Agrawala (Binôme 2, Partie 3), sans broker.
 *
 * <ul>
 *   <li>Règles de décision, testées message par message avec un émetteur qui enregistre ce qui est envoyé.</li>
 *   <li>Exclusion mutuelle et ordre d'accès, testés sur un petit réseau simulé en mémoire : 3 nœuds, un thread
 *       de traitement par nœud (comme {@code MessageHandler}), des délais aléatoires par canal (FIFO par canal).</li>
 * </ul>
 *
 * @author Houssine Ferjani (Binôme 2)
 */
class RicartAgrawalaTest {

    static final List<String> NODES = List.of("A", "B", "C");

    // =====================================================================================
    // Outils : émetteur enregistreur et messages reçus
    // =====================================================================================

    /** Émetteur factice : fait tick() comme NodeMqttClient et garde les messages envoyés. */
    static class RecordingSender implements MessageSender {
        final String nodeId;
        final LamportClock clock;
        final List<Message> sent = Collections.synchronizedList(new ArrayList<>());

        RecordingSender(String nodeId, LamportClock clock) {
            this.nodeId = nodeId;
            this.clock = clock;
        }

        @Override
        public synchronized int publish(Message m) {
            int ts = clock.tick();
            m.setLamport(ts);
            m.setFrom(nodeId);
            m.setMsgId(UUID.randomUUID().toString());
            sent.add(m);
            return ts;
        }

        List<String> summary() {
            synchronized (sent) {
                return sent.stream().map(m -> m.getType() + "->" + m.getTo()).toList();
            }
        }
    }

    /** Message reçu, tel que le MessageHandler le transmet (champs remplis par l'expéditeur). */
    static Message incoming(MessageType type, String from, String to, int lamport) {
        Message m = new Message(type, to, null);
        m.setFrom(from);
        m.setLamport(lamport);
        m.setMsgId(UUID.randomUUID().toString());
        return m;
    }

    static void waitUntil(BooleanSupplier condition, String what) {
        long end = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > end) fail("délai dépassé : " + what);
            sleep(2);
        }
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** Lance requestPump() dans un thread ; renvoie le thread. */
    static Thread requestInBackground(RicartAgrawala ra, AtomicReference<Throwable> error) {
        Thread t = new Thread(() -> {
            try {
                ra.requestPump();
            } catch (Throwable e) {
                error.set(e);
            }
        });
        t.setDaemon(true);   // certains tests laissent volontairement une demande en attente
        t.start();
        return t;
    }

    // =====================================================================================
    // Règles de décision (un seul nœud, B, et des messages fabriqués)
    // =====================================================================================

    @Test
    void releasedRepondToutDeSuite() {
        LamportClock clock = new LamportClock();
        RecordingSender s = new RecordingSender("B", clock);
        RicartAgrawala ra = new RicartAgrawala("B", clock, s, NODES);

        ra.onMessage(incoming(MessageType.REQUEST, "A", "ALL", 3));

        assertEquals(List.of("REPLY->A"), s.summary());
        assertEquals(RicartAgrawala.State.RELEASED, ra.getState());
        assertTrue(ra.getDeferredReplies().isEmpty());
    }

    @Test
    void requestPumpDiffuseUnRequestEtAttendUnReplyDeChaqueAutreNoeud() throws Exception {
        LamportClock clock = new LamportClock();
        RecordingSender s = new RecordingSender("B", clock);
        RicartAgrawala ra = new RicartAgrawala("B", clock, s, NODES);
        AtomicReference<Throwable> error = new AtomicReference<>();

        Thread t = requestInBackground(ra, error);
        waitUntil(() -> ra.getState() == RicartAgrawala.State.WANTED, "état WANTED");
        assertEquals(List.of("REQUEST->ALL"), s.summary());
        assertEquals(1, ra.getRequestTimestamp(), "l'estampille de la demande est celle du REQUEST");

        ra.onMessage(incoming(MessageType.REPLY, "A", "B", 2));
        sleep(50);
        assertTrue(t.isAlive(), "un seul REPLY sur deux : pas encore d'entrée");
        assertEquals(List.of("C"), ra.getAwaitingReplies());

        ra.onMessage(incoming(MessageType.REPLY, "C", "B", 2));
        t.join(2000);
        assertFalse(t.isAlive());
        assertNull(error.get());
        assertEquals(RicartAgrawala.State.HELD, ra.getState());
        assertEquals(1, ra.getEntries());
    }

    @Test
    void heldDiffereLesDemandesPuisRelacheDansLOrdreDuContrat() throws Exception {
        LamportClock clock = new LamportClock();
        RecordingSender s = new RecordingSender("B", clock);
        RicartAgrawala ra = new RicartAgrawala("B", clock, s, NODES);
        Thread t = requestInBackground(ra, new AtomicReference<>());
        waitUntil(() -> ra.getState() == RicartAgrawala.State.WANTED, "WANTED");
        ra.onMessage(incoming(MessageType.REPLY, "A", "B", 2));
        ra.onMessage(incoming(MessageType.REPLY, "C", "B", 2));
        t.join(2000);
        assertEquals(RicartAgrawala.State.HELD, ra.getState());

        // en HELD : même une demande d'estampille plus petite est différée
        ra.onMessage(incoming(MessageType.REQUEST, "C", "ALL", 1));
        ra.onMessage(incoming(MessageType.REQUEST, "A", "ALL", 9));
        assertEquals(List.of("C", "A"), ra.getDeferredReplies());
        assertEquals(List.of("REQUEST->ALL"), s.summary(), "aucune réponse tant que la pompe est tenue");

        ra.releasePump();
        // cours : pas de RELEASE, seulement les REPLY différés (dans l'ordre d'arrivée) → 2(N − 1) messages
        assertEquals(List.of("REQUEST->ALL", "REPLY->C", "REPLY->A"), s.summary());
        assertEquals(RicartAgrawala.State.RELEASED, ra.getState());
        assertTrue(ra.getDeferredReplies().isEmpty());
        assertEquals(-1, ra.getRequestTimestamp());
    }

    /** En WANTED, B a demandé avec l'estampille 4 : il compare (4, B) à la demande reçue. */
    @Test
    void wantedComparePrioritesAvecLOrdreLamportPuisIdentifiant() throws Exception {
        LamportClock clock = new LamportClock();
        for (int i = 0; i < 3; i++) clock.tick();                // la demande de B aura l'estampille 4
        RecordingSender s = new RecordingSender("B", clock);
        RicartAgrawala ra = new RicartAgrawala("B", clock, s, NODES);
        requestInBackground(ra, new AtomicReference<>());
        waitUntil(() -> ra.getState() == RicartAgrawala.State.WANTED, "WANTED");
        assertEquals(4, ra.getRequestTimestamp());

        ra.onMessage(incoming(MessageType.REQUEST, "C", "ALL", 4));   // (4,B) < (4,C) : différé
        ra.onMessage(incoming(MessageType.REQUEST, "A", "ALL", 5));   // (4,B) < (5,A) : différé
        assertEquals(List.of("C", "A"), ra.getDeferredReplies());
        assertEquals(List.of("REQUEST->ALL"), s.summary());

        LamportClock clock2 = new LamportClock();
        for (int i = 0; i < 3; i++) clock2.tick();
        RecordingSender s2 = new RecordingSender("B", clock2);
        RicartAgrawala ra2 = new RicartAgrawala("B", clock2, s2, NODES);
        requestInBackground(ra2, new AtomicReference<>());
        waitUntil(() -> ra2.getState() == RicartAgrawala.State.WANTED, "WANTED");

        ra2.onMessage(incoming(MessageType.REQUEST, "A", "ALL", 4));  // (4,A) < (4,B) : égalité, A gagne
        ra2.onMessage(incoming(MessageType.REQUEST, "C", "ALL", 3));  // (3,C) < (4,B) : plus ancienne
        assertEquals(List.of("REQUEST->ALL", "REPLY->A", "REPLY->C"), s2.summary());
        assertTrue(ra2.getDeferredReplies().isEmpty());
    }

    @Test
    void pasDeReentranceNiDeLiberationSansLaPompe() {
        LamportClock clock = new LamportClock();
        RicartAgrawala ra = new RicartAgrawala("A", clock, new RecordingSender("A", clock), NODES);
        assertThrows(IllegalStateException.class, ra::releasePump, "libérer sans tenir la pompe");

        requestInBackground(ra, new AtomicReference<>());
        waitUntil(() -> ra.getState() == RicartAgrawala.State.WANTED, "WANTED");
        assertThrows(IllegalStateException.class, ra::requestPump, "deuxième demande pendant la première");
    }

    @Test
    void messagesInattendusOuInconnusIgnores() {
        LamportClock clock = new LamportClock();
        RecordingSender s = new RecordingSender("A", clock);
        RicartAgrawala ra = new RicartAgrawala("A", clock, s, NODES);

        ra.onMessage(incoming(MessageType.REPLY, "B", "A", 2));       // REPLY sans demande en cours
        ra.onMessage(incoming(MessageType.REQUEST, "Z", "ALL", 1));   // nœud inconnu
        ra.onMessage(incoming(MessageType.PUMP_RELEASED, "C", "ALL", 3));   // supervision : pas pour RA

        assertTrue(s.summary().isEmpty());
        assertEquals(RicartAgrawala.State.RELEASED, ra.getState());
    }

    /**
     * Une demande interrompue est abandonnée proprement. Le REPLY qui lui était destiné (C n'avait pas encore
     * répondu) arrive plus tard : il ne doit PAS compter pour la demande suivante.
     */
    @Test
    void demandeInterrompueAbandonneeEtSonReplyTardifIgnore() throws Exception {
        LamportClock clock = new LamportClock();
        RecordingSender s = new RecordingSender("B", clock);
        RicartAgrawala ra = new RicartAgrawala("B", clock, s, NODES);

        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread t1 = requestInBackground(ra, error);
        waitUntil(() -> ra.getState() == RicartAgrawala.State.WANTED, "WANTED");
        ra.onMessage(incoming(MessageType.REPLY, "A", "B", 2));
        ra.onMessage(incoming(MessageType.REQUEST, "C", "ALL", 9));   // moins prioritaire : différé
        assertEquals(List.of("C"), ra.getDeferredReplies());

        t1.interrupt();
        t1.join(2000);
        assertInstanceOf(IllegalStateException.class, error.get());
        assertEquals(RicartAgrawala.State.RELEASED, ra.getState());
        assertTrue(s.summary().contains("REPLY->C"), "le REPLY différé est envoyé à l'abandon");

        Thread t2 = requestInBackground(ra, new AtomicReference<>());
        waitUntil(() -> ra.getState() == RicartAgrawala.State.WANTED, "nouvelle demande");
        ra.onMessage(incoming(MessageType.REPLY, "C", "B", 10));      // REPLY tardif de l'ancienne demande
        sleep(30);
        assertEquals(List.of("A", "C"), ra.getAwaitingReplies(), "le REPLY tardif ne compte pas");
        ra.onMessage(incoming(MessageType.REPLY, "A", "B", 12));
        ra.onMessage(incoming(MessageType.REPLY, "C", "B", 12));
        t2.join(2000);
        assertEquals(RicartAgrawala.State.HELD, ra.getState());
    }

    // =====================================================================================
    // Réseau simulé en mémoire : 3 nœuds réels qui échangent des messages
    // =====================================================================================

    /** 3 nœuds, un thread de traitement par nœud, un canal FIFO (avec délai) par couple expéditeur → destinataire. */
    static class Network implements AutoCloseable {
        final Map<String, LamportClock> clocks = new LinkedHashMap<>();
        final Map<String, RicartAgrawala> ras = new LinkedHashMap<>();
        final Map<String, ExecutorService> handlers = new LinkedHashMap<>();
        final Map<String, ExecutorService> channels = new LinkedHashMap<>();
        final Random random = new Random(42);
        final int maxDelayMs;

        Network(int maxDelayMs) {
            this.maxDelayMs = maxDelayMs;
            for (String id : NODES) {
                clocks.put(id, new LamportClock());
                handlers.put(id, Executors.newSingleThreadExecutor());
                for (String to : NODES) {
                    if (!to.equals(id)) channels.put(id + "->" + to, Executors.newSingleThreadExecutor());
                }
            }
            for (String id : NODES) {
                ras.put(id, new RicartAgrawala(id, clocks.get(id), senderFor(id), NODES));
            }
        }

        MessageSender senderFor(String id) {
            return new MessageSender() {
                @Override
                public synchronized int publish(Message m) {
                    int ts = clocks.get(id).tick();
                    m.setLamport(ts);
                    m.setFrom(id);
                    m.setMsgId(UUID.randomUUID().toString());
                    for (String to : NODES) {
                        if (to.equals(id) || !(m.isBroadcast() || to.equals(m.getTo()))) continue;
                        int delay;
                        synchronized (random) { delay = maxDelayMs == 0 ? 0 : random.nextInt(maxDelayMs + 1); }
                        channels.get(id + "->" + to).execute(() -> {
                            sleep(delay);   // délai du canal ; l'ordre d'un même canal est conservé
                            handlers.get(to).execute(() -> {
                                clocks.get(to).update(m.getLamport());   // comme MessageHandler
                                ras.get(to).onMessage(m);
                            });
                        });
                    }
                    return ts;
                }
            };
        }

        @Override
        public void close() {
            channels.values().forEach(ExecutorService::shutdownNow);
            handlers.values().forEach(ExecutorService::shutdownNow);
        }
    }

    Network net;

    @AfterEach
    void closeNetwork() {
        if (net != null) net.close();
    }

    /** Sûreté et vivacité : 3 nœuds × 30 demandes en concurrence, jamais deux nœuds dans la section critique. */
    @Test
    void exclusionMutuelleSousForteConcurrence() throws Exception {
        net = new Network(3);
        int rounds = 30;
        AtomicInteger inCs = new AtomicInteger();
        AtomicInteger maxInCs = new AtomicInteger();
        AtomicInteger total = new AtomicInteger();
        AtomicReference<Throwable> error = new AtomicReference<>();

        List<Thread> farmers = new ArrayList<>();
        for (String id : NODES) {
            RicartAgrawala ra = net.ras.get(id);
            Random r = new Random(id.hashCode());
            farmers.add(new Thread(() -> {
                try {
                    for (int i = 0; i < rounds; i++) {
                        ra.requestPump();
                        int now = inCs.incrementAndGet();
                        maxInCs.accumulateAndGet(now, Math::max);
                        total.incrementAndGet();
                        sleep(r.nextInt(3));
                        inCs.decrementAndGet();
                        ra.releasePump();
                        sleep(r.nextInt(3));
                    }
                } catch (Throwable e) {
                    error.set(e);
                }
            }));
        }
        farmers.forEach(Thread::start);
        for (Thread t : farmers) {
            t.join(30_000);
            assertFalse(t.isAlive(), "blocage : un agriculteur n'a jamais obtenu la pompe");
        }
        assertNull(error.get());
        assertEquals(1, maxInCs.get(), "deux nœuds dans la section critique en même temps");
        assertEquals(3 * rounds, total.get());
        for (RicartAgrawala ra : net.ras.values()) {
            assertEquals(rounds, ra.getEntries());
            waitUntil(() -> ra.getDeferredReplies().isEmpty(), "plus aucune réponse différée chez " + ra.getNodeId());
            assertEquals(RicartAgrawala.State.RELEASED, ra.getState());
        }
    }

    /**
     * Ordre d'accès : A tient la pompe ; B demande, puis C demande APRÈS avoir reçu la demande de B (donc avec
     * une estampille plus grande). À la libération par A, B doit passer avant C.
     */
    @Test
    void lOrdreDAccesRespecteLaPrioriteLamport() throws Exception {
        net = new Network(0);
        RicartAgrawala a = net.ras.get("A"), b = net.ras.get("B"), c = net.ras.get("C");
        List<String> order = Collections.synchronizedList(new ArrayList<>());

        a.requestPump();
        assertEquals(RicartAgrawala.State.HELD, a.getState());

        Thread tb = new Thread(() -> { b.requestPump(); order.add("B"); sleep(20); b.releasePump(); });
        tb.start();
        waitUntil(() -> a.getDeferredReplies().contains("B") && b.getAwaitingReplies().equals(List.of("A")),
                "A diffère B, C a répondu à B");

        Thread tc = new Thread(() -> { c.requestPump(); order.add("C"); c.releasePump(); });
        tc.start();
        waitUntil(() -> a.getDeferredReplies().equals(List.of("B", "C")) && b.getDeferredReplies().contains("C"),
                "A diffère B et C, B (prioritaire) diffère C");
        assertTrue(LamportClock.compare(b.getRequestTimestamp(), "B", c.getRequestTimestamp(), "C") < 0);

        a.releasePump();
        tb.join(5000);
        tc.join(5000);
        assertEquals(List.of("B", "C"), order);
    }
}
