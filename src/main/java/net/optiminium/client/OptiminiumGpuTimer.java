package net.optiminium.client;

import net.optiminium.optimization.OptiminiumSettings;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.ARBTimerQuery;

public final class OptiminiumGpuTimer {
	private static final int QUERY_COUNT = 4;
	private static final int SAMPLE_INTERVAL_FRAMES = 2;
	private static final double SMOOTHING = 0.12D;
	private static final int[] startQueries = new int[QUERY_COUNT];
	private static final int[] endQueries = new int[QUERY_COUNT];
	private static boolean initialized;
	private static boolean supported;
	private static boolean useArbTimerQuery;
	private static boolean sampleOpen;
	private static int writeIndex;
	private static int pendingQueries;
	private static int frameCounter;
	private static long latestGpuNanos;
	private static long worstGpuNanos;
	private static long sampleCount;
	private static double smoothedGpuNanos;
	private static String unavailableReason = "not initialized";

	private OptiminiumGpuTimer() {
	}

	public static void onFrameStart() {
		initialize();
		readReadyResults();
		if (!isActive()) {
			frameCounter = 0;
			return;
		}
		if (pendingQueries >= QUERY_COUNT || frameCounter++ % SAMPLE_INTERVAL_FRAMES != 0) {
			return;
		}
		queryCounter(startQueries[writeIndex]);
		sampleOpen = true;
	}

	public static void onFrameEnd() {
		if (!sampleOpen) {
			return;
		}
		sampleOpen = false;
		queryCounter(endQueries[writeIndex]);
		writeIndex = (writeIndex + 1) % QUERY_COUNT;
		pendingQueries = Math.min(QUERY_COUNT, pendingQueries + 1);
	}

	public static boolean isActive() {
		return supported && OptiminiumSettings.isGpuOptimizer();
	}

	public static boolean hasTiming() {
		return latestGpuNanos > 0L;
	}

	public static long getLatestGpuNanos() {
		return latestGpuNanos;
	}

	public static double getSmoothedGpuNanos() {
		return smoothedGpuNanos;
	}

	public static long getWorstGpuNanos() {
		return worstGpuNanos;
	}

	public static long getSampleCount() {
		return sampleCount;
	}

	public static void resetWorstGpuNanos() {
		worstGpuNanos = 0L;
	}

	public static String status() {
		if (!initialized) {
			return "not initialized";
		}
		if (!supported) {
			return "unsupported: " + unavailableReason;
		}
		return OptiminiumSettings.isGpuOptimizer() ? "active" : "disabled";
	}

	private static void initialize() {
		if (initialized) {
			return;
		}
		initialized = true;
		try {
			if (GL.getCapabilities() == null) {
				unavailableReason = "no GL capabilities";
				return;
			}
			if (!GL.getCapabilities().OpenGL33 && !GL.getCapabilities().GL_ARB_timer_query) {
				unavailableReason = "timer query extension missing";
				return;
			}
			useArbTimerQuery = !GL.getCapabilities().OpenGL33;
			for (int i = 0; i < QUERY_COUNT; i++) {
				startQueries[i] = GL15.glGenQueries();
				endQueries[i] = GL15.glGenQueries();
			}
			supported = true;
			unavailableReason = "";
		} catch (RuntimeException exception) {
			supported = false;
			unavailableReason = exception.getClass().getSimpleName();
		}
	}

	private static void readReadyResults() {
		if (!supported || pendingQueries <= 0) {
			return;
		}
		int readIndex = Math.floorMod(writeIndex - pendingQueries, QUERY_COUNT);
		while (pendingQueries > 0 && GL15.glGetQueryObjecti(endQueries[readIndex], GL15.GL_QUERY_RESULT_AVAILABLE) != 0) {
			long nanos = Math.max(0L, queryResult(endQueries[readIndex]) - queryResult(startQueries[readIndex]));
			latestGpuNanos = nanos;
			sampleCount++;
			worstGpuNanos = Math.max(worstGpuNanos, nanos);
			if (smoothedGpuNanos <= 0.0D) {
				smoothedGpuNanos = nanos;
			} else {
				smoothedGpuNanos += (nanos - smoothedGpuNanos) * SMOOTHING;
			}
			pendingQueries--;
			readIndex = (readIndex + 1) % QUERY_COUNT;
		}
	}

	private static void queryCounter(int query) {
		if (useArbTimerQuery) {
			ARBTimerQuery.glQueryCounter(query, ARBTimerQuery.GL_TIMESTAMP);
		} else {
			GL33C.glQueryCounter(query, GL33C.GL_TIMESTAMP);
		}
	}

	private static long queryResult(int query) {
		return useArbTimerQuery
			? ARBTimerQuery.glGetQueryObjectui64(query, GL15.GL_QUERY_RESULT)
			: GL33C.glGetQueryObjectui64(query, GL15.GL_QUERY_RESULT);
	}
}
