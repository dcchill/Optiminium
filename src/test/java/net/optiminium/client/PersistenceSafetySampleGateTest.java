package net.optiminium.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceSafetySampleGateTest {
	@Test
	void rejectsMovementAndAcceptsTheFollowingStableFrame() {
		PersistenceSafetySampleGate gate = new PersistenceSafetySampleGate();

		assertFalse(gate.observe(0.0D, 64.0D, 0.0D, 0.0F, 0.0F));
		assertTrue(gate.observe(0.0D, 64.0D, 0.0D, 0.0F, 0.0F));
		assertFalse(gate.observe(1.0D, 64.0D, 0.0D, 0.0F, 0.0F));
		assertTrue(gate.observe(1.0D, 64.0D, 0.0D, 0.0F, 0.0F));
	}

	@Test
	void rejectsRotationIncludingAcrossTheWrapBoundary() {
		PersistenceSafetySampleGate gate = new PersistenceSafetySampleGate();

		assertFalse(gate.observe(0.0D, 64.0D, 0.0D, 359.9F, 0.0F));
		assertTrue(gate.observe(0.0D, 64.0D, 0.0D, 0.1F, 0.0F));
		assertFalse(gate.observe(0.0D, 64.0D, 0.0D, 2.0F, 0.0F));
	}

	@Test
	void resetRequiresANewBaselineFrame() {
		PersistenceSafetySampleGate gate = new PersistenceSafetySampleGate();
		gate.observe(0.0D, 64.0D, 0.0D, 0.0F, 0.0F);
		assertTrue(gate.observe(0.0D, 64.0D, 0.0D, 0.0F, 0.0F));

		gate.reset();
		assertFalse(gate.observe(0.0D, 64.0D, 0.0D, 0.0F, 0.0F));
	}
}
