package net.optiminium.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.event.entity.living.EffectParticleModificationEvent;
import net.optiminium.optimization.OptiminiumSettings;

@EventBusSubscriber(modid = "optiminium", value = Dist.CLIENT)
public final class OptiminiumFpsOptimizer {
	private static boolean particleLimiter;
	private static LocalPlayer player;
	private static double effectParticleDistanceSqr;

	private OptiminiumFpsOptimizer() {
	}

	@SubscribeEvent
	public static void onFrameStart(RenderFrameEvent.Pre event) {
		OptiminiumGlStateTracker.onFrameStart();
		OptiminiumGpuOptimizer.onFrameStart();
		OptiminiumRenderProfiler.onFrameStart();
		OptiminiumVisualSignificance.onFrameStart();
		boolean enabled = OptiminiumSettings.isEnabled();
		particleLimiter = enabled && OptiminiumSettings.isParticleLimiter();
		if (particleLimiter) {
			player = Minecraft.getInstance().player;
			double effectParticleDistance = Math.max(8.0D,
				OptiminiumSettings.getParticleRenderDistanceBlocks() * 0.375D * OptiminiumGpuOptimizer.getParticleWorkScale());
			effectParticleDistanceSqr = effectParticleDistance * effectParticleDistance;
		} else {
			player = null;
			effectParticleDistanceSqr = 0.0D;
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
}
