package net.optiminium.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.optiminium.client.OptiminiumBenchmark;
import net.optiminium.client.OptiminiumFpsOptimizer;
import net.optiminium.client.OptiminiumGpuTimer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Preserves NeoForge's whole-frame pre/post event semantics on Fabric.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void optiminium$onFrameStart(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo callback) {
		OptiminiumGpuTimer.onFrameStart();
		OptiminiumFpsOptimizer.onFrameStart();
		OptiminiumBenchmark.onFrame();
	}

	@Inject(method = "render", at = @At("RETURN"))
	private void optiminium$onFrameEnd(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo callback) {
		OptiminiumBenchmark.onFrameComplete();
		OptiminiumGpuTimer.onFrameEnd();
	}
}
