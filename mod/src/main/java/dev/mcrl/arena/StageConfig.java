package dev.mcrl.arena;

/**
 * Arena dimensions and hazard counts per curriculum stage (spec §7).
 * {@code nearDiamonds} of the diamonds are placed within {@code nearRadius} blocks of the spawn feet
 * position; {@code nearRadius} is unused (0) when there are none.
 */
public record StageConfig(int stage, int sizeX, int sizeY, int sizeZ,
                          int minDiamonds, int maxDiamonds, int minLava, int maxLava,
                          int nearDiamonds, double nearRadius) {
    /** Rows of wall above the interior, so the agent cannot walk off the top surface. */
    public static final int WALL_ABOVE = 3;
    public static final int MAX_STAGE = 4;

    public StageConfig {
        if (nearDiamonds > 0 && !(nearRadius > 0)) {
            throw new IllegalArgumentException("stage " + stage + ": near-spawn diamonds need a positive radius");
        }
    }

    public static StageConfig forStage(int stage) {
        return switch (stage) {
            // Stage 0: small, lava-free, diamond-rich, so the agent learns that diamonds pay before it must search.
            case 0 -> new StageConfig(0, 7, 4, 7, 4, 5, 0, 0, 2, 3.0);
            // Stages 1-2: stepping stones to the 15x15 arena; one diamond a short tunnel away from spawn.
            case 1 -> new StageConfig(1, 9, 5, 9, 3, 4, 0, 0, 1, 5.0);
            case 2 -> new StageConfig(2, 11, 6, 11, 3, 3, 1, 1, 1, 7.0);
            case 3 -> new StageConfig(3, 15, 8, 15, 2, 3, 1, 2, 0, 0.0);
            case 4 -> new StageConfig(4, 25, 12, 25, 1, 2, 3, 4, 0, 0.0);
            default -> throw new IllegalArgumentException(
                    "stage " + stage + " is not implemented (valid: 0-" + MAX_STAGE + ")");
        };
    }

    public static StageConfig largest() {
        return forStage(MAX_STAGE);
    }

    public int width() { return sizeX + 2; }
    public int height() { return 1 + sizeY + WALL_ABOVE; }
    public int depth() { return sizeZ + 2; }
}
