/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.smartwater.algorithms;

/**
 *
 * @author nader
 */
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LamportClockMockTest {

    @Test
    void tickIncrements() {
        LamportClock c = new LamportClock();
        assertEquals(1, c.tick());
        assertEquals(2, c.tick());
    }

    @Test
    void updateTakesMaxPlusOne() {
        LamportClock c = new LamportClock();
        c.tick(); // 1
        assertEquals(11, c.update(10));
        assertEquals(12, c.update(3)); // local 11 > reçu 3
    }

    @Test
    void compareBreaksTiesById() {
        assertTrue(LamportClock.compare(5, "A", 5, "B") < 0);
        assertTrue(LamportClock.compare(4, "C", 5, "A") < 0);
        assertTrue(LamportClock.compare(6, "A", 5, "C") > 0);
    }
}
