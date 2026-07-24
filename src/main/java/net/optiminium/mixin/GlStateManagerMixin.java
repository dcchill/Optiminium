package net.optiminium.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import net.optiminium.client.OptiminiumGlStateTracker;
import net.optiminium.client.OptiminiumRenderProfiler;
import net.optiminium.optimization.OptiminiumSettings;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

/**
 * Mixin into GlStateManager to profile and deduplicate GL state changes.
 *
 * <p>Uses {@link Redirect} instead of cancellable {@link Inject} at HEAD so that
 * GlStateManager's own internal state tracking always executes correctly. The
 * redirect only wraps the actual GL call, allowing the rest of the method body
 * to run normally. This is critical for compatibility with Sodium's own mixins
 * into the same class.
 */
@Mixin(GlStateManager.class)
public abstract class GlStateManagerMixin {
	// ── Texture bind deduplication ───────────────────────────────────────

	@Redirect(method = "_bindTexture", at = @At(value = "INVOKE",
		target = "Lorg/lwjgl/opengl/GL11;glBindTexture(II)V", remap = false), remap = false)
	private static void optiminium$redirectTextureBind(int glTarget, int textureId) {
		OptiminiumSettings.OpenGlOptimizationMode mode = OptiminiumSettings.getOpenGlOptimizationMode();
		if (mode == OptiminiumSettings.OpenGlOptimizationMode.OFF) {
			GL11.glBindTexture(glTarget, textureId);
			return;
		}
		if (!OptiminiumGlStateTracker.tryBindTexture(glTarget, textureId)) {
			// Texture already bound — skip the GL call entirely.
			// GlStateManager's own internal state has already been updated
			// before this redirect fires; the caller only needs the GL state.
			return;
		}
		// Proceed with the GL call and profile it
		long start = OptiminiumRenderProfiler.start();
		GL11.glBindTexture(glTarget, textureId);
		optiminium$checkGlError(mode, "textureBind");
		OptiminiumRenderProfiler.recordTextureBind(start);
	}

	@Inject(method = "_activeTexture", at = @At("HEAD"), require = 0, remap = false)
	private static void optiminium$trackActiveTexture(int texture, CallbackInfo callback) {
		if (OptiminiumSettings.getOpenGlOptimizationMode() == OptiminiumSettings.OpenGlOptimizationMode.OFF) {
			return;
		}
		OptiminiumGlStateTracker.onActiveTexture(texture);
	}

	// ── Shader program deduplication ─────────────────────────────────────

	@Redirect(method = "_glUseProgram", at = @At(value = "INVOKE",
		target = "Lorg/lwjgl/opengl/GL20;glUseProgram(I)V", remap = false), remap = false)
	private static void optiminium$redirectShaderBind(int program) {
		OptiminiumSettings.OpenGlOptimizationMode mode = OptiminiumSettings.getOpenGlOptimizationMode();
		if (mode == OptiminiumSettings.OpenGlOptimizationMode.OFF) {
			GL20.glUseProgram(program);
			return;
		}
		if (!OptiminiumGlStateTracker.tryUseShader(program)) {
			// Program already active — skip the GL call entirely
			return;
		}
		// Proceed with the GL call and profile it
		long start = OptiminiumRenderProfiler.start();
		GL20.glUseProgram(program);
		optiminium$checkGlError(mode, "shaderBind");
		OptiminiumRenderProfiler.recordShaderBind(start);
	}

	// ── Framebuffer binding (with tracker invalidation) ──────────────────

	@Redirect(method = "_glBindFramebuffer", at = @At(value = "INVOKE",
		target = "Lorg/lwjgl/opengl/GL30;glBindFramebuffer(II)V", remap = false), remap = false)
	private static void optiminium$redirectFramebufferBind(int target, int framebuffer) {
		OptiminiumSettings.OpenGlOptimizationMode mode = OptiminiumSettings.getOpenGlOptimizationMode();
		if (mode == OptiminiumSettings.OpenGlOptimizationMode.OFF) {
			GL30.glBindFramebuffer(target, framebuffer);
			return;
		}
		// Framebuffer change may cause external GL state modification from
		// vanilla/Sodium code that we cannot see — invalidate the tracker.
		OptiminiumGlStateTracker.invalidate(OptiminiumGlStateTracker.InvalidationReason.FRAMEBUFFER);
		long start = OptiminiumRenderProfiler.start();
		GL30.glBindFramebuffer(target, framebuffer);
		optiminium$checkGlError(mode, "framebufferBind");
		OptiminiumRenderProfiler.recordFramebufferBind(start);
	}

	// ── Buffer upload profiling and duplicate suppression ───────────────

