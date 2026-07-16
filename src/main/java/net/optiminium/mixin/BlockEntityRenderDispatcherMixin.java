package net.optiminium.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.optiminium.client.OptiminiumBlockEntityRenderCache;
import net.optiminium.client.OptiminiumPersistentBlockEntityMeshes;
import net.optiminium.client.OptiminiumBlockEntityVirtualizer;
import net.optiminium.client.OptiminiumRenderProfiler;
import net.optiminium.optimization.OptiminiumSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {
	@Redirect(method = "setupAndRender(Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V"))
	private static <E extends BlockEntity> void optiminium$renderPersistentMesh(BlockEntityRenderer<E> renderer,
			E blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource,
			int packedLight, int packedOverlay) {
		if (!OptiminiumPersistentBlockEntityMeshes.tryRender(renderer, blockEntity, partialTick, poseStack,
				packedLight, packedOverlay)) {
			if (OptiminiumPersistentBlockEntityMeshes.hasPendingGenericVanillaSample(blockEntity)) {
				long start = System.nanoTime();
				OptiminiumPersistentBlockEntityMeshes.renderVanillaWithSplit(renderer, blockEntity, partialTick,
					poseStack, bufferSource, packedLight, packedOverlay);
				OptiminiumPersistentBlockEntityMeshes.recordGenericVanilla(renderer, blockEntity,
					System.nanoTime() - start);
			} else {
				OptiminiumPersistentBlockEntityMeshes.renderVanillaWithSplit(renderer, blockEntity, partialTick,
					poseStack, bufferSource, packedLight, packedOverlay);
			}
		}
	}
	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private <E extends BlockEntity> void optiminium$virtualizeBlockEntityRender(E blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, CallbackInfo callback) {
		if (OptiminiumBlockEntityRenderCache.isDispatcherHookActive()) {
			OptiminiumBlockEntityRenderCache.recordDispatcherHook(blockEntity);
		}
		if (OptiminiumRenderProfiler.areUploadCategoriesActive()) {
			OptiminiumRenderProfiler.pushUploadCategory(OptiminiumRenderProfiler.UploadCategory.BLOCK_ENTITY_PROXY);
		}
		if (!OptiminiumSettings.isEnabled()
				|| (!OptiminiumSettings.isBlockEntityVirtualizationEnabled()
					&& !OptiminiumSettings.isBlockEntityRenderCacheDebug())) {
			return;
		}
		BlockEntityRenderer<E> renderer = ((BlockEntityRenderDispatcher)(Object)this).getRenderer(blockEntity);
		if (OptiminiumBlockEntityVirtualizer.tryVirtualize(blockEntity, partialTick, poseStack, bufferSource, renderer)) {
			OptiminiumRenderProfiler.popUploadCategory();
			callback.cancel();
			return;
		}
		OptiminiumBlockEntityVirtualizer.beginFullRenderer();
	}

	@Inject(method = "render", at = @At("RETURN"))
	private <E extends BlockEntity> void optiminium$popBlockEntityUploadCategory(E blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, CallbackInfo callback) {
		OptiminiumBlockEntityVirtualizer.finishFullRenderer();
		if (OptiminiumRenderProfiler.areUploadCategoriesActive()) {
			OptiminiumRenderProfiler.popUploadCategory();
		}
	}

	@Redirect(method = "setupAndRender(Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderer;Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;getLightColor(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;)I"))
	private static int optiminium$cachedPackedLight(BlockAndTintGetter level, BlockPos pos, BlockEntityRenderer<?> renderer, BlockEntity blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource) {
		return OptiminiumBlockEntityRenderCache.lightFor(level, pos, renderer, blockEntity, partialTick, OverlayTexture.NO_OVERLAY);
	}
}
