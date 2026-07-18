package net.optiminium.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptiminiumBenchmarkGainTimelineTest {
	@Test
	void reportsGainForEachMeasurementWindow() {
		List<Long> offFrames = List.of(10_000_000L, 10_000_000L, 10_000_000L, 10_000_000L);
		List<Long> onFrames = List.of(5_000_000L, 5_000_000L, 5_000_000L, 5_000_000L);

		List<FpsGainTimeline.Slice> slices = FpsGainTimeline.calculate(offFrames, onFrames, 2);

		assertEquals(2, slices.size());
		assertEquals("0.00–50.00%", slices.getFirst().label());
		for (FpsGainTimeline.Slice slice : slices) {
			assertEquals(100.0D, slice.offFps(), 0.001D);
			assertEquals(200.0D, slice.onFps(), 0.001D);
			assertEquals(100.0D, slice.fpsGain(), 0.001D);
			assertEquals(100.0D, slice.gainPercent(), 0.001D);
		}
	}

	@Test
	void keepsRegressionsVisibleAndCapsSlicesToAvailableFrames() {
		List<FpsGainTimeline.Slice> slices = FpsGainTimeline.calculate(
			List.of(5_000_000L, 5_000_000L), List.of(10_000_000L, 10_000_000L), 10);

		assertEquals(2, slices.size());
		assertTrue(slices.getFirst().fpsGain() < 0.0D);
		assertEquals(-50.0D, slices.getFirst().gainPercent(), 0.001D);
	}

	@Test
	void returnsNoTimelineWithoutBothPhases() {
		assertTrue(FpsGainTimeline.calculate(List.of(), List.of(10_000_000L), 10).isEmpty());
	}
}
