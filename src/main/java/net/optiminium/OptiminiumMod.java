package net.optiminium;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.optiminium.client.OptiminiumBenchmark;
import net.optiminium.client.OptiminiumBlockEntityCacheOverlay;
import net.optiminium.client.OptiminiumGlStateTracker;
import net.optiminium.client.OptiminiumKeyBindings;
import net.optiminium.client.OptiminiumKeyInput;
import net.optiminium.client.OptiminiumPersistentMeshBenchmarkScene;
import net.optiminium.client.OptiminiumPersistentMeshShader;
import net.optiminium.client.OptiminiumVideoSettingsButton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class OptiminiumMod implements ClientModInitializer {
	public static final Logger LOGGER = LogManager.getLogger(OptiminiumMod.class);
	public static final String MODID = "optiminium";

	@Override
	public void onInitializeClient() {
		OptiminiumGlStateTracker.init();
		OptiminiumKeyBindings.registerKeyMappings();
		CoreShaderRegistrationCallback.EVENT.register(OptiminiumPersistentMeshShader::register);
		HudRenderCallback.EVENT.register(OptiminiumBlockEntityCacheOverlay::render);
		ScreenEvents.AFTER_INIT.register((minecraft, screen, scaledWidth, scaledHeight) ->
			OptiminiumVideoSettingsButton.onScreenInit(screen));
		ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
			OptiminiumKeyInput.onClientTick();
			OptiminiumBenchmark.onClientTick();
			OptiminiumPersistentMeshBenchmarkScene.onClientTick();
		});
	}
}
