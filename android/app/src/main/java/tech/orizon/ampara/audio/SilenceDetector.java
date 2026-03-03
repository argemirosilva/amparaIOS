package tech.orizon.ampara.audio;

import android.util.Log;

/**
 * Silence Detector
 * Detects prolonged silence to automatically stop recording
 */
public class SilenceDetector {
    private static final String TAG = "SilenceDetector";
    private static final long SILENCE_TIMEOUT_MS = 600000; // 10 minutes
    private static final double SILENCE_THRESHOLD_DB = -40.0; // dB threshold for silence
    
    private long lastSoundTime;
    private boolean isSilent = false;
    
    public SilenceDetector() {
        reset();
    }
    
    /**
     * Process audio frame and update silence state
     * @param rmsDb RMS level in dB
     * @return true if silence timeout reached
     */
    public boolean processFrame(double rmsDb) {
        long now = System.currentTimeMillis();
        
        if (rmsDb > SILENCE_THRESHOLD_DB) {
            // Sound detected
            lastSoundTime = now;
            if (isSilent) {
                isSilent = false;
                Log.d(TAG, "Sound detected, silence ended");
            }
        } else {
            // Silence detected
            if (!isSilent) {
                isSilent = true;
                Log.d(TAG, "Silence started");
            }
        }
        
        // Check if silence timeout reached
        long silenceDuration = now - lastSoundTime;
        if (silenceDuration >= SILENCE_TIMEOUT_MS) {
            Log.i(TAG, String.format("Silence timeout reached: %.1f minutes", 
                silenceDuration / 60000.0));
            return true;
        }
        
        return false;
    }
    
    /**
     * Reset silence detector
     */
    public void reset() {
        lastSoundTime = System.currentTimeMillis();
        isSilent = false;
    }
    
    /**
     * Get current silence duration in milliseconds
     */
    public long getSilenceDuration() {
        if (isSilent) {
            return System.currentTimeMillis() - lastSoundTime;
        }
        return 0;
    }
    
    /**
     * Check if currently in silence
     */
    public boolean isSilent() {
        return isSilent;
    }
}
