package net.optiminium.client;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import net.optiminium.optimization.OptiminiumSettings;
import org.joml.Vector3f;

/**
 * Lightweight Section Significance scoring for chunk sections.
 *
 * Assigns a score and priority band to each chunk section based on:
 * - distance from camera
 * - camera direction / view cone
 * - projected screen relevance
 * - whether the section is in front of or behind the player
 * - player velocity
 * - predicted future visibility
 * - recent visibility
 * - whether the player is moving toward it
 * - whether the section contains many block entities/entities
 * - rebuild/upload urgency
 * - whether the change was caused by nearby player interaction
 *
 * Designed to be cheap to compute. Does NOT scan the entire world every frame.
 * Only evaluates sections that are in the render/rebuild/upload path or recently tracked.
 *
 * Overhead target: < 0.01 ms per evaluated section
 */
public final class OptiminiumSectionSignificance {
	// ---- Priority bands (ordered from highest to lowest) ----
	public enum PriorityBand {
		CRITICAL(0),   // Must upload immediately
		HIGH(1),       // Upload next
		NORMAL(2),     // Upload within budget
		BACKGROUND(3), // Defer if under pressure
		DEFERRED(4);   // Delay until budget allows

		final int order;

		PriorityBand(int order) {
			this.order = order;
		}

		public boolean isMoreUrgentThan(PriorityBand other) {
			return this.order < other.order;
		}

		public boolean isLessUrgentThan(PriorityBand other) {
			return this.order > other.order;
		}
	}

	// ---- Distance thresholds (squared) ----
	private static final double CRITICAL_DISTANCE_SQR = 16.0D * 16.0D;
	private static final double HIGH_DISTANCE_SQR = 48.0D * 48.0D;
	private static final double NORMAL_DISTANCE_SQR = 96.0D * 96.0D;
	private static final double BACKGROUND_DISTANCE_SQR = 192.0D * 192.0D;

	// ---- View cone thresholds ----
	private static final double LOOKED_AT_DOT = 0.985D;
	private static final double IN_FRONT_DOT = 0.0D;
	private static final double WIDE_CONE_DOT = -0.3D; // slightly behind but still relevant

	// ---- Temporal constants ----
	private static final long RECENTLY_INTERACTED_FRAMES = 120L;
	private static final long RECENTLY_VISIBLE_FRAMES = 60L;
	private static final long NEVER_INTERACTED_FRAME = -RECENTLY_INTERACTED_FRAMES - 1L;
	private static final int BLOCK_ENTITY_COUNT_THRESHOLD = 4;
	private static final int ENTITY_COUNT_THRESHOLD = 3;

	// ---- Camera motion tracking (shared with VisualSignificance) ----
	private static double cameraVelocityX;
	private static double cameraVelocityZ;
	private static double cameraVelocityAbs;
	private static boolean cameraStable;
	private static boolean cameraFastMoving;

	// ---- Frame tracking ----
	private static long frameIndex;

	// ============================================================
	//  Metrics
	// ============================================================
	private static long sectionSignificanceUpdates;
	private static double accumulatedSectionSignificance;
	private static double maxSectionSignificance;
	private static long sectionPriorityCritical;
	private static long sectionPriorityHigh;
	private static long sectionPriorityNormal;
	private static long sectionPriorityBackground;
	private static long sectionPriorityDeferred;

	// ---- Upload admission metrics ----
	private static long chunkUploadsReady;
	private static long chunkUploadsAdmittedThisFrame;
	private static long chunkUploadsDeferredBySignificance;
	private static long chunkUploadsPromotedByNearCamera;
	private static long chunkUploadsPromotedByLookDirection;
	private static long chunkUploadsPromotedByPlayerVelocity;
	private static long chunkUploadsPromotedByRecentInteraction;
	static long uploadBurstPreventedFrames; // package-private for OptiminiumChunkUploadAdmission
	private static long maxDeferredChunkUploads;
	private static long accumulatedDeferredChunkUploads;
	private static long deferredCountSamples;
	private static long chunkUploadBudgetMs;
	private static long chunkUploadBudgetUsedMs;

