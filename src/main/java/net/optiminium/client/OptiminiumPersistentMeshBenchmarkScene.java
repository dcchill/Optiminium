package net.optiminium.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.optiminium.OptiminiumMod;
import net.optiminium.optimization.OptiminiumSettings;

import java.util.List;

/** Explicitly enabled development-only scene for repeatable persistent-mesh measurements. */
@EventBusSubscriber(modid = OptiminiumMod.MODID, value = Dist.CLIENT)
public final class OptiminiumPersistentMeshBenchmarkScene {
	private static final boolean ENABLED = Boolean.getBoolean("optiminium.persistentMeshBenchmarkScene");
	private static final int GRID_SIZE = Math.max(4, Integer.getInteger("optiminium.persistentMeshBenchmarkGridSize", 16));
	private static final String SCREENSHOT_NAME = System.getProperty("optiminium.persistentMeshBenchmarkScreenshot", "");
	private static final String BENCHMARK_ORIGIN = System.getProperty("optiminium.persistentMeshBenchmarkOrigin", "");
	private static final boolean ARMOR_STANDS = Boolean.getBoolean("optiminium.persistentMeshBenchmarkArmorStands");
	private static final boolean GENERIC_BLOCK_ENTITIES = Boolean.getBoolean("optiminium.persistentMeshBenchmarkGenericBlockEntities");
	private static final boolean MOBS = Boolean.getBoolean("optiminium.persistentMeshBenchmarkMobs");
	private static final boolean VARIANTS = Boolean.getBoolean("optiminium.persistentMeshBenchmarkVariants");
	private static final int BENCHMARK_COUNT = Math.max(1, Integer.getInteger("optiminium.persistentMeshBenchmarkCount", GRID_SIZE * GRID_SIZE));
	private static int readyTicks;
	private static boolean queued;
	private static boolean screenshotTaken;
	private static boolean previousHideGui;
	private static boolean cameraQueued;

