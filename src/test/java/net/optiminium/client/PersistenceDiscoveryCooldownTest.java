package net.optiminium.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceDiscoveryCooldownTest {
	@Test
	void measuredFallbackSleepsThenProvidesABoundedWakeWindow() {
		PersistenceDiscoveryCooldown cooldown = new PersistenceDiscoveryCooldown();
		assertTrue(cooldown.beginFrame(10L, true));
		cooldown.considerSleep(10L, false, false, true, false);
		assertTrue(cooldown.state(11L).startsWith("sleeping:"));
		assertFalse(cooldown.beginFrame(11L, true));
		assertFalse(cooldown.beginFrame(249L, true));
		assertTrue(cooldown.beginFrame(250L, true));
		assertTrue(cooldown.state(250L).startsWith("validation:"));
		cooldown.considerSleep(251L, false, false, true, false);
		assertTrue(cooldown.beginFrame(251L, true));
		cooldown.considerSleep(266L, false, false, true, false);
		assertFalse(cooldown.beginFrame(267L, true));
	}

	@Test
	void activationAndFeatureDisablePreventOrClearSleep() {
		PersistenceDiscoveryCooldown cooldown = new PersistenceDiscoveryCooldown();
		cooldown.considerSleep(5L, true, false, true, false);
		assertTrue(cooldown.beginFrame(6L, true));
		cooldown.considerSleep(6L, false, false, true, false);
		assertFalse(cooldown.beginFrame(7L, true));
		assertFalse(cooldown.beginFrame(8L, false));
		assertTrue(cooldown.beginFrame(9L, true));
	}

	@Test
	void persistentlySparseSceneSleepsWithoutAQualifiedFamily() {
		PersistenceDiscoveryCooldown cooldown = new PersistenceDiscoveryCooldown();
		for (long frame = 1L; frame <= PersistenceDiscoveryCooldown.SPARSE_FRAMES_BEFORE_SLEEP; frame++) {
			assertTrue(cooldown.beginFrame(frame, true));
			cooldown.considerSleep(frame, false, false, false, true);
		}
		assertFalse(cooldown.beginFrame(
			PersistenceDiscoveryCooldown.SPARSE_FRAMES_BEFORE_SLEEP + 1L, true));
	}

	@Test
	void denseObservationResetsSparseSleepEvidence() {
		PersistenceDiscoveryCooldown cooldown = new PersistenceDiscoveryCooldown();
		for (long frame = 1L; frame < PersistenceDiscoveryCooldown.SPARSE_FRAMES_BEFORE_SLEEP; frame++) {
			cooldown.considerSleep(frame, false, false, false, true);
		}
		cooldown.considerSleep(20L, false, false, false, false);
		assertTrue(cooldown.beginFrame(21L, true));
	}

}
