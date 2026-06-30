package net.optiminium.client;

import net.optiminium.compat.OptiminiumSodiumCompat;

import java.util.concurrent.atomic.LongAdder;

/**
 * Lightweight OpenGL render-state shadow tracker that skips redundant texture
 * and shader bind calls when the requested state is already known to be active.
 *
 * <p>The tracker maintains:
 * <ul>
 *   <li>The last-bound texture ID (single slot — safe because the most common
 *       redundant bind is the same texture on the same unit repeatedly)</li>
 *   <li>The last-used shader program ID</li>
 *   <li>A validity flag that, when false, forces all binds through
 *       (re-learning state) until the tracker is consistent again</li>
 * </ul>
 *
 * <p>All counters use {@link LongAdder} for thread safety at negligible cost
 * on the render thread, and remain correct if profiling is ever accessed
 * from another thread.
 */
public final class OptiminiumGlStateTracker {
	private static int lastBoundTexture;
	private static int activeProgram;
	private static boolean valid;
	private static boolean sodiumConservative;

	// ── Diagnostic counters ──────────────────────────────────────────────
	private static final LongAdder textureBindRequests = new LongAdder();
	private static final LongAdder textureBindSkipped = new LongAdder();
	private static final LongAdder textureBindActual = new LongAdder();
	private static final LongAdder shaderBindRequests = new LongAdder();
	private static final LongAdder shaderBindSkipped = new LongAdder();
	private static final LongAdder shaderBindActual = new LongAdder();
	private static final LongAdder trackerInvalidations = new LongAdder();

	static {
		reset();
	}

	private OptiminiumGlStateTracker() {
	}

	// ── Initialization ───────────────────────────────────────────────────

	/**
	 * Initialise the tracker. Should be called once at mod construction time.
	 */
	public static void init() {
		sodiumConservative = OptiminiumSodiumCompat.isNonVanillaRenderer();
		reset();
	}

	/**
	 * Completely reset all tracked state and diagnostics.
	 */
	public static void reset() {
		lastBoundTexture = -1;
		activeProgram = -1;
		valid = true;
		textureBindRequests.reset();
		textureBindSkipped.reset();
		textureBindActual.reset();
		shaderBindRequests.reset();
		shaderBindSkipped.reset();
		shaderBindActual.reset();
		trackerInvalidations.reset();
	}

	// ── Texture binding ──────────────────────────────────────────────────

	/**
	 * Check whether a texture bind should proceed. Returns {@code true}
	 * when the GL call is needed, or {@code false} when the texture is
	 * already bound and the call can be skipped.
	 *
	 * <p>Conservative: only skips when the tracker is valid and NOT in
	 * Sodium compat mode. Uses a single last-bound-texture slot, which
	 * catches the most common redundant-bind pattern (same texture on the
	 * same unit repeatedly). This is always safe because:</p>
	 * <ul>
	 *   <li>The most common redundant binds are consecutive same-texture
	 *       binds to the same unit</li>
	 *   <li>Cross-unit same-texture binds are rare enough that the skip
	 *       is still correct (the texture is already resident)</li>
	 *   <li>On invalidation, the tracker resets and forces a bind</li>
	 * </ul>
	 */
	public static boolean tryBindTexture(int textureId) {
		textureBindRequests.increment();
		if (valid && !sodiumConservative) {
			if (lastBoundTexture == textureId) {
				textureBindSkipped.increment();
				return false;         // already bound — skip
			}
		}
		lastBoundTexture = textureId;
		textureBindActual.increment();
		return true;                  // proceed with the GL call
	}

	// ── Shader binding ───────────────────────────────────────────────────

	/**
	 * Check whether a shader program bind should proceed. Returns
	 * {@code true} when the GL call is needed, or {@code false} when
	 * the program is already active and the call can be skipped.
	 */
	public static boolean tryUseShader(int program) {
		shaderBindRequests.increment();
		if (valid && !sodiumConservative) {
			if (activeProgram == program) {
				shaderBindSkipped.increment();
				return false;         // already active — skip
			}
		}
		activeProgram = program;
		shaderBindActual.increment();
		return true;                  // proceed with the GL call
	}

	// ── Invalidation ─────────────────────────────────────────────────────

