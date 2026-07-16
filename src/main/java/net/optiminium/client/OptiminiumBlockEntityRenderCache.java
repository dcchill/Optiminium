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
import net.minecraft.world.level.block.state.BlockState;
import net.optiminium.optimization.OptiminiumSettings;

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Predicate;

public final class OptiminiumBlockEntityRenderCache {
	// --- Cache data structures ---
	private static final ConcurrentHashMap<Key, Entry> CACHE = new ConcurrentHashMap<>(256);
	private static final ThreadLocal<Key> PROBE_KEY = ThreadLocal.withInitial(Key::new);

	// --- Frame counter (incremented by onFrameStart) ---
	private static long currentFrame;

	// --- Core statistics ---
	private static long hookCalls;
	private static long lookupAttempts;
	private static long hits;
	private static long misses;
	private static final LongAdder invalidations = new LongAdder();
	private static long fallbacks;
	private static final LongAdder entriesStored = new LongAdder();
	private static long rejectedRenderer;
	private static long rejectedType;
	private static long rejectedState;
	private static long rejectedAnimated;
	private static long rejectedInvalidType;
	private static long disabled;
	private static long buildNanos;
	private static long estimatedSavedNanos;
	private static boolean cacheActiveThisFrame;
	private static boolean hookMetricsThisFrame;
	private static long hookSampleNanos;
	private static long hookSamples;
	private static long lookupSampleNanos;
	private static long lookupSamples;
	private static int timingSampleCounter;
	private static long costBypasses;

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
	private static long averageBuildNanos;
	private static long buildSamples;

	// Per-frame tracked invalidation reason (top reason string, updated each frame)
	private static String topInvalidationReason = "none";
	private static boolean topInvalidationDirty;

	private OptiminiumBlockEntityRenderCache() {
	}

	public static boolean shouldRender(ClientLevel level, BlockEntity blockEntity, int viewDistance) {
		OptiminiumGpuOptimizer.recordRawVisibleBlockEntityBeforeCulling(blockEntity);
		return vanillaDecision(blockEntity, viewDistance);
	}

	public static void recordDispatcherHook(BlockEntity blockEntity) {
		if (!hookMetricsThisFrame) return;
		long start = (++timingSampleCounter & 63) == 0 ? System.nanoTime() : 0L;
		hookCalls++;
		visibleThisFrame++;
		if (start != 0L) { hookSampleNanos += System.nanoTime() - start; hookSamples++; }
	}

