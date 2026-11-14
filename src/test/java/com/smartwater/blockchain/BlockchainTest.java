package com.smartwater.blockchain;

import com.google.gson.Gson;
import com.smartwater.Config;
import com.smartwater.security.KeyGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Registre : chaîne valide, genesis, falsification, et une tentative de triche refusée par règle (V1 à V7).
 * Les clés sont générées en mémoire : aucun fichier, aucun broker nécessaire.
 */
class BlockchainTest {

    static final int RESERVOIR = Config.RESERVOIR_INITIAL;

    static Map<String, KeyPair> pairs;
    static Map<String, PublicKey> publicKeys;

    @BeforeAll
    static void generateKeys() {
        pairs = Map.of("A", KeyGenerator.generateKeyPair(),
                       "B", KeyGenerator.generateKeyPair(),
                       "C", KeyGenerator.generateKeyPair());
        publicKeys = Map.of("A", pairs.get("A").getPublic(),
                            "B", pairs.get("B").getPublic(),
                            "C", pairs.get("C").getPublic());
    }

    // ---------------------------------------------------------------- outils

    /** Registre du nœud {@code id} (signe avec sa clé), quotas et réservoir par défaut. */
    static Blockchain ledgerOf(String id) {
        return ledgerOf(id, RESERVOIR);
    }

    static Blockchain ledgerOf(String id, int reservoir) {
        return new Blockchain(pairs.get(id).getPrivate(), publicKeys, Config.initialQuotas(), reservoir);
    }

    /** Un bloc signé par {@code signer}, enchaîné sur le dernier bloc du registre (sans l'ajouter). */
    static Block blockOn(Blockchain ledger, String nodeId, int liters, int quotaRemaining, PrivateKey signer) {
        Block last = ledger.getLastBlock();
        return Block.create(last.getIndex() + 1, System.currentTimeMillis(), nodeId, liters, quotaRemaining,
                last.getHash(), signer);
    }

    /** Ajoute au registre le bloc légitime d'un nœud (signé par lui), comme après un LEDGER_UPDATE. */
    static Block append(Blockchain ledger, String nodeId, int liters, int quotaRemaining) throws InvalidBlockException {
        Block b = blockOn(ledger, nodeId, liters, quotaRemaining, pairs.get(nodeId).getPrivate());
        ledger.acceptBlock(b);
        return b;
    }

    /** Vérifie le refus par la règle attendue, avec la raison préfixée « Vn : ». */
    static InvalidBlockException assertRefused(String rule, Executable action) {
        InvalidBlockException e = assertThrows(InvalidBlockException.class, action);
        assertEquals(rule, e.getRule());
        assertTrue(e.getMessage().startsWith(rule + " : "), "message = " + e.getMessage());
        return e;
    }

    /** Après un refus : chaîne inchangée et refus enregistré avec la raison. */
    static void assertChainUntouched(Blockchain ledger, int expectedSize, InvalidBlockException cause) {
        assertEquals(expectedSize, ledger.getChain().size());
        assertTrue(ledger.validate());
        List<RejectedBlock> rejected = ledger.getRejected();
        assertFalse(rejected.isEmpty());
        assertEquals(cause.getMessage(), rejected.get(rejected.size() - 1).reason());
    }

    // ---------------------------------------------------------------- chaîne valide, genesis

    @Test
    void genesisIsTheSameFixedBlock() {
        Block g = ledgerOf("A").getChain().get(0);
        assertEquals(0, g.getIndex());
        assertEquals("GENESIS", g.getNodeId());
        assertEquals("0", g.getPreviousHash());
        assertNull(g.getSignature());
        assertEquals("f365eab353e2d7ec4acb7922c786fcf7790879ee61502e5f016aa9dbb602cafb", g.getHash());
        // identique sur les trois nœuds
        assertEquals(g.getHash(), ledgerOf("B").getChain().get(0).getHash());
        assertEquals(g.getHash(), ledgerOf("C").getChain().get(0).getHash());
    }

    @Test
    void chainOfFiveBlocksIsValid() throws Exception {
        Blockchain ledger = ledgerOf("A");
        append(ledger, "A", 40, 160);
        append(ledger, "B", 50, 150);
        append(ledger, "C", 30, 170);
        append(ledger, "A", 20, 140);
        append(ledger, "B", 10, 140);

        assertEquals(6, ledger.getChain().size());      // genesis + 5 blocs
        assertTrue(ledger.validate());
        assertTrue(ledger.getRejected().isEmpty());
    }

