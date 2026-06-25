package net.optiminium.client;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.optiminium.optimization.OptiminiumSettings;

public final class OptiminiumVisualSignificance {
	private static final double NEAR_DISTANCE_SQR = 24.0D * 24.0D;
	private static final double THROTTLED_DISTANCE_SQR = 56.0D * 56.0D;
	private static final double REUSED_DISTANCE_SQR = 96.0D * 96.0D;
	private static final double PROXY_DISTANCE_SQR = 160.0D * 160.0D;
	private static final double LOOKED_AT_DOT = 0.985D;
	private static final double LOW_SCREEN_SIZE_DISTANCE_SQR = 128.0D * 128.0D;
	private static final double TINY_DISTANCE_SQR = 192.0D * 192.0D;
	private static final long RECENT_FRAMES = 80L;
	private static final long NEVER_INTERACTED_FRAME = -RECENT_FRAMES - 1L;
	private static final Long2IntOpenHashMap seenCounts = new Long2IntOpenHashMap();
	private static final Long2LongOpenHashMap recentlyInteracted = new Long2LongOpenHashMap();
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
	private static long fullBecauseNearby;
	private static long fullBecauseImportant;
	private static long fullBecauseLookedAt;
	private static long fullBecauseRecentlyInteracted;
	private static long throttledBecauseDistance;
	private static long throttledBecauseFramePressure;
	private static long reusedBecauseStable;
	private static long reusedBecauseCameraStable;
	private static long proxyBecauseFarRepeated;
	private static long proxyBecauseLowScreenSize;
	private static long culledBecauseOffscreen;
	private static long culledBecauseBudget;
	private static long culledBecauseTiny;
	private static long culledBecauseLowSignificance;
	private static long significanceNanos;
	private static long worstSignificanceNanos;
	private static long profiledObjects;
	private static double nearestDistanceSqr = Double.POSITIVE_INFINITY;
	private static long frameIndex;
	private static double lastCameraX = Double.NaN;
	private static double lastCameraY = Double.NaN;
	private static double lastCameraZ = Double.NaN;
	private static boolean cameraStable;

	static {
		recentlyInteracted.defaultReturnValue(NEVER_INTERACTED_FRAME);
	}

	private OptiminiumVisualSignificance() {
	}

	public static void onFrameStart() {
		frameIndex++;
		recordInteractionTarget();
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
		updateCameraStability();
	}

	public static boolean isEnabled() {
		return OptiminiumSettings.isExperimentalTemporalSignificance();
	}

	public static boolean shouldProtectBlockEntity(BlockEntity blockEntity) {
		if (!isEnabled()) {
			return false;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.gameRenderer == null || minecraft.gameRenderer.getMainCamera() == null) {
			return false;
		}
		BlockPos pos = blockEntity.getBlockPos();
		Vec3 cameraPosition = minecraft.gameRenderer.getMainCamera().getPosition();
		double dx = pos.getX() + 0.5D - cameraPosition.x;
		double dy = pos.getY() + 0.5D - cameraPosition.y;
		double dz = pos.getZ() + 0.5D - cameraPosition.z;
		double distanceSqr = dx * dx + dy * dy + dz * dz;
		return distanceSqr <= NEAR_DISTANCE_SQR
			|| isImportant(blockEntity)
			|| isLookedAt(pos.asLong(), dx, dy, dz, distanceSqr)
			|| frameIndex - recentlyInteracted.get(pos.asLong()) <= RECENT_FRAMES;
	}

	public static boolean shouldProtectEntity(Entity entity) {
		if (!isEnabled()) {
			return false;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.gameRenderer == null || minecraft.gameRenderer.getMainCamera() == null) {
			return false;
		}
		Vec3 cameraPosition = minecraft.gameRenderer.getMainCamera().getPosition();
		double dx = entity.getX() - cameraPosition.x;
		double dy = entity.getY() - cameraPosition.y;
		double dz = entity.getZ() - cameraPosition.z;
		double distanceSqr = dx * dx + dy * dy + dz * dz;
		return distanceSqr <= NEAR_DISTANCE_SQR || isImportantEntity(entity) || isLookedAtDirection(dx, dy, dz, distanceSqr);
	}

