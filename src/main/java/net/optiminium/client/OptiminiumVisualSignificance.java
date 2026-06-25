package net.optiminium.client;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.optiminium.optimization.OptiminiumSettings;

/**
 * Second-generation Visual Importance Engine.
 * 
 * Instead of evaluating objects independently every frame, this engine
 * understands context over time. Objects have memory: historical importance,
 * confidence, predicted future importance, attention accumulation, and
 * transition cooldowns. Decisions prefer stability over oscillation.
 * 
 * Key concepts from AAA engines (Unreal Significance Manager, Frostbite,
 * Source 2) applied here:
 * 
 * - Hysteresis: objects resist changing bands too quickly
 * - Confidence: how sure we are about an object's classification
 * - Prediction: camera velocity projects where objects will be
 * - Attention accumulation: looked-at objects build up importance
 * - Importance decay: unobserved objects lose importance gradually
 * - Visual momentum: fast movement defers distant detail
 * - Transition cooldowns: band changes have minimum intervals
 * - Adaptive thresholds: thresholds tighten under frame pressure
 * 
 * Overhead target: < 0.04 ms/frame (incremental, cached, no full scans)
 */
public final class OptiminiumVisualSignificance {
	// ---- Distance thresholds (squared) ----
	private static final double NEAR_DISTANCE_SQR = 24.0D * 24.0D;
	private static final double THROTTLED_DISTANCE_SQR = 56.0D * 56.0D;
	private static final double REUSED_DISTANCE_SQR = 96.0D * 96.0D;
	private static final double PROXY_DISTANCE_SQR = 160.0D * 160.0D;
	private static final double LOW_SCREEN_SIZE_DISTANCE_SQR = 128.0D * 128.0D;
	private static final double TINY_DISTANCE_SQR = 192.0D * 192.0D;
	private static final double LOOKED_AT_DOT = 0.985D;

	// ---- Temporal constants ----
	private static final long RECENT_FRAMES = 80L;
	private static final long NEVER_INTERACTED_FRAME = -RECENT_FRAMES - 1L;
	private static final int TRANSITION_COOLDOWN_FRAMES = 8;
	private static final int HYSTERESIS_HOLD_FRAMES = 12;
	private static final int CONFIDENCE_BUILD_FRAMES = 20;
	private static final int ATTENTION_DECAY_INTERVAL = 60;
	private static final int PREDICTION_WINDOW_FRAMES = 10;
	private static final int ATTENTION_LOOK_BOOST_FRAMES = 4;
	private static final int STABILITY_REQUIRED_FRAMES = 15;

	// ---- Score weights ----
	private static final double IMPORTANCE_WEIGHT = 0.55D;
	private static final double COST_WEIGHT = 0.45D;

	// ---- Render cost thresholds ----
	private static final double HIGH_RENDER_COST_THRESHOLD = 0.65D;
	private static final double LOW_IMPORTANCE_THRESHOLD = 0.35D;

	// ---- Classification constants ----
	private static final byte UNKNOWN = -1;
	private static final byte FULL = 0;
	private static final byte THROTTLED = 1;
	private static final byte REUSED = 2;
	private static final byte PROXY = 3;
	private static final byte CULLED = 4;

	// ---- Per-key data ----
	private static final Long2IntOpenHashMap seenCounts = new Long2IntOpenHashMap();
	private static final Long2LongOpenHashMap recentlyInteracted = new Long2LongOpenHashMap();
	private static final Long2ByteOpenHashMap beClassifications = new Long2ByteOpenHashMap();
	private static final Long2ObjectOpenHashMap<ObjectMemory> objectMemory = new Long2ObjectOpenHashMap<>();

	// ---- Per-frame counters ----
	private static int fullThisFrame;
	private static int throttledThisFrame;
	private static int reusedThisFrame;
	private static int proxyThisFrame;
	private static int culledThisFrame;
	private static long fullTotal;
	private static long throttledTotal;
	private static long reusedTotal;
	private static long proxyTotal;
	private static long culledTotal;

	// ---- Reason counters ----
	private static long fullBecauseNearby;
	private static long fullBecauseImportant;
	private static long fullBecauseLookedAt;
	private static long fullBecauseRecentlyInteracted;
	private static long fullBecauseHighConfidence;
	private static long throttledBecauseDistance;
	private static long throttledBecauseFramePressure;
	private static long throttledBecauseHighCost;
	private static long throttledBecausePredictedLow;
	private static long reusedBecauseStable;
	private static long reusedBecauseCameraStable;
	private static long reusedBecauseHysteresis;
	private static long proxyBecauseFarRepeated;
	private static long proxyBecauseLowScreenSize;
	private static long proxyBecauseHighConfidence;
	private static long culledBecauseOffscreen;
	private static long culledBecauseBudget;
	private static long culledBecauseTiny;
	private static long culledBecauseLowSignificance;
	private static long culledBecauseHighCostLowImportance;
	private static long culledBecausePredictedOffscreen;
	private static long culledBecauseLowConfidencePromotable;
	private static long culledBecauseFastCamera;

	// ---- CPU timing ----
	private static long significanceNanos;
	private static long worstSignificanceNanos;
	private static long profiledObjects;

	// ---- Scene metrics ----
	private static double nearestDistanceSqr = Double.POSITIVE_INFINITY;
	private static long frameIndex;
	private static int stableFrameCounter;

	// ---- Camera motion tracking ----
	private static double lastCameraX = Double.NaN;
	private static double lastCameraY = Double.NaN;
	private static double lastCameraZ = Double.NaN;
	private static float lastCameraYaw;
	private static float lastCameraPitch;
	private static double cameraVelocityX;
	private static double cameraVelocityZ;
	private static double cameraVelocityAbs;
	private static double cameraRotationSpeed;
	private static double cameraAcceleration;
	private static double lastCameraVelocityAbs;
	private static boolean cameraStable;
	private static boolean cameraFastMoving;

	// ---- Cleanup state ----
	private static int objectMemoryCount;
	private static final int MAX_OBJECT_MEMORY = 4096;
	private static int cleanupCounter;

	static {
		recentlyInteracted.defaultReturnValue(NEVER_INTERACTED_FRAME);
		beClassifications.defaultReturnValue(UNKNOWN);
	}

	private OptiminiumVisualSignificance() {
	}

	// ============================================================
	//  Public API
	// ============================================================

