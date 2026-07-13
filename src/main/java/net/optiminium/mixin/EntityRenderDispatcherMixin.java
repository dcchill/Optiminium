package net.optiminium.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.optiminium.client.OptiminiumPersistentBlockEntityMeshes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
	@Redirect(method = "render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/EntityRenderer;render(Lnet/minecraft/world/entity/Entity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"))
	private static <E extends Entity> void optiminium$renderPersistentArmorStand(EntityRenderer<? super E> renderer,
			E entity, float yaw, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
		if (entity instanceof ArmorStand armorStand) {
			@SuppressWarnings("unchecked")
			EntityRenderer<? super ArmorStand> armorRenderer = (EntityRenderer<? super ArmorStand>)(EntityRenderer<?>)renderer;
			if (OptiminiumPersistentBlockEntityMeshes.tryRenderArmorStand(armorRenderer, armorStand,
					yaw, partialTick, poseStack, packedLight)) {
				return;
			}
			long start = System.nanoTime();
			renderer.render(entity, yaw, partialTick, poseStack, bufferSource, packedLight);
			OptiminiumPersistentBlockEntityMeshes.recordArmorStandVanilla(armorStand, System.nanoTime() - start);
			return;
		}
		renderer.render(entity, yaw, partialTick, poseStack, bufferSource, packedLight);
	}
}
