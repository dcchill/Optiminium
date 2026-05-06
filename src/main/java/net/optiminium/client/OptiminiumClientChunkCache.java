package net.optiminium.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.optiminium.optimization.OptiminiumSettings;
import org.joml.Vector3f;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@EventBusSubscriber(modid = "optiminium", value = Dist.CLIENT)
public final class OptiminiumClientChunkCache {
	private static final int MAX_CACHE_CHUNKS = 8192;
	private static final int SNAPSHOT_VERSION = 2;
	private static final int MIN_SUPPORTED_SNAPSHOT_VERSION = 1;
	private static final int MAX_PENDING_RENDER_REFRESHES_PER_TICK = 12;
	private static final int MAX_ACTIVE_DISK_LOADS = 64;
	private static final int MAX_DEBUG_CHUNKS_PER_FRAME = 1024;
	private static final int ALWAYS_LOAD_NEARBY_CACHED_CHUNKS = 2;
	private static final double MIN_VIEW_DOT_FOR_DISK_LOAD = -0.2D;
	private static final Path CACHE_ROOT = FMLPaths.GAMEDIR.get().resolve("optiminium-chunk-cache");
	private static final ExecutorService SAVE_EXECUTOR = Executors.newSingleThreadExecutor(task -> createWorker(task, "optiminium-cache-save"));
	private static final ExecutorService LOAD_EXECUTOR = Executors.newFixedThreadPool(2, task -> createWorker(task, "optiminium-cache-load"));

	private static final Map<CachedChunkKey, LevelChunk> cachedChunks = Collections.synchronizedMap(new LinkedHashMap<>(1024, 0.75F, false));
	private static final Map<CachedChunkKey, CompletableFuture<CompoundTag>> loadingDiskChunks = new ConcurrentHashMap<>();
	private static final Set<CachedChunkKey> pendingRenderRefreshes = ConcurrentHashMap.newKeySet();
	private static final Set<CachedChunkKey> missingDiskChunks = ConcurrentHashMap.newKeySet();

	private static ResourceKey<Level> currentDimension;
	private static int lastAdvertisedLiveDistance = -1;
	private static volatile CameraSnapshot cameraSnapshot;

	private OptiminiumClientChunkCache() {
	}

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		if (level == null || minecraft.player == null) {
			clear();
			lastAdvertisedLiveDistance = -1;
			return;
		}

		if (currentDimension != level.dimension()) {
			clear();
			currentDimension = level.dimension();
			minecraft.levelRenderer.allChanged();
		}

		syncServerRenderDistance(minecraft);
		updateCameraSnapshot(minecraft, level);
		if (!isActive()) {
			clear();
			return;
		}

