package net.optiminium.optimization;

import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side optimization layer for Optiminium.
 *
 * <p>NeoForge exposes cancellable entity tick events, so entity and item optimizations are active here.
 * Block-entity and pathfinding systems are tracked conservatively because replacing vanilla internals safely
 * requires deeper core hooks.</p>
 */
@EventBusSubscriber(modid = "optiminium")
public final class OptiminiumOptimizer {
	private static final int IDLE_SAMPLE_TICKS = 5;
	private static final int OCCLUDED_SAMPLE_TICKS = 10;
	private static final int ENTITY_STATE_TTL = 20 * 60;
	private static final int ITEM_ABSORB_MIN_AGE = 80;
	private static final int ITEM_PLAYER_SAFE_RADIUS = 18;
	private static final int ITEM_RELEASE_RADIUS = 20;
	private static final int ITEM_RELEASES_PER_PLAYER_TICK = 2;
	private static final int ITEM_SCAN_PERIOD_TICKS = 600;
	private static final int ITEM_SCAN_PHASE_TICKS = 37;
	private static final int ITEM_SCAN_BUDGET_PER_TICK = 96;
	private static final int XP_SCAN_PERIOD_TICKS = 200;
	private static final int XP_SCAN_PHASE_TICKS = 103;
	private static final int XP_SCAN_BUDGET_PER_TICK = 128;
	private static final int XP_PLAYER_SAFE_RADIUS = 8;
	private static final int REGION_CHUNK_SIZE = 4;
	private static final int REDSTONE_DUPLICATE_WINDOW_TICKS = 2;
	private static final int REDSTONE_CLEANUP_PERIOD_TICKS = 20;
	private static final int ENTITY_CLEANUP_PERIOD_TICKS = 200;
	private static final int BLOCK_ENTITY_CLEANUP_PERIOD_TICKS = 600;
	private static final int NEARBY_IDLE_CROWD_SAMPLE_TICKS = 4;
	private static final int VILLAGER_CROWD_THRESHOLD = 6;
	private static final double VILLAGER_CROWD_RADIUS_BLOCKS = 5.0D;
	private static final int DEGRADED_TPS_SAMPLE_TICKS = 20;
	private static final int DEGRADED_TPS_HOLD_TICKS = 20 * 10;
	private static final int DEGRADED_TPS_STABLE_RECOVERY_WINDOWS = 3;
	private static final double DEGRADED_MSPT_MARGIN = 3.0D;
	private static final double RECOVERED_MSPT_MARGIN = 8.0D;

	private static final ServerHealthMonitor serverHealth = new ServerHealthMonitor();
	private static final PredictiveTickScheduler predictiveTicks = new PredictiveTickScheduler();
	private static final VirtualizedItemSystem virtualItems = new VirtualizedItemSystem();
	private static final RedstoneGraphSolver redstoneGraph = new RedstoneGraphSolver();
	private static final AdaptiveSimulationDistance adaptiveDistance = new AdaptiveSimulationDistance();
	private static final BlockEntitySleepIndex blockEntitySleep = new BlockEntitySleepIndex();
	private static final XpOrbOptimizer xpOrbs = new XpOrbOptimizer();
	private static final PlayerSnapshotIndex playerSnapshots = new PlayerSnapshotIndex();

	@SubscribeEvent
	public static void onServerTickPre(ServerTickEvent.Pre event) {
		MinecraftServer server = event.getServer();
		long tick = server.getTickCount();
		if (!OptiminiumSettings.isEnabled()) {
			serverHealth.reset();
			return;
		}
		serverHealth.update(server, tick);
		if (needsPlayerSnapshots()) {
			playerSnapshots.refresh(server, tick);
		}
		if (shouldRunBlockEntityThrottling()) {
			blockEntitySleep.beginTick(tick);
		}
	}

	@SubscribeEvent
	public static void onServerTickPost(ServerTickEvent.Post event) {
		MinecraftServer server = event.getServer();
		long tick = server.getTickCount();
		if (!OptiminiumSettings.isEnabled()) {
			serverHealth.reset();
			adaptiveDistance.pause(server);
			virtualItems.releaseAll(server, 64);
			xpOrbs.pauseScans();
			return;
		}
		boolean degradedTps = serverHealth.isDegraded();
		if (OptiminiumSettings.isAdaptiveSimulationDistance()) {
			adaptiveDistance.update(server, tick);
		} else {
			adaptiveDistance.pause(server);
		}
		if (tick % REDSTONE_CLEANUP_PERIOD_TICKS == 0) {
			redstoneGraph.cleanup(tick);
		}
		if (tick % BLOCK_ENTITY_CLEANUP_PERIOD_TICKS == 0) {
			blockEntitySleep.cleanup(tick);
		}
		if (tick % ENTITY_CLEANUP_PERIOD_TICKS == 0) {
			predictiveTicks.cleanup(tick);
		}
		if (OptiminiumSettings.isItemVirtualization()) {
			virtualItems.releaseNearPlayers(server, tick);
		} else {
			virtualItems.releaseAll(server, 64);
		}
		if (OptiminiumSettings.isAdaptiveOptimizer() && !degradedTps) {
			virtualItems.pauseScans();
			xpOrbs.pauseScans();
		}
	}

