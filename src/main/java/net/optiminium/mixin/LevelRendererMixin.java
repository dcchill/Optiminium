package net.optiminium.mixin;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.optiminium.client.OptiminiumGpuOptimizer;
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

import java.util.Queue;

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
	private SectionRenderDispatcher sectionRenderDispatcher;

	@Inject(method = "compileSections", at = @At("HEAD"), cancellable = true)
	private void optiminium$compileSectionsPaced(Camera camera, CallbackInfo callback) {
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
		optiminium$uploadPendingChunks();
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

	@Unique
	private void optiminium$uploadPendingChunks() {
		Queue<Runnable> uploads = ((SectionRenderDispatcherAccessor)this.sectionRenderDispatcher).optiminium$getToUpload();
		int budget = Math.max(1, (int)Math.floor(OptiminiumSettings.getChunkUploadsPerFrame() * OptiminiumGpuOptimizer.getGpuWorkScale()));
		for (int uploaded = 0; uploaded < budget; uploaded++) {
			Runnable upload = uploads.poll();
			if (upload == null) {
				return;
			}
			upload.run();
		}
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
		double priority = distanceSqr - facing * 2048.0D;
		if (section.isDirtyFromPlayer()) {
			priority -= 4096.0D;
		}
		if (OptiminiumSettings.isOcclusionRebuildPriority() && optiminium$isOccluded(cameraPosition, centerX, centerY, centerZ)) {
			priority += 8192.0D;
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
