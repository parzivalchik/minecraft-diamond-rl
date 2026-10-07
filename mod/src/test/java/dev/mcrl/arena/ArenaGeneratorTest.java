package dev.mcrl.arena;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class ArenaGeneratorTest {
    private static final int SEEDS = 200;

    @Test
    void stageTable() {
        // stage, interior x/y/z, diamonds min-max, near-spawn diamonds, near radius, lava min-max
        assertEquals(new StageConfig(0, 7, 4, 7, 4, 5, 0, 0, 2, 3.0), StageConfig.forStage(0));
        assertEquals(new StageConfig(1, 9, 5, 9, 3, 4, 0, 0, 1, 5.0), StageConfig.forStage(1));
        assertEquals(new StageConfig(2, 11, 6, 11, 3, 3, 1, 1, 1, 7.0), StageConfig.forStage(2));
        assertEquals(new StageConfig(3, 15, 8, 15, 2, 3, 1, 2, 0, 0.0), StageConfig.forStage(3));
        assertEquals(new StageConfig(4, 25, 12, 25, 1, 2, 3, 4, 0, 0.0), StageConfig.forStage(4));
    }

    @Test
    void stageDimensionsAndLimits() {
        StageConfig s3 = StageConfig.forStage(3);
        assertEquals(17, s3.width());
        assertEquals(12, s3.height());
        assertEquals(17, s3.depth());
        StageConfig s4 = StageConfig.forStage(4);
        assertEquals(s4, StageConfig.largest());
        for (int stage = 0; stage <= 4; stage++) {
            StageConfig cfg = StageConfig.forStage(stage);
            assertTrue(cfg.width() <= s4.width() && cfg.height() <= s4.height() && cfg.depth() <= s4.depth(),
                    "stage " + stage + " fits in the cleared region");
        }
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> StageConfig.forStage(5));
        assertEquals("stage 5 is not implemented (valid: 0-4)", e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> StageConfig.forStage(-1));
    }

    @Test
    void nearDiamondsNeedAPositiveRadius() {
        assertThrows(IllegalArgumentException.class, () -> new StageConfig(9, 7, 4, 7, 4, 5, 0, 0, 2, 0.0));
    }

    @Test
    void stageZeroIsSmallLavaFreeAndRichInDiamonds() {
        StageConfig s0 = StageConfig.forStage(0);
        for (long seed = 0; seed < SEEDS; seed++) {
            ArenaLayout a = ArenaGenerator.generate(seed, s0);
            int diamonds = a.count(Cell.DIAMOND);
            assertTrue(diamonds >= 4 && diamonds <= 5, "seed " + seed + ": " + diamonds + " diamonds");
            assertEquals(0, a.count(Cell.LAVA), "seed " + seed);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void nearSpawnDiamondsAreWithinRadius(int stage) {
        StageConfig cfg = StageConfig.forStage(stage);
        for (long seed = 0; seed < SEEDS; seed++) {
            ArenaLayout a = ArenaGenerator.generate(seed, cfg);
            int[] near = {0};
            forEachCell(a, (x, y, z) -> {
                if (a.get(x, y, z) != Cell.DIAMOND) return;
                double d = Math.sqrt(sq(x - a.spawnX()) + sq(y - a.spawnY()) + sq(z - a.spawnZ()));
                if (d <= cfg.nearRadius()) near[0]++;
            });
            assertTrue(near[0] >= cfg.nearDiamonds(), "seed " + seed + ": only " + near[0] + " near-spawn diamonds");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void deterministicForSameSeed(int stage) {
        StageConfig cfg = StageConfig.forStage(stage);
        assertArrayEquals(ArenaGenerator.generate(42, cfg).snapshot(),
                ArenaGenerator.generate(42, cfg).snapshot());
        assertFalse(Arrays.equals(ArenaGenerator.generate(42, cfg).snapshot(),
                ArenaGenerator.generate(43, cfg).snapshot()));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void diamondAndLavaCountsInRange(int stage) {
        StageConfig cfg = StageConfig.forStage(stage);
        for (long seed = 0; seed < SEEDS; seed++) {
            ArenaLayout a = ArenaGenerator.generate(seed, cfg);
            int diamonds = a.count(Cell.DIAMOND);
            int lava = a.count(Cell.LAVA);
            assertTrue(diamonds >= cfg.minDiamonds() && diamonds <= cfg.maxDiamonds(), "seed " + seed);
            assertTrue(lava >= cfg.minLava() && lava <= cfg.maxLava(), "seed " + seed);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void lavaIsFarFromSpawnAndSealed(int stage) {
        StageConfig cfg = StageConfig.forStage(stage);
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (long seed = 0; seed < SEEDS; seed++) {
            ArenaLayout a = ArenaGenerator.generate(seed, cfg);
            forEachCell(a, (x, y, z) -> {
                if (a.get(x, y, z) != Cell.LAVA) return;
                double d = Math.sqrt(sq(x - a.spawnX()) + sq(y - a.spawnY()) + sq(z - a.spawnZ()));
                assertTrue(d >= 3.0, "lava too close to spawn: " + d);
                for (int[] dir : dirs) {
                    assertNotEquals(Cell.AIR, a.get(x + dir[0], y + dir[1], z + dir[2]), "lava touches air");
                }
                assertNotEquals(Cell.GRAVEL, a.get(x, y + 1, z), "gravel above lava");
            });
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void spawnPocketIsAirWithSolidStoneFloor(int stage) {
        StageConfig cfg = StageConfig.forStage(stage);
        for (long seed = 0; seed < SEEDS; seed++) {
            ArenaLayout a = ArenaGenerator.generate(seed, cfg);
            assertEquals(cfg.sizeY() - 1, a.spawnY());
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int x = a.spawnX() + dx, z = a.spawnZ() + dz;
                    assertEquals(Cell.AIR, a.get(x, a.spawnY(), z));
                    assertEquals(Cell.AIR, a.get(x, a.spawnY() + 1, z));
                    assertEquals(Cell.STONE, a.get(x, a.spawnY() - 1, z));
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void diamondsAreBelowTheSurface(int stage) {
        StageConfig cfg = StageConfig.forStage(stage);
        for (long seed = 0; seed < SEEDS; seed++) {
            ArenaLayout a = ArenaGenerator.generate(seed, cfg);
            forEachCell(a, (x, y, z) -> {
                if (a.get(x, y, z) == Cell.DIAMOND) {
                    assertTrue(y >= 1 && y <= cfg.sizeY() - 2, "diamond at y=" + y);
                }
            });
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void enclosedByBedrock(int stage) {
        ArenaLayout a = ArenaGenerator.generate(7, StageConfig.forStage(stage));
        forEachCell(a, (x, y, z) -> {
            boolean shell = y == 0 || x == 0 || z == 0 || x == a.width() - 1 || z == a.depth() - 1;
            if (shell) {
                assertEquals(Cell.BEDROCK, a.get(x, y, z), "shell at " + x + "," + y + "," + z);
            } else {
                assertNotEquals(Cell.BEDROCK, a.get(x, y, z), "bedrock inside at " + x + "," + y + "," + z);
            }
            if (!shell && y > a.config().sizeY()) {
                assertEquals(Cell.AIR, a.get(x, y, z), "open air above the interior");
            }
        });
    }

    @Test
    void fillIsMostlyStone() {
        ArenaLayout a = ArenaGenerator.generate(1, StageConfig.forStage(4));
        StageConfig cfg = a.config();
        double interior = cfg.sizeX() * cfg.sizeY() * cfg.sizeZ();
        double stone = a.count(Cell.STONE) / interior;
        assertTrue(stone > 0.75 && stone < 0.92, "stone share " + stone);
        assertTrue(a.count(Cell.COAL) > 0 && a.count(Cell.IRON) > 0 && a.count(Cell.GRAVEL) > 0);
    }

    @Test
    void deepLayersAreTheLowerHalf() {
        ArenaLayout a = ArenaGenerator.generate(1, StageConfig.forStage(3));
        assertFalse(a.isDeep(0));
        assertTrue(a.isDeep(1));
        assertTrue(a.isDeep(4));
        assertFalse(a.isDeep(5));
    }

    private interface CellVisitor { void visit(int x, int y, int z); }

    private static void forEachCell(ArenaLayout a, CellVisitor v) {
        for (int y = 0; y < a.height(); y++)
            for (int z = 0; z < a.depth(); z++)
                for (int x = 0; x < a.width(); x++)
                    v.visit(x, y, z);
    }

    private static int sq(int v) { return v * v; }
}
