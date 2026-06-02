package net.optiminium.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.optiminium.optimization.OptiminiumSettings;

import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;

public final class OptiminiumSettingsScreen extends Screen {
	private static final Component TITLE = Component.literal("Optiminium Settings");
	private static final int CONTROL_WIDTH = 220;
	private static final int CONTROL_HEIGHT = 20;
	private static final int COLUMN_GAP = 12;
	private static final int ROW_GAP = 6;
	private static final int PAGE_BUTTON_WIDTH = 64;
	private static final int PAGE_BUTTON_GAP = 6;

	private final Screen lastScreen;
	private final Page selectedPage;
	private int controlsX;
	private int controlsY;
	private int controlsColumns;

	public OptiminiumSettingsScreen(Screen lastScreen) {
		this(lastScreen, Page.GENERAL);
	}

	private OptiminiumSettingsScreen(Screen lastScreen, Page selectedPage) {
		super(TITLE);
		this.lastScreen = lastScreen;
		this.selectedPage = selectedPage;
	}

	@Override
	protected void init() {
		addPageButtons();
		configureControlLayout(selectedPage.controlCount);

		switch (selectedPage) {
			case GENERAL -> addGeneralControls();
			case RENDER -> addRenderControls();
			case EFFECTS -> addEffectsControls();
			case SERVER -> addServerControls();
			case WORLD -> addWorldControls();
		}

		this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, pressed -> this.onClose())
			.bounds((this.width - 200) / 2, this.height - 30, 200, CONTROL_HEIGHT)
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

	private void addPageButtons() {
		Page[] pages = Page.values();
		int buttonWidth = Math.min(PAGE_BUTTON_WIDTH, Math.max(52, (this.width - 20 - (pages.length - 1) * PAGE_BUTTON_GAP) / pages.length));
		int totalWidth = pages.length * buttonWidth + (pages.length - 1) * PAGE_BUTTON_GAP;
		int x = (this.width - totalWidth) / 2;
		int y = 34;
		for (Page page : pages) {
			Button button = Button.builder(Component.literal(page.label), pressed -> switchPage(page))
				.bounds(x, y, buttonWidth, CONTROL_HEIGHT)
				.build();
			button.active = page != selectedPage;
			this.addRenderableWidget(button);
			x += buttonWidth + PAGE_BUTTON_GAP;
		}
	}

	private void switchPage(Page page) {
		if (page != selectedPage && this.minecraft != null) {
			this.minecraft.setScreen(new OptiminiumSettingsScreen(this.lastScreen, page));
		}
	}

	private void configureControlLayout(int controlCount) {
		boolean twoColumns = this.width >= CONTROL_WIDTH * 2 + COLUMN_GAP + 40 && controlCount > 6;
		this.controlsColumns = twoColumns ? 2 : 1;
		int totalWidth = this.controlsColumns * CONTROL_WIDTH + (this.controlsColumns - 1) * COLUMN_GAP;
		this.controlsX = (this.width - totalWidth) / 2;
		int rows = (controlCount + this.controlsColumns - 1) / this.controlsColumns;
		int totalHeight = rows * CONTROL_HEIGHT + Math.max(0, rows - 1) * ROW_GAP;
		this.controlsY = Math.max(58, Math.min(64, this.height - 42 - totalHeight));
	}

	private void addGeneralControls() {
		int index = 0;
		index = addToggle(index, "Optiminium", OptiminiumSettings::isEnabled, () -> OptiminiumSettings.toggleEnabled(), "Toggle every Optiminium optimization.");
		index = addSlider(index, OptiminiumSettings::getFogDistanceBlocks, OptiminiumSettings::setFogDistanceBlocks, OptiminiumSettings.getMinFogDistanceBlocks(),
			OptiminiumSettings.getMaxFogDistanceBlocks(), value -> Component.literal("Fog: " + value + " blocks"), "Set Optiminium's client fog far plane.");
		index = addToggle(index, "Rebuild Scheduler", OptiminiumSettings::isChunkRebuildScheduling, () -> OptiminiumSettings.toggleChunkRebuildScheduling(),
			"Prioritize visible chunk mesh rebuilds near the player and crosshair.");
		index = addSlider(index, OptiminiumSettings::getChunkRebuildsPerFrame, OptiminiumSettings::setChunkRebuildsPerFrame, OptiminiumSettings.getMinChunkRebuildsPerFrame(),
			OptiminiumSettings.getMaxChunkRebuildsPerFrame(), value -> Component.literal("Rebuilds/Frame: " + value), "Limit async chunk mesh rebuilds scheduled each frame.");
		addToggle(index, "Light Dedup", OptiminiumSettings::isLightingDeduplication, () -> OptiminiumSettings.toggleLightingDeduplication(),
			"Merge duplicate light checks for the same block until the light engine drains.");
	}

