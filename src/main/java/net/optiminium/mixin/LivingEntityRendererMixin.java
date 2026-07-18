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
import net.minecraft.world.entity.decoration.ArmorStand;
import net.optiminium.client.OptiminiumPersistentBlockEntityMeshes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Audited interception for independently persistent armor-stand feature layers. */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
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
