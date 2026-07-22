package net.optiminium.client;

final class BenchmarkDiagnostics {
	private BenchmarkDiagnostics() {
	}

	static String bottleneckSignal(double cpuMs, double gpuMs) {
		if (gpuMs <= 0.0D || cpuMs <= 0.0D) return "Unknown (GPU timer unavailable)";
		double gpuShare = gpuMs / cpuMs;
		if (gpuShare >= 0.75D) return "GPU pressure";
		if (gpuShare <= 0.50D) return "CPU / uninstrumented pressure";
		return "Mixed CPU / GPU pressure";
	}

	static double perFrame(long total, int frames) {
		return frames > 0 ? (double)total / frames : 0.0D;
	}
}
