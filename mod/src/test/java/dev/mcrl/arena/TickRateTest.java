package dev.mcrl.arena;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TickRateTest {
    @Test
    void defaultsTo100WhenUnset() {
        assertEquals(100f, ArenaManager.parseTickRate(null));
    }

    @Test
    void parsesConfiguredRate() {
        assertEquals(200f, ArenaManager.parseTickRate("200"));
        assertEquals(62.5f, ArenaManager.parseTickRate(" 62.5 "));
    }

    @Test
    void rejectsGarbageAndOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> ArenaManager.parseTickRate("fast"));
        assertThrows(IllegalArgumentException.class, () -> ArenaManager.parseTickRate("0"));
        assertThrows(IllegalArgumentException.class, () -> ArenaManager.parseTickRate("-5"));
        assertThrows(IllegalArgumentException.class, () -> ArenaManager.parseTickRate("NaN"));
        assertThrows(IllegalArgumentException.class, () -> ArenaManager.parseTickRate("20000"));
    }
}
