package net.optiminium.client;

import net.minecraft.world.level.material.FogType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.optiminium.optimization.OptiminiumSettings;

@EventBusSubscriber(modid = "optiminium", value = Dist.CLIENT)
public final class OptiminiumFogController {
	private OptiminiumFogController() {
	}

	@SubscribeEvent
	public static void onRenderFog(ViewportEvent.RenderFog event) {
		if (!OptiminiumSettings.isEnabled() || event.getType() != FogType.NONE) {
			return;
		}

		float farPlane = (float)Math.max(32.0D, OptiminiumSettings.getFogDistanceBlocks());
		event.setNearPlaneDistance(Math.max(0.0F, farPlane * 0.7F));
		event.setFarPlaneDistance(farPlane);
		event.setCanceled(true);
	}
}
