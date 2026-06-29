package net.optiminium.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.optiminium.optimization.OptiminiumSettings;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

public final class OptiminiumSettingsScreen extends Screen {
	private static final Component TITLE = Component.literal("Optiminium");
	private static final int BUTTON_WIDTH = 220;
	private static final int BUTTON_HEIGHT = 20;
	private final Screen lastScreen;
	private boolean advanced;

	public OptiminiumSettingsScreen(Screen lastScreen) {
		super(TITLE);
		this.lastScreen = lastScreen;
	}

	@Override
	protected void init() {
		if (advanced) {
			initAdvanced();
		} else {
			initSimple();
		}
	}

	private void initSimple() {
		int x = (this.width - BUTTON_WIDTH) / 2;
		int y = 54;
		this.addRenderableWidget(Button.builder(enabledLabel(), button -> button.setMessage(Component.literal("Optiminium: " + (OptiminiumSettings.toggleEnabled() ? "ON" : "OFF"))))
			.bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
			.build());
		this.addRenderableWidget(Button.builder(Component.literal("High Performance"), button -> applyPreset(OptiminiumSettings.Preset.HIGH_PERFORMANCE))
			.bounds(x, y + 34, BUTTON_WIDTH, BUTTON_HEIGHT)
			.build());
		this.addRenderableWidget(Button.builder(Component.literal("Medium"), button -> applyPreset(OptiminiumSettings.Preset.MEDIUM))
			.bounds(x, y + 60, BUTTON_WIDTH, BUTTON_HEIGHT)
			.build());
		this.addRenderableWidget(Button.builder(Component.literal("Quality"), button -> applyPreset(OptiminiumSettings.Preset.QUALITY))
			.bounds(x, y + 86, BUTTON_WIDTH, BUTTON_HEIGHT)
			.build());
		this.addRenderableWidget(new SettingsSlider(x, y + 120, OptiminiumSettings::getEntityAlwaysRenderDistanceBlocks, OptiminiumSettings::setEntityAlwaysRenderDistanceBlocks, 10, 200, "Entity Safe Range"));
		this.addRenderableWidget(Button.builder(Component.literal("Advanced Settings"), button -> {
				this.advanced = true;
				refreshWidgets();
			})
			.bounds(x, y + 154, BUTTON_WIDTH, BUTTON_HEIGHT)
			.build());
		this.addRenderableWidget(Button.builder(Component.literal("Run Benchmark"), button -> OptiminiumBenchmark.start())
			.bounds(x, y + 180, BUTTON_WIDTH, BUTTON_HEIGHT)
			.build());
		this.addRenderableWidget(Button.builder(Component.literal("Run Full Benchmark"), button -> OptiminiumBenchmark.startFull())
			.bounds(x, y + 206, BUTTON_WIDTH, BUTTON_HEIGHT)
			.build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
			.bounds((this.width - 200) / 2, this.height - 30, 200, BUTTON_HEIGHT)
			.build());
	}

