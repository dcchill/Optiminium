package net.optiminium.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.optiminium.OptiminiumMod;
import net.optiminium.compat.OptiminiumSodiumCompat;
import net.optiminium.optimization.OptiminiumMetrics;
import net.optiminium.optimization.OptiminiumSettings;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

@EventBusSubscriber(modid = "optiminium", value = Dist.CLIENT)
public final class OptiminiumBenchmark {
	private static final String FORMAT_VERSION = "scene-v5";
	private static final int PREFLIGHT_TICKS = 20 * 2;
	private static final int WARMUP_TICKS = 20 * 4;
	private static final int MEASURE_START_TICKS = PREFLIGHT_TICKS + WARMUP_TICKS;
	private static final int MIN_MEASURE_TICKS = 20 * 16;
	private static final int MAX_MEASURE_TICKS = 20 * 30;
	private static final double STABLE_FRAME_CV_PERCENT = 18.0D;
	private static final int MIN_STABLE_FRAME_SAMPLES = 360;
	private static final int STABLE_HOLD_TICKS = 20;
	private static final int RECENT_HISTORY_LIMIT = 5;
	private static final double HISTORY_REPEATABILITY_PERCENT_POINTS = 8.0D;
	private static final long FPS_SLICE_NANOS = 1_000_000_000L;
	private static final int MIN_FPS_SLICES = 4;
	private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
	private static boolean running;
	private static boolean previousEnabled;
	private static OptiminiumSettings.Snapshot previousSettings;
	private static boolean cameraStable;
	private static int ticks;
	private static int normalBenchmarkStarts;
	private static int offMeasuredTicks;
	private static int onMeasuredTicks;
	private static int stableTicksThisPhase;
	private static Phase phase = Phase.OFF;
	private static Phase[] phaseSequence = new Phase[]{Phase.OFF, Phase.ON};
	private static int phaseSequenceIndex;
	private static long lastFrameNanos;
	private static long lastThreadCpuNanos;
	private static long lastGpuSample;
	private static String offDiagnostics = "";
	private static String onDiagnostics = "";
	private static OptiminiumGpuOptimizer.ProfileSnapshot offProfile = emptyProfile();
	private static OptiminiumGpuOptimizer.ProfileSnapshot onProfile = emptyProfile();
	private static OptiminiumGpuOptimizer.SceneSnapshot offScene = emptyScene();
	private static OptiminiumGpuOptimizer.SceneSnapshot onScene = emptyScene();
	private static OptiminiumRenderProfiler.Snapshot offRenderProfile = emptyRenderProfile();
	private static OptiminiumRenderProfiler.Snapshot onRenderProfile = emptyRenderProfile();
	private static OptiminiumGlStateTracker.DiagnosticSnapshot offGlTracker = OptiminiumGlStateTracker.DiagnosticSnapshot.empty();
	private static OptiminiumGlStateTracker.DiagnosticSnapshot onGlTracker = OptiminiumGlStateTracker.DiagnosticSnapshot.empty();
	private static OptiminiumMetrics.Snapshot offMetricStart = emptyMetrics();
	private static OptiminiumMetrics.Snapshot onMetricStart = emptyMetrics();
	private static OptiminiumMetrics.Snapshot offMetrics = emptyMetrics();
	private static OptiminiumMetrics.Snapshot onMetrics = emptyMetrics();
	private static OptiminiumVisualSignificance.Snapshot offSignificanceStart;
	private static OptiminiumVisualSignificance.Snapshot offSignificanceEnd;
	private static OptiminiumVisualSignificance.Snapshot onSignificanceStart;
	private static OptiminiumVisualSignificance.Snapshot onSignificanceEnd;
	private static OptiminiumVisualSignificance.SchedulerSnapshot offScheduler;
	private static OptiminiumVisualSignificance.SchedulerSnapshot onScheduler;
	private static OptiminiumGpuOptimizer.ParticleProfileSnapshot offParticleProfile;
	private static OptiminiumGpuOptimizer.ParticleProfileSnapshot onParticleProfile;
	private static CameraSnapshot offCameraSnapshot = CameraSnapshot.EMPTY;
	private static final List<Long> offFrames = new ArrayList<>();
	private static final List<Long> onFrames = new ArrayList<>();
	private static final List<Long> offThreadCpuFrames = new ArrayList<>();
	private static final List<Long> onThreadCpuFrames = new ArrayList<>();
	private static final List<Long> offGpuFrames = new ArrayList<>();
	private static final List<Long> onGpuFrames = new ArrayList<>();
	private static final List<FrameBurstSample> onBurstFrames = new ArrayList<>();
	private static List<BenchmarkCase> fullBenchmarkCases = Collections.emptyList();
	private static final List<FullBenchmarkResult> fullBenchmarkResults = new ArrayList<>();
	private static final List<RepeatBenchmarkResult> repeatBenchmarkResults = new ArrayList<>();
	private static int fullBenchmarkIndex;
	private static int repeatBenchmarkTarget;
	private static OptiminiumSettings.Snapshot fullBenchmarkSettings;
	private static String activeBenchmarkName;
	private static int autoStartTicks;
	private static boolean autoStarted;
	private static boolean persistenceOnlyRequested;

	private OptiminiumBenchmark() {
	}

	public static void start() {
		if (running) {
			return;
		}
		startBenchmark(null, true);
	}

	/** Runs an A/B pass that changes only the applicable persistence feature. */
	public static void startPersistence() {
		if (running) return;
		persistenceOnlyRequested = true;
		startBenchmark(null, true);
	}

	public static void startRepeat() {
		if (running) {
			return;
		}
		repeatBenchmarkTarget = 3;
		repeatBenchmarkResults.clear();
		message("Optiminium repeat benchmark: " + repeatBenchmarkTarget + " samples queued.");
		startBenchmark(null, true);
	}

	public static void startFull() {
		if (running) {
			return;
		}
		fullBenchmarkSettings = OptiminiumSettings.snapshot();
		fullBenchmarkCases = fullBenchmarkCases();
		fullBenchmarkResults.clear();
		fullBenchmarkIndex = 0;
		message("Optiminium full benchmark: " + fullBenchmarkCases.size() + " individual settings queued.");
		startNextFullBenchmark();
	}

	private static void startBenchmark(String benchmarkName, boolean forceSignificanceMetrics) {
		running = true;
		activeBenchmarkName = benchmarkName;
		previousEnabled = OptiminiumSettings.isEnabled();
		previousSettings = OptiminiumSettings.snapshot();
		phaseSequence = phaseSequenceFor(benchmarkName);
		phaseSequenceIndex = 0;
		phase = phaseSequence[phaseSequenceIndex];
		ticks = 0;
		offMeasuredTicks = 0;
		onMeasuredTicks = 0;
		stableTicksThisPhase = 0;
		lastFrameNanos = 0L;
		lastThreadCpuNanos = readThreadCpuNanos();
		lastGpuSample = OptiminiumGpuTimer.getSampleCount();
		OptiminiumGpuOptimizer.flushPendingMetrics();
		offDiagnostics = "";
		onDiagnostics = "";
		offProfile = emptyProfile();
		onProfile = emptyProfile();
		offScene = emptyScene();
		onScene = emptyScene();
		offRenderProfile = emptyRenderProfile();
		onRenderProfile = emptyRenderProfile();
		offGlTracker = OptiminiumGlStateTracker.DiagnosticSnapshot.empty();
		onGlTracker = OptiminiumGlStateTracker.DiagnosticSnapshot.empty();
		offMetricStart = OptiminiumMetrics.snapshot();
		onMetricStart = emptyMetrics();
		offMetrics = emptyMetrics();
		onMetrics = emptyMetrics();
		offCameraSnapshot = CameraSnapshot.capture();
		cameraStable = true;
		offFrames.clear();
		onFrames.clear();
		offThreadCpuFrames.clear();
		onThreadCpuFrames.clear();
		offGpuFrames.clear();
		onGpuFrames.clear();
		onBurstFrames.clear();
		OptiminiumGpuOptimizer.resetAdaptiveStats();
		OptiminiumGpuOptimizer.setProfilingEnabled(true);
		OptiminiumRenderProfiler.setEnabled(true);
		OptiminiumVisualSignificance.setDetailedStatisticsEnabled(true);
		applyPhaseSettings();
		settleBeforeWarmup();
		offSignificanceStart = null;
		offSignificanceEnd = null;
		onSignificanceStart = null;
		onSignificanceEnd = null;
		offScheduler = null;
		onScheduler = null;
		offParticleProfile = null;
		onParticleProfile = null;
		message(benchmarkPrefix() + ": " + phase.name() + " preflight started.");
	}

	@SubscribeEvent
	public static void onFrame(RenderFrameEvent.Pre event) {
		if (!running) {
			return;
		}
		long now = System.nanoTime();
		long threadCpuNanos = readThreadCpuNanos();
		if (!isMeasuring()) {
			lastFrameNanos = now;
			lastThreadCpuNanos = threadCpuNanos;
			return;
		}
		if (lastFrameNanos != 0L) {
			long frameNanos = now - lastFrameNanos;
			(phase == Phase.OFF ? offFrames : onFrames).add(frameNanos);
			if (phase == Phase.ON) {
				onBurstFrames.add(new FrameBurstSample(frameNanos,
					OptiminiumVisualSignificance.frameBurstSnapshot(),
					OptiminiumRenderProfiler.frameSnapshot()));
			}
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
		if (!running && !autoStarted && Boolean.getBoolean("optiminium.autoBenchmark")) {
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft.level == null || minecraft.player == null) {
				autoStartTicks = 0;
				return;
			}
			if (++autoStartTicks >= 100) {
				autoStarted = true;
				start();
			}
		}
		if (!running) {
			return;
		}
		cameraStable = cameraStable && offCameraSnapshot.matches(CameraSnapshot.capture());
		ticks++;
		if (ticks == PREFLIGHT_TICKS) {
			beginWarmupPhase();
			return;
		}
		if (ticks == MEASURE_START_TICKS) {
			beginMeasuredPhase();
			return;
		}
		if (!shouldFinishPhase()) {
			return;
		}
		captureMeasuredPhase();
		if (advancePhase()) {
			return;
		}
		running = false;
		if (isPersistenceOnlyBenchmark() && previousSettings != null) {
			OptiminiumSettings.restore(previousSettings);
		} else {
			OptiminiumSettings.setEnabled(previousEnabled);
		}
		OptiminiumGpuOptimizer.setProfilingEnabled(false);
		OptiminiumRenderProfiler.setEnabled(false);
		OptiminiumVisualSignificance.setDetailedStatisticsEnabled(false);
		if (fullBenchmarkSettings != null) {
			fullBenchmarkResults.add(fullBenchmarkResult(activeBenchmarkName, onMetrics, onProfile, onScene, onRenderProfile, onDiagnostics));
		}
		for (String line : report(offMetrics, onMetrics, offProfile, onProfile, offScene, onScene, offRenderProfile, onRenderProfile, offGlTracker, onGlTracker, onDiagnostics)) {
			message(line);
		}
		if (repeatBenchmarkTarget > 0 && fullBenchmarkSettings == null) {
			repeatBenchmarkResults.add(repeatBenchmarkResult());
			int validSamples = validRepeatCount(repeatBenchmarkResults);
			if (validSamples < repeatBenchmarkTarget) {
				message("Optiminium repeat benchmark: " + validSamples + "/" + repeatBenchmarkTarget
					+ " valid samples (" + repeatBenchmarkResults.size() + " total attempts).");
				startBenchmark(null, true);
				return;
			}
			Path repeatReport = writeRepeatBenchmarkReport(repeatBenchmarkResults);
			repeatBenchmarkTarget = 0;
			repeatBenchmarkResults.clear();
			if (repeatReport != null) {
				message("Optiminium repeat benchmark HTML report: " + repeatReport);
			}
			message("Optiminium repeat benchmark: complete.");
		}
		if (fullBenchmarkSettings != null) {
			if (startNextFullBenchmark()) {
				return;
			}
			OptiminiumSettings.restore(fullBenchmarkSettings);
			Path fullReport = writeFullBenchmarkReport(fullBenchmarkResults);
			fullBenchmarkSettings = null;
			fullBenchmarkCases = Collections.emptyList();
			fullBenchmarkResults.clear();
			activeBenchmarkName = null;
			if (fullReport != null) {
				message("Optiminium full benchmark HTML report: " + fullReport);
			}
			message("Optiminium full benchmark: complete.");
		}
		persistenceOnlyRequested = false;
		if (Boolean.getBoolean("optiminium.autoExitAfterBenchmark")) {
			message("Optiminium benchmark: automatic client shutdown requested.");
			Minecraft.getInstance().stop();
		}
	}

	private static Phase[] phaseSequenceFor(String benchmarkName) {
		if (benchmarkName != null) {
			return new Phase[]{Phase.OFF, Phase.ON};
		}
		String lastOrder = lastRecordedPhaseOrder();
		if ("OFF->ON".equals(lastOrder)) {
			return new Phase[]{Phase.ON, Phase.OFF};
		}
		if ("ON->OFF".equals(lastOrder)) {
			return new Phase[]{Phase.OFF, Phase.ON};
		}
		boolean startOn = (normalBenchmarkStarts++ & 1) == 1;
		return startOn ? new Phase[]{Phase.ON, Phase.OFF} : new Phase[]{Phase.OFF, Phase.ON};
	}

	private static String lastRecordedPhaseOrder() {
		for (Path history : benchmarkHistoryPaths()) {
			try {
			if (!Files.isRegularFile(history)) {
				continue;
			}
			List<String> lines = Files.readAllLines(history, StandardCharsets.UTF_8);
			for (int i = lines.size() - 1; i >= 0; i--) {
				String line = lines.get(i).trim();
				if (line.isEmpty() || line.startsWith("timestamp,")) {
					continue;
				}
				if (line.contains("\"OFF->ON\"")) {
					return "OFF->ON";
				}
				if (line.contains("\"ON->OFF\"")) {
					return "ON->OFF";
				}
			}
			} catch (IOException | RuntimeException exception) {
				OptiminiumMod.LOGGER.debug("Could not read Optiminium benchmark history for phase-order selection", exception);
			}
		}
		return "";
	}

	private static List<Path> benchmarkHistoryPaths() {
		Path directory = Minecraft.getInstance().gameDirectory.toPath().resolve("optiminium_reports");
		return List.of(directory.resolve("optiminium-benchmark-history-scene-v2.csv"),
			directory.resolve("optiminium-benchmark-history.csv"));
	}

	private static Path benchmarkHistoryPath() {
		return Minecraft.getInstance().gameDirectory.toPath()
			.resolve("optiminium_reports")
			.resolve("optiminium-benchmark-history-scene-v2.csv");
	}

	private static String rendererHistoryMode() {
		return OptiminiumSodiumCompat.rendererModeString().replaceAll("[^A-Za-z0-9_-]", "_");
	}

	private static String benchmarkSceneKey() {
		return offCameraSnapshot.sceneKey();
	}

	private static void applyPhaseSettings() {
		if (!isPersistenceOnlyBenchmark()) {
			OptiminiumSettings.setEnabled(phase == Phase.ON);
			return;
		}
		OptiminiumSettings.setEnabled(true);
		if (Boolean.getBoolean("optiminium.persistentMeshBenchmarkMobs")) {
			OptiminiumSettings.setBlockEntityPersistenceEnabled(false);
			OptiminiumSettings.setMobPersistenceEnabled(phase == Phase.ON);
		} else {
			OptiminiumSettings.setMobPersistenceEnabled(false);
			OptiminiumSettings.setBlockEntityPersistenceEnabled(phase == Phase.ON);
		}
	}

	private static boolean isPersistenceOnlyBenchmark() {
		return persistenceOnlyRequested || Boolean.getBoolean("optiminium.persistenceOnlyBenchmark");
	}

	private static void captureMeasuredPhase() {
		OptiminiumGpuOptimizer.flushPendingMetrics();
		if (phase == Phase.OFF) {
			offMeasuredTicks = measuredTicksThisPhase();
			offSignificanceEnd = OptiminiumVisualSignificance.snapshot();
			offScheduler = OptiminiumVisualSignificance.schedulerSnapshot();
			offParticleProfile = OptiminiumGpuOptimizer.particleProfileSnapshot();
			offDiagnostics = OptiminiumGpuOptimizer.diagnosticLine();
			offProfile = OptiminiumGpuOptimizer.profileSnapshot();
			offScene = OptiminiumGpuOptimizer.sceneSnapshot();
			offRenderProfile = OptiminiumRenderProfiler.snapshot();
			offGlTracker = OptiminiumGlStateTracker.snapshot();
			offMetrics = delta(offMetricStart, OptiminiumMetrics.snapshot());
			offCameraSnapshot = offCameraSnapshot.finish();
		} else {
			onMeasuredTicks = measuredTicksThisPhase();
			onSignificanceEnd = OptiminiumVisualSignificance.snapshot();
			onScheduler = OptiminiumVisualSignificance.schedulerSnapshot();
			onParticleProfile = OptiminiumGpuOptimizer.particleProfileSnapshot();
			onDiagnostics = OptiminiumGpuOptimizer.diagnosticLine();
			onProfile = OptiminiumGpuOptimizer.profileSnapshot();
			onScene = OptiminiumGpuOptimizer.sceneSnapshot();
			onRenderProfile = OptiminiumRenderProfiler.snapshot();
			onGlTracker = OptiminiumGlStateTracker.snapshot();
			onMetrics = delta(onMetricStart, OptiminiumMetrics.snapshot());
		}
	}

	private static boolean advancePhase() {
		if (++phaseSequenceIndex >= phaseSequence.length) {
			return false;
		}
		phase = phaseSequence[phaseSequenceIndex];
		ticks = 0;
		stableTicksThisPhase = 0;
		lastFrameNanos = 0L;
		lastThreadCpuNanos = readThreadCpuNanos();
		lastGpuSample = OptiminiumGpuTimer.getSampleCount();
		OptiminiumGpuOptimizer.resetAdaptiveStats();
		OptiminiumRenderProfiler.reset();
		OptiminiumVisualSignificance.reset();
		applyPhaseSettings();
		settleBeforeWarmup();
		message(benchmarkPrefix() + ": " + phase.name() + " preflight started.");
		return true;
	}

