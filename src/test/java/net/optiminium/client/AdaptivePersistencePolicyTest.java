package net.optiminium.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptivePersistencePolicyTest {
	@Test
	void staysVanillaBelowAdaptiveMinimum() {
		AdaptivePersistencePolicy policy = new AdaptivePersistencePolicy();
		assertFalse(policy.beginFrame(1L, 7, true, 8, 128));
	}

	@Test
	void newEightInstanceFloorCanEvaluate() {
		AdaptivePersistencePolicy policy = new AdaptivePersistencePolicy();
		for (long frame = 1; frame <= 3; frame++) {
			policy.recordVanilla(120_000L, 8);
			policy.beginFrame(frame, 8, true, 8, 128);
		}
		assertTrue(policy.trial());
	}

	@Test
	void gpuRegressionVetoesCpuWin() {
		AdaptivePersistencePolicy policy = activePolicy();
		for (int i = 0; i < 8; i++) policy.recordFrameSafety(2_000_000L, 10_000_000L, false);
		for (int i = 0; i < 8; i++) policy.recordFrameSafety(3_000_001L, 10_000_000L, true);
		for (int i = 0; i < AdaptivePersistencePolicy.REQUIRED_LOSSES; i++) {
			policy.beginFrame(20L + i, 16, true, 16, 128);
		}
		assertFalse(policy.active());
		assertTrue(policy.reason().contains("veto"));
	}

	@Test
	void p95FrameRegressionVetoesCpuWin() {
		AdaptivePersistencePolicy policy = activePolicy();
		for (int i = 0; i < 8; i++) policy.recordFrameSafety(2_000_000L, 10_000_000L, false);
		for (int i = 0; i < 8; i++) policy.recordFrameSafety(2_000_000L, 10_300_000L, true);
		for (int i = 0; i < AdaptivePersistencePolicy.REQUIRED_LOSSES; i++) {
			policy.beginFrame(20L + i, 16, true, 16, 128);
		}
		assertFalse(policy.active());
		assertTrue(policy.reason().contains("veto"));
	}

	@Test
	void hardSafetyVetoAbortsTrialAndUsesLongCooldown() {
		AdaptivePersistencePolicy policy = activePolicy();
		for (int i = 0; i < 8; i++) policy.recordFrameSafety(2_000_000L, 10_000_000L, false);
		for (int i = 0; i < 8; i++) policy.recordFrameSafety(3_000_001L, 10_000_000L, true);
		assertFalse(policy.beginFrame(20L, 16, true, 16, 128));
		assertFalse(policy.trial());
		assertFalse(policy.beginFrame(20L + AdaptivePersistencePolicy.SAFETY_VETO_INTERVAL_FRAMES - 1L,
			16, true, 16, 128));
	}

	@Test
	void beneficialTrialActivatesAfterHysteresis() {
		AdaptivePersistencePolicy policy = new AdaptivePersistencePolicy();
		for (int i = 0; i < 8; i++) policy.recordFrameSafety(2_000_000L, 10_000_000L, false);
		policy.recordVanilla(160_000L, 16);
		assertFalse(policy.beginFrame(1L, 16, true, 16, 128));
		policy.recordVanilla(160_000L, 16);
		assertFalse(policy.beginFrame(2L, 16, true, 16, 128));
		policy.recordVanilla(160_000L, 16);
		assertTrue(policy.beginFrame(3L, 16, true, 16, 128));
		assertTrue(policy.trial());
		for (long frame = 4L; frame <= 10L; frame++) {
			policy.recordFrameSafety(2_000_000L, 10_000_000L, true);
			policy.recordPersistent(80_000L, 16);
			assertTrue(policy.beginFrame(frame, 16, true, 16, 128));
			assertTrue(policy.trial());
		}
		policy.recordFrameSafety(2_000_000L, 10_000_000L, true);
		policy.recordPersistent(80_000L, 16);
		assertTrue(policy.beginFrame(11L, 16, true, 16, 128));
		assertTrue(policy.active());
	}

	@Test
	void moderateCpuWinWaitsForSafetyEvidence() {
		AdaptivePersistencePolicy policy = new AdaptivePersistencePolicy();
		for (long frame = 1L; frame <= 3L; frame++) {
			policy.recordVanilla(160_000L, 16);
			policy.beginFrame(frame, 16, true, 16, 128);
		}
		for (long frame = 4L; frame <= 8L; frame++) {
			policy.recordPersistent(80_000L, 16);
			assertTrue(policy.beginFrame(frame, 16, true, 16, 128));
			assertFalse(policy.active());
		}
	}

	@Test
	void cheapModerateSceneNeverPaysForATrial() {
		AdaptivePersistencePolicy policy = new AdaptivePersistencePolicy();
		for (long frame = 1L; frame <= 240L; frame++) {
			policy.recordVanilla(16_000L, 16);
			assertFalse(policy.beginFrame(frame, 16, true, 16, 128));
		}
		assertFalse(policy.trial());
	}

	@Test
	void sustainedRegressionDisablesAnActiveKey() {
		AdaptivePersistencePolicy policy = activePolicy();
		policy.recordPersistent(320_000L, 16);
		for (int i = 0; i < AdaptivePersistencePolicy.REQUIRED_LOSSES - 1; i++) {
			assertTrue(policy.beginFrame(10L + i, 16, true, 16, 128));
		}
		assertFalse(policy.beginFrame(20L, 16, true, 16, 128));
		assertFalse(policy.active());
	}

	@Test
	void guaranteedThresholdWaitsForSafetyBaselineThenActivates() {
		AdaptivePersistencePolicy policy = new AdaptivePersistencePolicy();
		for (int i = 0; i < 7; i++) policy.recordFrameSafety(2_000_000L, 10_000_000L, false);
		assertFalse(policy.beginFrame(1L, 128, true, 16, 128));
		policy.recordFrameSafety(2_000_000L, 10_000_000L, false);
		assertTrue(policy.beginFrame(1L, 128, true, 16, 128));
		assertTrue(policy.active());
	}

	@Test
	void measuredRegressionCanOverrideGuaranteedThresholdAndCooldownTrials() {
		AdaptivePersistencePolicy policy = new AdaptivePersistencePolicy();
		for (int i = 0; i < 8; i++) policy.recordFrameSafety(2_000_000L, 10_000_000L, false);
		policy.recordVanilla(128_000L, 128);
		assertTrue(policy.beginFrame(1L, 128, true, 16, 128));
		policy.recordPersistent(256_000L, 128);
		for (long frame = 2L; frame < 9L; frame++) {
			assertTrue(policy.beginFrame(frame, 128, true, 16, 128));
		}
		assertFalse(policy.beginFrame(9L, 128, true, 16, 128));
		assertFalse(policy.beginFrame(9L + AdaptivePersistencePolicy.TRIAL_INTERVAL_FRAMES - 1L,
			128, true, 16, 128));
		assertTrue(policy.beginFrame(9L + AdaptivePersistencePolicy.TRIAL_INTERVAL_FRAMES,
			128, true, 16, 128));
		assertTrue(policy.trial());
	}

	@Test
	void disablingAdaptiveUsesOnlyFixedThreshold() {
		AdaptivePersistencePolicy policy = new AdaptivePersistencePolicy();
		assertFalse(policy.beginFrame(1L, 64, false, 16, 128));
		assertTrue(policy.beginFrame(2L, 128, false, 16, 128));
	}

	@Test
	void inactivePolicyExpires() {
		AdaptivePersistencePolicy policy = new AdaptivePersistencePolicy();
		policy.beginFrame(1L, 1, true, 16, 128);
		assertFalse(policy.expired(1L + AdaptivePersistencePolicy.EXPIRE_AFTER_FRAMES));
		assertTrue(policy.expired(2L + AdaptivePersistencePolicy.EXPIRE_AFTER_FRAMES));
	}

	private static AdaptivePersistencePolicy activePolicy() {
		AdaptivePersistencePolicy policy = new AdaptivePersistencePolicy();
		for (int i = 0; i < 8; i++) policy.recordFrameSafety(2_000_000L, 10_000_000L, false);
		policy.recordVanilla(160_000L, 16);
		policy.beginFrame(1L, 16, true, 16, 128);
		policy.recordVanilla(160_000L, 16);
		policy.beginFrame(2L, 16, true, 16, 128);
		policy.recordVanilla(160_000L, 16);
		policy.beginFrame(3L, 16, true, 16, 128);
		for (long frame = 4L; frame <= 11L; frame++) {
			policy.recordFrameSafety(2_000_000L, 10_000_000L, true);
			policy.recordPersistent(80_000L, 16);
			policy.beginFrame(frame, 16, true, 16, 128);
		}
		return policy;
	}
}
