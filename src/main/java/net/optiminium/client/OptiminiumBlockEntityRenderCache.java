package net.optiminium.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.LidBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.optiminium.optimization.OptiminiumSettings;

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Predicate;

public final class OptiminiumBlockEntityRenderCache {
	// --- Cache data structures ---
	private static final LinkedHashMap<Key, Entry> CACHE = new LinkedHashMap<>(256, 0.75F, true);
	private static final Object CACHE_LOCK = new Object();

	// --- Frame counter (incremented by onFrameStart) ---
	private static long currentFrame;

	// --- Core statistics ---
	private static final LongAdder hookCalls = new LongAdder();
	private static final LongAdder lookupAttempts = new LongAdder();
	private static final LongAdder hits = new LongAdder();
	private static final LongAdder misses = new LongAdder();
	private static final LongAdder invalidations = new LongAdder();
	private static final LongAdder fallbacks = new LongAdder();
	private static final LongAdder entriesStored = new LongAdder();
	private static final LongAdder rejectedRenderer = new LongAdder();
	private static final LongAdder rejectedType = new LongAdder();
	private static final LongAdder rejectedState = new LongAdder();
	private static final LongAdder rejectedAnimated = new LongAdder();
	private static final LongAdder rejectedInvalidType = new LongAdder();
	private static final LongAdder disabled = new LongAdder();
	private static final LongAdder buildNanos = new LongAdder();
	private static final LongAdder estimatedSavedNanos = new LongAdder();

	// --- Invalidation reason counters ---
	private static final LongAdder invalBlockStateChanged = new LongAdder();
	private static final LongAdder invalLightChanged = new LongAdder();
	private static final LongAdder invalOverlayChanged = new LongAdder();
	private static final LongAdder invalBEDirty = new LongAdder();
	private static final LongAdder invalRendererChanged = new LongAdder();
	private static final LongAdder invalResourceReload = new LongAdder();
	private static final LongAdder invalChunkUnload = new LongAdder();
	private static final LongAdder invalAnimationState = new LongAdder();
	private static final LongAdder invalDistanceCamera = new LongAdder();
	private static final LongAdder invalEvictedLRU = new LongAdder();
	private static final LongAdder invalUnknown = new LongAdder();

	// --- Cache entry lifetime and reuse tracking ---
	private static final LongAdder totalEntryLifetimeFrames = new LongAdder();
	private static final LongAdder totalReuses = new LongAdder();
	private static final LongAdder totalInvalidatedEntries = new LongAdder();

	// --- Per-type stat tracking ---
	private static final ConcurrentHashMap<BlockEntityType<?>, TypeStats> typeStatsMap = new ConcurrentHashMap<>();

	// --- Unstable entry detection ---
	// If an entry is invalidated within UNSTABLE_FRAME_THRESHOLD frames of creation,
	// that block entity type is marked unstable and bypassed for a cooldown.
	private static final int UNSTABLE_FRAME_THRESHOLD = 3;
	private static final int UNSTABLE_COOLDOWN_FRAMES = 200;
	// Tracks frame number when each type was last found unstable
	private static final ConcurrentHashMap<BlockEntityType<?>, Long> unstableTypeCooldown = new ConcurrentHashMap<>();

	private static long lastDebugNanos;
	private static int visibleThisFrame;
	private static int lastVisibleBlockEntities;
	private static int rebuildsThisFrame;
	private static int lastRebuildsThisFrame;
	private static long savedThisFrameNanos;
	private static long lastSavedFrameNanos;

	// Per-frame tracked invalidation reason (top reason string, updated each frame)
	private static String topInvalidationReason = "none";

	private OptiminiumBlockEntityRenderCache() {
	}

	public static boolean shouldRender(ClientLevel level, BlockEntity blockEntity, int viewDistance) {
		OptiminiumGpuOptimizer.recordRawVisibleBlockEntityBeforeCulling(blockEntity);
		return vanillaDecision(blockEntity, viewDistance);
	}

