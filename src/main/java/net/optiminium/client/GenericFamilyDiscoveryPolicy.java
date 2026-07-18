package net.optiminium.client;

/** Tracks whether a generic renderer family can reuse one stable source key while dormant. */
final class GenericFamilyDiscoveryPolicy {
	static final long RESCAN_INTERVAL_FRAMES = 100L;
	private long observationFrame = Long.MIN_VALUE;
	private long nextScanFrame;
	private long lastSeenFrame;
	private Object observedKey;
	private Object stableKey;
	private boolean diverse;

	void observe(long frame, Object key) {
		lastSeenFrame = frame;
		if (observationFrame != frame) {
			observationFrame = frame;
			observedKey = key;
			diverse = false;
		} else if (!observedKey.equals(key)) {
			diverse = true;
		}
	}

	void finishFrame(long frame) {
		if (observationFrame != frame) return;
		stableKey = diverse ? null : observedKey;
		nextScanFrame = frame + RESCAN_INTERVAL_FRAMES;
	}

	boolean shouldScan(long frame, boolean active, boolean qualifying) {
		lastSeenFrame = frame;
		if (observationFrame == frame && diverse) return false;
		return active || qualifying || frame >= nextScanFrame;
	}

	boolean scanned(long frame) { return observationFrame == frame; }
	Object stableKey() { return stableKey; }
	long lastSeenFrame() { return lastSeenFrame; }
}
