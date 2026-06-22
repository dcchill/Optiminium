package net.optiminium.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.optiminium.optimization.OptiminiumSettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@EventBusSubscriber(modid = "optiminium", value = Dist.CLIENT)
public final class OptiminiumBenchmark {
	private static final int PHASE_TICKS = 20 * 12;
	private static final int PARTICLES_PER_TICK = 180;
	private static final RandomSource random = RandomSource.create();
	private static boolean running;
	private static boolean previousEnabled;
	private static int ticks;
	private static Phase phase = Phase.OFF;
	private static long lastFrameNanos;
	private static long lastGpuSample;
	private static final List<Long> offFrames = new ArrayList<>();
	private static final List<Long> onFrames = new ArrayList<>();
	private static final List<Long> offGpuFrames = new ArrayList<>();
	private static final List<Long> onGpuFrames = new ArrayList<>();

	private OptiminiumBenchmark() {
	}

	public static void start() {
		if (running) {
			return;
		}
		running = true;
		previousEnabled = OptiminiumSettings.isEnabled();
		phase = Phase.OFF;
		ticks = 0;
		lastFrameNanos = 0L;
		lastGpuSample = OptiminiumGpuTimer.getSampleCount();
		offFrames.clear();
		onFrames.clear();
		offGpuFrames.clear();
		onGpuFrames.clear();
		OptiminiumSettings.setEnabled(false);
		message("Optiminium benchmark: OFF pass started.");
	}

	@SubscribeEvent
	public static void onFrame(RenderFrameEvent.Pre event) {
		if (!running) {
			return;
		}
		long now = System.nanoTime();
		if (lastFrameNanos != 0L) {
			(phase == Phase.OFF ? offFrames : onFrames).add(now - lastFrameNanos);
		}
		lastFrameNanos = now;
		long gpuSample = OptiminiumGpuTimer.getSampleCount();
		if (gpuSample != lastGpuSample && OptiminiumGpuTimer.hasTiming()) {
			(phase == Phase.OFF ? offGpuFrames : onGpuFrames).add(OptiminiumGpuTimer.getLatestGpuNanos());
			lastGpuSample = gpuSample;
		}
	}

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		if (!running) {
			return;
		}
		stressParticles();
		ticks++;
		if (ticks < PHASE_TICKS) {
			return;
		}
		if (phase == Phase.OFF) {
			phase = Phase.ON;
			ticks = 0;
			lastFrameNanos = 0L;
			lastGpuSample = OptiminiumGpuTimer.getSampleCount();
			OptiminiumSettings.setEnabled(true);
			message("Optiminium benchmark: ON pass started.");
			return;
		}
		running = false;
		OptiminiumSettings.setEnabled(previousEnabled);
		message("Optiminium benchmark: OFF " + stats(offFrames, offGpuFrames) + " | ON " + stats(onFrames, onGpuFrames));
	}

	private static void stressParticles() {
		Minecraft minecraft = Minecraft.getInstance();
		Player player = minecraft.player;
		if (player == null || minecraft.level == null) {
			return;
		}
		for (int i = 0; i < PARTICLES_PER_TICK; i++) {
			double x = player.getX() + (random.nextDouble() - 0.5D) * 18.0D;
			double y = player.getY() + random.nextDouble() * 6.0D;
			double z = player.getZ() + (random.nextDouble() - 0.5D) * 18.0D;
			minecraft.level.addParticle(ParticleTypes.CLOUD, x, y, z, 0.0D, 0.02D, 0.0D);
		}
	}

	private static String stats(List<Long> frames, List<Long> gpuFrames) {
		if (frames.isEmpty()) {
			return "no data";
		}
		long total = 0L;
		long slowest = 0L;
		for (long frame : frames) {
			total += frame;
			slowest = Math.max(slowest, frame);
		}
		double averageFps = 1_000_000_000.0D / (total / (double)frames.size());
		double onePercentLow = onePercentLowFps(frames);
		double worstFrameFps = 1_000_000_000.0D / slowest;
		String text = String.format("%.1f avg FPS, %.1f 1%% low FPS, %.1f worst-frame FPS", averageFps, onePercentLow, worstFrameFps);
		if (!gpuFrames.isEmpty()) {
			text += String.format(", %.2f avg GPU ms, %.2f worst GPU ms", averageNanos(gpuFrames) / 1_000_000.0D, maxNanos(gpuFrames) / 1_000_000.0D);
		}
		return text;
	}

	private static double onePercentLowFps(List<Long> frames) {
		List<Long> sorted = new ArrayList<>(frames);
		sorted.sort(Collections.reverseOrder());
		int count = Math.max(1, (int)Math.ceil(sorted.size() * 0.01D));
		long total = 0L;
		for (int i = 0; i < count; i++) {
			total += sorted.get(i);
		}
		return 1_000_000_000.0D / (total / (double)count);
	}

	private static double averageNanos(List<Long> frames) {
		long total = 0L;
		for (long frame : frames) {
			total += frame;
		}
		return total / (double)frames.size();
	}

	private static long maxNanos(List<Long> frames) {
		long max = 0L;
		for (long frame : frames) {
			max = Math.max(max, frame);
		}
		return max;
	}

	private static void message(String text) {
		if (Minecraft.getInstance().player != null) {
			Minecraft.getInstance().player.displayClientMessage(Component.literal(text), false);
		}
	}

	private enum Phase {
		OFF,
		ON
	}
}