	public static void recordDispatcherHook(BlockEntity blockEntity, Object renderer) {
		hookCalls.increment();
		visibleThisFrame++;
		if (!OptiminiumSettings.isEnabled() || !OptiminiumSettings.isBlockEntityRenderCache()) {
			disabled.increment();
			return;
		}
		if (renderer == null) {
			fallback("no_renderer");
			rejectedRenderer.increment();
			return;
		}
		Level level = blockEntity.getLevel();
		if (level == null) {
			fallback("no_level");
			rejectedState.increment();
			return;
		}
		if (!blockEntity.getType().isValid(blockEntity.getBlockState())) {
			fallback("invalid_type");
			rejectedInvalidType.increment();
			return;
		}
	}

	/**
	 * Main cache lookup for packed light color.
	 * Unlike the original implementation, this computes the light value ONLY on cache miss,
	 * and the cache key does NOT include packedLight, so light changes between frames
	 * still result in a cache hit for stable block entities.
	 */
	public static int lightFor(BlockAndTintGetter levelView, BlockPos pos, BlockEntityRenderer<?> renderer,
			BlockEntity blockEntity, float partialTick, int packedOverlay) {
		// Fast path: if caching is disabled, compute and return immediately
		if (!OptiminiumSettings.isEnabled() || !OptiminiumSettings.isBlockEntityRenderCache()) {
			disabled.increment();
			return LevelRenderer.getLightColor(levelView, pos);
		}
		if (renderer == null) {
			fallback("no_renderer");
			rejectedRenderer.increment();
			return LevelRenderer.getLightColor(levelView, pos);
		}
		if (blockEntity.getLevel() == null) {
			fallback("no_level");
			rejectedState.increment();
			return LevelRenderer.getLightColor(levelView, pos);
		}
		if (!blockEntity.getType().isValid(blockEntity.getBlockState())) {
			fallback("invalid_type");
			rejectedInvalidType.increment();
			return LevelRenderer.getLightColor(levelView, pos);
		}

		BlockEntityType<?> type = blockEntity.getType();

		// Check if this type is on unstable cooldown — bypass caching entirely
		if (isTypeUnstable(type)) {
			fallback("unstable_type");
			return LevelRenderer.getLightColor(levelView, pos);
		}

		// Reject animated or unsuitable types
		String rejectionReason = rejectionReason(blockEntity, partialTick, true);
		if (rejectionReason != null) {
			fallback(rejectionReason);
			return LevelRenderer.getLightColor(levelView, pos);
		}

		lookupAttempts.increment();
		BlockState state = blockEntity.getBlockState();
		Key key = new Key(
			blockEntity.getLevel().dimension(),
			blockEntity.getBlockPos().asLong(),
			type,
			Block.getId(state),
			renderer.getClass()
		);

		synchronized (CACHE_LOCK) {
			Entry entry = CACHE.get(key);
			if (entry != null && entry.matches(blockEntity, state)) {
				// Cache HIT — return cached light without computing it
				hits.increment();
				entry.recordReuse(currentFrame);
				long savedNanos = averageBuildNanos();
				estimatedSavedNanos.add(savedNanos);
				savedThisFrameNanos += savedNanos;
				TypeStats ts = getOrCreateTypeStats(type);
				ts.hits.increment();
				return entry.packedLight;
			}
		}

		// Cache MISS — compute light, store entry
		long start = System.nanoTime();
		int packedLight = LevelRenderer.getLightColor(levelView, pos);
		buildNanos.add(System.nanoTime() - start);
		misses.increment();
		rebuildsThisFrame++;

		Entry entry = new Entry(blockEntity, state, packedLight, currentFrame);
		put(key, entry, type);

		TypeStats ts = getOrCreateTypeStats(type);
		ts.misses.increment();

		return packedLight;
	}

