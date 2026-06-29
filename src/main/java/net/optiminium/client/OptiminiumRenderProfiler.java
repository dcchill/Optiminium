package net.optiminium.client;

public final class OptiminiumRenderProfiler {
	private static final long STALL_NANOS = 2_000_000L;
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
	private static int frameTextureBinds;
	private static int frameShaderBinds;
	private static int frameBufferUploads;
	private static int frameRenderLayerSwitches;
	private static int maxTextureBindsPerFrame;
	private static int maxShaderBindsPerFrame;
	private static int maxBufferUploadsPerFrame;
	private static int maxRenderLayerSwitchesPerFrame;
	private static long maxBufferUploadNanosPerFrame;
	private static boolean frameHasTranslucentRender;
	private static boolean frameHasParticleRender;
	private static boolean frameHasTerrainRender;
	private static FrameSnapshot lastFrameSnapshot = FrameSnapshot.EMPTY;

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
			totalRenderProfilingNanos / 1_000_000.0D,
			maxTextureBindsPerFrame,
			maxShaderBindsPerFrame,
			maxBufferUploadsPerFrame,
			maxRenderLayerSwitchesPerFrame,
			maxBufferUploadNanosPerFrame / 1_000_000.0D
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
		frameTextureBinds = 0;
		frameShaderBinds = 0;
		frameBufferUploads = 0;
		frameRenderLayerSwitches = 0;
		maxTextureBindsPerFrame = 0;
		maxShaderBindsPerFrame = 0;
		maxBufferUploadsPerFrame = 0;
		maxRenderLayerSwitchesPerFrame = 0;
		maxBufferUploadNanosPerFrame = 0L;
		lastFrameSnapshot = FrameSnapshot.EMPTY;
		frameHasTranslucentRender = false;
		frameHasParticleRender = false;
		frameHasTerrainRender = false;
	}

	public static void recordTextureBind(long startNanos) {
		if (startNanos != 0L) {
			textureBindCount++;
			frameTextureBinds++;
			recordRenderProfilingSince(startNanos);
			frameOpen = true;
		}
	}

	public static void recordShaderBind(long startNanos) {
		if (startNanos != 0L) {
			shaderBindCount++;
			frameShaderBinds++;
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
			frameRenderLayerSwitches++;
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

	public static void recordBufferUpload(long startNanos) {
		if (startNanos == 0L) {
			return;
		}
		long nanos = Math.max(0L, System.nanoTime() - startNanos);
		bufferUploadCount++;
		frameBufferUploads++;
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
		lastFrameSnapshot = new FrameSnapshot(
			frameTextureBinds,
			frameShaderBinds,
			frameBufferUploads,
			frameRenderLayerSwitches,
			frameBufferUploadNanos / 1_000_000.0D,
			frameRenderProfilingNanos / 1_000_000.0D
		);
		maxTextureBindsPerFrame = Math.max(maxTextureBindsPerFrame, frameTextureBinds);
		maxShaderBindsPerFrame = Math.max(maxShaderBindsPerFrame, frameShaderBinds);
		maxBufferUploadsPerFrame = Math.max(maxBufferUploadsPerFrame, frameBufferUploads);
		maxRenderLayerSwitchesPerFrame = Math.max(maxRenderLayerSwitchesPerFrame, frameRenderLayerSwitches);
		maxBufferUploadNanosPerFrame = Math.max(maxBufferUploadNanosPerFrame, frameBufferUploadNanos);
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
		frameTextureBinds = 0;
		frameShaderBinds = 0;
		frameBufferUploads = 0;
		frameRenderLayerSwitches = 0;
		frameHasTranslucentRender = false;
		frameHasParticleRender = false;
		frameHasTerrainRender = false;
		frameOpen = false;
	}

	private static boolean isActive() {
		return enabled;
	}

	public static FrameSnapshot frameSnapshot() {
		return lastFrameSnapshot;
	}

	public record FrameSnapshot(
		int textureBinds,
		int shaderBinds,
		int bufferUploads,
		int renderLayerSwitches,
		double bufferUploadMs,
		double renderProfileMs
	) {
		private static final FrameSnapshot EMPTY = new FrameSnapshot(0, 0, 0, 0, 0.0D, 0.0D);
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
		double totalRenderProfilingMs,
		int maxTextureBindsPerFrame,
		int maxShaderBindsPerFrame,
		int maxBufferUploadsPerFrame,
		int maxRenderLayerSwitchesPerFrame,
		double maxBufferUploadMsPerFrame
	) {
	}
}
