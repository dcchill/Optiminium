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
	private static final int HEIGHT = 350;

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
		OptiminiumPersistentBlockEntityMeshes.Snapshot meshes = OptiminiumPersistentBlockEntityMeshes.snapshot();
		OptiminiumBlockEntityVirtualizer.Snapshot virtualization = OptiminiumBlockEntityVirtualizer.snapshot();
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
		lineY += 11;
		drawLine(graphics, font, lineY, "GPU meshes: " + format(meshes.cachedMeshes())
			+ " | memory: " + formatMemory(meshes.estimatedGpuBytes()));
		lineY += 11;
		drawLine(graphics, font, lineY, "Mesh frame hit/miss/upload: " + format(meshes.hitsThisFrame()) + "/"
			+ format(meshes.missesThisFrame()) + "/" + format(meshes.uploadsThisFrame())
			+ " | instance uploads: " + format(meshes.instanceUploadsThisFrame()));
		lineY += 11;
		drawLine(graphics, font, lineY, "Cached instances/frame: " + format(meshes.instancesDrawnThisFrame())
			+ " | culled: " + format(meshes.instancesCulledThisFrame())
			+ " | fallback: " + format(meshes.fallbacksThisFrame()));
		lineY += 11;
		drawLine(graphics, font, lineY, "Vertices avoided/frame: " + format(meshes.verticesAvoidedThisFrame())
			+ " | draws: " + format(meshes.drawCallsThisFrame()));
		lineY += 14;

		graphics.drawString(font, "--- BE Virtualization ---", X + 10, lineY, 0x88CCFF, false);
		lineY += 12;
		drawLine(graphics, font, lineY, "Mode: " + OptiminiumSettings.getBlockEntityVirtualizationAggressiveness().name().toLowerCase(Locale.ROOT)
			+ " | Enabled: " + OptiminiumSettings.isBlockEntityVirtualizationEnabled()
			+ " | Debug: " + OptiminiumSettings.isBlockEntityVirtualizationDebugProxies());
		lineY += 11;
		drawLine(graphics, font, lineY, "Frame: total=" + format(virtualization.totalThisFrame())
			+ " would=" + format(virtualization.wouldVirtualizeThisFrame())
			+ " actual=" + format(virtualization.skippedThisFrame()));
		lineY += 11;
		drawLine(graphics, font, lineY, "Cancelled: cached=" + format(virtualization.cachedThisFrame())
			+ " simple=" + format(virtualization.simplifiedThisFrame())
			+ " impostor=" + format(virtualization.impostorThisFrame())
			+ " off=" + format(virtualization.virtualizedThisFrame()));
		lineY += 11;
		drawLine(graphics, font, lineY, "Total would=" + format(virtualization.wouldVirtualize())
			+ " | BER cancelled=" + format(virtualization.skippedBerCalls()));
		lineY += 11;
		drawLine(graphics, font, lineY, String.format(Locale.ROOT,
			"Cache=%s | failures=%s", format(virtualization.cachedRepresentations()), format(virtualization.proxyRenderFailures())));
		lineY += 11;
		drawLine(graphics, font, lineY, String.format(Locale.ROOT,
			"Est CPU %.2f ms | Avg BER %.3f ms", virtualization.estimatedCpuSavedMs(), virtualization.averageFullRendererMs()));
		lineY += 14;

		// GL State Tracker section
		graphics.drawString(font, "--- OpenGL Tweaks ---", X + 10, lineY, 0x88FF88, false);
		lineY += 12;
		drawLine(graphics, font, lineY, "Mode: " + tracker.mode() + " | Enabled: " + tracker.openGlTweaksEnabled()
			+ " | AutoOff: " + tracker.glAutoDisabled());
		lineY += 11;
		drawLine(graphics, font, lineY, "Textures: " + format(frameDiag.textureBindRequests()) + " req, "
			+ format(frameDiag.textureBindPotentialSkipped()) + " potential, "
			+ format(frameDiag.textureBindSkipped()) + " skip, "
			+ format(frameDiag.textureBindActual()) + " bind"
			+ " (" + frameDiag.textureSkippedPercent() + "%)");
		lineY += 11;
		drawLine(graphics, font, lineY, "Shaders:  " + format(frameDiag.shaderBindRequests()) + " req, "
			+ format(frameDiag.shaderBindPotentialSkipped()) + " potential, "
			+ format(frameDiag.shaderBindSkipped()) + " skip, "
			+ format(frameDiag.shaderBindActual()) + " bind"
			+ " (" + frameDiag.shaderSkippedPercent() + "%)");
		lineY += 11;
		drawLine(graphics, font, lineY, "Invalidations: " + format(frameDiag.trackerInvalidations())
			+ " | Top no-skip: " + tracker.topNoSkipReason());
		lineY += 14;

		// Cumulative totals
		drawLine(graphics, font, lineY, "Cumulative: tex=" + format(tracker.textureBindRequests())
			+ " req / " + format(tracker.textureBindPotentialSkipped()) + " pot / " + format(tracker.textureBindSkipped()) + " skip"
			+ " | shader=" + format(tracker.shaderBindRequests())
			+ " req / " + format(tracker.shaderBindPotentialSkipped()) + " pot / " + format(tracker.shaderBindSkipped()) + " skip"
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
