package net.optiminium.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArmorStandOptimizationPolicyTest {
	@Test
	void classifiesOnlyStructuralCaptureFailuresAsDeterministic() {
		assertTrue(ArmorStandOptimizationPolicy.isDeterministicCaptureFailure("sorted:entity_translucent"));
		assertTrue(ArmorStandOptimizationPolicy.isDeterministicCaptureFailure("primitive_mode"));
		assertTrue(ArmorStandOptimizationPolicy.isDeterministicCaptureFailure("vertex_format"));
		assertTrue(ArmorStandOptimizationPolicy.isDeterministicCaptureFailure("shader_state"));
		assertFalse(ArmorStandOptimizationPolicy.isDeterministicCaptureFailure("IllegalStateException:driver"));
		assertFalse(ArmorStandOptimizationPolicy.isDeterministicCaptureFailure(null));
	}

	@Test
	void sharesFeatureKeysAcrossEntitiesWithTheSameExactState() {
		Object layer = new Object();
		Object state = "same-revisioned-state";
		assertEquals(new ArmorStandOptimizationPolicy.FeatureKey(layer, state),
			new ArmorStandOptimizationPolicy.FeatureKey(layer, state));
		assertNotEquals(new ArmorStandOptimizationPolicy.FeatureKey(layer, state),
			new ArmorStandOptimizationPolicy.FeatureKey(new Object(), state));
		assertNotEquals(new ArmorStandOptimizationPolicy.FeatureKey(layer, state),
			new ArmorStandOptimizationPolicy.FeatureKey(layer, "changed-revisioned-state"));
	}

	@Test
	void keepsVanillaEntitySeedOnlyForEquippedArmorStands() {
		assertEquals(0, ArmorStandOptimizationPolicy.renderSeed(false, 41));
		assertEquals(41, ArmorStandOptimizationPolicy.renderSeed(true, 41));
		assertNotEquals(
			ArmorStandOptimizationPolicy.renderSeed(true, 41),
			ArmorStandOptimizationPolicy.renderSeed(true, 42));
	}
}
