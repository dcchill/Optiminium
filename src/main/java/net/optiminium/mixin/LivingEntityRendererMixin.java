package net.optiminium.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.optiminium.client.OptiminiumMobAnimationThrottler;
import net.optiminium.client.OptiminiumPersistentBlockEntityMeshes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Audited interception for independently persistent armor-stand feature layers. */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
	@SuppressWarnings({"rawtypes", "unchecked"})
	@Redirect(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/model/EntityModel;prepareMobModel(Lnet/minecraft/world/entity/Entity;FFF)V"))
	private void optiminium$prepareThrottledMobAnimation(EntityModel model, Entity entity,
			float limbPosition, float limbSpeed, float partialTick) {
		if (entity instanceof Mob mob) {
			OptiminiumMobAnimationThrottler.prepare(model, mob, limbPosition, limbSpeed, partialTick);
		} else {
			model.prepareMobModel(entity, limbPosition, limbSpeed, partialTick);
		}
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	@Redirect(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V"))
	private void optiminium$setupThrottledMobAnimation(EntityModel model, Entity entity,
			float limbPosition, float limbSpeed, float ageInTicks, float netHeadYaw, float headPitch) {
		if (entity instanceof Mob mob) {
			OptiminiumMobAnimationThrottler.setup(model, mob, limbPosition, limbSpeed,
				ageInTicks, netHeadYaw, headPitch);
		} else {
			model.setupAnim(entity, limbPosition, limbSpeed, ageInTicks, netHeadYaw, headPitch);
		}
	}

	@Redirect(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/MultiBufferSource;getBuffer(Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"))
	private VertexConsumer optiminium$beginPersistentArmorStandBaseModel(MultiBufferSource buffers,
			RenderType renderType, LivingEntity entity, float yaw, float partialTick, PoseStack poseStack,
			MultiBufferSource originalBuffers, int packedLight) {
		if (!(entity instanceof ArmorStand stand)) return buffers.getBuffer(renderType);
		MultiBufferSource selected = OptiminiumPersistentBlockEntityMeshes.beginArmorStandModel(
			(EntityRenderer<?>)(Object)this, stand, buffers, poseStack);
		return selected.getBuffer(renderType);
	}

	@Redirect(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/model/EntityModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"))
	private void optiminium$finishPersistentArmorStandBaseModel(EntityModel<?> model, PoseStack poseStack,
			VertexConsumer consumer, int packedLight, int packedOverlay, int color) {
		if (OptiminiumPersistentBlockEntityMeshes.tryRenderReusedMobBaseModel(
				poseStack, packedLight, packedOverlay, color)) return;
		if (!OptiminiumPersistentBlockEntityMeshes.hasArmorStandModelPass()) {
			model.renderToBuffer(poseStack, consumer, packedLight, packedOverlay, color);
			return;
		}
		long start = System.nanoTime();
		try {
			model.renderToBuffer(poseStack, consumer, packedLight, packedOverlay, color);
		} finally {
			OptiminiumPersistentBlockEntityMeshes.endArmorStandModel(System.nanoTime() - start);
		}
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	@Redirect(method = "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/entity/layers/RenderLayer;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/Entity;FFFFFF)V"))
	private void optiminium$renderPersistentArmorStandLayer(RenderLayer layer, PoseStack poseStack,
			MultiBufferSource buffers, int packedLight, Entity entity,
			float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
			float netHeadYaw, float headPitch) {
		if (!(entity instanceof ArmorStand stand)) {
			layer.render(poseStack, buffers, packedLight, entity, limbSwing, limbSwingAmount,
				partialTick, ageInTicks, netHeadYaw, headPitch);
			return;
		}
		OptiminiumPersistentBlockEntityMeshes.renderArmorStandFeatureLayer(layer, stand,
			poseStack, buffers, packedLight,
			(renderPose, renderBuffers) -> layer.render(renderPose, renderBuffers, packedLight,
				stand, limbSwing, limbSwingAmount, partialTick, ageInTicks, netHeadYaw, headPitch));
	}
}
