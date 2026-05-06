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
	private static final String CAMERA_CHUNK_LOADING_KEY = "cameraChunkLoading";
	private static final String CAMERA_ALWAYS_TRACK_RADIUS_KEY = "cameraAlwaysTrackRadiusChunks";
	private static final String CAMERA_YAW_STEP_DEGREES_KEY = "cameraYawStepDegrees";
	private static final String CAMERA_CHUNK_GROUP_SIZE_KEY = "cameraChunkGroupSizeChunks";
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
	private static final String ADAPTIVE_SIMULATION_DISTANCE_KEY = "adaptiveSimulationDistance";
	private static final String ADAPTIVE_SIMULATION_TARGET_MSPT_KEY = "adaptiveSimulationTargetMspt";
	private static final String ADAPTIVE_SIMULATION_MIN_DISTANCE_KEY = "adaptiveSimulationMinDistanceChunks";
	private static final String ITEM_VIRTUALIZATION_KEY = "itemVirtualization";
	private static final String ITEM_CLUSTER_THRESHOLD_KEY = "itemClusterThreshold";
	private static final String XP_ORB_MERGING_KEY = "xpOrbMerging";
	private static final String XP_MERGE_THRESHOLD_KEY = "xpMergeThreshold";
	private static final String REDSTONE_DEDUPLICATION_KEY = "redstoneDeduplication";

	private static final Path CONFIG_FILE = FMLPaths.CONFIGDIR.get().resolve("optiminium.properties");

	private static final int MIN_FOG_DISTANCE_BLOCKS = 32;
	private static final int MAX_FOG_DISTANCE_BLOCKS = 512;
	private static final int DEFAULT_FOG_DISTANCE_BLOCKS = 192;
	private static final int MIN_CAMERA_ALWAYS_TRACK_RADIUS_CHUNKS = 0;
	private static final int MAX_CAMERA_ALWAYS_TRACK_RADIUS_CHUNKS = 4;
	private static final int DEFAULT_CAMERA_ALWAYS_TRACK_RADIUS_CHUNKS = 2;
	private static final int MIN_CAMERA_YAW_STEP_DEGREES = 1;
	private static final int MAX_CAMERA_YAW_STEP_DEGREES = 45;
	private static final int DEFAULT_CAMERA_YAW_STEP_DEGREES = 15;
	private static final int MIN_CAMERA_CHUNK_GROUP_SIZE_CHUNKS = 1;
	private static final int MAX_CAMERA_CHUNK_GROUP_SIZE_CHUNKS = 8;
	private static final int DEFAULT_CAMERA_CHUNK_GROUP_SIZE_CHUNKS = 4;
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

	private static volatile boolean enabled = true;
	private static volatile int fogDistanceBlocks = DEFAULT_FOG_DISTANCE_BLOCKS;
	private static volatile boolean cameraChunkLoading = false;
	private static volatile int cameraAlwaysTrackRadiusChunks = DEFAULT_CAMERA_ALWAYS_TRACK_RADIUS_CHUNKS;
	private static volatile int cameraYawStepDegrees = DEFAULT_CAMERA_YAW_STEP_DEGREES;
	private static volatile int cameraChunkGroupSizeChunks = DEFAULT_CAMERA_CHUNK_GROUP_SIZE_CHUNKS;
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
	private static volatile boolean adaptiveSimulationDistance = true;
	private static volatile int adaptiveSimulationTargetMspt = DEFAULT_ADAPTIVE_SIMULATION_TARGET_MSPT;
	private static volatile int adaptiveSimulationMinDistanceChunks = DEFAULT_ADAPTIVE_SIMULATION_MIN_DISTANCE_CHUNKS;
	private static volatile boolean itemVirtualization = true;
	private static volatile int itemClusterThreshold = DEFAULT_ITEM_CLUSTER_THRESHOLD;
	private static volatile boolean xpOrbMerging = true;
	private static volatile int xpMergeThreshold = DEFAULT_XP_MERGE_THRESHOLD;
	private static volatile boolean redstoneDeduplication = true;

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

	public static boolean isCameraChunkLoading() {
		return cameraChunkLoading;
	}

	public static void setCameraChunkLoading(boolean enabled) {
		if (cameraChunkLoading != enabled) {
			cameraChunkLoading = enabled;
			save();
		}
	}

	public static boolean toggleCameraChunkLoading() {
		setCameraChunkLoading(!cameraChunkLoading);
		return cameraChunkLoading;
	}

	public static int getCameraAlwaysTrackRadiusChunks() {
		return cameraAlwaysTrackRadiusChunks;
	}

	public static void setCameraAlwaysTrackRadiusChunks(int radiusChunks) {
		int clamped = clamp(radiusChunks, MIN_CAMERA_ALWAYS_TRACK_RADIUS_CHUNKS, MAX_CAMERA_ALWAYS_TRACK_RADIUS_CHUNKS);
		if (cameraAlwaysTrackRadiusChunks != clamped) {
			cameraAlwaysTrackRadiusChunks = clamped;
			save();
		}
	}

	public static int getMinCameraAlwaysTrackRadiusChunks() {
		return MIN_CAMERA_ALWAYS_TRACK_RADIUS_CHUNKS;
	}

	public static int getMaxCameraAlwaysTrackRadiusChunks() {
		return MAX_CAMERA_ALWAYS_TRACK_RADIUS_CHUNKS;
	}

	public static int getCameraYawStepDegrees() {
		return cameraYawStepDegrees;
	}

	public static void setCameraYawStepDegrees(int yawStepDegrees) {
		int clamped = clamp(yawStepDegrees, MIN_CAMERA_YAW_STEP_DEGREES, MAX_CAMERA_YAW_STEP_DEGREES);
		if (cameraYawStepDegrees != clamped) {
			cameraYawStepDegrees = clamped;
			save();
		}
	}

	public static int getMinCameraYawStepDegrees() {
		return MIN_CAMERA_YAW_STEP_DEGREES;
	}

	public static int getMaxCameraYawStepDegrees() {
		return MAX_CAMERA_YAW_STEP_DEGREES;
	}

	public static int getCameraChunkGroupSizeChunks() {
		return cameraChunkGroupSizeChunks;
	}

	public static void setCameraChunkGroupSizeChunks(int groupSizeChunks) {
		int clamped = clamp(groupSizeChunks, MIN_CAMERA_CHUNK_GROUP_SIZE_CHUNKS, MAX_CAMERA_CHUNK_GROUP_SIZE_CHUNKS);
		if (cameraChunkGroupSizeChunks != clamped) {
			cameraChunkGroupSizeChunks = clamped;
			save();
		}
	}

	public static int getMinCameraChunkGroupSizeChunks() {
		return MIN_CAMERA_CHUNK_GROUP_SIZE_CHUNKS;
	}

	public static int getMaxCameraChunkGroupSizeChunks() {
		return MAX_CAMERA_CHUNK_GROUP_SIZE_CHUNKS;
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

	private static void load() {
		if (!Files.isRegularFile(CONFIG_FILE)) {
			return;
		}
		Properties properties = new Properties();
		try (InputStream input = Files.newInputStream(CONFIG_FILE)) {
			properties.load(input);
			enabled = parseBoolean(properties, ENABLED_KEY, true);
			fogDistanceBlocks = parseClamped(properties, FOG_DISTANCE_KEY, DEFAULT_FOG_DISTANCE_BLOCKS, MIN_FOG_DISTANCE_BLOCKS, MAX_FOG_DISTANCE_BLOCKS);
			cameraChunkLoading = parseBoolean(properties, CAMERA_CHUNK_LOADING_KEY, false);
			cameraAlwaysTrackRadiusChunks = parseClamped(properties, CAMERA_ALWAYS_TRACK_RADIUS_KEY, DEFAULT_CAMERA_ALWAYS_TRACK_RADIUS_CHUNKS, MIN_CAMERA_ALWAYS_TRACK_RADIUS_CHUNKS,
					MAX_CAMERA_ALWAYS_TRACK_RADIUS_CHUNKS);
			cameraYawStepDegrees = parseClamped(properties, CAMERA_YAW_STEP_DEGREES_KEY, DEFAULT_CAMERA_YAW_STEP_DEGREES, MIN_CAMERA_YAW_STEP_DEGREES, MAX_CAMERA_YAW_STEP_DEGREES);
			cameraChunkGroupSizeChunks = parseClamped(properties, CAMERA_CHUNK_GROUP_SIZE_KEY, DEFAULT_CAMERA_CHUNK_GROUP_SIZE_CHUNKS, MIN_CAMERA_CHUNK_GROUP_SIZE_CHUNKS,
					MAX_CAMERA_CHUNK_GROUP_SIZE_CHUNKS);
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
		} catch (IOException ignored) {
		}
	}

	private static void save() {
		Properties properties = new Properties();
		properties.setProperty(ENABLED_KEY, Boolean.toString(enabled));
		properties.setProperty(FOG_DISTANCE_KEY, Integer.toString(fogDistanceBlocks));
		properties.setProperty(CAMERA_CHUNK_LOADING_KEY, Boolean.toString(cameraChunkLoading));
		properties.setProperty(CAMERA_ALWAYS_TRACK_RADIUS_KEY, Integer.toString(cameraAlwaysTrackRadiusChunks));
		properties.setProperty(CAMERA_YAW_STEP_DEGREES_KEY, Integer.toString(cameraYawStepDegrees));
		properties.setProperty(CAMERA_CHUNK_GROUP_SIZE_KEY, Integer.toString(cameraChunkGroupSizeChunks));
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
		properties.setProperty(ADAPTIVE_SIMULATION_DISTANCE_KEY, Boolean.toString(adaptiveSimulationDistance));
		properties.setProperty(ADAPTIVE_SIMULATION_TARGET_MSPT_KEY, Integer.toString(adaptiveSimulationTargetMspt));
		properties.setProperty(ADAPTIVE_SIMULATION_MIN_DISTANCE_KEY, Integer.toString(adaptiveSimulationMinDistanceChunks));
		properties.setProperty(ITEM_VIRTUALIZATION_KEY, Boolean.toString(itemVirtualization));
		properties.setProperty(ITEM_CLUSTER_THRESHOLD_KEY, Integer.toString(itemClusterThreshold));
		properties.setProperty(XP_ORB_MERGING_KEY, Boolean.toString(xpOrbMerging));
		properties.setProperty(XP_MERGE_THRESHOLD_KEY, Integer.toString(xpMergeThreshold));
		properties.setProperty(REDSTONE_DEDUPLICATION_KEY, Boolean.toString(redstoneDeduplication));
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
