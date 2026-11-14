package com.smartwater.node;

/**
 * Fournit l'état local d'un nœud à Chandy-Lamport (contrat, section 7).
 * Implémenté par Binôme 2 (FarmerNode) ; utilisé par Binôme 1 (ChandyLamport).
 */
public interface LocalStateProvider {

    /**
     * Copie cohérente de l'état local : humidité, quota restant, état de la pompe, demandes différées.
     * Appelée sur le thread du MessageHandler, entre deux messages.
     */
    LocalState getLocalState();
}
