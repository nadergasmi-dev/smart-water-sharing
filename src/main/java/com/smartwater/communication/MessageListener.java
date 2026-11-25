package com.smartwater.communication;

/**
 * @author nader
 */
public interface MessageListener {
    // Appelé à la réception, APRÈS update() de l'horloge de Lamport
    void onMessage(Message m);
}