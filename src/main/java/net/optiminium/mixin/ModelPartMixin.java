package net.optiminium.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import net.optiminium.client.OptiminiumPersistentBlockEntityMeshes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ModelPart.class)
public abstract class ModelPartMixin {
	@Redirect(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/model/geom/ModelPart;compile(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"))
	private void optiminium$renderPersistentMobBone(ModelPart part, PoseStack.Pose pose,
			VertexConsumer consumer, int packedLight, int packedOverlay, int color) {
		ModelPartAccessor accessor = (ModelPartAccessor)(Object)part;
		if (!OptiminiumPersistentBlockEntityMeshes.hasActiveModelPartContext()) {
			accessor.optiminium$compile(pose, consumer, packedLight, packedOverlay, color);
			return;
		}
		if (!OptiminiumPersistentBlockEntityMeshes.tryRenderMobPart(part, pose,
				consumer, packedLight, packedOverlay, color)) {
			accessor.optiminium$compile(pose, consumer, packedLight, packedOverlay, color);
		}
		OptiminiumPersistentBlockEntityMeshes.finishMobPart();
	}
}
