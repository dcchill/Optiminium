package net.optiminium.optimization;

import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicLong;

public final class OptiminiumMetrics {
	private static final LongAdder skippedEntityTicks = new LongAdder();
	private static final LongAdder virtualizedItems = new LongAdder();
	private static final LongAdder mergedXpOrbs = new LongAdder();
	private static final LongAdder mergedXpValue = new LongAdder();
	private static final LongAdder culledEntityRenders = new LongAdder();
	private static final LongAdder culledBlockEntityRenders = new LongAdder();
	private static final LongAdder hiddenNameTags = new LongAdder();
	private static final LongAdder hiddenParticles = new LongAdder();
	private static final LongAdder suppressedSounds = new LongAdder();
	private static final AtomicLong blockEntityLodCachedEntries = new AtomicLong();
	private static final LongAdder blockEntityLodRendered = new LongAdder();
	private static final LongAdder blockEntityLodEstimatedSkippedRenders = new LongAdder();

	private OptiminiumMetrics() {
	}

	public static void skippedEntityTick() {
		skippedEntityTicks.increment();
	}

	public static void virtualizedItems(int count) {
		virtualizedItems.add(count);
	}

	public static void mergedXpOrbs(int count, int value) {
		mergedXpOrbs.add(count);
		mergedXpValue.add(value);
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

	public static void suppressedSound() {
		suppressedSounds.increment();
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
			skippedEntityTicks.sum(),
			virtualizedItems.sum(),
			mergedXpOrbs.sum(),
			mergedXpValue.sum(),
			culledEntityRenders.sum(),
			culledBlockEntityRenders.sum(),
			hiddenNameTags.sum(),
			hiddenParticles.sum(),
			suppressedSounds.sum(),
			blockEntityLodCachedEntries.get(),
			blockEntityLodRendered.sum(),
			blockEntityLodEstimatedSkippedRenders.sum()
		);
	}

	public static void reset() {
		skippedEntityTicks.reset();
		virtualizedItems.reset();
		mergedXpOrbs.reset();
		mergedXpValue.reset();
		culledEntityRenders.reset();
		culledBlockEntityRenders.reset();
		hiddenNameTags.reset();
		hiddenParticles.reset();
		suppressedSounds.reset();
		blockEntityLodCachedEntries.set(0L);
		blockEntityLodRendered.reset();
		blockEntityLodEstimatedSkippedRenders.reset();
	}

	public record Snapshot(
		long skippedEntityTicks,
		long virtualizedItems,
		long mergedXpOrbs,
		long mergedXpValue,
		long culledEntityRenders,
		long culledBlockEntityRenders,
		long hiddenNameTags,
		long hiddenParticles,
		long suppressedSounds,
		long blockEntityLodCachedEntries,
		long blockEntityLodRendered,
		long blockEntityLodEstimatedSkippedRenders
	) {
	}
}
