package net.optiminium.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.regex.Pattern;

public final class OptiminiumRenderProfiler {
	private static final long STALL_NANOS = 2_000_000L;
	private static final Pattern RESOURCE_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
	private static final ThreadLocal<Deque<UploadCategory>> UPLOAD_CATEGORY_STACK = ThreadLocal.withInitial(ArrayDeque::new);
	private static boolean enabled;
	private static boolean frameOpen;
	private static long textureBindCount;
	private static long shaderBindCount;
	private static long framebufferBindCount;
	private static long bufferUploadCount;
	private static long bufferUploadNanos;
	private static long renderLayerSwitchCount;
	private static final Map<Object, Long> renderLayerCounts = new IdentityHashMap<>();
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
	private static long proxyLodUploadCount;
	private static long proxyLodUploadBytes;
	private static long terrainChunkUploadCount;
	private static long terrainChunkUploadBytes;
	private static long blockEntityProxyUploadCount;
	private static long blockEntityProxyUploadBytes;
	private static long particleEffectUploadCount;
	private static long particleEffectUploadBytes;
	private static long unknownVanillaUploadCount;
	private static long unknownVanillaUploadBytes;
	private static long totalUploadBytes;
	private static long optiminiumDrawCalls;
	private static long optiminiumRenderTypeSwitches;
	private static long optiminiumEndBatchCalls;
	private static long proxyDrawCalls;
	private static long proxyBatches;
	private static long debugDrawCalls;
	private static long debugBatches;
	private static Object lastOptiminiumRenderType;
	private static long largestUploadBytes;
	private static UploadCategory largestUploadCategory = UploadCategory.UNKNOWN_VANILLA;
	private static long frameUploadBytes;
	private static long frameLargestUploadBytes;
	private static UploadCategory frameLargestUploadCategory = UploadCategory.UNKNOWN_VANILLA;
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
			maxBufferUploadNanosPerFrame / 1_000_000.0D,
			proxyLodUploadCount,
			proxyLodUploadBytes,
			terrainChunkUploadCount,
			terrainChunkUploadBytes,
			blockEntityProxyUploadCount,
			blockEntityProxyUploadBytes,
			particleEffectUploadCount,
			particleEffectUploadBytes,
			unknownVanillaUploadCount,
			unknownVanillaUploadBytes,
			totalUploadBytes,
			largestUploadCategory.displayName(), optiminiumDrawCalls, optiminiumRenderTypeSwitches,
			optiminiumEndBatchCalls, proxyDrawCalls, proxyBatches, debugDrawCalls, debugBatches,
			topRenderLayers()
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
		renderLayerCounts.clear();
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
		proxyLodUploadCount = 0L;
		proxyLodUploadBytes = 0L;
		terrainChunkUploadCount = 0L;
		terrainChunkUploadBytes = 0L;
		blockEntityProxyUploadCount = 0L;
		blockEntityProxyUploadBytes = 0L;
		particleEffectUploadCount = 0L;
		particleEffectUploadBytes = 0L;
		unknownVanillaUploadCount = 0L;
		unknownVanillaUploadBytes = 0L;
		totalUploadBytes = 0L;
		optiminiumDrawCalls = 0L; optiminiumRenderTypeSwitches = 0L; optiminiumEndBatchCalls = 0L;
		proxyDrawCalls = 0L; proxyBatches = 0L; debugDrawCalls = 0L; debugBatches = 0L;
		lastOptiminiumRenderType = null;
		largestUploadBytes = 0L;
		largestUploadCategory = UploadCategory.UNKNOWN_VANILLA;
		frameUploadBytes = 0L;
		frameLargestUploadBytes = 0L;
		frameLargestUploadCategory = UploadCategory.UNKNOWN_VANILLA;
		UPLOAD_CATEGORY_STACK.get().clear();
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

