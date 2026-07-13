package net.optiminium.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.optiminium.client.OptiminiumGlStateTracker;
import net.optiminium.client.OptiminiumPersistentBlockEntityMeshes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Inject(method = "reloadResourcePacks", at = @At("HEAD"), require = 0)
	private void optiminium$invalidateGlOnResourceReload(CallbackInfoReturnable<CompletableFuture<Void>> callback) {
		OptiminiumGlStateTracker.invalidate(OptiminiumGlStateTracker.InvalidationReason.RESOURCE_RELOAD);
		OptiminiumPersistentBlockEntityMeshes.clear();
	}

	@Inject(method = "clearClientLevel", at = @At("HEAD"), require = 0)
	private void optiminium$invalidateGlOnWorldUnload(Screen screen, CallbackInfo callback) {
		OptiminiumGlStateTracker.invalidate(OptiminiumGlStateTracker.InvalidationReason.WORLD_UNLOAD);
		OptiminiumPersistentBlockEntityMeshes.clear();
	}
}
