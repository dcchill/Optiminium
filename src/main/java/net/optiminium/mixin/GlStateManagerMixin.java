package net.optiminium.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import net.optiminium.client.OptiminiumRenderProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

@Mixin(GlStateManager.class)
public abstract class GlStateManagerMixin {
	@Inject(method = "_bindTexture", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glBindTexture(II)V"))
	private static void optiminium$startTextureBind(int textureId, CallbackInfo callback) {
		optiminium$textureBindStart = OptiminiumRenderProfiler.start();
	}

	@Inject(method = "_bindTexture", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glBindTexture(II)V", shift = At.Shift.AFTER))
	private static void optiminium$endTextureBind(int textureId, CallbackInfo callback) {
		OptiminiumRenderProfiler.recordTextureBind(optiminium$textureBindStart);
	}

	@Inject(method = "_glUseProgram", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL20;glUseProgram(I)V"))
	private static void optiminium$startShaderBind(int program, CallbackInfo callback) {
		optiminium$shaderBindStart = OptiminiumRenderProfiler.start();
	}

	@Inject(method = "_glUseProgram", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL20;glUseProgram(I)V", shift = At.Shift.AFTER))
	private static void optiminium$endShaderBind(int program, CallbackInfo callback) {
		OptiminiumRenderProfiler.recordShaderBind(optiminium$shaderBindStart);
	}

	@Inject(method = "_glBindFramebuffer", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL30;glBindFramebuffer(II)V"))
	private static void optiminium$startFramebufferBind(int target, int framebuffer, CallbackInfo callback) {
		optiminium$framebufferBindStart = OptiminiumRenderProfiler.start();
	}

	@Inject(method = "_glBindFramebuffer", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL30;glBindFramebuffer(II)V", shift = At.Shift.AFTER))
	private static void optiminium$endFramebufferBind(int target, int framebuffer, CallbackInfo callback) {
		OptiminiumRenderProfiler.recordFramebufferBind(optiminium$framebufferBindStart);
	}

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
	@Unique
	private static long optiminium$textureBindStart;
	@Unique
	private static long optiminium$shaderBindStart;
	@Unique
	private static long optiminium$framebufferBindStart;
}
