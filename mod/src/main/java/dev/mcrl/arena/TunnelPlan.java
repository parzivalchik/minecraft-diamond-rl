package dev.mcrl.arena;

/**
 * Pure math for action 12, tunnel_forward (spec §5): which way to dig, and how many game ticks the
 * step lasts. No Minecraft classes, so it is unit-testable; {@link Tunneler} applies it to the world.
 *
 * @param ticks game ticks the step lasts (server and client both advance exactly this many)
 * @param walk  whether forward is held for the last {@link #WALK_TICKS} of them
 */
public record TunnelPlan(int ticks, boolean walk) {
    /** Ticks of holding forward after the break time: from standing still, about one block of walking. */
    public static final int WALK_TICKS = 6;

    /** Yaw rounded to the nearest multiple of 90 (a cardinal), keeping the winding so the camera never spins. */
    public static float cardinalYaw(float yaw) {
        return 90f * Math.round(yaw / 90f);
    }

    /** Minecraft's horizontal direction id for the nearest cardinal: 0 south (+z), 1 west (-x), 2 north (-z), 3 east (+x). */
    public static int quarter(float yaw) {
        return Math.floorMod(Math.round(yaw / 90f), 4);
    }

    public static int offsetX(int quarter) {
        return switch (quarter) {
            case 0, 2 -> 0;
            case 1 -> -1;
            case 3 -> 1;
            default -> throw new IllegalArgumentException("quarter out of range: " + quarter);
        };
    }

    public static int offsetZ(int quarter) {
        return switch (quarter) {
            case 0 -> 1;
            case 2 -> -1;
            case 1, 3 -> 0;
            default -> throw new IllegalArgumentException("quarter out of range: " + quarter);
        };
    }

    /** Ticks vanilla survival mining needs at {@code delta} progress per tick: ceil(1 / delta), at least 1. */
    public static int breakTicks(float delta) {
        if (!(delta > 0f)) throw new IllegalArgumentException("block cannot be broken (delta " + delta + ")");
        return Math.max(1, (int) Math.ceil(1.0 / delta));
    }

    /** Both targets are breakable or passable: break for {@code breakTicks}, then walk; never shorter than a normal step. */
    public static TunnelPlan dig(int minTicks, int breakTicks) {
        if (breakTicks < 0) throw new IllegalArgumentException("negative break ticks: " + breakTicks);
        return new TunnelPlan(Math.max(minTicks, breakTicks + WALK_TICKS), true);
    }

    /**
     * The blocks were broken but lava is now in or next to the opening: charge the break time and stay
     * put, as a player stops on seeing lava behind a block they just mined.
     */
    public static TunnelPlan halt(int minTicks, int breakTicks) {
        if (breakTicks < 0) throw new IllegalArgumentException("negative break ticks: " + breakTicks);
        return new TunnelPlan(Math.max(minTicks, breakTicks), false);
    }

    /** A target is unbreakable (bedrock): nothing happens, and the step costs a normal step. */
    public static TunnelPlan blocked(int minTicks) {
        return new TunnelPlan(minTicks, false);
    }

    /** Centre of the block column containing {@code coord}, so a 0.6-wide player fits a 1-wide tunnel. */
    public static double columnCenter(double coord) {
        return Math.floor(coord) + 0.5;
    }
}
