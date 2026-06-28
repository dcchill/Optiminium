package net.optiminium.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.Vec3;
import net.optiminium.OptiminiumMod;
import net.optiminium.optimization.OptiminiumMetrics;
import net.optiminium.optimization.OptiminiumSettings;

public final class OptiminiumBlockEntityLod {
	private static final float CUBE_HALF = 0.56F;
	private static final float CUBE_ALPHA = 0.90F;
	private static final int PACKED_LIGHT = 0xF000F0;
	private static final int DEFAULT_COLOR = 0xFFA09080;
	private static final RenderType RENDER_TYPE = RenderType.create(
		"optiminium_block_entity_lod_cube",
		DefaultVertexFormat.POSITION_COLOR_LIGHTMAP,
		com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
		1536,
		false,
		true,
		RenderType.CompositeState.builder()
			.setShaderState(RenderStateShard.POSITION_COLOR_LIGHTMAP_SHADER)
			.setCullState(RenderStateShard.NO_CULL)
			.setLightmapState(RenderStateShard.LIGHTMAP)
			.createCompositeState(false)
	);

	private static final Long2ObjectOpenHashMap<Entry> entriesByPos = new Long2ObjectOpenHashMap<>();
	private static final Long2ObjectOpenHashMap<SectionBucket> bucketsBySection = new Long2ObjectOpenHashMap<>();
	private static final LongOpenHashSet activeThisFrame = new LongOpenHashSet();
	private static final LongOpenHashSet skippedThisFrame = new LongOpenHashSet();
	private static int frameIndex;
	private static long skippedRenderEstimatesThisFrame;
	private static DebugSnapshot lastDebugSnapshot = DebugSnapshot.EMPTY;
	private static long debugObserved;
	private static long debugCreated;
	private static long debugUpdated;
	private static long debugSkippedRecorded;
	private static long debugRenderChecks;
	private static long debugEligibleChecks;
	private static long debugRejectedNull;
	private static long debugRejectedActive;
	private static long debugRejectedLoadedNotSkipped;
	private static long debugPredictedLoaded;
	private static long debugRejectedStale;
	private static long debugRejectedTooNear;
	private static long debugRejectedTooFar;
	private static long debugWorldPassCalls;
	private static long debugWorldPassRendered;
	private static long debugWorldPassEmpty;
	private static long debugCleanupRemoved;
	private static long debugTrimRemoved;

	private OptiminiumBlockEntityLod() {
	}

	public static void beginFrame() {
		publishDebugSnapshot();
		frameIndex++;
		activeThisFrame.clear();
		skippedThisFrame.clear();
		skippedRenderEstimatesThisFrame = 0L;
		resetDebugCounters();
	}

	public static void observe(BlockEntity blockEntity) {
		observe(blockEntity, false);
	}

	public static void recordRendered(BlockEntity blockEntity) {
		observe(blockEntity, true);
	}

	public static void recordSkippedRender(BlockEntity blockEntity) {
		if (!isEnabled() || blockEntity == null) return;
		observe(blockEntity, false);
		debugSkippedRecorded++;
		long posKey = blockEntity.getBlockPos().asLong();
		if (skippedThisFrame.add(posKey) && shouldRenderEntry(entriesByPos.get(posKey), currentCameraPosition())) {
			skippedRenderEstimatesThisFrame++;
		}
	}

	public static int cachedEntries() {
		return entriesByPos.size();
	}

	public static long skippedRenderEstimatesThisFrame() {
		return skippedRenderEstimatesThisFrame;
	}

