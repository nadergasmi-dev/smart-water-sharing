package com.smartwater.algorithms;

import java.util.Objects;

/**
 * Horloge logique de Lamport d'un nœud
 * @author Houssine Ferjani (Binôme 2)
 */
public class LamportClock {

    // Valeur courante de l'horloge ; 0 tant qu'aucun événement n'a eu lieu
    private int counter = 0;

    // Événement local ou envoi d'un message : incrémente l'horloge
    public synchronized int tick() {
        return ++counter;
    }

    // Réception d'un message
    public synchronized int update(int received) {
        counter = Math.max(counter, received) + 1;
        return counter;
    }

    public synchronized int get() {
        return counter;
    }

    // Ordre total des demandes
    public static int compare(int tsA, String idA, int tsB, String idB) {
        Objects.requireNonNull(idA, "idA");
        Objects.requireNonNull(idB, "idB");
        if (tsA != tsB) {
            return Integer.compare(tsA, tsB);
        }
        return idA.compareTo(idB);
    }

    @Override
    public synchronized String toString() {
        return "LamportClock[" + counter + "]";
    }
}
