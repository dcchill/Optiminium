package net.optiminium.client;

import net.optiminium.compat.OptiminiumSodiumCompat;
import net.optiminium.optimization.OptiminiumSettings;

import java.util.concurrent.atomic.LongAdder;

public final class OptiminiumGlStateTracker {
	private static final int MAX_TEXTURE_UNITS = 32;
	private static final int UNKNOWN = -1;
	private static final int GL_TEXTURE0 = 33984;

	private static final int[] textureTargets = new int[MAX_TEXTURE_UNITS];
	private static final int[] textureIds = new int[MAX_TEXTURE_UNITS];
	private static final int[] relaxedTextureTargets = new int[MAX_TEXTURE_UNITS];
	private static final int[] relaxedTextureIds = new int[MAX_TEXTURE_UNITS];
	private static int activeTextureUnit;
	private static boolean activeTextureKnown;
	private static int activeProgram;
	private static int relaxedActiveProgram;
	private static boolean shaderKnown;
	private static boolean relaxedShaderKnown;
	private static boolean textureStateInvalidated;
	private static boolean shaderStateInvalidated;
	private static boolean sodiumConservative;
	private static boolean glAutoDisabled;
	private static String glAutoDisableReason = "none";
	private static int observedPreviousTextureId = UNKNOWN;
	private static int requestedTextureId = UNKNOWN;
	private static int observedPreviousShaderId = UNKNOWN;
	private static int requestedShaderId = UNKNOWN;

	private static final LongAdder textureBindRequests = new LongAdder();
	private static final LongAdder textureBindPotentialSkipped = new LongAdder();
	private static final LongAdder textureRelaxedPotentialSkipped = new LongAdder();
	private static final LongAdder textureBindSkipped = new LongAdder();
	private static final LongAdder textureBindActual = new LongAdder();
	private static final LongAdder shaderBindRequests = new LongAdder();
	private static final LongAdder shaderBindPotentialSkipped = new LongAdder();
	private static final LongAdder shaderRelaxedPotentialSkipped = new LongAdder();
	private static final LongAdder shaderBindSkipped = new LongAdder();
	private static final LongAdder shaderBindActual = new LongAdder();
	private static final LongAdder trackerInvalidations = new LongAdder();
	private static final LongAdder activeTextureUnitMismatches = new LongAdder();
	private static final LongAdder textureTargetMismatches = new LongAdder();
	private static final LongAdder textureIdMismatches = new LongAdder();
	private static final LongAdder shaderIdMismatches = new LongAdder();
	private static final LongAdder framebufferOnlyInvalidations = new LongAdder();
	private static final LongAdder textureStateInvalidations = new LongAdder();
	private static final LongAdder shaderStateInvalidations = new LongAdder();
	private static final LongAdder fullStateInvalidations = new LongAdder();
	private static final LongAdder noSkipDifferentTextureId = new LongAdder();
	private static final LongAdder noSkipDifferentShaderId = new LongAdder();
	private static final LongAdder noSkipDifferentTarget = new LongAdder();
	private static final LongAdder noSkipDifferentActiveUnit = new LongAdder();
	private static final LongAdder noSkipStateInvalidated = new LongAdder();
	private static final LongAdder noSkipModeDisabled = new LongAdder();
	private static final LongAdder noSkipCompatibilityMode = new LongAdder();
	private static final LongAdder glErrorsDetected = new LongAdder();
	private static final LongAdder[] invalidationReasons = new LongAdder[InvalidationReason.values().length];

	static {
		for (int i = 0; i < invalidationReasons.length; i++) {
			invalidationReasons[i] = new LongAdder();
		}
		reset();
	}

	private OptiminiumGlStateTracker() {
	}

	public static void init() {
		sodiumConservative = OptiminiumSodiumCompat.isNonVanillaRenderer();
		reset();
	}