	public static void onFrameStart() {
		frameIndex++;
		recordInteractionTarget();
		accumulateTotals();
		resetFrameCounters();
		updateCameraMotion();
		advanceObjectMemories();
		cleanupStaleMemory();
	}

	public static boolean isEnabled() {
		return OptiminiumSettings.isExperimentalTemporalSignificance();
	}

	public static byte getBlockEntityClassification(BlockEntity blockEntity) {
		if (!isEnabled()) return UNKNOWN;
		return beClassifications.get(blockEntity.getBlockPos().asLong());
	}

	public static boolean shouldRenderBySignificance(BlockEntity blockEntity) {
		if (!isEnabled()) return true;
		byte classification = beClassifications.get(blockEntity.getBlockPos().asLong());
		return classification != CULLED;
	}

	public static boolean shouldProtectBlockEntity(BlockEntity blockEntity) {
		if (!isEnabled()) return false;
		BlockPos pos = blockEntity.getBlockPos();
		long key = pos.asLong();
		Vec3 cameraPosition = cameraPosition();
		if (cameraPosition == null) return false;
		double dx = pos.getX() + 0.5D - cameraPosition.x;
		double dy = pos.getY() + 0.5D - cameraPosition.y;
		double dz = pos.getZ() + 0.5D - cameraPosition.z;
		double distanceSqr = dx * dx + dy * dy + dz * dz;
		return distanceSqr <= NEAR_DISTANCE_SQR
			|| isImportant(blockEntity)
			|| isLookedAt(key, dx, dy, dz, distanceSqr)
			|| frameIndex - recentlyInteracted.get(key) <= RECENT_FRAMES
			|| prototypeConfidenceProtection(key);
	}

	public static boolean shouldProtectEntity(Entity entity) {
		if (!isEnabled()) return false;
		Vec3 cameraPosition = cameraPosition();
		if (cameraPosition == null) return false;
		double dx = entity.getX() - cameraPosition.x;
		double dy = entity.getY() - cameraPosition.y;
		double dz = entity.getZ() - cameraPosition.z;
		double distanceSqr = dx * dx + dy * dy + dz * dz;
		return distanceSqr <= NEAR_DISTANCE_SQR
			|| isImportantEntity(entity)
			|| isLookedAtDirection(dx, dy, dz, distanceSqr);
	}

	public static void recordBlockEntity(BlockEntity blockEntity, Vec3 cameraPosition) {
		if (!isEnabled()) return;
		long startNanos = System.nanoTime();
		try {
			recordBlockEntityProfiled(blockEntity, cameraPosition);
		} finally {
			recordTiming(startNanos);
		}
	}

	public static void recordEntity(Entity entity, boolean culled) {
		if (!isEnabled()) return;
		long startNanos = System.nanoTime();
		try {
			boolean important = isImportantEntity(entity);
			recordPoint(entity.getX(), entity.getY(), entity.getZ(), important, culled, entityRenderCost(entity));
		} finally {
			recordTiming(startNanos);
		}
	}

	public static void recordParticle(ParticleOptions options, double x, double y, double z, boolean culled) {
		if (!isEnabled()) return;
		long startNanos = System.nanoTime();
		try {
			recordPoint(x, y, z, isImportantParticle(options), culled, particleRenderCost(options));
		} finally {
			recordTiming(startNanos);
		}
	}

	// ============================================================
	//  Core recording
	// ============================================================

	private static void recordBlockEntityProfiled(BlockEntity blockEntity, Vec3 cameraPosition) {
		BlockPos pos = blockEntity.getBlockPos();
		long key = pos.asLong();
		double dx = pos.getX() + 0.5D - cameraPosition.x;
		double dy = pos.getY() + 0.5D - cameraPosition.y;
		double dz = pos.getZ() + 0.5D - cameraPosition.z;
		double distanceSqr = dx * dx + dy * dy + dz * dz;
		nearestDistanceSqr = Math.min(nearestDistanceSqr, distanceSqr);

		boolean lookedAt = isLookedAt(key, dx, dy, dz, distanceSqr);
		boolean inFront = isInFront(dx, dy, dz, distanceSqr);
		boolean important = isImportant(blockEntity);
		boolean pressured = OptiminiumGpuOptimizer.hasVisualSignificancePressure();
		int repeatCount = seenCounts.addTo(key, 1) + 1;
		boolean recent = frameIndex - recentlyInteracted.get(key) <= RECENT_FRAMES;
		double renderCost = blockEntityRenderCost(blockEntity);

		// Get or create object memory
		ObjectMemory mem = objectMemory.get(key);
		if (mem == null) {
			mem = new ObjectMemory();
			objectMemory.put(key, mem);
		}
		mem.lastSeenFrame = frameIndex;

		// Compute current score
		double score = computeScore(distanceSqr, inFront, lookedAt, important,
			recent, repeatCount, pressured, renderCost);

		// Update attention score (temporal accumulation)
		updateAttention(mem, lookedAt, inFront, distanceSqr);

		// Update predicted importance (camera motion projection)
		updatePrediction(mem, dx, dy, dz, distanceSqr, inFront);

		// Update historical importance (EWMA)
		mem.historicalImportance = mem.historicalImportance * 0.85D + score * 0.15D;

		// Determine discrete classification with hysteresis
		byte classification = classifyWithHysteresis(mem, distanceSqr, inFront,
			lookedAt, important, recent, repeatCount, pressured, score, renderCost);

		// Store classification
		beClassifications.put(key, classification);
		mem.lastClassification = classification;
		mem.lastScore = score;

		// Record reason counter
		incrementCounter(classification, distanceSqr, inFront, important,
			recent, repeatCount, pressured, score, renderCost, mem);
	}

	private static void recordPoint(double x, double y, double z, boolean important, boolean culled, double renderCost) {
		Vec3 cameraPosition = cameraPosition();
		if (cameraPosition == null) return;
		double dx = x - cameraPosition.x;
		double dy = y - cameraPosition.y;
		double dz = z - cameraPosition.z;
		double distanceSqr = dx * dx + dy * dy + dz * dz;
		nearestDistanceSqr = Math.min(nearestDistanceSqr, distanceSqr);
		boolean inFront = isInFront(dx, dy, dz, distanceSqr);
		boolean lookedAt = isLookedAtDirection(dx, dy, dz, distanceSqr);
		boolean pressured = OptiminiumGpuOptimizer.hasVisualSignificancePressure();
		double score = computeScore(distanceSqr, inFront, lookedAt, important, false, 1, pressured, renderCost);
		byte classification = classifySimple(distanceSqr, inFront, lookedAt, important, false, 1, pressured, score, renderCost);
		incrementCounter(classification, distanceSqr, inFront, important, false, 1, pressured, score, renderCost, null);
	}

