package net.optiminium.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.optiminium.OptiminiumMod;
import net.optiminium.compat.OptiminiumSodiumCompat;
import net.optiminium.optimization.OptiminiumMetrics;
import net.optiminium.optimization.OptiminiumSettings;

public final class OptiminiumGpuOptimizer {
	private static final double FRAME_SMOOTHING = 0.12D;
	private static final double GPU_SCALE_STEP_DOWN = 0.04D;
	private static final double GPU_SCALE_STEP_UP = 0.015D;
	private static final double RAW_SPIKE_TRIGGER_SCALE = 1.20D;
	private static final double PACING_SPIKE_TRIGGER_SCALE = 1.10D;
	private static final int SUSTAINED_PRESSURE_FRAMES = 20;
	private static final double BLOCK_ENTITY_RENDER_BUDGET_DISTANCE_SQR = 14.0D * 14.0D;
	private static final int DENSE_SCENE_HOLD_FRAMES = 20;
	private static final int CLOUD_SKIP_HOLD_FRAMES = 30;
	private static final int WEATHER_SKIP_HOLD_FRAMES = 20;
	private static final double CLOUD_SKIP_WORK_SCALE = 0.78D;
	private static final double WEATHER_SKIP_WORK_SCALE = 0.66D;
	private static final int PROFILE_PARTICLE_CULLING = 0;
	private static final int PROFILE_BLOCK_ENTITY_CULLING = 1;
	private static final int PROFILE_ENTITY_CULLING = 2;
	private static final int PROFILE_ADAPTIVE_QUALITY = 3;
	private static final int PROFILE_VISUAL_SIGNIFICANCE = 4;
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
	private static int pendingCulledBlockEntityRenders;
	private static int pendingHiddenNameTags;
	private static int pendingHiddenParticles;
	private static long particleChecksAttempted;
	private static long particleChecksRejected;
	private static long particleChecksRendered;
	private static long particleCheckNanos;
	private static long culledBlockEntitiesThisRun;
	private static long renderedBlockEntitiesThisRun;
	private static boolean frameStateReady;
	private static boolean blockEntityCulling;
	private static boolean particleLimiter;
	private static boolean hasCamera;
	private static boolean visualSignificanceEnabled;
	private static boolean visualSignificanceParticleRecording;
	private static Entity cameraEntity;
	private static double cameraX;
	private static double cameraY;
	private static double cameraZ;
	private static double particleRenderDistanceSqr;
	private static double lowPriorityParticleDistanceSqr;
	private static double gpuWorkScale = 1.0D;
	private static double particleWorkScale = 1.0D;
	private static double blockEntityWorkScale = 1.0D;
	private static double rebuildWorkScale = 1.0D;
	private static double minParticleScale = 1.0D;
	private static double minBlockEntityScale = 1.0D;
	private static long lastFrameNanos;
	private static double smoothedFrameNanos;
	private static long latestCpuFrameNanos;
	private static int maxParticlesPerFrame;
	private static int maxLowPriorityParticlesPerFrame;
	private static boolean denseSceneActive;
	private static boolean countedDenseParticleBudgetFrame;
	private static boolean countedDenseBlockEntityBudgetFrame;
	private static boolean countedDenseRebuildBudgetFrame;
	private static boolean lastRawSpike;
	private static boolean lastPacingSpike;
	private static boolean lastDenseSceneTrigger;
	private static boolean sustainedFramePressure;
	private static int consecutivePressureFrames;
	private static int cloudSkipHoldFrames;
	private static int weatherSkipHoldFrames;
	private static String lastAdaptiveReason = "not sampled";
	private static long denseSceneFrames;
	private static long denseAdaptiveFrames;
	private static long denseParticleBudgetFrames;
	private static long denseBlockEntityBudgetFrames;
	private static long denseRebuildBudgetFrames;
	private static long cloudSkipFrames;
	private static long weatherSkipFrames;
	private static long adaptiveActivationAttempts;
	private static long adaptiveActivationSuccesses;
	private static long adaptiveBlockEntityFrames;
	private static long adaptiveParticleFrames;
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
		OptiminiumBlockEntityRenderCache.onFrameStart();
		OptiminiumPersistentBlockEntityMeshes.onFrameStart();
		OptiminiumBlockEntityVirtualizer.onFrameStart();
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
		boolean enabled = OptiminiumSettings.isEnabled();
		updateGpuWorkScale(enabled);
		updateAdaptiveScales(enabled);
		updateGraphicsEffectPressure(enabled);
		blockEntityCulling = false;
		particleLimiter = enabled && OptiminiumSettings.isParticleLimiter();
		visualSignificanceEnabled = false;
		visualSignificanceParticleRecording = false;

