package net.optiminium.client;

/** Pure capability decision for atlas-page submission, kept deterministic for tests. */
final class PersistentDrawBatchPlanner {
	enum Mode { INDIRECT, INSTANCED_FALLBACK }

	private PersistentDrawBatchPlanner() {
	}

	static Mode choose(boolean sharedInstances, boolean multiDrawIndirect, int pageSlices) {
		return sharedInstances && multiDrawIndirect && pageSlices > 1
			? Mode.INDIRECT : Mode.INSTANCED_FALLBACK;
	}
}
