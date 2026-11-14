package com.smartwater.security;

import com.smartwater.blockchain.Block;
import com.smartwater.blockchain.Blockchain;
import com.smartwater.blockchain.HashValidator;
import com.smartwater.blockchain.InvalidBlockException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;

import static org.junit.jupiter.api.Assertions.*;

/** Génération et chargement des clés (dossier temporaire : jamais le vrai dossier keys/). */
class KeyGeneratorTest {

    @TempDir
    Path keysDir;

    @Test
    void generatesSixFilesAndLoadsThemBack() throws IOException {
        assertEquals(3, KeyGenerator.generateAll(keysDir, false));

        for (String id : new String[] {"A", "B", "C"}) {
            assertTrue(Files.exists(keysDir.resolve(id + ".pub")));
            assertTrue(Files.exists(keysDir.resolve(id + ".key")));
            assertNotNull(KeyGenerator.loadPublicKey(keysDir, id));
            assertNotNull(KeyGenerator.loadPrivateKey(keysDir, id));
        }
        assertEquals(3, KeyGenerator.loadAllPublicKeys(keysDir).size());
    }

    @Test
    void keyFilesUseTheDocumentedFormats() throws IOException {
        KeyGenerator.generateAll(keysDir, false);
        PublicKey pub = KeyGenerator.loadPublicKey(keysDir, "A");
        PrivateKey key = KeyGenerator.loadPrivateKey(keysDir, "A");
        assertEquals("X.509", pub.getFormat());
        assertEquals("PKCS#8", key.getFormat());
        assertEquals("EC", pub.getAlgorithm());
        // le fichier .pub contient bien l'encodage Base64 de la clé
        assertEquals(java.util.Base64.getEncoder().encodeToString(pub.getEncoded()),
                Files.readString(keysDir.resolve("A.pub")).trim());
    }

    @Test
    void loadedKeysSignAndVerify() throws IOException {
        KeyGenerator.generateAll(keysDir, false);
        PrivateKey keyA = KeyGenerator.loadPrivateKey(keysDir, "A");
        PublicKey pubA = KeyGenerator.loadPublicKey(keysDir, "A");
        PublicKey pubB = KeyGenerator.loadPublicKey(keysDir, "B");

        Block b = Block.create(1, 1L, "A", 40, 160, Block.genesis().getHash(), keyA);
        assertTrue(BlockSigner.verify(b, pubA));
        assertFalse(BlockSigner.verify(b, pubB), "la clé de B ne doit pas valider la signature de A");
    }

    @Test
    void existingKeysAreNeverOverwrittenWithoutForce() throws IOException {
        KeyGenerator.generateAll(keysDir, false);
        String before = Files.readString(keysDir.resolve("A.key"));

        assertEquals(0, KeyGenerator.generateAll(keysDir, false));
        assertEquals(before, Files.readString(keysDir.resolve("A.key")));

        assertEquals(3, KeyGenerator.generateAll(keysDir, true));
        assertNotEquals(before, Files.readString(keysDir.resolve("A.key")));
    }

    @Test
    void halfAPairIsReportedInsteadOfSilentlyReplaced() throws IOException {
        KeyGenerator.generateAll(keysDir, false);
        Files.delete(keysDir.resolve("B.key"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> KeyGenerator.generateAll(keysDir, false));
        assertTrue(e.getMessage().contains("--force"), e.getMessage());
    }

    @Test
    void missingOrInvalidKeyFilesGiveClearErrors() throws IOException {
        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> KeyGenerator.loadPrivateKey(keysDir, "A"));
        assertTrue(missing.getMessage().contains("KeyGenerator"), missing.getMessage());

        Files.writeString(keysDir.resolve("A.pub"), "ceci n'est pas du base64 !!");
        assertThrows(IllegalStateException.class, () -> KeyGenerator.loadPublicKey(keysDir, "A"));
    }

    @Test
    void onlyKnownNodeIdsAreAccepted() {
        assertThrows(IllegalArgumentException.class, () -> KeyGenerator.loadPublicKey(keysDir, "D"));
        assertThrows(IllegalArgumentException.class, () -> KeyGenerator.loadPrivateKey(keysDir, "../A"));
    }

    // ---------------------------------------------------------------- Blockchain.forNode / observer

    @Test
    void forNodeLoadsKeysAndSignsItsOwnBlocks() throws Exception {
        KeyGenerator.generateAll(keysDir, false);
        Blockchain ledgerA = Blockchain.forNode("A", keysDir);

        Block b = ledgerA.addBlock("A", 40, 160);
        assertTrue(ledgerA.validate());
        assertTrue(BlockSigner.verify(b, KeyGenerator.loadPublicKey(keysDir, "A")));

        // initialisé avec les valeurs de Config : quota 200, réservoir 1000
        assertEquals(160, ledgerA.getQuota("A"));
        assertEquals(960, ledgerA.getReservoirRemaining());

        // un nœud ne peut pas écrire au nom d'un autre : V4
        InvalidBlockException e = assertThrows(InvalidBlockException.class,
                () -> ledgerA.addBlock("B", 10, 190));
        assertEquals("V4", e.getRule());
    }

    @Test
    void forNodeRejectsUnknownNodeAndMissingKeys() {
        assertThrows(IllegalArgumentException.class, () -> Blockchain.forNode("D", keysDir));
        assertThrows(IllegalStateException.class, () -> Blockchain.forNode("A", keysDir));   // pas de clés générées
    }

    @Test
    void observerNeedsOnlyPublicKeysAndCannotCreateBlocks() throws Exception {
        KeyGenerator.generateAll(keysDir, false);
        Blockchain nodeA = Blockchain.forNode("A", keysDir);
        Block b = nodeA.addBlock("A", 40, 160);

        // les clés privées ne sont pas disponibles pour le dashboard
        for (String id : new String[] {"A", "B", "C"}) {
            Files.delete(keysDir.resolve(id + ".key"));
        }
        Blockchain observer = Blockchain.observer(keysDir);

        assertThrows(IllegalStateException.class, () -> observer.addBlock("A", 10, 150));
        observer.acceptBlock(b);
        assertTrue(observer.validate());
        assertEquals(2, observer.getChain().size());

        // même hash de genesis partout
        assertEquals(HashValidator.computeHash(0, 0L, "GENESIS", 0, 0, "0"),
                observer.getChain().get(0).getHash());
    }

    @Test
    void signatureWithGeneratedPairVerifiesOnlyWithItsOwnKey() {
        KeyPair one = KeyGenerator.generateKeyPair();
        KeyPair two = KeyGenerator.generateKeyPair();
        Block b = Block.create(1, 1L, "A", 10, 190, Block.genesis().getHash(), one.getPrivate());
        assertTrue(BlockSigner.verify(b, one.getPublic()));
        assertFalse(BlockSigner.verify(b, two.getPublic()));
        assertFalse(BlockSigner.verify(b, null));
        assertFalse(BlockSigner.verify(null, one.getPublic()));
    }
}
