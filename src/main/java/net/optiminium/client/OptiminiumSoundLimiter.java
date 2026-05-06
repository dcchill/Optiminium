package net.optiminium.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import net.optiminium.optimization.OptiminiumMetrics;
import net.optiminium.optimization.OptiminiumSettings;

@EventBusSubscriber(modid = "optiminium", value = Dist.CLIENT)
public final class OptiminiumSoundLimiter {
	private static final long WINDOW_NANOS = 250_000_000L;
	private static final int MAX_VILLAGER_AMBIENT_PER_WINDOW = 8;
	private static final int MAX_ENTITY_AMBIENT_PER_WINDOW = 32;
	private static long villagerAmbientWindowStart;
	private static long entityAmbientWindowStart;
	private static int villagerAmbientSounds;
	private static int entityAmbientSounds;

	private OptiminiumSoundLimiter() {
	}

	@SubscribeEvent
	public static void onPlaySound(PlaySoundEvent event) {
		if (!OptiminiumSettings.isEnabled() || event.getSound() == null) {
			return;
		}

		String name = event.getName();
		long now = System.nanoTime();
		if (isVillagerAmbient(name) && isOverVillagerAmbientBudget(now)) {
			event.setSound(null);
			OptiminiumMetrics.suppressedSound();
			return;
		}
		if (isEntityAmbient(name) && isOverEntityAmbientBudget(now)) {
			event.setSound(null);
			OptiminiumMetrics.suppressedSound();
		}
	}

	private static boolean isOverVillagerAmbientBudget(long now) {
		if (now - villagerAmbientWindowStart > WINDOW_NANOS) {
			villagerAmbientWindowStart = now;
			villagerAmbientSounds = 0;
		}
		villagerAmbientSounds++;
		return villagerAmbientSounds > MAX_VILLAGER_AMBIENT_PER_WINDOW;
	}

	private static boolean isOverEntityAmbientBudget(long now) {
		if (now - entityAmbientWindowStart > WINDOW_NANOS) {
			entityAmbientWindowStart = now;
			entityAmbientSounds = 0;
		}
		entityAmbientSounds++;
		return entityAmbientSounds > MAX_ENTITY_AMBIENT_PER_WINDOW;
	}

	private static boolean isVillagerAmbient(String soundName) {
		return soundName.equals("entity.villager.ambient")
			|| soundName.equals("entity.villager.celebrate")
			|| soundName.equals("entity.villager.no")
			|| soundName.equals("entity.villager.yes");
	}

	private static boolean isEntityAmbient(String soundName) {
		return soundName.startsWith("entity.")
			&& (soundName.endsWith(".ambient") || soundName.contains(".ambient_") || soundName.endsWith(".idle") || soundName.contains(".idle_"));
	}
}
