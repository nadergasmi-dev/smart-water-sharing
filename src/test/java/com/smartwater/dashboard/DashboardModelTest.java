package com.smartwater.dashboard;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.smartwater.blockchain.Block;
import com.smartwater.blockchain.Blockchain;
import com.smartwater.communication.Message;
import com.smartwater.communication.MessageType;
import com.smartwater.node.LocalState;
import com.smartwater.security.KeyGenerator;
import com.smartwater.snapshot.LocalSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Le modèle du dashboard, sans broker ni interface : on lui donne des messages comme s'ils venaient de
 * {@code water/from/+}.
 *
 * @author Houssine Ferjani (Binôme 2)
 */
class DashboardModelTest {

    @TempDir
    Path keys;

    DashboardModel model;

    @BeforeEach
    void setUp() throws Exception {
        KeyGenerator.generateAll(keys, false);
        model = new DashboardModel(keys);
    }

    static Message msg(MessageType type, String from, String to, int lamport, JsonObject payload) {
        Message m = new Message(type, to, payload);
        m.setFrom(from);
        m.setLamport(lamport);
        m.setMsgId(UUID.randomUUID().toString());
        return m;
    }

    DashboardModel.FarmerView farmer(String id) {
        return model.view().farmers().stream().filter(f -> f.nodeId().equals(id)).findFirst().orElseThrow();
    }

    static JsonObject blockPayload(Block b) {
        JsonObject p = new JsonObject();
        p.add("block", new Gson().toJsonTree(b));
        return p;
    }

    // ------------------------------------------------------------------ pompe

    @Test
    void etatDeLaPompeReconstitueDepuisLesMessages() {
        model.accept(msg(MessageType.REQUEST, "A", Message.ALL, 3, null));
        model.accept(msg(MessageType.REQUEST, "B", Message.ALL, 5, null));
        assertEquals("WANTED", farmer("A").pumpState());
        assertEquals(List.of("A", "B"), model.view().waiting(), "file dans l'ordre (estampille, id)");

        model.accept(msg(MessageType.REPLY, "B", "A", 6, null));   // B laisse passer A (3 < 5)
        model.accept(msg(MessageType.REPLY, "C", "A", 7, null));
        DashboardModel.View v = model.view();
        assertEquals("HELD", farmer("A").pumpState());
        assertEquals(List.of("A"), v.holders());
        assertEquals(List.of("B"), v.waiting());
        assertFalse(v.mutualExclusionViolated());

        model.accept(msg(MessageType.REPLY, "C", "B", 8, null));
        assertEquals("WANTED", farmer("B").pumpState(), "il manque encore le REPLY de A");

        model.accept(msg(MessageType.RELEASE, "A", Message.ALL, 9, null));
        model.accept(msg(MessageType.REPLY, "A", "B", 10, null));    // REPLY différé
        assertEquals("RELEASED", farmer("A").pumpState());
        assertEquals("HELD", farmer("B").pumpState());
        assertEquals(1, farmer("A").entries());
        assertEquals(1, farmer("A").requests());
        assertEquals(3, farmer("A").lastRequestLamport());
    }

    @Test
    void replyArriveAvantLeRequestTopicsDifferents() {
        // Le REPLY de B (topic water/from/B) peut précéder le REQUEST de A (topic water/from/A)
        model.accept(msg(MessageType.REPLY, "B", "A", 4, null));
        assertEquals("RELEASED", farmer("A").pumpState());
        model.accept(msg(MessageType.REPLY, "C", "A", 4, null));
        model.accept(msg(MessageType.REQUEST, "A", Message.ALL, 3, null));
        assertEquals("HELD", farmer("A").pumpState());
    }

    @Test
    void deuxNoeudsEnSectionCritiqueSontSignales() {
        for (String id : List.of("A", "B")) {
            model.accept(msg(MessageType.REQUEST, id, Message.ALL, 1, null));
            for (String peer : List.of("A", "B", "C")) {
                if (!peer.equals(id)) model.accept(msg(MessageType.REPLY, peer, id, 2, null));
            }
        }
        assertTrue(model.view().mutualExclusionViolated());
        assertEquals(List.of("A", "B"), model.view().holders());
    }

    // ------------------------------------------------------------------ registre

