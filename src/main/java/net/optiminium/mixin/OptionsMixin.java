package net.optiminium.mixin;

import net.minecraft.client.Options;
import net.optiminium.client.OptiminiumClientChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Options.class)
public abstract class OptionsMixin {
	@Inject(method = "getEffectiveRenderDistance", at = @At("RETURN"), cancellable = true)
	private void optiminium$includeCachedChunkRing(CallbackInfoReturnable<Integer> callback) {
		callback.setReturnValue(OptiminiumClientChunkCache.getClientRenderDistance(callback.getReturnValue()));
	}

	@ModifyArg(
		method = "buildPlayerInformation",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ClientInformation;<init>(Ljava/lang/String;ILnet/minecraft/world/entity/player/ChatVisiblity;ZILnet/minecraft/world/entity/HumanoidArm;ZZ)V"),
		index = 1
	)
	private int optiminium$sendLiveChunkDistance(int viewDistance) {
		return OptiminiumClientChunkCache.isActive() ? OptiminiumClientChunkCache.getLiveRenderDistance() : viewDistance;
	}
}
