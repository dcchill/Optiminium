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
	private static final String LIVE_RENDER_DISTANCE_RESERVE_KEY = "liveRenderDistanceReserveChunks";
	private static final String CACHED_CHUNK_DISTANCE_KEY = "cachedChunkDistanceChunks";
	private static final String DEBUG_CACHED_CHUNKS_KEY = "debugCachedChunks";
	private static final Path CONFIG_FILE = FMLPaths.CONFIGDIR.get().resolve("optiminium.properties");
	private static final int MIN_FOG_DISTANCE_BLOCKS = 32;
	private static final int MAX_FOG_DISTANCE_BLOCKS = 512;
	private static final int DEFAULT_FOG_DISTANCE_BLOCKS = 192;
	private static final int MIN_LIVE_RENDER_DISTANCE_RESERVE_CHUNKS = 0;
	private static final int MAX_LIVE_RENDER_DISTANCE_RESERVE_CHUNKS = 8;
	private static final int DEFAULT_LIVE_RENDER_DISTANCE_RESERVE_CHUNKS = 2;
	private static final int MIN_CACHED_CHUNK_DISTANCE_CHUNKS = 0;
	private static final int MAX_CACHED_CHUNK_DISTANCE_CHUNKS = 16;
	private static final int DEFAULT_CACHED_CHUNK_DISTANCE_CHUNKS = 4;
	private static volatile boolean enabled = true;
	private static volatile int fogDistanceBlocks = DEFAULT_FOG_DISTANCE_BLOCKS;
	private static volatile int liveRenderDistanceReserveChunks = DEFAULT_LIVE_RENDER_DISTANCE_RESERVE_CHUNKS;
	private static volatile int cachedChunkDistanceChunks = DEFAULT_CACHED_CHUNK_DISTANCE_CHUNKS;
	private static volatile boolean debugCachedChunks = false;

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

	public static boolean isDebugCachedChunks() {
		return debugCachedChunks;
	}

	public static void setDebugCachedChunks(boolean debug) {
		if (debugCachedChunks != debug) {
			debugCachedChunks = debug;
			save();
		}
	}

	public static boolean toggleDebugCachedChunks() {
		setDebugCachedChunks(!debugCachedChunks);
		return debugCachedChunks;
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

	public static int getLiveRenderDistanceReserveChunks() {
		return liveRenderDistanceReserveChunks;
	}

	public static void setLiveRenderDistanceReserveChunks(int distanceChunks) {
		int clamped = clamp(distanceChunks, MIN_LIVE_RENDER_DISTANCE_RESERVE_CHUNKS, MAX_LIVE_RENDER_DISTANCE_RESERVE_CHUNKS);
		if (liveRenderDistanceReserveChunks != clamped) {
			liveRenderDistanceReserveChunks = clamped;
			save();
		}
	}

	public static int getMinLiveRenderDistanceReserveChunks() {
		return MIN_LIVE_RENDER_DISTANCE_RESERVE_CHUNKS;
	}

	public static int getMaxLiveRenderDistanceReserveChunks() {
		return MAX_LIVE_RENDER_DISTANCE_RESERVE_CHUNKS;
	}

	public static int getCachedChunkDistanceChunks() {
		return cachedChunkDistanceChunks;
	}

	public static void setCachedChunkDistanceChunks(int distanceChunks) {
		int clamped = clamp(distanceChunks, MIN_CACHED_CHUNK_DISTANCE_CHUNKS, MAX_CACHED_CHUNK_DISTANCE_CHUNKS);
		if (cachedChunkDistanceChunks != clamped) {
			cachedChunkDistanceChunks = clamped;
			save();
		}
	}

	public static int getMinCachedChunkDistanceChunks() {
		return MIN_CACHED_CHUNK_DISTANCE_CHUNKS;
	}

	public static int getMaxCachedChunkDistanceChunks() {
		return MAX_CACHED_CHUNK_DISTANCE_CHUNKS;
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
			liveRenderDistanceReserveChunks = parseClamped(properties, LIVE_RENDER_DISTANCE_RESERVE_KEY, DEFAULT_LIVE_RENDER_DISTANCE_RESERVE_CHUNKS, MIN_LIVE_RENDER_DISTANCE_RESERVE_CHUNKS,
					MAX_LIVE_RENDER_DISTANCE_RESERVE_CHUNKS);
			cachedChunkDistanceChunks = parseClamped(properties, CACHED_CHUNK_DISTANCE_KEY, DEFAULT_CACHED_CHUNK_DISTANCE_CHUNKS, MIN_CACHED_CHUNK_DISTANCE_CHUNKS, MAX_CACHED_CHUNK_DISTANCE_CHUNKS);
			debugCachedChunks = Boolean.parseBoolean(properties.getProperty(DEBUG_CACHED_CHUNKS_KEY, "false"));
		} catch (IOException ignored) {
		}
	}

	private static void save() {
		Properties properties = new Properties();
		properties.setProperty(ENABLED_KEY, Boolean.toString(enabled));
		properties.setProperty(FOG_DISTANCE_KEY, Integer.toString(fogDistanceBlocks));
		properties.setProperty(LIVE_RENDER_DISTANCE_RESERVE_KEY, Integer.toString(liveRenderDistanceReserveChunks));
		properties.setProperty(CACHED_CHUNK_DISTANCE_KEY, Integer.toString(cachedChunkDistanceChunks));
		properties.setProperty(DEBUG_CACHED_CHUNKS_KEY, Boolean.toString(debugCachedChunks));
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