    @Test
    void addBlockCreatesSignsAndAppendsOwnBlock() throws Exception {
        Blockchain ledger = ledgerOf("A");
        Block b = ledger.addBlock("A", 40, 160);

        assertEquals(1, b.getIndex());
        assertNotNull(b.getSignature());
        assertSame(b, ledger.getLastBlock());
        assertEquals(2, ledger.getChain().size());
        // un autre nœud accepte ce même bloc (réplication)
        Blockchain other = ledgerOf("B");
        other.acceptBlock(b);
        assertEquals(b.getHash(), other.getLastBlock().getHash());
    }

    @Test
    void quotaAndReservoirAreRecomputedFromTheChain() throws Exception {
        Blockchain ledger = ledgerOf("A");
        assertEquals(200, ledger.getQuota("A"));
        assertEquals(1000, ledger.getReservoirRemaining());

        ledger.addBlock("A", 40, 160);
        append(ledger, "B", 50, 150);
        ledger.addBlock("A", 60, 100);

        assertEquals(100, ledger.getQuota("A"));
        assertEquals(150, ledger.getQuota("B"));
        assertEquals(200, ledger.getQuota("C"));
        assertEquals(850, ledger.getReservoirRemaining());
    }

    @Test
    void getChainAndGetRejectedAreReadOnly() throws Exception {
        Blockchain ledger = ledgerOf("A");
        Block extra = ledger.addBlock("A", 10, 190);
        assertThrows(UnsupportedOperationException.class, () -> ledger.getChain().add(extra));
        assertThrows(UnsupportedOperationException.class, () -> ledger.getChain().remove(0));
        assertThrows(UnsupportedOperationException.class, () -> ledger.getRejected().clear());
    }

    // ---------------------------------------------------------------- falsification de la chaîne (validate)

    @Test
    void tamperingWithBlock2IsDetectedAtIndex2() throws Exception {
        Blockchain ledger = ledgerOf("A");
        append(ledger, "A", 40, 160);
        append(ledger, "B", 50, 150);
        append(ledger, "C", 30, 170);
        append(ledger, "A", 20, 140);
        append(ledger, "B", 10, 140);

        List<Block> copy = new ArrayList<>(ledger.getChain());
        assertEquals(-1, Blockchain.firstInvalidIndex(copy, publicKeys));

        // le bloc 2 est modifié (50 L -> 5 L) sans recalculer le hash
        Block b2 = copy.get(2);
        copy.set(2, new Block(b2.getIndex(), b2.getTimestamp(), b2.getNodeId(), 5, b2.getQuotaRemaining(),
                b2.getPreviousHash(), b2.getHash(), b2.getSignature()));
        assertEquals(2, Blockchain.firstInvalidIndex(copy, publicKeys));
    }

    @Test
    void tamperingWithRecomputedHashStillFailsOnTheSignature() throws Exception {
        Blockchain ledger = ledgerOf("A");
        append(ledger, "A", 40, 160);
        append(ledger, "B", 50, 150);
        append(ledger, "C", 30, 170);

        List<Block> copy = new ArrayList<>(ledger.getChain());
        // le tricheur recalcule le hash du bloc 2, mais ne peut pas refaire la signature de B
        Block b2 = copy.get(2);
        String newHash = HashValidator.computeHash(b2.getIndex(), b2.getTimestamp(), b2.getNodeId(), 5,
                b2.getQuotaRemaining(), b2.getPreviousHash());
        copy.set(2, new Block(b2.getIndex(), b2.getTimestamp(), b2.getNodeId(), 5, b2.getQuotaRemaining(),
                b2.getPreviousHash(), newHash, b2.getSignature()));
        assertEquals(2, Blockchain.firstInvalidIndex(copy, publicKeys));
    }

    @Test
    void alteredGenesisIsDetected() {
        Block g = Block.genesis();
        Block fake = new Block(0, 0L, "GENESIS", 999, 0, "0",
                HashValidator.computeHash(0, 0L, "GENESIS", 999, 0, "0"), null);
        assertEquals(0, Blockchain.firstInvalidIndex(List.of(fake), publicKeys));
        assertEquals(-1, Blockchain.firstInvalidIndex(List.of(g), publicKeys));
    }

    // ---------------------------------------------------------------- les 8 tentatives de triche

