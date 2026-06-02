package net.optiminium.mixin;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.optiminium.optimization.OptiminiumSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelLightEngine.class)
public abstract class LevelLightEngineMixin {
	@Unique
	private final LongOpenHashSet optiminium$queuedLightChecks = new LongOpenHashSet();

	@Inject(method = "checkBlock", at = @At("HEAD"), cancellable = true)
	private void optiminium$deduplicateBlockLightChecks(BlockPos pos, CallbackInfo callback) {
		if (!OptiminiumSettings.isEnabled() || !OptiminiumSettings.isLightingDeduplication()) {
			return;
		}
		if (!this.optiminium$queuedLightChecks.add(pos.asLong())) {
			callback.cancel();
		}
	}

	@Inject(method = "runLightUpdates", at = @At("RETURN"))
	private void optiminium$clearDeduplicatedLightChecks(CallbackInfoReturnable<Integer> callback) {
		this.optiminium$queuedLightChecks.clear();
	}
}