	public static void invalidatePosition(ResourceKey<Level> dimension, BlockPos pos) {
		long posKey = pos.asLong();
		removeIf(key -> {
			if (key.dimension.equals(dimension) && key.pos == posKey) {
				trackInvalidationReason(key, INVALIDATION_BLOCK_STATE_CHANGED);
				return true;
			}
			return false;
		});
	}

	public static void invalidatePosition(ResourceKey<Level> dimension, BlockPos pos, String reason) {
		long posKey = pos.asLong();
		removeIf(key -> {
			if (key.dimension.equals(dimension) && key.pos == posKey) {
				trackInvalidationReason(key, reason != null ? reason : INVALIDATION_UNKNOWN);
				return true;
			}
			return false;
		});
	}

	public static void invalidateSection(ResourceKey<Level> dimension, int sectionX, int sectionY, int sectionZ) {
		removeIf(key -> {
			if (key.dimension.equals(dimension)
					&& SectionPos.blockToSectionCoord(BlockPos.getX(key.pos)) == sectionX
					&& SectionPos.blockToSectionCoord(BlockPos.getY(key.pos)) == sectionY
					&& SectionPos.blockToSectionCoord(BlockPos.getZ(key.pos)) == sectionZ) {
				trackInvalidationReason(key, INVALIDATION_CHUNK_UNLOAD);
				return true;
			}
			return false;
		});
	}

	public static void invalidateChunk(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
		removeIf(key -> {
			if (key.dimension.equals(dimension)
					&& SectionPos.blockToSectionCoord(BlockPos.getX(key.pos)) == chunkX
					&& SectionPos.blockToSectionCoord(BlockPos.getZ(key.pos)) == chunkZ) {
				trackInvalidationReason(key, INVALIDATION_CHUNK_UNLOAD);
				return true;
			}
			return false;
		});
	}

	public static void clear() {
		synchronized (CACHE_LOCK) {
			int size = CACHE.size();
			if (size > 0) {
				invalResourceReload.add(size);
				recordInvalidationBatch(size);
				invalidations.add(size);
				CACHE.clear();
			}
		}
	}

	public static void onFrameStart() {
		lastVisibleBlockEntities = visibleThisFrame;
		lastRebuildsThisFrame = rebuildsThisFrame;
		lastSavedFrameNanos = savedThisFrameNanos;
		visibleThisFrame = 0;
		rebuildsThisFrame = 0;
		savedThisFrameNanos = 0L;
		currentFrame++;

		// Decay unstable type cooldowns
		if ((currentFrame & 0xF) == 0) { // every 16 frames
			decayUnstableCooldowns();
		}

		// Update top invalidation reason for diagnostic output
		updateTopInvalidationReason();
	}

	// --- Diagnostic output ---

	public static String diagnosticLine() {
		Snapshot snapshot = snapshot();
		return String.format(Locale.ROOT,
			"blockEntityRenderCache=%s, beHookCalls=%d, beLookupAttempts=%d, beCacheSize=%d, beCacheHitRate=%.1f%%, beCacheHits=%d, beCacheMisses=%d, beCacheInvalidations=%d, beCacheFallbacks=%d, beRejectedRenderer=%d, beRejectedType=%d, beRejectedState=%d, beRejectedAnimated=%d, beRejectedInvalidType=%d, beCacheStored=%d, beCacheSavedMs=%.3f, beAvgLifetimeFrames=%.1f, beAvgReuses=%.1f, beTopInvalidation=%s, beUnstableTypes=%d",
			OptiminiumSettings.isBlockEntityRenderCache() ? "on" : "off",
			snapshot.hookCalls(), snapshot.lookupAttempts(), snapshot.cachedEntries(), snapshot.hitRate(),
			snapshot.hits(), snapshot.misses(), snapshot.invalidations(), snapshot.fallbacks(),
			snapshot.rejectedRenderer(), snapshot.rejectedType(), snapshot.rejectedState(),
			snapshot.rejectedAnimated(), snapshot.rejectedInvalidType(), snapshot.entriesStored(),
			estimatedSavedNanos.sum() / 1_000_000.0D,
			snapshot.avgEntryLifetimeFrames(),
			snapshot.avgReuses(),
			snapshot.topInvalidationReason(),
			unstableTypeCooldown.size()
		);
	}

