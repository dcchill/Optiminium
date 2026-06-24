package net.optiminium.optimization;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

public class OptimiumSettings {
    private static final String CONFIG_FILE = "config/optiminium.properties";
    private static volatile boolean adaptiveQualityEnabled = true;          // 1.7
    private static volatile int spikeTriggerPercent = 20;                  // 1.8
    private static volatile int denseBlockEntityThreshold = 200;           // 1.9
    private static volatile boolean graphicsEffectCulling = true;          // 1.10

    public static boolean isAdaptiveQualityEnabled() { return adaptiveQualityEnabled; }
    public static void setAdaptiveQualityEnabled(boolean enabled) {
        adaptiveQualityEnabled = enabled;
        save();
    }

    public static int getSpikeTriggerPercent() { return spikeTriggerPercent; }
    public static void setSpikeTriggerPercent(int percent) {
        spikeTriggerPercent = clamp(percent, 1, 100);
        save();
    }

    public static int getDenseBlockEntityThreshold() { return denseBlockEntityThreshold; }
    public static void setDenseBlockEntityThreshold(int threshold) {
        denseBlockEntityThreshold = clamp(threshold, 50, 500);
        save();
    }

    public static boolean isGraphicsEffectCullingEnabled() { return graphicsEffectCulling; }
    public static void setGraphicsEffectCulling(boolean enabled) {
        graphicsEffectCulling = enabled;
        save();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static void load() {
        Properties properties = new Properties();
        try (FileInputStream fis = new FileInputStream(CONFIG_FILE)) {
            properties.load(fis);
        } catch (IOException e) {
            e.printStackTrace();
        }
        adaptiveQualityEnabled = Boolean.parseBoolean(
                properties.getProperty("adaptiveQualityEnabled",
                        Boolean.toString(adaptiveQualityEnabled)));
        spikeTriggerPercent = Integer.parseInt(
                properties.getProperty("spikeTriggerPercent",
                        Integer.toString(spikeTriggerPercent)));
        denseBlockEntityThreshold = Integer.parseInt(
                properties.getProperty("denseBlockEntityThreshold",
                        Integer.toString(denseBlockEntityThreshold)));
        graphicsEffectCulling = Boolean.parseBoolean(
                properties.getProperty("graphicsEffectCulling",
                        Boolean.toString(graphicsEffectCulling)));
    }

    public static void save() {
        Properties properties = new Properties();
        properties.setProperty("adaptiveQualityEnabled", Boolean.toString(adaptiveQualityEnabled));
        properties.setProperty("spikeTriggerPercent", Integer.toString(spikeTriggerPercent));
        properties.setProperty("denseBlockEntityThreshold", Integer.toString(denseBlockEntityThreshold));
        properties.setProperty("graphicsEffectCulling", Boolean.toString(graphicsEffectCulling));
        try (FileOutputStream fos = new FileOutputStream(CONFIG_FILE)) {
            properties.store(fos, "Optiminium Settings");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