	public static void render(PoseStack poseStack, MultiBufferSource bufferSource, Camera camera) {
		if (!isEnabled() || poseStack == null || bufferSource == null || camera == null) return;
		debugWorldPassCalls++;
		if (entriesByPos.isEmpty()) {
			debugWorldPassEmpty++;
			OptiminiumMetrics.blockEntityLodCachedEntries(0);
			return;
		}
		Vec3 cameraPosition = camera.getPosition();
		cleanup(cameraPosition);
		if (entriesByPos.isEmpty()) {
			debugWorldPassEmpty++;
			OptiminiumMetrics.blockEntityLodCachedEntries(0);
			return;
		}

		int rendered = 0;
		VertexConsumer consumer = bufferSource.getBuffer(RENDER_TYPE);
		for (SectionBucket bucket : bucketsBySection.values()) {
			if (bucket.entries.isEmpty()) continue;
			for (Entry entry : bucket.entries) {
				if (!shouldRenderEntry(entry, cameraPosition)) continue;
				poseStack.pushPose();
				poseStack.translate(entry.x - cameraPosition.x, entry.y - cameraPosition.y, entry.z - cameraPosition.z);
				drawCube(consumer, poseStack, CUBE_HALF, entry.red, entry.green, entry.blue, CUBE_ALPHA);
				poseStack.popPose();
				rendered++;
			}
		}
		if (rendered > 0) {
			OptiminiumMetrics.blockEntityLodRendered(rendered);
		}
		debugWorldPassRendered += rendered;
		OptiminiumMetrics.blockEntityLodCachedEntries(entriesByPos.size());
	}

	public static RenderType renderType() {
		return RENDER_TYPE;
	}

	public static String[] debugLines() {
		return lastDebugSnapshot.lines();
	}

	public static String debugLine() {
		return lastDebugSnapshot.compactLine();
	}

	private static boolean isEnabled() {
		return OptiminiumSettings.isEnabled() && OptiminiumSettings.isBlockEntityLodCubesEnabled();
	}

	private static void observe(BlockEntity blockEntity, boolean activeRenderer) {
		if (!isEnabled() || blockEntity == null) return;
		int maxCachedEntries = OptiminiumSettings.getBlockEntityLodMaxCachedEntries();
		if (maxCachedEntries <= 0) return;
		debugObserved++;
		BlockPos pos = blockEntity.getBlockPos();
		long posKey = pos.asLong();
		if (activeRenderer) {
			activeThisFrame.add(posKey);
		}
		long sectionKey = sectionKey(pos);
		Entry entry = entriesByPos.get(posKey);
		if (entry == null) {
			trimToCapacity(maxCachedEntries - 1);
			entry = new Entry(posKey, sectionKey, pos, color(blockEntity));
			entriesByPos.put(posKey, entry);
			bucketsBySection.computeIfAbsent(sectionKey, SectionBucket::new).entries.add(entry);
			debugCreated++;
		} else {
			entry.color = color(blockEntity);
			entry.applyColor();
			debugUpdated++;
		}
		entry.lastSeenFrame = frameIndex;
	}

	private static boolean shouldRenderEntry(Entry entry, Vec3 cameraPosition) {
		debugRenderChecks++;
		if (entry == null || cameraPosition == null) {
			debugRejectedNull++;
			return false;
		}
		if (activeThisFrame.contains(entry.posKey)) {
			debugRejectedActive++;
			return false;
		}
		int staleFrames = Math.max(1, OptiminiumSettings.getBlockEntityLodStaleTimeoutFrames());
		if (frameIndex - entry.lastSeenFrame > staleFrames) {
			debugRejectedStale++;
			return false;
		}
		double distanceSqr = distanceSqr(entry, cameraPosition);
		double minDistance = OptiminiumSettings.getBlockEntityLodMinDistanceBlocks();
		double configuredMaxDistance = OptiminiumSettings.getBlockEntityLodMaxDistanceBlocks();
		double renderDistance = vanillaRenderDistanceBlocks();
		double unloadMargin = OptiminiumSettings.getBlockEntityLodUnloadMarginBlocks();
		double predictedUnloadDistance = predictedUnloadDistance(renderDistance, unloadMargin, minDistance);
		double maxDistance = maxLodDistance(configuredMaxDistance, renderDistance, unloadMargin, minDistance);
		if (distanceSqr < minDistance * minDistance) {
			debugRejectedTooNear++;
			return false;
		}
		if (distanceSqr > maxDistance * maxDistance) {
			debugRejectedTooFar++;
			return false;
		}
		if (isBlockEntityCurrentlyLoaded(entry)) {
			debugPredictedLoaded++;
		}
		debugEligibleChecks++;
		return true;
	}

