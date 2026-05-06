package net.optiminium.mixin;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.entity.Entity;
import net.optiminium.client.OptiminiumGpuOptimizer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ParticleEngine.class)
public abstract class ParticleEngineMixin {
	@Inject(method = "createParticle", at = @At("HEAD"), cancellable = true)
	private void optiminium$skipParticleCreate(ParticleOptions options, double x, double y, double z, double xSpeed, double ySpeed, double zSpeed, CallbackInfoReturnable<Particle> callback) {
		if (OptiminiumGpuOptimizer.shouldSkipParticle(options, x, y, z)) {
			callback.setReturnValue(null);
		}
	}

	@Inject(method = "createTrackingEmitter(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/particles/ParticleOptions;)V", at = @At("HEAD"), cancellable = true)
	private void optiminium$skipTrackingEmitter(Entity entity, ParticleOptions options, CallbackInfo callback) {
		if (OptiminiumGpuOptimizer.shouldSkipParticle(options, entity.getX(), entity.getY(), entity.getZ())) {
			callback.cancel();
		}
	}

	@Inject(method = "createTrackingEmitter(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/particles/ParticleOptions;I)V", at = @At("HEAD"), cancellable = true)
	private void optiminium$skipTimedTrackingEmitter(Entity entity, ParticleOptions options, int lifetime, CallbackInfo callback) {
		if (OptiminiumGpuOptimizer.shouldSkipParticle(options, entity.getX(), entity.getY(), entity.getZ())) {
			callback.cancel();
		}
	}
}
