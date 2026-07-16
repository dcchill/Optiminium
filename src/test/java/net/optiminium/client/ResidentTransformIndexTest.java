package net.optiminium.client;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResidentTransformIndexTest {
	@Test
	void stableTransformsKeepTheirSlotWithoutAnotherUpload() {
		ResidentTransformIndex index = new ResidentTransformIndex();
		Object owner = new Object();
		ResidentTransformIndex.Update first = index.observe(owner,
			new Matrix4f().translation(12.0F, 64.0F, -3.0F), 1L);
		ResidentTransformIndex.Update stable = index.observe(owner,
			new Matrix4f().translation(12.00001F, 64.0F, -3.0F), 2L);

		assertEquals(first.slot(), stable.slot());
		assertTrue(first.changed());
		assertFalse(stable.changed());
	}

	@Test
	void changedTransformsUploadAndExpiredSlotsAreReused() {
		ResidentTransformIndex index = new ResidentTransformIndex();
		Object firstOwner = new Object();
		int firstSlot = index.observe(firstOwner, new Matrix4f(), 1L).slot();
		assertTrue(index.observe(firstOwner, new Matrix4f().translation(1.0F, 0.0F, 0.0F), 2L).changed());
		assertEquals(1, index.expire(603L, 600L).size());

		int reused = index.observe(new Object(), new Matrix4f(), 604L).slot();
		assertEquals(firstSlot, reused);
	}

	@Test
	void staticOwnersCanRefreshTheirSlotWithoutAnotherMatrix() {
		ResidentTransformIndex index = new ResidentTransformIndex();
		Object owner = new Object();
		int slot = index.observe(owner, new Matrix4f().translation(2.0F, 3.0F, 4.0F), 1L).slot();

		assertEquals(slot, index.touch(owner, 500L));
		assertTrue(index.expire(1_100L, 600L).isEmpty());
		assertEquals(-1, index.touch(new Object(), 1_101L));
	}
}
