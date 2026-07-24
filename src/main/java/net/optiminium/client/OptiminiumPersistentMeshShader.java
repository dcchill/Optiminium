package net.optiminium.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.optiminium.OptiminiumMod;

import java.io.IOException;

/** Shader registration for per-instance light/overlay on shared vanilla entity-format meshes. */
public final class OptiminiumPersistentMeshShader {
	private static volatile ShaderInstance shader;

	private OptiminiumPersistentMeshShader() {
	}

	public static void register(CoreShaderRegistrationCallback.RegistrationContext context) throws IOException {
		context.register(
			ResourceLocation.fromNamespaceAndPath(OptiminiumMod.MODID, "persistent_entity"),
			DefaultVertexFormat.NEW_ENTITY,
			loaded -> shader = loaded
		);
	}

	public static ShaderInstance get() {
		return shader;
	}
}
