package net.optiminium.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelReader;
import net.optiminium.client.OptiminiumFadeBufferSource;
import net.optiminium.client.OptiminiumGpuOptimizer;
import net.optiminium.client.OptiminiumVisualSignificance;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
	@Shadow
	private static void renderShadow(PoseStack poseStack, MultiBufferSource bufferSource, Entity entity, float weight, float partialTick, LevelReader level, float size) {
		throw new AssertionError();
	}

	@Inject(
		method = "render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private <E extends Entity> void optiminium$cullNonLivingEntities(E entity, double x, double y, double z, float rotationYaw, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource,
			int packedLight, CallbackInfo callback) {
		if (OptiminiumGpuOptimizer.shouldSkipEntityRender(entity)) {
			callback.cancel();
			return;
		}
		float alpha = OptiminiumVisualSignificance.entityFadeAlpha(entity);
		if (alpha < 1.0F) {
			RenderSystem.enableBlend();
			RenderSystem.defaultBlendFunc();
			RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);
		}
	}

	@Inject(
		method = "render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
		at = @At("RETURN")
	)
	private <E extends Entity> void optiminium$resetEntityFade(E entity, double x, double y, double z, float rotationYaw, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource,
			int packedLight, CallbackInfo callback) {
		RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
		RenderSystem.disableBlend();
	}

	@ModifyVariable(
		method = "render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
		at = @At("HEAD"),
		argsOnly = true
	)
	private <E extends Entity> MultiBufferSource optiminium$fadeEntityBuffer(MultiBufferSource bufferSource, E entity) {
		return OptiminiumFadeBufferSource.wrap(bufferSource, OptiminiumVisualSignificance.entityFadeAlpha(entity));
	}

	@Redirect(
		method = "render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;renderShadow(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/entity/Entity;FFLnet/minecraft/world/level/LevelReader;F)V")
	)
	private <E extends Entity> void optiminium$cullEntityShadow(PoseStack poseStack, MultiBufferSource bufferSource, E entity, float weight, float partialTick, LevelReader level, float size) {
		if (!OptiminiumGpuOptimizer.shouldSkipEntityShadow(entity)) {
			renderShadow(poseStack, bufferSource, entity, weight, partialTick, level, size);
		}
	}
}
