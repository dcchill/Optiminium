package net.optiminium.client.persistence;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Low-contention diagnostics for audited entity persistence, grouped by adapter family. */
public final class PersistentEntityMetrics {
	private static final Map<String, Counters> FAMILIES = new ConcurrentHashMap<>();

	private PersistentEntityMetrics() {}

	private static Counters counters(Object family) {
		return FAMILIES.computeIfAbsent(String.valueOf(family), ignored -> new Counters());
	}

	public static void eligible(Object family) { counters(family).eligible.increment(); }
	public static void active(Object family) { counters(family).active.increment(); }
	public static void candidate(Object family) { counters(family).candidates.increment(); }
	public static void cached(Object family) { counters(family).cached.increment(); }
	public static void anchor(Object family) { counters(family).anchors.increment(); }
	public static void unsupportedRenderType(Object family) { counters(family).unsupportedRenderTypes.increment(); }
	public static void dynamicStateFallback(Object family) { counters(family).dynamicStateFallbacks.increment(); }
	public static void safetyVeto(Object family, String reason) {
		Counters counters = counters(family);
		counters.safetyVetoes.increment();
		counters.safetyVetoReasons.computeIfAbsent(String.valueOf(reason), ignored -> new LongAdder()).increment();
	}
	public static void vanilla(Object family, long nanos) {
		Counters counters = counters(family);
		counters.vanilla.increment();
		counters.vanillaNanos.add(Math.max(0L, nanos));
	}
	public static void build(Object family, long nanos) {
		Counters counters = counters(family);
		counters.builds.increment();
		counters.buildNanos.add(Math.max(0L, nanos));
	}
	public static void fallback(Object family) { counters(family).fallbacks.increment(); }
	public static void validationMatch(Object family) { counters(family).validationMatches.increment(); }

	public static Snapshot snapshot() {
		long eligible = 0L, active = 0L, candidates = 0L, cached = 0L, anchors = 0L;
		long unsupported = 0L, dynamic = 0L, vetoes = 0L, vanilla = 0L, fallbacks = 0L, builds = 0L;
		long validationMatches = 0L;
		long vanillaNanos = 0L, buildNanos = 0L;
		Map<String, FamilySnapshot> families = new LinkedHashMap<>();
		for (Map.Entry<String, Counters> entry : FAMILIES.entrySet()) {
			Counters value = entry.getValue();
			Map<String, Long> reasons = new LinkedHashMap<>();
			value.safetyVetoReasons.forEach((reason, count) -> reasons.put(reason, count.sum()));
			FamilySnapshot family = new FamilySnapshot(entry.getKey(), value.eligible.sum(), value.active.sum(),
				value.candidates.sum(), value.cached.sum(), value.anchors.sum(), value.unsupportedRenderTypes.sum(),
				value.dynamicStateFallbacks.sum(), value.safetyVetoes.sum(), value.vanilla.sum(),
				value.fallbacks.sum(), value.builds.sum(), value.vanillaNanos.sum(), value.buildNanos.sum(),
				value.validationMatches.sum(), Map.copyOf(reasons));
			families.put(entry.getKey(), family);
			eligible += family.eligible(); active += family.active(); candidates += family.candidates();
			cached += family.cached(); anchors += family.anchors(); unsupported += family.unsupportedRenderTypes();
			dynamic += family.dynamicStateFallbacks(); vetoes += family.safetyVetoes();
			vanilla += family.vanillaDraws(); fallbacks += family.fallbacks(); builds += family.builds();
			validationMatches += family.validationMatches();
			vanillaNanos += family.vanillaNanos(); buildNanos += family.buildNanos();
		}
		return new Snapshot(families.size(), eligible, active, candidates, cached, anchors, unsupported,
			dynamic, vetoes, vanilla, fallbacks, builds, vanillaNanos, buildNanos,
			validationMatches, Map.copyOf(families));
	}

	public static void reset() { FAMILIES.clear(); }

	public record Snapshot(int families, long eligible, long active, long candidates, long cachedDraws,
		long anchors, long unsupportedRenderTypes, long dynamicStateFallbacks, long safetyVetoes,
		long vanillaDraws, long fallbacks, long builds, long vanillaNanos, long buildNanos,
		long validationMatches, Map<String, FamilySnapshot> familiesByName) {
		public static Snapshot empty() {
			return new Snapshot(0, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
				0L, 0L, 0L, Map.of());
		}
	}

	public record FamilySnapshot(String family, long eligible, long active, long candidates, long cached,
		long anchors, long unsupportedRenderTypes, long dynamicStateFallbacks, long safetyVetoes,
		long vanillaDraws, long fallbacks, long builds, long vanillaNanos, long buildNanos,
		long validationMatches, Map<String, Long> safetyVetoReasons) {}

	private static final class Counters {
		final LongAdder eligible = new LongAdder();
		final LongAdder active = new LongAdder();
		final LongAdder candidates = new LongAdder();
		final LongAdder cached = new LongAdder();
		final LongAdder anchors = new LongAdder();
		final LongAdder unsupportedRenderTypes = new LongAdder();
		final LongAdder dynamicStateFallbacks = new LongAdder();
		final LongAdder safetyVetoes = new LongAdder();
		final LongAdder vanilla = new LongAdder();
		final LongAdder fallbacks = new LongAdder();
		final LongAdder builds = new LongAdder();
		final LongAdder vanillaNanos = new LongAdder();
		final LongAdder buildNanos = new LongAdder();
		final LongAdder validationMatches = new LongAdder();
		final Map<String, LongAdder> safetyVetoReasons = new ConcurrentHashMap<>();
	}
}
