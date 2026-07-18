package net.optiminium.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.optiminium.client.OptiminiumPersistentBlockEntityMeshes;
import net.optiminium.client.OptiminiumRenderProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
	@Redirect(method = "render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/EntityRenderer;render(Lnet/minecraft/world/entity/Entity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"))
	private static <E extends Entity> void optiminium$renderPersistentArmorStand(EntityRenderer<? super E> renderer,
			E entity, float yaw, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
		OptiminiumRenderProfiler.recordRenderedEntity(entity);
		long sceneTimingStart = OptiminiumRenderProfiler.beginSceneRenderTiming();
		try {
		if (entity instanceof ArmorStand armorStand) {
			if (!OptiminiumPersistentBlockEntityMeshes.isArmorStandWholeCacheEnabled()) {
				renderer.render(entity, yaw, partialTick, poseStack, bufferSource, packedLight);
				return;
			}
			if (!OptiminiumPersistentBlockEntityMeshes.shouldEvaluateArmorStand()) {
				renderer.render(entity, yaw, partialTick, poseStack, bufferSource, packedLight);
				return;
			}
			@SuppressWarnings("unchecked")
			EntityRenderer<? super ArmorStand> armorRenderer = (EntityRenderer<? super ArmorStand>)(EntityRenderer<?>)renderer;
			// Prefer the complete stable-render cache. It avoids LivingEntityRenderer setup,
			// NeoForge render events and all empty/equipped feature-layer traversal together.
			// The exact wooden-part experiment remains available only when a complete render
			// has not qualified, and its safety veto does not prevent this alternative policy.
			if (OptiminiumPersistentBlockEntityMeshes.tryRenderArmorStand(armorRenderer, armorStand,
					yaw, partialTick, poseStack, packedLight)) {
				return;
			}
			long start = System.nanoTime();
			renderer.render(entity, yaw, partialTick, poseStack, bufferSource, packedLight);
			OptiminiumPersistentBlockEntityMeshes.recordArmorStandVanilla(armorStand, System.nanoTime() - start);
			return;
		}
		if (entity instanceof Mob mob) {
			if (!OptiminiumPersistentBlockEntityMeshes.shouldEvaluateMob(mob)) {
				renderer.render(entity, yaw, partialTick, poseStack, bufferSource, packedLight);
				return;
			}
			MultiBufferSource persistentSource = OptiminiumPersistentBlockEntityMeshes.beginMob(renderer, mob, bufferSource);
			long start = System.nanoTime();
			try {
				renderer.render(entity, yaw, partialTick, poseStack, persistentSource, packedLight);
			} finally {
				OptiminiumPersistentBlockEntityMeshes.endMob(mob, System.nanoTime() - start);
			}
			return;
		}
		if (OptiminiumPersistentBlockEntityMeshes.tryRenderPersistentEntity(renderer, entity,
				yaw, partialTick, poseStack, packedLight)) return;
		long vanillaStart = System.nanoTime();
		renderer.render(entity, yaw, partialTick, poseStack, bufferSource, packedLight);
		OptiminiumPersistentBlockEntityMeshes.recordPersistentEntityVanilla(renderer, entity,
			System.nanoTime() - vanillaStart);
		} finally {
			OptiminiumRenderProfiler.finishRenderedEntity(entity, sceneTimingStart);
		}
	}
}