    @Test
    void blocsValidesQuotasReservoirEtSignatures() throws Exception {
        Blockchain a = Blockchain.forNode("A", keys);
        Block b1 = a.addBlock("A", 40, 160);
        Block b2 = a.addBlock("A", 25, 135);
        model.accept(msg(MessageType.LEDGER_UPDATE, "A", Message.ALL, 1, blockPayload(b1)));
        model.accept(msg(MessageType.LEDGER_UPDATE, "A", Message.ALL, 2, blockPayload(b2)));

        DashboardModel.View v = model.view();
        assertEquals(3, v.chain().size());
        assertTrue(v.chainValid());
        assertNull(v.chain().get(0).signatureOk(), "genesis non signé");
        assertTrue(v.chain().get(1).signatureOk());
        assertTrue(v.chain().get(2).signatureOk());
        assertEquals(935, v.reservoir());
        assertEquals(135, farmer("A").quota());
        assertEquals(65, farmer("A").litersUsed());
        assertEquals(200, farmer("B").quota());
        assertTrue(v.rejected().isEmpty());
    }

    @Test
    void blocsFalsifiesRefusesAvecLaRegle() {
        Block last = model.ledger().getLastBlock();
        var key = KeyGenerator.loadPrivateKey(keys, "C");
        Block greedy = Block.create(1, System.currentTimeMillis(), "C", 500, -300, last.getHash(), key);
        Block honest = Block.create(1, System.currentTimeMillis(), "C", 5, 195, last.getHash(), key);
        Block tampered = new Block(1, honest.getTimestamp(), "C", 150, 195, honest.getPreviousHash(),
                honest.getHash(), honest.getSignature());
        model.accept(msg(MessageType.LEDGER_UPDATE, "C", Message.ALL, 1, blockPayload(greedy)));
        model.accept(msg(MessageType.LEDGER_UPDATE, "C", Message.ALL, 2, blockPayload(tampered)));

        DashboardModel.View v = model.view();
        assertEquals(1, v.chain().size(), "aucun bloc falsifié dans la chaîne");
        assertEquals(1000, v.reservoir());
        assertEquals(2, v.rejected().size());
        assertTrue(v.rejected().get(0).reason().contains("V6"), v.rejected().get(0).reason());
        assertTrue(v.rejected().get(1).reason().contains("V1"), v.rejected().get(1).reason());
    }

    // ------------------------------------------------------------------ snapshot

    @Test
    void snapshotDonneLHumiditeEtLEtatDesNoeuds() {
        for (String id : List.of("A", "B", "C")) {
            LocalState st = new LocalState(id, 20.0 + id.charAt(0) - 'A', 200, id.equals("B") ? "HELD" : "RELEASED",
                    id.equals("B") ? List.of("C") : List.of());
            LocalSnapshot ls = new LocalSnapshot(id, st, 1, model.ledger().getLastBlock().getHash(), Map.of(),
                    System.currentTimeMillis());
            JsonObject p = new JsonObject();
            p.addProperty("snapshotId", "A-1");
            p.addProperty("initiator", "A");
            p.add("local", new Gson().toJsonTree(ls));
            model.accept(msg(MessageType.SNAPSHOT_STATE, id, Message.ALL, 10, p));
        }
        DashboardModel.View v = model.view();
        assertNotNull(v.snapshot());
        assertTrue(v.snapshot().isComplete());
        assertEquals(21.0, farmer("B").humidity(), 1e-9);
        assertEquals("HELD", farmer("B").snapshotPumpState());
        assertTrue(DashboardModel.summarize(v.snapshot()).contains("complet"));
    }

    // ------------------------------------------------------------------ journal et client

    @Test
    void journalDecritChaqueTypeDeMessage() {
        for (MessageType t : MessageType.values()) {
            model.accept(msg(t, "A", Message.ALL, 1, new JsonObject()));
        }
        DashboardModel.View v = model.view();
        assertEquals(MessageType.values().length, v.messages());
        assertEquals(MessageType.values().length, v.events().size());
        v.events().forEach(e -> assertFalse(e.text().isBlank()));
        MessageType[] all = MessageType.values();
        assertEquals(all[all.length - 1].name(), v.events().get(0).type(), "le plus récent en premier");
    }

    @Test
    void clientTransmetToutSaufUsurpationsEtDoublons() {
        List<Message> received = new ArrayList<>();
        DashboardMqttClient client = new DashboardMqttClient("tcp://localhost:1", null, received::add);

        Message reply = msg(MessageType.REPLY, "B", "A", 4, null);       // adressé à A : transmis quand même
        client.onRaw("water/from/B", reply.toJson());
        client.onRaw("water/from/B", reply.toJson());                    // doublon
        client.onRaw("water/from/C", msg(MessageType.REQUEST, "A", Message.ALL, 1, null).toJson());   // usurpation
        client.onRaw("autre/topic", reply.toJson());
        client.onRaw("water/from/B", "pas du json");

        assertEquals(1, received.size());
        assertEquals("A", received.get(0).getTo());
        assertEquals(1, client.getSpoofedCount());
    }
}
