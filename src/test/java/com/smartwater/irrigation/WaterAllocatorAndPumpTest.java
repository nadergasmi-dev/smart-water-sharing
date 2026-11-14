package com.smartwater.irrigation;

import com.smartwater.Config;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** @author Houssine Ferjani (Binôme 2) */
class WaterAllocatorAndPumpTest {

    @Test
    void allocationEstLeMinimumDesTrois() {
        assertEquals(35, WaterAllocator.allocate(35, 200, 1000));   // limité par le besoin
        assertEquals(20, WaterAllocator.allocate(60, 20, 1000));    // limité par le quota
        assertEquals(15, WaterAllocator.allocate(60, 200, 15));     // limité par le réservoir
        assertEquals(0, WaterAllocator.allocate(60, 0, 1000));
        assertEquals(0, WaterAllocator.allocate(60, 200, 0));
        assertEquals(0, WaterAllocator.allocate(0, 200, 1000));
    }

    @Test
    void jamaisNegatif() {
        assertEquals(0, WaterAllocator.allocate(-10, 200, 1000));
        assertEquals(0, WaterAllocator.allocate(10, -3, 1000));
        assertEquals(0, WaterAllocator.allocate(10, 200, -1));
    }

    @Test
    void allocationPourLaSequenceDIrrigation() {
        assertEquals(30, WaterAllocator.forNeed(30).liters(200, 1000));
        assertEquals(12, WaterAllocator.forNeed(30).liters(12, 1000));
    }

    @Test
    void pompeNiveauInitialEtIrrigationsDuNoeud() {
        PumpResource p = new PumpResource();
        assertEquals(Config.RESERVOIR_INITIAL, p.getCapacity());
        assertEquals(Config.RESERVOIR_INITIAL, p.getLevel());
        p.recordOwnIrrigation("A", 30, 970);
        p.recordOwnIrrigation("A", 20, 950);
        assertEquals(950, p.getLevel());
        assertEquals(2, p.getOwnUses());
        assertEquals(50, p.getOwnLiters());
        assertEquals("A", p.getLastUser());
        assertThrows(IllegalArgumentException.class, () -> p.recordOwnIrrigation("A", 0, 950));
    }

    @Test
    void evenementsDesAutresDansLeDesordreGardentLeNiveauLePlusBas() {
        PumpResource p = new PumpResource();
        p.recordOtherIrrigation("C", 920);     // C a irrigué après B, mais son événement arrive en premier
        p.recordOtherIrrigation("B", 960);
        assertEquals(920, p.getLevel(), "le réservoir ne remonte jamais");
        p.observeLevel(-1);
        assertEquals(920, p.getLevel());
        assertEquals(0, p.getOwnUses());
    }
}
