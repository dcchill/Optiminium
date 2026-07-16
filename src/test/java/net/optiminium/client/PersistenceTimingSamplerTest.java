package net.optiminium.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceTimingSamplerTest {
	@Test
	void denseGroupsUseInfrequentWeightedSamples() {
		assertEquals(1, PersistenceTimingSampler.strideForCount(15));
		assertEquals(4, PersistenceTimingSampler.strideForCount(16));
		assertEquals(16, PersistenceTimingSampler.strideForCount(64));
		assertEquals(64, PersistenceTimingSampler.queueStrideForCount(128));
		assertTrue(PersistenceTimingSampler.shouldSample(32L, 16));
		assertFalse(PersistenceTimingSampler.shouldSample(33L, 16));
	}
}
