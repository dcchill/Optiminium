package net.optiminium.mixin;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.optiminium.client.OptiminiumGlStateTracker;
import net.optiminium.client.OptiminiumGpuOptimizer;
import net.optiminium.client.OptiminiumRenderProfiler;
import net.optiminium.client.OptiminiumPersistentBlockEntityMeshes;
import net.optiminium.optimization.OptiminiumSettings;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	@Inject(method = "renderLevel", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endLastBatch()V",
		ordinal = 0))
	private void optiminium$flushPersistentArmorStandInstances(DeltaTracker deltaTracker,
			boolean renderBlockOutline, net.minecraft.client.Camera camera, GameRenderer gameRenderer,
			LightTexture lightTexture, Matrix4f projectionMatrix, Matrix4f modelViewMatrix,
			CallbackInfo callback) {
		// Compatible entity atlases piggyback RenderType.draw before its state is cleared.
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;endBatch()V",
		ordinal = 0, shift = At.Shift.AFTER))
	private void optiminium$flushPersistentBlockEntityInstances(DeltaTracker deltaTracker,
			boolean renderBlockOutline, net.minecraft.client.Camera camera, GameRenderer gameRenderer,
			LightTexture lightTexture, Matrix4f projectionMatrix, Matrix4f modelViewMatrix,
			CallbackInfo callback) {
		OptiminiumPersistentBlockEntityMeshes.flushQueued();
	}
    @Shadow
    private net.minecraft.client.multiplayer.ClientLevel level;

    @Shadow
    @Final
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;

    // Invalidate tracker at render pass boundary (start of each level render)
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void optiminium$invalidateGlStateOnLevelRender(DeltaTracker deltaTracker, boolean renderBlockOutline, net.minecraft.client.Camera camera, GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f projectionMatrix, Matrix4f modelViewMatrix, CallbackInfo callback) {
        if (OptiminiumSettings.getOpenGlOptimizationMode() == OptiminiumSettings.OpenGlOptimizationMode.OFF) {
            return;
        }
        OptiminiumGlStateTracker.invalidate(OptiminiumGlStateTracker.InvalidationReason.RENDER_PASS);
    }

    // Weather skipping preserved - unrelated to Block Entity Culling
    @Inject(method = "renderSnowAndRain", at = @At("HEAD"), cancellable = true)
    private void optiminium$skipWeatherUnderGpuPressure(LightTexture lightTexture, float partialTick, double cameraX, double cameraY, double cameraZ, CallbackInfo callback) {
        if (OptiminiumGpuOptimizer.shouldSkipWeather()) {
            callback.cancel();
        }
    }

    // Terrain rendering profiling preserved - unrelated to Block Entity Culling
    @Inject(method = "renderSectionLayer", at = @At("HEAD"))
    private void optiminium$pushTerrainUploadCategory(RenderType renderType, double cameraX, double cameraY, double cameraZ, Matrix4f frustumMatrix, Matrix4f projectionMatrix, CallbackInfo callback) {
        if (OptiminiumRenderProfiler.areUploadCategoriesActive()) {
            OptiminiumRenderProfiler.pushUploadCategory(OptiminiumRenderProfiler.UploadCategory.TERRAIN_CHUNK);
        }
    }

    @Inject(method = "renderSectionLayer", at = @At("RETURN"))
    private void optiminium$popTerrainUploadCategory(RenderType renderType, double cameraX, double cameraY, double cameraZ, Matrix4f frustumMatrix, Matrix4f projectionMatrix, CallbackInfo callback) {
        if (OptiminiumRenderProfiler.areUploadCategoriesActive()) {
            OptiminiumRenderProfiler.popUploadCategory();
        }
    }

    @Inject(method = "renderSectionLayer", at = @At("HEAD"), cancellable = true)
    private void optiminium$profileRenderSectionLayer(RenderType renderType, double cameraX, double cameraY, double cameraZ, Matrix4f frustumMatrix, Matrix4f projectionMatrix, CallbackInfo callback) {
        if (!OptiminiumRenderProfiler.isEnabled()) {
            return;
        }
        if (renderType == RenderType.translucent()) {
            OptiminiumRenderProfiler.recordTranslucentRender();
        } else if (renderType == RenderType.solid() || renderType == RenderType.cutoutMipped() || renderType == RenderType.cutout()) {
            OptiminiumRenderProfiler.recordTerrainRender();
        }
    }

}