	public static void recordBlockEntity(BlockEntity blockEntity, Vec3 cameraPosition) {
		if (!isEnabled()) {
			return;
		}
		long startNanos = System.nanoTime();
		try {
			recordBlockEntityProfiled(blockEntity, cameraPosition);
		} finally {
			long nanos = Math.max(0L, System.nanoTime() - startNanos);
			significanceNanos += nanos;
			worstSignificanceNanos = Math.max(worstSignificanceNanos, nanos);
			profiledObjects++;
		}
	}

	public static void recordEntity(Entity entity, boolean culled) {
		if (!isEnabled()) {
			return;
		}
		long startNanos = System.nanoTime();
		try {
			boolean important = isImportantEntity(entity);
			recordPoint(entity.getX(), entity.getY(), entity.getZ(), important, culled);
		} finally {
			recordProfileNanos(startNanos);
		}
	}

	public static void recordParticle(ParticleOptions options, double x, double y, double z, boolean culled) {
		if (!isEnabled()) {
			return;
		}
		long startNanos = System.nanoTime();
		try {
			recordPoint(x, y, z, isImportantParticle(options), culled);
		} finally {
			recordProfileNanos(startNanos);
		}
	}

	private static void recordBlockEntityProfiled(BlockEntity blockEntity, Vec3 cameraPosition) {
		BlockPos pos = blockEntity.getBlockPos();
		double dx = pos.getX() + 0.5D - cameraPosition.x;
		double dy = pos.getY() + 0.5D - cameraPosition.y;
		double dz = pos.getZ() + 0.5D - cameraPosition.z;
		double distanceSqr = dx * dx + dy * dy + dz * dz;
		nearestDistanceSqr = Math.min(nearestDistanceSqr, distanceSqr);
		long key = pos.asLong();
		boolean lookedAt = isLookedAt(key, dx, dy, dz, distanceSqr);
		boolean inFront = isInFront(dx, dy, dz, distanceSqr);
		boolean important = isImportant(blockEntity);
		boolean pressured = OptiminiumGpuOptimizer.hasVisualSignificancePressure();
		int repeatCount = seenCounts.addTo(key, 1) + 1;
		double score = score(distanceSqr, inFront, lookedAt, important, frameIndex - recentlyInteracted.get(key) <= RECENT_FRAMES, repeatCount, pressured);
		if (distanceSqr <= NEAR_DISTANCE_SQR) {
			fullThisFrame++;
			fullBecauseNearby++;
		} else if (important) {
			fullThisFrame++;
			fullBecauseImportant++;
		} else if (lookedAt) {
			fullThisFrame++;
			fullBecauseLookedAt++;
		} else if (frameIndex - recentlyInteracted.get(key) <= RECENT_FRAMES) {
			fullThisFrame++;
			fullBecauseRecentlyInteracted++;
		} else if (!inFront) {
			culledThisFrame++;
			culledBecauseOffscreen++;
		} else if (distanceSqr > TINY_DISTANCE_SQR) {
			culledThisFrame++;
			culledBecauseTiny++;
		} else if (pressured && score < 0.30D) {
			culledThisFrame++;
			culledBecauseLowSignificance++;
		} else if (pressured && distanceSqr <= THROTTLED_DISTANCE_SQR) {
			throttledThisFrame++;
			throttledBecauseFramePressure++;
		} else if (distanceSqr <= THROTTLED_DISTANCE_SQR) {
			throttledThisFrame++;
			throttledBecauseDistance++;
		} else if (cameraStable && distanceSqr <= REUSED_DISTANCE_SQR) {
			reusedThisFrame++;
			reusedBecauseCameraStable++;
		} else if (distanceSqr <= REUSED_DISTANCE_SQR) {
			reusedThisFrame++;
			reusedBecauseStable++;
		} else if (distanceSqr >= LOW_SCREEN_SIZE_DISTANCE_SQR) {
			proxyThisFrame++;
			proxyBecauseLowScreenSize++;
		} else if (distanceSqr <= PROXY_DISTANCE_SQR && repeatCount >= 4) {
			proxyThisFrame++;
			proxyBecauseFarRepeated++;
		} else {
			culledThisFrame++;
			culledBecauseBudget++;
		}
	}

