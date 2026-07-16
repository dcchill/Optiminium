package net.optiminium.client;

/** Deterministic per-mesh policy for choosing vanilla or persistent rendering. */
final class AdaptivePersistencePolicy {
	static final int REQUIRED_WINS = 3;
	static final int REQUIRED_LOSSES = 8;
	static final long TRIAL_INTERVAL_FRAMES = 120L;
	static final long SAFETY_VETO_INTERVAL_FRAMES = 1_200L;
	static final int TRIAL_BURST_FRAMES = 10;
	static final long EXPIRE_AFTER_FRAMES = 600L;
	private static final double REQUIRED_SAVING = 0.15D;
	private static final double EWMA_OLD = 0.75D;
	private static final long BUILD_AMORTIZATION_FRAMES = 300L;
	private static final long MIN_TRIAL_VANILLA_FRAME_NANOS = 100_000L;
	static final long MAX_GPU_REGRESSION_NANOS = 250_000L;
	static final double MAX_P95_FRAME_REGRESSION = 0.02D;
	private static final int SAFETY_SAMPLES = 32;
	private static final int MIN_SAFETY_SAMPLES = 8;

	private boolean active;
	private boolean trial;
	private int trialFramesRemaining;
	private boolean discardNextPersistentSample;
	private boolean persistentSampleAccepted;
	private int wins;
	private int losses;
	private int trials;
	private int lastCount;
	private int stableFrames;
	private int previousCount;
	private long lastSeenFrame;
	private long nextTrialFrame;
	private double vanillaPerInstanceNanos;
	private double persistentPerInstanceNanos;
	private double buildNanos;
	private long pendingVanillaNanos;
	private int pendingVanillaInstances;
	private long pendingPersistentNanos;
	private int pendingPersistentInstances;
	private long meshVertices;
	private int builds;
	private String reason = "cold";
	private final RollingSamples vanillaGpu = new RollingSamples(SAFETY_SAMPLES);
	private final RollingSamples persistentGpu = new RollingSamples(SAFETY_SAMPLES);
	private final RollingSamples vanillaFrame = new RollingSamples(SAFETY_SAMPLES);
	private final RollingSamples persistentFrame = new RollingSamples(SAFETY_SAMPLES);

	boolean beginFrame(long frame, int candidates, boolean adaptive, int adaptiveMin, int guaranteed) {
		boolean wasTrial = trial;
		commitFrameSamples();
		lastCount = candidates;
		if (candidates > 0 && Math.abs(candidates - previousCount) <= Math.max(2, previousCount / 8)) {
			stableFrames++;
		} else {
			stableFrames = candidates > 0 ? 1 : 0;
		}
		previousCount = candidates;
		trial = false;
		if (candidates > 0) lastSeenFrame = frame;
		if (!adaptive) {
			active = candidates >= guaranteed;
			reason = active ? "fixed_threshold" : "below_fixed_threshold";
			return active;
		}
		if (candidates < adaptiveMin) {
			active = false;
			wins = 0;
			losses = 0;
			reason = "below_adaptive_min";
			return false;
		}
		if (candidates >= guaranteed && !vanillaSafetyReady()) {
			active = false;
			reason = "guaranteed_safety_warmup";
			return false;
		}

		boolean comparable = vanillaPerInstanceNanos > 0.0D && persistentPerInstanceNanos > 0.0D;
		if (comparable && (active || wasTrial && persistentSampleAccepted)) {
			double vanilla = vanillaPerInstanceNanos * candidates;
			double persistent = persistentPerInstanceNanos * candidates + buildNanos / BUILD_AMORTIZATION_FRAMES;
			// Sparse trials are intentionally short and their GPU timer samples can include the
			// preceding vanilla frame. Treat them as CPU qualification only; safety vetoes apply
			// after activation has produced a sustained, correctly attributed sample window.
			boolean gpuUnsafe = (active || wasTrial) && safetyReady()
				&& persistentGpu.average() - vanillaGpu.average() > MAX_GPU_REGRESSION_NANOS;
			boolean frameUnsafe = (active || wasTrial) && safetyReady()
				&& persistentFrame.percentile95() > vanillaFrame.percentile95() * (1.0D + MAX_P95_FRAME_REGRESSION);
			if (gpuUnsafe || frameUnsafe) {
				active = false;
				trial = false;
				trialFramesRemaining = 0;
				wins = 0;
				losses = REQUIRED_LOSSES;
				nextTrialFrame = frame + SAFETY_VETO_INTERVAL_FRAMES;
				reason = gpuUnsafe ? "gpu_safety_veto" : "p95_safety_veto";
				return false;
			}
			boolean beneficial = persistent <= vanilla * (1.0D - REQUIRED_SAVING) && !gpuUnsafe && !frameUnsafe;
			if (beneficial) {
				wins++;
				losses = 0;
				reason = "measured_win";
				// Moderate groups must establish their safety window during the trial. The
				// guaranteed threshold retains immediate activation for compatibility.
				if (wins >= REQUIRED_WINS && (candidates >= guaranteed || safetyReady())) active = true;
			} else {
				losses++;
				wins = 0;
				reason = gpuUnsafe ? "gpu_safety_veto" : frameUnsafe ? "p95_safety_veto" : "measured_regression";
				if (losses == REQUIRED_LOSSES) {
					active = false;
					nextTrialFrame = Math.max(nextTrialFrame, frame + TRIAL_INTERVAL_FRAMES);
				}
			}
		}

		if (candidates >= guaranteed && (!comparable || losses < REQUIRED_LOSSES)) {
			active = true;
			reason = "guaranteed_threshold";
		}
		if (!active && wasTrial && trialFramesRemaining > 0) {
			trialFramesRemaining--;
			trial = true;
			if (!reason.startsWith("measured_")) reason = "trial_warmup";
			return true;
		}
		boolean trialWorthwhile = candidates >= guaranteed
			|| vanillaPerInstanceNanos * candidates >= MIN_TRIAL_VANILLA_FRAME_NANOS;
		if (!active && stableFrames >= REQUIRED_WINS && trialWorthwhile && frame >= nextTrialFrame) {
			trial = true;
			trialFramesRemaining = TRIAL_BURST_FRAMES - 1;
			discardNextPersistentSample = true;
			trials++;
			nextTrialFrame = frame + TRIAL_INTERVAL_FRAMES;
			reason = "trial";
		}
		return active || trial;
	}

