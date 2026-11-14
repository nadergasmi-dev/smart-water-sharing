package com.smartwater.algorithms;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests de l'horloge de Lamport (Binôme 2, Partie 1).
 *
 * @author Houssine Ferjani (Binôme 2)
 */
class LamportClockTest {

    // ------------------------------------------------------------------ tick / get

    @Test
    void demarreAZero() {
        assertEquals(0, new LamportClock().get());
    }

    @Test
    void tickIncrementeDeUn() {
        LamportClock c = new LamportClock();
        assertEquals(1, c.tick());
        assertEquals(2, c.tick());
        assertEquals(3, c.tick());
        assertEquals(3, c.get());
    }

    @Test
    void getNeModifiePasLHorloge() {
        LamportClock c = new LamportClock();
        c.tick();
        assertEquals(1, c.get());
        assertEquals(1, c.get());
    }

    // ------------------------------------------------------------------ update

    @Test
    void updateAvecValeurPlusGrandeSeRecale() {
        LamportClock c = new LamportClock();
        c.tick();                       // 1
        assertEquals(11, c.update(10)); // max(1, 10) + 1
        assertEquals(11, c.get());
    }

    @Test
    void updateAvecValeurPlusPetiteIncrementeSeulement() {
        LamportClock c = new LamportClock();
        for (int i = 0; i < 7; i++) c.tick(); // 7
        assertEquals(8, c.update(3));         // max(7, 3) + 1
    }

    @Test
    void updateAvecValeurEgaleIncremente() {
        LamportClock c = new LamportClock();
        c.update(4);                    // 5
        assertEquals(6, c.update(5));   // max(5, 5) + 1
    }

    @Test
    void updateMalFormeeNeLevePasDException() {
        LamportClock c = new LamportClock();
        c.tick();                        // 1
        assertEquals(2, c.update(0));
        assertEquals(3, c.update(-42));  // valeur invalide : simple incrément
    }

    /**
     * Propriété de Lamport : si e → e' alors H(e) < H(e').
     * Scénario : A envoie à B, B envoie à C ; chaque réception est datée après l'envoi.
     */
    @Test
    void respecteLaCausaliteSurUneChaineDeMessages() {
        LamportClock a = new LamportClock(), b = new LamportClock(), c = new LamportClock();
        for (int i = 0; i < 5; i++) b.tick();     // B a déjà vécu 5 événements locaux

        int envoiA = a.tick();                    // A → B
        int receptionB = b.update(envoiA);
        assertTrue(envoiA < receptionB);

        int envoiB = b.tick();                    // B → C
        int receptionC = c.update(envoiB);
        assertTrue(receptionB < envoiB);          // ordre local de B
        assertTrue(envoiB < receptionC);
        assertTrue(envoiA < receptionC);          // transitivité
    }

    // ------------------------------------------------------------------ compare

    @Test
    void comparePlusPetiteEstampilleGagne() {
        assertTrue(LamportClock.compare(4, "C", 5, "A") < 0);
        assertTrue(LamportClock.compare(6, "A", 5, "C") > 0);
    }

    @Test
    void compareEgaliteDepartageeParIdentifiant() {
        assertTrue(LamportClock.compare(5, "A", 5, "B") < 0);
        assertTrue(LamportClock.compare(5, "B", 5, "C") < 0);
        assertTrue(LamportClock.compare(5, "C", 5, "A") > 0);
    }

    @Test
    void compareEstAntisymetriqueEtNulSeulementPourLeMemeCouple() {
        String[] ids = {"A", "B", "C"};
        for (int ts1 = 1; ts1 <= 3; ts1++) {
            for (int ts2 = 1; ts2 <= 3; ts2++) {
                for (String id1 : ids) {
                    for (String id2 : ids) {
                        int ab = LamportClock.compare(ts1, id1, ts2, id2);
                        int ba = LamportClock.compare(ts2, id2, ts1, id1);
                        assertEquals(Integer.signum(ab), -Integer.signum(ba));
                        assertEquals(ts1 == ts2 && id1.equals(id2), ab == 0);
                    }
                }
            }
        }
    }

    @Test
    void compareDonneUnOrdreTotalTransitif() {
        // (2,C) < (3,A) < (3,B) < (4,A)
        assertTrue(LamportClock.compare(2, "C", 3, "A") < 0);
        assertTrue(LamportClock.compare(3, "A", 3, "B") < 0);
        assertTrue(LamportClock.compare(3, "B", 4, "A") < 0);
        assertTrue(LamportClock.compare(2, "C", 4, "A") < 0);
    }

    @Test
    void compareRefuseUnIdentifiantNull() {
        assertThrows(NullPointerException.class, () -> LamportClock.compare(1, null, 1, "A"));
        assertThrows(NullPointerException.class, () -> LamportClock.compare(1, "A", 1, null));
    }

    // ------------------------------------------------------------------ concurrence

    /**
     * 8 threads font 10 000 tick() chacun en même temps : aucune valeur ne doit être perdue
     * ni dupliquée (sans synchronized, ce test échoue).
     */
    @Test
    void tickEstThreadSafe() throws Exception {
        LamportClock c = new LamportClock();
        int threads = 8, parThread = 10_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch depart = new CountDownLatch(1);
        try {
            List<Future<List<Integer>>> resultats = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                Callable<List<Integer>> tache = () -> {
                    depart.await();
                    List<Integer> vus = new ArrayList<>(parThread);
                    for (int i = 0; i < parThread; i++) vus.add(c.tick());
                    return vus;
                };
                resultats.add(pool.submit(tache));
            }
            depart.countDown();

            Set<Integer> toutes = new HashSet<>();
            for (Future<List<Integer>> f : resultats) toutes.addAll(f.get(10, TimeUnit.SECONDS));

            assertEquals(threads * parThread, c.get());
            assertEquals(threads * parThread, toutes.size(), "une estampille a été distribuée deux fois");
        } finally {
            pool.shutdownNow();
        }
    }

    /** tick() et update() mélangés sur plusieurs threads : l'horloge ne recule jamais. */
    @Test
    void lHorlogeNeReculeJamaisSousConcurrence() throws Exception {
        LamportClock c = new LamportClock();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Boolean>> resultats = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                final int graine = t;
                resultats.add(pool.submit(() -> {
                    int precedente = 0;
                    for (int i = 0; i < 5_000; i++) {
                        int v = (i + graine) % 3 == 0 ? c.update(i) : c.tick();
                        if (v <= precedente) return false;   // vu par ce thread : strictement croissant
                        precedente = v;
                    }
                    return true;
                }));
            }
            for (Future<Boolean> f : resultats) assertTrue(f.get(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }
}
