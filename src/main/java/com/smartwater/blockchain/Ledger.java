package com.smartwater.blockchain;

import java.util.List;

// Registre d'irrigation
public interface Ledger {

    // Crée le bloc, le signe avec la clé du nœud local, le valide (V1 à V7) puis l'ajoute
    Block addBlock(String nodeId, int liters, int quotaRemaining) throws InvalidBlockException;

    // true si toute la chaîne est valide (V1 à V4)
    boolean validate();

    // Copie en lecture seule de la chaîne (genesis inclus)
    List<Block> getChain();

    // Copie en lecture seule des blocs refusés, avec la raison (affichés par le dashboard)
    List<RejectedBlock> getRejected();
}
