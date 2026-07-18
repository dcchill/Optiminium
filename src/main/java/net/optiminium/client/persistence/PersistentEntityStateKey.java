package net.optiminium.client.persistence;

/** Allocation-friendly hashing helpers shared by audited adapters and unit tests. */
public final class PersistentEntityStateKey {
	private PersistentEntityStateKey() {}

	public static long mix(long hash, long value) {
		value ^= value >>> 33;
		value *= 0xff51afd7ed558ccdl;
		value ^= value >>> 33;
		return (hash ^ value) * 0x100000001b3L;
	}

	public static long exactFloats(float... values) {
		long hash = 0xcbf29ce484222325L;
		for (float value : values) hash = mix(hash, Float.floatToRawIntBits(value));
		return hash;
	}
}
