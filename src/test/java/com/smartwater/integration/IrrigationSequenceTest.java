package com.smartwater.integration;

import com.google.gson.JsonObject;
import com.smartwater.Config;
import com.smartwater.blockchain.Block;
import com.smartwater.blockchain.Blockchain;
import com.smartwater.blockchain.LedgerReplicator;
import com.smartwater.communication.Message;
import com.smartwater.communication.MessageSender;
import com.smartwater.communication.MessageType;
import com.smartwater.irrigation.PumpAccess;
import com.smartwater.security.KeyGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Séquence d'irrigation (contrat, section 8) : ordre exact des étapes et chemins d'erreur. Sans broker. */
class IrrigationSequenceTest {

    final List<String> trace = new ArrayList<>();      // ordre des étapes observées
    final List<Message> published = new ArrayList<>();
    Blockchain ledger;
    LedgerReplicator replicator;
    IrrigationSequence sequence;

    final PumpAccess pump = new PumpAccess() {
        @Override public void requestPump() { trace.add("requestPump"); }
        @Override public void releasePump() { trace.add("releasePump"); }
    };

    final MessageSender sender = m -> {
        m.setFrom("A");
        published.add(m);
        trace.add("publish " + m.getType());
        return 0;
    };

    /** Compteurs de Binôme 2, simulés. */
    class Counters implements IrrigationSequence.Listener {
        int quota = 200;
        String refused;
        boolean nothing;
        @Override public void onIrrigated(Block b) { quota = b.getQuotaRemaining(); trace.add("compteurs"); }
        @Override public void onRefused(String reason) { refused = reason; }
        @Override public void onNothingAllocated() { nothing = true; }
    }

    @BeforeEach
    void setUp() {
        KeyPair a = KeyGenerator.generateKeyPair();
        Map<String, PublicKey> keys = Map.of("A", a.getPublic(),
                "B", KeyGenerator.generateKeyPair().getPublic(), "C", KeyGenerator.generateKeyPair().getPublic());
        ledger = new Blockchain(a.getPrivate(), keys, Config.initialQuotas(), Config.RESERVOIR_INITIAL);
        replicator = new LedgerReplicator("A", ledger, sender);
        sequence = new IrrigationSequence("A", pump, ledger, replicator, sender, 200);
    }

    @Test
    void stepsHappenInTheOrderImposedByTheContract() {
        Counters counters = new Counters();
        IrrigationSequence.Result r = sequence.irrigate((quota, reservoir) -> Math.min(40, Math.min(quota, reservoir)), counters);

        assertEquals(IrrigationSequence.Outcome.IRRIGATED, r.outcome());
        assertEquals(List.of("requestPump", "compteurs", "publish IRRIGATION_EVENT", "publish LEDGER_UPDATE",
                "publish PUMP_RELEASED", "releasePump"), trace);
        assertEquals(160, counters.quota);
        assertEquals(2, ledger.getChain().size());

        JsonObject event = published.get(0).getPayload();
        assertEquals(40, event.get("liters").getAsInt());
        assertEquals(160, event.get("quotaRemaining").getAsInt());
        assertEquals(960, event.get("reservoirLevel").getAsInt());
        assertEquals("T-A-1", event.get("txId").getAsString());
    }

    @Test
    void allocationReceivesQuotaAndReservoirRecomputedFromTheChain() {
        sequence.irrigate((quota, reservoir) -> 40, new Counters());
        int[] seen = new int[2];
        sequence.irrigate((quota, reservoir) -> { seen[0] = quota; seen[1] = reservoir; return 10; }, new Counters());
        assertArrayEquals(new int[] {160, 960}, seen);
    }

    @Test
    void nothingAllocatedMeansNoBlockNoEventButThePumpIsReleased() {
        Counters counters = new Counters();
        IrrigationSequence.Result r = sequence.irrigate((quota, reservoir) -> 0, counters);

        assertEquals(IrrigationSequence.Outcome.NOTHING_ALLOCATED, r.outcome());
        assertTrue(counters.nothing);
        assertEquals(List.of("requestPump", "publish PUMP_RELEASED", "releasePump"), trace);
        assertEquals(1, ledger.getChain().size());
    }

    @Test
    void refusedBlockChangesNoCounterSendsNoEventButReleasesThePump() {
        Counters counters = new Counters();
        // allocation fautive : ignore le quota (250 L > 200 L) → V6
        IrrigationSequence.Result r = sequence.irrigate((quota, reservoir) -> 250, counters);

        assertEquals(IrrigationSequence.Outcome.REFUSED, r.outcome());
        assertTrue(r.reason().startsWith("V6 : "), r.reason());
        assertEquals(r.reason(), counters.refused);
        assertEquals(200, counters.quota, "compteurs inchangés");
        assertEquals(List.of("requestPump", "publish PUMP_RELEASED", "releasePump"), trace,
                "aucun événement diffusé (seulement l'annonce de supervision)");
        assertEquals(1, ledger.getRejected().size(), "le refus est visible par le dashboard");
    }

    @Test
    void aSnapshotCannotSlipBetweenTheBlockAndTheCounters() throws Exception {
        List<String> order = java.util.Collections.synchronizedList(new ArrayList<>());
        Thread[] snapshot = new Thread[1];
        boolean[] lockHeld = new boolean[1];

        sequence.irrigate((quota, reservoir) -> 40, new IrrigationSequence.Listener() {
            @Override public void onIrrigated(Block b) {
                lockHeld[0] = Thread.holdsLock(sender);
                // un snapshot arrive pile maintenant : il prend le même verrou que Chandy-Lamport
                snapshot[0] = new Thread(() -> { synchronized (sender) { order.add("snapshot"); } });
                snapshot[0].start();
                try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                order.add("compteurs");
            }
        });
        snapshot[0].join(2000);

        assertTrue(lockHeld[0], "les compteurs sont mis à jour sous le verrou du client MQTT");
        assertEquals(List.of("compteurs", "snapshot"), order,
                "le snapshot attend la fin de l'irrigation, il ne voit jamais un état à moitié mis à jour");
        assertTrue(trace.indexOf("publish LEDGER_UPDATE") >= 0);
    }

    @Test
    void pumpIsReleasedEvenIfTheAllocationThrows() {
        assertThrows(IllegalStateException.class, () -> sequence.irrigate((quota, reservoir) -> {
            throw new IllegalStateException("bug dans l'allocation");
        }, new Counters()));
        assertEquals(List.of("requestPump", "publish PUMP_RELEASED", "releasePump"), trace);
    }

    @Test
    void noBlockIsCreatedOnALedgerKnownToBeBehind() {
        // B annonce une copie de 5 blocs (LEDGER_REQUEST avec fromIndex = 5), alors que A n'en a qu'1
        JsonObject p = new JsonObject();
        p.addProperty("fromIndex", 5);
        p.addProperty("lastHash", "abcd");
        Message fromB = new Message(MessageType.LEDGER_REQUEST, Message.ALL, p);
        fromB.setFrom("B");
        replicator.onMessage(fromB);
        trace.clear();
        published.clear();

        IrrigationSequence.Result r = sequence.irrigate((quota, reservoir) -> 40, new Counters());

        assertEquals(IrrigationSequence.Outcome.LEDGER_NOT_UP_TO_DATE, r.outcome());
        assertEquals(1, ledger.getChain().size(), "pas de bloc sur une copie en retard (il créerait une fourche)");
        assertTrue(published.stream().anyMatch(m -> m.getType() == MessageType.LEDGER_REQUEST), "rattrapage demandé");
        assertEquals("releasePump", trace.get(trace.size() - 1));
    }
}
