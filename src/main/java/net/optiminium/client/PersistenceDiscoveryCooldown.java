package net.optiminium.client;

/** Scene-level cooldown for persistence discovery after every measured group has fallen back. */
final class PersistenceDiscoveryCooldown {
	static final long SLEEP_FRAMES = 240L;
	static final long WAKE_FRAMES = 16L;
	static final int SPARSE_FRAMES_BEFORE_SLEEP = 16;
	private long sleepUntil;
	private long wakeUntil;
	private int consecutiveSparseFrames;

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

	void considerSleep(long frame, boolean activeOrTrial, boolean qualifying,
			boolean measuredFallback, boolean sparseScene) {
		if (sleepUntil != 0L || activeOrTrial || qualifying) {
			consecutiveSparseFrames = 0;
			return;
		}
		if (measuredFallback) {
			if (frame < wakeUntil) return;
			sleep(frame);
			return;
		}
		consecutiveSparseFrames = sparseScene ? consecutiveSparseFrames + 1 : 0;
		if (frame >= wakeUntil && consecutiveSparseFrames >= SPARSE_FRAMES_BEFORE_SLEEP) {
			sleep(frame);
		}
	}

	void reset() {
		sleepUntil = 0L;
		wakeUntil = 0L;
		consecutiveSparseFrames = 0;
	}

	String state(long frame) {
		if (sleepUntil != 0L && frame < sleepUntil) return "sleeping:" + (sleepUntil - frame);
		if (frame < wakeUntil) return "validation:" + (wakeUntil - frame);
		return "awake";
	}

	private void sleep(long frame) {
		sleepUntil = frame + SLEEP_FRAMES;
		wakeUntil = 0L;
		consecutiveSparseFrames = 0;
	}
}
