package net.optiminium.optimization;

import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class OptiminiumSettings {
	private static final Path CONFIG_FILE = FMLPaths.CONFIGDIR.get().resolve("optiminium.properties");
	private static volatile boolean enabled = true;
	private static volatile boolean framePacing = true;
	private static volatile boolean gpuTimerPacing = true;
	private static volatile int gpuTargetFps = 60;
	private static volatile int gpuMinRenderScalePercent = 60;
	private static volatile int chunkUploadsPerFrame = 8;
	private static volatile int entityAlwaysRenderDistanceBlocks = 50;
	private static volatile boolean particleLimiter = true;
	private static volatile int particleRenderDistanceBlocks = 64;
	private static volatile int maxParticlesPerFrame = 96;
	private static volatile boolean asyncResourceStreaming = true;
	private static volatile boolean blockEntityCulling = true;
	private static volatile int blockEntityDistanceScalePercent = 100;
	private static volatile int denseBlockEntityThreshold = 512;
	private static volatile DenseSceneAdaptiveMode denseSceneAdaptiveMode = DenseSceneAdaptiveMode.BALANCED;
	private static volatile boolean occlusionRebuildPriority = true;
	private static volatile boolean shaderResourceCache = true;
	private static volatile boolean experimentalRendererFeatures = false;
	private static volatile boolean experimentalUploadStallLimiter = false;
	private static volatile boolean experimentalTemporalSignificance = false;
	private static volatile boolean adaptiveSimulationDistance = true;
	private static volatile int adaptiveSimulationTargetMspt = 50;
	private static volatile int adaptiveSimulationMinDistanceChunks = 4;
	private static volatile boolean smartTickScheduler = true;
	private static volatile boolean aiPathfindingOptimizer = true;

	static {
		load();
	}

	private OptiminiumSettings() {
	}

	public static boolean isEnabled() {
		return enabled;
	}

	public static boolean toggleEnabled() {
		enabled = !enabled;
		save();
		return enabled;
	}

	public static void setEnabled(boolean newEnabled) {
		enabled = newEnabled;
		save();
	}

	public static void applyPreset(Preset preset) {
		enabled = true;
		framePacing = true;
		gpuTimerPacing = true;
		asyncResourceStreaming = true;
		shaderResourceCache = true;
		blockEntityCulling = true;
		occlusionRebuildPriority = true;
		adaptiveSimulationDistance = true;
		smartTickScheduler = true;
		aiPathfindingOptimizer = true;
		switch (preset) {
			case HIGH_PERFORMANCE -> {
				gpuTargetFps = 120;
				gpuMinRenderScalePercent = 45;
				chunkUploadsPerFrame = 4;
				entityAlwaysRenderDistanceBlocks = 40;
				particleLimiter = true;
				particleRenderDistanceBlocks = 32;
				maxParticlesPerFrame = 64;
				blockEntityDistanceScalePercent = 75;
				denseSceneAdaptiveMode = DenseSceneAdaptiveMode.AGGRESSIVE;
				adaptiveSimulationTargetMspt = 45;
				adaptiveSimulationMinDistanceChunks = 3;
			}
			case MEDIUM -> {
				gpuTargetFps = 75;
				gpuMinRenderScalePercent = 60;
				chunkUploadsPerFrame = 8;
				entityAlwaysRenderDistanceBlocks = 50;
				particleLimiter = true;
				particleRenderDistanceBlocks = 64;
				maxParticlesPerFrame = 128;
				blockEntityDistanceScalePercent = 100;
				denseSceneAdaptiveMode = DenseSceneAdaptiveMode.BALANCED;
				adaptiveSimulationTargetMspt = 50;
				adaptiveSimulationMinDistanceChunks = 4;
			}
			case QUALITY -> {
				gpuTargetFps = 60;
				gpuMinRenderScalePercent = 85;
				chunkUploadsPerFrame = 16;
				entityAlwaysRenderDistanceBlocks = 70;
				particleLimiter = true;
				particleRenderDistanceBlocks = 128;
				maxParticlesPerFrame = 256;
				blockEntityDistanceScalePercent = 160;
				denseSceneAdaptiveMode = DenseSceneAdaptiveMode.CONSERVATIVE;
				adaptiveSimulationTargetMspt = 55;
				adaptiveSimulationMinDistanceChunks = 6;
			}
		}
		save();
	}

	public static boolean isChunkRebuildScheduling() {
		return true;
	}

	public static boolean isOcclusionRebuildPriority() {
		return occlusionRebuildPriority;
	}

	public static boolean toggleOcclusionRebuildPriority() {
		occlusionRebuildPriority = !occlusionRebuildPriority;
		save();
		return occlusionRebuildPriority;
	}

	public static int getChunkRebuildsPerFrame() {
		return 2;
	}

	public static int getSyncChunkRebuildsPerFrame() {
		return 0;
	}

	public static int getChunkUploadsPerFrame() {
		return chunkUploadsPerFrame;
	}

	public static void setChunkUploadsPerFrame(int uploadsPerFrame) {
		chunkUploadsPerFrame = clamp(uploadsPerFrame, 1, 64);
		save();
	}

	public static boolean isLightingDeduplication() {
		return true;
	}

	public static boolean isGpuOptimizer() {
		return framePacing;
	}

	public static boolean toggleFramePacing() {
		framePacing = !framePacing;
		save();
		return framePacing;
	}

	public static boolean isGpuTimerPacing() {
		return gpuTimerPacing;
	}

	public static boolean toggleGpuTimerPacing() {
		gpuTimerPacing = !gpuTimerPacing;
		save();
		return gpuTimerPacing;
	}

	public static int getGpuTargetFps() {
		return gpuTargetFps;
	}

	public static void setGpuTargetFps(int targetFps) {
		gpuTargetFps = clamp(targetFps, 30, 240);
		save();
	}

	public static int getGpuMinRenderScalePercent() {
		return gpuMinRenderScalePercent;
	}

	public static void setGpuMinRenderScalePercent(int scalePercent) {
		gpuMinRenderScalePercent = clamp(scalePercent, 35, 100);
		save();
	}

	public static boolean isGraphicsEffectCulling() {
		return true;
	}

	public static boolean isAsyncResourceStreaming() {
		return asyncResourceStreaming;
	}

	public static boolean toggleAsyncResourceStreaming() {
		asyncResourceStreaming = !asyncResourceStreaming;
		save();
		return asyncResourceStreaming;
	}

	public static boolean isShaderResourceCache() {
		return shaderResourceCache;
	}

	public static boolean toggleShaderResourceCache() {
		shaderResourceCache = !shaderResourceCache;
		save();
		return shaderResourceCache;
	}

	public static boolean isExperimentalRendererFeatures() {
		return experimentalRendererFeatures;
	}

	public static boolean toggleExperimentalRendererFeatures() {
		experimentalRendererFeatures = !experimentalRendererFeatures;
		save();
		return experimentalRendererFeatures;
	}

	public static boolean isExperimentalUploadStallLimiter() {
		return experimentalRendererFeatures && experimentalUploadStallLimiter;
	}

	public static boolean toggleExperimentalUploadStallLimiter() {
		experimentalUploadStallLimiter = !experimentalUploadStallLimiter;
		save();
		return experimentalUploadStallLimiter;
	}

	public static boolean isExperimentalTemporalSignificance() {
		return experimentalRendererFeatures && experimentalTemporalSignificance;
	}

	public static boolean toggleExperimentalTemporalSignificance() {
		experimentalTemporalSignificance = !experimentalTemporalSignificance;
		save();
		return experimentalTemporalSignificance;
	}

	public static boolean isClientRenderCulling() {
		return true;
	}

	public static int getEntityRenderDistanceScalePercent() {
		return 100;
	}

	public static int getEntityAlwaysRenderDistanceBlocks() {
		return entityAlwaysRenderDistanceBlocks;
	}

	public static void setEntityAlwaysRenderDistanceBlocks(int distanceBlocks) {
		entityAlwaysRenderDistanceBlocks = clamp(distanceBlocks, 10, 200);
		save();
	}

	public static boolean isBlockEntityCulling() {
		return blockEntityCulling;
	}

	public static boolean toggleBlockEntityCulling() {
		blockEntityCulling = !blockEntityCulling;
		save();
		return blockEntityCulling;
	}

	public static int getBlockEntityDistanceScalePercent() {
		return blockEntityDistanceScalePercent;
	}

	public static void setBlockEntityDistanceScalePercent(int scalePercent) {
		blockEntityDistanceScalePercent = clamp(scalePercent, 25, 200);
		save();
	}

	public static int getDenseBlockEntityThreshold() {
		return denseBlockEntityThreshold;
	}

	public static void setDenseBlockEntityThreshold(int threshold) {
		denseBlockEntityThreshold = clamp(threshold, 64, 4096);
		save();
	}

	public static DenseSceneAdaptiveMode getDenseSceneAdaptiveMode() {
		return denseSceneAdaptiveMode;
	}

	public static DenseSceneAdaptiveMode cycleDenseSceneAdaptiveMode() {
		DenseSceneAdaptiveMode[] modes = DenseSceneAdaptiveMode.values();
		denseSceneAdaptiveMode = modes[(denseSceneAdaptiveMode.ordinal() + 1) % modes.length];
		save();
		return denseSceneAdaptiveMode;
	}

	public static boolean isCrowdCulling() {
		return true;
	}

	public static int getCrowdRenderBudgetPercent() {
		return 100;
	}

	public static boolean isParticleLimiter() {
		return particleLimiter;
	}

	public static boolean toggleParticleLimiter() {
		particleLimiter = !particleLimiter;
		save();
		return particleLimiter;
	}

	public static int getParticleRenderDistanceBlocks() {
		return particleRenderDistanceBlocks;
	}

	public static void setParticleRenderDistanceBlocks(int distanceBlocks) {
		particleRenderDistanceBlocks = clamp(distanceBlocks, 16, 160);
		save();
	}

	public static int getMaxParticlesPerFrame() {
		return maxParticlesPerFrame;
	}

	public static void setMaxParticlesPerFrame(int maxParticles) {
		maxParticlesPerFrame = clamp(maxParticles, 16, 512);
		save();
	}

	public static boolean isAmbientSoundLimiter() {
		return true;
	}

	public static int getAmbientSoundBudget() {
		return 32;
	}

	public static boolean isServerEntityTickThrottling() {
		return smartTickScheduler;
	}

	public static boolean isSmartTickScheduler() {
		return smartTickScheduler;
	}

	public static boolean toggleSmartTickScheduler() {
		smartTickScheduler = !smartTickScheduler;
		save();
		return smartTickScheduler;
	}

	public static boolean isAiPathfindingOptimizer() {
		return aiPathfindingOptimizer;
	}

	public static boolean toggleAiPathfindingOptimizer() {
		aiPathfindingOptimizer = !aiPathfindingOptimizer;
		save();
		return aiPathfindingOptimizer;
	}

	public static int getFarEntityTickInterval() {
		return 40;
	}

	public static int getMaxFarEntityTickInterval() {
		return 100;
	}

	public static boolean isAdaptiveOptimizer() {
		return true;
	}

	public static boolean isAdaptiveSimulationDistance() {
		return adaptiveSimulationDistance;
	}

	public static boolean toggleAdaptiveSimulationDistance() {
		adaptiveSimulationDistance = !adaptiveSimulationDistance;
		save();
		return adaptiveSimulationDistance;
	}

	public static int getAdaptiveSimulationTargetMspt() {
		return adaptiveSimulationTargetMspt;
	}

	public static void setAdaptiveSimulationTargetMspt(int targetMspt) {
		adaptiveSimulationTargetMspt = clamp(targetMspt, 35, 80);
		save();
	}

	public static int getAdaptiveSimulationMinDistanceChunks() {
		return adaptiveSimulationMinDistanceChunks;
	}

	public static void setAdaptiveSimulationMinDistanceChunks(int distanceChunks) {
		adaptiveSimulationMinDistanceChunks = clamp(distanceChunks, 2, 12);
		save();
	}

	public static boolean isItemVirtualization() {
		return true;
	}

	public static int getItemClusterThreshold() {
		return 24;
	}

	public static boolean isXpOrbMerging() {
		return true;
	}

	public static int getXpMergeThreshold() {
		return 8;
	}

	public static boolean isRedstoneDeduplication() {
		return true;
	}

	public static boolean isBlockEntityUpdateThrottling() {
		return true;
	}

	public static int getBlockEntitySleepAfterTicks() {
		return 20 * 10;
	}

	public static int getBlockEntityWakeRadiusBlocks() {
		return 48;
	}

	public static int getSleepingBlockEntityTicksPerTick() {
		return 8;
	}

	public static int getSleepingBlockEntityTickInterval() {
		return 20 * 10;
	}

	private static void load() {
		if (!Files.isRegularFile(CONFIG_FILE)) {
			return;
		}
		Properties properties = new Properties();
		try (InputStream input = Files.newInputStream(CONFIG_FILE)) {
			properties.load(input);
			enabled = Boolean.parseBoolean(properties.getProperty("enabled", Boolean.toString(enabled)));
			framePacing = Boolean.parseBoolean(properties.getProperty("framePacing", Boolean.toString(framePacing)));
			gpuTimerPacing = Boolean.parseBoolean(properties.getProperty("gpuTimerPacing", Boolean.toString(gpuTimerPacing)));
			gpuTargetFps = clamp(Integer.parseInt(properties.getProperty("gpuTargetFps", Integer.toString(gpuTargetFps))), 30, 240);
			gpuMinRenderScalePercent = clamp(Integer.parseInt(properties.getProperty("gpuMinRenderScalePercent", Integer.toString(gpuMinRenderScalePercent))), 35, 100);
			chunkUploadsPerFrame = clamp(Integer.parseInt(properties.getProperty("chunkUploadsPerFrame", Integer.toString(chunkUploadsPerFrame))), 1, 64);
			entityAlwaysRenderDistanceBlocks = clamp(Integer.parseInt(properties.getProperty("entityAlwaysRenderDistanceBlocks", Integer.toString(entityAlwaysRenderDistanceBlocks))), 10, 200);
			particleLimiter = Boolean.parseBoolean(properties.getProperty("particleLimiter", Boolean.toString(particleLimiter)));
			particleRenderDistanceBlocks = clamp(Integer.parseInt(properties.getProperty("particleRenderDistanceBlocks", Integer.toString(particleRenderDistanceBlocks))), 16, 160);
			maxParticlesPerFrame = clamp(Integer.parseInt(properties.getProperty("maxParticlesPerFrame", Integer.toString(maxParticlesPerFrame))), 16, 512);
			asyncResourceStreaming = Boolean.parseBoolean(properties.getProperty("asyncResourceStreaming", Boolean.toString(asyncResourceStreaming)));
			blockEntityCulling = Boolean.parseBoolean(properties.getProperty("blockEntityCulling", Boolean.toString(blockEntityCulling)));
			blockEntityDistanceScalePercent = clamp(Integer.parseInt(properties.getProperty("blockEntityDistanceScalePercent", Integer.toString(blockEntityDistanceScalePercent))), 25, 200);
			denseBlockEntityThreshold = clamp(Integer.parseInt(properties.getProperty("denseBlockEntityThreshold", Integer.toString(denseBlockEntityThreshold))), 64, 4096);
			denseSceneAdaptiveMode = DenseSceneAdaptiveMode.parse(properties.getProperty("denseSceneAdaptiveMode", denseSceneAdaptiveMode.name()));
			occlusionRebuildPriority = Boolean.parseBoolean(properties.getProperty("occlusionRebuildPriority", Boolean.toString(occlusionRebuildPriority)));
			shaderResourceCache = Boolean.parseBoolean(properties.getProperty("shaderResourceCache", Boolean.toString(shaderResourceCache)));
			experimentalRendererFeatures = Boolean.parseBoolean(properties.getProperty("experimentalRendererFeatures", Boolean.toString(experimentalRendererFeatures)));
			experimentalUploadStallLimiter = Boolean.parseBoolean(properties.getProperty("experimentalUploadStallLimiter", Boolean.toString(experimentalUploadStallLimiter)));
			experimentalTemporalSignificance = Boolean.parseBoolean(properties.getProperty("experimentalTemporalSignificance", Boolean.toString(experimentalTemporalSignificance)));
			adaptiveSimulationDistance = Boolean.parseBoolean(properties.getProperty("adaptiveSimulationDistance", Boolean.toString(adaptiveSimulationDistance)));
			adaptiveSimulationTargetMspt = clamp(Integer.parseInt(properties.getProperty("adaptiveSimulationTargetMspt", Integer.toString(adaptiveSimulationTargetMspt))), 35, 80);
			adaptiveSimulationMinDistanceChunks = clamp(Integer.parseInt(properties.getProperty("adaptiveSimulationMinDistanceChunks", Integer.toString(adaptiveSimulationMinDistanceChunks))), 2, 12);
			smartTickScheduler = Boolean.parseBoolean(properties.getProperty("smartTickScheduler", Boolean.toString(smartTickScheduler)));
			aiPathfindingOptimizer = Boolean.parseBoolean(properties.getProperty("aiPathfindingOptimizer", Boolean.toString(aiPathfindingOptimizer)));
		} catch (IOException | NumberFormatException ignored) {
		}
	}

	private static void save() {
		Properties properties = new Properties();
		properties.setProperty("enabled", Boolean.toString(enabled));
		properties.setProperty("framePacing", Boolean.toString(framePacing));
		properties.setProperty("gpuTimerPacing", Boolean.toString(gpuTimerPacing));
		properties.setProperty("gpuTargetFps", Integer.toString(gpuTargetFps));
		properties.setProperty("gpuMinRenderScalePercent", Integer.toString(gpuMinRenderScalePercent));
		properties.setProperty("chunkUploadsPerFrame", Integer.toString(chunkUploadsPerFrame));
		properties.setProperty("entityAlwaysRenderDistanceBlocks", Integer.toString(entityAlwaysRenderDistanceBlocks));
		properties.setProperty("particleLimiter", Boolean.toString(particleLimiter));
		properties.setProperty("particleRenderDistanceBlocks", Integer.toString(particleRenderDistanceBlocks));
		properties.setProperty("maxParticlesPerFrame", Integer.toString(maxParticlesPerFrame));
		properties.setProperty("asyncResourceStreaming", Boolean.toString(asyncResourceStreaming));
		properties.setProperty("blockEntityCulling", Boolean.toString(blockEntityCulling));
		properties.setProperty("blockEntityDistanceScalePercent", Integer.toString(blockEntityDistanceScalePercent));
		properties.setProperty("denseBlockEntityThreshold", Integer.toString(denseBlockEntityThreshold));
		properties.setProperty("denseSceneAdaptiveMode", denseSceneAdaptiveMode.name());
		properties.setProperty("occlusionRebuildPriority", Boolean.toString(occlusionRebuildPriority));
		properties.setProperty("shaderResourceCache", Boolean.toString(shaderResourceCache));
		properties.setProperty("experimentalRendererFeatures", Boolean.toString(experimentalRendererFeatures));
		properties.setProperty("experimentalUploadStallLimiter", Boolean.toString(experimentalUploadStallLimiter));
		properties.setProperty("experimentalTemporalSignificance", Boolean.toString(experimentalTemporalSignificance));
		properties.setProperty("adaptiveSimulationDistance", Boolean.toString(adaptiveSimulationDistance));
		properties.setProperty("adaptiveSimulationTargetMspt", Integer.toString(adaptiveSimulationTargetMspt));
		properties.setProperty("adaptiveSimulationMinDistanceChunks", Integer.toString(adaptiveSimulationMinDistanceChunks));
		properties.setProperty("smartTickScheduler", Boolean.toString(smartTickScheduler));
		properties.setProperty("aiPathfindingOptimizer", Boolean.toString(aiPathfindingOptimizer));
		try {
			Files.createDirectories(CONFIG_FILE.getParent());
			try (OutputStream output = Files.newOutputStream(CONFIG_FILE)) {
				properties.store(output, "Optiminium settings");
			}
		} catch (IOException ignored) {
		}
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	public enum DenseSceneAdaptiveMode {
		OFF,
		CONSERVATIVE,
		BALANCED,
		AGGRESSIVE;

		private static DenseSceneAdaptiveMode parse(String value) {
			try {
				return DenseSceneAdaptiveMode.valueOf(value.toUpperCase());
			} catch (IllegalArgumentException exception) {
				return BALANCED;
			}
		}
	}

	public enum Preset {
		HIGH_PERFORMANCE,
		MEDIUM,
		QUALITY
	}
}
