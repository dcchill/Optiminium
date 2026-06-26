package net.optiminium.mixin;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.optiminium.client.OptiminiumChunkUploadAdmission;
import net.optiminium.client.OptiminiumGpuOptimizer;
import net.optiminium.client.OptiminiumRenderProfiler;
import net.optiminium.client.OptiminiumVisualSignificance;
import net.optiminium.client.OptiminiumSectionSignificance;
import net.optiminium.compat.OptiminiumSodiumCompat;
import net.optiminium.optimization.OptiminiumSettings;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	@Shadow
	@Final
	private Minecraft minecraft;

	@Shadow
	private net.minecraft.client.multiplayer.ClientLevel level;

	@Shadow
	@Final
	private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;

	@Shadow
	@Final
	private Set<BlockEntity> globalBlockEntities;

	@Shadow
	private SectionRenderDispatcher sectionRenderDispatcher;

	@Inject(method = "compileSections", at = @At("HEAD"), cancellable = true)
	private void optiminium$compileSectionsPaced(Camera camera, CallbackInfo callback) {
		optiminium$recordRawVisibleBlockEntities(camera.getPosition());

		// When Sodium is present, do NOT cancel compileSections.
		// Sodium manages its own rebuild/upload pipeline and cancelling
		// the vanilla call would prevent Sodium from scheduling work,
		// causing GL stalls and rendering issues.
		if (OptiminiumSodiumCompat.isNonVanillaRenderer()) {
			// Mark that Sodium owns upload scheduling for this frame.
			// This prevents Optiminium's scaledChunkUploadBudget and
			// upload admission from running (they operate on the vanilla
			// upload queue which is empty under Sodium).
			OptiminiumGpuOptimizer.setSodiumOwnsUploadScheduling(true);
			return;
		}

		if (!OptiminiumSettings.isEnabled() || !OptiminiumSettings.isChunkRebuildScheduling() || this.level == null || this.sectionRenderDispatcher == null) {
			return;
		}

		callback.cancel();
		this.minecraft.getProfiler().push("optiminium_populate_sections_to_compile");
		LevelLightEngine lightEngine = this.level.getLightEngine();
		RenderRegionCache renderRegionCache = new RenderRegionCache();
		BlockPos cameraBlock = camera.getBlockPosition();
		int asyncBudget = OptiminiumGpuOptimizer.scaledChunkRebuildBudget(OptiminiumSettings.getChunkRebuildsPerFrame());
		SectionRenderDispatcher.RenderSection[] candidates = new SectionRenderDispatcher.RenderSection[asyncBudget];
		double[] priorities = new double[asyncBudget];
		int candidateCount = 0;
		int syncBudget = OptiminiumGpuOptimizer.scaledSyncChunkRebuildBudget(OptiminiumSettings.getSyncChunkRebuildsPerFrame());

		for (SectionRenderDispatcher.RenderSection section : this.visibleSections) {
			SectionPos sectionPos = SectionPos.of(section.getOrigin());
			if (!section.isDirty() || !lightEngine.lightOnInSection(sectionPos)) {
				continue;
			}
			if (syncBudget > 0 && optiminium$shouldBuildSynchronously(section, cameraBlock)) {
				this.minecraft.getProfiler().push("optiminium_build_near_sync");
				this.sectionRenderDispatcher.rebuildSectionSync(section, renderRegionCache);
				section.setNotDirty();
				this.minecraft.getProfiler().pop();
				syncBudget--;
			} else {
				candidateCount = optiminium$offerCandidate(candidates, priorities, candidateCount, section, optiminium$sectionPriority(section, camera));
			}
		}

		this.minecraft.getProfiler().popPush("upload");
		optiminium$uploadPendingChunks(camera);
		this.minecraft.getProfiler().popPush("optiminium_schedule_async_compile");

		for (int scheduled = 0; scheduled < candidateCount; scheduled++) {
			int bestIndex = optiminium$bestCandidateIndex(candidates, priorities, candidateCount);
			SectionRenderDispatcher.RenderSection section = candidates[bestIndex];
			priorities[bestIndex] = Double.POSITIVE_INFINITY;
			if (section != null && section.isDirty()) {
				section.rebuildSectionAsync(this.sectionRenderDispatcher, renderRegionCache);
				section.setNotDirty();
			}
		}

		this.minecraft.getProfiler().pop();
	}

	@Inject(method = "renderLevel", at = @At("HEAD"))
	private void optiminium$recordRawVisibleBlockEntitiesForFrame(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture,
			Matrix4f frustumMatrix, Matrix4f projectionMatrix, CallbackInfo callback) {
		// Under Sodium: do NOT call upload admission here.
		// Sodium manages its own chunk mesh upload pipeline. The Optiminium
		// upload admission queue is never populated under Sodium because
		// the vanilla SectionRenderDispatcher.toUpload is empty (Sodium uses
		// its own internal upload queue). Calling processUploads() with
		// zero pending entries just burns CPU for no benefit.
		if (OptiminiumSodiumCompat.isNonVanillaRenderer()) {
			// Skip upload admission and vanilla block entity counting for Sodium.
			// Sodium owns all terrain mesh upload scheduling.
			OptiminiumGpuOptimizer.setSodiumOwnsUploadScheduling(true);
			return;
		}
		// Non-Sodium path: proceed with vanilla block entity tracking
		// and upload admission scheduling.
		OptiminiumGpuOptimizer.setSodiumOwnsUploadScheduling(false);
		optiminium$recordRawVisibleBlockEntities(camera.getPosition());
	}

	@Unique
	private void optiminium$recordRawVisibleBlockEntities(Vec3 cameraPosition) {
		if (OptiminiumSodiumCompat.isNonVanillaRenderer()) {
			// With Sodium, visible sections are managed differently.
			// Skip vanilla block entity counting for Sodium renderers.
			// Sodium has its own visible section tracking.
			return;
		}
		int count = 0;
		boolean recordSignificance = OptiminiumVisualSignificance.isEnabled();
		for (SectionRenderDispatcher.RenderSection section : this.visibleSections) {
			var blockEntities = section.getCompiled().getRenderableBlockEntities();
			count += blockEntities.size();
			if (recordSignificance) {
				for (BlockEntity blockEntity : blockEntities) {
					OptiminiumVisualSignificance.recordBlockEntity(blockEntity, cameraPosition);
				}
			}
		}
		synchronized (this.globalBlockEntities) {
			count += this.globalBlockEntities.size();
			if (recordSignificance) {
				for (BlockEntity blockEntity : this.globalBlockEntities) {
					OptiminiumVisualSignificance.recordBlockEntity(blockEntity, cameraPosition);
				}
			}
		}
		OptiminiumGpuOptimizer.recordRawVisibleBlockEntitiesBeforeCulling(count);
	}

	/**
	 * Collects all non-dirty sections that have a pending upload task.
	 * Pairs each upload with its correct section origin for proper deduplication.
	 * 
	 * Fixes the bug where ALL uploads were incorrectly tagged with the same
	 * section origin (from the first non-dirty section found), which defeated
	 * the enqueuedSections deduplication in OptiminiumChunkUploadAdmission.
	 */
	@Unique
	private void optiminium$uploadPendingChunks(Camera camera) {
		// With Sodium, this queue is empty because Sodium doesn't use the
		// vanilla SectionRenderDispatcher.toUpload path. Sodium manages its
		// own upload queue internally.
		if (OptiminiumSodiumCompat.isNonVanillaRenderer()) {
			return;
		}

		Queue<Runnable> uploads = ((SectionRenderDispatcherAccessor)this.sectionRenderDispatcher).optiminium$getToUpload();
		if (uploads.isEmpty()) {
			return;
		}

		// Collect non-dirty sections in the SAME order they appear in visibleSections.
		// Vanilla sets sections non-dirty immediately before queuing each upload,
		// so the order of non-dirty sections matches the order of upload tasks.
		List<SectionRenderDispatcher.RenderSection> uploadedSections = new ArrayList<>();
		for (SectionRenderDispatcher.RenderSection section : this.visibleSections) {
			if (!section.isDirty() && section.getCompiled() != null) {
				uploadedSections.add(section);
			}
			if (uploadedSections.size() >= uploads.size()) {
				break;
			}
		}

		int sectionIndex = 0;
		while (!uploads.isEmpty()) {
			Runnable upload = uploads.poll();
			if (upload == null) break;

			BlockPos sectionOrigin = null;
			boolean isDirtyFromPlayer = false;

			// Pair each upload with the next non-dirty section's origin
			if (sectionIndex < uploadedSections.size()) {
				SectionRenderDispatcher.RenderSection section = uploadedSections.get(sectionIndex);
				sectionOrigin = section.getOrigin();
				isDirtyFromPlayer = section.isDirtyFromPlayer();
				sectionIndex++;
			}

			OptiminiumChunkUploadAdmission.enqueue(upload, sectionOrigin, isDirtyFromPlayer);
		}

		int baseBudget = OptiminiumGpuOptimizer.scaledChunkUploadBudget(OptiminiumSettings.getChunkUploadsPerFrame());
		OptiminiumChunkUploadAdmission.processUploads(baseBudget, 0L);
	}

	@Inject(method = "renderSnowAndRain", at = @At("HEAD"), cancellable = true)
	private void optiminium$skipWeatherUnderGpuPressure(LightTexture lightTexture, float partialTick, double cameraX, double cameraY, double cameraZ, CallbackInfo callback) {
		if (OptiminiumGpuOptimizer.shouldSkipWeather()) {
			callback.cancel();
		}
	}

	@Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
	private void optiminium$skipCloudsUnderGpuPressure(PoseStack poseStack, Matrix4f projectionMatrix, Matrix4f frustumMatrix, float partialTick, double cameraX, double cameraY, double cameraZ,
			CallbackInfo callback) {
		if (OptiminiumGpuOptimizer.shouldSkipClouds()) {
			callback.cancel();
		}
	}

	@Inject(method = "renderSectionLayer", at = @At("HEAD"))
	private void optiminium$profileRenderSectionLayer(RenderType renderType, double cameraX, double cameraY, double cameraZ, Matrix4f frustumMatrix, Matrix4f projectionMatrix, CallbackInfo callback) {
		if (renderType == RenderType.translucent()) {
			OptiminiumRenderProfiler.recordTranslucentRender();
		} else if (renderType == RenderType.solid() || renderType == RenderType.cutoutMipped() || renderType == RenderType.cutout()) {
			OptiminiumRenderProfiler.recordTerrainRender();
		}

		// Priority 4: Skip empty render layers to avoid unnecessary GL state transitions.
		// The vanilla renderSectionLayer iterates visibleSections and draws compiled
		// sections that have geometry for this layer. If no visible section has a
		// compiled result, we can skip the entire GL state setup/teardown for this layer.
		// This avoids redundant shader binds, texture binds, and buffer binds.
		//
		// Heuristic: if there are no visible sections with compiled data at all,
		// no layer can have geometry. This is a cheap check (no per-section layer scan).
		if (OptiminiumSettings.isEnabled() && OptiminiumSettings.isGpuOptimizer()) {
			boolean hasAnyCompiled = false;
			for (SectionRenderDispatcher.RenderSection section : this.visibleSections) {
				if (section.getCompiled() != null) {
					hasAnyCompiled = true;
					break;
				}
			}
			if (!hasAnyCompiled) {
				callback.cancel();
			}
		}
	}

	@Unique
	private int optiminium$offerCandidate(SectionRenderDispatcher.RenderSection[] candidates, double[] priorities, int candidateCount, SectionRenderDispatcher.RenderSection section, double priority) {
		if (candidates.length <= 0) {
			return candidateCount;
		}
		if (candidateCount < candidates.length) {
			candidates[candidateCount] = section;
			priorities[candidateCount] = priority;
			return candidateCount + 1;
		}
		int worstIndex = 0;
		for (int index = 1; index < priorities.length; index++) {
			if (priorities[index] > priorities[worstIndex]) {
				worstIndex = index;
			}
		}
		if (priority < priorities[worstIndex]) {
			candidates[worstIndex] = section;
			priorities[worstIndex] = priority;
		}
		return candidateCount;
	}

	@Unique
	private boolean optiminium$shouldBuildSynchronously(SectionRenderDispatcher.RenderSection section, BlockPos cameraBlock) {
		if (!section.isDirtyFromPlayer()) {
			return false;
		}
		BlockPos center = section.getOrigin().offset(8, 8, 8);
		return center.distSqr(cameraBlock) < 768.0D;
	}

	@Unique
	private double optiminium$sectionPriority(SectionRenderDispatcher.RenderSection section, Camera camera) {
		BlockPos origin = section.getOrigin();
		Vec3 cameraPosition = camera.getPosition();
		double centerX = origin.getX() + 8.0D;
		double centerY = origin.getY() + 8.0D;
		double centerZ = origin.getZ() + 8.0D;
		double dx = centerX - cameraPosition.x;
		double dy = centerY - cameraPosition.y;
		double dz = centerZ - cameraPosition.z;
		double distanceSqr = dx * dx + dy * dy + dz * dz;
		double distance = Math.sqrt(Math.max(distanceSqr, 0.0001D));
		Vector3f look = camera.getLookVector();
		double facing = Math.max(0.0D, (dx * look.x + dy * look.y + dz * look.z) / distance);

		// Base priority: distance and view cone (lower = more urgent)
		double priority = distanceSqr - facing * 2048.0D;

		// Player-interacted sections get highest priority
		if (section.isDirtyFromPlayer()) {
			priority -= 4096.0D;
		}

		// Occluded sections can be delayed
		if (OptiminiumSettings.isOcclusionRebuildPriority() && optiminium$isOccluded(cameraPosition, centerX, centerY, centerZ)) {
			priority += 8192.0D;
		}

		// ---- Priority 3: Section Significance scoring ----
		// Use the existing significance system to further refine rebuild order.
		// The priority is a lower-is-better value. We apply a significance bonus
		// where HIGH/CRITICAL sections get a large boost, DEFERRED sections get a penalty.
		if (OptiminiumSectionSignificance.isEnabled() && origin != null) {
			net.optiminium.client.OptiminiumSectionSignificance.SectionResult sigResult =
				OptiminiumSectionSignificance.evaluate(origin, camera, section.isDirtyFromPlayer());
			double score = sigResult.score(); // 0.0 (insignificant) -> 1.0 (critical)
			var band = sigResult.band();

			// Map significance to a priority bonus:
			// - CRITICAL (score > 0.70): large bonus (scheduled first)
			// - HIGH (score > 0.50): moderate bonus
			// - NORMAL (score > 0.30): small bonus
			// - BACKGROUND (score > 0.15): slight bonus
			// - DEFERRED (score <= 0.15): penalty (scheduled last)
			//
			// Values are chosen to be large enough to affect ordering vs distanceSqr
			// but not so large as to override player-interaction logic.
			double sigBonus = switch (band) {
				case CRITICAL -> -16384.0D; // Highest priority
				case HIGH    -> -8192.0D;
				case NORMAL  -> -2048.0D;
				case BACKGROUND -> 0.0D;
				case DEFERRED -> 4096.0D; // Delayed
			};

			priority += sigBonus;

			// Deterministic tiebreaker: use section origin hash within the same
			// significance band to ensure consistent ordering across frames.
			priority += (origin.hashCode() & 0xFF) * 0.001D;
		}

		return priority;
	}

	@Unique
	private boolean optiminium$isOccluded(Vec3 cameraPosition, double centerX, double centerY, double centerZ) {
		Vec3 sectionCenter = new Vec3(centerX, centerY, centerZ);
		if (cameraPosition.distanceToSqr(sectionCenter) < 32.0D * 32.0D) {
			return false;
		}
		HitResult hit = this.level.clip(new ClipContext(cameraPosition, sectionCenter, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this.minecraft.cameraEntity));
		return hit.getType() == HitResult.Type.BLOCK && hit.getLocation().distanceToSqr(cameraPosition) + 16.0D < sectionCenter.distanceToSqr(cameraPosition);
	}

	@Unique
	private int optiminium$bestCandidateIndex(SectionRenderDispatcher.RenderSection[] candidates, double[] priorities, int candidateCount) {
		int bestIndex = 0;
		for (int index = 1; index < candidateCount; index++) {
			if (candidates[index] != null && priorities[index] < priorities[bestIndex]) {
				bestIndex = index;
			}
		}
		return bestIndex;
	}
}