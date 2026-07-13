package net.optiminium.client;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.optiminium.OptiminiumMod;
import net.optiminium.optimization.OptiminiumSettings;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
/**
 * Second-generation Visual Importance Engine with entity anti-pop protection.
 * 
 * OPTIMIZED: Staggered evaluation, cached results, reduced per-frame overhead.
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
	private static final int RECENTLY_LOOKED_AT_GRACE_FRAMES = 35;
	private static final int STABILITY_REQUIRED_FRAMES = 15;
	private static final int FADE_TRANSITION_FRAMES = 10;
	private static final double IMPORTANCE_DEBT_LIMIT = 1.0D;

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

	// ---- Weighted attention score factors ----
	private static final double ATTENTION_WEIGHT_VISUAL_IMPORTANCE = 0.18D;
	private static final double ATTENTION_WEIGHT_GAMEPLAY_IMPORTANCE = 0.16D;
	private static final double ATTENTION_WEIGHT_SAFETY_IMPORTANCE = 0.13D;
	private static final double ATTENTION_WEIGHT_CONFIDENCE_UNCERTAINTY = 0.10D;
	private static final double ATTENTION_WEIGHT_POP_RISK = 0.12D;
	private static final double ATTENTION_WEIGHT_RECENT_VISIBILITY = 0.08D;
	private static final double ATTENTION_WEIGHT_RECENT_INTERACTION = 0.06D;
	private static final double ATTENTION_WEIGHT_IMPORTANCE_DEBT = 0.07D;
	private static final double ATTENTION_WEIGHT_SCREEN_CENTER = 0.07D;
	private static final double ATTENTION_WEIGHT_DISTANCE = 0.05D;
	private static final double ATTENTION_WEIGHT_TEMPORAL_INSTABILITY = 0.05D;
	private static final double ATTENTION_WEIGHT_MOTION = 0.04D;
	private static final double ATTENTION_WEIGHT_ANIMATION = 0.04D;
	private static final double ATTENTION_HIGH_CONFIDENCE_OPTIMIZATION_BIAS = 0.12D;
	private static final double HIGH_CONFIDENCE_THRESHOLD = 0.55D;
	private static final double ATTENTION_PROMOTION_ALPHA = 0.45D;
	private static final double ATTENTION_DEMOTION_ALPHA = 0.18D;
	private static final int MIN_DEMOTION_BAND_LIFETIME_FRAMES = 4;
	private static final int POP_RISK_DEMOTION_COOLDOWN_FRAMES = 6;
	private static final int RECENT_FULL_DEMOTION_GRACE_FRAMES = 8;
	private static final double PROMOTION_SCORE_DEADBAND = 0.025D;
	private static final double DEMOTION_SCORE_DEADBAND = 0.035D;
	private static final int PERIODIC_RECHECK_FULL_FRAMES = 31;
	private static final int PERIODIC_RECHECK_THROTTLED_FRAMES = 37;
	private static final int PERIODIC_RECHECK_REUSED_FRAMES = 17;
	private static final int PERIODIC_RECHECK_PROXY_FRAMES = 19;
	private static final int PERIODIC_RECHECK_CULLED_NEAR_FRAMES = 13;
	private static final int PERIODIC_RECHECK_CULLED_FAR_FRAMES = 41;
	// Lower budgets to reduce per-frame CPU work while keeping behavior conservative.
	private static final int BAND_TRANSITION_BUDGET_PER_FRAME = 16;
	private static final int DECAY_OBJECTS_PER_FRAME = 16;
	private static final int DECAY_ENTITY_MEMORY_PER_FRAME = 8;
	private static final int MAX_NON_URGENT_TRANSITIONS_PER_FRAME = 48;
	private static final int MAX_URGENT_TRANSITIONS_PER_FRAME = 128;
	private static final int DEFERRED_TRANSITIONS_PROCESS_PER_FRAME = 32;
	private static final int DEFERRED_QUEUE_MAX_SIZE = 512;
	// Aggressively prefer cached/stable decisions by default.
	private static final int SCORE_RECOMPUTE_BUDGET_PER_FRAME = 32;
	private static final int MAX_VISIBLE_DEMOTIONS_PER_FRAME = 12;
	private static final double CENTER_DEMOTION_HOLD_PRESENCE = 0.55D;
	private static final int DIAGNOSTIC_SAMPLE_INTERVAL = 8;

	private static final int DIRTY_ENTERED_VIEW = 1;
	private static final int DIRTY_LEFT_VIEW = 1 << 1;
	private static final int DIRTY_OBJECT_MOVED = 1 << 2;
	private static final int DIRTY_OBJECT_STATE_CHANGED = 1 << 3;
	private static final int DIRTY_CAMERA_MOVED = 1 << 4;
	private static final int DIRTY_CAMERA_ROTATED = 1 << 5;
	private static final int DIRTY_CROSSHAIR_PROXIMITY = 1 << 6;
	private static final int DIRTY_PERIODIC_RECHECK = 1 << 7;
	private static final int DIRTY_IMPORTANCE_DEBT = 1 << 8;
	private static final int DIRTY_RECENT_VISIBILITY = 1 << 9;
	private static final int DIRTY_INTERACTION = 1 << 10;
	private static final int DIRTY_FIRST_SEEN = 1 << 11;
	private static final int DIRTY_NEARBY_CHANGED = 1 << 12;
	private static final int DIRTY_GAMEPLAY_IMPORTANCE_CHANGED = 1 << 13;
	private static final int DIRTY_REASON_COUNT = 14;

	// ---- Render cost thresholds ----
	private static final double HIGH_RENDER_COST_THRESHOLD = 0.65D;
	private static final double LOW_RENDER_COST_THRESHOLD  = 0.35D;
	private static final double NOTICEABLE_RENDER_COST_THRESHOLD = 0.50D;

	// ---- Score ceilings by band (for target-score references) ----
	private static final double MAX_SIGNIFICANCE_DISTANCE = 256.0D;
	private static final double MIN_SCREEN_COVERAGE_DISTANCE = 4.0D;

	// Legacy threshold reused by classifySimple / incrementCounter
	private static final double LOW_IMPORTANCE_THRESHOLD = 0.35D;

	// ---- Staggered evaluation ----
	// Only evaluate full significance every N frames for distant/low-importance objects.
	// Near/looked-at objects evaluate every frame.
	private static final int STAGGER_INTERVAL_NEAR = 1;      // every frame
	private static final int STAGGER_INTERVAL_MID = 3;       // every 3 frames
	private static final int STAGGER_INTERVAL_FAR = 6;       // every 6 frames
	private static final int STAGGER_INTERVAL_CULLED = 10;   // every 10 frames
	private static final double STAGGER_MID_DISTANCE_SQR = 48.0D * 48.0D;
	private static final double STAGGER_FAR_DISTANCE_SQR = 96.0D * 96.0D;

	// ---- Top-10 tracking with deduplication ----
	private static final int TOP_EXPENSIVE_COUNT = 10;
	private static final Long2DoubleOpenHashMap topExpensiveScores = new Long2DoubleOpenHashMap();
	private static final it.unimi.dsi.fastutil.longs.LongArrayList topExpensiveKeys =
		new it.unimi.dsi.fastutil.longs.LongArrayList(TOP_EXPENSIVE_COUNT + 1);
	private static boolean topExpensiveDirty = true;
	private static String topExpensiveCached = "none";
	private static final IdentityHashMap<BlockEntityType<?>, Int2ByteOpenHashMap> importantBlockCache =
		new IdentityHashMap<>();

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
	private static final LongArrayList objectMemoryKeys = new LongArrayList();
	private static int objectMemoryDecayIndex;

	// ---- Entity-specific data ----
	private static final Int2ObjectOpenHashMap<EntityMemory> entityMemory = new Int2ObjectOpenHashMap<>();
	private static final Int2ObjectOpenHashMap<EntityOscillationTracker> entityOscillation = new Int2ObjectOpenHashMap<>();
	private static final IntArrayList entityMemoryKeys = new IntArrayList();
	private static int entityMemoryDecayIndex;
	private static final Int2LongOpenHashMap recentlyInteractedEntities = new Int2LongOpenHashMap();

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
	private static long lowConfidenceDemotionBlocks;
	private static long highPopRiskDemotionBlocks;
	private static long recentlyLookedAtProtections;
	private static long recentlyInteractedProtections;
	private static long recentlyChangedProtections;
	private static long recentlyMovedProtections;
	private static long recentlyEnteredViewProtections;
	private static long fastCameraDemotionBlocks;
	private static long promotionsBecauseLowConfidence;
	private static long demotionsAllowedBecauseHighConfidence;
	private static long importantButCulled;
	private static long promotionTransitions;
	private static long demotionTransitions;
	private static long accumulatedBandLifetimeFrames;
	private static final long[] bandTransitionPairs = new long[25];
	private static long importanceDebtPromotions;
	private static long singleBandTransitionsEnforced;
	private static double accumulatedContinuousScores;
	private static double accumulatedConfidence;
	private static double minConfidence = Double.POSITIVE_INFINITY;
	private static double maxConfidence;
	private static long accumulatedScoreCount;

	// ---- Diagnostic accumulators ----
	private static double highestVisualSignificance;
	private static double lowestVisualSignificance = Double.POSITIVE_INFINITY;
	private static double accumulatedRenderCost;
	private static double accumulatedScreenCoverage;
	private static double accumulatedTemporalScore;
	private static double accumulatedImportanceDebt;
	private static double accumulatedPopRisk;
	private static double accumulatedVisualImportance;
	private static double accumulatedGameplayImportance;
	private static double accumulatedSafetyImportance;
	private static double accumulatedWeightedAttentionScore;
	private static final double[] weightedAttentionByBand = new double[5];
	private static final long[] weightedAttentionSamplesByBand = new long[5];
	private static final long[] confidenceBuckets = new long[5];
	private static final long[] dirtyReasonCounts = new long[DIRTY_REASON_COUNT];
	private static int dirtyObjectsThisFrame;
	private static long dirtyObjectsTotal;
	private static long skippedStableObjects;
	private static long scoreCacheHits;
	private static long scoreRecomputes;
	private static int bandTransitionBudgetUsedThisFrame;
	private static long bandTransitionBudgetUsed;
	private static long bandTransitionsDeferred;
	private static int bandTransitionsDeferredThisFrame;
	private static int scoreRecomputesThisFrame;
	private static long scoreRecomputesDeferred;
	private static long scoreRecomputesDeferredThisFrame;
	private static long periodicRechecks;
	private static long periodicScheduled;
	private static long periodicTriggered;
	private static long periodicMerged;
	private static long periodicDelayed;
	private static long periodicCancelled;
	private static int evaluationTimingCounter;
	private static long periodicEvaluationSampleNanos;
	private static long periodicEvaluationSamples;
	private static long debtEvaluationSampleNanos;
	private static long debtEvaluationSamples;
	private static long cacheFastPathSampleNanos;
	private static long cacheFastPathSamples;
	private static int periodicRechecksThisFrame;
	private static long accumulatedTicksInBand;
	private static long ticksInBandSamples;
	private static long transitionsFromDirtyObjects;
	private static long transitionsFromStableObjects;
	private static int promotionTransitionsThisFrame;
	private static int demotionTransitionsThisFrame;
	private static int proxyCreationsThisFrame;
	private static int proxyDestructionsThisFrame;
	private static int visibilityChangesThisFrame;
	private static int importanceDebtPromotionsThisFrame;
	private static int visibleDemotionsThisFrame;
	private static FrameBurstSnapshot lastFrameBurst = FrameBurstSnapshot.EMPTY;

	// ---- Deferred transition queue (non-urgent transitions exceeding budget) ----
	private static final Deque<Long> deferredTransitionKeys = new ArrayDeque<>();
	private static int maxDeferredQueueSize;
	private static long emergencyTransitionsBypassedBudget;
	private static long nonUrgentTransitionsDelayed;
	private static int urgentTransitionsThisFrame;
	private static int largestTransitionBurstRecorded;
	private static String largestTransitionBurstReason = "none";

	// ---- Distribution tracking (new) ----
	private static long fullForcedBySafety;
	private static long fullByWeightedScore;
	private static long importantButThrottled;
	private static long importantButReused;
	private static long importantButProxy;
	private static long decisionBecauseWeightedScore;
	private static long decisionBecausePopRiskVeto;
	private static long decisionBecauseConfidenceVeto;
	private static long decisionBecauseSafetyOverride;
	private static long decisionBecauseRecentlyVisible;
	private static long decisionBecauseImportanceDebt;
	private static long decisionBecauseHysteresis;
	private static long decisionBecauseNearbyOverride;
	private static long decisionBecauseGameplayOverride;

	// Event-driven importance debt. Scheduling state lives in the existing per-object
	// memory, so the hot path performs no queue-node allocations or duplicate enqueues.
	private static long importanceDebtScheduled;
	private static long importanceDebtTriggered;
	private static long importanceDebtCancelled;
	private static long importanceDebtMerged;
	private static long scheduledEvaluations;
	private static long eventDrivenEvaluations;
	private static long evaluationDelayFrames;
	private static long maxEvaluationDelayFrames;
	private static long schedulerSizeSamples;
	private static long accumulatedSchedulerSize;
	private static long peakSchedulerSize;
	private static long accumulatedFramesUntilPromotion;
	private static long objectsAwaitingPromotion;

	// ---- CPU timing ----
	private static long significanceNanos;
	private static long worstSignificanceNanos;
	private static long profiledObjects;

	// ---- Scene metrics ----
	private static double nearestDistanceSqr = Double.POSITIVE_INFINITY;
	private static long frameIndex;
	private static int stableFrameCounter;
	private static long crosshairBlockKey = Long.MIN_VALUE;
	private static long lastProtectionCacheFrame = -1L;
	private static long lastProtectionCacheKey = Long.MIN_VALUE;
	private static byte lastProtectionCacheValue = UNKNOWN;
	private static long lastBlockInteractionFrame = NEVER_INTERACTED_FRAME;
	private static long lastEntityInteractionFrame = NEVER_INTERACTED_FRAME;

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
	private static double cameraLookX;
	private static double cameraLookY;
	private static double cameraLookZ = 1.0D;
	private static long cameraLookFrame = -1L;

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
	private static int diagnosticSampleCounter;
	private static boolean activeLastFrame;
	private static boolean detailedStatisticsEnabled;
	private static boolean detailedStatisticsThisFrame;

	// ---- Staggered evaluation state ----
	// Skip full evaluation for objects that haven't changed and are far away.
	// Only re-evaluate when: camera moved significantly, object was interacted with,
	// or the stagger interval has elapsed.
	private static boolean cameraMovedSignificantly;
	private static boolean cameraRotatedSignificantly;

	static {
		recentlyInteracted.defaultReturnValue(NEVER_INTERACTED_FRAME);
		recentlyInteractedEntities.defaultReturnValue(NEVER_INTERACTED_FRAME);
		beClassifications.defaultReturnValue(UNKNOWN);
	}

	private OptiminiumVisualSignificance() {
	}

	// ============================================================
	//  Public API
	// ============================================================

	public static void onFrameStart() {
		if (!isEnabled()) {
			if (activeLastFrame) {
				reset();
				activeLastFrame = false;
			}
			detailedStatisticsThisFrame = false;
			return;
		}
		activeLastFrame = true;
		detailedStatisticsThisFrame = detailedStatisticsEnabled || isDiagnosticDetailEnabled();
		if (detailedStatisticsThisFrame) {
			captureFrameBurstSnapshot();
		}
		frameIndex++;
		schedulerSizeSamples++;
		accumulatedSchedulerSize += objectsAwaitingPromotion;
		peakSchedulerSize = Math.max(peakSchedulerSize, objectsAwaitingPromotion);
		recordInteractionTarget();
		captureCrosshairTarget();
		accumulateTotals();
		if (objectMemory.isEmpty() && entityMemory.isEmpty() && deferredTransitionKeys.isEmpty()) {
			resetFrameCounters();
			return;
		}
		resetFrameCounters();
		processDeferredTransitions();
		updateCameraMotion();
		advanceObjectMemories();
		decayVisibility();
		// Stagger cleanup: only run every 60 frames
		if (cleanupCounter++ >= 60) {
			cleanupCounter = 0;
			cleanupStaleMemory();
			cleanupStaleEntityMemory();
		}
	}

	public static boolean isEnabled() {
		return OptiminiumSettings.isEnabled() && OptiminiumSettings.isExperimentalTemporalSignificance();
	}

	public static boolean isParticleRecordingEnabled() {
		return isEnabled() && (detailedStatisticsEnabled || isDiagnosticDetailEnabled());
	}

	public static void setDetailedStatisticsEnabled(boolean enabled) {
		detailedStatisticsEnabled = enabled;
	}

	public static byte getBlockEntityClassification(BlockEntity blockEntity) {
		if (!isEnabled()) return UNKNOWN;
		return beClassifications.get(blockEntity.getBlockPos().asLong());
	}

	public static boolean shouldRenderBySignificance(BlockEntity blockEntity) {
		if (!isEnabled()) return true;
		return shouldRenderBySignificanceActive(blockEntity.getBlockPos().asLong());
	}

	public static boolean shouldRenderBySignificance(long key) {
		if (!isEnabled()) return true;
		return shouldRenderBySignificanceActive(key);
	}

	static boolean shouldRenderBySignificanceActive(long key) {
		if (objectMemory.isEmpty()) return true;
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
		if (frameIndex - mem.firstSeenFrame < FADE_TRANSITION_FRAMES) {
			return false;
		}
		if (mem.lastClassification == CULLED && isFadingOut(mem.lastChangedFrame)) return true;
		if (mem.lastClassification != CULLED && mem.lastClassification != UNKNOWN
				&& (mem.previousClassification == CULLED || mem.previousClassification == UNKNOWN)
				&& isFadingOut(mem.lastChangedFrame)) return true;
		return false;
	}

	public static boolean shouldProtectBlockEntity(BlockEntity blockEntity) {
		if (!isEnabled()) return false;
		BlockPos pos = blockEntity.getBlockPos();
		Vec3 cameraPosition = cameraPosition();
		if (cameraPosition == null) return false;
		double dx = pos.getX() + 0.5D - cameraPosition.x;
		double dy = pos.getY() + 0.5D - cameraPosition.y;
		double dz = pos.getZ() + 0.5D - cameraPosition.z;
		double distanceSqr = dx * dx + dy * dy + dz * dz;
		return shouldProtectBlockEntity(blockEntity, pos.asLong(), dx, dy, dz, distanceSqr);
	}

	public static boolean shouldProtectBlockEntity(BlockEntity blockEntity, long key,
			double dx, double dy, double dz, double distanceSqr) {
		if (!isEnabled()) return false;
		return shouldProtectBlockEntityActive(blockEntity, key, dx, dy, dz, distanceSqr);
	}

	static boolean shouldProtectBlockEntityActive(BlockEntity blockEntity, long key,
			double dx, double dy, double dz, double distanceSqr) {
		byte cached = lastProtectionCacheFrame == frameIndex && lastProtectionCacheKey == key
			? lastProtectionCacheValue : UNKNOWN;
		if (cached != UNKNOWN) return cached == 1;
		boolean protect = distanceSqr <= NEAR_DISTANCE_SQR
			|| (frameIndex - lastBlockInteractionFrame <= RECENT_FRAMES
				&& frameIndex - recentlyInteracted.get(key) <= RECENT_FRAMES)
			|| (!objectMemory.isEmpty() && prototypeConfidenceProtection(key))
			|| crosshairBlockKey == key
			|| isImportant(blockEntity)
			|| isLookedAt(key, dx, dy, dz, distanceSqr);
		lastProtectionCacheFrame = frameIndex;
		lastProtectionCacheKey = key;
		lastProtectionCacheValue = protect ? (byte)1 : (byte)0;
		return protect;
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

	// ---- Sampled timing (not per-object) ----
	// Increase sampling interval to reduce profiling overhead in hot paths
	private static final int TIMING_SAMPLE_INTERVAL = 64; // sample every 64 calls
	private static int timingSampleCounter;
	private static long sampledTimingTotal;
	private static int sampledTimingCount;
	private static long sampledWorstTiming;
	private static long sampledTimingNanos;
	private static int sampledProfiledObjects;

	public static void recordBlockEntity(BlockEntity blockEntity, Vec3 cameraPosition) {
		if (!isEnabled()) return;
		// Sampled timing: only profile every N-th call
		boolean doProfile = (++timingSampleCounter % TIMING_SAMPLE_INTERVAL) == 0;
		long startNanos = 0L;
		if (doProfile) {
			startNanos = System.nanoTime();
		}
		recordBlockEntityProfiled(blockEntity, cameraPosition);
		if (doProfile) {
			long nanos = Math.max(0L, System.nanoTime() - startNanos);
			sampledTimingTotal += nanos;
			sampledTimingCount++;
			if (nanos > sampledWorstTiming) sampledWorstTiming = nanos;
		}
	}

	public static void recordEntity(Entity entity, boolean culled) {
		if (!isEnabled()) return;
		boolean doProfile = (++timingSampleCounter % TIMING_SAMPLE_INTERVAL) == 0;
		long startNanos = 0L;
		if (doProfile) {
			startNanos = System.nanoTime();
		}
		recordEntityProfiled(entity, culled);
		if (doProfile) {
			long nanos = Math.max(0L, System.nanoTime() - startNanos);
			sampledTimingTotal += nanos;
			sampledTimingCount++;
			if (nanos > sampledWorstTiming) sampledWorstTiming = nanos;
		}
	}

	public static void recordParticle(ParticleOptions options, double x, double y, double z, boolean culled) {
		if (!isEnabled()) return;
		if (!detailedStatisticsEnabled && !isDiagnosticDetailEnabled()) return;
		boolean doProfile = (++timingSampleCounter % TIMING_SAMPLE_INTERVAL) == 0;
		long startNanos = 0L;
		if (doProfile) {
			startNanos = System.nanoTime();
		}
		recordPoint(x, y, z, isImportantParticle(options), culled, particleRenderCost(options));
		if (doProfile) {
			long nanos = Math.max(0L, System.nanoTime() - startNanos);
			sampledTimingTotal += nanos;
			sampledTimingCount++;
			if (nanos > sampledWorstTiming) sampledWorstTiming = nanos;
		}
	}

	// ============================================================
	//  Core recording (with staggered evaluation)
	// ============================================================

	

	private static int blockDirtyReasons(ObjectMemory mem, long key, double distanceSqr, double centerPresence,
			boolean lookedAt, boolean inFront, boolean important, boolean interactedThisFrame,
			boolean firstSeen, boolean wasInFront, boolean wasNearCrosshair, boolean wasNearby,
			boolean wasGameplayImportant) {
		int reasons = firstSeen ? DIRTY_FIRST_SEEN : 0;
		if (!firstSeen && !wasInFront && inFront) reasons |= DIRTY_ENTERED_VIEW;
		if (!firstSeen && wasInFront && !inFront) reasons |= DIRTY_LEFT_VIEW;
		if (mem.lastStateChangeFrame == frameIndex) reasons |= DIRTY_OBJECT_STATE_CHANGED;
		if (cameraMovedSignificantly && (distanceSqr <= STAGGER_MID_DISTANCE_SQR || mem.lastClassification <= THROTTLED)) {
			reasons |= DIRTY_CAMERA_MOVED;
		}
		if (cameraRotatedSignificantly && (inFront || wasInFront || distanceSqr <= STAGGER_FAR_DISTANCE_SQR)) {
			reasons |= DIRTY_CAMERA_ROTATED;
		}
		boolean nearCrosshair = lookedAt || centerPresence >= 0.72D;
		if (!firstSeen && nearCrosshair != wasNearCrosshair) reasons |= DIRTY_CROSSHAIR_PROXIMITY;
		mem.nearCrosshair = nearCrosshair;
		boolean nearby = distanceSqr <= NEAR_DISTANCE_SQR;
		if (!firstSeen && nearby != wasNearby) reasons |= DIRTY_NEARBY_CHANGED;
		mem.nearby = nearby;
		if (!firstSeen && important != wasGameplayImportant) reasons |= DIRTY_GAMEPLAY_IMPORTANCE_CHANGED;
		mem.gameplayImportant = important;
		if (interactedThisFrame) reasons |= DIRTY_INTERACTION;
		if (mem.lastClassification == CULLED && inFront
				&& frameIndex - mem.lastVisibleFrame <= BLOCK_ENTITY_RECENTLY_VISIBLE_GRACE_FRAMES) {
				reasons |= DIRTY_RECENT_VISIBILITY;
		}
		if (mem.importanceDebtDueFrame > 0L && frameIndex >= mem.importanceDebtDueFrame) {
			reasons |= DIRTY_IMPORTANCE_DEBT;
		}
		int periodicSalt = (int)(key ^ (key >>> 32));
		ensurePeriodicScheduled(mem, mem.lastClassification, distanceSqr, periodicSalt);
		if (!firstSeen && mem.nextPeriodicEvaluationFrame > 0L && frameIndex >= mem.nextPeriodicEvaluationFrame) {
			reasons |= DIRTY_PERIODIC_RECHECK;
		}
		return reasons;
	}

	private static int entityDirtyReasons(EntityMemory mem, int entityId, double distanceSqr, double centerPresence,
			boolean lookedAt, boolean inFront, boolean important, boolean interactedThisFrame,
			boolean firstSeen, boolean wasInFront, boolean wasNearCrosshair, boolean wasNearby,
			boolean wasGameplayImportant, EntityCategory previousCategory, EntityCategory category) {
		int reasons = firstSeen ? DIRTY_FIRST_SEEN : 0;
		if (!firstSeen && !wasInFront && inFront) reasons |= DIRTY_ENTERED_VIEW;
		if (!firstSeen && wasInFront && !inFront) reasons |= DIRTY_LEFT_VIEW;
		if (mem.lastMotionChangeFrame == frameIndex) reasons |= DIRTY_OBJECT_MOVED;
		if (previousCategory != category) reasons |= DIRTY_OBJECT_STATE_CHANGED;
		if (cameraMovedSignificantly && (distanceSqr <= STAGGER_MID_DISTANCE_SQR || mem.lastClassification <= THROTTLED)) {
			reasons |= DIRTY_CAMERA_MOVED;
		}
		if (cameraRotatedSignificantly && (inFront || wasInFront || distanceSqr <= STAGGER_FAR_DISTANCE_SQR)) {
			reasons |= DIRTY_CAMERA_ROTATED;
		}
		boolean nearCrosshair = lookedAt || centerPresence >= 0.72D;
		if (!firstSeen && nearCrosshair != wasNearCrosshair) reasons |= DIRTY_CROSSHAIR_PROXIMITY;
		mem.nearCrosshair = nearCrosshair;
		boolean nearby = distanceSqr <= ENTITY_NEAR_DISTANCE_SQR;
		if (!firstSeen && nearby != wasNearby) reasons |= DIRTY_NEARBY_CHANGED;
		mem.nearby = nearby;
		if (!firstSeen && important != wasGameplayImportant) reasons |= DIRTY_GAMEPLAY_IMPORTANCE_CHANGED;
		mem.gameplayImportant = important;
		if (interactedThisFrame) reasons |= DIRTY_INTERACTION;
		if (mem.lastClassification == CULLED && inFront
				&& frameIndex - mem.lastVisibleFrame <= RECENTLY_VISIBLE_GRACE_FRAMES) {
			reasons |= DIRTY_RECENT_VISIBILITY;
		}
		if (mem.importanceDebtDueFrame > 0L && frameIndex >= mem.importanceDebtDueFrame) {
			reasons |= DIRTY_IMPORTANCE_DEBT;
		}
		ensurePeriodicScheduled(mem, mem.lastClassification, distanceSqr, entityId);
		if (!firstSeen && mem.nextPeriodicEvaluationFrame > 0L && frameIndex >= mem.nextPeriodicEvaluationFrame) {
			reasons |= DIRTY_PERIODIC_RECHECK;
		}
		return reasons;
	}

	static long nextPeriodicEvaluationFrame(long lastEvaluationFrame, int interval, int salt) {
		return OptiminiumEvaluationScheduler.nextPeriodicEvaluationFrame(lastEvaluationFrame, interval, salt);
	}

	private static int periodicRecheckInterval(byte band, double distanceSqr) {
		return switch (band) {
			case FULL -> PERIODIC_RECHECK_FULL_FRAMES;
			case THROTTLED -> PERIODIC_RECHECK_THROTTLED_FRAMES;
			case REUSED -> PERIODIC_RECHECK_REUSED_FRAMES;
			case PROXY -> PERIODIC_RECHECK_PROXY_FRAMES;
			case CULLED -> distanceSqr <= STAGGER_FAR_DISTANCE_SQR
				? PERIODIC_RECHECK_CULLED_NEAR_FRAMES : PERIODIC_RECHECK_CULLED_FAR_FRAMES;
			default -> 1;
		};
	}

	private static void recordDirtyObject(int dirtyReasons) {
		if (dirtyReasons == 0) {
			skippedStableObjects++;
			return;
		}
		dirtyObjectsThisFrame++;
		dirtyObjectsTotal++;
		if ((dirtyReasons & (DIRTY_ENTERED_VIEW | DIRTY_LEFT_VIEW)) != 0) visibilityChangesThisFrame++;
		for (int i = 0; i < DIRTY_REASON_COUNT; i++) {
			if ((dirtyReasons & (1 << i)) != 0) dirtyReasonCounts[i]++;
		}
		if ((dirtyReasons & DIRTY_PERIODIC_RECHECK) != 0) {
			periodicRechecks++;
			periodicRechecksThisFrame++;
		}
	}

	private static void ensurePeriodicScheduled(ObjectMemory mem, byte band, double distanceSqr, int salt) {
		if (mem.nextPeriodicEvaluationFrame != 0L || mem.lastReevaluateFrame <= 0L) return;
		mem.nextPeriodicEvaluationFrame = nextPeriodicEvaluationFrame(mem.lastReevaluateFrame,
			periodicRecheckInterval(band, distanceSqr), salt);
		periodicScheduled++;
	}

	private static void ensurePeriodicScheduled(EntityMemory mem, byte band, double distanceSqr, int salt) {
		if (mem.nextPeriodicEvaluationFrame != 0L || mem.lastReevaluateFrame <= 0L) return;
		mem.nextPeriodicEvaluationFrame = nextPeriodicEvaluationFrame(mem.lastReevaluateFrame,
			periodicRecheckInterval(band, distanceSqr), salt);
		periodicScheduled++;
	}

	private static void consumePeriodicEvaluation(ObjectMemory mem, int dirtyReasons) {
		if (mem.nextPeriodicEvaluationFrame == 0L) return;
		if ((dirtyReasons & DIRTY_PERIODIC_RECHECK) != 0) {
			periodicTriggered++;
			if (frameIndex > mem.nextPeriodicEvaluationFrame) periodicDelayed += frameIndex - mem.nextPeriodicEvaluationFrame;
		} else periodicMerged++;
		mem.nextPeriodicEvaluationFrame = 0L;
	}

	private static void consumePeriodicEvaluation(EntityMemory mem, int dirtyReasons) {
		if (mem.nextPeriodicEvaluationFrame == 0L) return;
		if ((dirtyReasons & DIRTY_PERIODIC_RECHECK) != 0) {
			periodicTriggered++;
			if (frameIndex > mem.nextPeriodicEvaluationFrame) periodicDelayed += frameIndex - mem.nextPeriodicEvaluationFrame;
		} else periodicMerged++;
		mem.nextPeriodicEvaluationFrame = 0L;
	}

	private static void consumeScheduledEvaluation(ObjectMemory mem, int dirtyReasons) {
		if (mem.importanceDebtDueFrame <= 0L) return;
		if ((dirtyReasons & DIRTY_IMPORTANCE_DEBT) != 0) {
			long delay = Math.max(0L, frameIndex - mem.importanceDebtDueFrame);
			importanceDebtTriggered++;
			scheduledEvaluations++;
			evaluationDelayFrames += delay;
			maxEvaluationDelayFrames = Math.max(maxEvaluationDelayFrames, delay);
		} else {
			importanceDebtMerged++;
			eventDrivenEvaluations++;
		}
		mem.importanceDebtDueFrame = 0L;
		objectsAwaitingPromotion = Math.max(0L, objectsAwaitingPromotion - 1L);
	}

	private static void consumeScheduledEvaluation(EntityMemory mem, int dirtyReasons) {
		if (mem.importanceDebtDueFrame <= 0L) return;
		if ((dirtyReasons & DIRTY_IMPORTANCE_DEBT) != 0) {
			long delay = Math.max(0L, frameIndex - mem.importanceDebtDueFrame);
			importanceDebtTriggered++;
			scheduledEvaluations++;
			evaluationDelayFrames += delay;
			maxEvaluationDelayFrames = Math.max(maxEvaluationDelayFrames, delay);
		} else {
			importanceDebtMerged++;
			eventDrivenEvaluations++;
		}
		mem.importanceDebtDueFrame = 0L;
		objectsAwaitingPromotion = Math.max(0L, objectsAwaitingPromotion - 1L);
	}

	private static void reuseCachedBlockDecision(ObjectMemory mem, long key, double distanceSqr, double renderCost,
			boolean important, boolean recent, int repeatCount, boolean pressured) {
		long timingStart = (++evaluationTimingCounter & 63) == 0 ? System.nanoTime() : 0L;
		scoreCacheHits++;
		byte classification = mem.lastClassification == UNKNOWN ? THROTTLED : mem.lastClassification;
		mem.cachedCandidateBand = classification;
		beClassifications.put(key, classification);
		incrementStableBandFrames(mem);
		updateImportanceDebt(mem, classification);
		// Fast path for cache hits: avoid diagnostic/sample math unless detailed mode enabled.
		if (isDetailedStatisticsEnabled()) {
			sampleTicksInBand(mem.stableBandFrames);
			sampleDiagnostics(mem.continuousScore, mem.confidence, renderCost, distanceSqr,
				mem.importanceDebt, mem.popRisk, mem.visualImportance, mem.gameplayImportance,
				mem.safetyImportance, mem.weightedAttentionScore, classification);
			incrementCounter(classification, distanceSqr, mem.lastInFrontSample, important,
				recent, repeatCount, pressured, mem.lastScore, renderCost, mem, false);
		} else {
			// keep lastVisibleFrame for non-culled objects but skip other bookkeeping
			if (classification != CULLED) mem.lastVisibleFrame = frameIndex;
			incrementCounterFast(classification);
		}
		if (timingStart != 0L) { cacheFastPathSampleNanos += System.nanoTime() - timingStart; cacheFastPathSamples++; }
	}

	private static void reuseCachedEntityDecision(Entity entity, boolean culled, EntityMemory mem, double distanceSqr,
			boolean inFront, boolean important, boolean pressured, double renderCost, EntityCategory category) {
		long timingStart = (++evaluationTimingCounter & 63) == 0 ? System.nanoTime() : 0L;
		scoreCacheHits++;
		byte classification = mem.lastClassification == UNKNOWN ? THROTTLED : mem.lastClassification;
		mem.cachedCandidateBand = classification;
		incrementStableBandFrames(mem);
		mem.lastCulled = classification == CULLED;
		updateImportanceDebt(mem, classification);
		// Fast path: avoid diagnostics and importance updates in normal mode to keep cache hits cheap.
		if (isDetailedStatisticsEnabled()) {
			if (!culled && classification != CULLED && !(entity instanceof LivingEntity)) {
				mem.lastVisibleFrame = frameIndex;
			}
			sampleTicksInBand(mem.stableBandFrames);
			sampleDiagnostics(mem.continuousScore, mem.confidence, renderCost, distanceSqr,
				mem.importanceDebt, mem.popRisk, mem.visualImportance, mem.gameplayImportance,
				mem.safetyImportance, mem.weightedAttentionScore, classification);
			boolean entityWasRendered = classification != CULLED;
			incrementEntityCounter(classification, distanceSqr, inFront, important,
				pressured, mem.lastScore, renderCost, category, mem, entityWasRendered);
		} else {
			if (!culled && classification != CULLED && !(entity instanceof LivingEntity)) mem.lastVisibleFrame = frameIndex;
			incrementEntityCounterFast(classification);
		}
		if (timingStart != 0L) { cacheFastPathSampleNanos += System.nanoTime() - timingStart; cacheFastPathSamples++; }
	}

	private static byte applyTransitionBudget(byte previous, byte desired, boolean urgentPromotion,
			long key, double centerPresence, boolean inFront) {
		if (previous == UNKNOWN || desired == UNKNOWN || previous == desired) return desired;
		// Urgent promotions (nearby, looked-at, important, recent, safety-critical, etc.)
		// must never be deferred. Count them for diagnostics.
		if (desired < previous && urgentPromotion) {
			emergencyTransitionsBypassedBudget++;
			urgentTransitionsThisFrame++;
			bandTransitionBudgetUsedThisFrame++;
			bandTransitionBudgetUsed++;
			return desired;
		}
		if (desired > previous && shouldDelayVisibleDemotion(previous, desired, key, centerPresence, inFront)) {
			nonUrgentTransitionsDelayed++;
			bandTransitionsDeferred++;
			bandTransitionsDeferredThisFrame++;
			if (deferredTransitionKeys.size() < DEFERRED_QUEUE_MAX_SIZE) {
				deferredTransitionKeys.addLast(key);
			}
			return previous;
		}
		// Non-urgent transitions: strict per-frame cap
		if (bandTransitionBudgetUsedThisFrame >= BAND_TRANSITION_BUDGET_PER_FRAME) {
			// Defer: store the key for accelerated recheck next frame
			nonUrgentTransitionsDelayed++;
			bandTransitionsDeferred++;
			bandTransitionsDeferredThisFrame++;
			if (deferredTransitionKeys.size() < DEFERRED_QUEUE_MAX_SIZE) {
				deferredTransitionKeys.addLast(key);
			}
			return previous;
		}
		bandTransitionBudgetUsedThisFrame++;
		bandTransitionBudgetUsed++;
		if (desired > previous && inFront) {
			visibleDemotionsThisFrame++;
		}
		return desired;
	}

	private static boolean shouldDelayVisibleDemotion(byte previous, byte desired, long key,
			double centerPresence, boolean inFront) {
		if (!inFront) return false;
		// ponytail: tiny temporal jitter; replace with a priority queue only if diagnostics show this cap still bursts.
		if (Math.floorMod((int)(frameIndex + key), 3) != 0) return true;
		if (centerPresence >= CENTER_DEMOTION_HOLD_PRESENCE && desired > REUSED) return true;
		return visibleDemotionsThisFrame >= MAX_VISIBLE_DEMOTIONS_PER_FRAME;
	}

	private static boolean tryStartScoreRecompute(boolean urgent) {
		if (!urgent && scoreRecomputesThisFrame >= SCORE_RECOMPUTE_BUDGET_PER_FRAME) {
			scoreRecomputesDeferred++;
			scoreRecomputesDeferredThisFrame++;
			return false;
		}
		scoreRecomputes++;
		scoreRecomputesThisFrame++;
		return true;
	}

	private static boolean isUrgentBlockRecompute(int dirtyReasons, double distanceSqr,
			boolean lookedAt, boolean important, boolean recent, ObjectMemory mem) {
		return lookedAt || important || recent || distanceSqr <= NEAR_DISTANCE_SQR
			|| (dirtyReasons & DIRTY_INTERACTION) != 0
			|| mem.safetyImportance >= 0.75D || mem.gameplayImportance >= 0.70D;
	}

	private static boolean isUrgentEntityRecompute(int dirtyReasons, double distanceSqr,
			boolean lookedAt, boolean important, EntityCategory category, Entity entity, EntityMemory mem) {
		return lookedAt || important || distanceSqr <= ENTITY_NEAR_DISTANCE_SQR
			|| (dirtyReasons & DIRTY_INTERACTION) != 0
			|| entity instanceof Player || entity instanceof Projectile
			|| category == EntityCategory.CRITICAL || mem.safetyImportance >= 0.75D
			|| mem.gameplayImportance >= 0.70D;
	}

	private static boolean isUrgentBlockPromotion(byte previous, byte desired, double distanceSqr,
			boolean lookedAt, boolean important, boolean recent, ObjectMemory mem) {
		return previous != UNKNOWN && desired < previous
			&& (lookedAt || important || recent || distanceSqr <= NEAR_DISTANCE_SQR
				|| mem.safetyImportance >= 0.75D || mem.gameplayImportance >= 0.70D);
	}

	private static boolean isUrgentEntityPromotion(byte previous, byte desired, double distanceSqr,
			boolean lookedAt, boolean important, EntityCategory category, Entity entity, EntityMemory mem) {
		return previous != UNKNOWN && desired < previous
			&& (lookedAt || important || distanceSqr <= ENTITY_NEAR_DISTANCE_SQR
				|| entity instanceof Projectile
				|| category == EntityCategory.CRITICAL || mem.safetyImportance >= 0.75D
				|| mem.gameplayImportance >= 0.70D);
	}

	private static void incrementStableBandFrames(ObjectMemory mem) {
		if (mem.stableBandFrames < Integer.MAX_VALUE) mem.stableBandFrames++;
	}

	private static void incrementStableBandFrames(EntityMemory mem) {
		if (mem.stableBandFrames < Integer.MAX_VALUE) mem.stableBandFrames++;
	}

	private static void sampleTicksInBand(int ticksInBand) {
		accumulatedTicksInBand += Math.max(0, ticksInBand);
		ticksInBandSamples++;
	}

	private static void recordBlockEntityProfiled(BlockEntity blockEntity, Vec3 cameraPosition) {
		BlockPos pos = blockEntity.getBlockPos();
		long key = pos.asLong();
		BlockEntityType<?> type = blockEntity.getType();
		BlockState state = blockEntity.getBlockState();
		double dx = pos.getX() + 0.5D - cameraPosition.x;
		double dy = pos.getY() + 0.5D - cameraPosition.y;
		double dz = pos.getZ() + 0.5D - cameraPosition.z;
		double distanceSqr = dx * dx + dy * dy + dz * dz;
		nearestDistanceSqr = Math.min(nearestDistanceSqr, distanceSqr);

		double dot = lookDot(dx, dy, dz, distanceSqr);
		boolean lookedAt = distanceSqr <= 0.0001D || crosshairBlockKey == key || dot >= LOOKED_AT_DOT;
		boolean inFront = distanceSqr <= 0.0001D || dot > 0.0D;
		double centerPresence = centerPresenceFromDot(dot, distanceSqr);
		boolean important = isImportant(type, state);
		boolean pressured = OptiminiumGpuOptimizer.hasVisualSignificancePressure();
		int repeatCount = seenCounts.addTo(key, 1) + 1;
		long lastInteractionFrame = frameIndex - lastBlockInteractionFrame <= RECENT_FRAMES
			? recentlyInteracted.get(key) : NEVER_INTERACTED_FRAME;
		boolean recent = frameIndex - lastInteractionFrame <= RECENT_FRAMES;

		ObjectMemory mem = objectMemory.get(key);
		if (mem == null) {
			mem = new ObjectMemory();
			objectMemory.put(key, mem);
		}
		boolean firstSeen = mem.lastClassification == UNKNOWN || mem.lastReevaluateFrame <= 0L;
		boolean wasInFront = mem.hasInFrontSample && mem.lastInFrontSample;
		boolean wasNearCrosshair = mem.nearCrosshair;
		boolean wasNearby = mem.nearby;
		boolean wasGameplayImportant = mem.gameplayImportant;
		mem.lastSeenFrame = frameIndex;
		if (recent) {
			mem.lastInteractedFrame = frameIndex;
		}
		updateViewMemory(mem, inFront);
		updateBlockStateMemory(mem, state);
		double renderCost;
		if (Double.isNaN(mem.renderCost) || mem.lastStateChangeFrame == frameIndex) {
			renderCost = blockEntityRenderCost(blockEntity, type, state);
			mem.renderCost = renderCost;
		} else {
			renderCost = mem.renderCost;
		}
		updateBlockImportance(mem, blockEntity, type, state, distanceSqr, centerPresence, lookedAt, important, recent, repeatCount, renderCost);

		boolean interactedThisFrame = lastInteractionFrame == frameIndex;
		int dirtyReasons = blockDirtyReasons(mem, key, distanceSqr, centerPresence, lookedAt, inFront,
			important, interactedThisFrame, firstSeen, wasInFront, wasNearCrosshair, wasNearby,
			wasGameplayImportant);
		recordDirtyObject(dirtyReasons);
		if (dirtyReasons == 0) {
			reuseCachedBlockDecision(mem, key, distanceSqr, renderCost, important, recent, repeatCount, pressured);
			return;
		}
		if (!tryStartScoreRecompute(isUrgentBlockRecompute(dirtyReasons, distanceSqr, lookedAt, important, recent, mem))) {
			reuseCachedBlockDecision(mem, key, distanceSqr, renderCost, important, recent, repeatCount, pressured);
			return;
		}
		long evaluationTimingStart = (++evaluationTimingCounter & 63) == 0 ? System.nanoTime() : 0L;
		consumeScheduledEvaluation(mem, dirtyReasons);
		consumePeriodicEvaluation(mem, dirtyReasons);
		mem.lastReevaluateFrame = frameIndex;
		mem.dirtyReasons = dirtyReasons;

		mem.visibilityDecay = 1.0D;

		updateAttention(mem, lookedAt, inFront, distanceSqr);
		// Skip prediction and pop-risk/confidence heavy work unless diagnostics enabled
		if (isDetailedStatisticsEnabled()) {
			updatePrediction(mem, dx, dy, dz, distanceSqr, inFront);
		}
		double score = computeScore(distanceSqr, inFront, lookedAt, important, recent, repeatCount, pressured, renderCost, mem);
		mem.historicalImportance = mem.historicalImportance * 0.85D + score * 0.15D;
		mem.continuousScore = mem.continuousScore * 0.70D + score * 0.30D;
		if (isDetailedStatisticsEnabled()) mem.popRisk = computeBlockEntityPopRisk(mem, distanceSqr, inFront, lookedAt, important, recent);

		// Top-10 expensive tracking is diagnostic-only; keep it off the normal render path.
		if (isDetailedStatisticsEnabled()
				&& (topExpensiveScores.size() < TOP_EXPENSIVE_COUNT || renderCost > getMinTopExpensiveScore())) {
			final long effKey = key;
			if (!topExpensiveScores.containsKey(effKey)) {
				topExpensiveKeys.add(effKey);
			}
			topExpensiveScores.put(effKey, renderCost);
			trimTopExpensive();
			topExpensiveDirty = true;
		}

		byte previousClassification = mem.lastClassification;
		byte classification = classifyByWeightedAttention(mem, distanceSqr, centerPresence, inFront, recent);
		classification = protectVisibleBlockEntityClassification(classification, mem,
			distanceSqr, inFront, lookedAt, important, recent, pressured);
		classification = applyImportanceDebt(mem, classification);
		classification = applyTransitionBudget(previousClassification, classification,
			isUrgentBlockPromotion(previousClassification, classification, distanceSqr, lookedAt, important, recent, mem),
			key, centerPresence, inFront);

		beClassifications.put(key, classification);
		recordBandTransition(previousClassification, classification, mem.lastChangedFrame, dirtyReasons != 0);
		updateConfidence(mem, classification, distanceSqr, centerPresence);
		assignBlockClassification(mem, previousClassification, classification);
		mem.cachedCandidateBand = classification;
		mem.lastScore = score;
		updateImportanceDebt(mem, classification);
		sampleTicksInBand(mem.stableBandFrames);
		sampleDiagnostics(mem.continuousScore, mem.confidence, renderCost, distanceSqr,
			mem.importanceDebt, mem.popRisk, mem.visualImportance, mem.gameplayImportance,
			mem.safetyImportance, mem.weightedAttentionScore, classification);
		if (classification != CULLED) {
			mem.lastVisibleFrame = frameIndex;
		}

		incrementCounter(classification, distanceSqr, inFront, important,
			recent, repeatCount, pressured, score, renderCost, mem, false);
		recordEvaluationTiming(dirtyReasons, evaluationTimingStart);
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

		double dot = lookDot(dx, dy, dz, distanceSqr);
		boolean inFront = distanceSqr <= 0.0001D || dot > 0.0D;
		boolean lookedAt = distanceSqr <= 0.0001D || dot >= LOOKED_AT_DOT;
		double centerPresence = centerPresenceFromDot(dot, distanceSqr);
		boolean important = isImportantEntity(entity);
		boolean pressured = OptiminiumGpuOptimizer.hasVisualSignificancePressure();
		double renderCost = entityRenderCost(entity);

		EntityMemory mem = entityMemory.get(entityId);
		if (mem == null) {
			mem = new EntityMemory();
			mem.firstSeenFrame = frameIndex;
			entityMemory.put(entityId, mem);
		}
		boolean firstSeen = mem.lastClassification == UNKNOWN || mem.lastReevaluateFrame <= 0L;
		boolean wasInFront = mem.hasInFrontSample && mem.lastInFrontSample;
		boolean wasNearCrosshair = mem.nearCrosshair;
		boolean wasNearby = mem.nearby;
		boolean wasGameplayImportant = mem.gameplayImportant;
		mem.lastSeenFrame = frameIndex;
		mem.lastDistanceSqr = distanceSqr;
		mem.lastInFront = inFront;
		updateViewMemory(mem, inFront);
		updateEntityMotionMemory(mem, x, y, z);

		EntityCategory previousCategory = mem.category;
		EntityCategory category = classifyEntity(entity);
		mem.category = category;
		updateEntityImportance(mem, entity, distanceSqr, centerPresence, lookedAt, important, category, renderCost);

		boolean interactedThisFrame = frameIndex == lastEntityInteractionFrame
			&& recentlyInteractedEntities.get(entityId) == frameIndex;
		int dirtyReasons = entityDirtyReasons(mem, entityId, distanceSqr, centerPresence, lookedAt, inFront,
			important, interactedThisFrame, firstSeen, wasInFront, wasNearCrosshair, wasNearby,
			wasGameplayImportant, previousCategory, category);
		recordDirtyObject(dirtyReasons);
		if (dirtyReasons == 0) {
			reuseCachedEntityDecision(entity, culled, mem, distanceSqr, inFront, important, pressured,
				renderCost, category);
			return;
		}
		if (!tryStartScoreRecompute(isUrgentEntityRecompute(dirtyReasons, distanceSqr, lookedAt, important, category, entity, mem))) {
			reuseCachedEntityDecision(entity, culled, mem, distanceSqr, inFront, important, pressured,
				renderCost, category);
			return;
		}
		long evaluationTimingStart = (++evaluationTimingCounter & 63) == 0 ? System.nanoTime() : 0L;
		consumeScheduledEvaluation(mem, dirtyReasons);
		consumePeriodicEvaluation(mem, dirtyReasons);
		mem.lastReevaluateFrame = frameIndex;
		mem.dirtyReasons = dirtyReasons;

		double score = computeEntityScore(distanceSqr, inFront, lookedAt, important,
			pressured, renderCost, category, entity, dx, dy, dz, mem);

		mem.visibilityDecay = 1.0D;

		updateEntityAttention(mem, lookedAt, inFront, distanceSqr, category);
		// Skip prediction/popRisk/confidence updates unless diagnostics enabled
		if (isDetailedStatisticsEnabled()) {
			updateEntityPrediction(mem, dx, dy, dz, distanceSqr, inFront);
		}
		mem.historicalImportance = mem.historicalImportance * 0.85D + score * 0.15D;
		mem.continuousScore = mem.continuousScore * 0.70D + score * 0.30D;
		if (isDetailedStatisticsEnabled()) mem.popRisk = computeEntityPopRisk(mem, distanceSqr, inFront, lookedAt, important, category, entity);

		// Top-10 expensive tracking with deduplication (entities use entityId).
		final long eKey = entityId;
		if (isDetailedStatisticsEnabled()
				&& (topExpensiveScores.size() < TOP_EXPENSIVE_COUNT || renderCost > getMinTopExpensiveScore())) {
			if (!topExpensiveScores.containsKey(eKey)) {
				topExpensiveKeys.add(eKey);
			}
			topExpensiveScores.put(eKey, renderCost);
			trimTopExpensive();
			topExpensiveDirty = true;
		}

		byte classification = classifyEntityByWeightedAttention(mem, distanceSqr, centerPresence, inFront,
			lookedAt, important, category, entity);
		classification = applyImportanceDebt(mem, classification);
		byte previousClassification = mem.lastClassification;
		classification = applyTransitionBudget(previousClassification, classification,
			isUrgentEntityPromotion(previousClassification, classification, distanceSqr, lookedAt, important, category, entity, mem),
			~entityId, centerPresence, inFront);

		recordBandTransition(previousClassification, classification, mem.lastChangedFrame, dirtyReasons != 0);
		if (isDetailedStatisticsEnabled()) updateEntityConfidence(mem, classification, distanceSqr, centerPresence);

		assignEntityClassification(entityId, mem, previousClassification, classification);
		mem.cachedCandidateBand = classification;
		mem.lastScore = score;
		mem.lastCulled = (classification == CULLED);
		updateImportanceDebt(mem, classification);
		sampleTicksInBand(mem.stableBandFrames);
		sampleDiagnostics(mem.continuousScore, mem.confidence, renderCost, distanceSqr,
			mem.importanceDebt, mem.popRisk, mem.visualImportance, mem.gameplayImportance,
			mem.safetyImportance, mem.weightedAttentionScore, classification);
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
		recordEvaluationTiming(dirtyReasons, evaluationTimingStart);
	}

	private static void recordEvaluationTiming(int dirtyReasons, long startNanos) {
		if (startNanos == 0L) return;
		long elapsed = Math.max(0L, System.nanoTime() - startNanos);
		if ((dirtyReasons & DIRTY_PERIODIC_RECHECK) != 0) {
			periodicEvaluationSampleNanos += elapsed;
			periodicEvaluationSamples++;
		}
		if ((dirtyReasons & DIRTY_IMPORTANCE_DEBT) != 0) {
			debtEvaluationSampleNanos += elapsed;
			debtEvaluationSamples++;
		}
	}

	// ============================================================
	//  Continuous significance score and hysteresis
	// ============================================================

	private static byte classifyByWeightedAttention(ObjectMemory mem, double distanceSqr,
			double centerPresence, boolean inFront, boolean recent) {
		double score = smoothWeightedAttention(mem, computeBlockWeightedAttention(mem, distanceSqr, centerPresence, inFront, recent));
		mem.weightedAttentionScore = score;
		byte previous = mem.lastClassification;

		if (previous == UNKNOWN) {
			decisionBecauseWeightedScore++;
			return scoreToBand(score);
		}

		byte desired;
		if (score > promotionThreshold(previous)) {
			desired = (byte)(previous - 1);
		} else if (score < demotionThreshold(previous)) {
			desired = (byte)(previous + 1);
		} else {
			mem.demotionCandidateFrames = 0;
			return previous;
		}

		if (desired < FULL) desired = FULL;
		if (desired > CULLED) desired = CULLED;

		if (desired < previous) {
			mem.demotionCandidateFrames = 0;
		}
		if (desired > previous) {
			mem.demotionCandidateFrames++;
			long bandAge = mem.lastChangedFrame < 0L ? Long.MAX_VALUE : frameIndex - mem.lastChangedFrame;
			if (bandAge < MIN_DEMOTION_BAND_LIFETIME_FRAMES) {
				decisionBecauseHysteresis++;
				demotionsPreventedByHysteresis++;
				return previous;
			}
			if (mem.safetyImportance >= 0.75D && desired > PROXY) {
				decisionBecauseSafetyOverride++;
				demotionsPreventedByHysteresis++;
				return previous;
			}
			if (mem.gameplayImportance >= 0.70D && desired > REUSED) {
				decisionBecauseGameplayOverride++;
				demotionsPreventedByHysteresis++;
				return previous;
			}
			if (mem.visualImportance >= 0.70D && desired > THROTTLED) {
				decisionBecauseSafetyOverride++;
				demotionsPreventedByHysteresis++;
				return previous;
			}
			if (frameIndex - mem.lastLookedAtFrame <= RECENTLY_LOOKED_AT_GRACE_FRAMES && desired > REUSED) {
				recentlyLookedAtProtections++;
				demotionsPreventedByHysteresis++;
				return previous;
			}
			if (previous == FULL && desired == THROTTLED
					&& frameIndex - mem.lastLookedAtFrame <= RECENT_FULL_DEMOTION_GRACE_FRAMES) {
				recentlyLookedAtProtections++;
				decisionBecauseHysteresis++;
				demotionsPreventedByHysteresis++;
				return previous;
			}
			if (frameIndex - mem.lastInteractedFrame <= RECENT_FRAMES && desired > THROTTLED) {
				recentlyInteractedProtections++;
				demotionsPreventedByHysteresis++;
				return previous;
			}
			if (frameIndex - mem.lastStateChangeFrame <= TRANSITION_COOLDOWN_FRAMES && desired > THROTTLED) {
				recentlyChangedProtections++;
				demotionsPreventedByHysteresis++;
				return previous;
			}
			if (frameIndex - mem.lastEnteredViewFrame <= RECENTLY_LOOKED_AT_GRACE_FRAMES && desired > REUSED) {
				recentlyEnteredViewProtections++;
				demotionsPreventedByHysteresis++;
				return previous;
			}
			if (cameraFastMoving || cameraRotatedSignificantly) {
				fastCameraDemotionBlocks++;
				decisionBecauseHysteresis++;
				demotionsPreventedByHysteresis++;
				return previous;
			}
			if (frameIndex <= mem.popRiskDemotionCooldownUntil) {
				decisionBecauseHysteresis++;
				demotionsPreventedByHysteresis++;
				return previous;
			}
			if (mem.popRisk >= 0.65D) {
				decisionBecausePopRiskVeto++;
				demotionsPreventedByConfidence++;
				highPopRiskDemotionBlocks++;
				mem.popRiskDemotionCooldownUntil = frameIndex + POP_RISK_DEMOTION_COOLDOWN_FRAMES;
				return previous;
			}
			if (mem.confidence < demotionConfidenceThreshold(previous, desired)) {
				decisionBecauseConfidenceVeto++;
				demotionsPreventedByConfidence++;
				lowConfidenceDemotionBlocks++;
				promotionsBecauseLowConfidence++;
				return previous;
			}
			if (mem.demotionCandidateFrames < requiredDemotionHoldFrames(previous, desired, mem.confidence, mem.popRisk)) {
				decisionBecauseHysteresis++;
				demotionsPreventedByHysteresis++;
				return previous;
			}
			if (mem.confidence >= HIGH_CONFIDENCE_THRESHOLD) {
				demotionsAllowedBecauseHighConfidence++;
			}
		}

		if (Math.abs(desired - previous) > 1) {
			singleBandTransitionsEnforced++;
			desired = desired < previous ? (byte)(previous - 1) : (byte)(previous + 1);
		}

		decisionBecauseWeightedScore++;
		mem.demotionCandidateFrames = 0;
		return desired;
	}

	private static byte protectVisibleBlockEntityClassification(byte classification, ObjectMemory mem,
			double distanceSqr, boolean inFront, boolean lookedAt, boolean important,
			boolean recent, boolean pressured) {
		if (lookedAt) {
			if (classification == CULLED) blockEntityCullPreventedByLookedAt++;
			mem.lastLookedAtFrame = frameIndex;
			mem.lowSignificanceFrames = 0;
			fullForcedBySafety++;
			decisionBecauseSafetyOverride++;
			return FULL;
		}
		if (distanceSqr <= NEAR_DISTANCE_SQR || recent) {
			if (recent) {
				recentlyInteractedProtections++;
				mem.lastInteractedFrame = frameIndex;
				decisionBecauseGameplayOverride++;
			} else {
				decisionBecauseNearbyOverride++;
			}
			mem.lowSignificanceFrames = 0;
			fullForcedBySafety++;
			return FULL;
		}

		boolean visible = inFront && hasMinimumBlockEntityScreenPresence(distanceSqr);

		// Allow visible block entities to reach REUSED when stable.
		// Previously ANY visible entity within BLOCK_ENTITY_VISIBLE_DISTANCE_SQR
		// with classification > THROTTLED (including REUSED) was forced to THROTTLED.
		// Now only CULLED entities are promoted; REUSED entities stay REUSED.
		if (visible && distanceSqr <= BLOCK_ENTITY_VISIBLE_DISTANCE_SQR && classification == CULLED) {
			if (classification == CULLED) blockEntityCullPreventedByVisibility++;
			mem.lowSignificanceFrames = 0;
			decisionBecauseSafetyOverride++;
			return THROTTLED;
		}

		if (classification != CULLED) {
			mem.lowSignificanceFrames = 0;
			return classification;
		}

		mem.lowSignificanceFrames++;
		boolean recentlyVisible = frameIndex - mem.lastVisibleFrame <= BLOCK_ENTITY_RECENTLY_VISIBLE_GRACE_FRAMES;
		if (recentlyVisible) {
			blockEntityCullPreventedByRecentlyVisible++;
			decisionBecauseRecentlyVisible++;
			return REUSED;
		}
		if (visible) {
			blockEntityVisibleCullEvents++;
			boolean extremePressure = currentBudget == RenderBudget.HEAVY_PRESSURE || currentBudget == RenderBudget.EMERGENCY;
			if (!pressured || !extremePressure || distanceSqr <= REUSED_DISTANCE_SQR || mem.lowSignificanceFrames < BLOCK_ENTITY_CULL_HOLD_FRAMES) {
				blockEntityCullPreventedByVisibility++;
				blockEntityDowngradedToReusedInsteadOfCulled++;
				decisionBecauseSafetyOverride++;
				return REUSED;
			}
		} else if (mem.lowSignificanceFrames < BLOCK_ENTITY_CULL_HOLD_FRAMES) {
			decisionBecauseHysteresis++;
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

	private static byte classifyEntityByWeightedAttention(EntityMemory mem, double distanceSqr, double centerPresence,
			boolean inFront, boolean lookedAt, boolean important, EntityCategory category, Entity entity) {
		if (category == EntityCategory.CRITICAL) { fullForcedBySafety++; decisionBecauseGameplayOverride++; return FULL; }
		if (distanceSqr <= ENTITY_NEAR_DISTANCE_SQR) { fullForcedBySafety++; decisionBecauseNearbyOverride++; return FULL; }
		if (lookedAt || important) { fullForcedBySafety++; decisionBecauseSafetyOverride++; return FULL; }

		boolean isClearlyVisible = distanceSqr <= ENTITY_VISIBLE_DISTANCE_SQR;
		if (isClearlyVisible && category != EntityCategory.ITEM && category != EntityCategory.OTHER) {
			if (mem.lastClassification <= THROTTLED) return mem.lastClassification;
		}

		double effectiveScore = smoothWeightedAttention(mem, computeEntityWeightedAttention(mem, distanceSqr, centerPresence, inFront, category, entity));
		mem.weightedAttentionScore = effectiveScore;
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
				// Previously THROTTLED was returned here, which skipped REUSED entirely.
				// For re-entering visible objects, start at REUSED (not THROTTLED)
				// to make REUSED a meaningful intermediate band.
				// Only looked-at or important objects get THROTTLED.
				if (lookedAt || important) {
					return THROTTLED;
				}
				decisionBecauseRecentlyVisible++;
				return REUSED;
			}
			if (raw == CULLED && category != EntityCategory.ITEM && category != EntityCategory.OTHER
					&& effectiveScore > 0.15D) {
				decisionBecauseSafetyOverride++;
				return PROXY;
			}
			decisionBecauseWeightedScore++;
			return raw;
		}

		byte desired;
		if (effectiveScore > promotionThreshold(previous)) {
			desired = (byte)(previous - 1);
		} else if (effectiveScore < demotionThreshold(previous)) {
			desired = (byte)(previous + 1);
		} else {
			mem.demotionCandidateFrames = 0;
			return previous;
		}

		if (desired < FULL) desired = FULL;
		if (desired > CULLED) desired = CULLED;

		// Allow stable visible objects to reach REUSED.
		// Only objects that are clearly visible AND important or
		// looked-at should stay at THROTTLED or above.
		if (isClearlyVisible && category != EntityCategory.ITEM && category != EntityCategory.OTHER) {
			if (desired > THROTTLED && (lookedAt || important || distanceSqr <= ENTITY_NEAR_DISTANCE_SQR)) {
				desired = THROTTLED;
			} else if (desired > REUSED) {
				// For stable visible objects not directly looked at: allow REUSED
				desired = REUSED;
			}
		}

		if (desired < previous) {
			mem.demotionCandidateFrames = 0;
		}
		if (desired > previous) {
			mem.demotionCandidateFrames++;
			long bandAge = mem.lastChangedFrame < 0L ? Long.MAX_VALUE : frameIndex - mem.lastChangedFrame;
			if (bandAge < MIN_DEMOTION_BAND_LIFETIME_FRAMES) {
				decisionBecauseHysteresis++;
				demotionsPreventedByHysteresis++;
				return previous;
			}
			if (mem.safetyImportance >= 0.75D && desired > PROXY) {
				decisionBecauseSafetyOverride++;
				demotionsPreventedByHysteresis++;
				return previous;
			}
			if (mem.gameplayImportance >= 0.70D && desired > REUSED) {
				decisionBecauseGameplayOverride++;
				demotionsPreventedByHysteresis++;
				return previous;
			}
			if (mem.visualImportance >= 0.70D && desired > THROTTLED) {
				decisionBecauseSafetyOverride++;
				demotionsPreventedByHysteresis++;
				return previous;
			}
			if (frameIndex - mem.lastLookedAtFrame <= RECENTLY_LOOKED_AT_GRACE_FRAMES && desired > REUSED) {
				recentlyLookedAtProtections++;
				demotionsPreventedByHysteresis++;
				return previous;
			}
			if (previous == FULL && desired == THROTTLED
					&& frameIndex - mem.lastLookedAtFrame <= RECENT_FULL_DEMOTION_GRACE_FRAMES) {
				recentlyLookedAtProtections++;
				decisionBecauseHysteresis++;
				demotionsPreventedByHysteresis++;
				return previous;
			}
			if (frameIndex - mem.lastMotionChangeFrame <= TRANSITION_COOLDOWN_FRAMES && desired > REUSED) {
				recentlyMovedProtections++;
				demotionsPreventedByHysteresis++;
				return previous;
			}
			if (frameIndex - mem.lastEnteredViewFrame <= RECENTLY_LOOKED_AT_GRACE_FRAMES && desired > REUSED) {
				recentlyEnteredViewProtections++;
				demotionsPreventedByHysteresis++;
				return previous;
			}
			if (cameraFastMoving || cameraRotatedSignificantly) {
				fastCameraDemotionBlocks++;
				decisionBecauseHysteresis++;
				demotionsPreventedByHysteresis++;
				return previous;
			}
			if (frameIndex <= mem.popRiskDemotionCooldownUntil) {
				decisionBecauseHysteresis++;
				demotionsPreventedByHysteresis++;
				return previous;
			}
			if (mem.popRisk >= 0.65D) {
				decisionBecausePopRiskVeto++;
				demotionsPreventedByConfidence++;
				highPopRiskDemotionBlocks++;
				mem.popRiskDemotionCooldownUntil = frameIndex + POP_RISK_DEMOTION_COOLDOWN_FRAMES;
				return previous;
			}
			if (mem.confidence < demotionConfidenceThreshold(previous, desired)) {
				decisionBecauseConfidenceVeto++;
				demotionsPreventedByConfidence++;
				lowConfidenceDemotionBlocks++;
				promotionsBecauseLowConfidence++;
				return previous;
			}
			if (mem.demotionCandidateFrames < requiredDemotionHoldFrames(previous, desired, mem.confidence, mem.popRisk)) {
				decisionBecauseHysteresis++;
				demotionsPreventedByHysteresis++;
				return previous;
			}
			if (mem.confidence >= HIGH_CONFIDENCE_THRESHOLD) {
				demotionsAllowedBecauseHighConfidence++;
			}
		}

		if (desired == CULLED && category != EntityCategory.ITEM && category != EntityCategory.OTHER) {
			if (frameIndex - mem.lastVisibleFrame <= RECENTLY_VISIBLE_GRACE_FRAMES) {
				entityCullPreventedByRecentlyVisible++;
				decisionBecauseRecentlyVisible++;
				return PROXY;
			}
			if (previous <= THROTTLED) {
				entityCullPreventedByHysteresis++;
				decisionBecauseHysteresis++;
				return REUSED;
			}
			if (mem.lowSignificanceFrames < ENTITY_HYSTERESIS_HOLD_FRAMES) {
				entityCullPreventedByHysteresis++;
				decisionBecauseHysteresis++;
				return PROXY;
			}
			if (effectiveScore > 0.12D) return PROXY;
		}

		if (Math.abs(desired - previous) > 1) {
			singleBandTransitionsEnforced++;
			desired = desired < previous ? (byte)(previous - 1) : (byte)(previous + 1);
		}

		mem.demotionCandidateFrames = 0;
		decisionBecauseWeightedScore++;
		return desired;
	}

	private static byte scoreToBand(double score) {
		if (score > 0.70D) return FULL;
		if (score > 0.50D) return THROTTLED;
		if (score > 0.30D) return REUSED;
		if (score > 0.15D) return PROXY;
		return CULLED;
	}

	private static double promotionThreshold(byte previous) {
		if (previous <= FULL || previous > CULLED) return 1.0D;
		return Math.min(1.0D, PROMOTE_THRESHOLDS[previous] + PROMOTION_SCORE_DEADBAND);
	}

	private static double demotionThreshold(byte previous) {
		if (previous < FULL || previous >= CULLED) return 0.0D;
		return Math.max(0.0D, DEMOTE_THRESHOLDS[previous] - DEMOTION_SCORE_DEADBAND);
	}

	private static double computeBlockWeightedAttention(ObjectMemory mem, double distanceSqr,
			double centerPresence, boolean inFront, boolean recent) {
		double distanceAttention = distanceAttention(distanceSqr);
		double recentVisibility = frameIndex - mem.lastVisibleFrame <= BLOCK_ENTITY_RECENTLY_VISIBLE_GRACE_FRAMES ? 1.0D : 0.0D;
		double recentInteraction = recent || frameIndex - mem.lastInteractedFrame <= RECENT_FRAMES ? 1.0D : 0.0D;
		double temporalInstability = 1.0D - Math.min(1.0D, mem.stableBandFrames / (double) (STABILITY_REQUIRED_FRAMES * 2));
		double motion = cameraMotionAttention(inFront);
		double score = weightedAttention(mem.visualImportance, mem.gameplayImportance, mem.safetyImportance,
			mem.confidence, mem.popRisk, recentVisibility, recentInteraction, mem.importanceDebt,
			centerPresence, distanceAttention, temporalInstability, motion, 0.35D);
		if (mem.confidence >= HIGH_CONFIDENCE_THRESHOLD && mem.popRisk < 0.35D && mem.visualImportance < 0.30D
				&& mem.gameplayImportance < 0.45D && mem.safetyImportance < 0.35D) {
			score -= ATTENTION_HIGH_CONFIDENCE_OPTIMIZATION_BIAS * mem.confidence;
		}
		return clamp01(score);
	}

	private static double smoothWeightedAttention(ObjectMemory mem, double rawScore) {
		if (!mem.hasWeightedAttentionSample) {
			mem.hasWeightedAttentionSample = true;
			return rawScore;
		}
		double alpha = rawScore > mem.weightedAttentionScore ? ATTENTION_PROMOTION_ALPHA : ATTENTION_DEMOTION_ALPHA;
		return clamp01(mem.weightedAttentionScore + (rawScore - mem.weightedAttentionScore) * alpha);
	}

	private static double computeEntityWeightedAttention(EntityMemory mem, double distanceSqr,
			double centerPresence, boolean inFront, EntityCategory category, Entity entity) {
		double distanceAttention = distanceAttention(distanceSqr);
		double recentVisibility = frameIndex - mem.lastVisibleFrame <= RECENTLY_VISIBLE_GRACE_FRAMES ? 1.0D : 0.0D;
		double recentMotion = frameIndex - mem.lastMotionChangeFrame <= TRANSITION_COOLDOWN_FRAMES ? 1.0D : 0.0D;
		double temporalInstability = 1.0D - Math.min(1.0D, mem.stableBandFrames / (double) (STABILITY_REQUIRED_FRAMES * 2));
		double animation = entity instanceof LivingEntity ? 0.85D : (category == EntityCategory.ITEM ? 0.20D : 0.35D);
		double score = weightedAttention(mem.visualImportance, mem.gameplayImportance, mem.safetyImportance,
			mem.confidence, mem.popRisk, recentVisibility, 0.0D, mem.importanceDebt,
			centerPresence, distanceAttention, temporalInstability, Math.max(recentMotion, cameraMotionAttention(inFront)), animation);
		if (mem.confidence >= HIGH_CONFIDENCE_THRESHOLD && mem.popRisk < 0.35D && mem.visualImportance < 0.25D
				&& mem.gameplayImportance < 0.35D && mem.safetyImportance < 0.30D
				&& (category == EntityCategory.ITEM || category == EntityCategory.OTHER || category == EntityCategory.AMBIENT)) {
			score -= ATTENTION_HIGH_CONFIDENCE_OPTIMIZATION_BIAS * mem.confidence;
		}
		return clamp01(score);
	}

	private static double smoothWeightedAttention(EntityMemory mem, double rawScore) {
		if (!mem.hasWeightedAttentionSample) {
			mem.hasWeightedAttentionSample = true;
			return rawScore;
		}
		double alpha = rawScore > mem.weightedAttentionScore ? ATTENTION_PROMOTION_ALPHA : ATTENTION_DEMOTION_ALPHA;
		return clamp01(mem.weightedAttentionScore + (rawScore - mem.weightedAttentionScore) * alpha);
	}

	private static double weightedAttention(double visualImportance, double gameplayImportance,
			double safetyImportance, double confidence, double popRisk, double recentVisibility,
			double recentInteraction, double importanceDebt, double centerPresence,
			double distanceAttention, double temporalInstability, double motion, double animation) {
		double uncertainty = 1.0D - clamp01(confidence);
		return clamp01(visualImportance) * ATTENTION_WEIGHT_VISUAL_IMPORTANCE
			+ clamp01(gameplayImportance) * ATTENTION_WEIGHT_GAMEPLAY_IMPORTANCE
			+ clamp01(safetyImportance) * ATTENTION_WEIGHT_SAFETY_IMPORTANCE
			+ uncertainty * ATTENTION_WEIGHT_CONFIDENCE_UNCERTAINTY
			+ clamp01(popRisk) * ATTENTION_WEIGHT_POP_RISK
			+ clamp01(recentVisibility) * ATTENTION_WEIGHT_RECENT_VISIBILITY
			+ clamp01(recentInteraction) * ATTENTION_WEIGHT_RECENT_INTERACTION
			+ clamp01(importanceDebt) * ATTENTION_WEIGHT_IMPORTANCE_DEBT
			+ clamp01(centerPresence) * ATTENTION_WEIGHT_SCREEN_CENTER
			+ clamp01(distanceAttention) * ATTENTION_WEIGHT_DISTANCE
			+ clamp01(temporalInstability) * ATTENTION_WEIGHT_TEMPORAL_INSTABILITY
			+ clamp01(motion) * ATTENTION_WEIGHT_MOTION
			+ clamp01(animation) * ATTENTION_WEIGHT_ANIMATION;
	}

	private static double distanceAttention(double distanceSqr) {
		return 1.0D - Math.min(1.0D, Math.sqrt(Math.max(0.0D, distanceSqr)) / MAX_SIGNIFICANCE_DISTANCE);
	}

	private static double cameraMotionAttention(boolean inFront) {
		double motion = 0.0D;
		if (cameraFastMoving || cameraRotatedSignificantly) motion += 0.75D;
		else if (!cameraStable) motion += 0.35D;
		if (inFront && cameraVelocityAbs > 0.05D) motion += 0.15D;
		return Math.min(1.0D, motion);
	}

	private static void sampleDiagnostics(double continuousScore, double confidence, double renderCost, double distanceSqr,
			double importanceDebt, double popRisk, double visualImportance, double gameplayImportance,
			double safetyImportance, double weightedAttentionScore, byte classification) {
		if (accumulatedScoreCount >= 1000) return;
		if (!isDetailedStatisticsEnabled()
				&& (++diagnosticSampleCounter % DIAGNOSTIC_SAMPLE_INTERVAL) != 0) {
			return;
		}
		double distance = Math.sqrt(distanceSqr);
		double screenCoverage = Math.min(1.0D, MIN_SCREEN_COVERAGE_DISTANCE / Math.max(MIN_SCREEN_COVERAGE_DISTANCE, distance));
		double clampedConfidence = Math.max(0.0D, Math.min(1.0D, confidence));
		double clampedScore = Math.max(0.0D, Math.min(1.0D, continuousScore));
		double clampedWeightedAttention = clamp01(weightedAttentionScore);
		accumulatedContinuousScores += clampedScore;
		accumulatedConfidence += clampedConfidence;
		accumulatedRenderCost += Math.max(0.0D, Math.min(1.0D, renderCost));
		accumulatedScreenCoverage += screenCoverage;
		accumulatedTemporalScore += clampedScore;
		accumulatedImportanceDebt += Math.max(0.0D, Math.min(IMPORTANCE_DEBT_LIMIT, importanceDebt));
		accumulatedPopRisk += clamp01(popRisk);
		accumulatedVisualImportance += clamp01(visualImportance);
		accumulatedGameplayImportance += clamp01(gameplayImportance);
		accumulatedSafetyImportance += clamp01(safetyImportance);
		accumulatedWeightedAttentionScore += clampedWeightedAttention;
		int confidenceBucket = Math.min(4, (int)(clampedConfidence * 5.0D));
		confidenceBuckets[confidenceBucket]++;
		if (classification >= FULL && classification <= CULLED) {
			weightedAttentionByBand[classification] += clampedWeightedAttention;
			weightedAttentionSamplesByBand[classification]++;
		}
		accumulatedScoreCount++;
		highestVisualSignificance = Math.max(highestVisualSignificance, clampedScore);
		lowestVisualSignificance = Math.min(lowestVisualSignificance, clampedScore);
		minConfidence = Math.min(minConfidence, clampedConfidence);
		maxConfidence = Math.max(maxConfidence, clampedConfidence);
	}

	private static void recordBandTransition(byte previous, byte current, long lastChangedFrame, boolean dirtyObject) {
		if (previous == UNKNOWN || current == UNKNOWN || previous == current) return;
		if (previous >= FULL && previous <= CULLED && current >= FULL && current <= CULLED) {
			bandTransitionPairs[previous * 5 + current]++;
		}
		if (dirtyObject) {
			transitionsFromDirtyObjects++;
		} else {
			transitionsFromStableObjects++;
		}
		if (current == PROXY && previous != PROXY) proxyCreationsThisFrame++;
		if (previous == PROXY && current != PROXY) proxyDestructionsThisFrame++;
		if (lastChangedFrame >= 0L && frameIndex >= lastChangedFrame) {
			accumulatedBandLifetimeFrames += frameIndex - lastChangedFrame;
		}
		if (current < previous) {
			promotionTransitions++;
			promotionTransitionsThisFrame++;
		} else {
			demotionTransitions++;
			demotionTransitionsThisFrame++;
		}
	}

	private static void assignBlockClassification(ObjectMemory mem, byte previous, byte current) {
		mem.lastClassification = current;
	}

	private static void assignEntityClassification(int entityId, EntityMemory mem, byte previous, byte current) {
		if (previous != UNKNOWN && previous != current) {
			entityBandTransitions++;
			trackEntityOscillation(entityId, current);
		}
		mem.previousClassification = previous;
		mem.lastClassification = current;
	}

	private static double demotionConfidenceThreshold(byte previous, byte desired) {
		if (desired >= CULLED) return 0.45D;
		if (desired >= PROXY) return 0.36D;
		if (previous <= THROTTLED) return 0.32D;
		return 0.28D;
	}

	private static int demotionHoldFrames(byte previous, byte desired) {
		if (desired >= CULLED) return HYSTERESIS_HOLD_FRAMES;
		if (desired >= PROXY) return Math.max(6, HYSTERESIS_HOLD_FRAMES / 2);
		if (previous <= FULL) return 5;
		return 3;
	}

	private static int requiredDemotionHoldFrames(byte previous, byte desired, double confidence, double popRisk) {
		int holdFrames = demotionHoldFrames(previous, desired);
		if (confidence >= HIGH_CONFIDENCE_THRESHOLD && popRisk < 0.35D) {
			return Math.max(2, holdFrames / 2);
		}
		return holdFrames;
	}

	private static double computeBlockEntityPopRisk(ObjectMemory mem, double distanceSqr, boolean inFront,
			boolean lookedAt, boolean important, boolean recent) {
		double distance = Math.sqrt(distanceSqr);
		double screenSizeRisk = 1.0D - Math.min(1.0D, distance / MAX_SIGNIFICANCE_DISTANCE);
		double risk = 0.0D;
		if (inFront) risk += 0.12D;
		if (lookedAt) risk += 0.35D;
		if (important) risk += 0.25D;
		if (recent) risk += 0.25D;
		if (frameIndex - mem.lastVisibleFrame <= BLOCK_ENTITY_RECENTLY_VISIBLE_GRACE_FRAMES) risk += 0.20D;
		if (frameIndex - mem.lastChangedFrame <= TRANSITION_COOLDOWN_FRAMES) risk += 0.18D;
		if (frameIndex - mem.lastStateChangeFrame <= TRANSITION_COOLDOWN_FRAMES) risk += 0.18D;
		if (frameIndex - mem.lastEnteredViewFrame <= RECENTLY_LOOKED_AT_GRACE_FRAMES) risk += 0.16D;
		if (cameraFastMoving || cameraRotatedSignificantly) risk += 0.20D;
		risk += screenSizeRisk * 0.28D;
		risk += mem.attentionScore * 0.25D;
		risk += mem.visualImportance * 0.20D;
		risk += mem.gameplayImportance * 0.12D;
		risk += mem.safetyImportance * 0.18D;
		return Math.max(0.0D, Math.min(1.0D, risk));
	}

	private static byte applyImportanceDebt(ObjectMemory mem, byte classification) {
		if (classification <= FULL || mem.importanceDebt < IMPORTANCE_DEBT_LIMIT) return classification;
		importanceDebtPromotions++;
		importanceDebtPromotionsThisFrame++;
		decisionBecauseImportanceDebt++;
		mem.importanceDebt *= 0.35D;
		return (byte)(classification - 1);
	}

	private static byte applyImportanceDebt(EntityMemory mem, byte classification) {
		if (classification <= FULL || mem.importanceDebt < IMPORTANCE_DEBT_LIMIT) return classification;
		importanceDebtPromotions++;
		importanceDebtPromotionsThisFrame++;
		decisionBecauseImportanceDebt++;
		mem.importanceDebt *= 0.35D;
		return (byte)(classification - 1);
	}

	private static void updateImportanceDebt(ObjectMemory mem, byte classification) {
		if (classification == FULL) {
			mem.importanceDebt = 0.0D;
			cancelImportanceDebt(mem);
			return;
		}
		double visualWeight = mem.visualImportance * 0.05D;
		double gameplayWeight = mem.gameplayImportance * 0.06D;
		double bandWeight = switch (classification) {
			case THROTTLED -> 0.025D;
			case REUSED -> 0.040D;
			case PROXY -> 0.060D;
			case CULLED -> 0.020D;
			default -> 0.0D;
		};
		double increment = bandWeight + visualWeight + gameplayWeight;
		mem.importanceDebt = Math.min(IMPORTANCE_DEBT_LIMIT, mem.importanceDebt + increment);
		scheduleImportanceDebt(mem, increment);
	}

	private static void updateImportanceDebt(EntityMemory mem, byte classification) {
		if (classification == FULL) {
			mem.importanceDebt = 0.0D;
			cancelImportanceDebt(mem);
			return;
		}
		double visualWeight = mem.visualImportance * 0.05D;
		double gameplayWeight = mem.gameplayImportance * 0.08D;
		double bandWeight = switch (classification) {
			case THROTTLED -> 0.020D;
			case REUSED -> 0.035D;
			case PROXY -> 0.055D;
			case CULLED -> 0.018D;
			default -> 0.0D;
		};
		double increment = bandWeight + visualWeight + gameplayWeight;
		mem.importanceDebt = Math.min(IMPORTANCE_DEBT_LIMIT, mem.importanceDebt + increment);
		scheduleImportanceDebt(mem, increment);
	}

	private static long promotionDueFrame(double debt, double increment) {
		if (increment <= 0.0D) return 0L;
		long frames = Math.max(1L, (long)Math.ceil((IMPORTANCE_DEBT_LIMIT - debt) / increment));
		return frameIndex + frames;
	}

	private static void scheduleImportanceDebt(ObjectMemory mem, double increment) {
		long due = promotionDueFrame(mem.importanceDebt, increment);
		if (due <= 0L) { cancelImportanceDebt(mem); return; }
		if (mem.importanceDebtDueFrame == 0L) { importanceDebtScheduled++; objectsAwaitingPromotion++; }
		mem.importanceDebtDueFrame = due;
		accumulatedFramesUntilPromotion += Math.max(0L, due - frameIndex);
	}

	private static void scheduleImportanceDebt(EntityMemory mem, double increment) {
		long due = promotionDueFrame(mem.importanceDebt, increment);
		if (due <= 0L) { cancelImportanceDebt(mem); return; }
		if (mem.importanceDebtDueFrame == 0L) { importanceDebtScheduled++; objectsAwaitingPromotion++; }
		mem.importanceDebtDueFrame = due;
		accumulatedFramesUntilPromotion += Math.max(0L, due - frameIndex);
	}

	private static void cancelImportanceDebt(ObjectMemory mem) {
		if (mem.importanceDebtDueFrame != 0L) { importanceDebtCancelled++; objectsAwaitingPromotion = Math.max(0L, objectsAwaitingPromotion - 1L); }
		mem.importanceDebtDueFrame = 0L;
	}

	private static void cancelImportanceDebt(EntityMemory mem) {
		if (mem.importanceDebtDueFrame != 0L) { importanceDebtCancelled++; objectsAwaitingPromotion = Math.max(0L, objectsAwaitingPromotion - 1L); }
		mem.importanceDebtDueFrame = 0L;
	}

	private static double computeEntityPopRisk(EntityMemory mem, double distanceSqr, boolean inFront,
			boolean lookedAt, boolean important, EntityCategory category, Entity entity) {
		double distance = Math.sqrt(distanceSqr);
		double size = entity.getBbWidth() * entity.getBbHeight();
		double screenSizeRisk = Math.min(1.0D, (size / Math.max(1.0D, distance)) * 0.6D);
		double risk = 0.0D;
		if (inFront) risk += 0.10D;
		if (lookedAt) risk += 0.35D;
		if (important) risk += 0.25D;
		if (frameIndex - mem.lastVisibleFrame <= RECENTLY_VISIBLE_GRACE_FRAMES) risk += 0.20D;
		if (frameIndex - mem.lastChangedFrame <= TRANSITION_COOLDOWN_FRAMES) risk += 0.18D;
		if (frameIndex - mem.lastMotionChangeFrame <= TRANSITION_COOLDOWN_FRAMES) risk += 0.18D;
		if (frameIndex - mem.lastEnteredViewFrame <= RECENTLY_LOOKED_AT_GRACE_FRAMES) risk += 0.16D;
		if (cameraFastMoving || cameraRotatedSignificantly) risk += 0.20D;
		if (category != EntityCategory.ITEM && category != EntityCategory.OTHER) risk += 0.20D;
		if (entity.hasCustomName() || entity.hasGlowingTag()) risk += 0.25D;
		risk += screenSizeRisk * 0.30D;
		risk += mem.attentionScore * 0.25D;
		risk += mem.visualImportance * 0.20D;
		risk += mem.gameplayImportance * 0.15D;
		risk += mem.safetyImportance * 0.18D;
		return Math.max(0.0D, Math.min(1.0D, risk));
	}

	private static void updateEntityConfidence(EntityMemory mem, byte newClassification,
			double distanceSqr, double centerPresence) {
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
		boolean recentlyVisible = frameIndex - mem.lastVisibleFrame <= RECENTLY_VISIBLE_GRACE_FRAMES;
		boolean recentlyChanged = frameIndex - mem.lastChangedFrame <= TRANSITION_COOLDOWN_FRAMES;
		boolean recentlyMoved = frameIndex - mem.lastMotionChangeFrame <= TRANSITION_COOLDOWN_FRAMES;
		boolean recentlyEnteredView = frameIndex - mem.lastEnteredViewFrame <= RECENTLY_LOOKED_AT_GRACE_FRAMES;
		double motionRisk = recentlyMoved ? 1.0D : 0.0D;
		double target = computeReductionConfidence(distanceSqr, centerPresence, recentlyVisible,
			recentlyChanged, recentlyEnteredView, motionRisk, mem.stableBandFrames,
			mem.predictionCorrectFrames, mem.visualImportance, mem.gameplayImportance,
			mem.safetyImportance, mem.popRisk, mem.attentionScore);
		mem.confidence = smoothConfidence(mem.confidence, target);
		if (newClassification != mem.lastClassification) {
			mem.lastChangedFrame = frameIndex;
		}
	}

	private static void decayVisibility() {
		if (!objectMemory.isEmpty()) {
			var it = objectMemory.long2ObjectEntrySet().fastIterator();
			int processed = 0;
			while (it.hasNext() && processed < DECAY_OBJECTS_PER_FRAME) {
				var e = it.next();
				ObjectMemory mem = e.getValue();
				if (mem == null) continue;
				if (mem.lastSeenFrame < frameIndex) {
					mem.visibilityDecay *= 0.96D;
					mem.continuousScore *= 0.98D;
				}
				processed++;
			}
		}
		if (!entityMemory.isEmpty()) {
			var it = entityMemory.int2ObjectEntrySet().fastIterator();
			int processed = 0;
			while (it.hasNext() && processed < DECAY_ENTITY_MEMORY_PER_FRAME) {
				var e = it.next();
				EntityMemory mem = e.getValue();
				if (mem == null) continue;
				if (mem.lastSeenFrame < frameIndex) {
					mem.visibilityDecay *= 0.96D;
					mem.continuousScore *= 0.98D;
				}
				processed++;
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
			Entity entity, double dx, double dy, double dz, EntityMemory mem) {
		double dist = Math.sqrt(distanceSqr);
		double distNorm = Math.min(1.0D, dist / MAX_SIGNIFICANCE_DISTANCE);

		double size = entity.getBbWidth() * entity.getBbHeight();
		double angularSize = size / Math.max(1.0D, dist);
		double screenCoverage = Math.min(1.0D, angularSize * 0.3D);
		screenCoverage = Math.max(0.0D, screenCoverage);

		double distanceFactor = 1.0D - distNorm;

		double typeImportance = mem == null ? entityCategoryProtection(category) : mem.gameplayImportance;
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

		double recentVisibility = mem != null && frameIndex - mem.lastVisibleFrame <= RECENTLY_VISIBLE_GRACE_FRAMES ? 0.8D : 0.35D;
		double animationState = entity instanceof LivingEntity ? 0.8D : 0.3D;
		double temporalConfidence = mem == null ? 0.5D : 1.0D - mem.confidence;
		double safety = mem == null ? 0.0D : mem.safetyImportance * 0.05D;

		return screenCoverage * WEIGHT_SCREEN_COVERAGE
			+ distanceFactor * WEIGHT_DISTANCE
			+ typeImportance * WEIGHT_TYPE_IMPORTANCE
			+ cameraFocus * WEIGHT_CAMERA_FOCUS
			+ renderCostFactor * WEIGHT_RENDER_COST
			+ temporalConfidence * WEIGHT_TEMPORAL_CONFIDENCE
			+ recentVisibility * WEIGHT_RECENT_VISIBILITY
			+ motionFactor * WEIGHT_MOTION
			+ animationState * WEIGHT_ANIMATION_STATE
			+ safety;
	}

	// ============================================================
	//  Entity memory
	// ============================================================

	private static final class EntityMemory {
		byte lastClassification = UNKNOWN;
		byte previousClassification = UNKNOWN;
		double lastScore;
		double continuousScore;
		double weightedAttentionScore;
		boolean hasWeightedAttentionSample;
		double visibilityDecay;
		double historicalImportance;
		double confidence;
		double popRisk;
		double visualImportance;
		double gameplayImportance;
		double safetyImportance;
		double importanceDebt;
		double attentionScore;
		double predictedImportance;
		byte predictedClassification;
		byte cachedCandidateBand = UNKNOWN;
		long lastReevaluateFrame;
		long nextPeriodicEvaluationFrame;
		long importanceDebtDueFrame;
		int dirtyReasons;
		long lastSeenFrame = 1;
		long lastChangedFrame = -1;
		int stableBandFrames;
		int predictionCorrectFrames;
		int demotionCandidateFrames;
		long popRiskDemotionCooldownUntil = -1L;
		int offscreenFrames;
		boolean lastCulled;
		double lastDistanceSqr;
		boolean lastInFront;
		double lastEntityX = Double.NaN;
		double lastEntityY = Double.NaN;
		double lastEntityZ = Double.NaN;
		boolean hasInFrontSample;
		boolean lastInFrontSample;
		boolean nearCrosshair;
		boolean nearby;
		boolean gameplayImportant;
		EntityCategory category = EntityCategory.OTHER;
		long lastVisibleFrame = NEVER_INTERACTED_FRAME;
		long lastLookedAtFrame = NEVER_INTERACTED_FRAME;
		long lastMotionChangeFrame = NEVER_INTERACTED_FRAME;
		long lastEnteredViewFrame = NEVER_INTERACTED_FRAME;
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
		if (lookedAt) {
			mem.lastLookedAtFrame = frameIndex;
			mem.attentionScore = Math.min(1.0D, mem.attentionScore + 0.15D);
		}
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
		double weightedAttentionScore;
		boolean hasWeightedAttentionSample;
		double visibilityDecay;
		double historicalImportance;
		double confidence;
		double popRisk;
		double visualImportance;
		double gameplayImportance;
		double safetyImportance;
		double renderCost = Double.NaN;
		double importanceDebt;
		double attentionScore;
		double predictedImportance;
		byte predictedClassification;
		byte cachedCandidateBand = UNKNOWN;
		long lastReevaluateFrame;
		long nextPeriodicEvaluationFrame;
		long importanceDebtDueFrame;
		int dirtyReasons;
		long lastChangedFrame = -1;
		long lastInteractedFrame = NEVER_INTERACTED_FRAME;
		long lastStateChangeFrame = NEVER_INTERACTED_FRAME;
		long lastSeenFrame = 1;
		int lastStateId = Integer.MIN_VALUE;
		int stableBandFrames;
		int lookedAtStreak;
		int predictionCorrectFrames;
		int demotionCandidateFrames;
		long popRiskDemotionCooldownUntil = -1L;
		float predictionDot;
		boolean recentlyPromoted;
		boolean recentlyDemoted;
		boolean hasInFrontSample;
		boolean lastInFrontSample;
		boolean nearCrosshair;
		boolean nearby;
		boolean gameplayImportant;
		long lastVisibleFrame = NEVER_INTERACTED_FRAME;
		long lastLookedAtFrame = NEVER_INTERACTED_FRAME;
		long lastEnteredViewFrame = NEVER_INTERACTED_FRAME;
		int lowSignificanceFrames;
	}

	private static void advanceObjectMemories() {
		stableFrameCounter++;
	}

	private static void updateAttention(ObjectMemory mem, boolean lookedAt, boolean inFront, double distanceSqr) {
		if (lookedAt) {
			mem.lastLookedAtFrame = frameIndex;
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

	private static void updateConfidence(ObjectMemory mem, byte newClassification,
			double distanceSqr, double centerPresence) {
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
		boolean recentlyVisible = frameIndex - mem.lastVisibleFrame <= BLOCK_ENTITY_RECENTLY_VISIBLE_GRACE_FRAMES;
		boolean recentlyChanged = frameIndex - mem.lastChangedFrame <= TRANSITION_COOLDOWN_FRAMES
			|| frameIndex - mem.lastStateChangeFrame <= TRANSITION_COOLDOWN_FRAMES;
		boolean recentlyEnteredView = frameIndex - mem.lastEnteredViewFrame <= RECENTLY_LOOKED_AT_GRACE_FRAMES;
		double target = computeReductionConfidence(distanceSqr, centerPresence, recentlyVisible,
			recentlyChanged, recentlyEnteredView, recentlyChanged ? 0.35D : 0.0D,
			mem.stableBandFrames, mem.predictionCorrectFrames, mem.visualImportance,
			mem.gameplayImportance, mem.safetyImportance, mem.popRisk, mem.attentionScore);
		mem.confidence = smoothConfidence(mem.confidence, target);
		if (newClassification != mem.lastClassification) {
			mem.lastChangedFrame = frameIndex;
		}
	}

	private static double computeReductionConfidence(double distanceSqr, double centerPresence,
			boolean recentlyVisible, boolean recentlyChanged, boolean recentlyEnteredView,
			double objectMotionRisk, int stableBandFrames, int predictionCorrectFrames,
			double visualImportance, double gameplayImportance, double safetyImportance,
			double popRisk, double attentionScore) {
		double distance = Math.sqrt(Math.max(0.0D, distanceSqr));
		double distanceSafety = Math.min(1.0D, distance / MAX_SIGNIFICANCE_DISTANCE);
		double smallScreenSafety = 1.0D - clamp01(visualImportance);
		double offCenterSafety = 1.0D - clamp01(centerPresence);
		double temporalStability = Math.min(1.0D, stableBandFrames / (double) (STABILITY_REQUIRED_FRAMES * 2));
		double predictionStability = Math.min(1.0D, predictionCorrectFrames / (double) CONFIDENCE_BUILD_FRAMES);
		double cameraSafety = cameraStable ? 1.0D : (cameraFastMoving || cameraRotatedSignificantly ? 0.0D : 0.45D);
		double recentVisibilityRisk = recentlyVisible ? 1.0D : 0.0D;
		double recentChangeRisk = recentlyChanged ? 1.0D : 0.0D;
		double enteredViewRisk = recentlyEnteredView ? 1.0D : 0.0D;

		double confidence =
			0.16D
			+ distanceSafety * 0.22D
			+ smallScreenSafety * 0.20D
			+ offCenterSafety * 0.15D
			+ temporalStability * 0.18D
			+ predictionStability * 0.12D
			+ cameraSafety * 0.08D
			- attentionScore * 0.10D
			- recentVisibilityRisk * 0.08D
			- recentChangeRisk * 0.12D
			- enteredViewRisk * 0.07D
			- clamp01(objectMotionRisk) * 0.08D
			- gameplayImportance * 0.09D
			- safetyImportance * 0.09D
			- popRisk * 0.07D;

		return clamp01(confidence);
	}

	private static double smoothConfidence(double previous, double target) {
		double current = previous <= 0.0D ? target : previous;
		double alpha = target < current ? 0.22D : 0.12D;
		return Math.max(0.05D, Math.min(1.0D, current + (target - current) * alpha));
	}

	// ============================================================
	//  Counter tracking
	// ============================================================

	private static void incrementCounter(byte classification, double distanceSqr, boolean inFront,
			boolean important, boolean recent, int repeatCount, boolean pressured,
			double score, double renderCost, ObjectMemory mem, boolean isEntity) {
		boolean detailed = isDetailedStatisticsEnabled();
		switch (classification) {
			case FULL -> {
				fullThisFrame++;
				if (!detailed) break;
				if (mem != null && mem.confidence >= HIGH_CONFIDENCE_THRESHOLD && mem.weightedAttentionScore >= 0.70D) {
					fullBecauseHighConfidence++;
				}
				if (distanceSqr <= NEAR_DISTANCE_SQR || important || recent) {
					fullBecauseImportant++;
				} else {
					fullBecauseLookedAt++;
					fullByWeightedScore++;
				}
			}
			case THROTTLED -> {
				throttledThisFrame++;
				if (!detailed) break;
				if (important) importantButThrottled++;
				if (renderCost >= HIGH_RENDER_COST_THRESHOLD) throttledBecauseHighCost++;
				else if (pressured) throttledBecauseFramePressure++;
				else if (mem != null && mem.predictedImportance < 0.4D) throttledBecausePredictedLow++;
				else throttledBecauseDistance++;
			}
			case REUSED -> {
				reusedThisFrame++;
				if (!detailed) break;
				if (important) importantButReused++;
				if (cameraStable) reusedBecauseCameraStable++;
				else if (mem != null && mem.confidence > 0.7D && mem.stableBandFrames > STABILITY_REQUIRED_FRAMES) reusedBecauseHysteresis++;
				else reusedBecauseStable++;
			}
			case PROXY -> {
				proxyThisFrame++;
				if (!detailed) break;
				if (important) importantButProxy++;
				if (distanceSqr >= LOW_SCREEN_SIZE_DISTANCE_SQR) proxyBecauseLowScreenSize++;
				else if (mem != null && mem.confidence >= HIGH_CONFIDENCE_THRESHOLD) proxyBecauseHighConfidence++;
				else proxyBecauseFarRepeated++;
			}
			case CULLED -> {
				culledThisFrame++;
				if (!detailed) break;
				if (important) importantButCulled++;
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

	// Lightweight increment used for cache-hit fast path: only update minimal band counters.
	private static void incrementCounterFast(byte classification) {
		switch (classification) {
			case FULL -> fullThisFrame++;
			case THROTTLED -> throttledThisFrame++;
			case REUSED -> reusedThisFrame++;
			case PROXY -> proxyThisFrame++;
			case CULLED -> culledThisFrame++;
		}
	}

	private static void incrementEntityCounterFast(byte classification) {
		// Entities share the same top-level counters
		switch (classification) {
			case FULL -> fullThisFrame++;
			case THROTTLED -> throttledThisFrame++;
			case REUSED -> reusedThisFrame++;
			case PROXY -> proxyThisFrame++;
			case CULLED -> culledThisFrame++;
		}
	}

	private static void incrementEntityCounter(byte classification, double distanceSqr, boolean inFront,
			boolean important, boolean pressured, double score, double renderCost,
			EntityCategory category, EntityMemory mem, boolean wasRendered) {
		boolean detailed = isDetailedStatisticsEnabled();
		switch (classification) {
			case FULL -> {
				fullThisFrame++;
				if (!detailed) break;
				fullForcedBySafety++;
				if (mem != null && mem.confidence >= HIGH_CONFIDENCE_THRESHOLD && mem.weightedAttentionScore >= 0.70D) {
					fullBecauseHighConfidence++;
				}
				if (distanceSqr <= ENTITY_NEAR_DISTANCE_SQR) fullBecauseNearby++;
				else if (important) { fullBecauseImportant++; entityPromotedBecauseImportant++; }
				else if (category == EntityCategory.PASSIVE || category == EntityCategory.HOSTILE || category == EntityCategory.VILLAGER) {
					entityPromotedBecauseLiving++; fullBecauseLookedAt++;
				} else { fullBecauseLookedAt++; }
			}
			case THROTTLED -> {
				throttledThisFrame++;
				if (!detailed) break;
				if (important) importantButThrottled++;
				throttledBecauseDistance++;
			}
			case REUSED -> {
				reusedThisFrame++;
				if (!detailed) break;
				if (important) importantButReused++;
				if (cameraStable) reusedBecauseCameraStable++; else reusedBecauseStable++;
			}
			case PROXY -> {
				proxyThisFrame++;
				if (!detailed) break;
				if (important) importantButProxy++;
				if (distanceSqr >= ENTITY_FAR_DISTANCE_SQR) proxyBecauseLowScreenSize++; else proxyBecauseFarRepeated++;
			}
			case CULLED -> {
				culledThisFrame++;
				if (!detailed) break;
				if (important) importantButCulled++;
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
		var cameraObject = minecraft.gameRenderer.getMainCamera();
		Vec3 camera = cameraObject.getPosition();
		float yaw = cameraObject.getXRot();
		float pitch = cameraObject.getYRot();
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
			cameraMovedSignificantly = cameraVelocityAbs > 0.1D;
			cameraRotatedSignificantly = cameraRotationSpeed > 2.0D;
		}
		lastCameraX = camera.x; lastCameraY = camera.y; lastCameraZ = camera.z;
		lastCameraYaw = yaw; lastCameraPitch = pitch; lastCameraVelocityAbs = cameraVelocityAbs;
		var look = cameraObject.getLookVector();
		cameraLookX = look.x;
		cameraLookY = look.y;
		cameraLookZ = look.z;
		cameraLookFrame = frameIndex;
	}

	// ============================================================
	//  Memory cleanup
	// ============================================================

	private static void cleanupStaleMemory() {
		if (objectMemory.isEmpty()) return;
		var it = objectMemory.long2ObjectEntrySet().fastIterator();
		while (it.hasNext()) {
			var e = it.next();
			if (frameIndex - e.getValue().lastSeenFrame > 200) {
				long key = e.getLongKey();
				if (e.getValue().nextPeriodicEvaluationFrame != 0L) periodicCancelled++;
				cancelImportanceDebt(e.getValue());
				it.remove();
				seenCounts.remove(key);
			}
		}
	}

	private static void cleanupStaleEntityMemory() {
		if (entityMemory.isEmpty()) return;
		var it = entityMemory.int2ObjectEntrySet().fastIterator();
		while (it.hasNext()) {
			var e = it.next();
			if (frameIndex - e.getValue().lastSeenFrame > 120) {
				int key = e.getIntKey();
				if (e.getValue().nextPeriodicEvaluationFrame != 0L) periodicCancelled++;
				cancelImportanceDebt(e.getValue());
				it.remove();
				entityOscillation.remove(key);
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
		return computeScore(distanceSqr, inFront, lookedAt, important, recent, repeatCount, pressured, renderCost, null);
	}

	private static double computeScore(double distanceSqr, boolean inFront, boolean lookedAt,
			boolean important, boolean recent, int repeatCount, boolean pressured, double renderCost, ObjectMemory mem) {
		// Cheap short-circuits to avoid heavy math for distant, non-important objects.
		if (!inFront && distanceSqr > TINY_DISTANCE_SQR) return 0.0D;
		if (!important && !recent && distanceSqr > PROXY_DISTANCE_SQR) return 0.0D;
		double dist = Math.sqrt(distanceSqr);
		double distNorm = Math.min(1.0D, dist / MAX_SIGNIFICANCE_DISTANCE);

		double screenCoverage = mem == null ? 1.0D - distNorm : mem.visualImportance;
		screenCoverage = Math.max(0.0D, Math.min(1.0D, screenCoverage));

		double distanceFactor = 1.0D - distNorm;

		double typeImportance = mem == null ? (important ? 1.0D : 0.3D) : mem.gameplayImportance;
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

		double temporalConfidence = mem == null ? 0.5D : 1.0D - mem.confidence;
		double recentVisibility = mem != null && frameIndex - mem.lastVisibleFrame <= BLOCK_ENTITY_RECENTLY_VISIBLE_GRACE_FRAMES ? 0.8D : 0.35D;
		double animationState = 0.5D;
		double safety = mem == null ? 0.0D : mem.safetyImportance * 0.05D;

		return screenCoverage * WEIGHT_SCREEN_COVERAGE
			+ distanceFactor * WEIGHT_DISTANCE
			+ typeImportance * WEIGHT_TYPE_IMPORTANCE
			+ cameraFocus * WEIGHT_CAMERA_FOCUS
			+ renderCostFactor * WEIGHT_RENDER_COST
			+ temporalConfidence * WEIGHT_TEMPORAL_CONFIDENCE
			+ recentVisibility * WEIGHT_RECENT_VISIBILITY
			+ motionFactor * WEIGHT_MOTION
			+ animationState * WEIGHT_ANIMATION_STATE
			+ safety;
	}

	private static void updateBlockImportance(ObjectMemory mem, BlockEntity blockEntity, BlockEntityType<?> type,
			BlockState state, double distanceSqr,
			double centerPresence, boolean lookedAt, boolean important, boolean recent, int repeatCount, double renderCost) {
		double distance = Math.sqrt(distanceSqr);
		double distancePresence = 1.0D - Math.min(1.0D, distance / MAX_SIGNIFICANCE_DISTANCE);
		double visual = distancePresence * 0.45D
			+ (lookedAt ? 0.35D : centerPresence * 0.25D)
			+ (state.getLightEmission() > 0 ? 0.10D : 0.0D)
			+ Math.min(0.10D, renderCost * 0.10D);
		double gameplay = important ? 0.75D : 0.20D;
		if (type == BlockEntityType.CHEST || type == BlockEntityType.TRAPPED_CHEST
				|| type == BlockEntityType.ENDER_CHEST || type == BlockEntityType.BARREL
				|| type == BlockEntityType.LECTERN) {
			gameplay = Math.max(gameplay, 0.55D);
		}
		if (recent) gameplay = Math.max(gameplay, 0.85D);
		double safety = 0.0D;
		if (recent) safety += 0.35D;
		if (lookedAt) safety += 0.25D;
		if (frameIndex - mem.lastVisibleFrame <= BLOCK_ENTITY_RECENTLY_VISIBLE_GRACE_FRAMES) safety += 0.20D;
		if (frameIndex - mem.lastEnteredViewFrame <= RECENTLY_LOOKED_AT_GRACE_FRAMES) safety += 0.18D;
		if (frameIndex - mem.lastChangedFrame <= TRANSITION_COOLDOWN_FRAMES) safety += 0.15D;
		if (frameIndex - mem.lastStateChangeFrame <= TRANSITION_COOLDOWN_FRAMES) safety += 0.20D;
		if (isModdedBlockEntity(type)) safety += 0.12D;
		if (repeatCount >= 4 && !important && !recent) {
			gameplay = Math.min(gameplay, 0.35D);
		}
		mem.visualImportance = clamp01(visual);
		mem.gameplayImportance = clamp01(gameplay);
		mem.safetyImportance = clamp01(safety);
	}

	private static void updateEntityImportance(EntityMemory mem, Entity entity, double distanceSqr,
			double centerPresence, boolean lookedAt, boolean important, EntityCategory category, double renderCost) {
		double distance = Math.sqrt(distanceSqr);
		double size = Math.max(0.05D, entity.getBbWidth() * entity.getBbHeight());
		double screenPresence = Math.min(1.0D, size / Math.max(1.0D, distance) * 0.8D);
		double distancePresence = 1.0D - Math.min(1.0D, distance / MAX_SIGNIFICANCE_DISTANCE);
		double visual = screenPresence * 0.40D
			+ distancePresence * 0.20D
			+ (lookedAt ? 0.30D : centerPresence * 0.20D)
			+ (entity.hasGlowingTag() || entity.hasCustomName() ? 0.18D : 0.0D)
			+ (entity instanceof LivingEntity ? 0.10D : 0.0D);
		double gameplay = Math.max(entityCategoryProtection(category), important ? 0.85D : 0.0D);
		if (entity instanceof Projectile || entity instanceof FallingBlockEntity) gameplay = Math.max(gameplay, 0.90D);
		double safety = 0.0D;
		if (lookedAt) safety += 0.25D;
		if (frameIndex - mem.lastVisibleFrame <= RECENTLY_VISIBLE_GRACE_FRAMES) safety += 0.20D;
		if (frameIndex - mem.lastEnteredViewFrame <= RECENTLY_LOOKED_AT_GRACE_FRAMES) safety += 0.18D;
		if (frameIndex - mem.lastChangedFrame <= TRANSITION_COOLDOWN_FRAMES) safety += 0.15D;
		if (frameIndex - mem.lastMotionChangeFrame <= TRANSITION_COOLDOWN_FRAMES) safety += 0.20D;
		if (isModdedEntity(entity)) safety += 0.12D;
		if (cameraFastMoving || cameraRotatedSignificantly) safety += 0.15D;
		mem.visualImportance = clamp01(visual + renderCost * 0.05D);
		mem.gameplayImportance = clamp01(gameplay);
		mem.safetyImportance = clamp01(safety);
	}

	private static void updateViewMemory(ObjectMemory mem, boolean inFront) {
		if ((!mem.hasInFrontSample && inFront) || (mem.hasInFrontSample && !mem.lastInFrontSample && inFront)) {
			mem.lastEnteredViewFrame = frameIndex;
		}
		mem.hasInFrontSample = true;
		mem.lastInFrontSample = inFront;
	}

	private static void updateViewMemory(EntityMemory mem, boolean inFront) {
		if ((!mem.hasInFrontSample && inFront) || (mem.hasInFrontSample && !mem.lastInFrontSample && inFront)) {
			mem.lastEnteredViewFrame = frameIndex;
		}
		mem.hasInFrontSample = true;
		mem.lastInFrontSample = inFront;
	}

	private static void updateBlockStateMemory(ObjectMemory mem, BlockState state) {
		int stateId = Block.getId(state);
		if (mem.lastStateId == Integer.MIN_VALUE) {
			mem.lastStateId = stateId;
			return;
		}
		if (mem.lastStateId != stateId) {
			mem.lastStateId = stateId;
			mem.lastStateChangeFrame = frameIndex;
		}
	}

	private static void updateEntityMotionMemory(EntityMemory mem, double x, double y, double z) {
		if (Double.isNaN(mem.lastEntityX)) {
			mem.lastEntityX = x;
			mem.lastEntityY = y;
			mem.lastEntityZ = z;
			return;
		}
		double dx = x - mem.lastEntityX;
		double dy = y - mem.lastEntityY;
		double dz = z - mem.lastEntityZ;
		if (dx * dx + dy * dy + dz * dz > 0.01D) {
			mem.lastMotionChangeFrame = frameIndex;
		}
		mem.lastEntityX = x;
		mem.lastEntityY = y;
		mem.lastEntityZ = z;
	}

	private static double clamp01(double value) {
		return Math.max(0.0D, Math.min(1.0D, value));
	}

	private static boolean isLookedAt(long key, double dx, double dy, double dz, double dsqr) {
		if (dsqr <= 0.0001D) return true;
		if (crosshairBlockKey == key) return true;
		return lookDot(dx, dy, dz, dsqr) >= LOOKED_AT_DOT;
	}

	private static boolean isInFront(double dx, double dy, double dz, double dsqr) {
		return dsqr <= 0.0001D || lookDot(dx, dy, dz, dsqr) > 0.0D;
	}

	private static boolean isLookedAtDirection(double dx, double dy, double dz, double dsqr) {
		return dsqr <= 0.0001D || lookDot(dx, dy, dz, dsqr) >= LOOKED_AT_DOT;
	}

	private static double centerPresence(double dx, double dy, double dz, double dsqr) {
		if (dsqr <= 0.0001D) return 1.0D;
		double dot = lookDot(dx, dy, dz, dsqr);
		return centerPresenceFromDot(dot, dsqr);
	}

	private static double centerPresenceFromDot(double dot, double dsqr) {
		if (dsqr <= 0.0001D) return 1.0D;
		if (dot <= 0.0D) return 0.0D;
		return Math.max(0.0D, Math.min(1.0D, (dot - 0.50D) * 2.0D));
	}

	private static boolean isMovingToward(double dx, double dz, double distance) {
		return cameraVelocityAbs > 0.05D && movingTowardDot(dx, dz, distance) > 0.5D;
	}

	private static double movingTowardDot(double dx, double dz, double distance) {
		return (dx * cameraVelocityX + dz * cameraVelocityZ) / Math.max(0.001D, distance * cameraVelocityAbs);
	}

	private static double lookDot(double dx, double dy, double dz, double dsqr) {
		if (dsqr <= 0.0001D) return 1.0D;
		if (cameraLookFrame == frameIndex) {
			return (dx * cameraLookX + dy * cameraLookY + dz * cameraLookZ) / Math.sqrt(dsqr);
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.gameRenderer == null || mc.gameRenderer.getMainCamera() == null) return 0.0D;
		var look = mc.gameRenderer.getMainCamera().getLookVector();
		cameraLookX = look.x;
		cameraLookY = look.y;
		cameraLookZ = look.z;
		cameraLookFrame = frameIndex;
		return (dx * look.x + dy * look.y + dz * look.z) / Math.sqrt(dsqr);
	}

	private static boolean isDiagnosticDetailEnabled() {
		return OptiminiumSettings.isBlockEntityRenderCacheDebug()
			|| Boolean.getBoolean("optiminium.visualSignificanceDebug");
	}

	private static boolean isDetailedStatisticsEnabled() {
		return detailedStatisticsThisFrame;
	}

	private static void recordInteractionTarget() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.options == null || (!mc.options.keyUse.isDown() && !mc.options.keyAttack.isDown())) return;
		if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
			recentlyInteracted.put(hit.getBlockPos().asLong(), frameIndex);
			lastBlockInteractionFrame = frameIndex;
		} else if (mc.hitResult instanceof EntityHitResult hit && hit.getType() == HitResult.Type.ENTITY) {
			recentlyInteractedEntities.put(hit.getEntity().getId(), frameIndex);
			lastEntityInteractionFrame = frameIndex;
		}
	}

	private static void captureCrosshairTarget() {
		Minecraft mc = Minecraft.getInstance();
		crosshairBlockKey = mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
			? hit.getBlockPos().asLong() : Long.MIN_VALUE;
	}

	private static boolean prototypeConfidenceProtection(long key) {
		ObjectMemory mem = objectMemory.get(key);
		return mem != null && mem.confidence > 0.8D && mem.attentionScore > 0.6D;
	}

	// Remove per-object recording - we use sampled timing above
	// Keep simplified version for snapshot backward compat
	private static double averageSignificanceMsExt() {
		if (sampledTimingCount <= 0) return 0.0D;
		double avgNanos = (double) sampledTimingTotal / sampledTimingCount;
		return avgNanos / 1_000_000.0D;
	}
	
	private static double worstSignificanceMsExt() {
		return sampledWorstTiming / 1_000_000.0D;
	}

	// ============================================================
	//  Importance and render cost helpers
	// ============================================================

	private static boolean isImportant(BlockEntity be) {
		return isImportant(be.getType(), be.getBlockState());
	}

	private static boolean isImportant(BlockEntityType<?> t, BlockState state) {
		int stateId = Block.getId(state);
		Int2ByteOpenHashMap stateCache = importantBlockCache.get(t);
		if (stateCache == null) {
			stateCache = new Int2ByteOpenHashMap();
			stateCache.defaultReturnValue(UNKNOWN);
			importantBlockCache.put(t, stateCache);
		} else {
			byte cached = stateCache.get(stateId);
			if (cached != UNKNOWN) return cached == 1;
		}
		boolean important = computeBlockImportant(t, state);
		stateCache.put(stateId, important ? (byte)1 : (byte)0);
		return important;
	}

	private static boolean computeBlockImportant(BlockEntityType<?> t, BlockState state) {
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
		return blockEntityRenderCost(be, be.getType(), be.getBlockState());
	}

	private static double blockEntityRenderCost(BlockEntity be, BlockEntityType<?> t, BlockState state) {
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
		if (isModdedBlockEntity(t)) return dynamicBlockEntityCost(be, state);
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
		return isModdedBlockEntity(be.getType());
	}

	private static boolean isModdedBlockEntity(BlockEntityType<?> type) {
		return isModdedKey(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type));
	}

	private static boolean isModdedEntity(Entity entity) {
		return isModdedKey(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
	}

	private static boolean isModdedKey(ResourceLocation key) {
		return key != null && !"minecraft".equals(key.getNamespace());
	}

	private static void recordDynamicModNamespace(ResourceLocation key) {
		if (!isDetailedStatisticsEnabled()) return;
		if (!isModdedKey(key)) return;
		if ("none".equals(firstDynamicModNamespace)) firstDynamicModNamespace = key.getNamespace();
		lastDynamicModNamespace = key.getNamespace();
	}

	private static double dynamicBlockEntityCost(BlockEntity be) {
		return dynamicBlockEntityCost(be, be.getBlockState());
	}

	private static double dynamicBlockEntityCost(BlockEntity be, BlockState state) {
		if (isDetailedStatisticsEnabled()) {
			dynamicModdedBlockEntityCosted++;
			recordDynamicModNamespace(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()));
		}
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
		if (isDetailedStatisticsEnabled()) {
			dynamicModdedLivingEntityCosted++;
			recordDynamicModNamespace(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
		}
		double cost = 0.65D + Math.min(0.20D, entity.getBbWidth() * entity.getBbHeight() * 0.04D);
		if (entity.hasCustomName() || entity.hasGlowingTag()) cost += 0.10D;
		if (entity.isPassenger() || !entity.getPassengers().isEmpty()) cost += 0.10D;
		return Math.min(0.90D, cost);
	}

	private static double dynamicNonLivingEntityCost(Entity entity) {
		if (isDetailedStatisticsEnabled()) {
			dynamicModdedNonLivingEntityCosted++;
			recordDynamicModNamespace(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
		}
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
		dirtyObjectsThisFrame = 0;
		bandTransitionBudgetUsedThisFrame = 0;
		bandTransitionsDeferredThisFrame = 0;
		scoreRecomputesThisFrame = 0;
		scoreRecomputesDeferredThisFrame = 0L;
		periodicRechecksThisFrame = 0;
		promotionTransitionsThisFrame = 0;
		demotionTransitionsThisFrame = 0;
		proxyCreationsThisFrame = 0;
		proxyDestructionsThisFrame = 0;
		visibilityChangesThisFrame = 0;
		importanceDebtPromotionsThisFrame = 0;
		urgentTransitionsThisFrame = 0;
		visibleDemotionsThisFrame = 0;
	}
	
	private static void processDeferredTransitions() {
		if (deferredTransitionKeys.isEmpty()) return;
		int processed = 0;
		int maxProcess = Math.min(DEFERRED_TRANSITIONS_PROCESS_PER_FRAME, deferredTransitionKeys.size());
		while (processed < maxProcess && !deferredTransitionKeys.isEmpty()) {
			long key = deferredTransitionKeys.pollFirst();
			// Entity keys are stored as ~entityId (negative)
			if (key < 0L) {
				int entityId = (int)(~key);
				EntityMemory mem = entityMemory.get(entityId);
				if (mem != null) {
					mem.lastReevaluateFrame = 0L;
				}
			} else {
				ObjectMemory mem = objectMemory.get(key);
				if (mem != null) {
					mem.lastReevaluateFrame = 0L;
				}
			}
			processed++;
		}
		// Track max queue size for diagnostics
		if (deferredTransitionKeys.size() > maxDeferredQueueSize) {
			maxDeferredQueueSize = deferredTransitionKeys.size();
		}
	}

	private static void captureFrameBurstSnapshot() {
		lastFrameBurst = new FrameBurstSnapshot(
			frameIndex,
			scoreRecomputesThisFrame,
			scoreRecomputesDeferredThisFrame,
			bandTransitionBudgetUsedThisFrame,
			bandTransitionsDeferredThisFrame,
			promotionTransitionsThisFrame,
			demotionTransitionsThisFrame,
			proxyCreationsThisFrame,
			proxyDestructionsThisFrame,
			periodicRechecksThisFrame,
			dirtyObjectsThisFrame,
			visibilityChangesThisFrame,
			importanceDebtPromotionsThisFrame
		);
		// Track largest transition burst for diagnostics
		int totalTransitionsThisFrame = promotionTransitionsThisFrame + demotionTransitionsThisFrame;
		if (totalTransitionsThisFrame > largestTransitionBurstRecorded) {
			largestTransitionBurstRecorded = totalTransitionsThisFrame;
			String reason = "normal";
			int maxCount = 0;
			if (periodicRechecksThisFrame > maxCount) { reason = "periodicRecheck"; maxCount = periodicRechecksThisFrame; }
			if (dirtyObjectsThisFrame > maxCount) { reason = "dirtyObjects"; maxCount = dirtyObjectsThisFrame; }
			if (visibilityChangesThisFrame > maxCount) { reason = "visibilityChange"; maxCount = visibilityChangesThisFrame; }
			if (importanceDebtPromotionsThisFrame > maxCount) { reason = "importanceDebt"; maxCount = importanceDebtPromotionsThisFrame; }
			if (urgentTransitionsThisFrame > maxCount) { reason = "urgentPromotions"; maxCount = urgentTransitionsThisFrame; }
			largestTransitionBurstReason = reason + ":" + totalTransitionsThisFrame
				+ "T/" + scoreRecomputesThisFrame + "R/" + periodicRechecksThisFrame + "P"
				+ "/urgent:" + urgentTransitionsThisFrame;
		}
	}

	public static FrameBurstSnapshot frameBurstSnapshot() {
		return lastFrameBurst;
	}

	public static SchedulerSnapshot schedulerSnapshot() {
		double averageDelay = importanceDebtTriggered == 0L ? 0.0D
			: evaluationDelayFrames / (double)importanceDebtTriggered;
		double averageSize = schedulerSizeSamples == 0L ? 0.0D
			: accumulatedSchedulerSize / (double)schedulerSizeSamples;
		long schedulingSamples = importanceDebtScheduled + importanceDebtMerged;
		double averageFrames = schedulingSamples == 0L ? 0.0D
			: accumulatedFramesUntilPromotion / (double)schedulingSamples;
		return new SchedulerSnapshot(importanceDebtScheduled, importanceDebtTriggered,
			importanceDebtCancelled, importanceDebtMerged, scheduledEvaluations,
			eventDrivenEvaluations, periodicRechecks, averageDelay, maxEvaluationDelayFrames,
			averageSize, peakSchedulerSize, objectsAwaitingPromotion, averageFrames,
			periodicScheduled, periodicTriggered, periodicMerged, periodicDelayed, periodicCancelled,
			periodicEvaluationSamples == 0L ? 0.0D : periodicEvaluationSampleNanos / (double)periodicEvaluationSamples / 1_000_000.0D,
			debtEvaluationSamples == 0L ? 0.0D : debtEvaluationSampleNanos / (double)debtEvaluationSamples / 1_000_000.0D,
			cacheFastPathSamples == 0L ? 0.0D : cacheFastPathSampleNanos / (double)cacheFastPathSamples / 1_000_000.0D);
	}

	public static String diagnosticLine() {
		return snapshot().toLine();
	}

	public static String diagnosticLine(int trackedObjectFallback) {
		return snapshot(trackedObjectFallback).toLine();
	}

	public static String topExpensiveObjectsLine() {
		if (!topExpensiveDirty) return topExpensiveCached;
		if (topExpensiveScores.isEmpty()) {
			topExpensiveCached = "none";
		} else {
			var entries = new java.util.ArrayList<>(topExpensiveScores.long2DoubleEntrySet());
			entries.sort(java.util.Map.Entry.comparingByValue(java.util.Comparator.reverseOrder()));
			StringBuilder sb = new StringBuilder("top10Expensive=");
			int count = 0;
			for (var entry : entries) {
				if (count > 0) sb.append("|");
				sb.append(String.format("key=%d,cost=%.4f", entry.getLongKey(), entry.getDoubleValue()));
				count++;
				if (count >= TOP_EXPENSIVE_COUNT) break;
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
		double avgImportanceDebt = accumulatedScoreCount > 0 ? accumulatedImportanceDebt / accumulatedScoreCount : 0.0D;
		double avgPopRisk = accumulatedScoreCount > 0 ? accumulatedPopRisk / accumulatedScoreCount : 0.0D;
		double avgVisualImportance = accumulatedScoreCount > 0 ? accumulatedVisualImportance / accumulatedScoreCount : 0.0D;
		double avgGameplayImportance = accumulatedScoreCount > 0 ? accumulatedGameplayImportance / accumulatedScoreCount : 0.0D;
		double avgSafetyImportance = accumulatedScoreCount > 0 ? accumulatedSafetyImportance / accumulatedScoreCount : 0.0D;
		double avgWeightedAttention = accumulatedScoreCount > 0 ? accumulatedWeightedAttentionScore / accumulatedScoreCount : 0.0D;
		double avgWeightedFull = averageWeightedAttentionForBand(FULL);
		double avgWeightedThrottled = averageWeightedAttentionForBand(THROTTLED);
		double avgWeightedReused = averageWeightedAttentionForBand(REUSED);
		double avgWeightedProxy = averageWeightedAttentionForBand(PROXY);
		double avgWeightedCulled = averageWeightedAttentionForBand(CULLED);
		double hiScore = highestVisualSignificance;
		double loScore = lowestVisualSignificance == Double.POSITIVE_INFINITY ? 0.0D : lowestVisualSignificance;
		double minConf = minConfidence == Double.POSITIVE_INFINITY ? 0.0D : minConfidence;
		long transitionCount = promotionTransitions + demotionTransitions;
		double averageBandLifetime = transitionCount > 0L ? accumulatedBandLifetimeFrames / (double) transitionCount : 0.0D;
		double averageTicksInBand = ticksInBandSamples > 0L ? accumulatedTicksInBand / (double) ticksInBandSamples : 0.0D;
		double frames = Math.max(1.0D, frameIndex);
		long recentlyVisibleProtections = blockEntityCullPreventedByRecentlyVisible + entityCullPreventedByRecentlyVisible;
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
			averageSignificanceMs(), worstSignificanceMs(),
			mostCommonReason(),
			nearestDistanceSqr == Double.POSITIVE_INFINITY ? -1.0D : Math.sqrt(nearestDistanceSqr),
			cameraVelocityAbs, cameraRotationSpeed, cameraFastMoving, cameraStable,
			Math.max(objectMemory.size(), trackedObjectFallback), entityMemory.size(),
			promotionsPreventedByHysteresis, demotionsPreventedByHysteresis,
			promotionsPreventedByConfidence, demotionsPreventedByConfidence,
			lowConfidenceDemotionBlocks, highPopRiskDemotionBlocks,
			recentlyVisibleProtections, recentlyLookedAtProtections,
			recentlyInteractedProtections, recentlyChangedProtections,
			recentlyMovedProtections, recentlyEnteredViewProtections,
			fastCameraDemotionBlocks,
			promotionsBecauseLowConfidence, demotionsAllowedBecauseHighConfidence,
			singleBandTransitionsEnforced, avgScore, avgConf, minConf, maxConfidence,
			avgCost, avgCoverage, avgTemporal, hiScore, loScore,
			topExpensiveObjectsLine(),
			fullForcedBySafety, fullByWeightedScore,
			importantButThrottled, importantButReused, importantButProxy, importantButCulled,
			averageBandLifetime, demotionTransitions / frames, promotionTransitions / frames,
			avgImportanceDebt, importanceDebtPromotions,
			avgPopRisk, avgVisualImportance, avgGameplayImportance, avgSafetyImportance,
			avgWeightedAttention, avgWeightedFull, avgWeightedThrottled, avgWeightedReused,
			avgWeightedProxy, avgWeightedCulled,
			confidenceBuckets[0], confidenceBuckets[1], confidenceBuckets[2],
			confidenceBuckets[3], confidenceBuckets[4],
			decisionBecauseWeightedScore, decisionBecausePopRiskVeto, decisionBecauseConfidenceVeto,
			decisionBecauseSafetyOverride, decisionBecauseRecentlyVisible, decisionBecauseImportanceDebt,
			decisionBecauseHysteresis, decisionBecauseNearbyOverride, decisionBecauseGameplayOverride,
			topTransitionPairsLine(), dirtyObjectsThisFrame, dirtyObjectsTotal, skippedStableObjects,
			scoreCacheHits, scoreRecomputes, bandTransitionBudgetUsed,
			bandTransitionBudgetUsedThisFrame, bandTransitionsDeferred, periodicRechecks,
			averageTicksInBand, transitionsFromDirtyObjects, transitionsFromStableObjects,
			dirtyReasonsLine()
		);
	}

	public static void reset() {
		for (ObjectMemory mem : objectMemory.values()) mem.importanceDebtDueFrame = 0L;
		for (EntityMemory mem : entityMemory.values()) mem.importanceDebtDueFrame = 0L;
		for (ObjectMemory mem : objectMemory.values()) mem.nextPeriodicEvaluationFrame = 0L;
		for (EntityMemory mem : entityMemory.values()) mem.nextPeriodicEvaluationFrame = 0L;
		importanceDebtScheduled = 0L; importanceDebtTriggered = 0L;
		importanceDebtCancelled = 0L; importanceDebtMerged = 0L;
		scheduledEvaluations = 0L; eventDrivenEvaluations = 0L;
		evaluationDelayFrames = 0L; maxEvaluationDelayFrames = 0L;
		schedulerSizeSamples = 0L; accumulatedSchedulerSize = 0L; peakSchedulerSize = 0L;
		accumulatedFramesUntilPromotion = 0L;
		objectsAwaitingPromotion = 0L;
		periodicScheduled = 0L; periodicTriggered = 0L; periodicMerged = 0L;
		periodicDelayed = 0L; periodicCancelled = 0L;
		evaluationTimingCounter = 0; periodicEvaluationSampleNanos = 0L; periodicEvaluationSamples = 0L;
		debtEvaluationSampleNanos = 0L; debtEvaluationSamples = 0L;
		cacheFastPathSampleNanos = 0L; cacheFastPathSamples = 0L;
		topExpensiveScores.clear();
		topExpensiveKeys.clear();
		topExpensiveDirty = true;
		topExpensiveCached = "none";
		highestVisualSignificance = 0.0D;
		lowestVisualSignificance = Double.POSITIVE_INFINITY;
		accumulatedRenderCost = 0.0D;
		accumulatedScreenCoverage = 0.0D;
		accumulatedTemporalScore = 0.0D;
		accumulatedImportanceDebt = 0.0D;
		accumulatedPopRisk = 0.0D;
		accumulatedVisualImportance = 0.0D;
		accumulatedGameplayImportance = 0.0D;
		accumulatedSafetyImportance = 0.0D;
		accumulatedWeightedAttentionScore = 0.0D;
		for (int i = 0; i < weightedAttentionByBand.length; i++) {
			weightedAttentionByBand[i] = 0.0D;
			weightedAttentionSamplesByBand[i] = 0L;
			confidenceBuckets[i] = 0L;
		}
		for (int i = 0; i < dirtyReasonCounts.length; i++) {
			dirtyReasonCounts[i] = 0L;
		}
		dirtyObjectsThisFrame = 0; dirtyObjectsTotal = 0L; skippedStableObjects = 0L;
		scoreCacheHits = 0L; scoreRecomputes = 0L; scoreRecomputesDeferred = 0L;
		scoreRecomputesThisFrame = 0; scoreRecomputesDeferredThisFrame = 0L;
		bandTransitionBudgetUsedThisFrame = 0; bandTransitionsDeferredThisFrame = 0;
		bandTransitionBudgetUsed = 0L; bandTransitionsDeferred = 0L; periodicRechecks = 0L;
		periodicRechecksThisFrame = 0;
		accumulatedTicksInBand = 0L; ticksInBandSamples = 0L;
		transitionsFromDirtyObjects = 0L; transitionsFromStableObjects = 0L;
		promotionTransitionsThisFrame = 0; demotionTransitionsThisFrame = 0;
		proxyCreationsThisFrame = 0; proxyDestructionsThisFrame = 0;
		visibilityChangesThisFrame = 0; importanceDebtPromotionsThisFrame = 0;
		lastFrameBurst = FrameBurstSnapshot.EMPTY;
		fullForcedBySafety = 0L; fullByWeightedScore = 0L;
		importantButThrottled = 0L; importantButReused = 0L; importantButProxy = 0L;
		decisionBecauseWeightedScore = 0L; decisionBecausePopRiskVeto = 0L;
		decisionBecauseConfidenceVeto = 0L; decisionBecauseSafetyOverride = 0L;
		decisionBecauseRecentlyVisible = 0L; decisionBecauseImportanceDebt = 0L;
		decisionBecauseHysteresis = 0L; decisionBecauseNearbyOverride = 0L;
		decisionBecauseGameplayOverride = 0L;
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
		lowConfidenceDemotionBlocks = 0L; highPopRiskDemotionBlocks = 0L;
		recentlyLookedAtProtections = 0L; recentlyInteractedProtections = 0L;
		recentlyChangedProtections = 0L; recentlyMovedProtections = 0L;
		recentlyEnteredViewProtections = 0L;
		fastCameraDemotionBlocks = 0L; promotionsBecauseLowConfidence = 0L;
		demotionsAllowedBecauseHighConfidence = 0L; importantButCulled = 0L;
		promotionTransitions = 0L; demotionTransitions = 0L; accumulatedBandLifetimeFrames = 0L;
		importanceDebtPromotions = 0L;
		singleBandTransitionsEnforced = 0L;
		for (int i = 0; i < bandTransitionPairs.length; i++) {
			bandTransitionPairs[i] = 0L;
		}
		accumulatedContinuousScores = 0.0D; accumulatedConfidence = 0.0D; accumulatedScoreCount = 0L;
		diagnosticSampleCounter = 0;
		minConfidence = Double.POSITIVE_INFINITY; maxConfidence = 0.0D;
		significanceNanos = 0L; worstSignificanceNanos = 0L; profiledObjects = 0L;
		nearestDistanceSqr = Double.POSITIVE_INFINITY;
		seenCounts.clear(); recentlyInteracted.clear(); recentlyInteractedEntities.clear(); beClassifications.clear();
		objectMemory.clear(); entityMemory.clear(); entityOscillation.clear();
		cleanupCounter = 0;
		cameraStable = false; cameraFastMoving = false; cameraVelocityAbs = 0.0D;
		cameraRotationSpeed = 0.0D; cameraAcceleration = 0.0D; lastCameraVelocityAbs = 0.0D;
		lastCameraX = Double.NaN; lastCameraY = Double.NaN; lastCameraZ = Double.NaN;
		cameraLookX = 0.0D; cameraLookY = 0.0D; cameraLookZ = 1.0D; cameraLookFrame = -1L;
		crosshairBlockKey = Long.MIN_VALUE;
		lastProtectionCacheFrame = -1L; lastProtectionCacheKey = Long.MIN_VALUE; lastProtectionCacheValue = UNKNOWN;
		lastBlockInteractionFrame = NEVER_INTERACTED_FRAME; lastEntityInteractionFrame = NEVER_INTERACTED_FRAME;
		lastCameraYaw = 0.0f; lastCameraPitch = 0.0f; stableFrameCounter = 0;
		debugEntityId = -1; debugLogCounter = 0;
		activeLastFrame = false;
		detailedStatisticsThisFrame = false;
		deferredTransitionKeys.clear();
		maxDeferredQueueSize = 0;
		emergencyTransitionsBypassedBudget = 0L;
		nonUrgentTransitionsDelayed = 0L;
		urgentTransitionsThisFrame = 0;
		largestTransitionBurstRecorded = 0;
		largestTransitionBurstReason = "none";
	}

	private static double getMinTopExpensiveScore() {
		if (topExpensiveScores.isEmpty()) return 0.0D;
		double min = Double.POSITIVE_INFINITY;
		for (double v : topExpensiveScores.values()) {
			if (v < min) min = v;
		}
		return min;
	}

	private static void trimTopExpensive() {
		if (topExpensiveScores.size() <= TOP_EXPENSIVE_COUNT) return;
		double min = getMinTopExpensiveScore();
		var it = topExpensiveKeys.iterator();
		while (it.hasNext() && topExpensiveScores.size() > TOP_EXPENSIVE_COUNT) {
			long key = it.next();
			if (topExpensiveScores.containsKey(key) && topExpensiveScores.get(key) <= min) {
				topExpensiveScores.remove(key);
				it.remove();
			}
		}
	}

	private static double averageSignificanceMs() {
		return averageSignificanceMsExt();
	}

	private static double averageWeightedAttentionForBand(byte band) {
		long samples = weightedAttentionSamplesByBand[band];
		return samples > 0L ? weightedAttentionByBand[band] / samples : 0.0D;
	}

	private static String topTransitionPairsLine() {
		long firstCount = 0L, secondCount = 0L, thirdCount = 0L;
		int firstIndex = -1, secondIndex = -1, thirdIndex = -1;
		for (int i = 0; i < bandTransitionPairs.length; i++) {
			long count = bandTransitionPairs[i];
			if (count <= 0L) continue;
			if (count > firstCount) {
				thirdCount = secondCount; thirdIndex = secondIndex;
				secondCount = firstCount; secondIndex = firstIndex;
				firstCount = count; firstIndex = i;
			} else if (count > secondCount) {
				thirdCount = secondCount; thirdIndex = secondIndex;
				secondCount = count; secondIndex = i;
			} else if (count > thirdCount) {
				thirdCount = count; thirdIndex = i;
			}
		}
		if (firstIndex < 0) return "none";
		StringBuilder builder = new StringBuilder();
		appendTransitionPair(builder, firstIndex, firstCount);
		if (secondIndex >= 0) appendTransitionPair(builder, secondIndex, secondCount);
		if (thirdIndex >= 0) appendTransitionPair(builder, thirdIndex, thirdCount);
		return builder.toString();
	}

	private static String dirtyReasonsLine() {
		StringBuilder builder = new StringBuilder();
		appendDirtyReason(builder, DIRTY_ENTERED_VIEW, "enteredView");
		appendDirtyReason(builder, DIRTY_LEFT_VIEW, "leftView");
		appendDirtyReason(builder, DIRTY_OBJECT_MOVED, "objectMoved");
		appendDirtyReason(builder, DIRTY_OBJECT_STATE_CHANGED, "objectStateChanged");
		appendDirtyReason(builder, DIRTY_CAMERA_MOVED, "cameraMoved");
		appendDirtyReason(builder, DIRTY_CAMERA_ROTATED, "cameraRotated");
		appendDirtyReason(builder, DIRTY_CROSSHAIR_PROXIMITY, "crosshairProximity");
		appendDirtyReason(builder, DIRTY_PERIODIC_RECHECK, "periodicRecheck");
		appendDirtyReason(builder, DIRTY_IMPORTANCE_DEBT, "importanceDebt");
		appendDirtyReason(builder, DIRTY_RECENT_VISIBILITY, "recentVisibility");
		appendDirtyReason(builder, DIRTY_INTERACTION, "interaction");
		appendDirtyReason(builder, DIRTY_FIRST_SEEN, "firstSeen");
		appendDirtyReason(builder, DIRTY_NEARBY_CHANGED, "nearbyChanged");
		appendDirtyReason(builder, DIRTY_GAMEPLAY_IMPORTANCE_CHANGED, "gameplayImportanceChanged");
		return builder.length() == 0 ? "none" : builder.toString();
	}

	private static void appendDirtyReason(StringBuilder builder, int bit, String name) {
		long count = dirtyReasonCounts[Integer.numberOfTrailingZeros(bit)];
		if (count <= 0L) return;
		if (builder.length() > 0) builder.append("|");
		builder.append(name).append(":").append(count);
	}

	private static void appendTransitionPair(StringBuilder builder, int index, long count) {
		if (builder.length() > 0) builder.append("|");
		builder.append(bandName((byte)(index / 5))).append("->").append(bandName((byte)(index % 5)))
			.append(":").append(count);
	}
	
	private static double worstSignificanceMs() {
		return worstSignificanceMsExt();
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

	public record FrameBurstSnapshot(
		long frameIndex,
		int scoreRecomputes,
		long scoreRecomputesDeferred,
		int bandTransitions,
		int bandTransitionsDeferred,
		int promotions,
		int demotions,
		int proxyCreations,
		int proxyDestructions,
		int periodicRechecks,
		int dirtyObjects,
		int visibilityChanges,
		int importanceDebtPromotions
	) {
		private static final FrameBurstSnapshot EMPTY = new FrameBurstSnapshot(0L, 0, 0L, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

		int totalTransitions() {
			return promotions + demotions;
		}
	}

	public record SchedulerSnapshot(
		long importanceDebtScheduled, long importanceDebtTriggered,
		long importanceDebtCancelled, long importanceDebtMerged,
		long scheduledEvaluations, long eventDrivenEvaluations, long periodicEvaluations,
		double averageEvaluationDelay, long maxEvaluationDelay,
		double averageSchedulerSize, long peakSchedulerSize,
		long objectsAwaitingPromotion, double averageFramesUntilPromotion,
		long periodicScheduled, long periodicTriggered, long periodicMerged,
		long periodicDelayed, long periodicCancelled,
		double averagePeriodicEvaluationMs, double averageDebtEvaluationMs,
		double averageCacheFastPathMs
	) {
		public String toLine() {
			return String.format(java.util.Locale.US,
				"importanceDebtScheduled=%d, importanceDebtTriggered=%d, importanceDebtCancelled=%d, importanceDebtMerged=%d, scheduledEvaluations=%d, eventDrivenEvaluations=%d, periodicEvaluations=%d, averageEvaluationDelay=%.3f, maxEvaluationDelay=%d, averageSchedulerSize=%.3f, peakSchedulerSize=%d, objectsAwaitingPromotion=%d, averageFramesUntilPromotion=%.3f, periodicScheduled=%d, periodicTriggered=%d, periodicMerged=%d, periodicDelayed=%d, periodicCancelled=%d, averagePeriodicEvaluationMs=%.6f, averageDebtEvaluationMs=%.6f, averageCacheFastPathMs=%.6f",
				importanceDebtScheduled, importanceDebtTriggered, importanceDebtCancelled,
				importanceDebtMerged, scheduledEvaluations, eventDrivenEvaluations,
				periodicEvaluations, averageEvaluationDelay, maxEvaluationDelay,
				averageSchedulerSize, peakSchedulerSize, objectsAwaitingPromotion,
				averageFramesUntilPromotion, periodicScheduled, periodicTriggered,
				periodicMerged, periodicDelayed, periodicCancelled,
				averagePeriodicEvaluationMs, averageDebtEvaluationMs, averageCacheFastPathMs);
		}
	}

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
		long lowConfidenceDemotionBlocks, long highPopRiskDemotionBlocks,
		long recentlyVisibleProtections, long recentlyLookedAtProtections,
		long recentlyInteractedProtections, long recentlyChangedProtections,
		long recentlyMovedProtections, long recentlyEnteredViewProtections,
		long fastCameraDemotionBlocks,
		long promotionsBecauseLowConfidence, long demotionsAllowedBecauseHighConfidence,
		long singleBandTransitionsEnforced,
		double averageSignificanceScore, double averageConfidence,
		double minConfidence, double maxConfidence,
		double averageRenderCost, double averageScreenCoverage,
		double averageTemporalScore, double highestVisualSignificance,
		double lowestVisualSignificance, String topExpensiveObjects,
		long fullForcedBySafety, long fullByWeightedScore,
		long importantButThrottled, long importantButReused,
		long importantButProxy, long importantButCulled,
		double averageBandLifetime, double demotionsPerFrame,
		double promotionsPerFrame,
		double averageImportanceDebt, long importanceDebtPromotions,
		double averagePopRisk, double averageVisualImportance,
		double averageGameplayImportance, double averageSafetyImportance,
		double averageWeightedAttentionScore, double averageWeightedAttentionFull,
		double averageWeightedAttentionThrottled, double averageWeightedAttentionReused,
		double averageWeightedAttentionProxy, double averageWeightedAttentionCulled,
		long confidenceBucketVeryLow, long confidenceBucketLow, long confidenceBucketMedium,
		long confidenceBucketHigh, long confidenceBucketVeryHigh,
		long decisionBecauseWeightedScore, long decisionBecausePopRiskVeto,
		long decisionBecauseConfidenceVeto, long decisionBecauseSafetyOverride,
		long decisionBecauseRecentlyVisible, long decisionBecauseImportanceDebt,
		long decisionBecauseHysteresis, long decisionBecauseNearbyOverride,
		long decisionBecauseGameplayOverride, String topTransitionPairs,
		long dirtyObjectsThisFrame, long dirtyObjectsTotal, long skippedStableObjects,
		long scoreCacheHits, long scoreRecomputes, long bandTransitionBudgetUsed,
		long bandTransitionBudgetUsedThisFrame, long bandTransitionsDeferred,
		long periodicRechecks, double averageTicksInBand,
		long transitionsFromDirtyObjects, long transitionsFromStableObjects,
		String dirtyReasons
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
				+ ",lowConfidenceDemotionBlocks:" + lowConfidenceDemotionBlocks
				+ ",highPopRiskDemotionBlocks:" + highPopRiskDemotionBlocks
				+ ",recentlyVisibleProtections:" + recentlyVisibleProtections
				+ ",recentlyLookedAtProtections:" + recentlyLookedAtProtections
				+ ",recentlyInteractedProtections:" + recentlyInteractedProtections
				+ ",recentlyChangedProtections:" + recentlyChangedProtections
				+ ",recentlyMovedProtections:" + recentlyMovedProtections
				+ ",recentlyEnteredViewProtections:" + recentlyEnteredViewProtections
				+ ",fastCameraDemotionBlocks:" + fastCameraDemotionBlocks
				+ ",promotionsBecauseLowConfidence:" + promotionsBecauseLowConfidence
				+ ",demotionsAllowedBecauseHighConfidence:" + demotionsAllowedBecauseHighConfidence
				+ ",singleBandTransitionsEnforced:" + singleBandTransitionsEnforced
				+ ",avgSignificanceScore:" + String.format("%.4f", averageSignificanceScore)
				+ ",avgConfidence:" + String.format("%.4f", averageConfidence)
				+ ",minConfidence:" + String.format("%.4f", minConfidence)
				+ ",maxConfidence:" + String.format("%.4f", maxConfidence)
				+ ",avgRenderCost:" + String.format("%.4f", averageRenderCost)
				+ ",avgScreenCoverage:" + String.format("%.4f", averageScreenCoverage)
				+ ",avgTemporalScore:" + String.format("%.4f", averageTemporalScore)
				+ ",highestVisualSignificance:" + String.format("%.4f", highestVisualSignificance)
				+ ",lowestVisualSignificance:" + String.format("%.4f", lowestVisualSignificance)
				+ ",importantButCulled:" + importantButCulled
				+ ",averageBandLifetime:" + String.format("%.2f", averageBandLifetime)
				+ ",demotionsPerFrame:" + String.format("%.4f", demotionsPerFrame)
				+ ",promotionsPerFrame:" + String.format("%.4f", promotionsPerFrame)
				+ ",avgImportanceDebt:" + String.format("%.4f", averageImportanceDebt)
				+ ",importanceDebtPromotions:" + importanceDebtPromotions
				+ ",avgPopRisk:" + String.format("%.4f", averagePopRisk)
				+ ",avgVisualImportance:" + String.format("%.4f", averageVisualImportance)
				+ ",avgGameplayImportance:" + String.format("%.4f", averageGameplayImportance)
				+ ",avgSafetyImportance:" + String.format("%.4f", averageSafetyImportance)
				+ ",avgWeightedAttentionScore:" + String.format("%.4f", averageWeightedAttentionScore)
				+ ",avgWeightedAttentionFull:" + String.format("%.4f", averageWeightedAttentionFull)
				+ ",avgWeightedAttentionThrottled:" + String.format("%.4f", averageWeightedAttentionThrottled)
				+ ",avgWeightedAttentionReused:" + String.format("%.4f", averageWeightedAttentionReused)
				+ ",avgWeightedAttentionProxy:" + String.format("%.4f", averageWeightedAttentionProxy)
				+ ",avgWeightedAttentionCulled:" + String.format("%.4f", averageWeightedAttentionCulled)
				+ ",confidenceBucketVeryLow:" + confidenceBucketVeryLow
				+ ",confidenceBucketLow:" + confidenceBucketLow
				+ ",confidenceBucketMedium:" + confidenceBucketMedium
				+ ",confidenceBucketHigh:" + confidenceBucketHigh
				+ ",confidenceBucketVeryHigh:" + confidenceBucketVeryHigh
				+ ",decisionBecauseWeightedScore:" + decisionBecauseWeightedScore
				+ ",decisionBecausePopRiskVeto:" + decisionBecausePopRiskVeto
				+ ",decisionBecauseConfidenceVeto:" + decisionBecauseConfidenceVeto
				+ ",decisionBecauseSafetyOverride:" + decisionBecauseSafetyOverride
				+ ",decisionBecauseRecentlyVisible:" + decisionBecauseRecentlyVisible
				+ ",decisionBecauseImportanceDebt:" + decisionBecauseImportanceDebt
				+ ",decisionBecauseHysteresis:" + decisionBecauseHysteresis
				+ ",decisionBecauseNearbyOverride:" + decisionBecauseNearbyOverride
				+ ",decisionBecauseGameplayOverride:" + decisionBecauseGameplayOverride
				+ ",topTransitionPairs:" + topTransitionPairs
				+ ",dirtyObjectsThisFrame:" + dirtyObjectsThisFrame
				+ ",dirtyObjectsTotal:" + dirtyObjectsTotal
				+ ",skippedStableObjects:" + skippedStableObjects
				+ ",scoreCacheHits:" + scoreCacheHits
				+ ",scoreRecomputes:" + scoreRecomputes
				+ ",bandTransitionBudgetUsed:" + bandTransitionBudgetUsed
				+ ",bandTransitionBudgetUsedThisFrame:" + bandTransitionBudgetUsedThisFrame
				+ ",bandTransitionsDeferred:" + bandTransitionsDeferred
				+ ",periodicRechecks:" + periodicRechecks
				+ ",averageTicksInBand:" + String.format("%.2f", averageTicksInBand)
				+ ",transitionsFromDirtyObjects:" + transitionsFromDirtyObjects
				+ ",transitionsFromStableObjects:" + transitionsFromStableObjects
				+ ",dirtyReasons:" + dirtyReasons
				+ "," + topExpensiveObjects
				+ ",maxDeferredTransitions:" + maxDeferredQueueSize
				+ ",deferredQueueSize:" + deferredTransitionKeys.size()
				+ ",emergencyBypassedBudget:" + emergencyTransitionsBypassedBudget
				+ ",nonUrgentDelayed:" + nonUrgentTransitionsDelayed
				+ ",largestBurstReason:" + largestTransitionBurstReason
				+ ",largestBurstRecorded:" + largestTransitionBurstRecorded;
		}
	}
}
