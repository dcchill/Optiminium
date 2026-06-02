package net.optiminium.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.optiminium.optimization.OptiminiumSettings;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = "optiminium", value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class OptiminiumKeyBindings {
	private static final KeyMapping TOGGLE_OPTIMINIUM = new KeyMapping(
		"key.optiminium.toggle",
		InputConstants.Type.KEYSYM,
		GLFW.GLFW_KEY_F10,
		"key.categories.optiminium"
	);

	private OptiminiumKeyBindings() {
	}

	@SubscribeEvent
	public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
		event.register(TOGGLE_OPTIMINIUM);
	}

	public static void handleClientInput() {
		while (TOGGLE_OPTIMINIUM.consumeClick()) {
			boolean enabled = OptiminiumSettings.toggleEnabled();
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft.player != null) {
				minecraft.player.displayClientMessage(Component.literal("Optiminium: " + (enabled ? "ON" : "OFF")), true);
			}
		}
	}
}
