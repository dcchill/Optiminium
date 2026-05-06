package net.optiminium.optimization;

import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import java.util.function.Consumer;

public final class OptiminiumCameraChunkLoading {
	private static final int CLIENT_STORAGE_MARGIN_CHUNKS = 3;
	private static final double HALF_VIEW_ANGLE_RADIANS = Math.toRadians(70.0D);
	private static final double MIN_VIEW_DOT = Math.cos(HALF_VIEW_ANGLE_RADIANS);

	private OptiminiumCameraChunkLoading() {
	}

	public static boolean isActive() {
		return OptiminiumSettings.isEnabled() && OptiminiumSettings.isCameraChunkLoading();
	}

	public static boolean isCameraView(ChunkTrackingView view) {
		return view instanceof CameraChunkTrackingView;
	}

	public static boolean equivalent(ChunkTrackingView first, ChunkTrackingView second) {
		return first.equals(second);
	}

	public static void difference(ChunkTrackingView oldView, ChunkTrackingView newView, Consumer<ChunkPos> chunkAdded, Consumer<ChunkPos> chunkRemoved) {
		if (oldView.equals(newView)) {
			return;
		}
		if (oldView instanceof CameraChunkTrackingView oldCameraView && newView instanceof CameraChunkTrackingView newCameraView && oldCameraView.squareIntersects(newCameraView)) {
			for (int chunkX = Math.min(oldCameraView.minX(), newCameraView.minX()); chunkX <= Math.max(oldCameraView.maxX(), newCameraView.maxX()); chunkX++) {
				for (int chunkZ = Math.min(oldCameraView.minZ(), newCameraView.minZ()); chunkZ <= Math.max(oldCameraView.maxZ(), newCameraView.maxZ()); chunkZ++) {
					boolean wasTracked = oldCameraView.contains(chunkX, chunkZ);
					boolean isTracked = newCameraView.contains(chunkX, chunkZ);
					if (wasTracked != isTracked) {
						if (isTracked) {
							chunkAdded.accept(new ChunkPos(chunkX, chunkZ));
						} else {
							chunkRemoved.accept(new ChunkPos(chunkX, chunkZ));
						}
					}
				}
			}
			return;
		}
		ChunkTrackingView.difference(oldView, newView, chunkAdded, chunkRemoved);
	}

	public static ChunkTrackingView createView(ChunkPos center, ChunkPos previousCenter, int viewDistance, float yawDegrees) {
		int yawStepDegrees = OptiminiumSettings.getCameraYawStepDegrees();
		int groupSizeChunks = OptiminiumSettings.getCameraChunkGroupSizeChunks();
		ChunkPos groupedCenter = groupCenter(center, groupSizeChunks);
		return new CameraChunkTrackingView(groupedCenter, nearbyPreviousCenter(groupedCenter, previousCenter, groupSizeChunks), viewDistance, OptiminiumSettings.getCameraAlwaysTrackRadiusChunks(),
				yawStepDegrees, quantizeYaw(yawDegrees, yawStepDegrees), groupSizeChunks);
	}

	public static void sendCenterIfNeeded(ServerPlayer player, ChunkTrackingView currentView, ChunkPos center) {
		ChunkPos currentCenter = centerOf(currentView);
		if (currentCenter == null || !currentCenter.equals(center)) {
			player.connection.send(new ClientboundSetChunkCacheCenterPacket(center.x, center.z));
		}
	}

	public static ChunkPos centerOf(ChunkTrackingView view) {
		if (view instanceof ChunkTrackingView.Positioned positioned) {
			return positioned.center();
		}
		if (view instanceof CameraChunkTrackingView cameraView) {
			return cameraView.center();
		}
		return null;
	}

	private static int quantizeYaw(float yawDegrees, int yawStepDegrees) {
		return Math.round(yawDegrees / yawStepDegrees);
	}

	private static ChunkPos nearbyPreviousCenter(ChunkPos center, ChunkPos previousCenter, int groupSizeChunks) {
		if (previousCenter == null || previousCenter.equals(center)) {
			return null;
		}
		return Math.max(Math.abs(previousCenter.x - center.x), Math.abs(previousCenter.z - center.z)) <= groupSizeChunks ? previousCenter : null;
	}

