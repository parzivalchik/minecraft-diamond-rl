package dev.mcrl;

import dev.mcrl.arena.ArenaManager;
import dev.mcrl.bridge.RlBridgeServer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;

public final class McrlClient implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("mcrl");
    public static final int DEFAULT_PORT = 5005;

    @Override
    public void onInitializeClient() {
        int port = Integer.getInteger("mcrl.port", DEFAULT_PORT);
        EpisodeTracker tracker = new EpisodeTracker();
        RlBridgeServer bridge = new RlBridgeServer(port);
        try {
            bridge.start();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot bind MCRL bridge on port " + port, e);
        }
        StepController controller = new StepController(bridge, new ArenaManager(tracker));
        StepController.install(controller);

        registerTrackerEvents(tracker);
        ClientTickEvents.START_CLIENT_TICK.register(controller::onStartTick);
        ClientTickEvents.END_CLIENT_TICK.register(controller::onEndTick);
        WorldRenderEvents.END.register(context -> controller.onWorldRendered(MinecraftClient.getInstance()));
        ClientLifecycleEvents.CLIENT_STARTED.register(RenderSettings::apply);
        AutoWorld.register();
        LOG.info("MCRL bridge listening on 127.0.0.1:{} (tick rate {} Hz while driving)",
                bridge.getPort(), ArenaManager.TICK_RATE);
    }

    private static void registerTrackerEvents(EpisodeTracker tracker) {
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (!world.isClient()) tracker.onBlockBroken(Registries.BLOCK.getId(state.getBlock()).toString());
        });
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damageTaken, blocked) -> {
            if (entity instanceof ServerPlayerEntity && damageTaken > 0) {
                tracker.onDamage(sourceId(source), damageTaken);
            }
        });
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
            if (!(entity instanceof ServerPlayerEntity player)) return true;
            // Cancel real death (no death screen); report it and let Python end the episode and reset.
            // No onDamage here: with health set back to 1 the entity is no longer dead when
            // LivingEntity.damage reaches its TAIL, so AFTER_DAMAGE still fires and records the
            // lethal hit (and its cause) exactly once.
            tracker.onDeath();
            player.setHealth(1.0f);
            return false;
        });
    }

    /** e.g. "lava", "in_fire", "on_fire", "fall", "in_wall". */
    private static String sourceId(DamageSource source) {
        return source.getTypeRegistryEntry().getKey().map(k -> k.getValue().getPath()).orElse("unknown");
    }
}
