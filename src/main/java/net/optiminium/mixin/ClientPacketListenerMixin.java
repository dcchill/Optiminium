package net.optiminium.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.optiminium.client.OptiminiumClientChunkCache;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	@Shadow
	@Final
	private ClientLevel level;

	@Inject(method = "queueLightRemoval", at = @At("HEAD"), cancellable = true)
	private void optiminium$keepCachedChunkLight(ClientboundForgetLevelChunkPacket packet, CallbackInfo callback) {
		if (OptiminiumClientChunkCache.hasCachedChunk(this.level, packet.pos())) {
			callback.cancel();
		}
	}
}
