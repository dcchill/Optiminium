package net.optiminium.optimization;

import java.util.concurrent.atomic.LongAdder;

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

	public static void culledBlockEntityRender() {
		culledBlockEntityRenders.increment();
	}

	public static void hiddenNameTag() {
		hiddenNameTags.increment();
	}

	public static void hiddenParticle() {
		hiddenParticles.increment();
	}

	public static void suppressedSound() {
		suppressedSounds.increment();
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
			suppressedSounds.sum()
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
		long suppressedSounds
	) {
	}
}
