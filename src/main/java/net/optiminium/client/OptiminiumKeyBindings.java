package net.optiminium.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = "optiminium", value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class OptiminiumKeyBindings {
	static final KeyMapping TOGGLE_OPTIMINIUM = new KeyMapping(
		"key.optiminium.toggle",
		InputConstants.Type.KEYSYM,
		GLFW.GLFW_KEY_F8,
		"key.categories.optiminium"
	);
	static final KeyMapping TOGGLE_BLOCK_ENTITY_CACHE_DEBUG = new KeyMapping(
		"key.optiminium.block_entity_cache_debug",
		InputConstants.Type.KEYSYM,
		GLFW.GLFW_KEY_F9,
		"key.categories.optiminium"
	);

	private OptiminiumKeyBindings() {
	}

	@SubscribeEvent
	public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
		event.register(TOGGLE_OPTIMINIUM);
		event.register(TOGGLE_BLOCK_ENTITY_CACHE_DEBUG);
	}
}
