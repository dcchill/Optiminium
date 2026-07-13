package net.optiminium.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.blockentity.SignRenderer;
import net.optiminium.client.OptiminiumPersistentBlockEntityMeshes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(SignRenderer.class)
public abstract class SignRendererMixin {
	@Redirect(method = "renderSignModel(Lcom/mojang/blaze3d/vertex/PoseStack;IILnet/minecraft/client/model/Model;Lcom/mojang/blaze3d/vertex/VertexConsumer;)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/geom/ModelPart;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;II)V"))
	private void optiminium$renderPersistentSignBoard(ModelPart root, PoseStack poseStack,
			VertexConsumer consumer, int packedLight, int packedOverlay) {
		if (!OptiminiumPersistentBlockEntityMeshes.tryRenderSignBoard(root, poseStack, consumer,
				packedLight, packedOverlay)) {
			root.render(poseStack, consumer, packedLight, packedOverlay);
		}
	}
}
