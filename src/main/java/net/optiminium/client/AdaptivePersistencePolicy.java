package net.optiminium.client;

/** Deterministic per-mesh policy for choosing vanilla or persistent rendering. */
final class AdaptivePersistencePolicy {
	static final int REQUIRED_WINS = 3;
	static final int REQUIRED_LOSSES = 8;
	static final long TRIAL_INTERVAL_FRAMES = 120L;
	static final long EXPIRE_AFTER_FRAMES = 600L;
	private static final double REQUIRED_SAVING = 0.15D;
	private static final double EWMA_OLD = 0.75D;
	private static final long BUILD_AMORTIZATION_FRAMES = 300L;
	private static final long MIN_TRIAL_VANILLA_FRAME_NANOS = 100_000L;

	private boolean active;
	private boolean trial;
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

	boolean beginFrame(long frame, int candidates, boolean adaptive, int adaptiveMin, int guaranteed) {
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

		boolean comparable = vanillaPerInstanceNanos > 0.0D && persistentPerInstanceNanos > 0.0D;
		if (comparable) {
			double vanilla = vanillaPerInstanceNanos * candidates;
			double persistent = persistentPerInstanceNanos * candidates + buildNanos / BUILD_AMORTIZATION_FRAMES;
			boolean beneficial = persistent <= vanilla * (1.0D - REQUIRED_SAVING);
			if (beneficial) {
				wins++;
				losses = 0;
				reason = "measured_win";
				if (wins >= REQUIRED_WINS) active = true;
			} else {
				losses++;
				wins = 0;
				reason = "measured_regression";
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
		boolean trialWorthwhile = candidates >= guaranteed
			|| vanillaPerInstanceNanos * candidates >= MIN_TRIAL_VANILLA_FRAME_NANOS;
		if (!active && stableFrames >= REQUIRED_WINS && trialWorthwhile && frame >= nextTrialFrame) {
			trial = true;
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

	private void commitFrameSamples() {
		if (pendingVanillaInstances > 0) {
			vanillaPerInstanceNanos = ewma(vanillaPerInstanceNanos,
				pendingVanillaNanos / (double)pendingVanillaInstances);
		}
		if (pendingPersistentInstances > 0) {
			persistentPerInstanceNanos = ewma(persistentPerInstanceNanos,
				pendingPersistentNanos / (double)pendingPersistentInstances);
		}
		pendingVanillaNanos = 0L;
		pendingVanillaInstances = 0;
		pendingPersistentNanos = 0L;
		pendingPersistentInstances = 0;
	}

	private static double ewma(double oldValue, double sample) {
		return oldValue == 0.0D ? sample : oldValue * EWMA_OLD + sample * (1.0D - EWMA_OLD);
	}
}
