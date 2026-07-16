package net.optiminium.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericCandidateGateTest {
	@Test
	void adaptiveModeAvoidsStateSerializationBelowAdaptiveMinimum() {
		assertFalse(GenericCandidateGate.shouldBuildKey(15, true, 16, 128));
		assertTrue(GenericCandidateGate.shouldBuildKey(16, true, 16, 128));
	}

	@Test
	void fixedModeRetainsGuaranteedCompatibilityThreshold() {
		assertFalse(GenericCandidateGate.shouldBuildKey(127, false, 16, 128));
		assertTrue(GenericCandidateGate.shouldBuildKey(128, false, 16, 128));
	}
}
