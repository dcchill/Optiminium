package net.optiminium.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericFamilyDiscoveryPolicyTest {
	@Test
	void homogeneousFamiliesSleepUntilValidationButWakeForActivation() {
		GenericFamilyDiscoveryPolicy policy = new GenericFamilyDiscoveryPolicy();
		Object key = new Object();
		policy.observe(10L, key);
		policy.observe(10L, key);
		policy.finishFrame(10L);
		assertEquals(key, policy.stableKey());
		assertFalse(policy.shouldScan(11L, false, false));
		assertTrue(policy.shouldScan(11L, true, false));
		assertTrue(policy.shouldScan(110L, false, false));
	}

	@Test
	void fragmentedFamiliesNeverSynthesizeAKey() {
		GenericFamilyDiscoveryPolicy policy = new GenericFamilyDiscoveryPolicy();
		policy.observe(4L, "a");
		policy.observe(4L, "b");
		assertFalse(policy.shouldScan(4L, false, false));
		policy.finishFrame(4L);
		assertNull(policy.stableKey());
	}
}