	public static void reset() {
		activeTextureUnit = 0;
		activeTextureKnown = true;
		activeProgram = UNKNOWN;
		relaxedActiveProgram = UNKNOWN;
		shaderKnown = false;
		relaxedShaderKnown = false;
		textureStateInvalidated = false;
		shaderStateInvalidated = false;
		glAutoDisabled = false;
		glAutoDisableReason = "none";
		observedPreviousTextureId = UNKNOWN;
		requestedTextureId = UNKNOWN;
		observedPreviousShaderId = UNKNOWN;
		requestedShaderId = UNKNOWN;
		clearTextures(textureTargets, textureIds);
		clearTextures(relaxedTextureTargets, relaxedTextureIds);
		textureBindRequests.reset();
		textureBindPotentialSkipped.reset();
		textureRelaxedPotentialSkipped.reset();
		textureBindSkipped.reset();
		textureBindActual.reset();
		shaderBindRequests.reset();
		shaderBindPotentialSkipped.reset();
		shaderRelaxedPotentialSkipped.reset();
		shaderBindSkipped.reset();
		shaderBindActual.reset();
		trackerInvalidations.reset();
		activeTextureUnitMismatches.reset();
		textureTargetMismatches.reset();
		textureIdMismatches.reset();
		shaderIdMismatches.reset();
		framebufferOnlyInvalidations.reset();
		textureStateInvalidations.reset();
		shaderStateInvalidations.reset();
		fullStateInvalidations.reset();
		noSkipDifferentTextureId.reset();
		noSkipDifferentShaderId.reset();
		noSkipDifferentTarget.reset();
		noSkipDifferentActiveUnit.reset();
		noSkipStateInvalidated.reset();
		noSkipModeDisabled.reset();
		noSkipCompatibilityMode.reset();
		glErrorsDetected.reset();
		for (LongAdder reason : invalidationReasons) {
			reason.reset();
		}
	}

	public static void onActiveTexture(int glTexture) {
		OptiminiumSettings.OpenGlOptimizationMode mode = effectiveMode();
		if (mode == OptiminiumSettings.OpenGlOptimizationMode.OFF || !tracksState(mode)) {
			return;
		}
		int unit = glTexture - GL_TEXTURE0;
		if (unit >= 0 && unit < MAX_TEXTURE_UNITS) {
			activeTextureUnit = unit;
			activeTextureKnown = true;
		} else {
			activeTextureKnown = false;
		}
	}

	public static boolean tryBindTexture(int target, int textureId) {
		OptiminiumSettings.OpenGlOptimizationMode mode = effectiveMode();
		if (mode == OptiminiumSettings.OpenGlOptimizationMode.OFF) {
			return true;
		}

		textureBindRequests.increment();
		if (mode == OptiminiumSettings.OpenGlOptimizationMode.DIAGNOSTIC_ONLY
				|| mode == OptiminiumSettings.OpenGlOptimizationMode.MEASURE_ONLY) {
			return true;
		}
		textureBindActual.increment();

		boolean knownUnit = activeTextureKnown && activeTextureUnit >= 0 && activeTextureUnit < MAX_TEXTURE_UNITS;
		int previousTarget = knownUnit ? textureTargets[activeTextureUnit] : UNKNOWN;
		int previousTexture = knownUnit ? textureIds[activeTextureUnit] : UNKNOWN;
		int relaxedPreviousTarget = knownUnit ? relaxedTextureTargets[activeTextureUnit] : UNKNOWN;
		int relaxedPreviousTexture = knownUnit ? relaxedTextureIds[activeTextureUnit] : UNKNOWN;
		observedPreviousTextureId = previousTexture;
		requestedTextureId = textureId;

		boolean conservativeSame = knownUnit && !textureStateInvalidated && previousTarget == target && previousTexture == textureId;
		boolean relaxedSame = knownUnit && relaxedPreviousTarget == target && relaxedPreviousTexture == textureId;
		if (conservativeSame) {
			textureBindPotentialSkipped.increment();
		}
		if (relaxedSame) {
			textureRelaxedPotentialSkipped.increment();
		}
		if (!conservativeSame) {
			recordTextureNoSkip(knownUnit, previousTarget, previousTexture, target);
		}

		boolean skip = conservativeSame
			&& mode == OptiminiumSettings.OpenGlOptimizationMode.SAFE_OPTIMIZE
			&& !sodiumConservative
			&& !glAutoDisabled
			&& hookOrder() == HookOrder.BEFORE_CALL;
		if (skip) {
			textureBindSkipped.increment();
			textureBindActual.decrement();
			return false;
		}
		
		if (mode == OptiminiumSettings.OpenGlOptimizationMode.SAFE_OPTIMIZE && sodiumConservative && conservativeSame) {
			noSkipCompatibilityMode.increment();
		}

		if (knownUnit) {
			textureTargets[activeTextureUnit] = target;
			textureIds[activeTextureUnit] = textureId;
			relaxedTextureTargets[activeTextureUnit] = target;
			relaxedTextureIds[activeTextureUnit] = textureId;
			textureStateInvalidated = false;
		}
		return true;
	}

