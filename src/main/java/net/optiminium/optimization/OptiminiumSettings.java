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
	private static volatile int gpuTargetFps = 60;
	private static volatile int gpuMinRenderScalePercent = 60;
	private static volatile int entityAlwaysRenderDistanceBlocks = 50;
	private static volatile boolean particleLimiter = true;
	private static volatile int particleRenderDistanceBlocks = 64;
	private static volatile int maxParticlesPerFrame = 96;
	private static volatile boolean blockEntityCulling = true;
	private static volatile int blockEntityDistanceScalePercent = 90;
	private static volatile int denseBlockEntityThreshold = 512;
	private static volatile boolean blockEntityRenderCache = true;
	private static volatile boolean blockEntityVirtualizationEnabled = false;
	private static volatile boolean blockEntityVirtualizationDebugProxies = false;
	private static volatile BlockEntityVirtualizationAggressiveness blockEntityVirtualizationAggressiveness = BlockEntityVirtualizationAggressiveness.CONSERVATIVE;
	private static volatile int blockEntityRenderCacheMaxEntries = 4096;
	private static volatile boolean blockEntityRenderCacheDebug = false;
	private static volatile DenseSceneAdaptiveMode denseSceneAdaptiveMode = DenseSceneAdaptiveMode.BALANCED;
	private static volatile boolean experimentalTemporalSignificance = false;
	private static volatile boolean enableOpenGlTweaks = true;
	private static volatile OpenGlOptimizationMode openGlOptimizationMode = OpenGlOptimizationMode.OFF;

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

	public static Snapshot snapshot() {
		return new Snapshot(enabled, framePacing, particleLimiter, blockEntityCulling,
			blockEntityRenderCache, blockEntityVirtualizationEnabled, blockEntityVirtualizationDebugProxies,
			blockEntityVirtualizationAggressiveness, denseSceneAdaptiveMode, enableOpenGlTweaks, openGlOptimizationMode);
	}

	public static void restore(Snapshot snapshot) {
		enabled = snapshot.enabled;
		framePacing = snapshot.framePacing;
		particleLimiter = snapshot.particleLimiter;
		blockEntityCulling = snapshot.blockEntityCulling;
		blockEntityRenderCache = snapshot.blockEntityRenderCache;
		blockEntityVirtualizationEnabled = snapshot.blockEntityVirtualizationEnabled;
		blockEntityVirtualizationDebugProxies = snapshot.blockEntityVirtualizationDebugProxies;
		blockEntityVirtualizationAggressiveness = snapshot.blockEntityVirtualizationAggressiveness;
		denseSceneAdaptiveMode = snapshot.denseSceneAdaptiveMode;
		enableOpenGlTweaks = snapshot.enableOpenGlTweaks;
		openGlOptimizationMode = snapshot.openGlOptimizationMode;
		save();
	}

	public static void disableBenchmarkFeatures() {
		framePacing = false;
		particleLimiter = false;
		blockEntityCulling = false;
		blockEntityRenderCache = false;
		blockEntityVirtualizationEnabled = false;
		blockEntityVirtualizationDebugProxies = false;
		blockEntityVirtualizationAggressiveness = BlockEntityVirtualizationAggressiveness.CONSERVATIVE;
		experimentalTemporalSignificance = false;
		denseSceneAdaptiveMode = DenseSceneAdaptiveMode.OFF;
		enableOpenGlTweaks = false;
		openGlOptimizationMode = OpenGlOptimizationMode.OFF;
		save();
	}

	public static void applyPreset(Preset preset) {
		enabled = true;
		framePacing = true;
		blockEntityCulling = true;
		blockEntityVirtualizationEnabled = false;
		blockEntityVirtualizationDebugProxies = false;
		blockEntityVirtualizationAggressiveness = BlockEntityVirtualizationAggressiveness.CONSERVATIVE;
		openGlOptimizationMode = OpenGlOptimizationMode.OFF;
		switch (preset) {
			case HIGH_PERFORMANCE -> {
				gpuTargetFps = 120;
				gpuMinRenderScalePercent = 45;
				entityAlwaysRenderDistanceBlocks = 40;
				particleLimiter = true;
				particleRenderDistanceBlocks = 32;
				maxParticlesPerFrame = 64;
				blockEntityDistanceScalePercent = 75;
				denseSceneAdaptiveMode = DenseSceneAdaptiveMode.AGGRESSIVE;
			}
			case MEDIUM -> {
				gpuTargetFps = 75;
				gpuMinRenderScalePercent = 60;
				entityAlwaysRenderDistanceBlocks = 50;
				particleLimiter = true;
				particleRenderDistanceBlocks = 64;
				maxParticlesPerFrame = 128;
				blockEntityDistanceScalePercent = 100;
				denseSceneAdaptiveMode = DenseSceneAdaptiveMode.BALANCED;
			}
			case QUALITY -> {
				gpuTargetFps = 60;
				gpuMinRenderScalePercent = 85;
				entityAlwaysRenderDistanceBlocks = 70;
				particleLimiter = true;
				particleRenderDistanceBlocks = 128;
				maxParticlesPerFrame = 256;
				blockEntityDistanceScalePercent = 160;
				denseSceneAdaptiveMode = DenseSceneAdaptiveMode.CONSERVATIVE;
			}
		}
		save();
	}

	public static boolean isGpuOptimizer() {
		return framePacing;
	}

	public static boolean toggleFramePacing() {
		framePacing = !framePacing;
		save();
		return framePacing;
	}

	public static void setFramePacing(boolean value) {
		framePacing = value;
		save();
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

	public static boolean isExperimentalTemporalSignificance() {
		return experimentalTemporalSignificance;
	}

	public static boolean isOpenGlTweaksEnabled() {
		return enableOpenGlTweaks;
	}

	public static void setOpenGlTweaksEnabled(boolean value) {
		enableOpenGlTweaks = value;
		save();
	}

	public static OpenGlOptimizationMode getOpenGlOptimizationMode() {
		return enabled && enableOpenGlTweaks ? openGlOptimizationMode : OpenGlOptimizationMode.OFF;
	}

	public static OpenGlOptimizationMode cycleOpenGlOptimizationMode() {
		OpenGlOptimizationMode[] modes = OpenGlOptimizationMode.values();
		openGlOptimizationMode = modes[(openGlOptimizationMode.ordinal() + 1) % modes.length];
		save();
		return openGlOptimizationMode;
	}

	public static void setOpenGlOptimizationMode(OpenGlOptimizationMode mode) {
		openGlOptimizationMode = mode;
		save();
	}

	public static boolean toggleExperimentalTemporalSignificance() {
		experimentalTemporalSignificance = !experimentalTemporalSignificance;
		save();
		return experimentalTemporalSignificance;
	}

	public static void setExperimentalTemporalSignificance(boolean value) {
		experimentalTemporalSignificance = value;
		save();
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

	public static void setBlockEntityCulling(boolean value) {
		blockEntityCulling = value;
		save();
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

	public static boolean isBlockEntityRenderCache() {
		return blockEntityRenderCache;
	}

	public static boolean toggleBlockEntityRenderCache() {
		blockEntityRenderCache = !blockEntityRenderCache;
		save();
		return blockEntityRenderCache;
	}

	public static void setBlockEntityRenderCache(boolean value) {
		blockEntityRenderCache = value;
		save();
	}

	public static boolean isBlockEntityRenderVirtualization() {
		return isBlockEntityVirtualizationEnabled();
	}

	public static boolean toggleBlockEntityRenderVirtualization() {
		return toggleBlockEntityVirtualizationEnabled();
	}

	public static void setBlockEntityRenderVirtualization(boolean value) {
		setBlockEntityVirtualizationEnabled(value);
	}

	public static boolean isBlockEntityVirtualizationEnabled() {
		return blockEntityVirtualizationEnabled;
	}

	public static boolean toggleBlockEntityVirtualizationEnabled() {
		blockEntityVirtualizationEnabled = !blockEntityVirtualizationEnabled;
		save();
		return blockEntityVirtualizationEnabled;
	}

	public static void setBlockEntityVirtualizationEnabled(boolean value) {
		blockEntityVirtualizationEnabled = value;
		save();
	}

	public static boolean isBlockEntityVirtualizationDebugProxies() {
		return blockEntityVirtualizationDebugProxies;
	}

	public static boolean toggleBlockEntityVirtualizationDebugProxies() {
		blockEntityVirtualizationDebugProxies = !blockEntityVirtualizationDebugProxies;
		save();
		return blockEntityVirtualizationDebugProxies;
	}

	public static void setBlockEntityVirtualizationDebugProxies(boolean value) {
		blockEntityVirtualizationDebugProxies = value;
		save();
	}

	public static BlockEntityVirtualizationAggressiveness getBlockEntityVirtualizationAggressiveness() {
		return blockEntityVirtualizationAggressiveness;
	}

	public static BlockEntityVirtualizationAggressiveness cycleBlockEntityVirtualizationAggressiveness() {
		BlockEntityVirtualizationAggressiveness[] modes = BlockEntityVirtualizationAggressiveness.values();
		blockEntityVirtualizationAggressiveness = modes[(blockEntityVirtualizationAggressiveness.ordinal() + 1) % modes.length];
		save();
		return blockEntityVirtualizationAggressiveness;
	}

	public static void setBlockEntityVirtualizationAggressiveness(BlockEntityVirtualizationAggressiveness value) {
		blockEntityVirtualizationAggressiveness = value == null ? BlockEntityVirtualizationAggressiveness.CONSERVATIVE : value;
		save();
	}

	public static int getBlockEntityRenderCacheMaxEntries() {
		return blockEntityRenderCacheMaxEntries;
	}

	public static void setBlockEntityRenderCacheMaxEntries(int maxEntries) {
		blockEntityRenderCacheMaxEntries = clamp(maxEntries, 256, 65536);
		save();
	}

	public static boolean isBlockEntityRenderCacheDebug() {
		return blockEntityRenderCacheDebug;
	}

	public static boolean toggleBlockEntityRenderCacheDebug() {
		blockEntityRenderCacheDebug = !blockEntityRenderCacheDebug;
		save();
		return blockEntityRenderCacheDebug;
	}

	public static void setBlockEntityRenderCacheDebug(boolean value) {
		blockEntityRenderCacheDebug = value;
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

	public static void setDenseSceneAdaptiveMode(DenseSceneAdaptiveMode mode) {
		denseSceneAdaptiveMode = mode;
		save();
	}

	public static boolean isParticleLimiter() {
		return particleLimiter;
	}

	public static boolean toggleParticleLimiter() {
		particleLimiter = !particleLimiter;
		save();
		return particleLimiter;
	}

	public static void setParticleLimiter(boolean value) {
		particleLimiter = value;
		save();
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

	private static void load() {
		if (!Files.isRegularFile(CONFIG_FILE)) {
			return;
		}
		Properties properties = new Properties();
		try (InputStream input = Files.newInputStream(CONFIG_FILE)) {
			properties.load(input);
			enabled = Boolean.parseBoolean(properties.getProperty("enabled", Boolean.toString(enabled)));
			framePacing = Boolean.parseBoolean(properties.getProperty("framePacing", Boolean.toString(framePacing)));
			gpuTargetFps = clamp(Integer.parseInt(properties.getProperty("gpuTargetFps", Integer.toString(gpuTargetFps))), 30, 240);
			gpuMinRenderScalePercent = clamp(Integer.parseInt(properties.getProperty("gpuMinRenderScalePercent", Integer.toString(gpuMinRenderScalePercent))), 35, 100);
			entityAlwaysRenderDistanceBlocks = clamp(Integer.parseInt(properties.getProperty("entityAlwaysRenderDistanceBlocks", Integer.toString(entityAlwaysRenderDistanceBlocks))), 10, 200);
			particleLimiter = Boolean.parseBoolean(properties.getProperty("particleLimiter", Boolean.toString(particleLimiter)));
			particleRenderDistanceBlocks = clamp(Integer.parseInt(properties.getProperty("particleRenderDistanceBlocks", Integer.toString(particleRenderDistanceBlocks))), 16, 160);
			maxParticlesPerFrame = clamp(Integer.parseInt(properties.getProperty("maxParticlesPerFrame", Integer.toString(maxParticlesPerFrame))), 16, 512);
			blockEntityCulling = Boolean.parseBoolean(properties.getProperty("blockEntityCulling", Boolean.toString(blockEntityCulling)));
			blockEntityDistanceScalePercent = clamp(Integer.parseInt(properties.getProperty("blockEntityDistanceScalePercent", Integer.toString(blockEntityDistanceScalePercent))), 25, 200);
			denseBlockEntityThreshold = clamp(Integer.parseInt(properties.getProperty("denseBlockEntityThreshold", Integer.toString(denseBlockEntityThreshold))), 64, 4096);
			blockEntityRenderCache = Boolean.parseBoolean(properties.getProperty("blockEntityRenderCache", Boolean.toString(blockEntityRenderCache)));
			blockEntityVirtualizationEnabled = Boolean.parseBoolean(properties.getProperty("blockEntityVirtualizationEnabled",
				properties.getProperty("blockEntityRenderVirtualization", Boolean.toString(blockEntityVirtualizationEnabled))));
			blockEntityVirtualizationDebugProxies = Boolean.parseBoolean(properties.getProperty("blockEntityVirtualizationDebugProxies", Boolean.toString(blockEntityVirtualizationDebugProxies)));
			blockEntityVirtualizationAggressiveness = BlockEntityVirtualizationAggressiveness.parse(properties.getProperty("blockEntityVirtualizationAggressiveness", blockEntityVirtualizationAggressiveness.name()));
			blockEntityRenderCacheMaxEntries = clamp(Integer.parseInt(properties.getProperty("blockEntityRenderCacheMaxEntries", Integer.toString(blockEntityRenderCacheMaxEntries))), 256, 65536);
			blockEntityRenderCacheDebug = Boolean.parseBoolean(properties.getProperty("blockEntityRenderCacheDebug", Boolean.toString(blockEntityRenderCacheDebug)));
			denseSceneAdaptiveMode = DenseSceneAdaptiveMode.parse(properties.getProperty("denseSceneAdaptiveMode", denseSceneAdaptiveMode.name()));
			experimentalTemporalSignificance = Boolean.parseBoolean(properties.getProperty("visualSignificance", Boolean.toString(experimentalTemporalSignificance)));
			enableOpenGlTweaks = Boolean.parseBoolean(properties.getProperty("enableOpenGlTweaks", Boolean.toString(enableOpenGlTweaks)));
			openGlOptimizationMode = OpenGlOptimizationMode.parse(properties.getProperty("openGlOptimizationMode",
				properties.getProperty("glStateTrackerMode", openGlOptimizationMode.name())));
		} catch (IOException | NumberFormatException ignored) {
		}
	}

	private static void save() {
		Properties properties = new Properties();
		properties.setProperty("enabled", Boolean.toString(enabled));
		properties.setProperty("framePacing", Boolean.toString(framePacing));
		properties.setProperty("gpuTargetFps", Integer.toString(gpuTargetFps));
		properties.setProperty("gpuMinRenderScalePercent", Integer.toString(gpuMinRenderScalePercent));
		properties.setProperty("entityAlwaysRenderDistanceBlocks", Integer.toString(entityAlwaysRenderDistanceBlocks));
		properties.setProperty("particleLimiter", Boolean.toString(particleLimiter));
		properties.setProperty("particleRenderDistanceBlocks", Integer.toString(particleRenderDistanceBlocks));
		properties.setProperty("maxParticlesPerFrame", Integer.toString(maxParticlesPerFrame));
		properties.setProperty("blockEntityCulling", Boolean.toString(blockEntityCulling));
		properties.setProperty("blockEntityDistanceScalePercent", Integer.toString(blockEntityDistanceScalePercent));
		properties.setProperty("denseBlockEntityThreshold", Integer.toString(denseBlockEntityThreshold));
		properties.setProperty("blockEntityRenderCache", Boolean.toString(blockEntityRenderCache));
		properties.setProperty("blockEntityVirtualizationEnabled", Boolean.toString(blockEntityVirtualizationEnabled));
		properties.setProperty("blockEntityVirtualizationDebugProxies", Boolean.toString(blockEntityVirtualizationDebugProxies));
		properties.setProperty("blockEntityVirtualizationAggressiveness", blockEntityVirtualizationAggressiveness.name().toLowerCase());
		properties.setProperty("blockEntityRenderCacheMaxEntries", Integer.toString(blockEntityRenderCacheMaxEntries));
		properties.setProperty("blockEntityRenderCacheDebug", Boolean.toString(blockEntityRenderCacheDebug));
		properties.setProperty("denseSceneAdaptiveMode", denseSceneAdaptiveMode.name());
		properties.setProperty("visualSignificance", Boolean.toString(experimentalTemporalSignificance));
		properties.setProperty("enableOpenGlTweaks", Boolean.toString(enableOpenGlTweaks));
		properties.setProperty("openGlOptimizationMode", openGlOptimizationMode.name());
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

	public enum BlockEntityVirtualizationAggressiveness {
		CONSERVATIVE,
		BALANCED,
		AGGRESSIVE;

		private static BlockEntityVirtualizationAggressiveness parse(String value) {
			try {
				return BlockEntityVirtualizationAggressiveness.valueOf(value.toUpperCase());
			} catch (IllegalArgumentException | NullPointerException exception) {
				return CONSERVATIVE;
			}
		}
	}

	public enum OpenGlOptimizationMode {
		OFF,
		DIAGNOSTIC_ONLY,
		MEASURE_ONLY,
		SAFE_OPTIMIZE;

		private static OpenGlOptimizationMode parse(String value) {
			try {
				String normalized = value.toUpperCase();
				if ("SAFE_SKIP".equals(normalized)) {
					return SAFE_OPTIMIZE;
				}
				return OpenGlOptimizationMode.valueOf(normalized);
			} catch (IllegalArgumentException exception) {
				return OFF;
			}
		}
	}

	public enum Preset {
		HIGH_PERFORMANCE,
		MEDIUM,
		QUALITY
	}

	public record Snapshot(boolean enabled, boolean framePacing, boolean particleLimiter,
			boolean blockEntityCulling, boolean blockEntityRenderCache,
			boolean blockEntityVirtualizationEnabled,
			boolean blockEntityVirtualizationDebugProxies,
			BlockEntityVirtualizationAggressiveness blockEntityVirtualizationAggressiveness,
			DenseSceneAdaptiveMode denseSceneAdaptiveMode,
			boolean enableOpenGlTweaks, OpenGlOptimizationMode openGlOptimizationMode) {
	}
}