	// ---- Safety metrics ----
	private static long nearCameraUploadDelayedFrames;
	private static long playerInteractionUploadDelayedFrames;
	private static long visibleChunkPopInRiskFrames;

	private OptiminiumSectionSignificance() {
	}

	// ============================================================
	//  Public API
	// ============================================================

	/**
	 * Called each frame before any section significance evaluations.
	 */
	public static void onFrameStart() {
		frameIndex++;
		updateCameraMotion();
		chunkUploadsAdmittedThisFrame = 0;
		chunkUploadBudgetUsedMs = 0L;
	}

	/**
	 * Compute a significance score and priority band for a chunk section.
	 *
	 * @param sectionOrigin The BlockPos origin of the section (getOrigin())
	 * @param sectionPos    The SectionPos (for entity/block entity lookup)
	 * @param camera        The camera for this frame
	 * @param isDirtyFromPlayer Whether the section is dirty from player interaction
	 * @param blockEntityCount Approximate block entity count in this section
	 * @param entityCount     Approximate entity count in this section
	 * @return The computed SectionResult with score and band
	 */
	public static SectionResult evaluate(
			BlockPos sectionOrigin,
			SectionPos sectionPos,
			Camera camera,
			boolean isDirtyFromPlayer,
			int blockEntityCount,
			int entityCount) {
		sectionSignificanceUpdates++;

		Vec3 cameraPos = camera.getPosition();

		// Section center (16-block sections, origin is min corner)
		double centerX = sectionOrigin.getX() + 8.0D;
		double centerY = sectionOrigin.getY() + 8.0D;
		double centerZ = sectionOrigin.getZ() + 8.0D;

		double dx = centerX - cameraPos.x;
		double dy = centerY - cameraPos.y;
		double dz = centerZ - cameraPos.z;
		double distanceSqr = dx * dx + dy * dy + dz * dz;
		double distance = Math.sqrt(Math.max(distanceSqr, 0.0001D));

		// ---- 1. Distance factor ----
		double distanceNorm = Math.min(1.0D, distance / 256.0D);
		double distanceScore = 1.0D - distanceNorm;

		// ---- 2. View cone / camera direction ----
		Vector3f lookVec = camera.getLookVector();
		double dot = (dx * lookVec.x + dy * lookVec.y + dz * lookVec.z) / distance;
		boolean inFront = dot > IN_FRONT_DOT;
		boolean lookedAt = dot > LOOKED_AT_DOT;
		boolean inWideCone = dot > WIDE_CONE_DOT;

		double viewConeScore = 0.0D;
		if (lookedAt) {
			viewConeScore = 1.0D;
		} else if (inFront) {
			viewConeScore = 0.5D + 0.5D * ((dot - IN_FRONT_DOT) / (LOOKED_AT_DOT - IN_FRONT_DOT));
		} else if (inWideCone) {
			viewConeScore = 0.2D * ((dot - WIDE_CONE_DOT) / (IN_FRONT_DOT - WIDE_CONE_DOT));
		}

		// ---- 3. Projected screen relevance (angular size proxy) ----
		// Larger sections closer to view direction get higher relevance
		double angularSize = 16.0D / Math.max(1.0D, distance);
		double screenRelevance = Math.min(1.0D, angularSize * 0.15D);
		if (!inFront) {
			screenRelevance *= 0.3D;
		}

		// ---- 4. Player velocity & predicted future visibility ----
		double velocityScore = 0.0D;
		double predictedVisibility = 0.0D;
		if (cameraVelocityAbs > 0.05D) {
			// Moving toward this section = higher priority
			double moveDot = (dx * cameraVelocityX + dz * cameraVelocityZ)
					/ Math.max(0.001D, distance * cameraVelocityAbs);
			if (moveDot > 0.3D) {
				velocityScore = moveDot * 0.4D;
			}

			// Predict future position
			double predictedDx = dx - cameraVelocityX * 10.0D * 0.05D;
			double predictedDz = dz - cameraVelocityZ * 10.0D * 0.05D;
			double predictedDistSqr = predictedDx * predictedDx + dy * dy + predictedDz * predictedDz;
			double predictedDist = Math.sqrt(Math.max(predictedDistSqr, 0.0001D));
			double predictedDot = (predictedDx * lookVec.x + dy * lookVec.y + predictedDz * lookVec.z) / predictedDist;
			if (predictedDot > IN_FRONT_DOT && predictedDistSqr < NORMAL_DISTANCE_SQR) {
				predictedVisibility = 0.3D;
				if (predictedDot > LOOKED_AT_DOT && predictedDistSqr < HIGH_DISTANCE_SQR) {
					predictedVisibility = 0.6D;
				}
			}
		}

		// ---- 5. Player interaction urgency ----
		double interactionScore = 0.0D;
		if (isDirtyFromPlayer) {
			interactionScore = 1.0D; // Max urgency for player-placed/broken blocks
		}

		// ---- 6. Block entity / entity density ----
		double densityScore = 0.0D;
		if (blockEntityCount > BLOCK_ENTITY_COUNT_THRESHOLD) {
			densityScore += 0.15D;
		}
		if (entityCount > ENTITY_COUNT_THRESHOLD) {
			densityScore += 0.10D;
		}
		densityScore = Math.min(0.25D, densityScore);

		// ---- 7. Combined score ----
		// Factors and their weights (must sum to 1.0 effectively with capped scores)
		double weightedScore = 0.0D;
		weightedScore += distanceScore * 0.15D;
		weightedScore += viewConeScore * 0.25D;
		weightedScore += screenRelevance * 0.10D;
		weightedScore += velocityScore * 0.10D;
		weightedScore += predictedVisibility * 0.10D;
		weightedScore += interactionScore * 0.20D;
		weightedScore += densityScore * 0.10D;

		// Clamp to [0, 1]
		double score = Math.max(0.0D, Math.min(1.0D, weightedScore));

		// ---- 8. Assign priority band ----
		PriorityBand band = assignPriorityBand(score, distanceSqr, inFront, lookedAt,
				isDirtyFromPlayer, dot, predictedVisibility);

		// ---- 9. Update metrics ----
		accumulatedSectionSignificance += score;
		maxSectionSignificance = Math.max(maxSectionSignificance, score);
		trackBandMetric(band);

		return new SectionResult(score, band);
	}

