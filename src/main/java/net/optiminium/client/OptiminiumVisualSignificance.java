package net.optiminium.client;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ambient.AmbientCreature;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.optiminium.OptiminiumMod;
import net.optiminium.optimization.OptiminiumSettings;

/**
 * Second-generation Visual Importance Engine with entity anti-pop protection.
 * 
 * Key improvements over first-gen:
 * - Continuous significance score (EWMA-smoothed, not per-frame threshold)
 * - Separate promotion/demotion thresholds with hysteresis
 * - Visibility decay for smooth fade-out
 * - Entity-specific ObjectMemory with hysteresis, confidence, prediction
 * - Recently-visible grace period (entities stay visible for ~20 frames after last render)
 * - Entity category rules (hostile > passive > items)
 * - Anti-pop counters and debug logging
 * - Player-movement-aware significance (moving toward = higher importance)
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

	// ---- Entity-specific distance thresholds ----
	private static final double ENTITY_NEAR_DISTANCE_SQR = 32.0D * 32.0D;
	private static final double ENTITY_MID_DISTANCE_SQR = 80.0D * 80.0D;
	private static final double ENTITY_FAR_DISTANCE_SQR = 128.0D * 128.0D;
	private static final double ENTITY_CULL_DISTANCE_SQR = 192.0D * 192.0D;

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
	private static final int FADE_TRANSITION_FRAMES = 10;

	// ---- Block entity anti-pop constants ----
	private static final int BLOCK_ENTITY_RECENTLY_VISIBLE_GRACE_FRAMES = 45;
	private static final int BLOCK_ENTITY_CULL_HOLD_FRAMES = 12;
	private static final double BLOCK_ENTITY_VISIBLE_DISTANCE_SQR = 64.0D * 64.0D;

	// ---- Entity anti-pop constants ----
	private static final int RECENTLY_VISIBLE_GRACE_FRAMES = 45;
	private static final int ENTITY_HYSTERESIS_HOLD_FRAMES = 12;
	private static final int ENTITY_CULL_OSCILLATION_WINDOW = 30;
	private static final double ENTITY_MOVING_TOWARD_BOOST = 0.25D;
	private static final double ENTITY_MID_DISTANCE_PROTECTION = 0.15D;

	// ---- Weighted score factors (must sum to 1.0) ----
	private static final double WEIGHT_SCREEN_COVERAGE   = 0.20D;
	private static final double WEIGHT_DISTANCE          = 0.15D;
	private static final double WEIGHT_TYPE_IMPORTANCE   = 0.15D;
	private static final double WEIGHT_CAMERA_FOCUS      = 0.15D;
	private static final double WEIGHT_RENDER_COST       = 0.10D;
	private static final double WEIGHT_TEMPORAL_CONFIDENCE = 0.10D;
	private static final double WEIGHT_MOTION            = 0.08D;
	private static final double WEIGHT_RECENT_VISIBILITY = 0.05D;
	private static final double WEIGHT_ANIMATION_STATE   = 0.02D;

	// ---- Render cost thresholds ----
	private static final double HIGH_RENDER_COST_THRESHOLD = 0.65D;
	private static final double LOW_RENDER_COST_THRESHOLD  = 0.35D;
	private static final double NOTICEABLE_RENDER_COST_THRESHOLD = 0.50D;

	// ---- Score ceilings by band (for target-score references) ----
	private static final double MAX_SIGNIFICANCE_DISTANCE = 256.0D;
	private static final double MIN_SCREEN_COVERAGE_DISTANCE = 4.0D;

	// Legacy threshold reused by classifySimple / incrementCounter
	private static final double LOW_IMPORTANCE_THRESHOLD = 0.35D;

	// ---- Top-10 tracking (cache-friendly) ----
	private static final int TOP_EXPENSIVE_COUNT = 10;
	private static final java.util.Comparator<double[]> EXPENSIVE_COMPARATOR =
		java.util.Comparator.comparingDouble((double[] a) -> a[1]).reversed();
	private static final java.util.PriorityQueue<double[]> topExpensiveObjects =
		new java.util.PriorityQueue<>(TOP_EXPENSIVE_COUNT + 1, EXPENSIVE_COMPARATOR);
	private static boolean topExpensiveDirty = true;
	private static String topExpensiveCached = "none";

	// ---- Classification constants ----
	private static final byte UNKNOWN = -1;
	private static final byte FULL = 0;
	private static final byte THROTTLED = 1;
	private static final byte REUSED = 2;
	private static final byte PROXY = 3;
	private static final byte CULLED = 4;

	// ---- Hysteresis thresholds for continuous score ----
	private static final double[] PROMOTE_THRESHOLDS = {
		1.0D,  // FULL: cannot promote above
		0.80D, // THROTTLED -> FULL (score > 0.80)
		0.60D, // REUSED -> THROTTLED (score > 0.60)
		0.40D, // PROXY -> REUSED (score > 0.40)
		0.25D  // CULLED -> PROXY (score > 0.25)
	};

	private static final double[] DEMOTE_THRESHOLDS = {
		0.60D, // FULL -> THROTTLED (score < 0.60)
		0.42D, // THROTTLED -> REUSED (score < 0.42)
		0.28D, // REUSED -> PROXY (score < 0.28)
		0.15D, // PROXY -> CULLED (score < 0.15)
		0.0D   // CULLED: cannot demote below
	};

	// ---- Per-key data ----
	private static final Long2IntOpenHashMap seenCounts = new Long2IntOpenHashMap();
	private static final Long2LongOpenHashMap recentlyInteracted = new Long2LongOpenHashMap();
	private static final Long2ByteOpenHashMap beClassifications = new Long2ByteOpenHashMap();
	private static final Long2ObjectOpenHashMap<ObjectMemory> objectMemory = new Long2ObjectOpenHashMap<>();

	// ---- Entity-specific data ----
	private static final Int2ObjectOpenHashMap<EntityMemory> entityMemory = new Int2ObjectOpenHashMap<>();
	private static final Int2ObjectOpenHashMap<EntityOscillationTracker> entityOscillation = new Int2ObjectOpenHashMap<>();

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
	private static long blockEntityCullPreventedByVisibility;
	private static long blockEntityCullPreventedByRecentlyVisible;
	private static long blockEntityCullPreventedByLookedAt;
	private static long blockEntityDowngradedToReusedInsteadOfCulled;
	private static long blockEntityVisibleCullEvents;

	// ---- Entity anti-pop counters ----
	private static long entityCullPreventedByHysteresis;
	private static long entityCullPreventedByRecentlyVisible;
	private static long entityCullPreventedByMiddleDistance;
	private static long entityCullPreventedByMovingToward;
	private static long entityPromotedBecauseLiving;
	private static long entityPromotedBecauseImportant;
	private static long entityBandTransitions;
	private static long entityCullOscillationEvents;
	private static long dynamicModdedBlockEntityCosted;
	private static long dynamicModdedLivingEntityCosted;
	private static long dynamicModdedNonLivingEntityCosted;
	private static long dynamicModdedEntityCulled;
	private static String firstDynamicModNamespace = "none";
	private static String lastDynamicModNamespace = "none";

	// ---- Continuous score counters ----
	private static long promotionsPreventedByHysteresis;
	private static long demotionsPreventedByHysteresis;
	private static long promotionsPreventedByConfidence;
	private static long demotionsPreventedByConfidence;
	private static long singleBandTransitionsEnforced;
	private static double accumulatedContinuousScores;
	private static double accumulatedConfidence;
	private static long accumulatedScoreCount;

	// ---- Diagnostic accumulators ----
	private static double highestVisualSignificance;
	private static double lowestVisualSignificance = Double.POSITIVE_INFINITY;
	private static double accumulatedRenderCost;
	private static double accumulatedScreenCoverage;
	private static double accumulatedTemporalScore;

	// ---- Distribution tracking (new) ----
	private static long fullForcedBySafety;
	private static long fullByWeightedScore;
	private static long importantButThrottled;
	private static long importantButReused;
	private static long importantButProxy;

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
	private static final int MAX_OBJECT_MEMORY = 4096;
	private static final int MAX_ENTITY_MEMORY = 1024;
	private static int cleanupCounter;

	// ---- Render budget for Adaptive Quality ----
	public enum RenderBudget {
		NORMAL,
		MEDIUM_PRESSURE,
		HEAVY_PRESSURE,
		EMERGENCY
	}

	private static RenderBudget currentBudget = RenderBudget.NORMAL;
	private static int budgetPressureFrames;
	private static double budgetCulledFraction;

	// ---- Debug logging ----
	private static int debugEntityId = -1;
	private static int debugLogCounter;

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
		decayVisibility();
		cleanupStaleMemory();
		cleanupStaleEntityMemory();
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
		long key = blockEntity.getBlockPos().asLong();
		ObjectMemory mem = objectMemory.get(key);
		if (mem == null || mem.lastSeenFrame != frameIndex) return true;
		byte classification = beClassifications.get(key);
		return classification != CULLED || isFadingOut(mem.lastChangedFrame);
	}

	public static float blockEntityFadeAlpha(BlockEntity blockEntity) {
		if (!isEnabled()) return 1.0F;
		ObjectMemory mem = objectMemory.get(blockEntity.getBlockPos().asLong());
		return fadeAlpha(mem == null ? UNKNOWN : mem.lastClassification, mem == null ? -1L : mem.lastChangedFrame);
	}

	public static float entityFadeAlpha(Entity entity) {
		if (!isEnabled()) return 1.0F;
		EntityMemory mem = entityMemory.get(entity.getId());
		return fadeAlpha(mem == null ? UNKNOWN : mem.lastClassification, mem == null ? -1L : mem.lastChangedFrame);
	}

	public static boolean shouldRenderEntityDuringFade(Entity entity) {
		if (!isEnabled()) return false;
		EntityMemory mem = entityMemory.get(entity.getId());
		if (mem == null) return false;
		// New entity: never seen before, don't start rendering until it's actually come into view.
		// This prevents the "pop at full alpha and fade out" effect for entities that load at a distance.
		if (frameIndex - mem.firstSeenFrame < FADE_TRANSITION_FRAMES) {
			return false;
		}
		// Fade-out: entity was just culled, keep rendering during fade transition
		if (mem.lastClassification == CULLED && isFadingOut(mem.lastChangedFrame)) return true;
		// Fade-in: entity was just promoted from CULLED, keep rendering during fade transition
		if (mem.lastClassification != CULLED && mem.lastClassification != UNKNOWN
				&& (mem.previousClassification == CULLED || mem.previousClassification == UNKNOWN)
				&& isFadingOut(mem.lastChangedFrame)) return true;
		return false;
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
		return distanceSqr <= ENTITY_NEAR_DISTANCE_SQR
			|| isImportantEntity(entity)
			|| isLookedAtDirection(dx, dy, dz, distanceSqr);
	}

	public static boolean allowsLivingEntityCull(LivingEntity entity, double distanceSqr) {
		if (!isEnabled()) return true;
		Vec3 cameraPosition = cameraPosition();
		if (cameraPosition == null) return false;
		double dx = entity.getX() - cameraPosition.x;
		double dy = entity.getY() - cameraPosition.y;
		double dz = entity.getZ() - cameraPosition.z;
		boolean inFront = isInFront(dx, dy, dz, distanceSqr);
		EntityCategory category = classifyEntity(entity);
		EntityMemory mem = entityMemory.get(entity.getId());

		if (mem == null) {
			entityCullPreventedByHysteresis++;
			return false;
		}
		if (category == EntityCategory.CRITICAL || category == EntityCategory.HOSTILE
				|| category == EntityCategory.VILLAGER || category == EntityCategory.NEUTRAL) {
			entityPromotedBecauseImportant++;
			return false;
		}
		if (distanceSqr <= ENTITY_NEAR_DISTANCE_SQR) {
			entityPromotedBecauseLiving++;
			return false;
		}
		if (inFront && distanceSqr <= ENTITY_MID_DISTANCE_SQR) {
			entityCullPreventedByMiddleDistance++;
			return false;
		}
		if (isMovingToward(dx, dz, Math.sqrt(distanceSqr))) {
			entityCullPreventedByMovingToward++;
			return false;
		}
		if (mem != null && frameIndex - mem.lastVisibleFrame <= RECENTLY_VISIBLE_GRACE_FRAMES) {
			entityCullPreventedByRecentlyVisible++;
			return false;
		}
		if (mem != null && mem.lastClassification != CULLED
				&& mem.lowSignificanceFrames < ENTITY_HYSTERESIS_HOLD_FRAMES) {
			entityCullPreventedByHysteresis++;
			return false;
		}
		return distanceSqr > ENTITY_CULL_DISTANCE_SQR
			&& mem.lastClassification == CULLED
			&& mem.lowSignificanceFrames >= ENTITY_HYSTERESIS_HOLD_FRAMES;
	}

	public static void recordLivingEntityRendered(LivingEntity entity) {
		if (!isEnabled()) return;
		EntityMemory mem = entityMemory.get(entity.getId());
		if (mem == null) {
			recordEntity(entity, false);
			mem = entityMemory.get(entity.getId());
		}
		if (mem != null) {
			mem.lastVisibleFrame = frameIndex;
		}
	}

	public static boolean shouldCullDynamicEntity(Entity entity, double distanceSqr) {
		if (!isEnabled() || !isModdedEntity(entity) || isImportantEntity(entity) || entity.isVehicle()) return false;
		if (entity instanceof LivingEntity || entity instanceof Projectile || entity instanceof FallingBlockEntity
				|| entity instanceof HangingEntity || entity instanceof ItemEntity
				|| entity instanceof net.minecraft.world.entity.ExperienceOrb) return false;
		if (entity.getBbWidth() * entity.getBbHeight() > 0.75F) return false;
		double trackingDistance = Math.max(48.0D, entity.getType().clientTrackingRange() * 0.75D);
		boolean culled = distanceSqr > trackingDistance * trackingDistance;
		if (culled) dynamicModdedEntityCulled++;
		return culled;
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
			recordEntityProfiled(entity, culled);
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

		ObjectMemory mem = objectMemory.get(key);
		if (mem == null) {
			mem = new ObjectMemory();
			objectMemory.put(key, mem);
		}
		mem.lastSeenFrame = frameIndex;

		// Compute raw score from all factors
		double score = computeScore(distanceSqr, inFront, lookedAt, important,
			recent, repeatCount, pressured, renderCost);

		mem.visibilityDecay = 1.0D;

		updateAttention(mem, lookedAt, inFront, distanceSqr);
		updatePrediction(mem, dx, dy, dz, distanceSqr, inFront);
		mem.historicalImportance = mem.historicalImportance * 0.85D + score * 0.15D;
		mem.continuousScore = mem.continuousScore * 0.70D + score * 0.30D;

		// Diagnostics
		double dist = Math.sqrt(distanceSqr);
		double screenCoverage = 1.0D - Math.min(1.0D, dist / MAX_SIGNIFICANCE_DISTANCE);
		double temporalScore = mem.continuousScore;
		accumulatedContinuousScores += mem.continuousScore;
		accumulatedConfidence += mem.confidence;
		accumulatedScoreCount++;
		accumulatedRenderCost += renderCost;
		accumulatedScreenCoverage += screenCoverage;
		accumulatedTemporalScore += temporalScore;
		if (mem.continuousScore > highestVisualSignificance) highestVisualSignificance = mem.continuousScore;
		if (mem.continuousScore < lowestVisualSignificance) lowestVisualSignificance = mem.continuousScore;

		// Top-10 expensive tracking (lightweight: O(log N) per insert)
		if (topExpensiveObjects.size() < TOP_EXPENSIVE_COUNT || renderCost > topExpensiveObjects.peek()[1]) {
			topExpensiveObjects.offer(new double[] { (double) key, renderCost });
			if (topExpensiveObjects.size() > TOP_EXPENSIVE_COUNT) topExpensiveObjects.poll();
			topExpensiveDirty = true;
		}

		byte classification = classifyByContinuousScore(mem);
		classification = protectVisibleBlockEntityClassification(classification, mem,
			distanceSqr, inFront, lookedAt, important, recent, pressured);

		beClassifications.put(key, classification);
		updateConfidence(mem, classification);
		mem.lastClassification = classification;
		mem.lastScore = score;
		if (classification != CULLED) {
			mem.lastVisibleFrame = frameIndex;
		}

		incrementCounter(classification, distanceSqr, inFront, important,
			recent, repeatCount, pressured, score, renderCost, mem, false);
	}

	private static void recordEntityProfiled(Entity entity, boolean culled) {
		int entityId = entity.getId();
		Vec3 cameraPosition = cameraPosition();
		if (cameraPosition == null) return;

		double x = entity.getX();
		double y = entity.getY();
		double z = entity.getZ();
		double dx = x - cameraPosition.x;
		double dy = y - cameraPosition.y;
		double dz = z - cameraPosition.z;
		double distanceSqr = dx * dx + dy * dy + dz * dz;
		nearestDistanceSqr = Math.min(nearestDistanceSqr, distanceSqr);

		boolean inFront = isInFront(dx, dy, dz, distanceSqr);
		boolean lookedAt = isLookedAtDirection(dx, dy, dz, distanceSqr);
		boolean important = isImportantEntity(entity);
		boolean pressured = OptiminiumGpuOptimizer.hasVisualSignificancePressure();
		double renderCost = entityRenderCost(entity);

		EntityMemory mem = entityMemory.get(entityId);
		if (mem == null) {
			mem = new EntityMemory();
			mem.firstSeenFrame = frameIndex;
			entityMemory.put(entityId, mem);
		}
		mem.lastSeenFrame = frameIndex;
		mem.lastDistanceSqr = distanceSqr;
		mem.lastInFront = inFront;

		EntityCategory category = classifyEntity(entity);
		mem.category = category;

		double score = computeEntityScore(distanceSqr, inFront, lookedAt, important,
			pressured, renderCost, category, entity, dx, dy, dz);

		mem.visibilityDecay = 1.0D;

		updateEntityAttention(mem, lookedAt, inFront, distanceSqr, category);
		updateEntityPrediction(mem, dx, dy, dz, distanceSqr, inFront);
		mem.historicalImportance = mem.historicalImportance * 0.85D + score * 0.15D;
		mem.continuousScore = mem.continuousScore * 0.70D + score * 0.30D;

		// Diagnostics
		double estDist = Math.sqrt(distanceSqr);
		double estScreenCoverage = 1.0D - Math.min(1.0D, estDist / MAX_SIGNIFICANCE_DISTANCE);
		double temporalScore = mem.continuousScore;
		accumulatedContinuousScores += mem.continuousScore;
		accumulatedConfidence += mem.confidence;
		accumulatedScoreCount++;
		accumulatedRenderCost += renderCost;
		accumulatedScreenCoverage += estScreenCoverage;
		accumulatedTemporalScore += temporalScore;
		if (mem.continuousScore > highestVisualSignificance) highestVisualSignificance = mem.continuousScore;
		if (mem.continuousScore < lowestVisualSignificance) lowestVisualSignificance = mem.continuousScore;

		// Top-10 expensive tracking (entities use entityId)
		if (topExpensiveObjects.size() < TOP_EXPENSIVE_COUNT || renderCost > topExpensiveObjects.peek()[1]) {
			topExpensiveObjects.offer(new double[] { (double) entityId, renderCost });
			if (topExpensiveObjects.size() > TOP_EXPENSIVE_COUNT) topExpensiveObjects.poll();
			topExpensiveDirty = true;
		}

		byte classification = classifyEntityByContinuousScore(mem, distanceSqr, inFront,
			lookedAt, important, category);
		byte previousClassification = mem.lastClassification;

		updateEntityConfidence(mem, classification);

		if (previousClassification != UNKNOWN && previousClassification != classification) {
			entityBandTransitions++;
			trackEntityOscillation(entityId, classification);
		}

		mem.previousClassification = previousClassification;
		mem.lastClassification = classification;
		mem.lastScore = score;
		mem.lastCulled = (classification == CULLED);
		if (!culled && classification != CULLED && !(entity instanceof LivingEntity)) {
			mem.lastVisibleFrame = frameIndex;
		}

		if (debugEntityId == entityId || (debugEntityId < 0 && shouldSampleDebugEntity(entity, distanceSqr))) {
			debugEntityId = entityId;
			debugLogEntity(entity, distanceSqr, score, previousClassification, classification, mem);
		}

		boolean entityWasRendered = mem.lastClassification != CULLED;
		incrementEntityCounter(classification, distanceSqr, inFront, important,
			pressured, score, renderCost, category, mem, entityWasRendered);
	}

	// ============================================================
	//  Continuous significance score and hysteresis
	// ============================================================

	private static byte classifyByContinuousScore(ObjectMemory mem) {
		double score = mem.continuousScore;
		byte previous = mem.lastClassification;

		if (previous == UNKNOWN) {
			return scoreToBand(score);
		}

		byte desired;
		if (score > PROMOTE_THRESHOLDS[previous]) {
			desired = (byte)(previous - 1);
		} else if (score < DEMOTE_THRESHOLDS[previous]) {
			desired = (byte)(previous + 1);
		} else {
			return previous;
		}

		if (desired < FULL) desired = FULL;
		if (desired > CULLED) desired = CULLED;

		if (mem.confidence > 0.0D) {
			if (desired < previous && mem.confidence < 0.3D) {
				promotionsPreventedByConfidence++;
				return previous;
			}
			if (desired > previous && mem.confidence < 0.2D) {
				demotionsPreventedByConfidence++;
				return previous;
			}
		}

		if (Math.abs(desired - previous) > 1) {
			singleBandTransitionsEnforced++;
			desired = desired < previous ? (byte)(previous - 1) : (byte)(previous + 1);
		}

		if (desired != previous) {
			if (desired < previous) {
				promotionsPreventedByHysteresis++;
			} else {
				demotionsPreventedByHysteresis++;
			}
		}

		return desired;
	}

	/**
	 * Protect visible block entity classification.
	 * 
	 * Key change: "important" alone no longer forces FULL.
	 * Only lookedAt, nearby, or recently-interacted force FULL.
	 * Important block entities instead get a score boost via computeScore()
	 * and are protected from culling via shouldProtectBlockEntity().
	 */
	private static byte protectVisibleBlockEntityClassification(byte classification, ObjectMemory mem,
			double distanceSqr, boolean inFront, boolean lookedAt, boolean important,
			boolean recent, boolean pressured) {
		// Only these safety cases force FULL:
		if (lookedAt) {
			if (classification == CULLED) blockEntityCullPreventedByLookedAt++;
			mem.lowSignificanceFrames = 0;
			fullForcedBySafety++;
			return FULL;
		}
		if (distanceSqr <= NEAR_DISTANCE_SQR || recent) {
			mem.lowSignificanceFrames = 0;
			fullForcedBySafety++;
			return FULL;
		}

		boolean visible = inFront && hasMinimumBlockEntityScreenPresence(distanceSqr);

		// Visible block entities within mid-range get at least THROTTLED
		if (visible && distanceSqr <= BLOCK_ENTITY_VISIBLE_DISTANCE_SQR && classification > THROTTLED) {
			if (classification == CULLED) blockEntityCullPreventedByVisibility++;
			mem.lowSignificanceFrames = 0;
			return THROTTLED;
		}

		if (classification != CULLED) {
			mem.lowSignificanceFrames = 0;
			return classification;
		}

		// CULLED handling below:
		mem.lowSignificanceFrames++;
		boolean recentlyVisible = frameIndex - mem.lastVisibleFrame <= BLOCK_ENTITY_RECENTLY_VISIBLE_GRACE_FRAMES;
		if (recentlyVisible) {
			blockEntityCullPreventedByRecentlyVisible++;
			return REUSED;
		}
		if (visible) {
			blockEntityVisibleCullEvents++;
			boolean extremePressure = currentBudget == RenderBudget.HEAVY_PRESSURE || currentBudget == RenderBudget.EMERGENCY;
			if (!pressured || !extremePressure || distanceSqr <= REUSED_DISTANCE_SQR || mem.lowSignificanceFrames < BLOCK_ENTITY_CULL_HOLD_FRAMES) {
				blockEntityCullPreventedByVisibility++;
				blockEntityDowngradedToReusedInsteadOfCulled++;
				return REUSED;
			}
		} else if (mem.lowSignificanceFrames < BLOCK_ENTITY_CULL_HOLD_FRAMES) {
			demotionsPreventedByHysteresis++;
			return PROXY;
		}

		return classification;
	}

	private static boolean hasMinimumBlockEntityScreenPresence(double distanceSqr) {
		return distanceSqr <= LOW_SCREEN_SIZE_DISTANCE_SQR;
	}

	private static boolean isFadingOut(long lastChangedFrame) {
		return frameIndex - lastChangedFrame <= FADE_TRANSITION_FRAMES;
	}

	private static float fadeAlpha(byte classification, long lastChangedFrame) {
		long frames = frameIndex - lastChangedFrame;
		if (lastChangedFrame < 0L || frames < 0L || frames > FADE_TRANSITION_FRAMES) return 1.0F;
		float progress = frames / (float) FADE_TRANSITION_FRAMES;
		return classification == CULLED ? 1.0F - progress : Math.max(0.15F, progress);
	}

	// ---- Entity-specific classification ----

	private static final double ENTITY_VISIBLE_DISTANCE_SQR = 64.0D * 64.0D;

	private static byte classifyEntityByContinuousScore(EntityMemory mem, double distanceSqr, boolean inFront,
			boolean lookedAt, boolean important, EntityCategory category) {
		if (category == EntityCategory.CRITICAL) { fullForcedBySafety++; return FULL; }
		if (distanceSqr <= ENTITY_NEAR_DISTANCE_SQR) { fullForcedBySafety++; return FULL; }
		if (lookedAt || important) { fullForcedBySafety++; return FULL; }

		boolean isClearlyVisible = distanceSqr <= ENTITY_VISIBLE_DISTANCE_SQR;
		if (isClearlyVisible && category != EntityCategory.ITEM && category != EntityCategory.OTHER) {
			if (mem.lastClassification <= THROTTLED) return mem.lastClassification;
		}

		double effectiveScore = mem.continuousScore * (0.5D + 0.5D * mem.visibilityDecay);
		double categoryProtection = entityCategoryProtection(category);
		effectiveScore = Math.max(effectiveScore, categoryProtection * 0.30D);
		if (category != EntityCategory.ITEM && category != EntityCategory.OTHER && effectiveScore < DEMOTE_THRESHOLDS[PROXY]) {
			mem.lowSignificanceFrames++;
		} else {
			mem.lowSignificanceFrames = 0;
		}

		if (isClearlyVisible && category != EntityCategory.ITEM && category != EntityCategory.OTHER) {
			effectiveScore = Math.max(effectiveScore, 0.45D);
		}

		byte previous = mem.lastClassification;

		if (previous == UNKNOWN || previous == CULLED) {
			byte raw = scoreToBand(effectiveScore);
			if (isClearlyVisible && category != EntityCategory.ITEM && category != EntityCategory.OTHER) {
				return THROTTLED;
			}
			if (raw == CULLED && category != EntityCategory.ITEM && category != EntityCategory.OTHER
					&& effectiveScore > 0.15D) {
				return PROXY;
			}
			return raw;
		}

		byte desired;
		if (effectiveScore > PROMOTE_THRESHOLDS[previous]) {
			desired = (byte)(previous - 1);
		} else if (effectiveScore < DEMOTE_THRESHOLDS[previous]) {
			desired = (byte)(previous + 1);
		} else {
			return previous;
		}

		if (desired < FULL) desired = FULL;
		if (desired > CULLED) desired = CULLED;

		if (isClearlyVisible && category != EntityCategory.ITEM && category != EntityCategory.OTHER) {
			if (desired > THROTTLED) {
				desired = THROTTLED;
			}
		}

		if (desired == CULLED && category != EntityCategory.ITEM && category != EntityCategory.OTHER) {
			if (frameIndex - mem.lastVisibleFrame <= RECENTLY_VISIBLE_GRACE_FRAMES) {
				entityCullPreventedByRecentlyVisible++;
				return PROXY;
			}
			if (previous <= THROTTLED) {
				entityCullPreventedByHysteresis++;
				return REUSED;
			}
			if (mem.lowSignificanceFrames < ENTITY_HYSTERESIS_HOLD_FRAMES) {
				entityCullPreventedByHysteresis++;
				return PROXY;
			}
			if (effectiveScore > 0.12D) return PROXY;
		}

		if (Math.abs(desired - previous) > 1) {
			singleBandTransitionsEnforced++;
			desired = desired < previous ? (byte)(previous - 1) : (byte)(previous + 1);
		}

		return desired;
	}

	private static byte scoreToBand(double score) {
		if (score > 0.70D) return FULL;
		if (score > 0.50D) return THROTTLED;
		if (score > 0.30D) return REUSED;
		if (score > 0.15D) return PROXY;
		return CULLED;
	}

	private static void updateEntityConfidence(EntityMemory mem, byte newClassification) {
		if (newClassification == mem.predictedClassification) {
			mem.predictionCorrectFrames = Math.min(CONFIDENCE_BUILD_FRAMES, mem.predictionCorrectFrames + 1);
		} else {
			mem.predictionCorrectFrames = Math.max(0, mem.predictionCorrectFrames - 2);
		}
		if (newClassification == mem.lastClassification) {
			mem.stableBandFrames++;
		} else {
			mem.stableBandFrames = 0;
		}
		mem.confidence = Math.max(0.05D, Math.min(1.0D,
			0.5D + Math.min(0.3D, mem.predictionCorrectFrames / (double) CONFIDENCE_BUILD_FRAMES)
			+ Math.min(0.25D, mem.stableBandFrames / (double) STABILITY_REQUIRED_FRAMES)
			+ mem.attentionScore * 0.15D
			- (cameraFastMoving ? 0.15D : 0.0D)));
		if (newClassification != mem.lastClassification) {
			mem.lastChangedFrame = frameIndex;
		}
	}

	/**
	 * Decay visibility for all tracked memories not seen this frame.
	 */
	private static void decayVisibility() {
		var objIterator = objectMemory.long2ObjectEntrySet().fastIterator();
		while (objIterator.hasNext()) {
			var entry = objIterator.next();
			ObjectMemory mem = entry.getValue();
			if (mem.lastSeenFrame < frameIndex) {
				mem.visibilityDecay *= 0.96D;
				mem.continuousScore *= 0.98D;
			}
		}
		var entIterator = entityMemory.int2ObjectEntrySet().fastIterator();
		while (entIterator.hasNext()) {
			var entry = entIterator.next();
			EntityMemory mem = entry.getValue();
			if (mem.lastSeenFrame < frameIndex) {
				mem.visibilityDecay *= 0.96D;
				mem.continuousScore *= 0.98D;
			}
		}
	}

	private static byte classifySimple(double distanceSqr, boolean inFront, boolean lookedAt,
			boolean important, boolean recent, int repeatCount, boolean pressured,
			double score, double renderCost) {
		if (distanceSqr <= NEAR_DISTANCE_SQR || important || lookedAt) return FULL;
		if (!inFront || distanceSqr > TINY_DISTANCE_SQR) return CULLED;
		if (renderCost >= HIGH_RENDER_COST_THRESHOLD && score < LOW_IMPORTANCE_THRESHOLD) return CULLED;
		if (pressured && score < 0.30D) return CULLED;
		if (renderCost >= HIGH_RENDER_COST_THRESHOLD && distanceSqr <= THROTTLED_DISTANCE_SQR) return THROTTLED;
		if (pressured && distanceSqr <= THROTTLED_DISTANCE_SQR) return THROTTLED;
		if (distanceSqr <= THROTTLED_DISTANCE_SQR) return THROTTLED;
		if (distanceSqr >= LOW_SCREEN_SIZE_DISTANCE_SQR) return PROXY;
		if (distanceSqr <= PROXY_DISTANCE_SQR && repeatCount >= 4) return PROXY;
		return CULLED;
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
		incrementCounter(classification, distanceSqr, inFront, important, false, 1, pressured, score, renderCost, null, false);
	}

	// ============================================================
	//  Entity classification and anti-pop
	// ============================================================

	private enum EntityCategory {
		CRITICAL, HOSTILE, VILLAGER, PASSIVE, NEUTRAL, AMBIENT, ITEM, OTHER
	}

	private static EntityCategory classifyEntity(Entity entity) {
		if (entity instanceof Player) return EntityCategory.CRITICAL;
		if (entity instanceof TamableAnimal tamable && tamable.isTame()) return EntityCategory.CRITICAL;
		if (entity.hasCustomName()) return EntityCategory.CRITICAL;
		if (entity.isPassenger() || !entity.getPassengers().isEmpty()) return EntityCategory.CRITICAL;
		if (entity instanceof Villager) return EntityCategory.VILLAGER;
		if (entity instanceof NeutralMob) return EntityCategory.NEUTRAL;
		if (entity instanceof Animal) return EntityCategory.PASSIVE;
		if (entity instanceof AmbientCreature) return EntityCategory.AMBIENT;
		if (entity instanceof Enemy) return EntityCategory.HOSTILE;
		if (entity instanceof Mob mob && (mob.isAggressive() || mob.getTarget() != null)) return EntityCategory.HOSTILE;
		if (entity instanceof LivingEntity) return EntityCategory.PASSIVE;
		if (entity instanceof ItemEntity || entity instanceof net.minecraft.world.entity.ExperienceOrb) return EntityCategory.ITEM;
		return EntityCategory.OTHER;
	}

	private static double entityCategoryProtection(EntityCategory category) {
		return switch (category) {
			case CRITICAL -> 1.0D;
			case HOSTILE -> 0.85D;
			case VILLAGER -> 0.90D;
			case PASSIVE -> 0.75D;
			case NEUTRAL -> 0.80D;
			case AMBIENT -> 0.50D;
			case ITEM -> 0.20D;
			case OTHER -> 0.30D;
		};
	}

	private static double computeEntityScore(double distanceSqr, boolean inFront, boolean lookedAt,
			boolean important, boolean pressured, double renderCost, EntityCategory category,
			Entity entity, double dx, double dy, double dz) {
		double dist = Math.sqrt(distanceSqr);
		double distNorm = Math.min(1.0D, dist / MAX_SIGNIFICANCE_DISTANCE);

		double size = entity.getBbWidth() * entity.getBbHeight();
		double angularSize = size / Math.max(1.0D, dist);
		double screenCoverage = Math.min(1.0D, angularSize * 0.3D);
		screenCoverage = Math.max(0.0D, screenCoverage);

		double distanceFactor = 1.0D - distNorm;

		double typeImportance = entityCategoryProtection(category);
		if (important) typeImportance = Math.max(typeImportance, 0.85D);
		typeImportance = Math.min(1.0D, typeImportance);

		double cameraFocus = 0.0D;
		if (inFront) cameraFocus += 0.2D;
		if (lookedAt) cameraFocus += 0.6D;
		if (cameraVelocityAbs > 0.05D && inFront) {
			double dot = movingTowardDot(dx, dz, dist);
			if (dot > 0.3D) cameraFocus += 0.15D * dot;
		}
		if (category != EntityCategory.ITEM && category != EntityCategory.OTHER
				&& distanceSqr > THROTTLED_DISTANCE_SQR && distanceSqr <= ENTITY_MID_DISTANCE_SQR) {
			cameraFocus += ENTITY_MID_DISTANCE_PROTECTION * 0.5D;
		}
		if (pressured) cameraFocus -= 0.08D;
		cameraFocus = Math.max(0.0D, Math.min(1.0D, cameraFocus));

		double renderCostFactor = 1.0D - renderCost;

		double motionFactor = 0.0D;
		if (cameraRotationSpeed > 1.0D) motionFactor += 0.10D;
		if (cameraVelocityAbs > 0.05D && inFront) {
			double dot = movingTowardDot(dx, dz, dist);
			if (dot > 0.5D) motionFactor += ENTITY_MOVING_TOWARD_BOOST * dot;
		}
		motionFactor = Math.min(0.3D, motionFactor);

		double recentVisibility = 1.0D;
		double animationState = entity instanceof LivingEntity ? 0.8D : 0.3D;
		double temporalConfidence = 0.5D;

		return screenCoverage * WEIGHT_SCREEN_COVERAGE
			+ distanceFactor * WEIGHT_DISTANCE
			+ typeImportance * WEIGHT_TYPE_IMPORTANCE
			+ cameraFocus * WEIGHT_CAMERA_FOCUS
			+ renderCostFactor * WEIGHT_RENDER_COST
			+ temporalConfidence * WEIGHT_TEMPORAL_CONFIDENCE
			+ recentVisibility * WEIGHT_RECENT_VISIBILITY
			+ motionFactor * WEIGHT_MOTION
			+ animationState * WEIGHT_ANIMATION_STATE;
	}

	// ============================================================
	//  Entity memory
	// ============================================================

	private static final class EntityMemory {
		byte lastClassification = UNKNOWN;
		byte previousClassification = UNKNOWN;
		double lastScore;
		double continuousScore;
		double visibilityDecay;
		double historicalImportance;
		double confidence;
		double attentionScore;
		double predictedImportance;
		byte predictedClassification;
		long lastSeenFrame = 1;
		long lastChangedFrame = -1;
		int stableBandFrames;
		int predictionCorrectFrames;
		int offscreenFrames;
		boolean lastCulled;
		double lastDistanceSqr;
		boolean lastInFront;
		EntityCategory category = EntityCategory.OTHER;
		long lastVisibleFrame = NEVER_INTERACTED_FRAME;
		int lowSignificanceFrames;
		long firstSeenFrame = 1;
	}

	private static final class EntityOscillationTracker {
		int oscillationCount;
		long firstOscillationFrame;
		long lastOscillationFrame;
		byte bandA = UNKNOWN;
		byte bandB = UNKNOWN;
	}

	private static void trackEntityOscillation(int entityId, byte newBand) {
		EntityOscillationTracker tracker = entityOscillation.get(entityId);
		if (tracker == null) {
			tracker = new EntityOscillationTracker();
			tracker.firstOscillationFrame = frameIndex;
			tracker.bandA = newBand;
			entityOscillation.put(entityId, tracker);
			return;
		}
		if (tracker.bandA != UNKNOWN && tracker.bandB != UNKNOWN && newBand == tracker.bandA) {
			tracker.oscillationCount++;
			tracker.lastOscillationFrame = frameIndex;
			if (tracker.oscillationCount >= 3) entityCullOscillationEvents++;
			tracker.bandA = newBand;
			tracker.bandB = UNKNOWN;
		} else if (tracker.bandA != UNKNOWN && tracker.bandB == UNKNOWN && newBand != tracker.bandA) {
			tracker.bandB = newBand;
		} else {
			tracker.bandA = newBand;
			tracker.bandB = UNKNOWN;
		}
	}

	private static void updateEntityAttention(EntityMemory mem, boolean lookedAt, boolean inFront,
			double distanceSqr, EntityCategory category) {
		if (lookedAt) mem.attentionScore = Math.min(1.0D, mem.attentionScore + 0.15D);
		else {
			double decayRate = 0.006D + Math.sqrt(distanceSqr) * 0.00008D;
			if (category != EntityCategory.ITEM && category != EntityCategory.OTHER) decayRate *= 0.5D;
			mem.attentionScore = Math.max(0.0D, mem.attentionScore - decayRate);
		}
		if (inFront && distanceSqr < ENTITY_MID_DISTANCE_SQR && !lookedAt)
			mem.attentionScore = Math.min(1.0D, mem.attentionScore + 0.02D);
	}

	private static void updateEntityPrediction(EntityMemory mem, double dx, double dy, double dz,
			double distanceSqr, boolean inFront) {
		if (distanceSqr <= 0.0001D || !inFront) {
			mem.predictedImportance = mem.historicalImportance;
			mem.predictedClassification = mem.lastClassification;
			return;
		}
		double predictedDx = dx - cameraVelocityX * PREDICTION_WINDOW_FRAMES * 0.05D;
		double predictedDz = dz - cameraVelocityZ * PREDICTION_WINDOW_FRAMES * 0.05D;
		double predictedDistSqr = predictedDx * predictedDx + dy * dy + predictedDz * predictedDz;
		Vec3 cameraPos = cameraPosition();
		boolean predictedInFront = true;
		if (cameraPos != null) {
			var look = Minecraft.getInstance().gameRenderer.getMainCamera().getLookVector();
			double dot = (predictedDx * look.x + dy * look.y + predictedDz * look.z) / Math.sqrt(Math.max(predictedDistSqr, 0.0001D));
			predictedInFront = dot > 0.0D;
		}
		if (!predictedInFront) {
			mem.predictedImportance = mem.historicalImportance * 0.6D;
			mem.predictedClassification = CULLED;
		} else {
			double predictedDist = Math.sqrt(predictedDistSqr);
			double predictedScore = Math.max(0.0D, Math.min(1.0D,
				(1.0D - Math.min(1.0D, predictedDist / 256.0D)) * 0.6D + mem.historicalImportance * 0.4D));
			mem.predictedImportance = predictedScore;
			if (predictedScore > 0.65D) mem.predictedClassification = FULL;
			else if (predictedScore > 0.45D) mem.predictedClassification = THROTTLED;
			else if (predictedScore > 0.25D) mem.predictedClassification = REUSED;
			else if (predictedScore > 0.12D) mem.predictedClassification = PROXY;
			else mem.predictedClassification = CULLED;
		}
	}

	// ============================================================
	//  Temporal object memory (for block entities)
	// ============================================================

	private static final class ObjectMemory {
		byte lastClassification = UNKNOWN;
		double lastScore;
		double continuousScore;
		double visibilityDecay;
		double historicalImportance;
		double confidence;
		double attentionScore;
		double predictedImportance;
		byte predictedClassification;
		long lastChangedFrame = -1;
		long lastSeenFrame = 1;
		int stableBandFrames;
		int lookedAtStreak;
		int predictionCorrectFrames;
		float predictionDot;
		boolean recentlyPromoted;
		boolean recentlyDemoted;
		long lastVisibleFrame = NEVER_INTERACTED_FRAME;
		int lowSignificanceFrames;
	}

	private static void advanceObjectMemories() {
		stableFrameCounter++;
	}

	private static void updateAttention(ObjectMemory mem, boolean lookedAt, boolean inFront, double distanceSqr) {
		if (lookedAt) {
			mem.lookedAtStreak++;
			mem.attentionScore = Math.min(1.0D, mem.attentionScore + 0.12D);
		} else {
			mem.lookedAtStreak = 0;
			mem.attentionScore = Math.max(0.0D, mem.attentionScore - (0.008D + Math.sqrt(distanceSqr) * 0.0001D));
		}
		if (inFront && distanceSqr < THROTTLED_DISTANCE_SQR && !lookedAt)
			mem.attentionScore = Math.min(1.0D, mem.attentionScore + 0.03D);
	}

	private static void updatePrediction(ObjectMemory mem, double dx, double dy, double dz, double distanceSqr, boolean inFront) {
		if (distanceSqr <= 0.0001D || !inFront) {
			mem.predictedImportance = mem.historicalImportance;
			mem.predictedClassification = mem.lastClassification;
			return;
		}
		double predictedDx = dx - cameraVelocityX * PREDICTION_WINDOW_FRAMES * 0.05D;
		double predictedDz = dz - cameraVelocityZ * PREDICTION_WINDOW_FRAMES * 0.05D;
		double predictedDistSqr = predictedDx * predictedDx + dy * dy + predictedDz * predictedDz;
		Vec3 cameraPos = cameraPosition();
		boolean predictedInFront = true;
		if (cameraPos != null) {
			var look = Minecraft.getInstance().gameRenderer.getMainCamera().getLookVector();
			double dot = (predictedDx * look.x + dy * look.y + predictedDz * look.z) / Math.sqrt(Math.max(predictedDistSqr, 0.0001D));
			predictedInFront = dot > 0.0D;
			mem.predictionDot = (float) dot;
		}
		if (!predictedInFront) {
			mem.predictedImportance = mem.historicalImportance * 0.5D;
			mem.predictedClassification = CULLED;
		} else {
			double predictedDist = Math.sqrt(predictedDistSqr);
			double predictedScore = Math.max(0.0D, Math.min(1.0D,
				(1.0D - Math.min(1.0D, predictedDist / 192.0D)) * 0.6D + mem.historicalImportance * 0.4D));
			mem.predictedImportance = predictedScore;
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
		if (newClassification == mem.lastClassification) {
			mem.stableBandFrames++;
		} else {
			mem.stableBandFrames = 0;
		}
		mem.confidence = Math.max(0.05D, Math.min(1.0D,
			0.5D + Math.min(0.3D, mem.predictionCorrectFrames / (double) CONFIDENCE_BUILD_FRAMES)
			+ Math.min(0.25D, mem.stableBandFrames / (double) STABILITY_REQUIRED_FRAMES)
			+ mem.attentionScore * 0.15D
			- (cameraFastMoving ? 0.15D : 0.0D)));
		if (newClassification != mem.lastClassification) {
			mem.lastChangedFrame = frameIndex;
		}
	}

	// ============================================================
	//  Counter tracking
	// ============================================================

	private static void incrementCounter(byte classification, double distanceSqr, boolean inFront,
			boolean important, boolean recent, int repeatCount, boolean pressured,
			double score, double renderCost, ObjectMemory mem, boolean isEntity) {
		switch (classification) {
			case FULL -> {
				fullThisFrame++;
				// Track whether FULL came from forced safety or weighted score
				// (safety = lookedAt, nearby, recentInteracted; these are handled
				//  in protectVisibleBlockEntityClassification separately)
				if (distanceSqr <= NEAR_DISTANCE_SQR || important || recent) {
					fullBecauseImportant++;
				} else {
					fullBecauseLookedAt++;
					fullByWeightedScore++;
				}
			}
			case THROTTLED -> {
				throttledThisFrame++;
				if (important) importantButThrottled++;
				if (renderCost >= HIGH_RENDER_COST_THRESHOLD) throttledBecauseHighCost++;
				else if (pressured) throttledBecauseFramePressure++;
				else if (mem != null && mem.predictedImportance < 0.4D) throttledBecausePredictedLow++;
				else throttledBecauseDistance++;
			}
			case REUSED -> {
				reusedThisFrame++;
				if (important) importantButReused++;
				if (cameraStable) reusedBecauseCameraStable++;
				else if (mem != null && mem.confidence > 0.7D && mem.stableBandFrames > STABILITY_REQUIRED_FRAMES) reusedBecauseHysteresis++;
				else reusedBecauseStable++;
			}
			case PROXY -> {
				proxyThisFrame++;
				if (important) importantButProxy++;
				if (distanceSqr >= LOW_SCREEN_SIZE_DISTANCE_SQR) proxyBecauseLowScreenSize++;
				else if (mem != null && mem.confidence > 0.75D) proxyBecauseHighConfidence++;
				else proxyBecauseFarRepeated++;
			}
			case CULLED -> {
				culledThisFrame++;
				if (!inFront) culledBecauseOffscreen++;
				else if (distanceSqr > TINY_DISTANCE_SQR) culledBecauseTiny++;
				else if (renderCost >= HIGH_RENDER_COST_THRESHOLD && score < LOW_IMPORTANCE_THRESHOLD) culledBecauseHighCostLowImportance++;
				else if (pressured && score < 0.30D) culledBecauseLowSignificance++;
				else if (cameraFastMoving && mem != null && mem.confidence < 0.5D) culledBecauseFastCamera++;
				else if (mem != null && mem.predictedClassification == CULLED && mem.confidence > 0.65D) culledBecausePredictedOffscreen++;
				else if (mem != null && mem.confidence < 0.2D && !cameraFastMoving) culledBecauseLowConfidencePromotable++;
				else culledBecauseBudget++;
			}
		}
	}

	private static void incrementEntityCounter(byte classification, double distanceSqr, boolean inFront,
			boolean important, boolean pressured, double score, double renderCost,
			EntityCategory category, EntityMemory mem, boolean wasRendered) {
		switch (classification) {
			case FULL -> {
				fullThisFrame++;
				fullForcedBySafety++;
				if (distanceSqr <= ENTITY_NEAR_DISTANCE_SQR) fullBecauseNearby++;
				else if (important) { fullBecauseImportant++; entityPromotedBecauseImportant++; }
				else if (category == EntityCategory.PASSIVE || category == EntityCategory.HOSTILE || category == EntityCategory.VILLAGER) {
					entityPromotedBecauseLiving++; fullBecauseLookedAt++;
				} else { fullBecauseLookedAt++; }
			}
			case THROTTLED -> {
				throttledThisFrame++;
				if (important) importantButThrottled++;
				throttledBecauseDistance++;
			}
			case REUSED -> {
				reusedThisFrame++;
				if (important) importantButReused++;
				if (cameraStable) reusedBecauseCameraStable++; else reusedBecauseStable++;
			}
			case PROXY -> {
				proxyThisFrame++;
				if (important) importantButProxy++;
				if (distanceSqr >= ENTITY_FAR_DISTANCE_SQR) proxyBecauseLowScreenSize++; else proxyBecauseFarRepeated++;
			}
			case CULLED -> {
				culledThisFrame++;
				if (!inFront) culledBecauseOffscreen++;
				else if (distanceSqr > ENTITY_CULL_DISTANCE_SQR) culledBecauseTiny++;
				else if (pressured && score < 0.30D) culledBecauseLowSignificance++;
				else culledBecauseBudget++;
			}
		}
	}

	// ============================================================
	//  Camera motion tracking
	// ============================================================

	private static void updateCameraMotion() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.gameRenderer == null || minecraft.gameRenderer.getMainCamera() == null) {
			cameraStable = false; cameraFastMoving = false; return;
		}
		Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
		float yaw = minecraft.gameRenderer.getMainCamera().getXRot();
		float pitch = minecraft.gameRenderer.getMainCamera().getYRot();
		if (!Double.isNaN(lastCameraX)) {
			cameraVelocityX = camera.x - lastCameraX;
			cameraVelocityZ = camera.z - lastCameraZ;
			cameraVelocityAbs = Math.sqrt(cameraVelocityX * cameraVelocityX + cameraVelocityZ * cameraVelocityZ);
			cameraAcceleration = Math.abs(cameraVelocityAbs - lastCameraVelocityAbs);
			float dy = yaw - lastCameraYaw;
			float dp = pitch - lastCameraPitch;
			cameraRotationSpeed = Math.sqrt(dy * dy + dp * dp);
			cameraStable = Math.abs(camera.x - lastCameraX) < 0.01D
				&& Math.abs(camera.y - lastCameraY) < 0.01D
				&& Math.abs(camera.z - lastCameraZ) < 0.01D
				&& cameraRotationSpeed < 0.5D;
			cameraFastMoving = cameraVelocityAbs > 0.5D || cameraRotationSpeed > 10.0D;
		}
		lastCameraX = camera.x; lastCameraY = camera.y; lastCameraZ = camera.z;
		lastCameraYaw = yaw; lastCameraPitch = pitch; lastCameraVelocityAbs = cameraVelocityAbs;
	}

	// ============================================================
	//  Memory cleanup
	// ============================================================

	private static void cleanupStaleMemory() {
		if (objectMemory.isEmpty()) return;
		cleanupCounter++;
		if (cleanupCounter < 60) return;
		cleanupCounter = 0;
		var it = objectMemory.long2ObjectEntrySet().fastIterator();
		while (it.hasNext()) {
			var e = it.next();
			if (frameIndex - e.getValue().lastSeenFrame > 200) {
				it.remove(); seenCounts.remove(e.getLongKey());
			}
		}
	}

	private static void cleanupStaleEntityMemory() {
		if (entityMemory.isEmpty()) return;
		if (cleanupCounter % 2 != 0) return;
		var it = entityMemory.int2ObjectEntrySet().fastIterator();
		while (it.hasNext()) {
			var e = it.next();
			if (frameIndex - e.getValue().lastSeenFrame > 120) {
				it.remove(); entityOscillation.remove(e.getIntKey());
			}
		}
	}

	// ============================================================
	//  Utilities
	// ============================================================

	private static Vec3 cameraPosition() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gameRenderer == null || mc.gameRenderer.getMainCamera() == null) return null;
		return mc.gameRenderer.getMainCamera().getPosition();
	}

	private static double computeScore(double distanceSqr, boolean inFront, boolean lookedAt,
			boolean important, boolean recent, int repeatCount, boolean pressured, double renderCost) {
		double dist = Math.sqrt(distanceSqr);
		double distNorm = Math.min(1.0D, dist / MAX_SIGNIFICANCE_DISTANCE);

		double screenCoverage = 1.0D - distNorm;
		screenCoverage = Math.max(0.0D, Math.min(1.0D, screenCoverage));

		double distanceFactor = 1.0D - distNorm;

		double typeImportance = important ? 1.0D : 0.3D;
		if (recent) typeImportance = Math.max(typeImportance, 0.7D);
		if (repeatCount >= 4) typeImportance = Math.max(typeImportance, 0.5D);
		typeImportance = Math.min(1.0D, typeImportance);

		double cameraFocus = 0.0D;
		if (inFront) cameraFocus += 0.3D;
		if (lookedAt) cameraFocus += 0.7D;
		if (pressured) cameraFocus -= 0.1D;
		cameraFocus = Math.max(0.0D, Math.min(1.0D, cameraFocus));

		double renderCostFactor = 1.0D - renderCost;

		double motionFactor = 0.0D;
		if (cameraRotationSpeed > 1.0D) motionFactor += 0.15D;
		if (cameraRotationSpeed > 5.0D) motionFactor += 0.10D;
		if (cameraVelocityAbs > 0.05D && inFront) motionFactor += 0.10D;
		motionFactor = Math.min(0.35D, motionFactor);

		double temporalConfidence = 0.5D;
		double recentVisibility = 1.0D;
		double animationState = 0.5D;

		return screenCoverage * WEIGHT_SCREEN_COVERAGE
			+ distanceFactor * WEIGHT_DISTANCE
			+ typeImportance * WEIGHT_TYPE_IMPORTANCE
			+ cameraFocus * WEIGHT_CAMERA_FOCUS
			+ renderCostFactor * WEIGHT_RENDER_COST
			+ temporalConfidence * WEIGHT_TEMPORAL_CONFIDENCE
			+ recentVisibility * WEIGHT_RECENT_VISIBILITY
			+ motionFactor * WEIGHT_MOTION
			+ animationState * WEIGHT_ANIMATION_STATE;
	}

	private static boolean isLookedAt(long key, double dx, double dy, double dz, double dsqr) {
		if (dsqr <= 0.0001D) return true;
		Minecraft mc = Minecraft.getInstance();
		if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().asLong() == key) return true;
		return lookDot(dx, dy, dz, dsqr) >= LOOKED_AT_DOT;
	}

	private static boolean isInFront(double dx, double dy, double dz, double dsqr) {
		return dsqr <= 0.0001D || lookDot(dx, dy, dz, dsqr) > 0.0D;
	}

	private static boolean isLookedAtDirection(double dx, double dy, double dz, double dsqr) {
		return dsqr <= 0.0001D || lookDot(dx, dy, dz, dsqr) >= LOOKED_AT_DOT;
	}

	private static boolean isMovingToward(double dx, double dz, double distance) {
		return cameraVelocityAbs > 0.05D && movingTowardDot(dx, dz, distance) > 0.5D;
	}

	private static double movingTowardDot(double dx, double dz, double distance) {
		return (dx * cameraVelocityX + dz * cameraVelocityZ) / Math.max(0.001D, distance * cameraVelocityAbs);
	}

	private static double lookDot(double dx, double dy, double dz, double dsqr) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gameRenderer == null || mc.gameRenderer.getMainCamera() == null) return 0.0D;
		var look = mc.gameRenderer.getMainCamera().getLookVector();
		return (dx * look.x + dy * look.y + dz * look.z) / Math.sqrt(dsqr);
	}

	private static void recordInteractionTarget() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.options == null || (!mc.options.keyUse.isDown() && !mc.options.keyAttack.isDown())) return;
		if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK)
			recentlyInteracted.put(hit.getBlockPos().asLong(), frameIndex);
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
	//  Importance and render cost helpers
	// ============================================================

	private static boolean isImportant(BlockEntity be) {
		BlockEntityType<?> t = be.getType();
		BlockState state = be.getBlockState();
		return t == BlockEntityType.BEACON || t == BlockEntityType.CONDUIT || t == BlockEntityType.END_PORTAL
			|| t == BlockEntityType.END_GATEWAY || t == BlockEntityType.MOB_SPAWNER
			|| t == BlockEntityType.TRIAL_SPAWNER || t == BlockEntityType.VAULT
			|| state.getLightEmission() > 0
			|| state.isRandomlyTicking()
			|| state.getRenderShape() != RenderShape.MODEL;
	}

	private static boolean isImportantEntity(Entity e) {
		return e instanceof Player || e instanceof Projectile
			|| e instanceof FallingBlockEntity || e.hasGlowingTag() || e.hasCustomName()
			|| e.isPassenger() || !e.getPassengers().isEmpty() || e.displayFireAnimation()
			|| e instanceof TamableAnimal tamable && tamable.isTame()
			|| e instanceof Villager
			|| e instanceof Mob mob && (mob.isAggressive() || mob.getTarget() != null || mob.isLeashed() || mob.isNoAi())
			|| isLargeLivingEntity(e);
	}

	private static boolean isImportantParticle(ParticleOptions opts) {
		return opts.getType() == ParticleTypes.EXPLOSION || opts.getType() == ParticleTypes.EXPLOSION_EMITTER
			|| opts.getType() == ParticleTypes.FLASH || opts.getType() == ParticleTypes.DAMAGE_INDICATOR
			|| opts.getType() == ParticleTypes.TOTEM_OF_UNDYING;
	}

	private static double blockEntityRenderCost(BlockEntity be) {
		BlockEntityType<?> t = be.getType();
		if (t == BlockEntityType.BEACON) return 0.85D;
		if (t == BlockEntityType.CONDUIT) return 0.80D;
		if (t == BlockEntityType.SHULKER_BOX) return 0.75D;
		if (t == BlockEntityType.CHEST || t == BlockEntityType.TRAPPED_CHEST) return 0.70D;
		if (t == BlockEntityType.ENDER_CHEST || t == BlockEntityType.BELL) return 0.65D;
		if (t == BlockEntityType.BARREL || t == BlockEntityType.CHISELED_BOOKSHELF) return 0.55D;
		if (t == BlockEntityType.CAMPFIRE || t == BlockEntityType.ENCHANTING_TABLE) return 0.60D;
		if (t == BlockEntityType.MOB_SPAWNER || t == BlockEntityType.TRIAL_SPAWNER || t == BlockEntityType.VAULT) return 0.55D;
		if (t == BlockEntityType.BANNER) return 0.50D;
		if (t == BlockEntityType.HANGING_SIGN) return 0.45D;
		if (t == BlockEntityType.SIGN || t == BlockEntityType.SKULL || t == BlockEntityType.PISTON || t == BlockEntityType.BED) return 0.40D;
		if (t == BlockEntityType.LECTERN || t == BlockEntityType.DECORATED_POT) return 0.35D;
		if (t == BlockEntityType.BRUSHABLE_BLOCK || t == BlockEntityType.END_PORTAL || t == BlockEntityType.END_GATEWAY) return 0.30D;
		if (isModdedBlockEntity(be)) return dynamicBlockEntityCost(be);
		return 0.50D;
	}

	private static double entityRenderCost(Entity e) {
		if (e instanceof Player) return 0.90D;
		if (e instanceof LivingEntity) return isModdedEntity(e) ? dynamicLivingEntityCost(e) : 0.75D;
		if (e instanceof Projectile) return 0.25D;
		if (e instanceof FallingBlockEntity) return 0.30D;
		if (e.hasGlowingTag()) return 0.50D;
		if (e.hasCustomName()) return 0.40D;
		if (e.isPassenger()) return 0.50D;
		if (!e.getPassengers().isEmpty()) return 0.55D;
		if (e.displayFireAnimation()) return 0.35D;
		if (isModdedEntity(e)) return dynamicNonLivingEntityCost(e);
		return 0.30D;
	}

	private static boolean isModdedBlockEntity(BlockEntity be) {
		return isModdedKey(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()));
	}

	private static boolean isModdedEntity(Entity entity) {
		return isModdedKey(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
	}

	private static boolean isModdedKey(ResourceLocation key) {
		return key != null && !"minecraft".equals(key.getNamespace());
	}

	private static void recordDynamicModNamespace(ResourceLocation key) {
		if (!isModdedKey(key)) return;
		if ("none".equals(firstDynamicModNamespace)) firstDynamicModNamespace = key.getNamespace();
		lastDynamicModNamespace = key.getNamespace();
	}

	private static double dynamicBlockEntityCost(BlockEntity be) {
		dynamicModdedBlockEntityCosted++;
		recordDynamicModNamespace(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()));
		BlockState state = be.getBlockState();
		double cost = 0.55D;
		if (state.getLightEmission() > 0) cost += 0.10D;
		if (state.isRandomlyTicking()) cost += 0.10D;
		if (state.getRenderShape() != RenderShape.MODEL) cost += 0.10D;
		return Math.min(0.85D, cost);
	}

	private static boolean isLargeLivingEntity(Entity entity) {
		return entity instanceof LivingEntity && entity.getBbWidth() * entity.getBbHeight() >= 3.0F;
	}

	private static double dynamicLivingEntityCost(Entity entity) {
		dynamicModdedLivingEntityCosted++;
		recordDynamicModNamespace(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
		double cost = 0.65D + Math.min(0.20D, entity.getBbWidth() * entity.getBbHeight() * 0.04D);
		if (entity.hasCustomName() || entity.hasGlowingTag()) cost += 0.10D;
		if (entity.isPassenger() || !entity.getPassengers().isEmpty()) cost += 0.10D;
		return Math.min(0.90D, cost);
	}

	private static double dynamicNonLivingEntityCost(Entity entity) {
		dynamicModdedNonLivingEntityCosted++;
		recordDynamicModNamespace(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
		double cost = 0.25D + Math.min(0.30D, entity.getBbWidth() * entity.getBbHeight() * 0.06D);
		if (entity.isVehicle()) cost += 0.15D;
		return Math.min(0.70D, cost);
	}

	private static boolean shouldSampleDebugEntity(Entity entity, double distanceSqr) {
		return distanceSqr > 40.0D * 40.0D && distanceSqr < 100.0D * 100.0D
			&& (entity instanceof Sheep || isModdedEntity(entity));
	}

	private static double particleRenderCost(ParticleOptions opts) {
		var t = opts.getType();
		if (t == ParticleTypes.EXPLOSION_EMITTER) return 0.90D;
		if (t == ParticleTypes.EXPLOSION || t == ParticleTypes.FIREWORK) return 0.80D;
		if (t == ParticleTypes.TOTEM_OF_UNDYING) return 0.70D;
		if (t == ParticleTypes.ELDER_GUARDIAN) return 0.60D;
		if (t == ParticleTypes.DAMAGE_INDICATOR) return 0.40D;
		if (t == ParticleTypes.FLASH) return 0.30D;
		if (t == ParticleTypes.SMOKE || t == ParticleTypes.WHITE_SMOKE || t == ParticleTypes.CLOUD) return 0.20D;
		if (t == ParticleTypes.ASH || t == ParticleTypes.MYCELIUM || t == ParticleTypes.SPORE_BLOSSOM_AIR
			|| t == ParticleTypes.CRIMSON_SPORE || t == ParticleTypes.WARPED_SPORE) return 0.15D;
		if (t == ParticleTypes.RAIN || t == ParticleTypes.SNOWFLAKE || t == ParticleTypes.UNDERWATER) return 0.10D;
		return 0.25D;
	}

	// ============================================================
	//  Debug logging
	// ============================================================

	private static void debugLogEntity(Entity entity, double dsqr, double score,
			byte prevBand, byte newBand, EntityMemory mem) {
		if (++debugLogCounter % 10 != 0) return;
		double dist = Math.sqrt(dsqr);
		String reason = "normal";
		if (newBand == CULLED && prevBand != CULLED) {
			if (!mem.lastInFront) reason = "offscreen";
			else if (dsqr > ENTITY_CULL_DISTANCE_SQR) reason = "too far";
			else reason = "low significance";
		} else if (newBand != CULLED && prevBand == CULLED) reason = "re-entered view";
		OptiminiumMod.LOGGER.debug(
			"Optiminium entity sig: type={}, id={}, dist={:.1f}, score={:.3f}, prev={}, new={}, reason={}, cat={}, inFront={}, vel={:.3f}",
			entity.getType().getDescriptionId(), entity.getId(), dist, score,
			bandName(prevBand), bandName(newBand), reason, mem.category.name(),
			mem.lastInFront, cameraVelocityAbs);
	}

	private static String bandName(byte b) {
		return switch (b) { case FULL -> "FULL"; case THROTTLED -> "THROTTLED";
			case REUSED -> "REUSED"; case PROXY -> "PROXY";
			case CULLED -> "CULLED"; default -> "UNKNOWN"; };
	}

	// ============================================================
	//  Snapshot / Reset / Diagnostics
	// ============================================================

	public static RenderBudget renderBudget() {
		return currentBudget;
	}

	/**
	 * Public accessor for camera position, used by Sodium compat mixins.
	 */
	public static Vec3 getCameraPosition() {
		return cameraPosition();
	}

	private static void updateBudget() {
		int totalThisFrame = fullThisFrame + throttledThisFrame + reusedThisFrame + proxyThisFrame + culledThisFrame;
		budgetCulledFraction = totalThisFrame > 0 ? (double)culledThisFrame / totalThisFrame : 0.0D;

		if (budgetCulledFraction > 0.40D || (cameraFastMoving && budgetCulledFraction > 0.25D)) {
			if (currentBudget != RenderBudget.EMERGENCY) budgetPressureFrames = 0;
			budgetPressureFrames++;
			if (budgetPressureFrames >= 5) { currentBudget = RenderBudget.EMERGENCY; return; }
		}
		if (budgetCulledFraction > 0.25D || (budgetCulledFraction > 0.15D && cameraFastMoving)) {
			if (currentBudget != RenderBudget.HEAVY_PRESSURE && currentBudget != RenderBudget.EMERGENCY) budgetPressureFrames = 0;
			budgetPressureFrames++;
			if (budgetPressureFrames >= 5) { currentBudget = RenderBudget.HEAVY_PRESSURE; return; }
		}
		if (budgetCulledFraction > 0.12D) {
			if (currentBudget != RenderBudget.MEDIUM_PRESSURE && currentBudget != RenderBudget.HEAVY_PRESSURE
					&& currentBudget != RenderBudget.EMERGENCY) budgetPressureFrames = 0;
			budgetPressureFrames++;
			if (budgetPressureFrames >= 5) { currentBudget = RenderBudget.MEDIUM_PRESSURE; return; }
		}
		if (currentBudget != RenderBudget.NORMAL) {
			budgetPressureFrames = Math.max(0, budgetPressureFrames - 1);
			if (budgetPressureFrames <= 0 && budgetCulledFraction < 0.10D) currentBudget = RenderBudget.NORMAL;
		}
	}

	private static void accumulateTotals() {
		updateBudget();
		fullTotal += fullThisFrame; throttledTotal += throttledThisFrame;
		reusedTotal += reusedThisFrame; proxyTotal += proxyThisFrame; culledTotal += culledThisFrame;
	}

	private static void resetFrameCounters() {
		fullThisFrame = 0; throttledThisFrame = 0; reusedThisFrame = 0;
		proxyThisFrame = 0; culledThisFrame = 0;
	}

	public static String diagnosticLine() {
		return snapshot().toLine();
	}

	public static String diagnosticLine(int trackedObjectFallback) {
		return snapshot(trackedObjectFallback).toLine();
	}

	/**
	 * Returns the top expensive objects line (cached, only re-sorts on dirty).
	 */
	public static String topExpensiveObjectsLine() {
		if (!topExpensiveDirty) return topExpensiveCached;
		if (topExpensiveObjects.isEmpty()) {
			topExpensiveCached = "none";
		} else {
			var sorted = new java.util.ArrayList<double[]>(topExpensiveObjects);
			sorted.sort(EXPENSIVE_COMPARATOR);
			StringBuilder sb = new StringBuilder("top10Expensive=");
			for (int i = 0; i < sorted.size(); i++) {
				if (i > 0) sb.append("|");
				sb.append(String.format("key=%.0f,cost=%.4f", sorted.get(i)[0], sorted.get(i)[1]));
			}
			topExpensiveCached = sb.toString();
		}
		topExpensiveDirty = false;
		return topExpensiveCached;
	}

	public static Snapshot snapshot() {
		return snapshot(0);
	}

	public static Snapshot snapshot(int trackedObjectFallback) {
		double avgScore = accumulatedScoreCount > 0 ? accumulatedContinuousScores / accumulatedScoreCount : 0.0D;
		double avgConf = accumulatedScoreCount > 0 ? accumulatedConfidence / accumulatedScoreCount : 0.0D;
		double avgCost = accumulatedScoreCount > 0 ? accumulatedRenderCost / accumulatedScoreCount : 0.0D;
		double avgCoverage = accumulatedScoreCount > 0 ? accumulatedScreenCoverage / accumulatedScoreCount : 0.0D;
		double avgTemporal = accumulatedScoreCount > 0 ? accumulatedTemporalScore / accumulatedScoreCount : 0.0D;
		double hiScore = highestVisualSignificance;
		double loScore = lowestVisualSignificance == Double.POSITIVE_INFINITY ? 0.0D : lowestVisualSignificance;
		return new Snapshot(
			fullTotal + fullThisFrame, throttledTotal + throttledThisFrame,
			reusedTotal + reusedThisFrame, proxyTotal + proxyThisFrame, culledTotal + culledThisFrame,
			fullBecauseNearby, fullBecauseImportant, fullBecauseLookedAt,
			fullBecauseRecentlyInteracted, fullBecauseHighConfidence,
			throttledBecauseDistance, throttledBecauseFramePressure,
			throttledBecauseHighCost, throttledBecausePredictedLow,
			reusedBecauseStable, reusedBecauseCameraStable, reusedBecauseHysteresis,
			proxyBecauseFarRepeated, proxyBecauseLowScreenSize, proxyBecauseHighConfidence,
			culledBecauseOffscreen, culledBecauseBudget, culledBecauseTiny,
			culledBecauseLowSignificance, culledBecauseHighCostLowImportance,
			culledBecausePredictedOffscreen, culledBecauseLowConfidencePromotable, culledBecauseFastCamera,
			blockEntityCullPreventedByVisibility, blockEntityCullPreventedByRecentlyVisible,
			blockEntityCullPreventedByLookedAt, blockEntityDowngradedToReusedInsteadOfCulled,
			blockEntityVisibleCullEvents,
			entityCullPreventedByHysteresis, entityCullPreventedByRecentlyVisible,
			entityCullPreventedByMiddleDistance, entityCullPreventedByMovingToward,
			entityPromotedBecauseLiving, entityPromotedBecauseImportant,
			entityBandTransitions, entityCullOscillationEvents,
			dynamicModdedBlockEntityCosted, dynamicModdedLivingEntityCosted,
			dynamicModdedNonLivingEntityCosted, dynamicModdedEntityCulled,
			firstDynamicModNamespace, lastDynamicModNamespace,
			averageSignificanceMs(), worstSignificanceNanos / 1_000_000.0D,
			mostCommonReason(),
			nearestDistanceSqr == Double.POSITIVE_INFINITY ? -1.0D : Math.sqrt(nearestDistanceSqr),
			cameraVelocityAbs, cameraRotationSpeed, cameraFastMoving, cameraStable,
			Math.max(objectMemory.size(), trackedObjectFallback), entityMemory.size(),
			promotionsPreventedByHysteresis, demotionsPreventedByHysteresis,
			promotionsPreventedByConfidence, demotionsPreventedByConfidence,
			singleBandTransitionsEnforced, avgScore, avgConf,
			avgCost, avgCoverage, avgTemporal, hiScore, loScore,
			topExpensiveObjectsLine(),
			fullForcedBySafety, fullByWeightedScore,
			importantButThrottled, importantButReused, importantButProxy
		);
	}

	public static void reset() {
		topExpensiveObjects.clear();
		topExpensiveDirty = true;
		topExpensiveCached = "none";
		highestVisualSignificance = 0.0D;
		lowestVisualSignificance = Double.POSITIVE_INFINITY;
		accumulatedRenderCost = 0.0D;
		accumulatedScreenCoverage = 0.0D;
		accumulatedTemporalScore = 0.0D;
		fullForcedBySafety = 0L; fullByWeightedScore = 0L;
		importantButThrottled = 0L; importantButReused = 0L; importantButProxy = 0L;
		fullThisFrame = 0; throttledThisFrame = 0; reusedThisFrame = 0; proxyThisFrame = 0; culledThisFrame = 0;
		fullTotal = 0L; throttledTotal = 0L; reusedTotal = 0L; proxyTotal = 0L; culledTotal = 0L;
		fullBecauseNearby = 0L; fullBecauseImportant = 0L; fullBecauseLookedAt = 0L;
		fullBecauseRecentlyInteracted = 0L; fullBecauseHighConfidence = 0L;
		throttledBecauseDistance = 0L; throttledBecauseFramePressure = 0L;
		throttledBecauseHighCost = 0L; throttledBecausePredictedLow = 0L;
		reusedBecauseStable = 0L; reusedBecauseCameraStable = 0L; reusedBecauseHysteresis = 0L;
		proxyBecauseFarRepeated = 0L; proxyBecauseLowScreenSize = 0L; proxyBecauseHighConfidence = 0L;
		culledBecauseOffscreen = 0L; culledBecauseBudget = 0L; culledBecauseTiny = 0L;
		culledBecauseLowSignificance = 0L; culledBecauseHighCostLowImportance = 0L;
		culledBecausePredictedOffscreen = 0L; culledBecauseLowConfidencePromotable = 0L; culledBecauseFastCamera = 0L;
		blockEntityCullPreventedByVisibility = 0L; blockEntityCullPreventedByRecentlyVisible = 0L;
		blockEntityCullPreventedByLookedAt = 0L; blockEntityDowngradedToReusedInsteadOfCulled = 0L;
		blockEntityVisibleCullEvents = 0L;
		entityCullPreventedByHysteresis = 0L; entityCullPreventedByRecentlyVisible = 0L;
		entityCullPreventedByMiddleDistance = 0L; entityCullPreventedByMovingToward = 0L;
		entityPromotedBecauseLiving = 0L; entityPromotedBecauseImportant = 0L;
		entityBandTransitions = 0L; entityCullOscillationEvents = 0L;
		dynamicModdedBlockEntityCosted = 0L; dynamicModdedLivingEntityCosted = 0L;
		dynamicModdedNonLivingEntityCosted = 0L; dynamicModdedEntityCulled = 0L;
		firstDynamicModNamespace = "none"; lastDynamicModNamespace = "none";
		promotionsPreventedByHysteresis = 0L; demotionsPreventedByHysteresis = 0L;
		promotionsPreventedByConfidence = 0L; demotionsPreventedByConfidence = 0L;
		singleBandTransitionsEnforced = 0L;
		accumulatedContinuousScores = 0.0D; accumulatedConfidence = 0.0D; accumulatedScoreCount = 0L;
		significanceNanos = 0L; worstSignificanceNanos = 0L; profiledObjects = 0L;
		nearestDistanceSqr = Double.POSITIVE_INFINITY;
		seenCounts.clear(); recentlyInteracted.clear(); beClassifications.clear();
		objectMemory.clear(); entityMemory.clear(); entityOscillation.clear();
		cleanupCounter = 0;
		cameraStable = false; cameraFastMoving = false; cameraVelocityAbs = 0.0D;
		cameraRotationSpeed = 0.0D; cameraAcceleration = 0.0D; lastCameraVelocityAbs = 0.0D;
		lastCameraX = Double.NaN; lastCameraY = Double.NaN; lastCameraZ = Double.NaN;
		lastCameraYaw = 0.0f; lastCameraPitch = 0.0f; stableFrameCounter = 0;
		debugEntityId = -1; debugLogCounter = 0;
	}

	private static double averageSignificanceMs() {
		return profiledObjects <= 0L ? 0.0D : significanceNanos / 1_000_000.0D / profiledObjects;
	}

	private static String mostCommonReason() {
		String reason = "none"; long count = 0L;
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
		if (blockEntityCullPreventedByVisibility > count) { reason = "blockEntityCullPreventedByVisibility"; count = blockEntityCullPreventedByVisibility; }
		if (blockEntityCullPreventedByRecentlyVisible > count) { reason = "blockEntityCullPreventedByRecentlyVisible"; count = blockEntityCullPreventedByRecentlyVisible; }
		if (blockEntityCullPreventedByLookedAt > count) { reason = "blockEntityCullPreventedByLookedAt"; count = blockEntityCullPreventedByLookedAt; }
		if (blockEntityDowngradedToReusedInsteadOfCulled > count) { reason = "blockEntityDowngradedToReusedInsteadOfCulled"; count = blockEntityDowngradedToReusedInsteadOfCulled; }
		if (blockEntityVisibleCullEvents > count) { reason = "blockEntityVisibleCullEvents"; count = blockEntityVisibleCullEvents; }
		if (entityCullPreventedByHysteresis > count) { reason = "entityCullPreventedByHysteresis"; count = entityCullPreventedByHysteresis; }
		if (entityCullPreventedByRecentlyVisible > count) { reason = "entityCullPreventedByRecentlyVisible"; count = entityCullPreventedByRecentlyVisible; }
		if (entityCullPreventedByMiddleDistance > count) { reason = "entityCullPreventedByMiddleDistance"; count = entityCullPreventedByMiddleDistance; }
		if (entityCullPreventedByMovingToward > count) { reason = "entityCullPreventedByMovingToward"; count = entityCullPreventedByMovingToward; }
		if (entityPromotedBecauseLiving > count) { reason = "entityPromotedBecauseLiving"; count = entityPromotedBecauseLiving; }
		if (entityPromotedBecauseImportant > count) { reason = "entityPromotedBecauseImportant"; count = entityPromotedBecauseImportant; }
		if (entityBandTransitions > count) { reason = "entityBandTransitions"; count = entityBandTransitions; }
		if (entityCullOscillationEvents > count) { reason = "entityCullOscillationEvents"; count = entityCullOscillationEvents; }
		if (dynamicModdedBlockEntityCosted > count) { reason = "dynamicModdedBlockEntityCosted"; count = dynamicModdedBlockEntityCosted; }
		if (dynamicModdedLivingEntityCosted > count) { reason = "dynamicModdedLivingEntityCosted"; count = dynamicModdedLivingEntityCosted; }
		if (dynamicModdedNonLivingEntityCosted > count) { reason = "dynamicModdedNonLivingEntityCosted"; count = dynamicModdedNonLivingEntityCosted; }
		if (dynamicModdedEntityCulled > count) { reason = "dynamicModdedEntityCulled"; count = dynamicModdedEntityCulled; }
		return reason;
	}

	// ============================================================
	//  Snapshot record
	// ============================================================

	public record Snapshot(
		long full, long throttled, long reused, long proxy, long culled,
		long fullBecauseNearby, long fullBecauseImportant, long fullBecauseLookedAt,
		long fullBecauseRecentlyInteracted, long fullBecauseHighConfidence,
		long throttledBecauseDistance, long throttledBecauseFramePressure,
		long throttledBecauseHighCost, long throttledBecausePredictedLow,
		long reusedBecauseStable, long reusedBecauseCameraStable, long reusedBecauseHysteresis,
		long proxyBecauseFarRepeated, long proxyBecauseLowScreenSize, long proxyBecauseHighConfidence,
		long culledBecauseOffscreen, long culledBecauseBudget, long culledBecauseTiny,
		long culledBecauseLowSignificance, long culledBecauseHighCostLowImportance,
		long culledBecausePredictedOffscreen, long culledBecauseLowConfidencePromotable,
		long culledBecauseFastCamera,
		long blockEntityCullPreventedByVisibility, long blockEntityCullPreventedByRecentlyVisible,
		long blockEntityCullPreventedByLookedAt, long blockEntityDowngradedToReusedInsteadOfCulled,
		long blockEntityVisibleCullEvents,
		long entityCullPreventedByHysteresis, long entityCullPreventedByRecentlyVisible,
		long entityCullPreventedByMiddleDistance, long entityCullPreventedByMovingToward,
		long entityPromotedBecauseLiving, long entityPromotedBecauseImportant,
		long entityBandTransitions, long entityCullOscillationEvents,
		long dynamicModdedBlockEntityCosted, long dynamicModdedLivingEntityCosted,
		long dynamicModdedNonLivingEntityCosted, long dynamicModdedEntityCulled,
		String firstDynamicModNamespace, String lastDynamicModNamespace,
		double significanceCpuMs, double worstSignificanceCpuMs,
		String mostCommonReason, double nearestDistance,
		double cameraVelocity, double cameraRotationSpeed,
		boolean cameraFastMoving, boolean cameraStable,
		int trackedObjects, int trackedEntities,
		long promotionsPreventedByHysteresis, long demotionsPreventedByHysteresis,
		long promotionsPreventedByConfidence, long demotionsPreventedByConfidence,
		long singleBandTransitionsEnforced,
		double averageSignificanceScore, double averageConfidence,
		double averageRenderCost, double averageScreenCoverage,
		double averageTemporalScore, double highestVisualSignificance,
		double lowestVisualSignificance, String topExpensiveObjects,
		long fullForcedBySafety, long fullByWeightedScore,
		long importantButThrottled, long importantButReused,
		long importantButProxy
	) {
		private String toLine() {
			return "significanceBands=full:" + full + ",throttled:" + throttled
				+ ",reused:" + reused + ",proxy:" + proxy + ",culled:" + culled
				+ ",fullBecauseNearby:" + fullBecauseNearby
				+ ",fullBecauseImportant:" + fullBecauseImportant
				+ ",fullBecauseLookedAt:" + fullBecauseLookedAt
				+ ",fullBecauseRecentlyInteracted:" + fullBecauseRecentlyInteracted
				+ ",fullBecauseHighConfidence:" + fullBecauseHighConfidence
				+ ",fullForcedBySafety:" + fullForcedBySafety
				+ ",fullByWeightedScore:" + fullByWeightedScore
				+ ",importantButThrottled:" + importantButThrottled
				+ ",importantButReused:" + importantButReused
				+ ",importantButProxy:" + importantButProxy
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
				+ ",blockEntityCullPreventedByVisibility:" + blockEntityCullPreventedByVisibility
				+ ",blockEntityCullPreventedByRecentlyVisible:" + blockEntityCullPreventedByRecentlyVisible
				+ ",blockEntityCullPreventedByLookedAt:" + blockEntityCullPreventedByLookedAt
				+ ",blockEntityDowngradedToReusedInsteadOfCulled:" + blockEntityDowngradedToReusedInsteadOfCulled
				+ ",blockEntityVisibleCullEvents:" + blockEntityVisibleCullEvents
				+ ",entityCullPreventedByHysteresis:" + entityCullPreventedByHysteresis
				+ ",entityCullPreventedByRecentlyVisible:" + entityCullPreventedByRecentlyVisible
				+ ",entityCullPreventedByMiddleDistance:" + entityCullPreventedByMiddleDistance
				+ ",entityCullPreventedByMovingToward:" + entityCullPreventedByMovingToward
				+ ",entityPromotedBecauseLiving:" + entityPromotedBecauseLiving
				+ ",entityPromotedBecauseImportant:" + entityPromotedBecauseImportant
				+ ",entityBandTransitions:" + entityBandTransitions
				+ ",entityCullOscillationEvents:" + entityCullOscillationEvents
				+ ",dynamicModdedBlockEntityCosted:" + dynamicModdedBlockEntityCosted
				+ ",dynamicModdedLivingEntityCosted:" + dynamicModdedLivingEntityCosted
				+ ",dynamicModdedNonLivingEntityCosted:" + dynamicModdedNonLivingEntityCosted
				+ ",dynamicModdedEntityCulled:" + dynamicModdedEntityCulled
				+ ",firstDynamicModNamespace:" + firstDynamicModNamespace
				+ ",lastDynamicModNamespace:" + lastDynamicModNamespace
				+ ",significanceCpuMs:" + String.format("%.4f", significanceCpuMs)
				+ ",worstSignificanceCpuMs:" + String.format("%.4f", worstSignificanceCpuMs)
				+ ",mostCommonReason:" + mostCommonReason
				+ ",nearestDistance:" + String.format("%.1f", nearestDistance)
				+ ",cameraVelocity:" + String.format("%.3f", cameraVelocity)
				+ ",cameraRotationSpeed:" + String.format("%.1f", cameraRotationSpeed)
				+ ",cameraFastMoving:" + cameraFastMoving + ",cameraStable:" + cameraStable
				+ ",trackedObjects:" + trackedObjects + ",trackedEntities:" + trackedEntities
				+ ",promotionsPreventedByHysteresis:" + promotionsPreventedByHysteresis
				+ ",demotionsPreventedByHysteresis:" + demotionsPreventedByHysteresis
				+ ",promotionsPreventedByConfidence:" + promotionsPreventedByConfidence
				+ ",demotionsPreventedByConfidence:" + demotionsPreventedByConfidence
				+ ",singleBandTransitionsEnforced:" + singleBandTransitionsEnforced
				+ ",avgSignificanceScore:" + String.format("%.4f", averageSignificanceScore)
				+ ",avgConfidence:" + String.format("%.4f", averageConfidence)
				+ ",avgRenderCost:" + String.format("%.4f", averageRenderCost)
				+ ",avgScreenCoverage:" + String.format("%.4f", averageScreenCoverage)
				+ ",avgTemporalScore:" + String.format("%.4f", averageTemporalScore)
				+ ",highestVisualSignificance:" + String.format("%.4f", highestVisualSignificance)
				+ ",lowestVisualSignificance:" + String.format("%.4f", lowestVisualSignificance)
				+ "," + topExpensiveObjects;
		}
	}
}