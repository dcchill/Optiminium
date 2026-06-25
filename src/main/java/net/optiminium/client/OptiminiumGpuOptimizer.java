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
import net.optiminium.OptiminiumMod;
import net.optiminium.optimization.OptiminiumMetrics;
import net.optiminium.optimization.OptiminiumSettings;

public final class OptiminiumGpuOptimizer {
	private static final double ITEM_ENTITY_RENDER_DISTANCE_SQR = 48.0D * 48.0D;
	private static final double EXPERIENCE_ORB_RENDER_DISTANCE_SQR = 32.0D * 32.0D;
	private static final double HANGING_ENTITY_RENDER_DISTANCE_SQR = 64.0D * 64.0D;
	private static final double FRAME_SMOOTHING = 0.12D;
	private static final double GPU_SCALE_STEP_DOWN = 0.04D;
	private static final double GPU_SCALE_STEP_UP = 0.015D;
	private static final double RAW_SPIKE_TRIGGER_SCALE = 1.20D;
	private static final double PACING_SPIKE_TRIGGER_SCALE = 1.10D;
	private static final int SUSTAINED_PRESSURE_FRAMES = 20;
	private static final double SHADOW_CULL_DISTANCE_SQR = 18.0D * 18.0D;
	private static final double NON_LIVING_SHADOW_CULL_DISTANCE_SQR = 10.0D * 10.0D;
	private static final double BLOCK_ENTITY_RENDER_BUDGET_DISTANCE_SQR = 14.0D * 14.0D;
	private static final int DENSE_SCENE_HOLD_FRAMES = 20;
	private static final int PROFILE_PARTICLE_CULLING = 0;
	private static final int PROFILE_BLOCK_ENTITY_CULLING = 1;
	private static final int PROFILE_ENTITY_CULLING = 2;
	private static final int PROFILE_UPLOAD_MANAGEMENT = 3;
	private static final int PROFILE_ADAPTIVE_QUALITY = 4;
	private static final int PROFILE_COUNT = 5;

	private static int particlesThisFrame;
	private static int lowPriorityParticlesThisFrame;
	private static int distantBlockEntityRendersThisFrame;
	private static int rawVisibleBlockEntitiesThisFrame;
	private static int renderedBlockEntitiesThisFrame;
	private static int lastRawVisibleBlockEntities;
	private static int lastRenderedBlockEntities;
	private static int maxRawVisibleBlockEntities;
	private static int maxRenderedBlockEntities;
	private static int consecutiveDenseSceneFrames;
	private static int denseSceneHoldFrames;
	private static int pendingCulledEntityRenders;
	private static int pendingCulledBlockEntityRenders;
	private static int pendingHiddenNameTags;
	private static int pendingHiddenParticles;
	private static long culledBlockEntitiesThisRun;
	private static long renderedBlockEntitiesThisRun;
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
	private static double particleRenderDistanceSqr;
	private static double lowPriorityParticleDistanceSqr;
	private static double gpuWorkScale = 1.0D;
	private static double particleWorkScale = 1.0D;
	private static double blockEntityWorkScale = 1.0D;
	private static double rebuildWorkScale = 1.0D;
	private static double uploadWorkScale = 1.0D;
	private static double minParticleScale = 1.0D;
	private static double minBlockEntityScale = 1.0D;
	private static long lastFrameNanos;
	private static double smoothedFrameNanos;
	private static long latestCpuFrameNanos;
	private static int maxParticlesPerFrame;
	private static int maxLowPriorityParticlesPerFrame;
	private static float blockEntityDistanceScale;
	private static boolean denseSceneActive;
	private static boolean countedDenseParticleBudgetFrame;
	private static boolean countedDenseBlockEntityBudgetFrame;
	private static boolean countedDenseRebuildBudgetFrame;
	private static boolean countedDenseUploadBudgetFrame;
	private static boolean lastRawSpike;
	private static boolean lastPacingSpike;
	private static boolean lastDenseSceneTrigger;
	private static boolean sustainedFramePressure;
	private static int consecutivePressureFrames;
	private static String lastAdaptiveReason = "not sampled";
	private static long denseSceneFrames;
	private static long denseAdaptiveFrames;
	private static long denseParticleBudgetFrames;
	private static long denseBlockEntityBudgetFrames;
	private static long denseRebuildBudgetFrames;
	private static long denseUploadBudgetFrames;
	private static long experimentalUploadStallLimitedFrames;
	private static long adaptiveActivationAttempts;
	private static long adaptiveActivationSuccesses;
	private static long adaptiveBlockEntityFrames;
	private static long adaptiveParticleFrames;
	private static long adaptiveUploadFrames;
	private static long rawSpikeTriggerFrames;
	private static long pacingSpikeTriggerFrames;
	private static long lastAdaptiveDebugNanos;
	private static boolean profilingEnabled;
	private static boolean profileFrameOpen;
	private static long profiledFrames;
	private static long profileWorstTotalFrame;
	private static final long[] profileTotals = new long[PROFILE_COUNT];
	private static final long[] profileFrameTotals = new long[PROFILE_COUNT];
	private static final long[] profileWorstFrames = new long[PROFILE_COUNT];

