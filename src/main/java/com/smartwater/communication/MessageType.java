package com.smartwater.communication;

/** Les 10 types de messages (contrat, section 4). */
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
    LEDGER_SYNC,
    /**
     * Balise d'état (supervision) : chaque nœud diffuse périodiquement son état réel (pompe, humidité, quota,
     * compteurs) et sa chaîne complète. Permet à un observateur en lecture seule (dashboard) lancé en retard
     * de se mettre à jour sans rien publier. Ce n'est PAS un message applicatif : jamais enregistré en transit.
     */
    NODE_STATUS
}
