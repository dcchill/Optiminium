package net.optiminium.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PersistentDrawBatchPlannerTest {
	@Test
	void usesIndirectForMultipleSharedSlicesWhenSupported() {
		assertEquals(PersistentDrawBatchPlanner.Mode.INDIRECT,
			PersistentDrawBatchPlanner.choose(true, true, 4));
	}

	@Test
	void fallsBackWithoutCapabilityOrForSingleSlice() {
		assertEquals(PersistentDrawBatchPlanner.Mode.INSTANCED_FALLBACK,
			PersistentDrawBatchPlanner.choose(true, false, 4));
		assertEquals(PersistentDrawBatchPlanner.Mode.INSTANCED_FALLBACK,
			PersistentDrawBatchPlanner.choose(true, true, 1));
		assertEquals(PersistentDrawBatchPlanner.Mode.INSTANCED_FALLBACK,
			PersistentDrawBatchPlanner.choose(false, true, 4));
	}
}
