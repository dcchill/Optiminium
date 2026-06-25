package net.optiminium.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.optiminium.optimization.OptiminiumMetrics;
import net.optiminium.optimization.OptiminiumSettings;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@EventBusSubscriber(modid = "optiminium", value = Dist.CLIENT)
public final class OptiminiumBenchmark {
	private static final String FORMAT_VERSION = "scene-v3";
	private static final int PHASE_TICKS = 20 * 12;
	private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
	private static boolean running;
	private static boolean previousEnabled;
	private static boolean cameraStable;
	private static int ticks;
	private static Phase phase = Phase.OFF;
	private static long lastFrameNanos;
	private static long lastThreadCpuNanos;
	private static long lastGpuSample;
	private static String offDiagnostics = "";
	private static OptiminiumGpuOptimizer.ProfileSnapshot offProfile = emptyProfile();
	private static OptiminiumGpuOptimizer.SceneSnapshot offScene = emptyScene();
	private static OptiminiumRenderProfiler.Snapshot offRenderProfile = emptyRenderProfile();
	private static OptiminiumMetrics.Snapshot offMetricStart = emptyMetrics();
	private static OptiminiumMetrics.Snapshot onMetricStart = emptyMetrics();
	private static OptiminiumMetrics.Snapshot offMetrics = emptyMetrics();
	private static CameraSnapshot offCameraSnapshot = CameraSnapshot.EMPTY;
	private static final List<Long> offFrames = new ArrayList<>();
	private static final List<Long> onFrames = new ArrayList<>();
	private static final List<Long> offThreadCpuFrames = new ArrayList<>();
	private static final List<Long> onThreadCpuFrames = new ArrayList<>();
	private static final List<Long> offGpuFrames = new ArrayList<>();
	private static final List<Long> onGpuFrames = new ArrayList<>();

	private OptiminiumBenchmark() {
	}

	public static void start() {
		if (running) {
			return;
		}
		running = true;
		previousEnabled = OptiminiumSettings.isEnabled();
		phase = Phase.OFF;
		ticks = 0;
		lastFrameNanos = 0L;
		lastThreadCpuNanos = readThreadCpuNanos();
		lastGpuSample = OptiminiumGpuTimer.getSampleCount();
		OptiminiumGpuOptimizer.flushPendingMetrics();
		offDiagnostics = "";
		offProfile = emptyProfile();
		offScene = emptyScene();
		offRenderProfile = emptyRenderProfile();
		offMetricStart = OptiminiumMetrics.snapshot();
		onMetricStart = emptyMetrics();
		offMetrics = emptyMetrics();
		offCameraSnapshot = CameraSnapshot.capture();
		cameraStable = true;
		offFrames.clear();
		onFrames.clear();
		offThreadCpuFrames.clear();
		onThreadCpuFrames.clear();
		offGpuFrames.clear();
		onGpuFrames.clear();
		OptiminiumGpuOptimizer.resetAdaptiveStats();
		OptiminiumGpuOptimizer.setProfilingEnabled(true);
		OptiminiumRenderProfiler.setEnabled(true);
		OptiminiumSettings.setEnabled(false);
		message("Optiminium benchmark: OFF pass started.");
	}

