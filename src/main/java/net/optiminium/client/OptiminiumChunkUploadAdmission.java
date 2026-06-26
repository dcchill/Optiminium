package net.optiminium.client;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.optiminium.client.OptiminiumSectionSignificance.PriorityBand;
import net.optiminium.client.OptiminiumSectionSignificance.SectionResult;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Chunk Upload Admission Control.
 *
 * Uses Section Significance scores to decide which chunk-section uploads
 * should be admitted now vs deferred vs promoted.
 *
 * Does NOT replace Sodium chunk meshing or terrain rendering.
 * Only controls the order in which ready upload tasks are handed to the GPU.
 *
 * Key behaviors:
 * - CRITICAL uploads are admitted immediately
 * - HIGH uploads are admitted next (within budget)
 * - NORMAL uploads are admitted within budget
 * - BACKGROUND uploads are deferred when under pressure
 * - DEFERRED uploads are delayed until budget is comfortable
 * - Catch-up bursts are prevented by spreading deferred uploads across frames
 * - A small upload budget is always reserved for urgent near-camera changes
 * - Never noticeably delay player-placed/broken blocks
 *
 * Works with both vanilla and Sodium render paths via the same public API.
 */
public final class OptiminiumChunkUploadAdmission {
	// ---- Upload entry with significance metadata ----
	static final class UploadEntry {
		final Runnable task;
		final BlockPos sectionOrigin;
		PriorityBand band;
		SectionResult lastResult;
		boolean isDirtyFromPlayer;
		int deferCount;
		long enqueueFrame;
		long lastEvaluatedFrame;

		UploadEntry(Runnable task, BlockPos sectionOrigin, boolean isDirtyFromPlayer) {
			this.task = task;
			this.sectionOrigin = sectionOrigin;
			this.isDirtyFromPlayer = isDirtyFromPlayer;
			this.band = PriorityBand.DEFERRED; // Initial conservative assignment
			this.deferCount = 0;
			this.enqueueFrame = frameCounter;
			this.lastEvaluatedFrame = -1;
		}
	}

	// ---- Queues by priority band ----
	private static final Deque<UploadEntry> criticalQueue = new ArrayDeque<>();
	private static final Deque<UploadEntry> highQueue = new ArrayDeque<>();
	private static final Deque<UploadEntry> normalQueue = new ArrayDeque<>();
	private static final Deque<UploadEntry> backgroundQueue = new ArrayDeque<>();
	private static final Deque<UploadEntry> deferredQueue = new ArrayDeque<>();

	// ---- Deduplication ----
	// Maps section origin -> frame when it was enqueued.
	// Allows eviction of stale entries that were queued but never actually
	// uploaded (e.g., because the admission system deferred or dropped them).
	private static final Map<BlockPos, Long> enqueuedSections = new HashMap<>();

	// ---- Frame tracking ----
	private static long frameCounter;
	private static long lastFrameDeferredCount;

	// ---- Dedup eviction ----
	private static final int DEDUP_EVICT_INTERVAL = 60; // evict stale entries every 60 frames
	private static final int DEDUP_MAX_STALE_FRAMES = 120; // entries older than this are evicted
	private static long lastDedupEvictFrame;

	// ---- Burst prevention ----
	private static int consecutiveHighDeferralFrames;
	private static int admittedThisFrame;
	private static int deferredThisFrame;
	private static boolean burstPreventionActive;

	// ---- Budget tracking ----
	private static int baseUploadBudget;

	// ---- Priority 5: Frame-time aware upload pacing ----
	// Monitors the actual time spent uploading per frame and dynamically
	// reduces the budget if uploads are consuming too much frame time.
	private static long uploadElapsedThisFrame; // nanos spent on uploads this frame
	private static int uploadCountThisFrame;    // upload tasks executed this frame
	private static long maxUploadNanosPerFrame; // max nanos to spend on uploads per frame
	private static int consecutiveBudgetCutFrames;
	private static int uploadedThisRun;         // total uploads processed in this processUploads call

