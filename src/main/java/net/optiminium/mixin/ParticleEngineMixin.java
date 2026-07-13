package net.optiminium.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.entity.Entity;
import net.optiminium.client.OptiminiumGpuOptimizer;
import net.optiminium.client.OptiminiumRenderProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

@Mixin(ParticleEngine.class)
public abstract class ParticleEngineMixin {
	@Inject(method = "createParticle", at = @At("HEAD"), cancellable = true)
	private void optiminium$skipParticleCreate(ParticleOptions options, double x, double y, double z, double xSpeed, double ySpeed, double zSpeed, CallbackInfoReturnable<Particle> callback) {
		if (!OptiminiumGpuOptimizer.shouldProcessParticleHookThisFrame()) {
			return;
		}
		if (OptiminiumGpuOptimizer.shouldSkipParticle(options, x, y, z)) {
			callback.setReturnValue(null);
		}
	}

	@Inject(method = "createTrackingEmitter(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/particles/ParticleOptions;)V", at = @At("HEAD"), cancellable = true)
	private void optiminium$skipTrackingEmitter(Entity entity, ParticleOptions options, CallbackInfo callback) {
		if (!OptiminiumGpuOptimizer.shouldProcessParticleHookThisFrame()) {
			return;
		}
		if (OptiminiumGpuOptimizer.shouldSkipParticle(options, entity.getX(), entity.getY(), entity.getZ())) {
			callback.cancel();
		}
	}

	@Inject(method = "createTrackingEmitter(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/particles/ParticleOptions;I)V", at = @At("HEAD"), cancellable = true)
	private void optiminium$skipTimedTrackingEmitter(Entity entity, ParticleOptions options, int lifetime, CallbackInfo callback) {
		if (!OptiminiumGpuOptimizer.shouldProcessParticleHookThisFrame()) {
			return;
		}
		if (OptiminiumGpuOptimizer.shouldSkipParticle(options, entity.getX(), entity.getY(), entity.getZ())) {
			callback.cancel();
		}
	}

	@Inject(method = "render(Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;Ljava/util/function/Predicate;)V",
		at = @At("HEAD"))
	private void optiminium$pushParticleUploadCategory(LightTexture lightTexture, Camera camera, float partialTick, Frustum frustum, Predicate<ParticleRenderType> renderTypePredicate, CallbackInfo callback) {
		if (OptiminiumRenderProfiler.areUploadCategoriesActive()) {
			OptiminiumRenderProfiler.pushUploadCategory(OptiminiumRenderProfiler.UploadCategory.PARTICLES_EFFECTS);
		}
	}

	@Inject(method = "render(Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;Ljava/util/function/Predicate;)V",
		at = @At("RETURN"))
	private void optiminium$popParticleUploadCategory(LightTexture lightTexture, Camera camera, float partialTick, Frustum frustum, Predicate<ParticleRenderType> renderTypePredicate, CallbackInfo callback) {
		if (OptiminiumRenderProfiler.areUploadCategoriesActive()) {
			OptiminiumRenderProfiler.popUploadCategory();
		}
	}

	@Inject(method = "render(Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;Ljava/util/function/Predicate;)V",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/BufferUploader;drawWithShader(Lcom/mojang/blaze3d/vertex/MeshData;)V"))
	private void optiminium$profileParticleRender(LightTexture lightTexture, Camera camera, float partialTick, Frustum frustum, Predicate<ParticleRenderType> renderTypePredicate, CallbackInfo callback) {
		if (OptiminiumRenderProfiler.isEnabled()) {
			OptiminiumRenderProfiler.recordParticleRender();
		}
	}
}