	private void addRenderControls() {
		int index = 0;
		index = addToggle(index, "Render Culling", OptiminiumSettings::isClientRenderCulling, () -> OptiminiumSettings.toggleClientRenderCulling(),
			"Skip distant low-value entity renders and name tags.");
		index = addSlider(index, OptiminiumSettings::getEntityRenderDistanceScalePercent, OptiminiumSettings::setEntityRenderDistanceScalePercent,
			OptiminiumSettings.getMinEntityRenderDistanceScalePercent(), OptiminiumSettings.getMaxEntityRenderDistanceScalePercent(),
			value -> Component.literal("Entity Dist: " + value + "%"), "Scale distance cutoffs for item, XP, projectile, and hanging entity renders.");
		index = addToggle(index, "Block Entities", OptiminiumSettings::isBlockEntityCulling, () -> OptiminiumSettings.toggleBlockEntityCulling(),
			"Skip distant block entity renderers such as signs, chests, and spawners.");
		index = addSlider(index, OptiminiumSettings::getBlockEntityDistanceScalePercent, OptiminiumSettings::setBlockEntityDistanceScalePercent,
			OptiminiumSettings.getMinBlockEntityDistanceScalePercent(), OptiminiumSettings.getMaxBlockEntityDistanceScalePercent(),
			value -> Component.literal("Block Entity Dist: " + value + "%"), "Scale distance cutoffs for block entity renderers.");
		index = addToggle(index, "Crowd Culling", OptiminiumSettings::isCrowdCulling, () -> OptiminiumSettings.toggleCrowdCulling(),
			"Limit how many idle mobs render inside crowded distant cells.");
		addSlider(index, OptiminiumSettings::getCrowdRenderBudgetPercent, OptiminiumSettings::setCrowdRenderBudgetPercent,
			OptiminiumSettings.getMinCrowdRenderBudgetPercent(), OptiminiumSettings.getMaxCrowdRenderBudgetPercent(),
			value -> Component.literal("Crowd Budget: " + value + "%"), "Scale the render budget for dense idle mob crowds.");
	}

	private void addEffectsControls() {
		int index = 0;
		index = addToggle(index, "Particle Limiter", OptiminiumSettings::isParticleLimiter, () -> OptiminiumSettings.toggleParticleLimiter(),
			"Drop distant and excessive low-priority particles.");
		index = addSlider(index, OptiminiumSettings::getParticleRenderDistanceBlocks, OptiminiumSettings::setParticleRenderDistanceBlocks,
			OptiminiumSettings.getMinParticleRenderDistanceBlocks(), OptiminiumSettings.getMaxParticleRenderDistanceBlocks(),
			value -> Component.literal("Particle Dist: " + value), "Distance cutoff for non-critical particles.");
		index = addSlider(index, OptiminiumSettings::getMaxParticlesPerFrame, OptiminiumSettings::setMaxParticlesPerFrame,
			OptiminiumSettings.getMinMaxParticlesPerFrame(), OptiminiumSettings.getMaxMaxParticlesPerFrame(),
			value -> Component.literal("Particle Budget: " + value), "Maximum non-critical particles Optiminium allows each frame.");
		index = addToggle(index, "Sound Limiter", OptiminiumSettings::isAmbientSoundLimiter, () -> OptiminiumSettings.toggleAmbientSoundLimiter(),
			"Limit repeated ambient sounds from dense groups of entities.");
		addSlider(index, OptiminiumSettings::getAmbientSoundBudget, OptiminiumSettings::setAmbientSoundBudget, OptiminiumSettings.getMinAmbientSoundBudget(),
			OptiminiumSettings.getMaxAmbientSoundBudget(), value -> Component.literal("Sound Budget: " + value), "Ambient entity sounds allowed per quarter second.");
	}

	private void addServerControls() {
		int index = 0;
		index = addToggle(index, "Tick Throttle", OptiminiumSettings::isServerEntityTickThrottling, () -> OptiminiumSettings.toggleServerEntityTickThrottling(),
			"Tick idle far mobs less often while keeping active mobs awake.");
		index = addSlider(index, OptiminiumSettings::getFarEntityTickInterval, OptiminiumSettings::setFarEntityTickInterval, OptiminiumSettings.getMinFarEntityTickInterval(),
			OptiminiumSettings.getMaxFarEntityTickInterval(), value -> Component.literal("Far Tick Gap: " + value), "Ticks between updates for quiet mobs far from every player.");
		index = addToggle(index, "Adaptive Sim", OptiminiumSettings::isAdaptiveSimulationDistance, () -> OptiminiumSettings.toggleAdaptiveSimulationDistance(),
			"Lower simulation distance under sustained server tick pressure.");
		index = addSlider(index, OptiminiumSettings::getAdaptiveSimulationTargetMspt, OptiminiumSettings::setAdaptiveSimulationTargetMspt,
			OptiminiumSettings.getMinAdaptiveSimulationTargetMspt(), OptiminiumSettings.getMaxAdaptiveSimulationTargetMspt(),
			value -> Component.literal("Target MSPT: " + value), "Tick-time target used by adaptive simulation distance.");
		addSlider(index, OptiminiumSettings::getAdaptiveSimulationMinDistanceChunks, OptiminiumSettings::setAdaptiveSimulationMinDistanceChunks,
			OptiminiumSettings.getMinAdaptiveSimulationMinDistanceChunks(), OptiminiumSettings.getMaxAdaptiveSimulationMinDistanceChunks(),
			value -> Component.literal("Min Sim Dist: " + value), "Lowest simulation distance adaptive mode may use.");
	}