	// ---- Constants ----
	private static final int MAX_PENDING_UPLOADS = 512;
	private static final int MAX_DEFER_COUNT = 8;
	private static final int BURST_PREVENTION_THRESHOLD = 20; // deferred count triggers spread
	private static final int BURST_SPREAD_FRAMES = 4; // spread across this many frames
	private static final int MIN_URGENT_RESERVE = 1; // always reserve at least 1 slot for urgent
	private static final int PROMOTION_INTERVAL_FRAMES = 10; // re-evaluate every N frames

	// Priority 5: frame-time aware pacing constants
	private static final long MAX_UPLOAD_NANOS_DEFAULT = 2_000_000L; // 2ms default max
	private static final long MAX_UPLOAD_NANOS_STRICT = 500_000L;   // 0.5ms when under pressure
	private static final long BUDGET_CUT_THRESHOLD_NANOS = 1_000_000L; // 1ms triggers cut
	private static final int CONSECUTIVE_CUT_LIMIT = 3; // max consecutive cuts
	private static final int BUDGET_RECOVERY_FRAMES = 10; // frames before budget recovery

	private OptiminiumChunkUploadAdmission() {
	}

	// ============================================================
	//  Public API
	// ============================================================

	/**
	 * Called at the start of each frame.
	 */
	public static void onFrameStart() {
		frameCounter++;
		admittedThisFrame = 0;
		deferredThisFrame = 0;
		uploadElapsedThisFrame = 0L;
		uploadCountThisFrame = 0;

		// Priority 5: Recover upload budget if we've had enough quiet frames
		if (consecutiveBudgetCutFrames > 0 && uploadElapsedThisFrame == 0) {
			consecutiveBudgetCutFrames--;
		}

		// Adjust max upload nanos based on current pressure
		maxUploadNanosPerFrame = OptiminiumGpuOptimizer.hasVisualSignificancePressure()
			? MAX_UPLOAD_NANOS_STRICT : MAX_UPLOAD_NANOS_DEFAULT;

		// Update Section Significance frame state
		OptiminiumSectionSignificance.onFrameStart();

		// Re-evaluate queued uploads for promotion opportunities
		Camera camera = getCamera();
		if (camera != null) {
			promoteQueuedUploads(camera);
		}

		// Detect and prevent catch-up bursts
		if (lastFrameDeferredCount > BURST_PREVENTION_THRESHOLD) {
			burstPreventionActive = true;
			consecutiveHighDeferralFrames++;
			OptiminiumSectionSignificance.incrementUploadBurstPreventedFrames();
		} else {
			if (burstPreventionActive && lastFrameDeferredCount == 0) {
				burstPreventionActive = false;
				consecutiveHighDeferralFrames = 0;
			}
		}
	}

	/**
	 * Enqueue a chunk-section upload with significance awareness.
	 *
	 * @param upload           The upload runnable
	 * @param sectionOrigin    The BlockPos origin of the section being uploaded
	 * @param isDirtyFromPlayer Whether this section was dirtied by player interaction
	 */
	public static void enqueue(Runnable upload, BlockPos sectionOrigin, boolean isDirtyFromPlayer) {
		if (sectionOrigin != null) {
			// Evict stale dedup entries periodically to prevent leaks
			if (frameCounter - lastDedupEvictFrame >= DEDUP_EVICT_INTERVAL) {
				lastDedupEvictFrame = frameCounter;
				evictStaleDedupEntries();
			}
			if (enqueuedSections.containsKey(sectionOrigin)) {
				// Deduplicate: same section already queued (or pending admission)
				OptiminiumGpuOptimizer.recordDuplicateUploadRequest("alreadyPending");
				return;
			}
		}

		if (totalPending() >= MAX_PENDING_UPLOADS) {
			return; // Bound the queue
		}

		UploadEntry entry = new UploadEntry(upload, sectionOrigin, isDirtyFromPlayer);

		// Evaluate significance immediately to assign initial band
		Camera camera = getCamera();
		if (camera != null && sectionOrigin != null) {
			SectionResult result = OptiminiumSectionSignificance.evaluate(
					sectionOrigin, camera, isDirtyFromPlayer);
			entry.band = result.band();
			entry.lastResult = result;
			entry.lastEvaluatedFrame = frameCounter;
		} else {
			// No camera or section info: treat as HIGH (safe default)
			entry.band = PriorityBand.HIGH;
		}

		// Enqueue into the appropriate priority queue
		enqueueByBand(entry);

		if (sectionOrigin != null) {
			enqueuedSections.put(sectionOrigin, frameCounter);
		}
	}

