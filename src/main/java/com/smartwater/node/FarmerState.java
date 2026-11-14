package com.smartwater.node;

import com.smartwater.Config;

import java.util.Objects;

/**
 * État « métier » d'un agriculteur : son quota restant, ses seuils d'humidité et ses compteurs.
 *
 * <p><b>Règle de déclenchement (contrat, section 9) :</b> un agriculteur ne diffuse un {@code REQUEST}
 * que si l'humidité est <b>sous le seuil</b> {@code threshold} <b>et</b> qu'il lui reste du quota
 * ({@link #shouldRequest(double)}). Il demande alors de quoi remonter jusqu'à {@code target}.</p>
 *
 * <p>Les compteurs (quota, litres reçus) ne sont modifiés que par {@link #recordIrrigation(int, int)},
 * appelé <b>après</b> le succès de {@code Ledger.addBlock} : un bloc refusé ne touche à rien.
 * Le quota reste aligné sur la chaîne, qui fait foi ({@link #syncQuota(int)}).</p>
 *
 * @author Houssine Ferjani (Binôme 2)
 */
public class FarmerState {

    /** Seuil d'humidité sous lequel l'agriculteur demande la pompe (%) : point ouvert du contrat. */
    public static final double DEFAULT_THRESHOLD = 30.0;
    /** Humidité visée après irrigation (%). */
    public static final double DEFAULT_TARGET = 60.0;

    private final String nodeId;
    private final double threshold;
    private final double target;

    private int quotaRemaining;
    private int requests;
    private int irrigations;
    private int litersReceived;
    private int nothingAllocated;
    private int refused;

    /** Agriculteur avec le quota initial du contrat (200 L) et les seuils par défaut. */
    public FarmerState(String nodeId) {
        this(nodeId, Config.QUOTA_INITIAL, DEFAULT_THRESHOLD, DEFAULT_TARGET);
    }

    public FarmerState(String nodeId, int initialQuota, double threshold, double target) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        if (initialQuota < 0) {
            throw new IllegalArgumentException("quota initial < 0");
        }
        if (!(0 <= threshold && threshold < target && target <= 100)) {
            throw new IllegalArgumentException("il faut 0 <= threshold < target <= 100");
        }
        this.quotaRemaining = initialQuota;
        this.threshold = threshold;
        this.target = target;
    }

    /** Humidité insuffisante ET quota disponible : seul cas où l'agriculteur envoie un REQUEST. */
    public synchronized boolean shouldRequest(double humidity) {
        return humidity < threshold && quotaRemaining > 0;
    }

    /** Une demande de pompe va partir (statistique). */
    public synchronized void recordRequest() {
        requests++;
    }

    /**
     * Irrigation acceptée dans le registre : met à jour les compteurs.
     *
     * @param liters         litres reçus (bloc accepté)
     * @param quotaAfter     quota restant inscrit dans le bloc
     */
    public synchronized void recordIrrigation(int liters, int quotaAfter) {
        if (liters <= 0 || quotaAfter < 0) {
            throw new IllegalArgumentException("irrigation invalide : " + liters + " L, quota " + quotaAfter);
        }
        irrigations++;
        litersReceived += liters;
        quotaRemaining = quotaAfter;
    }

    public synchronized void recordNothingAllocated() {
        nothingAllocated++;
    }

    public synchronized void recordRefused() {
        refused++;
    }

    /**
     * Réaligne le quota sur la valeur recalculée depuis la chaîne (par exemple après un rattrapage du
     * registre au démarrage). Le quota ne peut que baisser : une valeur plus haute est ignorée.
     */
    public synchronized void syncQuota(int quotaFromLedger) {
        if (quotaFromLedger >= 0 && quotaFromLedger < quotaRemaining) {
            quotaRemaining = quotaFromLedger;
        }
    }

    public String getNodeId()                     { return nodeId; }
    public double getThreshold()                  { return threshold; }
    public double getTarget()                     { return target; }
    public synchronized int getQuotaRemaining()   { return quotaRemaining; }
    public synchronized int getRequests()         { return requests; }
    public synchronized int getIrrigations()      { return irrigations; }
    public synchronized int getLitersReceived()   { return litersReceived; }
    public synchronized int getNothingAllocated() { return nothingAllocated; }
    public synchronized int getRefused()          { return refused; }

    @Override
    public synchronized String toString() {
        return "FarmerState[" + nodeId + ", quota=" + quotaRemaining + " L, demandes=" + requests
                + ", irrigations=" + irrigations + ", reçu=" + litersReceived + " L]";
    }
}
