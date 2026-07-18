package net.optiminium.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistentRenderTypeCompatibilityTest {
	@Test
	void acceptsPlainAndWrappedVanillaEntityTypes() {
		assertTrue(PersistentRenderTypeCompatibility.isCompatible("entity_cutout"));
		assertTrue(PersistentRenderTypeCompatibility.isCompatible(
			"RenderType[entity_cutout_no_cull:CompositeState[texture=minecraft:cow]]"));
		assertTrue(PersistentRenderTypeCompatibility.isCompatible(
			"RenderType[entity_solid]"));
		assertTrue(PersistentRenderTypeCompatibility.isCompatible(
			"RenderType[armor_cutout_no_cull:CompositeState[texture=minecraft:leather_layer_1]]"));
	}

	@Test
	void rejectsTranslucentGlintAndLookalikeCustomTypes() {
		assertFalse(PersistentRenderTypeCompatibility.isCompatible("entity_translucent"));
		assertFalse(PersistentRenderTypeCompatibility.isCompatible("entity_glint"));
		assertFalse(PersistentRenderTypeCompatibility.isCompatible("mod_entity_cutout"));
	}
}