	@SubscribeEvent
	public static void onFrame(RenderFrameEvent.Pre event) {
		if (!running) {
			return;
		}
		long now = System.nanoTime();
		long threadCpuNanos = readThreadCpuNanos();
		if (lastFrameNanos != 0L) {
			(phase == Phase.OFF ? offFrames : onFrames).add(now - lastFrameNanos);
		}
		if (lastThreadCpuNanos != 0L && threadCpuNanos != 0L) {
			(phase == Phase.OFF ? offThreadCpuFrames : onThreadCpuFrames).add(Math.max(0L, threadCpuNanos - lastThreadCpuNanos));
		}
		lastFrameNanos = now;
		lastThreadCpuNanos = threadCpuNanos;
		long gpuSample = OptiminiumGpuTimer.getSampleCount();
		if (gpuSample != lastGpuSample && OptiminiumGpuTimer.hasTiming()) {
			(phase == Phase.OFF ? offGpuFrames : onGpuFrames).add(OptiminiumGpuTimer.getLatestGpuNanos());
			lastGpuSample = gpuSample;
		}
	}

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		if (!running) {
			return;
		}
		cameraStable = cameraStable && offCameraSnapshot.matches(CameraSnapshot.capture());
		ticks++;
		if (ticks < PHASE_TICKS) {
			return;
		}
		if (phase == Phase.OFF) {
			OptiminiumGpuOptimizer.flushPendingMetrics();
			offDiagnostics = OptiminiumGpuOptimizer.diagnosticLine();
			offProfile = OptiminiumGpuOptimizer.profileSnapshot();
			offScene = OptiminiumGpuOptimizer.sceneSnapshot();
			offRenderProfile = OptiminiumRenderProfiler.snapshot();
			offMetrics = delta(offMetricStart, OptiminiumMetrics.snapshot());
			offCameraSnapshot = offCameraSnapshot.finish();
			phase = Phase.ON;
			ticks = 0;
			lastFrameNanos = 0L;
			lastThreadCpuNanos = readThreadCpuNanos();
			lastGpuSample = OptiminiumGpuTimer.getSampleCount();
			OptiminiumGpuOptimizer.resetAdaptiveStats();
			OptiminiumRenderProfiler.reset();
			onMetricStart = OptiminiumMetrics.snapshot();
			OptiminiumSettings.setEnabled(true);
			message("Optiminium benchmark: ON pass started.");
			return;
		}
		running = false;
		OptiminiumGpuOptimizer.flushPendingMetrics();
		String onDiagnostics = OptiminiumGpuOptimizer.diagnosticLine();
		OptiminiumGpuOptimizer.ProfileSnapshot onProfile = OptiminiumGpuOptimizer.profileSnapshot();
		OptiminiumGpuOptimizer.SceneSnapshot onScene = OptiminiumGpuOptimizer.sceneSnapshot();
		OptiminiumRenderProfiler.Snapshot onRenderProfile = OptiminiumRenderProfiler.snapshot();
		OptiminiumMetrics.Snapshot onMetrics = delta(onMetricStart, OptiminiumMetrics.snapshot());
		OptiminiumSettings.setEnabled(previousEnabled);
		OptiminiumGpuOptimizer.setProfilingEnabled(false);
		OptiminiumRenderProfiler.setEnabled(false);
		for (String line : report(offMetrics, onMetrics, offProfile, onProfile, offScene, onScene, offRenderProfile, onRenderProfile, onDiagnostics)) {
			message(line);
		}
	}

	private static List<String> report(OptiminiumMetrics.Snapshot offMetrics, OptiminiumMetrics.Snapshot onMetrics, OptiminiumGpuOptimizer.ProfileSnapshot offProfile,
			OptiminiumGpuOptimizer.ProfileSnapshot onProfile, OptiminiumGpuOptimizer.SceneSnapshot offScene, OptiminiumGpuOptimizer.SceneSnapshot onScene,
			OptiminiumRenderProfiler.Snapshot offRenderProfile, OptiminiumRenderProfiler.Snapshot onRenderProfile, String onDiagnostics) {
		double offFps = averageFps(offFrames);
		double onFps = averageFps(onFrames);
		double fpsGain = onFps - offFps;
		double gpuSavingsMs = averageNanosOrZero(offGpuFrames) / 1_000_000.0D - averageNanosOrZero(onGpuFrames) / 1_000_000.0D;
		double frameSavingsMs = averageNanosOrZero(offFrames) / 1_000_000.0D - averageNanosOrZero(onFrames) / 1_000_000.0D;
		long particlesPrevented = onMetrics.hiddenParticles() - offMetrics.hiddenParticles();
		long blockEntitiesPrevented = onMetrics.culledBlockEntityRenders() - offMetrics.culledBlockEntityRenders();
		long entitiesPrevented = onMetrics.culledEntityRenders() - offMetrics.culledEntityRenders();
		long totalPrevented = Math.max(0L, particlesPrevented) + Math.max(0L, blockEntitiesPrevented) + Math.max(0L, entitiesPrevented);
		List<String> lines = new ArrayList<>();
		lines.add("Optiminium benchmark " + FORMAT_VERSION + ": OFF[" + stats(offFrames, offGpuFrames) + "] ON[" + stats(onFrames, onGpuFrames) + "]");
		lines.add(String.format("Optiminium benchmark net: GPU savings=%.2f ms, CPU overhead=%.3f ms/frame, net frame gain=%.2f ms, FPS gain=%.1f", gpuSavingsMs, onProfile.totalOptiminiumCpuMs(), frameSavingsMs, fpsGain));
		lines.add("Optiminium benchmark normalized OFF[" + normalizedCpuLine(offFrames, offThreadCpuFrames, offGpuFrames, offProfile) + "] ON[" + normalizedCpuLine(onFrames, onThreadCpuFrames, onGpuFrames, onProfile) + "]");
		lines.add(String.format("Optiminium benchmark prevented: particles=%d, blockEntities=%d, entities=%d", particlesPrevented, blockEntitiesPrevented, entitiesPrevented));
		lines.add("Optiminium benchmark scene OFF[" + sceneLine(offScene) + "] ON[" + sceneLine(onScene) + "]");
		lines.add("Optiminium benchmark significance OFF[" + significanceLine(offScene.significanceBands(), offMetrics, offScene) + "] ON[" + significanceLine(onScene.significanceBands(), onMetrics, onScene) + "]");
		lines.add("Optiminium benchmark significance summary OFF[" + significanceSummary(offScene.significanceBands()) + "] ON[" + significanceSummary(onScene.significanceBands()) + "]");
		lines.add(String.format("Optiminium benchmark FPS estimate: particleCulling=%.1f, blockEntityCulling=%.1f, entityCulling=%.1f, uploadManagement=not isolated, adaptiveQuality=not isolated", estimatedFpsGain(fpsGain, particlesPrevented, totalPrevented), estimatedFpsGain(fpsGain, blockEntitiesPrevented, totalPrevented), estimatedFpsGain(fpsGain, entitiesPrevented, totalPrevented)));
		lines.add("Optiminium benchmark CPU OFF[" + profileLine(offProfile) + "]");
		lines.add("Optiminium benchmark CPU ON[" + profileLine(onProfile) + "]");
		lines.add("Optiminium benchmark render OFF[" + renderProfileLine(offRenderProfile, offFrames.size()) + "]");
		lines.add("Optiminium benchmark render ON[" + renderProfileLine(onRenderProfile, onFrames.size()) + "]");
		lines.add("Optiminium benchmark render delta: " + renderDeltaLine(offRenderProfile, onRenderProfile));
		lines.add("Optiminium benchmark low-gain profile: dominatedBy=" + lowGainDominance(fpsGain, offRenderProfile, onRenderProfile));
		lines.add("Optiminium benchmark recommendation: " + recommendation(onProfile, offRenderProfile, onRenderProfile, particlesPrevented, blockEntitiesPrevented, entitiesPrevented));
		lines.add("Optiminium benchmark diagnostics: OFF" + offDiagnostics + " | ON" + onDiagnostics + ", cameraStable=" + cameraStable + ", phaseTicks=" + PHASE_TICKS);
		return lines;
	}

	private static String stats(List<Long> frames, List<Long> gpuFrames) {
		if (frames.isEmpty()) {
			return "no data";
		}
		long total = 0L;
		long slowest = 0L;
		for (long frame : frames) {
			total += frame;
			slowest = Math.max(slowest, frame);
		}
		double averageFps = 1_000_000_000.0D / (total / (double)frames.size());
		double onePercentLow = onePercentLowFps(frames);
		double worstFrameFps = 1_000_000_000.0D / slowest;
		String text = String.format("%.1f avg FPS, %.1f 1%% low FPS, %.1f worst-frame FPS", averageFps, onePercentLow, worstFrameFps);
		if (!gpuFrames.isEmpty()) {
			text += String.format(", %.2f avg GPU ms, %.2f worst GPU ms", averageNanos(gpuFrames) / 1_000_000.0D, maxNanos(gpuFrames) / 1_000_000.0D);
		}
		return text;
	}

	private static String profileLine(OptiminiumGpuOptimizer.ProfileSnapshot profile) {
		return String.format("particleCullingMs=%.3f, blockEntityCullingMs=%.3f, entityCullingMs=%.3f, uploadManagementMs=%.3f, adaptiveQualityMs=%.3f, totalOptiminiumCpuMs=%.3f, worstParticleCullingMs=%.3f, worstBlockEntityCullingMs=%.3f, worstEntityCullingMs=%.3f, worstOptiminiumCpuMs=%.3f",
			profile.particleCullingMs(),
			profile.blockEntityCullingMs(),
			profile.entityCullingMs(),
			profile.uploadManagementMs(),
			profile.adaptiveQualityMs(),
			profile.totalOptiminiumCpuMs(),
			profile.worstParticleCullingMs(),
			profile.worstBlockEntityCullingMs(),
			profile.worstEntityCullingMs(),
			profile.worstOptiminiumCpuMs()
		);
	}

	private static String normalizedCpuLine(List<Long> frames, List<Long> threadCpuFrames, List<Long> gpuFrames, OptiminiumGpuOptimizer.ProfileSnapshot profile) {
		double avgCpuMs = averageNanosOrZero(frames) / 1_000_000.0D;
		double renderThreadCpuMs = averageNanosOrZero(threadCpuFrames) / 1_000_000.0D;
		double optiminiumCpuMs = profile.totalOptiminiumCpuMs();
		double externalCpuMs = Math.max(0.0D, renderThreadCpuMs - optiminiumCpuMs);
		double gpuWaitOrStallMs = renderThreadCpuMs <= 0.0D ? 0.0D : Math.max(0.0D, avgCpuMs - renderThreadCpuMs);
		return String.format("avgCpuMs=%.3f, renderThreadCpuMs=%.3f, optiminiumCpuMs=%.3f, estimatedExternalCpuMs=%.3f, gpuWaitOrStallMs=%.3f, avgGpuMs=%.3f, cpuTiming=%s",
			avgCpuMs,
			renderThreadCpuMs,
			optiminiumCpuMs,
			externalCpuMs,
			gpuWaitOrStallMs,
			averageNanosOrZero(gpuFrames) / 1_000_000.0D,
			threadCpuFrames.isEmpty() ? "unavailable" : "thread"
		);
	}

	private static String sceneLine(OptiminiumGpuOptimizer.SceneSnapshot scene) {
		OptiminiumVisualSignificance.Snapshot bands = scene.significanceBands();
		return "rawVisibleBlockEntities=" + scene.rawVisibleBlockEntities()
			+ ", maxRawVisibleBlockEntities=" + scene.maxRawVisibleBlockEntities()
			+ ", renderedBlockEntitiesAfterCulling=" + scene.renderedBlockEntitiesAfterCulling()
			+ ", maxRenderedBlockEntitiesAfterCulling=" + scene.maxRenderedBlockEntitiesAfterCulling()
			+ ", renderedBlockEntitiesThisRun=" + scene.renderedBlockEntitiesThisRun()
			+ ", culledBlockEntitiesThisRun=" + scene.culledBlockEntitiesThisRun()
			+ ", denseSceneFrames=" + scene.denseSceneFrames()
			+ ", significanceBands=full:" + bands.full()
			+ ",throttled:" + bands.throttled()
			+ ",reused:" + bands.reused()
			+ ",proxy:" + bands.proxy()
			+ ",culled:" + bands.culled()
			+ ",fullBecauseNearby:" + bands.fullBecauseNearby()
			+ ",fullBecauseImportant:" + bands.fullBecauseImportant()
			+ ",fullBecauseLookedAt:" + bands.fullBecauseLookedAt()
			+ ",fullBecauseRecentlyInteracted:" + bands.fullBecauseRecentlyInteracted()
			+ ",throttledBecauseDistance:" + bands.throttledBecauseDistance()
			+ ",throttledBecauseFramePressure:" + bands.throttledBecauseFramePressure()
			+ ",throttledBecauseHighCost:" + bands.throttledBecauseHighCost()
			+ ",reusedBecauseStable:" + bands.reusedBecauseStable()
			+ ",reusedBecauseCameraStable:" + bands.reusedBecauseCameraStable()
			+ ",proxyBecauseFarRepeated:" + bands.proxyBecauseFarRepeated()
			+ ",proxyBecauseLowScreenSize:" + bands.proxyBecauseLowScreenSize()
			+ ",culledBecauseOffscreen:" + bands.culledBecauseOffscreen()
			+ ",culledBecauseBudget:" + bands.culledBecauseBudget()
			+ ",culledBecauseTiny:" + bands.culledBecauseTiny()
			+ ",culledBecauseLowSignificance:" + bands.culledBecauseLowSignificance()
			+ ",culledBecauseHighCostLowImportance:" + bands.culledBecauseHighCostLowImportance()
			+ ",blockEntityCullPreventedByVisibility:" + bands.blockEntityCullPreventedByVisibility()
			+ ",blockEntityCullPreventedByRecentlyVisible:" + bands.blockEntityCullPreventedByRecentlyVisible()
			+ ",blockEntityCullPreventedByLookedAt:" + bands.blockEntityCullPreventedByLookedAt()
			+ ",blockEntityDowngradedToReusedInsteadOfCulled:" + bands.blockEntityDowngradedToReusedInsteadOfCulled()
			+ ",blockEntityVisibleCullEvents:" + bands.blockEntityVisibleCullEvents()
			+ ",nearestSignificanceDistance:" + String.format("%.1f", bands.nearestDistance());
	}

	private static String significanceLine(OptiminiumVisualSignificance.Snapshot bands, OptiminiumMetrics.Snapshot metrics, OptiminiumGpuOptimizer.SceneSnapshot scene) {
		return String.format("full=%d, throttled=%d, reused=%d, proxy=%d, culled=%d, significanceCpuMs=%.4f, worstSignificanceCpuMs=%.4f, estimatedSavedBlockEntityRenders=%d, estimatedSavedParticleRenders=%d, estimatedSavedEntityRenders=%d, blockEntityCullPreventedByVisibility=%d, blockEntityCullPreventedByRecentlyVisible=%d, blockEntityCullPreventedByLookedAt=%d, blockEntityDowngradedToReusedInsteadOfCulled=%d, blockEntityVisibleCullEvents=%d, moddedBlockEntities=%d, moddedLivingEntities=%d, moddedNonLivingEntities=%d, moddedDynamicEntityCulls=%d, firstDynamicMod=%s, lastDynamicMod=%s, mostCommonSignificanceReason=%s",
			bands.full(),
			bands.throttled(),
			bands.reused(),
			bands.proxy(),
			bands.culled(),
			bands.significanceCpuMs(),
			bands.worstSignificanceCpuMs(),
			scene.culledBlockEntitiesThisRun(),
			metrics.hiddenParticles(),
			metrics.culledEntityRenders(),
			bands.blockEntityCullPreventedByVisibility(),
			bands.blockEntityCullPreventedByRecentlyVisible(),
			bands.blockEntityCullPreventedByLookedAt(),
			bands.blockEntityDowngradedToReusedInsteadOfCulled(),
			bands.blockEntityVisibleCullEvents(),
			bands.dynamicModdedBlockEntityCosted(),
			bands.dynamicModdedLivingEntityCosted(),
			bands.dynamicModdedNonLivingEntityCosted(),
			bands.dynamicModdedEntityCulled(),
			bands.firstDynamicModNamespace(),
			bands.lastDynamicModNamespace(),
			bands.mostCommonReason()
		);
	}

	private static String significanceSummary(OptiminiumVisualSignificance.Snapshot bands) {
		return "fullQuality=" + bands.full() + " because nearby:" + bands.fullBecauseNearby() + "/important:" + bands.fullBecauseImportant() + "/lookedAt:" + bands.fullBecauseLookedAt() + "/recent:" + bands.fullBecauseRecentlyInteracted()
			+ ", throttled=" + bands.throttled() + " because distance:" + bands.throttledBecauseDistance() + "/framePressure:" + bands.throttledBecauseFramePressure() + "/highCost:" + bands.throttledBecauseHighCost()
			+ ", reused=" + bands.reused() + " because stable:" + bands.reusedBecauseStable() + "/cameraStable:" + bands.reusedBecauseCameraStable()
			+ ", proxied=" + bands.proxy() + " because repeatedFar:" + bands.proxyBecauseFarRepeated() + "/lowScreenSize:" + bands.proxyBecauseLowScreenSize()
			+ ", culled=" + bands.culled() + " because offscreen:" + bands.culledBecauseOffscreen() + "/budget:" + bands.culledBecauseBudget() + "/tiny:" + bands.culledBecauseTiny() + "/lowSignificance:" + bands.culledBecauseLowSignificance() + "/highCostLowImportance:" + bands.culledBecauseHighCostLowImportance()
			+ ", blockEntityAntiPop=visibility:" + bands.blockEntityCullPreventedByVisibility() + "/recent:" + bands.blockEntityCullPreventedByRecentlyVisible() + "/lookedAt:" + bands.blockEntityCullPreventedByLookedAt() + "/reusedInsteadOfCulled:" + bands.blockEntityDowngradedToReusedInsteadOfCulled() + "/visibleCullEvents:" + bands.blockEntityVisibleCullEvents()
			+ ", nearestDistance=" + String.format("%.1f", bands.nearestDistance());
	}

	private static String renderProfileLine(OptiminiumRenderProfiler.Snapshot profile, int frames) {
		double divisor = Math.max(1, frames);
		return String.format("renderLayerSwitchCount=%d, textureBindCount=%d, shaderBindCount=%d, framebufferBindCount=%d, bufferUploadCount=%d, bufferUploadMs=%.3f, textureBindsPerFrame=%.2f, shaderBindsPerFrame=%.2f, renderLayerSwitchesPerFrame=%.2f, bufferUploadsPerFrame=%.2f, translucentRenderFrames=%d, particleRenderFrames=%d, terrainRenderFrames=%d, suspectedGlStallFrames=%d, totalRenderProfilingMs=%.3f",
			profile.renderLayerSwitchCount(),
			profile.textureBindCount(),
			profile.shaderBindCount(),
			profile.framebufferBindCount(),
			profile.bufferUploadCount(),
			profile.bufferUploadMs(),
			profile.textureBindCount() / divisor,
			profile.shaderBindCount() / divisor,
			profile.renderLayerSwitchCount() / divisor,
			profile.bufferUploadCount() / divisor,
			profile.translucentRenderFrames(),
			profile.particleRenderFrames(),
			profile.terrainRenderFrames(),
			profile.suspectedGlStallFrames(),
			profile.totalRenderProfilingMs()
		);
	}

	private static String renderDeltaLine(OptiminiumRenderProfiler.Snapshot offProfile, OptiminiumRenderProfiler.Snapshot onProfile) {
		return "GPU uploads " + deltaWord(offProfile.bufferUploadCount(), onProfile.bufferUploadCount())
			+ ", render-layer switches " + deltaWord(offProfile.renderLayerSwitchCount(), onProfile.renderLayerSwitchCount())
			+ ", texture binds " + deltaWord(offProfile.textureBindCount(), onProfile.textureBindCount())
			+ ", shader binds " + deltaWord(offProfile.shaderBindCount(), onProfile.shaderBindCount())
			+ ", suspected GL stalls " + deltaWord(offProfile.suspectedGlStallFrames(), onProfile.suspectedGlStallFrames());
	}

	private static String lowGainDominance(double fpsGain, OptiminiumRenderProfiler.Snapshot offProfile, OptiminiumRenderProfiler.Snapshot onProfile) {
		if (fpsGain > 3.0D) {
			return "not low-gain";
		}
		if (onProfile.suspectedGlStallFrames() > 0L) {
			return "GL stalls";
		}
		if (onProfile.bufferUploadMs() > offProfile.bufferUploadMs() || onProfile.bufferUploadCount() > offProfile.bufferUploadCount()) {
			return "buffer uploads";
		}
		if (onProfile.shaderBindCount() > onProfile.textureBindCount() && onProfile.shaderBindCount() > onProfile.renderLayerSwitchCount()) {
			return "shader switches";
		}
		if (onProfile.textureBindCount() > onProfile.shaderBindCount()) {
			return "texture binds";
		}
		if (onProfile.particleRenderFrames() >= onProfile.translucentRenderFrames() && onProfile.particleRenderFrames() > 0L) {
			return "particles/effects";
		}
		if (onProfile.translucentRenderFrames() > 0L) {
			return "translucent layers or overdraw/fill-rate";
		}
		if (onProfile.terrainRenderFrames() > 0L) {
			return "terrain rendering";
		}
		return "unknown render cost";
	}

	private static String deltaWord(long offValue, long onValue) {
		if (onValue < offValue) {
			return "reduced (" + offValue + " -> " + onValue + ")";
		}
		if (onValue > offValue) {
			return "increased (" + offValue + " -> " + onValue + ")";
		}
		return "unchanged (" + onValue + ")";
	}

	private static String recommendation(OptiminiumGpuOptimizer.ProfileSnapshot profile, OptiminiumRenderProfiler.Snapshot offRenderProfile,
			OptiminiumRenderProfiler.Snapshot onRenderProfile, long particlesPrevented, long blockEntitiesPrevented, long entitiesPrevented) {
		if (profile.totalOptiminiumCpuMs() > 0.75D) {
			return "reduce measurement/decision overhead before adding optimizations";
		}
		if (onRenderProfile.bufferUploadCount() > offRenderProfile.bufferUploadCount() || onRenderProfile.bufferUploadMs() > offRenderProfile.bufferUploadMs()) {
			return "next safe GL target: reduce redundant Optiminium-managed chunk upload scheduling, without caching raw GL state";
		}
		if (onRenderProfile.renderLayerSwitchCount() > offRenderProfile.renderLayerSwitchCount()) {
			return "next safe GL target: batch Optiminium debug/overlay work by existing RenderType, without changing vanilla layer order";
		}
		if (onRenderProfile.translucentRenderFrames() > 0L || onRenderProfile.particleRenderFrames() > 0L) {
			return "next safe GL target: add diagnostics for translucent and particle pass cost before changing GL state";
		}
		if (blockEntitiesPrevented >= particlesPrevented && blockEntitiesPrevented >= entitiesPrevented) {
			return "block entity rendering remains the best next target";
		}
		if (particlesPrevented >= entitiesPrevented) {
			return "particle limiting remains the best next target";
		}
		return "entity render culling remains the best next target";
	}

	private static double estimatedFpsGain(double fpsGain, long prevented, long totalPrevented) {
		if (fpsGain <= 0.0D || prevented <= 0L || totalPrevented <= 0L) {
			return 0.0D;
		}
		return fpsGain * prevented / (double)totalPrevented;
	}

	private static double averageFps(List<Long> frames) {
		double average = averageNanosOrZero(frames);
		return average <= 0.0D ? 0.0D : 1_000_000_000.0D / average;
	}

	private static double averageNanosOrZero(List<Long> frames) {
		return frames.isEmpty() ? 0.0D : averageNanos(frames);
	}

	private static OptiminiumMetrics.Snapshot delta(OptiminiumMetrics.Snapshot start, OptiminiumMetrics.Snapshot end) {
		return new OptiminiumMetrics.Snapshot(
			end.skippedEntityTicks() - start.skippedEntityTicks(),
			end.virtualizedItems() - start.virtualizedItems(),
			end.mergedXpOrbs() - start.mergedXpOrbs(),
			end.mergedXpValue() - start.mergedXpValue(),
			end.culledEntityRenders() - start.culledEntityRenders(),
			end.culledBlockEntityRenders() - start.culledBlockEntityRenders(),
			end.hiddenNameTags() - start.hiddenNameTags(),
			end.hiddenParticles() - start.hiddenParticles(),
			end.suppressedSounds() - start.suppressedSounds()
		);
	}

	private static OptiminiumMetrics.Snapshot emptyMetrics() {
		return new OptiminiumMetrics.Snapshot(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
	}

	private static OptiminiumGpuOptimizer.ProfileSnapshot emptyProfile() {
		return new OptiminiumGpuOptimizer.ProfileSnapshot(0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D);
	}

	private static OptiminiumGpuOptimizer.SceneSnapshot emptyScene() {
		return new OptiminiumGpuOptimizer.SceneSnapshot(0, 0, 0, 0, 0L, 0L, 0L, new OptiminiumVisualSignificance.Snapshot(
			0L, 0L, 0L, 0L, 0L,
			0L, 0L, 0L, 0L, 0L,
			0L, 0L, 0L, 0L,
			0L, 0L, 0L,
			0L, 0L, 0L,
			0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
			0L, 0L, 0L, 0L, 0L,
			0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
			0L, 0L, 0L, 0L, "none", "none",
			0.0D, 0.0D, "none", -1.0D, 0.0D, 0.0D, false, false, 0, 0,
			0L, 0L, 0L, 0L, 0L, 0.0D, 0.0D,
			0.0D, 0.0D, 0.0D, 0.0D, 0.0D, "none",
			0L, 0L, 0L, 0L, 0L));
	}

	private static OptiminiumRenderProfiler.Snapshot emptyRenderProfile() {
		return new OptiminiumRenderProfiler.Snapshot(0L, 0L, 0L, 0L, 0.0D, 0L, 0L, 0L, 0L, 0L, 0.0D);
	}

	private static double onePercentLowFps(List<Long> frames) {
		List<Long> sorted = new ArrayList<>(frames);
		sorted.sort(Collections.reverseOrder());
		int count = Math.max(1, (int)Math.ceil(sorted.size() * 0.01D));
		long total = 0L;
		for (int i = 0; i < count; i++) {
			total += sorted.get(i);
		}
		return 1_000_000_000.0D / (total / (double)count);
	}

	private static double averageNanos(List<Long> frames) {
		long total = 0L;
		for (long frame : frames) {
			total += frame;
		}
		return total / (double)frames.size();
	}

	private static long maxNanos(List<Long> frames) {
		long max = 0L;
		for (long frame : frames) {
			max = Math.max(max, frame);
		}
		return max;
	}

	private static long readThreadCpuNanos() {
		if (!THREADS.isCurrentThreadCpuTimeSupported()) {
			return 0L;
		}
		if (!THREADS.isThreadCpuTimeEnabled()) {
			try {
				THREADS.setThreadCpuTimeEnabled(true);
			} catch (UnsupportedOperationException | SecurityException exception) {
				return 0L;
			}
		}
		long nanos = THREADS.getCurrentThreadCpuTime();
		return nanos < 0L ? 0L : nanos;
	}

	private static void message(String text) {
		if (Minecraft.getInstance().player != null) {
			Minecraft.getInstance().player.displayClientMessage(Component.literal(text), false);
		}
	}

	private enum Phase {
		OFF,
		ON
	}

	private record CameraSnapshot(double x, double y, double z, float xRot, float yRot) {
		private static final CameraSnapshot EMPTY = new CameraSnapshot(Double.NaN, Double.NaN, Double.NaN, Float.NaN, Float.NaN);

		private static CameraSnapshot capture() {
			Player player = Minecraft.getInstance().player;
			return player == null ? EMPTY : new CameraSnapshot(player.getX(), player.getY(), player.getZ(), player.getXRot(), player.getYRot());
		}

		private CameraSnapshot finish() {
			return matches(capture()) ? this : EMPTY;
		}

		private boolean matches(CameraSnapshot other) {
			return Math.abs(x - other.x) < 0.001D
				&& Math.abs(y - other.y) < 0.001D
				&& Math.abs(z - other.z) < 0.001D
				&& Math.abs(xRot - other.xRot) < 0.001F
				&& Math.abs(yRot - other.yRot) < 0.001F;
		}
	}
}
