package net.optiminium.optimization;

import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicLong;

public final class OptiminiumMetrics {
	private static final LongAdder culledEntityRenders = new LongAdder();
	private static final LongAdder culledBlockEntityRenders = new LongAdder();
	private static final LongAdder hiddenNameTags = new LongAdder();
	private static final LongAdder hiddenParticles = new LongAdder();
	private static final AtomicLong blockEntityLodCachedEntries = new AtomicLong();
	private static final LongAdder blockEntityLodRendered = new LongAdder();
	private static final LongAdder blockEntityLodEstimatedSkippedRenders = new LongAdder();

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

	public static void blockEntityLodCachedEntries(long count) {
		blockEntityLodCachedEntries.set(Math.max(0L, count));
	}

	public static void blockEntityLodRendered(long count) {
		if (count > 0) {
			blockEntityLodRendered.add(count);
		}
	}

	public static void blockEntityLodEstimatedSkippedRenders(long count) {
		if (count > 0) {
			blockEntityLodEstimatedSkippedRenders.add(count);
		}
	}

	public static Snapshot snapshot() {
		return new Snapshot(
			culledEntityRenders.sum(),
			culledBlockEntityRenders.sum(),
			hiddenNameTags.sum(),
			hiddenParticles.sum(),
			blockEntityLodCachedEntries.get(),
			blockEntityLodRendered.sum(),
			blockEntityLodEstimatedSkippedRenders.sum()
		);
	}

	public static void reset() {
		culledEntityRenders.reset();
		culledBlockEntityRenders.reset();
		hiddenNameTags.reset();
		hiddenParticles.reset();
		blockEntityLodCachedEntries.set(0L);
		blockEntityLodRendered.reset();
		blockEntityLodEstimatedSkippedRenders.reset();
	}

	public record Snapshot(
		long culledEntityRenders,
		long culledBlockEntityRenders,
		long hiddenNameTags,
		long hiddenParticles,
		long blockEntityLodCachedEntries,
		long blockEntityLodRendered,
		long blockEntityLodEstimatedSkippedRenders
	) {
	}
}
