package net.optiminium.client;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class MobAnimationThrottlePolicyTest {
	private static final UUID ENTITY = new UUID(0x1234L, 0x5678L);
	private static final Object MODEL = new Object();
	private static final MobAnimationThrottlePolicy.Configuration CONFIG =
		new MobAnimationThrottlePolicy.Configuration(true, 32, 64, 15, 8);

	@Test
	void nearEntitiesRemainFullRate() {
		assertEquals(MobAnimationThrottlePolicy.Action.FULL_RATE,
			decide(31.0D, 0L, 1L, null).action());
	}

	@Test
	void distantEntityRefreshesOnceThenReusesWithinSlot() {
		MobAnimationThrottlePolicy.Decision first = decide(48.0D, 100_000_000L, 1L, null);
		assertEquals(MobAnimationThrottlePolicy.Action.REFRESH, first.action());
		assertEquals(MobAnimationThrottlePolicy.Action.REUSE,
			decide(48.0D, 101_000_000L, 1L, first.state()).action());
	}

	@Test
	void advancesAtConfiguredRate() {
		MobAnimationThrottlePolicy.Decision first = decide(48.0D, 0L, 1L, null);
		assertEquals(MobAnimationThrottlePolicy.Action.REFRESH,
			decide(48.0D, 70_000_000L, 1L, first.state()).action());
	}

	@Test
	void fingerprintAndModelChangesInvalidateImmediately() {
		MobAnimationThrottlePolicy.Decision first = decide(80.0D, 0L, 1L, null);
		assertEquals(MobAnimationThrottlePolicy.Action.REFRESH,
			decide(80.0D, 1_000_000L, 2L, first.state()).action());
		MobAnimationThrottlePolicy.Decision stable = decide(80.0D, 1_000_000L, 1L, first.state());
		assertEquals(MobAnimationThrottlePolicy.Action.REFRESH,
			MobAnimationThrottlePolicy.decide(ENTITY, 80.0D * 80.0D, 1_000_000L,
				1L, new Object(), CONFIG, stable.state()).action());
	}

	@Test
	void hysteresisPreventsBoundaryFlapping() {
		MobAnimationThrottlePolicy.Decision medium = decide(33.0D, 0L, 1L, null);
		assertEquals(MobAnimationThrottlePolicy.Band.MEDIUM, medium.state().band());
		MobAnimationThrottlePolicy.Decision held = decide(31.0D, 1_000_000L, 1L, medium.state());
		assertEquals(MobAnimationThrottlePolicy.Band.MEDIUM, held.state().band());
	}

	@Test
	void stableUuidPhaseDistributesEntities() {
		long interval = 1_000_000_000L / 15L;
		assertNotEquals(MobAnimationThrottlePolicy.phase(ENTITY, interval),
			MobAnimationThrottlePolicy.phase(new UUID(99L, 101L), interval));
	}

	@Test
	void disabledPolicyIsAlwaysFullRate() {
		MobAnimationThrottlePolicy.Configuration disabled =
			new MobAnimationThrottlePolicy.Configuration(false, 32, 64, 15, 8);
		assertEquals(MobAnimationThrottlePolicy.Action.FULL_RATE,
			MobAnimationThrottlePolicy.decide(ENTITY, 10_000.0D, 0L, 1L, MODEL, disabled, null).action());
	}

	private static MobAnimationThrottlePolicy.Decision decide(double blocks, long nanos,
			long fingerprint, MobAnimationThrottlePolicy.State previous) {
		return MobAnimationThrottlePolicy.decide(ENTITY, blocks * blocks, nanos,
			fingerprint, MODEL, CONFIG, previous);
	}
}
