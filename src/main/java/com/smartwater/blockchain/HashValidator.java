package com.smartwater.blockchain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

// Calcul et vérification de l'empreinte SHA-256 d'un bloc
public final class HashValidator {

    private HashValidator() { }

    // Les 6 champs concaténés avec "|" (les champs hash et signature n'y entrent jamais)
    public static String content(int index, long timestamp, String nodeId, int liters,
                                 int quotaRemaining, String previousHash) {
        return index + "|" + timestamp + "|" + nodeId + "|" + liters + "|" + quotaRemaining + "|" + previousHash;
    }

    public static String computeHash(int index, long timestamp, String nodeId, int liters,
                                     int quotaRemaining, String previousHash) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content(index, timestamp, nodeId, liters, quotaRemaining, previousHash)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();   // 64 caractères hexa minuscules
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    // Le contenu n'a pas été modifié : le hash recalculé = le hash stocké
    public static boolean hasValidHash(Block b) {
        String recomputed = computeHash(b.getIndex(), b.getTimestamp(), b.getNodeId(),
                b.getLiters(), b.getQuotaRemaining(), b.getPreviousHash());
        return recomputed.equals(b.getHash());
    }

    // index = précédent + 1 ET previousHash = hash du précédent
    public static boolean isLinked(Block previous, Block current) {
        return current.getIndex() == previous.getIndex() + 1
                && Objects.equals(current.getPreviousHash(), previous.getHash());
    }
}