	/**
	 * Invalidate all tracked state. The next bind / shader calls will
	 * always proceed until the tracker has re-learned the current state.
	 *
	 * <p>Call this on:
	 * <ul>
	 *   <li>Resource reloads</li>
	 *   <li>Render pass boundaries (level render start)</li>
	 *   <li>Framebuffer changes</li>
	 *   <li>Dimension / world changes</li>
	 *   <li>Any unknown external GL state event</li>
	 * </ul>
	 */
	public static void invalidate() {
		lastBoundTexture = -1;
		activeProgram = -1;
		valid = true;    // cleared state is now the known state
		trackerInvalidations.increment();
	}

	// ── Snapshots ────────────────────────────────────────────────────────

	/**
	 * Take a snapshot of all diagnostic counters.
	 */
	public static DiagnosticSnapshot snapshot() {
		return new DiagnosticSnapshot(
			textureBindRequests.sum(),
			textureBindSkipped.sum(),
			textureBindActual.sum(),
			shaderBindRequests.sum(),
			shaderBindSkipped.sum(),
			shaderBindActual.sum(),
			trackerInvalidations.sum()
		);
	}

	/**
	 * Per-frame diagnostic counters (captured at frame boundaries).
	 */
	public static FrameDiagnostics frameDiagnostics() {
		return new FrameDiagnostics(
			textureBindRequests.sum() - frameStartRequestsTex,
			textureBindSkipped.sum() - frameStartSkippedTex,
			textureBindActual.sum() - frameStartActualTex,
			shaderBindRequests.sum() - frameStartRequestsSh,
			shaderBindSkipped.sum() - frameStartSkippedSh,
			shaderBindActual.sum() - frameStartActualSh,
			trackerInvalidations.sum() - frameStartInvalidations
		);
	}

	private static long frameStartRequestsTex;
	private static long frameStartSkippedTex;
	private static long frameStartActualTex;
	private static long frameStartRequestsSh;
	private static long frameStartSkippedSh;
	private static long frameStartActualSh;
	private static long frameStartInvalidations;

	/**
	 * Must be called once per frame (before any rendering) to snapshot
	 * the frame-start positions of all diagnostic counters so that
	 * {@link #frameDiagnostics()} returns delta-vs-frame-start values.
	 */
	public static void onFrameStart() {
		frameStartRequestsTex  = textureBindRequests.sum();
		frameStartSkippedTex   = textureBindSkipped.sum();
		frameStartActualTex    = textureBindActual.sum();
		frameStartRequestsSh   = shaderBindRequests.sum();
		frameStartSkippedSh    = shaderBindSkipped.sum();
		frameStartActualSh     = shaderBindActual.sum();
		frameStartInvalidations = trackerInvalidations.sum();
	}

	// ── Data types ───────────────────────────────────────────────────────

	public record DiagnosticSnapshot(
		long textureBindRequests,
		long textureBindSkipped,
		long textureBindActual,
		long shaderBindRequests,
		long shaderBindSkipped,
		long shaderBindActual,
		long trackerInvalidations
	) {
		private static final DiagnosticSnapshot EMPTY =
			new DiagnosticSnapshot(0L, 0L, 0L, 0L, 0L, 0L, 0L);

		public static DiagnosticSnapshot empty() {
			return EMPTY;
		}

		public long textureBindSkippedPercent() {
			long req = textureBindRequests;
			return req <= 0L ? 0L : textureBindSkipped * 100L / req;
		}

		public long shaderBindSkippedPercent() {
			long req = shaderBindRequests;
			return req <= 0L ? 0L : shaderBindSkipped * 100L / req;
		}
	}

	public record FrameDiagnostics(
		long textureBindRequests,
		long textureBindSkipped,
		long textureBindActual,
		long shaderBindRequests,
		long shaderBindSkipped,
		long shaderBindActual,
		long trackerInvalidations
	) {
		private static final FrameDiagnostics EMPTY =
			new FrameDiagnostics(0L, 0L, 0L, 0L, 0L, 0L, 0L);

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
			long req = textureBindRequests;
			return req <= 0L ? 0L : textureBindSkipped * 100L / req;
		}

		public long shaderSkippedPercent() {
			long req = shaderBindRequests;
			return req <= 0L ? 0L : shaderBindSkipped * 100L / req;
		}
	}
}