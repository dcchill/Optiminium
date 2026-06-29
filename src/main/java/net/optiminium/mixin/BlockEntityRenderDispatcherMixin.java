package net.optiminium.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.optiminium.client.OptiminiumBlockEntityLod;
import net.optiminium.client.OptiminiumBlockEntityCulling;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.optiminium.client.OptiminiumFadeBufferSource;
import net.optiminium.client.OptiminiumGpuOptimizer;
import net.optiminium.client.OptiminiumVisualSignificance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {
	@Inject(
		method = "setupAndRender",
		at = @At("HEAD"),
		cancellable = true
	)
	private static <T extends BlockEntity> void optiminium$countRenderedBlockEntity(BlockEntityRenderer<T> renderer, T blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource,
			CallbackInfo callback) {
		if (!OptiminiumBlockEntityCulling.isDistanceCullingRenderer(renderer)) {
			OptiminiumBlockEntityLod.observe(blockEntity, renderer.getViewDistance());
			if (!OptiminiumGpuOptimizer.shouldRenderBlockEntity(blockEntity, renderer.getViewDistance())) {
				callback.cancel();
				return;
			}
			OptiminiumBlockEntityLod.recordRendered(blockEntity, renderer.getViewDistance());
			float alpha = OptiminiumVisualSignificance.blockEntityFadeAlpha(blockEntity);
			if (alpha < 1.0F) {
				RenderSystem.enableBlend();
				RenderSystem.defaultBlendFunc();
				RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);
			}
			OptiminiumGpuOptimizer.recordRenderedBlockEntityAfterCulling();
		}
	}

	@Inject(method = "setupAndRender", at = @At("RETURN"))
	private static <T extends BlockEntity> void optiminium$resetFade(BlockEntityRenderer<T> renderer, T blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource,
			CallbackInfo callback) {
		RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
		RenderSystem.disableBlend();
	}

	@ModifyVariable(
		method = "setupAndRender",
		at = @At("HEAD"),
		argsOnly = true
	)
	private static <T extends BlockEntity> MultiBufferSource optiminium$fadeBlockEntityBuffer(MultiBufferSource bufferSource, BlockEntityRenderer<T> renderer, T blockEntity) {
		return OptiminiumFadeBufferSource.wrap(bufferSource, OptiminiumVisualSignificance.blockEntityFadeAlpha(blockEntity));
	}
}