	public static boolean tryUseShader(int program) {
		OptiminiumSettings.OpenGlOptimizationMode mode = effectiveMode();
		if (mode == OptiminiumSettings.OpenGlOptimizationMode.OFF) {
			return true;
		}

		shaderBindRequests.increment();
		if (mode == OptiminiumSettings.OpenGlOptimizationMode.DIAGNOSTIC_ONLY
				|| mode == OptiminiumSettings.OpenGlOptimizationMode.MEASURE_ONLY) {
			return true;
		}
		shaderBindActual.increment();

		observedPreviousShaderId = activeProgram;
		requestedShaderId = program;
		boolean conservativeSame = shaderKnown && !shaderStateInvalidated && activeProgram == program;
		boolean relaxedSame = relaxedShaderKnown && relaxedActiveProgram == program;
		if (conservativeSame) {
			shaderBindPotentialSkipped.increment();
		}
		if (relaxedSame) {
			shaderRelaxedPotentialSkipped.increment();
		}
		if (!conservativeSame) {
			if (shaderStateInvalidated) {
				noSkipStateInvalidated.increment();
			} else if (shaderKnown) {
				shaderIdMismatches.increment();
				noSkipDifferentShaderId.increment();
			}
		}

		boolean skip = conservativeSame
			&& mode == OptiminiumSettings.OpenGlOptimizationMode.SAFE_OPTIMIZE
			&& !sodiumConservative
			&& !glAutoDisabled
			&& hookOrder() == HookOrder.BEFORE_CALL;
		if (skip) {
			shaderBindSkipped.increment();
			shaderBindActual.decrement();
			return false;
		}
		if (mode == OptiminiumSettings.OpenGlOptimizationMode.SAFE_OPTIMIZE && sodiumConservative && conservativeSame) {
			noSkipCompatibilityMode.increment();
		}

		activeProgram = program;
		relaxedActiveProgram = program;
		shaderKnown = true;
		relaxedShaderKnown = true;
		shaderStateInvalidated = false;
		return true;
	}

	public static void invalidate() {
		invalidate(InvalidationReason.UNKNOWN_GL);
	}

	public static void invalidate(InvalidationReason reason) {
		OptiminiumSettings.OpenGlOptimizationMode mode = effectiveMode();
		if (mode == OptiminiumSettings.OpenGlOptimizationMode.OFF) {
			return;
		}
		trackerInvalidations.increment();
		invalidationReasons[reason.ordinal()].increment();
		if (!tracksState(mode)) {
			if (reason == InvalidationReason.FRAMEBUFFER) {
				framebufferOnlyInvalidations.increment();
			}
			return;
		}
		switch (reason) {
			case FRAMEBUFFER -> {
				framebufferOnlyInvalidations.increment();
				clearConservativeTextureState();
				clearConservativeShaderState();
			}
			case TEXTURE_STATE -> clearConservativeTextureState();
			case SHADER_RELOAD, SHADER_STATE -> clearConservativeShaderState();
			default -> clearFullState();
		}
	}

	public static void onGlError(int error, String reason) {
		if (error == 0) {
			return;
		}
		glErrorsDetected.increment();
		if (effectiveMode() == OptiminiumSettings.OpenGlOptimizationMode.SAFE_OPTIMIZE && !glAutoDisabled) {
			glAutoDisabled = true;
			glAutoDisableReason = reason + ":glError=" + error;
		}
	}

