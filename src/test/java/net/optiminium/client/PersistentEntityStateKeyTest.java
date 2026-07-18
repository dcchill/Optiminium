package net.optiminium.client;

import net.optiminium.client.persistence.PersistentEntityStateKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PersistentEntityStateKeyTest {
	@Test void exactFloatSignatureIsStable() {
		assertEquals(PersistentEntityStateKey.exactFloats(1.0F, -0.0F, 42.5F),
			PersistentEntityStateKey.exactFloats(1.0F, -0.0F, 42.5F));
	}

	@Test void exactFloatSignatureDoesNotQuantizeAnimationState() {
		assertNotEquals(PersistentEntityStateKey.exactFloats(1.0F),
			PersistentEntityStateKey.exactFloats(Math.nextUp(1.0F)));
		assertNotEquals(PersistentEntityStateKey.exactFloats(0.0F),
			PersistentEntityStateKey.exactFloats(-0.0F));
	}
}
