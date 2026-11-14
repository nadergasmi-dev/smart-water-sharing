package com.smartwater.integration;

import com.smartwater.blockchain.Block;
import com.smartwater.irrigation.PumpAccess;
import com.smartwater.node.LocalState;
import com.smartwater.security.KeyGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Scénario d'intégration à 3 nœuds, à travers le broker local (comme ThreeNodesTest) : 3 demandes quasi
 * simultanées, registre répliqué, quotas corrects, un bloc refusé qui ne bloque pas la pompe.
 *
 * <b>Pompe simulée</b> en attendant Ricart-Agrawala (Binôme 2) : un verrou partagé dans la JVM. À la libération,
 * elle attend que tous les nœuds aient reçu le dernier bloc, ce que Ricart-Agrawala garantit dans le vrai
 * système (le bloc part sur le même topic, AVANT le REPLY qui autorise le nœud suivant).
 */
class IrrigationOverMqttTest {

    @TempDir
    Path keysDir;

    final Map<String, NodeRuntime> nodes = new LinkedHashMap<>();
    final ReentrantLock pumpLock = new ReentrantLock(true);

    /** Pompe simulée (remplacée par RicartAgrawala dans le vrai nœud). */
    PumpAccess pumpFor(String id) {
        return new PumpAccess() {
            @Override public void requestPump() { pumpLock.lock(); }
            @Override public void releasePump() {
                int size = nodes.get(id).ledger().getChain().size();
                long end = System.currentTimeMillis() + 3000;
                while (System.currentTimeMillis() < end
                        && nodes.values().stream().anyMatch(n -> n.ledger().getChain().size() < size)) {
                    sleep(20);
                }
                pumpLock.unlock();
            }
        };
    }

    @BeforeEach
    void start() throws Exception {
        KeyGenerator.generateAll(keysDir, false);
        for (String id : List.of("A", "B", "C")) {
            NodeRuntime rt = new NodeRuntime(id, () -> new LocalState(id, 30.0, 200, "RELEASED", List.of()), keysDir);
            nodes.put(id, rt);
        }
        for (NodeRuntime rt : nodes.values()) rt.start();
        sleep(500);   // abonnements
    }

    @AfterEach
    void stop() {
        nodes.values().forEach(NodeRuntime::stop);
        sleep(300);
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** Lance une irrigation par nœud, en même temps, et attend la fin de toutes. */
    Map<String, IrrigationSequence.Result> irrigateTogether(Map<String, Integer> wanted) throws Exception {
        Map<String, IrrigationSequence.Result> results = new ConcurrentHashMap<>();
        List<Thread> threads = new ArrayList<>();
        for (Map.Entry<String, Integer> e : wanted.entrySet()) {
            String id = e.getKey();
            int need = e.getValue();
            Thread t = new Thread(() -> results.put(id, nodes.get(id).irrigationSequence(pumpFor(id))
                    .irrigate((quota, reservoir) -> need, b -> { })));
            threads.add(t);
        }
        threads.forEach(Thread::start);
        for (Thread t : threads) {
            t.join(15000);
            assertFalse(t.isAlive(), "blocage : une irrigation ne s'est pas terminée");
        }
        return results;
    }

    void assertSameChainEverywhere(int expectedSize) {
        long end = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < end
                && nodes.values().stream().anyMatch(n -> n.ledger().getChain().size() != expectedSize)) {
            sleep(20);
        }
        List<Block> ref = nodes.get("A").ledger().getChain();
        assertEquals(expectedSize, ref.size());
        for (NodeRuntime n : nodes.values()) {
            List<Block> chain = n.ledger().getChain();
            assertEquals(ref.size(), chain.size(), "longueur chez " + n.nodeId());
            for (int i = 0; i < chain.size(); i++) {
                assertEquals(ref.get(i).getHash(), chain.get(i).getHash(), "bloc " + i + " chez " + n.nodeId());
            }
            assertTrue(n.ledger().validate());
            assertTrue(n.replicator().getDivergences().isEmpty(), "divergence chez " + n.nodeId());
        }
    }

    @Test
    void threeSimultaneousRequestsGiveThreeIdenticalLedgersAndCorrectQuotas() throws Exception {
        Map<String, IrrigationSequence.Result> results = irrigateTogether(Map.of("A", 40, "B", 50, "C", 30));

        results.values().forEach(r -> assertEquals(IrrigationSequence.Outcome.IRRIGATED, r.outcome()));
        assertSameChainEverywhere(4);
        for (NodeRuntime n : nodes.values()) {
            assertEquals(160, n.ledger().getQuota("A"));
            assertEquals(150, n.ledger().getQuota("B"));
            assertEquals(170, n.ledger().getQuota("C"));
            assertEquals(880, n.ledger().getReservoirRemaining());
        }
    }

    @Test
    void aRefusedBlockDoesNotBlockThePump() throws Exception {
        // C demande 500 L alors que son quota est de 200 L : refusé (V6), pompe quand même libérée
        Map<String, IrrigationSequence.Result> results = irrigateTogether(Map.of("A", 40, "C", 500, "B", 50));

        assertEquals(IrrigationSequence.Outcome.REFUSED, results.get("C").outcome());
        assertTrue(results.get("C").reason().startsWith("V6 : "));
        assertEquals(IrrigationSequence.Outcome.IRRIGATED, results.get("A").outcome());
        assertEquals(IrrigationSequence.Outcome.IRRIGATED, results.get("B").outcome());
        assertSameChainEverywhere(3);
        assertEquals(200, nodes.get("A").ledger().getQuota("C"), "le refus n'a débité personne");
    }

    @Test
    void snapshotTakenAfterTheIrrigationsSeesTheSameLedgerEverywhere() throws Exception {
        irrigateTogether(Map.of("A", 40, "B", 50));
        assertSameChainEverywhere(3);

        nodes.get("B").snapshot().startSnapshot();
        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end && !nodes.values().stream().allMatch(n -> n.snapshot().isComplete())) {
            sleep(50);
        }
        for (NodeRuntime n : nodes.values()) {
            assertTrue(n.snapshot().isComplete(), "snapshot complet chez " + n.nodeId());
            assertTrue(n.snapshot().getResult().sameLedgerEverywhere());
            assertEquals(3, n.snapshot().getResult().nodes().get("A").ledgerLength());
        }
    }
}