	/**
	 * Evaluate a chunk section using default entity/block entity counts (0).
	 * Used when counts aren't readily available.
	 */
	public static SectionResult evaluate(
			BlockPos sectionOrigin,
			Camera camera,
			boolean isDirtyFromPlayer) {
		return evaluate(sectionOrigin, SectionPos.of(sectionOrigin), camera,
				isDirtyFromPlayer, 0, 0);
	}

	/**
	 * Determines if a section upload should be admitted this frame based on
	 * the priority band and available budget.
	 *
	 * @param band        The section's priority band
	 * @param budgetUsed  Uploads already admitted this frame
	 * @param maxBudget   Maximum uploads allowed this frame
	 * @return true if the upload should be admitted now
	 */
	public static boolean shouldAdmitUpload(PriorityBand band, int budgetUsed, int maxBudget) {
		chunkUploadsReady++;

		boolean admitted = switch (band) {
			case CRITICAL -> true; // Always admit critical immediately
			case HIGH -> budgetUsed < maxBudget; // Admit if budget remains
			case NORMAL -> budgetUsed < Math.max(1, maxBudget - 1); // Reserve 1 slot for urgent
			case BACKGROUND -> {
				// Only admit background if we have surplus budget and no urgent work
				if (budgetUsed < maxBudget / 2) {
					yield true;
				}
				chunkUploadsDeferredBySignificance++;
				yield false;
			}
			case DEFERRED -> {
				// Only admit deferred when budget is very comfortable
				if (budgetUsed < maxBudget / 3) {
					yield true;
				}
				chunkUploadsDeferredBySignificance++;
				yield false;
			}
		};

		if (admitted) {
			chunkUploadsAdmittedThisFrame++;
		} else {
			trackDeferredMetric();
		}

		return admitted;
	}