	private static boolean isMeasuring() {
		return ticks >= MEASURE_START_TICKS && measuredTicksThisPhase() < MAX_MEASURE_TICKS;
	}

	private static int measuredTicksThisPhase() {
		return Math.max(0, ticks - MEASURE_START_TICKS);
	}

	private static boolean shouldFinishPhase() {
		int measuredTicks = measuredTicksThisPhase();
		if (measuredTicks < MIN_MEASURE_TICKS) {
			return false;
		}
		if (measuredTicks >= MAX_MEASURE_TICKS) {
			return true;
		}
		if (stability(currentPhaseFrames()).isStable()) {
			stableTicksThisPhase++;
		} else {
			stableTicksThisPhase = 0;
		}
		return stableTicksThisPhase >= STABLE_HOLD_TICKS;
	}

	private static List<Long> currentPhaseFrames() {
		return phase == Phase.OFF ? offFrames : onFrames;
	}

	private static void settleBeforeWarmup() {
		OptiminiumGpuOptimizer.flushPendingMetrics();
		OptiminiumGpuOptimizer.resetAdaptiveStats();
		OptiminiumBlockEntityRenderCache.resetForBenchmark();
		OptiminiumRenderProfiler.reset();
		OptiminiumVisualSignificance.reset();
		System.gc();
		lastFrameNanos = 0L;
		lastThreadCpuNanos = readThreadCpuNanos();
		lastGpuSample = OptiminiumGpuTimer.getSampleCount();
	}

	private static void beginWarmupPhase() {
		OptiminiumGpuOptimizer.flushPendingMetrics();
		OptiminiumGpuOptimizer.resetAdaptiveStats();
		OptiminiumBlockEntityRenderCache.resetForBenchmark();
		OptiminiumRenderProfiler.reset();
		OptiminiumVisualSignificance.reset();
		lastFrameNanos = 0L;
		lastThreadCpuNanos = readThreadCpuNanos();
		lastGpuSample = OptiminiumGpuTimer.getSampleCount();
		offCameraSnapshot = offCameraSnapshot.finish();
		message(benchmarkPrefix() + ": " + phase.name() + " warmup started after "
			+ ticksToSeconds(PREFLIGHT_TICKS) + "s preflight.");
	}

	private static void beginMeasuredPhase() {
		OptiminiumGpuOptimizer.flushPendingMetrics();
		OptiminiumGpuOptimizer.resetAdaptiveStats();
		OptiminiumRenderProfiler.reset();
		OptiminiumVisualSignificance.reset();
		stableTicksThisPhase = 0;
		lastFrameNanos = 0L;
		lastThreadCpuNanos = readThreadCpuNanos();
		lastGpuSample = OptiminiumGpuTimer.getSampleCount();
		if (phase == Phase.OFF) {
			offFrames.clear();
			offThreadCpuFrames.clear();
			offGpuFrames.clear();
			offMetricStart = OptiminiumMetrics.snapshot();
			offSignificanceStart = OptiminiumVisualSignificance.snapshot();
		} else {
			onFrames.clear();
			onThreadCpuFrames.clear();
			onGpuFrames.clear();
			onBurstFrames.clear();
			onMetricStart = OptiminiumMetrics.snapshot();
			onSignificanceStart = OptiminiumVisualSignificance.snapshot();
		}
		message(benchmarkPrefix() + ": " + phase.name() + " measured pass started after "
			+ ticksToSeconds(WARMUP_TICKS) + "s warmup.");
	}

	private static boolean startNextFullBenchmark() {
		if (fullBenchmarkIndex >= fullBenchmarkCases.size()) {
			return false;
		}
		BenchmarkCase benchmarkCase = fullBenchmarkCases.get(fullBenchmarkIndex++);
		OptiminiumSettings.restore(fullBenchmarkSettings);
		OptiminiumSettings.disableBenchmarkFeatures();
		OptiminiumSettings.setEnabled(true);
		benchmarkCase.enable();
		message("Optiminium full benchmark: running " + benchmarkCase.name() + ".");
		startBenchmark(benchmarkCase.name(), false);
		return true;
	}

	private static List<BenchmarkCase> fullBenchmarkCases() {
		return List.of(
			new BenchmarkCase("All settings off", () -> {
			}),
			new BenchmarkCase("Frame Pacing", () -> OptiminiumSettings.setFramePacing(true)),
			new BenchmarkCase("Block Entity Cache", () -> OptiminiumSettings.setBlockEntityRenderCache(true)),
			new BenchmarkCase("Block Entity Persistence", () -> {
				OptiminiumSettings.setBlockEntityRenderCache(true);
				OptiminiumSettings.setBlockEntityPersistenceEnabled(true);
			}),
			new BenchmarkCase("All Features", () -> {
				OptiminiumSettings.restore(fullBenchmarkSettings);
				OptiminiumSettings.setEnabled(true);
				OptiminiumSettings.setBlockEntityRenderCache(true);
				OptiminiumSettings.setBlockEntityPersistenceEnabled(true);
			})
		);
	}

	private static String benchmarkPrefix() {
		return activeBenchmarkName == null ? "Optiminium benchmark" : "Optiminium full benchmark [" + activeBenchmarkName + "]";
	}

	private static List<String> report(OptiminiumMetrics.Snapshot offMetrics, OptiminiumMetrics.Snapshot onMetrics, OptiminiumGpuOptimizer.ProfileSnapshot offProfile,
			OptiminiumGpuOptimizer.ProfileSnapshot onProfile, OptiminiumGpuOptimizer.SceneSnapshot offScene, OptiminiumGpuOptimizer.SceneSnapshot onScene,
			OptiminiumRenderProfiler.Snapshot offRenderProfile, OptiminiumRenderProfiler.Snapshot onRenderProfile,
			OptiminiumGlStateTracker.DiagnosticSnapshot offGlTracker, OptiminiumGlStateTracker.DiagnosticSnapshot onGlTracker, String onDiagnostics) {
		double offFps = averageFps(offFrames);
		double onFps = averageFps(onFrames);
		double fpsGain = onFps - offFps;
		BenchmarkStability offStability = stability(offFrames);
		BenchmarkStability onStability = stability(onFrames);
		BenchmarkComparison comparison = compareStability(offStability, onStability);
		BenchmarkHistoryTrend historyTrend = historyTrend(rendererHistoryMode(), benchmarkSceneKey(), percentChange(offFps, onFps), comparison.frameGainMs());
		double gpuSavingsMs = averageNanosOrZero(offGpuFrames) / 1_000_000.0D - averageNanosOrZero(onGpuFrames) / 1_000_000.0D;
		double frameSavingsMs = averageNanosOrZero(offFrames) / 1_000_000.0D - averageNanosOrZero(onFrames) / 1_000_000.0D;
		long particlesPrevented = onMetrics.hiddenParticles() - offMetrics.hiddenParticles();
		long blockEntitiesPrevented = onMetrics.culledBlockEntityRenders() - offMetrics.culledBlockEntityRenders();
		long entitiesPrevented = onMetrics.culledEntityRenders() - offMetrics.culledEntityRenders();
		// Significance delta: delta = end - start. Since we called reset() at the start
		// of each phase, the snapshot is already an isolated accumulation for that phase.
		OptiminiumVisualSignificance.Snapshot offSignificanceDelta = offSignificanceEnd != null ? offSignificanceEnd : OptiminiumVisualSignificance.snapshot();
		OptiminiumVisualSignificance.Snapshot onSignificanceDelta = onSignificanceEnd != null ? onSignificanceEnd : OptiminiumVisualSignificance.snapshot();
		String recommendation = recommendation(onProfile, offRenderProfile, onRenderProfile, particlesPrevented, blockEntitiesPrevented, entitiesPrevented);
		long totalPrevented = Math.max(0L, particlesPrevented) + Math.max(0L, blockEntitiesPrevented) + Math.max(0L, entitiesPrevented);
		String prefix = benchmarkPrefix();
		List<String> lines = new ArrayList<>();
		lines.add(prefix + " " + FORMAT_VERSION + ": rendererCompatibilityMode=" + OptiminiumSodiumCompat.rendererModeString() + ", phaseOrder=" + phaseOrderLine() + " OFF[" + stats(offFrames, offGpuFrames) + "] ON[" + stats(onFrames, onGpuFrames) + "]");
		lines.add(String.format(prefix + " net: GPU savings=%.2f ms, CPU overhead=%.3f ms/frame, net frame gain=%.2f ms, FPS gain=%.1f", gpuSavingsMs, onProfile.totalOptiminiumCpuMs(), frameSavingsMs, fpsGain));
		lines.add(prefix + " consistency: " + consistencyLine(offStability, onStability, comparison));
		lines.add(prefix + " repeatability: " + historyTrend.line());
		lines.add(prefix + " normalized OFF[" + normalizedCpuLine(offFrames, offThreadCpuFrames, offGpuFrames, offProfile) + "] ON[" + normalizedCpuLine(onFrames, onThreadCpuFrames, onGpuFrames, onProfile) + "]");
		lines.add(String.format(prefix + " prevented: particles=%d, blockEntities=%d, entities=%d", particlesPrevented, blockEntitiesPrevented, entitiesPrevented));
		lines.add(prefix + " scene OFF[" + sceneLine(offScene) + "] ON[" + sceneLine(onScene) + "]");
		lines.add(prefix + " significance OFF[" + significanceLine(offSignificanceDelta, offMetrics, offScene) + "] ON[" + significanceLine(onSignificanceDelta, onMetrics, onScene) + "]");
		lines.add(prefix + " significance summary OFF[" + significanceSummary(offSignificanceDelta) + "] ON[" + significanceSummary(onSignificanceDelta) + "]");
		if (offScheduler != null) lines.add(prefix + " scheduler OFF[" + offScheduler.toLine() + "]");
		if (onScheduler != null) lines.add(prefix + " scheduler ON[" + onScheduler.toLine() + "]");
		if (offParticleProfile != null) lines.add(prefix + " particles OFF[" + offParticleProfile.toLine() + "]");
		if (onParticleProfile != null) lines.add(prefix + " particles ON[" + onParticleProfile.toLine() + "]");
		lines.add(String.format(prefix + " FPS estimate: particleCulling=%.1f, blockEntityCulling=%.1f, entityCulling=%.1f, adaptiveQuality=not isolated", estimatedFpsGain(fpsGain, particlesPrevented, totalPrevented), estimatedFpsGain(fpsGain, blockEntitiesPrevented, totalPrevented), estimatedFpsGain(fpsGain, entitiesPrevented, totalPrevented)));
		lines.add(prefix + " CPU OFF[" + profileLine(offProfile) + "]");
		lines.add(prefix + " CPU ON[" + profileLine(onProfile) + "]");
		lines.add(prefix + " render OFF[" + renderProfileLine(offRenderProfile, offFrames.size()) + "]");
		lines.add(prefix + " render ON[" + renderProfileLine(onRenderProfile, onFrames.size()) + "]");
		lines.add(prefix + " render delta: " + renderDeltaLine(offRenderProfile, onRenderProfile));
		lines.add(prefix + " gl tracker OFF[" + glTrackerLine(offGlTracker) + "]");
		lines.add(prefix + " gl tracker ON[" + glTrackerLine(onGlTracker) + "]");
		lines.add(prefix + " low-gain profile: dominatedBy=" + lowGainDominance(fpsGain, offRenderProfile, onRenderProfile));
		lines.add(prefix + " recommendation: " + recommendation);
		lines.add(prefix + " diagnostics: OFF" + offDiagnostics + " | ON" + onDiagnostics + ", cameraStable=" + cameraStable + ", preflightTicks=" + PREFLIGHT_TICKS + ", warmupTicks=" + WARMUP_TICKS + ", stableHoldTicks=" + STABLE_HOLD_TICKS + ", offMeasureTicks=" + offMeasuredTicks + ", onMeasureTicks=" + onMeasuredTicks + ", maxMeasureTicks=" + MAX_MEASURE_TICKS);
		// Inactive metric detection: flag optimizations that show zero or negative delta
		String inactiveMetrics = inactiveMetricDetection(offMetrics, onMetrics, offSignificanceDelta, onSignificanceDelta);
		if (!inactiveMetrics.isEmpty()) {
			lines.add(prefix + " inactive metrics: " + inactiveMetrics);
		}
		if (fullBenchmarkSettings == null) {
			Path htmlReport = writeHtmlReport(offMetrics, onMetrics, offProfile, onProfile, offScene, onScene,
				offRenderProfile, onRenderProfile, offGlTracker, onGlTracker, onDiagnostics, recommendation,
				offSignificanceDelta, onSignificanceDelta);
			if (htmlReport != null) {
				lines.add(prefix + " HTML report: " + htmlReport);
			}
		}
		return lines;
	}

	private static String stats(List<Long> frames, List<Long> gpuFrames) {
		if (frames.isEmpty()) {
			return "no data";
		}
		long slowest = 0L;
		for (long frame : frames) {
			slowest = Math.max(slowest, frame);
		}
		double averageFps = averageFps(frames);
		double onePercentLow = onePercentLowFps(frames);
		double worstFrameFps = 1_000_000_000.0D / slowest;
		BenchmarkStability stability = stability(frames);
		String text = String.format("%.1f avg FPS, %.1f 1%% low FPS, %.1f worst-frame FPS, samples=%d, cv=%.2f%%",
			averageFps, onePercentLow, worstFrameFps, frames.size(), stability.coefficientOfVariationPercent());
		if (!gpuFrames.isEmpty()) {
			text += String.format(", %.2f avg GPU ms, %.2f worst GPU ms", averageNanos(gpuFrames) / 1_000_000.0D, maxNanos(gpuFrames) / 1_000_000.0D);
		}
		return text;
	}

	private static String consistencyLine(BenchmarkStability offStability, BenchmarkStability onStability,
			BenchmarkComparison comparison) {
		return String.format(Locale.US,
			"verdict=%s, cameraStable=%s, warmup=%ds, measure=%ds, offCv=%.2f%%, onCv=%.2f%%, frameGain=%.3f ms, combined95Ci=%.3f ms, signalToNoise=%.2f",
			consistencyVerdict(offStability, onStability, comparison),
			cameraStable,
			ticksToSeconds(WARMUP_TICKS),
			ticksToSeconds(Math.max(offMeasuredTicks, onMeasuredTicks)),
			offStability.coefficientOfVariationPercent(),
			onStability.coefficientOfVariationPercent(),
			comparison.frameGainMs(),
			comparison.combinedCiMs(),
			comparison.signalToNoise());
	}

	private static String consistencyVerdict(BenchmarkStability offStability, BenchmarkStability onStability,
			BenchmarkComparison comparison) {
		if (!cameraStable) {
			return "invalid-camera";
		}
		return comparison.isReliable() && offStability.isStable() && onStability.isStable()
			? "stable" : "noisy";
	}

	private static BenchmarkHistoryTrend historyTrend(String mode, String sceneKey, double latestFpsGainPercent, double latestFrameGainMs) {
		Path history = benchmarkHistoryPath();
		if (!Files.isRegularFile(history) || !Double.isFinite(latestFpsGainPercent) || !Double.isFinite(latestFrameGainMs)) {
			return BenchmarkHistoryTrend.EMPTY;
		}
		try {
			List<String> lines = Files.readAllLines(history, StandardCharsets.UTF_8);
			List<Double> fpsGainPercents = new ArrayList<>();
			List<Double> frameGainMs = new ArrayList<>();
			for (int i = lines.size() - 1; i >= 0 && fpsGainPercents.size() < RECENT_HISTORY_LIMIT; i--) {
				List<String> columns = parseCsvLine(lines.get(i));
				if (columns.size() < 21 || "timestamp".equals(columns.get(0))) {
					continue;
				}
				if (!FORMAT_VERSION.equals(columns.get(1)) || !mode.equals(columns.get(2))
						|| !sceneKey.equals(columns.get(20)) || !"true".equalsIgnoreCase(columns.get(19))) {
					continue;
				}
				double fpsGainPercent = parseDoubleOrNaN(columns.get(7));
				double frameGain = parseDoubleOrNaN(columns.get(12));
				if (Double.isFinite(fpsGainPercent) && Double.isFinite(frameGain)) {
					fpsGainPercents.add(fpsGainPercent);
					frameGainMs.add(frameGain);
				}
			}
			if (fpsGainPercents.size() < 2) {
				return BenchmarkHistoryTrend.EMPTY;
			}
			double medianFpsGainPercent = median(fpsGainPercents);
			double medianFrameGainMs = median(frameGainMs);
			double deviationPercentPoints = Math.abs(latestFpsGainPercent - medianFpsGainPercent);
			double allowedDeviation = Math.max(HISTORY_REPEATABILITY_PERCENT_POINTS, Math.abs(medianFpsGainPercent) * 0.50D);
			boolean repeatable = deviationPercentPoints <= allowedDeviation;
			return new BenchmarkHistoryTrend(fpsGainPercents.size(), sceneKey, medianFpsGainPercent, latestFpsGainPercent,
				deviationPercentPoints, medianFrameGainMs, latestFrameGainMs, allowedDeviation, repeatable);
		} catch (IOException | RuntimeException exception) {
			OptiminiumMod.LOGGER.debug("Could not read Optiminium benchmark history for repeatability analysis", exception);
			return BenchmarkHistoryTrend.EMPTY;
		}
	}

	private static String phaseOrderLine() {
		StringBuilder builder = new StringBuilder();
		for (Phase step : phaseSequence) {
			if (builder.length() > 0) {
				builder.append("->");
			}
			builder.append(step.name());
		}
		return builder.toString();
	}

