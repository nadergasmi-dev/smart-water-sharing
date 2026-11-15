package com.smartwater.dashboard;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.smartwater.blockchain.Block;
import com.smartwater.blockchain.Blockchain;
import com.smartwater.blockchain.RejectedBlock;
import com.smartwater.communication.Message;
import com.smartwater.communication.MessageType;
import com.smartwater.node.LocalState;
import com.smartwater.node.NodeStatus;
import com.smartwater.security.KeyGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Balises d'état {@code NODE_STATUS} : un dashboard lancé en retard n'a aucun historique, les balises doivent
 * suffire à l'amener à l'état réel des nœuds. Sans broker ni interface.
 */
class DashboardBeaconsTest {

    @TempDir
    Path keys;

    DashboardModel model;
    Blockchain nodeA;       // la vraie copie du registre de A (clés de keys/)

    @BeforeEach
    void setUp() throws Exception {
        KeyGenerator.generateAll(keys, false);
        model = new DashboardModel(keys);
        nodeA = Blockchain.forNode("A", keys);
    }

    static Message msg(MessageType type, String from, String to, JsonObject payload) {
        Message m = new Message(type, to, payload);
        m.setFrom(from);
        m.setLamport(1);
        m.setMsgId(UUID.randomUUID().toString());
        return m;
    }

    /** Balise telle que la publierait le nœud {@code id}. */
    static Message beacon(String id, String pump, double humidity, int quota, List<Block> blocks,
                          long requests, long entries, int requestLamport, List<String> awaiting) {
        JsonObject stats = new JsonObject();
        stats.addProperty("requests", requests);
        stats.addProperty("entries", entries);
        stats.addProperty("requestLamport", requestLamport);
        JsonArray aw = new JsonArray();
        awaiting.forEach(aw::add);
        stats.add("awaiting", aw);
        NodeStatus st = new NodeStatus(new LocalState(id, humidity, quota, pump, List.of()), stats, blocks);
        return msg(MessageType.NODE_STATUS, id, Message.ALL, st.toPayload());
    }

