/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.smartwater.algorithms;

/**
 *
 * @author nader
 */
/**
 * MOCK temporaire écrit par Binôme 1.
 * À remplacer par la version de Binôme 2 (même signature, contrat section 7).
 */
public class LamportClock {
    private int counter = 0;

    /** Événement local ou envoi : compteur + 1. */
    public synchronized int tick() {
        return ++counter;
    }

    /** Réception : max(local, reçu) + 1. */
    public synchronized int update(int received) {
        counter = Math.max(counter, received) + 1;
        return counter;
    }

    public synchronized int get() {
        return counter;
    }

    /** Négatif si (tsA, idA) est prioritaire sur (tsB, idB). */
    public static int compare(int tsA, String idA, int tsB, String idB) {
        if (tsA != tsB) {
            return Integer.compare(tsA, tsB);
        }
        return idA.compareTo(idB);
    }
}