package net.optiminium.mixin;

import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.renderer.RenderType;
import net.optiminium.client.OptiminiumPersistentBlockEntityMeshes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Appends compatible persistent atlas draws before vanilla clears an already-active RenderType. */
@Mixin(RenderType.class)
public abstract class RenderTypeMixin {
	@Inject(method = "draw", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/RenderType;clearRenderState()V"))
	private void optiminium$flushCompatibleAtlas(MeshData meshData, CallbackInfo callback) {
		OptiminiumPersistentBlockEntityMeshes.flushQueuedInActiveState((RenderType)(Object)this);
	}

	@Inject(method = "draw", at = @At("RETURN"))
	private void optiminium$validateAtlasState(MeshData meshData, CallbackInfo callback) {
		OptiminiumPersistentBlockEntityMeshes.finishAtlasStateAfterVanillaDraw();
	}
}