	@SubscribeEvent
	public static void onLevelTickPost(LevelTickEvent.Post event) {
		if (!OptiminiumSettings.isEnabled()) {
			return;
		}
		if (event.getLevel() instanceof ServerLevel level) {
			long tick = level.getServer().getTickCount();
			if (OptiminiumSettings.isItemVirtualization() && shouldRunAdaptiveOptimizations()) {
				virtualItems.tickScan(level, tick);
			}
			if (OptiminiumSettings.isXpOrbMerging() && shouldRunAdaptiveOptimizations()) {
				xpOrbs.tickScan(level, tick);
			}
		}
	}

	@SubscribeEvent
	public static void onEntityJoin(EntityJoinLevelEvent event) {
		if (!OptiminiumSettings.isEnabled() || !needsEntityScheduler()) {
			return;
		}
		if (event.getLevel() instanceof ServerLevel level) {
			predictiveTicks.wake(event.getEntity(), level.getServer().getTickCount() + 20);
		}
	}

	@SubscribeEvent
	public static void onEntityTickPre(EntityTickEvent.Pre event) {
		if (!OptiminiumSettings.isEnabled() || !OptiminiumSettings.isSmartTickScheduler()) {
			return;
		}
		Entity entity = event.getEntity();
		if (entity.level() instanceof ServerLevel level && predictiveTicks.shouldSkip(entity, level)) {
			event.setCanceled(true);
			OptiminiumMetrics.skippedEntityTick();
		}
	}

	@SubscribeEvent
	public static void onRegisterCommands(RegisterCommandsEvent event) {
		event.getDispatcher().register(Commands.literal("optiminium")
			.requires(source -> source.hasPermission(2))
			.then(Commands.literal("stats").executes(context -> {
				OptiminiumMetrics.Snapshot snapshot = OptiminiumMetrics.snapshot();
				context.getSource().sendSuccess(() -> Component.literal(
					"Optiminium stats: skippedTicks=" + snapshot.skippedEntityTicks()
						+ ", virtualizedItems=" + snapshot.virtualizedItems()
						+ ", mergedXpOrbs=" + snapshot.mergedXpOrbs()
						+ ", mergedXpValue=" + snapshot.mergedXpValue()
						+ ", culledRenders=" + snapshot.culledEntityRenders()
						+ ", culledBlockEntityRenders=" + snapshot.culledBlockEntityRenders()
						+ ", hiddenNameTags=" + snapshot.hiddenNameTags()
						+ ", hiddenParticles=" + snapshot.hiddenParticles()
						+ ", suppressedSounds=" + snapshot.suppressedSounds()
						+ clientStats()
				), false);
				return 1;
			}))
			.then(Commands.literal("reset").executes(context -> {
				OptiminiumMetrics.reset();
				resetClientStats();
				context.getSource().sendSuccess(() -> Component.literal("Optiminium stats reset."), false);
				return 1;
			}))
		);
	}

	@SubscribeEvent
	public static void onEntityTickPost(EntityTickEvent.Post event) {
		if (!OptiminiumSettings.isEnabled() || !needsEntityScheduler()) {
			return;
		}
		Entity entity = event.getEntity();
		if (entity.level() instanceof ServerLevel level) {
			long tick = level.getServer().getTickCount();
			predictiveTicks.recordTick(entity, level, tick);
		}
	}

