package net.optiminium.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.MinecartRenderer;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.level.block.state.BlockState;
import net.optiminium.client.OptiminiumPersistentBlockEntityMeshes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Audited split that replaces only the final, static cart-model traversal. */
@Mixin(MinecartRenderer.class)
public abstract class MinecartRendererMixin {
	@Unique private AbstractMinecart optiminium$currentMinecart;
	@Unique private RenderType optiminium$currentMinecartRenderType;

	@Inject(method = "render(Lnet/minecraft/world/entity/vehicle/AbstractMinecart;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
		at = @At("HEAD"))
	private void optiminium$beginMinecart(AbstractMinecart cart, float yaw, float partialTick,
			PoseStack pose, MultiBufferSource buffers, int packedLight, CallbackInfo callback) {
		optiminium$currentMinecart = cart;
		optiminium$currentMinecartRenderType = null;
	}

	@Inject(method = "render(Lnet/minecraft/world/entity/vehicle/AbstractMinecart;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
		at = @At("RETURN"))
	private void optiminium$endMinecart(AbstractMinecart cart, float yaw, float partialTick,
			PoseStack pose, MultiBufferSource buffers, int packedLight, CallbackInfo callback) {
		optiminium$currentMinecart = null;
		optiminium$currentMinecartRenderType = null;
	}

	@Redirect(method = "render(Lnet/minecraft/world/entity/vehicle/AbstractMinecart;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/MultiBufferSource;getBuffer(Lnet/minecraft/client/renderer/RenderType;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"))
	private VertexConsumer optiminium$rememberMinecartRenderType(MultiBufferSource buffers,
			RenderType renderType) {
		optiminium$currentMinecartRenderType = renderType;
		return buffers.getBuffer(renderType);
	}

	@Redirect(method = "render(Lnet/minecraft/world/entity/vehicle/AbstractMinecart;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/model/EntityModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;II)V"))
	private void optiminium$renderPersistentMinecartModel(EntityModel<?> model, PoseStack pose,
			VertexConsumer consumer, int packedLight, int packedOverlay) {
		AbstractMinecart cart = optiminium$currentMinecart;
		RenderType renderType = optiminium$currentMinecartRenderType;
		if (cart == null || renderType == null) {
			model.renderToBuffer(pose, consumer, packedLight, packedOverlay);
			return;
		}
		OptiminiumPersistentBlockEntityMeshes.renderMinecartModel(cart, model, pose, consumer,
			renderType, packedLight, packedOverlay, model::renderToBuffer);
	}

	@Redirect(method = "renderMinecartContents(Lnet/minecraft/world/entity/vehicle/AbstractMinecart;FLnet/minecraft/world/level/block/state/BlockState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;renderSingleBlock(Lnet/minecraft/world/level/block/state/BlockState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V"))
	private void optiminium$renderPersistentDisplayBlock(BlockRenderDispatcher renderer, BlockState state,
			PoseStack pose, MultiBufferSource buffers, int packedLight, int packedOverlay) {
		AbstractMinecart cart = optiminium$currentMinecart;
		if (cart == null) {
			renderer.renderSingleBlock(state, pose, buffers, packedLight, packedOverlay);
			return;
		}
		OptiminiumPersistentBlockEntityMeshes.renderMinecartDisplayBlock(cart, renderer, state, pose,
			buffers, packedLight, packedOverlay,
			(renderPose, renderBuffers, light, overlay) -> renderer.renderSingleBlock(state,
				renderPose, renderBuffers, light, overlay));
	}
}
