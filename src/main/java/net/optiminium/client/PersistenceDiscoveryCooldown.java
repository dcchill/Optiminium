package net.optiminium.client;

/** Scene-level cooldown for persistence discovery after every measured group has fallen back. */
final class PersistenceDiscoveryCooldown {
	static final long SLEEP_FRAMES = 240L;
	static final long WAKE_FRAMES = 16L;
	private long sleepUntil;
	private long wakeUntil;

	boolean beginFrame(long frame, boolean configured) {
		if (!configured) {
			reset();
			return false;
		}
		if (sleepUntil == 0L) return true;
		if (frame < sleepUntil) return false;
		sleepUntil = 0L;
		wakeUntil = frame + WAKE_FRAMES;
		return true;
	}

	void considerSleep(long frame, boolean activeOrTrial, boolean qualifying, boolean measuredFallback) {
		if (sleepUntil != 0L || frame < wakeUntil || activeOrTrial || qualifying || !measuredFallback) return;
		sleepUntil = frame + SLEEP_FRAMES;
	}

	void reset() {
		sleepUntil = 0L;
		wakeUntil = 0L;
	}

	String state(long frame) {
		if (sleepUntil != 0L && frame < sleepUntil) return "sleeping:" + (sleepUntil - frame);
		if (frame < wakeUntil) return "validation:" + (wakeUntil - frame);
		return "awake";
	}
}
