package net.optiminium.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

@EventBusSubscriber(modid = "optiminium", value = Dist.CLIENT)
public final class OptiminiumBlockEntityLodWorldRenderer {
	private OptiminiumBlockEntityLodWorldRenderer() {
	}

	@SubscribeEvent
	public static void onRenderLevelStage(RenderLevelStageEvent event) {
		if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.renderBuffers() == null) return;

		MultiBufferSource.BufferSource bufferSource = minecraft.renderBuffers().bufferSource();
		if (OptiminiumBlockEntityLod.render(event.getPoseStack(), bufferSource, event.getCamera(), event.getFrustum())) {
			bufferSource.endBatch(OptiminiumBlockEntityLod.renderType());
		}
	}
}
