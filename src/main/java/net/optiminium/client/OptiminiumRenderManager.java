package net.optiminium.client;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Optiminium Render Manager - Tracks and manages render-related metrics for the mod.
 * Provides profiling information on draw calls, batching, and rendering overhead.
 */
public class OptiminiumRenderManager {

    private static final Logger LOGGER = LogManager.getLogger(OptiminiumRenderManager.class);

    // Quality tier constants for LOD/rendering decisions
    public static final byte CULLED     = 0;
    public static final byte REUSED     = 1;
    public static final byte THROTTLED  = 2;
    public static final byte PROXY      = 3;
    public static final byte LOW_QUALITY   = 4;
    public static final byte HIGH_QUALITY  = 5;

    // Render profiling counters - track Optiminium-specific rendering work only
    private long optiminiumDrawCalls;
    private long optiminiumRenderTypeSwitches;
    private long optiminiumEndBatchCalls;
    private long proxyDrawCalls;
    private long proxyBatches;
    private long debugDrawCalls;
    private long debugBatches;

    // Track last known render type to count switches (0 = none)
    private int currentRenderType = 0;
    
    public OptiminiumRenderManager() {
        this.optiminiumDrawCalls = 0L;
        this.optiminiumRenderTypeSwitches = 0L;
        this.optiminiumEndBatchCalls = 0L;
        this.proxyDrawCalls = 0L;
        this.proxyBatches = 0L;
        this.debugDrawCalls = 0L;
        this.debugBatches = 0L;
    }

    /** Reset all render counters for a new measurement period. */
    public void reset() {
        optiminiumDrawCalls = 0L;
        optiminiumRenderTypeSwitches = 0L;
        optiminiumEndBatchCalls = 0L;
        proxyDrawCalls = 0L;
        proxyBatches = 0L;
        debugDrawCalls = 0L;
        debugBatches = 0L;
        currentRenderType = 0;
    }

    /** Track an Optiminium draw call. */
    public void trackOptiminiumDrawCall() {
        optiminiumDrawCalls++;
    }

    /** Track a proxy (billboard/fallback) draw call. */
    public void trackProxyDrawCall() {
        proxyDrawCalls++;
    }

    /** Track debug rendering calls separately from game objects. */
    public void trackDebugDrawCall() {
        debugDrawCalls++;
    }

    /** 
     * Track a RenderType change - counts how many switches Optiminium caused.
     * @param newRenderTypeId The OpenGL/render type ID being switched to (0 = no switch).
     */
    public void trackRenderType(int newRenderTypeId) {
        if (newRenderTypeId != currentRenderType && newRenderTypeId != 0) {
            optiminiumRenderTypeSwitches++;
            currentRenderType = newRenderTypeId;
        }
    }

    /** Track when a batch is ended. */
    public void trackEndBatch() {
        optiminiumEndBatchCalls++;
    }

    /** Track proxy batching operations. */
    public void trackProxyBatch() {
        proxyBatches++;
    }

    /** Track debug rendering batches. */
    public void trackDebugBatch() {
        debugBatches++;
    }

    // Getters for benchmark reporting
    
    public long getOptiminiumDrawCalls() { return optiminiumDrawCalls; }
    public long getProxyDrawCalls() { return proxyDrawCalls; }
    public long getDebugDrawCalls() { return debugDrawCalls; }
    
    public long getTotalExtraDrawCalls() { 
        return optiminiumDrawCalls + proxyDrawCalls + debugDrawCalls; 
    }

    public long getOptiminiumRenderTypeSwitches() { return optiminiumRenderTypeSwitches; }
    public long getProxyBatches() { return proxyBatches; }
    public long getDebugBatches() { return debugBatches; }
    
    /** Total Optiminium-caused batches. */
    public long getTotalExtraBatches() { 
        return optiminiumEndBatchCalls + proxyBatches + debugBatches; 
    }

    public RenderMetrics snapshot() {
        return new RenderMetrics(optiminiumDrawCalls, optiminiumRenderTypeSwitches,
            optiminiumEndBatchCalls, proxyDrawCalls, proxyBatches, debugDrawCalls, debugBatches);
    }

    /** Simple metrics record for reporting. */
    public static class RenderMetrics {
        private final long optiminiumDrawCalls;
        private final long renderTypeSwitches;
        private final long endBatchCalls;
        private final long proxyDrawCalls;
        private final long proxyBatches;
        private final long debugDrawCalls;
        private final long debugBatches;

        public RenderMetrics(long optiminiumDrawCalls, long switches, long batches,
                            long proxyDraws, long proxyBaths, long dbgDraws, long dbgBatch) {
            this.optiminiumDrawCalls = optiminiumDrawCalls;
            this.renderTypeSwitches = switches;
            this.endBatchCalls = batches;
            this.proxyDrawCalls = proxyDraws;
            this.proxyBatches = proxyBaths;
            this.debugDrawCalls = dbgDraws;
            this.debugBatches = dbgBatch;
        }

        public String report() {
            StringBuilder sb = new StringBuilder();
            sb.append("Optiminium Render Metrics:\n");
            sb.append(String.format("  Optiminium draw calls: %d\n", optiminiumDrawCalls));
            sb.append(String.format("  Extra render type switches (caused by Optiminium): %d\n", renderTypeSwitches));
            sb.append(String.format("  endBatch() calls (Optiminium only): %d\n", endBatchCalls));
            sb.append(String.format("  Proxy draw calls: %d, batches: %d\n", proxyDrawCalls, proxyBatches));
            sb.append(String.format("  Debug draw calls: %d, batches: %d\n", debugDrawCalls, debugBatches));
            return sb.toString();
        }

        public String toCSVLine() {
            return optiminiumDrawCalls + "," + renderTypeSwitches + "," + endBatchCalls 
                + "," + proxyDrawCalls + "," + proxyBatches;
        }
    }

}
