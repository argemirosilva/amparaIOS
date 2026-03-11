package tech.orizon.ampara.audio;

import android.util.Log;

/**
 * Default configuration for Audio Trigger Detection
 * 
 * REGRA MESTRE: Estes valores são a ÚNICA fonte de thresholds.
 * NUNCA usar audio_trigger_config da API.
 * API só controla: monitoringEnabled + monitoringPeriods.
 */
public class AudioTriggerDefaults {

    /**
     * Get default AudioTriggerConfig with fixed thresholds
     * These values are hardcoded and NEVER change based on API response
     */
    public static AudioTriggerConfig getDefaultConfig() {
        AudioTriggerConfig config = new AudioTriggerConfig();

        // Audio capture settings
        config.sampleRate = 16000;
        config.frameMs = 25;
        config.aggregationMs = 1000;

        // Detection thresholds (FIXED - never from API)
        config.loudDeltaDb = 10.0;
        config.vadDeltaDb = 8.0;
        config.speechDensityMin = 0.35;
        config.loudDensityMin = 0.25;

        // Timing windows
        config.discussionWindowSeconds = 10;
        config.preTriggerSeconds = 5;
        config.startHoldSeconds = 6;
        config.endHoldSeconds = 60;
        config.cooldownSeconds = 60;

        // Noise floor learning
        config.noiseFloorLearningRate = 0.029;

        // Turn-taking detection
        config.turnTakingMin = 10;

        // End detection
        config.speechDensityEnd = 0.25;
        config.loudDensityEnd = 0.09;
        config.silenceDecaySeconds = 10;
        config.silenceDecayRate = 0.5;

        // ZCR thresholds for voice detection
        config.zcrMinVoice = 0.02;
        config.zcrMaxVoice = 0.22;

        return config;
    }

    /**
     * Log current configuration source
     */
    public static void logConfigSource(String tag) {
        AudioTriggerConfig config = getDefaultConfig();
        Log.i(tag, "[AudioTriggerDefaults] Thresholds source = LOCAL DEFAULTS (hardcoded)");
        Log.i(tag, String.format("[AudioTriggerDefaults] vadDeltaDb=%.1f, loudDeltaDb=%.1f",
                config.vadDeltaDb, config.loudDeltaDb));
        Log.i(tag, String.format("[AudioTriggerDefaults] speechDensityMin=%.2f, loudDensityMin=%.2f",
                config.speechDensityMin, config.loudDensityMin));
    }
}
