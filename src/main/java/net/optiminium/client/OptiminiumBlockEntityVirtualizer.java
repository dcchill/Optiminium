package net.optiminium.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.LidBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.optiminium.optimization.OptiminiumSettings;
import org.joml.Vector3f;

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public final class OptiminiumBlockEntityVirtualizer {
	private static final byte FULL = 0;
	private static final byte CACHED_STATIC = 1;
	private static final byte SIMPLIFIED = 2;
	private static final byte IMPOSTOR = 3;
	private static final byte VIRTUALIZED = 4;
	private static final byte UNKNOWN = -1;

	private static final int TRANSITION_COOLDOWN_FRAMES = 10;
	private static final int MAX_CACHE_ENTRIES = 8192;
	private static final double LOOKED_AT_DOT = 0.985D;
	private static final double OFFSCREEN_DOT = -0.10D;
	private static final double LEVEL_0_DISTANCE_SQR = 18.0D * 18.0D;
	private static final double LEVEL_1_DISTANCE_SQR = 48.0D * 48.0D;
	private static final double LEVEL_2_DISTANCE_SQR = 96.0D * 96.0D;
	private static final double LEVEL_3_DISTANCE_SQR = 192.0D * 192.0D;
	private static final double LEVEL_4_DISTANCE_SQR = 384.0D * 384.0D;

	private static final ConcurrentHashMap<Long, Memory> MEMORIES = new ConcurrentHashMap<>(512);
	private static final ConcurrentHashMap<CacheKey, CachedRepresentation> REPRESENTATIONS = new ConcurrentHashMap<>(512);
	private static final ThreadLocal<Long> FULL_RENDER_START = new ThreadLocal<>();

	private static long frameIndex;
	private static long fullRendererAverageNanos = 120_000L;
	private static boolean frameCameraReady;
	private static double frameCameraX;
	private static double frameCameraY;
	private static double frameCameraZ;
	private static double frameLookX;
	private static double frameLookY;
	private static double frameLookZ;

	private static final LongAdder totalBlockEntities = new LongAdder();
	private static final LongAdder renderedNormally = new LongAdder();
	private static final LongAdder wouldVirtualize = new LongAdder();
	private static final LongAdder cachedStatic = new LongAdder();
	private static final LongAdder simplified = new LongAdder();
	private static final LongAdder impostors = new LongAdder();
	private static final LongAdder fullyVirtualized = new LongAdder();
	private static final LongAdder skippedBerCalls = new LongAdder();
	private static final LongAdder proxyRenderFailures = new LongAdder();
	private static final LongAdder representationBuilds = new LongAdder();
	private static final LongAdder representationHits = new LongAdder();
	private static final LongAdder savedCpuNanos = new LongAdder();
	private static final LongAdder fullRenderSamples = new LongAdder();

	private static int totalThisFrame;
	private static int normalThisFrame;
	private static int wouldVirtualizeThisFrame;
	private static int cachedThisFrame;
	private static int simplifiedThisFrame;
	private static int impostorThisFrame;
	private static int virtualizedThisFrame;
	private static int skippedThisFrame;
	private static int lastTotalThisFrame;
	private static int lastNormalThisFrame;
	private static int lastWouldVirtualizeThisFrame;
	private static int lastCachedThisFrame;
	private static int lastSimplifiedThisFrame;
	private static int lastImpostorThisFrame;
	private static int lastVirtualizedThisFrame;
	private static int lastSkippedThisFrame;

	private OptiminiumBlockEntityVirtualizer() {
	}

	public static void onFrameStart() {
		lastTotalThisFrame = totalThisFrame;
		lastNormalThisFrame = normalThisFrame;
		lastWouldVirtualizeThisFrame = wouldVirtualizeThisFrame;
		lastCachedThisFrame = cachedThisFrame;
		lastSimplifiedThisFrame = simplifiedThisFrame;
		lastImpostorThisFrame = impostorThisFrame;
		lastVirtualizedThisFrame = virtualizedThisFrame;
		lastSkippedThisFrame = skippedThisFrame;
		totalThisFrame = 0;
		normalThisFrame = 0;
		wouldVirtualizeThisFrame = 0;
		cachedThisFrame = 0;
		simplifiedThisFrame = 0;
		impostorThisFrame = 0;
		virtualizedThisFrame = 0;
		skippedThisFrame = 0;
		frameIndex++;
		if ((frameIndex & 0x3F) == 0) {
			cleanup();
		}
		updateFrameCamera();
	}

	public static boolean tryVirtualize(BlockEntity blockEntity, float partialTick, PoseStack poseStack,
			MultiBufferSource bufferSource, BlockEntityRenderer<?> renderer) {
		totalBlockEntities.increment();
		totalThisFrame++;
		if (!OptiminiumSettings.isEnabled()
				|| blockEntity == null || renderer == null || blockEntity.getLevel() == null || blockEntity.isRemoved()) {
			recordNormal();
			return false;
		}
		if (!OptiminiumSettings.isBlockEntityVirtualizationEnabled()
				&& !OptiminiumSettings.isBlockEntityRenderCacheDebug()) {
			recordNormal();
			return false;
		}

		VirtualizationDecision decision = chooseLevel(blockEntity);
		if (decision.level == FULL) {
			recordNormal();
			return false;
		}
		recordWouldVirtualize();
		if (!OptiminiumSettings.isBlockEntityVirtualizationEnabled()
				|| !isCancellationAllowlisted(blockEntity, decision.level)) {
			recordNormal();
			return false;
		}

		if (decision.level == VIRTUALIZED) {
			if (decision.fullSkipSafe) {
				recordVirtualized(decision.level);
				return true;
			}
			recordNormal();
			return false;
		}
		CachedRepresentation representation = representationFor(blockEntity, decision.level);
		if (representation.render(blockEntity, poseStack, bufferSource, decision.level, decision.fadeAlpha,
				OptiminiumSettings.isBlockEntityVirtualizationDebugProxies())) {
			recordVirtualized(decision.level);
			return true;
		}
		proxyRenderFailures.increment();
		recordNormal();
		return false;
	}

	public static void beginFullRenderer() {
		if (OptiminiumSettings.isEnabled() && OptiminiumSettings.isBlockEntityVirtualizationEnabled()) {
			FULL_RENDER_START.set(System.nanoTime());
		}
	}

	public static void finishFullRenderer() {
		Long start = FULL_RENDER_START.get();
		if (start == null) {
			return;
		}
		FULL_RENDER_START.remove();
		long elapsed = Math.max(0L, System.nanoTime() - start);
		if (elapsed > 0L) {
			long samples = fullRenderSamples.sum();
			fullRendererAverageNanos = samples == 0L
				? elapsed
				: (long)(fullRendererAverageNanos * 0.92D + elapsed * 0.08D);
			fullRenderSamples.increment();
		}
	}

	public static String diagnosticLine() {
		Snapshot snapshot = snapshot();
		return String.format(Locale.ROOT,
			"blockEntityVirtualization=%s, beVirtualAggressiveness=%s, beDebugProxies=%s, beVirtualTotal=%d, beWouldVirtualize=%d, beFull=%d, beCachedCancelled=%d, beSimplifiedCancelled=%d, beImpostorCancelled=%d, beFullyVirtualized=%d, beBerSkipped=%d, beVirtualCache=%d, beVirtualCacheHits=%d, beVirtualBuilds=%d, beVirtualFailures=%d, beVirtualCpuSavedMs=%.3f, beVirtualGpuSavedMs=%.3f",
			OptiminiumSettings.isBlockEntityVirtualizationEnabled() ? "on" : "off",
			OptiminiumSettings.getBlockEntityVirtualizationAggressiveness().name().toLowerCase(Locale.ROOT),
			OptiminiumSettings.isBlockEntityVirtualizationDebugProxies() ? "on" : "off",
			snapshot.totalBlockEntities(), snapshot.wouldVirtualize(), snapshot.renderedNormally(), snapshot.cachedStatic(),
			snapshot.simplified(), snapshot.impostors(), snapshot.fullyVirtualized(),
			snapshot.skippedBerCalls(), snapshot.cachedRepresentations(),
			snapshot.representationHits(), snapshot.representationBuilds(), snapshot.proxyRenderFailures(),
			snapshot.estimatedCpuSavedMs(), snapshot.estimatedGpuSavedMs());
	}

	public static Snapshot snapshot() {
		long skipped = skippedBerCalls.sum();
		double savedMs = savedCpuNanos.sum() / 1_000_000.0D;
		return new Snapshot(
			lastTotalThisFrame,
			lastNormalThisFrame,
			lastWouldVirtualizeThisFrame,
			lastCachedThisFrame,
			lastSimplifiedThisFrame,
			lastImpostorThisFrame,
			lastVirtualizedThisFrame,
			lastSkippedThisFrame,
			totalBlockEntities.sum(),
			renderedNormally.sum(),
			wouldVirtualize.sum(),
			cachedStatic.sum(),
			simplified.sum(),
			impostors.sum(),
			fullyVirtualized.sum(),
			skipped,
			REPRESENTATIONS.size(),
			representationHits.sum(),
			representationBuilds.sum(),
			proxyRenderFailures.sum(),
			savedMs,
			savedMs * 0.35D,
			fullRendererAverageNanos / 1_000_000.0D
		);
	}

	private static VirtualizationDecision chooseLevel(BlockEntity blockEntity) {
		if (!frameCameraReady) {
			return VirtualizationDecision.full();
		}
		BlockPos pos = blockEntity.getBlockPos();
		long key = pos.asLong();
		double dx = pos.getX() + 0.5D - frameCameraX;
		double dy = pos.getY() + 0.5D - frameCameraY;
		double dz = pos.getZ() + 0.5D - frameCameraZ;
		double distanceSqr = dx * dx + dy * dy + dz * dz;
		boolean lookedAt = isLookedAt(dx, dy, dz, distanceSqr);
		double lookDot = lookDot(dx, dy, dz, distanceSqr);
		if (distanceSqr <= LEVEL_0_DISTANCE_SQR || lookedAt || isProtectedFullRenderer(blockEntity)) {
			return stableDecision(key, FULL);
		}
		if (!isKnownVirtualizationCandidate(blockEntity)) {
			return stableDecision(key, FULL);
		}

		if (OptiminiumVisualSignificance.isEnabled()) {
			OptiminiumVisualSignificance.recordBlockEntity(blockEntity, frameCameraPosition());
		}
		byte classification = OptiminiumVisualSignificance.getBlockEntityClassification(blockEntity);
		float fadeAlpha = OptiminiumVisualSignificance.blockEntityFadeAlpha(blockEntity);
		byte desired = levelFromClassification(classification, blockEntity, distanceSqr, lookedAt, lookDot);
		boolean fullSkipSafe = desired == VIRTUALIZED && isFullSkipSafe(distanceSqr, lookedAt, lookDot);
		return stableDecision(key, desired, fadeAlpha, fullSkipSafe);
	}

	private static byte levelFromClassification(byte classification, BlockEntity blockEntity,
			double distanceSqr, boolean lookedAt, double lookDot) {
		if (classification == FULL || lookedAt) {
			return FULL;
		}
		if (classification == CACHED_STATIC) {
			return CACHED_STATIC;
		}
		if (classification == SIMPLIFIED) {
			return isStaticCandidate(blockEntity) ? CACHED_STATIC : SIMPLIFIED;
		}
		if (classification == IMPOSTOR) {
			return isAnimated(blockEntity) ? SIMPLIFIED : IMPOSTOR;
		}
		if (classification == VIRTUALIZED) {
			return isFullSkipSafe(distanceSqr, lookedAt, lookDot) ? VIRTUALIZED : IMPOSTOR;
		}
		if (OptiminiumSettings.getBlockEntityVirtualizationAggressiveness()
				== OptiminiumSettings.BlockEntityVirtualizationAggressiveness.CONSERVATIVE) {
			return isFullSkipSafe(distanceSqr, lookedAt, lookDot) ? VIRTUALIZED : FULL;
		}
		if (distanceSqr <= LEVEL_1_DISTANCE_SQR) {
			return FULL;
		}
		if (distanceSqr <= LEVEL_2_DISTANCE_SQR) {
			return OptiminiumSettings.getBlockEntityVirtualizationAggressiveness()
				== OptiminiumSettings.BlockEntityVirtualizationAggressiveness.AGGRESSIVE
				? CACHED_STATIC : FULL;
		}
		if (distanceSqr <= LEVEL_3_DISTANCE_SQR) {
			return IMPOSTOR;
		}
		return isFullSkipSafe(distanceSqr, lookedAt, lookDot) ? VIRTUALIZED : IMPOSTOR;
	}

	private static VirtualizationDecision stableDecision(long key, byte desired) {
		return stableDecision(key, desired, 1.0F, false);
	}

	private static VirtualizationDecision stableDecision(long key, byte desired, float fadeAlpha, boolean fullSkipSafe) {
		Memory memory = MEMORIES.computeIfAbsent(key, ignored -> new Memory());
		byte current = memory.level == UNKNOWN ? desired : memory.level;
		if (desired < current || frameIndex - memory.lastChangedFrame >= TRANSITION_COOLDOWN_FRAMES) {
			if (desired != current) {
				memory.previousLevel = current;
				memory.level = desired;
				memory.lastChangedFrame = frameIndex;
			}
		}
		memory.lastSeenFrame = frameIndex;
		float stableFade = Mth.clamp(fadeAlpha, 0.18F, 1.0F);
		return new VirtualizationDecision(memory.level, stableFade, fullSkipSafe && memory.level == VIRTUALIZED);
	}

	private static CachedRepresentation representationFor(BlockEntity blockEntity, byte level) {
		BlockState state = blockEntity.getBlockState();
		CacheKey key = new CacheKey(blockEntity.getType(), Block.getId(state), level);
		CachedRepresentation representation = REPRESENTATIONS.get(key);
		if (representation != null) {
			representation.lastUsedFrame = frameIndex;
			representationHits.increment();
			return representation;
		}
		representationBuilds.increment();
		CachedRepresentation built = CachedRepresentation.build(blockEntity, level);
		CachedRepresentation previous = REPRESENTATIONS.putIfAbsent(key, built);
		return previous == null ? built : previous;
	}

	private static void recordNormal() {
		renderedNormally.increment();
		normalThisFrame++;
	}

	private static void recordWouldVirtualize() {
		wouldVirtualize.increment();
		wouldVirtualizeThisFrame++;
	}

	private static void recordVirtualized(byte level) {
		skippedBerCalls.increment();
		skippedThisFrame++;
		savedCpuNanos.add(fullRendererAverageNanos);
		switch (level) {
			case CACHED_STATIC -> {
				cachedStatic.increment();
				cachedThisFrame++;
			}
			case SIMPLIFIED -> {
				simplified.increment();
				simplifiedThisFrame++;
			}
			case IMPOSTOR -> {
				impostors.increment();
				impostorThisFrame++;
			}
			case VIRTUALIZED -> {
				fullyVirtualized.increment();
				virtualizedThisFrame++;
			}
			default -> recordNormal();
		}
	}

	private static void updateFrameCamera() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.gameRenderer == null || minecraft.gameRenderer.getMainCamera() == null) {
			frameCameraReady = false;
			return;
		}
		Vec3 position = minecraft.gameRenderer.getMainCamera().getPosition();
		Vector3f look = minecraft.gameRenderer.getMainCamera().getLookVector();
		frameCameraX = position.x;
		frameCameraY = position.y;
		frameCameraZ = position.z;
		frameLookX = look.x;
		frameLookY = look.y;
		frameLookZ = look.z;
		frameCameraReady = true;
	}

	private static Vec3 frameCameraPosition() {
		return new Vec3(frameCameraX, frameCameraY, frameCameraZ);
	}

	private static boolean isLookedAt(double dx, double dy, double dz, double distanceSqr) {
		if (distanceSqr <= 0.0001D) {
			return true;
		}
		if (!frameCameraReady) {
			return false;
		}
		double invDistance = 1.0D / Math.sqrt(distanceSqr);
		double dot = (dx * invDistance) * frameLookX + (dy * invDistance) * frameLookY + (dz * invDistance) * frameLookZ;
		return dot >= LOOKED_AT_DOT;
	}

	private static double lookDot(double dx, double dy, double dz, double distanceSqr) {
		if (distanceSqr <= 0.0001D) {
			return 1.0D;
		}
		if (!frameCameraReady) {
			return 1.0D;
		}
		double invDistance = 1.0D / Math.sqrt(distanceSqr);
		return (dx * invDistance) * frameLookX + (dy * invDistance) * frameLookY + (dz * invDistance) * frameLookZ;
	}

	private static boolean isProtectedFullRenderer(BlockEntity blockEntity) {
		BlockEntityType<?> type = blockEntity.getType();
		BlockState state = blockEntity.getBlockState();
		return state.getLightEmission() > 0
			|| type == BlockEntityType.LECTERN
			|| type == BlockEntityType.SIGN
			|| type == BlockEntityType.HANGING_SIGN
			|| type == BlockEntityType.CHEST
			|| type == BlockEntityType.TRAPPED_CHEST
			|| type == BlockEntityType.BARREL
			|| type == BlockEntityType.BED
			|| type == BlockEntityType.BANNER
			|| type == BlockEntityType.SKULL
			|| type == BlockEntityType.DECORATED_POT
			|| type == BlockEntityType.SHULKER_BOX
			|| type == BlockEntityType.BELL
			|| type == BlockEntityType.ENCHANTING_TABLE
			|| type == BlockEntityType.BEACON
			|| type == BlockEntityType.CONDUIT
			|| type == BlockEntityType.END_PORTAL
			|| type == BlockEntityType.END_GATEWAY
			|| type == BlockEntityType.MOB_SPAWNER
			|| type == BlockEntityType.TRIAL_SPAWNER
			|| type == BlockEntityType.VAULT
			|| type == BlockEntityType.PISTON
			|| isOpeningLid(blockEntity);
	}

	private static boolean isStaticCandidate(BlockEntity blockEntity) {
		return !isAnimated(blockEntity) && !isProtectedFullRenderer(blockEntity);
	}

	private static boolean isKnownVirtualizationCandidate(BlockEntity blockEntity) {
		BlockEntityType<?> type = blockEntity.getType();
		return type == BlockEntityType.FURNACE
			|| type == BlockEntityType.BLAST_FURNACE
			|| type == BlockEntityType.SMOKER
			|| type == BlockEntityType.CAMPFIRE;
	}

	private static boolean isCancellationAllowlisted(BlockEntity blockEntity, byte level) {
		if (!isKnownVirtualizationCandidate(blockEntity) || !isStaticCandidate(blockEntity)) {
			return false;
		}
		OptiminiumSettings.BlockEntityVirtualizationAggressiveness aggressiveness =
			OptiminiumSettings.getBlockEntityVirtualizationAggressiveness();
		if (level == VIRTUALIZED) {
			return aggressiveness != OptiminiumSettings.BlockEntityVirtualizationAggressiveness.CONSERVATIVE;
		}
		return OptiminiumSettings.isBlockEntityVirtualizationDebugProxies()
			&& aggressiveness == OptiminiumSettings.BlockEntityVirtualizationAggressiveness.AGGRESSIVE;
	}

	private static boolean isFullSkipSafe(double distanceSqr, boolean lookedAt, double lookDot) {
		return !lookedAt && distanceSqr >= LEVEL_4_DISTANCE_SQR && lookDot <= OFFSCREEN_DOT;
	}

	private static boolean isAnimated(BlockEntity blockEntity) {
		if (isOpeningLid(blockEntity) || blockEntity.getType() == BlockEntityType.PISTON) {
			return true;
		}
		ResourceLocation id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType());
		if (id == null || "minecraft".equals(id.getNamespace())) {
			return false;
		}
		String path = id.getPath().toLowerCase(Locale.ROOT);
		return path.contains("shaft") || path.contains("gear") || path.contains("kinetic")
			|| path.contains("motor") || path.contains("fan") || path.contains("wheel")
			|| path.contains("press") || path.contains("mixer") || path.contains("crusher")
			|| path.contains("deployer") || path.contains("pump") || path.contains("belt")
			|| path.contains("piston");
	}

	private static boolean isOpeningLid(BlockEntity blockEntity) {
		return blockEntity instanceof LidBlockEntity lid && lid.getOpenNess(1.0F) > 0.0F;
	}

	private static void cleanup() {
		long staleBefore = frameIndex - 600L;
		MEMORIES.entrySet().removeIf(entry -> entry.getValue().lastSeenFrame < staleBefore);
		if (REPRESENTATIONS.size() <= MAX_CACHE_ENTRIES) {
			return;
		}
		Iterator<Map.Entry<CacheKey, CachedRepresentation>> iterator = REPRESENTATIONS.entrySet().iterator();
		while (REPRESENTATIONS.size() > MAX_CACHE_ENTRIES && iterator.hasNext()) {
			Map.Entry<CacheKey, CachedRepresentation> entry = iterator.next();
			if (entry.getValue().lastUsedFrame < frameIndex - 120L) {
				iterator.remove();
			}
		}
	}

	private record VirtualizationDecision(byte level, float fadeAlpha, boolean fullSkipSafe) {
		static VirtualizationDecision full() {
			return new VirtualizationDecision(FULL, 1.0F, false);
		}
	}

	private static final class Memory {
		byte level = UNKNOWN;
		byte previousLevel = UNKNOWN;
		long lastChangedFrame = Long.MIN_VALUE / 4L;
		long lastSeenFrame;
	}

	private record CacheKey(BlockEntityType<?> type, int stateId, byte level) {
	}

	private static final class CachedRepresentation {
		private final WeakReference<BlockEntityType<?>> type;
		private final int color;
		private final AABB bounds;
		private final boolean renderModel;
		private volatile long lastUsedFrame;

		private CachedRepresentation(BlockEntityType<?> type, int color, AABB bounds, boolean renderModel) {
			this.type = new WeakReference<>(type);
			this.color = color;
			this.bounds = bounds;
			this.renderModel = renderModel;
			this.lastUsedFrame = frameIndex;
		}

		static CachedRepresentation build(BlockEntity blockEntity, byte level) {
			BlockState state = blockEntity.getBlockState();
			AABB bounds = boundsFor(blockEntity, level);
			return new CachedRepresentation(blockEntity.getType(), colorFor(blockEntity), bounds,
				level == CACHED_STATIC && false);
		}

		boolean render(BlockEntity blockEntity, PoseStack poseStack, MultiBufferSource bufferSource,
				byte level, float fadeAlpha, boolean debugProxy) {
			if (type.get() == null) {
				return false;
			}
			int packedLight = LevelRenderer.getLightColor(blockEntity.getLevel(), blockEntity.getBlockPos());
			if (renderModel && level == CACHED_STATIC) {
				Minecraft.getInstance().getBlockRenderer().renderSingleBlock(blockEntity.getBlockState(), poseStack,
					bufferSource, packedLight, OverlayTexture.NO_OVERLAY);
				return true;
			}
			if (!debugProxy) {
				return false;
			}
			float alpha = level == IMPOSTOR ? 0.88F * fadeAlpha : fadeAlpha;
			RenderType renderType = RenderType.debugFilledBox();
			VertexConsumer consumer = bufferSource.getBuffer(renderType);
			OptiminiumRenderProfiler.recordOptiminiumDraw(renderType, true, true);
			int r = color >> 16 & 0xFF;
			int g = color >> 8 & 0xFF;
			int b = color & 0xFF;
			LevelRenderer.addChainedFilledBoxVertices(poseStack, consumer,
				bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ,
				r / 255.0F, g / 255.0F, b / 255.0F, alpha);
			return true;
		}

		private static AABB boundsFor(BlockEntity blockEntity, byte level) {
			if (level == IMPOSTOR) {
				return new AABB(0.18D, 0.18D, 0.18D, 0.82D, 0.82D, 0.82D);
			}
			if (level == SIMPLIFIED) {
				return new AABB(0.08D, 0.08D, 0.08D, 0.92D, 0.92D, 0.92D);
			}
			return new AABB(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D);
		}

		private static int colorFor(BlockEntity blockEntity) {
			ResourceLocation id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType());
			String key = id == null ? "unknown" : id.toString();
			int hash = key.hashCode();
			int r = 96 + Math.abs(hash & 0x5F);
			int g = 96 + Math.abs((hash >> 8) & 0x5F);
			int b = 96 + Math.abs((hash >> 16) & 0x5F);
			return r << 16 | g << 8 | b;
		}
	}

	public record Snapshot(
		int totalThisFrame,
		int normalThisFrame,
		int wouldVirtualizeThisFrame,
		int cachedThisFrame,
		int simplifiedThisFrame,
		int impostorThisFrame,
		int virtualizedThisFrame,
		int skippedThisFrame,
		long totalBlockEntities,
		long renderedNormally,
		long wouldVirtualize,
		long cachedStatic,
		long simplified,
		long impostors,
		long fullyVirtualized,
		long skippedBerCalls,
		int cachedRepresentations,
		long representationHits,
		long representationBuilds,
		long proxyRenderFailures,
		double estimatedCpuSavedMs,
		double estimatedGpuSavedMs,
		double averageFullRendererMs
	) {
	}
}
