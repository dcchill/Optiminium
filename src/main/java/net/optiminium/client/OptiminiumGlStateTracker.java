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
	private static int activeTextureUnit;
	private static boolean activeTextureKnown;
	private static int activeProgram;
	private static boolean shaderKnown;
	private static boolean sodiumConservative;

	private static final LongAdder textureBindRequests = new LongAdder();
	private static final LongAdder textureBindPotentialSkipped = new LongAdder();
	private static final LongAdder textureBindSkipped = new LongAdder();
	private static final LongAdder textureBindActual = new LongAdder();
	private static final LongAdder shaderBindRequests = new LongAdder();
	private static final LongAdder shaderBindPotentialSkipped = new LongAdder();
	private static final LongAdder shaderBindSkipped = new LongAdder();
	private static final LongAdder shaderBindActual = new LongAdder();
	private static final LongAdder trackerInvalidations = new LongAdder();
	private static final LongAdder activeTextureUnitMismatches = new LongAdder();
	private static final LongAdder textureTargetMismatches = new LongAdder();
	private static final LongAdder textureIdMismatches = new LongAdder();
	private static final LongAdder shaderIdMismatches = new LongAdder();
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
		shaderKnown = false;
		for (int i = 0; i < MAX_TEXTURE_UNITS; i++) {
			textureTargets[i] = UNKNOWN;
			textureIds[i] = UNKNOWN;
		}
		textureBindRequests.reset();
		textureBindPotentialSkipped.reset();
		textureBindSkipped.reset();
		textureBindActual.reset();
		shaderBindRequests.reset();
		shaderBindPotentialSkipped.reset();
		shaderBindSkipped.reset();
		shaderBindActual.reset();
		trackerInvalidations.reset();
		activeTextureUnitMismatches.reset();
		textureTargetMismatches.reset();
		textureIdMismatches.reset();
		shaderIdMismatches.reset();
		for (LongAdder reason : invalidationReasons) {
			reason.reset();
		}
	}

	public static void onActiveTexture(int glTexture) {
		int unit = glTexture - GL_TEXTURE0;
		if (unit >= 0 && unit < MAX_TEXTURE_UNITS) {
			activeTextureUnit = unit;
			activeTextureKnown = true;
		} else {
			activeTextureKnown = false;
		}
	}

	public static boolean tryBindTexture(int target, int textureId) {
		textureBindRequests.increment();
		OptiminiumSettings.GlStateTrackerMode mode = OptiminiumSettings.getGlStateTrackerMode();
		boolean knownUnit = activeTextureKnown && activeTextureUnit >= 0 && activeTextureUnit < MAX_TEXTURE_UNITS;
		boolean same = mode != OptiminiumSettings.GlStateTrackerMode.OFF
			&& knownUnit
			&& textureTargets[activeTextureUnit] == target
			&& textureIds[activeTextureUnit] == textureId;

		if (same) {
			textureBindPotentialSkipped.increment();
			if (mode == OptiminiumSettings.GlStateTrackerMode.SAFE_SKIP && !sodiumConservative) {
				textureBindSkipped.increment();
				return false;
			}
		} else if (mode != OptiminiumSettings.GlStateTrackerMode.OFF) {
			if (!knownUnit) {
				activeTextureUnitMismatches.increment();
			} else if (textureTargets[activeTextureUnit] != target) {
				textureTargetMismatches.increment();
			} else {
				textureIdMismatches.increment();
			}
		}

		if (knownUnit) {
			textureTargets[activeTextureUnit] = target;
			textureIds[activeTextureUnit] = textureId;
		}
		textureBindActual.increment();
		return true;
	}

	public static boolean tryUseShader(int program) {
		shaderBindRequests.increment();
		OptiminiumSettings.GlStateTrackerMode mode = OptiminiumSettings.getGlStateTrackerMode();
		boolean same = mode != OptiminiumSettings.GlStateTrackerMode.OFF && shaderKnown && activeProgram == program;

		if (same) {
			shaderBindPotentialSkipped.increment();
			if (mode == OptiminiumSettings.GlStateTrackerMode.SAFE_SKIP && !sodiumConservative) {
				shaderBindSkipped.increment();
				return false;
			}
		} else if (mode != OptiminiumSettings.GlStateTrackerMode.OFF && shaderKnown) {
			shaderIdMismatches.increment();
		}

		activeProgram = program;
		shaderKnown = true;
		shaderBindActual.increment();
		return true;
	}

	public static void invalidate() {
		invalidate(InvalidationReason.UNKNOWN_GL);
	}

	public static void invalidate(InvalidationReason reason) {
		activeTextureKnown = false;
		activeProgram = UNKNOWN;
		shaderKnown = false;
		for (int i = 0; i < MAX_TEXTURE_UNITS; i++) {
			textureTargets[i] = UNKNOWN;
			textureIds[i] = UNKNOWN;
		}
		trackerInvalidations.increment();
		invalidationReasons[reason.ordinal()].increment();
	}

	public static DiagnosticSnapshot snapshot() {
		OptiminiumSettings.GlStateTrackerMode mode = OptiminiumSettings.getGlStateTrackerMode();
		return new DiagnosticSnapshot(
			textureBindRequests.sum(),
			textureBindPotentialSkipped.sum(),
			textureBindSkipped.sum(),
			textureBindActual.sum(),
			shaderBindRequests.sum(),
			shaderBindPotentialSkipped.sum(),
			shaderBindSkipped.sum(),
			shaderBindActual.sum(),
			trackerInvalidations.sum(),
			activeTextureUnitMismatches.sum(),
			textureTargetMismatches.sum(),
			textureIdMismatches.sum(),
			shaderIdMismatches.sum(),
			topInvalidationReason(),
			mode.name(),
			sodiumConservative && mode == OptiminiumSettings.GlStateTrackerMode.SAFE_SKIP
		);
	}

	public static FrameDiagnostics frameDiagnostics() {
		return new FrameDiagnostics(
			textureBindRequests.sum() - frameStartRequestsTex,
			textureBindPotentialSkipped.sum() - frameStartPotentialSkippedTex,
			textureBindSkipped.sum() - frameStartSkippedTex,
			textureBindActual.sum() - frameStartActualTex,
			shaderBindRequests.sum() - frameStartRequestsSh,
			shaderBindPotentialSkipped.sum() - frameStartPotentialSkippedSh,
			shaderBindSkipped.sum() - frameStartSkippedSh,
			shaderBindActual.sum() - frameStartActualSh,
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

	public enum InvalidationReason {
		NONE,
		UNKNOWN_GL,
		FRAMEBUFFER,
		RENDER_PASS,
		RENDER_TARGET,
		SHADER_RELOAD,
		RESOURCE_RELOAD,
		WORLD_UNLOAD,
		SODIUM_COMPAT
	}

	public record DiagnosticSnapshot(
		long textureBindRequests,
		long textureBindPotentialSkipped,
		long textureBindSkipped,
		long textureBindActual,
		long shaderBindRequests,
		long shaderBindPotentialSkipped,
		long shaderBindSkipped,
		long shaderBindActual,
		long trackerInvalidations,
		long activeTextureUnitMismatches,
		long textureTargetMismatches,
		long textureIdMismatches,
		long shaderIdMismatches,
		String topInvalidationReason,
		String mode,
		boolean compatibilitySkipDisabled
	) {
		private static final DiagnosticSnapshot EMPTY =
			new DiagnosticSnapshot(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, "none", "MEASURE_ONLY", false);

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
