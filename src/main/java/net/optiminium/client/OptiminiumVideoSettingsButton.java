package net.optiminium.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
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
	private static final int BUTTON_WIDTH = 150;
	private static final int BUTTON_HEIGHT = 20;
	private static final int PADDING = 6;

	private OptiminiumVideoSettingsButton() {
	}

	@SubscribeEvent
	public static void onScreenInit(ScreenEvent.Init.Post event) {
		Screen screen = event.getScreen();
		if (!(screen instanceof OptionsScreen) && !(screen instanceof VideoSettingsScreen)) {
			return;
		}

		int x = Math.max(PADDING, screen.width - BUTTON_WIDTH - PADDING);
		int y = PADDING;
		Button button = Button.builder(Component.literal("Optiminium..."), pressed -> Minecraft.getInstance().setScreen(new OptiminiumSettingsScreen(screen)))
			.bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
			.tooltip(Tooltip.create(Component.literal("Open Optiminium rendering and optimization settings.")))
			.build();
		event.addListener(button);
	}
}