	private OptiminiumPersistentMeshBenchmarkScene() {
	}

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null || minecraft.player == null || minecraft.getSingleplayerServer() == null) {
			readyTicks = 0;
			return;
		}
		readyTicks++;
		if (!SCREENSHOT_NAME.isEmpty() && readyTicks == 1) {
			OptiminiumSettings.setBlockEntityRenderCacheDebug(false);
		}
		if (!BENCHMARK_ORIGIN.isEmpty() && readyTicks >= 80) {
			minecraft.player.setYRot(0.0F);
			minecraft.player.setXRot(31.0F);
			minecraft.player.yRotO = 0.0F;
			minecraft.player.xRotO = 31.0F;
		}
		if (!BENCHMARK_ORIGIN.isEmpty() && !cameraQueued && readyTicks >= 80) {
			cameraQueued = true;
			String[] coordinates = BENCHMARK_ORIGIN.split(",");
			if (coordinates.length != 3) throw new IllegalArgumentException("Expected x,y,z benchmark origin");
			int originX = Integer.parseInt(coordinates[0].trim());
			int originY = Integer.parseInt(coordinates[1].trim());
			int originZ = Integer.parseInt(coordinates[2].trim());
			var server = minecraft.getSingleplayerServer();
			var playerId = minecraft.player.getUUID();
			server.execute(() -> {
				ServerPlayer player = server.getPlayerList().getPlayer(playerId);
				if (player == null) return;
				ServerLevel level = player.serverLevel();
				level.setDayTime(6000L);
				player.setGameMode(GameType.SPECTATOR);
				player.teleportTo(level, originX + (GRID_SIZE - 1) * 0.5D,
					originY + GRID_SIZE * 0.8D, originZ - GRID_SIZE * 0.9D, 0.0F, 31.0F);
			});
		}
		if (!SCREENSHOT_NAME.isEmpty() && !screenshotTaken && readyTicks == 220) {
			previousHideGui = minecraft.options.hideGui;
			minecraft.options.hideGui = true;
		}
		if (ENABLED && readyTicks == 200) {
			OptiminiumMod.LOGGER.info("Persistent mesh benchmark diagnostics: {}",
				OptiminiumPersistentBlockEntityMeshes.diagnosticLine());
			OptiminiumMod.LOGGER.info("Persistent mesh benchmark cache diagnostics: {}",
				OptiminiumBlockEntityRenderCache.diagnosticLine());
		}
		if (!SCREENSHOT_NAME.isEmpty() && !screenshotTaken && readyTicks >= 240) {
			screenshotTaken = true;
			Screenshot.grab(minecraft.gameDirectory, SCREENSHOT_NAME, minecraft.getMainRenderTarget(),
				message -> OptiminiumMod.LOGGER.info("Persistent mesh benchmark screenshot: {}", message.getString()));
		}
		if (screenshotTaken && readyTicks == 245) minecraft.options.hideGui = previousHideGui;
		if (!ENABLED || queued || readyTicks < 80) return;
		queued = true;
		var server = minecraft.getSingleplayerServer();
		var playerId = minecraft.player.getUUID();
		server.execute(() -> {
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player == null) return;
			ServerLevel level = player.serverLevel();
			level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, level.getServer());
			BlockPos origin = player.blockPosition().offset(4, 0, 4);
			int floorY = origin.getY() - 1;
			for (int x = -2; x < GRID_SIZE + 2; x++) {
				for (int z = -GRID_SIZE; z < GRID_SIZE + 4; z++) {
					level.setBlock(new BlockPos(origin.getX() + x, floorY, origin.getZ() + z),
						Blocks.STONE.defaultBlockState(), 3);
					for (int y = 0; y < 10; y++) {
						level.setBlock(new BlockPos(origin.getX() + x, origin.getY() + y, origin.getZ() + z),
							Blocks.AIR.defaultBlockState(), 3);
					}
				}
			}
			AABB entityCleanup = new AABB(origin).inflate(Math.max(512, GRID_SIZE * 8));
			for (Mob existing : level.getEntitiesOfClass(Mob.class, entityCleanup)) existing.discard();
			for (ArmorStand existing : level.getEntitiesOfClass(ArmorStand.class, entityCleanup)) existing.discard();
			int cleanupRadius = Math.max(128, GRID_SIZE * 8);
			for (int chunkX = origin.getX() - cleanupRadius >> 4;
					chunkX <= origin.getX() + cleanupRadius >> 4; chunkX++) {
				for (int chunkZ = origin.getZ() - cleanupRadius >> 4;
						chunkZ <= origin.getZ() + cleanupRadius >> 4; chunkZ++) {
					LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
					if (chunk == null) continue;
					for (BlockPos pos : List.copyOf(chunk.getBlockEntities().keySet())) {
						BlockEntity blockEntity = chunk.getBlockEntity(pos);
						if (blockEntity == null) continue;
						BlockEntityType<?> type = blockEntity.getType();
						if ((type == BlockEntityType.DECORATED_POT || type == BlockEntityType.SHULKER_BOX)
								&& Math.abs(pos.getX() - origin.getX()) <= cleanupRadius
								&& Math.abs(pos.getZ() - origin.getZ()) <= cleanupRadius) {
							level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
						}
					}
				}
			}
			if (MOBS) {
				for (int index = 0; index < BENCHMARK_COUNT; index++) {
					int x = index % GRID_SIZE;
					int z = index / GRID_SIZE;
					Cow cow = EntityType.COW.create(level);
					if (cow == null) continue;
					cow.setPos(origin.getX() + x + 0.5D, origin.getY(), origin.getZ() + z + 0.5D);
					cow.setNoAi(true);
					cow.setYRot((index & 3) * 90.0F);
					level.addFreshEntity(cow);
				}
			} else if (ARMOR_STANDS) {
				for (int index = 0; index < BENCHMARK_COUNT; index++) {
					int x = index % GRID_SIZE;
					int z = index / GRID_SIZE;
					ArmorStand stand = new ArmorStand(level, origin.getX() + x + 0.5D,
						origin.getY(), origin.getZ() + z + 0.5D);
					stand.setNoGravity(true);
					stand.setYRot(0.0F);
					level.addFreshEntity(stand);
				}
			} else {
				BlockState benchmarkState = GENERIC_BLOCK_ENTITIES
					? Blocks.SHULKER_BOX.defaultBlockState()
					: Blocks.DECORATED_POT.defaultBlockState()
						.setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH);
				for (int index = 0; index < BENCHMARK_COUNT; index++) {
					int x = index % GRID_SIZE;
					int z = index / GRID_SIZE;
					BlockState placedState = VARIANTS && !GENERIC_BLOCK_ENTITIES
						? benchmarkState.setValue(HorizontalDirectionalBlock.FACING,
							Direction.from2DDataValue(index & 3)) : benchmarkState;
					level.setBlock(origin.offset(x, 0, z), placedState, 3);
				}
			}
			level.setDayTime(6000L);
			player.setGameMode(GameType.SPECTATOR);
			player.teleportTo(level, origin.getX() + (GRID_SIZE - 1) * 0.5D,
				origin.getY() + GRID_SIZE * 0.8D, origin.getZ() - GRID_SIZE * 0.9D,
				0.0F, 31.0F);
			OptiminiumMod.LOGGER.info("Persistent mesh benchmark scene ready: {} identical {} at {}",
				BENCHMARK_COUNT, MOBS ? "exact mobs" : ARMOR_STANDS ? "armor stands"
					: GENERIC_BLOCK_ENTITIES ? "generic shulker boxes" : "decorated pots", origin);
		});
	}
}
