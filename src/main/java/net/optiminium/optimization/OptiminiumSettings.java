package net.optiminium.optimization;

import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class OptiminiumSettings {
	private static final String ENABLED_KEY = "enabled";
	private static final String FOG_DISTANCE_KEY = "fogDistanceBlocks";
	private static final String CAMERA_CHUNK_LOADING_KEY = "cameraChunkLoading";
	private static final String CAMERA_ALWAYS_TRACK_RADIUS_KEY = "cameraAlwaysTrackRadiusChunks";
	private static final String CAMERA_YAW_STEP_DEGREES_KEY = "cameraYawStepDegrees";
	private static final Path CONFIG_FILE = FMLPaths.CONFIGDIR.get().resolve("optiminium.properties");
	private static final int MIN_FOG_DISTANCE_BLOCKS = 32;
	private static final int MAX_FOG_DISTANCE_BLOCKS = 512;
	private static final int DEFAULT_FOG_DISTANCE_BLOCKS = 192;
	private static final int MIN_CAMERA_ALWAYS_TRACK_RADIUS_CHUNKS = 0;
	private static final int MAX_CAMERA_ALWAYS_TRACK_RADIUS_CHUNKS = 4;
	private static final int DEFAULT_CAMERA_ALWAYS_TRACK_RADIUS_CHUNKS = 2;
	private static final int MIN_CAMERA_YAW_STEP_DEGREES = 1;
	private static final int MAX_CAMERA_YAW_STEP_DEGREES = 45;
	private static final int DEFAULT_CAMERA_YAW_STEP_DEGREES = 15;
	private static volatile boolean enabled = true;
	private static volatile int fogDistanceBlocks = DEFAULT_FOG_DISTANCE_BLOCKS;
	private static volatile boolean cameraChunkLoading = false;
	private static volatile int cameraAlwaysTrackRadiusChunks = DEFAULT_CAMERA_ALWAYS_TRACK_RADIUS_CHUNKS;
	private static volatile int cameraYawStepDegrees = DEFAULT_CAMERA_YAW_STEP_DEGREES;

	static {
		load();
	}

	private OptiminiumSettings() {
	}

	public static boolean isEnabled() {
		return enabled;
	}

	public static void setEnabled(boolean newEnabled) {
		enabled = newEnabled;
		save();
	}

	public static boolean toggleEnabled() {
		setEnabled(!enabled);
		return enabled;
	}

	public static boolean isCameraChunkLoading() {
		return cameraChunkLoading;
	}

	public static void setCameraChunkLoading(boolean enabled) {
		if (cameraChunkLoading != enabled) {
			cameraChunkLoading = enabled;
			save();
		}
	}

	public static boolean toggleCameraChunkLoading() {
		setCameraChunkLoading(!cameraChunkLoading);
		return cameraChunkLoading;
	}

	public static int getCameraAlwaysTrackRadiusChunks() {
		return cameraAlwaysTrackRadiusChunks;
	}

	public static void setCameraAlwaysTrackRadiusChunks(int radiusChunks) {
		int clamped = clamp(radiusChunks, MIN_CAMERA_ALWAYS_TRACK_RADIUS_CHUNKS, MAX_CAMERA_ALWAYS_TRACK_RADIUS_CHUNKS);
		if (cameraAlwaysTrackRadiusChunks != clamped) {
			cameraAlwaysTrackRadiusChunks = clamped;
			save();
		}
	}

	public static int getMinCameraAlwaysTrackRadiusChunks() {
		return MIN_CAMERA_ALWAYS_TRACK_RADIUS_CHUNKS;
	}

	public static int getMaxCameraAlwaysTrackRadiusChunks() {
		return MAX_CAMERA_ALWAYS_TRACK_RADIUS_CHUNKS;
	}

	public static int getCameraYawStepDegrees() {
		return cameraYawStepDegrees;
	}

	public static void setCameraYawStepDegrees(int yawStepDegrees) {
		int clamped = clamp(yawStepDegrees, MIN_CAMERA_YAW_STEP_DEGREES, MAX_CAMERA_YAW_STEP_DEGREES);
		if (cameraYawStepDegrees != clamped) {
			cameraYawStepDegrees = clamped;
			save();
		}
	}

	public static int getMinCameraYawStepDegrees() {
		return MIN_CAMERA_YAW_STEP_DEGREES;
	}

	public static int getMaxCameraYawStepDegrees() {
		return MAX_CAMERA_YAW_STEP_DEGREES;
	}

	public static int getFogDistanceBlocks() {
		return fogDistanceBlocks;
	}

	public static void setFogDistanceBlocks(int distanceBlocks) {
		int clamped = clamp(distanceBlocks, MIN_FOG_DISTANCE_BLOCKS, MAX_FOG_DISTANCE_BLOCKS);
		if (fogDistanceBlocks != clamped) {
			fogDistanceBlocks = clamped;
			save();
		}
	}

	public static int getMinFogDistanceBlocks() {
		return MIN_FOG_DISTANCE_BLOCKS;
	}

	public static int getMaxFogDistanceBlocks() {
		return MAX_FOG_DISTANCE_BLOCKS;
	}

	private static void load() {
		if (!Files.isRegularFile(CONFIG_FILE)) {
			return;
		}
		Properties properties = new Properties();
		try (InputStream input = Files.newInputStream(CONFIG_FILE)) {
			properties.load(input);
			enabled = Boolean.parseBoolean(properties.getProperty(ENABLED_KEY, "true"));
			fogDistanceBlocks = parseClamped(properties, FOG_DISTANCE_KEY, DEFAULT_FOG_DISTANCE_BLOCKS, MIN_FOG_DISTANCE_BLOCKS, MAX_FOG_DISTANCE_BLOCKS);
			cameraChunkLoading = Boolean.parseBoolean(properties.getProperty(CAMERA_CHUNK_LOADING_KEY, "false"));
			cameraAlwaysTrackRadiusChunks = parseClamped(properties, CAMERA_ALWAYS_TRACK_RADIUS_KEY, DEFAULT_CAMERA_ALWAYS_TRACK_RADIUS_CHUNKS, MIN_CAMERA_ALWAYS_TRACK_RADIUS_CHUNKS,
					MAX_CAMERA_ALWAYS_TRACK_RADIUS_CHUNKS);
			cameraYawStepDegrees = parseClamped(properties, CAMERA_YAW_STEP_DEGREES_KEY, DEFAULT_CAMERA_YAW_STEP_DEGREES, MIN_CAMERA_YAW_STEP_DEGREES, MAX_CAMERA_YAW_STEP_DEGREES);
		} catch (IOException ignored) {
		}
	}

	private static void save() {
		Properties properties = new Properties();
		properties.setProperty(ENABLED_KEY, Boolean.toString(enabled));
		properties.setProperty(FOG_DISTANCE_KEY, Integer.toString(fogDistanceBlocks));
		properties.setProperty(CAMERA_CHUNK_LOADING_KEY, Boolean.toString(cameraChunkLoading));
		properties.setProperty(CAMERA_ALWAYS_TRACK_RADIUS_KEY, Integer.toString(cameraAlwaysTrackRadiusChunks));
		properties.setProperty(CAMERA_YAW_STEP_DEGREES_KEY, Integer.toString(cameraYawStepDegrees));
		try {
			Files.createDirectories(CONFIG_FILE.getParent());
			try (OutputStream output = Files.newOutputStream(CONFIG_FILE)) {
				properties.store(output, "Optiminium settings");
			}
		} catch (IOException ignored) {
		}
	}

	private static int parseClamped(Properties properties, String key, int fallback, int min, int max) {
		try {
			return clamp(Integer.parseInt(properties.getProperty(key, Integer.toString(fallback))), min, max);
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
