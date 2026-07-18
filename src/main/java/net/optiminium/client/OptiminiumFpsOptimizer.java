package net.optiminium.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

@EventBusSubscriber(modid = "optiminium", value = Dist.CLIENT)
public final class OptiminiumFpsOptimizer {
	private OptiminiumFpsOptimizer() {
	}

	@SubscribeEvent
	public static void onFrameStart(RenderFrameEvent.Pre event) {
		OptiminiumGlStateTracker.onFrameStart();
		OptiminiumGpuOptimizer.onFrameStart();
		OptiminiumRenderProfiler.onFrameStart();
		OptiminiumSceneInvestigator.onFrameStart();
	}
}
