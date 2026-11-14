/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.smartwater.snapshot;

/**
 *
 * @author nader
 */
import com.smartwater.communication.Message;

public interface SnapshotService {
    void startSnapshot();             // initiateur
    void onMessage(Message m);        // appelé par MessageHandler pour enregistrer les canaux
    boolean isComplete();
    // GlobalSnapshot getResult();    // à ajouter en partie 4, quand GlobalSnapshot existera
}