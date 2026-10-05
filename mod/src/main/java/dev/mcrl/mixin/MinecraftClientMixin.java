package dev.mcrl.mixin;

import dev.mcrl.StepController;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @Inject(method = "handleBlockBreaking", at = @At("HEAD"), cancellable = true)
    private void mcrl$driveBreaking(boolean breaking, CallbackInfo ci) {
        StepController controller = StepController.get();
        if (controller != null && controller.driveBreaking((MinecraftClient) (Object) this)) {
            ci.cancel();
        }
    }
}
