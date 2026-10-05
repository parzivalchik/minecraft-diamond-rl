package dev.mcrl.mixin;

import dev.mcrl.StepController;
import dev.mcrl.arena.ArenaManager;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @Inject(method = "handleBlockBreaking", at = @At("HEAD"), cancellable = true)
    private void mcrl$driveBreaking(boolean breaking, CallbackInfo ci) {
        StepController controller = StepController.get();
        if (controller != null && controller.driveBreaking((MinecraftClient) (Object) this)) {
            ci.cancel();
        }
    }

    /**
     * Vanilla returns max(default 50 ms, server mspt), capping client ticks at 20 Hz even when the
     * server runs faster. While driving, run client ticks at ArenaManager.TICK_RATE as well.
     */
    @Inject(method = "getTargetMillisPerTick", at = @At("HEAD"), cancellable = true)
    private void mcrl$fastClientTicks(float defaultMillisPerTick, CallbackInfoReturnable<Float> cir) {
        StepController controller = StepController.get();
        if (controller != null && controller.isDriving((MinecraftClient) (Object) this)) {
            cir.setReturnValue(1000f / ArenaManager.TICK_RATE);
        }
    }
}
