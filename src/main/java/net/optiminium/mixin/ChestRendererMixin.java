package net.optiminium.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.blockentity.ChestRenderer;
import net.optiminium.client.OptiminiumPersistentBlockEntityMeshes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ChestRenderer.class)
public abstract class ChestRendererMixin {
	@Redirect(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/client/model/geom/ModelPart;Lnet/minecraft/client/model/geom/ModelPart;Lnet/minecraft/client/model/geom/ModelPart;FII)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/geom/ModelPart;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;II)V", ordinal = 2))
	private void optiminium$renderPersistentChestBottom(ModelPart bottom, PoseStack poseStack,
			VertexConsumer consumer, int packedLight, int packedOverlay) {
		if (!OptiminiumPersistentBlockEntityMeshes.tryRenderChestBottom(bottom, poseStack, consumer,
				packedLight, packedOverlay)) {
			bottom.render(poseStack, consumer, packedLight, packedOverlay);
		}
	}
}
