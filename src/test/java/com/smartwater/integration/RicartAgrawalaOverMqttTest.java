package com.smartwater.integration;

import com.smartwater.algorithms.RicartAgrawala;
import com.smartwater.blockchain.Block;
import com.smartwater.irrigation.PumpAccess;
import com.smartwater.node.LocalState;
import com.smartwater.security.KeyGenerator;
import com.smartwater.snapshot.GlobalSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ricart-Agrawala à travers le vrai broker (Mosquitto local), branché dans la séquence d'irrigation de
 * Binôme 1 ({@link IrrigationSequence}) : c'est le scénario de {@code IrrigationOverMqttTest}, mais avec la
 * <b>vraie</b> pompe distribuée au lieu du verrou partagé.
 *
 * <p>Différence importante avec la pompe simulée : ici, la libération n'attend pas que les autres nœuds aient
 * reçu le bloc. C'est l'ordre FIFO du topic {@code water/from/{id}} (le {@code LEDGER_UPDATE} part avant le
 * {@code REPLY}) qui garantit que le nœud suivant a toujours le registre à jour. Le test le vérifie : aucune
 * divergence, aucun bloc refusé.</p>
 *
 * @author Houssine Ferjani (Binôme 2)
 */
class RicartAgrawalaOverMqttTest {

    @TempDir
    Path keysDir;

    final Map<String, NodeRuntime> nodes = new LinkedHashMap<>();
    final Map<String, RicartAgrawala> pumps = new LinkedHashMap<>();

    /** Nombre de nœuds dans la section critique à cet instant, et maximum observé. */
    final AtomicInteger inCs = new AtomicInteger();
    final AtomicInteger maxInCs = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        KeyGenerator.generateAll(keysDir, false);
        for (String id : List.of("A", "B", "C")) {
            AtomicReference<RicartAgrawala> ref = new AtomicReference<>();
            // État local lu par Chandy-Lamport : l'état réel de Ricart-Agrawala
            NodeRuntime rt = new NodeRuntime(id, () -> {
                RicartAgrawala.View v = ref.get().view();   // lecture atomique
                return new LocalState(id, 30.0, 200, v.state().name(), v.deferredReplies());
            }, keysDir);
            RicartAgrawala ra = new RicartAgrawala(id, rt.clock(), rt.sender());
            ref.set(ra);
            rt.handler().register(ra, RicartAgrawala.TYPES);
            nodes.put(id, rt);
            pumps.put(id, ra);
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

    /** La vraie pompe, entourée d'un compteur qui détecte deux nœuds dans la section critique. */
    PumpAccess watched(RicartAgrawala ra) {
        return new PumpAccess() {
            @Override public void requestPump() {
                ra.requestPump();
                maxInCs.accumulateAndGet(inCs.incrementAndGet(), Math::max);
            }
            @Override public void releasePump() {
                inCs.decrementAndGet();
                ra.releasePump();
            }
        };
    }

    /** Chaque nœud irrigue {@code rounds} fois, les trois en même temps ; renvoie tous les résultats. */
    Map<String, List<IrrigationSequence.Result>> irrigateTogether(int rounds, int liters) throws Exception {
        Map<String, List<IrrigationSequence.Result>> results = new ConcurrentHashMap<>();
        List<Thread> threads = new ArrayList<>();
        for (String id : nodes.keySet()) {
            IrrigationSequence seq = nodes.get(id).irrigationSequence(watched(pumps.get(id)));
            List<IrrigationSequence.Result> mine = new ArrayList<>();
            results.put(id, mine);
            threads.add(new Thread(() -> {
                for (int i = 0; i < rounds; i++) {
                    mine.add(seq.irrigate((quota, reservoir) -> Math.min(liters, Math.min(quota, reservoir)), b -> { }));
                }
            }));
        }
        threads.forEach(Thread::start);
        for (Thread t : threads) {
            t.join(30_000);
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
            assertTrue(n.ledger().getRejected().isEmpty(), "bloc refusé chez " + n.nodeId());
        }
    }

    @Test
    void troisNoeudsEnConcurrencePompeExclusiveEtRegistresIdentiques() throws Exception {
        Map<String, List<IrrigationSequence.Result>> results = irrigateTogether(3, 10);

        results.values().forEach(list -> list.forEach(
                r -> assertEquals(IrrigationSequence.Outcome.IRRIGATED, r.outcome(), "résultat " + r)));
        assertEquals(1, maxInCs.get(), "deux nœuds ont tenu la pompe en même temps");
        assertSameChainEverywhere(1 + 9);   // genesis + 3 nœuds × 3 irrigations
        for (NodeRuntime n : nodes.values()) {
            for (String id : nodes.keySet()) assertEquals(170, n.ledger().getQuota(id));
            assertEquals(910, n.ledger().getReservoirRemaining());
        }
        for (RicartAgrawala ra : pumps.values()) {
            assertEquals(3, ra.getEntries());
            assertEquals(RicartAgrawala.State.RELEASED, ra.getState());
            assertTrue(ra.getDeferredReplies().isEmpty());
        }
    }

    /**
     * Snapshots Chandy-Lamport déclenchés en boucle PENDANT les irrigations : vérifie qu'il n'y a pas
     * d'interblocage entre le verrou du client MQTT (pris par Chandy-Lamport) et celui de Ricart-Agrawala,
     * et que l'état de la pompe enregistré est cohérent.
     */
    @Test
    void snapshotsPendantLesIrrigationsSansInterblocage() throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread trigger = new Thread(() -> {
            try {
                for (int i = 0; i < 40; i++) {
                    nodes.get("A").snapshot().startSnapshot();   // ignoré si un snapshot est déjà en cours
                    sleep(25);
                }
            } catch (Throwable e) {
                error.set(e);
            }
        });
        trigger.start();
        irrigateTogether(3, 10);
        trigger.join(10_000);
        assertNull(error.get());
        assertEquals(1, maxInCs.get());
        assertSameChainEverywhere(1 + 9);

        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end && !nodes.get("A").snapshot().isComplete()) sleep(50);
        GlobalSnapshot snap = nodes.get("A").snapshot().getResult();
        assertNotNull(snap);
        assertTrue(snap.isComplete(), "au moins un snapshot complet");
        long held = snap.nodes().values().stream().filter(s -> "HELD".equals(s.state().pumpState())).count();
        assertTrue(held <= 1, "le snapshot montre deux nœuds en HELD : état incohérent");
        snap.nodes().values().forEach(s ->
                assertTrue(Set.of("RELEASED", "WANTED", "HELD").contains(s.state().pumpState())));
    }
}
