package net.optiminium.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericMeshQualificationTest {
	@Test
	void requiresThreeIdenticalSpacedSamples() {
		OptiminiumPersistentBlockEntityMeshes.GenericQualification qualification =
			new OptiminiumPersistentBlockEntityMeshes.GenericQualification();
		qualification.observe(42L, 0L);
		assertFalse(qualification.qualified);
		qualification.observe(42L, 10L);
		assertFalse(qualification.qualified);
		qualification.observe(42L, 20L);
		assertTrue(qualification.qualified);
	}

	@Test
	void changedOutputResetsQualification() {
		OptiminiumPersistentBlockEntityMeshes.GenericQualification qualification =
			new OptiminiumPersistentBlockEntityMeshes.GenericQualification();
		qualification.observe(42L, 0L);
		qualification.observe(42L, 10L);
		qualification.observe(7L, 20L);
		assertFalse(qualification.qualified);
		assertTrue(qualification.qualifying);
	}

	@Test
	void validationMismatchReturnsToSampling() {
		OptiminiumPersistentBlockEntityMeshes.GenericQualification qualification =
			new OptiminiumPersistentBlockEntityMeshes.GenericQualification();
		qualification.observe(42L, 0L);
		qualification.observe(42L, 10L);
		qualification.observe(42L, 20L);
		qualification.reset(7L, 140L);
		assertFalse(qualification.qualified);
		assertTrue(qualification.qualifying);
	}
}
