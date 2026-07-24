package net.optiminium.client;

final class ArmorStandOptimizationPolicy {
	private ArmorStandOptimizationPolicy() {
	}

	static boolean isDeterministicCaptureFailure(String reason) {
		return reason != null && (reason.startsWith("sorted:")
			|| "primitive_mode".equals(reason)
			|| "vertex_format".equals(reason)
			|| "shader_state".equals(reason));
	}

	/**
	 * Vanilla seeds held and head-item model selection from the entity id. Empty armor stands
	 * remain safely shareable, while equipped stands retain the seed used by their exact render.
	 */
	static int renderSeed(boolean hasEquipment, int entityId) {
		return hasEquipment ? entityId : 0;
	}

	record FeatureKey(Object layer, Object state) {
		@Override public boolean equals(Object other) {
			return other instanceof FeatureKey key && layer == key.layer && state.equals(key.state);
		}

		@Override public int hashCode() {
			return 31 * System.identityHashCode(layer) + state.hashCode();
		}
	}

}
