package net.optiminium.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class OptiminiumVisualSignificanceSchedulerTest {
	@Test
	void periodicDeadlinePreservesMinimumIntervalAndSaltBucket() {
		long due = OptiminiumEvaluationScheduler.nextPeriodicEvaluationFrame(100L, 30, 7);
		assertEquals(0, Math.floorMod((int)due + 7, 30));
		assertEquals(true, due >= 130L);
	}

	@Test
	void periodicDeadlineUsesEarliestMatchingFrame() {
		long due = OptiminiumEvaluationScheduler.nextPeriodicEvaluationFrame(100L, 30, 7);
		assertEquals(143L, due);
	}

	@Test
	void periodicDeadlineHandlesFrameIndexRolloverWithoutGoingBackward() {
		long last = (long)Integer.MAX_VALUE + 100L;
		long due = OptiminiumEvaluationScheduler.nextPeriodicEvaluationFrame(last, 60, 11);
		assertEquals(true, due >= last + 60L);
	}
}