		double particleDistance = Math.max(8.0D, OptiminiumSettings.getParticleRenderDistanceBlocks() * particleWorkScale);
		double lowPriorityDistance = Math.max(8.0D, particleDistance * 0.5D);
		particleRenderDistanceSqr = particleDistance * particleDistance;
		lowPriorityParticleDistanceSqr = lowPriorityDistance * lowPriorityDistance;
		maxParticlesPerFrame = Math.max(8, (int)Math.round(OptiminiumSettings.getMaxParticlesPerFrame() * particleWorkScale));
		maxLowPriorityParticlesPerFrame = Math.max(4, maxParticlesPerFrame / 3);

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

	public static boolean shouldSkipParticle(ParticleOptions options, double x, double y, double z) {
		ensureFrameState();
		long profileStart = profileStart();
		if (profilingEnabled) particleChecksAttempted++;
		try {
			ParticleType<?> type = options.getType();
			if (!particleLimiter || !hasCamera || isImportantParticle(type)) {
				return false;
			}

			double distanceSqr = distanceToCameraSqr(x, y, z);
			boolean lowPriority = isLowPriorityParticle(type);
			boolean skip = distanceSqr > particleRenderDistanceSqr
				|| lowPriority && distanceSqr > lowPriorityParticleDistanceSqr
				|| particlesThisFrame >= maxParticlesPerFrame
				|| lowPriority && lowPriorityParticlesThisFrame >= maxLowPriorityParticlesPerFrame;
			if (skip) {
				if (profilingEnabled) particleChecksRejected++;
				recordHiddenParticle();
				return true;
			}

			particlesThisFrame++;
			if (profilingEnabled) particleChecksRendered++;
			if (lowPriority) {
				lowPriorityParticlesThisFrame++;
			}
			return false;
		} finally {
			if (profileStart != 0L) particleCheckNanos += Math.max(0L, System.nanoTime() - profileStart);
			recordProfileNanos(PROFILE_PARTICLE_CULLING, profileStart);
		}
	}

	public static boolean shouldProcessParticleHookThisFrame() {
		if (!frameStateReady) {
			return OptiminiumSettings.isEnabled() && OptiminiumSettings.isParticleLimiter();
		}
		return particleLimiter && hasCamera || visualSignificanceParticleRecording;
	}

	/**
	 * Unified block entity render decision.
	 * Checks Visual Significance classification, distance/defer culling, and the delegate.
	 * Called from the DistanceCullingRenderer rendering path.
	 */
	public static double getGpuWorkScale() {
		ensureFrameState();
		return gpuWorkScale;
	}

	public static double getParticleWorkScale() {
		ensureFrameState();
		return particleWorkScale;
	}

	public static long getLatestCpuFrameNanos() {
		return latestCpuFrameNanos;
	}

	public static boolean hasVisualSignificancePressure() {
		return false;
	}

	public static double getSmoothedFrameNanos() {
		return smoothedFrameNanos;
	}

