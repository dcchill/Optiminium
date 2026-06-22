package net.optiminium.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.optiminium.optimization.OptiminiumSettings;

@EventBusSubscriber(modid = "optiminium", value = Dist.CLIENT)
public final class OptiminiumKeyInput {
	private OptiminiumKeyInput() {
	}

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		while (OptiminiumKeyBindings.TOGGLE_OPTIMINIUM.consumeClick()) {
			boolean enabled = OptiminiumSettings.toggleEnabled();
			if (Minecraft.getInstance().player != null) {
				Minecraft.getInstance().player.displayClientMessage(Component.literal("Optiminium: " + (enabled ? "ON" : "OFF")), true);
			}
		}
	}
}
