package net.optiminium.client;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobTopologyPolicyTest {
	@Test
	void requiresThreeSamplesAndTwoEntities() {
		MobTopologyPolicy policy = new MobTopologyPolicy();
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		policy.observe(42L, first, 1L);
		policy.observe(42L, first, 2L);
		assertFalse(policy.qualified());
		policy.observe(42L, second, 3L);
		assertTrue(policy.qualified());
	}

	@Test
	void topologyChangeImmediatelyRevokesQualification() {
		MobTopologyPolicy policy = new MobTopologyPolicy();
		policy.observe(42L, UUID.randomUUID(), 1L);
		policy.observe(42L, UUID.randomUUID(), 2L);
		policy.observe(42L, UUID.randomUUID(), 3L);
		assertTrue(policy.qualified());
		policy.observe(43L, UUID.randomUUID(), 4L);
		assertFalse(policy.qualified());
	}

	@Test
	void qualifiedTopologyUsesPeriodicValidation() {
		MobTopologyPolicy policy = new MobTopologyPolicy();
		policy.observe(42L, UUID.randomUUID(), 1L);
		policy.observe(42L, UUID.randomUUID(), 2L);
		policy.observe(42L, UUID.randomUUID(), 3L);
		assertFalse(policy.shouldValidate(122L));
		assertTrue(policy.shouldValidate(123L));
	}
}
