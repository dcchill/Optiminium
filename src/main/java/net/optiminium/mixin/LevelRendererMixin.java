package net.optiminium.mixin;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.optiminium.client.OptiminiumBlockEntityLod;
import net.optiminium.client.OptiminiumGpuOptimizer;
import net.optiminium.client.OptiminiumRenderProfiler;
import net.optiminium.client.OptiminiumVisualSignificance;
import net.optiminium.compat.OptiminiumSodiumCompat;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	@Shadow
	private net.minecraft.client.multiplayer.ClientLevel level;

	@Shadow
	@Final
	private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;

	@Shadow
	@Final
	private Set<BlockEntity> globalBlockEntities;

	@Inject(method = "compileSections", at = @At("HEAD"))
	private void optiminium$recordVisibleBlockEntitiesDuringCompile(Camera camera, CallbackInfo callback) {
		optiminium$recordRawVisibleBlockEntities(camera.getPosition());
	}

	@Inject(method = "renderLevel", at = @At("HEAD"))
	private void optiminium$recordRawVisibleBlockEntitiesForFrame(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture,
			Matrix4f frustumMatrix, Matrix4f projectionMatrix, CallbackInfo callback) {
		// Sodium manages visible sections differently, so Optiminium skips
		// vanilla visible-section sampling in non-vanilla renderers.
		if (OptiminiumSodiumCompat.isNonVanillaRenderer()) {
			return;
		}
		optiminium$recordRawVisibleBlockEntities(camera.getPosition());
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;compileSections(Lnet/minecraft/client/Camera;)V"))
	private void optiminium$captureVisibleSectionsForBlockEntityLod(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightTexture lightTexture,
			Matrix4f frustumMatrix, Matrix4f projectionMatrix, CallbackInfo callback) {
		if (OptiminiumSodiumCompat.isNonVanillaRenderer()) {
			return;
		}
		OptiminiumBlockEntityLod.beginVisibleSectionFrame();
		for (SectionRenderDispatcher.RenderSection section : this.visibleSections) {
			OptiminiumBlockEntityLod.recordVisibleSection(section.getOrigin());
		}
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
					OptiminiumBlockEntityLod.observe(blockEntity);
				}
			}
		}
		synchronized (this.globalBlockEntities) {
			count += this.globalBlockEntities.size();
			if (recordSignificance) {
				for (BlockEntity blockEntity : this.globalBlockEntities) {
					OptiminiumVisualSignificance.recordBlockEntity(blockEntity, cameraPosition);
					OptiminiumBlockEntityLod.observe(blockEntity);
				}
			}
		}
		OptiminiumGpuOptimizer.recordRawVisibleBlockEntitiesBeforeCulling(count);
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

	@Inject(method = "renderSectionLayer", at = @At("HEAD"), cancellable = true)
	private void optiminium$profileRenderSectionLayer(RenderType renderType, double cameraX, double cameraY, double cameraZ, Matrix4f frustumMatrix, Matrix4f projectionMatrix, CallbackInfo callback) {
		if (renderType == RenderType.translucent()) {
			OptiminiumRenderProfiler.recordTranslucentRender();
		} else if (renderType == RenderType.solid() || renderType == RenderType.cutoutMipped() || renderType == RenderType.cutout()) {
			OptiminiumRenderProfiler.recordTerrainRender();
		}
	}

}
