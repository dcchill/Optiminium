package net.optiminium.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
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
	private static final double FRAME_SMOOTHING = 0.12D;
	private static final double GPU_SCALE_STEP_DOWN = 0.04D;
	private static final double GPU_SCALE_STEP_UP = 0.015D;
	private static final double SHADOW_CULL_DISTANCE_SQR = 18.0D * 18.0D;
	private static final double NON_LIVING_SHADOW_CULL_DISTANCE_SQR = 10.0D * 10.0D;
	private static final double BLOCK_ENTITY_RENDER_BUDGET_DISTANCE_SQR = 14.0D * 14.0D;

	private static int particlesThisFrame;
	private static int lowPriorityParticlesThisFrame;
	private static int distantBlockEntityRendersThisFrame;
	private static int pendingCulledEntityRenders;
	private static int pendingCulledBlockEntityRenders;
	private static int pendingHiddenNameTags;
	private static int pendingHiddenParticles;
	private static boolean frameStateReady;
	private static boolean clientRenderCulling;
	private static boolean blockEntityCulling;
	private static boolean particleLimiter;
	private static boolean hasCamera;
	private static Entity cameraEntity;
	private static double cameraX;
	private static double cameraY;
	private static double cameraZ;
	private static double itemEntityRenderDistanceSqr;
	private static double experienceOrbRenderDistanceSqr;
	private static double hangingEntityRenderDistanceSqr;
	private static double projectileRenderDistanceSqr;
	private static double fallingBlockRenderDistanceSqr;
	private static double particleRenderDistanceSqr;
	private static double lowPriorityParticleDistanceSqr;
	private static double gpuWorkScale = 1.0D;
	private static long lastFrameNanos;
	private static double smoothedFrameNanos;
	private static long latestCpuFrameNanos;
	private static int maxParticlesPerFrame;
	private static int maxLowPriorityParticlesPerFrame;
	private static float blockEntityDistanceScale;

	private OptiminiumGpuOptimizer() {
	}

	public static void onFrameStart() {
		flushMetrics();
		particlesThisFrame = 0;
		lowPriorityParticlesThisFrame = 0;
		distantBlockEntityRendersThisFrame = 0;
		boolean enabled = OptiminiumSettings.isEnabled();
		updateGpuWorkScale(enabled);
		clientRenderCulling = enabled && OptiminiumSettings.isClientRenderCulling();
		blockEntityCulling = enabled && OptiminiumSettings.isBlockEntityCulling();
		particleLimiter = enabled && OptiminiumSettings.isParticleLimiter();

		double entityDistanceScale = OptiminiumSettings.getEntityRenderDistanceScalePercent() / 100.0D * gpuWorkScale;
		double entityDistanceScaleSqr = entityDistanceScale * entityDistanceScale;
		itemEntityRenderDistanceSqr = ITEM_ENTITY_RENDER_DISTANCE_SQR * entityDistanceScaleSqr;
		experienceOrbRenderDistanceSqr = EXPERIENCE_ORB_RENDER_DISTANCE_SQR * entityDistanceScaleSqr;
		hangingEntityRenderDistanceSqr = HANGING_ENTITY_RENDER_DISTANCE_SQR * entityDistanceScaleSqr;
		projectileRenderDistanceSqr = PROJECTILE_RENDER_DISTANCE_SQR * entityDistanceScaleSqr;
		fallingBlockRenderDistanceSqr = FALLING_BLOCK_RENDER_DISTANCE_SQR * entityDistanceScaleSqr;

		double particleDistance = Math.max(8.0D, OptiminiumSettings.getParticleRenderDistanceBlocks() * gpuWorkScale);
		double lowPriorityDistance = Math.max(8.0D, particleDistance * 0.5D);
		particleRenderDistanceSqr = particleDistance * particleDistance;
		lowPriorityParticleDistanceSqr = lowPriorityDistance * lowPriorityDistance;
		maxParticlesPerFrame = Math.max(8, (int)Math.round(OptiminiumSettings.getMaxParticlesPerFrame() * gpuWorkScale));
		maxLowPriorityParticlesPerFrame = Math.max(4, maxParticlesPerFrame / 3);
		blockEntityDistanceScale = (float)(OptiminiumSettings.getBlockEntityDistanceScalePercent() / 100.0D * gpuWorkScale);

		Minecraft minecraft = Minecraft.getInstance();
		cameraEntity = minecraft.cameraEntity;
		if (minecraft.gameRenderer == null || minecraft.gameRenderer.getMainCamera() == null) {
			hasCamera = false;
		} else {
			Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
			cameraX = camera.x;
			cameraY = camera.y;
			cameraZ = camera.z;
			hasCamera = true;
		}
		frameStateReady = true;
	}

	public static boolean shouldSkipEntityRender(Entity entity) {
		ensureFrameState();
		if (!clientRenderCulling || !hasCamera || entity instanceof LivingEntity || entity instanceof Player || entity == cameraEntity) {
			return false;
		}
		if (entity.hasGlowingTag() || entity.hasCustomName() || entity.isPassenger() || !entity.getPassengers().isEmpty() || entity.displayFireAnimation()) {
			return false;
		}

		double distanceSqr = distanceToCameraSqr(entity.getX(), entity.getY(), entity.getZ());
		boolean skip = false;
		if (entity instanceof ItemEntity) {
			skip = distanceSqr > itemEntityRenderDistanceSqr;
		} else if (entity instanceof ExperienceOrb) {
			skip = distanceSqr > experienceOrbRenderDistanceSqr;
		} else if (entity instanceof HangingEntity) {
			skip = distanceSqr > hangingEntityRenderDistanceSqr;
		} else if (entity instanceof Projectile) {
			skip = distanceSqr > projectileRenderDistanceSqr;
		} else if (entity instanceof FallingBlockEntity) {
			skip = distanceSqr > fallingBlockRenderDistanceSqr;
		}
		if (skip) {
			recordCulledEntityRender();
		}
		return skip;
	}

	public static boolean shouldSkipParticle(ParticleOptions options, double x, double y, double z) {
		ensureFrameState();
		if (!particleLimiter || !hasCamera || isImportantParticle(options)) {
			return false;
		}

		double distanceSqr = distanceToCameraSqr(x, y, z);
		boolean lowPriority = isLowPriorityParticle(options);
		boolean skip = distanceSqr > particleRenderDistanceSqr
			|| lowPriority && distanceSqr > lowPriorityParticleDistanceSqr
			|| particlesThisFrame >= maxParticlesPerFrame
			|| lowPriority && lowPriorityParticlesThisFrame >= maxLowPriorityParticlesPerFrame;
		if (skip) {
			recordHiddenParticle();
			return true;
		}

		particlesThisFrame++;
		if (lowPriority) {
			lowPriorityParticlesThisFrame++;
		}
		return false;
	}

	public static boolean isBlockEntityCullingActive() {
		ensureFrameState();
		return blockEntityCulling;
	}

	public static int scaledBlockEntityViewDistance(int viewDistance) {
		ensureFrameState();
		return Math.max(1, Math.round(viewDistance * blockEntityDistanceScale));
	}

	public static boolean shouldSkipBlockEntityRender(BlockEntity blockEntity, int viewDistance) {
		ensureFrameState();
		if (!blockEntityCulling || !hasCamera) {
			return false;
		}
		int scaledViewDistance = scaledBlockEntityViewDistance(viewDistance);
		double distanceSqr = distanceToCameraSqr(blockEntity.getBlockPos().getX() + 0.5D, blockEntity.getBlockPos().getY() + 0.5D, blockEntity.getBlockPos().getZ() + 0.5D);
		if (distanceSqr > scaledViewDistance * scaledViewDistance) {
			recordCulledBlockEntityRender();
			return true;
		}
		if (shouldDeferBlockEntityRender(blockEntity, distanceSqr)) {
			recordCulledBlockEntityRender();
			return true;
		}
		return false;
	}

	public static double getGpuWorkScale() {
		ensureFrameState();
		return gpuWorkScale;
	}

	public static long getLatestCpuFrameNanos() {
		return latestCpuFrameNanos;
	}

	public static double getSmoothedFrameNanos() {
		return smoothedFrameNanos;
	}

	public static String diagnosticLine() {
		ensureFrameState();
		return String.format(
			", gpuTimer=%s, gpuMs=%.2f, cpuMs=%.2f, gpuScale=%.2f, pendingGpuUploads=%d",
			OptiminiumGpuTimer.status(),
			OptiminiumGpuTimer.hasTiming() ? OptiminiumGpuTimer.getSmoothedGpuNanos() / 1_000_000.0D : 0.0D,
			latestCpuFrameNanos / 1_000_000.0D,
			gpuWorkScale,
			OptiminiumGpuUploadQueue.pendingUploads()
		);
	}

	public static int scaledChunkRebuildBudget(int configuredBudget) {
		ensureFrameState();
		if (configuredBudget <= 0) {
			return 0;
		}
		return Math.max(1, (int)Math.floor(configuredBudget * gpuWorkScale));
	}

	public static int scaledSyncChunkRebuildBudget(int configuredBudget) {
		ensureFrameState();
		if (configuredBudget <= 0) {
			return 0;
		}
		return gpuWorkScale < 0.75D ? 0 : Math.max(1, (int)Math.floor(configuredBudget * gpuWorkScale));
	}

	public static boolean shouldSkipEntityShadow(Entity entity) {
		ensureFrameState();
		if (!shouldCullGraphicsEffects() || !hasCamera || entity instanceof Player || entity == cameraEntity || entity.hasGlowingTag() || entity.hasCustomName()) {
			return false;
		}
		double distanceSqr = distanceToCameraSqr(entity.getX(), entity.getY(), entity.getZ());
		if (!(entity instanceof LivingEntity)) {
			return gpuWorkScale <= 0.92D && distanceSqr > NON_LIVING_SHADOW_CULL_DISTANCE_SQR;
		}
		return gpuWorkScale <= 0.82D && distanceSqr > SHADOW_CULL_DISTANCE_SQR;
	}

	public static boolean shouldSkipClouds() {
		ensureFrameState();
		return shouldCullGraphicsEffects() && gpuWorkScale <= 0.68D;
	}

	public static boolean shouldSkipWeather() {
		ensureFrameState();
		return shouldCullGraphicsEffects() && gpuWorkScale <= 0.74D;
	}

	public static void recordCulledEntityRender() {
		pendingCulledEntityRenders++;
	}

	public static void recordCulledBlockEntityRender() {
		pendingCulledBlockEntityRenders++;
	}

	public static void recordHiddenNameTag() {
		pendingHiddenNameTags++;
	}

	public static void recordHiddenParticle() {
		pendingHiddenParticles++;
	}

	private static void flushMetrics() {
		OptiminiumMetrics.culledEntityRenders(pendingCulledEntityRenders);
		OptiminiumMetrics.culledBlockEntityRenders(pendingCulledBlockEntityRenders);
		OptiminiumMetrics.hiddenNameTags(pendingHiddenNameTags);
		OptiminiumMetrics.hiddenParticles(pendingHiddenParticles);
		pendingCulledEntityRenders = 0;
		pendingCulledBlockEntityRenders = 0;
		pendingHiddenNameTags = 0;
		pendingHiddenParticles = 0;
	}

	private static void ensureFrameState() {
		if (!frameStateReady) {
			onFrameStart();
		}
	}

	private static boolean shouldCullGraphicsEffects() {
		return OptiminiumSettings.isEnabled() && OptiminiumSettings.isGpuOptimizer() && OptiminiumSettings.isGraphicsEffectCulling();
	}

	private static boolean shouldDeferBlockEntityRender(BlockEntity blockEntity, double distanceSqr) {
		if (!OptiminiumSettings.isGpuOptimizer() || gpuWorkScale > 0.90D || distanceSqr <= BLOCK_ENTITY_RENDER_BUDGET_DISTANCE_SQR || isPriorityBlockEntity(blockEntity)) {
			return false;
		}
		int budget = maxDistantBlockEntityRendersPerFrame();
		if (distantBlockEntityRendersThisFrame >= budget) {
			return true;
		}
		distantBlockEntityRendersThisFrame++;
		return false;
	}

	private static int maxDistantBlockEntityRendersPerFrame() {
		if (gpuWorkScale <= 0.60D) {
			return 12;
		}
		if (gpuWorkScale <= 0.70D) {
			return 24;
		}
		if (gpuWorkScale <= 0.80D) {
			return 40;
		}
		return 64;
	}

	private static boolean isPriorityBlockEntity(BlockEntity blockEntity) {
		BlockEntityType<?> type = blockEntity.getType();
		return type == BlockEntityType.BEACON
			|| type == BlockEntityType.CONDUIT
			|| type == BlockEntityType.END_PORTAL
			|| type == BlockEntityType.END_GATEWAY
			|| type == BlockEntityType.PISTON;
	}

	private static void updateGpuWorkScale(boolean enabled) {
		long now = System.nanoTime();
		if (lastFrameNanos == 0L) {
			lastFrameNanos = now;
			gpuWorkScale = 1.0D;
			return;
		}
		long frameNanos = Math.max(1L, now - lastFrameNanos);
		lastFrameNanos = now;
		latestCpuFrameNanos = frameNanos;
		if (!enabled || !OptiminiumSettings.isGpuOptimizer()) {
			gpuWorkScale = 1.0D;
			smoothedFrameNanos = 0.0D;
			return;
		}
		double pacingNanos;
		if (OptiminiumGpuTimer.isActive() && OptiminiumGpuTimer.hasTiming()) {
			pacingNanos = OptiminiumGpuTimer.getSmoothedGpuNanos();
			smoothedFrameNanos = frameNanos;
		} else {
			if (smoothedFrameNanos <= 0.0D) {
				smoothedFrameNanos = frameNanos;
			} else {
				smoothedFrameNanos += (frameNanos - smoothedFrameNanos) * FRAME_SMOOTHING;
			}
			pacingNanos = smoothedFrameNanos;
		}
		double targetFrameNanos = 1_000_000_000.0D / OptiminiumSettings.getGpuTargetFps();
		double minScale = OptiminiumSettings.getGpuMinRenderScalePercent() / 100.0D;
		if (pacingNanos > targetFrameNanos * 1.10D) {
			gpuWorkScale = Math.max(minScale, gpuWorkScale - GPU_SCALE_STEP_DOWN);
		} else if (pacingNanos < targetFrameNanos * 0.86D) {
			gpuWorkScale = Math.min(1.0D, gpuWorkScale + GPU_SCALE_STEP_UP);
		}
	}

	private static double distanceToCameraSqr(double x, double y, double z) {
		double dx = cameraX - x;
		double dy = cameraY - y;
		double dz = cameraZ - z;
		return dx * dx + dy * dy + dz * dz;
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