	// ============================================================
	//  Temporal object memory
	// ============================================================

	private static final class ObjectMemory {
		byte lastClassification = UNKNOWN;
		double lastScore;
		double historicalImportance;
		double confidence;              // 0.0 (uncertain) to 1.0 (certain)
		double attentionScore;          // 0.0 (ignored) to 1.0 (high attention)
		double predictedImportance;     // estimated importance next frame
		byte predictedClassification;   // predicted band next frame
		long lastChangedFrame = -1;     // last band transition frame
		long lastSeenFrame = 1;         // first seen frame
		int stableBandFrames;           // consecutive frames in current band
		int lookedAtStreak;             // consecutive frames looked at
		int predictionCorrectFrames;    // consecutive correct predictions
		float predictionDot;            // cached dot product for prediction
		boolean recentlyPromoted;       // was promoted in last TRANSITION_COOLDOWN frames
		boolean recentlyDemoted;        // was demoted in last TRANSITION_COOLDOWN frames
	}

	private static void advanceObjectMemories() {
		// Advance per-frame state for all tracked objects
		// This is called once per frame, not per object
		stableFrameCounter++;
	}

	private static void updateAttention(ObjectMemory mem, boolean lookedAt, boolean inFront, double distanceSqr) {
		if (lookedAt) {
			mem.lookedAtStreak++;
			mem.attentionScore = Math.min(1.0D, mem.attentionScore + 0.12D);
		} else {
			mem.lookedAtStreak = 0;
			// Attention decays proportional to distance and time
			double decayRate = 0.008D + Math.sqrt(distanceSqr) * 0.0001D;
			mem.attentionScore = Math.max(0.0D, mem.attentionScore - decayRate);
		}
		// Boost from being in front and near center
		if (inFront && distanceSqr < THROTTLED_DISTANCE_SQR && !lookedAt) {
			mem.attentionScore = Math.min(1.0D, mem.attentionScore + 0.03D);
		}
	}

	private static void updatePrediction(ObjectMemory mem, double dx, double dy, double dz, double distanceSqr, boolean inFront) {
		if (distanceSqr <= 0.0001D || !inFront) {
			mem.predictedImportance = mem.historicalImportance;
			mem.predictedClassification = mem.lastClassification;
			return;
		}

		double dist = Math.sqrt(distanceSqr);
		// Project where the object will be relative to the camera given velocity
		double predictedDx = dx - cameraVelocityX * PREDICTION_WINDOW_FRAMES * 0.05D;
		double predictedDz = dz - cameraVelocityZ * PREDICTION_WINDOW_FRAMES * 0.05D;
		double predictedDistSqr = predictedDx * predictedDx + dy * dy + predictedDz * predictedDz;

		// Will it be in front?
		Vec3 cameraPos = cameraPosition();
		boolean predictedInFront = true;
		if (cameraPos != null) {
			var look = Minecraft.getInstance().gameRenderer.getMainCamera().getLookVector();
			double dot = (predictedDx * look.x + dy * look.y + predictedDz * look.z) / Math.sqrt(Math.max(predictedDistSqr, 0.0001D));
			predictedInFront = dot > 0.0D;
			mem.predictionDot = (float) dot;
		}

		if (!predictedInFront) {
			// Likely to go off-screen soon
			mem.predictedImportance = mem.historicalImportance * 0.5D;
			mem.predictedClassification = CULLED;
		} else {
			double predictedDist = Math.sqrt(predictedDistSqr);
			double predictedScore = 1.0D - Math.min(1.0D, predictedDist / 192.0D);
			predictedScore = predictedScore * 0.6D + mem.historicalImportance * 0.4D;
			mem.predictedImportance = Math.max(0.0D, Math.min(1.0D, predictedScore));
			// Map predicted score to predicted band
			if (predictedScore > 0.70D) mem.predictedClassification = FULL;
			else if (predictedScore > 0.50D) mem.predictedClassification = THROTTLED;
			else if (predictedScore > 0.30D) mem.predictedClassification = REUSED;
			else if (predictedScore > 0.15D) mem.predictedClassification = PROXY;
			else mem.predictedClassification = CULLED;
		}
	}

	private static void updateConfidence(ObjectMemory mem, byte newClassification) {
		if (newClassification == mem.predictedClassification) {
			mem.predictionCorrectFrames++;
		} else {
			mem.predictionCorrectFrames = Math.max(0, mem.predictionCorrectFrames - 2);
		}

		// Confidence builds with:
		// - Consistent classification matches prediction
		// - Long stable run in same band
		// - High look-at streak
		boolean classificationStable = newClassification == mem.lastClassification;
		if (classificationStable) {
			mem.stableBandFrames++;
		} else {
			mem.stableBandFrames = 0;
		}

		double baseConfidence = 0.5D;
		double correctBonus = Math.min(0.3D, mem.predictionCorrectFrames / (double) CONFIDENCE_BUILD_FRAMES);
		double stableBonus = Math.min(0.25D, mem.stableBandFrames / (double) STABILITY_REQUIRED_FRAMES);
		double attentionBonus = mem.attentionScore * 0.15D;
		double penalty = cameraFastMoving ? 0.15D : 0.0D;

		mem.confidence = Math.max(0.05D, Math.min(1.0D,
			baseConfidence + correctBonus + stableBonus + attentionBonus - penalty));

		// Track transition cooldown
		if (newClassification != mem.lastClassification) {
			mem.lastChangedFrame = frameIndex;
			mem.recentlyPromoted = newClassification < mem.lastClassification;
			mem.recentlyDemoted = newClassification > mem.lastClassification;
		}
	}

