package net.optiminium.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class FpsGainTimeline {
	private FpsGainTimeline() {
	}

	static List<Slice> calculate(List<Long> offFrameTimes, List<Long> onFrameTimes, int requestedSlices) {
		if (offFrameTimes.isEmpty() || onFrameTimes.isEmpty() || requestedSlices <= 0) return List.of();
		int sliceCount = Math.min(requestedSlices, Math.min(offFrameTimes.size(), onFrameTimes.size()));
		double[] offFps = durationWindowFps(offFrameTimes, sliceCount);
		double[] onFps = durationWindowFps(onFrameTimes, sliceCount);
		List<Slice> slices = new ArrayList<>(sliceCount);
		for (int i = 0; i < sliceCount; i++) {
			double start = i * 100.0D / sliceCount;
			double end = (i + 1) * 100.0D / sliceCount;
			double gain = onFps[i] - offFps[i];
			slices.add(new Slice(format(start) + "–" + format(end) + "%", offFps[i], onFps[i], gain,
				percentChange(offFps[i], onFps[i])));
		}
		return List.copyOf(slices);
	}

	private static double[] durationWindowFps(List<Long> frameTimes, int sliceCount) {
		long totalNanos = 0L;
		for (long frameTime : frameTimes) totalNanos += Math.max(0L, frameTime);
		double[] fps = new double[sliceCount];
		double[] frames = new double[sliceCount];
		if (totalNanos <= 0L) return fps;
		long cursor = 0L;
		for (long rawFrameTime : frameTimes) {
			long frameTime = Math.max(0L, rawFrameTime);
			long frameStart = cursor;
			long frameEnd = cursor + frameTime;
			while (frameStart < frameEnd) {
				int slice = Math.min(sliceCount - 1, (int)(frameStart * sliceCount / totalNanos));
				long boundary = (long)Math.ceil((slice + 1) * (double)totalNanos / sliceCount);
				long portion = Math.min(frameEnd, boundary) - frameStart;
				if (portion <= 0L) break;
				fps[slice] += portion;
				frames[slice] += portion / (double)Math.max(1L, frameTime);
				frameStart += portion;
			}
			cursor = frameEnd;
		}
		for (int i = 0; i < sliceCount; i++) {
			fps[i] = fps[i] <= 0.0D ? 0.0D : frames[i] * 1_000_000_000.0D / fps[i];
		}
		return fps;
	}

	private static double percentChange(double oldValue, double newValue) {
		if (Math.abs(oldValue) < 0.000001D) return Math.abs(newValue) < 0.000001D ? 0.0D : 100.0D;
		return (newValue - oldValue) * 100.0D / Math.abs(oldValue);
	}

	private static String format(double value) {
		return String.format(Locale.US, "%.2f", value);
	}

	record Slice(String label, double offFps, double onFps, double fpsGain, double gainPercent) {
	}
}
