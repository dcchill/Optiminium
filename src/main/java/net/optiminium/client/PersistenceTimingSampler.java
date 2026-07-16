package net.optiminium.client;

/** Sampling policy for hot-path timing whose weighted samples feed rolling averages. */
final class PersistenceTimingSampler {
	private PersistenceTimingSampler() {
	}

	static int strideForCount(int count) {
		if (count >= 64) return 16;
		if (count >= 16) return 4;
		return 1;
	}

	static int queueStrideForCount(int count) {
		return count >= 128 ? 64 : strideForCount(count);
	}

	static boolean shouldSample(long sequence, int stride) {
		return stride <= 1 || Math.floorMod(sequence, stride) == 0;
	}
}
