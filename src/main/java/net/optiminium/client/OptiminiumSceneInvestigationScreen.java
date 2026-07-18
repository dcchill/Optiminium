package net.optiminium.client;

import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

public final class OptiminiumSceneInvestigationScreen extends Screen {
	private static final int ROWS_PER_PAGE = 12;
	private final Screen returnScreen;
	private final OptiminiumRenderProfiler.Snapshot snapshot;
	private final int capturedFrames;
	private final String exportMessage;
	private int page;

	public OptiminiumSceneInvestigationScreen(Screen returnScreen,
			OptiminiumRenderProfiler.Snapshot snapshot, int capturedFrames, String exportMessage) {
		super(Component.literal("Scene Investigation"));
		this.returnScreen = returnScreen;
		this.snapshot = snapshot;
		this.capturedFrames = capturedFrames;
		this.exportMessage = exportMessage;
	}

	@Override
	protected void init() {
		int center = this.width / 2;
		this.addRenderableWidget(Button.builder(Component.literal("< Previous"), button -> {
			page = Math.max(0, page - 1);
			rebuildWidgets();
		}).bounds(center - 155, this.height - 30, 100, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Next >"), button -> {
			page = Math.min(maxPage(), page + 1);
			rebuildWidgets();
		}).bounds(center + 55, this.height - 30, 100, 20).build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
			.bounds(center - 50, this.height - 30, 100, 20).build());
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		int left = Math.max(12, this.width / 2 - 300);
		graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
		graphics.drawString(this.font,
			"CPU-v1: " + capturedFrames + " frames, ranked by measured renderer CPU (not comparable to old impact scores).",
			left, 30, 0xA0A0A0);
		graphics.drawString(this.font,
			"Uploads: " + formatBytes(snapshot.totalUploadBytes()) + "   Layer switches: "
				+ snapshot.renderLayerSwitchCount() + "   Suspected stall frames: " + snapshot.suspectedGlStallFrames(),
			left, 43, 0xA0A0A0);
		graphics.drawString(this.font, trim(exportMessage, 90), left, 55,
			exportMessage.startsWith("CSV export failed") ? 0xFF7070 : 0x80E080);
		graphics.drawString(this.font, "Priority", left, 72, 0xFFD36A);
		graphics.drawString(this.font, "Rendered item", left + 70, 72, 0xFFD36A);
		graphics.drawString(this.font, "Calls", left + 390, 72, 0xFFD36A);
		graphics.drawString(this.font, "CPU ms", left + 455, 72, 0xFFD36A);
		graphics.drawString(this.font, "Avg us", left + 525, 72, 0xFFD36A);
		List<OptiminiumRenderProfiler.SceneItem> items = snapshot.sceneItems();
		int start = page * ROWS_PER_PAGE;
		int end = Math.min(items.size(), start + ROWS_PER_PAGE);
		for (int index = start; index < end; index++) {
			OptiminiumRenderProfiler.SceneItem item = items.get(index);
			int y = 90 + (index - start) * 14;
			graphics.drawString(this.font, "#" + (index + 1), left, y, 0xFFFFFF);
			graphics.drawString(this.font, trim(item.category() + ": " + item.name(), 50), left + 70, y, 0xFFFFFF);
			graphics.drawString(this.font, Long.toString(item.renderCount()), left + 390, y, 0xD0D0D0);
			graphics.drawString(this.font, String.format(Locale.ROOT, "%.1f", item.impactScore()),
				left + 455, y, impactColor(index));
			graphics.drawString(this.font, String.format(Locale.ROOT, "%.1f", item.averageCpuMicros()),
				left + 525, y, 0xD0D0D0);
		}
		if (items.isEmpty()) graphics.drawString(this.font, "No profiled render activity was captured.", left, 90, 0xFF8080);
		graphics.drawCenteredString(this.font, "Page " + (page + 1) + " / " + (maxPage() + 1),
			this.width / 2, this.height - 43, 0xA0A0A0);
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(returnScreen);
	}

	private int maxPage() {
		return Math.max(0, (snapshot.sceneItems().size() - 1) / ROWS_PER_PAGE);
	}

	private static String trim(String value, int maxLength) {
		return value.length() <= maxLength ? value : value.substring(0, maxLength - 1) + "…";
	}

	private static String formatBytes(long bytes) {
		if (bytes < 1024L) return bytes + " B";
		if (bytes < 1024L * 1024L) return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0D);
		return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0D * 1024.0D));
	}

	private static int impactColor(int rank) {
		if (rank < 3) return 0xFF7070;
		if (rank < 8) return 0xFFD36A;
		return 0x80E080;
	}
}