		ChunkPos center = minecraft.player.chunkPosition();
		int clientDistance = getClientRenderDistance(minecraft.options.getEffectiveRenderDistance());
		completeDiskLoads(level, center, clientDistance);
		refreshPendingCachedChunks(level);
		prune(level.dimension(), center, clientDistance);
	}

	@SubscribeEvent
	public static void onChunkLoad(ChunkEvent.Load event) {
		if (!event.getLevel().isClientSide() || !(event.getChunk() instanceof LevelChunk chunk) || !(chunk.getLevel() instanceof ClientLevel level)) {
			return;
		}

		remove(level, chunk.getPos());
	}

	@SubscribeEvent
	public static void onChunkUnload(ChunkEvent.Unload event) {
		if (!isActive() || !event.getLevel().isClientSide() || !(event.getChunk() instanceof LevelChunk chunk) || !(chunk.getLevel() instanceof ClientLevel level)) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) {
			return;
		}

		int distance = chunkDistance(minecraft.player.chunkPosition(), chunk.getPos());
		int liveDistance = getLiveRenderDistance();
		int clientDistance = getClientRenderDistance(liveDistance);
		if (distance <= liveDistance || distance > clientDistance) {
			return;
		}

		CachedChunkKey key = new CachedChunkKey(level.dimension(), chunk.getPos().x, chunk.getPos().z);
		CompoundTag tag = saveChunkSnapshot(level, chunk);
		LevelChunk cachedChunk = createChunkFromSnapshot(level, tag, false);
		if (cachedChunk == null) {
			return;
		}

		cachedChunks.put(key, cachedChunk);
		pendingRenderRefreshes.add(key);
		missingDiskChunks.remove(key);
		saveChunkSnapshotAsync(key, tag);
		scheduleChunkRenders(trimCache());
	}

	@SubscribeEvent
	public static void onRenderLevelStage(RenderLevelStageEvent event) {
		if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || !isActive() || !OptiminiumSettings.isDebugCachedChunks() || cachedChunks.isEmpty()) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		if (level == null || minecraft.player == null) {
			return;
		}

		updateCameraSnapshot(minecraft, level);
		PoseStack poseStack = event.getPoseStack();
		Vec3 cameraPosition = event.getCamera().getPosition();
		MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
		VertexConsumer lines = buffers.getBuffer(RenderType.lines());
		int drawn = 0;
		for (Map.Entry<CachedChunkKey, LevelChunk> entry : cachedChunkEntriesSnapshot()) {
			CachedChunkKey key = entry.getKey();
			if (key.dimension != level.dimension()) {
				continue;
			}

			ChunkPos pos = entry.getValue().getPos();
			AABB bounds = new AABB(pos.getMinBlockX(), level.getMinBuildHeight(), pos.getMinBlockZ(), pos.getMaxBlockX() + 1, level.getMaxBuildHeight(), pos.getMaxBlockZ() + 1)
				.move(-cameraPosition.x, -cameraPosition.y, -cameraPosition.z);
			LevelRenderer.renderLineBox(poseStack, lines, bounds, 0.1F, 0.85F, 1.0F, 0.9F);
			if (++drawn >= MAX_DEBUG_CHUNKS_PER_FRAME) {
				break;
			}
		}
		buffers.endBatch(RenderType.lines());
	}

	public static LevelChunk getChunk(ClientLevel level, int chunkX, int chunkZ) {
		if (!isActive()) {
			return null;
		}
		CachedChunkKey key = new CachedChunkKey(level.dimension(), chunkX, chunkZ);
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) {
			return null;
		}

		int radius = getClientRenderDistance(minecraft.options.getEffectiveRenderDistance());
		if (!shouldUseCachedChunk(level, minecraft.player.chunkPosition(), radius, key)) {
			return null;
		}

		LevelChunk chunk = cachedChunks.get(key);
		if (chunk == null) {
			queueDiskLoad(level, key);
		}
		return chunk;
	}

	public static boolean hasCachedChunk(ClientLevel level, ChunkPos pos) {
		return isActive() && cachedChunks.containsKey(new CachedChunkKey(level.dimension(), pos.x, pos.z));
	}

	public static boolean isActive() {
		return OptiminiumSettings.isEnabled() && OptiminiumSettings.getCachedChunkDistanceChunks() > 0;
	}

	public static int getLiveRenderDistance() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.options == null) {
			return 2;
		}
		int requestedDistance = minecraft.options.renderDistance().get();
		int reserve = OptiminiumSettings.getLiveRenderDistanceReserveChunks();
		return Math.max(2, requestedDistance - reserve);
	}

	public static int getClientRenderDistance(int vanillaDistance) {
		if (!isActive()) {
			return vanillaDistance;
		}
		return Math.max(vanillaDistance, getLiveRenderDistance() + OptiminiumSettings.getCachedChunkDistanceChunks());
	}

	public static void onSettingsChanged() {
		lastAdvertisedLiveDistance = -1;
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.levelRenderer != null) {
			minecraft.levelRenderer.allChanged();
		}
	}

	private static void remove(ClientLevel level, ChunkPos pos) {
		if (cachedChunks.remove(new CachedChunkKey(level.dimension(), pos.x, pos.z)) != null) {
			scheduleChunkRender(level, pos);
		}
	}

	private static void clear() {
		if (cachedChunks.isEmpty() && loadingDiskChunks.isEmpty() && pendingRenderRefreshes.isEmpty() && missingDiskChunks.isEmpty()) {
			return;
		}
		cachedChunks.clear();
		loadingDiskChunks.clear();
		pendingRenderRefreshes.clear();
		missingDiskChunks.clear();
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.levelRenderer != null) {
			minecraft.levelRenderer.allChanged();
		}
	}

	private static void prune(ResourceKey<Level> dimension, ChunkPos center, int radius) {
		List<LevelChunk> removedChunks = new ArrayList<>();
		for (Map.Entry<CachedChunkKey, LevelChunk> entry : cachedChunkEntriesSnapshot()) {
			CachedChunkKey key = entry.getKey();
			if (key.dimension != dimension || Math.max(Math.abs(key.chunkX - center.x), Math.abs(key.chunkZ - center.z)) > radius) {
				LevelChunk removed = cachedChunks.remove(key);
				if (removed != null) {
					removedChunks.add(removed);
				}
			}
		}
		scheduleChunkRenders(removedChunks);
	}

	private static List<LevelChunk> trimCache() {
		List<LevelChunk> removedChunks = new ArrayList<>();
		synchronized (cachedChunks) {
			while (cachedChunks.size() > MAX_CACHE_CHUNKS) {
				Map.Entry<CachedChunkKey, LevelChunk> eldest = cachedChunks.entrySet().iterator().next();
				LevelChunk chunk = cachedChunks.remove(eldest.getKey());
				if (chunk != null) {
					removedChunks.add(chunk);
				}
			}
		}
		return removedChunks;
	}

	private static void completeDiskLoads(ClientLevel level, ChunkPos center, int radius) {
		for (Map.Entry<CachedChunkKey, CompletableFuture<CompoundTag>> entry : new ArrayList<>(loadingDiskChunks.entrySet())) {
			CompletableFuture<CompoundTag> future = entry.getValue();
			if (!future.isDone()) {
				continue;
			}

			CachedChunkKey key = entry.getKey();
			if (!loadingDiskChunks.remove(key, future)) {
				continue;
			}
			CompoundTag tag = future.getNow(null);
			if (tag == null) {
				missingDiskChunks.add(key);
				continue;
			}
			if (!shouldUseCachedChunk(level, center, radius, key)) {
				continue;
			}

			LevelChunk chunk = loadChunkSnapshot(level, tag);
			if (chunk != null) {
				cachedChunks.put(key, chunk);
				pendingRenderRefreshes.add(key);
				scheduleChunkRenders(trimCache());
			} else {
				missingDiskChunks.add(key);
			}
		}
	}

	private static void enableCachedChunkRendering(ClientLevel level, LevelChunk chunk) {
		level.getChunkSource().getLightEngine().setLightEnabled(chunk.getPos(), true);
		scheduleChunkRender(level, chunk.getPos());
	}

	private static void queueDiskLoad(ClientLevel level, CachedChunkKey key) {
		if (cachedChunks.containsKey(key) || loadingDiskChunks.containsKey(key) || missingDiskChunks.contains(key) || loadingDiskChunks.size() >= MAX_ACTIVE_DISK_LOADS) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) {
			return;
		}

		int radius = getClientRenderDistance(minecraft.options.getEffectiveRenderDistance());
		if (!shouldUseCachedChunk(level, minecraft.player.chunkPosition(), radius, key)) {
			return;
		}

		Path path = chunkCachePath(key);
		if (!Files.isRegularFile(path)) {
			missingDiskChunks.add(key);
			return;
		}

		loadingDiskChunks.computeIfAbsent(key, unused -> CompletableFuture.supplyAsync(() -> {
			try {
				return NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
			} catch (IOException ignored) {
				return null;
			}
		}, LOAD_EXECUTOR));
	}

	private static void saveChunkSnapshotAsync(CachedChunkKey key, CompoundTag tag) {
		Path path = chunkCachePath(key);
		SAVE_EXECUTOR.execute(() -> {
			try {
				Files.createDirectories(path.getParent());
				NbtIo.writeCompressed(tag, path);
			} catch (IOException ignored) {
			}
		});
	}

	private static CompoundTag saveChunkSnapshot(ClientLevel level, LevelChunk chunk) {
		CompoundTag tag = new CompoundTag();
		ChunkPos pos = chunk.getPos();
		tag.putInt("version", SNAPSHOT_VERSION);
		tag.putInt("x", pos.x);
		tag.putInt("z", pos.z);
		tag.putInt("minSection", level.getMinSection());
		tag.putBoolean("exposedOnly", true);

		ListTag sections = new ListTag();
		LevelChunkSection[] sourceSections = chunk.getSections();
		for (int sectionIndex = 0; sectionIndex < sourceSections.length; sectionIndex++) {
			LevelChunkSection section = sourceSections[sectionIndex];
			if (section.hasOnlyAir()) {
				continue;
			}

			int[] states = new int[4096];
			int stateIndex = 0;
			boolean hasVisibleBlock = false;
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						BlockState state = section.getBlockState(x, y, z);
						if (shouldKeepCachedBlock(sourceSections, sectionIndex, x, y, z, state)) {
							states[stateIndex] = Block.getId(state);
							hasVisibleBlock = true;
						}
						stateIndex++;
					}
				}
			}
			if (!hasVisibleBlock) {
				continue;
			}

			CompoundTag sectionTag = new CompoundTag();
			sectionTag.putInt("y", level.getMinSection() + sectionIndex);
			sectionTag.putIntArray("states", states);
			sections.add(sectionTag);
		}
		tag.put("sections", sections);

		CompoundTag heightmaps = new CompoundTag();
		for (Map.Entry<Heightmap.Types, Heightmap> entry : chunk.getHeightmaps()) {
			heightmaps.putLongArray(entry.getKey().getSerializationKey(), entry.getValue().getRawData());
		}
		tag.put("heightmaps", heightmaps);

		saveLightLayer(level, pos, tag, LightLayer.BLOCK, "blockLight");
		saveLightLayer(level, pos, tag, LightLayer.SKY, "skyLight");
		return tag;
	}

	private static boolean shouldKeepCachedBlock(LevelChunkSection[] sections, int sectionIndex, int x, int y, int z, BlockState state) {
		if (state.isAir()) {
			return false;
		}
		if (!state.canOcclude() || !state.getFluidState().isEmpty()) {
			return true;
		}

		for (Direction direction : Direction.values()) {
			BlockState neighbor = getCachedNeighborState(sections, sectionIndex, x, y, z, direction);
			if (neighbor == null || neighbor.isAir() || !neighbor.canOcclude() || !neighbor.getFluidState().isEmpty()) {
				return true;
			}
		}
		return false;
	}

	private static BlockState getCachedNeighborState(LevelChunkSection[] sections, int sectionIndex, int x, int y, int z, Direction direction) {
		int neighborX = x + direction.getStepX();
		int neighborY = y + direction.getStepY();
		int neighborZ = z + direction.getStepZ();
		int neighborSectionIndex = sectionIndex;

		if (neighborX < 0 || neighborX > 15 || neighborZ < 0 || neighborZ > 15) {
			return null;
		}
		if (neighborY < 0) {
			neighborSectionIndex--;
			neighborY = 15;
		} else if (neighborY > 15) {
			neighborSectionIndex++;
			neighborY = 0;
		}
		if (neighborSectionIndex < 0 || neighborSectionIndex >= sections.length) {
			return null;
		}

		LevelChunkSection neighborSection = sections[neighborSectionIndex];
		if (neighborSection == null || neighborSection.hasOnlyAir()) {
			return Blocks.AIR.defaultBlockState();
		}
		return neighborSection.getBlockState(neighborX, neighborY, neighborZ);
	}

	private static void saveLightLayer(ClientLevel level, ChunkPos pos, CompoundTag tag, LightLayer layer, String key) {
		ListTag lightSections = new ListTag();
		for (int sectionY = level.getLightEngine().getMinLightSection(); sectionY < level.getLightEngine().getMaxLightSection(); sectionY++) {
			DataLayer data = level.getLightEngine().getLayerListener(layer).getDataLayerData(SectionPos.of(pos, sectionY));
			if (data == null || data.isEmpty()) {
				continue;
			}

			CompoundTag section = new CompoundTag();
			section.putInt("y", sectionY);
			section.putByteArray("data", data.getData().clone());
			lightSections.add(section);
		}
		tag.put(key, lightSections);
	}

	private static LevelChunk loadChunkSnapshot(ClientLevel level, CompoundTag tag) {
		return createChunkFromSnapshot(level, tag, true);
	}

	private static LevelChunk createChunkFromSnapshot(ClientLevel level, CompoundTag tag, boolean restoreLight) {
		int version = tag.getInt("version");
		if (version < MIN_SUPPORTED_SNAPSHOT_VERSION || version > SNAPSHOT_VERSION) {
			return null;
		}

		ChunkPos pos = new ChunkPos(tag.getInt("x"), tag.getInt("z"));
		int sectionCount = level.getMaxSection() - level.getMinSection();
		LevelChunkSection[] sections = new LevelChunkSection[sectionCount];
		for (int i = 0; i < sections.length; i++) {
			sections[i] = newChunkSection(level);
		}

		ListTag sectionTags = tag.getList("sections", Tag.TAG_COMPOUND);
		for (int i = 0; i < sectionTags.size(); i++) {
			CompoundTag sectionTag = sectionTags.getCompound(i);
			int sectionIndex = sectionTag.getInt("y") - level.getMinSection();
			if (sectionIndex < 0 || sectionIndex >= sections.length) {
				continue;
			}

			int[] states = sectionTag.getIntArray("states");
			if (states.length != 4096) {
				continue;
			}

			LevelChunkSection section = sections[sectionIndex];
			int stateIndex = 0;
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						BlockState state = Block.stateById(states[stateIndex++]);
						section.setBlockState(x, y, z, state, false);
					}
				}
			}
			section.recalcBlockCounts();
		}

		if (!tag.getBoolean("exposedOnly")) {
			sections = createExposedOnlySections(level, sections);
		}

		LevelChunk chunk = new LevelChunk(level, pos, UpgradeData.EMPTY, new LevelChunkTicks<Block>(), new LevelChunkTicks<Fluid>(), 0L, sections, null, null);
		chunk.setLightCorrect(true);
		chunk.setUnsaved(false);

		CompoundTag heightmaps = tag.getCompound("heightmaps");
		EnumSet<Heightmap.Types> missingHeightmaps = EnumSet.noneOf(Heightmap.Types.class);
		for (Heightmap.Types type : Heightmap.Types.values()) {
			String key = type.getSerializationKey();
			if (heightmaps.contains(key, Tag.TAG_LONG_ARRAY)) {
				chunk.setHeightmap(type, heightmaps.getLongArray(key));
			} else {
				missingHeightmaps.add(type);
			}
		}
		if (!missingHeightmaps.isEmpty()) {
			Heightmap.primeHeightmaps(chunk, missingHeightmaps);
		}

		if (restoreLight) {
			loadLightLayer(level, pos, tag, LightLayer.BLOCK, "blockLight");
			loadLightLayer(level, pos, tag, LightLayer.SKY, "skyLight");
		}
		return chunk;
	}

	private static LevelChunkSection[] createExposedOnlySections(ClientLevel level, LevelChunkSection[] sourceSections) {
		LevelChunkSection[] sections = new LevelChunkSection[sourceSections.length];
		for (int sectionIndex = 0; sectionIndex < sourceSections.length; sectionIndex++) {
			LevelChunkSection sourceSection = sourceSections[sectionIndex];
			LevelChunkSection targetSection = newChunkSection(level);
			sections[sectionIndex] = targetSection;
			if (sourceSection == null || sourceSection.hasOnlyAir()) {
				continue;
			}

			boolean hasVisibleBlock = false;
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						BlockState state = sourceSection.getBlockState(x, y, z);
						if (shouldKeepCachedBlock(sourceSections, sectionIndex, x, y, z, state)) {
							targetSection.setBlockState(x, y, z, state, false);
							hasVisibleBlock = true;
						}
					}
				}
			}
			if (hasVisibleBlock) {
				targetSection.recalcBlockCounts();
			}
		}
		return sections;
	}

	private static LevelChunkSection newChunkSection(ClientLevel level) {
		return new LevelChunkSection(level.registryAccess().registryOrThrow(Registries.BIOME));
	}

	private static void loadLightLayer(ClientLevel level, ChunkPos pos, CompoundTag tag, LightLayer layer, String key) {
		ListTag lightSections = tag.getList(key, Tag.TAG_COMPOUND);
		for (int i = 0; i < lightSections.size(); i++) {
			CompoundTag section = lightSections.getCompound(i);
			byte[] data = section.getByteArray("data");
			if (data.length == 2048) {
				level.getLightEngine().queueSectionData(layer, SectionPos.of(pos, section.getInt("y")), new DataLayer(data));
			}
		}
		level.getLightEngine().setLightEnabled(pos, true);
	}

	private static void refreshPendingCachedChunks(ClientLevel level) {
		int refreshed = 0;
		for (CachedChunkKey key : new ArrayList<>(pendingRenderRefreshes)) {
			if (!pendingRenderRefreshes.remove(key)) {
				continue;
			}
			if (key.dimension == level.dimension()) {
				LevelChunk chunk = cachedChunks.get(key);
				if (chunk != null) {
					enableCachedChunkRendering(level, chunk);
				}
			}
			if (++refreshed >= MAX_PENDING_RENDER_REFRESHES_PER_TICK) {
				break;
			}
		}
	}

	private static void syncServerRenderDistance(Minecraft minecraft) {
		int advertisedDistance = isActive() ? getLiveRenderDistance() : minecraft.options.renderDistance().get();
		if (advertisedDistance != lastAdvertisedLiveDistance) {
			minecraft.options.broadcastOptions();
			lastAdvertisedLiveDistance = advertisedDistance;
		}
	}

	private static void scheduleChunkRender(ClientLevel level, ChunkPos pos) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.levelRenderer == null) {
			return;
		}

		for (int sectionY = level.getMinSection(); sectionY < level.getMaxSection(); sectionY++) {
			minecraft.levelRenderer.setSectionDirty(pos.x, sectionY, pos.z);
			minecraft.levelRenderer.setSectionDirty(pos.x - 1, sectionY, pos.z);
			minecraft.levelRenderer.setSectionDirty(pos.x + 1, sectionY, pos.z);
			minecraft.levelRenderer.setSectionDirty(pos.x, sectionY, pos.z - 1);
			minecraft.levelRenderer.setSectionDirty(pos.x, sectionY, pos.z + 1);
		}
	}

	private static void scheduleChunkRenders(List<LevelChunk> chunks) {
		for (LevelChunk chunk : chunks) {
			if (chunk.getLevel() instanceof ClientLevel level) {
				scheduleChunkRender(level, chunk.getPos());
			}
		}
	}

	private static List<Map.Entry<CachedChunkKey, LevelChunk>> cachedChunkEntriesSnapshot() {
		synchronized (cachedChunks) {
			return new ArrayList<>(cachedChunks.entrySet());
		}
	}

	private static boolean shouldUseCachedChunk(ClientLevel level, ChunkPos center, int radius, CachedChunkKey key) {
		if (key.dimension != level.dimension()) {
			return false;
		}

		int distance = chunkDistance(center, new ChunkPos(key.chunkX, key.chunkZ));
		if (distance > radius) {
			return false;
		}
		if (distance <= getLiveRenderDistance() + ALWAYS_LOAD_NEARBY_CACHED_CHUNKS) {
			return true;
		}
		return isChunkInViewCone(level, key);
	}

	private static boolean isChunkInViewCone(ClientLevel level, CachedChunkKey key) {
		CameraSnapshot snapshot = cameraSnapshot;
		if (snapshot == null || snapshot.dimension != level.dimension()) {
			return true;
		}

		double chunkCenterX = key.chunkX * 16.0D + 8.0D;
		double chunkCenterZ = key.chunkZ * 16.0D + 8.0D;
		double dx = chunkCenterX - snapshot.x;
		double dz = chunkCenterZ - snapshot.z;
		double distanceSq = dx * dx + dz * dz;
		if (distanceSq < 1.0D) {
			return true;
		}

		double lookX = snapshot.lookX;
		double lookZ = snapshot.lookZ;
		double lookLengthSq = lookX * lookX + lookZ * lookZ;
		if (lookLengthSq < 1.0E-4D) {
			return true;
		}

		double dot = (dx * lookX + dz * lookZ) / Math.sqrt(distanceSq * lookLengthSq);
		return dot >= MIN_VIEW_DOT_FOR_DISK_LOAD;
	}

	private static void updateCameraSnapshot(Minecraft minecraft, ClientLevel level) {
		Vec3 position = minecraft.gameRenderer.getMainCamera().getPosition();
		Vector3f look = minecraft.gameRenderer.getMainCamera().getLookVector();
		cameraSnapshot = new CameraSnapshot(level.dimension(), position.x, position.z, look.x(), look.z());
	}

	private static int chunkDistance(ChunkPos a, ChunkPos b) {
		return Math.max(Math.abs(a.x - b.x), Math.abs(a.z - b.z));
	}

	private static Path chunkCachePath(CachedChunkKey key) {
		ResourceLocation dimension = key.dimension.location();
		int regionX = Math.floorDiv(key.chunkX, 32);
		int regionZ = Math.floorDiv(key.chunkZ, 32);
		return CACHE_ROOT
			.resolve(safePathPart(dimension.getNamespace()))
			.resolve(safePathPart(dimension.getPath()))
			.resolve("r." + regionX + "." + regionZ)
			.resolve("c." + key.chunkX + "." + key.chunkZ + ".nbt");
	}

	private static String safePathPart(String value) {
		return value.replaceAll("[^a-zA-Z0-9._-]", "_");
	}

	private static Thread createWorker(Runnable task, String name) {
		Thread thread = new Thread(task, name);
		thread.setDaemon(true);
		return thread;
	}

	private record CachedChunkKey(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
	}

	private record CameraSnapshot(ResourceKey<Level> dimension, double x, double z, float lookX, float lookZ) {
	}
}