	/**
	 * Check for promotion opportunities based on camera movement or player interaction.
	 * Returns the promoted band if the section should be bumped up.
	 */
	public static PromotionResult checkPromotion(
			BlockPos sectionOrigin,
			Camera camera,
			boolean isDirtyFromPlayer,
			PriorityBand currentBand) {
		boolean promoted = false;
		PriorityBand newBand = currentBand;
		String reason = null;

		Vec3 cameraPos = camera.getPosition();
		double centerX = sectionOrigin.getX() + 8.0D;
		double centerY = sectionOrigin.getY() + 8.0D;
		double centerZ = sectionOrigin.getZ() + 8.0D;
		double dx = centerX - cameraPos.x;
		double dy = centerY - cameraPos.y;
		double dz = centerZ - cameraPos.z;
		double distanceSqr = dx * dx + dy * dy + dz * dz;

		// Near-camera promotion: any section within CRITICAL distance gets promoted
		if (distanceSqr <= CRITICAL_DISTANCE_SQR && currentBand.isLessUrgentThan(PriorityBand.CRITICAL)) {
			newBand = PriorityBand.CRITICAL;
			promoted = true;
			chunkUploadsPromotedByNearCamera++;
			reason = "near_camera";
		}

		// Look-direction promotion
		if (!promoted && currentBand.isLessUrgentThan(PriorityBand.HIGH)) {
			double distance = Math.sqrt(Math.max(distanceSqr, 0.0001D));
			Vector3f lookVec = camera.getLookVector();
			double dot = (dx * lookVec.x + dy * lookVec.y + dz * lookVec.z) / distance;
			if (dot > LOOKED_AT_DOT && distanceSqr <= HIGH_DISTANCE_SQR) {
				newBand = PriorityBand.HIGH;
				promoted = true;
				chunkUploadsPromotedByLookDirection++;
				reason = "look_direction";
			}
		}

		// Player velocity promotion
		if (!promoted && currentBand.isLessUrgentThan(PriorityBand.NORMAL)
				&& cameraVelocityAbs > 0.1D) {
			double distance = Math.sqrt(Math.max(distanceSqr, 0.0001D));
			double moveDot = (dx * cameraVelocityX + dz * cameraVelocityZ)
					/ Math.max(0.001D, distance * cameraVelocityAbs);
			if (moveDot > 0.5D && distanceSqr <= NORMAL_DISTANCE_SQR) {
				newBand = currentBand.isLessUrgentThan(PriorityBand.NORMAL)
						? PriorityBand.NORMAL
						: PriorityBand.HIGH;
				promoted = true;
				chunkUploadsPromotedByPlayerVelocity++;
				reason = "player_velocity";
			}
		}

		// Recent-interaction promotion
		if (!promoted && isDirtyFromPlayer && currentBand.isLessUrgentThan(PriorityBand.CRITICAL)) {
			newBand = PriorityBand.CRITICAL;
			promoted = true;
			chunkUploadsPromotedByRecentInteraction++;
			reason = "recent_interaction";
		}

		return new PromotionResult(promoted, newBand, reason);
	}

	/**
	 * Reports the number of deferred uploads this frame for burst prevention tracking.
	 */
	public static void reportDeferredCount(int deferredCount) {
		if (deferredCount > 0) {
			accumulatedDeferredChunkUploads += deferredCount;
			deferredCountSamples++;
			maxDeferredChunkUploads = Math.max(maxDeferredChunkUploads, deferredCount);
		}
	}

	/**
	 * Records budget usage for metrics.
	 */
	public static void recordUploadBudgetMs(long nanos) {
		chunkUploadBudgetUsedMs += nanos;
	}

