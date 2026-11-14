package com.smartwater.security;

import com.smartwater.Config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Clés de signature des nœuds (contrat, section 1.1).
 *
 * <pre>
 * keys/A.pub  clé publique  (X.509, Base64)   lue par tous
 * keys/A.key  clé privée    (PKCS#8, Base64)  lue uniquement par le nœud A (jamais commitée)
 * </pre>
 *
 * À lancer une seule fois avant le premier démarrage : {@code KeyGenerator}.
 * Les clés existantes ne sont jamais écrasées, sauf avec {@code --force}.
 */
public final class KeyGenerator {

    private static final String CURVE = "secp256r1";

    private KeyGenerator() { }

    // ---------------------------------------------------------------- génération

    /** Génère keys/{A,B,C}.pub et keys/{A,B,C}.key. {@code --force} régénère les clés existantes. */
    public static void main(String[] args) {
        boolean force = Arrays.asList(args).contains("--force");
        Path dir = Path.of(Config.KEYS_DIR);
        try {
            int generated = generateAll(dir, force);
            System.out.println(generated + " paire(s) de clés générée(s) dans " + dir.toAbsolutePath());
        } catch (IllegalStateException | IOException e) {
            System.err.println("Échec : " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Génère les clés de tous les nœuds dans {@code dir}.
     *
     * @return le nombre de paires générées (les paires déjà présentes sont conservées sauf si {@code force})
     * @throws IllegalStateException si un nœud n'a que la moitié de sa paire (état incohérent) sans {@code force}
     */
    public static int generateAll(Path dir, boolean force) throws IOException {
        Files.createDirectories(dir);
        int generated = 0;
        for (String id : Config.NODE_IDS) {
            boolean hasPub = Files.exists(publicKeyFile(dir, id));
            boolean hasKey = Files.exists(privateKeyFile(dir, id));
            if (!force) {
                if (hasPub && hasKey) {
                    System.out.println("[" + id + "] clés déjà présentes, conservées (--force pour régénérer)");
                    continue;
                }
                if (hasPub != hasKey) {
                    throw new IllegalStateException("Clés de " + id + " incohérentes dans " + dir
                            + " (un seul des deux fichiers existe) : relancer avec --force");
                }
            }
            KeyPair pair = generateKeyPair();
            write(publicKeyFile(dir, id), pair.getPublic().getEncoded(), false);
            write(privateKeyFile(dir, id), pair.getPrivate().getEncoded(), true);
            System.out.println("[" + id + "] clés générées");
            generated++;
        }
        return generated;
    }

    /** Nouvelle paire ECDSA secp256r1 (utilisée aussi par les tests). */
    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec(CURVE));
            return gen.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Impossible de générer les clés", e);
        }
    }

    // ---------------------------------------------------------------- chargement

    public static PublicKey loadPublicKey(String nodeId) {
        return loadPublicKey(Path.of(Config.KEYS_DIR), nodeId);
    }

    public static PrivateKey loadPrivateKey(String nodeId) {
        return loadPrivateKey(Path.of(Config.KEYS_DIR), nodeId);
    }

    public static PublicKey loadPublicKey(Path dir, String nodeId) {
        byte[] der = read(publicKeyFile(dir, nodeId));
        try {
            return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Clé publique illisible pour " + nodeId + " : " + e.getMessage(), e);
        }
    }

    public static PrivateKey loadPrivateKey(Path dir, String nodeId) {
        byte[] der = read(privateKeyFile(dir, nodeId));
        try {
            return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Clé privée illisible pour " + nodeId + " : " + e.getMessage(), e);
        }
    }

    /** Les clés publiques de tous les nœuds (A, B, C) : ce dont un nœud ou le dashboard a besoin. */
    public static Map<String, PublicKey> loadAllPublicKeys(Path dir) {
        Map<String, PublicKey> keys = new LinkedHashMap<>();
        for (String id : Config.NODE_IDS) {
            keys.put(id, loadPublicKey(dir, id));
        }
        return keys;
    }

    // ---------------------------------------------------------------- fichiers

    private static Path publicKeyFile(Path dir, String nodeId) {
        return dir.resolve(checkNodeId(nodeId) + ".pub");
    }

    private static Path privateKeyFile(Path dir, String nodeId) {
        return dir.resolve(checkNodeId(nodeId) + ".key");
    }

    /** Refuse tout identifiant hors A/B/C (empêche aussi un chemin du type « ../x »). */
    private static String checkNodeId(String nodeId) {
        if (!Config.NODE_IDS.contains(nodeId)) {
            throw new IllegalArgumentException("Nœud inconnu : " + nodeId);
        }
        return nodeId;
    }

    private static void write(Path file, byte[] der, boolean secret) throws IOException {
        Files.writeString(file, Base64.getEncoder().encodeToString(der) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        if (secret) {
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException | IOException ignored) {
                // Windows : pas de permissions POSIX
            }
        }
    }

    private static byte[] read(Path file) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8).trim();
            return Base64.getDecoder().decode(text);
        } catch (IOException e) {
            throw new IllegalStateException("Clé introuvable ou illisible : " + file
                    + " (lancer KeyGenerator une fois)", e);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Fichier de clé mal formé (Base64 invalide) : " + file, e);
        }
    }
}
