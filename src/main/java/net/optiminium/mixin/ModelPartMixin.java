package net.optiminium.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import net.optiminium.client.OptiminiumPersistentBlockEntityMeshes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ModelPart.class)
public abstract class ModelPartMixin {
	@Inject(method = "compile", at = @At("HEAD"), cancellable = true)
	private void optiminium$renderPersistentMobBone(PoseStack.Pose pose, VertexConsumer consumer,
			int packedLight, int packedOverlay, int color, CallbackInfo callback) {
		if (OptiminiumPersistentBlockEntityMeshes.tryRenderMobPart((ModelPart)(Object)this, pose,
				consumer, packedLight, packedOverlay, color)) {
			callback.cancel();
		}
	}

	@Inject(method = "compile", at = @At("RETURN"))
	private void optiminium$finishPersistentMobBone(PoseStack.Pose pose, VertexConsumer consumer,
			int packedLight, int packedOverlay, int color, CallbackInfo callback) {
		OptiminiumPersistentBlockEntityMeshes.finishMobPart();
	}
}