    @Test
    void v6_quotaExceededIsRefused() {
        Blockchain ledger = ledgerOf("C");
        InvalidBlockException e = assertRefused("V6", () -> ledger.addBlock("C", 250, -50));
        assertTrue(e.getMessage().contains("quota dépassé pour C"), e.getMessage());
        assertChainUntouched(ledger, 1, e);
    }

    @Test
    void v6_quotaExceededAfterPreviousBlocksIsRefused() throws Exception {
        Blockchain ledger = ledgerOf("C");
        ledger.addBlock("C", 150, 50);
        // il ne reste que 50 L à C : 80 L dépassent le quota
        InvalidBlockException e = assertRefused("V6", () -> ledger.addBlock("C", 80, -30));
        assertTrue(e.getMessage().contains("50 L restants"), e.getMessage());
        assertChainUntouched(ledger, 2, e);
    }

    @Test
    void v6_falsifiedRemainingQuotaIsRefused() {
        Blockchain ledger = ledgerOf("C");
        // C prélève 30 L mais annonce qu'il lui reste 200 L au lieu de 170
        InvalidBlockException e = assertRefused("V6", () -> ledger.addBlock("C", 30, 200));
        assertTrue(e.getMessage().contains("quota falsifié pour C"), e.getMessage());
        assertChainUntouched(ledger, 1, e);
    }

    @Test
    void v4_blockInAnotherNodesNameIsRefused() {
        // A demande un bloc au nom de B : il est signé avec la clé de A
        Blockchain ledgerA = ledgerOf("A");
        InvalidBlockException e = assertRefused("V4", () -> ledgerA.addBlock("B", 50, 150));
        assertTrue(e.getMessage().contains("n'a pas été signé par B"), e.getMessage());
        assertChainUntouched(ledgerA, 1, e);
    }

    @Test
    void v4_forgedBlockReceivedByAnotherNodeIsRefused() {
        Blockchain ledgerC = ledgerOf("C");
        Block forged = blockOn(ledgerC, "B", 50, 150, pairs.get("A").getPrivate());   // signé par A, au nom de B
        InvalidBlockException e = assertRefused("V4", () -> ledgerC.acceptBlock(forged));
        assertChainUntouched(ledgerC, 1, e);
    }

    @Test
    void v4_malformedSignatureIsRefused() {
        Blockchain ledger = ledgerOf("B");
        Block ok = blockOn(ledger, "B", 50, 150, pairs.get("B").getPrivate());
        Block bad = new Block(ok.getIndex(), ok.getTimestamp(), "B", 50, 150, ok.getPreviousHash(),
                ok.getHash(), "pas-du-base64!!");
        InvalidBlockException e = assertRefused("V4", () -> ledger.acceptBlock(bad));
        assertChainUntouched(ledger, 1, e);

        Block unsigned = new Block(ok.getIndex(), ok.getTimestamp(), "B", 50, 150, ok.getPreviousHash(),
                ok.getHash(), null);
        assertRefused("V4", () -> ledger.acceptBlock(unsigned));
    }

    @Test
    void v1_blockModifiedInTransitIsRefused() {
        Blockchain sender = ledgerOf("B");
        Block ok = blockOn(sender, "B", 50, 150, pairs.get("B").getPrivate());
        // 50 L -> 5 L en route, sans recalculer le hash
        Block tampered = new Block(ok.getIndex(), ok.getTimestamp(), "B", 5, ok.getQuotaRemaining(),
                ok.getPreviousHash(), ok.getHash(), ok.getSignature());

        Blockchain receiver = ledgerOf("C");
        InvalidBlockException e = assertRefused("V1", () -> receiver.acceptBlock(tampered));
        assertChainUntouched(receiver, 1, e);
    }

    @Test
    void v1_incompleteBlockFromTheNetworkIsRefusedWithoutCrash() {
        Blockchain ledger = ledgerOf("A");
        Block noHash = new Block(1, 1L, "A", 10, 190, Block.genesis().getHash(), null, null);
        assertRefused("V1", () -> ledger.acceptBlock(noHash));
        assertRefused("V1", () -> ledger.acceptBlock(null));
        assertEquals(1, ledger.getChain().size());
    }

    @Test
    void v2_replayedBlockIsRefused() throws Exception {
        Blockchain ledger = ledgerOf("A");
        Block first = ledger.addBlock("A", 40, 160);

        // le même bloc rejoué : mêmes index et previousHash, déjà consommés
        InvalidBlockException e = assertRefused("V2", () -> ledger.acceptBlock(first));
        assertChainUntouched(ledger, 2, e);
        assertEquals(160, ledger.getQuota("A"));   // quota non décompté une seconde fois
    }

