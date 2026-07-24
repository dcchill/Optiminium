package net.optiminium.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.network.chat.Component;

public final class OptiminiumVideoSettingsButton {
	private OptiminiumVideoSettingsButton() {
	}

	public static void onScreenInit(Screen screen) {
		if (!(screen instanceof OptionsScreen) && !(screen instanceof VideoSettingsScreen)) {
			return;
		}

		Screens.getButtons(screen).add(Button.builder(Component.literal("Optiminium..."), button -> Minecraft.getInstance().setScreen(new OptiminiumSettingsScreen(screen)))
			.bounds(Math.max(6, screen.width - 156), 6, 150, 20)
			.build());
	}
}
