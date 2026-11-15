package com.smartwater.node;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.smartwater.blockchain.Block;
import com.smartwater.blockchain.Blockchain;
import com.smartwater.blockchain.RejectedBlock;
import com.smartwater.security.KeyGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Charge utile d'une balise d'état {@code NODE_STATUS} : aller-retour JSON et rejet des charges mal formées. */
class NodeStatusTest {

    @TempDir
    Path keys;

    @Test
    void roundTripKeepsStateStatsAndBlocks() throws Exception {
        KeyGenerator.generateAll(keys, false);
        Blockchain ledger = Blockchain.forNode("A", keys);
        ledger.addBlock("A", 40, 160);
        ledger.addBlock("A", 30, 130);

        JsonObject stats = new JsonObject();
        stats.addProperty("requests", 7);
        stats.addProperty("requestLamport", 42);
        JsonArray awaiting = new JsonArray();
        awaiting.add("B");
        stats.add("awaiting", awaiting);
        LocalState local = new LocalState("A", 36.5, 130, "WANTED", List.of("C"));
        List<Block> blocks = ledger.getChain().subList(1, 3);

        NodeStatus back = NodeStatus.fromPayload(new com.google.gson.Gson().fromJson(
                new NodeStatus(local, stats, blocks).toPayload().toString(), JsonObject.class));   // comme sur le réseau

        assertEquals(local, back.local());
        assertEquals(7, back.stat("requests", -1));
        assertEquals(42, back.stat("requestLamport", -1));
        assertEquals(-1, back.stat("absent", -1), "valeur par défaut si le compteur manque");
        assertEquals(List.of("B"), back.awaiting());
        assertEquals(2, back.blocks().size());
        assertEquals(blocks.get(1).getHash(), back.blocks().get(1).getHash());
        assertEquals(blocks.get(1).getSignature(), back.blocks().get(1).getSignature());
    }

    @Test
    void optionalPartsMayBeAbsent() {
        NodeStatus st = new NodeStatus(new LocalState("B", 50, 200, "RELEASED", List.of()), null, null);
        NodeStatus back = NodeStatus.fromPayload(st.toPayload());
        assertTrue(back.blocks().isEmpty());
        assertTrue(back.awaiting().isEmpty());
        assertEquals(5, back.stat("requests", 5));
    }

    @Test
    void malformedPayloadsAreRejectedWithAClearError() {
        assertThrows(IllegalArgumentException.class, () -> NodeStatus.fromPayload(null));
        assertThrows(IllegalArgumentException.class, () -> NodeStatus.fromPayload(new JsonObject()));

        JsonObject notAnObject = new JsonObject();
        notAnObject.addProperty("local", "texte");
        assertThrows(IllegalArgumentException.class, () -> NodeStatus.fromPayload(notAnObject));

        JsonObject badBlock = new NodeStatus(new LocalState("A", 1, 1, "RELEASED", List.of()), null, null).toPayload();
        JsonArray blocks = new JsonArray();
        blocks.add("pas un bloc");
        badBlock.add("blocks", blocks);
        assertThrows(IllegalArgumentException.class, () -> NodeStatus.fromPayload(badBlock));
    }

    @Test
    void rejectedBlocksTravelWithTheirReason() throws Exception {
        KeyGenerator.generateAll(keys, false);
        Blockchain ledger = Blockchain.forNode("C", keys);
        Block greedy = Block.create(1, 5L, "C", 500, -300, ledger.getLastBlock().getHash(),
                KeyGenerator.loadPrivateKey(keys, "C"));
        assertThrows(com.smartwater.blockchain.InvalidBlockException.class, () -> ledger.acceptBlock(greedy));
        assertEquals(1, ledger.getRejected().size());

        NodeStatus st = new NodeStatus(new LocalState("C", 40, 200, "RELEASED", List.of()), null, List.of(),
                ledger.getRejected());
        NodeStatus back = NodeStatus.fromPayload(new com.google.gson.Gson().fromJson(
                st.toPayload().toString(), JsonObject.class));

        assertEquals(1, back.rejected().size());
        RejectedBlock r = back.rejected().get(0);
        assertTrue(r.reason().startsWith("V6 : "), r.reason());
        assertEquals(500, r.block().getLiters());
        assertEquals(greedy.getHash(), r.block().getHash());

        JsonObject bad = st.toPayload();
        JsonArray junk = new JsonArray();
        junk.add("pas un refus");
        bad.add("rejected", junk);
        assertThrows(IllegalArgumentException.class, () -> NodeStatus.fromPayload(bad));
    }
}
