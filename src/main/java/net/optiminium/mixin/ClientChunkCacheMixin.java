package net.optiminium.mixin;

import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.optiminium.client.OptiminiumClientChunkCache;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheMixin {
	@Shadow
	@Final
	ClientLevel level;

	@Shadow
	@Final
	private LevelChunk emptyChunk;

	@Inject(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/LevelChunk;", at = @At("RETURN"), cancellable = true)
	private void optiminium$getCachedChunk(int chunkX, int chunkZ, ChunkStatus status, boolean createEmptyChunk, CallbackInfoReturnable<LevelChunk> callback) {
		if (status != ChunkStatus.FULL) {
			return;
		}

		LevelChunk current = callback.getReturnValue();
		LevelChunk missedChunk = createEmptyChunk ? this.emptyChunk : null;
		if (current == missedChunk) {
			LevelChunk cachedChunk = OptiminiumClientChunkCache.getChunk(level, chunkX, chunkZ);
			if (cachedChunk != null) {
				callback.setReturnValue(cachedChunk);
			}
		}
	}
}
