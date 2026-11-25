package com.smartwater.irrigation;

import com.smartwater.integration.IrrigationSequence;

/**
 * Règle d'allocation de l'eau
 * @author Houssine Ferjani (Binôme 2)
 */
public final class WaterAllocator {

    private WaterAllocator() { }

    public static int allocate(int need, int quotaRemaining, int reservoirRemaining) {
        return Math.max(0, Math.min(need, Math.min(quotaRemaining, reservoirRemaining)));
    }

    // L'allocation à passer à irrigate, pour un besoin donné
    public static IrrigationSequence.Allocation forNeed(int need) {
        return (quota, reservoir) -> allocate(need, quota, reservoir);
    }
}
