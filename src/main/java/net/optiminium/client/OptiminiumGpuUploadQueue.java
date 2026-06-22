package net.optiminium.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.optiminium.optimization.OptiminiumSettings;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

@EventBusSubscriber(modid = "optiminium", value = Dist.CLIENT)
public final class OptiminiumGpuUploadQueue {
	private static final Queue<Runnable> uploads = new ConcurrentLinkedQueue<>();

	private OptiminiumGpuUploadQueue() {
	}

	public static void enqueue(Runnable upload) {
		uploads.offer(upload);
	}

	public static int pendingUploads() {
		return uploads.size();
	}

	@SubscribeEvent
	public static void onFrame(RenderFrameEvent.Pre event) {
		int budget = Math.max(1, (int)Math.floor(OptiminiumSettings.getChunkUploadsPerFrame() * OptiminiumGpuOptimizer.getGpuWorkScale()));
		for (int i = 0; i < budget; i++) {
			Runnable upload = uploads.poll();
			if (upload == null) {
				return;
			}
			upload.run();
		}
	}
}