	/**
	 * Process admitted uploads this frame, given a base budget.
	 * Returns the number of uploads actually processed.
	 */
	public static int processUploads(int baseBudget, long budgetNanos) {
		if (baseBudget <= 0) {
			return 0;
		}

		baseUploadBudget = baseBudget;
		OptiminiumSectionSignificance.recordUploadBudgetSet(baseBudget);

		// Calculate effective budget with burst prevention
		int effectiveBudget = baseBudget;
		if (burstPreventionActive) {
			// During burst prevention, limit to a fraction of budget
			effectiveBudget = Math.max(MIN_URGENT_RESERVE, baseBudget / 2);
		}

		// Always reserve at least 1 slot for urgent uploads
		int urgentReserve = MIN_URGENT_RESERVE;

		int processed = 0;

		// Phase 1: Process CRITICAL uploads (unbounded, always admitted)
		processed += processQueue(criticalQueue, Integer.MAX_VALUE, processed);

		// Phase 2: Process HIGH uploads (within remaining budget, with frame-time cap)
		uploadedThisRun = processed;
		int remaining = effectiveBudget - processed;
		if (remaining > 0) {
			int highBudget = optiminium$frameAwareBudget(remaining);
			processed += processQueue(highQueue, highBudget, processed);
		}

		// Phase 3: Process NORMAL uploads (within remaining budget after reserve)
		remaining = Math.max(0, effectiveBudget - processed - urgentReserve);
		if (remaining > 0 && !burstPreventionActive) {
			int normBudget = optiminium$frameAwareBudget(remaining);
			processed += processQueue(normalQueue, normBudget, processed);
		}

		// Phase 4: Admit BACKGROUND uploads only when very comfortable
		if (!burstPreventionActive && processed < effectiveBudget / 2) {
			int bgBudget = Math.min(2, optiminium$frameAwareBudget(effectiveBudget / 2 - processed));
			processed += processQueue(backgroundQueue, bgBudget, processed);
		}

		// Phase 5: Deferred - only admit when truly idle
		if (processed == 0 && !burstPreventionActive && totalPending() > 0) {
			// Spread deferred uploads across frames
			int spreadAdmit = Math.min(lastFrameDeferredCount > 0 ? 1 : 0, optiminium$frameAwareBudget(effectiveBudget));
			processed += processQueue(deferredQueue, spreadAdmit, processed);
		}

		// Priority 5: If we spent too much time uploading this frame, cut budget for next frame
		if (uploadElapsedThisFrame > BUDGET_CUT_THRESHOLD_NANOS) {
			consecutiveBudgetCutFrames = Math.min(consecutiveBudgetCutFrames + 1, CONSECUTIVE_CUT_LIMIT);
		}

		// Record metrics
		OptiminiumSectionSignificance.reportDeferredCount(deferredThisFrame);
		lastFrameDeferredCount = deferredThisFrame;

		return processed;
	}

	/**
	 * Returns the total number of pending uploads across all queues.
	 */
	public static int pendingUploads() {
		return totalPending();
	}

	/**
	 * Clears all queues (e.g., on world unload).
	 */
	public static void clear() {
		criticalQueue.clear();
		highQueue.clear();
		normalQueue.clear();
		backgroundQueue.clear();
		deferredQueue.clear();
		enqueuedSections.clear();
		lastDedupEvictFrame = 0L;
		admittedThisFrame = 0;
		deferredThisFrame = 0;
		burstPreventionActive = false;
		consecutiveHighDeferralFrames = 0;
		lastFrameDeferredCount = 0;
	}