    DashboardModel.FarmerView farmer(String id) {
        return model.view().farmers().stream().filter(f -> f.nodeId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void dashboardLanceEnRetardSeMetAJourAvecUneSeuleBalise() throws Exception {
        // 3 blocs ajoutés AVANT le démarrage du dashboard : il n'en a rien vu passer
        nodeA.addBlock("A", 40, 160);
        nodeA.addBlock("A", 30, 130);
        nodeA.addBlock("A", 20, 110);
        List<Block> history = nodeA.getChain().subList(1, nodeA.getChain().size());
        assertEquals(1, model.view().chain().size(), "avant la balise : seulement le genesis");

        model.accept(beacon("A", "RELEASED", 41.5, 110, history, 5, 3, -1, List.of()));

        DashboardModel.View v = model.view();
        assertEquals(4, v.chain().size(), "la chaîne complète arrive avec la balise");
        assertTrue(v.chainValid());
        assertTrue(v.chain().get(3).signatureOk());
        assertEquals(910, v.reservoir(), "réservoir recalculé depuis la chaîne : 1000 - 90");
        assertEquals(110, farmer("A").quota());
        assertEquals(90, farmer("A").litersUsed());
        assertEquals(41.5, farmer("A").humidity(), 1e-9, "humidité fraîche, sans attendre un snapshot");
        assertEquals(5, farmer("A").requests(), "compteurs annoncés par le nœud, pas déduits des messages");
        assertEquals(3, farmer("A").entries());
        assertEquals("ONLINE", farmer("A").liveness());
        assertEquals("UNKNOWN", farmer("B").liveness(), "rien reçu de B");
        assertTrue(v.rejected().isEmpty());
    }

    @Test
    void ledgerUpdateDejaVuEtBaliseNeFontPasDeDoublon() throws Exception {
        Block b1 = nodeA.addBlock("A", 40, 160);
        JsonObject p = new JsonObject();
        p.add("block", new com.google.gson.Gson().toJsonTree(b1));
        model.accept(msg(MessageType.LEDGER_UPDATE, "A", Message.ALL, p));
        model.accept(beacon("A", "RELEASED", 40, 160, nodeA.getChain().subList(1, 2), 1, 1, -1, List.of()));
        model.accept(beacon("A", "RELEASED", 40, 160, nodeA.getChain().subList(1, 2), 1, 1, -1, List.of()));

        assertEquals(2, model.view().chain().size());
        assertTrue(model.view().divergences().isEmpty());
    }

    @Test
    void balisesNeSontPasAuJournalNiDansLeCompteurDeMessages() throws Exception {
        model.accept(msg(MessageType.REQUEST, "A", Message.ALL, null));
        model.accept(beacon("A", "WANTED", 30, 200, List.of(), 1, 0, 7, List.of("B", "C")));
        model.accept(beacon("B", "RELEASED", 50, 200, List.of(), 0, 0, -1, List.of()));

        DashboardModel.View v = model.view();
        assertEquals(1, v.messages(), "seul le REQUEST est un message applicatif");
        assertEquals(1, v.events().size());
        assertEquals(2, v.beacons());
    }

    @Test
    void etatDeLaPompeRecaleSurLaBaliseAuLieuDeCompterDepuisLeDebut() {
        // Le dashboard a raté 2 cycles complets de A : sans balise il resterait décalé. Il n'a vu que :
        model.accept(msg(MessageType.RELEASE, "A", Message.ALL, null));       // fin d'un cycle commencé avant lui
        model.accept(msg(MessageType.REQUEST, "A", Message.ALL, null));       // nouvelle demande
        assertEquals("RELEASED", farmer("A").pumpState(), "comptage décalé d'un cran : faux (A est en réalité WANTED)");

        // La balise de A dit la vérité : WANTED, attend encore B
        model.accept(beacon("A", "WANTED", 29, 150, List.of(), 3, 2, 12, List.of("B")));
        assertEquals("WANTED", farmer("A").pumpState());
        assertEquals(12, farmer("A").lastRequestLamport());

        // Évolution en direct, à partir de l'état recalé
        model.accept(msg(MessageType.REPLY, "B", "A", null));
        assertEquals("HELD", farmer("A").pumpState());
        assertEquals(List.of("A"), model.view().holders());
        model.accept(msg(MessageType.RELEASE, "A", Message.ALL, null));
        assertEquals("RELEASED", farmer("A").pumpState());
    }

    @Test
    void compteursAnnoncesPlusCeQuiSEstPasseDepuisLaBalise() {
        model.accept(beacon("A", "RELEASED", 40, 200, List.of(), 4, 4, -1, List.of()));
        assertEquals(4, farmer("A").requests());
        model.accept(msg(MessageType.REQUEST, "A", Message.ALL, null));       // arrivé après la balise
        model.accept(msg(MessageType.REPLY, "B", "A", null));
        model.accept(msg(MessageType.REPLY, "C", "A", null));
        model.accept(msg(MessageType.RELEASE, "A", Message.ALL, null));
        assertEquals(5, farmer("A").requests(), "la balise a jusqu'à 1 s de retard : on ajoute ce qui s'est passé depuis");
        assertEquals(5, farmer("A").entries());
    }

    @Test
    void baliseMalFormeeOuAuNomDUnAutreEstIgnoree() throws Exception {
        JsonObject garbage = new JsonObject();
        garbage.addProperty("local", "pas un objet");
        model.accept(msg(MessageType.NODE_STATUS, "A", Message.ALL, garbage));
        // B publie une balise qui prétend décrire A : ignorée
        Message forged = beacon("A", "HELD", 10, 0, List.of(), 9, 9, 1, List.of());
        forged.setFrom("B");
        model.accept(forged);

        assertEquals(0, model.view().beacons());
        assertEquals("UNKNOWN", farmer("A").liveness());
        assertEquals("UNKNOWN", farmer("B").liveness());
        assertEquals("RELEASED", farmer("A").pumpState());
    }

    @Test
    void baliseAvecUnBlocFalsifieEstRefuseeUneSeuleFois() throws Exception {
        Block good = nodeA.addBlock("A", 40, 160);
        Block tampered = new Block(good.getIndex(), good.getTimestamp(), "A", 5, good.getQuotaRemaining(),
                good.getPreviousHash(), good.getHash(), good.getSignature());
        for (int i = 0; i < 4; i++) {                                          // une balise par seconde, toujours la même
            model.accept(beacon("A", "RELEASED", 40, 160, List.of(tampered), 1, 1, -1, List.of()));
        }
        DashboardModel.View v = model.view();
        assertEquals(1, v.chain().size(), "aucun bloc falsifié dans la chaîne");
        assertEquals(1, v.divergences().size(), "alerte unique : pas une par balise");
        assertTrue(v.divergences().get(0).reason().startsWith("V1 : "), v.divergences().get(0).reason());
    }

    @Test
    void refusPassesAnnoncesParLesNoeudsApparaissentUneSeuleFois() throws Exception {
        // C a triché AVANT le démarrage du dashboard : A et B ont refusé le faux bloc, chacun l'annonce dans sa balise
        Block greedy = Block.create(1, 5L, "C", 500, -300, nodeA.getLastBlock().getHash(),
                KeyGenerator.loadPrivateKey(keys, "C"));
        RejectedBlock refusedByA = new RejectedBlock(greedy, "V6 : quota dépassé pour C : 500 L demandés, 200 L restants", 1000L);
        RejectedBlock refusedByB = new RejectedBlock(greedy, "V6 : quota dépassé pour C : 500 L demandés, 200 L restants", 1001L);
        assertTrue(model.view().rejected().isEmpty(), "rien avant les balises");

        for (String id : List.of("A", "B")) {
            NodeStatus st = new NodeStatus(new LocalState(id, 40, 200, "RELEASED", List.of()), null, List.of(),
                    List.of(id.equals("A") ? refusedByA : refusedByB));
            model.accept(msg(MessageType.NODE_STATUS, id, Message.ALL, st.toPayload()));
        }
        // et la même balise, une seconde plus tard
        NodeStatus again = new NodeStatus(new LocalState("A", 40, 200, "RELEASED", List.of()), null, List.of(), List.of(refusedByA));
        model.accept(msg(MessageType.NODE_STATUS, "A", Message.ALL, again.toPayload()));

        List<RejectedBlock> v = model.view().rejected();
        assertEquals(1, v.size(), "même bloc, même raison : une seule ligne, quel que soit le nombre de rapporteurs");
        assertEquals("C", v.get(0).block().getNodeId());
        assertTrue(v.get(0).reason().startsWith("V6 : "));
        assertEquals(1, model.view().chain().size(), "un bloc refusé n'entre jamais dans la chaîne affichée");
    }

    @Test
    void refusVusEnDirectEtAnnoncesParBaliseNeFontPasDeDoublon() throws Exception {
        Block good = nodeA.addBlock("A", 40, 160);
        Block tampered = new Block(good.getIndex(), good.getTimestamp(), "A", 5, good.getQuotaRemaining(),
                good.getPreviousHash(), good.getHash(), good.getSignature());
        JsonObject p = new JsonObject();
        p.add("block", new com.google.gson.Gson().toJsonTree(tampered));
        model.accept(msg(MessageType.LEDGER_UPDATE, "A", Message.ALL, p));          // refusé en direct (V1)
        assertEquals(1, model.view().rejected().size());

        RejectedBlock sameRefusal = model.view().rejected().get(0);
        NodeStatus st = new NodeStatus(new LocalState("B", 40, 200, "RELEASED", List.of()), null, List.of(), List.of(sameRefusal));
        model.accept(msg(MessageType.NODE_STATUS, "B", Message.ALL, st.toPayload()));

        assertEquals(1, model.view().rejected().size());
    }
}