	public static void recordRenderLayerSwitch(Object renderType) {
		if (isActive()) {
			renderLayerSwitchCount++;
			if (renderType != null) renderLayerCounts.merge(renderType, 1L, Long::sum);
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

	public static void recordOptiminiumDraw(Object renderType, boolean proxy, boolean debug) {
		if (!isActive()) return;
		optiminiumDrawCalls++;
		if (renderType != null && renderType != lastOptiminiumRenderType) {
			optiminiumRenderTypeSwitches++;
			lastOptiminiumRenderType = renderType;
		}
		if (proxy) { proxyDrawCalls++; proxyBatches++; }
		if (debug) { debugDrawCalls++; debugBatches++; }
	}

	public static void recordOptiminiumEndBatch() {
		if (isActive()) optiminiumEndBatchCalls++;
	}

	public static boolean isEnabled() {
		return enabled;
	}

	public static boolean areUploadCategoriesActive() {
		return isActive();
	}

	public static void pushUploadCategory(UploadCategory category) {
		if (!isActive()) {
			return;
		}
		UPLOAD_CATEGORY_STACK.get().push(category == null ? UploadCategory.UNKNOWN_VANILLA : category);
	}

	public static void popUploadCategory() {
		if (!isActive()) {
			return;
		}
		Deque<UploadCategory> stack = UPLOAD_CATEGORY_STACK.get();
		if (!stack.isEmpty()) {
			stack.pop();
		}
	}

	private static String topRenderLayers() {
		if (renderLayerCounts.isEmpty()) return "none";
		var entries = new ArrayList<>(renderLayerCounts.entrySet());
		entries.sort(Map.Entry.<Object, Long>comparingByValue(Comparator.reverseOrder()));
		StringBuilder result = new StringBuilder();
		for (int i = 0; i < Math.min(8, entries.size()); i++) {
			if (i > 0) result.append(' ');
			Map.Entry<Object, Long> entry = entries.get(i);
			result.append(renderLayerName(entry.getKey())).append('=').append(entry.getValue());
		}
		return result.toString();
	}

	private static String renderLayerName(Object renderType) {
		String value = String.valueOf(renderType);
		if (!value.startsWith("RenderType[")) return value;
		int start = "RenderType[".length();
		int colon = value.indexOf(':', start);
		int bracket = value.indexOf(']', start);
		int end = colon >= 0 ? colon : bracket >= 0 ? bracket : value.length();
		String name = value.substring(start, end);
		var resource = RESOURCE_ID.matcher(value.substring(end));
		return resource.find() ? name + "@" + resource.group() : name;
	}

	public static UploadCategory currentUploadCategory() {
		if (!isActive()) {
			return UploadCategory.UNKNOWN_VANILLA;
		}
		Deque<UploadCategory> stack = UPLOAD_CATEGORY_STACK.get();
		return stack.isEmpty() ? UploadCategory.UNKNOWN_VANILLA : stack.peek();
	}

	public static void recordBufferUpload(long startNanos) {
		if (startNanos == 0L) {
			return;
		}
		recordBufferUpload(startNanos, 0L, currentUploadCategory());
	}

	public static void recordBufferUpload(long startNanos, long bytes, UploadCategory category) {
		recordBufferUpload(startNanos, bytes, category, true);
	}

	public static void recordBufferUpload(long startNanos, long bytes, UploadCategory category, boolean countAsUpload) {
		if (startNanos == 0L) {
			return;
		}
		long nanos = Math.max(0L, System.nanoTime() - startNanos);
		if (countAsUpload) {
			bufferUploadCount++;
			frameBufferUploads++;
		}
		bufferUploadNanos += nanos;
		recordRenderProfilingDuration(nanos);
		frameBufferUploadNanos += nanos;
		frameOpen = true;
		if (bytes < 0L) {
			bytes = 0L;
		}
		if (bytes > 0L && countAsUpload) {
			if (category == null) {
				category = UploadCategory.UNKNOWN_VANILLA;
			}
			totalUploadBytes += bytes;
			switch (category) {
				case PROXY_LOD -> {
					proxyLodUploadCount++;
					proxyLodUploadBytes += bytes;
				}
				case TERRAIN_CHUNK -> {
					terrainChunkUploadCount++;
					terrainChunkUploadBytes += bytes;
				}
				case BLOCK_ENTITY_PROXY -> {
					blockEntityProxyUploadCount++;
					blockEntityProxyUploadBytes += bytes;
				}
				case PARTICLES_EFFECTS -> {
					particleEffectUploadCount++;
					particleEffectUploadBytes += bytes;
				}
				case UNKNOWN_VANILLA -> {
					unknownVanillaUploadCount++;
					unknownVanillaUploadBytes += bytes;
				}
			}
			frameUploadBytes += bytes;
			if (bytes > frameLargestUploadBytes) {
				frameLargestUploadBytes = bytes;
				frameLargestUploadCategory = category;
			}
			if (bytes > largestUploadBytes) {
				largestUploadBytes = bytes;
				largestUploadCategory = category;
			}
		}
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
			frameRenderProfilingNanos / 1_000_000.0D,
			frameUploadBytes,
			frameLargestUploadCategory.displayName()
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
		frameUploadBytes = 0L;
		frameLargestUploadBytes = 0L;
		frameLargestUploadCategory = UploadCategory.UNKNOWN_VANILLA;
		frameOpen = false;
	}

	private static boolean isActive() {
		return enabled;
	}

	public static FrameSnapshot frameSnapshot() {
		return lastFrameSnapshot;
	}

	public enum UploadCategory {
		PROXY_LOD("proxyLod"),
		TERRAIN_CHUNK("terrainChunk"),
		BLOCK_ENTITY_PROXY("blockEntity"),
		PARTICLES_EFFECTS("particles"),
		UNKNOWN_VANILLA("unknown");

		private final String displayName;

		UploadCategory(String displayName) {
			this.displayName = displayName;
		}

		public String displayName() {
			return displayName;
		}
	}

	public record FrameSnapshot(
		int textureBinds,
		int shaderBinds,
		int bufferUploads,
		int renderLayerSwitches,
		double bufferUploadMs,
		double renderProfileMs,
		long uploadBytes,
		String largestUploadSource
	) {
		private static final FrameSnapshot EMPTY = new FrameSnapshot(0, 0, 0, 0, 0.0D, 0.0D, 0L, "none");
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
		double maxBufferUploadMsPerFrame,
		long proxyLodUploadCount,
		long proxyLodUploadBytes,
		long terrainChunkUploadCount,
		long terrainChunkUploadBytes,
		long blockEntityProxyUploadCount,
		long blockEntityProxyUploadBytes,
		long particleEffectUploadCount,
		long particleEffectUploadBytes,
		long unknownVanillaUploadCount,
		long unknownVanillaUploadBytes,
		long totalUploadBytes,
		String largestUploadSource,
		long optiminiumDrawCalls, long optiminiumRenderTypeSwitches,
		long optiminiumEndBatchCalls, long proxyDrawCalls, long proxyBatches,
		long debugDrawCalls, long debugBatches, String topRenderLayers
	) {
	}
}
