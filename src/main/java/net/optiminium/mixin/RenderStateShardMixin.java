package net.optiminium.mixin;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.optiminium.client.OptiminiumRenderProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderStateShard.class)
public abstract class RenderStateShardMixin {
	@Inject(method = "setupRenderState", at = @At("HEAD"))
	private void optiminium$countRenderLayerSwitch(CallbackInfo callback) {
		if ((Object)this instanceof RenderType) {
			OptiminiumRenderProfiler.recordRenderLayerSwitch();
		}
	}
}