	private static void cleanup(Vec3 cameraPosition) {
		int staleFrames = Math.max(1, OptiminiumSettings.getBlockEntityLodStaleTimeoutFrames());
		double renderDistance = maxLodDistance(
			OptiminiumSettings.getBlockEntityLodMaxDistanceBlocks(),
			vanillaRenderDistanceBlocks(),
			OptiminiumSettings.getBlockEntityLodUnloadMarginBlocks(),
			OptiminiumSettings.getBlockEntityLodMinDistanceBlocks()
		);
		ObjectArrayList<Entry> removals = new ObjectArrayList<>();
		for (Entry entry : entriesByPos.values()) {
			boolean stale = frameIndex - entry.lastSeenFrame > staleFrames;
			boolean outsideClientRange = cameraPosition != null && distanceSqr(entry, cameraPosition) > renderDistance * renderDistance;
			if (stale || outsideClientRange) {
				removals.add(entry);
			}
		}
		for (Entry entry : removals) {
			debugCleanupRemoved++;
			remove(entry);
		}
		trimToCapacity(OptiminiumSettings.getBlockEntityLodMaxCachedEntries());
	}

	private static void trimToCapacity(int capacity) {
		int maxEntries = Math.max(0, capacity);
		while (entriesByPos.size() > maxEntries) {
			Entry oldest = null;
			for (Entry entry : entriesByPos.values()) {
				if (oldest == null || entry.lastSeenFrame < oldest.lastSeenFrame) {
					oldest = entry;
				}
			}
			if (oldest == null) return;
			debugTrimRemoved++;
			remove(oldest);
		}
	}

	private static void publishDebugSnapshot() {
		double renderDistance = vanillaRenderDistanceBlocks();
		int unloadMargin = OptiminiumSettings.getBlockEntityLodUnloadMarginBlocks();
		int minDistance = OptiminiumSettings.getBlockEntityLodMinDistanceBlocks();
		int effectiveMaxDistance = (int)Math.round(maxLodDistance(
			OptiminiumSettings.getBlockEntityLodMaxDistanceBlocks(),
			renderDistance,
			unloadMargin,
			minDistance
		));
		lastDebugSnapshot = new DebugSnapshot(
			OptiminiumSettings.isEnabled(),
			OptiminiumSettings.isBlockEntityCulling(),
			OptiminiumSettings.isBlockEntityLodCubesEnabled(),
			OptiminiumSettings.isBlockEntityLodDebugEnabled(),
			entriesByPos.size(),
			activeThisFrame.size(),
			skippedThisFrame.size(),
			minDistance,
			effectiveMaxDistance,
			(int)Math.round(renderDistance),
			unloadMargin,
			(int)Math.round(predictedUnloadDistance(renderDistance, unloadMargin, minDistance)),
			OptiminiumSettings.getBlockEntityLodMaxCachedEntries(),
			OptiminiumSettings.getBlockEntityLodStaleTimeoutFrames(),
			debugObserved,
			debugCreated,
			debugUpdated,
			debugSkippedRecorded,
			debugRenderChecks,
			debugEligibleChecks,
			debugRejectedNull,
			debugRejectedActive,
			debugRejectedLoadedNotSkipped,
			debugPredictedLoaded,
			debugRejectedStale,
			debugRejectedTooNear,
			debugRejectedTooFar,
			debugWorldPassCalls,
			debugWorldPassRendered,
			debugWorldPassEmpty,
			debugCleanupRemoved,
			debugTrimRemoved
		);
		if (lastDebugSnapshot.debugEnabled() && frameIndex > 0 && frameIndex % 60 == 0) {
			OptiminiumMod.LOGGER.info("Optiminium block entity LOD debug: {}", lastDebugSnapshot.compactLine());
		}
	}

