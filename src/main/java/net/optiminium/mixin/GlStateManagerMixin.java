package net.optiminium.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import net.optiminium.client.OptiminiumGlStateTracker;
import net.optiminium.client.OptiminiumRenderProfiler;
import org.lwjgl.opengl.GL11;
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

	@Redirect(method = "_bindTexture", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glBindTexture(II)V"))
	private static void optiminium$redirectTextureBind(int glTarget, int textureId) {
		if (!OptiminiumGlStateTracker.tryBindTexture(textureId)) {
			// Texture already bound — skip the GL call entirely.
			// GlStateManager's own internal state has already been updated
			// before this redirect fires; the caller only needs the GL state.
			return;
		}
		// Proceed with the GL call and profile it
		long start = OptiminiumRenderProfiler.start();
		GL11.glBindTexture(glTarget, textureId);
		OptiminiumRenderProfiler.recordTextureBind(start);
	}

	// ── Shader program deduplication ─────────────────────────────────────

	@Redirect(method = "_glUseProgram", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL20;glUseProgram(I)V"))
	private static void optiminium$redirectShaderBind(int program) {
		if (!OptiminiumGlStateTracker.tryUseShader(program)) {
			// Program already active — skip the GL call entirely
			return;
		}
		// Proceed with the GL call and profile it
		long start = OptiminiumRenderProfiler.start();
		GL20.glUseProgram(program);
		OptiminiumRenderProfiler.recordShaderBind(start);
	}

	// ── Framebuffer binding (with tracker invalidation) ──────────────────

	@Redirect(method = "_glBindFramebuffer", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL30;glBindFramebuffer(II)V"))
	private static void optiminium$redirectFramebufferBind(int target, int framebuffer) {
		// Framebuffer change may cause external GL state modification from
		// vanilla/Sodium code that we cannot see — invalidate the tracker.
		OptiminiumGlStateTracker.invalidate();
		long start = OptiminiumRenderProfiler.start();
		GL30.glBindFramebuffer(target, framebuffer);
		OptiminiumRenderProfiler.recordFramebufferBind(start);
	}

	// ── Buffer upload profiling (unchanged from original) ────────────────

	@Inject(method = "_glBufferData(ILjava/nio/ByteBuffer;I)V", at = @At("HEAD"))
	private static void optiminium$startBufferUpload(int target, ByteBuffer data, int usage, CallbackInfo callback) {
		optiminium$bufferUploadStart = OptiminiumRenderProfiler.start();
	}

	@Inject(method = "_glBufferData(ILjava/nio/ByteBuffer;I)V", at = @At("RETURN"))
	private static void optiminium$endBufferUpload(int target, ByteBuffer data, int usage, CallbackInfo callback) {
		OptiminiumRenderProfiler.recordBufferUpload(optiminium$bufferUploadStart);
	}

	@Inject(method = "_glBufferData(IJI)V", at = @At("HEAD"))
	private static void optiminium$startBufferUpload(int target, long size, int usage, CallbackInfo callback) {
		optiminium$bufferUploadStart = OptiminiumRenderProfiler.start();
	}

	@Inject(method = "_glBufferData(IJI)V", at = @At("RETURN"))
	private static void optiminium$endBufferUpload(int target, long size, int usage, CallbackInfo callback) {
		OptiminiumRenderProfiler.recordBufferUpload(optiminium$bufferUploadStart);
	}

	@Unique
	private static long optiminium$bufferUploadStart;
}