	private OptiminiumGpuOptimizer() {
	}

	public static void onFrameStart() {
		finishProfileFrame();
		flushMetrics();
		particlesThisFrame = 0;
		lowPriorityParticlesThisFrame = 0;
		distantBlockEntityRendersThisFrame = 0;
		lastRawVisibleBlockEntities = rawVisibleBlockEntitiesThisFrame;
		lastRenderedBlockEntities = renderedBlockEntitiesThisFrame;
		maxRawVisibleBlockEntities = Math.max(maxRawVisibleBlockEntities, lastRawVisibleBlockEntities);
		maxRenderedBlockEntities = Math.max(maxRenderedBlockEntities, lastRenderedBlockEntities);
		rawVisibleBlockEntitiesThisFrame = 0;
		renderedBlockEntitiesThisFrame = 0;
		countedDenseParticleBudgetFrame = false;
		countedDenseBlockEntityBudgetFrame = false;
		countedDenseRebuildBudgetFrame = false;
		countedDenseUploadBudgetFrame = false;
		boolean enabled = OptiminiumSettings.isEnabled();
		updateDenseSceneState(enabled);
		updateGpuWorkScale(enabled);
		updateAdaptiveScales(enabled);
		clientRenderCulling = enabled && OptiminiumSettings.isClientRenderCulling();
		blockEntityCulling = enabled && OptiminiumSettings.isBlockEntityCulling();
		particleLimiter = enabled && OptiminiumSettings.isParticleLimiter();

		double entityDistanceScale = OptiminiumSettings.getEntityRenderDistanceScalePercent() / 100.0D * gpuWorkScale;
		double entityDistanceScaleSqr = entityDistanceScale * entityDistanceScale;
		itemEntityRenderDistanceSqr = ITEM_ENTITY_RENDER_DISTANCE_SQR * entityDistanceScaleSqr;
		experienceOrbRenderDistanceSqr = EXPERIENCE_ORB_RENDER_DISTANCE_SQR * entityDistanceScaleSqr;
		hangingEntityRenderDistanceSqr = HANGING_ENTITY_RENDER_DISTANCE_SQR * entityDistanceScaleSqr;

		double particleDistance = Math.max(8.0D, OptiminiumSettings.getParticleRenderDistanceBlocks() * particleWorkScale);
		double lowPriorityDistance = Math.max(8.0D, particleDistance * 0.5D);
		particleRenderDistanceSqr = particleDistance * particleDistance;
		lowPriorityParticleDistanceSqr = lowPriorityDistance * lowPriorityDistance;
		maxParticlesPerFrame = Math.max(8, (int)Math.round(OptiminiumSettings.getMaxParticlesPerFrame() * particleWorkScale));
		maxLowPriorityParticlesPerFrame = Math.max(4, maxParticlesPerFrame / 3);
		blockEntityDistanceScale = (float)(OptiminiumSettings.getBlockEntityDistanceScalePercent() / 100.0D * blockEntityWorkScale);

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
		long profileStart = profileStart();
		try {
			if (!clientRenderCulling || !hasCamera || entity instanceof LivingEntity || entity instanceof Player || entity == cameraEntity) {
				OptiminiumVisualSignificance.recordEntity(entity, false);
				return false;
			}
			if (entity instanceof Projectile || entity instanceof FallingBlockEntity || entity.hasGlowingTag() || entity.hasCustomName() || entity.isPassenger() || !entity.getPassengers().isEmpty() || entity.displayFireAnimation()) {
				OptiminiumVisualSignificance.recordEntity(entity, false);
				return false;
			}
			if (OptiminiumVisualSignificance.shouldProtectEntity(entity)) {
				OptiminiumVisualSignificance.recordEntity(entity, false);
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
			}
			if (skip) {
				recordCulledEntityRender();
			}
			OptiminiumVisualSignificance.recordEntity(entity, skip);
			return skip;
		} finally {
			recordProfileNanos(PROFILE_ENTITY_CULLING, profileStart);
		}
	}

