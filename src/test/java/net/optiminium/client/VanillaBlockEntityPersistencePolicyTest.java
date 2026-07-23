package net.optiminium.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VanillaBlockEntityPersistencePolicyTest {
	@Test
	void acceptsOpaqueSkullsAndRejectsTranslucentPlayerHeads() {
		assertTrue(VanillaBlockEntityPersistencePolicy.supportsWholeSkullMesh(
			VanillaBlockEntityPersistencePolicy.classifySkull(true, false, false)));
		assertTrue(VanillaBlockEntityPersistencePolicy.supportsWholeSkullMesh(
			VanillaBlockEntityPersistencePolicy.classifySkull(true, false, true)));
		assertFalse(VanillaBlockEntityPersistencePolicy.supportsWholeSkullMesh(
			VanillaBlockEntityPersistencePolicy.classifySkull(true, true, false)));
		assertFalse(VanillaBlockEntityPersistencePolicy.supportsWholeSkullMesh(
			VanillaBlockEntityPersistencePolicy.classifySkull(false, false, false)));
	}

	@Test
	void identifiesSkullModelsWithPoweredAnimation() {
		assertTrue(VanillaBlockEntityPersistencePolicy.hasAnimatedSkullGeometry(
			VanillaBlockEntityPersistencePolicy.classifySkull(true, false, true)));
		assertFalse(VanillaBlockEntityPersistencePolicy.hasAnimatedSkullGeometry(
			VanillaBlockEntityPersistencePolicy.classifySkull(true, false, false)));
		assertFalse(VanillaBlockEntityPersistencePolicy.hasAnimatedSkullGeometry(
			VanillaBlockEntityPersistencePolicy.classifySkull(true, true, false)));
	}
}