	private static void resetDebugCounters() {
		debugObserved = 0L;
		debugCreated = 0L;
		debugUpdated = 0L;
		debugSkippedRecorded = 0L;
		debugRenderChecks = 0L;
		debugEligibleChecks = 0L;
		debugRejectedNull = 0L;
		debugRejectedActive = 0L;
		debugRejectedLoadedNotSkipped = 0L;
		debugPredictedLoaded = 0L;
		debugRejectedStale = 0L;
		debugRejectedTooNear = 0L;
		debugRejectedTooFar = 0L;
		debugWorldPassCalls = 0L;
		debugWorldPassRendered = 0L;
		debugWorldPassEmpty = 0L;
		debugCleanupRemoved = 0L;
		debugTrimRemoved = 0L;
	}

	private static void remove(Entry entry) {
		entriesByPos.remove(entry.posKey);
		SectionBucket bucket = bucketsBySection.get(entry.sectionKey);
		if (bucket != null) {
			bucket.entries.remove(entry);
			if (bucket.entries.isEmpty()) {
				bucketsBySection.remove(entry.sectionKey);
			}
		}
		activeThisFrame.remove(entry.posKey);
		skippedThisFrame.remove(entry.posKey);
	}

	private static double distanceSqr(Entry entry, Vec3 cameraPosition) {
		double dx = entry.x - cameraPosition.x;
		double dy = entry.y - cameraPosition.y;
		double dz = entry.z - cameraPosition.z;
		return dx * dx + dy * dy + dz * dz;
	}