	public static DiagnosticSnapshot snapshot() {
		OptiminiumSettings.OpenGlOptimizationMode mode = effectiveMode();
		long conservativePotential = textureBindPotentialSkipped.sum() + shaderBindPotentialSkipped.sum();
		long relaxedPotential = textureRelaxedPotentialSkipped.sum() + shaderRelaxedPotentialSkipped.sum();
		long textureRequests = textureBindRequests.sum();
		long textureSkipped = textureBindSkipped.sum();
		long shaderRequests = shaderBindRequests.sum();
		long shaderSkipped = shaderBindSkipped.sum();
		long textureActual = tracksState(mode) ? textureBindActual.sum() : Math.max(0L, textureRequests - textureSkipped);
		long shaderActual = tracksState(mode) ? shaderBindActual.sum() : Math.max(0L, shaderRequests - shaderSkipped);
		return new DiagnosticSnapshot(
			OptiminiumSettings.isOpenGlTweaksEnabled(),
			mode.name(),
			sodiumConservative && mode == OptiminiumSettings.OpenGlOptimizationMode.SAFE_OPTIMIZE,
			glAutoDisabled,
			glAutoDisableReason,
			textureRequests,
			textureBindPotentialSkipped.sum(),
			textureRelaxedPotentialSkipped.sum(),
			textureSkipped,
			textureActual,
			shaderRequests,
			shaderBindPotentialSkipped.sum(),
			shaderRelaxedPotentialSkipped.sum(),
			shaderSkipped,
			shaderActual,
			trackerInvalidations.sum(),
			activeTextureUnitMismatches.sum(),
			textureTargetMismatches.sum(),
			textureIdMismatches.sum(),
			shaderIdMismatches.sum(),
			framebufferOnlyInvalidations.sum(),
			invalidationReasons[InvalidationReason.RESOURCE_RELOAD.ordinal()].sum(),
			invalidationReasons[InvalidationReason.WORLD_UNLOAD.ordinal()].sum(),
			invalidationReasons[InvalidationReason.UNKNOWN_GL.ordinal()].sum(),
			textureStateInvalidations.sum(),
			shaderStateInvalidations.sum(),
			fullStateInvalidations.sum(),
			noSkipDifferentTextureId.sum(),
			noSkipDifferentShaderId.sum(),
			noSkipDifferentTarget.sum(),
			noSkipDifferentActiveUnit.sum(),
			noSkipStateInvalidated.sum(),
			noSkipModeDisabled.sum(),
			noSkipCompatibilityMode.sum(),
			conservativePotential,
			relaxedPotential,
			glErrorsDetected.sum(),
			topInvalidationReason(),
			hookOrder().name(),
			observedPreviousTextureId,
			requestedTextureId,
			observedPreviousShaderId,
			requestedShaderId,
			topNoSkipReason()
		);
	}

	public static FrameDiagnostics frameDiagnostics() {
		if (!isFrameDiagnosticsActive()) {
			return FrameDiagnostics.empty();
		}
		OptiminiumSettings.OpenGlOptimizationMode mode = effectiveMode();
		long textureRequests = textureBindRequests.sum() - frameStartRequestsTex;
		long textureSkipped = textureBindSkipped.sum() - frameStartSkippedTex;
		long shaderRequests = shaderBindRequests.sum() - frameStartRequestsSh;
		long shaderSkipped = shaderBindSkipped.sum() - frameStartSkippedSh;
		return new FrameDiagnostics(
			textureRequests,
			textureBindPotentialSkipped.sum() - frameStartPotentialSkippedTex,
			textureSkipped,
			tracksState(mode) ? textureBindActual.sum() - frameStartActualTex : Math.max(0L, textureRequests - textureSkipped),
			shaderRequests,
			shaderBindPotentialSkipped.sum() - frameStartPotentialSkippedSh,
			shaderSkipped,
			tracksState(mode) ? shaderBindActual.sum() - frameStartActualSh : Math.max(0L, shaderRequests - shaderSkipped),
			trackerInvalidations.sum() - frameStartInvalidations
		);
	}

	private static long frameStartRequestsTex;
	private static long frameStartPotentialSkippedTex;
	private static long frameStartSkippedTex;
	private static long frameStartActualTex;
	private static long frameStartRequestsSh;
	private static long frameStartPotentialSkippedSh;
	private static long frameStartSkippedSh;
	private static long frameStartActualSh;
	private static long frameStartInvalidations;