	public static boolean isDispatcherHookActive() {
		return hookMetricsThisFrame;
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
		if (!cacheActiveThisFrame) {
			disabled++;
			return LevelRenderer.getLightColor(levelView, pos);
		}
		if (renderer == null) {
			fallback("no_renderer");
			rejectedRenderer++;
			return LevelRenderer.getLightColor(levelView, pos);
		}
		Level level = blockEntity.getLevel();
		if (level == null) {
			fallback("no_level");
			rejectedState++;
			return LevelRenderer.getLightColor(levelView, pos);
		}
		BlockState state = blockEntity.getBlockState();
		BlockEntityType<?> type = blockEntity.getType();
		if (!type.isValid(state)) {
			fallback("invalid_type");
			rejectedInvalidType++;
			return LevelRenderer.getLightColor(levelView, pos);
		}

		// Check if this type is on unstable cooldown — bypass caching entirely
		if (isTypeUnstable(type)) {
			fallback("unstable_type");
			return LevelRenderer.getLightColor(levelView, pos);
		}

		// Reject animated or unsuitable types
		String rejectionReason = rejectionReason(blockEntity, state, type, true);
		if (rejectionReason != null) {
			fallback(rejectionReason);
			return LevelRenderer.getLightColor(levelView, pos);
		}
		TypeStats ts = getOrCreateTypeStats(type);
		if (ts.shouldBypass()) {
			costBypasses++;
			return LevelRenderer.getLightColor(levelView, pos);
		}

		long lookupStart = (++timingSampleCounter & 63) == 0 ? System.nanoTime() : 0L;
		lookupAttempts++;
		Key probe = PROBE_KEY.get();
		probe.set(
			level.dimension(),
			pos.asLong(),
			type,
			Block.getId(state),
			renderer.getClass()
		);

		Entry cachedEntry = CACHE.get(probe);
		if (lookupStart != 0L) {
			long lookupNanos = System.nanoTime() - lookupStart;
			lookupSampleNanos += lookupNanos; lookupSamples++;
			ts.lookupSampleNanos += lookupNanos; ts.lookupSamples++;
		}
		if (cachedEntry != null && cachedEntry.matches(blockEntity, state)) {
				// Cache HIT — return cached light without computing it
				hits++;
				cachedEntry.recordReuse(currentFrame);
				long savedNanos = averageBuildNanos;
				estimatedSavedNanos += savedNanos;
				savedThisFrameNanos += savedNanos;
				cachedEntry.typeStats.hits++;
			return cachedEntry.packedLight;
		}

		// Cache MISS — compute light, store entry
		long start = System.nanoTime();
		int packedLight = LevelRenderer.getLightColor(levelView, pos);
		long elapsedNanos = System.nanoTime() - start;
		buildNanos += elapsedNanos;
		misses++;
		rebuildsThisFrame++;

		ts.misses++;
		ts.buildNanos += elapsedNanos;
		updateAverageBuildNanos(elapsedNanos);

		Entry entry = new Entry(blockEntity, state, packedLight, currentFrame, ts);
		put(probe.copy(), entry, type);

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
		int size = CACHE.size();
		if (size > 0) {
			invalResourceReload.add(size);
			topInvalidationDirty = true;
			recordInvalidationBatch(size);
			invalidations.add(size);
			CACHE.clear();
		}
	}

	public static void resetForBenchmark() {
		CACHE.clear();
		hookCalls = 0L;
		lookupAttempts = 0L;
		hits = 0L;
		misses = 0L;
		invalidations.reset();
		fallbacks = 0L;
		entriesStored.reset();
		rejectedRenderer = 0L;
		rejectedType = 0L;
		rejectedState = 0L;
		rejectedAnimated = 0L;
		rejectedInvalidType = 0L;
		disabled = 0L;
		buildNanos = 0L;
		estimatedSavedNanos = 0L;
		invalBlockStateChanged.reset();
		invalLightChanged.reset();
		invalOverlayChanged.reset();
		invalBEDirty.reset();
		invalRendererChanged.reset();
		invalResourceReload.reset();
		invalChunkUnload.reset();
		invalAnimationState.reset();
		invalDistanceCamera.reset();
		invalEvictedLRU.reset();
		invalUnknown.reset();
		totalEntryLifetimeFrames.reset();
		totalReuses.reset();
		totalInvalidatedEntries.reset();
		typeStatsMap.clear();
		unstableTypeCooldown.clear();
		visibleThisFrame = 0;
		lastVisibleBlockEntities = 0;
		rebuildsThisFrame = 0;
		lastRebuildsThisFrame = 0;
		savedThisFrameNanos = 0L;
		lastSavedFrameNanos = 0L;
		averageBuildNanos = 0L;
		buildSamples = 0L;
		topInvalidationReason = "none";
		topInvalidationDirty = false;
		hookSampleNanos = 0L; hookSamples = 0L; lookupSampleNanos = 0L; lookupSamples = 0L; timingSampleCounter = 0;
		costBypasses = 0L;
	}

	public static void onFrameStart() {
		cacheActiveThisFrame = OptiminiumSettings.isEnabled() && OptiminiumSettings.isBlockEntityRenderCache();
		hookMetricsThisFrame = cacheActiveThisFrame && OptiminiumRenderProfiler.isEnabled();
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

	}

	// --- Diagnostic output ---

