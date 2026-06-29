package net.optiminium.client;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.optiminium.compat.OptiminiumSodiumCompat;
import net.optiminium.optimization.OptiminiumSettings;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@EventBusSubscriber(modid = "optiminium", value = Dist.CLIENT)
public final class OptiminiumResourceStreamer {
	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "Optiminium Resource Streamer");
		thread.setDaemon(true);
		return thread;
	});
	private static volatile boolean scheduled;

	private OptiminiumResourceStreamer() {
	}

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		if (scheduled || !OptiminiumSettings.isEnabled() || !OptiminiumSettings.isAsyncResourceStreaming()
				|| OptiminiumSodiumCompat.isNonVanillaRenderer() || Minecraft.getInstance().level == null) {
			return;
		}
		scheduled = true;
		List<WarmResource> resources = collectWarmupResources(Minecraft.getInstance().getResourceManager());
		WORKER.execute(() -> warm(resources));
	}

	private static List<WarmResource> collectWarmupResources(ResourceManager manager) {
		List<WarmResource> resources = new ArrayList<>();
		add(resources, manager.listResources("textures", OptiminiumResourceStreamer::isPng));
		add(resources, manager.listResources("models", OptiminiumResourceStreamer::isJson));
		add(resources, manager.listResources("particles", OptiminiumResourceStreamer::isJson));
		add(resources, manager.listResources("sounds", OptiminiumResourceStreamer::isOgg));
		return resources;
	}

	private static void add(List<WarmResource> resources, Map<ResourceLocation, Resource> found) {
		found.forEach((location, resource) -> resources.add(new WarmResource(location, resource)));
	}

	private static void warm(List<WarmResource> resources) {
		for (WarmResource warmResource : resources) {
			try (var input = warmResource.resource.open()) {
				input.readAllBytes();
				if (isPng(warmResource.location)) {
					OptiminiumGpuUploadQueue.enqueue(() -> Minecraft.getInstance().getTextureManager().getTexture(warmResource.location), warmResource.location, true);
				}
			} catch (IOException ignored) {
			}
		}
	}

	private record WarmResource(ResourceLocation location, Resource resource) {
	}

	private static boolean isPng(ResourceLocation location) {
		return location.getPath().endsWith(".png");
	}

	private static boolean isJson(ResourceLocation location) {
		return location.getPath().endsWith(".json");
	}

	private static boolean isOgg(ResourceLocation location) {
		return location.getPath().endsWith(".ogg");
	}
}
