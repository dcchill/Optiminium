package net.optiminium.optimization;

import java.util.concurrent.atomic.LongAdder;

public class OptimiumMetrics {
    private static final LongAdder rawSpikeTriggerFrames = new LongAdder();
    private static final LongAdder pacingSpikeTriggerFrames = new LongAdder();
    private static final LongAdder uploadOverBudgetFrames = new LongAdder();

    public static void rawSpikeTriggerFrames(long count) { rawSpikeTriggerFrames.add(count); }
    public static long rawSpikeTriggerFrames() { return rawSpikeTriggerFrames.sum(); }

    public static void pacingSpikeTriggerFrames(long count) { pacingSpikeTriggerFrames.add(count); }
    public static long pacingSpikeTriggerFrames() { return pacingSpikeTriggerFrames.sum(); }

    public static void uploadOverBudgetFrames(long count) { uploadOverBudgetFrames.add(count); }
    public static long uploadOverBudgetFrames() { return uploadOverBudgetFrames.sum(); }

    public static void reset() {
        rawSpikeTriggerFrames.reset();
        pacingSpikeTriggerFrames.reset();
        uploadOverBudgetFrames.reset();
    }
}