	/**
	 * Returns the current frame's admitted count.
	 */
	public static int getAdmittedThisFrame() {
		return admittedThisFrame;
	}

	/**
	 * Returns the current frame's deferred count.
	 */
	public static int getDeferredThisFrame() {
		return deferredThisFrame;
	}

	/**
	 * Returns queue sizes for diagnostics.
	 */
	public static String queueSizes() {
		return "crit=" + criticalQueue.size()
				+ ",high=" + highQueue.size()
				+ ",norm=" + normalQueue.size()
				+ ",bg=" + backgroundQueue.size()
				+ ",def=" + deferredQueue.size();
	}

	// ============================================================
	//  Internal
	// ============================================================

	private static int processQueue(Deque<UploadEntry> queue, int budget, int alreadyProcessed) {
		int processed = 0;
		while (!queue.isEmpty() && processed < budget) {
			UploadEntry entry = queue.peekFirst();
			if (entry == null) break;

			// Check if this entry should be admitted based on significance
			Camera camera = getCamera();
			boolean admit;

			if (camera != null && entry.sectionOrigin != null) {
				// Re-evaluate if enough frames have passed
				if (frameCounter - entry.lastEvaluatedFrame >= PROMOTION_INTERVAL_FRAMES) {
					SectionResult result = OptiminiumSectionSignificance.evaluate(
							entry.sectionOrigin, camera, entry.isDirtyFromPlayer);
					entry.band = result.band();
					entry.lastResult = result;
					entry.lastEvaluatedFrame = frameCounter;

					// If band changed, re-queue to correct queue
					if (!isInCorrectQueue(entry, queue)) {
						queue.removeFirst();
						enqueueByBand(entry);
						continue;
					}
				}

				admit = OptiminiumSectionSignificance.shouldAdmitUpload(
						entry.band, alreadyProcessed + processed, baseUploadBudget);
			} else {
				// No camera: admit conservatively
				admit = entry.deferCount < 2;
			}

			if (admit) {
				queue.removeFirst();
				if (entry.sectionOrigin != null) {
					enqueuedSections.remove(entry.sectionOrigin);
				}
				long profileStart = OptiminiumGpuOptimizer.profileStart();

				// Track budget usage and frame-time pacing
				long startNanos = System.nanoTime();
				entry.task.run();
				long elapsedNanos = System.nanoTime() - startNanos;
				OptiminiumSectionSignificance.recordUploadBudgetMs(elapsedNanos);
				recordUploadTime(elapsedNanos);

				OptiminiumGpuOptimizer.recordUploadManagementProfileNanos(profileStart);
				admittedThisFrame++;
				processed++;
			} else {
				// Defer: move to back of queue
				entry.deferCount++;
				queue.removeFirst();

				// If deferred too many times, promote to prevent starvation
				if (entry.deferCount >= MAX_DEFER_COUNT) {
					entry.band = promoteBand(entry.band);
					entry.deferCount = 0;
				}

				enqueueByBand(entry);
				deferredThisFrame++;
				break; // Stop processing this queue to avoid livelock
			}
		}
		return processed;
	}

	/**
	 * Promote a band one level (DEFERRED -> BACKGROUND -> NORMAL -> HIGH -> CRITICAL).
	 */
	private static PriorityBand promoteBand(PriorityBand band) {
		return switch (band) {
			case DEFERRED -> PriorityBand.BACKGROUND;
			case BACKGROUND -> PriorityBand.NORMAL;
			case NORMAL -> PriorityBand.HIGH;
			case HIGH -> PriorityBand.CRITICAL;
			case CRITICAL -> PriorityBand.CRITICAL;
		};
	}