	/**
	 * Classifies with temporal hysteresis to prevent oscillation.
	 */
	private static byte classifyWithHysteresis(ObjectMemory mem, double distanceSqr, boolean inFront,
			boolean lookedAt, boolean important, boolean recent, int repeatCount,
			boolean pressured, double score, double renderCost) {

		// First, compute the "raw" desired classification (no hysteresis)
		byte rawClassification = computeRawClassification(distanceSqr, inFront, lookedAt,
			important, recent, repeatCount, pressured, score, renderCost, mem);

		// Update confidence based on prediction match
		updateConfidence(mem, rawClassification);

		// ---- Hysteresis rules ----
		boolean onCooldown = (frameIndex - mem.lastChangedFrame) < TRANSITION_COOLDOWN_FRAMES;

		// Rule 1: If on transition cooldown, prefer staying in current band
		if (onCooldown && mem.lastClassification != UNKNOWN && rawClassification != mem.lastClassification) {
			// The band must differ by more than 1 to override cooldown
			if (Math.abs(rawClassification - mem.lastClassification) <= 1) {
				return mem.lastClassification;
			}
			// But large jumps (FULL<->CULLED) can override cooldown
		}

		// Rule 2: If confidence is high and prediction matches current band, resist change
		if (mem.confidence > 0.75D && mem.predictedClassification == mem.lastClassification
				&& mem.lastClassification != UNKNOWN && rawClassification != mem.lastClassification) {
			// Only allow change if the score difference is significant (>30% of range)
			double scoreDiff = Math.abs(score - mem.lastScore);
			if (scoreDiff < 0.15D) {
				return mem.lastClassification;
			}
		}

		// Rule 3: If recently promoted to FULL, hold FULL for hysteresis period
		if (rawClassification != FULL && mem.lastClassification == FULL
				&& mem.stableBandFrames < HYSTERESIS_HOLD_FRAMES && mem.confidence > 0.5D) {
			return FULL;
		}

		// Rule 4: If recently demoted to CULLED, hold CULLED for hysteresis period
		if (rawClassification != CULLED && mem.lastClassification == CULLED
				&& mem.stableBandFrames < HYSTERESIS_HOLD_FRAMES && mem.confidence > 0.3D) {
			return CULLED;
		}

		// Rule 5: Fast camera → resist upgrading distant objects
		if (cameraFastMoving && rawClassification < mem.lastClassification && distanceSqr > THROTTLED_DISTANCE_SQR) {
			return mem.lastClassification;
		}

		return rawClassification;
	}

	/**
	 * Computes the raw "desired" classification without hysteresis.
	 */
	private static byte computeRawClassification(double distanceSqr, boolean inFront, boolean lookedAt,
			boolean important, boolean recent, int repeatCount, boolean pressured,
			double score, double renderCost, ObjectMemory mem) {

		// ---- Immediate FULL triggers ----
		if (distanceSqr <= NEAR_DISTANCE_SQR) return FULL;
		if (important || lookedAt || recent) return FULL;

		// ---- Predicted off-screen ----
		if (mem != null && cameraVelocityAbs > 0.1D && !inFront && mem.predictedClassification == CULLED) {
			return CULLED;
		}

		// ---- Fast camera: defer processing for distant objects ----
		if (cameraFastMoving && distanceSqr > THROTTLED_DISTANCE_SQR && mem != null
				&& mem.confidence < 0.5D) {
			return CULLED;
		}

		// ---- Offscreen ----
		if (!inFront) return CULLED;
		if (distanceSqr > TINY_DISTANCE_SQR) return CULLED;

		// ---- Cost-based culling ----
		if (renderCost >= HIGH_RENDER_COST_THRESHOLD && score < LOW_IMPORTANCE_THRESHOLD) return CULLED;
		if (pressured && score < 0.30D) return CULLED;
		if (pressured && score < 0.40D && mem != null && mem.confidence < 0.3D) return CULLED;

		// ---- Predicted low importance ----
		if (mem != null && mem.predictedImportance < 0.20D && mem.confidence > 0.6D && !cameraFastMoving) {
			return CULLED;
		}

		// ---- Throttled ----
		if (renderCost >= HIGH_RENDER_COST_THRESHOLD && distanceSqr <= THROTTLED_DISTANCE_SQR) return THROTTLED;
		if (pressured && distanceSqr <= THROTTLED_DISTANCE_SQR) return THROTTLED;
		if (distanceSqr <= THROTTLED_DISTANCE_SQR) return THROTTLED;

		// ---- Reused ----
		if (distanceSqr <= REUSED_DISTANCE_SQR) {
			if (mem != null && mem.confidence > 0.6D && mem.stableBandFrames > STABILITY_REQUIRED_FRAMES) {
				return cameraStable ? REUSED : REUSED;
			}
			// Low confidence fallback
			return THROTTLED;
		}

		// ---- Proxy ----
		if (distanceSqr >= LOW_SCREEN_SIZE_DISTANCE_SQR) return PROXY;
		if (distanceSqr <= PROXY_DISTANCE_SQR && repeatCount >= 4) return PROXY;

		return CULLED;
	}

	/**
	 * Simple classification without memory (for entities/particles).
	 */
	private static byte classifySimple(double distanceSqr, boolean inFront, boolean lookedAt,
			boolean important, boolean recent, int repeatCount, boolean pressured,
			double score, double renderCost) {
		if (distanceSqr <= NEAR_DISTANCE_SQR) return FULL;
		if (important || lookedAt) return FULL;
		if (!inFront) return CULLED;
		if (distanceSqr > TINY_DISTANCE_SQR) return CULLED;
		if (renderCost >= HIGH_RENDER_COST_THRESHOLD && score < LOW_IMPORTANCE_THRESHOLD) return CULLED;
		if (pressured && score < 0.30D) return CULLED;
		if (renderCost >= HIGH_RENDER_COST_THRESHOLD && distanceSqr <= THROTTLED_DISTANCE_SQR) return THROTTLED;
		if (pressured && distanceSqr <= THROTTLED_DISTANCE_SQR) return THROTTLED;
		if (distanceSqr <= THROTTLED_DISTANCE_SQR) return THROTTLED;
		if (cameraStable && distanceSqr <= REUSED_DISTANCE_SQR) return REUSED;
		if (distanceSqr <= REUSED_DISTANCE_SQR) return REUSED;
		if (distanceSqr >= LOW_SCREEN_SIZE_DISTANCE_SQR) return PROXY;
		if (distanceSqr <= PROXY_DISTANCE_SQR && repeatCount >= 4) return PROXY;
		return CULLED;
	}

	// ============================================================
	//  Counter tracking
	// ============================================================