	public static void recordUploadBudgetSet(int budgetMs) {
		chunkUploadBudgetMs = budgetMs;
	}

	/**
	 * Safety check: records frames where near-camera uploads were delayed.
	 */
	public static void recordNearCameraDelayed() {
		nearCameraUploadDelayedFrames++;
	}

	/**
	 * Safety check: records frames where player interaction uploads were delayed.
	 */
	public static void recordPlayerInteractionDelayed() {
		playerInteractionUploadDelayedFrames++;
	}

	/**
	 * Safety check: records frames at risk of visible chunk pop-in.
	 */
	public static void recordVisibleChunkPopInRisk() {
		visibleChunkPopInRiskFrames++;
	}

	// ============================================================
	//  Metrics Accessors
	// ============================================================

	public static long sectionSignificanceUpdates() { return sectionSignificanceUpdates; }
	public static double avgSectionSignificance() { return sectionSignificanceUpdates > 0
			? accumulatedSectionSignificance / sectionSignificanceUpdates : 0.0D; }
	public static double maxSectionSignificance() { return maxSectionSignificance; }
	public static long sectionPriorityCritical() { return sectionPriorityCritical; }
	public static long sectionPriorityHigh() { return sectionPriorityHigh; }
	public static long sectionPriorityNormal() { return sectionPriorityNormal; }
	public static long sectionPriorityBackground() { return sectionPriorityBackground; }
	public static long sectionPriorityDeferred() { return sectionPriorityDeferred; }
	public static long chunkUploadsReady() { return chunkUploadsReady; }
	public static long chunkUploadsAdmittedThisFrame() { return chunkUploadsAdmittedThisFrame; }
	public static long chunkUploadsDeferredBySignificance() { return chunkUploadsDeferredBySignificance; }
	public static long chunkUploadsPromotedByNearCamera() { return chunkUploadsPromotedByNearCamera; }
	public static long chunkUploadsPromotedByLookDirection() { return chunkUploadsPromotedByLookDirection; }
	public static long chunkUploadsPromotedByPlayerVelocity() { return chunkUploadsPromotedByPlayerVelocity; }
	public static long chunkUploadsPromotedByRecentInteraction() { return chunkUploadsPromotedByRecentInteraction; }
	public static long uploadBurstPreventedFrames() { return uploadBurstPreventedFrames; }
	
	/**
	 * Increment the burst prevention counter (called by admission control).
	 */
	public static void incrementUploadBurstPreventedFrames() {
		uploadBurstPreventedFrames++;
	}
	public static long maxDeferredChunkUploads() { return maxDeferredChunkUploads; }
	public static double avgDeferredChunkUploads() { return deferredCountSamples > 0
			? (double) accumulatedDeferredChunkUploads / deferredCountSamples : 0.0D; }
	public static long chunkUploadBudgetMs() { return chunkUploadBudgetMs; }
	public static long chunkUploadBudgetUsedMs() { return chunkUploadBudgetUsedMs; }
	public static long nearCameraUploadDelayedFrames() { return nearCameraUploadDelayedFrames; }
	public static long playerInteractionUploadDelayedFrames() { return playerInteractionUploadDelayedFrames; }
	public static long visibleChunkPopInRiskFrames() { return visibleChunkPopInRiskFrames; }

	public static boolean isEnabled() {
		return OptiminiumSettings.isEnabled()
				&& OptiminiumSettings.isExperimentalRendererFeatures();
	}

