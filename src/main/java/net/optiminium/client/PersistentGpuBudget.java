package net.optiminium.client;

/** Shared mesh-page budget decision, kept deterministic for eviction tests. */
final class PersistentGpuBudget {
	static final long DEFAULT_MAX_BYTES = 64L * 1024L * 1024L;

	private PersistentGpuBudget() {
	}

	static boolean shouldEvict(int meshCount, int maxMeshes, long gpuBytes) {
		return meshCount > 0 && (meshCount > maxMeshes || gpuBytes > DEFAULT_MAX_BYTES);
	}
}
