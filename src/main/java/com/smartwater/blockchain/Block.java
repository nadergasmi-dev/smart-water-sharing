package com.smartwater.blockchain;

import com.smartwater.security.BlockSigner;

import java.security.PrivateKey;

/**
 * Bloc immuable : aucun setter, tous les champs sont final (contrat, section 6.1).
 * Le hash couvre les 6 champs de données ; la signature couvre le hash.
 * Sérialisable tel quel avec Gson (champ {@code signature} inclus, pour {@code LEDGER_UPDATE}).
 */
public final class Block {

    private final int index;
    private final long timestamp;
    private final String nodeId;
    private final int liters;
    private final int quotaRemaining;
    private final String previousHash;
    private final String hash;
    private final String signature;   // Base64 ; null pour le Genesis

    /** Constructeur complet (utilisé aussi pour un bloc reçu via MQTT). */
    public Block(int index, long timestamp, String nodeId, int liters,
                 int quotaRemaining, String previousHash, String hash, String signature) {
        this.index = index;
        this.timestamp = timestamp;
        this.nodeId = nodeId;
        this.liters = liters;
        this.quotaRemaining = quotaRemaining;
        this.previousHash = previousHash;
        this.hash = hash;
        this.signature = signature;
    }

    /** Crée un nouveau bloc local : calcule le hash puis le signe avec la clé privée du nœud. */
    public static Block create(int index, long timestamp, String nodeId, int liters,
                               int quotaRemaining, String previousHash, PrivateKey privateKey) {
        String h = HashValidator.computeHash(index, timestamp, nodeId, liters, quotaRemaining, previousHash);
        String sig = BlockSigner.sign(h, privateKey);
        return new Block(index, timestamp, nodeId, liters, quotaRemaining, previousHash, h, sig);
    }

    /** Bloc Genesis : identique sur A, B et C, non signé. */
    public static Block genesis() {
        String h = HashValidator.computeHash(0, 0L, "GENESIS", 0, 0, "0");
        return new Block(0, 0L, "GENESIS", 0, 0, "0", h, null);
    }

    public int getIndex()            { return index; }
    public long getTimestamp()       { return timestamp; }
    public String getNodeId()        { return nodeId; }
    public int getLiters()           { return liters; }
    public int getQuotaRemaining()   { return quotaRemaining; }
    public String getPreviousHash()  { return previousHash; }
    public String getHash()          { return hash; }
    public String getSignature()     { return signature; }

    /** Début d'un hash pour les logs ; tolère un hash absent (bloc mal formé reçu du réseau). */
    static String shortHash(String h) {
        if (h == null) return "null";
        return h.length() > 8 ? h.substring(0, 8) + "..." : h;
    }

    @Override
    public String toString() {
        return "Block#" + index + " [" + nodeId + ", " + liters + " L, quota=" + quotaRemaining
                + ", hash=" + shortHash(hash) + "]";
    }
}