	public static Snapshot snapshot() {
		long hitCount = hits.sum();
		long missCount = misses.sum();
		long total = hitCount + missCount;
		double hitRate = total == 0L ? 0.0D : hitCount * 100.0D / total;
		int cachedEntries = size();
		long invalCount = invalidations.sum();
		long invalEntryCount = totalInvalidatedEntries.sum();
		double avgLifetime = invalEntryCount == 0L ? 0.0D : totalEntryLifetimeFrames.sum() / (double) invalEntryCount;
		double avgReuses = invalEntryCount == 0L ? 0.0D : totalReuses.sum() / (double) invalEntryCount;

		return new Snapshot(
			cachedEntries,
			lastVisibleBlockEntities,
			hookCalls.sum(),
			lookupAttempts.sum(),
			hitCount,
			missCount,
			hitRate,
			lastRebuildsThisFrame,
			invalCount,
			fallbacks.sum(),
			entriesStored.sum(),
			rejectedRenderer.sum(),
			rejectedType.sum(),
			rejectedState.sum(),
			rejectedAnimated.sum(),
			rejectedInvalidType.sum(),
			disabled.sum(),
			lastSavedFrameNanos / 1_000_000.0D,
			cachedEntries * 6L * 1024L,
			avgLifetime,
			avgReuses,
			topInvalidationReason,
			unstableTypeCooldown.size(),
			collectTypeStats()
		);
	}

	// --- Private helpers ---

	private static boolean vanillaDecision(BlockEntity blockEntity, int viewDistance) {
		boolean render = OptiminiumGpuOptimizer.shouldRenderBlockEntity(blockEntity, viewDistance);
		if (render) {
			OptiminiumGpuOptimizer.recordRenderedBlockEntityAfterCulling();
		}
		return render;
	}

	private static void put(Key key, Entry entry, BlockEntityType<?> type) {
		synchronized (CACHE_LOCK) {
			CACHE.put(key, entry);
			entriesStored.increment();
			int max = OptiminiumSettings.getBlockEntityRenderCacheMaxEntries();
			while (CACHE.size() > max && !CACHE.isEmpty()) {
				Iterator<Key> iterator = CACHE.keySet().iterator();
				Key evictedKey = iterator.next();
				// Record eviction reason
				trackInvalidationReason(evictedKey, INVALIDATION_EVICTED_LRU);
				iterator.remove();
				invalidations.increment();
			}
		}
	}

	private static String rejectionReason(BlockEntity blockEntity, float partialTick, boolean count) {
		BlockState state = blockEntity.getBlockState();
		BlockEntityType<?> type = blockEntity.getType();

		if (blockEntity.isRemoved() || state.isRandomlyTicking()) {
			if (count) rejectedState.increment();
			return "state";
		}
		if (type == BlockEntityType.BEACON || type == BlockEntityType.CONDUIT
				|| type == BlockEntityType.END_PORTAL || type == BlockEntityType.END_GATEWAY
				|| type == BlockEntityType.MOB_SPAWNER || type == BlockEntityType.TRIAL_SPAWNER
				|| type == BlockEntityType.VAULT || type == BlockEntityType.PISTON
				|| state.getLightEmission() > 0) {
			if (count) rejectedType.increment();
			return "type";
		}
		// Only cache chest-like block entities when they are fully closed
		if (type == BlockEntityType.CHEST || type == BlockEntityType.TRAPPED_CHEST
				|| type == BlockEntityType.ENDER_CHEST || type == BlockEntityType.SHULKER_BOX) {
			if (!(blockEntity instanceof LidBlockEntity lid)) {
				if (count) rejectedAnimated.increment();
				return "animated";
			}
			// getOpenNess with partialTick interpolates — use 1.0F (full tick) for stability
			if (lid.getOpenNess(1.0F) != 0.0F) {
				if (count) rejectedAnimated.increment();
				return "animated";
			}
		}
		// Skip modded block entities that don't look static
		ResourceLocation id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
		if (id != null && !"minecraft".equals(id.getNamespace()) && !looksStaticModded(id)) {
			if (count) rejectedType.increment();
			return "modded_type";
		}
		return null;
	}

