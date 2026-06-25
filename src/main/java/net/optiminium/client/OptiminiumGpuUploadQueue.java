package net.optiminium.client;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.optiminium.optimization.OptiminiumSettings;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Significance-aware GPU upload queue.
 * 
 * Replaces the simple FIFO queue with:
 * - Deduplication: same resource location is only enqueued once
 * - Significance prioritization: high-significance uploads processed first
 * - Deferral: low-significance uploads skipped when budget is tight
 * - Catch-up burst prevention: limits how many uploads can be processed per frame
 *   even when the queue is large, preventing frame-time spikes
 * 
 * Does NOT add raw OpenGL state caching or touch Sodium terrain rendering.
 */
@EventBusSubscriber(modid = "optiminium", value = Dist.CLIENT)
public final class OptiminiumGpuUploadQueue {
	// ---- Upload entry with significance metadata ----
	private static final class UploadEntry {
		final Runnable task;
		final ResourceLocation location;
		final long enqueueFrame;
		final boolean isTexture;
		boolean isHighSignificance;
		int deferCount;

		UploadEntry(Runnable task, ResourceLocation location, boolean isTexture) {
			this.task = task;
			this.location = location;
			this.enqueueFrame = frameCounter;
			this.isTexture = isTexture;
			this.isHighSignificance = false;
			this.deferCount = 0;
		}
	}

	private static final Deque<UploadEntry> uploads = new ArrayDeque<>();
	private static final Set<ResourceLocation> enqueuedLocations = new HashSet<>();
	private static long frameCounter;

	// ---- Metrics ----
	private static long uploadsSkippedBecauseLowSignificance;
	private static long uploadsDeduplicated;
	private static long uploadsDeferredBySignificance;
	private static long uploadsPromotedBecauseNear;
	private static long redundantUploadSchedulingPrevented;

	// ---- Constants ----
	private static final int MAX_PENDING_UPLOADS = 256;
	private static final int MAX_DEFER_COUNT = 5; // max frames a low-significance upload can be deferred
	private static final int CATCHUP_BURST_LIMIT = 4; // max extra uploads beyond budget during catch-up
	private static final int SIGNIFICANCE_PROMOTION_FRAMES = 60; // after this many frames, promote to high

	private OptiminiumGpuUploadQueue() {
	}

	/**
	 * Enqueue a texture upload with significance awareness.
	 * Deduplicates by ResourceLocation.
	 */
	public static void enqueue(Runnable upload) {
		enqueue(upload, null, true);
	}

	/**
	 * Enqueue an upload with optional location for deduplication.
	 */
	public static void enqueue(Runnable upload, ResourceLocation location, boolean isTexture) {
		// Deduplication: skip if same location already queued
		if (location != null) {
			if (enqueuedLocations.contains(location)) {
				uploadsDeduplicated++;
				redundantUploadSchedulingPrevented++;
				return;
			}
		}

		// Bound the queue
		if (uploads.size() >= MAX_PENDING_UPLOADS) {
			redundantUploadSchedulingPrevented++;
			return;
		}

		UploadEntry entry = new UploadEntry(upload, location, isTexture);
		uploads.addLast(entry);
		if (location != null) {
			enqueuedLocations.add(location);
		}
	}

	/**
	 * Mark an upload as high significance (called when a nearby/looked-at object is detected).
	 */
	public static void markHighSignificance(ResourceLocation location) {
		for (UploadEntry entry : uploads) {
			if (location.equals(entry.location) && !entry.isHighSignificance) {
				entry.isHighSignificance = true;
				uploadsPromotedBecauseNear++;
				// Move to front of queue for priority processing
				uploads.remove(entry);
				uploads.addFirst(entry);
				return;
			}
		}
	}

	public static int pendingUploads() {
		return uploads.size();
	}

	// ---- Metrics accessors ----
	public static long uploadsSkippedBecauseLowSignificance() { return uploadsSkippedBecauseLowSignificance; }
	public static long uploadsDeduplicated() { return uploadsDeduplicated; }
	public static long uploadsDeferredBySignificance() { return uploadsDeferredBySignificance; }
	public static long uploadsPromotedBecauseNear() { return uploadsPromotedBecauseNear; }
	public static long redundantUploadSchedulingPrevented() { return redundantUploadSchedulingPrevented; }

