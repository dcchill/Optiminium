package net.optiminium.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.optiminium.optimization.OptiminiumSettings;

public final class OptiminiumVisualSignificance {
	private static int fullThisFrame;
	private static int throttledThisFrame;
	private static int reusedThisFrame;
	private static int proxyThisFrame;
	private static int culledThisFrame;
	private static long fullTotal;
	private static long throttledTotal;
	private static long reusedTotal;
	private static long proxyTotal;
	private static long culledTotal;

	private OptiminiumVisualSignificance() {
	}

	public static void onFrameStart() {
		fullTotal += fullThisFrame;
		throttledTotal += throttledThisFrame;
		reusedTotal += reusedThisFrame;
		proxyTotal += proxyThisFrame;
		culledTotal += culledThisFrame;
		fullThisFrame = 0;
		throttledThisFrame = 0;
		reusedThisFrame = 0;
		proxyThisFrame = 0;
		culledThisFrame = 0;
	}

	public static boolean isEnabled() {
		return OptiminiumSettings.isExperimentalTemporalSignificance();
	}

	public static void recordBlockEntity(BlockEntity blockEntity, Vec3 cameraPosition) {
		if (!isEnabled()) {
			return;
		}
		BlockPos pos = blockEntity.getBlockPos();
		double dx = pos.getX() + 0.5D - cameraPosition.x;
		double dy = pos.getY() + 0.5D - cameraPosition.y;
		double dz = pos.getZ() + 0.5D - cameraPosition.z;
		double distanceSqr = dx * dx + dy * dy + dz * dz;
		if (distanceSqr <= 16.0D * 16.0D) {
			fullThisFrame++;
		} else if (distanceSqr <= 32.0D * 32.0D) {
			throttledThisFrame++;
		} else if (distanceSqr <= 64.0D * 64.0D) {
			reusedThisFrame++;
		} else if (distanceSqr <= 128.0D * 128.0D) {
			proxyThisFrame++;
		} else {
			culledThisFrame++;
		}
	}

	public static String diagnosticLine() {
		return snapshot().toLine();
	}

	public static Snapshot snapshot() {
		return new Snapshot(
			fullTotal + fullThisFrame,
			throttledTotal + throttledThisFrame,
			reusedTotal + reusedThisFrame,
			proxyTotal + proxyThisFrame,
			culledTotal + culledThisFrame
		);
	}

	public static void reset() {
		fullThisFrame = 0;
		throttledThisFrame = 0;
		reusedThisFrame = 0;
		proxyThisFrame = 0;
		culledThisFrame = 0;
		fullTotal = 0L;
		throttledTotal = 0L;
		reusedTotal = 0L;
		proxyTotal = 0L;
		culledTotal = 0L;
	}

	public record Snapshot(long full, long throttled, long reused, long proxy, long culled) {
		private String toLine() {
			return "significanceBands=full:" + full
				+ ",throttled:" + throttled
				+ ",reused:" + reused
				+ ",proxy:" + proxy
				+ ",culled:" + culled;
		}
	}
}
