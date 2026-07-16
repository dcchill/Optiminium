package net.optiminium.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistentGpuBudgetTest {
	@Test
	void evictsAtMeshOrSharedMemoryLimit() {
		assertFalse(PersistentGpuBudget.shouldEvict(16, 16, PersistentGpuBudget.DEFAULT_MAX_BYTES));
		assertTrue(PersistentGpuBudget.shouldEvict(17, 16, 1L));
		assertTrue(PersistentGpuBudget.shouldEvict(1, 16, PersistentGpuBudget.DEFAULT_MAX_BYTES + 1L));
	}
}