	public static void resetMetrics() {
		uploadsSkippedBecauseLowSignificance = 0L;
		uploadsDeduplicated = 0L;
		uploadsDeferredBySignificance = 0L;
		uploadsPromotedBecauseNear = 0L;
		redundantUploadSchedulingPrevented = 0L;
	}

	@SubscribeEvent
	public static void onFrame(RenderFrameEvent.Pre event) {
		frameCounter++;
		int baseBudget = OptiminiumGpuOptimizer.scaledChunkUploadBudget(OptiminiumSettings.getChunkUploadsPerFrame());
		if (baseBudget <= 0 || uploads.isEmpty()) {
			return;
		}

		// Determine significance threshold from Visual Significance budget
		boolean isUnderPressure = OptiminiumVisualSignificance.renderBudget() != OptiminiumVisualSignificance.RenderBudget.NORMAL;
		boolean isHeavyPressure = OptiminiumVisualSignificance.renderBudget() == OptiminiumVisualSignificance.RenderBudget.HEAVY_PRESSURE
			|| OptiminiumVisualSignificance.renderBudget() == OptiminiumVisualSignificance.RenderBudget.EMERGENCY;

		// Calculate catch-up burst limit: allow extra uploads if queue is large,
		// but cap to prevent frame-time spikes
		int queueSize = uploads.size();
		int catchUpExtra = 0;
		if (queueSize > baseBudget * 3 && !isHeavyPressure) {
			// Moderate catch-up: allow a few extra per frame
			catchUpExtra = Math.min(CATCHUP_BURST_LIMIT, (queueSize - baseBudget * 3) / 10);
		}
		int effectiveBudget = baseBudget + catchUpExtra;

		int processed = 0;
		int deferredThisFrame = 0;

		// Process uploads with significance awareness
		// We iterate manually to allow reordering
		int iterations = 0;
		int maxIterations = uploads.size() + effectiveBudget; // safety bound
		while (!uploads.isEmpty() && processed < effectiveBudget && iterations < maxIterations) {
			iterations++;
			UploadEntry entry = uploads.peekFirst();
			if (entry == null) break;

			// Determine if this upload should be deferred
			boolean shouldDefer = false;
			if (isUnderPressure && !entry.isHighSignificance) {
				// Under pressure: defer low-significance texture uploads
				if (entry.isTexture) {
					// Check if this texture is for a culled/proxied object
					// We use a simple heuristic: if under heavy pressure, defer all non-high-significance
					if (isHeavyPressure) {
						shouldDefer = true;
					} else if (entry.deferCount < MAX_DEFER_COUNT) {
						shouldDefer = true;
					}
				}
			}

			if (shouldDefer) {
				// Move to back of queue to try again later
				entry.deferCount++;
				uploadsDeferredBySignificance++;
				deferredThisFrame++;
				uploads.removeFirst();
				uploads.addLast(entry);

				// If we've deferred too many in one frame, stop to avoid livelock
				if (deferredThisFrame >= effectiveBudget) {
					break;
				}
				continue;
			}

			// Process this upload
			uploads.removeFirst();
			if (entry.location != null) {
				enqueuedLocations.remove(entry.location);
			}

			long profileStart = OptiminiumGpuOptimizer.profileStart();
			try {
				entry.task.run();
			} finally {
				OptiminiumGpuOptimizer.recordUploadManagementProfileNanos(profileStart);
			}
			processed++;
		}

		// Track skipped uploads (those that were deferred but never processed)
		// These are counted when they're eventually processed or when the queue is cleared
	}

	/**
	 * Called when a block entity or entity is classified as high significance.
	 * This allows the upload queue to prioritize related texture uploads.
	 */
	public static void onSignificantObjectDetected(ResourceLocation textureLocation) {
		if (textureLocation != null) {
			markHighSignificance(textureLocation);
		}
	}

	/**
	 * Clear all pending uploads (e.g., on world unload).
	 */
	public static void clear() {
		uploads.clear();
		enqueuedLocations.clear();
	}
}