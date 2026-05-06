package net.optiminium.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import net.optiminium.optimization.OptiminiumMetrics;
import net.optiminium.optimization.OptiminiumSettings;

public final class OptiminiumGpuOptimizer {
	private static final double ITEM_ENTITY_RENDER_DISTANCE_SQR = 48.0D * 48.0D;
	private static final double EXPERIENCE_ORB_RENDER_DISTANCE_SQR = 32.0D * 32.0D;
	private static final double HANGING_ENTITY_RENDER_DISTANCE_SQR = 64.0D * 64.0D;
	private static final double PROJECTILE_RENDER_DISTANCE_SQR = 96.0D * 96.0D;
	private static final double FALLING_BLOCK_RENDER_DISTANCE_SQR = 96.0D * 96.0D;
	private static final double PARTICLE_RENDER_DISTANCE_SQR = 64.0D * 64.0D;
	private static final double LOW_PRIORITY_PARTICLE_DISTANCE_SQR = 32.0D * 32.0D;
	private static final int MAX_PARTICLES_PER_FRAME = 96;
	private static final int MAX_LOW_PRIORITY_PARTICLES_PER_FRAME = 32;

	private static int particlesThisFrame;
	private static int lowPriorityParticlesThisFrame;

	private OptiminiumGpuOptimizer() {
	}

	public static void onFrameStart() {
		particlesThisFrame = 0;
		lowPriorityParticlesThisFrame = 0;
	}

	public static boolean shouldSkipEntityRender(Entity entity) {
		if (!OptiminiumSettings.isEnabled() || entity instanceof LivingEntity || entity instanceof Player || entity == Minecraft.getInstance().cameraEntity) {
			return false;
		}
		if (entity.hasGlowingTag() || entity.hasCustomName() || entity.isPassenger() || !entity.getPassengers().isEmpty() || entity.displayFireAnimation()) {
			return false;
		}

		Vec3 camera = cameraPosition();
		if (camera == null) {
			return false;
		}

		double distanceSqr = camera.distanceToSqr(entity.position());
		boolean skip = false;
		if (entity instanceof ItemEntity) {
			skip = distanceSqr > ITEM_ENTITY_RENDER_DISTANCE_SQR;
		} else if (entity instanceof ExperienceOrb) {
			skip = distanceSqr > EXPERIENCE_ORB_RENDER_DISTANCE_SQR;
		} else if (entity instanceof HangingEntity) {
			skip = distanceSqr > HANGING_ENTITY_RENDER_DISTANCE_SQR;
		} else if (entity instanceof Projectile) {
			skip = distanceSqr > PROJECTILE_RENDER_DISTANCE_SQR;
		} else if (entity instanceof FallingBlockEntity) {
			skip = distanceSqr > FALLING_BLOCK_RENDER_DISTANCE_SQR;
		}
		if (skip) {
			OptiminiumMetrics.culledEntityRender();
		}
		return skip;
	}

	public static boolean shouldSkipParticle(ParticleOptions options, double x, double y, double z) {
		if (!OptiminiumSettings.isEnabled() || isImportantParticle(options)) {
			return false;
		}

		Vec3 camera = cameraPosition();
		if (camera == null) {
			return false;
		}

		double distanceSqr = camera.distanceToSqr(x, y, z);
		boolean lowPriority = isLowPriorityParticle(options);
		boolean skip = distanceSqr > PARTICLE_RENDER_DISTANCE_SQR
			|| lowPriority && distanceSqr > LOW_PRIORITY_PARTICLE_DISTANCE_SQR
			|| particlesThisFrame >= MAX_PARTICLES_PER_FRAME
			|| lowPriority && lowPriorityParticlesThisFrame >= MAX_LOW_PRIORITY_PARTICLES_PER_FRAME;
		if (skip) {
			OptiminiumMetrics.hiddenParticle();
			return true;
		}

		particlesThisFrame++;
		if (lowPriority) {
			lowPriorityParticlesThisFrame++;
		}
		return false;
	}

	private static Vec3 cameraPosition() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.gameRenderer == null || minecraft.gameRenderer.getMainCamera() == null) {
			return null;
		}
		return minecraft.gameRenderer.getMainCamera().getPosition();
	}

	private static boolean isImportantParticle(ParticleOptions options) {
		ParticleType<?> type = options.getType();
		return type == ParticleTypes.EXPLOSION_EMITTER
			|| type == ParticleTypes.EXPLOSION
			|| type == ParticleTypes.FLASH
			|| type == ParticleTypes.FIREWORK
			|| type == ParticleTypes.TOTEM_OF_UNDYING
			|| type == ParticleTypes.DAMAGE_INDICATOR
			|| type == ParticleTypes.ELDER_GUARDIAN;
	}

	private static boolean isLowPriorityParticle(ParticleOptions options) {
		ParticleType<?> type = options.getType();
		return type == ParticleTypes.ASH
			|| type == ParticleTypes.CLOUD
			|| type == ParticleTypes.MYCELIUM
			|| type == ParticleTypes.RAIN
			|| type == ParticleTypes.SMOKE
			|| type == ParticleTypes.WHITE_SMOKE
			|| type == ParticleTypes.SNOWFLAKE
			|| type == ParticleTypes.SPORE_BLOSSOM_AIR
			|| type == ParticleTypes.CRIMSON_SPORE
			|| type == ParticleTypes.WARPED_SPORE
			|| type == ParticleTypes.UNDERWATER;
	}
}