    @Test
    void v2_blockWithWrongPositionIsRefused() {
        Blockchain ledger = ledgerOf("A");
        Block last = ledger.getLastBlock();
        // bon previousHash mais index sauté (3 au lieu de 1)
        Block skipped = Block.create(3, 1L, "A", 10, 190, last.getHash(), pairs.get("A").getPrivate());
        assertRefused("V2", () -> ledger.acceptBlock(skipped));
        // bon index mais previousHash inventé
        Block orphan = Block.create(1, 1L, "A", 10, 190, "abcd", pairs.get("A").getPrivate());
        assertRefused("V2", () -> ledger.acceptBlock(orphan));
    }

    @Test
    void v7_litersAboveReservoirAreRefused() {
        // réservoir de 100 L seulement : le quota de A (200 L) ne suffit pas à autoriser 150 L
        Blockchain ledger = ledgerOf("A", 100);
        InvalidBlockException e = assertRefused("V7", () -> ledger.addBlock("A", 150, 50));
        assertTrue(e.getMessage().contains("100 L disponibles"), e.getMessage());
        assertChainUntouched(ledger, 1, e);
    }

    @Test
    void v7_reservoirIsSharedBetweenNodes() throws Exception {
        Blockchain ledger = ledgerOf("A", 250);
        ledger.addBlock("A", 200, 0);                  // il reste 50 L au réservoir
        Block forB = blockOn(ledger, "B", 80, 120, pairs.get("B").getPrivate());
        assertRefused("V7", () -> ledger.acceptBlock(forB));
    }

    @Test
    void v3_unknownNodeIsRefused() {
        Blockchain ledger = ledgerOf("A");
        InvalidBlockException e = assertRefused("V3", () -> ledger.addBlock("D", 10, 0));
        assertTrue(e.getMessage().contains("nœud inconnu : D"), e.getMessage());
        assertChainUntouched(ledger, 1, e);

        Block anonymous = new Block(1, 1L, null, 10, 0, Block.genesis().getHash(),
                HashValidator.computeHash(1, 1L, null, 10, 0, Block.genesis().getHash()), "x");
        assertRefused("V3", () -> ledger.acceptBlock(anonymous));
    }

    @Test
    void v5_zeroOrNegativeLitersAreRefused() {
        Blockchain ledger = ledgerOf("A");
        InvalidBlockException e = assertRefused("V5", () -> ledger.addBlock("A", 0, 200));
        assertChainUntouched(ledger, 1, e);
        assertRefused("V5", () -> ledger.addBlock("A", -10, 210));
        assertEquals(2, ledger.getRejected().size());
    }

    // ---------------------------------------------------------------- observateur, Gson

    @Test
    void observerNeverCreatesBlocksButValidatesReceivedOnes() throws Exception {
        Blockchain observer = new Blockchain(null, publicKeys, Config.initialQuotas(), RESERVOIR);
        assertThrows(IllegalStateException.class, () -> observer.addBlock("A", 10, 190));

        Blockchain nodeA = ledgerOf("A");
        Block good = nodeA.addBlock("A", 40, 160);
        observer.acceptBlock(good);
        assertEquals(2, observer.getChain().size());
        assertTrue(observer.validate());

        // un faux bloc reçu est refusé et signalé pour le dashboard
        Block forged = blockOn(observer, "B", 50, 150, pairs.get("A").getPrivate());
        assertRefused("V4", () -> observer.acceptBlock(forged));
        assertEquals(1, observer.getRejected().size());
        assertEquals(forged.getHash(), observer.getRejected().get(0).block().getHash());
        assertTrue(observer.getRejected().get(0).reason().startsWith("V4 : "));
    }

    @Test
    void blockSurvivesGsonRoundTripIncludingSignature() throws Exception {
        Blockchain sender = ledgerOf("A");
        Block original = sender.addBlock("A", 40, 160);

        Gson gson = new Gson();
        Block received = gson.fromJson(gson.toJson(original), Block.class);

        assertEquals(original.getHash(), received.getHash());
        assertEquals(original.getSignature(), received.getSignature());
        assertEquals(original.getLiters(), received.getLiters());
        // et le bloc désérialisé est accepté tel quel par un autre nœud (LEDGER_UPDATE)
        Blockchain receiver = ledgerOf("C");
        receiver.acceptBlock(received);
        assertEquals(2, receiver.getChain().size());
    }
}
