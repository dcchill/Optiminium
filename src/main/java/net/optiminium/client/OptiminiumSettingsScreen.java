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

	public OptiminiumSettingsScreen(Screen lastScreen) {
		super(TITLE);
		this.lastScreen = lastScreen;
	}

	@Override
	protected void init() {
		boolean twoColumns = this.width >= BUTTON_WIDTH * 2 + 24;
		int leftX = twoColumns ? this.width / 2 - BUTTON_WIDTH - 6 : (this.width - BUTTON_WIDTH) / 2;
		int rightX = twoColumns ? this.width / 2 + 6 : leftX;
		int rightY = twoColumns ? 42 : 276;
		int x = leftX;
		int y = 42;
		this.addRenderableWidget(Button.builder(enabledLabel(), button -> button.setMessage(Component.literal("Optiminium: " + (OptiminiumSettings.toggleEnabled() ? "ON" : "OFF"))))
			.bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
			.build());
		this.addRenderableWidget(Button.builder(framePacingLabel(), button -> button.setMessage(Component.literal("Frame Pacing: " + (OptiminiumSettings.toggleFramePacing() ? "ON" : "OFF"))))
			.bounds(x, y + 26, BUTTON_WIDTH, BUTTON_HEIGHT)
			.build());
		this.addRenderableWidget(new SettingsSlider(x, y + 52, OptiminiumSettings::getGpuTargetFps, OptiminiumSettings::setGpuTargetFps, 30, 240, "Target FPS"));
		this.addRenderableWidget(new SettingsSlider(x, y + 78, OptiminiumSettings::getGpuMinRenderScalePercent, OptiminiumSettings::setGpuMinRenderScalePercent, 35, 100, "Min Scale %"));
		this.addRenderableWidget(new SettingsSlider(x, y + 104, OptiminiumSettings::getEntityAlwaysRenderDistanceBlocks, OptiminiumSettings::setEntityAlwaysRenderDistanceBlocks, 10, 200, "Entity Safe Range"));
		this.addRenderableWidget(Button.builder(beRenderCacheLabel(), button -> { OptiminiumSettings.toggleBlockEntityRenderCache(); button.setMessage(beRenderCacheLabel()); })
			.bounds(x, y + 130, BUTTON_WIDTH, BUTTON_HEIGHT)
			.build());
		this.addRenderableWidget(Button.builder(beVirtualizationLabel(), button -> { OptiminiumSettings.toggleBlockEntityRenderVirtualization(); button.setMessage(beVirtualizationLabel()); })
			.bounds(x, y + 156, BUTTON_WIDTH, BUTTON_HEIGHT)
			.build());
		this.addRenderableWidget(Button.builder(beVirtualizationDebugProxyLabel(), button -> { OptiminiumSettings.toggleBlockEntityVirtualizationDebugProxies(); button.setMessage(beVirtualizationDebugProxyLabel()); })
			.bounds(x, y + 182, BUTTON_WIDTH, BUTTON_HEIGHT)
			.build());
		this.addRenderableWidget(Button.builder(beVirtualizationAggressivenessLabel(), button -> { OptiminiumSettings.cycleBlockEntityVirtualizationAggressiveness(); button.setMessage(beVirtualizationAggressivenessLabel()); })
			.bounds(x, y + 208, BUTTON_WIDTH, BUTTON_HEIGHT)
			.build());
		x = rightX;
		y = rightY;
		this.addRenderableWidget(Button.builder(beRenderCacheDebugLabel(), button -> { OptiminiumSettings.toggleBlockEntityRenderCacheDebug(); button.setMessage(beRenderCacheDebugLabel()); })
			.bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
			.build());
		this.addRenderableWidget(Button.builder(bePersistenceLabel(), button -> { OptiminiumSettings.toggleBlockEntityPersistenceEnabled(); button.setMessage(bePersistenceLabel()); })
			.bounds(x, y + 26, BUTTON_WIDTH, BUTTON_HEIGHT)
			.build());
		this.addRenderableWidget(new SettingsSlider(x, y + 52, OptiminiumSettings::getBlockEntityPersistenceMinInstances, OptiminiumSettings::setBlockEntityPersistenceMinInstances, 16, 1024, "BE Persistence Threshold"));
		this.addRenderableWidget(Button.builder(bePersistenceAdaptiveLabel(), button -> { OptiminiumSettings.toggleBlockEntityPersistenceAdaptive(); button.setMessage(bePersistenceAdaptiveLabel()); })
			.bounds(x, y + 78, BUTTON_WIDTH, BUTTON_HEIGHT)
			.build());
		this.addRenderableWidget(new SettingsSlider(x, y + 104, OptiminiumSettings::getBlockEntityPersistenceAdaptiveMinInstances, OptiminiumSettings::setBlockEntityPersistenceAdaptiveMinInstances, 4, 128, "BE Adaptive Minimum"));
		this.addRenderableWidget(new SettingsSlider(x, y + 130, OptiminiumSettings::getBlockEntityPersistenceMaxMeshes, OptiminiumSettings::setBlockEntityPersistenceMaxMeshes, 16, 4096, "BE Persistent Meshes"));
		this.addRenderableWidget(Button.builder(armorStandPersistenceLabel(), button -> { OptiminiumSettings.toggleArmorStandPersistenceEnabled(); button.setMessage(armorStandPersistenceLabel()); })
			.bounds(x, y + 156, BUTTON_WIDTH, BUTTON_HEIGHT)
			.build());
		this.addRenderableWidget(Button.builder(mobPersistenceLabel(), button -> { OptiminiumSettings.toggleMobPersistenceEnabled(); button.setMessage(mobPersistenceLabel()); })
			.bounds(x, y + 182, BUTTON_WIDTH, BUTTON_HEIGHT)
			.build());
		this.addRenderableWidget(new SettingsSlider(x, y + 208, OptiminiumSettings::getMobPersistenceAdaptiveMinInstances,
			OptiminiumSettings::setMobPersistenceAdaptiveMinInstances, 2, 128, "Mob Adaptive Minimum"));
		this.addRenderableWidget(Button.builder(Component.literal("Benchmark Persistence"), button -> OptiminiumBenchmark.startPersistence())
			.bounds(x, y + 234, BUTTON_WIDTH, BUTTON_HEIGHT)
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

	@Override
	public void onClose() {
		this.minecraft.setScreen(this.lastScreen);
	}

	private static Component framePacingLabel() {
		return Component.literal("Frame Pacing: " + (OptiminiumSettings.isGpuOptimizer() ? "ON" : "OFF"));
	}

	private static Component enabledLabel() {
		return Component.literal("Optiminium: " + (OptiminiumSettings.isEnabled() ? "ON" : "OFF"));
	}

	private static Component beRenderCacheLabel() {
		boolean enabled = OptiminiumSettings.isBlockEntityRenderCache();
		return Component.literal("BE Render Cache: " + (enabled ? "ON" : "OFF"));
	}

	private static Component bePersistenceLabel() {
		boolean enabled = OptiminiumSettings.isBlockEntityPersistenceEnabled();
		return Component.literal("BE Persistence: " + (enabled ? "ON" : "OFF"));
	}

	private static Component bePersistenceAdaptiveLabel() {
		return Component.literal("BE Adaptive Persistence: "
			+ (OptiminiumSettings.isBlockEntityPersistenceAdaptive() ? "ON" : "OFF"));
	}

	private static Component armorStandPersistenceLabel() {
		return Component.literal("Armor Stand Persistence: "
			+ (OptiminiumSettings.isArmorStandPersistenceEnabled() ? "ON" : "OFF"));
	}

	private static Component mobPersistenceLabel() {
		return Component.literal("Exact Mob Persistence: "
			+ (OptiminiumSettings.isMobPersistenceEnabled() ? "ON" : "OFF"));
	}

	private static Component beVirtualizationLabel() {
		boolean enabled = OptiminiumSettings.isBlockEntityVirtualizationEnabled();
		return Component.literal("BE Virtualization: " + (enabled ? "ON" : "OFF"));
	}

	private static Component beVirtualizationDebugProxyLabel() {
		boolean enabled = OptiminiumSettings.isBlockEntityVirtualizationDebugProxies();
		return Component.literal("BE Debug Proxies: " + (enabled ? "ON" : "OFF"));
	}

	private static Component beVirtualizationAggressivenessLabel() {
		return Component.literal("BE Virtual Mode: "
			+ OptiminiumSettings.getBlockEntityVirtualizationAggressiveness().name().toLowerCase());
	}

	private static Component beRenderCacheDebugLabel() {
		boolean enabled = OptiminiumSettings.isBlockEntityRenderCacheDebug();
		return Component.literal("BE Cache Debug (F9): " + (enabled ? "ON" : "OFF"));
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
