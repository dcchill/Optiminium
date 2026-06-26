package net.optiminium.compat;

/**
 * Detects Sodium/Embeddium renderer replacements at runtime via classpath checks.
 * 
 * Uses safe reflection-based detection - never imports Sodium classes directly.
 * All detection is done by checking for known class names that only exist when
 * Sodium or Embeddium is installed.
 */
public final class OptiminiumSodiumCompat {
	public enum RendererMode {
		VANILLA,
		SODIUM,
		EMBEDDIUM,
		CREATE_PATH,
		UNKNOWN
	}

	private static RendererMode cachedMode = null;
	private static Boolean cachedSodiumPresent = null;
	private static Boolean cachedEmbeddiumPresent = null;
	private static Boolean cachedCreatePresent = null;

	private OptiminiumSodiumCompat() {
	}

	/**
	 * Returns the detected renderer compatibility mode.
	 * Results are cached after first detection.
	 */
	public static RendererMode getRendererMode() {
		if (cachedMode != null) {
			return cachedMode;
		}
		cachedMode = detectMode();
		return cachedMode;
	}

	/**
	 * Returns true if Sodium (or a Sodium fork like Embeddium) is detected.
	 */
	public static boolean isSodiumPresent() {
		if (cachedSodiumPresent != null) {
			return cachedSodiumPresent;
		}
		cachedSodiumPresent = isModLoaded("sodium")
			|| checkClassExists("me.jellysquid.mods.sodium.client.SodiumMod")
			|| checkClassExists("me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer")
			|| checkClassExists("net.caffeinemc.mods.sodium.client.SodiumMod")
			|| checkClassExists("net.caffeinemc.mods.sodium.neoforge.SodiumForgeMod");
		return cachedSodiumPresent;
	}

	/**
	 * Returns true if Embeddium specifically is detected.
	 */
	public static boolean isEmbeddiumPresent() {
		if (cachedEmbeddiumPresent != null) {
			return cachedEmbeddiumPresent;
		}
		cachedEmbeddiumPresent = isModLoaded("embeddium")
			|| checkClassExists("org.embeddedt.embeddium.api.EmbeddiumApi")
			|| checkClassExists("org.embeddedt.embeddium.client.EmbeddiumClient");
		return cachedEmbeddiumPresent;
	}

	public static boolean isCreatePresent() {
		if (cachedCreatePresent != null) {
			return cachedCreatePresent;
		}
		cachedCreatePresent = isModLoaded("create") || checkClassExists("com.simibubi.create.Create");
		return cachedCreatePresent;
	}

	/**
	 * Returns a human-readable string for the diagnostic flag.
	 */
	public static String rendererModeString() {
		return switch (getRendererMode()) {
			case VANILLA -> "vanilla";
			case SODIUM -> "sodium";
			case EMBEDDIUM -> "embeddium";
			case CREATE_PATH -> "create_path";
			case UNKNOWN -> "unknown";
		};
	}

	/**
	 * Returns true if the renderer is not vanilla (i.e., Sodium or Embeddium is active).
	 */
	public static boolean isNonVanillaRenderer() {
		return switch (getRendererMode()) {
			case SODIUM, EMBEDDIUM, CREATE_PATH -> true;
			case VANILLA, UNKNOWN -> false;
		};
	}

	private static RendererMode detectMode() {
		boolean embeddium = isEmbeddiumPresent();
		boolean sodium = isSodiumPresent();
		if (isCreatePresent() && (embeddium || sodium)) {
			return RendererMode.CREATE_PATH;
		}
		if (embeddium) {
			return RendererMode.EMBEDDIUM;
		}
		if (sodium) {
			return RendererMode.SODIUM;
		}
		return RendererMode.VANILLA;
	}

	private static boolean checkClassExists(String className) {
		try {
			Class.forName(className, false, OptiminiumSodiumCompat.class.getClassLoader());
			return true;
		} catch (ClassNotFoundException | LinkageError e) {
			return false;
		}
	}

	private static boolean isModLoaded(String modId) {
		try {
			Class<?> modListClass = Class.forName("net.neoforged.fml.ModList", false, OptiminiumSodiumCompat.class.getClassLoader());
			Object modList = modListClass.getMethod("get").invoke(null);
			return Boolean.TRUE.equals(modListClass.getMethod("isLoaded", String.class).invoke(modList, modId));
		} catch (ReflectiveOperationException | LinkageError e) {
			return false;
		}
	}
}
