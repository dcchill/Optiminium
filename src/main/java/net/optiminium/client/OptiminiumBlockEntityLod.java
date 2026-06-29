package net.optiminium.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
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
import net.optiminium.mixin.FrustumAccessor;
import net.optiminium.optimization.OptiminiumMetrics;
import net.optiminium.optimization.OptiminiumSettings;
import org.joml.Matrix4f;

public final class OptiminiumBlockEntityLod {
	private static final float CUBE_HALF = 0.56F;
	private static final float CUBE_ALPHA = 0.90F;
	private static final int PACKED_LIGHT = 0xF000F0;
	private static final int DEFAULT_COLOR = 0xFFA09080;
	private static final int MESH_STRIDE = 6;
	private static final int MIN_BUCKET_ENTRIES_TO_DRAW = 2;
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
	private static final ObjectArrayList<Entry> allEntries = new ObjectArrayList<>();
	private static final LongOpenHashSet activeThisFrame = new LongOpenHashSet();
	private static final LongOpenHashSet skippedThisFrame = new LongOpenHashSet();
	private static final LongOpenHashSet visibleSectionsThisFrame = new LongOpenHashSet();
	private static int frameIndex;
	private static boolean hasVisibleSectionData;
	private static Object currentLevel;
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
	private static long debugSectionFrustumCulled;
	private static long debugSectionOcclusionCulled;
	private static long debugEntryFrustumCulled;
	private static long debugVisibilityRetested;
	private static long debugCandidates;
	private static long debugDrawBatches;
	private static long debugCacheHits;
	private static long debugCacheRefreshes;
	private static long debugEvictions;
	private static long debugBucketsConsidered;
	private static long debugBucketsFrustumCulled;
	private static long debugBucketsOcclusionCulled;
	private static long debugBucketsRebuilt;
	private static long debugWorldPassCalls;
	private static long debugWorldPassRendered;
	private static long debugWorldPassEmpty;
	private static long debugCleanupRemoved;
	private static long debugStaleRemoved;
	private static long debugSourceInvalidRemoved;
	private static long debugTrimRemoved;

	private OptiminiumBlockEntityLod() {
	}

	public static void beginFrame() {
		Minecraft minecraft = Minecraft.getInstance();
		Object level = minecraft == null ? null : minecraft.level;
		if (level != currentLevel) {
			clearCache();
			currentLevel = level;
		}
		publishDebugSnapshot();
		frameIndex++;
		activeThisFrame.clear();
		skippedThisFrame.clear();
		visibleSectionsThisFrame.clear();
		for (SectionBucket bucket : bucketsBySection.values()) {
			bucket.dirty = true;
		}
		hasVisibleSectionData = false;
		skippedRenderEstimatesThisFrame = 0L;
		resetDebugCounters();
	}

	public static void beginVisibleSectionFrame() {
		visibleSectionsThisFrame.clear();
		hasVisibleSectionData = true;
	}

	public static void recordVisibleSection(BlockPos origin) {
		if (origin == null) return;
		visibleSectionsThisFrame.add(sectionKey(origin));
	}

	public static void observe(BlockEntity blockEntity) {
		observe(blockEntity, false, -1);
	}

	public static void observe(BlockEntity blockEntity, int sourceViewDistance) {
		observe(blockEntity, false, sourceViewDistance);
	}

	public static void recordRendered(BlockEntity blockEntity) {
		observe(blockEntity, true, -1);
	}

	public static void recordRendered(BlockEntity blockEntity, int sourceViewDistance) {
		observe(blockEntity, true, sourceViewDistance);
	}

	public static void recordSkippedRender(BlockEntity blockEntity) {
		recordSkippedRender(blockEntity, -1);
	}

	public static void recordSkippedRender(BlockEntity blockEntity, int sourceViewDistance) {
		if (!isEnabled() || blockEntity == null) return;
		observe(blockEntity, false, sourceViewDistance);
		debugSkippedRecorded++;
		long posKey = blockEntity.getBlockPos().asLong();
		if (skippedThisFrame.add(posKey)) {
			skippedRenderEstimatesThisFrame++;
		}
	}