	public static String diagnosticLine() {
		ensureFrameState();
		finishProfileFrame();
		ProfileSnapshot profile = profileSnapshot();
		return String.format(
			", rendererCompatibilityMode=%s, gpuTimer=%s, gpuMs=%.2f, cpuMs=%.2f, gpuScale=%.2f, particleScale=%.2f, blockEntityScale=%.2f, minParticleScale=%.2f, minBlockEntityScale=%.2f, particleCullingMs=%.3f, blockEntityCullingMs=%.3f, entityCullingMs=%.3f, adaptiveQualityMs=%.3f, visualSignificanceMs=%.3f, totalOptiminiumCpuMs=%.3f, worstParticleCullingMs=%.3f, worstBlockEntityCullingMs=%.3f, worstEntityCullingMs=%.3f, worstAdaptiveQualityMs=%.3f, worstVisualSignificanceMs=%.3f, worstOptiminiumCpuMs=%.3f, rawVisibleBlockEntities=%d, maxRawVisibleBlockEntities=%d, renderedBlockEntitiesAfterCulling=%d, renderedBlockEntitiesThisRun=%d, culledBlockEntitiesThisRun=%d, maxVisibleBlockEntities=%d, maxRenderedBlockEntitiesAfterCulling=%d, denseThreshold=%d, denseMode=%s, denseSceneFrames=%d, denseAdaptiveFrames=%d, adaptiveActivationAttempts=%d, adaptiveActivationSuccesses=%d, adaptiveBlockEntityFrames=%d, adaptiveParticleFrames=%d, rawSpikeTriggerFrames=%d, pacingSpikeTriggerFrames=%d, denseParticleBudgetFrames=%d, denseBlockEntityBudgetFrames=%d, denseRebuildBudgetFrames=%d, cloudSkipFrames=%d, weatherSkipFrames=%d, %s, adaptiveReason=\"%s\"",
			OptiminiumSodiumCompat.rendererModeString(),
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
			profile.adaptiveQualityMs(),
			profile.visualSignificanceMs(),
			profile.totalOptiminiumCpuMs(),
			profile.worstParticleCullingMs(),
			profile.worstBlockEntityCullingMs(),
			profile.worstEntityCullingMs(),
			profile.worstAdaptiveQualityMs(),
			profile.worstVisualSignificanceMs(),
			profile.worstOptiminiumCpuMs(),
			reportedRawVisibleBlockEntities(),
			reportedMaxRawVisibleBlockEntities(),
			lastRenderedBlockEntities,
			renderedBlockEntitiesThisRun,
			culledBlockEntitiesThisRun,
			reportedMaxRawVisibleBlockEntities(),
			maxRenderedBlockEntities,
			0,
			"removed",
			reportedDenseSceneFrames(),
			denseAdaptiveFrames,
			adaptiveActivationAttempts,
			adaptiveActivationSuccesses,
			adaptiveBlockEntityFrames,
			adaptiveParticleFrames,
			rawSpikeTriggerFrames,
			pacingSpikeTriggerFrames,
			denseParticleBudgetFrames,
			denseBlockEntityBudgetFrames,
			denseRebuildBudgetFrames,
			cloudSkipFrames,
			weatherSkipFrames,
			OptiminiumBlockEntityRenderCache.diagnosticLine()
				+ ", " + OptiminiumPersistentBlockEntityMeshes.diagnosticLine(),
			lastAdaptiveReason
		);
	}

	public static SceneSnapshot sceneSnapshot() {
		ensureFrameState();
		return new SceneSnapshot(
			reportedRawVisibleBlockEntities(),
			reportedMaxRawVisibleBlockEntities(),
			lastRenderedBlockEntities,
			maxRenderedBlockEntities,
			renderedBlockEntitiesThisRun,
			culledBlockEntitiesThisRun,
			reportedDenseSceneFrames(),
			OptiminiumVisualSignificance.snapshot(reportedRawVisibleBlockEntities())
		);
	}

	public static boolean shouldSkipClouds() {
		boolean skip = shouldCullGraphicsEffects() && cloudSkipHoldFrames > 0;
		if (skip) {
			cloudSkipFrames++;
		}
		return skip;
	}

	public static boolean shouldSkipWeather() {
		boolean skip = shouldCullGraphicsEffects() && weatherSkipHoldFrames > 0;
		if (skip) {
			weatherSkipFrames++;
		}
		return skip;
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
		ensureFrameState();
		rawVisibleBlockEntitiesThisFrame = Math.max(rawVisibleBlockEntitiesThisFrame, count);
	}

