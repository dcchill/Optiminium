package net.optiminium.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
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
import net.optiminium.optimization.OptiminiumMetrics;
import net.optiminium.optimization.OptiminiumSettings;

import java.util.HashMap;
import java.util.Map;

@EventBusSubscriber(modid = "optiminium", value = Dist.CLIENT)
public final class OptiminiumFpsOptimizer {
	private static final int CELL_SIZE_SHIFT = 4;
	private static final double ALWAYS_RENDER_DISTANCE_SQR = 10.0 * 10.0;
	private static final double NAME_TAG_DISTANCE_SQR = 32.0 * 32.0;
	private static final double PARTICLE_DISTANCE_SQR = 24.0 * 24.0;
	private static final Map<Long, Integer> renderedCrowdByCell = new HashMap<>();

	private OptiminiumFpsOptimizer() {
	}

	@SubscribeEvent
	public static void onFrameStart(RenderFrameEvent.Pre event) {
		renderedCrowdByCell.clear();
		OptiminiumGpuOptimizer.onFrameStart();
	}

	@SubscribeEvent
	public static void onRenderLiving(RenderLivingEvent.Pre<?, ?> event) {
		if (!OptiminiumSettings.isEnabled()) {
			return;
		}
		LivingEntity entity = event.getEntity();
		if (!isCullableCrowdEntity(entity)) {
			return;
		}

		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return;
		}

		double distanceSqr = player.distanceToSqr(entity);
		if (distanceSqr <= ALWAYS_RENDER_DISTANCE_SQR) {
			return;
		}

		int budget = renderBudgetForDistance(distanceSqr);
		long cellKey = crowdCellKey(entity, distanceSqr);
		int renderedInCell = renderedCrowdByCell.getOrDefault(cellKey, 0);
		if (renderedInCell >= budget) {
			event.setCanceled(true);
			OptiminiumMetrics.culledEntityRender();
			return;
		}
		renderedCrowdByCell.put(cellKey, renderedInCell + 1);
	}

	@SubscribeEvent
	public static void onRenderNameTag(RenderNameTagEvent event) {
		if (!OptiminiumSettings.isEnabled()) {
			return;
		}
		Entity entity = event.getEntity();
		if (!(entity instanceof LivingEntity) || entity instanceof LocalPlayer) {
			return;
		}
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null && player.distanceToSqr(entity) > NAME_TAG_DISTANCE_SQR) {
			event.setCanRender(TriState.FALSE);
			OptiminiumMetrics.hiddenNameTag();
		}
	}

	@SubscribeEvent
	public static void onEffectParticle(EffectParticleModificationEvent event) {
		if (!OptiminiumSettings.isEnabled()) {
			return;
		}
		LocalPlayer player = Minecraft.getInstance().player;
		LivingEntity entity = event.getEntity();
		if (player != null && entity != player && player.distanceToSqr(entity) > PARTICLE_DISTANCE_SQR) {
			event.setVisible(false);
			OptiminiumMetrics.hiddenParticle();
		}
	}

	private static boolean isCullableCrowdEntity(LivingEntity entity) {
		if (!(entity instanceof Mob mob) || entity instanceof Player) {
			return false;
		}
		if (entity instanceof AbstractVillager villager && villager.isTrading()) {
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
		if (distanceSqr > 112.0 * 112.0) {
			return 0;
		}
		if (distanceSqr > 72.0 * 72.0) {
			return 1;
		}
		if (distanceSqr > 40.0 * 40.0) {
			return 2;
		}
		if (distanceSqr > 20.0 * 20.0) {
			return 6;
		}
		return 12;
	}

	private static long crowdCellKey(Entity entity, double distanceSqr) {
		int x = entity.blockPosition().getX() >> CELL_SIZE_SHIFT;
		int z = entity.blockPosition().getZ() >> CELL_SIZE_SHIFT;
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
