package net.optiminium.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BannerRenderer;
import net.minecraft.client.renderer.blockentity.BedRenderer;
import net.minecraft.client.renderer.blockentity.BellRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.BrushableBlockRenderer;
import net.minecraft.client.renderer.blockentity.CampfireRenderer;
import net.minecraft.client.renderer.blockentity.ChestRenderer;
import net.minecraft.client.renderer.blockentity.DecoratedPotRenderer;
import net.minecraft.client.renderer.blockentity.EnchantTableRenderer;
import net.minecraft.client.renderer.blockentity.HangingSignRenderer;
import net.minecraft.client.renderer.blockentity.LecternRenderer;
import net.minecraft.client.renderer.blockentity.ShulkerBoxRenderer;
import net.minecraft.client.renderer.blockentity.SignRenderer;
import net.minecraft.client.renderer.blockentity.SkullBlockRenderer;
import net.minecraft.client.renderer.blockentity.SpawnerRenderer;
import net.minecraft.client.renderer.blockentity.TrialSpawnerRenderer;
import net.minecraft.client.renderer.blockentity.VaultRenderer;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BedBlockEntity;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BrushableBlockEntity;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.entity.EnchantingTableBlockEntity;
import net.minecraft.world.level.block.entity.HangingSignBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@EventBusSubscriber(modid = "optiminium", value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class OptiminiumBlockEntityCulling {
	private OptiminiumBlockEntityCulling() {
	}

	@SubscribeEvent
	public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
		register(event, BlockEntityType.SIGN, SignRenderer::new, 24);
		register(event, BlockEntityType.HANGING_SIGN, HangingSignRenderer::new, 24);
		register(event, BlockEntityType.MOB_SPAWNER, SpawnerRenderer::new, 32);
		register(event, BlockEntityType.CHEST, ChestRenderer::new, 40);
		register(event, BlockEntityType.ENDER_CHEST, ChestRenderer::new, 40);
		register(event, BlockEntityType.TRAPPED_CHEST, ChestRenderer::new, 40);
		register(event, BlockEntityType.ENCHANTING_TABLE, EnchantTableRenderer::new, 40);
		register(event, BlockEntityType.LECTERN, LecternRenderer::new, 24);
		register(event, BlockEntityType.SKULL, SkullBlockRenderer::new, 24);
		register(event, BlockEntityType.BANNER, BannerRenderer::new, 24);
		register(event, BlockEntityType.SHULKER_BOX, ShulkerBoxRenderer::new, 40);
		register(event, BlockEntityType.BED, BedRenderer::new, 24);
		register(event, BlockEntityType.BELL, BellRenderer::new, 24);
		register(event, BlockEntityType.CAMPFIRE, CampfireRenderer::new, 24);
		register(event, BlockEntityType.BRUSHABLE_BLOCK, BrushableBlockRenderer::new, 20);
		register(event, BlockEntityType.DECORATED_POT, DecoratedPotRenderer::new, 24);
		register(event, BlockEntityType.TRIAL_SPAWNER, TrialSpawnerRenderer::new, 40);
		register(event, BlockEntityType.VAULT, VaultRenderer::new, 40);
	}

	private static <T extends BlockEntity> void register(EntityRenderersEvent.RegisterRenderers event, BlockEntityType<? extends T> type, BlockEntityRendererProvider<T> provider, int viewDistance) {
		event.registerBlockEntityRenderer(type, context -> new DistanceCullingRenderer<>(provider.create(context), viewDistance));
	}

	private static final class DistanceCullingRenderer<T extends BlockEntity> implements BlockEntityRenderer<T> {
		private final BlockEntityRenderer<T> delegate;
		private final int viewDistance;

		private DistanceCullingRenderer(BlockEntityRenderer<T> delegate, int viewDistance) {
			this.delegate = delegate;
			this.viewDistance = viewDistance;
		}

		@Override
		public void render(T blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
			if (OptiminiumGpuOptimizer.shouldSkipBlockEntityRender(blockEntity, viewDistance)) {
				return;
			}
			delegate.render(blockEntity, partialTick, poseStack, bufferSource, packedLight, packedOverlay);
		}

		@Override
		public boolean shouldRender(T blockEntity, Vec3 cameraPos) {
			if (!OptiminiumGpuOptimizer.isBlockEntityCullingActive()) {
				return delegate.shouldRender(blockEntity, cameraPos);
			}
			int scaledViewDistance = OptiminiumGpuOptimizer.scaledBlockEntityViewDistance(viewDistance);
			boolean shouldRender = closerThanBlockCenter(blockEntity.getBlockPos(), cameraPos, scaledViewDistance) && delegate.shouldRender(blockEntity, cameraPos);
			if (!shouldRender) {
				OptiminiumGpuOptimizer.recordCulledBlockEntityRender();
			}
			return shouldRender;
		}

		@Override
		public boolean shouldRenderOffScreen(T blockEntity) {
			if (!OptiminiumGpuOptimizer.isBlockEntityCullingActive()) {
				return delegate.shouldRenderOffScreen(blockEntity);
			}
			return false;
		}

		@Override
		public int getViewDistance() {
			if (!OptiminiumGpuOptimizer.isBlockEntityCullingActive()) {
				return delegate.getViewDistance();
			}
			return OptiminiumGpuOptimizer.scaledBlockEntityViewDistance(viewDistance);
		}

		@Override
		public AABB getRenderBoundingBox(T blockEntity) {
			return delegate.getRenderBoundingBox(blockEntity);
		}

		private static boolean closerThanBlockCenter(BlockPos pos, Vec3 cameraPos, int distance) {
			double dx = pos.getX() + 0.5D - cameraPos.x;
			double dy = pos.getY() + 0.5D - cameraPos.y;
			double dz = pos.getZ() + 0.5D - cameraPos.z;
			return dx * dx + dy * dy + dz * dz < distance * distance;
		}
	}
}
