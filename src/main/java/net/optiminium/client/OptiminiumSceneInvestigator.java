package net.optiminium.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.optiminium.OptiminiumMod;

public final class OptiminiumSceneInvestigator {
	private static final int CAPTURE_FRAMES = 120;
	private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
	private static int framesRemaining;
	private static boolean profilerWasEnabled;
	private static Screen returnScreen;

	private OptiminiumSceneInvestigator() {
	}

	public static void start(Screen settingsScreen) {
		if (framesRemaining > 0) return;
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null) return;
		returnScreen = settingsScreen;
		profilerWasEnabled = OptiminiumRenderProfiler.isEnabled();
		OptiminiumRenderProfiler.setEnabled(true);
		OptiminiumRenderProfiler.setSceneTimingStride(1);
		OptiminiumRenderProfiler.setSceneTimingEnabled(true);
		framesRemaining = CAPTURE_FRAMES;
		minecraft.setScreen(null);
		if (minecraft.player != null) {
			minecraft.player.displayClientMessage(
				Component.literal("Optiminium: investigating the scene for about 2 seconds..."), false);
		}
	}

	public static void onFrameStart() {
		if (framesRemaining <= 0 || --framesRemaining > 0) return;
		OptiminiumRenderProfiler.Snapshot snapshot = OptiminiumRenderProfiler.snapshot();
		ExportResult export = exportCsv(snapshot, CAPTURE_FRAMES);
		OptiminiumRenderProfiler.setSceneTimingEnabled(false);
		if (!profilerWasEnabled) OptiminiumRenderProfiler.setEnabled(false);
		Minecraft.getInstance().setScreen(
			new OptiminiumSceneInvestigationScreen(returnScreen, snapshot, CAPTURE_FRAMES, export.message()));
		returnScreen = null;
	}

	public static boolean isCapturing() {
		return framesRemaining > 0;
	}

	private static ExportResult exportCsv(OptiminiumRenderProfiler.Snapshot snapshot, int capturedFrames) {
		Path directory = Minecraft.getInstance().gameDirectory.toPath().resolve("optiminium_reports");
		Path report = directory.resolve("optiminium-scene-investigation-"
			+ FILE_TIMESTAMP.format(LocalDateTime.now()) + ".csv");
		try {
			Files.createDirectories(directory);
			Files.writeString(report, csv(snapshot, capturedFrames), StandardCharsets.UTF_8);
			return new ExportResult("CSV: " + report.toAbsolutePath());
		} catch (IOException exception) {
			OptiminiumMod.LOGGER.warn("Failed to export scene investigation CSV to {}", report, exception);
			return new ExportResult("CSV export failed: " + exception.getMessage());
		}
	}

	private static String csv(OptiminiumRenderProfiler.Snapshot snapshot, int capturedFrames) {
		StringBuilder csv = new StringBuilder();
		double totalMeasuredCpuMs = snapshot.sceneItems().stream()
			.mapToDouble(OptiminiumRenderProfiler.SceneItem::impactScore).sum();
		csv.append("metric_version,ranking_basis,rank,category,name,render_count,measured_cpu_ms,")
			.append("cpu_ms_per_frame,cpu_share_percent,average_cpu_us,captured_frames,total_upload_bytes,")
			.append("render_layer_switches,suspected_stall_frames,texture_binds,shader_binds,buffer_uploads\n");
		for (int index = 0; index < snapshot.sceneItems().size(); index++) {
			OptiminiumRenderProfiler.SceneItem item = snapshot.sceneItems().get(index);
			double cpuSharePercent = totalMeasuredCpuMs <= 0.0D
				? 0.0D : item.impactScore() * 100.0D / totalMeasuredCpuMs;
			csv.append("cpu-v1,measured_render_cpu,")
				.append(index + 1).append(',')
				.append(csvField(item.category())).append(',')
				.append(csvField(item.name())).append(',')
				.append(item.renderCount()).append(',')
				.append(String.format(Locale.ROOT, "%.3f", item.impactScore())).append(',')
				.append(String.format(Locale.ROOT, "%.6f", item.impactScore() / capturedFrames)).append(',')
				.append(String.format(Locale.ROOT, "%.3f", cpuSharePercent)).append(',')
				.append(String.format(Locale.ROOT, "%.3f", item.averageCpuMicros())).append(',')
				.append(capturedFrames).append(',')
				.append(snapshot.totalUploadBytes()).append(',')
				.append(snapshot.renderLayerSwitchCount()).append(',')
				.append(snapshot.suspectedGlStallFrames()).append(',')
				.append(snapshot.textureBindCount()).append(',')
				.append(snapshot.shaderBindCount()).append(',')
				.append(snapshot.bufferUploadCount()).append('\n');
		}
		return csv.toString();
	}

	private static String csvField(String value) {
		return '"' + value.replace("\"", "\"\"") + '"';
	}

	private record ExportResult(String message) {
	}
}
