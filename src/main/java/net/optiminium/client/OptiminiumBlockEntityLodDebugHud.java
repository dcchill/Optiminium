package net.optiminium.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.optiminium.optimization.OptiminiumSettings;

@EventBusSubscriber(modid = "optiminium", value = Dist.CLIENT)
public final class OptiminiumBlockEntityLodDebugHud {
	private static final int X = 6;
	private static final int Y = 6;
	private static final int LINE_HEIGHT = 10;

	private OptiminiumBlockEntityLodDebugHud() {
	}

	@SubscribeEvent
	public static void onRenderGui(RenderGuiEvent.Post event) {
		if (!OptiminiumSettings.isBlockEntityLodDebugEnabled()) return;
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.font == null || minecraft.options.hideGui) return;
		String[] lines = OptiminiumBlockEntityLod.debugLines();
		if (lines.length == 0) return;

		GuiGraphics graphics = event.getGuiGraphics();
		int width = 0;
		for (String line : lines) {
			width = Math.max(width, minecraft.font.width(line));
		}
		int height = lines.length * LINE_HEIGHT + 4;
		graphics.fill(X - 3, Y - 3, X + width + 4, Y + height, 0xAA000000);
		for (int i = 0; i < lines.length; i++) {
			int color = i == 0 ? 0xFFD8F0FF : 0xFFE8E8E8;
			graphics.drawString(minecraft.font, lines[i], X, Y + i * LINE_HEIGHT, color);
		}
	}
}
