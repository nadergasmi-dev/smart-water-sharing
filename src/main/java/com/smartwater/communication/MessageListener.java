/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.smartwater.communication;

/**
 *
 * @author nader
 */
public interface MessageListener {
    /**
     * Appelé à la réception, APRÈS update() de l'horloge de Lamport.
     * Jamais appelé pour les propres messages du nœud.
     */
    void onMessage(Message m);
}