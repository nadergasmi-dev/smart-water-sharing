package com.smartwater.node;

import java.util.Random;

/**
 * Humidité simulée de la parcelle d'un agriculteur, en pourcentage (0 à 100).
 *
 * <ul>
 *   <li>À chaque pas de temps ({@link #dry()}), le sol s'assèche : l'humidité baisse de
 *       {@code dryingRate} points, plus ou moins un aléa d'au plus {@code noise} points
 *       (évaporation plus ou moins forte selon la météo).</li>
 *   <li>Une irrigation de {@code n} litres ({@link #irrigate(int)}) la fait remonter de
 *       {@code n × gainPerLiter} points.</li>
 * </ul>
 *
 * <p>Le générateur aléatoire est injecté : avec une graine fixe, la simulation est reproductible
 * (tests, démonstration). Les méthodes sont {@code synchronized} : l'humidité est modifiée par le
 * thread de l'agriculteur et lue par Chandy-Lamport (thread du {@code MessageHandler}).</p>
 *
 * @author Houssine Ferjani (Binôme 2)
 */
public class SoilSimulator {

    /** Humidité de départ par défaut (%). */
    public static final double DEFAULT_INITIAL = 55.0;
    /** Baisse moyenne de l'humidité par pas de temps (points de %). */
    public static final double DEFAULT_DRYING_RATE = 3.0;
    /** Aléa maximal autour de la baisse moyenne (points de %). */
    public static final double DEFAULT_NOISE = 1.5;
    /** Hausse de l'humidité par litre reçu (points de %). */
    public static final double DEFAULT_GAIN_PER_LITER = 1.0;

    private final double dryingRate;
    private final double noise;
    private final double gainPerLiter;
    private final Random random;
    private double humidity;

    /** Sol avec les valeurs par défaut et une graine donnée (une graine différente par agriculteur). */
    public SoilSimulator(long seed) {
        this(DEFAULT_INITIAL, DEFAULT_DRYING_RATE, DEFAULT_NOISE, DEFAULT_GAIN_PER_LITER, new Random(seed));
    }

    public SoilSimulator(double initialHumidity, double dryingRate, double noise, double gainPerLiter,
                         Random random) {
        if (dryingRate < 0 || noise < 0 || noise > dryingRate) {
            throw new IllegalArgumentException("il faut 0 <= noise <= dryingRate (le sol ne s'humidifie pas seul)");
        }
        if (gainPerLiter <= 0) {
            throw new IllegalArgumentException("gainPerLiter doit être > 0");
        }
        this.dryingRate = dryingRate;
        this.noise = noise;
        this.gainPerLiter = gainPerLiter;
        this.random = java.util.Objects.requireNonNull(random, "random");
        this.humidity = clamp(initialHumidity);
    }

    /** Un pas de temps : le sol s'assèche. @return la nouvelle humidité */
    public synchronized double dry() {
        double delta = dryingRate + (noise == 0 ? 0 : (random.nextDouble() * 2 - 1) * noise);
        humidity = clamp(humidity - delta);
        return humidity;
    }

    /** Le sol reçoit {@code liters} litres. @return la nouvelle humidité */
    public synchronized double irrigate(int liters) {
        if (liters < 0) {
            throw new IllegalArgumentException("liters < 0 : " + liters);
        }
        humidity = clamp(humidity + liters * gainPerLiter);
        return humidity;
    }

    public synchronized double getHumidity() {
        return humidity;
    }

    /**
     * Besoin en eau pour atteindre {@code target} % : nombre de litres arrondi au-dessus, 0 si le sol
     * est déjà assez humide.
     */
    public synchronized int litersToReach(double target) {
        double missing = clamp(target) - humidity;
        return missing <= 0 ? 0 : (int) Math.ceil(missing / gainPerLiter - 1e-9);
    }

    private static double clamp(double h) {
        return Math.max(0.0, Math.min(100.0, h));
    }
}