	void recordVanilla(long totalNanos, int instances) {
		if (instances <= 0 || totalNanos <= 0L) return;
		pendingVanillaNanos += totalNanos;
		pendingVanillaInstances += instances;
	}

	void recordPersistent(long totalNanos, int instances) {
		if (instances <= 0 || totalNanos <= 0L) return;
		pendingPersistentNanos += totalNanos;
		pendingPersistentInstances += instances;
	}

	void recordPersistentOverhead(long nanos) {
		if (nanos > 0L) pendingPersistentNanos += nanos;
	}

	void recordBuild(long nanos, long vertices) {
		if (nanos > 0L) buildNanos = ewma(buildNanos, nanos);
		meshVertices = Math.max(meshVertices, vertices);
		builds++;
	}

	void recordFrameSafety(long gpuNanos, long frameNanos, boolean persistentMode) {
		if (gpuNanos <= 0L || frameNanos <= 0L) return;
		(persistentMode ? persistentGpu : vanillaGpu).add(gpuNanos);
		(persistentMode ? persistentFrame : vanillaFrame).add(frameNanos);
	}

	boolean expired(long frame) {
		return frame - lastSeenFrame > EXPIRE_AFTER_FRAMES;
	}

	boolean active() { return active; }
	boolean trial() { return trial; }
	int trials() { return trials; }
	int lastCount() { return lastCount; }
	double vanillaPerInstanceNanos() { return vanillaPerInstanceNanos; }
	double persistentPerInstanceNanos() { return persistentPerInstanceNanos; }
	long meshVertices() { return meshVertices; }
	int builds() { return builds; }
	String reason() { return reason; }
	boolean safetyReady() {
		return vanillaGpu.size() >= MIN_SAFETY_SAMPLES && persistentGpu.size() >= MIN_SAFETY_SAMPLES
			&& vanillaFrame.size() >= MIN_SAFETY_SAMPLES && persistentFrame.size() >= MIN_SAFETY_SAMPLES;
	}

	private boolean vanillaSafetyReady() {
		return vanillaGpu.size() >= MIN_SAFETY_SAMPLES && vanillaFrame.size() >= MIN_SAFETY_SAMPLES;
	}

	private void commitFrameSamples() {
		persistentSampleAccepted = false;
		if (pendingVanillaInstances > 0) {
			vanillaPerInstanceNanos = ewma(vanillaPerInstanceNanos,
				pendingVanillaNanos / (double)pendingVanillaInstances);
		}
		if (pendingPersistentInstances > 0) {
			if (discardNextPersistentSample) {
				discardNextPersistentSample = false;
			} else {
				persistentPerInstanceNanos = ewma(persistentPerInstanceNanos,
					pendingPersistentNanos / (double)pendingPersistentInstances);
				persistentSampleAccepted = true;
			}
		}
		pendingVanillaNanos = 0L;
		pendingVanillaInstances = 0;
		pendingPersistentNanos = 0L;
		pendingPersistentInstances = 0;
	}

	private static double ewma(double oldValue, double sample) {
		return oldValue == 0.0D ? sample : oldValue * EWMA_OLD + sample * (1.0D - EWMA_OLD);
	}

	private static final class RollingSamples {
		private final long[] values;
		private int cursor;
		private int size;

		RollingSamples(int capacity) { values = new long[capacity]; }

		void add(long value) {
			values[cursor] = value;
			cursor = (cursor + 1) % values.length;
			size = Math.min(values.length, size + 1);
		}

		int size() { return size; }

		double average() {
			long total = 0L;
			for (int i = 0; i < size; i++) total += values[i];
			return size == 0 ? 0.0D : total / (double)size;
		}

		long percentile95() {
			if (size == 0) return 0L;
			long[] copy = java.util.Arrays.copyOf(values, size);
			java.util.Arrays.sort(copy);
			return copy[Math.min(size - 1, (int)Math.ceil(size * 0.95D) - 1)];
		}
	}
}
