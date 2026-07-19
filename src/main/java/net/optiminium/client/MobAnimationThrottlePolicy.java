package net.optiminium.client;

import java.util.UUID;

/** Deterministic distance and time policy for reduced-rate mob model animation. */
public final class MobAnimationThrottlePolicy {
	private static final double HYSTERESIS_BLOCKS = 2.0D;
	private static final long NANOS_PER_SECOND = 1_000_000_000L;

	private MobAnimationThrottlePolicy() {
	}

	public static Decision decide(UUID entityId, double distanceSquared, long nowNanos,
			long invalidationFingerprint, Object modelIdentity, Configuration configuration,
			State previous) {
		if (!configuration.enabled()) return new Decision(Action.FULL_RATE, null);
		Band band = band(distanceSquared, configuration, previous == null ? null : previous.band());
		if (band == Band.NEAR) return new Decision(Action.FULL_RATE, null);
		int fps = band == Band.MEDIUM ? configuration.mediumFps() : configuration.farFps();
		long interval = Math.max(1L, NANOS_PER_SECOND / Math.max(1, fps));
		long phase = phase(entityId, interval);
		long slot = Math.floorDiv(nowNanos + phase, interval);
		State next = new State(band, slot, invalidationFingerprint, modelIdentity);
		boolean refresh = previous == null || previous.band() != band || previous.slot() != slot
			|| previous.invalidationFingerprint() != invalidationFingerprint
			|| previous.modelIdentity() != modelIdentity;
		return new Decision(refresh ? Action.REFRESH : Action.REUSE, next);
	}

	static long phase(UUID entityId, long interval) {
		long value = entityId.getMostSignificantBits() ^ Long.rotateLeft(entityId.getLeastSignificantBits(), 32);
		value ^= value >>> 30;
		value *= 0xbf58476d1ce4e5b9L;
		value ^= value >>> 27;
		value *= 0x94d049bb133111ebL;
		value ^= value >>> 31;
		return Math.floorMod(value, interval);
	}

	private static Band band(double distanceSquared, Configuration configuration, Band previous) {
		double distance = Math.sqrt(Math.max(0.0D, distanceSquared));
		double near = configuration.nearDistanceBlocks();
		double far = configuration.farDistanceBlocks();
		if (previous == Band.NEAR && distance < near + HYSTERESIS_BLOCKS) return Band.NEAR;
		if (previous == Band.MEDIUM) {
			if (distance >= near - HYSTERESIS_BLOCKS && distance < far + HYSTERESIS_BLOCKS) return Band.MEDIUM;
		}
		if (previous == Band.FAR && distance >= far - HYSTERESIS_BLOCKS) return Band.FAR;
		if (distance < near) return Band.NEAR;
		return distance < far ? Band.MEDIUM : Band.FAR;
	}

	public enum Action { FULL_RATE, REFRESH, REUSE }
	public enum Band { NEAR, MEDIUM, FAR }

	public record Configuration(boolean enabled, int nearDistanceBlocks, int farDistanceBlocks,
			int mediumFps, int farFps) {
	}

	public record State(Band band, long slot, long invalidationFingerprint, Object modelIdentity) {
	}

	public record Decision(Action action, State state) {
	}
}