	private static boolean looksStaticModded(ResourceLocation id) {
		String path = id.getPath().toLowerCase(Locale.ROOT);
		return path.contains("chest") || path.contains("barrel") || path.contains("sign")
			|| path.contains("banner") || path.contains("skull") || path.contains("bed")
			|| path.contains("shelf") || path.contains("pot") || path.contains("crate")
			|| path.contains("decor") || path.contains("furnace") || path.contains("smoker")
			|| path.contains("blast");
	}

	private static int size() {
		synchronized (CACHE_LOCK) {
			return CACHE.size();
		}
	}

	private static long averageBuildNanos() {
		long missCount = misses.sum();
		return missCount <= 1L ? 0L : buildNanos.sum() / missCount;
	}

	private static void removeIf(Predicate<Key> predicate) {
		synchronized (CACHE_LOCK) {
			Iterator<Map.Entry<Key, Entry>> iterator = CACHE.entrySet().iterator();
			while (iterator.hasNext()) {
				Map.Entry<Key, Entry> mapEntry = iterator.next();
				Key key = mapEntry.getKey();
				Entry entry = mapEntry.getValue();
				if (predicate.test(key)) {
					// Record lifetime and reuse data before removal
					recordEntryLifetime(entry);
					iterator.remove();
					invalidations.increment();
				}
			}
		}
	}

	private static void fallback(String reason) {
		fallbacks.increment();
	}

	// --- Entry lifetime tracking ---

	private static void recordEntryLifetime(Entry entry) {
		if (entry == null) return;
		long lifetimeFrames = currentFrame - entry.birthFrame;
		if (lifetimeFrames >= 0) {
			totalEntryLifetimeFrames.add(lifetimeFrames);
			totalReuses.add(entry.reuseCount);
			totalInvalidatedEntries.increment();
		}
		// Check for unstable entry (invalidated within UNSTABLE_FRAME_THRESHOLD frames)
		if (lifetimeFrames >= 0 && lifetimeFrames <= UNSTABLE_FRAME_THRESHOLD && entry.reuseCount <= 1) {
			// This entry was created and quickly invalidated — mark type as unstable
			markTypeUnstable(entry.getType());
		}
	}

	private static void recordInvalidationBatch(int count) {
		// For batch operations like clear(), record approximate per-entry data
		// by using average estimates
		long avgLifetime = totalInvalidatedEntries.sum() == 0L ? 0L
			: totalEntryLifetimeFrames.sum() / Math.max(1, totalInvalidatedEntries.sum());
		long avgReuses = totalInvalidatedEntries.sum() == 0L ? 0L
			: totalReuses.sum() / Math.max(1, totalInvalidatedEntries.sum());
		totalEntryLifetimeFrames.add(avgLifetime * count);
		totalReuses.add(avgReuses * count);
		totalInvalidatedEntries.add(count);
	}

	// --- Invalidation reason tracking ---

