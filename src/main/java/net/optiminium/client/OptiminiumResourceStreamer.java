package net.optiminium.client;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.optiminium.optimization.OptiminiumSettings;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@EventBusSubscriber(modid = "optiminium", value = Dist.CLIENT)
public final class OptiminiumResourceStreamer {
	private static final Map<ResourceLocation, byte[]> shaderCache = new ConcurrentHashMap<>();
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
		if (scheduled || !OptiminiumSettings.isEnabled() || !OptiminiumSettings.isAsyncResourceStreaming() || Minecraft.getInstance().level == null) {
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
		if (OptiminiumSettings.isShaderResourceCache()) {
			add(resources, manager.listResources("shaders", OptiminiumResourceStreamer::isShaderResource));
		}
		return resources;
	}

	private static void add(List<WarmResource> resources, Map<ResourceLocation, Resource> found) {
		found.forEach((location, resource) -> resources.add(new WarmResource(location, resource)));
	}

	private static void warm(List<WarmResource> resources) {
		for (WarmResource warmResource : resources) {
			try (var input = warmResource.resource.open()) {
				byte[] bytes = input.readAllBytes();
				if (isShaderResource(warmResource.location)) {
					shaderCache.put(warmResource.location, bytes);
				}
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

	private static boolean isShaderResource(ResourceLocation location) {
		String path = location.getPath();
		return path.endsWith(".json") || path.endsWith(".vsh") || path.endsWith(".fsh") || path.endsWith(".glsl");
	}
}
