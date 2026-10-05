package dev.mcrl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ActionsTest {
    @Test
    void hasTwelveActions() {
        assertEquals(12, Actions.COUNT);
        for (int i = 0; i < Actions.COUNT; i++) {
            assertNotNull(Actions.of(i));
        }
    }

    @Test
    void rejectsOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> Actions.of(-1));
        assertThrows(IllegalArgumentException.class, () -> Actions.of(12));
    }

    @Test
    void noopDoesNothing() {
        assertEquals(Actions.NOOP, Actions.of(0));
        Actions.Spec s = Actions.NOOP;
        assertFalse(s.forward() || s.back() || s.left() || s.right() || s.jump() || s.attack());
        assertEquals(0f, s.dYaw());
        assertEquals(0f, s.dPitch());
    }

    @Test
    void movementActions() {
        assertTrue(Actions.of(1).forward());
        assertTrue(Actions.of(2).back());
        assertTrue(Actions.of(3).left());
        assertTrue(Actions.of(4).right());
        assertTrue(Actions.of(5).jump() && Actions.of(5).forward());
    }

    @Test
    void turnActions() {
        assertEquals(-15f, Actions.of(6).dYaw());
        assertEquals(15f, Actions.of(7).dYaw());
        assertEquals(-15f, Actions.of(8).dPitch());
        assertEquals(15f, Actions.of(9).dPitch());
    }

    @Test
    void attackActions() {
        assertTrue(Actions.of(10).attack());
        assertFalse(Actions.of(10).forward());
        assertTrue(Actions.of(11).attack() && Actions.of(11).forward());
        for (int i = 0; i < 10; i++) {
            assertFalse(Actions.of(i).attack(), "action " + i);
        }
    }

    @Test
    void clampsPitch() {
        assertEquals(90f, Actions.clampPitch(105f));
        assertEquals(-90f, Actions.clampPitch(-120f));
        assertEquals(30f, Actions.clampPitch(30f));
    }
}
