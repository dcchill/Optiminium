package net.optiminium.client;

final class OptiminiumEvaluationScheduler {
	private OptiminiumEvaluationScheduler() {
	}

	static long nextPeriodicEvaluationFrame(long lastEvaluationFrame, int interval, int salt) {
		int safeInterval = Math.max(1, interval);
		long earliest = Math.max(1L, lastEvaluationFrame + safeInterval);
		int remainder = Math.floorMod((int)earliest + salt, safeInterval);
		return remainder == 0 ? earliest : earliest + (safeInterval - remainder);
	}
}
