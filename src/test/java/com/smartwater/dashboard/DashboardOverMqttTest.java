package com.smartwater.dashboard;

import com.smartwater.Config;
import com.smartwater.blockchain.Block;
import com.smartwater.node.FarmerNode;
import com.smartwater.node.FarmerState;
import com.smartwater.node.ScenarioDemo;
import com.smartwater.node.SoilSimulator;
import com.smartwater.security.KeyGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bout en bout sur le vrai broker : 3 agriculteurs + le dashboard abonné à {@code water/from/+}.
 * Ce que le dashboard affiche (déduit des seuls messages) doit correspondre à l'état réel des nœuds.
 *
 * @author Houssine Ferjani (Binôme 2)
 */
class DashboardOverMqttTest {

    @TempDir
    Path keys;

    final Map<String, FarmerNode> farmers = new LinkedHashMap<>();
    DashboardMqttClient client;

    @AfterEach
    void stop() {
        farmers.values().forEach(FarmerNode::stop);
        if (client != null) client.disconnect();
    }

    static void waitFor(BooleanSupplier cond, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end && !cond.getAsBoolean()) Thread.sleep(20);
    }

    @Test
    void leDashboardVoitCeQueFontLesNoeuds() throws Exception {
        KeyGenerator.generateAll(keys, false);
        DashboardModel model = new DashboardModel(keys);
        client = new DashboardMqttClient(Config.BROKER_URL, null, model);
        client.connect();                                             // avant les nœuds : il voit tout

        for (String id : Config.NODE_IDS) {
            // sol qui sèche complètement à chaque pas : 60 L demandés à chaque cycle
            farmers.put(id, new FarmerNode(id, keys, new SoilSimulator(0, 100, 0, 1.0, new Random(0)),
                    new FarmerState(id)));
        }
        for (FarmerNode f : farmers.values()) f.start();
        Thread.sleep(500);

        // 2 cycles par agriculteur, les trois en même temps
        List<Thread> threads = new ArrayList<>();
        for (FarmerNode f : farmers.values()) {
            threads.add(new Thread(() -> { f.step(); f.step(); }));
        }
        threads.forEach(Thread::start);
        for (Thread t : threads) t.join(30_000);

        farmers.get("A").triggerSnapshot();
        ScenarioDemo.cheat(farmers.get("C"), keys);

        waitFor(() -> model.view().chain().size() == 7 && model.view().rejected().size() == 2
                && model.view().snapshot() != null && model.view().snapshot().isComplete(), 5000);
        DashboardModel.View v = model.view();

        // Registre : même chaîne que les nœuds, blocs signés, falsifications refusées
        List<Block> real = farmers.get("A").runtime().ledger().getChain();
        assertEquals(7, real.size());
        assertEquals(real.size(), v.chain().size());
        for (int i = 0; i < real.size(); i++) {
            assertEquals(real.get(i).getHash(), v.chain().get(i).hash());
            if (i > 0) assertTrue(v.chain().get(i).signatureOk());
        }
        assertTrue(v.chainValid());
        assertEquals(1000 - 6 * 60, v.reservoir());
        assertEquals(2, v.rejected().size(), "les 2 blocs falsifiés de C");

        // Pompe : état reconstitué = état réel
        assertTrue(v.holders().isEmpty());
        assertTrue(v.waiting().isEmpty());
        for (DashboardModel.FarmerView f : v.farmers()) {
            FarmerNode real0 = farmers.get(f.nodeId());
            assertEquals("RELEASED", f.pumpState());
            assertEquals(real0.ra().getEntries(), f.requests());
            assertEquals(2, f.entries());
            assertEquals(80, f.quota());
            assertNotNull(f.humidity(), "humidité connue grâce au snapshot");
        }
        assertEquals(0, client.getSpoofedCount());
    }
}
