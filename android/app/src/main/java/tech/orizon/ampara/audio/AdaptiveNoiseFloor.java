package tech.orizon.ampara.audio;

import android.util.Log;
import java.util.Arrays;
import java.util.LinkedList;

/**
 * Adaptive Noise Floor Manager
 * 
 * Continuously learns the ambient noise level and adapts the noise floor
 * threshold.
 * Uses a rolling window of RMS values to calculate the median (robust to
 * outliers).
 * 
 * Key features:
 * - Fast adaptation (10-30 seconds)
 * - Continuous calibration (never stops)
 * - Only updates during non-discussion periods
 * - Uses median (robust to spikes)
 */
public class AdaptiveNoiseFloor {
    private static final String TAG = "AdaptiveNoiseFloor";

    // Rolling window size (in aggregations)
    // 30 aggregations = 30 seconds (assuming 1 aggregation per second)
    private static final int WINDOW_SIZE = 30;

    // Minimum samples before trusting the adaptive noise floor
    private static final int MIN_SAMPLES = 6;

    // Rolling buffer of RMS values (in dB)
    private LinkedList<Double> rmsBuffer;

    // Current adaptive noise floor
    private double noiseFloor;

    // Initial noise floor (from config or first measurement)
    private double initialNoiseFloor;

    // Learning rate (how fast to adapt)
    // 0.0 = never adapt, 1.0 = instant adapt
    private double learningRate;

    // Statistics
    private int totalSamples;
    private long lastLogTime;

    // Calibration callback
    private CalibrationCallback callback;
    private boolean wasCalibrated = false;

    public interface CalibrationCallback {
        void onCalibrationChanged(boolean isCalibrated);
    }

    public AdaptiveNoiseFloor(double initialNoiseFloor, double learningRate) {
        this.initialNoiseFloor = initialNoiseFloor;
        this.noiseFloor = initialNoiseFloor;
        this.learningRate = learningRate;
        this.rmsBuffer = new LinkedList<>();
        this.totalSamples = 0;
        this.lastLogTime = 0;
        this.wasCalibrated = false;

        Log.i(TAG, String.format("Initialized with initial noise floor: %.1f dB, learning rate: %.3f",
                initialNoiseFloor, learningRate));
    }

    public void setCalibrationCallback(CalibrationCallback callback) {
        this.callback = callback;
    }

    /**
     * Add a new RMS sample (only during non-discussion periods)
     * 
     * @param rmsDb Current RMS in dB
     */
    public void addSample(double rmsDb) {
        // Add to buffer
        rmsBuffer.add(rmsDb);
        totalSamples++;

        // Keep buffer size limited
        if (rmsBuffer.size() > WINDOW_SIZE) {
            rmsBuffer.removeFirst();
        }

        // Update noise floor if we have enough samples
        if (rmsBuffer.size() >= MIN_SAMPLES) {
            updateNoiseFloor();
        }

        // Check calibration status change
        boolean isNowCalibrated = isCalibrated();
        if (isNowCalibrated != wasCalibrated) {
            wasCalibrated = isNowCalibrated;
            if (callback != null) {
                callback.onCalibrationChanged(isNowCalibrated);
            }
            Log.i(TAG, "Calibration status changed: " + (isNowCalibrated ? "CALIBRATED" : "CALIBRATING"));
        }

        // Log periodically (every 30 samples = ~30 seconds)
        long now = System.currentTimeMillis();
        if (now - lastLogTime > 30000) {
            logStatistics();
            lastLogTime = now;
        }
    }

    /**
     * Calculate and update the adaptive noise floor
     * Uses median of the rolling window (robust to outliers)
     */
    private void updateNoiseFloor() {
        // Convert to array for sorting
        double[] samples = new double[rmsBuffer.size()];
        int i = 0;
        for (Double val : rmsBuffer) {
            samples[i++] = val;
        }

        // Sort to find median
        Arrays.sort(samples);

        // Calculate median
        double median;
        int mid = samples.length / 2;
        if (samples.length % 2 == 0) {
            median = (samples[mid - 1] + samples[mid]) / 2.0;
        } else {
            median = samples[mid];
        }

        // Apply learning rate for smooth transition
        double oldNoiseFloor = noiseFloor;
        noiseFloor = noiseFloor * (1.0 - learningRate) + median * learningRate;

        // Log significant changes
        if (Math.abs(noiseFloor - oldNoiseFloor) > 3.0) {
            Log.i(TAG, String.format("Noise floor adapted: %.1f dB → %.1f dB (median: %.1f dB)",
                    oldNoiseFloor, noiseFloor, median));
        }
    }

    /**
     * Get the current adaptive noise floor
     */
    public double getNoiseFloor() {
        return noiseFloor;
    }

    /**
     * Reset the adaptive noise floor (e.g., when environment changes drastically)
     */
    public void reset() {
        rmsBuffer.clear();
        noiseFloor = initialNoiseFloor;
        totalSamples = 0;
        Log.i(TAG, "Adaptive noise floor reset");
    }

    /**
     * Check if the noise floor is well-calibrated
     */
    public boolean isCalibrated() {
        return rmsBuffer.size() >= MIN_SAMPLES;
    }

    /**
     * Log current statistics
     */
    private void logStatistics() {
        if (rmsBuffer.isEmpty())
            return;

        double min = Double.MAX_VALUE;
        double max = Double.MIN_VALUE;
        double sum = 0;

        for (Double val : rmsBuffer) {
            min = Math.min(min, val);
            max = Math.max(max, val);
            sum += val;
        }

        double avg = sum / rmsBuffer.size();

        Log.i(TAG, String.format("[ADAPTIVE] NoiseFloor: %.1f dB | Window: %d samples | " +
                "Min: %.1f dB | Avg: %.1f dB | Max: %.1f dB | Total: %d samples",
                noiseFloor, rmsBuffer.size(), min, avg, max, totalSamples));
    }

    /**
     * Get statistics for debugging
     */
    public String getStatistics() {
        if (rmsBuffer.isEmpty()) {
            return String.format("NoiseFloor: %.1f dB (not calibrated)", noiseFloor);
        }

        return String.format("NoiseFloor: %.1f dB (calibrated with %d samples)",
                noiseFloor, rmsBuffer.size());
    }
}
