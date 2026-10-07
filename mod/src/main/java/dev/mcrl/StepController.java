package dev.mcrl;

import com.google.gson.JsonObject;
import dev.mcrl.arena.ArenaManager;
import dev.mcrl.arena.TunnelPlan;
import dev.mcrl.bridge.Request;
import dev.mcrl.bridge.RlBridgeServer;
import dev.mcrl.bridge.RlBridgeServer.Pending;
import dev.mcrl.bridge.RlBridgeServer.Reply;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Client-thread state machine (spec §3.2):
 * IDLE --request--> ACTING (inputs applied for N ticks while the server runs N ticks)
 *      --> AWAIT_FRAME (next world render is captured) --> reply --> IDLE.
 * Between steps the server is frozen and block breaking is suspended, so slow Python never desyncs.
 *
 * <p>Requests are picked up at the start of a client tick, before inputs are applied, so the tick
 * that begins a step is also its first ACTING tick: a step costs exactly N client ticks (plus Python's
 * round trip), not N + 1.
 *
 * <p>Action 12 (tunnel_forward) is a macro step: its server-side dig decides the step length
 * (break time + walk, at least TICKS_PER_STEP); the player stands still while the break time elapses and
 * holds forward for the last TunnelPlan.WALK_TICKS ticks. Server and client still advance the same N ticks.
 *
 * <p>Known limitation: a tick-frozen server still ticks players, so the agent's own physics (falling,
 * sliding) and fire/lava damage keep advancing between steps. That idle drift is bounded by how long
 * Python is not stepping: usually a tick or so of reply latency, but several seconds during PPO's
 * optimisation phase after each rollout (or any other pause in stepping). It affects only the player,
 * not blocks or the world.
 */
public final class StepController {
    private static final Logger LOG = LoggerFactory.getLogger("mcrl");
    static final int TICKS_PER_STEP = 4;
    static final int RESET_SETTLE_TICKS = 10;
    /** Client ticks to wait in AWAIT_FRAME for a world render (about 1 s at the default 100 Hz) before failing. */
    static final int FRAME_TIMEOUT_TICKS = 100;
    /** Upper bound on waiting for a task submitted to the integrated server thread. */
    static final long SERVER_TIMEOUT_SECONDS = 10;

    private enum State { IDLE, ACTING, AWAIT_FRAME }

    private static StepController instance;

    public static StepController get() { return instance; }

    public static void install(StepController controller) { instance = controller; }

    private final RlBridgeServer bridge;
    private final ArenaManager arena;
    private State state = State.IDLE;
    private Pending current;
    private Actions.Spec spec = Actions.NOOP;
    /** For a tunnel step: whether forward is held for its last WALK_TICKS ticks. */
    private boolean tunnelWalk;
    private int ticksLeft;
    private int awaitTicks;
    private boolean ownsKeys;

    public StepController(RlBridgeServer bridge, ArenaManager arena) {
        this.bridge = bridge;
        this.arena = arena;
    }

    /** True while an agent is connected and a singleplayer world is loaded. */
    public boolean isDriving(MinecraftClient client) {
        return bridge.isConnected() && client.player != null && client.world != null && client.getServer() != null;
    }

    /**
     * Starts the next queued request (so this tick is its first ACTING tick), then applies the
     * current step's movement keys for this tick.
     */
    public void onStartTick(MinecraftClient client) {
        // Pause menu open: wait; queued requests are handled after unpausing.
        if (state == State.IDLE && !client.isPaused()) pollAndBegin(client);
        if (!isDriving(client)) {
            if (ownsKeys) {
                setMovementKeys(client.options, Actions.NOOP);
                ownsKeys = false;
            }
            return;
        }
        ownsKeys = true;
        setMovementKeys(client.options, state == State.ACTING ? actingKeys() : Actions.NOOP);
    }

    /** Keys for the ACTING tick about to run; ticksLeft counts this tick, so the last WALK_TICKS walk. */
    private Actions.Spec actingKeys() {
        if (!spec.tunnel()) return spec;
        return tunnelWalk && ticksLeft <= TunnelPlan.WALK_TICKS ? Actions.TUNNEL_WALK : Actions.NOOP;
    }

    /** Counts the tick that just ran: ACTING ticks toward the step, AWAIT_FRAME ticks toward the watchdog. */
    public void onEndTick(MinecraftClient client) {
        if (client.isPaused()) return; // pause menu open: the tick didn't run the world, don't count it
        if (state == State.IDLE) return;
        if (!isDriving(client)) {
            fail("world unloaded or agent disconnected mid-step");
            return;
        }
        if (state == State.ACTING) {
            if (--ticksLeft <= 0) {
                state = State.AWAIT_FRAME;
                awaitTicks = 0;
            }
        } else if (state == State.AWAIT_FRAME && ++awaitTicks > FRAME_TIMEOUT_TICKS) {
            fail("no frame rendered within " + FRAME_TIMEOUT_TICKS + " client ticks");
        }
    }

    public void onWorldRendered(MinecraftClient client) {
        if (state != State.AWAIT_FRAME) return;
        try {
            byte[] frame = FrameCapture.capture(client.getFramebuffer());
            IntegratedServer server = client.getServer();
            UUID id = client.player.getUuid();
            JsonObject header = onServer(server.submit(
                    () -> arena.observe(server, server.getPlayerManager().getPlayer(id))));
            complete(new Reply(header, frame));
        } catch (Throwable t) {
            failAndMaybeRethrow("frame capture", t);
        }
    }

