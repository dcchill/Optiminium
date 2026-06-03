package net.optiminium.optimization;

import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class OptiminiumSettings {
	private static final String ENABLED_KEY = "enabled";
	private static final String FOG_DISTANCE_KEY = "fogDistanceBlocks";
	private static final String CHUNK_REBUILD_SCHEDULING_KEY = "chunkRebuildScheduling";
	private static final String CHUNK_REBUILDS_PER_FRAME_KEY = "chunkRebuildsPerFrame";
	private static final String SYNC_CHUNK_REBUILDS_PER_FRAME_KEY = "syncChunkRebuildsPerFrame";
	private static final String LIGHTING_DEDUPLICATION_KEY = "lightingDeduplication";
	private static final String GPU_OPTIMIZER_KEY = "gpuOptimizer";
	private static final String GPU_TARGET_FPS_KEY = "gpuTargetFps";
	private static final String GPU_MIN_RENDER_SCALE_KEY = "gpuMinRenderScalePercent";
	private static final String CLIENT_RENDER_CULLING_KEY = "clientRenderCulling";
	private static final String ENTITY_RENDER_DISTANCE_SCALE_KEY = "entityRenderDistanceScalePercent";
	private static final String BLOCK_ENTITY_CULLING_KEY = "blockEntityCulling";
	private static final String BLOCK_ENTITY_DISTANCE_SCALE_KEY = "blockEntityDistanceScalePercent";
	private static final String CROWD_CULLING_KEY = "crowdCulling";
	private static final String CROWD_RENDER_BUDGET_KEY = "crowdRenderBudgetPercent";
	private static final String PARTICLE_LIMITER_KEY = "particleLimiter";
	private static final String PARTICLE_RENDER_DISTANCE_KEY = "particleRenderDistanceBlocks";
	private static final String MAX_PARTICLES_PER_FRAME_KEY = "maxParticlesPerFrame";
	private static final String AMBIENT_SOUND_LIMITER_KEY = "ambientSoundLimiter";
	private static final String AMBIENT_SOUND_BUDGET_KEY = "ambientSoundBudget";
	private static final String SERVER_ENTITY_TICK_THROTTLING_KEY = "serverEntityTickThrottling";
	private static final String FAR_ENTITY_TICK_INTERVAL_KEY = "farEntityTickInterval";
	private static final String ADAPTIVE_OPTIMIZER_KEY = "adaptiveOptimizer";
	private static final String ADAPTIVE_SIMULATION_DISTANCE_KEY = "adaptiveSimulationDistance";
	private static final String ADAPTIVE_SIMULATION_TARGET_MSPT_KEY = "adaptiveSimulationTargetMspt";
	private static final String ADAPTIVE_SIMULATION_MIN_DISTANCE_KEY = "adaptiveSimulationMinDistanceChunks";
	private static final String ITEM_VIRTUALIZATION_KEY = "itemVirtualization";
	private static final String ITEM_CLUSTER_THRESHOLD_KEY = "itemClusterThreshold";
	private static final String XP_ORB_MERGING_KEY = "xpOrbMerging";
	private static final String XP_MERGE_THRESHOLD_KEY = "xpMergeThreshold";
	private static final String REDSTONE_DEDUPLICATION_KEY = "redstoneDeduplication";
	private static final String BLOCK_ENTITY_UPDATE_THROTTLING_KEY = "blockEntityUpdateThrottling";
	private static final String BLOCK_ENTITY_SLEEP_AFTER_TICKS_KEY = "blockEntitySleepAfterTicks";
	private static final String BLOCK_ENTITY_WAKE_RADIUS_BLOCKS_KEY = "blockEntityWakeRadiusBlocks";
	private static final String SLEEPING_BLOCK_ENTITY_TICKS_PER_TICK_KEY = "sleepingBlockEntityTicksPerTick";
	private static final String SLEEPING_BLOCK_ENTITY_TICK_INTERVAL_KEY = "sleepingBlockEntityTickInterval";

	private static final Path CONFIG_FILE = FMLPaths.CONFIGDIR.get().resolve("optiminium.properties");

	private static final int MIN_FOG_DISTANCE_BLOCKS = 32;
	private static final int MAX_FOG_DISTANCE_BLOCKS = 512;
	private static final int DEFAULT_FOG_DISTANCE_BLOCKS = 192;
	private static final int MIN_CHUNK_REBUILDS_PER_FRAME = 1;
	private static final int MAX_CHUNK_REBUILDS_PER_FRAME = 16;
	private static final int DEFAULT_CHUNK_REBUILDS_PER_FRAME = 2;
	private static final int MIN_SYNC_CHUNK_REBUILDS_PER_FRAME = 0;
	private static final int MAX_SYNC_CHUNK_REBUILDS_PER_FRAME = 4;
	private static final int DEFAULT_SYNC_CHUNK_REBUILDS_PER_FRAME = 0;
	private static final int MIN_GPU_TARGET_FPS = 30;
	private static final int MAX_GPU_TARGET_FPS = 240;
	private static final int DEFAULT_GPU_TARGET_FPS = 60;
	private static final int MIN_GPU_MIN_RENDER_SCALE_PERCENT = 35;
	private static final int MAX_GPU_MIN_RENDER_SCALE_PERCENT = 100;
	private static final int DEFAULT_GPU_MIN_RENDER_SCALE_PERCENT = 60;
	private static final int MIN_DISTANCE_SCALE_PERCENT = 25;
	private static final int MAX_DISTANCE_SCALE_PERCENT = 200;
	private static final int DEFAULT_DISTANCE_SCALE_PERCENT = 100;
	private static final int MIN_CROWD_RENDER_BUDGET_PERCENT = 0;
	private static final int MAX_CROWD_RENDER_BUDGET_PERCENT = 200;
	private static final int DEFAULT_CROWD_RENDER_BUDGET_PERCENT = 100;
	private static final int MIN_PARTICLE_RENDER_DISTANCE_BLOCKS = 16;
	private static final int MAX_PARTICLE_RENDER_DISTANCE_BLOCKS = 160;
	private static final int DEFAULT_PARTICLE_RENDER_DISTANCE_BLOCKS = 64;
	private static final int MIN_MAX_PARTICLES_PER_FRAME = 16;
	private static final int MAX_MAX_PARTICLES_PER_FRAME = 512;
	private static final int DEFAULT_MAX_PARTICLES_PER_FRAME = 96;
	private static final int MIN_AMBIENT_SOUND_BUDGET = 0;
	private static final int MAX_AMBIENT_SOUND_BUDGET = 128;
	private static final int DEFAULT_AMBIENT_SOUND_BUDGET = 32;
	private static final int MIN_FAR_ENTITY_TICK_INTERVAL = 10;
	private static final int MAX_FAR_ENTITY_TICK_INTERVAL = 100;
	private static final int DEFAULT_FAR_ENTITY_TICK_INTERVAL = 40;
	private static final int MIN_ADAPTIVE_SIMULATION_TARGET_MSPT = 35;
	private static final int MAX_ADAPTIVE_SIMULATION_TARGET_MSPT = 80;
	private static final int DEFAULT_ADAPTIVE_SIMULATION_TARGET_MSPT = 50;
	private static final int MIN_ADAPTIVE_SIMULATION_MIN_DISTANCE_CHUNKS = 2;
	private static final int MAX_ADAPTIVE_SIMULATION_MIN_DISTANCE_CHUNKS = 12;
	private static final int DEFAULT_ADAPTIVE_SIMULATION_MIN_DISTANCE_CHUNKS = 4;
	private static final int MIN_ITEM_CLUSTER_THRESHOLD = 8;
	private static final int MAX_ITEM_CLUSTER_THRESHOLD = 128;
	private static final int DEFAULT_ITEM_CLUSTER_THRESHOLD = 24;
	private static final int MIN_XP_MERGE_THRESHOLD = 4;
	private static final int MAX_XP_MERGE_THRESHOLD = 64;
	private static final int DEFAULT_XP_MERGE_THRESHOLD = 8;
	private static final int MIN_BLOCK_ENTITY_SLEEP_AFTER_TICKS = 20;
	private static final int MAX_BLOCK_ENTITY_SLEEP_AFTER_TICKS = 20 * 60;
	private static final int DEFAULT_BLOCK_ENTITY_SLEEP_AFTER_TICKS = 20 * 10;
	private static final int MIN_BLOCK_ENTITY_WAKE_RADIUS_BLOCKS = 16;
	private static final int MAX_BLOCK_ENTITY_WAKE_RADIUS_BLOCKS = 128;
	private static final int DEFAULT_BLOCK_ENTITY_WAKE_RADIUS_BLOCKS = 48;
	private static final int MIN_SLEEPING_BLOCK_ENTITY_TICKS_PER_TICK = 0;
	private static final int MAX_SLEEPING_BLOCK_ENTITY_TICKS_PER_TICK = 128;
	private static final int DEFAULT_SLEEPING_BLOCK_ENTITY_TICKS_PER_TICK = 8;
	private static final int MIN_SLEEPING_BLOCK_ENTITY_TICK_INTERVAL = 20;
	private static final int MAX_SLEEPING_BLOCK_ENTITY_TICK_INTERVAL = 20 * 60;
	private static final int DEFAULT_SLEEPING_BLOCK_ENTITY_TICK_INTERVAL = 20 * 10;

	private static volatile boolean enabled = true;
	private static volatile int fogDistanceBlocks = DEFAULT_FOG_DISTANCE_BLOCKS;
	private static volatile boolean chunkRebuildScheduling = true;
	private static volatile int chunkRebuildsPerFrame = DEFAULT_CHUNK_REBUILDS_PER_FRAME;
	private static volatile int syncChunkRebuildsPerFrame = DEFAULT_SYNC_CHUNK_REBUILDS_PER_FRAME;
	private static volatile boolean lightingDeduplication = true;
	private static volatile boolean gpuOptimizer = true;
	private static volatile int gpuTargetFps = DEFAULT_GPU_TARGET_FPS;
	private static volatile int gpuMinRenderScalePercent = DEFAULT_GPU_MIN_RENDER_SCALE_PERCENT;
	private static volatile boolean clientRenderCulling = true;
	private static volatile int entityRenderDistanceScalePercent = DEFAULT_DISTANCE_SCALE_PERCENT;
	private static volatile boolean blockEntityCulling = true;
	private static volatile int blockEntityDistanceScalePercent = DEFAULT_DISTANCE_SCALE_PERCENT;
	private static volatile boolean crowdCulling = true;
	private static volatile int crowdRenderBudgetPercent = DEFAULT_CROWD_RENDER_BUDGET_PERCENT;
	private static volatile boolean particleLimiter = true;
	private static volatile int particleRenderDistanceBlocks = DEFAULT_PARTICLE_RENDER_DISTANCE_BLOCKS;
	private static volatile int maxParticlesPerFrame = DEFAULT_MAX_PARTICLES_PER_FRAME;
	private static volatile boolean ambientSoundLimiter = true;
	private static volatile int ambientSoundBudget = DEFAULT_AMBIENT_SOUND_BUDGET;
	private static volatile boolean serverEntityTickThrottling = true;
	private static volatile int farEntityTickInterval = DEFAULT_FAR_ENTITY_TICK_INTERVAL;
	private static volatile boolean adaptiveOptimizer = true;
	private static volatile boolean adaptiveSimulationDistance = true;
	private static volatile int adaptiveSimulationTargetMspt = DEFAULT_ADAPTIVE_SIMULATION_TARGET_MSPT;
	private static volatile int adaptiveSimulationMinDistanceChunks = DEFAULT_ADAPTIVE_SIMULATION_MIN_DISTANCE_CHUNKS;
	private static volatile boolean itemVirtualization = true;
	private static volatile int itemClusterThreshold = DEFAULT_ITEM_CLUSTER_THRESHOLD;
	private static volatile boolean xpOrbMerging = true;
	private static volatile int xpMergeThreshold = DEFAULT_XP_MERGE_THRESHOLD;
	private static volatile boolean redstoneDeduplication = true;
	private static volatile boolean blockEntityUpdateThrottling = true;
	private static volatile int blockEntitySleepAfterTicks = DEFAULT_BLOCK_ENTITY_SLEEP_AFTER_TICKS;
	private static volatile int blockEntityWakeRadiusBlocks = DEFAULT_BLOCK_ENTITY_WAKE_RADIUS_BLOCKS;
	private static volatile int sleepingBlockEntityTicksPerTick = DEFAULT_SLEEPING_BLOCK_ENTITY_TICKS_PER_TICK;
	private static volatile int sleepingBlockEntityTickInterval = DEFAULT_SLEEPING_BLOCK_ENTITY_TICK_INTERVAL;

	static {
		load();
	}

	private OptiminiumSettings() {
	}

	public static boolean isEnabled() {
		return enabled;
	}

	public static void setEnabled(boolean newEnabled) {
		if (enabled != newEnabled) {
			enabled = newEnabled;
			save();
		}
	}

	public static boolean toggleEnabled() {
		setEnabled(!enabled);
		return enabled;
	}

	public static boolean isChunkRebuildScheduling() {
		return chunkRebuildScheduling;
	}

	public static boolean toggleChunkRebuildScheduling() {
		setChunkRebuildScheduling(!chunkRebuildScheduling);
		return chunkRebuildScheduling;
	}

	public static void setChunkRebuildScheduling(boolean enabled) {
		if (chunkRebuildScheduling != enabled) {
			chunkRebuildScheduling = enabled;
			save();
		}
	}

	public static int getChunkRebuildsPerFrame() {
		return chunkRebuildsPerFrame;
	}

	public static void setChunkRebuildsPerFrame(int rebuildsPerFrame) {
		int clamped = clamp(rebuildsPerFrame, MIN_CHUNK_REBUILDS_PER_FRAME, MAX_CHUNK_REBUILDS_PER_FRAME);
		if (chunkRebuildsPerFrame != clamped) {
			chunkRebuildsPerFrame = clamped;
			save();
		}
	}

	public static int getMinChunkRebuildsPerFrame() {
		return MIN_CHUNK_REBUILDS_PER_FRAME;
	}

	public static int getMaxChunkRebuildsPerFrame() {
		return MAX_CHUNK_REBUILDS_PER_FRAME;
	}

	public static int getSyncChunkRebuildsPerFrame() {
		return syncChunkRebuildsPerFrame;
	}

	public static void setSyncChunkRebuildsPerFrame(int rebuildsPerFrame) {
		int clamped = clamp(rebuildsPerFrame, MIN_SYNC_CHUNK_REBUILDS_PER_FRAME, MAX_SYNC_CHUNK_REBUILDS_PER_FRAME);
		if (syncChunkRebuildsPerFrame != clamped) {
			syncChunkRebuildsPerFrame = clamped;
			save();
		}
	}

	public static int getMinSyncChunkRebuildsPerFrame() {
		return MIN_SYNC_CHUNK_REBUILDS_PER_FRAME;
	}

	public static int getMaxSyncChunkRebuildsPerFrame() {
		return MAX_SYNC_CHUNK_REBUILDS_PER_FRAME;
	}

	public static boolean isLightingDeduplication() {
		return lightingDeduplication;
	}

	public static boolean toggleLightingDeduplication() {
		setLightingDeduplication(!lightingDeduplication);
		return lightingDeduplication;
	}

	public static void setLightingDeduplication(boolean enabled) {
		if (lightingDeduplication != enabled) {
			lightingDeduplication = enabled;
			save();
		}
	}

	public static boolean isGpuOptimizer() {
		return gpuOptimizer;
	}

	public static boolean toggleGpuOptimizer() {
		setGpuOptimizer(!gpuOptimizer);
		return gpuOptimizer;
	}

	public static void setGpuOptimizer(boolean enabled) {
		if (gpuOptimizer != enabled) {
			gpuOptimizer = enabled;
			save();
		}
	}

	public static int getGpuTargetFps() {
		return gpuTargetFps;
	}

	public static void setGpuTargetFps(int targetFps) {
		int clamped = clamp(targetFps, MIN_GPU_TARGET_FPS, MAX_GPU_TARGET_FPS);
		if (gpuTargetFps != clamped) {
			gpuTargetFps = clamped;
			save();
		}
	}

	public static int getMinGpuTargetFps() {
		return MIN_GPU_TARGET_FPS;
	}

	public static int getMaxGpuTargetFps() {
		return MAX_GPU_TARGET_FPS;
	}

	public static int getGpuMinRenderScalePercent() {
		return gpuMinRenderScalePercent;
	}

	public static void setGpuMinRenderScalePercent(int scalePercent) {
		int clamped = clamp(scalePercent, MIN_GPU_MIN_RENDER_SCALE_PERCENT, MAX_GPU_MIN_RENDER_SCALE_PERCENT);
		if (gpuMinRenderScalePercent != clamped) {
			gpuMinRenderScalePercent = clamped;
			save();
		}
	}

	public static int getMinGpuMinRenderScalePercent() {
		return MIN_GPU_MIN_RENDER_SCALE_PERCENT;
	}

	public static int getMaxGpuMinRenderScalePercent() {
		return MAX_GPU_MIN_RENDER_SCALE_PERCENT;
	}

	public static int getFogDistanceBlocks() {
		return fogDistanceBlocks;
	}

	public static void setFogDistanceBlocks(int distanceBlocks) {
		int clamped = clamp(distanceBlocks, MIN_FOG_DISTANCE_BLOCKS, MAX_FOG_DISTANCE_BLOCKS);
		if (fogDistanceBlocks != clamped) {
			fogDistanceBlocks = clamped;
			save();
		}
	}

	public static int getMinFogDistanceBlocks() {
		return MIN_FOG_DISTANCE_BLOCKS;
	}

	public static int getMaxFogDistanceBlocks() {
		return MAX_FOG_DISTANCE_BLOCKS;
	}

	public static boolean isClientRenderCulling() {
		return clientRenderCulling;
	}

	public static boolean toggleClientRenderCulling() {
		setClientRenderCulling(!clientRenderCulling);
		return clientRenderCulling;
	}

	public static void setClientRenderCulling(boolean enabled) {
		if (clientRenderCulling != enabled) {
			clientRenderCulling = enabled;
			save();
		}
	}

	public static int getEntityRenderDistanceScalePercent() {
		return entityRenderDistanceScalePercent;
	}

	public static void setEntityRenderDistanceScalePercent(int scalePercent) {
		int clamped = clamp(scalePercent, MIN_DISTANCE_SCALE_PERCENT, MAX_DISTANCE_SCALE_PERCENT);
		if (entityRenderDistanceScalePercent != clamped) {
			entityRenderDistanceScalePercent = clamped;
			save();
		}
	}

	public static int getMinEntityRenderDistanceScalePercent() {
		return MIN_DISTANCE_SCALE_PERCENT;
	}

	public static int getMaxEntityRenderDistanceScalePercent() {
		return MAX_DISTANCE_SCALE_PERCENT;
	}

	public static boolean isBlockEntityCulling() {
		return blockEntityCulling;
	}

	public static boolean toggleBlockEntityCulling() {
		setBlockEntityCulling(!blockEntityCulling);
		return blockEntityCulling;
	}

	public static void setBlockEntityCulling(boolean enabled) {
		if (blockEntityCulling != enabled) {
			blockEntityCulling = enabled;
			save();
		}
	}

	public static int getBlockEntityDistanceScalePercent() {
		return blockEntityDistanceScalePercent;
	}

	public static void setBlockEntityDistanceScalePercent(int scalePercent) {
		int clamped = clamp(scalePercent, MIN_DISTANCE_SCALE_PERCENT, MAX_DISTANCE_SCALE_PERCENT);
		if (blockEntityDistanceScalePercent != clamped) {
			blockEntityDistanceScalePercent = clamped;
			save();
		}
	}

	public static int getMinBlockEntityDistanceScalePercent() {
		return MIN_DISTANCE_SCALE_PERCENT;
	}

	public static int getMaxBlockEntityDistanceScalePercent() {
		return MAX_DISTANCE_SCALE_PERCENT;
	}

	public static boolean isCrowdCulling() {
		return crowdCulling;
	}

	public static boolean toggleCrowdCulling() {
		setCrowdCulling(!crowdCulling);
		return crowdCulling;
	}

	public static void setCrowdCulling(boolean enabled) {
		if (crowdCulling != enabled) {
			crowdCulling = enabled;
			save();
		}
	}

	public static int getCrowdRenderBudgetPercent() {
		return crowdRenderBudgetPercent;
	}

	public static void setCrowdRenderBudgetPercent(int budgetPercent) {
		int clamped = clamp(budgetPercent, MIN_CROWD_RENDER_BUDGET_PERCENT, MAX_CROWD_RENDER_BUDGET_PERCENT);
		if (crowdRenderBudgetPercent != clamped) {
			crowdRenderBudgetPercent = clamped;
			save();
		}
	}

	public static int getMinCrowdRenderBudgetPercent() {
		return MIN_CROWD_RENDER_BUDGET_PERCENT;
	}

	public static int getMaxCrowdRenderBudgetPercent() {
		return MAX_CROWD_RENDER_BUDGET_PERCENT;
	}

	public static boolean isParticleLimiter() {
		return particleLimiter;
	}

	public static boolean toggleParticleLimiter() {
		setParticleLimiter(!particleLimiter);
		return particleLimiter;
	}

	public static void setParticleLimiter(boolean enabled) {
		if (particleLimiter != enabled) {
			particleLimiter = enabled;
			save();
		}
	}

	public static int getParticleRenderDistanceBlocks() {
		return particleRenderDistanceBlocks;
	}

	public static void setParticleRenderDistanceBlocks(int distanceBlocks) {
		int clamped = clamp(distanceBlocks, MIN_PARTICLE_RENDER_DISTANCE_BLOCKS, MAX_PARTICLE_RENDER_DISTANCE_BLOCKS);
		if (particleRenderDistanceBlocks != clamped) {
			particleRenderDistanceBlocks = clamped;
			save();
		}
	}

	public static int getMinParticleRenderDistanceBlocks() {
		return MIN_PARTICLE_RENDER_DISTANCE_BLOCKS;
	}

	public static int getMaxParticleRenderDistanceBlocks() {
		return MAX_PARTICLE_RENDER_DISTANCE_BLOCKS;
	}

	public static int getMaxParticlesPerFrame() {
		return maxParticlesPerFrame;
	}

	public static void setMaxParticlesPerFrame(int maxParticles) {
		int clamped = clamp(maxParticles, MIN_MAX_PARTICLES_PER_FRAME, MAX_MAX_PARTICLES_PER_FRAME);
		if (maxParticlesPerFrame != clamped) {
			maxParticlesPerFrame = clamped;
			save();
		}
	}

	public static int getMinMaxParticlesPerFrame() {
		return MIN_MAX_PARTICLES_PER_FRAME;
	}

	public static int getMaxMaxParticlesPerFrame() {
		return MAX_MAX_PARTICLES_PER_FRAME;
	}

	public static boolean isAmbientSoundLimiter() {
		return ambientSoundLimiter;
	}

	public static boolean toggleAmbientSoundLimiter() {
		setAmbientSoundLimiter(!ambientSoundLimiter);
		return ambientSoundLimiter;
	}

	public static void setAmbientSoundLimiter(boolean enabled) {
		if (ambientSoundLimiter != enabled) {
			ambientSoundLimiter = enabled;
			save();
		}
	}

	public static int getAmbientSoundBudget() {
		return ambientSoundBudget;
	}

	public static void setAmbientSoundBudget(int soundBudget) {
		int clamped = clamp(soundBudget, MIN_AMBIENT_SOUND_BUDGET, MAX_AMBIENT_SOUND_BUDGET);
		if (ambientSoundBudget != clamped) {
			ambientSoundBudget = clamped;
			save();
		}
	}

	public static int getMinAmbientSoundBudget() {
		return MIN_AMBIENT_SOUND_BUDGET;
	}

	public static int getMaxAmbientSoundBudget() {
		return MAX_AMBIENT_SOUND_BUDGET;
	}

	public static boolean isServerEntityTickThrottling() {
		return serverEntityTickThrottling;
	}

	public static boolean toggleServerEntityTickThrottling() {
		setServerEntityTickThrottling(!serverEntityTickThrottling);
		return serverEntityTickThrottling;
	}

	public static void setServerEntityTickThrottling(boolean enabled) {
		if (serverEntityTickThrottling != enabled) {
			serverEntityTickThrottling = enabled;
			save();
		}
	}

	public static int getFarEntityTickInterval() {
		return farEntityTickInterval;
	}

	public static void setFarEntityTickInterval(int tickInterval) {
		int clamped = clamp(tickInterval, MIN_FAR_ENTITY_TICK_INTERVAL, MAX_FAR_ENTITY_TICK_INTERVAL);
		if (farEntityTickInterval != clamped) {
			farEntityTickInterval = clamped;
			save();
		}
	}

	public static int getMinFarEntityTickInterval() {
		return MIN_FAR_ENTITY_TICK_INTERVAL;
	}

	public static int getMaxFarEntityTickInterval() {
		return MAX_FAR_ENTITY_TICK_INTERVAL;
	}

	public static boolean isAdaptiveOptimizer() {
		return adaptiveOptimizer;
	}

	public static boolean toggleAdaptiveOptimizer() {
		setAdaptiveOptimizer(!adaptiveOptimizer);
		return adaptiveOptimizer;
	}

	public static void setAdaptiveOptimizer(boolean enabled) {
		if (adaptiveOptimizer != enabled) {
			adaptiveOptimizer = enabled;
			save();
		}
	}

	public static boolean isAdaptiveSimulationDistance() {
		return adaptiveSimulationDistance;
	}

	public static boolean toggleAdaptiveSimulationDistance() {
		setAdaptiveSimulationDistance(!adaptiveSimulationDistance);
		return adaptiveSimulationDistance;
	}

	public static void setAdaptiveSimulationDistance(boolean enabled) {
		if (adaptiveSimulationDistance != enabled) {
			adaptiveSimulationDistance = enabled;
			save();
		}
	}

	public static int getAdaptiveSimulationTargetMspt() {
		return adaptiveSimulationTargetMspt;
	}

	public static void setAdaptiveSimulationTargetMspt(int targetMspt) {
		int clamped = clamp(targetMspt, MIN_ADAPTIVE_SIMULATION_TARGET_MSPT, MAX_ADAPTIVE_SIMULATION_TARGET_MSPT);
		if (adaptiveSimulationTargetMspt != clamped) {
			adaptiveSimulationTargetMspt = clamped;
			save();
		}
	}

	public static int getMinAdaptiveSimulationTargetMspt() {
		return MIN_ADAPTIVE_SIMULATION_TARGET_MSPT;
	}

	public static int getMaxAdaptiveSimulationTargetMspt() {
		return MAX_ADAPTIVE_SIMULATION_TARGET_MSPT;
	}

	public static int getAdaptiveSimulationMinDistanceChunks() {
		return adaptiveSimulationMinDistanceChunks;
	}

	public static void setAdaptiveSimulationMinDistanceChunks(int distanceChunks) {
		int clamped = clamp(distanceChunks, MIN_ADAPTIVE_SIMULATION_MIN_DISTANCE_CHUNKS, MAX_ADAPTIVE_SIMULATION_MIN_DISTANCE_CHUNKS);
		if (adaptiveSimulationMinDistanceChunks != clamped) {
			adaptiveSimulationMinDistanceChunks = clamped;
			save();
		}
	}

	public static int getMinAdaptiveSimulationMinDistanceChunks() {
		return MIN_ADAPTIVE_SIMULATION_MIN_DISTANCE_CHUNKS;
	}

	public static int getMaxAdaptiveSimulationMinDistanceChunks() {
		return MAX_ADAPTIVE_SIMULATION_MIN_DISTANCE_CHUNKS;
	}

	public static boolean isItemVirtualization() {
		return itemVirtualization;
	}

	public static boolean toggleItemVirtualization() {
		setItemVirtualization(!itemVirtualization);
		return itemVirtualization;
	}

	public static void setItemVirtualization(boolean enabled) {
		if (itemVirtualization != enabled) {
			itemVirtualization = enabled;
			save();
		}
	}

	public static int getItemClusterThreshold() {
		return itemClusterThreshold;
	}

	public static void setItemClusterThreshold(int threshold) {
		int clamped = clamp(threshold, MIN_ITEM_CLUSTER_THRESHOLD, MAX_ITEM_CLUSTER_THRESHOLD);
		if (itemClusterThreshold != clamped) {
			itemClusterThreshold = clamped;
			save();
		}
	}

	public static int getMinItemClusterThreshold() {
		return MIN_ITEM_CLUSTER_THRESHOLD;
	}

	public static int getMaxItemClusterThreshold() {
		return MAX_ITEM_CLUSTER_THRESHOLD;
	}

	public static boolean isXpOrbMerging() {
		return xpOrbMerging;
	}

	public static boolean toggleXpOrbMerging() {
		setXpOrbMerging(!xpOrbMerging);
		return xpOrbMerging;
	}

	public static void setXpOrbMerging(boolean enabled) {
		if (xpOrbMerging != enabled) {
			xpOrbMerging = enabled;
			save();
		}
	}

	public static int getXpMergeThreshold() {
		return xpMergeThreshold;
	}

	public static void setXpMergeThreshold(int threshold) {
		int clamped = clamp(threshold, MIN_XP_MERGE_THRESHOLD, MAX_XP_MERGE_THRESHOLD);
		if (xpMergeThreshold != clamped) {
			xpMergeThreshold = clamped;
			save();
		}
	}

	public static int getMinXpMergeThreshold() {
		return MIN_XP_MERGE_THRESHOLD;
	}

	public static int getMaxXpMergeThreshold() {
		return MAX_XP_MERGE_THRESHOLD;
	}

	public static boolean isRedstoneDeduplication() {
		return redstoneDeduplication;
	}

	public static boolean toggleRedstoneDeduplication() {
		setRedstoneDeduplication(!redstoneDeduplication);
		return redstoneDeduplication;
	}

	public static void setRedstoneDeduplication(boolean enabled) {
		if (redstoneDeduplication != enabled) {
			redstoneDeduplication = enabled;
			save();
		}
	}

	public static boolean isBlockEntityUpdateThrottling() {
		return blockEntityUpdateThrottling;
	}

	public static boolean toggleBlockEntityUpdateThrottling() {
		setBlockEntityUpdateThrottling(!blockEntityUpdateThrottling);
		return blockEntityUpdateThrottling;
	}

	public static void setBlockEntityUpdateThrottling(boolean enabled) {
		if (blockEntityUpdateThrottling != enabled) {
			blockEntityUpdateThrottling = enabled;
			save();
		}
	}

	public static int getBlockEntitySleepAfterTicks() {
		return blockEntitySleepAfterTicks;
	}

	public static void setBlockEntitySleepAfterTicks(int ticks) {
		int clamped = clamp(ticks, MIN_BLOCK_ENTITY_SLEEP_AFTER_TICKS, MAX_BLOCK_ENTITY_SLEEP_AFTER_TICKS);
		if (blockEntitySleepAfterTicks != clamped) {
			blockEntitySleepAfterTicks = clamped;
			save();
		}
	}

	public static int getMinBlockEntitySleepAfterTicks() {
		return MIN_BLOCK_ENTITY_SLEEP_AFTER_TICKS;
	}

	public static int getMaxBlockEntitySleepAfterTicks() {
		return MAX_BLOCK_ENTITY_SLEEP_AFTER_TICKS;
	}

	public static int getBlockEntityWakeRadiusBlocks() {
		return blockEntityWakeRadiusBlocks;
	}

	public static void setBlockEntityWakeRadiusBlocks(int radiusBlocks) {
		int clamped = clamp(radiusBlocks, MIN_BLOCK_ENTITY_WAKE_RADIUS_BLOCKS, MAX_BLOCK_ENTITY_WAKE_RADIUS_BLOCKS);
		if (blockEntityWakeRadiusBlocks != clamped) {
			blockEntityWakeRadiusBlocks = clamped;
			save();
		}
	}

	public static int getMinBlockEntityWakeRadiusBlocks() {
		return MIN_BLOCK_ENTITY_WAKE_RADIUS_BLOCKS;
	}

	public static int getMaxBlockEntityWakeRadiusBlocks() {
		return MAX_BLOCK_ENTITY_WAKE_RADIUS_BLOCKS;
	}

	public static int getSleepingBlockEntityTicksPerTick() {
		return sleepingBlockEntityTicksPerTick;
	}

	public static void setSleepingBlockEntityTicksPerTick(int ticksPerTick) {
		int clamped = clamp(ticksPerTick, MIN_SLEEPING_BLOCK_ENTITY_TICKS_PER_TICK, MAX_SLEEPING_BLOCK_ENTITY_TICKS_PER_TICK);
		if (sleepingBlockEntityTicksPerTick != clamped) {
			sleepingBlockEntityTicksPerTick = clamped;
			save();
		}
	}

	public static int getMinSleepingBlockEntityTicksPerTick() {
		return MIN_SLEEPING_BLOCK_ENTITY_TICKS_PER_TICK;
	}

	public static int getMaxSleepingBlockEntityTicksPerTick() {
		return MAX_SLEEPING_BLOCK_ENTITY_TICKS_PER_TICK;
	}

	public static int getSleepingBlockEntityTickInterval() {
		return sleepingBlockEntityTickInterval;
	}

	public static void setSleepingBlockEntityTickInterval(int tickInterval) {
		int clamped = clamp(tickInterval, MIN_SLEEPING_BLOCK_ENTITY_TICK_INTERVAL, MAX_SLEEPING_BLOCK_ENTITY_TICK_INTERVAL);
		if (sleepingBlockEntityTickInterval != clamped) {
			sleepingBlockEntityTickInterval = clamped;
			save();
		}
	}

	public static int getMinSleepingBlockEntityTickInterval() {
		return MIN_SLEEPING_BLOCK_ENTITY_TICK_INTERVAL;
	}

	public static int getMaxSleepingBlockEntityTickInterval() {
		return MAX_SLEEPING_BLOCK_ENTITY_TICK_INTERVAL;
	}

	private static void load() {
		if (!Files.isRegularFile(CONFIG_FILE)) {
			return;
		}
		Properties properties = new Properties();
		try (InputStream input = Files.newInputStream(CONFIG_FILE)) {
			properties.load(input);
			enabled = parseBoolean(properties, ENABLED_KEY, true);
			fogDistanceBlocks = parseClamped(properties, FOG_DISTANCE_KEY, DEFAULT_FOG_DISTANCE_BLOCKS, MIN_FOG_DISTANCE_BLOCKS, MAX_FOG_DISTANCE_BLOCKS);
			chunkRebuildScheduling = parseBoolean(properties, CHUNK_REBUILD_SCHEDULING_KEY, true);
			chunkRebuildsPerFrame = parseClamped(properties, CHUNK_REBUILDS_PER_FRAME_KEY, DEFAULT_CHUNK_REBUILDS_PER_FRAME, MIN_CHUNK_REBUILDS_PER_FRAME, MAX_CHUNK_REBUILDS_PER_FRAME);
			syncChunkRebuildsPerFrame = parseClamped(properties, SYNC_CHUNK_REBUILDS_PER_FRAME_KEY, DEFAULT_SYNC_CHUNK_REBUILDS_PER_FRAME, MIN_SYNC_CHUNK_REBUILDS_PER_FRAME,
					MAX_SYNC_CHUNK_REBUILDS_PER_FRAME);
			lightingDeduplication = parseBoolean(properties, LIGHTING_DEDUPLICATION_KEY, true);
			gpuOptimizer = parseBoolean(properties, GPU_OPTIMIZER_KEY, true);
			gpuTargetFps = parseClamped(properties, GPU_TARGET_FPS_KEY, DEFAULT_GPU_TARGET_FPS, MIN_GPU_TARGET_FPS, MAX_GPU_TARGET_FPS);
			gpuMinRenderScalePercent = parseClamped(properties, GPU_MIN_RENDER_SCALE_KEY, DEFAULT_GPU_MIN_RENDER_SCALE_PERCENT, MIN_GPU_MIN_RENDER_SCALE_PERCENT,
					MAX_GPU_MIN_RENDER_SCALE_PERCENT);
			clientRenderCulling = parseBoolean(properties, CLIENT_RENDER_CULLING_KEY, true);
			entityRenderDistanceScalePercent = parseClamped(properties, ENTITY_RENDER_DISTANCE_SCALE_KEY, DEFAULT_DISTANCE_SCALE_PERCENT, MIN_DISTANCE_SCALE_PERCENT, MAX_DISTANCE_SCALE_PERCENT);
			blockEntityCulling = parseBoolean(properties, BLOCK_ENTITY_CULLING_KEY, true);
			blockEntityDistanceScalePercent = parseClamped(properties, BLOCK_ENTITY_DISTANCE_SCALE_KEY, DEFAULT_DISTANCE_SCALE_PERCENT, MIN_DISTANCE_SCALE_PERCENT, MAX_DISTANCE_SCALE_PERCENT);
			crowdCulling = parseBoolean(properties, CROWD_CULLING_KEY, true);
			crowdRenderBudgetPercent = parseClamped(properties, CROWD_RENDER_BUDGET_KEY, DEFAULT_CROWD_RENDER_BUDGET_PERCENT, MIN_CROWD_RENDER_BUDGET_PERCENT, MAX_CROWD_RENDER_BUDGET_PERCENT);
			particleLimiter = parseBoolean(properties, PARTICLE_LIMITER_KEY, true);
			particleRenderDistanceBlocks = parseClamped(properties, PARTICLE_RENDER_DISTANCE_KEY, DEFAULT_PARTICLE_RENDER_DISTANCE_BLOCKS, MIN_PARTICLE_RENDER_DISTANCE_BLOCKS,
					MAX_PARTICLE_RENDER_DISTANCE_BLOCKS);
			maxParticlesPerFrame = parseClamped(properties, MAX_PARTICLES_PER_FRAME_KEY, DEFAULT_MAX_PARTICLES_PER_FRAME, MIN_MAX_PARTICLES_PER_FRAME, MAX_MAX_PARTICLES_PER_FRAME);
			ambientSoundLimiter = parseBoolean(properties, AMBIENT_SOUND_LIMITER_KEY, true);
			ambientSoundBudget = parseClamped(properties, AMBIENT_SOUND_BUDGET_KEY, DEFAULT_AMBIENT_SOUND_BUDGET, MIN_AMBIENT_SOUND_BUDGET, MAX_AMBIENT_SOUND_BUDGET);
			serverEntityTickThrottling = parseBoolean(properties, SERVER_ENTITY_TICK_THROTTLING_KEY, true);
			farEntityTickInterval = parseClamped(properties, FAR_ENTITY_TICK_INTERVAL_KEY, DEFAULT_FAR_ENTITY_TICK_INTERVAL, MIN_FAR_ENTITY_TICK_INTERVAL, MAX_FAR_ENTITY_TICK_INTERVAL);
			adaptiveOptimizer = parseBoolean(properties, ADAPTIVE_OPTIMIZER_KEY, true);
			adaptiveSimulationDistance = parseBoolean(properties, ADAPTIVE_SIMULATION_DISTANCE_KEY, true);
			adaptiveSimulationTargetMspt = parseClamped(properties, ADAPTIVE_SIMULATION_TARGET_MSPT_KEY, DEFAULT_ADAPTIVE_SIMULATION_TARGET_MSPT, MIN_ADAPTIVE_SIMULATION_TARGET_MSPT,
					MAX_ADAPTIVE_SIMULATION_TARGET_MSPT);
			adaptiveSimulationMinDistanceChunks = parseClamped(properties, ADAPTIVE_SIMULATION_MIN_DISTANCE_KEY, DEFAULT_ADAPTIVE_SIMULATION_MIN_DISTANCE_CHUNKS,
					MIN_ADAPTIVE_SIMULATION_MIN_DISTANCE_CHUNKS, MAX_ADAPTIVE_SIMULATION_MIN_DISTANCE_CHUNKS);
			itemVirtualization = parseBoolean(properties, ITEM_VIRTUALIZATION_KEY, true);
			itemClusterThreshold = parseClamped(properties, ITEM_CLUSTER_THRESHOLD_KEY, DEFAULT_ITEM_CLUSTER_THRESHOLD, MIN_ITEM_CLUSTER_THRESHOLD, MAX_ITEM_CLUSTER_THRESHOLD);
			xpOrbMerging = parseBoolean(properties, XP_ORB_MERGING_KEY, true);
			xpMergeThreshold = parseClamped(properties, XP_MERGE_THRESHOLD_KEY, DEFAULT_XP_MERGE_THRESHOLD, MIN_XP_MERGE_THRESHOLD, MAX_XP_MERGE_THRESHOLD);
			redstoneDeduplication = parseBoolean(properties, REDSTONE_DEDUPLICATION_KEY, true);
			blockEntityUpdateThrottling = parseBoolean(properties, BLOCK_ENTITY_UPDATE_THROTTLING_KEY, true);
			blockEntitySleepAfterTicks = parseClamped(properties, BLOCK_ENTITY_SLEEP_AFTER_TICKS_KEY, DEFAULT_BLOCK_ENTITY_SLEEP_AFTER_TICKS, MIN_BLOCK_ENTITY_SLEEP_AFTER_TICKS,
					MAX_BLOCK_ENTITY_SLEEP_AFTER_TICKS);
			blockEntityWakeRadiusBlocks = parseClamped(properties, BLOCK_ENTITY_WAKE_RADIUS_BLOCKS_KEY, DEFAULT_BLOCK_ENTITY_WAKE_RADIUS_BLOCKS, MIN_BLOCK_ENTITY_WAKE_RADIUS_BLOCKS,
					MAX_BLOCK_ENTITY_WAKE_RADIUS_BLOCKS);
			sleepingBlockEntityTicksPerTick = parseClamped(properties, SLEEPING_BLOCK_ENTITY_TICKS_PER_TICK_KEY, DEFAULT_SLEEPING_BLOCK_ENTITY_TICKS_PER_TICK,
					MIN_SLEEPING_BLOCK_ENTITY_TICKS_PER_TICK, MAX_SLEEPING_BLOCK_ENTITY_TICKS_PER_TICK);
			sleepingBlockEntityTickInterval = parseClamped(properties, SLEEPING_BLOCK_ENTITY_TICK_INTERVAL_KEY, DEFAULT_SLEEPING_BLOCK_ENTITY_TICK_INTERVAL,
					MIN_SLEEPING_BLOCK_ENTITY_TICK_INTERVAL, MAX_SLEEPING_BLOCK_ENTITY_TICK_INTERVAL);
		} catch (IOException ignored) {
		}
	}

	private static void save() {
		Properties properties = new Properties();
		properties.setProperty(ENABLED_KEY, Boolean.toString(enabled));
		properties.setProperty(FOG_DISTANCE_KEY, Integer.toString(fogDistanceBlocks));
		properties.setProperty(CHUNK_REBUILD_SCHEDULING_KEY, Boolean.toString(chunkRebuildScheduling));
		properties.setProperty(CHUNK_REBUILDS_PER_FRAME_KEY, Integer.toString(chunkRebuildsPerFrame));
		properties.setProperty(SYNC_CHUNK_REBUILDS_PER_FRAME_KEY, Integer.toString(syncChunkRebuildsPerFrame));
		properties.setProperty(LIGHTING_DEDUPLICATION_KEY, Boolean.toString(lightingDeduplication));
		properties.setProperty(GPU_OPTIMIZER_KEY, Boolean.toString(gpuOptimizer));
		properties.setProperty(GPU_TARGET_FPS_KEY, Integer.toString(gpuTargetFps));
		properties.setProperty(GPU_MIN_RENDER_SCALE_KEY, Integer.toString(gpuMinRenderScalePercent));
		properties.setProperty(CLIENT_RENDER_CULLING_KEY, Boolean.toString(clientRenderCulling));
		properties.setProperty(ENTITY_RENDER_DISTANCE_SCALE_KEY, Integer.toString(entityRenderDistanceScalePercent));
		properties.setProperty(BLOCK_ENTITY_CULLING_KEY, Boolean.toString(blockEntityCulling));
		properties.setProperty(BLOCK_ENTITY_DISTANCE_SCALE_KEY, Integer.toString(blockEntityDistanceScalePercent));
		properties.setProperty(CROWD_CULLING_KEY, Boolean.toString(crowdCulling));
		properties.setProperty(CROWD_RENDER_BUDGET_KEY, Integer.toString(crowdRenderBudgetPercent));
		properties.setProperty(PARTICLE_LIMITER_KEY, Boolean.toString(particleLimiter));
		properties.setProperty(PARTICLE_RENDER_DISTANCE_KEY, Integer.toString(particleRenderDistanceBlocks));
		properties.setProperty(MAX_PARTICLES_PER_FRAME_KEY, Integer.toString(maxParticlesPerFrame));
		properties.setProperty(AMBIENT_SOUND_LIMITER_KEY, Boolean.toString(ambientSoundLimiter));
		properties.setProperty(AMBIENT_SOUND_BUDGET_KEY, Integer.toString(ambientSoundBudget));
		properties.setProperty(SERVER_ENTITY_TICK_THROTTLING_KEY, Boolean.toString(serverEntityTickThrottling));
		properties.setProperty(FAR_ENTITY_TICK_INTERVAL_KEY, Integer.toString(farEntityTickInterval));
		properties.setProperty(ADAPTIVE_OPTIMIZER_KEY, Boolean.toString(adaptiveOptimizer));
		properties.setProperty(ADAPTIVE_SIMULATION_DISTANCE_KEY, Boolean.toString(adaptiveSimulationDistance));
		properties.setProperty(ADAPTIVE_SIMULATION_TARGET_MSPT_KEY, Integer.toString(adaptiveSimulationTargetMspt));
		properties.setProperty(ADAPTIVE_SIMULATION_MIN_DISTANCE_KEY, Integer.toString(adaptiveSimulationMinDistanceChunks));
		properties.setProperty(ITEM_VIRTUALIZATION_KEY, Boolean.toString(itemVirtualization));
		properties.setProperty(ITEM_CLUSTER_THRESHOLD_KEY, Integer.toString(itemClusterThreshold));
		properties.setProperty(XP_ORB_MERGING_KEY, Boolean.toString(xpOrbMerging));
		properties.setProperty(XP_MERGE_THRESHOLD_KEY, Integer.toString(xpMergeThreshold));
		properties.setProperty(REDSTONE_DEDUPLICATION_KEY, Boolean.toString(redstoneDeduplication));
		properties.setProperty(BLOCK_ENTITY_UPDATE_THROTTLING_KEY, Boolean.toString(blockEntityUpdateThrottling));
		properties.setProperty(BLOCK_ENTITY_SLEEP_AFTER_TICKS_KEY, Integer.toString(blockEntitySleepAfterTicks));
		properties.setProperty(BLOCK_ENTITY_WAKE_RADIUS_BLOCKS_KEY, Integer.toString(blockEntityWakeRadiusBlocks));
		properties.setProperty(SLEEPING_BLOCK_ENTITY_TICKS_PER_TICK_KEY, Integer.toString(sleepingBlockEntityTicksPerTick));
		properties.setProperty(SLEEPING_BLOCK_ENTITY_TICK_INTERVAL_KEY, Integer.toString(sleepingBlockEntityTickInterval));
		try {
			Files.createDirectories(CONFIG_FILE.getParent());
			try (OutputStream output = Files.newOutputStream(CONFIG_FILE)) {
				properties.store(output, "Optiminium settings");
			}
		} catch (IOException ignored) {
		}
	}

	private static boolean parseBoolean(Properties properties, String key, boolean fallback) {
		String value = properties.getProperty(key);
		if ("true".equalsIgnoreCase(value)) {
			return true;
		}
		if ("false".equalsIgnoreCase(value)) {
			return false;
		}
		return fallback;
	}

	private static int parseClamped(Properties properties, String key, int fallback, int min, int max) {
		try {
			return clamp(Integer.parseInt(properties.getProperty(key, Integer.toString(fallback))), min, max);
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
