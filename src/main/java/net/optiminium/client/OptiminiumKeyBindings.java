package net.optiminium.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

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

	public static void registerKeyMappings() {
		KeyBindingHelper.registerKeyBinding(TOGGLE_OPTIMINIUM);
		KeyBindingHelper.registerKeyBinding(TOGGLE_BLOCK_ENTITY_CACHE_DEBUG);
	}
}
