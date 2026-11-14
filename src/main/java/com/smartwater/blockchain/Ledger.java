package com.smartwater.blockchain;

import java.util.List;

/**
 * Registre d'irrigation (contrat, section 7). Binôme 1 le fournit, Binôme 2 l'utilise.
 * Obtention : {@link Blockchain#forNode(String)} pour un nœud, {@link Blockchain#observer()} pour le dashboard.
 */
public interface Ledger {

    /**
     * Crée le bloc, le signe avec la clé du nœud local, le valide (V1 à V7) puis l'ajoute.
     * Si une règle échoue : lève {@link InvalidBlockException} et la chaîne n'est PAS modifiée
     * (le refus est enregistré dans {@link #getRejected()}).
     */
    Block addBlock(String nodeId, int liters, int quotaRemaining) throws InvalidBlockException;

    /** true si toute la chaîne est valide (V1 à V4). */
    boolean validate();

    /** Copie en lecture seule de la chaîne (genesis inclus). */
    List<Block> getChain();

    /** Copie en lecture seule des blocs refusés, avec la raison (affichés par le dashboard). */
    List<RejectedBlock> getRejected();
}
