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

	record FeatureKey(Object layer, Object state) {
		@Override public boolean equals(Object other) {
			return other instanceof FeatureKey key && layer == key.layer && state.equals(key.state);
		}

		@Override public int hashCode() {
			return 31 * System.identityHashCode(layer) + state.hashCode();
		}
	}

}
