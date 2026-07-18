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
		String fragment = resource("/assets/optiminium/shaders/core/persistent_entity.fsh");

		assertTrue(vertex.contains("in vec2 InstanceLight"));
		assertTrue(vertex.contains("in vec2 InstanceOverlay"));
		assertTrue(vertex.contains("in float InstanceTransformIndex"));
		assertTrue(vertex.contains("uniform samplerBuffer TransformPalette"));
		assertTrue(vertex.contains("uniform vec3 PersistentCameraPos"));
		assertTrue(vertex.contains("in float InstanceWorldSpace"));
		assertTrue(vertex.contains("in vec4 InstanceColor"));
		assertTrue(vertex.contains("uniform samplerBuffer BonePalette"));
		assertTrue(vertex.contains("in float InstancePaletteOffset"));
		assertTrue(vertex.contains("UV1.x") && vertex.contains("texelFetch(BonePalette"));
		assertTrue(vertex.contains("in vec4 InstanceDirectMatrix0"));
		assertTrue(vertex.contains("in vec4 InstanceDirectMatrix3"));
		assertTrue(vertex.contains("InstancePaletteOffset < -1.5"));
		assertTrue(vertex.contains("mat4(InstanceDirectMatrix0"));
		assertTrue(descriptor.contains("\"name\": \"BonePalette\""));
		assertTrue(descriptor.contains("\"name\": \"TransformPalette\""));
		assertTrue(vertex.contains("InstanceLight.x > 65500.0 ? UV2 : ivec2(InstanceLight)"));
		assertTrue(vertex.contains("texelFetch(Sampler2, lightCoords / 16, 0)"));
		assertTrue(vertex.contains("fog_distance(viewPosition.xyz, FogShape)"));
		assertTrue(vertex.contains("transpose(inverse(mat3(instanceModel))) * Normal"));
		assertTrue(!descriptor.contains("\"name\": \"InstanceLight\""));
		assertTrue(fragment.contains("if (color.a < 0.1) discard"));
	}

	@Test
	void itemFrameBackingSplitMixinIsPackaged() throws IOException {
		String mixins = resource("/optiminium.mixins.json");
		assertTrue(mixins.contains("\"ItemFrameRendererMixin\""));
		assertTrue(mixins.contains("\"MinecartRendererMixin\""));
	}

	private static String resource(String path) throws IOException {
		try (InputStream stream = OptiminiumPersistentMeshResourcesTest.class.getResourceAsStream(path)) {
			assertNotNull(stream, "missing resource " + path);
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
