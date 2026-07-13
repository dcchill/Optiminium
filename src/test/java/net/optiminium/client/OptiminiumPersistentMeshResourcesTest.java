package net.optiminium.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptiminiumPersistentMeshResourcesTest {
	@Test
	void shaderKeepsLightAndOverlayAsPerInstanceUniforms() throws IOException {
		String vertex = resource("/assets/optiminium/shaders/core/persistent_entity.vsh");
		String descriptor = resource("/assets/optiminium/shaders/core/persistent_entity.json");

		assertTrue(vertex.contains("in vec2 InstanceLight"));
		assertTrue(vertex.contains("in vec2 InstanceOverlay"));
		assertTrue(vertex.contains("in vec4 InstanceModel0"));
		assertTrue(vertex.contains("texelFetch(Sampler2, ivec2(InstanceLight) / 16, 0)"));
		assertTrue(vertex.contains("fog_distance(viewPosition.xyz, FogShape)"));
		assertTrue(vertex.contains("mat3(instanceModel) * Normal"));
		assertTrue(!descriptor.contains("\"name\": \"InstanceLight\""));
	}

	private static String resource(String path) throws IOException {
		try (InputStream stream = OptiminiumPersistentMeshResourcesTest.class.getResourceAsStream(path)) {
			assertNotNull(stream, "missing resource " + path);
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
