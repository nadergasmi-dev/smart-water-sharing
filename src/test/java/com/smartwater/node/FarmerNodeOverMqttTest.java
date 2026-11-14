package com.smartwater.node;

import com.smartwater.Config;
import com.smartwater.algorithms.RicartAgrawala;
import com.smartwater.blockchain.Block;
import com.smartwater.blockchain.Blockchain;
import com.smartwater.security.KeyGenerator;
import com.smartwater.snapshot.GlobalSnapshot;
import com.smartwater.snapshot.LocalSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Les trois agriculteurs complets ({@link FarmerNode}) sur le vrai broker (Mosquitto local) :
 * déclenchement des demandes, épuisement du quota, puis scénario libre en boucle avec des snapshots.
 *
 * @author Houssine Ferjani (Binôme 2)
 */
class FarmerNodeOverMqttTest {

    @TempDir
    Path keysDir;

    final Map<String, FarmerNode> farmers = new LinkedHashMap<>();

    void startFarmers(Function<String, SoilSimulator> soil, Function<String, FarmerState> state) throws Exception {
        KeyGenerator.generateAll(keysDir, false);
        for (String id : Config.NODE_IDS) {
            farmers.put(id, new FarmerNode(id, keysDir, soil.apply(id), state.apply(id)));
        }
        for (FarmerNode f : farmers.values()) f.start();
        sleep(500);   // abonnements
    }

    @AfterEach
    void stop() {
        farmers.values().forEach(FarmerNode::stop);
        sleep(300);
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** Sol déterministe : baisse exacte de {@code rate} points par pas, 1 point par litre. */
    static SoilSimulator exact(double initial, double rate) {
        return new SoilSimulator(initial, rate, 0, 1.0, new Random(0));
    }

    Blockchain ledger(String id) {
        return farmers.get(id).runtime().ledger();
    }

    /** Attend que les 3 copies aient la même longueur, puis vérifie qu'elles sont identiques et saines. */
    List<Block> sameChainEverywhere() {
        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end && farmers.keySet().stream()
                .map(id -> ledger(id).getChain().size()).distinct().count() > 1) {
            sleep(20);
        }
        List<Block> ref = ledger("A").getChain();
        for (String id : farmers.keySet()) {
            List<Block> chain = ledger(id).getChain();
            assertEquals(ref.size(), chain.size(), "longueur chez " + id);
            for (int i = 0; i < chain.size(); i++) {
                assertEquals(ref.get(i).getHash(), chain.get(i).getHash(), "bloc " + i + " chez " + id);
            }
            assertTrue(ledger(id).validate());
            assertTrue(ledger(id).getRejected().isEmpty(), "bloc refusé chez " + id);
            assertTrue(farmers.get(id).runtime().replicator().getDivergences().isEmpty(), "divergence chez " + id);
        }
        return ref;
    }

