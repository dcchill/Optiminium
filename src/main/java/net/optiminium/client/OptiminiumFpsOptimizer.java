package net.optiminium.client;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.living.EffectParticleModificationEvent;
import net.optiminium.optimization.OptiminiumSettings;

@EventBusSubscriber(modid = "optiminium", value = Dist.CLIENT)
public final class OptiminiumFpsOptimizer {
	private static final int CELL_SIZE_SHIFT = 4;
	private static final double NAME_TAG_DISTANCE_SQR = 32.0 * 32.0;
	private static final Long2IntOpenHashMap renderedCrowdByCell = new Long2IntOpenHashMap();
	private static boolean crowdCulling;
	private static boolean clientRenderCulling;
	private static boolean particleLimiter;
	private static LocalPlayer player;
	private static double effectParticleDistanceSqr;
	private static int crowdRenderBudgetPercent;

	private OptiminiumFpsOptimizer() {
	}

	@SubscribeEvent
	public static void onFrameStart(RenderFrameEvent.Pre event) {
		if (!renderedCrowdByCell.isEmpty()) {
			renderedCrowdByCell.clear();
		}
		OptiminiumGpuOptimizer.onFrameStart();
		OptiminiumRenderProfiler.onFrameStart();
		OptiminiumVisualSignificance.onFrameStart();
		boolean enabled = OptiminiumSettings.isEnabled();
		crowdCulling = enabled && OptiminiumSettings.isCrowdCulling();
		clientRenderCulling = enabled && OptiminiumSettings.isClientRenderCulling();
		particleLimiter = enabled && OptiminiumSettings.isParticleLimiter();
		player = Minecraft.getInstance().player;
		double effectParticleDistance = Math.max(8.0D, OptiminiumSettings.getParticleRenderDistanceBlocks() * 0.375D);
		effectParticleDistanceSqr = effectParticleDistance * effectParticleDistance;
		crowdRenderBudgetPercent = Math.max(0, (int)Math.round(OptiminiumSettings.getCrowdRenderBudgetPercent() * OptiminiumGpuOptimizer.getGpuWorkScale()));
	}

	@SubscribeEvent
	public static void onRenderLiving(RenderLivingEvent.Pre<?, ?> event) {
		if (!crowdCulling || player == null) {
			return;
		}
		LivingEntity entity = event.getEntity();
		if (!(entity instanceof Mob mob) || entity instanceof Player) {
			return;
		}

		double distanceSqr = player.distanceToSqr(entity);
		int alwaysRenderDistance = OptiminiumSettings.getEntityAlwaysRenderDistanceBlocks();
		if (distanceSqr <= alwaysRenderDistance * alwaysRenderDistance) {
			OptiminiumVisualSignificance.recordLivingEntityRendered(entity);
			return;
		}

		if (!isCullableCrowdEntity(mob)) {
			OptiminiumVisualSignificance.recordLivingEntityRendered(entity);
			return;
		}
		if (!OptiminiumVisualSignificance.allowsLivingEntityCull(entity, distanceSqr)) {
			OptiminiumVisualSignificance.recordLivingEntityRendered(entity);
			return;
		}

		int budget = renderBudgetForDistance(distanceSqr);
		if (budget <= 0) {
			OptiminiumVisualSignificance.recordEntity(entity, true);
			if (OptiminiumVisualSignificance.shouldRenderEntityDuringFade(entity)) {
				return;
			}
			event.setCanceled(true);
			OptiminiumGpuOptimizer.recordCulledEntityRender();
			return;
		}
		long cellKey = crowdCellKey(entity, distanceSqr);
		int renderedInCell = renderedCrowdByCell.get(cellKey);
		if (renderedInCell >= budget) {
			OptiminiumVisualSignificance.recordEntity(entity, true);
			if (OptiminiumVisualSignificance.shouldRenderEntityDuringFade(entity)) {
				return;
			}
			event.setCanceled(true);
			OptiminiumGpuOptimizer.recordCulledEntityRender();
			return;
		}
		renderedCrowdByCell.put(cellKey, renderedInCell + 1);
		OptiminiumVisualSignificance.recordLivingEntityRendered(entity);
	}

	@SubscribeEvent
	public static void onRenderNameTag(RenderNameTagEvent event) {
		if (!clientRenderCulling || player == null) {
			return;
		}
		Entity entity = event.getEntity();
		if (!(entity instanceof LivingEntity) || entity instanceof LocalPlayer) {
			return;
		}
		if (player.distanceToSqr(entity) > NAME_TAG_DISTANCE_SQR) {
			event.setCanRender(TriState.FALSE);
			OptiminiumGpuOptimizer.recordHiddenNameTag();
		}
	}

	@SubscribeEvent
	public static void onEffectParticle(EffectParticleModificationEvent event) {
		if (!particleLimiter || player == null) {
			return;
		}
		LivingEntity entity = event.getEntity();
		if (entity != player && player.distanceToSqr(entity) > effectParticleDistanceSqr) {
			event.setVisible(false);
			OptiminiumGpuOptimizer.recordHiddenParticle();
		}
	}

	private static boolean isCullableCrowdEntity(Mob mob) {
		if (mob instanceof AbstractVillager villager && villager.isTrading()) {
			return false;
		}
		return !mob.isAggressive()
			&& mob.getTarget() == null
			&& !mob.isLeashed()
			&& !mob.isNoAi()
			&& !(mob instanceof AgeableMob ageableMob && ageableMob.isBaby())
			&& !mob.hasCustomName()
			&& !mob.hasGlowingTag()
			&& !mob.isOnFire()
			&& !mob.isPassenger()
			&& mob.getPassengers().isEmpty();
	}

	private static int renderBudgetForDistance(double distanceSqr) {
		int baseBudget;
		if (distanceSqr > 112.0 * 112.0) {
			baseBudget = 0;
		} else if (distanceSqr > 72.0 * 72.0) {
			baseBudget = 1;
		} else if (distanceSqr > 40.0 * 40.0) {
			baseBudget = 2;
		} else if (distanceSqr > 20.0 * 20.0) {
			baseBudget = 6;
		} else {
			baseBudget = 12;
		}
		int percent = crowdRenderBudgetPercent;
		if (baseBudget <= 0 || percent <= 0) {
			return 0;
		}
		return Math.max(1, (int)Math.ceil(baseBudget * (percent / 100.0D)));
	}

	private static long crowdCellKey(Entity entity, double distanceSqr) {
		int x = Mth.floor(entity.getX()) >> CELL_SIZE_SHIFT;
		int z = Mth.floor(entity.getZ()) >> CELL_SIZE_SHIFT;
		int tier = distanceTier(distanceSqr);
		return (((long)x) & 0x3FFFFFFL) << 38 | (((long)z) & 0x3FFFFFFL) << 12 | tier;
	}

	private static int distanceTier(double distanceSqr) {
		if (distanceSqr > 112.0 * 112.0) {
			return 4;
		}
		if (distanceSqr > 72.0 * 72.0) {
			return 3;
		}
		if (distanceSqr > 40.0 * 40.0) {
			return 2;
		}
		if (distanceSqr > 20.0 * 20.0) {
			return 1;
		}
		return 0;
	}
}
