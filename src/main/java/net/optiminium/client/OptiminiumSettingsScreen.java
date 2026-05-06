package net.optiminium.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.optiminium.optimization.OptiminiumSettings;

import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;

public final class OptiminiumSettingsScreen extends Screen {
	private static final Component TITLE = Component.literal("Optiminium Settings");
	private static final int CONTROL_WIDTH = 220;
	private static final int CONTROL_HEIGHT = 20;
	private static final int ROW_GAP = 8;

	private final Screen lastScreen;
	private Button toggleButton;

	public OptiminiumSettingsScreen(Screen lastScreen) {
		super(TITLE);
		this.lastScreen = lastScreen;
	}

	@Override
	protected void init() {
		int x = (this.width - CONTROL_WIDTH) / 2;
		int y = Math.max(40, this.height / 6);

		this.toggleButton = Button.builder(toggleLabel(), pressed -> {
			OptiminiumSettings.toggleEnabled();
			pressed.setMessage(toggleLabel());
		})
			.bounds(x, y, CONTROL_WIDTH, CONTROL_HEIGHT)
			.tooltip(Tooltip.create(Component.literal("Toggle Optiminium optimizations for this instance.")))
			.build();
		this.addRenderableWidget(this.toggleButton);

		SettingsSlider fogSlider = new SettingsSlider(
			x,
			y + (CONTROL_HEIGHT + ROW_GAP),
			CONTROL_WIDTH,
			CONTROL_HEIGHT,
			OptiminiumSettings::getFogDistanceBlocks,
			OptiminiumSettings::setFogDistanceBlocks,
			OptiminiumSettings.getMinFogDistanceBlocks(),
			OptiminiumSettings.getMaxFogDistanceBlocks(),
			value -> Component.literal("Fog: " + value + " blocks")
		);
		fogSlider.setTooltip(Tooltip.create(Component.literal("Set Optiminium's client fog far plane.")));
		this.addRenderableWidget(fogSlider);

		this.addRenderableWidget(Button.builder(cameraChunkLoadingLabel(), pressed -> {
			OptiminiumSettings.toggleCameraChunkLoading();
			pressed.setMessage(cameraChunkLoadingLabel());
		})
			.bounds(x, y + (CONTROL_HEIGHT + ROW_GAP) * 2, CONTROL_WIDTH, CONTROL_HEIGHT)
			.tooltip(Tooltip.create(Component.literal("Only track chunks inside the player's camera-facing view cone.")))
			.build());

		SettingsSlider cameraRadiusSlider = new SettingsSlider(
			x,
			y + (CONTROL_HEIGHT + ROW_GAP) * 3,
			CONTROL_WIDTH,
			CONTROL_HEIGHT,
			OptiminiumSettings::getCameraAlwaysTrackRadiusChunks,
			OptiminiumSettings::setCameraAlwaysTrackRadiusChunks,
			OptiminiumSettings.getMinCameraAlwaysTrackRadiusChunks(),
			OptiminiumSettings.getMaxCameraAlwaysTrackRadiusChunks(),
			value -> Component.literal("Camera Keep Radius: " + value + " chunks")
		);
		cameraRadiusSlider.setTooltip(Tooltip.create(Component.literal("Keep this many chunks around the player loaded even outside the camera cone.")));
		this.addRenderableWidget(cameraRadiusSlider);

		SettingsSlider cameraYawStepSlider = new SettingsSlider(
			x,
			y + (CONTROL_HEIGHT + ROW_GAP) * 4,
			CONTROL_WIDTH,
			CONTROL_HEIGHT,
			OptiminiumSettings::getCameraYawStepDegrees,
			OptiminiumSettings::setCameraYawStepDegrees,
			OptiminiumSettings.getMinCameraYawStepDegrees(),
			OptiminiumSettings.getMaxCameraYawStepDegrees(),
			value -> Component.literal("Camera Yaw Step: " + value + " deg")
		);
		cameraYawStepSlider.setTooltip(Tooltip.create(Component.literal("Smaller values update the chunk cone more often as the player turns.")));
		this.addRenderableWidget(cameraYawStepSlider);

		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, pressed -> this.onClose())
			.bounds((this.width - 200) / 2, this.height - 32, 200, CONTROL_HEIGHT)
			.build());
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		graphics.drawCenteredString(this.font, TITLE, this.width / 2, 16, 0xFFFFFF);
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(this.lastScreen);
	}

	private static Component toggleLabel() {
		return Component.literal("Optiminium: " + (OptiminiumSettings.isEnabled() ? "ON" : "OFF"));
	}

	private static Component cameraChunkLoadingLabel() {
		return Component.literal("Camera Chunks: " + (OptiminiumSettings.isCameraChunkLoading() ? "ON" : "OFF"));
	}

	private static final class SettingsSlider extends AbstractSliderButton {
		private final IntSupplier getter;
		private final IntConsumer setter;
		private final IntFunction<Component> labelFactory;
		private final int min;
		private final int max;

		private SettingsSlider(int x, int y, int width, int height, IntSupplier getter, IntConsumer setter, int min, int max, IntFunction<Component> labelFactory) {
			super(x, y, width, height, Component.empty(), normalize(getter.getAsInt(), min, max));
			this.getter = getter;
			this.setter = setter;
			this.min = min;
			this.max = max;
			this.labelFactory = labelFactory;
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			setMessage(labelFactory.apply(valueFromSlider()));
		}

		@Override
		protected void applyValue() {
			int value = valueFromSlider();
			if (getter.getAsInt() != value) {
				setter.accept(value);
			}
			updateMessage();
		}

		private int valueFromSlider() {
			return min + (int)Math.round(value * (max - min));
		}

		private static double normalize(int value, int min, int max) {
			if (max <= min) {
				return 0.0D;
			}
			return Math.max(0.0D, Math.min(1.0D, (value - min) / (double)(max - min)));
		}
	}
}
