package net.optiminium.client;

/**
 * Exactness policy for vanilla block-entity renderers whose transient state is not fully
 * represented by serialized block-entity data.
 */
final class VanillaBlockEntityPersistencePolicy {
	private VanillaBlockEntityPersistencePolicy() {
	}

	static SkullGeometry classifySkull(boolean knownVanillaType, boolean translucent, boolean animated) {
		if (!knownVanillaType) return SkullGeometry.UNSUPPORTED;
		if (translucent) return SkullGeometry.TRANSLUCENT;
		return animated ? SkullGeometry.ANIMATED_OPAQUE : SkullGeometry.STATIC_OPAQUE;
	}

	/**
	 * Player heads use a sorted translucent skin layer, which the resident unsorted atlas
	 * deliberately cannot reorder with surrounding translucent geometry.
	 */
	static boolean supportsWholeSkullMesh(SkullGeometry geometry) {
		return geometry == SkullGeometry.STATIC_OPAQUE || geometry == SkullGeometry.ANIMATED_OPAQUE;
	}

	/** Dragon jaws and piglin ears consume SkullBlockEntity's powered animation clock. */
	static boolean hasAnimatedSkullGeometry(SkullGeometry geometry) {
		return geometry == SkullGeometry.ANIMATED_OPAQUE;
	}

	enum SkullGeometry {
		STATIC_OPAQUE,
		ANIMATED_OPAQUE,
		TRANSLUCENT,
		UNSUPPORTED
	}
}