	public static int cachedEntries() {
		return entriesByPos.size();
	}

	public static long skippedRenderEstimatesThisFrame() {
		return skippedRenderEstimatesThisFrame;
	}

	public static boolean render(PoseStack poseStack, MultiBufferSource bufferSource, Camera camera, Frustum frustum) {
		if (!isEnabled() || poseStack == null || bufferSource == null || camera == null) return false;
		debugWorldPassCalls++;
		if (entriesByPos.isEmpty()) {
			debugWorldPassEmpty++;
			OptiminiumMetrics.blockEntityLodCachedEntries(0);
			return false;
		}
		Vec3 cameraPosition = camera.getPosition();
		cleanup();
		if (entriesByPos.isEmpty()) {
			debugWorldPassEmpty++;
			OptiminiumMetrics.blockEntityLodCachedEntries(0);
			return false;
		}

		int rendered = 0;
		VertexConsumer consumer = null;
		Matrix4f pose = poseStack.last().pose();
		if (hasVisibleSectionData) {
			LongIterator iterator = visibleSectionsThisFrame.iterator();
			while (iterator.hasNext()) {
				SectionBucket bucket = bucketsBySection.get(iterator.nextLong());
				if (bucket == null || !prepareBucket(bucket, cameraPosition, frustum)) continue;
				if (consumer == null) consumer = bufferSource.getBuffer(RENDER_TYPE);
				drawBucketMesh(consumer, pose, bucket, cameraPosition);
				debugDrawBatches++;
				rendered += bucket.cachedEntryCount;
			}
		} else {
			for (SectionBucket bucket : bucketsBySection.values()) {
				if (!prepareBucket(bucket, cameraPosition, frustum)) continue;
				if (consumer == null) consumer = bufferSource.getBuffer(RENDER_TYPE);
				drawBucketMesh(consumer, pose, bucket, cameraPosition);
				debugDrawBatches++;
				rendered += bucket.cachedEntryCount;
			}
		}
		if (rendered > 0) {
			OptiminiumMetrics.blockEntityLodRendered(rendered);
		}
		debugWorldPassRendered += rendered;
		OptiminiumMetrics.blockEntityLodCachedEntries(entriesByPos.size());
		return rendered > 0;
	}

	public static RenderType renderType() {
		return RENDER_TYPE;
	}

	public static String debugLine() {
		return lastDebugSnapshot.compactLine();
	}

	private static boolean isEnabled() {
		return OptiminiumSettings.isEnabled() && OptiminiumSettings.isBlockEntityLodCubesEnabled();
	}

	private static void observe(BlockEntity blockEntity, boolean activeRenderer, int sourceViewDistance) {
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
		double lodMinDistance = lodMinDistance(sourceViewDistance);
		if (entry == null) {
			trimToCapacity(maxCachedEntries - 1);
			entry = new Entry(posKey, sectionKey, pos, color(blockEntity), lodMinDistance);
			entriesByPos.put(posKey, entry);
			allEntries.add(entry);
			SectionBucket bucket = bucketsBySection.computeIfAbsent(sectionKey, SectionBucket::new);
			bucket.entries.add(entry);
			refreshBucketLodMinDistance(bucket);
			bucket.dirty = true;
			debugCreated++;
		} else {
			int color = color(blockEntity);
			SectionBucket bucket = bucketsBySection.get(entry.sectionKey);
			if (entry.color != color) {
				entry.color = color;
				entry.applyColor();
				if (bucket != null) bucket.dirty = true;
				debugCacheRefreshes++;
			} else {
				debugCacheHits++;
			}
			if (entry.lodMinDistance != lodMinDistance) {
				entry.lodMinDistance = lodMinDistance;
				if (bucket != null) refreshBucketLodMinDistance(bucket);
			}
			debugUpdated++;
		}
		entry.lastSeenFrame = frameIndex;
		entry.lastObservedFrame = frameIndex;
		entry.lastObservedNanos = System.nanoTime();
	}

