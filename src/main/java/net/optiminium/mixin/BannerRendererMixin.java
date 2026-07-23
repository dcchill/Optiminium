package net.optiminium.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.blockentity.BannerRenderer;
import net.optiminium.client.OptiminiumPersistentBlockEntityMeshes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(BannerRenderer.class)
public abstract class BannerRendererMixin {
	@Redirect(method = "render(Lnet/minecraft/world/level/block/entity/BannerBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/geom/ModelPart;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;II)V"))
	private void optiminium$renderPersistentBannerStructure(ModelPart part, PoseStack poseStack,
			VertexConsumer consumer, int packedLight, int packedOverlay) {
		if (!OptiminiumPersistentBlockEntityMeshes.tryRenderBannerPart(part, poseStack, consumer,
				packedLight, packedOverlay)) {
			long start = System.nanoTime();
			part.render(poseStack, consumer, packedLight, packedOverlay);
			OptiminiumPersistentBlockEntityMeshes.recordBannerPartVanilla(System.nanoTime() - start);
		}
	}
}