	private static final String INVALIDATION_BLOCK_STATE_CHANGED = "block_state_changed";
	private static final String INVALIDATION_LIGHT_CHANGED = "light_changed";
	private static final String INVALIDATION_OVERLAY_CHANGED = "overlay_changed";
	private static final String INVALIDATION_BE_DIRTY = "be_dirty";
	private static final String INVALIDATION_RENDERER_CHANGED = "renderer_changed";
	private static final String INVALIDATION_RESOURCE_RELOAD = "resource_reload";
	private static final String INVALIDATION_CHUNK_UNLOAD = "chunk_unload";
	private static final String INVALIDATION_ANIMATION_STATE = "animation_state_changed";
	private static final String INVALIDATION_DISTANCE_CAMERA = "distance_camera_changed";
	private static final String INVALIDATION_EVICTED_LRU = "evicted_lru";
	private static final String INVALIDATION_UNKNOWN = "unknown";

	private static void trackInvalidationReason(Key key, String reason) {
		switch (reason) {
			case INVALIDATION_BLOCK_STATE_CHANGED -> invalBlockStateChanged.increment();
			case INVALIDATION_LIGHT_CHANGED -> invalLightChanged.increment();
			case INVALIDATION_OVERLAY_CHANGED -> invalOverlayChanged.increment();
			case INVALIDATION_BE_DIRTY -> invalBEDirty.increment();
			case INVALIDATION_RENDERER_CHANGED -> invalRendererChanged.increment();
			case INVALIDATION_RESOURCE_RELOAD -> invalResourceReload.increment();
			case INVALIDATION_CHUNK_UNLOAD -> invalChunkUnload.increment();
			case INVALIDATION_ANIMATION_STATE -> invalAnimationState.increment();
			case INVALIDATION_DISTANCE_CAMERA -> invalDistanceCamera.increment();
			case INVALIDATION_EVICTED_LRU -> invalEvictedLRU.increment();
			default -> invalUnknown.increment();
		}
	}

	private static void updateTopInvalidationReason() {
		topInvalidationReason = findTopInvalidationReason();
	}

	private static String findTopInvalidationReason() {
		long[] values = {
			invalBlockStateChanged.sum(),
			invalLightChanged.sum(),
			invalOverlayChanged.sum(),
			invalBEDirty.sum(),
			invalRendererChanged.sum(),
			invalResourceReload.sum(),
			invalChunkUnload.sum(),
			invalAnimationState.sum(),
			invalDistanceCamera.sum(),
			invalEvictedLRU.sum(),
			invalUnknown.sum()
		};
		String[] names = {
			INVALIDATION_BLOCK_STATE_CHANGED,
			INVALIDATION_LIGHT_CHANGED,
			INVALIDATION_OVERLAY_CHANGED,
			INVALIDATION_BE_DIRTY,
			INVALIDATION_RENDERER_CHANGED,
			INVALIDATION_RESOURCE_RELOAD,
			INVALIDATION_CHUNK_UNLOAD,
			INVALIDATION_ANIMATION_STATE,
			INVALIDATION_DISTANCE_CAMERA,
			INVALIDATION_EVICTED_LRU,
			INVALIDATION_UNKNOWN
		};
		long max = 0L;
		int maxIdx = -1;
		for (int i = 0; i < values.length; i++) {
			if (values[i] > max) {
				max = values[i];
				maxIdx = i;
			}
		}
		if (maxIdx >= 0 && max > 0L) {
			return names[maxIdx] + ":" + max;
		}
		return "none";
	}

	// --- Per-type statistics ---

	private static TypeStats getOrCreateTypeStats(BlockEntityType<?> type) {
		return typeStatsMap.computeIfAbsent(type, k -> new TypeStats());
	}

