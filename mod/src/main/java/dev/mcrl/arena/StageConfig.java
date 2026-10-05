package dev.mcrl.arena;

/** Arena dimensions and hazard counts per curriculum stage (spec §7). */
public record StageConfig(int stage, int sizeX, int sizeY, int sizeZ,
                          int minDiamonds, int maxDiamonds, int minLava, int maxLava) {
    /** Rows of wall above the interior, so the agent cannot walk off the top surface. */
    public static final int WALL_ABOVE = 3;

    public static StageConfig forStage(int stage) {
        return switch (stage) {
            case 1 -> new StageConfig(1, 15, 8, 15, 2, 3, 1, 2);
            case 2 -> new StageConfig(2, 25, 12, 25, 1, 2, 3, 4);
            default -> throw new IllegalArgumentException("stage " + stage + " is not implemented (valid: 1-2)");
        };
    }

    public static StageConfig largest() {
        return forStage(2);
    }

    public int width() { return sizeX + 2; }
    public int height() { return 1 + sizeY + WALL_ABOVE; }
    public int depth() { return sizeZ + 2; }
}
