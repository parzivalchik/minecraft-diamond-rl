package dev.mcrl.arena;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class ArenaGeneratorTest {
    private static final int SEEDS = 200;

    @Test
    void stageConfigs() {
        StageConfig s1 = StageConfig.forStage(1);
        assertEquals(15, s1.sizeX());
        assertEquals(8, s1.sizeY());
        assertEquals(17, s1.width());
        assertEquals(12, s1.height());
        StageConfig s2 = StageConfig.forStage(2);
        assertEquals(25, s2.sizeX());
        assertEquals(12, s2.sizeY());
        assertEquals(s2, StageConfig.largest());
        assertThrows(IllegalArgumentException.class, () -> StageConfig.forStage(0));
        assertThrows(IllegalArgumentException.class, () -> StageConfig.forStage(3));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void deterministicForSameSeed(int stage) {
        StageConfig cfg = StageConfig.forStage(stage);
        assertArrayEquals(ArenaGenerator.generate(42, cfg).snapshot(),
                ArenaGenerator.generate(42, cfg).snapshot());
        assertFalse(Arrays.equals(ArenaGenerator.generate(42, cfg).snapshot(),
                ArenaGenerator.generate(43, cfg).snapshot()));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
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
    @ValueSource(ints = {1, 2})
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
    @ValueSource(ints = {1, 2})
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
    @ValueSource(ints = {1, 2})
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
    @ValueSource(ints = {1, 2})
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
        ArenaLayout a = ArenaGenerator.generate(1, StageConfig.forStage(2));
        StageConfig cfg = a.config();
        double interior = cfg.sizeX() * cfg.sizeY() * cfg.sizeZ();
        double stone = a.count(Cell.STONE) / interior;
        assertTrue(stone > 0.75 && stone < 0.92, "stone share " + stone);
        assertTrue(a.count(Cell.COAL) > 0 && a.count(Cell.IRON) > 0 && a.count(Cell.GRAVEL) > 0);
    }

    @Test
    void deepLayersAreTheLowerHalf() {
        ArenaLayout a = ArenaGenerator.generate(1, StageConfig.forStage(1));
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
