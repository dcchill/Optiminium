package net.optiminium.client;

import net.optiminium.optimization.OptiminiumSettings;

public final class OptiminiumRenderProfiler {
	private static final long STALL_NANOS = 2_000_000L;
	private static final int UPLOAD_PRESSURE_HOLD_FRAMES = 8;
	private static boolean enabled;
	private static boolean frameOpen;
	private static long textureBindCount;
	private static long shaderBindCount;
	private static long framebufferBindCount;
	private static long bufferUploadCount;
	private static long bufferUploadNanos;
	private static long renderLayerSwitchCount;
	private static long translucentRenderFrames;
	private static long particleRenderFrames;
	private static long terrainRenderFrames;
	private static long suspectedGlStallFrames;
	private static long totalRenderProfilingNanos;
	private static long frameBufferUploadNanos;
	private static long frameRenderProfilingNanos;
	private static boolean frameHasTranslucentRender;
	private static boolean frameHasParticleRender;
	private static boolean frameHasTerrainRender;
	private static int uploadPressureFrames;

	private OptiminiumRenderProfiler() {
	}

	public static void setEnabled(boolean newEnabled) {
		enabled = newEnabled;
		reset();
	}

	public static void onFrameStart() {
		finishFrame();
		frameOpen = isActive();
	}

	public static Snapshot snapshot() {
		finishFrame();
		return new Snapshot(
			textureBindCount,
			shaderBindCount,
			framebufferBindCount,
			bufferUploadCount,
			bufferUploadNanos / 1_000_000.0D,
			renderLayerSwitchCount,
			translucentRenderFrames,
			particleRenderFrames,
			terrainRenderFrames,
			suspectedGlStallFrames,
			totalRenderProfilingNanos / 1_000_000.0D
		);
	}

	public static void reset() {
		frameOpen = false;
		textureBindCount = 0L;
		shaderBindCount = 0L;
		framebufferBindCount = 0L;
		bufferUploadCount = 0L;
		bufferUploadNanos = 0L;
		renderLayerSwitchCount = 0L;
		translucentRenderFrames = 0L;
		particleRenderFrames = 0L;
		terrainRenderFrames = 0L;
		suspectedGlStallFrames = 0L;
		totalRenderProfilingNanos = 0L;
		frameBufferUploadNanos = 0L;
		frameRenderProfilingNanos = 0L;
		frameHasTranslucentRender = false;
		frameHasParticleRender = false;
		frameHasTerrainRender = false;
		uploadPressureFrames = 0;
	}

	public static void recordTextureBind(long startNanos) {
		if (startNanos != 0L) {
			textureBindCount++;
			recordRenderProfilingSince(startNanos);
			frameOpen = true;
		}
	}

	public static void recordShaderBind(long startNanos) {
		if (startNanos != 0L) {
			shaderBindCount++;
			recordRenderProfilingSince(startNanos);
			frameOpen = true;
		}
	}

	public static void recordFramebufferBind(long startNanos) {
		if (startNanos != 0L) {
			framebufferBindCount++;
			recordRenderProfilingSince(startNanos);
			frameOpen = true;
		}
	}

	public static void recordRenderLayerSwitch() {
		if (isActive()) {
			renderLayerSwitchCount++;
			frameOpen = true;
		}
	}

	public static void recordTranslucentRender() {
		if (isActive()) {
			frameHasTranslucentRender = true;
			frameOpen = true;
		}
	}

	public static void recordParticleRender() {
		if (isActive()) {
			frameHasParticleRender = true;
			frameOpen = true;
		}
	}

	public static void recordTerrainRender() {
		if (isActive()) {
			frameHasTerrainRender = true;
			frameOpen = true;
		}
	}

	public static long start() {
		return isActive() ? System.nanoTime() : 0L;
	}

	public static boolean hasRecentUploadPressure() {
		return OptiminiumSettings.isExperimentalUploadStallLimiter() && uploadPressureFrames > 0;
	}

	public static void recordBufferUpload(long startNanos) {
		if (startNanos == 0L) {
			return;
		}
		long nanos = Math.max(0L, System.nanoTime() - startNanos);
		bufferUploadCount++;
		bufferUploadNanos += nanos;
		recordRenderProfilingDuration(nanos);
		frameBufferUploadNanos += nanos;
		frameOpen = true;
	}

	private static void recordRenderProfilingSince(long startNanos) {
		recordRenderProfilingDuration(Math.max(0L, System.nanoTime() - startNanos));
	}

	private static void recordRenderProfilingDuration(long nanos) {
		totalRenderProfilingNanos += nanos;
		frameRenderProfilingNanos += nanos;
	}

	private static void finishFrame() {
		if (!isActive() || !frameOpen) {
			return;
		}
		if (frameBufferUploadNanos >= STALL_NANOS || frameRenderProfilingNanos >= STALL_NANOS) {
			suspectedGlStallFrames++;
		}
		if (frameBufferUploadNanos >= STALL_NANOS) {
			uploadPressureFrames = UPLOAD_PRESSURE_HOLD_FRAMES;
		} else if (uploadPressureFrames > 0) {
			uploadPressureFrames--;
		}
		if (frameHasTranslucentRender) {
			translucentRenderFrames++;
		}
		if (frameHasParticleRender) {
			particleRenderFrames++;
		}
		if (frameHasTerrainRender) {
			terrainRenderFrames++;
		}
		frameBufferUploadNanos = 0L;
		frameRenderProfilingNanos = 0L;
		frameHasTranslucentRender = false;
		frameHasParticleRender = false;
		frameHasTerrainRender = false;
		frameOpen = false;
	}

	private static boolean isActive() {
		return enabled || OptiminiumSettings.isExperimentalUploadStallLimiter();
	}

	public record Snapshot(
		long textureBindCount,
		long shaderBindCount,
		long framebufferBindCount,
		long bufferUploadCount,
		double bufferUploadMs,
		long renderLayerSwitchCount,
		long translucentRenderFrames,
		long particleRenderFrames,
		long terrainRenderFrames,
		long suspectedGlStallFrames,
		double totalRenderProfilingMs
	) {
	}
}
