package net.optiminium.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.optiminium.optimization.OptiminiumSettings;

public final class OptiminiumKeyInput {
	private OptiminiumKeyInput() {
	}

	public static void onClientTick() {
		while (OptiminiumKeyBindings.TOGGLE_OPTIMINIUM.consumeClick()) {
			boolean enabled = OptiminiumSettings.toggleEnabled();
			if (Minecraft.getInstance().player != null) {
				Minecraft.getInstance().player.displayClientMessage(Component.literal("Optiminium: " + (enabled ? "ON" : "OFF")), true);
			}
		}
		while (OptiminiumKeyBindings.TOGGLE_BLOCK_ENTITY_CACHE_DEBUG.consumeClick()) {
			boolean enabled = OptiminiumSettings.toggleBlockEntityRenderCacheDebug();
			if (Minecraft.getInstance().player != null) {
				Minecraft.getInstance().player.displayClientMessage(Component.literal("Block Entity Cache: " + (enabled ? "shown" : "hidden")), true);
			}
		}
	}
}
