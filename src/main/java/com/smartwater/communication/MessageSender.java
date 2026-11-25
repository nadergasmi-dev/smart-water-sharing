package com.smartwater.communication;

/**
 * @author nader
 */
public interface MessageSender {
    // Fait tick() sur l'horloge de Lamport
    int publish(Message m);
}