	private static void incrementCounter(byte classification, double distanceSqr, boolean inFront,
			boolean important, boolean recent, int repeatCount, boolean pressured,
			double score, double renderCost, ObjectMemory mem) {
		switch (classification) {
			case FULL -> {
				fullThisFrame++;
				if (distanceSqr <= NEAR_DISTANCE_SQR) {
					fullBecauseNearby++;
				} else if (important) {
					fullBecauseImportant++;
				} else if (recent) {
					fullBecauseRecentlyInteracted++;
				} else if (mem != null && mem.confidence > 0.8D && mem.stableBandFrames > HYSTERESIS_HOLD_FRAMES) {
					fullBecauseHighConfidence++;
				} else {
					fullBecauseLookedAt++;
				}
			}
			case THROTTLED -> {
				throttledThisFrame++;
				if (renderCost >= HIGH_RENDER_COST_THRESHOLD) {
					throttledBecauseHighCost++;
				} else if (pressured) {
					throttledBecauseFramePressure++;
				} else if (mem != null && mem.predictedImportance < 0.4D) {
					throttledBecausePredictedLow++;
				} else {
					throttledBecauseDistance++;
				}
			}
			case REUSED -> {
				reusedThisFrame++;
				if (cameraStable) {
					reusedBecauseCameraStable++;
				} else if (mem != null && mem.confidence > 0.7D && mem.stableBandFrames > STABILITY_REQUIRED_FRAMES) {
					reusedBecauseHysteresis++;
				} else {
					reusedBecauseStable++;
				}
			}
			case PROXY -> {
				proxyThisFrame++;
				if (distanceSqr >= LOW_SCREEN_SIZE_DISTANCE_SQR) {
					proxyBecauseLowScreenSize++;
				} else if (mem != null && mem.confidence > 0.75D) {
					proxyBecauseHighConfidence++;
				} else {
					proxyBecauseFarRepeated++;
				}
			}
			case CULLED -> {
				culledThisFrame++;
				if (!inFront) {
					culledBecauseOffscreen++;
				} else if (distanceSqr > TINY_DISTANCE_SQR) {
					culledBecauseTiny++;
				} else if (renderCost >= HIGH_RENDER_COST_THRESHOLD && score < LOW_IMPORTANCE_THRESHOLD) {
					culledBecauseHighCostLowImportance++;
				} else if (pressured && score < 0.30D) {
					culledBecauseLowSignificance++;
				} else if (cameraFastMoving && mem != null && mem.confidence < 0.5D) {
					culledBecauseFastCamera++;
				} else if (mem != null && mem.predictedClassification == CULLED && mem.confidence > 0.65D) {
					culledBecausePredictedOffscreen++;
				} else if (mem != null && mem.confidence < 0.2D && !cameraFastMoving) {
					culledBecauseLowConfidencePromotable++;
				} else {
					culledBecauseBudget++;
				}
			}
		}
	}

	// ============================================================
	//  Camera motion tracking
	// ============================================================