	private static double vanillaRenderDistanceBlocks() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.options == null) return 0.0D;
		return Math.max(0, minecraft.options.renderDistance().get()) * 16.0D;
	}

	private static double predictedUnloadDistance(double renderDistance, double unloadMargin, double minDistance) {
		if (renderDistance <= 0.0D) return minDistance;
		return Math.max(minDistance, renderDistance - Math.max(0.0D, unloadMargin));
	}

	private static double maxLodDistance(double configuredMaxDistance, double renderDistance, double unloadMargin, double minDistance) {
		double retainedEdge = renderDistance <= 0.0D ? configuredMaxDistance : renderDistance + Math.max(0.0D, unloadMargin);
		return Math.max(minDistance, Math.max(configuredMaxDistance, retainedEdge));
	}

	private static boolean isBlockEntityCurrentlyLoaded(Entry entry) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.level == null || entry == null) return false;
		BlockPos pos = BlockPos.containing(entry.x, entry.y, entry.z);
		return minecraft.level.getBlockEntity(pos) != null;
	}

	private static Vec3 currentCameraPosition() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.gameRenderer == null || minecraft.gameRenderer.getMainCamera() == null) return null;
		return minecraft.gameRenderer.getMainCamera().getPosition();
	}

	private static long sectionKey(BlockPos pos) {
		return SectionPos.asLong(
			SectionPos.blockToSectionCoord(pos.getX()),
			SectionPos.blockToSectionCoord(pos.getY()),
			SectionPos.blockToSectionCoord(pos.getZ())
		);
	}

	private static int color(BlockEntity blockEntity) {
		if (blockEntity instanceof ShulkerBoxBlockEntity shulker) {
			return blockColor(shulker.getBlockState(), 0xFF885088);
		}
		BlockEntityType<?> type = blockEntity.getType();
		if (type == BlockEntityType.CHEST || type == BlockEntityType.TRAPPED_CHEST) return 0xFFC49A5E;
		if (type == BlockEntityType.ENDER_CHEST) return 0xFF1A2B3C;
		if (type == BlockEntityType.BARREL) return 0xFFA08060;
		if (type == BlockEntityType.FURNACE || type == BlockEntityType.BLAST_FURNACE || type == BlockEntityType.SMOKER) return 0xFF7A7A7A;
		if (type == BlockEntityType.SIGN || type == BlockEntityType.HANGING_SIGN || type == BlockEntityType.LECTERN) return 0xFFB89060;
		if (type == BlockEntityType.BEACON) return 0xFF55CCEE;
		if (type == BlockEntityType.BELL) return 0xFFDDAA33;
		if (type == BlockEntityType.CONDUIT) return 0xFF229999;
		if (type == BlockEntityType.BED) return 0xFFCC4444;
		if (type == BlockEntityType.END_PORTAL || type == BlockEntityType.END_GATEWAY) return 0xFF0A001A;
		if (type == BlockEntityType.MOB_SPAWNER || type == BlockEntityType.TRIAL_SPAWNER || type == BlockEntityType.VAULT) return 0xFF384438;
		return blockColor(blockEntity.getBlockState(), DEFAULT_COLOR);
	}

	private static int blockColor(BlockState state, int fallback) {
		if (state == null || state.getBlock() == Blocks.AIR) return fallback;
		try {
			MapColor mapColor = state.getMapColor(null, null);
			if (mapColor != null && mapColor != MapColor.NONE) {
				return mapColor.col | 0xFF000000;
			}
		} catch (RuntimeException ignored) {
		}
		Block block = state.getBlock();
		return block == Blocks.AIR ? fallback : DEFAULT_COLOR;
	}

	private static void drawCube(VertexConsumer consumer, PoseStack poseStack, float h, float r, float g, float b, float a) {
		vertex(consumer, poseStack, -h, -h, -h, r, g, b, a);
		vertex(consumer, poseStack, -h, -h, h, r, g, b, a);
		vertex(consumer, poseStack, h, -h, h, r, g, b, a);
		vertex(consumer, poseStack, h, -h, -h, r, g, b, a);
		vertex(consumer, poseStack, -h, h, -h, r, g, b, a);
		vertex(consumer, poseStack, h, h, -h, r, g, b, a);
		vertex(consumer, poseStack, h, h, h, r, g, b, a);
		vertex(consumer, poseStack, -h, h, h, r, g, b, a);
		vertex(consumer, poseStack, -h, -h, -h, r, g, b, a);
		vertex(consumer, poseStack, h, -h, -h, r, g, b, a);
		vertex(consumer, poseStack, h, h, -h, r, g, b, a);
		vertex(consumer, poseStack, -h, h, -h, r, g, b, a);
		vertex(consumer, poseStack, -h, -h, h, r, g, b, a);
		vertex(consumer, poseStack, -h, h, h, r, g, b, a);
		vertex(consumer, poseStack, h, h, h, r, g, b, a);
		vertex(consumer, poseStack, h, -h, h, r, g, b, a);
		vertex(consumer, poseStack, -h, -h, -h, r, g, b, a);
		vertex(consumer, poseStack, -h, h, -h, r, g, b, a);
		vertex(consumer, poseStack, -h, h, h, r, g, b, a);
		vertex(consumer, poseStack, -h, -h, h, r, g, b, a);
		vertex(consumer, poseStack, h, -h, -h, r, g, b, a);
		vertex(consumer, poseStack, h, -h, h, r, g, b, a);
		vertex(consumer, poseStack, h, h, h, r, g, b, a);
		vertex(consumer, poseStack, h, h, -h, r, g, b, a);
	}

	private static void vertex(VertexConsumer consumer, PoseStack poseStack, float x, float y, float z, float r, float g, float b, float a) {
		consumer.addVertex(poseStack.last().pose(), x, y, z).setColor(r, g, b, a).setLight(PACKED_LIGHT);
	}

	private static final class SectionBucket {
		final long key;
		final ObjectArrayList<Entry> entries = new ObjectArrayList<>();

		SectionBucket(long key) {
			this.key = key;
		}
	}

	private static final class Entry {
		final long posKey;
		final long sectionKey;
		final double x;
		final double y;
		final double z;
		int color;
		int lastSeenFrame;
		float red;
		float green;
		float blue;

		Entry(long posKey, long sectionKey, BlockPos pos, int color) {
			this.posKey = posKey;
			this.sectionKey = sectionKey;
			this.x = pos.getX() + 0.5D;
			this.y = pos.getY() + 0.5D;
			this.z = pos.getZ() + 0.5D;
			this.color = color;
			applyColor();
		}

		void applyColor() {
			red = ((color >> 16) & 0xFF) / 255.0F;
			green = ((color >> 8) & 0xFF) / 255.0F;
			blue = (color & 0xFF) / 255.0F;
		}
	}

	public record DebugSnapshot(
		boolean optiminiumEnabled,
		boolean cullingEnabled,
		boolean cubesEnabled,
		boolean debugEnabled,
		int cachedEntries,
		int activeEntries,
		int skippedEntries,
		int minDistance,
		int maxDistance,
		int vanillaRenderDistance,
		int unloadMargin,
		int predictedUnloadDistance,
		int maxCachedEntries,
		int staleTimeoutFrames,
		long observed,
		long created,
		long updated,
		long skippedRecorded,
		long renderChecks,
		long eligibleChecks,
		long rejectedNull,
		long rejectedActive,
		long rejectedLoadedNotSkipped,
		long predictedLoaded,
		long rejectedStale,
		long rejectedTooNear,
		long rejectedTooFar,
		long worldPassCalls,
		long worldPassRendered,
		long worldPassEmpty,
		long cleanupRemoved,
		long trimRemoved
	) {
		private static final DebugSnapshot EMPTY = new DebugSnapshot(false, false, false, false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);

		String[] lines() {
			return new String[] {
				"Optiminium BE LOD Debug",
				"enabled=" + optiminiumEnabled + " culling=" + cullingEnabled + " cubes=" + cubesEnabled,
				"cache=" + cachedEntries + "/" + maxCachedEntries + " observed=" + observed + " new=" + created + " updated=" + updated,
				"skipped=" + skippedEntries + " skippedCalls=" + skippedRecorded + " active=" + activeEntries,
				"draw world=" + worldPassRendered + "/" + worldPassCalls + " empty=" + worldPassEmpty,
				"checks=" + renderChecks + " eligible=" + eligibleChecks + " range=" + minDistance + "-" + maxDistance + " vanilla=" + vanillaRenderDistance,
				"predict=" + predictedUnloadDistance + " margin=" + unloadMargin,
				"reject active=" + rejectedActive + " liveTooEarly=" + rejectedLoadedNotSkipped + " liveLod=" + predictedLoaded,
				"reject near=" + rejectedTooNear + " far=" + rejectedTooFar,
				"reject stale=" + rejectedStale + " null=" + rejectedNull + " removed=" + cleanupRemoved + "+" + trimRemoved
			};
		}

		String compactLine() {
			return "enabled=" + optiminiumEnabled
				+ ", culling=" + cullingEnabled
				+ ", cubes=" + cubesEnabled
				+ ", cache=" + cachedEntries + "/" + maxCachedEntries
				+ ", observed=" + observed
				+ ", skipped=" + skippedEntries + "/" + skippedRecorded
				+ ", active=" + activeEntries
				+ ", drawWorld=" + worldPassRendered + "/" + worldPassCalls
				+ ", checks=" + renderChecks
				+ ", eligible=" + eligibleChecks
				+ ", rejectActive=" + rejectedActive
				+ ", rejectLiveTooEarly=" + rejectedLoadedNotSkipped
				+ ", liveLod=" + predictedLoaded
				+ ", rejectNear=" + rejectedTooNear
				+ ", rejectFar=" + rejectedTooFar
				+ ", rejectStale=" + rejectedStale
				+ ", range=" + minDistance + "-" + maxDistance
				+ ", vanillaRange=" + vanillaRenderDistance
				+ ", predictedUnloadDistance=" + predictedUnloadDistance
				+ ", unloadMargin=" + unloadMargin;
		}
	}
}
