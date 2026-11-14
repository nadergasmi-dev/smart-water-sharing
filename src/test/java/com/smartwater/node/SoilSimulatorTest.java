package com.smartwater.node;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** @author Houssine Ferjani (Binôme 2) */
class SoilSimulatorTest {

    static SoilSimulator exact(double initial, double rate) {
        return new SoilSimulator(initial, rate, 0, 1.0, new Random(0));
    }

    @Test
    void sansAleaLeSolBaisseExactementDuTaux() {
        SoilSimulator s = exact(50, 5);
        assertEquals(45, s.dry(), 1e-9);
        assertEquals(40, s.dry(), 1e-9);
        assertEquals(40, s.getHumidity(), 1e-9);
    }

    @Test
    void avecAleaLaBaisseResteDansLIntervalleEtNeRemonteJamais() {
        SoilSimulator s = new SoilSimulator(100, 3, 1.5, 1.0, new Random(42));
        double previous = s.getHumidity();
        for (int i = 0; i < 30; i++) {
            double h = s.dry();
            double drop = previous - h;
            if (h > 0) {
                assertTrue(drop >= 1.5 - 1e-9 && drop <= 4.5 + 1e-9, "baisse hors intervalle : " + drop);
            }
            assertTrue(h <= previous, "le sol s'est humidifié tout seul");
            previous = h;
        }
    }

    @Test
    void memeGraineMemeSimulation() {
        SoilSimulator a = new SoilSimulator(7);
        SoilSimulator b = new SoilSimulator(7);
        for (int i = 0; i < 10; i++) {
            assertEquals(a.dry(), b.dry(), 0.0);
        }
    }

    @Test
    void humiditeBorneeEntre0Et100() {
        SoilSimulator s = exact(4, 10);
        assertEquals(0, s.dry(), 1e-9);
        assertEquals(0, s.dry(), 1e-9);
        assertEquals(100, s.irrigate(500), 1e-9);
        assertEquals(100, exact(150, 1).getHumidity(), 1e-9);
    }

    @Test
    void irrigationEtBesoinEnEau() {
        SoilSimulator s = new SoilSimulator(25, 1, 0, 2.0, new Random(0));   // 2 points par litre
        assertEquals(18, s.litersToReach(60));                              // 35 / 2 = 17,5 → 18
        assertEquals(61, s.irrigate(18), 1e-9);
        assertEquals(0, s.litersToReach(60));
        assertEquals(0, s.irrigate(0) - 61, 1e-9);
        assertThrows(IllegalArgumentException.class, () -> s.irrigate(-1));
    }

    @Test
    void besoinExactSansErreurDArrondi() {
        SoilSimulator s = exact(30, 1);
        assertEquals(30, s.litersToReach(60));
    }

    @Test
    void parametresInvalides() {
        Random r = new Random(0);
        assertThrows(IllegalArgumentException.class, () -> new SoilSimulator(50, 1, 2, 1, r));   // noise > rate
        assertThrows(IllegalArgumentException.class, () -> new SoilSimulator(50, -1, 0, 1, r));
        assertThrows(IllegalArgumentException.class, () -> new SoilSimulator(50, 1, 0, 0, r));
        assertThrows(NullPointerException.class, () -> new SoilSimulator(50, 1, 0, 1, null));
    }
}
