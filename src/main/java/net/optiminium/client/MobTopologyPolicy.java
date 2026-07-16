package net.optiminium.client;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Deterministic qualification state for an exact ModelPart renderer topology. */
final class MobTopologyPolicy {
	private static final long VALIDATION_INTERVAL_FRAMES = 120L;
	private long fingerprint;
	private int matchingSamples;
	private boolean qualified;
	private final Set<UUID> entities = new HashSet<>();
	long lastSeenFrame;
	private long nextValidationFrame;

	void observe(long sample, UUID entityId, long frame) {
		lastSeenFrame = frame;
		if (matchingSamples == 0 || fingerprint != sample) {
			fingerprint = sample;
			matchingSamples = 1;
			qualified = false;
			entities.clear();
			entities.add(entityId);
			return;
		}
		matchingSamples++;
		entities.add(entityId);
		qualified = matchingSamples >= 3 && entities.size() >= 2;
		if (qualified) nextValidationFrame = frame + VALIDATION_INTERVAL_FRAMES;
	}

	boolean qualified() { return qualified; }
	boolean shouldValidate(long frame) { return !qualified || frame >= nextValidationFrame; }
	int matchingSamples() { return matchingSamples; }
	int distinctEntities() { return entities.size(); }
	long fingerprint() { return fingerprint; }
}