	private static ChunkPos groupCenter(ChunkPos center, int groupSizeChunks) {
		if (groupSizeChunks <= 1) {
			return center;
		}
		return new ChunkPos(groupBase(center.x, groupSizeChunks) + groupSizeChunks / 2, groupBase(center.z, groupSizeChunks) + groupSizeChunks / 2);
	}

	private static int groupBase(int coordinate, int groupSizeChunks) {
		return Math.floorDiv(coordinate, groupSizeChunks) * groupSizeChunks;
	}

	private record CameraChunkTrackingView(ChunkPos center, ChunkPos previousCenter, int viewDistance, int alwaysTrackRadiusChunks, int yawStepDegrees, int yawStep, int groupSizeChunks)
			implements ChunkTrackingView {
		@Override
		public boolean contains(int chunkX, int chunkZ, boolean includeAdjacent) {
			if (!isNearCenter(this.center, chunkX, chunkZ, this.viewDistance + CLIENT_STORAGE_MARGIN_CHUNKS)) {
				return false;
			}
			if (this.groupSizeChunks > 1 && !isNearCenter(this.center, chunkX, chunkZ, this.alwaysTrackRadiusChunks)) {
				return groupIntersectsView(chunkX, chunkZ, includeAdjacent);
			}
			return baseContains(chunkX, chunkZ, includeAdjacent);
		}

		private boolean baseContains(int chunkX, int chunkZ, boolean includeAdjacent) {
			if (!ChunkTrackingView.isWithinDistance(this.center.x, this.center.z, this.viewDistance, chunkX, chunkZ, includeAdjacent)) {
				return false;
			}
			if (this.previousCenter != null && isNearCenter(this.previousCenter, chunkX, chunkZ, Math.max(1, this.alwaysTrackRadiusChunks))) {
				return true;
			}
			int offsetX = chunkX - this.center.x;
			int offsetZ = chunkZ - this.center.z;
			if (isNearCenter(this.center, chunkX, chunkZ, this.alwaysTrackRadiusChunks)) {
				return true;
			}
			double yawRadians = Math.toRadians(this.yawStep * this.yawStepDegrees);
			double viewX = -Math.sin(yawRadians);
			double viewZ = Math.cos(yawRadians);
			double length = Math.sqrt(offsetX * offsetX + offsetZ * offsetZ);
			return (offsetX * viewX + offsetZ * viewZ) / length >= MIN_VIEW_DOT;
		}

		private boolean groupIntersectsView(int chunkX, int chunkZ, boolean includeAdjacent) {
			int minGroupX = groupBase(chunkX, this.groupSizeChunks);
			int minGroupZ = groupBase(chunkZ, this.groupSizeChunks);
			for (int groupX = minGroupX; groupX < minGroupX + this.groupSizeChunks; groupX++) {
				for (int groupZ = minGroupZ; groupZ < minGroupZ + this.groupSizeChunks; groupZ++) {
					if (baseContains(groupX, groupZ, includeAdjacent)) {
						return true;
					}
				}
			}
			return false;
		}

		@Override
		public void forEach(Consumer<ChunkPos> consumer) {
			for (int chunkX = minX(); chunkX <= maxX(); chunkX++) {
				for (int chunkZ = minZ(); chunkZ <= maxZ(); chunkZ++) {
					if (contains(chunkX, chunkZ)) {
						consumer.accept(new ChunkPos(chunkX, chunkZ));
					}
				}
			}
		}

		private int minX() {
			return this.center.x - this.viewDistance - CLIENT_STORAGE_MARGIN_CHUNKS;
		}

		private int minZ() {
			return this.center.z - this.viewDistance - CLIENT_STORAGE_MARGIN_CHUNKS;
		}

		private int maxX() {
			return this.center.x + this.viewDistance + CLIENT_STORAGE_MARGIN_CHUNKS;
		}

		private int maxZ() {
			return this.center.z + this.viewDistance + CLIENT_STORAGE_MARGIN_CHUNKS;
		}

		private boolean squareIntersects(CameraChunkTrackingView view) {
			return this.minX() <= view.maxX() && this.maxX() >= view.minX() && this.minZ() <= view.maxZ() && this.maxZ() >= view.minZ();
		}

		private static boolean isNearCenter(ChunkPos center, int chunkX, int chunkZ, int radiusChunks) {
			return Math.max(Math.abs(chunkX - center.x), Math.abs(chunkZ - center.z)) <= radiusChunks;
		}
	}
}
