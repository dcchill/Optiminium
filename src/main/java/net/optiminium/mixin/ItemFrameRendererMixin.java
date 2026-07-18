package net.optiminium.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.entity.ItemFrameRenderer;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.optiminium.client.OptiminiumPersistentBlockEntityMeshes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Splits the static frame backing from vanilla item/map/event rendering. */
@Mixin(ItemFrameRenderer.class)
public abstract class ItemFrameRendererMixin {
	@Unique
	private ItemFrame optiminium$currentFrame;

	@Inject(method = "render(Lnet/minecraft/world/entity/decoration/ItemFrame;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
		at = @At("HEAD"))
	private void optiminium$beginFrameBacking(ItemFrame frame, float yaw, float partialTick,
			PoseStack poseStack, MultiBufferSource buffers, int packedLight, CallbackInfo callback) {
		optiminium$currentFrame = frame;
	}

	@Inject(method = "render(Lnet/minecraft/world/entity/decoration/ItemFrame;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
		at = @At("RETURN"))
	private void optiminium$endFrameBacking(ItemFrame frame, float yaw, float partialTick,
			PoseStack poseStack, MultiBufferSource buffers, int packedLight, CallbackInfo callback) {
		optiminium$currentFrame = null;
	}

	@Redirect(method = "render(Lnet/minecraft/world/entity/decoration/ItemFrame;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;renderModel(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/client/resources/model/BakedModel;FFFII)V"))
	private void optiminium$renderPersistentFrameBacking(ModelBlockRenderer renderer,
			PoseStack.Pose pose, VertexConsumer consumer, BlockState state, BakedModel model,
			float red, float green, float blue, int packedLight, int packedOverlay) {
		ItemFrame frame = optiminium$currentFrame;
		if (frame == null) {
			renderer.renderModel(pose, consumer, state, model, red, green, blue, packedLight, packedOverlay);
			return;
		}
		OptiminiumPersistentBlockEntityMeshes.renderItemFrameBacking(frame, model, pose,
			packedLight, packedOverlay, consumer,
			(renderPose, output) -> renderer.renderModel(renderPose, output, state, model,
				red, green, blue, packedLight, packedOverlay));
	}

	@Redirect(method = "render(Lnet/minecraft/world/entity/decoration/ItemFrame;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;renderStatic(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;IILcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/level/Level;I)V"))
	private void optiminium$renderPersistentFrameItem(ItemRenderer renderer, ItemStack stack,
			ItemDisplayContext context, int packedLight, int packedOverlay, PoseStack pose,
			MultiBufferSource buffers, Level level, int modelSeed) {
		ItemFrame frame = optiminium$currentFrame;
		if (frame == null) {
			renderer.renderStatic(stack, context, packedLight, packedOverlay, pose, buffers, level, modelSeed);
			return;
		}
		OptiminiumPersistentBlockEntityMeshes.renderItemFrameItem(frame, renderer, stack, pose,
			buffers, packedLight, packedOverlay, level, modelSeed,
			(renderPose, renderBuffers, light, overlay) -> renderer.renderStatic(stack, context,
				light, overlay, renderPose, renderBuffers, level, modelSeed));
	}
}
