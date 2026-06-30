package net.optiminium.optimization;

import java.util.concurrent.atomic.LongAdder;

public final class OptiminiumMetrics {
	private static final LongAdder culledEntityRenders = new LongAdder();
	private static final LongAdder culledBlockEntityRenders = new LongAdder();
	private static final LongAdder hiddenNameTags = new LongAdder();
	private static final LongAdder hiddenParticles = new LongAdder();

	private OptiminiumMetrics() {
	}

	public static void culledEntityRender() {
		culledEntityRenders.increment();
	}

	public static void culledEntityRenders(long count) {
		if (count > 0) {
			culledEntityRenders.add(count);
		}
	}

	public static void culledBlockEntityRender() {
		culledBlockEntityRenders.increment();
	}

	public static void culledBlockEntityRenders(long count) {
		if (count > 0) {
			culledBlockEntityRenders.add(count);
		}
	}

	public static void hiddenNameTag() {
		hiddenNameTags.increment();
	}

	public static void hiddenNameTags(long count) {
		if (count > 0) {
			hiddenNameTags.add(count);
		}
	}

	public static void hiddenParticle() {
		hiddenParticles.increment();
	}

	public static void hiddenParticles(long count) {
		if (count > 0) {
			hiddenParticles.add(count);
		}
	}

	public static Snapshot snapshot() {
		return new Snapshot(
			culledEntityRenders.sum(),
			culledBlockEntityRenders.sum(),
			hiddenNameTags.sum(),
			hiddenParticles.sum()
		);
	}

	public static void reset() {
		culledEntityRenders.reset();
		culledBlockEntityRenders.reset();
		hiddenNameTags.reset();
		hiddenParticles.reset();
	}

	public record Snapshot(
		long culledEntityRenders,
		long culledBlockEntityRenders,
		long hiddenNameTags,
		long hiddenParticles
	) {
	}
}
