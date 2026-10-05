package dev.mcrl;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opt-in (-Dmcrl.autoWorld=true): the first time the title screen is up, open the rl_arena save,
 * creating it (survival, cheats on, default superflat) if it doesn't exist. Lets the training world
 * come up with no clicks (spec §3.4). Any failure is logged and leaves the player at the title screen.
 */
public final class AutoWorld {
    private static final Logger LOG = LoggerFactory.getLogger("mcrl");
    public static final String WORLD_NAME = "rl_arena";

    private static boolean attempted;

    private AutoWorld() {}

    public static void register() {
        if (!Boolean.getBoolean("mcrl.autoWorld")) return;
        ClientTickEvents.END_CLIENT_TICK.register(AutoWorld::onEndTick);
        LOG.info("[mcrl] auto-world enabled: will open {} at the title screen", WORLD_NAME);
    }

    private static void onEndTick(MinecraftClient client) {
        // Wait until the title screen is showing and the resource-reload overlay is gone; only once per run.
        if (attempted || client.getOverlay() != null || !(client.currentScreen instanceof TitleScreen)) return;
        attempted = true;
        try {
            if (client.getLevelStorage().levelExists(WORLD_NAME)) {
                LOG.info("[mcrl] auto-world: opening existing {}", WORLD_NAME);
                client.createIntegratedServerLoader().start(WORLD_NAME, () -> client.setScreen(new TitleScreen()));
            } else {
                LOG.info("[mcrl] auto-world: creating {} (survival, cheats on, superflat)", WORLD_NAME);
                create(client);
            }
        } catch (Exception e) {
            LOG.error("[mcrl] auto-world: failed to open {}; staying at the title screen", WORLD_NAME, e);
            client.setScreen(new TitleScreen());
        }
    }

    private static void create(MinecraftClient client) {
        LevelInfo info = new LevelInfo(WORLD_NAME, GameMode.SURVIVAL, false, Difficulty.NORMAL,
                true, new GameRules(), DataConfiguration.SAFE_MODE);
        // No structures (no superflat villages) and no bonus chest.
        GeneratorOptions options = new GeneratorOptions(GeneratorOptions.getRandomSeed(), false, false);
        client.createIntegratedServerLoader().createAndStart(WORLD_NAME, info, options,
                registries -> registries.get(RegistryKeys.WORLD_PRESET)
                        .entryOf(WorldPresets.FLAT).value().createDimensionsRegistryHolder(),
                new TitleScreen());
    }
}
