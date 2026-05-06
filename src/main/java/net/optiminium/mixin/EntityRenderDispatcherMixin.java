package net.optiminium.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.optiminium.client.OptiminiumGpuOptimizer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
	@Inject(
		method = "render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private <E extends Entity> void optiminium$cullNonLivingEntities(E entity, double x, double y, double z, float rotationYaw, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource,
			int packedLight, CallbackInfo callback) {
		if (OptiminiumGpuOptimizer.shouldSkipEntityRender(entity)) {
			callback.cancel();
		}
	}
}
