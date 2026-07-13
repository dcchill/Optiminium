package net.optiminium.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.optiminium.OptiminiumMod;

import java.io.IOException;

/** Shader registration for per-instance light/overlay on shared vanilla entity-format meshes. */
@EventBusSubscriber(modid = OptiminiumMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class OptiminiumPersistentMeshShader {
	private static volatile ShaderInstance shader;

	private OptiminiumPersistentMeshShader() {
	}

	@SubscribeEvent
	public static void register(RegisterShadersEvent event) throws IOException {
		event.registerShader(new ShaderInstance(event.getResourceProvider(),
			ResourceLocation.fromNamespaceAndPath(OptiminiumMod.MODID, "persistent_entity"),
			DefaultVertexFormat.NEW_ENTITY), loaded -> shader = loaded);
	}

	public static ShaderInstance get() {
		return shader;
	}
}