	private void initAdvanced() {
		boolean twoColumns = this.width >= BUTTON_WIDTH * 2 + 24;
		int leftX = twoColumns ? this.width / 2 - BUTTON_WIDTH - 6 : (this.width - BUTTON_WIDTH) / 2;
		int rightX = twoColumns ? this.width / 2 + 6 : leftX;
		int rightY = twoColumns ? 42 : 214;
		int x = leftX;
		int y = 42;
		this.addRenderableWidget(Button.builder(enabledLabel(), button -> button.setMessage(Component.literal("Optiminium: " + (OptiminiumSettings.toggleEnabled() ? "ON" : "OFF"))))
			.bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
			.build());
		this.addRenderableWidget(Button.builder(asyncResourceStreamingLabel(), button -> button.setMessage(Component.literal("Async Resource Streaming: " + (OptiminiumSettings.toggleAsyncResourceStreaming() ? "ON" : "OFF"))))
			.bounds(x, y + 26, BUTTON_WIDTH, BUTTON_HEIGHT)
			.build());
		this.addRenderableWidget(Button.builder(shaderResourceCacheLabel(), button -> button.setMessage(Component.literal("Shader Resource Cache: " + (OptiminiumSettings.toggleShaderResourceCache() ? "ON" : "OFF"))))
			.bounds(x, y + 52, BUTTON_WIDTH, BUTTON_HEIGHT)
			.build());
		this.addRenderableWidget(Button.builder(experimentalRendererLabel(), button -> button.setMessage(Component.literal("Experimental Renderer: " + (OptiminiumSettings.toggleExperimentalRendererFeatures() ? "ON" : "OFF"))))
			.bounds(x, y + 78, BUTTON_WIDTH, BUTTON_HEIGHT)
			.build());
		this.addRenderableWidget(Button.builder(uploadStallLimiterLabel(), button -> button.setMessage(Component.literal("Upload Stall Limiter: " + (OptiminiumSettings.toggleExperimentalUploadStallLimiter() ? "ON" : "OFF"))))
			.bounds(x, y + 104, BUTTON_WIDTH, BUTTON_HEIGHT)
			.build());
		this.addRenderableWidget(Button.builder(temporalSignificanceLabel(), button -> button.setMessage(Component.literal("Temporal Significance: " + (OptiminiumSettings.toggleExperimentalTemporalSignificance() ? "ON" : "OFF"))))
			.bounds(x, y + 130, BUTTON_WIDTH, BUTTON_HEIGHT)
			.build());
		this.addRenderableWidget(Button.builder(framePacingLabel(), button -> button.setMessage(Component.literal("Frame Pacing: " + (OptiminiumSettings.toggleFramePacing() ? "ON" : "OFF"))))
			.bounds(x, y + 156, BUTTON_WIDTH, BUTTON_HEIGHT)
			.build());
		this.addRenderableWidget(Button.builder(gpuTimerPacingLabel(), button -> button.setMessage(Component.literal("GPU Timer Pacing: " + (OptiminiumSettings.toggleGpuTimerPacing() ? "ON" : "OFF"))))
			.bounds(x, y + 182, BUTTON_WIDTH, BUTTON_HEIGHT)
			.build());
		this.addRenderableWidget(new SettingsSlider(x, y + 208, OptiminiumSettings::getGpuTargetFps, OptiminiumSettings::setGpuTargetFps, 30, 240, "Target FPS"));
		this.addRenderableWidget(new SettingsSlider(x, y + 234, OptiminiumSettings::getGpuMinRenderScalePercent, OptiminiumSettings::setGpuMinRenderScalePercent, 35, 100, "Min Scale %"));
		this.addRenderableWidget(new SettingsSlider(x, y + 260, OptiminiumSettings::getEntityAlwaysRenderDistanceBlocks, OptiminiumSettings::setEntityAlwaysRenderDistanceBlocks, 10, 200, "Entity Safe Range"));
		this.addRenderableWidget(Button.builder(blockEntityLodDebugLabel(), button -> button.setMessage(Component.literal("Block Entity LOD Debug: " + (OptiminiumSettings.toggleBlockEntityLodDebug() ? "ON" : "OFF"))))
			.bounds(x, y + 286, BUTTON_WIDTH, BUTTON_HEIGHT)
			.build());
		x = rightX;
		y = rightY;
		this.addRenderableWidget(Button.builder(particleLimiterLabel(), button -> button.setMessage(Component.literal("Particle Limiter: " + (OptiminiumSettings.toggleParticleLimiter() ? "ON" : "OFF"))))
			.bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
			.build());
		this.addRenderableWidget(new SettingsSlider(x, y + 26, OptiminiumSettings::getParticleRenderDistanceBlocks, OptiminiumSettings::setParticleRenderDistanceBlocks, 16, 160, "Particle Distance"));
		this.addRenderableWidget(new SettingsSlider(x, y + 52, OptiminiumSettings::getMaxParticlesPerFrame, OptiminiumSettings::setMaxParticlesPerFrame, 16, 512, "Particles/Frame"));
		this.addRenderableWidget(Button.builder(blockEntityCullingLabel(), button -> button.setMessage(Component.literal("Block Entity Culling: " + (OptiminiumSettings.toggleBlockEntityCulling() ? "ON" : "OFF"))))
			.bounds(x, y + 86, BUTTON_WIDTH, BUTTON_HEIGHT)
			.build());
		this.addRenderableWidget(Button.builder(blockEntityLodCubesLabel(), button -> button.setMessage(Component.literal("Block Entity LOD Cubes: " + (OptiminiumSettings.toggleBlockEntityLodCubes() ? "ON" : "OFF"))))
			.bounds(x, y + 112, BUTTON_WIDTH, BUTTON_HEIGHT)
			.build());
		this.addRenderableWidget(new SettingsSlider(x, y + 138, OptiminiumSettings::getBlockEntityDistanceScalePercent, OptiminiumSettings::setBlockEntityDistanceScalePercent, 25, 200, "Block Entity Range %"));
		this.addRenderableWidget(Button.builder(denseSceneAdaptiveLabel(), button -> button.setMessage(denseSceneAdaptiveLabel(OptiminiumSettings.cycleDenseSceneAdaptiveMode())))
			.bounds(x, y + 164, BUTTON_WIDTH, BUTTON_HEIGHT)
			.build());
		this.addRenderableWidget(Button.builder(adaptiveSimulationLabel(), button -> button.setMessage(Component.literal("Dynamic Simulation Distance: " + (OptiminiumSettings.toggleAdaptiveSimulationDistance() ? "ON" : "OFF"))))
			.bounds(x, y + 198, BUTTON_WIDTH, BUTTON_HEIGHT)
			.build());
		this.addRenderableWidget(new SettingsSlider(x, y + 224, OptiminiumSettings::getAdaptiveSimulationTargetMspt, OptiminiumSettings::setAdaptiveSimulationTargetMspt, 35, 80, "Target MSPT"));
		this.addRenderableWidget(new SettingsSlider(x, y + 250, OptiminiumSettings::getAdaptiveSimulationMinDistanceChunks, OptiminiumSettings::setAdaptiveSimulationMinDistanceChunks, 2, 12, "Min Sim Distance"));
		this.addRenderableWidget(Button.builder(smartTickLabel(), button -> button.setMessage(Component.literal("SmartTick Scheduler: " + (OptiminiumSettings.toggleSmartTickScheduler() ? "ON" : "OFF"))))
			.bounds(x, y + 284, BUTTON_WIDTH, BUTTON_HEIGHT)
			.build());
		this.addRenderableWidget(Button.builder(aiPathfindingLabel(), button -> button.setMessage(Component.literal("AI Pathfinding Optimizer: " + (OptiminiumSettings.toggleAiPathfindingOptimizer() ? "ON" : "OFF"))))
			.bounds(x, y + 310, BUTTON_WIDTH, BUTTON_HEIGHT)
			.build());
		this.addRenderableWidget(Button.builder(Component.literal("Run Benchmark"), button -> OptiminiumBenchmark.start())
			.bounds(x, y + 344, BUTTON_WIDTH, BUTTON_HEIGHT)
			.build());
		this.addRenderableWidget(Button.builder(Component.literal("Run Full Benchmark"), button -> OptiminiumBenchmark.startFull())
			.bounds(x, y + 370, BUTTON_WIDTH, BUTTON_HEIGHT)
			.build());
		this.addRenderableWidget(Button.builder(Component.literal("Simple Settings"), button -> {
				this.advanced = false;
				refreshWidgets();
			})
			.bounds((this.width - 200) / 2, this.height - 56, 200, BUTTON_HEIGHT)
			.build());
		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
			.bounds((this.width - 200) / 2, this.height - 30, 200, BUTTON_HEIGHT)
			.build());
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		graphics.drawCenteredString(this.font, TITLE, this.width / 2, 16, 0xFFFFFF);
		graphics.drawCenteredString(this.font, "GPU Timer: " + OptiminiumGpuTimer.status(), this.width / 2, 28, 0xA0A0A0);
	}

	private void applyPreset(OptiminiumSettings.Preset preset) {
		OptiminiumSettings.applyPreset(preset);
		refreshWidgets();
	}

	private void refreshWidgets() {
		this.clearWidgets();
		init();
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(this.lastScreen);
	}

	private static Component framePacingLabel() {
		return Component.literal("Frame Pacing: " + (OptiminiumSettings.isGpuOptimizer() ? "ON" : "OFF"));
	}

	private static Component gpuTimerPacingLabel() {
		return Component.literal("GPU Timer Pacing: " + (OptiminiumSettings.isGpuTimerPacing() ? "ON" : "OFF"));
	}

	private static Component enabledLabel() {
		return Component.literal("Optiminium: " + (OptiminiumSettings.isEnabled() ? "ON" : "OFF"));
	}

	private static Component asyncResourceStreamingLabel() {
		return Component.literal("Async Resource Streaming: " + (OptiminiumSettings.isAsyncResourceStreaming() ? "ON" : "OFF"));
	}

	private static Component shaderResourceCacheLabel() {
		return Component.literal("Shader Resource Cache: " + (OptiminiumSettings.isShaderResourceCache() ? "ON" : "OFF"));
	}

	private static Component experimentalRendererLabel() {
		return Component.literal("Experimental Renderer: " + (OptiminiumSettings.isExperimentalRendererFeatures() ? "ON" : "OFF"));
	}

	private static Component uploadStallLimiterLabel() {
		return Component.literal("Upload Stall Limiter: " + (OptiminiumSettings.isExperimentalUploadStallLimiter() ? "ON" : "OFF"));
	}

	private static Component temporalSignificanceLabel() {
		return Component.literal("Temporal Significance: " + (OptiminiumSettings.isExperimentalTemporalSignificance() ? "ON" : "OFF"));
	}

	private static Component particleLimiterLabel() {
		return Component.literal("Particle Limiter: " + (OptiminiumSettings.isParticleLimiter() ? "ON" : "OFF"));
	}

	private static Component blockEntityCullingLabel() {
		return Component.literal("Block Entity Culling: " + (OptiminiumSettings.isBlockEntityCulling() ? "ON" : "OFF"));
	}

	private static Component blockEntityLodCubesLabel() {
		return Component.literal("Block Entity LOD Cubes: " + (OptiminiumSettings.isBlockEntityLodCubesEnabled() ? "ON" : "OFF"));
	}

	private static Component blockEntityLodDebugLabel() {
		return Component.literal("Block Entity LOD Debug: " + (OptiminiumSettings.isBlockEntityLodDebugEnabled() ? "ON" : "OFF"));
	}

	private static Component denseSceneAdaptiveLabel() {
		return denseSceneAdaptiveLabel(OptiminiumSettings.getDenseSceneAdaptiveMode());
	}

	private static Component denseSceneAdaptiveLabel(OptiminiumSettings.DenseSceneAdaptiveMode mode) {
		return Component.literal("Dense Scene Mode: " + mode.name().toLowerCase());
	}

	private static Component adaptiveSimulationLabel() {
		return Component.literal("Dynamic Simulation Distance: " + (OptiminiumSettings.isAdaptiveSimulationDistance() ? "ON" : "OFF"));
	}

	private static Component smartTickLabel() {
		return Component.literal("SmartTick Scheduler: " + (OptiminiumSettings.isSmartTickScheduler() ? "ON" : "OFF"));
	}

	private static Component aiPathfindingLabel() {
		return Component.literal("AI Pathfinding Optimizer: " + (OptiminiumSettings.isAiPathfindingOptimizer() ? "ON" : "OFF"));
	}

	private static final class SettingsSlider extends AbstractSliderButton {
		private final IntSupplier getter;
		private final IntConsumer setter;
		private final int min;
		private final int max;
		private final String label;

		private SettingsSlider(int x, int y, IntSupplier getter, IntConsumer setter, int min, int max, String label) {
			super(x, y, BUTTON_WIDTH, BUTTON_HEIGHT, Component.empty(), normalize(getter.getAsInt(), min, max));
			this.getter = getter;
			this.setter = setter;
			this.min = min;
			this.max = max;
			this.label = label;
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.literal(label + ": " + valueFromSlider()));
		}

		@Override
		protected void applyValue() {
			int value = valueFromSlider();
			if (getter.getAsInt() != value) {
				setter.accept(value);
			}
		}

		private int valueFromSlider() {
			return min + (int)Math.round(value * (max - min));
		}

		private static double normalize(int value, int min, int max) {
			return Math.max(0.0D, Math.min(1.0D, (value - min) / (double)(max - min)));
		}
	}
}
