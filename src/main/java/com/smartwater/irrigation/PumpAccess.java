package com.smartwater.irrigation;

// Accès exclusif à la pompe
public interface PumpAccess {

    // Bloque jusqu'à l'entrée en section critique
    void requestPump();

    // Sortie de section critique : envoie les REPLY différés
    void releasePump();
}