	public static String diagnosticLine() {
		Snapshot snapshot = snapshot();
		return String.format(Locale.ROOT,
			"blockEntityRenderCache=%s, beHookCalls=%d, beLookupAttempts=%d, beCacheSize=%d, beCacheHitRate=%.1f%%, beCacheHits=%d, beCacheMisses=%d, beCacheInvalidations=%d, beCacheFallbacks=%d, beRejectedRenderer=%d, beRejectedType=%d, beRejectedState=%d, beRejectedAnimated=%d, beRejectedInvalidType=%d, beCacheStored=%d, beCacheSavedMs=%.3f, beAvgLifetimeFrames=%.1f, beAvgReuses=%.1f, beTopInvalidation=%s, beUnstableTypes=%d, beHookSampleMs=%.6f, beLookupSampleMs=%.6f, beCostBypasses=%d, beTypeCosts=%s",
			OptiminiumSettings.isBlockEntityRenderCache() ? "on" : "off",
			snapshot.hookCalls(), snapshot.lookupAttempts(), snapshot.cachedEntries(), snapshot.hitRate(),
			snapshot.hits(), snapshot.misses(), snapshot.invalidations(), snapshot.fallbacks(),
			snapshot.rejectedRenderer(), snapshot.rejectedType(), snapshot.rejectedState(),
			snapshot.rejectedAnimated(), snapshot.rejectedInvalidType(), snapshot.entriesStored(),
			estimatedSavedNanos / 1_000_000.0D,
			snapshot.avgEntryLifetimeFrames(),
			snapshot.avgReuses(),
			snapshot.topInvalidationReason(),
			unstableTypeCooldown.size(),
			hookSamples == 0L ? 0.0D : hookSampleNanos / (double)hookSamples / 1_000_000.0D,
			lookupSamples == 0L ? 0.0D : lookupSampleNanos / (double)lookupSamples / 1_000_000.0D,
			costBypasses, collectTypeStats()
		);
	}

	public static Snapshot snapshot() {
		updateTopInvalidationReasonIfNeeded();
		long hitCount = hits;
		long missCount = misses;
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
			hookCalls,
			lookupAttempts,
			hitCount,
			missCount,
			hitRate,
			lastRebuildsThisFrame,
			invalCount,
			fallbacks,
			entriesStored.sum(),
			rejectedRenderer,
			rejectedType,
			rejectedState,
			rejectedAnimated,
			rejectedInvalidType,
			disabled,
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
		OptiminiumGpuOptimizer.recordRenderedBlockEntityAfterCulling();
		return true;
	}

	private static void put(Key key, Entry entry, BlockEntityType<?> type) {
		CACHE.put(key, entry);
		entriesStored.increment();
		int max = OptiminiumSettings.getBlockEntityRenderCacheMaxEntries();
		while (CACHE.size() > max && !CACHE.isEmpty()) {
			Iterator<Key> iterator = CACHE.keySet().iterator();
			if (!iterator.hasNext()) {
				return;
			}
			Key evictedKey = iterator.next();
			Entry removed = CACHE.remove(evictedKey);
			if (removed != null) {
				// Record eviction reason
				trackInvalidationReason(evictedKey, INVALIDATION_EVICTED_LRU);
				recordEntryLifetime(removed);
				invalidations.increment();
			}
		}
	}

	private static String rejectionReason(BlockEntity blockEntity, BlockState state, BlockEntityType<?> type, boolean count) {
		if (blockEntity.isRemoved()) {
			if (count) rejectedState++;
			return "state";
		}
		// Renderer animation and modded payloads do not affect the position-based light lookup.
		return null;
	}

	private static int size() {
		return CACHE.size();
	}

	private static void updateAverageBuildNanos(long elapsedNanos) {
		if (elapsedNanos <= 0L) {
			return;
		}
		if (buildSamples++ == 0L) {
			averageBuildNanos = elapsedNanos;
			return;
		}
		averageBuildNanos = (long)(averageBuildNanos * 0.875D + elapsedNanos * 0.125D);
	}