	public static void resetMetrics() {
		sectionSignificanceUpdates = 0L;
		accumulatedSectionSignificance = 0.0D;
		maxSectionSignificance = 0.0D;
		sectionPriorityCritical = 0L;
		sectionPriorityHigh = 0L;
		sectionPriorityNormal = 0L;
		sectionPriorityBackground = 0L;
		sectionPriorityDeferred = 0L;
		chunkUploadsReady = 0L;
		chunkUploadsAdmittedThisFrame = 0L;
		chunkUploadsDeferredBySignificance = 0L;
		chunkUploadsPromotedByNearCamera = 0L;
		chunkUploadsPromotedByLookDirection = 0L;
		chunkUploadsPromotedByPlayerVelocity = 0L;
		chunkUploadsPromotedByRecentInteraction = 0L;
		uploadBurstPreventedFrames = 0L;
		maxDeferredChunkUploads = 0L;
		accumulatedDeferredChunkUploads = 0L;
		deferredCountSamples = 0L;
		chunkUploadBudgetMs = 0L;
		chunkUploadBudgetUsedMs = 0L;
		nearCameraUploadDelayedFrames = 0L;
		playerInteractionUploadDelayedFrames = 0L;
		visibleChunkPopInRiskFrames = 0L;
	}

	// ============================================================
	//  Internal
	// ============================================================

	private static PriorityBand assignPriorityBand(double score, double distanceSqr,
			boolean inFront, boolean lookedAt, boolean isDirtyFromPlayer,
			double dot, double predictedVisibility) {
		// Hard rules override score-based assignment
		if (isDirtyFromPlayer) {
			return PriorityBand.CRITICAL;
		}
		if (lookedAt && distanceSqr <= HIGH_DISTANCE_SQR) {
			return PriorityBand.CRITICAL;
		}
		if (distanceSqr <= CRITICAL_DISTANCE_SQR) {
			return PriorityBand.CRITICAL;
		}

		// Score-based assignment
		if (score > 0.70D) return PriorityBand.CRITICAL;
		if (score > 0.50D) return PriorityBand.HIGH;
		if (score > 0.30D) return PriorityBand.NORMAL;
		if (score > 0.15D) return PriorityBand.BACKGROUND;

		// Distance-based safety checks for moderate scores
		if (inFront && distanceSqr <= HIGH_DISTANCE_SQR) {
			return PriorityBand.NORMAL; // In front but very far or low score
		}
		if (inFront && distanceSqr <= NORMAL_DISTANCE_SQR) {
			return PriorityBand.BACKGROUND;
		}
		if (predictedVisibility > 0.3D) {
			return PriorityBand.BACKGROUND; // Will likely become visible
		}

		return PriorityBand.DEFERRED;
	}

	private static void trackBandMetric(PriorityBand band) {
		switch (band) {
			case CRITICAL -> sectionPriorityCritical++;
			case HIGH -> sectionPriorityHigh++;
			case NORMAL -> sectionPriorityNormal++;
			case BACKGROUND -> sectionPriorityBackground++;
			case DEFERRED -> sectionPriorityDeferred++;
		}
	}

	private static void trackDeferredMetric() {
		// Tracked via reportDeferredCount
	}

	private static void updateCameraMotion() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.gameRenderer == null || minecraft.gameRenderer.getMainCamera() == null) {
			cameraStable = false;
			cameraFastMoving = false;
			return;
		}
		Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();

		// Use static fields to avoid recalculating
		// In a real integration, we'd read from OptiminiumVisualSignificance's cached values
		// For now, compute directly (lightweight)
		cameraVelocityX = camera.x - lastCameraX;
		cameraVelocityZ = camera.z - lastCameraZ;
		cameraVelocityAbs = Math.sqrt(cameraVelocityX * cameraVelocityX + cameraVelocityZ * cameraVelocityZ);
		cameraStable = cameraVelocityAbs < 0.01D;
		cameraFastMoving = cameraVelocityAbs > 0.5D;

		lastCameraX = camera.x;
		lastCameraZ = camera.z;
	}

	// Static camera tracking fields
	private static double lastCameraX = Double.NaN;
	private static double lastCameraZ = Double.NaN;

	// ============================================================
	//  Result records
	// ============================================================

	public record SectionResult(double score, PriorityBand band) {
	}

	public record PromotionResult(boolean promoted, PriorityBand newBand, String reason) {
		public static PromotionResult none() {
			return new PromotionResult(false, null, null);
		}
	}
}