	private static String profileLine(OptiminiumGpuOptimizer.ProfileSnapshot profile) {
		return String.format("particleCullingMs=%.3f, blockEntityCullingMs=%.3f, entityCullingMs=%.3f, adaptiveQualityMs=%.3f, visualSignificanceMs=%.3f, totalOptiminiumCpuMs=%.3f, worstParticleCullingMs=%.3f, worstBlockEntityCullingMs=%.3f, worstEntityCullingMs=%.3f, worstAdaptiveQualityMs=%.3f, worstVisualSignificanceMs=%.3f, worstOptiminiumCpuMs=%.3f",
			profile.particleCullingMs(),
			profile.blockEntityCullingMs(),
			profile.entityCullingMs(),
			profile.adaptiveQualityMs(),
			profile.visualSignificanceMs(),
			profile.totalOptiminiumCpuMs(),
			profile.worstParticleCullingMs(),
			profile.worstBlockEntityCullingMs(),
			profile.worstEntityCullingMs(),
			profile.worstAdaptiveQualityMs(),
			profile.worstVisualSignificanceMs(),
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
		return String.format("full=%d, throttled=%d, reused=%d, proxy=%d, culled=%d, significanceCpuMs=%.4f, worstSignificanceCpuMs=%.4f, avgConfidence=%.4f, minConfidence=%.4f, maxConfidence=%.4f, avgWeightedAttentionScore=%.4f, avgWeightedAttentionFull=%.4f, avgWeightedAttentionThrottled=%.4f, avgWeightedAttentionReused=%.4f, avgWeightedAttentionProxy=%.4f, avgWeightedAttentionCulled=%.4f, confidenceBuckets=[%d,%d,%d,%d,%d], avgPopRisk=%.4f, avgVisualImportance=%.4f, avgGameplayImportance=%.4f, avgSafetyImportance=%.4f, lowConfidenceDemotionBlocks=%d, highPopRiskDemotionBlocks=%d, recentlyVisibleProtections=%d, recentlyLookedAtProtections=%d, recentlyInteractedProtections=%d, recentlyChangedProtections=%d, recentlyMovedProtections=%d, recentlyEnteredViewProtections=%d, fastCameraDemotionBlocks=%d, promotionsBecauseLowConfidence=%d, demotionsAllowedBecauseHighConfidence=%d, importantButCulled=%d, averageBandLifetime=%.2f, averageTicksInBand=%.2f, demotionsPerFrame=%.4f, promotionsPerFrame=%.4f, avgImportanceDebt=%.4f, importanceDebtPromotions=%d, decisionBecauseWeightedScore=%d, decisionBecausePopRiskVeto=%d, decisionBecauseConfidenceVeto=%d, decisionBecauseSafetyOverride=%d, decisionBecauseRecentlyVisible=%d, decisionBecauseImportanceDebt=%d, decisionBecauseHysteresis=%d, decisionBecauseNearbyOverride=%d, decisionBecauseGameplayOverride=%d, topTransitionPairs=%s, dirtyObjectsThisFrame=%d, dirtyObjectsTotal=%d, skippedStableObjects=%d, scoreCacheHits=%d, scoreRecomputes=%d, bandTransitionBudgetUsed=%d, bandTransitionBudgetUsedThisFrame=%d, bandTransitionsDeferred=%d, periodicRechecks=%d, transitionsFromDirtyObjects=%d, transitionsFromStableObjects=%d, dirtyReasons=%s, estimatedSavedBlockEntityRenders=%d, estimatedSavedParticleRenders=%d, estimatedSavedEntityRenders=%d, blockEntityCullPreventedByVisibility=%d, blockEntityCullPreventedByRecentlyVisible=%d, blockEntityCullPreventedByLookedAt=%d, blockEntityDowngradedToReusedInsteadOfCulled=%d, blockEntityVisibleCullEvents=%d, moddedBlockEntities=%d, moddedLivingEntities=%d, moddedNonLivingEntities=%d, moddedDynamicEntityCulls=%d, firstDynamicMod=%s, lastDynamicMod=%s, mostCommonSignificanceReason=%s",
			bands.full(),
			bands.throttled(),
			bands.reused(),
			bands.proxy(),
			bands.culled(),
			bands.significanceCpuMs(),
			bands.worstSignificanceCpuMs(),
			bands.averageConfidence(),
			bands.minConfidence(),
			bands.maxConfidence(),
			bands.averageWeightedAttentionScore(),
			bands.averageWeightedAttentionFull(),
			bands.averageWeightedAttentionThrottled(),
			bands.averageWeightedAttentionReused(),
			bands.averageWeightedAttentionProxy(),
			bands.averageWeightedAttentionCulled(),
			bands.confidenceBucketVeryLow(),
			bands.confidenceBucketLow(),
			bands.confidenceBucketMedium(),
			bands.confidenceBucketHigh(),
			bands.confidenceBucketVeryHigh(),
			bands.averagePopRisk(),
			bands.averageVisualImportance(),
			bands.averageGameplayImportance(),
			bands.averageSafetyImportance(),
			bands.lowConfidenceDemotionBlocks(),
			bands.highPopRiskDemotionBlocks(),
			bands.recentlyVisibleProtections(),
			bands.recentlyLookedAtProtections(),
			bands.recentlyInteractedProtections(),
			bands.recentlyChangedProtections(),
			bands.recentlyMovedProtections(),
			bands.recentlyEnteredViewProtections(),
			bands.fastCameraDemotionBlocks(),
			bands.promotionsBecauseLowConfidence(),
			bands.demotionsAllowedBecauseHighConfidence(),
			bands.importantButCulled(),
			bands.averageBandLifetime(),
			bands.averageTicksInBand(),
			bands.demotionsPerFrame(),
			bands.promotionsPerFrame(),
			bands.averageImportanceDebt(),
			bands.importanceDebtPromotions(),
			bands.decisionBecauseWeightedScore(),
			bands.decisionBecausePopRiskVeto(),
			bands.decisionBecauseConfidenceVeto(),
			bands.decisionBecauseSafetyOverride(),
			bands.decisionBecauseRecentlyVisible(),
			bands.decisionBecauseImportanceDebt(),
			bands.decisionBecauseHysteresis(),
			bands.decisionBecauseNearbyOverride(),
			bands.decisionBecauseGameplayOverride(),
			bands.topTransitionPairs(),
			bands.dirtyObjectsThisFrame(),
			bands.dirtyObjectsTotal(),
			bands.skippedStableObjects(),
			bands.scoreCacheHits(),
			bands.scoreRecomputes(),
			bands.bandTransitionBudgetUsed(),
			bands.bandTransitionBudgetUsedThisFrame(),
			bands.bandTransitionsDeferred(),
			bands.periodicRechecks(),
			bands.transitionsFromDirtyObjects(),
			bands.transitionsFromStableObjects(),
			bands.dirtyReasons(),
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
		return String.format("renderLayerSwitchCount=%d, textureBindCount=%d, shaderBindCount=%d, framebufferBindCount=%d, bufferUploadCount=%d, bufferUploadMs=%.3f, textureBindsPerFrame=%.2f, shaderBindsPerFrame=%.2f, renderLayerSwitchesPerFrame=%.2f, bufferUploadsPerFrame=%.2f, maxTextureBindsPerFrame=%d, maxShaderBindsPerFrame=%d, maxBufferUploadsPerFrame=%d, maxRenderLayerSwitchesPerFrame=%d, maxBufferUploadMsPerFrame=%.3f, translucentRenderFrames=%d, particleRenderFrames=%d, terrainRenderFrames=%d, suspectedGlStallFrames=%d, totalRenderProfilingMs=%.3f, uploadBytes=%d, largestUploadSource=%s, proxyLodUploads=%d/%d, terrainChunkUploads=%d/%d, blockEntityUploads=%d/%d, particleUploads=%d/%d, unknownUploads=%d/%d, optiminiumDrawCalls=%d, optiminiumRenderTypeSwitches=%d, optiminiumEndBatchCalls=%d, proxyDrawCalls=%d, proxyBatches=%d, debugDrawCalls=%d, debugBatches=%d, renderLayerTop=%s",
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
			profile.maxTextureBindsPerFrame(),
			profile.maxShaderBindsPerFrame(),
			profile.maxBufferUploadsPerFrame(),
			profile.maxRenderLayerSwitchesPerFrame(),
			profile.maxBufferUploadMsPerFrame(),
			profile.translucentRenderFrames(),
			profile.particleRenderFrames(),
			profile.terrainRenderFrames(),
			profile.suspectedGlStallFrames(),
			profile.totalRenderProfilingMs(),
			profile.totalUploadBytes(),
			profile.largestUploadSource(),
			profile.proxyLodUploadCount(),
			profile.proxyLodUploadBytes(),
			profile.terrainChunkUploadCount(),
			profile.terrainChunkUploadBytes(),
			profile.blockEntityProxyUploadCount(),
			profile.blockEntityProxyUploadBytes(),
			profile.particleEffectUploadCount(),
			profile.particleEffectUploadBytes(),
			profile.unknownVanillaUploadCount(),
			profile.unknownVanillaUploadBytes(), profile.optiminiumDrawCalls(),
			profile.optiminiumRenderTypeSwitches(), profile.optiminiumEndBatchCalls(),
			profile.proxyDrawCalls(), profile.proxyBatches(), profile.debugDrawCalls(), profile.debugBatches(),
			profile.topRenderLayers()
		);
	}

	private static String renderDeltaLine(OptiminiumRenderProfiler.Snapshot offProfile, OptiminiumRenderProfiler.Snapshot onProfile) {
		return "GPU uploads " + deltaWord(offProfile.bufferUploadCount(), onProfile.bufferUploadCount())
			+ ", Optiminium caused " + onProfile.optiminiumRenderTypeSwitches() + " additional RenderType switches."
			+ ", render-layer switches " + deltaWord(offProfile.renderLayerSwitchCount(), onProfile.renderLayerSwitchCount())
			+ ", texture binds " + deltaWord(offProfile.textureBindCount(), onProfile.textureBindCount())
			+ ", shader binds " + deltaWord(offProfile.shaderBindCount(), onProfile.shaderBindCount())
			+ ", suspected GL stalls " + deltaWord(offProfile.suspectedGlStallFrames(), onProfile.suspectedGlStallFrames());
	}

	private static String glTrackerLine(OptiminiumGlStateTracker.DiagnosticSnapshot tracker) {
		return String.format("enableOpenGlTweaks=%s, mode=%s, compatibilitySkipDisabled=%s, autoDisabled=%s, topAutoDisableReason=%s, texReq=%d, texPotentialSkip=%d, texRelaxedPotentialSkip=%d, texSkip=%d, texAct=%d, texPotentialSkipPct=%d, texSkipPct=%d, shReq=%d, shPotentialSkip=%d, shRelaxedPotentialSkip=%d, shSkip=%d, shAct=%d, shPotentialSkipPct=%d, shSkipPct=%d, conservativePotentialSkips=%d, relaxedPotentialSkips=%d, actualSkips=%d, skipRate=%d, inval=%d, topInval=%s, framebufferInvalidations=%d, resourceReloadInvalidations=%d, worldUnloadInvalidations=%d, unknownExternalInvalidations=%d, invalidationsPerFrame=%.2f, textureRequestsPerFrame=%.2f, shaderRequestsPerFrame=%.2f, hookOrder=%s, prevTex=%d, reqTex=%d, prevShader=%d, reqShader=%d, topNoSkip=%s, activeUnitMiss=%d, targetMiss=%d, texIdMiss=%d, shaderIdMiss=%d, noSkipBecauseDifferentTextureId=%d, noSkipBecauseDifferentShaderId=%d, noSkipBecauseDifferentTarget=%d, noSkipBecauseDifferentActiveUnit=%d, noSkipBecauseStateInvalidated=%d, noSkipBecauseModeDisabled=%d, noSkipBecauseCompatibilityMode=%d, glErrorsDetected=%d",
			tracker.openGlTweaksEnabled(),
			tracker.mode(),
			tracker.compatibilitySkipDisabled(),
			tracker.glAutoDisabled(),
			tracker.glAutoDisableReason(),
			tracker.textureBindRequests(),
			tracker.textureBindPotentialSkipped(),
			tracker.textureRelaxedPotentialSkipped(),
			tracker.textureBindSkipped(),
			tracker.textureBindActual(),
			tracker.textureBindPotentialSkippedPercent(),
			tracker.textureBindSkippedPercent(),
			tracker.shaderBindRequests(),
			tracker.shaderBindPotentialSkipped(),
			tracker.shaderRelaxedPotentialSkipped(),
			tracker.shaderBindSkipped(),
			tracker.shaderBindActual(),
			tracker.shaderBindPotentialSkippedPercent(),
			tracker.shaderBindSkippedPercent(),
			tracker.conservativePotentialSkips(),
			tracker.relaxedPotentialSkips(),
			tracker.actualSkips(),
			tracker.skipRatePercent(),
			tracker.trackerInvalidations(),
			tracker.topInvalidationReason(),
			tracker.framebufferInvalidations(),
			tracker.resourceReloadInvalidations(),
			tracker.worldUnloadInvalidations(),
			tracker.unknownExternalInvalidations(),
			tracker.trackerInvalidations() / Math.max(1.0D, onFrames.size()),
			tracker.textureBindRequests() / Math.max(1.0D, onFrames.size()),
			tracker.shaderBindRequests() / Math.max(1.0D, onFrames.size()),
			tracker.hookOrder(),
			tracker.observedPreviousTextureId(),
			tracker.requestedTextureId(),
			tracker.observedPreviousShaderId(),
			tracker.requestedShaderId(),
			tracker.topNoSkipReason(),
			tracker.activeTextureUnitMismatches(),
			tracker.textureTargetMismatches(),
			tracker.textureIdMismatches(),
			tracker.shaderIdMismatches(),
			tracker.noSkipBecauseDifferentTextureId(),
			tracker.noSkipBecauseDifferentShaderId(),
			tracker.noSkipBecauseDifferentTarget(),
			tracker.noSkipBecauseDifferentActiveUnit(),
			tracker.noSkipBecauseStateInvalidated(),
			tracker.noSkipBecauseModeDisabled(),
			tracker.noSkipBecauseCompatibilityMode(),
			tracker.glErrorsDetected());
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
			return "next safe GL target: inspect Optiminium-owned resource uploads, without touching terrain chunk scheduling";
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

	private static FullBenchmarkResult fullBenchmarkResult(String name, OptiminiumMetrics.Snapshot metrics,
			OptiminiumGpuOptimizer.ProfileSnapshot profile, OptiminiumGpuOptimizer.SceneSnapshot scene,
			OptiminiumRenderProfiler.Snapshot renderProfile, String diagnostics) {
		OptiminiumVisualSignificance.Snapshot significance = onSignificanceEnd != null ? onSignificanceEnd : OptiminiumVisualSignificance.snapshot();
		String raw = "stats: " + stats(onFrames, onGpuFrames)
			+ "\nnormalized: " + normalizedCpuLine(onFrames, onThreadCpuFrames, onGpuFrames, profile)
			+ "\nprevented: particles=" + metrics.hiddenParticles() + ", blockEntities=" + metrics.culledBlockEntityRenders() + ", entities=" + metrics.culledEntityRenders()
			+ "\nscene: " + sceneLine(scene)
			+ "\nsignificance: " + significanceLine(significance, metrics, scene)
			+ "\nrender: " + renderProfileLine(renderProfile, onFrames.size())
			+ "\ngl tracker: " + glTrackerLine(onGlTracker)
			+ "\ndiagnostics: " + diagnostics;
		return new FullBenchmarkResult(name,
			averageFps(onFrames),
			onePercentLowOrZero(onFrames),
			worstFrameFps(onFrames),
			averageNanosOrZero(onGpuFrames) / 1_000_000.0D,
			profile.totalOptiminiumCpuMs(),
			raw);
	}

	private static RepeatBenchmarkResult repeatBenchmarkResult() {
		double offFps = averageFps(offFrames);
		double onFps = averageFps(onFrames);
		BenchmarkStability offStability = stability(offFrames);
		BenchmarkStability onStability = stability(onFrames);
		BenchmarkComparison comparison = compareStability(offStability, onStability);
		return new RepeatBenchmarkResult(
			repeatBenchmarkResults.size() + 1,
			phaseOrderLine(),
			benchmarkSceneKey(),
			offFps,
			onFps,
			onFps - offFps,
			percentChange(offFps, onFps),
			comparison.frameGainMs(),
			Math.max(offStability.coefficientOfVariationPercent(), onStability.coefficientOfVariationPercent()),
			cameraStable
		);
	}

	private static Path writeRepeatBenchmarkReport(List<RepeatBenchmarkResult> results) {
		if (results.isEmpty()) {
			return null;
		}
		try {
			Path directory = Minecraft.getInstance().gameDirectory.toPath().resolve("optiminium_reports");
			Files.createDirectories(directory);
			String mode = rendererHistoryMode();
			String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss"));
			Path report = directory.resolve("optiminium-repeat-benchmark-" + timestamp + "-" + mode + ".html");
			Files.writeString(report, repeatBenchmarkHtml(results), StandardCharsets.UTF_8);
			return report;
		} catch (IOException | RuntimeException exception) {
			OptiminiumMod.LOGGER.warn("Failed to write Optiminium repeat benchmark HTML report", exception);
			return null;
		}
	}

	private static String repeatBenchmarkHtml(List<RepeatBenchmarkResult> results) {
		List<RepeatBenchmarkResult> aggregateResults = validRepeatResults(results);
		List<Double> fpsGainPercents = repeatValues(aggregateResults, RepeatBenchmarkMetric.FPS_GAIN_PERCENT);
		List<Double> frameGainMs = repeatValues(aggregateResults, RepeatBenchmarkMetric.FRAME_GAIN_MS);
		List<Double> onFps = repeatValues(aggregateResults, RepeatBenchmarkMetric.ON_FPS);
		double medianFpsGainPercent = median(fpsGainPercents);
		double medianFrameGainMs = median(frameGainMs);
		double spreadFpsGainPercent = maxDouble(fpsGainPercents) - minDouble(fpsGainPercents);
		StringBuilder html = new StringBuilder(12000);
		html.append("<!doctype html><html><head><meta charset=\"utf-8\"><title>Optiminium Repeat Benchmark</title><style>");
		html.append("body{margin:0;background:#101318;color:#e8edf2;font:14px/1.45 system-ui,Segoe UI,Arial,sans-serif}main{max-width:1180px;margin:0 auto;padding:28px}h1{font-size:28px;margin:0 0 8px}h2{margin:28px 0 12px}.muted{color:#9aa8b5}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:12px}.card{background:#171d25;border:1px solid #28313d;border-radius:8px;padding:14px}.value{font-size:24px;font-weight:700}.good{color:#66d986}.bad{color:#ff8b73}.warn{color:#ffbf69}table{width:100%;border-collapse:collapse;background:#171d25;border:1px solid #28313d}th,td{padding:8px;border-bottom:1px solid #28313d;text-align:left}.raw{white-space:pre-wrap;overflow:auto;background:#0b0e12;border:1px solid #28313d;border-radius:8px;padding:12px}</style></head><body><main>");
		html.append("<h1>Optiminium Repeat Benchmark</h1><p class=\"muted\">")
			.append(escape(FORMAT_VERSION)).append(" | rendererCompatibilityMode=")
			.append(escape(OptiminiumSodiumCompat.rendererModeString()))
			.append(" | samples=").append(results.size())
			.append(" | aggregateValidSamples=").append(aggregateResults.size()).append("</p>");
		html.append("<h2>Aggregate</h2><div class=\"grid\">");
		card(html, "Median ON FPS", median(onFps), "fps", true);
		card(html, "Median FPS gain", median(repeatValues(aggregateResults, RepeatBenchmarkMetric.FPS_GAIN)), "fps", medianFpsGainPercent >= 0.0D);
		card(html, "Median FPS gain percent", medianFpsGainPercent, "%", medianFpsGainPercent >= 0.0D);
		card(html, "Median frame gain", medianFrameGainMs, "ms", medianFrameGainMs >= 0.0D);
		card(html, "FPS gain spread", spreadFpsGainPercent, "percentage points", spreadFpsGainPercent <= HISTORY_REPEATABILITY_PERCENT_POINTS);
		statusCard(html, "Aggregate source", aggregateResults.size() == results.size() ? "all samples" : "valid samples only", aggregateResults.size() == results.size());
		html.append("</div>");
		if (aggregateResults.size() < results.size()) {
			html.append("<p class=\"warn\">Invalid camera-moved samples were excluded from aggregate cards.</p>");
		}
		if (aggregateResults == results && !results.isEmpty() && !results.get(0).cameraStable()) {
			html.append("<p class=\"bad\">All repeat samples were invalid, so aggregate cards are diagnostic only.</p>");
		}
		html.append("<h2>Samples</h2><table><thead><tr><th>#</th><th>Phase order</th><th>OFF FPS</th><th>ON FPS</th><th>FPS gain</th><th>Gain %</th><th>Frame gain</th><th>Max CV</th><th>Valid</th></tr></thead><tbody>");
		for (RepeatBenchmarkResult result : results) {
			html.append("<tr><td>").append(result.sample()).append("</td><td>").append(escape(result.phaseOrder()))
				.append("</td><td>").append(format(result.offFps()))
				.append("</td><td>").append(format(result.onFps()))
				.append("</td><td>").append(format(result.fpsGain()))
				.append("</td><td>").append(format(result.fpsGainPercent())).append("%")
				.append("</td><td>").append(format(result.frameGainMs())).append(" ms")
				.append("</td><td>").append(format(result.maxCvPercent())).append("%")
				.append("</td><td>").append(result.cameraStable()).append("</td></tr>");
		}
		html.append("</tbody></table><h2>Scene</h2><div class=\"raw\">")
			.append(escape(results.get(0).sceneKey()))
			.append("</div></main></body></html>");
		return html.toString();
	}

	private static List<RepeatBenchmarkResult> validRepeatResults(List<RepeatBenchmarkResult> results) {
		List<RepeatBenchmarkResult> valid = new ArrayList<>();
		for (RepeatBenchmarkResult result : results) {
			if (result.cameraStable() && !"unknown".equals(result.sceneKey())) {
				valid.add(result);
			}
		}
		return valid.isEmpty() ? results : valid;
	}

	private static int validRepeatCount(List<RepeatBenchmarkResult> results) {
		int count = 0;
		for (RepeatBenchmarkResult result : results) if (result.cameraStable() && !"unknown".equals(result.sceneKey())) count++;
		return count;
	}

	private static Path writeFullBenchmarkReport(List<FullBenchmarkResult> results) {
		if (results.isEmpty()) {
			return null;
		}
		try {
			Path directory = Minecraft.getInstance().gameDirectory.toPath().resolve("optiminium_reports");
			Files.createDirectories(directory);
			String mode = rendererHistoryMode();
			String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss"));
			Path report = directory.resolve("optiminium-full-benchmark-" + timestamp + "-" + mode + ".html");
			Files.writeString(report, fullBenchmarkHtml(results), StandardCharsets.UTF_8);
			return report;
		} catch (IOException | RuntimeException exception) {
			OptiminiumMod.LOGGER.warn("Failed to write Optiminium full benchmark HTML report", exception);
			return null;
		}
	}

	private static String fullBenchmarkHtml(List<FullBenchmarkResult> results) {
		FullBenchmarkResult baseline = results.get(0);
		StringBuilder html = new StringBuilder(24000);
		html.append("<!doctype html><html><head><meta charset=\"utf-8\"><title>Optiminium Full Benchmark</title><style>");
		html.append("body{margin:0;background:#101318;color:#e8edf2;font:14px/1.45 system-ui,Segoe UI,Arial,sans-serif}main{max-width:1180px;margin:0 auto;padding:28px}h1{font-size:28px;margin:0 0 8px}h2{margin:28px 0 12px}.muted{color:#9aa8b5}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:12px}.card{background:#171d25;border:1px solid #28313d;border-radius:8px;padding:14px}.value{font-size:24px;font-weight:700}.good{color:#66d986}.bad{color:#ff8b73}.warn{color:#ffbf69}.chart{background:#151a21;border:1px solid #28313d;border-radius:8px;padding:12px;margin:10px 0}svg{width:100%;height:auto}.raw{white-space:pre-wrap;overflow:auto;background:#0b0e12;border:1px solid #28313d;border-radius:8px;padding:12px}details{margin:12px 0}.pill{display:inline-block;border:1px solid #344150;border-radius:999px;padding:4px 10px;margin:3px;color:#c8d3df}</style></head><body><main>");
		html.append("<h1>Optiminium Full Benchmark</h1><p class=\"muted\">")
			.append(escape(FORMAT_VERSION)).append(" | rendererCompatibilityMode=")
			.append(escape(OptiminiumSodiumCompat.rendererModeString()))
			.append(" | baseline=all mutable settings off</p>");
		html.append("<h2>Summary</h2><div class=\"grid\">");
		for (FullBenchmarkResult result : results) {
			card(html, result.name() + " avg FPS", result.averageFps(), "fps", result.averageFps() >= baseline.averageFps());
			card(html, result.name() + " vs baseline", result.averageFps() - baseline.averageFps(), "fps", result.averageFps() >= baseline.averageFps());
		}
		html.append("</div><h2>Charts</h2>");
		html.append(barChart("Average FPS by setting", fullLabels(results), fullValues(results, FullBenchmarkMetric.AVG_FPS)));
		html.append(barChart("1% low FPS by setting", fullLabels(results), fullValues(results, FullBenchmarkMetric.ONE_PERCENT_LOW)));
		html.append(barChart("Worst-frame FPS by setting", fullLabels(results), fullValues(results, FullBenchmarkMetric.WORST_FPS)));
		html.append(barChart("Average GPU ms by setting", fullLabels(results), fullValues(results, FullBenchmarkMetric.GPU_MS)));
		html.append(barChart("Optiminium CPU ms/frame by setting", fullLabels(results), fullValues(results, FullBenchmarkMetric.OPTIMINIUM_CPU_MS)));
		html.append("<h2>Per-setting Details</h2>");
		for (FullBenchmarkResult result : results) {
			html.append("<details><summary>").append(escape(result.name())).append("</summary><div class=\"raw\">")
				.append(escape(result.raw())).append("</div></details>");
		}
		html.append("</main></body></html>");
		return html.toString();
	}

	private static String[] fullLabels(List<FullBenchmarkResult> results) {
		String[] labels = new String[results.size()];
		for (int i = 0; i < results.size(); i++) {
			labels[i] = results.get(i).name();
		}
		return labels;
	}

	private static double[] fullValues(List<FullBenchmarkResult> results, FullBenchmarkMetric metric) {
		double[] values = new double[results.size()];
		for (int i = 0; i < results.size(); i++) {
			FullBenchmarkResult result = results.get(i);
			values[i] = switch (metric) {
				case AVG_FPS -> result.averageFps();
				case ONE_PERCENT_LOW -> result.onePercentLowFps();
				case WORST_FPS -> result.worstFrameFps();
				case GPU_MS -> result.averageGpuMs();
				case OPTIMINIUM_CPU_MS -> result.optiminiumCpuMs();
			};
		}
		return values;
	}

	private static List<Double> repeatValues(List<RepeatBenchmarkResult> results, RepeatBenchmarkMetric metric) {
		List<Double> values = new ArrayList<>(results.size());
		for (RepeatBenchmarkResult result : results) {
			values.add(switch (metric) {
				case ON_FPS -> result.onFps();
				case FPS_GAIN -> result.fpsGain();
				case FPS_GAIN_PERCENT -> result.fpsGainPercent();
				case FRAME_GAIN_MS -> result.frameGainMs();
			});
		}
		return values;
	}

	private static double minDouble(List<Double> values) {
		double min = Double.POSITIVE_INFINITY;
		for (double value : values) {
			min = Math.min(min, value);
		}
		return min;
	}

	private static double maxDouble(List<Double> values) {
		double max = Double.NEGATIVE_INFINITY;
		for (double value : values) {
			max = Math.max(max, value);
		}
		return max;
	}

	private static Path writeHtmlReport(OptiminiumMetrics.Snapshot offMetrics, OptiminiumMetrics.Snapshot onMetrics,
			OptiminiumGpuOptimizer.ProfileSnapshot offProfile, OptiminiumGpuOptimizer.ProfileSnapshot onProfile,
			OptiminiumGpuOptimizer.SceneSnapshot offScene, OptiminiumGpuOptimizer.SceneSnapshot onScene,
			OptiminiumRenderProfiler.Snapshot offRenderProfile, OptiminiumRenderProfiler.Snapshot onRenderProfile,
			OptiminiumGlStateTracker.DiagnosticSnapshot offGlTracker,
			OptiminiumGlStateTracker.DiagnosticSnapshot onGlTracker,
			String onDiagnostics, String recommendation,
			OptiminiumVisualSignificance.Snapshot offSignificanceDelta,
			OptiminiumVisualSignificance.Snapshot onSignificanceDelta) {
		try {
			Path directory = Minecraft.getInstance().gameDirectory.toPath().resolve("optiminium_reports");
			Files.createDirectories(directory);
			String mode = OptiminiumSodiumCompat.rendererModeString().replaceAll("[^A-Za-z0-9_-]", "_");
			String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss"));
			Path report = directory.resolve("optiminium-benchmark-" + timestamp + "-" + mode + ".html");
			Files.writeString(report, htmlReport(offMetrics, onMetrics, offProfile, onProfile, offScene, onScene,
				offRenderProfile, onRenderProfile, offGlTracker, onGlTracker, onDiagnostics, recommendation,
				offSignificanceDelta, onSignificanceDelta), StandardCharsets.UTF_8);
			if (cameraStable && !"unknown".equals(benchmarkSceneKey())) {
				appendBenchmarkHistory(directory, timestamp, mode);
			}
			return report;
		} catch (IOException | RuntimeException exception) {
			OptiminiumMod.LOGGER.warn("Failed to write Optiminium benchmark HTML report", exception);
			return null;
		}
	}

	private static void appendBenchmarkHistory(Path directory, String timestamp, String mode) {
		try {
			Path history = benchmarkHistoryPath();
			boolean needsHeader = !Files.isRegularFile(history);
			BenchmarkStability offStability = stability(offFrames);
			BenchmarkStability onStability = stability(onFrames);
			BenchmarkComparison comparison = compareStability(offStability, onStability);
			double offFps = averageFps(offFrames);
			double onFps = averageFps(onFrames);
			StringBuilder row = new StringBuilder(512);
			if (needsHeader) {
				row.append("timestamp,format,renderer,phaseOrder,offFps,onFps,fpsGain,fpsGainPercent,")
					.append("offSamples,onSamples,offCvPercent,onCvPercent,frameGainMs,combined95CiMs,")
					.append("signalToNoise,offMeasureTicks,onMeasureTicks,warmupTicks,maxMeasureTicks,cameraStable,sceneKey\n");
			}
			row.append(csv(timestamp)).append(',')
				.append(csv(FORMAT_VERSION)).append(',')
				.append(csv(mode)).append(',')
				.append(csv(phaseOrderLine())).append(',')
				.append(formatCsv(offFps)).append(',')
				.append(formatCsv(onFps)).append(',')
				.append(formatCsv(onFps - offFps)).append(',')
				.append(formatCsv(percentChange(offFps, onFps))).append(',')
				.append(offStability.samples()).append(',')
				.append(onStability.samples()).append(',')
				.append(formatCsv(offStability.coefficientOfVariationPercent())).append(',')
				.append(formatCsv(onStability.coefficientOfVariationPercent())).append(',')
				.append(formatCsv(comparison.frameGainMs())).append(',')
				.append(formatCsv(comparison.combinedCiMs())).append(',')
				.append(formatCsv(comparison.signalToNoise())).append(',')
				.append(offMeasuredTicks).append(',')
				.append(onMeasuredTicks).append(',')
				.append(WARMUP_TICKS).append(',')
				.append(MAX_MEASURE_TICKS).append(',')
				.append(cameraStable).append(',')
				.append(csv(benchmarkSceneKey())).append('\n');
			Files.writeString(history, row.toString(), StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException | RuntimeException exception) {
			OptiminiumMod.LOGGER.warn("Failed to append Optiminium benchmark history", exception);
		}
	}

	private static String htmlReport(OptiminiumMetrics.Snapshot offMetrics, OptiminiumMetrics.Snapshot onMetrics,
			OptiminiumGpuOptimizer.ProfileSnapshot offProfile, OptiminiumGpuOptimizer.ProfileSnapshot onProfile,
			OptiminiumGpuOptimizer.SceneSnapshot offScene, OptiminiumGpuOptimizer.SceneSnapshot onScene,
			OptiminiumRenderProfiler.Snapshot offRenderProfile, OptiminiumRenderProfiler.Snapshot onRenderProfile,
			OptiminiumGlStateTracker.DiagnosticSnapshot offGlTracker,
			OptiminiumGlStateTracker.DiagnosticSnapshot onGlTracker,
			String onDiagnostics, String recommendation,
			OptiminiumVisualSignificance.Snapshot offSignificanceDelta,
			OptiminiumVisualSignificance.Snapshot onSignificanceDelta) {
		OptiminiumVisualSignificance.Snapshot offBands = offSignificanceDelta;
		OptiminiumVisualSignificance.Snapshot onBands = onSignificanceDelta;
		double offFps = averageFps(offFrames);
		double onFps = averageFps(onFrames);
		double offOneLow = onePercentLowOrZero(offFrames);
		double onOneLow = onePercentLowOrZero(onFrames);
		double offWorstFps = worstFrameFps(offFrames);
		double onWorstFps = worstFrameFps(onFrames);
		double offGpuMs = averageNanosOrZero(offGpuFrames) / 1_000_000.0D;
		double onGpuMs = averageNanosOrZero(onGpuFrames) / 1_000_000.0D;
		double offCpuMs = averageNanosOrZero(offFrames) / 1_000_000.0D;
		double onCpuMs = averageNanosOrZero(onFrames) / 1_000_000.0D;
		BenchmarkStability offStability = stability(offFrames);
		BenchmarkStability onStability = stability(onFrames);
		BenchmarkComparison comparison = compareStability(offStability, onStability);
		BenchmarkHistoryTrend historyTrend = historyTrend(rendererHistoryMode(), benchmarkSceneKey(), percentChange(offFps, onFps), comparison.frameGainMs());
		StringBuilder html = new StringBuilder(32000);
		html.append("<!doctype html><html><head><meta charset=\"utf-8\"><title>Optiminium Benchmark</title><style>");
		html.append("body{margin:0;background:#101318;color:#e8edf2;font:14px/1.45 system-ui,Segoe UI,Arial,sans-serif}main{max-width:1180px;margin:0 auto;padding:28px}h1{font-size:28px;margin:0 0 8px}h2{margin:28px 0 12px}.muted{color:#9aa8b5}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:12px}.card{background:#171d25;border:1px solid #28313d;border-radius:8px;padding:14px}.value{font-size:24px;font-weight:700}.good{color:#66d986}.bad{color:#ff8b73}.warn{color:#ffbf69}.chart{background:#151a21;border:1px solid #28313d;border-radius:8px;padding:12px;margin:10px 0}svg{width:100%;height:auto}.raw{white-space:pre-wrap;overflow:auto;background:#0b0e12;border:1px solid #28313d;border-radius:8px;padding:12px}details{margin:12px 0}.pill{display:inline-block;border:1px solid #344150;border-radius:999px;padding:4px 10px;margin:3px;color:#c8d3df}</style></head><body><main>");
		html.append("<h1>Optiminium Benchmark</h1><p class=\"muted\">")
			.append(escape(FORMAT_VERSION)).append(" | rendererCompatibilityMode=")
			.append(escape(OptiminiumSodiumCompat.rendererModeString())).append("</p>");
		html.append("<div class=\"card\"><strong>Recommendation</strong><br>").append(escape(recommendation)).append("</div>");
		html.append("<div class=\"card\"><strong>Phase Order</strong><br>").append(escape(phaseOrderLine())).append("</div>");
		html.append("<h2>Summary</h2><div class=\"grid\">");
		card(html, "OFF average FPS", offFps, "fps", false);
		card(html, "ON average FPS", onFps, "fps", false);
		card(html, "FPS gain", onFps - offFps, "fps", onFps >= offFps);
		card(html, "FPS gain percent", percentChange(offFps, onFps), "%", onFps >= offFps);
		card(html, "OFF 1% low FPS", offOneLow, "fps", false);
		card(html, "ON 1% low FPS", onOneLow, "fps", false);
		card(html, "1% low gain percent", percentChange(offOneLow, onOneLow), "%", onOneLow >= offOneLow);
		card(html, "OFF worst-frame FPS", offWorstFps, "fps", false);
		card(html, "ON worst-frame FPS", onWorstFps, "fps", false);
		card(html, "Worst-frame gain percent", percentChange(offWorstFps, onWorstFps), "%", onWorstFps >= offWorstFps);
		card(html, "GPU ms delta", onGpuMs - offGpuMs, "ms", onGpuMs <= offGpuMs);
		card(html, "CPU ms delta", onCpuMs - offCpuMs, "ms", onCpuMs <= offCpuMs);
		card(html, "Optiminium CPU overhead", onProfile.totalOptiminiumCpuMs(), "ms/frame", onProfile.totalOptiminiumCpuMs() <= 0.20D);
		html.append("</div>");
		html.append("<h2>Benchmark Consistency</h2><div class=\"grid\">");
		statusCard(html, "Run validity", cameraStable ? "valid" : "invalid: camera moved", cameraStable);
		statusCard(html, "History repeatability", historyTrend.statusText(), historyTrend.repeatable());
		statusCard(html, "Scene key", benchmarkSceneKey(), cameraStable);
		statusCard(html, "FPS estimator", fpsEstimatorLabel(offFrames, onFrames), true);
		card(html, "Preflight settle", ticksToSeconds(PREFLIGHT_TICKS), "seconds", true);
		card(html, "Warmup per phase", ticksToSeconds(WARMUP_TICKS), "seconds", true);
		card(html, "Stable hold", ticksToSeconds(STABLE_HOLD_TICKS), "seconds", true);
		card(html, "OFF measured", ticksToSeconds(offMeasuredTicks), "seconds", true);
		card(html, "ON measured", ticksToSeconds(onMeasuredTicks), "seconds", true);
		card(html, "Max measured cap", ticksToSeconds(MAX_MEASURE_TICKS), "seconds", true);
		card(html, "OFF frame-time CV", offStability.coefficientOfVariationPercent(), "%", offStability.isStable());
		card(html, "ON frame-time CV", onStability.coefficientOfVariationPercent(), "%", onStability.isStable());
		card(html, "Frame gain confidence", comparison.signalToNoise(), "x CI", comparison.isReliable());
		card(html, "Combined 95% CI", comparison.combinedCiMs(), "ms", comparison.combinedCiMs() <= Math.abs(comparison.frameGainMs()));
		html.append("</div><div class=\"card\"><strong>Consistency Summary</strong><p>")
			.append(escape(consistencyLine(offStability, onStability, comparison)))
			.append("</p><p>")
			.append(escape(historyTrend.line()))
			.append("</p></div>");
		appendFramePacingDashboard(html, offFrames, onFrames, onBurstFrames);
		html.append("<h2>Charts</h2>");
		html.append(barChart("OFF vs ON FPS comparison", new String[]{"OFF avg FPS","ON avg FPS"}, new double[]{offFps, onFps}));
		html.append(barChart("OFF vs ON 1% low comparison", new String[]{"OFF 1% low","ON 1% low"}, new double[]{offOneLow, onOneLow}));
		html.append(barChart("OFF vs ON worst-frame comparison", new String[]{"OFF worst","ON worst"}, new double[]{offWorstFps, onWorstFps}));
		html.append(barChart("GPU ms OFF vs ON", new String[]{"OFF GPU ms","ON GPU ms"}, new double[]{offGpuMs, onGpuMs}));
		html.append(barChart("CPU ms OFF vs ON", new String[]{"OFF CPU ms","ON CPU ms"}, new double[]{offCpuMs, onCpuMs}));
		html.append(barChart("Significance band counts", new String[]{"Full","Throttled","Reused","Proxy","Culled"}, new double[]{onBands.full(), onBands.throttled(), onBands.reused(), onBands.proxy(), onBands.culled()}));
		html.append(barChart("Confidence distribution", new String[]{"0.00-0.20","0.20-0.40","0.40-0.60","0.60-0.80","0.80-1.00"}, new double[]{onBands.confidenceBucketVeryLow(), onBands.confidenceBucketLow(), onBands.confidenceBucketMedium(), onBands.confidenceBucketHigh(), onBands.confidenceBucketVeryHigh()}));
		html.append(barChart("Pop-risk / confidence / importance averages", new String[]{"Pop risk","Confidence","Visual","Gameplay","Safety"}, new double[]{onBands.averagePopRisk(), onBands.averageConfidence(), onBands.averageVisualImportance(), onBands.averageGameplayImportance(), onBands.averageSafetyImportance()}));
		html.append(barChart("Render stats", new String[]{"Uploads","Texture binds","Shader binds","Layer switches"}, new double[]{onRenderProfile.bufferUploadCount(), onRenderProfile.textureBindCount(), onRenderProfile.shaderBindCount(), onRenderProfile.renderLayerSwitchCount()}));
		html.append(barChart("GL State Tracker", new String[]{"Tex potential%","Tex actual%","Sh potential%","Sh actual%","Invalidations"}, new double[]{(double)onGlTracker.textureBindPotentialSkippedPercent(), (double)onGlTracker.textureBindSkippedPercent(), (double)onGlTracker.shaderBindPotentialSkippedPercent(), (double)onGlTracker.shaderBindSkippedPercent(), (double)onGlTracker.trackerInvalidations()}));
		html.append(barChart("Protection counters", new String[]{"Pop-risk blocks","Confidence blocks","Recently visible","Hysteresis"}, new double[]{onBands.highPopRiskDemotionBlocks(), onBands.lowConfidenceDemotionBlocks(), onBands.recentlyVisibleProtections(), onBands.demotionsPreventedByHysteresis()}));
		html.append("<h2>Block Entity Cache</h2><div class=\"grid\">");
		appendBeCacheCards(html, onDiagnostics);
		html.append("</div>");
		html.append("<h2>OpenGL Tweaks</h2><div class=\"grid\">");
		appendGlTrackerCards(html, offGlTracker, onGlTracker);
		html.append("</div>");
		html.append("<h2>Decision Engine</h2>");
		html.append(barChart("Weighted attention score averages", new String[]{"All","Full","Throttled","Reused","Proxy","Culled"}, new double[]{onBands.averageWeightedAttentionScore(), onBands.averageWeightedAttentionFull(), onBands.averageWeightedAttentionThrottled(), onBands.averageWeightedAttentionReused(), onBands.averageWeightedAttentionProxy(), onBands.averageWeightedAttentionCulled()}));
		html.append(barChart("Dominant decision reasons", new String[]{"Weighted score","Pop-risk veto","Confidence veto","Safety override","Recently visible","Importance debt","Hysteresis","Nearby","Gameplay"}, new double[]{onBands.decisionBecauseWeightedScore(), onBands.decisionBecausePopRiskVeto(), onBands.decisionBecauseConfidenceVeto(), onBands.decisionBecauseSafetyOverride(), onBands.decisionBecauseRecentlyVisible(), onBands.decisionBecauseImportanceDebt(), onBands.decisionBecauseHysteresis(), onBands.decisionBecauseNearbyOverride(), onBands.decisionBecauseGameplayOverride()}));
		html.append(barChart("Dirty evaluation cache", new String[]{"Cache hits","Score recomputes","Dirty objects","Periodic checks"}, new double[]{onBands.scoreCacheHits(), onBands.scoreRecomputes(), onBands.dirtyObjectsTotal(), onBands.periodicRechecks()}));
		if (onScheduler != null) {
			html.append("<h2>Evaluation Scheduler</h2><div class=\"grid\">");
			card(html, "Debt scheduled", onScheduler.importanceDebtScheduled(), "events", true);
			card(html, "Debt triggered", onScheduler.importanceDebtTriggered(), "events", true);
			card(html, "Debt merged", onScheduler.importanceDebtMerged(), "events", true);
			card(html, "Debt cancelled", onScheduler.importanceDebtCancelled(), "events", true);
			card(html, "Periodic evaluations", onScheduler.periodicEvaluations(), "events", onScheduler.periodicEvaluations() < 500L);
			card(html, "Average evaluation delay", onScheduler.averageEvaluationDelay(), "frames", onScheduler.averageEvaluationDelay() <= 1.0D);
			card(html, "Peak scheduler size", onScheduler.peakSchedulerSize(), "objects", true);
			card(html, "Awaiting promotion", onScheduler.objectsAwaitingPromotion(), "objects", true);
			card(html, "Periodic evaluation CPU", onScheduler.averagePeriodicEvaluationMs(), "ms sampled avg", true);
			card(html, "Debt evaluation CPU", onScheduler.averageDebtEvaluationMs(), "ms sampled avg", true);
			card(html, "Cache fast-path CPU", onScheduler.averageCacheFastPathMs(), "ms sampled avg", true);
			html.append("</div><div class=\"raw\">").append(escape(onScheduler.toLine())).append("</div>");
		}
		html.append("<p><span class=\"pill\">dominant promotion reason: ").append(escape(onBands.mostCommonReason())).append("</span><span class=\"pill\">confidence vetoes: ").append(onBands.lowConfidenceDemotionBlocks()).append("</span><span class=\"pill\">pop-risk vetoes: ").append(onBands.highPopRiskDemotionBlocks()).append("</span><span class=\"pill\">importance debt promotions: ").append(onBands.importanceDebtPromotions()).append("</span></p>");
		html.append("<h2>Stability</h2><div class=\"grid\">");
		card(html, "Entity cull oscillation events", onBands.entityCullOscillationEvents(), "events", onBands.entityCullOscillationEvents() <= 100L);
		card(html, "Entity band transitions", onBands.entityBandTransitions(), "transitions", false);
		card(html, "Transitions per frame", onBands.demotionsPerFrame() + onBands.promotionsPerFrame(), "per frame", (onBands.demotionsPerFrame() + onBands.promotionsPerFrame()) <= 1.0D);
		card(html, "Average band lifetime", onBands.averageBandLifetime(), "frames", onBands.averageBandLifetime() >= 4.0D);
		card(html, "Average ticks in band", onBands.averageTicksInBand(), "ticks", onBands.averageTicksInBand() >= 8.0D);
		card(html, "Skipped stable objects", onBands.skippedStableObjects(), "cache skips", onBands.skippedStableObjects() > onBands.scoreRecomputes());
		card(html, "Score cache hits", onBands.scoreCacheHits(), "hits", onBands.scoreCacheHits() > onBands.scoreRecomputes());
		card(html, "Score recomputes", onBands.scoreRecomputes(), "recomputes", false);
		card(html, "Transition budget used", onBands.bandTransitionBudgetUsed(), "transitions", false);
		card(html, "Transitions deferred", onBands.bandTransitionsDeferred(), "deferred", onBands.bandTransitionsDeferred() > 0L);
		card(html, "Transitions from stable objects", onBands.transitionsFromStableObjects(), "transitions", onBands.transitionsFromStableObjects() == 0L);
		card(html, "Demotions blocked by hysteresis", onBands.demotionsPreventedByHysteresis(), "blocks", false);
		card(html, "Promotions blocked by hysteresis", onBands.promotionsPreventedByHysteresis(), "blocks", false);
		html.append("</div><div class=\"card\"><strong>Top transition pairs</strong><p>")
			.append(escape(onBands.topTransitionPairs())).append("</p><strong>Dirty reasons</strong><p>")
			.append(escape(onBands.dirtyReasons())).append("</p></div>");
		html.append("<h2>Percent Changes</h2><div class=\"grid\">");
		card(html, "FPS percent change", percentChange(offFps, onFps), "%", onFps >= offFps);
		card(html, "1% low percent change", percentChange(offOneLow, onOneLow), "%", onOneLow >= offOneLow);
		card(html, "Worst-frame percent change", percentChange(offWorstFps, onWorstFps), "%", onWorstFps >= offWorstFps);
		card(html, "GPU ms percent change", percentChange(offGpuMs, onGpuMs), "%", onGpuMs <= offGpuMs);
		card(html, "CPU ms percent change", percentChange(offCpuMs, onCpuMs), "%", onCpuMs <= offCpuMs);
		card(html, "Upload count percent change", percentChange(offRenderProfile.bufferUploadCount(), onRenderProfile.bufferUploadCount()), "%", onRenderProfile.bufferUploadCount() <= offRenderProfile.bufferUploadCount());
		card(html, "Texture bind percent change", percentChange(offRenderProfile.textureBindCount(), onRenderProfile.textureBindCount()), "%", onRenderProfile.textureBindCount() <= offRenderProfile.textureBindCount());
		card(html, "Shader bind percent change", percentChange(offRenderProfile.shaderBindCount(), onRenderProfile.shaderBindCount()), "%", onRenderProfile.shaderBindCount() <= offRenderProfile.shaderBindCount());
		card(html, "Proxy band percent change", percentChange(offBands.proxy(), onBands.proxy()), "%", false);
		html.append("</div><h2>Fix Priorities</h2>");
		appendFixPriorities(html, offBands, onBands, offRenderProfile, onRenderProfile,
			offFps, onFps, offOneLow, onOneLow, offWorstFps, onWorstFps);
		html.append("<h2>Regression Warnings</h2><div class=\"card\">");
		appendWarnings(html, offFps, onFps, offOneLow, onOneLow, offWorstFps, onWorstFps, offGpuMs, onGpuMs,
			offCpuMs, onCpuMs, onProfile, offBands, onBands, offRenderProfile, onRenderProfile, historyTrend);
		html.append("</div>");
		appendSelfValidation(html, offFps, onFps, offOneLow, onOneLow, offGpuMs, onGpuMs, offCpuMs, onCpuMs,
			offMetrics, onMetrics, onProfile, offBands, onBands, offRenderProfile, onRenderProfile, cameraStable);
		html.append("<details><summary>Raw benchmark metrics</summary><div class=\"raw\">")
			.append(escape("OFF stats: " + stats(offFrames, offGpuFrames) + "\nON stats: " + stats(onFrames, onGpuFrames)
				+ "\nOFF significance: " + significanceLine(offBands, offMetrics, offScene)
				+ "\nON significance: " + significanceLine(onBands, onMetrics, onScene)
				+ "\nOFF render: " + renderProfileLine(offRenderProfile, offFrames.size())
				+ "\nON render: " + renderProfileLine(onRenderProfile, onFrames.size())
				+ "\nRender delta: " + renderDeltaLine(offRenderProfile, onRenderProfile)
				+ "\nOFF gl tracker: " + glTrackerLine(offGlTracker)
				+ "\nON gl tracker: " + glTrackerLine(onGlTracker)
				+ "\nON diagnostics: " + onDiagnostics))
			.append("</div></details>");
		html.append("</main></body></html>");
		return html.toString();
	}

	private static void card(StringBuilder html, String label, double value, String unit, boolean classified) {
		String lowerLabel = label.toLowerCase(Locale.ROOT);
		String cls = classified ? " good"
			: (lowerLabel.contains("gain") || lowerLabel.contains("delta")
				|| lowerLabel.contains("change") || lowerLabel.contains("overhead") ? " bad" : "");
		html.append("<div class=\"card\"><div class=\"muted\">").append(escape(label)).append("</div><div class=\"value")
			.append(cls).append("\">").append(format(value)).append("</div><div class=\"muted\">")
			.append(escape(unit)).append("</div></div>");
	}

	private static void statusCard(StringBuilder html, String label, String value, boolean good) {
		html.append("<div class=\"card\"><div class=\"muted\">").append(escape(label))
			.append("</div><div class=\"value ").append(good ? "good" : "bad").append("\">")
			.append(escape(value)).append("</div><div class=\"muted\">status</div></div>");
	}

	private static String barChart(String title, String[] labels, double[] values) {
		double max = 0.0D;
		for (double value : values) {
			max = Math.max(max, Math.abs(value));
		}
		max = Math.max(1.0D, max);
		int rowHeight = 28;
		int height = 36 + labels.length * rowHeight;
		StringBuilder svg = new StringBuilder(1024);
		svg.append("<div class=\"chart\"><strong>").append(escape(title)).append("</strong><svg viewBox=\"0 0 720 ")
			.append(height).append("\" role=\"img\">");
		for (int i = 0; i < labels.length; i++) {
			int y = 30 + i * rowHeight;
			double value = values[i];
			int width = (int)Math.round(Math.abs(value) / max * 440.0D);
			String color = value < 0.0D ? "#ff8b73" : "#66d986";
			svg.append("<text x=\"8\" y=\"").append(y + 15).append("\" fill=\"#c8d3df\" font-size=\"12\">")
				.append(escape(labels[i])).append("</text>");
			svg.append("<rect x=\"180\" y=\"").append(y).append("\" width=\"").append(width)
				.append("\" height=\"18\" rx=\"3\" fill=\"").append(color).append("\"></rect>");
			svg.append("<text x=\"").append(190 + width).append("\" y=\"").append(y + 14)
				.append("\" fill=\"#e8edf2\" font-size=\"12\">").append(format(value)).append("</text>");
		}
		svg.append("</svg></div>");
		return svg.toString();
	}

	private static void appendFramePacingDashboard(StringBuilder html, List<Long> offFrameTimes,
			List<Long> onFrameTimes, List<FrameBurstSample> bursts) {
		html.append("<h2>Frame Pacing</h2><div class=\"grid\">");
		card(html, "OFF p95 frame time", percentileNanos(offFrameTimes, 0.95D) / 1_000_000.0D, "ms", false);
		card(html, "ON p95 frame time", percentileNanos(onFrameTimes, 0.95D) / 1_000_000.0D, "ms",
			percentileNanos(onFrameTimes, 0.95D) <= percentileNanos(offFrameTimes, 0.95D));
		card(html, "OFF p99 frame time", percentileNanos(offFrameTimes, 0.99D) / 1_000_000.0D, "ms", false);
		card(html, "ON p99 frame time", percentileNanos(onFrameTimes, 0.99D) / 1_000_000.0D, "ms",
			percentileNanos(onFrameTimes, 0.99D) <= percentileNanos(offFrameTimes, 0.99D));
		FrameBurstSample worst = maxBurst(bursts, BurstMetric.FRAME_TIME);
		FrameBurstSample recompute = maxBurst(bursts, BurstMetric.RECOMPUTES);
		FrameBurstSample upload = maxBurst(bursts, BurstMetric.UPLOADS);
		FrameBurstSample transition = maxBurst(bursts, BurstMetric.TRANSITIONS);
		FrameBurstSample periodic = maxBurst(bursts, BurstMetric.PERIODIC);
		FrameBurstSample proxy = maxBurst(bursts, BurstMetric.PROXY_OPS);
		card(html, "Largest burst frame", frameMs(worst), "ms", false);
		card(html, "Largest upload frame", upload == null ? 0.0D : upload.render.bufferUploads(), "uploads", false);
		card(html, "Largest transition frame", transition == null ? 0.0D : transition.significance.totalTransitions(), "transitions", false);
		card(html, "Largest periodic frame", periodic == null ? 0.0D : periodic.significance.periodicRechecks(), "rechecks", false);
		card(html, "Largest proxy-op frame", proxy == null ? 0.0D : proxy.significance.proxyCreations() + proxy.significance.proxyDestructions(), "proxy ops", false);
		html.append("</div>");
		html.append("<div class=\"grid\">");
		operationCards(html, "Recomputes", bursts, BurstMetric.RECOMPUTES);
		operationCards(html, "Transitions", bursts, BurstMetric.TRANSITIONS);
		operationCards(html, "Uploads", bursts, BurstMetric.UPLOADS);
		operationCards(html, "Proxy ops", bursts, BurstMetric.PROXY_OPS);
		operationCards(html, "Periodic", bursts, BurstMetric.PERIODIC);
		html.append("</div>");
		html.append(barChart("Frame time histogram", new String[]{"<8ms","8-12ms","12-16ms","16-25ms","25-40ms",">40ms"}, frameTimeHistogram(onFrameTimes)));
		html.append(barChart("Transitions per frame", new String[]{"0","1-4","5-16","17-64","65-128",">128"}, burstHistogram(bursts, BurstMetric.TRANSITIONS)));
		html.append(barChart("Score recomputes per frame", new String[]{"0","1-32","33-96","97-192","193-384",">384"}, burstHistogram(bursts, BurstMetric.RECOMPUTES)));
		html.append("<div class=\"card\"><strong>Worst 10 frames</strong><p>")
			.append(escape(worstFramesLine(bursts))).append("</p><strong>Largest contributors</strong><p>")
			.append(escape(contributorLine(worst, recompute, upload, transition, periodic))).append("</p></div>");
	}

	private static double frameMs(FrameBurstSample sample) {
		return sample == null ? 0.0D : sample.frameNanos / 1_000_000.0D;
	}

	private static void operationCards(StringBuilder html, String label, List<FrameBurstSample> bursts, BurstMetric metric) {
		card(html, label + " avg", averageBurst(bursts, metric), "per frame", false);
		card(html, label + " max", maxBurstValue(bursts, metric), "per frame", false);
		card(html, label + " p95", percentileBurst(bursts, metric, 0.95D), "per frame", false);
		card(html, label + " p99", percentileBurst(bursts, metric, 0.99D), "per frame", false);
	}

	private static double percentileNanos(List<Long> frames, double percentile) {
		if (frames.isEmpty()) return 0.0D;
		List<Long> sorted = new ArrayList<>(frames);
		sorted.sort(Long::compareTo);
		int index = Math.min(sorted.size() - 1, Math.max(0, (int)Math.ceil(sorted.size() * percentile) - 1));
		return sorted.get(index);
	}

	private static double[] frameTimeHistogram(List<Long> frames) {
		double[] buckets = new double[6];
		for (long nanos : frames) {
			double ms = nanos / 1_000_000.0D;
			if (ms < 8.0D) buckets[0]++;
			else if (ms < 12.0D) buckets[1]++;
			else if (ms < 16.0D) buckets[2]++;
			else if (ms < 25.0D) buckets[3]++;
			else if (ms < 40.0D) buckets[4]++;
			else buckets[5]++;
		}
		return buckets;
	}

	private static double[] burstHistogram(List<FrameBurstSample> bursts, BurstMetric metric) {
		double[] buckets = new double[6];
		for (FrameBurstSample sample : bursts) {
			long value = metricValue(sample, metric);
			if (value == 0L) buckets[0]++;
			else if (value <= 4L && metric == BurstMetric.TRANSITIONS) buckets[1]++;
			else if (value <= 16L && metric == BurstMetric.TRANSITIONS) buckets[2]++;
			else if (value <= 64L && metric == BurstMetric.TRANSITIONS) buckets[3]++;
			else if (value <= 128L && metric == BurstMetric.TRANSITIONS) buckets[4]++;
			else if (metric == BurstMetric.TRANSITIONS) buckets[5]++;
			else if (value <= 32L) buckets[1]++;
			else if (value <= 96L) buckets[2]++;
			else if (value <= 192L) buckets[3]++;
			else if (value <= 384L) buckets[4]++;
			else buckets[5]++;
		}
		return buckets;
	}

	private static FrameBurstSample maxBurst(List<FrameBurstSample> bursts, BurstMetric metric) {
		FrameBurstSample best = null;
		long bestValue = Long.MIN_VALUE;
		for (FrameBurstSample sample : bursts) {
			long value = metricValue(sample, metric);
			if (value > bestValue) {
				best = sample;
				bestValue = value;
			}
		}
		return best;
	}

	private static double averageBurst(List<FrameBurstSample> bursts, BurstMetric metric) {
		if (bursts.isEmpty()) return 0.0D;
		long total = 0L;
		for (FrameBurstSample sample : bursts) {
			total += metricValue(sample, metric);
		}
		return total / (double)bursts.size();
	}

	private static long maxBurstValue(List<FrameBurstSample> bursts, BurstMetric metric) {
		FrameBurstSample sample = maxBurst(bursts, metric);
		return sample == null ? 0L : metricValue(sample, metric);
	}

	private static double percentileBurst(List<FrameBurstSample> bursts, BurstMetric metric, double percentile) {
		if (bursts.isEmpty()) return 0.0D;
		List<Long> values = new ArrayList<>(bursts.size());
		for (FrameBurstSample sample : bursts) {
			values.add(metricValue(sample, metric));
		}
		values.sort(Long::compareTo);
		int index = Math.min(values.size() - 1, Math.max(0, (int)Math.ceil(values.size() * percentile) - 1));
		return values.get(index);
	}

	private static long metricValue(FrameBurstSample sample, BurstMetric metric) {
		return switch (metric) {
			case FRAME_TIME -> sample.frameNanos;
			case RECOMPUTES -> sample.significance.scoreRecomputes();
			case TRANSITIONS -> sample.significance.totalTransitions();
			case UPLOADS -> sample.render.bufferUploads();
			case PROXY_OPS -> sample.significance.proxyCreations() + sample.significance.proxyDestructions();
			case PERIODIC -> sample.significance.periodicRechecks();
		};
	}

	private static String worstFramesLine(List<FrameBurstSample> bursts) {
		if (bursts.isEmpty()) return "none";
		List<FrameBurstSample> sorted = new ArrayList<>(bursts);
		sorted.sort((left, right) -> Long.compare(right.frameNanos, left.frameNanos));
		StringBuilder builder = new StringBuilder();
		int count = Math.min(10, sorted.size());
		for (int i = 0; i < count; i++) {
			FrameBurstSample sample = sorted.get(i);
			if (i > 0) builder.append(" | ");
			builder.append("frame=").append(sample.significance.frameIndex())
				.append(",ms=").append(format(sample.frameNanos / 1_000_000.0D))
				.append(",recomputes=").append(sample.significance.scoreRecomputes())
				.append(",transitions=").append(sample.significance.totalTransitions())
				.append(",uploads=").append(sample.render.bufferUploads())
				.append(",proxyOps=").append(sample.significance.proxyCreations() + sample.significance.proxyDestructions())
				.append(",deferred=").append(sample.deferredWork())
				.append(",periodic=").append(sample.significance.periodicRechecks())
				.append(",dirty=").append(sample.significance.dirtyObjects());
		}
		return builder.toString();
	}

	private static String contributorLine(FrameBurstSample worst, FrameBurstSample recompute,
			FrameBurstSample upload, FrameBurstSample transition, FrameBurstSample periodic) {
		if (worst == null) return "none";
		return "worstFrame=" + burstSummary(worst)
			+ " | maxRecompute=" + burstSummary(recompute)
			+ " | maxUpload=" + burstSummary(upload)
			+ " | maxTransition=" + burstSummary(transition)
			+ " | maxPeriodic=" + burstSummary(periodic)
			+ " | likelyWorstContributor=" + likelyContributor(worst);
	}

	private static String burstSummary(FrameBurstSample sample) {
		if (sample == null) return "none";
		return "frame " + sample.significance.frameIndex()
			+ " ms=" + format(sample.frameNanos / 1_000_000.0D)
			+ " recomputes=" + sample.significance.scoreRecomputes()
			+ " transitions=" + sample.significance.totalTransitions()
			+ " proxyOps=" + (sample.significance.proxyCreations() + sample.significance.proxyDestructions())
			+ " uploads=" + sample.render.bufferUploads()
			+ " uploadMs=" + format(sample.render.bufferUploadMs())
			+ " periodic=" + sample.significance.periodicRechecks();
	}

	private static String likelyContributor(FrameBurstSample sample) {
		long recomputes = sample.significance.scoreRecomputes();
		long transitions = sample.significance.totalTransitions();
		long uploads = sample.render.bufferUploads();
		long periodic = sample.significance.periodicRechecks();
		long proxyOps = sample.significance.proxyCreations() + sample.significance.proxyDestructions();
		if (uploads > 0L && sample.render.bufferUploadMs() >= 1.0D) return "buffer uploads";
		if (proxyOps >= transitions && proxyOps > 0L) return "proxy band churn";
		if (recomputes >= transitions && recomputes >= periodic && recomputes > 0L) return "score recomputes";
		if (transitions >= periodic && transitions > 0L) return "band transitions";
		if (periodic > 0L) return "periodic rechecks";
		return "external renderer or uninstrumented work";
	}

	private static void appendFixPriorities(StringBuilder html, OptiminiumVisualSignificance.Snapshot offBands,
			OptiminiumVisualSignificance.Snapshot onBands, OptiminiumRenderProfiler.Snapshot offRenderProfile,
			OptiminiumRenderProfiler.Snapshot onRenderProfile, double offFps, double onFps,
			double offOneLow, double onOneLow, double offWorstFps, double onWorstFps) {
		html.append("<div class=\"grid\">");
		int count = 0;
		long interactionDirtyCount = dirtyReasonCount(onBands.dirtyReasons(), "interaction");
		if (interactionDirtyCount > 10_000L && (onOneLow < offOneLow || onWorstFps < offWorstFps)) {
			priority(html, "High", "Interaction dirty trigger likely misclassified",
				"interaction dirty count=" + interactionDirtyCount
					+ ", scoreRecomputes=" + onBands.scoreRecomputes()
					+ ", scoreCacheHits=" + onBands.scoreCacheHits()
					+ ", 1% low change=" + format(percentChange(offOneLow, onOneLow)) + "%"
					+ ", worst-frame change=" + format(percentChange(offWorstFps, onWorstFps)) + "%",
				"Passive visibility, importance, or tracking is probably being counted as player interaction.",
				"Reserve interaction dirty state for actual use/attack targets and move passive changes to specific dirty reasons.");
			count++;
		}
		if (onBands.entityCullOscillationEvents() > 100L && (onOneLow < offOneLow || onWorstFps < offWorstFps)) {
			priority(html, "High", "Band oscillation causing frame pacing regression",
				"entityCullOscillationEvents=" + onBands.entityCullOscillationEvents()
					+ ", topTransitionPairs=" + onBands.topTransitionPairs()
					+ ", transitionsFromStableObjects=" + onBands.transitionsFromStableObjects()
					+ ", 1% low change=" + format(percentChange(offOneLow, onOneLow)) + "%"
					+ ", worst-frame change=" + format(percentChange(offWorstFps, onWorstFps)) + "%"
					+ ", average FPS change=" + format(percentChange(offFps, onFps)) + "%",
				"Objects are rapidly crossing adjacent weighted-attention bands, especially FULL/THROTTLED.",
				"Keep stable objects on cached bands and only re-enter weighted attention from dirty or periodic triggers.");
			count++;
		}
		if (onBands.transitionsFromStableObjects() > 0L) {
			priority(html, "High", "Stable objects still changing bands",
				"transitionsFromStableObjects=" + onBands.transitionsFromStableObjects()
					+ ", dirtyReasons=" + onBands.dirtyReasons(),
				"At least one band assignment path is bypassing dirty-state gating.",
				"Route every band change through the dirty transition function and keep clean objects on cached bands.");
			count++;
		}
		if (onBands.scoreCacheHits() > 0L && onBands.scoreRecomputes() >= onBands.scoreCacheHits()) {
			priority(html, "Medium", "Score recomputes too close to cache hits",
				"scoreCacheHits=" + onBands.scoreCacheHits() + ", scoreRecomputes=" + onBands.scoreRecomputes()
					+ ", dirtyReasons=" + onBands.dirtyReasons(),
				"Dirty triggers are too broad or periodic reevaluation is too frequent.",
				"Tighten dirty triggers so stationary, off-center, low-importance objects reuse cached scores longer.");
			count++;
		}
		if (onBands.bandTransitionsDeferred() > 0L) {
			priority(html, "Low", "Transition budget saturated",
				"bandTransitionsDeferred=" + onBands.bandTransitionsDeferred()
					+ ", bandTransitionBudgetUsed=" + onBands.bandTransitionBudgetUsed(),
				"More non-urgent objects requested transitions than the per-frame budget allowed.",
				"Check whether deferred transitions are mostly low-risk demotions before increasing the budget.");
			count++;
		}
		long confidenceSamples = onBands.confidenceBucketVeryLow() + onBands.confidenceBucketLow()
			+ onBands.confidenceBucketMedium() + onBands.confidenceBucketHigh() + onBands.confidenceBucketVeryHigh();
		if (confidenceSamples > 0L && onBands.confidenceBucketVeryLow() * 100L / confidenceSamples >= 90L) {
			priority(html, "High", "Confidence collapsed into lowest bucket",
				"avg=" + format(onBands.averageConfidence()) + ", min=" + format(onBands.minConfidence())
					+ ", max=" + format(onBands.maxConfidence()) + ", buckets=[" + onBands.confidenceBucketVeryLow()
					+ "," + onBands.confidenceBucketLow() + "," + onBands.confidenceBucketMedium() + ","
					+ onBands.confidenceBucketHigh() + "," + onBands.confidenceBucketVeryHigh() + "]",
				"Confidence target is over-penalized or self-reinforcing near the floor.",
				"Rebalance confidence inputs so stable, far, low-risk objects can leave the floor.");
			count++;
		}
		if (onBands.demotionsAllowedBecauseHighConfidence() == 0L
				&& (onBands.decisionBecauseConfidenceVeto() > 0L || onBands.lowConfidenceDemotionBlocks() > 0L)) {
			priority(html, "High", "Confidence only acting as veto",
				"demotionsAllowedBecauseHighConfidence=0, confidence vetoes=" + onBands.decisionBecauseConfidenceVeto(),
				"High-confidence threshold is unreachable or confidence is checked only as a brake.",
				"Let high-confidence, low-attention, low-pop-risk candidates shorten demotion hold or reach proxy/reuse.");
			count++;
		}
		if (onBands.averagePopRisk() > 0.50D && onBands.decisionBecausePopRiskVeto() == 0L) {
			priority(html, "High", "Pop-risk veto inactive despite high pop risk",
				"avgPopRisk=" + format(onBands.averagePopRisk()) + ", decisionBecausePopRiskVeto=0",
				"Earlier gates are likely preventing the pop-risk stage from observing risky demotions.",
				"Run pop-risk veto before lower-priority confidence/hysteresis gates for demotion candidates.");
			count++;
		}
		double textureBindChange = percentChange(offRenderProfile.textureBindCount(), onRenderProfile.textureBindCount());
		if (textureBindChange > 5.0D) {
			priority(html, textureBindChange > 10.0D ? "Medium" : "Low", "Texture binds increased",
				"textureBindCount " + offRenderProfile.textureBindCount() + " -> " + onRenderProfile.textureBindCount()
					+ " (" + format(textureBindChange) + "%)",
				"Optiminium-owned warmup, fade, overlay, or proxy paths may be triggering extra texture state changes.",
				"Avoid unchanged/redundant Optiminium-owned texture work; do not cache global GL state.");
			count++;
		}
		double shaderBindChange = percentChange(offRenderProfile.shaderBindCount(), onRenderProfile.shaderBindCount());
		if (shaderBindChange > 5.0D) {
			priority(html, shaderBindChange > 10.0D ? "Medium" : "Low", "Shader binds increased",
				"shaderBindCount " + offRenderProfile.shaderBindCount() + " -> " + onRenderProfile.shaderBindCount()
					+ " (" + format(shaderBindChange) + "%)",
				"Optiminium-owned debug, overlay, fade, or proxy rendering may be causing extra shader transitions.",
				"Batch Optiminium-owned work by existing RenderType where correctness allows.");
			count++;
		}
		double uploadChange = percentChange(offRenderProfile.bufferUploadCount(), onRenderProfile.bufferUploadCount());
		if (uploadChange > 5.0D) {
			priority(html, uploadChange > 10.0D ? "Medium" : "Low", "Upload count increased",
				"bufferUploadCount " + offRenderProfile.bufferUploadCount() + " -> " + onRenderProfile.bufferUploadCount()
					+ " (" + format(uploadChange) + "%)",
				"Optiminium-owned resource warmup or temporary buffers may be uploading unchanged work.",
				"Skip redundant Optiminium-owned uploads and reuse valid resources; do not alter terrain chunk scheduling.");
			count++;
		}
		if (count == 0) {
			html.append("<div class=\"card\"><strong>No ranked fix priorities</strong><p class=\"muted\">No automatic tuning issue crossed the configured thresholds.</p></div>");
		}
		html.append("</div>");
	}

	private static void appendBeCacheCards(StringBuilder html, String onDiagnostics) {
		if (onDiagnostics == null || onDiagnostics.isEmpty()) return;
		// Parse cache stats from the embedded diagnostic line
		int startIdx = onDiagnostics.indexOf("blockEntityRenderCache=");
		if (startIdx < 0) return;
		String cacheDiag = onDiagnostics.substring(startIdx);
		// Extract values using simple parsing
		int cacheSize = (int)tryParseDiagLong(cacheDiag, "beCacheSize=");
		double hitRate = tryParseDiagDouble(cacheDiag, "beCacheHitRate=");
		long hits = tryParseDiagLong(cacheDiag, "beCacheHits=");
		long misses = tryParseDiagLong(cacheDiag, "beCacheMisses=");
		long invalidations = tryParseDiagLong(cacheDiag, "beCacheInvalidations=");
		double avgLifetime = tryParseDiagDouble(cacheDiag, "beAvgLifetimeFrames=");
		double avgReuses = tryParseDiagDouble(cacheDiag, "beAvgReuses=");
		String topInval = extractDiagValue(cacheDiag, "beTopInvalidation=");
		int unstableTypes = (int)tryParseDiagLong(cacheDiag, "beUnstableTypes=");

		card(html, "BE Cache Hit Rate", hitRate, "%", hitRate > 80.0D);
		card(html, "BE Cache Size", cacheSize, "entries", false);
		card(html, "BE Cache Hits", (double)hits, "hits", false);
		card(html, "BE Cache Misses", (double)misses, "misses", misses < hits);
		card(html, "BE Cache Invalidations", (double)invalidations, "invalidations", invalidations < misses / 2L);
		card(html, "BE Avg Entry Lifetime", avgLifetime, "frames", avgLifetime >= 10.0D);
		card(html, "BE Avg Reuses/Entry", avgReuses, "reuses", avgReuses >= 2.0D);
		if (!"none".equals(topInval) && !topInval.isEmpty()) {
			html.append("<div class=\"card\"><div class=\"muted\">Top Invalidation Reason</div><div class=\"warn\" style=\"font-size:14px;word-break:break-all\">")
				.append(escape(topInval)).append("</div></div>");
		}
		card(html, "Unstable BE Types", unstableTypes, "types", unstableTypes <= 0);
	}

	private static void appendGlTrackerCards(StringBuilder html,
			OptiminiumGlStateTracker.DiagnosticSnapshot offTracker,
			OptiminiumGlStateTracker.DiagnosticSnapshot onTracker) {
		html.append("<div class=\"card\"><div class=\"muted\">OpenGL tweaks</div><div class=\"value\">")
			.append(onTracker.openGlTweaksEnabled() ? "ON" : "OFF")
			.append("</div><div class=\"muted\">mode=")
			.append(escape(onTracker.mode())).append("</div></div>");
		html.append("<div class=\"card\"><div class=\"muted\">Auto disabled</div><div class=\"value\">")
			.append(onTracker.glAutoDisabled() ? "YES" : "NO")
			.append("</div><div class=\"muted\">")
			.append(escape(onTracker.glAutoDisableReason())).append("</div></div>");
		card(html, "Texture bind requests", (double)onTracker.textureBindRequests(), "requests", false);
		card(html, "Texture potential skips", (double)onTracker.textureBindPotentialSkipped(), "potential", onTracker.textureBindPotentialSkipped() > 0L);
		card(html, "Texture relaxed potential skips", (double)onTracker.textureRelaxedPotentialSkipped(), "potential", onTracker.textureRelaxedPotentialSkipped() > onTracker.textureBindPotentialSkipped());
		card(html, "Texture actual skips", (double)onTracker.textureBindSkipped(), "skipped", onTracker.textureBindSkipped() > 0L);
		card(html, "Texture potential skip rate", (double)onTracker.textureBindPotentialSkippedPercent(), "%", onTracker.textureBindPotentialSkippedPercent() > 0L);
		card(html, "Texture actual skip rate", (double)onTracker.textureBindSkippedPercent(), "%", onTracker.textureBindSkippedPercent() > 0L);
		card(html, "Shader bind requests", (double)onTracker.shaderBindRequests(), "requests", false);
		card(html, "Shader potential skips", (double)onTracker.shaderBindPotentialSkipped(), "potential", onTracker.shaderBindPotentialSkipped() > 0L);
		card(html, "Shader relaxed potential skips", (double)onTracker.shaderRelaxedPotentialSkipped(), "potential", onTracker.shaderRelaxedPotentialSkipped() > onTracker.shaderBindPotentialSkipped());
		card(html, "Shader actual skips", (double)onTracker.shaderBindSkipped(), "skipped", onTracker.shaderBindSkipped() > 0L);
		card(html, "Shader potential skip rate", (double)onTracker.shaderBindPotentialSkippedPercent(), "%", onTracker.shaderBindPotentialSkippedPercent() > 0L);
		card(html, "Shader actual skip rate", (double)onTracker.shaderBindSkippedPercent(), "%", onTracker.shaderBindSkippedPercent() > 0L);
		card(html, "Conservative potential skips", (double)onTracker.conservativePotentialSkips(), "potential", onTracker.conservativePotentialSkips() > 0L);
		card(html, "Relaxed potential skips", (double)onTracker.relaxedPotentialSkips(), "potential", onTracker.relaxedPotentialSkips() > onTracker.conservativePotentialSkips());
		card(html, "Actual skips", (double)onTracker.actualSkips(), "skipped", onTracker.actualSkips() > 0L);
		card(html, "Combined skip rate", (double)onTracker.skipRatePercent(), "%", onTracker.skipRatePercent() > 0L);
		card(html, "Tracker invalidations", (double)onTracker.trackerInvalidations(), "invalidations", false);
		card(html, "Framebuffer invalidations", (double)onTracker.framebufferInvalidations(), "invalidations", false);
		card(html, "Resource reload invalidations", (double)onTracker.resourceReloadInvalidations(), "invalidations", onTracker.resourceReloadInvalidations() == 0L);
		card(html, "World unload invalidations", (double)onTracker.worldUnloadInvalidations(), "invalidations", onTracker.worldUnloadInvalidations() == 0L);
		card(html, "Unknown external invalidations", (double)onTracker.unknownExternalInvalidations(), "invalidations", onTracker.unknownExternalInvalidations() == 0L);
		card(html, "Active texture unit misses", (double)onTracker.activeTextureUnitMismatches(), "misses", onTracker.activeTextureUnitMismatches() == 0L);
		card(html, "Texture target misses", (double)onTracker.textureTargetMismatches(), "misses", onTracker.textureTargetMismatches() == 0L);
		card(html, "Texture id misses", (double)onTracker.textureIdMismatches(), "misses", onTracker.textureIdMismatches() == 0L);
		card(html, "Shader id misses", (double)onTracker.shaderIdMismatches(), "misses", onTracker.shaderIdMismatches() == 0L);
		card(html, "GL errors detected", (double)onTracker.glErrorsDetected(), "errors", onTracker.glErrorsDetected() == 0L);
		html.append("<div class=\"card\"><div class=\"muted\">Hook order</div><div class=\"value\">")
			.append(escape(onTracker.hookOrder())).append("</div><div class=\"muted\">prevTex=")
			.append(onTracker.observedPreviousTextureId()).append(", reqTex=").append(onTracker.requestedTextureId())
			.append(", prevShader=").append(onTracker.observedPreviousShaderId()).append(", reqShader=")
			.append(onTracker.requestedShaderId()).append("</div></div>");
		html.append("<div class=\"card\"><div class=\"muted\">Top invalidation reason</div><div class=\"warn\" style=\"font-size:14px;word-break:break-all\">")
			.append(escape(onTracker.topInvalidationReason())).append("</div></div>");
		html.append("<div class=\"card\"><div class=\"muted\">Top no-skip reason</div><div class=\"warn\" style=\"font-size:14px;word-break:break-all\">")
			.append(escape(onTracker.topNoSkipReason())).append("</div></div>");
		html.append("<div class=\"card\"><div class=\"muted\">Compatibility skip disabled</div><div class=\"value\">")
			.append(onTracker.compatibilitySkipDisabled()).append("</div></div>");
		if (onTracker.relaxedPotentialSkips() > onTracker.conservativePotentialSkips()) {
			html.append("<div class=\"card\"><strong class=\"warn\">Framebuffer invalidation suppresses possible skips</strong><p class=\"muted\">relaxedPotentialSkips=")
				.append(onTracker.relaxedPotentialSkips()).append(", conservativePotentialSkips=")
				.append(onTracker.conservativePotentialSkips()).append("</p></div>");
		}
		// Delta vs OFF
		long deltaTexSkip = onTracker.textureBindSkipped() - offTracker.textureBindSkipped();
		long deltaShSkip = onTracker.shaderBindSkipped() - offTracker.shaderBindSkipped();
		card(html, "Texture skip delta (ON-OFF)", (double)deltaTexSkip, "skipped", deltaTexSkip >= 0L);
		card(html, "Shader skip delta (ON-OFF)", (double)deltaShSkip, "skipped", deltaShSkip >= 0L);
	}

	private static String extractDiagValue(String diag, String prefix) {
		int start = diag.indexOf(prefix);
		if (start < 0) return "";
		start += prefix.length();
		int end = diag.indexOf(',', start);
		if (end < 0) return diag.substring(start).trim();
		String raw = diag.substring(start, end).trim();
		if (raw.endsWith("%")) raw = raw.substring(0, raw.length() - 1);
		return raw;
	}

	private static long tryParseDiagLong(String diag, String prefix) {
		String raw = extractDiagValue(diag, prefix);
		try {
			return Long.parseLong(raw);
		} catch (NumberFormatException e) {
			return 0L;
		}
	}

	private static double tryParseDiagDouble(String diag, String prefix) {
		String raw = extractDiagValue(diag, prefix);
		try {
			return Double.parseDouble(raw);
		} catch (NumberFormatException e) {
			return 0.0D;
		}
	}

	private static void appendSelfValidation(StringBuilder html, double offFps, double onFps,
			double offOneLow, double onOneLow, double offGpuMs, double onGpuMs,
			double offCpuMs, double onCpuMs,
			OptiminiumMetrics.Snapshot offMetrics, OptiminiumMetrics.Snapshot onMetrics,
			OptiminiumGpuOptimizer.ProfileSnapshot onProfile,
			OptiminiumVisualSignificance.Snapshot offBands, OptiminiumVisualSignificance.Snapshot onBands,
			OptiminiumRenderProfiler.Snapshot offRenderProfile,
			OptiminiumRenderProfiler.Snapshot onRenderProfile, boolean cameraStable) {
		html.append("<h2>Self-Validation</h2><div class=\"grid\">");
		int passes = 0;
		int failures = 0;
		// 1. Significance engine was active during the ON phase
		boolean significanceActive = onBands.full() + onBands.throttled() + onBands.reused()
			+ onBands.proxy() + onBands.culled() > 0L;
		html.append("<div class=\"card\"><strong>Significance engine active</strong><br><span class=\"")
			.append(significanceActive ? "good\">PASS" : "bad\">FAIL")
			.append("</span><p class=\"muted\">ON significance band total: ")
			.append(onBands.full() + onBands.throttled() + onBands.reused() + onBands.proxy() + onBands.culled())
			.append(" (OFF: ")
			.append(offBands.full() + offBands.throttled() + offBands.reused() + offBands.proxy() + offBands.culled())
			.append(")</p></div>");
		if (significanceActive) passes++; else failures++;

		// 2. OFF phase should not run Visual Significance work.
		boolean offHasBandData = offBands.full() + offBands.throttled() + offBands.reused()
			+ offBands.proxy() + offBands.culled() > 0L;
		html.append("<div class=\"card\"><strong>OFF phase significance inactive</strong><br><span class=\"")
			.append(!offHasBandData ? "good\">PASS" : "warn\">WARN (significance recorded during OFF)")
			.append("</span></div>");
		if (!offHasBandData) passes++; else failures++;

		// 3. Camera stability check
		html.append("<div class=\"card\"><strong>Camera stability</strong><br><span class=\"")
			.append(cameraStable ? "good\">PASS" : "warn\">WARN (camera moved during benchmark)")
			.append("</span><p class=\"muted\">cameraStable=").append(cameraStable).append("</p></div>");
		if (cameraStable) passes++; else failures++;

		// 4. Optiminium CPU overhead within budget
		boolean cpuOverheadOk = onProfile.totalOptiminiumCpuMs() <= 0.35D;
		html.append("<div class=\"card\"><strong>CPU overhead budget</strong><br><span class=\"")
			.append(cpuOverheadOk ? "good\">PASS" : "warn\">WARN (above 0.35 ms/frame)")
			.append("</span><p class=\"muted\">totalOptiminiumCpuMs=")
			.append(format(onProfile.totalOptiminiumCpuMs())).append(" ms/frame</p></div>");
		if (cpuOverheadOk) passes++; else failures++;

		// 5. FPS data collected for both phases
		boolean hasOffFps = offFps > 0.0D;
		boolean hasOnFps = onFps > 0.0D;
		boolean bothPhasesCollected = hasOffFps && hasOnFps;
		html.append("<div class=\"card\"><strong>Both phases collected data</strong><br><span class=\"")
			.append(bothPhasesCollected ? "good\">PASS" : "bad\">FAIL")
			.append("</span><p class=\"muted\">OFF FPS: ").append(format(offFps))
			.append(", ON FPS: ").append(format(onFps)).append("</p></div>");
		if (bothPhasesCollected) passes++; else failures++;

		// 6. Score recomputes vs cache hits balance
		boolean cacheHealthy = onBands.scoreRecomputes() > 0L && onBands.scoreCacheHits() > 0L;
		html.append("<div class=\"card\"><strong>Score cache health</strong><br><span class=\"")
			.append(cacheHealthy ? "good\">PASS" : "warn\">WARN (no score cache activity)")
			.append("</span><p class=\"muted\">cacheHits=").append(onBands.scoreCacheHits())
			.append(", recomputes=").append(onBands.scoreRecomputes()).append("</p></div>");
		if (cacheHealthy) passes++; else failures++;

		// 7. GPU timer data collected
		boolean hasGpuData = onGpuMs > 0.0D || offGpuMs > 0.0D;
		html.append("<div class=\"card\"><strong>GPU timer data</strong><br><span class=\"")
			.append(hasGpuData ? "good\">PASS" : "warn\">WARN (no GPU timing available)")
			.append("</span><p class=\"muted\">OFF GPU ms: ").append(format(offGpuMs))
			.append(", ON GPU ms: ").append(format(onGpuMs)).append("</p></div>");
		if (hasGpuData) passes++; else failures++;

		// 8. Culling active — at least one prevented metric
		boolean cullingActive = onMetrics.hiddenParticles() > 0L || onMetrics.culledBlockEntityRenders() > 0L
			|| onMetrics.culledEntityRenders() > 0L;
		html.append("<div class=\"card\"><strong>Culling metrics</strong><br><span class=\"")
			.append(cullingActive ? "good\">PASS" : "warn\">WARN (no culling detected)")
			.append("</span><p class=\"muted\">particlesPrevented=").append(onMetrics.hiddenParticles())
			.append(", blockEntities=").append(onMetrics.culledBlockEntityRenders())
			.append(", entities=").append(onMetrics.culledEntityRenders()).append("</p></div>");
		if (cullingActive) passes++; else failures++;

		// 9. Render profiling active
		boolean renderProfilingActive = onRenderProfile.textureBindCount() > 0L
			|| onRenderProfile.shaderBindCount() > 0L;
		html.append("<div class=\"card\"><strong>Render profiling</strong><br><span class=\"")
			.append(renderProfilingActive ? "good\">PASS" : "warn\">WARN (no render profile data)")
			.append("</span><p class=\"muted\">textureBinds=").append(onRenderProfile.textureBindCount())
			.append(", shaderBinds=").append(onRenderProfile.shaderBindCount()).append("</p></div>");
		if (renderProfilingActive) passes++; else failures++;

		// 10. Band transitions acting (non-zero decisions from engine)
		boolean engineActive = onBands.decisionBecauseWeightedScore()
			+ onBands.decisionBecausePopRiskVeto()
			+ onBands.decisionBecauseConfidenceVeto()
			+ onBands.decisionBecauseSafetyOverride()
			+ onBands.decisionBecauseRecentlyVisible() > 0L;
		html.append("<div class=\"card\"><strong>Decision engine firing</strong><br><span class=\"")
			.append(engineActive ? "good\">PASS" : "warn\">WARN (no decisions recorded)")
			.append("</span><p class=\"muted\">decisions: ").append(onBands.decisionBecauseWeightedScore())
			.append(" weighted, ").append(onBands.decisionBecausePopRiskVeto())
			.append(" pop-risk, ").append(onBands.decisionBecauseConfidenceVeto())
			.append(" confidence, ").append(onBands.decisionBecauseSafetyOverride())
			.append(" safety, ").append(onBands.decisionBecauseRecentlyVisible())
			.append(" recent-visibility</p></div>");
		if (engineActive) passes++; else failures++;

		html.append("</div><div class=\"card\"><strong>Validation summary: ")
			.append(passes).append("/").append(passes + failures)
			.append(" checks passed</strong>");
		if (failures == 0) {
			html.append("<p class=\"good\">All self-validation checks passed — benchmark data is trustworthy.</p>");
		} else {
			html.append("<p class=\"warn\">").append(failures)
				.append(" check(s) failed or warned — review individual results above.</p>");
		}
		html.append("</div>");
	}

	private static void priority(StringBuilder html, String severity, String title, String evidence, String likelyCause, String nextFix) {
		String cls = "High".equals(severity) ? "bad" : ("Medium".equals(severity) ? "warn" : "muted");
		html.append("<div class=\"card\"><strong class=\"").append(cls).append("\">").append(escape(severity))
			.append("</strong><h3>").append(escape(title)).append("</h3><p><strong>Evidence:</strong> ")
			.append(escape(evidence)).append("</p><p><strong>Likely cause:</strong> ")
			.append(escape(likelyCause)).append("</p><p><strong>Recommended next fix:</strong> ")
			.append(escape(nextFix)).append("</p></div>");
	}

	private static void appendWarnings(StringBuilder html, double offFps, double onFps,
			double offOneLow, double onOneLow, double offWorstFps, double onWorstFps,
			double offGpuMs, double onGpuMs, double offCpuMs, double onCpuMs,
			OptiminiumGpuOptimizer.ProfileSnapshot onProfile,
			OptiminiumVisualSignificance.Snapshot offBands, OptiminiumVisualSignificance.Snapshot onBands,
			OptiminiumRenderProfiler.Snapshot offRenderProfile, OptiminiumRenderProfiler.Snapshot onRenderProfile,
			BenchmarkHistoryTrend historyTrend) {
		int warnings = 0;
		warnings += warning(html, !cameraStable, "High severity: benchmark invalid because the camera moved during the run.");
		warnings += warning(html, historyTrend.hasBaseline() && !historyTrend.repeatable(), "Latest FPS gain is an outlier versus recent valid benchmark history.");
		warnings += warning(html, onFps < offFps, "FPS gain is negative.");
		warnings += warning(html, onOneLow < offOneLow, "1% low FPS gain is negative.");
		warnings += warning(html, onWorstFps < offWorstFps, "High severity: worst-frame FPS gain is negative.");
		warnings += warning(html, onGpuMs > offGpuMs, "GPU frame time increased.");
		warnings += warning(html, onCpuMs > offCpuMs, "CPU frame time increased.");
		warnings += warning(html, onProfile.totalOptiminiumCpuMs() > 0.20D, "Optiminium CPU overhead is above 0.20 ms/frame.");
		warnings += warning(html, onBands.entityCullOscillationEvents() > offBands.entityCullOscillationEvents(), "Entity cull oscillation events increased.");
		warnings += warning(html, onBands.entityCullOscillationEvents() > 100L, "High severity: entityCullOscillationEvents is above 100.");
		warnings += warning(html, dirtyReasonCount(onBands.dirtyReasons(), "interaction") > 10_000L, "High severity: interaction dirty trigger likely misclassified.");
		warnings += warning(html, onBands.transitionsFromStableObjects() > 0L, "High severity: transitionsFromStableObjects is above 0.");
		warnings += warning(html, onBands.scoreCacheHits() > 0L && onBands.scoreRecomputes() >= onBands.scoreCacheHits(), "Score recomputes are close to or above score cache hits.");
		warnings += warning(html, onBands.bandTransitionsDeferred() > 0L, "Band transition budget deferred one or more non-urgent transitions.");
		warnings += warning(html, onBands.averageBandLifetime() > 0.0D && onBands.averageBandLifetime() < 4.0D, "Medium severity: average band lifetime is below 4 frames.");
		warnings += warning(html, onBands.importantButCulled() > 0L, "Important objects were culled.");
		warnings += warning(html, onBands.demotionsAllowedBecauseHighConfidence() == 0L, "demotionsAllowedBecauseHighConfidence remains 0.");
		warnings += warning(html, onRenderProfile.suspectedGlStallFrames() > offRenderProfile.suspectedGlStallFrames(), "Suspected GL stalls increased.");
		warnings += warning(html, increasedSignificantly(offRenderProfile.textureBindCount(), onRenderProfile.textureBindCount()), "Texture binds increased significantly.");
		warnings += warning(html, increasedSignificantly(offRenderProfile.shaderBindCount(), onRenderProfile.shaderBindCount()), "Shader binds increased significantly.");
		warnings += warning(html, increasedSignificantly(offRenderProfile.bufferUploadCount(), onRenderProfile.bufferUploadCount()), "Buffer upload count increased significantly.");
		if (warnings == 0) {
			html.append("<p class=\"good\">No automatic regression warnings triggered.</p>");
		}
	}

	private static int warning(StringBuilder html, boolean condition, String message) {
		if (!condition) return 0;
		html.append("<p class=\"warn\">").append(escape(message)).append("</p>");
		return 1;
	}

	private static long dirtyReasonCount(String dirtyReasons, String reason) {
		if (dirtyReasons == null || dirtyReasons.isEmpty() || "none".equals(dirtyReasons)) return 0L;
		String prefix = reason + ":";
		int start = dirtyReasons.indexOf(prefix);
		if (start < 0) return 0L;
		start += prefix.length();
		int end = dirtyReasons.indexOf('|', start);
		if (end < 0) end = dirtyReasons.length();
		try {
			return Long.parseLong(dirtyReasons.substring(start, end));
		} catch (NumberFormatException exception) {
			return 0L;
		}
	}

	private static boolean increasedSignificantly(long offValue, long onValue) {
		return onValue > offValue && percentChange(offValue, onValue) > 10.0D;
	}

	private static String inactiveMetricDetection(OptiminiumMetrics.Snapshot offMetrics, OptiminiumMetrics.Snapshot onMetrics,
			OptiminiumVisualSignificance.Snapshot offBands, OptiminiumVisualSignificance.Snapshot onBands) {
		StringBuilder sb = new StringBuilder();
		// Culling metrics that should have increased (more culling = good when enabled)
		if (onMetrics.culledEntityRenders() <= offMetrics.culledEntityRenders() && onMetrics.culledEntityRenders() == 0L) {
			sb.append("|entityCulling:inactive");
		}
		// Significance engine counters
		if (onBands.culled() == 0L && onBands.full() + onBands.throttled() + onBands.reused() + onBands.proxy() > 0L) {
			sb.append("|significanceCulling:noCulls");
		}
		if (onBands.full() + onBands.throttled() + onBands.reused() + onBands.proxy() + onBands.culled() == 0L) {
			sb.append("|significanceEngine:noActivity");
		}
		if (onBands.scoreRecomputes() == 0L) {
			sb.append("|scoreRecomputes:none");
		}
		if (onBands.decisionBecauseWeightedScore() == 0L && onBands.decisionBecausePopRiskVeto() == 0L
				&& onBands.decisionBecauseConfidenceVeto() == 0L) {
			sb.append("|decisionEngine:noDecisions");
		}
		String result = sb.length() > 0 ? sb.substring(1) : "";
		return result.isEmpty() ? "none" : result;
	}

	private static double estimatedFpsGain(double fpsGain, long prevented, long totalPrevented) {
		if (fpsGain <= 0.0D || prevented <= 0L || totalPrevented <= 0L) {
			return 0.0D;
		}
		return fpsGain * prevented / (double)totalPrevented;
	}

	private static double averageFps(List<Long> frames) {
		double average = stableAverageFrameNanos(frames);
		return average <= 0.0D ? 0.0D : 1_000_000_000.0D / average;
	}

	private static String fpsEstimatorLabel(List<Long> offFrameTimes, List<Long> onFrameTimes) {
		return medianSliceAverageNanos(offFrameTimes) > 0.0D && medianSliceAverageNanos(onFrameTimes) > 0.0D
			? "median slices" : "trimmed mean";
	}

	private static double stableAverageFrameNanos(List<Long> frames) {
		double medianSlice = medianSliceAverageNanos(frames);
		return medianSlice > 0.0D ? medianSlice : averageNanosOrZero(frames);
	}

	private static double medianSliceAverageNanos(List<Long> frames) {
		if (frames.isEmpty()) {
			return 0.0D;
		}
		List<Double> slices = new ArrayList<>();
		long sliceTotal = 0L;
		int sliceFrames = 0;
		for (long frame : frames) {
			sliceTotal += frame;
			sliceFrames++;
			if (sliceTotal >= FPS_SLICE_NANOS) {
				slices.add(sliceTotal / (double)sliceFrames);
				sliceTotal = 0L;
				sliceFrames = 0;
			}
		}
		if (sliceFrames >= MIN_STABLE_FRAME_SAMPLES / 20) {
			slices.add(sliceTotal / (double)sliceFrames);
		}
		return slices.size() >= MIN_FPS_SLICES ? median(slices) : 0.0D;
	}

	private static double onePercentLowOrZero(List<Long> frames) {
		return frames.isEmpty() ? 0.0D : onePercentLowFps(frames);
	}

	private static double worstFrameFps(List<Long> frames) {
		long worst = maxNanos(frames);
		return worst <= 0L ? 0.0D : 1_000_000_000.0D / worst;
	}

	private static double percentChange(double oldValue, double newValue) {
		if (Math.abs(oldValue) < 0.000001D) {
			return Math.abs(newValue) < 0.000001D ? 0.0D : 100.0D;
		}
		return (newValue - oldValue) * 100.0D / Math.abs(oldValue);
	}

	private static String format(double value) {
		return String.format(Locale.US, "%.2f", value);
	}

	private static String formatCsv(double value) {
		if (Double.isInfinite(value) || Double.isNaN(value)) {
			return csv(Double.toString(value));
		}
		return String.format(Locale.US, "%.4f", value);
	}

	private static String csv(String value) {
		String escaped = value == null ? "" : value.replace("\"", "\"\"");
		return "\"" + escaped + "\"";
	}

	private static List<String> parseCsvLine(String line) {
		List<String> columns = new ArrayList<>();
		if (line == null) {
			return columns;
		}
		StringBuilder value = new StringBuilder();
		boolean quoted = false;
		for (int i = 0; i < line.length(); i++) {
			char ch = line.charAt(i);
			if (ch == '"') {
				if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
					value.append('"');
					i++;
				} else {
					quoted = !quoted;
				}
			} else if (ch == ',' && !quoted) {
				columns.add(value.toString());
				value.setLength(0);
			} else {
				value.append(ch);
			}
		}
		columns.add(value.toString());
		return columns;
	}

	private static double parseDoubleOrNaN(String value) {
		try {
			return Double.parseDouble(value);
		} catch (NumberFormatException exception) {
			return Double.NaN;
		}
	}

	private static double median(List<Double> values) {
		List<Double> sorted = new ArrayList<>(values);
		Collections.sort(sorted);
		int middle = sorted.size() / 2;
		if ((sorted.size() & 1) == 1) {
			return sorted.get(middle);
		}
		return (sorted.get(middle - 1) + sorted.get(middle)) * 0.5D;
	}

	private static int ticksToSeconds(int tickCount) {
		return Math.max(1, tickCount / 20);
	}

	private static String escape(String text) {
		return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
			.replace("\"", "&quot;").replace("'", "&#39;");
	}

	private static double averageNanosOrZero(List<Long> frames) {
		return frames.isEmpty() ? 0.0D : averageNanos(frames);
	}

	private static BenchmarkStability stability(List<Long> frames) {
		if (frames.isEmpty()) {
			return BenchmarkStability.EMPTY;
		}
		double meanNanos = rawAverageNanos(frames);
		double variance = 0.0D;
		for (long frame : frames) {
			double delta = frame - meanNanos;
			variance += delta * delta;
		}
		double stdDevNanos = frames.size() <= 1 ? 0.0D : Math.sqrt(variance / (frames.size() - 1));
		double standardErrorNanos = frames.size() <= 1 ? 0.0D : stdDevNanos / Math.sqrt(frames.size());
		return new BenchmarkStability(frames.size(), meanNanos, stdDevNanos, standardErrorNanos);
	}

	private static BenchmarkComparison compareStability(BenchmarkStability offStability, BenchmarkStability onStability) {
		double frameGainNanos = offStability.meanNanos() - onStability.meanNanos();
		double combinedStandardErrorNanos = Math.sqrt(offStability.standardErrorNanos() * offStability.standardErrorNanos()
			+ onStability.standardErrorNanos() * onStability.standardErrorNanos());
		double combinedCiNanos = 1.96D * combinedStandardErrorNanos;
		double signalToNoise = combinedCiNanos <= 0.0D
			? (Math.abs(frameGainNanos) > 0.0D ? Double.POSITIVE_INFINITY : 0.0D)
			: Math.abs(frameGainNanos) / combinedCiNanos;
		return new BenchmarkComparison(frameGainNanos, combinedCiNanos, signalToNoise);
	}

	private static OptiminiumMetrics.Snapshot delta(OptiminiumMetrics.Snapshot start, OptiminiumMetrics.Snapshot end) {
		return new OptiminiumMetrics.Snapshot(
			end.culledEntityRenders() - start.culledEntityRenders(),
			end.culledBlockEntityRenders() - start.culledBlockEntityRenders(),
			end.hiddenNameTags() - start.hiddenNameTags(),
			end.hiddenParticles() - start.hiddenParticles()
		);
	}

	private static OptiminiumMetrics.Snapshot emptyMetrics() {
		return new OptiminiumMetrics.Snapshot(0L, 0L, 0L, 0L);
	}

	private static OptiminiumGpuOptimizer.ProfileSnapshot emptyProfile() {
		return new OptiminiumGpuOptimizer.ProfileSnapshot(0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D);
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
			0L, 0L, 0L, 0L, 0L, 0L,
			0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
			0L, 0.0D, 0.0D, 0.0D, 0.0D,
			0.0D, 0.0D, 0.0D, 0.0D, 0.0D, "none",
			0L, 0L, 0L, 0L, 0L, 0L, 0.0D, 0.0D, 0.0D, 0.0D, 0L,
			0.0D, 0.0D, 0.0D, 0.0D,
			0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D,
			0L, 0L, 0L, 0L, 0L,
			0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, "none",
			0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0.0D, 0L, 0L, "none"));
	}

	private static OptiminiumRenderProfiler.Snapshot emptyRenderProfile() {
		return new OptiminiumRenderProfiler.Snapshot(0L, 0L, 0L, 0L, 0.0D, 0L, 0L, 0L, 0L, 0L, 0.0D,
			0, 0, 0, 0, 0.0D,
			0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, "none",
			0L, 0L, 0L, 0L, 0L, 0L, 0L, "none");
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
		if (frames.size() < 40) {
			return rawAverageNanos(frames);
		}
		List<Long> sorted = new ArrayList<>(frames);
		sorted.sort(Long::compare);
		int trim = Math.max(1, (int)Math.floor(sorted.size() * 0.025D));
		int start = trim;
		int end = sorted.size() - trim;
		if (start >= end) {
			return rawAverageNanos(frames);
		}
		long total = 0L;
		for (int i = start; i < end; i++) {
			total += sorted.get(i);
		}
		return total / (double)(end - start);
	}

	private static double rawAverageNanos(List<Long> frames) {
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

	private enum BurstMetric {
		FRAME_TIME,
		RECOMPUTES,
		TRANSITIONS,
		UPLOADS,
		PROXY_OPS,
		PERIODIC
	}

	private enum Phase {
		OFF,
		ON
	}

	private record BenchmarkCase(String name, Runnable enable) {
	}

	private enum FullBenchmarkMetric {
		AVG_FPS,
		ONE_PERCENT_LOW,
		WORST_FPS,
		GPU_MS,
		OPTIMINIUM_CPU_MS
	}

	private enum RepeatBenchmarkMetric {
		ON_FPS,
		FPS_GAIN,
		FPS_GAIN_PERCENT,
		FRAME_GAIN_MS
	}

	private record FullBenchmarkResult(
		String name,
		double averageFps,
		double onePercentLowFps,
		double worstFrameFps,
		double averageGpuMs,
		double optiminiumCpuMs,
		String raw
	) {
	}

	private record RepeatBenchmarkResult(
		int sample,
		String phaseOrder,
		String sceneKey,
		double offFps,
		double onFps,
		double fpsGain,
		double fpsGainPercent,
		double frameGainMs,
		double maxCvPercent,
		boolean cameraStable
	) {
	}

	private record FrameBurstSample(
		long frameNanos,
		OptiminiumVisualSignificance.FrameBurstSnapshot significance,
		OptiminiumRenderProfiler.FrameSnapshot render
	) {
		long deferredWork() {
			return significance.scoreRecomputesDeferred()
				+ significance.bandTransitionsDeferred();
		}
	}

	private record BenchmarkStability(int samples, double meanNanos, double stdDevNanos, double standardErrorNanos) {
		private static final BenchmarkStability EMPTY = new BenchmarkStability(0, 0.0D, 0.0D, 0.0D);

		double coefficientOfVariationPercent() {
			return meanNanos <= 0.0D ? 0.0D : stdDevNanos * 100.0D / meanNanos;
		}

		boolean isStable() {
			return samples >= MIN_STABLE_FRAME_SAMPLES
				&& coefficientOfVariationPercent() <= STABLE_FRAME_CV_PERCENT;
		}
	}

	private record BenchmarkComparison(double frameGainNanos, double combinedCiNanos, double signalToNoise) {
		double frameGainMs() {
			return frameGainNanos / 1_000_000.0D;
		}

		double combinedCiMs() {
			return combinedCiNanos / 1_000_000.0D;
		}

		boolean isReliable() {
			return signalToNoise >= 1.0D;
		}
	}

	private record BenchmarkHistoryTrend(
		int samples,
		String sceneKey,
		double medianFpsGainPercent,
		double latestFpsGainPercent,
		double deviationPercentPoints,
		double medianFrameGainMs,
		double latestFrameGainMs,
		double allowedDeviationPercentPoints,
		boolean repeatable
	) {
		private static final BenchmarkHistoryTrend EMPTY = new BenchmarkHistoryTrend(0, "", 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, true);

		boolean hasBaseline() {
			return samples > 0;
		}

		String statusText() {
			if (!hasBaseline()) {
				return "collecting baseline";
			}
			return repeatable ? "repeatable" : "outlier";
		}

		String line() {
			if (!hasBaseline()) {
				return "historySamples=0, status=collecting-baseline";
			}
			return String.format(Locale.US,
				"historySamples=%d, sceneKey=%s, status=%s, latestFpsGain=%.2f%%, medianFpsGain=%.2f%%, deviation=%.2fpp, allowedDeviation=%.2fpp, latestFrameGain=%.3f ms, medianFrameGain=%.3f ms",
				samples,
				sceneKey,
				repeatable ? "repeatable" : "outlier",
				latestFpsGainPercent,
				medianFpsGainPercent,
				deviationPercentPoints,
				allowedDeviationPercentPoints,
				latestFrameGainMs,
				medianFrameGainMs);
		}
	}

	private record CameraSnapshot(String dimension, double x, double y, double z, float xRot, float yRot) {
		private static final CameraSnapshot EMPTY = new CameraSnapshot("unknown", Double.NaN, Double.NaN, Double.NaN, Float.NaN, Float.NaN);

		private static CameraSnapshot capture() {
			Player player = Minecraft.getInstance().player;
			if (player == null) {
				return EMPTY;
			}
			return new CameraSnapshot(player.level().dimension().location().toString(),
				player.getX(), player.getY(), player.getZ(), player.getXRot(), player.getYRot());
		}

		private CameraSnapshot finish() {
			return matches(capture()) ? this : EMPTY;
		}

		private boolean matches(CameraSnapshot other) {
			return dimension.equals(other.dimension)
				&& Math.abs(x - other.x) < 0.001D
				&& Math.abs(y - other.y) < 0.001D
				&& Math.abs(z - other.z) < 0.001D
				&& Math.abs(xRot - other.xRot) < 0.001F
				&& Math.abs(yRot - other.yRot) < 0.001F;
		}

		private String sceneKey() {
			if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) {
				return "unknown";
			}
			return dimension + "@"
				+ (int)Math.floor(x) + ","
				+ (int)Math.floor(y) + ","
				+ (int)Math.floor(z) + ":"
				+ Math.round(xRot) + ","
				+ Math.round(yRot);
		}
	}
}
