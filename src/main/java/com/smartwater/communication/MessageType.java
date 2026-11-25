package com.smartwater.communication;

// Les 10 types de messages
public enum MessageType {
    // Accès à la pompe (Ricart-Agrawala : 2(N − 1) messages par entrée, pas de RELEASE)
    REQUEST,
    REPLY,
    // Supervision, HORS algorithme
    PUMP_RELEASED,
    // Snapshot (Chandy-Lamport)
    MARKER,
    // Instantané local d'un nœud, diffusé quand il est terminé (pour construire l'état global)
    SNAPSHOT_STATE,
    // Registre
    IRRIGATION_EVENT,
    LEDGER_UPDATE,
    // Rattrapage : « envoyez-moi les blocs à partir de l'index N »
    LEDGER_REQUEST,
    // Rattrapage : réponse à un LEDGER_REQUEST, contient les blocs demandés
    LEDGER_SYNC,
    // Balise d'état (supervision)
    NODE_STATUS
}
