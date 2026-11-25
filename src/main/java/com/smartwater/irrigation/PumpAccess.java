package com.smartwater.irrigation;

/**
 * Accès exclusif à la pompe (contrat, section 7). Implémenté par Binôme 2 ({@code RicartAgrawala}),
 * utilisé par la séquence d'irrigation ({@code IrrigationSequence}).
 */
public interface PumpAccess {

    /** Bloque jusqu'à l'entrée en section critique. */
    void requestPump();

    /** Sortie de section critique : envoie les REPLY différés. */
    void releasePump();
}
