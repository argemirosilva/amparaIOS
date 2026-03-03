package tech.orizon.ampara.audio;

import android.util.Log;

/**
 * Digital Signal Processing utilities for audio analysis
 */
public class AudioDSP {
    private static final String TAG = "AudioDSP";
    
    /**
     * Calculate RMS (Root Mean Square) amplitude of audio samples
     * @param samples Audio samples (PCM 16-bit)
     * @param length Number of samples to process
     * @return RMS value in dB
     */
    public static double calculateRMS(short[] samples, int length) {
        double sum = 0.0;
        for (int i = 0; i < length; i++) {
            double normalized = samples[i] / 32768.0; // Normalize to [-1, 1]
            sum += normalized * normalized;
        }
        double rms = Math.sqrt(sum / length);
        
        // Convert to dB
        if (rms > 0) {
            return 20 * Math.log10(rms);
        } else {
            return -100; // Silence
        }
    }
    
    /**
     * Calculate Zero Crossing Rate (ZCR) - useful for voice detection
     * @param samples Audio samples
     * @param length Number of samples
     * @return ZCR value (0-1)
     */
    public static double calculateZCR(short[] samples, int length) {
        int crossings = 0;
        for (int i = 1; i < length; i++) {
            if ((samples[i] >= 0 && samples[i-1] < 0) || 
                (samples[i] < 0 && samples[i-1] >= 0)) {
                crossings++;
            }
        }
        return (double) crossings / (length - 1);
    }
    
    /**
     * Detect if audio contains speech-like characteristics
     * @param rmsDb RMS in dB
     * @param zcr Zero Crossing Rate
     * @param config Audio trigger configuration
     * @return true if likely speech
     */
    public static boolean isSpeechLike(double rmsDb, double zcr, AudioTriggerConfig config, double noiseFloor) {
        // Speech typically has:
        // - RMS above noise floor + VAD threshold (adaptive)
        // - ZCR within voice range (configurable)
        double vadThreshold = noiseFloor + config.vadDeltaDb;
        boolean hasEnergy = rmsDb > vadThreshold;
        boolean hasVoiceZCR = zcr >= config.zcrMinVoice && zcr <= config.zcrMaxVoice;
        return hasEnergy && hasVoiceZCR;
    }
    
    /**
     * Detect if audio is loud (potential argument/discussion)
     * @param rmsDb RMS in dB
     * @param threshold Loudness threshold in dB
     * @return true if loud
     */
    public static boolean isLoud(double rmsDb, double threshold) {
        return rmsDb > threshold;
    }
    
    /**
     * Apply exponential moving average for noise floor estimation
     * @param currentNoiseFloor Current noise floor estimate
     * @param newSample New RMS sample
     * @param learningRate Learning rate (0-1)
     * @return Updated noise floor
     */
    public static double updateNoiseFloor(double currentNoiseFloor, double newSample, double learningRate) {
        return currentNoiseFloor * (1 - learningRate) + newSample * learningRate;
    }
}
