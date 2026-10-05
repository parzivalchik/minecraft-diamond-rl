package dev.mcrl;

import com.google.gson.JsonObject;
import dev.mcrl.arena.ArenaManager;
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

/**
 * Client-thread state machine (spec §3.2):
 * IDLE --request--> ACTING (inputs applied for N ticks while the server runs N ticks)
 *      --> AWAIT_FRAME (next world render is captured) --> reply --> IDLE.
 * Between steps the server is frozen and block breaking is suspended, so slow Python never desyncs.
 */
public final class StepController {
    private static final Logger LOG = LoggerFactory.getLogger("mcrl");
    static final int TICKS_PER_STEP = 4;
    static final int RESET_SETTLE_TICKS = 10;

    private enum State { IDLE, ACTING, AWAIT_FRAME }

    private static StepController instance;

    public static StepController get() { return instance; }

    public static void install(StepController controller) { instance = controller; }

    private final RlBridgeServer bridge;
    private final ArenaManager arena;
    private State state = State.IDLE;
    private Pending current;
    private Actions.Spec spec = Actions.NOOP;
    private int ticksLeft;
    private boolean ownsKeys;

    public StepController(RlBridgeServer bridge, ArenaManager arena) {
        this.bridge = bridge;
        this.arena = arena;
    }

    /** True while an agent is connected and a singleplayer world is loaded. */
    public boolean isDriving(MinecraftClient client) {
        return bridge.isConnected() && client.player != null && client.world != null && client.getServer() != null;
    }

    public void onStartTick(MinecraftClient client) {
        if (!isDriving(client)) {
            if (ownsKeys) {
                setMovementKeys(client.options, Actions.NOOP);
                ownsKeys = false;
            }
            return;
        }
        ownsKeys = true;
        setMovementKeys(client.options, state == State.ACTING ? spec : Actions.NOOP);
    }

    public void onEndTick(MinecraftClient client) {
        if (client.isPaused()) return; // pause menu open: wait; queued requests are handled after unpausing
        if (state == State.IDLE) {
            Pending pending;
            while ((pending = bridge.poll()) != null) {
                // The agent disconnected while this request was queued: never execute it.
                if (pending.isAbandoned()) {
                    LOG.info("skipping abandoned {} request", pending.request().cmd());
                    continue;
                }
                begin(client, pending);
                break;
            }
            return;
        }
        if (!isDriving(client)) {
            fail("world unloaded or agent disconnected mid-step");
            return;
        }
        if (state == State.ACTING && --ticksLeft <= 0) state = State.AWAIT_FRAME;
    }

    public void onWorldRendered(MinecraftClient client) {
        if (state != State.AWAIT_FRAME) return;
        try {
            byte[] frame = FrameCapture.capture(client.getFramebuffer());
            IntegratedServer server = client.getServer();
            UUID id = client.player.getUuid();
            JsonObject header = server.submit(
                    () -> arena.observe(server, server.getPlayerManager().getPlayer(id))).join();
            complete(new Reply(header, frame));
        } catch (RuntimeException e) {
            LOG.error("frame capture failed", e);
            fail("frame capture failed: " + e);
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
                server.submit(() -> arena.reset(server, server.getPlayerManager().getPlayer(id),
                        request.seed(), request.stage())).join();
                spec = Actions.NOOP;
                ticksLeft = RESET_SETTLE_TICKS;
            } else {
                spec = Actions.of(request.action());
                ClientPlayerEntity player = client.player;
                player.setYaw(player.getYaw() + spec.dYaw());
                player.setPitch(Actions.clampPitch(player.getPitch() + spec.dPitch()));
                ticksLeft = TICKS_PER_STEP;
            }
            int ticks = ticksLeft;
            server.execute(() -> server.getTickManager().step(ticks));
            state = State.ACTING;
        } catch (RuntimeException e) {
            LOG.error("failed to start {}", request, e);
            fail(request.cmd() + " failed: " + e);
        }
    }

    private void complete(Reply reply) {
        if (current != null) current.reply().complete(reply);
        current = null;
        state = State.IDLE;
    }

    private void fail(String message) {
        spec = Actions.NOOP;
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