	private static void enqueueByBand(UploadEntry entry) {
		switch (entry.band) {
			case CRITICAL -> criticalQueue.addLast(entry);
			case HIGH -> highQueue.addLast(entry);
			case NORMAL -> normalQueue.addLast(entry);
			case BACKGROUND -> backgroundQueue.addLast(entry);
			case DEFERRED -> deferredQueue.addLast(entry);
		}
	}

	private static boolean isInCorrectQueue(UploadEntry entry, Deque<UploadEntry> currentQueue) {
		return switch (entry.band) {
			case CRITICAL -> currentQueue == criticalQueue;
			case HIGH -> currentQueue == highQueue;
			case NORMAL -> currentQueue == normalQueue;
			case BACKGROUND -> currentQueue == backgroundQueue;
			case DEFERRED -> currentQueue == deferredQueue;
		};
	}

	/**
	 * Re-evaluate queued uploads for promotion based on camera movement or interaction.
	 */
	private static void promoteQueuedUploads(Camera camera) {
		promoteQueue(deferredQueue, camera);
		promoteQueue(backgroundQueue, camera);
		promoteQueue(normalQueue, camera);
		promoteQueue(highQueue, camera);
	}

	private static void promoteQueue(Deque<UploadEntry> queue, Camera camera) {
		if (queue.isEmpty()) return;

		int checkCount = Math.min(queue.size(), 8); // Limit checks per frame
		int checked = 0;

		var iterator = queue.iterator();
		while (iterator.hasNext() && checked < checkCount) {
			UploadEntry entry = iterator.next();
			checked++;

			if (entry.sectionOrigin == null) continue;
			if (frameCounter - entry.lastEvaluatedFrame < PROMOTION_INTERVAL_FRAMES) continue;

			var promotion = OptiminiumSectionSignificance.checkPromotion(
					entry.sectionOrigin, camera, entry.isDirtyFromPlayer, entry.band);

			if (promotion.promoted()) {
				iterator.remove();
				entry.band = promotion.newBand();
				entry.lastEvaluatedFrame = frameCounter;
				enqueueByBand(entry);
			}
		}
	}

	private static Camera getCamera() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gameRenderer == null || mc.gameRenderer.getMainCamera() == null) {
			return null;
		}
		return mc.gameRenderer.getMainCamera();
	}

	/**
	 * Returns a frame-time-aware budget cap that reduces when uploads are
	 * consuming too much frame time. This prevents individual frames from
	 * being hijacked by excessive upload work.
	 */
	private static int optiminium$frameAwareBudget(int requested) {
		if (requested <= 0) return 0;
		// If we've spent too much total time on uploads this frame, cap remaining
		if (uploadElapsedThisFrame >= maxUploadNanosPerFrame) {
			return 0;
		}
		// Apply consecutive-budget-cut scaling
		if (consecutiveBudgetCutFrames > 0) {
			double cutFactor = Math.max(0.25D, 1.0D - consecutiveBudgetCutFrames * 0.25D);
			return Math.max(1, (int)(requested * cutFactor));
		}
		return requested;
	}

	// ---- Time tracking (called by processQueue) ----
	static void recordUploadTime(long nanos) {
		uploadElapsedThisFrame += nanos;
		uploadCountThisFrame++;
	}

	private static int totalPending() {
		return criticalQueue.size() + highQueue.size() + normalQueue.size()
				+ backgroundQueue.size() + deferredQueue.size();
	}

	/**
	 * Evict stale entries from the deduplication set.
	 * Prevents entries submitted but never admitted from blocking future uploads.
	 */

	// ============================================================
	//  Diagnostics
	// ============================================================

	public static long getUploadElapsedThisFrame() {
		return uploadElapsedThisFrame;
	}

	public static int getUploadCountThisFrame() {
		return uploadCountThisFrame;
	}

	private static void evictStaleDedupEntries() {
		if (enqueuedSections.isEmpty()) return;
		long cutoff = frameCounter - DEDUP_MAX_STALE_FRAMES;
		enqueuedSections.values().removeIf(frame -> frame < cutoff);
	}
}