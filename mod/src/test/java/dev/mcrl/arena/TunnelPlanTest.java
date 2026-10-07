package dev.mcrl.arena;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TunnelPlanTest {
    @Test
    void roundsYawToNearestCardinalKeepingWinding() {
        assertEquals(0f, TunnelPlan.cardinalYaw(0f));
        assertEquals(0f, TunnelPlan.cardinalYaw(44f));
        assertEquals(90f, TunnelPlan.cardinalYaw(46f));
        assertEquals(90f, TunnelPlan.cardinalYaw(120f));
        assertEquals(180f, TunnelPlan.cardinalYaw(-179f + 360f));
        assertEquals(-90f, TunnelPlan.cardinalYaw(-80f));
        assertEquals(360f, TunnelPlan.cardinalYaw(350f)); // no spin back through 180
        assertEquals(-360f, TunnelPlan.cardinalYaw(-370f));
    }

    @Test
    void quarterUsesMinecraftHorizontalIds() {
        // Minecraft: yaw 0 faces south (+z), 90 west (-x), 180 north (-z), 270 / -90 east (+x).
        assertEquals(0, TunnelPlan.quarter(0f));
        assertEquals(1, TunnelPlan.quarter(90f));
        assertEquals(2, TunnelPlan.quarter(180f));
        assertEquals(3, TunnelPlan.quarter(270f));
        assertEquals(3, TunnelPlan.quarter(-90f));
        assertEquals(2, TunnelPlan.quarter(-180f));
        assertEquals(0, TunnelPlan.quarter(360f));
        assertEquals(1, TunnelPlan.quarter(100f));
        assertEquals(0, TunnelPlan.quarter(-30f));
    }

    @Test
    void offsetsPerQuarter() {
        assertArrayEquals(new int[]{0, 1}, new int[]{TunnelPlan.offsetX(0), TunnelPlan.offsetZ(0)});
        assertArrayEquals(new int[]{-1, 0}, new int[]{TunnelPlan.offsetX(1), TunnelPlan.offsetZ(1)});
        assertArrayEquals(new int[]{0, -1}, new int[]{TunnelPlan.offsetX(2), TunnelPlan.offsetZ(2)});
        assertArrayEquals(new int[]{1, 0}, new int[]{TunnelPlan.offsetX(3), TunnelPlan.offsetZ(3)});
        assertThrows(IllegalArgumentException.class, () -> TunnelPlan.offsetX(4));
    }

    @Test
    void breakTicksIsCeilOfInverseDelta() {
        // Iron pickaxe: stone 6/1.5/30, deepslate 6/3/30, deepslate diamond ore 6/4.5/30.
        assertEquals(8, TunnelPlan.breakTicks(6f / 1.5f / 30f));
        assertEquals(15, TunnelPlan.breakTicks(6f / 3f / 30f));
        assertEquals(23, TunnelPlan.breakTicks(6f / 4.5f / 30f));
        assertEquals(1, TunnelPlan.breakTicks(1f));
        assertEquals(1, TunnelPlan.breakTicks(5f)); // instant breaks still take a tick
        assertEquals(2, TunnelPlan.breakTicks(0.5f));
        assertThrows(IllegalArgumentException.class, () -> TunnelPlan.breakTicks(0f));
        assertThrows(IllegalArgumentException.class, () -> TunnelPlan.breakTicks(-1f));
        assertThrows(IllegalArgumentException.class, () -> TunnelPlan.breakTicks(Float.NaN));
    }

    @Test
    void digCostsBreakTimePlusWalkButNeverLessThanAStep() {
        TunnelPlan p = TunnelPlan.dig(4, 8 + 15);
        assertTrue(p.walk());
        assertEquals(8 + 15 + TunnelPlan.WALK_TICKS, p.ticks());
        assertEquals(TunnelPlan.WALK_TICKS, TunnelPlan.dig(4, 0).ticks()); // nothing to break: just walk
        assertEquals(10, TunnelPlan.dig(10, 0).ticks());                   // floor at the normal step length
        assertThrows(IllegalArgumentException.class, () -> TunnelPlan.dig(4, -1));
    }

    @Test
    void blockedCostsANormalStepAndDoesNotWalk() {
        TunnelPlan p = TunnelPlan.blocked(4);
        assertFalse(p.walk());
        assertEquals(4, p.ticks());
    }

    @Test
    void walkTicksIsAboutOneBlockOfWalking() {
        assertTrue(TunnelPlan.WALK_TICKS >= 5 && TunnelPlan.WALK_TICKS <= 8);
    }

    @Test
    void columnCenter() {
        assertEquals(4.5, TunnelPlan.columnCenter(4.0));
        assertEquals(4.5, TunnelPlan.columnCenter(4.93));
        assertEquals(-3.5, TunnelPlan.columnCenter(-3.2));
        assertEquals(-3.5, TunnelPlan.columnCenter(-2.9999 - 0.5));
    }
}
