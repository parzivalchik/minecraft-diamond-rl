package dev.mcrl;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.CloudRenderMode;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.ParticlesMode;

/** Cheap-render options for the dedicated training client (spec §3.3). */
public final class RenderSettings {
    private RenderSettings() {}

    public static void apply(MinecraftClient client) {
        GameOptions o = client.options;
        o.getViewDistance().setValue(2);
        o.getSimulationDistance().setValue(5);
        o.getMaxFps().setValue(260); // 260 = unlimited
        o.getEnableVsync().setValue(false);
        o.getCloudRenderMode().setValue(CloudRenderMode.OFF);
        o.getParticles().setValue(ParticlesMode.MINIMAL);
        o.getBobView().setValue(false);
        o.pauseOnLostFocus = false; // training must keep running when the window is in the background
        o.write();
    }
}
