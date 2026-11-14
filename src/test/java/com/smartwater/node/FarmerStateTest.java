package com.smartwater.node;

import com.smartwater.Config;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** @author Houssine Ferjani (Binôme 2) */
class FarmerStateTest {

    @Test
    void valeursParDefautDuContrat() {
        FarmerState s = new FarmerState("A");
        assertEquals(Config.QUOTA_INITIAL, s.getQuotaRemaining());
        assertEquals(FarmerState.DEFAULT_THRESHOLD, s.getThreshold());
        assertEquals(FarmerState.DEFAULT_TARGET, s.getTarget());
    }

    @Test
    void demandeSeulementSiSolSecEtQuotaDisponible() {
        FarmerState s = new FarmerState("A", 200, 30, 60);
        assertTrue(s.shouldRequest(29.9));
        assertFalse(s.shouldRequest(30.0), "au seuil exact : humidité suffisante");
        assertFalse(s.shouldRequest(55));

        FarmerState empty = new FarmerState("B", 0, 30, 60);
        assertFalse(empty.shouldRequest(0), "quota épuisé : jamais de REQUEST");
    }

    @Test
    void irrigationMetAJourLesCompteurs() {
        FarmerState s = new FarmerState("A", 200, 30, 60);
        s.recordRequest();
        s.recordIrrigation(35, 165);
        s.recordRequest();
        s.recordIrrigation(165, 0);
        assertEquals(0, s.getQuotaRemaining());
        assertEquals(2, s.getRequests());
        assertEquals(2, s.getIrrigations());
        assertEquals(200, s.getLitersReceived());
        assertFalse(s.shouldRequest(0));
    }

    @Test
    void refusEtRienAttribueNeTouchentPasAuQuota() {
        FarmerState s = new FarmerState("A", 200, 30, 60);
        s.recordRefused();
        s.recordNothingAllocated();
        assertEquals(200, s.getQuotaRemaining());
        assertEquals(0, s.getLitersReceived());
        assertEquals(1, s.getRefused());
        assertEquals(1, s.getNothingAllocated());
    }

    @Test
    void syncQuotaNeFaitQueBaisser() {
        FarmerState s = new FarmerState("A", 200, 30, 60);
        s.syncQuota(150);
        assertEquals(150, s.getQuotaRemaining());
        s.syncQuota(180);
        assertEquals(150, s.getQuotaRemaining(), "le quota ne remonte jamais");
        s.syncQuota(-5);
        assertEquals(150, s.getQuotaRemaining());
    }

    @Test
    void parametresInvalides() {
        assertThrows(IllegalArgumentException.class, () -> new FarmerState("A", -1, 30, 60));
        assertThrows(IllegalArgumentException.class, () -> new FarmerState("A", 200, 60, 30));
        assertThrows(IllegalArgumentException.class, () -> new FarmerState("A", 200, 30, 120));
        assertThrows(NullPointerException.class, () -> new FarmerState(null));
        FarmerState s = new FarmerState("A");
        assertThrows(IllegalArgumentException.class, () -> s.recordIrrigation(0, 200));
        assertThrows(IllegalArgumentException.class, () -> s.recordIrrigation(10, -1));
    }
}