	private static void removeIf(Predicate<Key> predicate) {
		for (Map.Entry<Key, Entry> mapEntry : CACHE.entrySet()) {
			Key key = mapEntry.getKey();
			Entry entry = mapEntry.getValue();
			if (predicate.test(key) && CACHE.remove(key, entry)) {
				// Record lifetime and reuse data before removal
				recordEntryLifetime(entry);
				invalidations.increment();
			}
		}
	}

	private static void fallback(String reason) {
		fallbacks++;
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
		topInvalidationDirty = true;
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

	private static void updateTopInvalidationReasonIfNeeded() {
		if (!topInvalidationDirty) {
			return;
		}
		topInvalidationReason = findTopInvalidationReason();
		topInvalidationDirty = false;
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
				b.getValue().hits + b.getValue().misses,
				a.getValue().hits + a.getValue().misses))
			.limit(8)
			.forEach(entry -> {
				ResourceLocation id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entry.getKey());
				String name = id != null ? id.toString() : "unknown";
				TypeStats stats = entry.getValue();
				long total = stats.hits + stats.misses;
				double rate = total <= 0L ? 0.0D : stats.hits * 100.0D / total;
				if (sb.length() > 0) sb.append(" ");
				sb.append(name).append(":h=").append(stats.hits)
					.append("/m=").append(stats.misses)
					.append("/r=").append(String.format(Locale.ROOT, "%.0f%%", rate))
					.append("/lookupNs=").append(stats.lookupSamples == 0L ? 0L : stats.lookupSampleNanos / stats.lookupSamples)
					.append("/buildNs=").append(stats.misses == 0L ? 0L : stats.buildNanos / stats.misses)
					.append("/bypass=").append(stats.shouldBypass());
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

	private static final class Key {
		ResourceKey<Level> dimension;
		long pos;
		BlockEntityType<?> type;
		int stateId;
		Class<?> rendererClass;
		int hash;

		Key() {
		}

		Key(ResourceKey<Level> dimension, long pos, BlockEntityType<?> type,
			int stateId, Class<?> rendererClass) {
			set(dimension, pos, type, stateId, rendererClass);
		}

		void set(ResourceKey<Level> dimension, long pos, BlockEntityType<?> type,
			int stateId, Class<?> rendererClass) {
			this.dimension = dimension;
			this.pos = pos;
			this.type = type;
			this.stateId = stateId;
			this.rendererClass = rendererClass;
			this.hash = computeHashCode();
		}

		Key copy() {
			return new Key(dimension, pos, type, stateId, rendererClass);
		}

		private int computeHashCode() {
			int result = dimension.hashCode();
			result = 31 * result + Long.hashCode(pos);
			result = 31 * result + type.hashCode();
			result = 31 * result + stateId;
			result = 31 * result + rendererClass.hashCode();
			return result;
		}

		@Override
		public int hashCode() {
			return hash;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) return true;
			if (!(obj instanceof Key other)) return false;
			return pos == other.pos
				&& dimension.equals(other.dimension)
				&& type == other.type
				&& stateId == other.stateId
				&& rendererClass == other.rendererClass;
		}
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
		private final BlockEntityType<?> type;
		private final TypeStats typeStats;

		Entry(BlockEntity blockEntity, BlockState state, int packedLight, long birthFrame, TypeStats typeStats) {
			this.blockEntity = new WeakReference<>(blockEntity);
			this.state = state;
			this.packedLight = packedLight;
			this.birthFrame = birthFrame;
			this.lastAccessFrame = birthFrame;
			this.reuseCount = 0;
			this.type = blockEntity.getType();
			this.typeStats = typeStats;
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
		long hits;
		long misses;
		long lookupSampleNanos;
		long lookupSamples;
		long buildNanos;

		boolean shouldBypass() {
			long accesses = hits + misses;
			if (accesses < 256L || lookupSamples < 4L || misses == 0L) return false;
			double averageLookup = lookupSampleNanos / (double)lookupSamples;
			double averageBuild = buildNanos / (double)misses;
			return averageLookup > averageBuild;
		}
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
