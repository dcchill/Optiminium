package net.optiminium.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.optiminium.optimization.OptiminiumSettings;

import java.util.Locale;

@EventBusSubscriber(modid = "optiminium", value = Dist.CLIENT)
public final class OptiminiumBlockEntityCacheOverlay {
	private static final int X = 8;
	private static final int Y = 8;
	private static final int WIDTH = 320;
	private static final int HEIGHT = 280;

	private OptiminiumBlockEntityCacheOverlay() {
	}

	@SubscribeEvent
	public static void render(RenderGuiEvent.Post event) {
		if (!OptiminiumSettings.isBlockEntityRenderCacheDebug() || Minecraft.getInstance().options.hideGui) {
			return;
		}
		GuiGraphics graphics = event.getGuiGraphics();
		Font font = Minecraft.getInstance().font;
		OptiminiumBlockEntityRenderCache.Snapshot cache = OptiminiumBlockEntityRenderCache.snapshot();
		OptiminiumGlStateTracker.DiagnosticSnapshot tracker = OptiminiumGlStateTracker.snapshot();
		OptiminiumGlStateTracker.FrameDiagnostics frameDiag = OptiminiumGlStateTracker.frameDiagnostics();
		graphics.fill(X - 2, Y - 2, X + WIDTH + 2, Y + HEIGHT + 2, 0xD0000000);
		graphics.fill(X, Y, X + WIDTH, Y + HEIGHT, 0xE0202020);
		int lineY = Y + 10;
		graphics.drawString(font, "Optiminium Diagnostics", X + 10, lineY, 0xFFFFFF, false);
		lineY += 18;

		// Block Entity Cache section
		graphics.drawString(font, "--- BE Cache ---", X + 10, lineY, 0x88FF88, false);
		lineY += 12;
		drawLine(graphics, font, lineY, "Entries: " + format(cache.cachedEntries()) + " | Visible: " + format(cache.visibleBlockEntities()));
		lineY += 11;
		drawLine(graphics, font, lineY, String.format(Locale.ROOT, "Hits: %s | Misses: %s | Rate: %.2f%%", format(cache.hits()), format(cache.misses()), cache.hitRate()));
		lineY += 11;
		drawLine(graphics, font, lineY, "Rebuilds/Frame: " + format(cache.rebuildsThisFrame()) + " | Invalidations: " + format(cache.invalidations()));
		lineY += 11;
		drawLine(graphics, font, lineY, String.format(Locale.ROOT, "CPU Saved: %.2f ms/frame", cache.cpuSavedMsPerFrame()));
		lineY += 11;
		drawLine(graphics, font, lineY, "Memory: " + formatMemory(cache.memoryBytes()) + " | Fallbacks: " + format(cache.fallbacks()));
		lineY += 11;
		drawLine(graphics, font, lineY, String.format(Locale.ROOT, "Avg Lifetime: %.1f frames", cache.avgEntryLifetimeFrames()));
		lineY += 11;
		drawLine(graphics, font, lineY, String.format(Locale.ROOT, "Avg Reuses/Entry: %.1f", cache.avgReuses()));
		lineY += 11;
		drawLine(graphics, font, lineY, "Top Invalidation: " + cache.topInvalidationReason());
		lineY += 11;
		drawLine(graphics, font, lineY, "Unstable Types: " + cache.unstableTypeCount());
		lineY += 14;

		// GL State Tracker section
		graphics.drawString(font, "--- GL State Tracker ---", X + 10, lineY, 0x88FF88, false);
		lineY += 12;
		drawLine(graphics, font, lineY, "Textures: " + format(frameDiag.textureBindRequests()) + " req, "
			+ format(frameDiag.textureBindSkipped()) + " skip, "
			+ format(frameDiag.textureBindActual()) + " bind"
			+ " (" + frameDiag.textureSkippedPercent() + "%)");
		lineY += 11;
		drawLine(graphics, font, lineY, "Shaders:  " + format(frameDiag.shaderBindRequests()) + " req, "
			+ format(frameDiag.shaderBindSkipped()) + " skip, "
			+ format(frameDiag.shaderBindActual()) + " bind"
			+ " (" + frameDiag.shaderSkippedPercent() + "%)");
		lineY += 11;
		drawLine(graphics, font, lineY, "Invalidations: " + format(frameDiag.trackerInvalidations()));
		lineY += 14;

		// Cumulative totals
		drawLine(graphics, font, lineY, "Cumulative: tex=" + format(tracker.textureBindRequests())
			+ " req / " + format(tracker.textureBindSkipped()) + " skip"
			+ " | shader=" + format(tracker.shaderBindRequests())
			+ " req / " + format(tracker.shaderBindSkipped()) + " skip"
			+ " | inval=" + format(tracker.trackerInvalidations()));

		// Per-type stats (first line only, may be truncated)
		String perType = cache.perTypeStats();
		if (!"none".equals(perType) && perType.length() > 40) {
			perType = perType.substring(0, 40) + "...";
		}
		if (!"none".equals(perType)) {
			lineY += 14;
			drawLine(graphics, font, lineY, perType);
		}
	}

	private static void drawLine(GuiGraphics graphics, Font font, int y, String text) {
		graphics.drawString(font, text, X + 10, y, 0xD8D8D8, false);
	}

	private static String format(long value) {
		return String.format(Locale.US, "%,d", value);
	}

	private static String formatMemory(long bytes) {
		return format(Math.round(bytes / (1024.0D * 1024.0D))) + " MB";
	}
}
