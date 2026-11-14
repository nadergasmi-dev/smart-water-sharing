package com.smartwater.snapshot;

import com.smartwater.communication.Message;

/** Snapshot global (Chandy-Lamport), contrat section 7. Implémenté par {@code ChandyLamport}. */
public interface SnapshotService {

    /** Déclenche un snapshot depuis ce nœud (initiateur). */
    void startSnapshot();

    /** Appelé par MessageHandler pour CHAQUE message reçu, avant le module concerné. */
    void onMessage(Message m);

    /** true quand l'instantané local de chaque nœud est connu (état global complet). */
    boolean isComplete();

    /** Dernier état global connu (complet ou en cours) ; null si aucun snapshot n'a eu lieu. */
    GlobalSnapshot getResult();
}
