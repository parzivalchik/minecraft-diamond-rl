package dev.mcrl.arena;

import java.util.Random;

/** Pure (seed, stage) -> layout generator (spec §7). No Minecraft classes, so it is unit-testable. */
public final class ArenaGenerator {
    static final double COAL_SHARE = 0.05;
    static final double IRON_SHARE = 0.03;
    static final double GRAVEL_SHARE = 0.03;
    static final double AIR_SHAFT_SHARE = 0.03;
    static final double MIN_LAVA_DISTANCE = 3.0;
    /** Max distance from the spawn feet position for the stage's guaranteed near-spawn diamonds. */
    public static final double NEAR_DIAMOND_RADIUS = 3.0;
    private static final int MAX_ATTEMPTS = 10_000;
    private static final int[][] NEIGHBORS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private ArenaGenerator() {}

    public static ArenaLayout generate(long seed, StageConfig cfg) {
        Random rng = new Random(seed * 31L + cfg.stage());
        ArenaLayout a = ArenaLayout.empty(cfg);
        fillInterior(a, rng);
        carveSpawn(a);
        int lava = between(rng, cfg.minLava(), cfg.maxLava());
        for (int i = 0; i < lava; i++) placeLava(a, rng);
        int diamonds = between(rng, cfg.minDiamonds(), cfg.maxDiamonds());
        for (int i = 0; i < diamonds; i++) placeDiamond(a, rng, i < cfg.nearDiamonds() ? NEAR_DIAMOND_RADIUS : Double.MAX_VALUE);
        return a;
    }

    private static void fillInterior(ArenaLayout a, Random rng) {
        StageConfig cfg = a.config();
        double coal = COAL_SHARE, iron = coal + IRON_SHARE, gravel = iron + GRAVEL_SHARE, shaft = gravel + AIR_SHAFT_SHARE;
        for (int y = 1; y <= cfg.sizeY(); y++)
            for (int z = 1; z <= cfg.sizeZ(); z++)
                for (int x = 1; x <= cfg.sizeX(); x++) {
                    double r = rng.nextDouble();
                    if (r < coal) a.set(x, y, z, Cell.COAL);
                    else if (r < iron) a.set(x, y, z, Cell.IRON);
                    else if (r < gravel) a.set(x, y, z, Cell.GRAVEL);
                    else if (r < shaft) {
                        // A 1x1 vertical air shaft 2-3 deep: a fall hazard. Cells below are already filled.
                        int depth = 2 + rng.nextInt(2);
                        for (int k = 0; k < depth && y - k >= 1; k++) a.set(x, y - k, z, Cell.AIR);
                    } else a.set(x, y, z, Cell.STONE);
                }
    }

    private static void carveSpawn(ArenaLayout a) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                int x = a.spawnX() + dx, z = a.spawnZ() + dz;
                a.set(x, a.spawnY(), z, Cell.AIR);
                a.set(x, a.spawnY() + 1, z, Cell.AIR);
                a.set(x, a.spawnY() - 1, z, Cell.STONE);
            }
    }

    private static void placeLava(ArenaLayout a, Random rng) {
        StageConfig cfg = a.config();
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            int x = 1 + rng.nextInt(cfg.sizeX()), y = 1 + rng.nextInt(cfg.sizeY()), z = 1 + rng.nextInt(cfg.sizeZ());
            Cell here = a.get(x, y, z);
            if (here == Cell.LAVA || here == Cell.AIR || a.inSpawnPocket(x, y, z) || a.underSpawnPocket(x, y, z)) continue;
            if (distanceToSpawn(a, x, y, z) < MIN_LAVA_DISTANCE) continue;
            if (touchesAir(a, x, y, z)) continue;
            a.set(x, y, z, Cell.LAVA);
            // Gravel resting on lava would fall into it as soon as the world ticks.
            if (a.get(x, y + 1, z) == Cell.GRAVEL) a.set(x, y + 1, z, Cell.STONE);
            return;
        }
        throw new IllegalStateException("could not place lava in " + MAX_ATTEMPTS + " attempts");
    }

    private static void placeDiamond(ArenaLayout a, Random rng, double maxDistanceToSpawn) {
        StageConfig cfg = a.config();
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            int x = 1 + rng.nextInt(cfg.sizeX()), y = 1 + rng.nextInt(cfg.sizeY() - 2), z = 1 + rng.nextInt(cfg.sizeZ());
            Cell here = a.get(x, y, z);
            if (here == Cell.LAVA || here == Cell.DIAMOND || a.underSpawnPocket(x, y, z)) continue;
            if (distanceToSpawn(a, x, y, z) > maxDistanceToSpawn) continue;
            a.set(x, y, z, Cell.DIAMOND);
            return;
        }
        throw new IllegalStateException("could not place diamond in " + MAX_ATTEMPTS + " attempts");
    }

    private static boolean touchesAir(ArenaLayout a, int x, int y, int z) {
        for (int[] d : NEIGHBORS) {
            if (a.get(x + d[0], y + d[1], z + d[2]) == Cell.AIR) return true;
        }
        return false;
    }

    private static double distanceToSpawn(ArenaLayout a, int x, int y, int z) {
        int dx = x - a.spawnX(), dy = y - a.spawnY(), dz = z - a.spawnZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static int between(Random rng, int lo, int hi) {
        return lo + rng.nextInt(hi - lo + 1);
    }
}