	@SubscribeEvent
	public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
		if (!OptiminiumSettings.isEnabled() || !needsEntityScheduler()) {
			return;
		}
		wakeInteractedEntity(event.getTarget(), event.getEntity());
	}

	@SubscribeEvent
	public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
		if (!OptiminiumSettings.isEnabled() || !needsEntityScheduler()) {
			return;
		}
		wakeInteractedEntity(event.getTarget(), event.getEntity());
	}

	@SubscribeEvent
	public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
		if (!OptiminiumSettings.isEnabled()) {
			return;
		}
		if (event.getEntity().level() instanceof ServerLevel level) {
			long tick = level.getServer().getTickCount();
			if (shouldRunBlockEntityThrottling()) {
				blockEntitySleep.markDirty(level.dimension(), event.getPos(), tick);
			}
			if (shouldRunRedstoneDeduplication()) {
				redstoneGraph.markTouched(level.dimension(), event.getPos(), tick);
			}
		}
	}

	@SubscribeEvent
	public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
		if (!OptiminiumSettings.isEnabled()) {
			return;
		}
		if (event.getLevel() instanceof ServerLevel level) {
			long tick = level.getServer().getTickCount();
			BlockPos pos = event.getPos();
			if (shouldRunBlockEntityThrottling()) {
				blockEntitySleep.markDirty(level.dimension(), pos, tick);
				for (Direction direction : event.getNotifiedSides()) {
					blockEntitySleep.markDirty(level.dimension(), pos.relative(direction), tick);
				}
			}
			if (shouldRunRedstoneDeduplication() && redstoneGraph.isDuplicate(level.dimension(), pos, event.getState(), event.getNotifiedSides(), tick)) {
				event.setCanceled(true);
			}
		}
	}

	@SubscribeEvent
	public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
		if (!OptiminiumSettings.isEnabled()) {
			return;
		}
		if (event.getLevel() instanceof ServerLevel level) {
			long tick = level.getServer().getTickCount();
			if (shouldRunBlockEntityThrottling()) {
				blockEntitySleep.markDirty(level.dimension(), event.getPos(), tick);
			}
			if (shouldRunRedstoneDeduplication()) {
				redstoneGraph.markTouched(level.dimension(), event.getPos(), tick);
			}
		}
	}

	@SubscribeEvent
	public static void onBlockBroken(BlockEvent.BreakEvent event) {
		if (!OptiminiumSettings.isEnabled()) {
			return;
		}
		if (event.getLevel() instanceof ServerLevel level) {
			long tick = level.getServer().getTickCount();
			if (shouldRunBlockEntityThrottling()) {
				blockEntitySleep.markDirty(level.dimension(), event.getPos(), tick);
			}
			if (shouldRunRedstoneDeduplication()) {
				redstoneGraph.markTouched(level.dimension(), event.getPos(), tick);
			}
		}
	}

	private static void wakeInteractedEntity(Entity target, Player player) {
		if (player.level() instanceof ServerLevel level) {
			predictiveTicks.wake(target, level.getServer().getTickCount() + 40);
		}
	}

	public static boolean shouldSleepBlockEntity(ServerLevel level, TickingBlockEntity tickingBlockEntity) {
		if (!shouldRunBlockEntityThrottling()) {
			return false;
		}
		BlockPos pos = tickingBlockEntity.getPos();
		BlockEntity blockEntity = level.getBlockEntity(pos);
		if (blockEntity == null || !isSleepEligibleBlockEntity(blockEntity)) {
			return false;
		}

		long tick = level.getServer().getTickCount();
		blockEntitySleep.observe(level, blockEntity, tick);
		if (hasNearbyPlayer(level, Vec3.atCenterOf(pos), OptiminiumSettings.getBlockEntityWakeRadiusBlocks())) {
			blockEntitySleep.markDirty(level.dimension(), pos, tick);
			return false;
		}
		return blockEntitySleep.canSleep(level, blockEntity, tick) && !blockEntitySleep.allowSleepingTick(level, blockEntity, tick);
	}

	private static boolean needsPlayerSnapshots() {
		return needsEntityScheduler()
			|| OptiminiumSettings.isItemVirtualization() && shouldRunAdaptiveOptimizations()
			|| OptiminiumSettings.isXpOrbMerging() && shouldRunAdaptiveOptimizations()
			|| OptiminiumSettings.isBlockEntityUpdateThrottling() && shouldRunAdaptiveOptimizations();
	}

	private static boolean needsEntityScheduler() {
		return OptiminiumSettings.isSmartTickScheduler() || OptiminiumSettings.isAiPathfindingOptimizer();
	}

	private static boolean shouldRunAdaptiveOptimizations() {
		return !OptiminiumSettings.isAdaptiveOptimizer() || serverHealth.isDegraded();
	}

	private static boolean shouldRunBlockEntityThrottling() {
		return OptiminiumSettings.isEnabled() && OptiminiumSettings.isBlockEntityUpdateThrottling() && shouldRunAdaptiveOptimizations();
	}

	private static boolean shouldRunRedstoneDeduplication() {
		return OptiminiumSettings.isEnabled() && OptiminiumSettings.isRedstoneDeduplication() && shouldRunAdaptiveOptimizations();
	}

	private static boolean isSleepEligibleBlockEntity(BlockEntity blockEntity) {
		BlockEntityType<?> type = blockEntity.getType();
		return type == BlockEntityType.MOB_SPAWNER
			|| type == BlockEntityType.TRIAL_SPAWNER
			|| type == BlockEntityType.VAULT;
	}

	private static String clientStats() {
		if (FMLEnvironment.dist != Dist.CLIENT) {
			return "";
		}
		try {
			Class<?> optimizer = Class.forName("net.optiminium.client.OptiminiumGpuOptimizer");
			return (String)optimizer.getMethod("diagnosticLine").invoke(null);
		} catch (ReflectiveOperationException exception) {
			return ", gpuStats=unavailable";
		}
	}

	private static void resetClientStats() {
		if (FMLEnvironment.dist != Dist.CLIENT) {
			return;
		}
		try {
			Class<?> optimizer = Class.forName("net.optiminium.client.OptiminiumGpuOptimizer");
			optimizer.getMethod("resetAdaptiveStats").invoke(null);
		} catch (ReflectiveOperationException ignored) {
		}
	}

	private static boolean hasNearbyPlayer(ServerLevel level, Vec3 position, double radius) {
		return playerSnapshots.hasNearbyPlayer(level, position, radius);
	}

	private static boolean hasLineOfSightFromAnyPlayer(ServerLevel level, Entity entity, double maxRadius) {
		double radiusSqr = maxRadius * maxRadius;
		for (ServerPlayer player : level.players()) {
			if (!player.isSpectator() && player.distanceToSqr(entity) <= radiusSqr && player.hasLineOfSight(entity)) {
				return true;
			}
		}
		return false;
	}

	private static double nearestPlayerDistanceSqr(ServerLevel level, Entity entity) {
		return playerSnapshots.nearestDistanceSqr(level, entity.position());
	}

	private static RegionKey regionKey(ResourceKey<Level> dimension, ChunkPos chunkPos) {
		return new RegionKey(dimension, Math.floorDiv(chunkPos.x, REGION_CHUNK_SIZE), Math.floorDiv(chunkPos.z, REGION_CHUNK_SIZE));
	}

	private record RegionKey(ResourceKey<Level> dimension, int x, int z) {
	}

	private record BlockKey(ResourceKey<Level> dimension, BlockPos pos) {
	}

	private static final class PlayerSnapshotIndex {
		private final Map<ResourceKey<Level>, PlayerSnapshot> snapshots = new ConcurrentHashMap<>();

		void refresh(MinecraftServer server, long tick) {
			for (ServerLevel level : server.getAllLevels()) {
				List<Vec3> positions = new ArrayList<>();
				for (ServerPlayer player : level.players()) {
					if (!player.isSpectator()) {
						positions.add(player.position());
					}
				}
				snapshots.put(level.dimension(), new PlayerSnapshot(tick, positions));
			}
		}

		boolean hasNearbyPlayer(ServerLevel level, Vec3 position, double radius) {
			PlayerSnapshot snapshot = snapshots.get(level.dimension());
			if (snapshot == null) {
				return hasNearbyPlayerDirect(level, position, radius);
			}
			double radiusSqr = radius * radius;
			for (Vec3 playerPosition : snapshot.positions) {
				if (playerPosition.distanceToSqr(position) <= radiusSqr) {
					return true;
				}
			}
			return false;
		}

		double nearestDistanceSqr(ServerLevel level, Vec3 position) {
			PlayerSnapshot snapshot = snapshots.get(level.dimension());
			if (snapshot == null) {
				return nearestPlayerDistanceSqrDirect(level, position);
			}
			double nearest = Double.MAX_VALUE;
			for (Vec3 playerPosition : snapshot.positions) {
				nearest = Math.min(nearest, playerPosition.distanceToSqr(position));
			}
			return nearest;
		}

		private boolean hasNearbyPlayerDirect(ServerLevel level, Vec3 position, double radius) {
			double radiusSqr = radius * radius;
			for (ServerPlayer player : level.players()) {
				if (!player.isSpectator() && player.position().distanceToSqr(position) <= radiusSqr) {
					return true;
				}
			}
			return false;
		}

		private double nearestPlayerDistanceSqrDirect(ServerLevel level, Vec3 position) {
			double nearest = Double.MAX_VALUE;
			for (ServerPlayer player : level.players()) {
				if (!player.isSpectator()) {
					nearest = Math.min(nearest, player.position().distanceToSqr(position));
				}
			}
			return nearest;
		}
	}

	private record PlayerSnapshot(long tick, List<Vec3> positions) {
	}

	private static final class ServerHealthMonitor {
		private boolean degraded;
		private long degradedUntilTick;
		private int stableRecoveryWindows;

		void update(MinecraftServer server, long tick) {
			if (tick % DEGRADED_TPS_SAMPLE_TICKS != 0) {
				return;
			}
			double mspt = server.getAverageTickTimeNanos() / 1_000_000.0;
			int targetMspt = OptiminiumSettings.getAdaptiveSimulationTargetMspt();
			if (mspt >= targetMspt + DEGRADED_MSPT_MARGIN) {
				degraded = true;
				degradedUntilTick = tick + DEGRADED_TPS_HOLD_TICKS;
				stableRecoveryWindows = 0;
				return;
			}
			if (!degraded) {
				return;
			}
			if (tick < degradedUntilTick) {
				return;
			}
			if (mspt <= targetMspt - RECOVERED_MSPT_MARGIN) {
				stableRecoveryWindows++;
				if (stableRecoveryWindows >= DEGRADED_TPS_STABLE_RECOVERY_WINDOWS) {
					degraded = false;
					stableRecoveryWindows = 0;
				}
			} else {
				stableRecoveryWindows = 0;
			}
		}

		boolean isDegraded() {
			return degraded;
		}

		void reset() {
			degraded = false;
			degradedUntilTick = 0;
			stableRecoveryWindows = 0;
		}
	}

	private static final class PredictiveTickScheduler {
		private final Map<UUID, EntityTickState> states = new ConcurrentHashMap<>();

		boolean shouldSkip(Entity entity, ServerLevel level) {
			if (!isEligible(entity)) {
				return false;
			}
			if (entity instanceof AbstractVillager villager && !isPackedVillager(villager, level)) {
				return false;
			}
			long tick = level.getServer().getTickCount();
			EntityTickState state = states.computeIfAbsent(entity.getUUID(), uuid -> new EntityTickState(uuid, entity.position(), tick));
			if (tick < state.forceAwakeUntil) {
				return false;
			}
			int interval = state.currentInterval;
			if (interval <= 1) {
				return false;
			}
			return Math.floorMod(tick + state.tickPhase, interval) != 0;
		}

		void recordTick(Entity entity, ServerLevel level, long tick) {
			if (!isEligible(entity)) {
				return;
			}
			EntityTickState state = states.computeIfAbsent(entity.getUUID(), uuid -> new EntityTickState(uuid, entity.position(), tick));
			Vec3 now = entity.position();
			boolean moved = now.distanceToSqr(state.lastPosition) > 0.0025 || entity.getDeltaMovement().lengthSqr() > 0.0004;
			if (isIdleCrowdEntity(entity)) {
				state.quietTicks++;
			} else if (moved || isCombatActive(entity)) {
				state.quietTicks = 0;
				state.forceAwakeUntil = Math.max(state.forceAwakeUntil, tick + 10);
			} else {
				state.quietTicks++;
			}
			state.lastPosition = now;
			state.lastSeenTick = tick;
			state.currentInterval = intervalFor(entity, level, state);
			optimizePathfinding(entity, level, state);
		}

		void wake(Entity entity, long untilTick) {
			EntityTickState state = states.computeIfAbsent(entity.getUUID(), uuid -> new EntityTickState(uuid, entity.position(), untilTick));
			state.forceAwakeUntil = Math.max(state.forceAwakeUntil, untilTick);
			state.quietTicks = 0;
		}

		void cleanup(long tick) {
			states.entrySet().removeIf(entry -> tick - entry.getValue().lastSeenTick > ENTITY_STATE_TTL);
		}

		private int intervalFor(Entity entity, ServerLevel level, EntityTickState state) {
			if (entity instanceof AbstractVillager villager && !isPackedVillager(villager, level)) {
				return 1;
			}
			if (state.quietTicks < 40 || isCombatActive(entity)) {
				return 1;
			}
			double distanceSqr = nearestPlayerDistanceSqr(level, entity);
			if (isIdleCrowdEntity(entity) && distanceSqr < 24.0 * 24.0) {
				return NEARBY_IDLE_CROWD_SAMPLE_TICKS;
			}
			if (distanceSqr < 24.0 * 24.0) {
				return 1;
			}
			if (serverHealth.isDegraded() && distanceSqr > 48.0 * 48.0) {
				return OptiminiumSettings.getFarEntityTickInterval();
			}
			if (distanceSqr > 96.0 * 96.0) {
				return OptiminiumSettings.getFarEntityTickInterval();
			}
			if (distanceSqr > 32.0 * 32.0) {
				return OCCLUDED_SAMPLE_TICKS;
			}
			return IDLE_SAMPLE_TICKS;
		}

		private void optimizePathfinding(Entity entity, ServerLevel level, EntityTickState state) {
			if (!OptiminiumSettings.isAiPathfindingOptimizer() || !(entity instanceof Mob mob) || state.quietTicks < 40 || isCombatActive(entity)) {
				return;
			}
			if (nearestPlayerDistanceSqr(level, entity) > 48.0 * 48.0 && !mob.getNavigation().isDone()) {
				mob.getNavigation().stop();
			}
		}

		private boolean isEligible(Entity entity) {
			if (!entity.isAlive() || entity instanceof Player || entity instanceof ItemEntity) {
				return false;
			}
			if (!(entity instanceof LivingEntity) || entity.hasExactlyOnePlayerPassenger() || entity.isPassenger() || entity.hasCustomName()) {
				return false;
			}
			if (entity instanceof Mob mob) {
				return !mob.isLeashed() && !mob.isNoAi();
			}
			return false;
		}

		private boolean isIdleCrowdEntity(Entity entity) {
			if (!(entity instanceof Mob mob) || isCombatActive(entity)) {
				return false;
			}
			if (entity instanceof AbstractVillager villager && villager.isTrading()) {
				return false;
			}
			if (entity instanceof AbstractVillager villager && !isPackedVillager(villager, (ServerLevel)villager.level())) {
				return false;
			}
			return !mob.isLeashed()
				&& !mob.isNoAi()
				&& !(mob instanceof AgeableMob ageableMob && ageableMob.isBaby());
		}

		private boolean isPackedVillager(AbstractVillager villager, ServerLevel level) {
			if (!villager.isAlive() || villager.isTrading()) {
				return false;
			}
			return level.getEntitiesOfClass(
				AbstractVillager.class,
				villager.getBoundingBox().inflate(VILLAGER_CROWD_RADIUS_BLOCKS),
				nearbyVillager -> nearbyVillager.isAlive() && !nearbyVillager.isTrading()
			).size() >= VILLAGER_CROWD_THRESHOLD;
		}

		private boolean isCombatActive(Entity entity) {
			if (entity instanceof Mob mob) {
				return mob.getTarget() != null || mob.isAggressive();
			}
			return false;
		}
	}

	private static final class EntityTickState {
		private Vec3 lastPosition;
		private long lastSeenTick;
		private long forceAwakeUntil;
		private final int tickPhase;
		private int quietTicks;
		private int currentInterval = 1;

		private EntityTickState(UUID uuid, Vec3 position, long tick) {
			this.lastPosition = position;
			this.lastSeenTick = tick;
			this.forceAwakeUntil = tick + 20;
			this.tickPhase = Math.floorMod(uuid.hashCode(), OptiminiumSettings.getMaxFarEntityTickInterval());
		}
	}

	private static final class VirtualizedItemSystem {
		private final Map<RegionKey, ItemCloud> clouds = new ConcurrentHashMap<>();
		private final Map<ResourceKey<Level>, ItemScan> scans = new ConcurrentHashMap<>();

		void tickScan(ServerLevel level, long tick) {
			ResourceKey<Level> dimension = level.dimension();
			ItemScan scan = scans.get(dimension);
			if (scan == null && Math.floorMod(tick + dimension.location().hashCode(), ITEM_SCAN_PERIOD_TICKS) == ITEM_SCAN_PHASE_TICKS) {
				scan = new ItemScan(level.getAllEntities().iterator());
				scans.put(dimension, scan);
			}
			if (scan == null) {
				return;
			}

			try {
				int scanned = 0;
				while (scan.iterator.hasNext() && scanned++ < ITEM_SCAN_BUDGET_PER_TICK) {
					Entity entity = scan.iterator.next();
					if (entity instanceof ItemEntity item && item.isAlive() && item.getAge() >= ITEM_ABSORB_MIN_AGE && !item.getItem().isEmpty()) {
						if (!hasNearbyPlayer(level, item.position(), ITEM_PLAYER_SAFE_RADIUS)) {
							RegionKey key = regionKey(dimension, item.chunkPosition());
							scan.byRegion.computeIfAbsent(key, ignored -> new ArrayList<>()).add(item);
						}
					}
				}
			} catch (ConcurrentModificationException ignored) {
				scans.remove(dimension);
				return;
			}
			if (!scan.iterator.hasNext()) {
				absorbDenseItemRegions(level, tick, scan.byRegion);
				scans.remove(dimension);
			}
		}

		private void absorbDenseItemRegions(ServerLevel level, long tick, Map<RegionKey, List<ItemEntity>> byRegion) {
			for (Map.Entry<RegionKey, List<ItemEntity>> entry : byRegion.entrySet()) {
				ItemCloud existing = clouds.get(entry.getKey());
				if (existing == null && entry.getValue().size() < OptiminiumSettings.getItemClusterThreshold()) {
					continue;
				}
				ItemCloud cloud = clouds.computeIfAbsent(entry.getKey(), ignored -> new ItemCloud());
				for (ItemEntity item : entry.getValue()) {
					if (item.isAlive() && !item.getItem().isEmpty() && !hasNearbyPlayer(level, item.position(), ITEM_PLAYER_SAFE_RADIUS)) {
						cloud.add(item.getItem(), item.position(), tick);
						item.discard();
						OptiminiumMetrics.virtualizedItems(1);
					}
				}
				if (cloud.isEmpty()) {
					clouds.remove(entry.getKey(), cloud);
				}
			}
		}

		void releaseNearPlayers(MinecraftServer server, long tick) {
			if (tick % 20 != 0 || clouds.isEmpty()) {
				return;
			}
			for (ServerLevel level : server.getAllLevels()) {
				for (ServerPlayer player : level.players()) {
					if (player.isSpectator()) {
						continue;
					}
					int releases = 0;
					for (Iterator<Map.Entry<RegionKey, ItemCloud>> iterator = clouds.entrySet().iterator(); iterator.hasNext() && releases < ITEM_RELEASES_PER_PLAYER_TICK; ) {
						Map.Entry<RegionKey, ItemCloud> entry = iterator.next();
						if (!entry.getKey().dimension.equals(level.dimension())) {
							continue;
						}
						ItemCloud cloud = entry.getValue();
						if (cloud.position.distanceToSqr(player.position()) > ITEM_RELEASE_RADIUS * ITEM_RELEASE_RADIUS) {
							continue;
						}
						ItemStack released = cloud.pollStack();
						if (!released.isEmpty()) {
							ItemEntity itemEntity = new ItemEntity(level, cloud.position.x, cloud.position.y, cloud.position.z, released);
							itemEntity.setDefaultPickUpDelay();
							level.addFreshEntity(itemEntity);
							releases++;
						}
						if (cloud.isEmpty()) {
							iterator.remove();
						}
					}
				}
			}
		}

		void releaseAll(MinecraftServer server, int maxStacks) {
			scans.clear();
			if (clouds.isEmpty() || maxStacks <= 0) {
				return;
			}
			int releasedStacks = 0;
			for (ServerLevel level : server.getAllLevels()) {
				for (Iterator<Map.Entry<RegionKey, ItemCloud>> iterator = clouds.entrySet().iterator(); iterator.hasNext() && releasedStacks < maxStacks; ) {
					Map.Entry<RegionKey, ItemCloud> entry = iterator.next();
					if (!entry.getKey().dimension.equals(level.dimension())) {
						continue;
					}
					ItemCloud cloud = entry.getValue();
					ItemStack released = cloud.pollStack();
					if (!released.isEmpty()) {
						ItemEntity itemEntity = new ItemEntity(level, cloud.position.x, cloud.position.y, cloud.position.z, released);
						itemEntity.setDefaultPickUpDelay();
						level.addFreshEntity(itemEntity);
						releasedStacks++;
					}
					if (cloud.isEmpty()) {
						iterator.remove();
					}
				}
				if (releasedStacks >= maxStacks) {
					return;
				}
			}
		}

		void pauseScans() {
			scans.clear();
		}
	}

	private static final class ItemScan {
		private final Iterator<Entity> iterator;
		private final Map<RegionKey, List<ItemEntity>> byRegion = new HashMap<>();

		private ItemScan(Iterator<Entity> iterator) {
			this.iterator = iterator;
		}
	}

	private static final class ItemCloud {
		private final List<VirtualStack> stacks = new ArrayList<>();
		private Vec3 position = Vec3.ZERO;
		private long totalItems;
		private long lastTouchedTick;

		void add(ItemStack stack, Vec3 sourcePosition, long tick) {
			int count = stack.getCount();
			if (count <= 0) {
				return;
			}
			for (VirtualStack virtualStack : stacks) {
				if (ItemStack.isSameItemSameComponents(virtualStack.prototype, stack)) {
					virtualStack.count += count;
					updatePosition(sourcePosition, count);
					lastTouchedTick = tick;
					return;
				}
			}
			stacks.add(new VirtualStack(stack.copyWithCount(1), count));
			updatePosition(sourcePosition, count);
			lastTouchedTick = tick;
		}

		ItemStack pollStack() {
			for (Iterator<VirtualStack> iterator = stacks.iterator(); iterator.hasNext(); ) {
				VirtualStack stack = iterator.next();
				int amount = (int)Math.min(stack.count, stack.prototype.getMaxStackSize());
				if (amount <= 0) {
					iterator.remove();
					continue;
				}
				stack.count -= amount;
				totalItems -= amount;
				ItemStack released = stack.prototype.copyWithCount(amount);
				if (stack.count <= 0) {
					iterator.remove();
				}
				return released;
			}
			return ItemStack.EMPTY;
		}

		boolean isEmpty() {
			return stacks.isEmpty() || totalItems <= 0;
		}

		private void updatePosition(Vec3 sourcePosition, int count) {
			if (totalItems <= 0) {
				position = sourcePosition;
				totalItems = count;
				return;
			}
			double newTotal = totalItems + count;
			position = position.scale(totalItems / newTotal).add(sourcePosition.scale(count / newTotal));
			totalItems += count;
		}
	}

	private static final class VirtualStack {
		private final ItemStack prototype;
		private long count;

		private VirtualStack(ItemStack prototype, long count) {
			this.prototype = prototype;
			this.count = count;
		}
	}

	private static final class RedstoneGraphSolver {
		private final Map<RedstoneUpdateKey, Long> recentUpdates = new HashMap<>();
		private final Map<BlockKey, Long> touchedNodes = new ConcurrentHashMap<>();

		boolean isDuplicate(ResourceKey<Level> dimension, BlockPos pos, BlockState state, EnumSet<Direction> sides, long tick) {
			if (!state.isSignalSource() && !state.hasBlockEntity()) {
				return false;
			}
			RedstoneUpdateKey key = new RedstoneUpdateKey(dimension, pos.immutable(), sides.hashCode(), state.getBlock().getDescriptionId());
			Long previous = recentUpdates.put(key, tick);
			touchedNodes.put(new BlockKey(dimension, pos.immutable()), tick);
			return previous != null && tick - previous <= REDSTONE_DUPLICATE_WINDOW_TICKS;
		}

		void markTouched(ResourceKey<Level> dimension, BlockPos pos, long tick) {
			touchedNodes.put(new BlockKey(dimension, pos.immutable()), tick);
		}

		void cleanup(long tick) {
			recentUpdates.entrySet().removeIf(entry -> tick - entry.getValue() > REDSTONE_DUPLICATE_WINDOW_TICKS);
			touchedNodes.entrySet().removeIf(entry -> tick - entry.getValue() > 20 * 60);
		}
	}

	private record RedstoneUpdateKey(ResourceKey<Level> dimension, BlockPos pos, int sidesHash, String blockId) {
	}

	private static final class XpOrbOptimizer {
		private final Map<ResourceKey<Level>, XpOrbScan> scans = new ConcurrentHashMap<>();

		void tickScan(ServerLevel level, long tick) {
			ResourceKey<Level> dimension = level.dimension();
			XpOrbScan scan = scans.get(dimension);
			if (scan == null && Math.floorMod(tick + dimension.location().hashCode(), XP_SCAN_PERIOD_TICKS) == XP_SCAN_PHASE_TICKS) {
				scan = new XpOrbScan(level.getAllEntities().iterator());
				scans.put(dimension, scan);
			}
			if (scan == null) {
				return;
			}

			try {
				int scanned = 0;
				while (scan.iterator.hasNext() && scanned++ < XP_SCAN_BUDGET_PER_TICK) {
					Entity entity = scan.iterator.next();
					if (entity instanceof ExperienceOrb orb && orb.isAlive()) {
						if (!hasNearbyPlayer(level, orb.position(), XP_PLAYER_SAFE_RADIUS)) {
							RegionKey key = regionKey(dimension, orb.chunkPosition());
							scan.byRegion.computeIfAbsent(key, ignored -> new ArrayList<>()).add(orb);
						}
					}
				}
			} catch (ConcurrentModificationException ignored) {
				scans.remove(dimension);
				return;
			}
			if (!scan.iterator.hasNext()) {
				mergeDenseOrbRegions(level, scan.byRegion);
				scans.remove(dimension);
			}
		}

		void pauseScans() {
			scans.clear();
		}

		private void mergeDenseOrbRegions(ServerLevel level, Map<RegionKey, List<ExperienceOrb>> byRegion) {
			for (List<ExperienceOrb> orbs : byRegion.values()) {
				if (orbs.size() < OptiminiumSettings.getXpMergeThreshold()) {
					continue;
				}
				Vec3 position = Vec3.ZERO;
				int value = 0;
				int merged = 0;
				for (ExperienceOrb orb : orbs) {
					if (orb.isAlive() && !hasNearbyPlayer(level, orb.position(), XP_PLAYER_SAFE_RADIUS)) {
						position = position.add(orb.position());
						value += orb.getValue();
						merged++;
						orb.discard();
					}
				}
				if (merged > 0 && value > 0) {
					ExperienceOrb.award(level, position.scale(1.0 / merged), value);
					OptiminiumMetrics.mergedXpOrbs(merged, value);
				}
			}
		}
	}

	private static final class XpOrbScan {
		private final Iterator<Entity> iterator;
		private final Map<RegionKey, List<ExperienceOrb>> byRegion = new HashMap<>();

		private XpOrbScan(Iterator<Entity> iterator) {
			this.iterator = iterator;
		}
	}

	private static final class AdaptiveSimulationDistance {
		private int targetDistance = -1;
		private int lastAppliedDistance = -1;
		private int stableWindows;

		void update(MinecraftServer server, long tick) {
			if (tick % 200 != 0) {
				return;
			}
			int current = server.getPlayerList().getSimulationDistance();
			if (targetDistance < 0) {
				targetDistance = current;
			}
			double mspt = server.getAverageTickTimeNanos() / 1_000_000.0;
			int targetMspt = OptiminiumSettings.getAdaptiveSimulationTargetMspt();
			int minDistance = OptiminiumSettings.getAdaptiveSimulationMinDistanceChunks();
			int next = current;
			if (mspt > targetMspt + 5.0D && current > minDistance) {
				next = current - 1;
				stableWindows = 0;
			} else if (mspt < targetMspt - 12.0D) {
				stableWindows++;
				if (stableWindows >= 6 && current < targetDistance) {
					next = current + 1;
					stableWindows = 0;
				}
			} else {
				stableWindows = 0;
			}
			if (next != current) {
				server.getPlayerList().setSimulationDistance(next);
				lastAppliedDistance = next;
			}
		}

		void pause(MinecraftServer server) {
			int current = server.getPlayerList().getSimulationDistance();
			if (targetDistance >= 0 && lastAppliedDistance >= 0 && current == lastAppliedDistance && current < targetDistance) {
				server.getPlayerList().setSimulationDistance(targetDistance);
			}
			targetDistance = -1;
			lastAppliedDistance = -1;
			stableWindows = 0;
		}
	}

	private static final class BlockEntitySleepIndex {
		private final Map<BlockKey, Long> dirtyTicks = new ConcurrentHashMap<>();
		private final Map<BlockKey, Long> observedTicks = new ConcurrentHashMap<>();
		private long currentTick = -1;
		private int sleepingTicksUsed;

		void beginTick(long tick) {
			if (currentTick != tick) {
				currentTick = tick;
				sleepingTicksUsed = 0;
			}
		}

		void observe(ServerLevel level, BlockEntity blockEntity, long tick) {
			BlockKey key = new BlockKey(level.dimension(), blockEntity.getBlockPos().immutable());
			observedTicks.put(key, tick);
			dirtyTicks.putIfAbsent(key, tick);
		}

		void markDirty(ResourceKey<Level> dimension, BlockPos pos, long tick) {
			dirtyTicks.put(new BlockKey(dimension, pos.immutable()), tick);
		}

		boolean canSleep(ServerLevel level, BlockEntity blockEntity, long tick) {
			BlockKey key = new BlockKey(level.dimension(), blockEntity.getBlockPos().immutable());
			Long dirtyTick = dirtyTicks.get(key);
			return dirtyTick != null && tick - dirtyTick >= OptiminiumSettings.getBlockEntitySleepAfterTicks();
		}

		boolean allowSleepingTick(ServerLevel level, BlockEntity blockEntity, long tick) {
			int budget = OptiminiumSettings.getSleepingBlockEntityTicksPerTick();
			if (budget <= 0 || sleepingTicksUsed >= budget) {
				return false;
			}
			int interval = OptiminiumSettings.getSleepingBlockEntityTickInterval();
			BlockKey key = new BlockKey(level.dimension(), blockEntity.getBlockPos().immutable());
			if (Math.floorMod(tick + key.hashCode(), interval) != 0) {
				return false;
			}
			sleepingTicksUsed++;
			return true;
		}

		void cleanup(long tick) {
			dirtyTicks.entrySet().removeIf(entry -> tick - entry.getValue() > 20 * 60 * 10);
			observedTicks.entrySet().removeIf(entry -> tick - entry.getValue() > 20 * 60 * 10);
		}
	}

}
