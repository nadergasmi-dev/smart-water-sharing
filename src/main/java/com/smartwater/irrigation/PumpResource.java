package com.smartwater.irrigation;

import com.smartwater.Config;

/**
 * Vue locale de la ressource partagée : le réservoir commun alimenté par la pompe
 * @author Houssine Ferjani (Binôme 2)
 */
public class PumpResource {

    private final int capacity;
    private int level;
    private int ownUses;
    private int ownLiters;
    private String lastUser;

    // Réservoir du contrat : 1000 L
    public PumpResource() {
        this(Config.RESERVOIR_INITIAL);
    }

    public PumpResource(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacité < 0");
        }
        this.capacity = capacity;
        this.level = capacity;
    }

    // Irrigation de ce nœud, acceptée dans le registre
    public synchronized void recordOwnIrrigation(String nodeId, int liters, int levelAfter) {
        if (liters <= 0) {
            throw new IllegalArgumentException("liters <= 0 : " + liters);
        }
        ownUses++;
        ownLiters += liters;
        lastUser = nodeId;
        observeLevel(levelAfter);
    }

    // Irrigation d'un autre nœud (IRRIGATION_EVENT)
    public synchronized void recordOtherIrrigation(String nodeId, int levelAfter) {
        lastUser = nodeId;
        observeLevel(levelAfter);
    }

    // Niveau annoncé (chaîne ou événement) : on garde le plus bas, le réservoir ne remonte jamais
    public synchronized void observeLevel(int announced) {
        if (announced >= 0 && announced < level) {
            level = announced;
        }
    }

    public int getCapacity()                 { return capacity; }
    public synchronized int getLevel()       { return level; }
    public synchronized int getOwnUses()     { return ownUses; }
    public synchronized int getOwnLiters()   { return ownLiters; }
    public synchronized String getLastUser() { return lastUser; }

    @Override
    public synchronized String toString() {
        return "PumpResource[" + level + "/" + capacity + " L, utilisations=" + ownUses
                + ", pompé=" + ownLiters + " L]";
    }
}
