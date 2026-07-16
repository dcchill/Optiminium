package net.optiminium.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceDiscoveryCooldownTest {
	@Test
	void measuredFallbackSleepsThenProvidesABoundedWakeWindow() {
		PersistenceDiscoveryCooldown cooldown = new PersistenceDiscoveryCooldown();
		assertTrue(cooldown.beginFrame(10L, true));
		cooldown.considerSleep(10L, false, false, true);
		assertTrue(cooldown.state(11L).startsWith("sleeping:"));
		assertFalse(cooldown.beginFrame(11L, true));
		assertFalse(cooldown.beginFrame(249L, true));
		assertTrue(cooldown.beginFrame(250L, true));
		assertTrue(cooldown.state(250L).startsWith("validation:"));
		cooldown.considerSleep(251L, false, false, true);
		assertTrue(cooldown.beginFrame(251L, true));
		cooldown.considerSleep(266L, false, false, true);
		assertFalse(cooldown.beginFrame(267L, true));
	}

	@Test
	void activationAndFeatureDisablePreventOrClearSleep() {
		PersistenceDiscoveryCooldown cooldown = new PersistenceDiscoveryCooldown();
		cooldown.considerSleep(5L, true, false, true);
		assertTrue(cooldown.beginFrame(6L, true));
		cooldown.considerSleep(6L, false, false, true);
		assertFalse(cooldown.beginFrame(7L, true));
		assertFalse(cooldown.beginFrame(8L, false));
		assertTrue(cooldown.beginFrame(9L, true));
	}
}
