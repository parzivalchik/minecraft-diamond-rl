package dev.mcrl;

/** The 12 discrete agent actions (spec §5). Turning right is +yaw; looking up is -pitch. */
public final class Actions {
    public static final int COUNT = 12;
    public static final float TURN_DEGREES = 15f;

    public record Spec(boolean forward, boolean back, boolean left, boolean right,
                       boolean jump, boolean attack, float dYaw, float dPitch) {}

    public static final Spec NOOP = new Spec(false, false, false, false, false, false, 0f, 0f);

    private Actions() {}

    public static Spec of(int action) {
        return switch (action) {
            case 0 -> NOOP;
            case 1 -> new Spec(true, false, false, false, false, false, 0f, 0f);
            case 2 -> new Spec(false, true, false, false, false, false, 0f, 0f);
            case 3 -> new Spec(false, false, true, false, false, false, 0f, 0f);
            case 4 -> new Spec(false, false, false, true, false, false, 0f, 0f);
            case 5 -> new Spec(true, false, false, false, true, false, 0f, 0f);
            case 6 -> new Spec(false, false, false, false, false, false, -TURN_DEGREES, 0f);
            case 7 -> new Spec(false, false, false, false, false, false, TURN_DEGREES, 0f);
            case 8 -> new Spec(false, false, false, false, false, false, 0f, -TURN_DEGREES);
            case 9 -> new Spec(false, false, false, false, false, false, 0f, TURN_DEGREES);
            case 10 -> new Spec(false, false, false, false, false, true, 0f, 0f);
            case 11 -> new Spec(true, false, false, false, false, true, 0f, 0f);
            default -> throw new IllegalArgumentException("action out of range: " + action);
        };
    }

    public static float clampPitch(float pitch) {
        return Math.max(-90f, Math.min(90f, pitch));
    }
}
