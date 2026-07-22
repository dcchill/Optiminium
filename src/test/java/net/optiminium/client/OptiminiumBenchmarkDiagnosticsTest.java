package net.optiminium.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OptiminiumBenchmarkDiagnosticsTest {
	@Test
	void identifiesGpuPressureWhenGpuOccupiesMostOfFrame() {
		assertEquals("GPU pressure", BenchmarkDiagnostics.bottleneckSignal(12.0D, 10.0D));
	}

	@Test
	void identifiesCpuOrUninstrumentedWorkWhenGpuIsSmallShare() {
		assertEquals("CPU / uninstrumented pressure", BenchmarkDiagnostics.bottleneckSignal(12.0D, 4.0D));
	}

	@Test
	void handlesMissingTimerData() {
		assertEquals("Unknown (GPU timer unavailable)", BenchmarkDiagnostics.bottleneckSignal(12.0D, 0.0D));
		assertEquals(0.0D, BenchmarkDiagnostics.perFrame(100L, 0), 0.0D);
		assertEquals(2.5D, BenchmarkDiagnostics.perFrame(10L, 4), 0.0D);
	}
}
