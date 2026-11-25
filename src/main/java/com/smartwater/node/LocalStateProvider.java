package com.smartwater.node;

// Fournit l'état local d'un nœud à Chandy-Lamport
public interface LocalStateProvider {

    // Copie cohérente de l'état local : humidité, quota restant, état de la pompe, demandes différées
    LocalState getLocalState();
}
