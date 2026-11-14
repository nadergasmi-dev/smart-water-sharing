package com.smartwater.communication;

/** Les 9 types de messages (contrat, section 4). */
public enum MessageType {
    // Accès à la pompe (Ricart-Agrawala)
    REQUEST,
    REPLY,
    RELEASE,
    // Snapshot (Chandy-Lamport)
    MARKER,
    /** Instantané local d'un nœud, diffusé quand il est terminé (pour construire l'état global). */
    SNAPSHOT_STATE,
    // Registre
    IRRIGATION_EVENT,
    LEDGER_UPDATE,
    /** Rattrapage : « envoyez-moi les blocs à partir de l'index N ». */
    LEDGER_REQUEST,
    /** Rattrapage : réponse à un LEDGER_REQUEST, contient les blocs demandés. */
    LEDGER_SYNC
}