	private void addWorldControls() {
		int index = 0;
		index = addToggle(index, "Item Clouds", OptiminiumSettings::isItemVirtualization, () -> OptiminiumSettings.toggleItemVirtualization(),
			"Compress large unattended item piles into lightweight virtual stacks.");
		index = addSlider(index, OptiminiumSettings::getItemClusterThreshold, OptiminiumSettings::setItemClusterThreshold, OptiminiumSettings.getMinItemClusterThreshold(),
			OptiminiumSettings.getMaxItemClusterThreshold(), value -> Component.literal("Item Cluster: " + value), "Minimum unattended item count before cloud compression starts.");
		index = addToggle(index, "XP Merge", OptiminiumSettings::isXpOrbMerging, () -> OptiminiumSettings.toggleXpOrbMerging(),
			"Merge dense unattended XP orb groups.");
		index = addSlider(index, OptiminiumSettings::getXpMergeThreshold, OptiminiumSettings::setXpMergeThreshold, OptiminiumSettings.getMinXpMergeThreshold(),
			OptiminiumSettings.getMaxXpMergeThreshold(), value -> Component.literal("XP Cluster: " + value), "Minimum XP orb group size before merging starts.");
		addToggle(index, "Redstone Dedup", OptiminiumSettings::isRedstoneDeduplication, () -> OptiminiumSettings.toggleRedstoneDeduplication(),
			"Suppress duplicate redstone neighbor notifications in the same short tick window.");
	}

	private int addToggle(int index, String label, BooleanSupplier getter, Runnable toggler, String tooltip) {
		Button button = Button.builder(toggleLabel(label, getter.getAsBoolean()), pressed -> {
			toggler.run();
			pressed.setMessage(toggleLabel(label, getter.getAsBoolean()));
		})
			.bounds(controlX(index), controlY(index), CONTROL_WIDTH, CONTROL_HEIGHT)
			.tooltip(Tooltip.create(Component.literal(tooltip)))
			.build();
		this.addRenderableWidget(button);
		return index + 1;
	}

	private int addSlider(int index, IntSupplier getter, IntConsumer setter, int min, int max, IntFunction<Component> labelFactory, String tooltip) {
		SettingsSlider slider = new SettingsSlider(controlX(index), controlY(index), CONTROL_WIDTH, CONTROL_HEIGHT, getter, setter, min, max, labelFactory);
		slider.setTooltip(Tooltip.create(Component.literal(tooltip)));
		this.addRenderableWidget(slider);
		return index + 1;
	}

	private int addButton(int index, java.util.function.Supplier<Component> label, Runnable action, String tooltip) {
		Button button = Button.builder(label.get(), pressed -> {
			action.run();
			pressed.setMessage(label.get());
		})
			.bounds(controlX(index), controlY(index), CONTROL_WIDTH, CONTROL_HEIGHT)
			.tooltip(Tooltip.create(Component.literal(tooltip)))
			.build();
		this.addRenderableWidget(button);
		return index + 1;
	}

	private int addDisabled(int index, String label, String tooltip) {
		Button button = Button.builder(Component.literal(label), pressed -> {
		})
			.bounds(controlX(index), controlY(index), CONTROL_WIDTH, CONTROL_HEIGHT)
			.tooltip(Tooltip.create(Component.literal(tooltip)))
			.build();
		button.active = false;
		this.addRenderableWidget(button);
		return index + 1;
	}

	private int controlX(int index) {
		return controlsX + (index % controlsColumns) * (CONTROL_WIDTH + COLUMN_GAP);
	}

	private int controlY(int index) {
		return controlsY + (index / controlsColumns) * (CONTROL_HEIGHT + ROW_GAP);
	}

	private static Component toggleLabel(String name, boolean enabled) {
		return Component.literal(name + ": " + (enabled ? "ON" : "OFF"));
	}

	private enum Page {
		GENERAL("General", 5),
		RENDER("Render", 6),
		EFFECTS("Effects", 5),
		SERVER("Server", 5),
		WORLD("World", 5);

		private final String label;
		private final int controlCount;

		Page(String label, int controlCount) {
			this.label = label;
			this.controlCount = controlCount;
		}
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
