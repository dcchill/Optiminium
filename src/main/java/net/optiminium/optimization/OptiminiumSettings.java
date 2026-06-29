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
	private static volatile int blockEntityDistanceScalePercent = 100;
	private static volatile boolean blockEntityLodCubes = true;
	private static volatile int blockEntityLodMinDistanceBlocks = 24;
	private static volatile int blockEntityLodMaxDistanceBlocks = 128;
	private static volatile int blockEntityLodMaxCachedEntries = 4096;
	private static volatile int blockEntityLodStaleTimeoutFrames = 600;
	private static volatile int blockEntityLodUnloadMarginBlocks = 48;
	private static volatile int denseBlockEntityThreshold = 512;
	private static volatile DenseSceneAdaptiveMode denseSceneAdaptiveMode = DenseSceneAdaptiveMode.BALANCED;
	private static volatile boolean experimentalTemporalSignificance = false;

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
			blockEntityLodCubes, denseSceneAdaptiveMode);
	}

	public static void restore(Snapshot snapshot) {
		enabled = snapshot.enabled;
		framePacing = snapshot.framePacing;
		particleLimiter = snapshot.particleLimiter;
		blockEntityCulling = snapshot.blockEntityCulling;
		blockEntityLodCubes = snapshot.blockEntityLodCubes;
		denseSceneAdaptiveMode = snapshot.denseSceneAdaptiveMode;
		save();
	}

	public static void disableBenchmarkFeatures() {
		framePacing = false;
		particleLimiter = false;
		blockEntityCulling = false;
		blockEntityLodCubes = false;
		experimentalTemporalSignificance = false;
		denseSceneAdaptiveMode = DenseSceneAdaptiveMode.OFF;
		save();
	}

	public static void applyPreset(Preset preset) {
		enabled = true;
		framePacing = true;
		blockEntityCulling = true;
		blockEntityLodCubes = true;
		switch (preset) {
			case HIGH_PERFORMANCE -> {
				gpuTargetFps = 120;
				gpuMinRenderScalePercent = 45;
				entityAlwaysRenderDistanceBlocks = 40;
				particleLimiter = true;
				particleRenderDistanceBlocks = 32;
				maxParticlesPerFrame = 64;
				blockEntityDistanceScalePercent = 75;
				blockEntityLodMinDistanceBlocks = 20;
				blockEntityLodMaxDistanceBlocks = 96;
				blockEntityLodMaxCachedEntries = 4096;
				blockEntityLodStaleTimeoutFrames = 450;
				blockEntityLodUnloadMarginBlocks = 64;
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
				blockEntityLodMinDistanceBlocks = 24;
				blockEntityLodMaxDistanceBlocks = 128;
				blockEntityLodMaxCachedEntries = 4096;
				blockEntityLodStaleTimeoutFrames = 600;
				blockEntityLodUnloadMarginBlocks = 48;
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
				blockEntityLodMinDistanceBlocks = 32;
				blockEntityLodMaxDistanceBlocks = 160;
				blockEntityLodMaxCachedEntries = 2048;
				blockEntityLodStaleTimeoutFrames = 900;
				blockEntityLodUnloadMarginBlocks = 32;
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

	public static void setExperimentalTemporalSignificance(boolean value) {
		experimentalTemporalSignificance = value;
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

	public static boolean isBlockEntityLodCubesEnabled() {
		return blockEntityLodCubes;
	}

	public static boolean toggleBlockEntityLodCubes() {
		blockEntityLodCubes = !blockEntityLodCubes;
		save();
		return blockEntityLodCubes;
	}

	public static void setBlockEntityLodCubes(boolean value) {
		blockEntityLodCubes = value;
		save();
	}

	public static int getBlockEntityDistanceScalePercent() {
		return blockEntityDistanceScalePercent;
	}

	public static void setBlockEntityDistanceScalePercent(int scalePercent) {
		blockEntityDistanceScalePercent = clamp(scalePercent, 25, 200);
		save();
	}

	public static int getBlockEntityLodMinDistanceBlocks() {
		return blockEntityLodMinDistanceBlocks;
	}

	public static void setBlockEntityLodMinDistanceBlocks(int distanceBlocks) {
		blockEntityLodMinDistanceBlocks = clamp(distanceBlocks, 0, 256);
		if (blockEntityLodMinDistanceBlocks > blockEntityLodMaxDistanceBlocks) {
			blockEntityLodMaxDistanceBlocks = blockEntityLodMinDistanceBlocks;
		}
		save();
	}

	public static int getBlockEntityLodMaxDistanceBlocks() {
		return blockEntityLodMaxDistanceBlocks;
	}

	public static void setBlockEntityLodMaxDistanceBlocks(int distanceBlocks) {
		blockEntityLodMaxDistanceBlocks = clamp(distanceBlocks, 1, 512);
		if (blockEntityLodMaxDistanceBlocks < blockEntityLodMinDistanceBlocks) {
			blockEntityLodMinDistanceBlocks = blockEntityLodMaxDistanceBlocks;
		}
		save();
	}

	public static int getBlockEntityLodMaxCachedEntries() {
		return blockEntityLodMaxCachedEntries;
	}

	public static void setBlockEntityLodMaxCachedEntries(int maxEntries) {
		blockEntityLodMaxCachedEntries = clamp(maxEntries, 0, 32768);
		save();
	}

	public static int getBlockEntityLodStaleTimeoutFrames() {
		return blockEntityLodStaleTimeoutFrames;
	}

	public static void setBlockEntityLodStaleTimeoutFrames(int frames) {
		blockEntityLodStaleTimeoutFrames = clamp(frames, 1, 7200);
		save();
	}

	public static int getBlockEntityLodUnloadMarginBlocks() {
		return blockEntityLodUnloadMarginBlocks;
	}

	public static void setBlockEntityLodUnloadMarginBlocks(int blocks) {
		blockEntityLodUnloadMarginBlocks = clamp(blocks, 0, 256);
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
			blockEntityLodCubes = Boolean.parseBoolean(properties.getProperty("blockEntityLodCubes", Boolean.toString(blockEntityLodCubes)));
			blockEntityLodMinDistanceBlocks = clamp(Integer.parseInt(properties.getProperty("blockEntityLodMinDistanceBlocks", Integer.toString(blockEntityLodMinDistanceBlocks))), 0, 256);
			blockEntityLodMaxDistanceBlocks = clamp(Integer.parseInt(properties.getProperty("blockEntityLodMaxDistanceBlocks", Integer.toString(blockEntityLodMaxDistanceBlocks))), 1, 512);
			blockEntityLodMaxCachedEntries = clamp(Integer.parseInt(properties.getProperty("blockEntityLodMaxCachedEntries", Integer.toString(blockEntityLodMaxCachedEntries))), 0, 32768);
			blockEntityLodStaleTimeoutFrames = clamp(Integer.parseInt(properties.getProperty("blockEntityLodStaleTimeoutFrames", Integer.toString(blockEntityLodStaleTimeoutFrames))), 1, 7200);
			blockEntityLodUnloadMarginBlocks = clamp(Integer.parseInt(properties.getProperty("blockEntityLodUnloadMarginBlocks", Integer.toString(blockEntityLodUnloadMarginBlocks))), 0, 256);
			denseBlockEntityThreshold = clamp(Integer.parseInt(properties.getProperty("denseBlockEntityThreshold", Integer.toString(denseBlockEntityThreshold))), 64, 4096);
			denseSceneAdaptiveMode = DenseSceneAdaptiveMode.parse(properties.getProperty("denseSceneAdaptiveMode", denseSceneAdaptiveMode.name()));
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
		properties.setProperty("blockEntityLodCubes", Boolean.toString(blockEntityLodCubes));
		properties.setProperty("blockEntityLodMinDistanceBlocks", Integer.toString(blockEntityLodMinDistanceBlocks));
		properties.setProperty("blockEntityLodMaxDistanceBlocks", Integer.toString(blockEntityLodMaxDistanceBlocks));
		properties.setProperty("blockEntityLodMaxCachedEntries", Integer.toString(blockEntityLodMaxCachedEntries));
		properties.setProperty("blockEntityLodStaleTimeoutFrames", Integer.toString(blockEntityLodStaleTimeoutFrames));
		properties.setProperty("blockEntityLodUnloadMarginBlocks", Integer.toString(blockEntityLodUnloadMarginBlocks));
		properties.setProperty("denseBlockEntityThreshold", Integer.toString(denseBlockEntityThreshold));
		properties.setProperty("denseSceneAdaptiveMode", denseSceneAdaptiveMode.name());
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

	public record Snapshot(boolean enabled, boolean framePacing, boolean particleLimiter,
			boolean blockEntityCulling, boolean blockEntityLodCubes,
			DenseSceneAdaptiveMode denseSceneAdaptiveMode) {
	}
}