	private static void updateCameraMotion() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.gameRenderer == null || minecraft.gameRenderer.getMainCamera() == null) {
			cameraStable = false;
			cameraFastMoving = false;
			return;
		}
		Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
		float yaw = minecraft.gameRenderer.getMainCamera().getXRot();
		float pitch = minecraft.gameRenderer.getMainCamera().getYRot();

		if (!Double.isNaN(lastCameraX)) {
			// Position velocity
			cameraVelocityX = camera.x - lastCameraX;
			cameraVelocityZ = camera.z - lastCameraZ;
			cameraVelocityAbs = Math.sqrt(cameraVelocityX * cameraVelocityX + cameraVelocityZ * cameraVelocityZ);

			// Acceleration (rate of change of velocity)
			cameraAcceleration = Math.abs(cameraVelocityAbs - lastCameraVelocityAbs);

			// Rotation speed (combined yaw+pitch delta per frame)
			float deltaYaw = yaw - lastCameraYaw;
			float deltaPitch = pitch - lastCameraPitch;
			cameraRotationSpeed = Math.sqrt(deltaYaw * deltaYaw + deltaPitch * deltaPitch);

			// Stability: position change < 0.01 + rotation change < 0.5 degrees
			cameraStable = Math.abs(camera.x - lastCameraX) < 0.01D
				&& Math.abs(camera.y - lastCameraY) < 0.01D
				&& Math.abs(camera.z - lastCameraZ) < 0.01D
				&& cameraRotationSpeed < 0.5D;

			// Fast moving: velocity > 0.5 blocks/frame (elytra, sprint) or fast rotation
			cameraFastMoving = cameraVelocityAbs > 0.5D || cameraRotationSpeed > 10.0D;
		}

		lastCameraX = camera.x;
		lastCameraY = camera.y;
		lastCameraZ = camera.z;
		lastCameraYaw = yaw;
		lastCameraPitch = pitch;
		lastCameraVelocityAbs = cameraVelocityAbs;
	}

	// ============================================================
	//  Memory cleanup
	// ============================================================

	private static void cleanupStaleMemory() {
		if (objectMemory.isEmpty()) return;
		cleanupCounter++;
		// Only clean up every 60 frames to avoid per-frame iteration
		if (cleanupCounter < 60) return;
		cleanupCounter = 0;

		// Remove objects not seen in 200+ frames
		var iterator = objectMemory.long2ObjectEntrySet().fastIterator();
		while (iterator.hasNext()) {
			var entry = iterator.next();
			if (frameIndex - entry.getValue().lastSeenFrame > 200) {
				iterator.remove();
				// Also clean up seen counts for this key
				seenCounts.remove(entry.getLongKey());
			}
		}
		objectMemoryCount = objectMemory.size();
	}

	// ============================================================
	//  Utilities
	// ============================================================

	private static Vec3 cameraPosition() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.gameRenderer == null || minecraft.gameRenderer.getMainCamera() == null) return null;
		return minecraft.gameRenderer.getMainCamera().getPosition();
	}

	private static double computeScore(double distanceSqr, boolean inFront, boolean lookedAt,
			boolean important, boolean recent, int repeatCount, boolean pressured, double renderCost) {
		double importanceScore = 1.0D - Math.min(1.0D, Math.sqrt(distanceSqr) / 192.0D);
		if (!inFront) importanceScore -= 0.45D;
		if (lookedAt) importanceScore += 0.55D;
		if (important) importanceScore += 0.45D;
		if (recent) importanceScore += 0.50D;
		if (repeatCount >= 4) importanceScore -= 0.10D;
		if (pressured) importanceScore -= 0.10D;
		importanceScore = Math.max(0.0D, Math.min(1.0D, importanceScore));

		double costFactor = 1.0D - renderCost;
		double combined = importanceScore * IMPORTANCE_WEIGHT + costFactor * COST_WEIGHT;
		return Math.max(0.0D, Math.min(1.0D, combined));
	}

	private static boolean isLookedAt(long blockEntityPos, double dx, double dy, double dz, double distanceSqr) {
		if (distanceSqr <= 0.0001D) return true;
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.hitResult instanceof BlockHitResult hit
				&& hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().asLong() == blockEntityPos) {
			return true;
		}
		return lookDot(dx, dy, dz, distanceSqr) >= LOOKED_AT_DOT;
	}

	private static boolean isInFront(double dx, double dy, double dz, double distanceSqr) {
		if (distanceSqr <= 0.0001D) return true;
		return lookDot(dx, dy, dz, distanceSqr) > 0.0D;
	}

	private static boolean isLookedAtDirection(double dx, double dy, double dz, double distanceSqr) {
		if (distanceSqr <= 0.0001D) return true;
		return lookDot(dx, dy, dz, distanceSqr) >= LOOKED_AT_DOT;
	}

	private static double lookDot(double dx, double dy, double dz, double distanceSqr) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.gameRenderer == null || minecraft.gameRenderer.getMainCamera() == null) return 0.0D;
		var look = minecraft.gameRenderer.getMainCamera().getLookVector();
		return (dx * look.x + dy * look.y + dz * look.z) / Math.sqrt(distanceSqr);
	}

	private static void recordInteractionTarget() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.options == null || (!minecraft.options.keyUse.isDown() && !minecraft.options.keyAttack.isDown())) return;
		if (minecraft.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
			recentlyInteracted.put(hit.getBlockPos().asLong(), frameIndex);
		}
	}

	private static boolean prototypeConfidenceProtection(long key) {
		ObjectMemory mem = objectMemory.get(key);
		return mem != null && mem.confidence > 0.8D && mem.attentionScore > 0.6D;
	}

	private static void recordTiming(long startNanos) {
		long nanos = Math.max(0L, System.nanoTime() - startNanos);
		significanceNanos += nanos;
		worstSignificanceNanos = Math.max(worstSignificanceNanos, nanos);
		profiledObjects++;
	}

	// ============================================================
	//  Snapshot / Reset / Diagnostics
	// ============================================================

	private static void accumulateTotals() {
		fullTotal += fullThisFrame;
		throttledTotal += throttledThisFrame;
		reusedTotal += reusedThisFrame;
		proxyTotal += proxyThisFrame;
		culledTotal += culledThisFrame;
	}

	private static void resetFrameCounters() {
		fullThisFrame = 0;
		throttledThisFrame = 0;
		reusedThisFrame = 0;
		proxyThisFrame = 0;
		culledThisFrame = 0;
	}

	public static String diagnosticLine() {
		return snapshot().toLine();
	}

	public static Snapshot snapshot() {
		return new Snapshot(
			fullTotal + fullThisFrame,
			throttledTotal + throttledThisFrame,
			reusedTotal + reusedThisFrame,
			proxyTotal + proxyThisFrame,
			culledTotal + culledThisFrame,
			fullBecauseNearby,
			fullBecauseImportant,
			fullBecauseLookedAt,
			fullBecauseRecentlyInteracted,
			fullBecauseHighConfidence,
			throttledBecauseDistance,
			throttledBecauseFramePressure,
			throttledBecauseHighCost,
			throttledBecausePredictedLow,
			reusedBecauseStable,
			reusedBecauseCameraStable,
			reusedBecauseHysteresis,
			proxyBecauseFarRepeated,
			proxyBecauseLowScreenSize,
			proxyBecauseHighConfidence,
			culledBecauseOffscreen,
			culledBecauseBudget,
			culledBecauseTiny,
			culledBecauseLowSignificance,
			culledBecauseHighCostLowImportance,
			culledBecausePredictedOffscreen,
			culledBecauseLowConfidencePromotable,
			culledBecauseFastCamera,
			averageSignificanceMs(),
			worstSignificanceNanos / 1_000_000.0D,
			mostCommonReason(),
			nearestDistanceSqr == Double.POSITIVE_INFINITY ? -1.0D : Math.sqrt(nearestDistanceSqr),
			cameraVelocityAbs,
			cameraRotationSpeed,
			cameraFastMoving,
			cameraStable,
			objectMemoryCount
		);
	}

	public static void reset() {
		fullThisFrame = 0;
		throttledThisFrame = 0;
		reusedThisFrame = 0;
		proxyThisFrame = 0;
		culledThisFrame = 0;
		fullTotal = 0L;
		throttledTotal = 0L;
		reusedTotal = 0L;
		proxyTotal = 0L;
		culledTotal = 0L;
		fullBecauseNearby = 0L;
		fullBecauseImportant = 0L;
		fullBecauseLookedAt = 0L;
		fullBecauseRecentlyInteracted = 0L;
		fullBecauseHighConfidence = 0L;
		throttledBecauseDistance = 0L;
		throttledBecauseFramePressure = 0L;
		throttledBecauseHighCost = 0L;
		throttledBecausePredictedLow = 0L;
		reusedBecauseStable = 0L;
		reusedBecauseCameraStable = 0L;
		reusedBecauseHysteresis = 0L;
		proxyBecauseFarRepeated = 0L;
		proxyBecauseLowScreenSize = 0L;
		proxyBecauseHighConfidence = 0L;
		culledBecauseOffscreen = 0L;
		culledBecauseBudget = 0L;
		culledBecauseTiny = 0L;
		culledBecauseLowSignificance = 0L;
		culledBecauseHighCostLowImportance = 0L;
		culledBecausePredictedOffscreen = 0L;
		culledBecauseLowConfidencePromotable = 0L;
		culledBecauseFastCamera = 0L;
		significanceNanos = 0L;
		worstSignificanceNanos = 0L;
		profiledObjects = 0L;
		nearestDistanceSqr = Double.POSITIVE_INFINITY;
		seenCounts.clear();
		recentlyInteracted.clear();
		beClassifications.clear();
		objectMemory.clear();
		objectMemoryCount = 0;
		cleanupCounter = 0;
		cameraStable = false;
		cameraFastMoving = false;
		cameraVelocityAbs = 0.0D;
		cameraRotationSpeed = 0.0D;
		cameraAcceleration = 0.0D;
		lastCameraVelocityAbs = 0.0D;
		lastCameraX = Double.NaN;
		lastCameraY = Double.NaN;
		lastCameraZ = Double.NaN;
		lastCameraYaw = 0.0f;
		lastCameraPitch = 0.0f;
		stableFrameCounter = 0;
	}

	private static double averageSignificanceMs() {
		return profiledObjects <= 0L ? 0.0D : significanceNanos / 1_000_000.0D / profiledObjects;
	}

	private static String mostCommonReason() {
		String reason = "none";
		long count = 0L;
		if (fullBecauseNearby > count) { reason = "fullBecauseNearby"; count = fullBecauseNearby; }
		if (fullBecauseImportant > count) { reason = "fullBecauseImportant"; count = fullBecauseImportant; }
		if (fullBecauseLookedAt > count) { reason = "fullBecauseLookedAt"; count = fullBecauseLookedAt; }
		if (fullBecauseRecentlyInteracted > count) { reason = "fullBecauseRecentlyInteracted"; count = fullBecauseRecentlyInteracted; }
		if (fullBecauseHighConfidence > count) { reason = "fullBecauseHighConfidence"; count = fullBecauseHighConfidence; }
		if (throttledBecauseDistance > count) { reason = "throttledBecauseDistance"; count = throttledBecauseDistance; }
		if (throttledBecauseFramePressure > count) { reason = "throttledBecauseFramePressure"; count = throttledBecauseFramePressure; }
		if (throttledBecauseHighCost > count) { reason = "throttledBecauseHighCost"; count = throttledBecauseHighCost; }
		if (throttledBecausePredictedLow > count) { reason = "throttledBecausePredictedLow"; count = throttledBecausePredictedLow; }
		if (reusedBecauseStable > count) { reason = "reusedBecauseStable"; count = reusedBecauseStable; }
		if (reusedBecauseCameraStable > count) { reason = "reusedBecauseCameraStable"; count = reusedBecauseCameraStable; }
		if (reusedBecauseHysteresis > count) { reason = "reusedBecauseHysteresis"; count = reusedBecauseHysteresis; }
		if (proxyBecauseFarRepeated > count) { reason = "proxyBecauseFarRepeated"; count = proxyBecauseFarRepeated; }
		if (proxyBecauseLowScreenSize > count) { reason = "proxyBecauseLowScreenSize"; count = proxyBecauseLowScreenSize; }
		if (proxyBecauseHighConfidence > count) { reason = "proxyBecauseHighConfidence"; count = proxyBecauseHighConfidence; }
		if (culledBecauseOffscreen > count) { reason = "culledBecauseOffscreen"; count = culledBecauseOffscreen; }
		if (culledBecauseBudget > count) { reason = "culledBecauseBudget"; count = culledBecauseBudget; }
		if (culledBecauseTiny > count) { reason = "culledBecauseTiny"; count = culledBecauseTiny; }
		if (culledBecauseLowSignificance > count) { reason = "culledBecauseLowSignificance"; count = culledBecauseLowSignificance; }
		if (culledBecauseHighCostLowImportance > count) { reason = "culledBecauseHighCostLowImportance"; count = culledBecauseHighCostLowImportance; }
		if (culledBecausePredictedOffscreen > count) { reason = "culledBecausePredictedOffscreen"; count = culledBecausePredictedOffscreen; }
		if (culledBecauseLowConfidencePromotable > count) { reason = "culledBecauseLowConfidencePromotable"; count = culledBecauseLowConfidencePromotable; }
		if (culledBecauseFastCamera > count) { reason = "culledBecauseFastCamera"; count = culledBecauseFastCamera; }
		return reason;
	}

	// ---- Importance classification ----

	private static boolean isImportant(BlockEntity blockEntity) {
		BlockEntityType<?> type = blockEntity.getType();
		return type == BlockEntityType.BEACON
			|| type == BlockEntityType.CONDUIT
			|| type == BlockEntityType.END_PORTAL
			|| type == BlockEntityType.END_GATEWAY
			|| type == BlockEntityType.MOB_SPAWNER
			|| type == BlockEntityType.TRIAL_SPAWNER
			|| type == BlockEntityType.VAULT;
	}

	private static boolean isImportantEntity(Entity entity) {
		return entity instanceof Player
			|| entity instanceof LivingEntity
			|| entity instanceof Projectile
			|| entity instanceof FallingBlockEntity
			|| entity.hasGlowingTag()
			|| entity.hasCustomName()
			|| entity.isPassenger()
			|| !entity.getPassengers().isEmpty()
			|| entity.displayFireAnimation();
	}

	private static boolean isImportantParticle(ParticleOptions options) {
		return options.getType() == ParticleTypes.EXPLOSION
			|| options.getType() == ParticleTypes.EXPLOSION_EMITTER
			|| options.getType() == ParticleTypes.FLASH
			|| options.getType() == ParticleTypes.DAMAGE_INDICATOR
			|| options.getType() == ParticleTypes.TOTEM_OF_UNDYING;
	}

	// ---- Render cost ----

	private static double blockEntityRenderCost(BlockEntity blockEntity) {
		BlockEntityType<?> type = blockEntity.getType();
		if (type == BlockEntityType.BEACON) return 0.85D;
		if (type == BlockEntityType.CONDUIT) return 0.80D;
		if (type == BlockEntityType.SHULKER_BOX) return 0.75D;
		if (type == BlockEntityType.CHEST) return 0.70D;
		if (type == BlockEntityType.TRAPPED_CHEST) return 0.70D;
		if (type == BlockEntityType.ENDER_CHEST) return 0.65D;
		if (type == BlockEntityType.BELL) return 0.65D;
		if (type == BlockEntityType.CAMPFIRE) return 0.60D;
		if (type == BlockEntityType.ENCHANTING_TABLE) return 0.60D;
		if (type == BlockEntityType.MOB_SPAWNER) return 0.55D;
		if (type == BlockEntityType.TRIAL_SPAWNER) return 0.55D;
		if (type == BlockEntityType.VAULT) return 0.55D;
		if (type == BlockEntityType.BANNER) return 0.50D;
		if (type == BlockEntityType.SIGN) return 0.40D;
		if (type == BlockEntityType.HANGING_SIGN) return 0.45D;
		if (type == BlockEntityType.SKULL) return 0.45D;
		if (type == BlockEntityType.PISTON) return 0.40D;
		if (type == BlockEntityType.BED) return 0.40D;
		if (type == BlockEntityType.LECTERN) return 0.35D;
		if (type == BlockEntityType.DECORATED_POT) return 0.35D;
		if (type == BlockEntityType.BRUSHABLE_BLOCK) return 0.30D;
		if (type == BlockEntityType.END_PORTAL) return 0.30D;
		if (type == BlockEntityType.END_GATEWAY) return 0.30D;
		return 0.50D;
	}

	private static double entityRenderCost(Entity entity) {
		if (entity instanceof Player) return 0.90D;
		if (entity instanceof LivingEntity) return 0.75D;
		if (entity instanceof Projectile) return 0.25D;
		if (entity instanceof FallingBlockEntity) return 0.30D;
		if (entity.hasGlowingTag()) return 0.50D;
		if (entity.hasCustomName()) return 0.40D;
		if (entity.isPassenger()) return 0.50D;
		if (!entity.getPassengers().isEmpty()) return 0.55D;
		if (entity.displayFireAnimation()) return 0.35D;
		return 0.30D;
	}

	private static double particleRenderCost(ParticleOptions options) {
		var type = options.getType();
		if (type == ParticleTypes.EXPLOSION_EMITTER) return 0.90D;
		if (type == ParticleTypes.EXPLOSION) return 0.80D;
		if (type == ParticleTypes.FLASH) return 0.30D;
		if (type == ParticleTypes.DAMAGE_INDICATOR) return 0.40D;
		if (type == ParticleTypes.TOTEM_OF_UNDYING) return 0.70D;
		if (type == ParticleTypes.FIREWORK) return 0.85D;
		if (type == ParticleTypes.ELDER_GUARDIAN) return 0.60D;
		if (type == ParticleTypes.ASH) return 0.15D;
		if (type == ParticleTypes.CLOUD) return 0.20D;
		if (type == ParticleTypes.MYCELIUM) return 0.15D;
		if (type == ParticleTypes.RAIN) return 0.10D;
		if (type == ParticleTypes.SMOKE) return 0.20D;
		if (type == ParticleTypes.WHITE_SMOKE) return 0.20D;
		if (type == ParticleTypes.SNOWFLAKE) return 0.10D;
		if (type == ParticleTypes.SPORE_BLOSSOM_AIR) return 0.15D;
		if (type == ParticleTypes.CRIMSON_SPORE) return 0.15D;
		if (type == ParticleTypes.WARPED_SPORE) return 0.15D;
		if (type == ParticleTypes.UNDERWATER) return 0.10D;
		return 0.25D;
	}

	// ============================================================
	//  Snapshot record
	// ============================================================

	public record Snapshot(
		long full,
		long throttled,
		long reused,
		long proxy,
		long culled,
		long fullBecauseNearby,
		long fullBecauseImportant,
		long fullBecauseLookedAt,
		long fullBecauseRecentlyInteracted,
		long fullBecauseHighConfidence,
		long throttledBecauseDistance,
		long throttledBecauseFramePressure,
		long throttledBecauseHighCost,
		long throttledBecausePredictedLow,
		long reusedBecauseStable,
		long reusedBecauseCameraStable,
		long reusedBecauseHysteresis,
		long proxyBecauseFarRepeated,
		long proxyBecauseLowScreenSize,
		long proxyBecauseHighConfidence,
		long culledBecauseOffscreen,
		long culledBecauseBudget,
		long culledBecauseTiny,
		long culledBecauseLowSignificance,
		long culledBecauseHighCostLowImportance,
		long culledBecausePredictedOffscreen,
		long culledBecauseLowConfidencePromotable,
		long culledBecauseFastCamera,
		double significanceCpuMs,
		double worstSignificanceCpuMs,
		String mostCommonReason,
		double nearestDistance,
		double cameraVelocity,
		double cameraRotationSpeed,
		boolean cameraFastMoving,
		boolean cameraStable,
		int trackedObjects
	) {
		private String toLine() {
			return "significanceBands=full:" + full
				+ ",throttled:" + throttled
				+ ",reused:" + reused
				+ ",proxy:" + proxy
				+ ",culled:" + culled
				+ ",fullBecauseNearby:" + fullBecauseNearby
				+ ",fullBecauseImportant:" + fullBecauseImportant
				+ ",fullBecauseLookedAt:" + fullBecauseLookedAt
				+ ",fullBecauseRecentlyInteracted:" + fullBecauseRecentlyInteracted
				+ ",fullBecauseHighConfidence:" + fullBecauseHighConfidence
				+ ",throttledBecauseDistance:" + throttledBecauseDistance
				+ ",throttledBecauseFramePressure:" + throttledBecauseFramePressure
				+ ",throttledBecauseHighCost:" + throttledBecauseHighCost
				+ ",throttledBecausePredictedLow:" + throttledBecausePredictedLow
				+ ",reusedBecauseStable:" + reusedBecauseStable
				+ ",reusedBecauseCameraStable:" + reusedBecauseCameraStable
				+ ",reusedBecauseHysteresis:" + reusedBecauseHysteresis
				+ ",proxyBecauseFarRepeated:" + proxyBecauseFarRepeated
				+ ",proxyBecauseLowScreenSize:" + proxyBecauseLowScreenSize
				+ ",proxyBecauseHighConfidence:" + proxyBecauseHighConfidence
				+ ",culledBecauseOffscreen:" + culledBecauseOffscreen
				+ ",culledBecauseBudget:" + culledBecauseBudget
				+ ",culledBecauseTiny:" + culledBecauseTiny
				+ ",culledBecauseLowSignificance:" + culledBecauseLowSignificance
				+ ",culledBecauseHighCostLowImportance:" + culledBecauseHighCostLowImportance
				+ ",culledBecausePredictedOffscreen:" + culledBecausePredictedOffscreen
				+ ",culledBecauseLowConfidencePromotable:" + culledBecauseLowConfidencePromotable
				+ ",culledBecauseFastCamera:" + culledBecauseFastCamera
				+ ",significanceCpuMs:" + String.format("%.4f", significanceCpuMs)
				+ ",worstSignificanceCpuMs:" + String.format("%.4f", worstSignificanceCpuMs)
				+ ",mostCommonReason:" + mostCommonReason
				+ ",nearestDistance:" + String.format("%.1f", nearestDistance)
				+ ",cameraVelocity:" + String.format("%.3f", cameraVelocity)
				+ ",cameraRotationSpeed:" + String.format("%.1f", cameraRotationSpeed)
				+ ",cameraFastMoving:" + cameraFastMoving
				+ ",cameraStable:" + cameraStable
				+ ",trackedObjects:" + trackedObjects;
		}
	}
}