	public static void onFrameStart() {
		if (!isFrameDiagnosticsActive()) {
			frameStartRequestsTex = 0L;
			frameStartPotentialSkippedTex = 0L;
			frameStartSkippedTex = 0L;
			frameStartActualTex = 0L;
			frameStartRequestsSh = 0L;
			frameStartPotentialSkippedSh = 0L;
			frameStartSkippedSh = 0L;
			frameStartActualSh = 0L;
			frameStartInvalidations = 0L;
			return;
		}
		frameStartRequestsTex = textureBindRequests.sum();
		frameStartPotentialSkippedTex = textureBindPotentialSkipped.sum();
		frameStartSkippedTex = textureBindSkipped.sum();
		frameStartActualTex = textureBindActual.sum();
		frameStartRequestsSh = shaderBindRequests.sum();
		frameStartPotentialSkippedSh = shaderBindPotentialSkipped.sum();
		frameStartSkippedSh = shaderBindSkipped.sum();
		frameStartActualSh = shaderBindActual.sum();
		frameStartInvalidations = trackerInvalidations.sum();
	}

	private static boolean isFrameDiagnosticsActive() {
		return OptiminiumSettings.isOpenGlTweaksEnabled()
			&& OptiminiumSettings.getOpenGlOptimizationMode() != OptiminiumSettings.OpenGlOptimizationMode.OFF
			&& !glAutoDisabled;
	}

	private static OptiminiumSettings.OpenGlOptimizationMode effectiveMode() {
		if (!OptiminiumSettings.isOpenGlTweaksEnabled() || glAutoDisabled) {
			if (!OptiminiumSettings.isOpenGlTweaksEnabled()) {
				noSkipModeDisabled.increment();
			}
			return OptiminiumSettings.OpenGlOptimizationMode.OFF;
		}
		return OptiminiumSettings.getOpenGlOptimizationMode();
	}

	private static boolean tracksState(OptiminiumSettings.OpenGlOptimizationMode mode) {
		return mode == OptiminiumSettings.OpenGlOptimizationMode.SAFE_OPTIMIZE;
	}

	private static HookOrder hookOrder() {
		return HookOrder.BEFORE_CALL;
	}

	private static void recordTextureNoSkip(boolean knownUnit, int previousTarget, int previousTexture, int target) {
		if (!knownUnit) {
			activeTextureUnitMismatches.increment();
			noSkipDifferentActiveUnit.increment();
		} else if (textureStateInvalidated) {
			noSkipStateInvalidated.increment();
		} else if (previousTarget != target) {
			textureTargetMismatches.increment();
			noSkipDifferentTarget.increment();
		} else if (previousTexture != UNKNOWN) {
			textureIdMismatches.increment();
			noSkipDifferentTextureId.increment();
		}
	}

	private static void clearConservativeTextureState() {
		clearTextures(textureTargets, textureIds);
		activeTextureKnown = false;
		textureStateInvalidated = true;
		textureStateInvalidations.increment();
	}

	private static void clearConservativeShaderState() {
		activeProgram = UNKNOWN;
		shaderKnown = false;
		shaderStateInvalidated = true;
		shaderStateInvalidations.increment();
	}

	private static void clearFullState() {
		clearConservativeTextureState();
		clearConservativeShaderState();
		clearTextures(relaxedTextureTargets, relaxedTextureIds);
		relaxedActiveProgram = UNKNOWN;
		relaxedShaderKnown = false;
		fullStateInvalidations.increment();
	}

	private static void clearTextures(int[] targets, int[] ids) {
		for (int i = 0; i < MAX_TEXTURE_UNITS; i++) {
			targets[i] = UNKNOWN;
			ids[i] = UNKNOWN;
		}
	}

	private static String topInvalidationReason() {
		InvalidationReason[] values = InvalidationReason.values();
		long best = 0L;
		InvalidationReason bestReason = InvalidationReason.NONE;
		for (int i = 0; i < values.length; i++) {
			long count = invalidationReasons[i].sum();
			if (count > best) {
				best = count;
				bestReason = values[i];
			}
		}
		return best <= 0L ? "none" : bestReason.name() + ":" + best;
	}

	private static String topNoSkipReason() {
		String reason = "none";
		long best = 0L;
		long[] counts = {
			noSkipDifferentTextureId.sum(),
			noSkipDifferentShaderId.sum(),
			noSkipDifferentTarget.sum(),
			noSkipDifferentActiveUnit.sum(),
			noSkipStateInvalidated.sum(),
			noSkipModeDisabled.sum(),
			noSkipCompatibilityMode.sum()
		};
		String[] names = {
			"differentTextureId",
			"differentShaderId",
			"differentTarget",
			"differentActiveUnit",
			"stateInvalidated",
			"modeDisabled",
			"compatibilityMode"
		};
		for (int i = 0; i < counts.length; i++) {
			if (counts[i] > best) {
				best = counts[i];
				reason = names[i] + ":" + best;
			}
		}
		return reason;
	}

