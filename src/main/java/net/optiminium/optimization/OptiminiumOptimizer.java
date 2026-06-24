package net.optiminium.optimization;

public class OptimiminumOptimizer {

    private static boolean shouldRunAdaptiveOptimizations() {
        return OptimiumSettings.isAdaptiveQualityEnabled()
                && (serverHealth.isDegraded() || /* other triggers */);
    }

    // Example: in ServerHealthMonitor.update(...)
    public void updateServerHealth(double mspt) {
        double rawSpikeThreshold = OptimiumSettings.getSpikeTriggerPercent() / 100.0;
        if (mspt > rawSpikeThreshold) {
            // trigger adaptive cooldowns
        }
    }

    // In the block‑entity render pass or in OptimiumGpuOptimizer:
    public void checkBlockEntityDensity(int visibleBlockEntities) {
        if (visibleBlockEntities > OptimiumSettings.getDenseBlockEntityThreshold()) {
            // tighten block‑entity distance / budget
        }
    }

    // In the relevant render‑culling code:
    public void cullGraphicsEffects() {
        if (!OptimiumSettings.isGraphicsEffectCullingEnabled()) {
            return;   // skip culling – keep all effects
        }
        // … existing cull logic …
    }
}