	public static void recordRawVisibleBlockEntityBeforeCulling(BlockEntity blockEntity) {
		ensureFrameState();
		if (blockEntity != null) {
			rawVisibleBlockEntitiesThisFrame++;
		}
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
		cloudSkipFrames = 0L;
		weatherSkipFrames = 0L;
		adaptiveActivationAttempts = 0L;
		adaptiveActivationSuccesses = 0L;
		adaptiveBlockEntityFrames = 0L;
		adaptiveParticleFrames = 0L;
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
		particleChecksAttempted = 0L; particleChecksRejected = 0L;
		particleChecksRendered = 0L; particleCheckNanos = 0L;
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

	public static void recordVisualSignificanceProfileNanos(long startNanos) {
		recordProfileNanos(PROFILE_VISUAL_SIGNIFICANCE, startNanos);
	}

	public static ProfileSnapshot profileSnapshot() {
		finishProfileFrame();
		return new ProfileSnapshot(
			averageProfileMs(PROFILE_PARTICLE_CULLING),
			averageProfileMs(PROFILE_BLOCK_ENTITY_CULLING),
			averageProfileMs(PROFILE_ENTITY_CULLING),
			averageProfileMs(PROFILE_ADAPTIVE_QUALITY),
			averageProfileMs(PROFILE_VISUAL_SIGNIFICANCE),
			totalOptiminiumCpuMs(),
			worstProfileMs(PROFILE_PARTICLE_CULLING),
			worstProfileMs(PROFILE_BLOCK_ENTITY_CULLING),
			worstProfileMs(PROFILE_ENTITY_CULLING),
			worstProfileMs(PROFILE_ADAPTIVE_QUALITY),
			worstProfileMs(PROFILE_VISUAL_SIGNIFICANCE),
			worstOptiminiumCpuMs()
		);
	}

	private static void flushMetrics() {
		OptiminiumMetrics.culledBlockEntityRenders(pendingCulledBlockEntityRenders);
		OptiminiumMetrics.hiddenNameTags(pendingHiddenNameTags);
		OptiminiumMetrics.hiddenParticles(pendingHiddenParticles);
		pendingCulledBlockEntityRenders = 0;
		pendingHiddenNameTags = 0;
		pendingHiddenParticles = 0;
	}

	private static int reportedRawVisibleBlockEntities() {
		return Math.max(lastRawVisibleBlockEntities, rawVisibleBlockEntitiesThisFrame);
	}

	private static int reportedMaxRawVisibleBlockEntities() {
		return Math.max(maxRawVisibleBlockEntities, rawVisibleBlockEntitiesThisFrame);
	}

	private static long reportedDenseSceneFrames() {
		return 0L;
	}

	private static void ensureFrameState() {
		if (!frameStateReady) {
			onFrameStart();
		}
	}

	private static boolean shouldCullGraphicsEffects() {
		return OptiminiumSettings.isEnabled() && OptiminiumSettings.isGpuOptimizer();
	}

	private static boolean shouldDeferBlockEntityRender(BlockEntity blockEntity, double distanceSqr) {
		if (blockEntityWorkScale > 0.90D || distanceSqr <= BLOCK_ENTITY_RENDER_BUDGET_DISTANCE_SQR) {
			return false;
		}
		int budget = maxDistantBlockEntityRendersPerFrame();
		if (distantBlockEntityRendersThisFrame < budget) {
			distantBlockEntityRendersThisFrame++;
			return false;
		}
		return !isPriorityBlockEntity(blockEntity);
	}

	public static ParticleProfileSnapshot particleProfileSnapshot() {
		return new ParticleProfileSnapshot(particleChecksAttempted, particleChecksRejected,
			particleChecksRendered, particleCheckNanos / 1_000_000.0D,
			particleChecksAttempted == 0L ? 0.0D : particleCheckNanos / (double)particleChecksAttempted / 1_000_000.0D);
	}

	public record ParticleProfileSnapshot(long attempted, long rejected, long rendered,
		double rejectionCheckMs, double averageCheckMs) {
		public String toLine() {
			return String.format(java.util.Locale.US,
				"particleChecksAttempted=%d, particleChecksRejected=%d, particleChecksRendered=%d, particleRejectionCheckMs=%.3f, particleAverageCheckMs=%.6f",
				attempted, rejected, rendered, rejectionCheckMs, averageCheckMs);
		}
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
		BlockState state = blockEntity.getBlockState();
		return type == BlockEntityType.BEACON
			|| type == BlockEntityType.CONDUIT
			|| type == BlockEntityType.END_PORTAL
			|| type == BlockEntityType.END_GATEWAY
			|| type == BlockEntityType.PISTON
			|| state.getLightEmission() > 0
			|| state.isRandomlyTicking()
			|| state.getRenderShape() != RenderShape.MODEL;
	}

	private static void updateDenseSceneState(boolean enabled) {
		long profileStart = profileStart();
		try {
			boolean rawDenseScene = false;
			if (rawDenseScene) {
				denseSceneFrames++;
			}
			if (true) {
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
				lastAdaptiveReason = adaptiveReason(false, false, false);
				logAdaptiveReason();
				return;
			}

			double budgetParticleScale = 1.0D;
			double budgetBlockEntityScale = 1.0D;
			double budgetAllScale = 1.0D;
			String reason = "frame-pressure";
			// Also apply gpuWorkScale (from raw frame time spikes) as an upper bound
			particleWorkScale = Math.min(gpuWorkScale, budgetParticleScale);
			blockEntityWorkScale = Math.min(gpuWorkScale, budgetBlockEntityScale);
			rebuildWorkScale = Math.min(gpuWorkScale, budgetAllScale);
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
		if (denseSceneActive || sustainedFramePressure || (lastRawSpike && lastPacingSpike)) {
			double stepDown = denseSceneActive || sustainedFramePressure ? GPU_SCALE_STEP_DOWN : GPU_SCALE_STEP_DOWN * 0.5D;
			gpuWorkScale = Math.max(minScale, gpuWorkScale - stepDown);
		} else if (pacingNanos < targetFrameNanos * 0.86D) {
			gpuWorkScale = Math.min(1.0D, gpuWorkScale + GPU_SCALE_STEP_UP);
		} else {
			gpuWorkScale = 1.0D;
		}
	}

	private static void updateGraphicsEffectPressure(boolean enabled) {
		if (!enabled || !OptiminiumSettings.isGpuOptimizer()) {
			cloudSkipHoldFrames = 0;
			weatherSkipHoldFrames = 0;
			return;
		}
		if (sustainedFramePressure || gpuWorkScale <= CLOUD_SKIP_WORK_SCALE) {
			cloudSkipHoldFrames = CLOUD_SKIP_HOLD_FRAMES;
		} else if (cloudSkipHoldFrames > 0) {
			cloudSkipHoldFrames--;
		}
		if (sustainedFramePressure && gpuWorkScale <= WEATHER_SKIP_WORK_SCALE) {
			weatherSkipHoldFrames = WEATHER_SKIP_HOLD_FRAMES;
		} else if (weatherSkipHoldFrames > 0) {
			weatherSkipHoldFrames--;
		}
	}

	private static void recordAdaptiveActivation() {
		boolean attempted = sustainedFramePressure || lastDenseSceneTrigger;
		boolean particleActive = particleWorkScale < 1.0D;
		boolean blockEntityActive = blockEntityWorkScale < 1.0D;
		if (attempted) {
			adaptiveActivationAttempts++;
		}
		if (attempted && (particleActive || blockEntityActive || rebuildWorkScale < 1.0D)) {
			adaptiveActivationSuccesses++;
		}
		if (particleActive) {
			adaptiveParticleFrames++;
		}
		if (blockEntityActive) {
			adaptiveBlockEntityFrames++;
		}
		lastAdaptiveReason = adaptiveReason(attempted, particleActive, blockEntityActive);
		logAdaptiveReason();
	}

	private static String adaptiveReason(boolean attempted, boolean particleActive, boolean blockEntityActive) {
		if (!OptiminiumSettings.isEnabled()) {
			return "disabled";
		}
		if (!OptiminiumSettings.isGpuOptimizer()) {
			return "gpu optimizer off";
		}
		if (attempted && (particleActive || blockEntityActive || rebuildWorkScale < 1.0D)) {
			return "active: sustainedPressure=" + sustainedFramePressure + ", denseScene=" + lastDenseSceneTrigger;
		}
		return "inactive: sustainedPressure=" + sustainedFramePressure + ", rawSpike=" + lastRawSpike
			+ ", pacingSpike=" + lastPacingSpike;
	}

	private static void logAdaptiveReason() {
		if (profilingEnabled) {
			return;
		}
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

	private static boolean isImportantParticle(ParticleType<?> type) {
		return type == ParticleTypes.EXPLOSION_EMITTER
			|| type == ParticleTypes.EXPLOSION
			|| type == ParticleTypes.FLASH
			|| type == ParticleTypes.FIREWORK
			|| type == ParticleTypes.TOTEM_OF_UNDYING
			|| type == ParticleTypes.DAMAGE_INDICATOR
			|| type == ParticleTypes.ELDER_GUARDIAN;
	}

	private static boolean isLowPriorityParticle(ParticleType<?> type) {
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
		double adaptiveQualityMs,
		double visualSignificanceMs,
		double totalOptiminiumCpuMs,
		double worstParticleCullingMs,
		double worstBlockEntityCullingMs,
		double worstEntityCullingMs,
		double worstAdaptiveQualityMs,
		double worstVisualSignificanceMs,
		double worstOptiminiumCpuMs
	) {
		public ProfileSnapshot(double particleCullingMs, double blockEntityCullingMs, double entityCullingMs,
				double adaptiveQualityMs, double totalOptiminiumCpuMs,
				double worstParticleCullingMs, double worstBlockEntityCullingMs, double worstEntityCullingMs,
				double worstOptiminiumCpuMs) {
			this(particleCullingMs, blockEntityCullingMs, entityCullingMs, adaptiveQualityMs,
				0.0D, totalOptiminiumCpuMs,
				worstParticleCullingMs, worstBlockEntityCullingMs, worstEntityCullingMs,
				0.0D, 0.0D, worstOptiminiumCpuMs);
		}
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
