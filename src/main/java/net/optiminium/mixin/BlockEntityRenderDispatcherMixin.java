package net.optiminium.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.optiminium.client.OptiminiumBlockEntityCulling;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.optiminium.client.OptiminiumGpuOptimizer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {
	@Inject(
		method = "setupAndRender",
		at = @At("HEAD")
	)
	private static <T extends BlockEntity> void optiminium$countRenderedBlockEntity(BlockEntityRenderer<T> renderer, T blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource,
			CallbackInfo callback) {
		if (!OptiminiumBlockEntityCulling.isDistanceCullingRenderer(renderer)) {
			OptiminiumGpuOptimizer.recordRenderedBlockEntityAfterCulling();
		}
	}
}
