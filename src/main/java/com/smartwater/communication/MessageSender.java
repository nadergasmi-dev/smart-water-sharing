/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.smartwater.communication;

/**
 *
 * @author nader
 */
public interface MessageSender {
    /**
     * Fait tick() sur l'horloge de Lamport, remplit lamport / msgId / from,
     * puis publie le message sur le bon topic.
     *
     * @return le timestamp Lamport utilisé pour cet envoi
     */
    int publish(Message m);
}