	@Redirect(method = "_glBufferData(ILjava/nio/ByteBuffer;I)V", at = @At(value = "INVOKE",
		target = "Lorg/lwjgl/opengl/GL15;glBufferData(ILjava/nio/ByteBuffer;I)V", remap = false), remap = false)
	private static void optiminium$redirectBufferedUpload(int target, ByteBuffer data, int usage) {
		OptiminiumSettings.OpenGlOptimizationMode mode = OptiminiumSettings.getOpenGlOptimizationMode();
		if (mode == OptiminiumSettings.OpenGlOptimizationMode.OFF) {
			optiminium$clearLastUploadSignature();
			GL15.glBufferData(target, data, usage);
			return;
		}
		long start = OptiminiumRenderProfiler.start();
		boolean shouldSkip = optiminium$shouldSkipDuplicateUpload(mode, target, data, usage);
		if (!shouldSkip) {
			GL15.glBufferData(target, data, usage);
		}
		optiminium$checkGlError(mode, "bufferUpload");
		if (start != 0L) {
			OptiminiumRenderProfiler.recordBufferUpload(start, shouldSkip ? 0L : (data == null ? 0L : (long)data.remaining()), OptiminiumRenderProfiler.currentUploadCategory(), !shouldSkip);
		}
	}

	@Redirect(method = "_glBufferData(IJI)V", at = @At(value = "INVOKE",
		target = "Lorg/lwjgl/opengl/GL15;glBufferData(IJI)V", remap = false), remap = false)
	private static void optiminium$redirectBufferedUpload(int target, long size, int usage) {
		OptiminiumSettings.OpenGlOptimizationMode mode = OptiminiumSettings.getOpenGlOptimizationMode();
		if (mode == OptiminiumSettings.OpenGlOptimizationMode.OFF) {
			optiminium$clearLastUploadSignature();
			GL15.glBufferData(target, size, usage);
			return;
		}
		long start = OptiminiumRenderProfiler.start();
		boolean shouldSkip = optiminium$shouldSkipDuplicateUpload(mode, target, size, usage);
		if (!shouldSkip) {
			GL15.glBufferData(target, size, usage);
		}
		optiminium$checkGlError(mode, "bufferUpload");
		if (start != 0L) {
			OptiminiumRenderProfiler.recordBufferUpload(start, shouldSkip ? 0L : Math.max(0L, size), OptiminiumRenderProfiler.currentUploadCategory(), !shouldSkip);
		}
	}

	@Unique
	private static int optiminium$lastUploadTarget = Integer.MIN_VALUE;

	@Unique
	private static OptiminiumRenderProfiler.UploadCategory optiminium$lastUploadCategory = OptiminiumRenderProfiler.UploadCategory.UNKNOWN_VANILLA;

	@Unique
	private static long optiminium$lastUploadSignature;

	@Unique
	private static final int optiminium$DUPLICATE_UPLOAD_HASH_LIMIT = 16 * 1024;

	@Unique
	private static boolean optiminium$shouldSkipDuplicateUpload(OptiminiumSettings.OpenGlOptimizationMode mode, int target, ByteBuffer data, int usage) {
		if (mode != OptiminiumSettings.OpenGlOptimizationMode.SAFE_OPTIMIZE) {
			optiminium$clearLastUploadSignature();
			return false;
		}
		if (data == null || data.remaining() <= 0 || data.remaining() > optiminium$DUPLICATE_UPLOAD_HASH_LIMIT) {
			optiminium$clearLastUploadSignature();
			return false;
		}
		OptiminiumRenderProfiler.UploadCategory category = OptiminiumRenderProfiler.currentUploadCategory();
		if (category == OptiminiumRenderProfiler.UploadCategory.UNKNOWN_VANILLA) {
			optiminium$clearLastUploadSignature();
			return false;
		}
		ByteBuffer view = data.duplicate();
		view.position(data.position());
		view.limit(data.limit());
		int hash = 1;
		for (int i = view.position(); i < view.limit(); i++) {
			hash = 31 * hash + view.get(i);
		}
		long signature = optiminium$uploadSignature(data.remaining(), hash, usage);
		boolean duplicate = optiminium$lastUploadTarget == target
			&& optiminium$lastUploadCategory == category
			&& optiminium$lastUploadSignature == signature;
		optiminium$lastUploadTarget = target;
		optiminium$lastUploadCategory = category;
		optiminium$lastUploadSignature = signature;
		return duplicate;
	}

	@Unique
	private static boolean optiminium$shouldSkipDuplicateUpload(OptiminiumSettings.OpenGlOptimizationMode mode, int target, long size, int usage) {
		optiminium$clearLastUploadSignature();
		return false;
	}

	@Unique
	private static void optiminium$clearLastUploadSignature() {
		optiminium$lastUploadTarget = Integer.MIN_VALUE;
		optiminium$lastUploadCategory = OptiminiumRenderProfiler.UploadCategory.UNKNOWN_VANILLA;
		optiminium$lastUploadSignature = 0L;
	}

	@Unique
	private static long optiminium$uploadSignature(int size, int hash, int usage) {
		long mixedHash = (hash & 0xFFFFFFFFL) ^ ((long)usage << 17);
		return ((long)size << 32) ^ mixedHash;
	}

	@Unique
	private static void optiminium$checkGlError(OptiminiumSettings.OpenGlOptimizationMode mode, String reason) {
		if (mode == OptiminiumSettings.OpenGlOptimizationMode.SAFE_OPTIMIZE) {
			OptiminiumGlStateTracker.onGlError(GL11.glGetError(), reason);
		}
	}
}