    @Test
    void laDemandePartQuandLHumiditePasseSousLeSeuil() throws Exception {
        startFarmers(id -> exact(40, 5), FarmerState::new);       // seuil 30 %, cible 60 %
        FarmerNode a = farmers.get("A");

        FarmerNode.StepResult r1 = a.step();                       // 35 %
        FarmerNode.StepResult r2 = a.step();                       // 30 % : pas SOUS le seuil
        assertEquals(FarmerNode.Outcome.NO_NEED, r1.outcome());
        assertEquals(FarmerNode.Outcome.NO_NEED, r2.outcome());
        assertEquals(0, a.ra().getEntries(), "aucun REQUEST tant que le sol est assez humide");
        assertEquals(0, a.state().getRequests());

        FarmerNode.StepResult r3 = a.step();                       // 25 % → besoin 35 L
        assertEquals(FarmerNode.Outcome.IRRIGATED, r3.outcome());
        assertEquals(25.0, r3.humidity(), 1e-9);
        assertEquals(35, r3.need());
        assertEquals(35, r3.liters());
        assertEquals(1, a.ra().getEntries());
        assertEquals(60.0, a.soil().getHumidity(), 1e-9);
        assertEquals(165, a.state().getQuotaRemaining());
        assertEquals(35, a.pump().getOwnLiters());
        assertEquals(965, a.pump().getLevel());

        sameChainEverywhere();
        for (String id : farmers.keySet()) {
            assertEquals(165, ledger(id).getQuota("A"));
            assertEquals(965, ledger(id).getReservoirRemaining());
        }
        // B et C n'ont rien demandé, mais ont vu l'irrigation de A
        for (String id : List.of("B", "C")) {
            assertEquals(0, farmers.get(id).ra().getEntries());
            long end = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < end && farmers.get(id).pump().getLevel() != 965) sleep(20);
            assertEquals(965, farmers.get(id).pump().getLevel(), "IRRIGATION_EVENT non reçu par " + id);
            assertEquals("A", farmers.get(id).pump().getLastUser());
        }
    }

    @Test
    void quotaEpuiseLaDerniereAllocationEstLimiteePuisPlusAucuneDemande() throws Exception {
        // Sol qui sèche complètement à chaque pas : besoin de 60 L à chaque cycle
        startFarmers(id -> exact(0, 100),
                id -> new FarmerState(id, id.equals("B") ? 0 : Config.QUOTA_INITIAL, 30, 60));
        FarmerNode a = farmers.get("A");

        List<Integer> liters = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            FarmerNode.StepResult r = a.step();
            assertEquals(FarmerNode.Outcome.IRRIGATED, r.outcome());
            assertEquals(60, r.need());
            liters.add(r.liters());
        }
        assertEquals(List.of(60, 60, 60, 20), liters, "la 4e allocation est limitée par le quota restant");
        assertEquals(0, a.state().getQuotaRemaining());
        assertEquals(200, a.state().getLitersReceived());

        long entries = a.ra().getEntries();
        int length = ledger("A").getChain().size();
        FarmerNode.StepResult r = a.step();
        assertEquals(FarmerNode.Outcome.NO_QUOTA, r.outcome());
        assertEquals(entries, a.ra().getEntries(), "quota épuisé : pas de REQUEST");
        assertEquals(length, ledger("A").getChain().size(), "quota épuisé : pas de bloc");

        // B démarre avec un quota nul : sol sec, mais aucune demande
        FarmerNode b = farmers.get("B");
        assertEquals(FarmerNode.Outcome.NO_QUOTA, b.step().outcome());
        assertEquals(0, b.ra().getEntries());

        sameChainEverywhere();
        assertEquals(0, ledger("C").getQuota("A"));
        assertEquals(800, ledger("C").getReservoirRemaining());
    }

    @Test
    void troisAgriculteursEnBoucleAvecSnapshots() throws Exception {
        startFarmers(id -> new SoilSimulator(50, 12, 4, 1.0, new Random(id.hashCode())), FarmerState::new);
        for (FarmerNode f : farmers.values()) f.startLoop(15);

        GlobalSnapshot lastComplete = null;
        for (int i = 0; i < 40; i++) {
            farmers.get("A").triggerSnapshot();
            sleep(60);
            GlobalSnapshot s = farmers.get("A").lastSnapshot();
            if (s != null && s.isComplete()) lastComplete = s;
        }
        for (FarmerNode f : farmers.values()) {
            assertTrue(f.isLooping(), "la boucle de " + f.getNodeId() + " s'est arrêtée");
        }
        for (FarmerNode f : farmers.values()) f.stopLoop();
        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end && !farmers.get("A").runtime().snapshot().isComplete()) sleep(50);
        if (farmers.get("A").runtime().snapshot().isComplete()) lastComplete = farmers.get("A").lastSnapshot();

        // Registres identiques ; compteurs locaux = valeurs recalculées depuis la chaîne
        List<Block> chain = sameChainEverywhere();
        assertTrue(chain.size() > 4, "trop peu d'irrigations : " + (chain.size() - 1));
        for (FarmerNode f : farmers.values()) {
            String id = f.getNodeId();
            FarmerState st = f.state();
            long blocks = chain.stream().filter(b -> id.equals(b.getNodeId())).count();
            assertEquals(ledger(id).getQuota(id), st.getQuotaRemaining(), "quota de " + id);
            assertEquals(Config.QUOTA_INITIAL - st.getQuotaRemaining(), st.getLitersReceived());
            assertEquals((int) blocks, st.getIrrigations(), "irrigations de " + id);
            assertEquals(st.getRequests(), f.ra().getEntries(), "une entrée en section critique par demande");
            assertEquals(0, st.getRefused());
            assertEquals(RicartAgrawala.State.RELEASED, f.ra().getState());
            long until = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < until
                    && f.pump().getLevel() != ledger(id).getReservoirRemaining()) sleep(20);
            assertEquals(ledger(id).getReservoirRemaining(), f.pump().getLevel(), "réservoir vu par " + id);
        }

        // Au moins un état global complet, cohérent
        assertNotNull(lastComplete, "aucun snapshot complet");
        long held = lastComplete.nodes().values().stream()
                .filter(s -> "HELD".equals(s.state().pumpState())).count();
        assertTrue(held <= 1, "deux nœuds en HELD dans le même état global");
        for (LocalSnapshot ls : lastComplete.nodes().values()) {
            LocalState st = ls.state();
            assertTrue(st.humidity() >= 0 && st.humidity() <= 100);
            // quota de la chaîne enregistrée (préfixe de la chaîne finale, identique partout)
            int spent = chain.subList(0, ls.ledgerLength()).stream()
                    .filter(b -> st.nodeId().equals(b.getNodeId())).mapToInt(Block::getLiters).sum();
            int chainQuota = Config.QUOTA_INITIAL - spent;
            assertTrue(st.quotaRemaining() >= chainQuota && st.quotaRemaining() <= Config.QUOTA_INITIAL,
                    "quota enregistré incohérent chez " + st.nodeId() + " : " + st.quotaRemaining()
                            + " (chaîne : " + chainQuota + ")");
        }
    }

}