	public enum HookOrder {
		BEFORE_CALL,
		AFTER_CALL,
		UNKNOWN
	}

	public enum InvalidationReason {
		NONE,
		UNKNOWN_GL,
		FRAMEBUFFER,
		RENDER_PASS,
		RENDER_TARGET,
		TEXTURE_STATE,
		SHADER_STATE,
		SHADER_RELOAD,
		RESOURCE_RELOAD,
		WORLD_UNLOAD,
		SODIUM_COMPAT
	}

	public record DiagnosticSnapshot(
		boolean openGlTweaksEnabled,
		String mode,
		boolean compatibilitySkipDisabled,
		boolean glAutoDisabled,
		String glAutoDisableReason,
		long textureBindRequests,
		long textureBindPotentialSkipped,
		long textureRelaxedPotentialSkipped,
		long textureBindSkipped,
		long textureBindActual,
		long shaderBindRequests,
		long shaderBindPotentialSkipped,
		long shaderRelaxedPotentialSkipped,
		long shaderBindSkipped,
		long shaderBindActual,
		long trackerInvalidations,
		long activeTextureUnitMismatches,
		long textureTargetMismatches,
		long textureIdMismatches,
		long shaderIdMismatches,
		long framebufferInvalidations,
		long resourceReloadInvalidations,
		long worldUnloadInvalidations,
		long unknownExternalInvalidations,
		long textureStateInvalidations,
		long shaderStateInvalidations,
		long fullStateInvalidations,
		long noSkipBecauseDifferentTextureId,
		long noSkipBecauseDifferentShaderId,
		long noSkipBecauseDifferentTarget,
		long noSkipBecauseDifferentActiveUnit,
		long noSkipBecauseStateInvalidated,
		long noSkipBecauseModeDisabled,
		long noSkipBecauseCompatibilityMode,
		long conservativePotentialSkips,
		long relaxedPotentialSkips,
		long glErrorsDetected,
		String topInvalidationReason,
		String hookOrder,
		int observedPreviousTextureId,
		int requestedTextureId,
		int observedPreviousShaderId,
		int requestedShaderId,
		String topNoSkipReason
	) {
		private static final DiagnosticSnapshot EMPTY =
			new DiagnosticSnapshot(true, "MEASURE_ONLY", false, false, "none", 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
				0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
				0L, 0L, "none", "UNKNOWN", UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN, "none");

		public static DiagnosticSnapshot empty() {
			return EMPTY;
		}

		public long textureBindSkippedPercent() {
			return percent(textureBindRequests, textureBindSkipped);
		}

		public long textureBindPotentialSkippedPercent() {
			return percent(textureBindRequests, textureBindPotentialSkipped);
		}

		public long shaderBindSkippedPercent() {
			return percent(shaderBindRequests, shaderBindSkipped);
		}

		public long shaderBindPotentialSkippedPercent() {
			return percent(shaderBindRequests, shaderBindPotentialSkipped);
		}

		public long actualSkips() {
			return textureBindSkipped + shaderBindSkipped;
		}

		public long skipRatePercent() {
			return percent(textureBindRequests + shaderBindRequests, actualSkips());
		}
	}

	public record FrameDiagnostics(
		long textureBindRequests,
		long textureBindPotentialSkipped,
		long textureBindSkipped,
		long textureBindActual,
		long shaderBindRequests,
		long shaderBindPotentialSkipped,
		long shaderBindSkipped,
		long shaderBindActual,
		long trackerInvalidations
	) {
		private static final FrameDiagnostics EMPTY =
			new FrameDiagnostics(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);

		public static FrameDiagnostics empty() {
			return EMPTY;
		}

		public long textureSkippedThisFrame() {
			return textureBindSkipped;
		}

		public long shaderSkippedThisFrame() {
			return shaderBindSkipped;
		}

		public long textureSkippedPercent() {
			return percent(textureBindRequests, textureBindSkipped);
		}

		public long shaderSkippedPercent() {
			return percent(shaderBindRequests, shaderBindSkipped);
		}
	}

	private static long percent(long requests, long skips) {
		return requests <= 0L ? 0L : skips * 100L / requests;
	}
}
