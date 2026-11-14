package com.smartwater.irrigation;

import com.smartwater.integration.IrrigationSequence;

/**
 * Règle d'allocation de l'eau (contrat, section 9) :
 *
 * <pre>attribué = min(besoin, quotaRestant, réservoirDisponible)</pre>
 *
 * <p>Le calcul se fait <b>dans la section critique</b> : {@link IrrigationSequence} appelle l'allocation
 * après {@code requestPump()}, avec le quota et le réservoir recalculés à partir de la chaîne. Deux nœuds
 * ne peuvent donc jamais se voir attribuer le même volume.</p>
 *
 * @author Houssine Ferjani (Binôme 2)
 */
public final class WaterAllocator {

    private WaterAllocator() { }

    /**
     * @return {@code min(need, quotaRemaining, reservoirRemaining)}, jamais négatif : une valeur négative
     *         (registre incohérent) donne 0, donc pas d'irrigation ni de bloc
     */
    public static int allocate(int need, int quotaRemaining, int reservoirRemaining) {
        return Math.max(0, Math.min(need, Math.min(quotaRemaining, reservoirRemaining)));
    }

    /** L'allocation à passer à {@link IrrigationSequence#irrigate}, pour un besoin donné. */
    public static IrrigationSequence.Allocation forNeed(int need) {
        return (quota, reservoir) -> allocate(need, quota, reservoir);
    }
}
