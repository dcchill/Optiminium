package net.optiminium.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.optiminium.client.OptiminiumGpuOptimizer;
import net.optiminium.client.OptiminiumRenderProfiler;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Shadow
    private net.minecraft.client.multiplayer.ClientLevel level;

    @Shadow
    @Final
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;

    // Weather and cloud skipping preserved - unrelated to Block Entity Culling
    @Inject(method = "renderSnowAndRain", at = @At("HEAD"), cancellable = true)
    private void optiminium$skipWeatherUnderGpuPressure(LightTexture lightTexture, float partialTick, double cameraX, double cameraY, double cameraZ, CallbackInfo callback) {
        if (OptiminiumGpuOptimizer.shouldSkipWeather()) {
            callback.cancel();
        }
    }

    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void optiminium$skipCloudsUnderGpuPressure(PoseStack poseStack, Matrix4f projectionMatrix, Matrix4f frustumMatrix, float partialTick, double cameraX, double cameraY, double cameraZ, CallbackInfo callback) {
        if (OptiminiumGpuOptimizer.shouldSkipClouds()) {
            callback.cancel();
        }
    }

    // Terrain rendering profiling preserved - unrelated to Block Entity Culling
    @Inject(method = "renderSectionLayer", at = @At("HEAD"), cancellable = true)
    private void optiminium$profileRenderSectionLayer(RenderType renderType, double cameraX, double cameraY, double cameraZ, Matrix4f frustumMatrix, Matrix4f projectionMatrix, CallbackInfo callback) {
        if (renderType == RenderType.translucent()) {
            OptiminiumRenderProfiler.recordTranslucentRender();
        } else if (renderType == RenderType.solid() || renderType == RenderType.cutoutMipped() || renderType == RenderType.cutout()) {
            OptiminiumRenderProfiler.recordTerrainRender();
        }
    }

}
