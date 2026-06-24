package net.optiminium.benchmark;

import net.optiminium.optimization.OptimiumMetrics;

public class Benchmark {
    public void writeLine(StringBuilder sb) {
        // Example snippet inside Benchmark#writeLine(...)
        sb.append(",")
          .append(OptimiumMetrics.rawSpikeTriggerFrames())
          .append(",")
          .append(OptimiumMetrics.pacingSpikeTriggerFrames())
          .append(",")
          .append(OptimiumMetrics.uploadOverBudgetFrames());
    }
}
