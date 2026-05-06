package net.optiminium.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.optiminium.optimization.OptiminiumCameraChunkLoading;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
	@Shadow
	abstract int getPlayerViewDistance(ServerPlayer player);

	@Shadow
	private void applyChunkTrackingView(ServerPlayer player, ChunkTrackingView view) {
	}

	@Invoker("markChunkPendingToSend")
	abstract void optiminium$markChunkPendingToSend(ServerPlayer player, ChunkPos pos);

	@Invoker("dropChunk")
	static void optiminium$dropChunk(ServerPlayer player, ChunkPos pos) {
		throw new AssertionError();
	}

	@Inject(method = "updateChunkTracking", at = @At("HEAD"), cancellable = true)
	private void optiminium$useCameraChunkTracking(ServerPlayer player, CallbackInfo callback) {
		if (!OptiminiumCameraChunkLoading.isActive()) {
			return;
		}

		ChunkTrackingView currentView = player.getChunkTrackingView();
		ChunkPos center = player.chunkPosition();
		if (!OptiminiumCameraChunkLoading.isCameraView(currentView)) {
			this.applyChunkTrackingView(player, ChunkTrackingView.EMPTY);
			currentView = ChunkTrackingView.EMPTY;
		}
		OptiminiumCameraChunkLoading.sendCenterIfNeeded(player, currentView, center);
		ChunkTrackingView newView = OptiminiumCameraChunkLoading.createView(center, OptiminiumCameraChunkLoading.centerOf(currentView), this.getPlayerViewDistance(player), player.getYRot());
		OptiminiumCameraChunkLoading.difference(currentView, newView, pos -> this.optiminium$markChunkPendingToSend(player, pos), pos -> optiminium$dropChunk(player, pos));
		player.setChunkTrackingView(newView);
		callback.cancel();
	}
}
