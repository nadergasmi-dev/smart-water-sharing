package com.smartwater.blockchain;

import com.smartwater.Config;
import com.smartwater.security.KeyGenerator;

import java.security.KeyPair;
import java.util.Map;

/**
 * Démonstration en console : un bloc valide est accepté, chaque tentative de triche est refusée
 * avec la règle violée. Les clés sont générées en mémoire (aucun fichier).
 * À lancer depuis l'IDE (méthode main).
 */
public class SecurityDemo {

    public static void main(String[] args) throws Exception {
        KeyPair a = KeyGenerator.generateKeyPair();
        KeyPair b = KeyGenerator.generateKeyPair();
        KeyPair c = KeyGenerator.generateKeyPair();
        Map<String, java.security.PublicKey> pub = Map.of("A", a.getPublic(), "B", b.getPublic(), "C", c.getPublic());

        // Chaque nœud a son registre ; ici on suit celui de A (il signe avec la clé de A).
        Blockchain ledger = new Blockchain(a.getPrivate(), pub, Config.initialQuotas(), Config.RESERVOIR_INITIAL);

        System.out.println("--- Cas normal ---");
        try0(ledger, "A irrigue 40 L (quota 200 -> 160)", () -> ledger.addBlock("A", 40, 160));

        System.out.println("\n--- Tentatives de triche ---");
        // V6 : C demande 250 L alors que son quota est de 200 L
        Block last = ledger.getLastBlock();
        try0(ledger, "C dépasse son quota (250 L)", () -> {
            ledger.acceptBlock(Block.create(2, now(), "C", 250, -50, last.getHash(), c.getPrivate()));
            return null;
        });
        // V6 : C annonce un faux quota restant (200 au lieu de 170)
        try0(ledger, "C falsifie son quota restant", () -> {
            ledger.acceptBlock(Block.create(2, now(), "C", 30, 200, last.getHash(), c.getPrivate()));
            return null;
        });
        // V4 : A crée un bloc au nom de B (signé avec la clé de A)
        try0(ledger, "A se fait passer pour B", () -> ledger.addBlock("B", 50, 150));
        // V1 : bloc de B modifié en route (50 -> 5 L) sans recalculer le hash
        Block okB = Block.create(2, now(), "B", 50, 150, last.getHash(), b.getPrivate());
        Block tampered = new Block(okB.getIndex(), okB.getTimestamp(), "B", 5, okB.getQuotaRemaining(),
                okB.getPreviousHash(), okB.getHash(), okB.getSignature());
        try0(ledger, "Bloc modifié en transit (50 -> 5 L)", () -> {
            ledger.acceptBlock(tampered);
            return null;
        });
        // V2 : rejeu du bloc de A déjà enregistré
        try0(ledger, "Rejeu du bloc de A", () -> {
            ledger.acceptBlock(ledger.getChain().get(1));
            return null;
        });
        // V3 : nœud inconnu
        try0(ledger, "Nœud inconnu « D »", () -> ledger.addBlock("D", 10, 0));
        // V5 : quantité nulle
        try0(ledger, "Quantité nulle (0 L)", () -> ledger.addBlock("A", 0, 160));
        // V7 : plus que le réservoir. Avec les valeurs du contrat (réservoir 1000 L, 3 x 200 L de quota)
        // le quota bloque toujours avant ; on montre donc V7 sur un registre à réservoir réduit (100 L).
        Blockchain smallReservoir = new Blockchain(a.getPrivate(), pub, Config.initialQuotas(), 100);
        try0(smallReservoir, "Réservoir de 100 L : A demande 150 L (quota OK)", () -> smallReservoir.addBlock("A", 150, 50));

        System.out.println("\n--- Le vrai bloc de B passe ---");
        try0(ledger, "Bloc valide B, 50 L", () -> {
            ledger.acceptBlock(okB);
            return okB;
        });

        System.out.println("\nChaîne valide : " + ledger.validate());
        System.out.println("Réservoir restant : " + ledger.getReservoirRemaining() + " L");
        ledger.getChain().forEach(blk -> System.out.println("  " + blk));
        System.out.println("Blocs refusés : " + ledger.getRejected().size());
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private interface Action {
        Object run() throws InvalidBlockException;
    }

    private static void try0(Blockchain ledger, String label, Action action) {
        try {
            action.run();
            System.out.println("[ACCEPTÉ] " + label);
        } catch (InvalidBlockException e) {
            System.out.println("[REFUSÉ]  " + label + " -> " + e.getMessage());
        }
    }
}