	private static String collectTypeStats() {
		if (typeStatsMap.isEmpty()) {
			return "none";
		}
		StringBuilder sb = new StringBuilder();
		// Sort by total accesses (hits+misses) descending, take top 8
		typeStatsMap.entrySet().stream()
			.sorted((a, b) -> Long.compare(
				b.getValue().hits.sum() + b.getValue().misses.sum(),
				a.getValue().hits.sum() + a.getValue().misses.sum()))
			.limit(8)
			.forEach(entry -> {
				ResourceLocation id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entry.getKey());
				String name = id != null ? id.toString() : "unknown";
				TypeStats stats = entry.getValue();
				long total = stats.hits.sum() + stats.misses.sum();
				double rate = total <= 0L ? 0.0D : stats.hits.sum() * 100.0D / total;
				if (sb.length() > 0) sb.append(" ");
				sb.append(name).append(":h=").append(stats.hits.sum())
					.append("/m=").append(stats.misses.sum())
					.append("/r=").append(String.format(Locale.ROOT, "%.0f%%", rate));
			});
		return sb.toString();
	}

	// --- Unstable type detection ---

	private static boolean isTypeUnstable(BlockEntityType<?> type) {
		Long cooldownUntil = unstableTypeCooldown.get(type);
		if (cooldownUntil == null) return false;
		if (currentFrame >= cooldownUntil) {
			unstableTypeCooldown.remove(type);
			return false;
		}
		return true;
	}

	private static void markTypeUnstable(BlockEntityType<?> type) {
		unstableTypeCooldown.put(type, currentFrame + UNSTABLE_COOLDOWN_FRAMES);
	}

	private static void decayUnstableCooldowns() {
		unstableTypeCooldown.entrySet().removeIf(entry ->
			currentFrame >= entry.getValue());
	}

	// --- Debug logging ---

	private static void debugLog() {
		if (!OptiminiumSettings.isBlockEntityRenderCacheDebug()) {
			return;
		}
		long now = System.nanoTime();
		if (now - lastDebugNanos < 1_000_000_000L) {
			return;
		}
		lastDebugNanos = now;
		// Use a proper logger
		System.out.println("[Optiminium] " + diagnosticLine());
	}

	// --- Key (without packedLight/packedOverlay) ---

	private record Key(ResourceKey<Level> dimension, long pos, BlockEntityType<?> type,
			int stateId, Class<?> rendererClass) {
	}

	// --- Entry (mutable for reuse tracking) ---

	private static class Entry {
		final WeakReference<BlockEntity> blockEntity;
		BlockState state;
		int packedLight;
		long birthFrame;
		long lastAccessFrame;
		int reuseCount;
		// Store type for unstable detection on eviction
		private BlockEntityType<?> type;

		Entry(BlockEntity blockEntity, BlockState state, int packedLight, long birthFrame) {
			this.blockEntity = new WeakReference<>(blockEntity);
			this.state = state;
			this.packedLight = packedLight;
			this.birthFrame = birthFrame;
			this.lastAccessFrame = birthFrame;
			this.reuseCount = 0;
			this.type = blockEntity.getType();
		}

		boolean matches(BlockEntity current, BlockState currentState) {
			BlockEntity cached = blockEntity.get();
			return cached == current && !current.isRemoved() && currentState == state;
		}

		void recordReuse(long frame) {
			this.reuseCount++;
			this.lastAccessFrame = frame;
		}

		BlockEntityType<?> getType() {
			return type;
		}
	}

	// --- Per-type stats holder ---

	private static class TypeStats {
		final LongAdder hits = new LongAdder();
		final LongAdder misses = new LongAdder();
	}

	// --- Snapshot record ---

	public record Snapshot(
		int cachedEntries,
		int visibleBlockEntities,
		long hookCalls,
		long lookupAttempts,
		long hits,
		long misses,
		double hitRate,
		int rebuildsThisFrame,
		long invalidations,
		long fallbacks,
		long entriesStored,
		long rejectedRenderer,
		long rejectedType,
		long rejectedState,
		long rejectedAnimated,
		long rejectedInvalidType,
		long disabled,
		double cpuSavedMsPerFrame,
		long memoryBytes,
		double avgEntryLifetimeFrames,
		double avgReuses,
		String topInvalidationReason,
		int unstableTypeCount,
		String perTypeStats
	) {
	}
}
