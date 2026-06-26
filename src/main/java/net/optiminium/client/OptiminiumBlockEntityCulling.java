package net.optiminium.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BannerRenderer;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.renderer.blockentity.BedRenderer;
import net.minecraft.client.renderer.blockentity.BellRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.BrushableBlockRenderer;
import net.minecraft.client.renderer.blockentity.CampfireRenderer;
import net.minecraft.client.renderer.blockentity.ChestRenderer;
import net.minecraft.client.renderer.blockentity.ConduitRenderer;
import net.minecraft.client.renderer.blockentity.DecoratedPotRenderer;
import net.minecraft.client.renderer.blockentity.EnchantTableRenderer;
import net.minecraft.client.renderer.blockentity.HangingSignRenderer;
import net.minecraft.client.renderer.blockentity.LecternRenderer;
import net.minecraft.client.renderer.blockentity.PistonHeadRenderer;
import net.minecraft.client.renderer.blockentity.ShulkerBoxRenderer;
import net.minecraft.client.renderer.blockentity.SignRenderer;
import net.minecraft.client.renderer.blockentity.SkullBlockRenderer;
import net.minecraft.client.renderer.blockentity.SpawnerRenderer;
import net.minecraft.client.renderer.blockentity.TheEndPortalRenderer;
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
import net.optiminium.compat.OptiminiumSodiumCompat;

/**
 * Distance-culling block entity renderers integrated with Visual Significance.
 * 
 * The seamless architecture:
 * 1. `shouldRender()` feeds ALL block entity positions into Visual Significance
 *    (whether they pass distance culling or not) so VS builds complete object memory.
 * 2. `render()` consults a unified decision: VS classification + distance + fade alpha.
 * 3. Visual Significance's `shouldRenderBySignificance()` now respects the full
 *    distance/protection pipeline internally, eliminating the two-path interference.
 * 
 * No block entity is skipped by distance without VS knowing about it first.
 * No block entity is skipped by VS without distance being respected.
 */
@EventBusSubscriber(modid = "optiminium", value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class OptiminiumBlockEntityCulling {
	private OptiminiumBlockEntityCulling() {
	}

	public static boolean isDistanceCullingRenderer(Object renderer) {
		return renderer instanceof DistanceCullingRenderer<?>;
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
		register(event, BlockEntityType.BEACON, BeaconRenderer::new, 64);
		register(event, BlockEntityType.CONDUIT, ConduitRenderer::new, 48);
		register(event, BlockEntityType.PISTON, PistonHeadRenderer::new, 48);
		register(event, BlockEntityType.END_PORTAL, TheEndPortalRenderer::new, 48);
		register(event, BlockEntityType.END_GATEWAY, TheEndPortalRenderer::new, 48);
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
			// Unified decision: synchronize VS classification before rendering
			if (!OptiminiumGpuOptimizer.shouldRenderBlockEntity(blockEntity, viewDistance)) {
				return;
			}
			float alpha = OptiminiumVisualSignificance.blockEntityFadeAlpha(blockEntity);
			if (alpha < 1.0F) {
				RenderSystem.enableBlend();
				RenderSystem.defaultBlendFunc();
				RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);
				bufferSource = OptiminiumFadeBufferSource.wrap(bufferSource, alpha);
			}
			OptiminiumGpuOptimizer.recordRenderedBlockEntityAfterCulling();
			try {
				delegate.render(blockEntity, partialTick, poseStack, bufferSource, packedLight, packedOverlay);
			} finally {
				RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
				RenderSystem.disableBlend();
			}
		}

		@Override
		public boolean shouldRender(T blockEntity, Vec3 cameraPos) {
			long profileStart = OptiminiumGpuOptimizer.profileStart();
			try {
				if (OptiminiumSodiumCompat.isNonVanillaRenderer()) {
					OptiminiumGpuOptimizer.recordRawVisibleBlockEntityBeforeCulling(blockEntity);
				}
				if (!OptiminiumGpuOptimizer.isBlockEntityCullingActive()) {
					return delegate.shouldRender(blockEntity, cameraPos);
				}

				// Feed ALL block entities into Visual Significance (including out-of-range ones)
				// so VS builds complete memory even for entities beyond the distance threshold.
				OptiminiumVisualSignificance.recordBlockEntity(blockEntity, cameraPos);

				int scaledViewDistance = OptiminiumGpuOptimizer.scaledBlockEntityViewDistance(viewDistance);
				Vec3 center = blockEntity.getBlockPos().getCenter();
				double dx = center.x - cameraPos.x;
				double dy = center.y - cameraPos.y;
				double dz = center.z - cameraPos.z;
				double distanceSqr = dx * dx + dy * dy + dz * dz;

				// Distance check (base culling)
				if (distanceSqr > scaledViewDistance * scaledViewDistance) {
					OptiminiumGpuOptimizer.recordCulledBlockEntityRender();
					return false;
				}

				// Delegate's own shouldRender (e.g., AABB frustum check)
				if (!delegate.shouldRender(blockEntity, cameraPos)) {
					OptiminiumGpuOptimizer.recordCulledBlockEntityRender();
					return false;
				}

				return true;
			} finally {
				OptiminiumGpuOptimizer.recordBlockEntityProfileNanos(profileStart);
			}
		}

		@Override
		public boolean shouldRenderOffScreen(T blockEntity) {
			if (!OptiminiumGpuOptimizer.isBlockEntityCullingActive()) {
				return delegate.shouldRenderOffScreen(blockEntity);
			}
			// Off-screen block entities are handled by Visual Significance classification
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
	}
}