    /**
     * Replaces vanilla block breaking while driving. Returns true if vanilla should be skipped.
     * Mirrors MinecraftClient.handleBlockBreaking, minus the focus/cursor-lock requirement.
     */
    public boolean driveBreaking(MinecraftClient client) {
        if (!isDriving(client)) return false;
        if (state != State.ACTING) return true; // between steps: keep breaking progress, do nothing
        if (spec.attack() && client.crosshairTarget instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = hit.getBlockPos();
            if (!client.world.getBlockState(pos).isAir()) {
                Direction side = hit.getSide();
                if (client.interactionManager.updateBlockBreakingProgress(pos, side)) {
                    client.particleManager.addBlockBreakingParticles(pos, side);
                    client.player.swingHand(Hand.MAIN_HAND);
                }
            }
        } else {
            client.interactionManager.cancelBlockBreaking();
        }
        return true;
    }

    private void pollAndBegin(MinecraftClient client) {
        Pending pending;
        while ((pending = bridge.poll()) != null) {
            // The agent disconnected while this request was queued: never execute it.
            if (pending.isAbandoned()) {
                LOG.info("skipping abandoned {} request", pending.request().cmd());
                continue;
            }
            begin(client, pending);
            return;
        }
    }

    /**
     * Runs in START_CLIENT_TICK. On success the state is ACTING with ticksLeft = N, and this same
     * tick is the first of the N ticks that apply the inputs and that onEndTick counts; the server
     * is stepped exactly N ticks to match.
     */
    private void begin(MinecraftClient client, Pending pending) {
        if (!isDriving(client)) {
            pending.reply().complete(Reply.error("no singleplayer world loaded - open the rl_arena world"));
            return;
        }
        current = pending;
        IntegratedServer server = client.getServer();
        UUID id = client.player.getUuid();
        Request request = pending.request();
        try {
            if (request.cmd().equals("reset")) {
                onServer(server.submit(() -> arena.reset(server, server.getPlayerManager().getPlayer(id),
                        request.seed(), request.stage())));
                spec = Actions.NOOP;
                tunnelWalk = false;
                ticksLeft = RESET_SETTLE_TICKS;
            } else {
                spec = Actions.of(request.action());
                ClientPlayerEntity player = client.player;
                if (spec.tunnel()) {
                    float yaw = TunnelPlan.cardinalYaw(player.getYaw());
                    player.setYaw(yaw);
                    TunnelPlan plan = onServer(server.submit(() -> arena.tunnel(
                            server.getPlayerManager().getPlayer(id), yaw, TICKS_PER_STEP)));
                    tunnelWalk = plan.walk();
                    ticksLeft = plan.ticks();
                } else {
                    player.setYaw(player.getYaw() + spec.dYaw());
                    player.setPitch(Actions.clampPitch(player.getPitch() + spec.dPitch()));
                    tunnelWalk = false;
                    ticksLeft = TICKS_PER_STEP;
                }
            }
            int ticks = ticksLeft;
            server.execute(() -> server.getTickManager().step(ticks));
            state = State.ACTING;
        } catch (Throwable t) {
            failAndMaybeRethrow(request.cmd(), t);
        }
    }

    /**
     * Waits for a server-thread task, at most SERVER_TIMEOUT_SECONDS, so a stopped or hung integrated
     * server fails the request instead of hanging the client thread. Unwraps the task's own failure.
     */
    private static <T> T onServer(CompletableFuture<T> task) {
        try {
            return task.orTimeout(SERVER_TIMEOUT_SECONDS, TimeUnit.SECONDS).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof TimeoutException) {
                throw new IllegalStateException("integrated server did not respond within "
                        + SERVER_TIMEOUT_SECONDS + " s", cause);
            }
            if (cause instanceof RuntimeException r) throw r;
            if (cause instanceof Error err) throw err;
            throw e;
        }
    }

    /**
     * Always answers the pending request so the bridge never wedges. VirtualMachineErrors (OOM,
     * StackOverflow, InternalError) are rethrown afterwards: the JVM is not in a state to keep
     * training, and Minecraft's crash handling should see them. Everything else, including
     * LinkageErrors from a bad mapping, is logged and reported to Python as an error reply.
     */
    private void failAndMaybeRethrow(String what, Throwable t) {
        LOG.error("{} failed", what, t);
        fail(what + " failed: " + t);
        if (t instanceof VirtualMachineError vme) throw vme;
    }

    private void complete(Reply reply) {
        if (current != null) current.reply().complete(reply);
        current = null;
        state = State.IDLE;
    }

    private void fail(String message) {
        spec = Actions.NOOP;
        tunnelWalk = false;
        complete(Reply.error(message));
    }

    private static void setMovementKeys(GameOptions o, Actions.Spec s) {
        o.forwardKey.setPressed(s.forward());
        o.backKey.setPressed(s.back());
        o.leftKey.setPressed(s.left());
        o.rightKey.setPressed(s.right());
        o.jumpKey.setPressed(s.jump());
        o.sneakKey.setPressed(false);
        o.sprintKey.setPressed(false);
    }
}
