package tech.orizon.ampara.audio;

/**
 * Configuration for native audio trigger
 */
public class AudioTriggerConfig {
    // Audio capture settings
    public int sampleRate = 16000;
    public int frameMs = 25;
    public int aggregationMs = 1000;

    // Detection thresholds
    public double loudDeltaDb = 12.0;
    public double vadDeltaDb = 7.0;
    public double speechDensityMin = 0.65;
    public double loudDensityMin = 0.30;

    // Timing windows
    public int discussionWindowSeconds = 10;
    public int preTriggerSeconds = 3;
    public int startHoldSeconds = 6;
    public int endHoldSeconds = 30;
    public int cooldownSeconds = 45;

    // Noise floor learning
    public double noiseFloorLearningRate = 0.029;

    // Turn-taking detection
    public int turnTakingMin = 7;

    // End detection
    public double speechDensityEnd = 0.10;
    public double loudDensityEnd = 0.09;
    public int silenceDecaySeconds = 10;
    public double silenceDecayRate = 0.5;

    // ZCR thresholds for voice detection
    public double zcrMinVoice = 0.02;
    public double zcrMaxVoice = 0.35; // Increased from 0.2 to be more permissive

    /**
     * Get frame size in samples
     */
    public int getFrameSamples() {
        return (sampleRate * frameMs) / 1000;
    }

    /**
     * Get aggregation size in frames
     */
    public int getAggregationFrames() {
        return aggregationMs / frameMs;
    }

    /**
     * Get discussion window in aggregations
     */
    public int getDiscussionWindowAggregations() {
        return (discussionWindowSeconds * 1000) / aggregationMs;
    }
}
