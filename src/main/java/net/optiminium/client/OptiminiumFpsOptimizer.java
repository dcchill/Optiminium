package net.optiminium.client;

public final class OptiminiumFpsOptimizer {
	private OptiminiumFpsOptimizer() {
	}

	public static void onFrameStart() {
		OptiminiumGlStateTracker.onFrameStart();
		OptiminiumGpuOptimizer.onFrameStart();
		OptiminiumRenderProfiler.onFrameStart();
		OptiminiumSceneInvestigator.onFrameStart();
	}
}