	private static void recordPoint(double x, double y, double z, boolean important, boolean culled) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.gameRenderer == null || minecraft.gameRenderer.getMainCamera() == null) {
			return;
		}
		Vec3 cameraPosition = minecraft.gameRenderer.getMainCamera().getPosition();
		double dx = x - cameraPosition.x;
		double dy = y - cameraPosition.y;
		double dz = z - cameraPosition.z;
		double distanceSqr = dx * dx + dy * dy + dz * dz;
		nearestDistanceSqr = Math.min(nearestDistanceSqr, distanceSqr);
		boolean inFront = isInFront(dx, dy, dz, distanceSqr);
		boolean lookedAt = isLookedAtDirection(dx, dy, dz, distanceSqr);
		boolean pressured = OptiminiumGpuOptimizer.hasVisualSignificancePressure();
		double score = score(distanceSqr, inFront, lookedAt, important, false, 1, pressured);
		if (distanceSqr <= NEAR_DISTANCE_SQR) {
			fullThisFrame++;
			fullBecauseNearby++;
		} else if (important) {
			fullThisFrame++;
			fullBecauseImportant++;
		} else if (lookedAt) {
			fullThisFrame++;
			fullBecauseLookedAt++;
		} else if (culled && !inFront) {
			culledThisFrame++;
			culledBecauseOffscreen++;
		} else if (culled && distanceSqr > TINY_DISTANCE_SQR) {
			culledThisFrame++;
			culledBecauseTiny++;
		} else if (culled) {
			culledThisFrame++;
			culledBecauseBudget++;
		} else if (pressured && score < 0.30D) {
			culledThisFrame++;
			culledBecauseLowSignificance++;
		} else if (pressured && distanceSqr <= THROTTLED_DISTANCE_SQR) {
			throttledThisFrame++;
			throttledBecauseFramePressure++;
		} else if (distanceSqr <= THROTTLED_DISTANCE_SQR) {
			throttledThisFrame++;
			throttledBecauseDistance++;
		} else if (cameraStable && distanceSqr <= REUSED_DISTANCE_SQR) {
			reusedThisFrame++;
			reusedBecauseCameraStable++;
		} else if (distanceSqr <= REUSED_DISTANCE_SQR) {
			reusedThisFrame++;
			reusedBecauseStable++;
		} else if (distanceSqr >= LOW_SCREEN_SIZE_DISTANCE_SQR) {
			proxyThisFrame++;
			proxyBecauseLowScreenSize++;
		} else {
			culledThisFrame++;
			culledBecauseLowSignificance++;
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
			culledTotal + culledThisFrame,
			fullBecauseNearby,
			fullBecauseImportant,
			fullBecauseLookedAt,
			fullBecauseRecentlyInteracted,
			throttledBecauseDistance,
			throttledBecauseFramePressure,
			reusedBecauseStable,
			reusedBecauseCameraStable,
			proxyBecauseFarRepeated,
			proxyBecauseLowScreenSize,
			culledBecauseOffscreen,
			culledBecauseBudget,
			culledBecauseTiny,
			culledBecauseLowSignificance,
			averageSignificanceMs(),
			worstSignificanceNanos / 1_000_000.0D,
			mostCommonReason(),
			nearestDistanceSqr == Double.POSITIVE_INFINITY ? -1.0D : Math.sqrt(nearestDistanceSqr)
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
		fullBecauseNearby = 0L;
		fullBecauseImportant = 0L;
		fullBecauseLookedAt = 0L;
		fullBecauseRecentlyInteracted = 0L;
		throttledBecauseDistance = 0L;
		throttledBecauseFramePressure = 0L;
		reusedBecauseStable = 0L;
		reusedBecauseCameraStable = 0L;
		proxyBecauseFarRepeated = 0L;
		proxyBecauseLowScreenSize = 0L;
		culledBecauseOffscreen = 0L;
		culledBecauseBudget = 0L;
		culledBecauseTiny = 0L;
		culledBecauseLowSignificance = 0L;
		significanceNanos = 0L;
		worstSignificanceNanos = 0L;
		profiledObjects = 0L;
		nearestDistanceSqr = Double.POSITIVE_INFINITY;
		seenCounts.clear();
		recentlyInteracted.clear();
	}

	private static double score(double distanceSqr, boolean inFront, boolean lookedAt, boolean important, boolean recent, int repeatCount, boolean pressured) {
		double score = 1.0D - Math.min(1.0D, Math.sqrt(distanceSqr) / 192.0D);
		if (!inFront) {
			score -= 0.45D;
		}
		if (lookedAt) {
			score += 0.55D;
		}
		if (important) {
			score += 0.45D;
		}
		if (recent) {
			score += 0.50D;
		}
		if (repeatCount >= 4) {
			score -= 0.10D;
		}
		if (pressured) {
			score -= 0.10D;
		}
		return Math.max(0.0D, Math.min(1.0D, score));
	}

	private static boolean isLookedAt(long blockEntityPos, double dx, double dy, double dz, double distanceSqr) {
		if (distanceSqr <= 0.0001D) {
			return true;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().asLong() == blockEntityPos) {
			return true;
		}
		if (minecraft.gameRenderer == null || minecraft.gameRenderer.getMainCamera() == null) {
			return false;
		}
		var look = minecraft.gameRenderer.getMainCamera().getLookVector();
		return (dx * look.x + dy * look.y + dz * look.z) / Math.sqrt(distanceSqr) >= LOOKED_AT_DOT;
	}

	private static boolean isInFront(double dx, double dy, double dz, double distanceSqr) {
		if (distanceSqr <= 0.0001D) {
			return true;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.gameRenderer == null || minecraft.gameRenderer.getMainCamera() == null) {
			return true;
		}
		var look = minecraft.gameRenderer.getMainCamera().getLookVector();
		return (dx * look.x + dy * look.y + dz * look.z) / Math.sqrt(distanceSqr) > 0.0D;
	}

	private static boolean isLookedAtDirection(double dx, double dy, double dz, double distanceSqr) {
		if (distanceSqr <= 0.0001D) {
			return true;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.gameRenderer == null || minecraft.gameRenderer.getMainCamera() == null) {
			return false;
		}
		var look = minecraft.gameRenderer.getMainCamera().getLookVector();
		return (dx * look.x + dy * look.y + dz * look.z) / Math.sqrt(distanceSqr) >= LOOKED_AT_DOT;
	}

	private static void recordInteractionTarget() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.options == null || (!minecraft.options.keyUse.isDown() && !minecraft.options.keyAttack.isDown())) {
			return;
		}
		if (minecraft.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
			recentlyInteracted.put(hit.getBlockPos().asLong(), frameIndex);
		}
	}

	private static void updateCameraStability() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.gameRenderer == null || minecraft.gameRenderer.getMainCamera() == null) {
			cameraStable = false;
			return;
		}
		Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
		cameraStable = !Double.isNaN(lastCameraX)
			&& Math.abs(camera.x - lastCameraX) < 0.01D
			&& Math.abs(camera.y - lastCameraY) < 0.01D
			&& Math.abs(camera.z - lastCameraZ) < 0.01D;
		lastCameraX = camera.x;
		lastCameraY = camera.y;
		lastCameraZ = camera.z;
	}

	private static double averageSignificanceMs() {
		return profiledObjects <= 0L ? 0.0D : significanceNanos / 1_000_000.0D / profiledObjects;
	}

	private static String mostCommonReason() {
		String reason = "none";
		long count = 0L;
		if (fullBecauseNearby > count) { reason = "fullBecauseNearby"; count = fullBecauseNearby; }
		if (fullBecauseImportant > count) { reason = "fullBecauseImportant"; count = fullBecauseImportant; }
		if (fullBecauseLookedAt > count) { reason = "fullBecauseLookedAt"; count = fullBecauseLookedAt; }
		if (fullBecauseRecentlyInteracted > count) { reason = "fullBecauseRecentlyInteracted"; count = fullBecauseRecentlyInteracted; }
		if (throttledBecauseDistance > count) { reason = "throttledBecauseDistance"; count = throttledBecauseDistance; }
		if (throttledBecauseFramePressure > count) { reason = "throttledBecauseFramePressure"; count = throttledBecauseFramePressure; }
		if (reusedBecauseStable > count) { reason = "reusedBecauseStable"; count = reusedBecauseStable; }
		if (reusedBecauseCameraStable > count) { reason = "reusedBecauseCameraStable"; count = reusedBecauseCameraStable; }
		if (proxyBecauseFarRepeated > count) { reason = "proxyBecauseFarRepeated"; count = proxyBecauseFarRepeated; }
		if (proxyBecauseLowScreenSize > count) { reason = "proxyBecauseLowScreenSize"; count = proxyBecauseLowScreenSize; }
		if (culledBecauseOffscreen > count) { reason = "culledBecauseOffscreen"; count = culledBecauseOffscreen; }
		if (culledBecauseBudget > count) { reason = "culledBecauseBudget"; count = culledBecauseBudget; }
		if (culledBecauseTiny > count) { reason = "culledBecauseTiny"; count = culledBecauseTiny; }
		if (culledBecauseLowSignificance > count) { reason = "culledBecauseLowSignificance"; count = culledBecauseLowSignificance; }
		return reason;
	}

	private static boolean isImportant(BlockEntity blockEntity) {
		BlockEntityType<?> type = blockEntity.getType();
		return type == BlockEntityType.BEACON
			|| type == BlockEntityType.CONDUIT
			|| type == BlockEntityType.END_PORTAL
			|| type == BlockEntityType.END_GATEWAY
			|| type == BlockEntityType.MOB_SPAWNER
			|| type == BlockEntityType.TRIAL_SPAWNER
			|| type == BlockEntityType.VAULT;
	}

	private static boolean isImportantEntity(Entity entity) {
		return entity instanceof Player
			|| entity instanceof LivingEntity
			|| entity instanceof Projectile
			|| entity instanceof FallingBlockEntity
			|| entity.hasGlowingTag()
			|| entity.hasCustomName()
			|| entity.isPassenger()
			|| !entity.getPassengers().isEmpty()
			|| entity.displayFireAnimation();
	}

	private static boolean isImportantParticle(ParticleOptions options) {
		return options.getType() == ParticleTypes.EXPLOSION
			|| options.getType() == ParticleTypes.EXPLOSION_EMITTER
			|| options.getType() == ParticleTypes.FLASH
			|| options.getType() == ParticleTypes.DAMAGE_INDICATOR
			|| options.getType() == ParticleTypes.TOTEM_OF_UNDYING;
	}

	private static void recordProfileNanos(long startNanos) {
		long nanos = Math.max(0L, System.nanoTime() - startNanos);
		significanceNanos += nanos;
		worstSignificanceNanos = Math.max(worstSignificanceNanos, nanos);
		profiledObjects++;
	}

	public record Snapshot(
		long full,
		long throttled,
		long reused,
		long proxy,
		long culled,
		long fullBecauseNearby,
		long fullBecauseImportant,
		long fullBecauseLookedAt,
		long fullBecauseRecentlyInteracted,
		long throttledBecauseDistance,
		long throttledBecauseFramePressure,
		long reusedBecauseStable,
		long reusedBecauseCameraStable,
		long proxyBecauseFarRepeated,
		long proxyBecauseLowScreenSize,
		long culledBecauseOffscreen,
		long culledBecauseBudget,
		long culledBecauseTiny,
		long culledBecauseLowSignificance,
		double significanceCpuMs,
		double worstSignificanceCpuMs,
		String mostCommonReason,
		double nearestDistance
	) {
		private String toLine() {
			return "significanceBands=full:" + full
				+ ",throttled:" + throttled
				+ ",reused:" + reused
				+ ",proxy:" + proxy
				+ ",culled:" + culled
				+ ",fullBecauseNearby:" + fullBecauseNearby
				+ ",fullBecauseImportant:" + fullBecauseImportant
				+ ",fullBecauseLookedAt:" + fullBecauseLookedAt
				+ ",fullBecauseRecentlyInteracted:" + fullBecauseRecentlyInteracted
				+ ",throttledBecauseDistance:" + throttledBecauseDistance
				+ ",throttledBecauseFramePressure:" + throttledBecauseFramePressure
				+ ",reusedBecauseStable:" + reusedBecauseStable
				+ ",reusedBecauseCameraStable:" + reusedBecauseCameraStable
				+ ",proxyBecauseFarRepeated:" + proxyBecauseFarRepeated
				+ ",proxyBecauseLowScreenSize:" + proxyBecauseLowScreenSize
				+ ",culledBecauseOffscreen:" + culledBecauseOffscreen
				+ ",culledBecauseBudget:" + culledBecauseBudget
				+ ",culledBecauseTiny:" + culledBecauseTiny
				+ ",culledBecauseLowSignificance:" + culledBecauseLowSignificance
				+ ",significanceCpuMs:" + String.format("%.4f", significanceCpuMs)
				+ ",worstSignificanceCpuMs:" + String.format("%.4f", worstSignificanceCpuMs)
				+ ",mostCommonReason:" + mostCommonReason
				+ ",nearestDistance:" + String.format("%.1f", nearestDistance);
		}
	}
}
