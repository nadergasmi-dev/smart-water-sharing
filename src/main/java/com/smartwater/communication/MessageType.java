package com.smartwater.communication;

/** Les 8 types de messages (contrat, section 4). */
public enum MessageType {
    // Accès à la pompe (Ricart-Agrawala)
    REQUEST,
    REPLY,
    RELEASE,
    // Snapshot (Chandy-Lamport)
    MARKER,
    // Registre
    IRRIGATION_EVENT,
    LEDGER_UPDATE,
    /** Rattrapage : « envoyez-moi les blocs à partir de l'index N ». */
    LEDGER_REQUEST,
    /** Rattrapage : réponse à un LEDGER_REQUEST, contient les blocs demandés. */
    LEDGER_SYNC
}
