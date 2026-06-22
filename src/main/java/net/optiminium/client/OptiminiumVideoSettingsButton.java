package net.optiminium.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

@EventBusSubscriber(modid = "optiminium", value = Dist.CLIENT)
public final class OptiminiumVideoSettingsButton {
	private OptiminiumVideoSettingsButton() {
	}

	@SubscribeEvent
	public static void onScreenInit(ScreenEvent.Init.Post event) {
		Screen screen = event.getScreen();
		if (!(screen instanceof OptionsScreen) && !(screen instanceof VideoSettingsScreen)) {
			return;
		}

		event.addListener(Button.builder(Component.literal("Optiminium..."), button -> Minecraft.getInstance().setScreen(new OptiminiumSettingsScreen(screen)))
			.bounds(Math.max(6, screen.width - 156), 6, 150, 20)
			.build());
	}
}