	public static boolean shouldSkipParticle(ParticleOptions options, double x, double y, double z) {
		ensureFrameState();
		long profileStart = profileStart();
		try {
			if (!particleLimiter || !hasCamera || isImportantParticle(options)) {
				OptiminiumVisualSignificance.recordParticle(options, x, y, z, false);
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
				OptiminiumVisualSignificance.recordParticle(options, x, y, z, true);
				return true;
			}

			particlesThisFrame++;
			if (lowPriority) {
				lowPriorityParticlesThisFrame++;
			}
			OptiminiumVisualSignificance.recordParticle(options, x, y, z, false);
			return false;
		} finally {
			recordProfileNanos(PROFILE_PARTICLE_CULLING, profileStart);
		}
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
		long profileStart = profileStart();
		try {
			if (!blockEntityCulling || !hasCamera) {
				return false;
			}
			// When Visual Significance is enabled, use its classification to skip CULLED block entities
			if (OptiminiumVisualSignificance.isEnabled()) {
				if (!OptiminiumVisualSignificance.shouldRenderBySignificance(blockEntity)) {
					recordCulledBlockEntityRender();
					return true;
				}
				return false;
			}
			if (OptiminiumVisualSignificance.shouldProtectBlockEntity(blockEntity)) {
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
		} finally {
			recordProfileNanos(PROFILE_BLOCK_ENTITY_CULLING, profileStart);
		}
	}

	public static double getGpuWorkScale() {
		ensureFrameState();
		return gpuWorkScale;
	}

	public static long getLatestCpuFrameNanos() {
		return latestCpuFrameNanos;
	}

	public static boolean hasVisualSignificancePressure() {
		ensureFrameState();
		return denseSceneActive || sustainedFramePressure;
	}

	public static double getSmoothedFrameNanos() {
		return smoothedFrameNanos;
	}

	public static String diagnosticLine() {
		ensureFrameState();
		finishProfileFrame();
		ProfileSnapshot profile = profileSnapshot();
		return String.format(
			", gpuTimer=%s, gpuMs=%.2f, cpuMs=%.2f, gpuScale=%.2f, particleScale=%.2f, blockEntityScale=%.2f, minParticleScale=%.2f, minBlockEntityScale=%.2f, particleCullingMs=%.3f, blockEntityCullingMs=%.3f, entityCullingMs=%.3f, uploadManagementMs=%.3f, adaptiveQualityMs=%.3f, totalOptiminiumCpuMs=%.3f, worstParticleCullingMs=%.3f, worstBlockEntityCullingMs=%.3f, worstEntityCullingMs=%.3f, worstOptiminiumCpuMs=%.3f, rawVisibleBlockEntities=%d, maxRawVisibleBlockEntities=%d, renderedBlockEntitiesAfterCulling=%d, renderedBlockEntitiesThisRun=%d, culledBlockEntitiesThisRun=%d, maxVisibleBlockEntities=%d, maxRenderedBlockEntitiesAfterCulling=%d, denseThreshold=%d, denseMode=%s, denseSceneFrames=%d, denseAdaptiveFrames=%d, adaptiveActivationAttempts=%d, adaptiveActivationSuccesses=%d, adaptiveBlockEntityFrames=%d, adaptiveParticleFrames=%d, adaptiveUploadFrames=%d, rawSpikeTriggerFrames=%d, pacingSpikeTriggerFrames=%d, denseParticleBudgetFrames=%d, denseBlockEntityBudgetFrames=%d, denseRebuildBudgetFrames=%d, denseUploadBudgetFrames=%d, experimentalUploadStallLimitedFrames=%d, %s, adaptiveReason=\"%s\", pendingGpuUploads=%d, uploadsSkippedBecauseLowSignificance=%d, uploadsDeduplicated=%d, uploadsDeferredBySignificance=%d, uploadsPromotedBecauseNear=%d, redundantUploadSchedulingPrevented=%d",
			OptiminiumGpuTimer.status(),
			OptiminiumGpuTimer.hasTiming() ? OptiminiumGpuTimer.getSmoothedGpuNanos() / 1_000_000.0D : 0.0D,
			latestCpuFrameNanos / 1_000_000.0D,
			gpuWorkScale,
			particleWorkScale,
			blockEntityWorkScale,
			minParticleScale,
			minBlockEntityScale,
			profile.particleCullingMs(),
			profile.blockEntityCullingMs(),
			profile.entityCullingMs(),
			profile.uploadManagementMs(),
			profile.adaptiveQualityMs(),
			profile.totalOptiminiumCpuMs(),
			profile.worstParticleCullingMs(),
			profile.worstBlockEntityCullingMs(),
			profile.worstEntityCullingMs(),
			profile.worstOptiminiumCpuMs(),
			lastRawVisibleBlockEntities,
			maxRawVisibleBlockEntities,
			lastRenderedBlockEntities,
			renderedBlockEntitiesThisRun,
			culledBlockEntitiesThisRun,
			maxRawVisibleBlockEntities,
			maxRenderedBlockEntities,
			OptiminiumSettings.getDenseBlockEntityThreshold(),
			OptiminiumSettings.getDenseSceneAdaptiveMode().name().toLowerCase(),
			denseSceneFrames,
			denseAdaptiveFrames,
			adaptiveActivationAttempts,
			adaptiveActivationSuccesses,
			adaptiveBlockEntityFrames,
			adaptiveParticleFrames,
			adaptiveUploadFrames,
			rawSpikeTriggerFrames,
			pacingSpikeTriggerFrames,
			denseParticleBudgetFrames,
			denseBlockEntityBudgetFrames,
			denseRebuildBudgetFrames,
			denseUploadBudgetFrames,
			experimentalUploadStallLimitedFrames,
			OptiminiumVisualSignificance.diagnosticLine(),
			lastAdaptiveReason,
			OptiminiumGpuUploadQueue.pendingUploads(),
			OptiminiumGpuUploadQueue.uploadsSkippedBecauseLowSignificance(),
			OptiminiumGpuUploadQueue.uploadsDeduplicated(),
			OptiminiumGpuUploadQueue.uploadsDeferredBySignificance(),
			OptiminiumGpuUploadQueue.uploadsPromotedBecauseNear(),
			OptiminiumGpuUploadQueue.redundantUploadSchedulingPrevented()
		);
	}

	public static SceneSnapshot sceneSnapshot() {
		ensureFrameState();
		return new SceneSnapshot(
			lastRawVisibleBlockEntities,
			maxRawVisibleBlockEntities,
			lastRenderedBlockEntities,
			maxRenderedBlockEntities,
			renderedBlockEntitiesThisRun,
			culledBlockEntitiesThisRun,
			denseSceneFrames,
			OptiminiumVisualSignificance.snapshot()
		);
	}

	public static int scaledChunkRebuildBudget(int configuredBudget) {
		ensureFrameState();
		if (configuredBudget <= 0) {
			return 0;
		}
		int budget = Math.max(1, (int)Math.floor(configuredBudget * rebuildWorkScale));
		if (denseSceneActive && budget < configuredBudget && !countedDenseRebuildBudgetFrame) {
			denseRebuildBudgetFrames++;
			countedDenseRebuildBudgetFrame = true;
		}
		return budget;
	}

	public static int scaledSyncChunkRebuildBudget(int configuredBudget) {
		ensureFrameState();
		if (configuredBudget <= 0) {
			return 0;
		}
		if (rebuildWorkScale < 0.75D) {
			if (denseSceneActive && !countedDenseRebuildBudgetFrame) {
				denseRebuildBudgetFrames++;
				countedDenseRebuildBudgetFrame = true;
			}
			return 0;
		}
		return Math.max(1, (int)Math.floor(configuredBudget * rebuildWorkScale));
	}

	public static int scaledChunkUploadBudget(int configuredBudget) {
		ensureFrameState();
		long profileStart = profileStart();
		try {
			int budget = Math.max(1, (int)Math.floor(configuredBudget * uploadWorkScale));
			if (denseSceneActive && budget < configuredBudget && !countedDenseUploadBudgetFrame) {
				denseUploadBudgetFrames++;
				countedDenseUploadBudgetFrame = true;
			}
			if (OptiminiumRenderProfiler.hasRecentUploadPressure() && budget > 1) {
				budget = 1;
				experimentalUploadStallLimitedFrames++;
			}
			return budget;
		} finally {
			recordProfileNanos(PROFILE_UPLOAD_MANAGEMENT, profileStart);
		}
	}

	public static boolean shouldSkipEntityShadow(Entity entity) {
		ensureFrameState();
		if (!shouldCullGraphicsEffects() || !hasCamera || entity instanceof Player || entity == cameraEntity || entity.hasGlowingTag() || entity.hasCustomName()) {
			return false;
		}
		double distanceSqr = distanceToCameraSqr(entity.getX(), entity.getY(), entity.getZ());
		if (!(entity instanceof LivingEntity)) {
			return particleWorkScale <= 0.92D && distanceSqr > NON_LIVING_SHADOW_CULL_DISTANCE_SQR;
		}
		return particleWorkScale <= 0.82D && distanceSqr > SHADOW_CULL_DISTANCE_SQR;
	}

	public static boolean shouldSkipClouds() {
		return false;
	}

	public static boolean shouldSkipWeather() {
		return false;
	}

	public static void recordCulledEntityRender() {
		pendingCulledEntityRenders++;
	}

	public static void recordCulledBlockEntityRender() {
		pendingCulledBlockEntityRenders++;
		culledBlockEntitiesThisRun++;
	}

	public static void recordHiddenNameTag() {
		pendingHiddenNameTags++;
	}

	public static void recordHiddenParticle() {
		pendingHiddenParticles++;
	}

	public static void recordRawVisibleBlockEntitiesBeforeCulling(int count) {
		rawVisibleBlockEntitiesThisFrame = Math.max(rawVisibleBlockEntitiesThisFrame, count);
	}

	public static void recordRenderedBlockEntityAfterCulling() {
		renderedBlockEntitiesThisFrame++;
		renderedBlockEntitiesThisRun++;
	}

	public static void resetAdaptiveStats() {
		resetProfilingStats();
		denseSceneFrames = 0L;
		denseAdaptiveFrames = 0L;
		denseParticleBudgetFrames = 0L;
		denseBlockEntityBudgetFrames = 0L;
		denseRebuildBudgetFrames = 0L;
		denseUploadBudgetFrames = 0L;
		experimentalUploadStallLimitedFrames = 0L;
		adaptiveActivationAttempts = 0L;
		adaptiveActivationSuccesses = 0L;
		adaptiveBlockEntityFrames = 0L;
		adaptiveParticleFrames = 0L;
		adaptiveUploadFrames = 0L;
		rawSpikeTriggerFrames = 0L;
		pacingSpikeTriggerFrames = 0L;
		sustainedFramePressure = false;
		consecutivePressureFrames = 0;
		minParticleScale = 1.0D;
		minBlockEntityScale = 1.0D;
		rawVisibleBlockEntitiesThisFrame = 0;
		renderedBlockEntitiesThisFrame = 0;
		lastRawVisibleBlockEntities = 0;
		lastRenderedBlockEntities = 0;
		maxRawVisibleBlockEntities = 0;
		maxRenderedBlockEntities = 0;
		culledBlockEntitiesThisRun = 0L;
		renderedBlockEntitiesThisRun = 0L;
		OptiminiumVisualSignificance.reset();
		consecutiveDenseSceneFrames = 0;
		denseSceneHoldFrames = 0;
		lastAdaptiveReason = "reset";
	}

	public static void flushPendingMetrics() {
		flushMetrics();
	}

	public static void setProfilingEnabled(boolean enabled) {
		profilingEnabled = enabled;
		resetProfilingStats();
	}

	public static long profileStart() {
		return profilingEnabled ? System.nanoTime() : 0L;
	}

	public static void recordBlockEntityProfileNanos(long startNanos) {
		recordProfileNanos(PROFILE_BLOCK_ENTITY_CULLING, startNanos);
	}

	public static void recordUploadManagementProfileNanos(long startNanos) {
		recordProfileNanos(PROFILE_UPLOAD_MANAGEMENT, startNanos);
	}

	public static ProfileSnapshot profileSnapshot() {
		finishProfileFrame();
		return new ProfileSnapshot(
			averageProfileMs(PROFILE_PARTICLE_CULLING),
			averageProfileMs(PROFILE_BLOCK_ENTITY_CULLING),
			averageProfileMs(PROFILE_ENTITY_CULLING),
			averageProfileMs(PROFILE_UPLOAD_MANAGEMENT),
			averageProfileMs(PROFILE_ADAPTIVE_QUALITY),
			totalOptiminiumCpuMs(),
			worstProfileMs(PROFILE_PARTICLE_CULLING),
			worstProfileMs(PROFILE_BLOCK_ENTITY_CULLING),
			worstProfileMs(PROFILE_ENTITY_CULLING),
			worstOptiminiumCpuMs()
		);
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
		if (!OptiminiumSettings.isGpuOptimizer() || blockEntityWorkScale > 0.90D || distanceSqr <= BLOCK_ENTITY_RENDER_BUDGET_DISTANCE_SQR || isPriorityBlockEntity(blockEntity)) {
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
		if (denseSceneActive && !countedDenseBlockEntityBudgetFrame) {
			denseBlockEntityBudgetFrames++;
			countedDenseBlockEntityBudgetFrame = true;
		}
		if (blockEntityWorkScale <= 0.60D) {
			return 12;
		}
		if (blockEntityWorkScale <= 0.70D) {
			return 24;
		}
		if (blockEntityWorkScale <= 0.80D) {
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

	private static void updateDenseSceneState(boolean enabled) {
		long profileStart = profileStart();
		try {
			OptiminiumSettings.DenseSceneAdaptiveMode mode = OptiminiumSettings.getDenseSceneAdaptiveMode();
			boolean rawDenseScene = lastRawVisibleBlockEntities >= OptiminiumSettings.getDenseBlockEntityThreshold();
			if (rawDenseScene) {
				denseSceneFrames++;
			}
			if (!enabled || !OptiminiumSettings.isGpuOptimizer() || mode == OptiminiumSettings.DenseSceneAdaptiveMode.OFF) {
				denseSceneActive = false;
				denseSceneHoldFrames = 0;
				lastDenseSceneTrigger = false;
				return;
			}
			if (rawDenseScene) {
				consecutiveDenseSceneFrames++;
				denseSceneHoldFrames = DENSE_SCENE_HOLD_FRAMES;
			} else {
				consecutiveDenseSceneFrames = 0;
			}
			denseSceneActive = denseSceneHoldFrames > 0;
			lastDenseSceneTrigger = denseSceneActive;
			if (denseSceneActive) {
				denseAdaptiveFrames++;
				denseSceneHoldFrames--;
			}
		} finally {
			recordProfileNanos(PROFILE_ADAPTIVE_QUALITY, profileStart);
		}
	}

	private static void updateAdaptiveScales(boolean enabled) {
		long profileStart = profileStart();
		try {
			if (!enabled || !OptiminiumSettings.isGpuOptimizer()) {
				particleWorkScale = 1.0D;
				blockEntityWorkScale = 1.0D;
				rebuildWorkScale = 1.0D;
				uploadWorkScale = 1.0D;
				lastAdaptiveReason = adaptiveReason(false, false, false, false);
				logAdaptiveReason();
				return;
			}

			// Visual Significance drives the render budget.
			// Adaptive Quality simply reacts.
			double budgetParticleScale = 1.0D;
			double budgetBlockEntityScale = 1.0D;
			double budgetAllScale = 1.0D;
			String reason;
			switch (OptiminiumVisualSignificance.renderBudget()) {
				case NORMAL -> {
					budgetParticleScale = 1.0D;
					budgetBlockEntityScale = 1.0D;
					budgetAllScale = 1.0D;
					reason = "normal";
				}
				case MEDIUM_PRESSURE -> {
					budgetParticleScale = 0.85D;
					budgetBlockEntityScale = 0.80D;
					budgetAllScale = 0.70D;
					reason = "medium";
				}
				case HEAVY_PRESSURE -> {
					budgetParticleScale = 0.72D;
					budgetBlockEntityScale = 0.65D;
					budgetAllScale = 0.55D;
					reason = "heavy";
				}
				case EMERGENCY -> {
					budgetParticleScale = 0.55D;
					budgetBlockEntityScale = 0.45D;
					budgetAllScale = 0.35D;
					reason = "emergency";
				}
				default -> {
					budgetParticleScale = 1.0D;
					budgetBlockEntityScale = 1.0D;
					budgetAllScale = 1.0D;
					reason = "unknown";
				}
			}
			// Also apply gpuWorkScale (from raw frame time spikes) as an upper bound
			particleWorkScale = Math.min(gpuWorkScale, budgetParticleScale);
			blockEntityWorkScale = Math.min(gpuWorkScale, budgetBlockEntityScale);
			rebuildWorkScale = Math.min(gpuWorkScale, budgetAllScale);
			uploadWorkScale = Math.min(gpuWorkScale, budgetAllScale);
			lastAdaptiveReason = reason;
			logAdaptiveReason();
			recordAdaptiveActivation();
			minParticleScale = Math.min(minParticleScale, particleWorkScale);
			minBlockEntityScale = Math.min(minBlockEntityScale, blockEntityWorkScale);
		} finally {
			recordProfileNanos(PROFILE_ADAPTIVE_QUALITY, profileStart);
		}
	}

	private static void updateGpuWorkScale(boolean enabled) {
		long now = System.nanoTime();
		if (lastFrameNanos == 0L) {
			lastFrameNanos = now;
			gpuWorkScale = 1.0D;
			lastRawSpike = false;
			lastPacingSpike = false;
			return;
		}
		long frameNanos = Math.max(1L, now - lastFrameNanos);
		lastFrameNanos = now;
		latestCpuFrameNanos = frameNanos;
		if (!enabled || !OptiminiumSettings.isGpuOptimizer()) {
			gpuWorkScale = 1.0D;
			smoothedFrameNanos = 0.0D;
			lastRawSpike = false;
			lastPacingSpike = false;
			sustainedFramePressure = false;
			consecutivePressureFrames = 0;
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
		lastRawSpike = frameNanos > targetFrameNanos * RAW_SPIKE_TRIGGER_SCALE;
		lastPacingSpike = pacingNanos > targetFrameNanos * PACING_SPIKE_TRIGGER_SCALE;
		if (lastRawSpike) {
			rawSpikeTriggerFrames++;
		}
		if (lastPacingSpike) {
			pacingSpikeTriggerFrames++;
		}
		if (lastRawSpike || lastPacingSpike) {
			consecutivePressureFrames++;
		} else {
			consecutivePressureFrames = 0;
		}
		sustainedFramePressure = consecutivePressureFrames >= SUSTAINED_PRESSURE_FRAMES;
		if (denseSceneActive || sustainedFramePressure) {
			gpuWorkScale = Math.max(minScale, gpuWorkScale - GPU_SCALE_STEP_DOWN);
		} else if (pacingNanos < targetFrameNanos * 0.86D) {
			gpuWorkScale = Math.min(1.0D, gpuWorkScale + GPU_SCALE_STEP_UP);
		} else {
			gpuWorkScale = 1.0D;
		}
	}

	private static void recordAdaptiveActivation() {
		boolean attempted = sustainedFramePressure || lastDenseSceneTrigger;
		boolean particleActive = particleWorkScale < 1.0D;
		boolean blockEntityActive = blockEntityWorkScale < 1.0D;
		boolean uploadActive = uploadWorkScale < 1.0D;
		if (attempted) {
			adaptiveActivationAttempts++;
		}
		if (attempted && (particleActive || blockEntityActive || rebuildWorkScale < 1.0D || uploadActive)) {
			adaptiveActivationSuccesses++;
		}
		if (particleActive) {
			adaptiveParticleFrames++;
		}
		if (blockEntityActive) {
			adaptiveBlockEntityFrames++;
		}
		if (uploadActive) {
			adaptiveUploadFrames++;
		}
		lastAdaptiveReason = adaptiveReason(attempted, particleActive, blockEntityActive, uploadActive);
		logAdaptiveReason();
	}

	private static String adaptiveReason(boolean attempted, boolean particleActive, boolean blockEntityActive, boolean uploadActive) {
		if (!OptiminiumSettings.isEnabled()) {
			return "disabled";
		}
		if (!OptiminiumSettings.isGpuOptimizer()) {
			return "gpu optimizer off";
		}
		if (attempted && (particleActive || blockEntityActive || uploadActive || rebuildWorkScale < 1.0D)) {
			return "active: sustainedPressure=" + sustainedFramePressure + ", denseScene=" + lastDenseSceneTrigger;
		}
		return "inactive: sustainedPressure=" + sustainedFramePressure + ", rawSpike=" + lastRawSpike + ", pacingSpike=" + lastPacingSpike + ", denseScene=" + lastDenseSceneTrigger
			+ ", rawVisibleBlockEntities=" + lastRawVisibleBlockEntities + "/" + OptiminiumSettings.getDenseBlockEntityThreshold();
	}

	private static void logAdaptiveReason() {
		long now = System.nanoTime();
		if (now - lastAdaptiveDebugNanos < 1_000_000_000L) {
			return;
		}
		lastAdaptiveDebugNanos = now;
		OptiminiumMod.LOGGER.debug("Optiminium adaptive quality: {}", lastAdaptiveReason);
	}

	private static void recordProfileNanos(int feature, long startNanos) {
		if (startNanos == 0L) {
			return;
		}
		profileFrameTotals[feature] += Math.max(0L, System.nanoTime() - startNanos);
		profileFrameOpen = true;
	}

	private static void finishProfileFrame() {
		if (!profilingEnabled || !profileFrameOpen) {
			return;
		}
		long totalFrameNanos = 0L;
		for (int feature = 0; feature < PROFILE_COUNT; feature++) {
			long nanos = profileFrameTotals[feature];
			profileTotals[feature] += nanos;
			profileWorstFrames[feature] = Math.max(profileWorstFrames[feature], nanos);
			totalFrameNanos += nanos;
			profileFrameTotals[feature] = 0L;
		}
		profileWorstTotalFrame = Math.max(profileWorstTotalFrame, totalFrameNanos);
		profiledFrames++;
		profileFrameOpen = false;
	}

	private static void resetProfilingStats() {
		profileFrameOpen = false;
		profiledFrames = 0L;
		profileWorstTotalFrame = 0L;
		for (int feature = 0; feature < PROFILE_COUNT; feature++) {
			profileTotals[feature] = 0L;
			profileFrameTotals[feature] = 0L;
			profileWorstFrames[feature] = 0L;
		}
	}

	private static double averageProfileMs(int feature) {
		return profiledFrames <= 0L ? 0.0D : profileTotals[feature] / (profiledFrames * 1_000_000.0D);
	}

	private static double worstProfileMs(int feature) {
		return profileWorstFrames[feature] / 1_000_000.0D;
	}

	private static double totalOptiminiumCpuMs() {
		long total = 0L;
		for (int feature = 0; feature < PROFILE_COUNT; feature++) {
			total += profileTotals[feature];
		}
		return profiledFrames <= 0L ? 0.0D : total / (profiledFrames * 1_000_000.0D);
	}

	private static double worstOptiminiumCpuMs() {
		return profileWorstTotalFrame / 1_000_000.0D;
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

	public record ProfileSnapshot(
		double particleCullingMs,
		double blockEntityCullingMs,
		double entityCullingMs,
		double uploadManagementMs,
		double adaptiveQualityMs,
		double totalOptiminiumCpuMs,
		double worstParticleCullingMs,
		double worstBlockEntityCullingMs,
		double worstEntityCullingMs,
		double worstOptiminiumCpuMs
	) {
	}

	public record SceneSnapshot(
		int rawVisibleBlockEntities,
		int maxRawVisibleBlockEntities,
		int renderedBlockEntitiesAfterCulling,
		int maxRenderedBlockEntitiesAfterCulling,
		long renderedBlockEntitiesThisRun,
		long culledBlockEntitiesThisRun,
		long denseSceneFrames,
		OptiminiumVisualSignificance.Snapshot significanceBands
	) {
	}
}