	private static void cleanup() {
		// ponytail: persistent in-world cache; validate removals only if ghost LODs beat FPS wins.
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
			debugEvictions++;
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
			debugSectionFrustumCulled,
			debugSectionOcclusionCulled,
			debugEntryFrustumCulled,
			debugVisibilityRetested,
			debugCandidates,
			debugDrawBatches,
			debugCacheHits,
			debugCacheRefreshes,
			debugEvictions,
			debugBucketsConsidered,
			debugBucketsFrustumCulled,
			debugBucketsOcclusionCulled,
			debugBucketsRebuilt,
			debugWorldPassCalls,
			debugWorldPassRendered,
			debugWorldPassEmpty,
			debugCleanupRemoved,
			debugStaleRemoved,
			debugSourceInvalidRemoved,
			debugTrimRemoved
		);
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
		debugSectionFrustumCulled = 0L;
		debugSectionOcclusionCulled = 0L;
		debugEntryFrustumCulled = 0L;
		debugVisibilityRetested = 0L;
		debugCandidates = 0L;
		debugDrawBatches = 0L;
		debugCacheHits = 0L;
		debugCacheRefreshes = 0L;
		debugEvictions = 0L;
		debugBucketsConsidered = 0L;
		debugBucketsFrustumCulled = 0L;
		debugBucketsOcclusionCulled = 0L;
		debugBucketsRebuilt = 0L;
		debugWorldPassCalls = 0L;
		debugWorldPassRendered = 0L;
		debugWorldPassEmpty = 0L;
		debugCleanupRemoved = 0L;
		debugStaleRemoved = 0L;
		debugSourceInvalidRemoved = 0L;
		debugTrimRemoved = 0L;
	}

	private static void remove(Entry entry) {
		entriesByPos.remove(entry.posKey);
		allEntries.remove(entry);
		SectionBucket bucket = bucketsBySection.get(entry.sectionKey);
		if (bucket != null) {
			bucket.entries.remove(entry);
			if (bucket.entries.isEmpty()) {
				bucketsBySection.remove(entry.sectionKey);
			} else {
				refreshBucketLodMinDistance(bucket);
				bucket.dirty = true;
			}
		}
		activeThisFrame.remove(entry.posKey);
		skippedThisFrame.remove(entry.posKey);
	}

	private static void clearCache() {
		entriesByPos.clear();
		bucketsBySection.clear();
		allEntries.clear();
		activeThisFrame.clear();
		skippedThisFrame.clear();
		visibleSectionsThisFrame.clear();
		hasVisibleSectionData = false;
	}

	private static double lodMinDistance(int sourceViewDistance) {
		double configuredMinDistance = OptiminiumSettings.getBlockEntityLodMinDistanceBlocks();
		if (sourceViewDistance <= 0) return configuredMinDistance;
		return Math.max(configuredMinDistance, sourceViewDistance + 1.0D);
	}

	private static boolean shouldRenderBucket(SectionBucket bucket, Vec3 cameraPosition, int candidateCount) {
		debugRenderChecks += candidateCount;
		if (cameraPosition == null) {
			debugRejectedNull += candidateCount;
			return false;
		}
		double distanceSqr = sectionDistanceSqr(bucket, cameraPosition);
		double minDistance = Math.max(OptiminiumSettings.getBlockEntityLodMinDistanceBlocks(), bucket.lodMinDistance);
		double maxDistance = maxLodDistance(
			OptiminiumSettings.getBlockEntityLodMaxDistanceBlocks(),
			vanillaRenderDistanceBlocks(),
			OptiminiumSettings.getBlockEntityLodUnloadMarginBlocks(),
			minDistance
		);
		if (distanceSqr < minDistance * minDistance) {
			debugRejectedTooNear += candidateCount;
			return false;
		}
		if (distanceSqr > maxDistance * maxDistance) {
			debugRejectedTooFar += candidateCount;
			return false;
		}
		debugEligibleChecks += candidateCount;
		return true;
	}

	private static boolean prepareBucket(SectionBucket bucket, Vec3 cameraPosition, Frustum frustum) {
		if (bucket.entries.isEmpty()) return false;
		debugBucketsConsidered++;
		int bucketCandidates = skippedEntryCount(bucket);
		debugCandidates += bucketCandidates;
		if (bucketCandidates < MIN_BUCKET_ENTRIES_TO_DRAW) return false;
		if (!shouldRenderBucket(bucket, cameraPosition, bucketCandidates)) return false;
		debugVisibilityRetested++;
		if (!isSectionVisibleThisFrame(bucket, frustum)) {
			debugSectionFrustumCulled += bucketCandidates;
			debugBucketsFrustumCulled++;
			return false;
		}
		if (isSectionOccludedThisFrame(bucket)) {
			debugSectionOcclusionCulled += bucketCandidates;
			debugBucketsOcclusionCulled++;
			return false;
		}
		if (bucket.dirty) {
			rebuildMesh(bucket);
			debugBucketsRebuilt++;
		}
		return bucket.cachedEntryCount > 0 && bucket.meshLength > 0;
	}

	private static void refreshBucketLodMinDistance(SectionBucket bucket) {
		double lodMinDistance = OptiminiumSettings.getBlockEntityLodMinDistanceBlocks();
		for (Entry entry : bucket.entries) {
			lodMinDistance = Math.max(lodMinDistance, entry.lodMinDistance);
		}
		bucket.lodMinDistance = lodMinDistance;
	}

	private static int skippedEntryCount(SectionBucket bucket) {
		int count = 0;
		for (Entry entry : bucket.entries) {
			if (activeThisFrame.contains(entry.posKey)) {
				debugRejectedActive++;
			} else if (skippedThisFrame.contains(entry.posKey)) {
				count++;
			} else {
				debugRejectedLoadedNotSkipped++;
			}
		}
		return count;
	}

	private static double sectionDistanceSqr(SectionBucket bucket, Vec3 cameraPosition) {
		double dx = axisDistance(cameraPosition.x, bucket.originX, bucket.originX + 16.0D);
		double dy = axisDistance(cameraPosition.y, bucket.originY, bucket.originY + 16.0D);
		double dz = axisDistance(cameraPosition.z, bucket.originZ, bucket.originZ + 16.0D);
		return dx * dx + dy * dy + dz * dz;
	}

	private static double axisDistance(double value, double min, double max) {
		if (value < min) return min - value;
		if (value > max) return value - max;
		return 0.0D;
	}

	private static boolean isSectionVisibleThisFrame(SectionBucket bucket, Frustum frustum) {
		if (frustum == null) return true;
		return ((FrustumAccessor)frustum).optiminium$cubeInFrustum(bucket.minX, bucket.minY, bucket.minZ, bucket.maxX, bucket.maxY, bucket.maxZ);
	}

	private static boolean isSectionOccludedThisFrame(SectionBucket bucket) {
		return hasVisibleSectionData && !visibleSectionsThisFrame.contains(bucket.key);
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
		if (renderDistance <= 0.0D) return Math.max(minDistance, configuredMaxDistance);
		return Math.max(minDistance, Math.min(configuredMaxDistance, renderDistance));
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

	private static void rebuildMesh(SectionBucket bucket) {
		int required = bucket.entries.size() * 24 * MESH_STRIDE;
		if (bucket.mesh.length < required) {
			bucket.mesh = new float[required];
		}
		refreshBucketLodMinDistance(bucket);
		int offset = 0;
		int cachedEntryCount = 0;
		for (Entry entry : bucket.entries) {
			if (activeThisFrame.contains(entry.posKey) || !skippedThisFrame.contains(entry.posKey)) {
				continue;
			}
			float x = (float)(entry.x - bucket.originX);
			float y = (float)(entry.y - bucket.originY);
			float z = (float)(entry.z - bucket.originZ);
			offset = appendCube(bucket.mesh, offset, x, y, z, CUBE_HALF, entry.red, entry.green, entry.blue);
			cachedEntryCount++;
		}
		bucket.meshLength = offset;
		bucket.cachedEntryCount = cachedEntryCount;
		bucket.dirty = false;
	}

	private static int appendCube(float[] mesh, int offset, float cx, float cy, float cz, float h, float r, float g, float b) {
		offset = appendFace(mesh, offset, cx - h, cy + h, cz - h, cx + h, cy + h, cz - h, cx + h, cy + h, cz + h, cx - h, cy + h, cz + h, r, g, b, 1.00F);
		offset = appendFace(mesh, offset, cx - h, cy - h, cz - h, cx - h, cy - h, cz + h, cx + h, cy - h, cz + h, cx + h, cy - h, cz - h, r, g, b, 0.50F);
		offset = appendFace(mesh, offset, cx - h, cy - h, cz - h, cx + h, cy - h, cz - h, cx + h, cy + h, cz - h, cx - h, cy + h, cz - h, r, g, b, 0.68F);
		offset = appendFace(mesh, offset, cx - h, cy - h, cz + h, cx - h, cy + h, cz + h, cx + h, cy + h, cz + h, cx + h, cy - h, cz + h, r, g, b, 0.82F);
		offset = appendFace(mesh, offset, cx - h, cy - h, cz - h, cx - h, cy + h, cz - h, cx - h, cy + h, cz + h, cx - h, cy - h, cz + h, r, g, b, 0.74F);
		return appendFace(mesh, offset, cx + h, cy - h, cz - h, cx + h, cy - h, cz + h, cx + h, cy + h, cz + h, cx + h, cy + h, cz - h, r, g, b, 0.90F);
	}

	private static int appendFace(float[] mesh, int offset,
			float x1, float y1, float z1, float x2, float y2, float z2,
			float x3, float y3, float z3, float x4, float y4, float z4,
			float r, float g, float b, float shade) {
		r *= shade;
		g *= shade;
		b *= shade;
		offset = appendMeshVertex(mesh, offset, x1, y1, z1, r, g, b);
		offset = appendMeshVertex(mesh, offset, x2, y2, z2, r, g, b);
		offset = appendMeshVertex(mesh, offset, x3, y3, z3, r, g, b);
		return appendMeshVertex(mesh, offset, x4, y4, z4, r, g, b);
	}

	private static int appendMeshVertex(float[] mesh, int offset, float x, float y, float z, float r, float g, float b) {
		mesh[offset++] = x;
		mesh[offset++] = y;
		mesh[offset++] = z;
		mesh[offset++] = r;
		mesh[offset++] = g;
		mesh[offset++] = b;
		return offset;
	}

	private static void drawBucketMesh(VertexConsumer consumer, Matrix4f pose, SectionBucket bucket, Vec3 cameraPosition) {
		float baseX = (float)(bucket.originX - cameraPosition.x);
		float baseY = (float)(bucket.originY - cameraPosition.y);
		float baseZ = (float)(bucket.originZ - cameraPosition.z);
		float[] mesh = bucket.mesh;
		for (int i = 0; i < bucket.meshLength; i += MESH_STRIDE) {
			consumer.addVertex(pose, baseX + mesh[i], baseY + mesh[i + 1], baseZ + mesh[i + 2])
				.setColor(mesh[i + 3], mesh[i + 4], mesh[i + 5], CUBE_ALPHA)
				.setLight(PACKED_LIGHT);
		}
	}

	private static final class SectionBucket {
		final long key;
		final ObjectArrayList<Entry> entries = new ObjectArrayList<>();
		final int originX;
		final int originY;
		final int originZ;
		final double minX;
		final double minY;
		final double minZ;
		final double maxX;
		final double maxY;
		final double maxZ;
		boolean dirty = true;
		float[] mesh = new float[0];
		int meshLength;
		int cachedEntryCount;
		double lodMinDistance;

		SectionBucket(long key) {
			this.key = key;
			this.originX = SectionPos.x(key) << 4;
			this.originY = SectionPos.y(key) << 4;
			this.originZ = SectionPos.z(key) << 4;
			this.minX = originX;
			this.minY = originY;
			this.minZ = originZ;
			this.maxX = originX + 16.0D;
			this.maxY = originY + 16.0D;
			this.maxZ = originZ + 16.0D;
		}
	}

	private static final class Entry {
		final long posKey;
		final long sectionKey;
		final double x;
		final double y;
		final double z;
		final double minX;
		final double minY;
		final double minZ;
		final double maxX;
		final double maxY;
		final double maxZ;
		int color;
		int lastSeenFrame;
		int lastObservedFrame;
		long lastObservedNanos;
		double lodMinDistance;
		float red;
		float green;
		float blue;

		Entry(long posKey, long sectionKey, BlockPos pos, int color, double lodMinDistance) {
			this.posKey = posKey;
			this.sectionKey = sectionKey;
			this.x = pos.getX() + 0.5D;
			this.y = pos.getY() + 0.5D;
			this.z = pos.getZ() + 0.5D;
			this.minX = x - CUBE_HALF;
			this.minY = y - CUBE_HALF;
			this.minZ = z - CUBE_HALF;
			this.maxX = x + CUBE_HALF;
			this.maxY = y + CUBE_HALF;
			this.maxZ = z + CUBE_HALF;
			this.color = color;
			this.lodMinDistance = lodMinDistance;
			this.lastObservedFrame = frameIndex;
			this.lastObservedNanos = System.nanoTime();
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
		long sectionFrustumCulled,
		long sectionOcclusionCulled,
		long entryFrustumCulled,
		long visibilityRetested,
		long candidates,
		long drawBatches,
		long cacheHits,
		long cacheRefreshes,
		long cacheEvictions,
		long bucketsConsidered,
		long bucketsFrustumCulled,
		long bucketsOcclusionCulled,
		long bucketsRebuilt,
		long worldPassCalls,
		long worldPassRendered,
		long worldPassEmpty,
		long cleanupRemoved,
		long staleRemoved,
		long sourceInvalidRemoved,
		long trimRemoved
	) {
		private static final DebugSnapshot EMPTY = new DebugSnapshot(false, false, false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);

		String compactLine() {
			return "enabled=" + optiminiumEnabled
				+ ", culling=" + cullingEnabled
				+ ", cubes=" + cubesEnabled
				+ ", cache=" + cachedEntries + "/" + maxCachedEntries
				+ ", observed=" + observed
				+ ", cacheHits=" + cacheHits
				+ ", cacheRefreshes=" + cacheRefreshes
				+ ", cacheEvictions=" + cacheEvictions
				+ ", skipped=" + skippedEntries + "/" + skippedRecorded
				+ ", active=" + activeEntries
				+ ", drawWorld=" + worldPassRendered + "/" + worldPassCalls
				+ ", lodDrawBatches=" + drawBatches
				+ ", bucketsConsidered=" + bucketsConsidered
				+ ", bucketsFrustumCulled=" + bucketsFrustumCulled
				+ ", bucketsOcclusionCulled=" + bucketsOcclusionCulled
				+ ", bucketsRebuilt=" + bucketsRebuilt
				+ ", checks=" + renderChecks
				+ ", candidates=" + candidates
				+ ", eligible=" + eligibleChecks
				+ ", visibilityRetested=" + visibilityRetested
				+ ", sectionFrustumCulled=" + sectionFrustumCulled
				+ ", sectionOcclusionCulled=" + sectionOcclusionCulled
				+ ", entryFrustumCulled=" + entryFrustumCulled
				+ ", rejectActive=" + rejectedActive
				+ ", rejectLiveTooEarly=" + rejectedLoadedNotSkipped
				+ ", liveLod=" + predictedLoaded
				+ ", rejectNear=" + rejectedTooNear
				+ ", rejectFar=" + rejectedTooFar
				+ ", rejectStale=" + rejectedStale
				+ ", staleRemoved=" + staleRemoved
				+ ", sourceInvalidRemoved=" + sourceInvalidRemoved
				+ ", range=" + minDistance + "-" + maxDistance
				+ ", sourceMin=perBucket"
				+ ", vanillaRange=" + vanillaRenderDistance
				+ ", predictedUnloadDistance=" + predictedUnloadDistance
				+ ", unloadMargin=" + unloadMargin;
		}
	}
}
