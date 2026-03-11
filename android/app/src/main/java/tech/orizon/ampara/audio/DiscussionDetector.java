package tech.orizon.ampara.audio;

import android.util.Log;
import java.util.LinkedList;
import java.util.Queue;

/**
 * Detects discussions/arguments based on audio characteristics
 */
public class DiscussionDetector {
    private static final String TAG = "DiscussionDetector";

    private final AudioTriggerConfig config;
    private final Queue<AggregationMetrics> window;
    private double noiseFloor = -50.0;
    private final AdaptiveNoiseFloor adaptiveNoiseFloor;
    private AdaptiveNoiseFloor.CalibrationCallback calibrationCallback;

    private enum State {
        IDLE,
        DISCUSSION_DETECTED,
        RECORDING_STARTED,
        DISCUSSION_ENDING,
        COOLDOWN
    }

    private State state = State.IDLE;
    private long stateStartTime = 0;
    private long silenceStartTime = 0; // Track continuous silence
    private boolean isManualRecording = false;
    private int processedCount = 0;

    public static class AggregationMetrics {
        public double rmsDb;
        public double zcr;
        public boolean isSpeech;
        public boolean isLoud;
        public long timestamp;

        public AggregationMetrics(double rmsDb, double zcr, boolean isSpeech, boolean isLoud) {
            this.rmsDb = rmsDb;
            this.zcr = zcr;
            this.isSpeech = isSpeech;
            this.isLoud = isLoud;
            this.timestamp = System.currentTimeMillis();
        }
    }

    public static class DetectionResult {
        public boolean shouldStartRecording = false;
        public boolean shouldStopRecording = false;
        public String reason = "";
        public double speechDensity = 0.0;
        public double loudDensity = 0.0;
    }

    public DiscussionDetector(AudioTriggerConfig config) {
        this.config = config;
        this.window = new LinkedList<>();

        // Initialize adaptive noise floor with config learning rate
        this.adaptiveNoiseFloor = new AdaptiveNoiseFloor(
                noiseFloor,
                config.noiseFloorLearningRate);
    }

    public void setCalibrationCallback(AdaptiveNoiseFloor.CalibrationCallback callback) {
        this.calibrationCallback = callback;
        this.adaptiveNoiseFloor.setCalibrationCallback(callback);

        Log.i(TAG, "DiscussionDetector initialized with adaptive noise floor");
    }

    /**
     * Process aggregated metrics and detect discussion
     */
    public DetectionResult process(AggregationMetrics metrics) {
        DetectionResult result = new DetectionResult();

        // Update adaptive noise floor (only during non-discussion periods)
        if (state == State.IDLE || state == State.COOLDOWN) {
            // Se ainda não estiver calibrado, aceitamos amostras muito mais livremente (até
            // -15dB)
            // Isso garante calibração mesmo em ambientes com ruído constante de fundo.
            boolean forceCalibrationSample = !adaptiveNoiseFloor.isCalibrated() && metrics.rmsDb < -15.0;

            if (forceCalibrationSample || (!metrics.isSpeech && !metrics.isLoud)) {
                adaptiveNoiseFloor.addSample(metrics.rmsDb);
            } else {
                // Log de diagnóstico crítico para entender o travamento
                Log.w(TAG, String.format(
                        "[CALIBRATION] Sample REJECTED: RMS=%.1f dB, Speech=%b, Loud=%b, State=%s, Target < -15.0",
                        metrics.rmsDb, metrics.isSpeech, metrics.isLoud, state.name()));
            }
        }

        processedCount++;

        // Use adaptive noise floor
        noiseFloor = adaptiveNoiseFloor.getNoiseFloor();

        // Add to window
        window.add(metrics);

        // Keep window size
        int maxWindowSize = config.getDiscussionWindowAggregations();
        while (window.size() > maxWindowSize) {
            window.poll();
        }

        // Calculate densities
        double speechDensity = calculateSpeechDensity();
        double loudDensity = calculateLoudDensity();

        result.speechDensity = speechDensity;
        result.loudDensity = loudDensity;

        long now = System.currentTimeMillis();
        long timeInState = now - stateStartTime;

        // State machine
        switch (state) {
            case IDLE:
                // Check if discussion started
                if (speechDensity >= config.speechDensityMin &&
                        loudDensity >= config.loudDensityMin) {
                    Log.d(TAG, String.format("Discussion detected! Speech: %.2f, Loud: %.2f",
                            speechDensity, loudDensity));
                    state = State.DISCUSSION_DETECTED;
                    stateStartTime = now;
                }
                break;

            case DISCUSSION_DETECTED:
                // Wait for start hold period
                if (timeInState >= config.startHoldSeconds * 1000) {
                    // Still discussing after hold period - start recording
                    if (speechDensity >= config.speechDensityMin &&
                            loudDensity >= config.loudDensityMin) {
                        Log.d(TAG, "Discussion confirmed after hold period - starting recording");
                        result.shouldStartRecording = true;
                        result.reason = "discussion_confirmed";
                        state = State.RECORDING_STARTED;
                        stateStartTime = now;
                    } else {
                        // False alarm - back to idle
                        Log.d(TAG, "Discussion ended before hold period - false alarm");
                        state = State.IDLE;
                        stateStartTime = now;
                    }
                } else {
                    // Check if discussion ended prematurely
                    if (speechDensity < config.speechDensityEnd &&
                            loudDensity < config.loudDensityEnd) {
                        Log.d(TAG, "Discussion ended during hold period");
                        state = State.IDLE;
                        stateStartTime = now;
                        isManualRecording = false; // Reset if aborted by silence too soon
                    }
                }
                break;

            case RECORDING_STARTED:
                // Check if discussion ended
                if (speechDensity < config.speechDensityEnd &&
                        loudDensity < config.loudDensityEnd) {
                    // Start tracking silence if not already
                    if (silenceStartTime == 0) {
                        silenceStartTime = now;
                        Log.d(TAG, "Silence detected - starting 10s confirmation timer");
                    }

                    // Check if silence lasted 10s
                    long silenceDuration = now - silenceStartTime;
                    if (silenceDuration >= config.silenceDecaySeconds * 1000) {
                        int countdownSeconds = isManualRecording ? 120 : config.endHoldSeconds;
                        Log.d(TAG, String.format("Discussion ending confirmed (%ds silence) - starting %ds countdown",
                                silenceDuration / 1000, countdownSeconds));
                        state = State.DISCUSSION_ENDING;
                        stateStartTime = now;
                        silenceStartTime = 0;
                    }
                } else {
                    // Só interrompe silêncio se fala for forte (>=65% na janela)
                    // Fala fraca/moderada não deve resetar o timer de silêncio contínuo
                    if (speechDensity >= 0.65 && silenceStartTime != 0) {
                        Log.d(TAG, String.format("Silence interrupted by strong speech (%.0f%%) - resetting timer",
                                speechDensity * 100));
                        silenceStartTime = 0;
                    }
                }
                break;

            case DISCUSSION_ENDING:
                // In countdown period
                if (speechDensity < config.speechDensityEnd &&
                        loudDensity < config.loudDensityEnd) {

                    int requiredHoldSeconds = isManualRecording ? 120 : config.endHoldSeconds;

                    // Still quiet - check if elapsed
                    if (timeInState >= requiredHoldSeconds * 1000) {
                        Log.d(TAG, String.format("Discussion ended after %ds countdown - stopping recording",
                                requiredHoldSeconds));
                        result.shouldStopRecording = true;
                        result.reason = "discussion_ended";
                        state = State.COOLDOWN;
                        stateStartTime = now;
                        isManualRecording = false; // Reset on completion
                    }
                } else {
                    // Só cancela countdown se fala for forte (>=65%)
                    if (speechDensity >= 0.65) {
                        Log.d(TAG, String.format("Discussion resumed (%.0f%% speech) - cancelling countdown",
                                speechDensity * 100));
                        state = State.RECORDING_STARTED;
                        stateStartTime = now;
                    }
                }
                break;

            case COOLDOWN:
                // Wait for cooldown period
                if (timeInState >= config.cooldownSeconds * 1000) {
                    Log.d(TAG, "Cooldown period ended - ready for new detection");
                    state = State.IDLE;
                    stateStartTime = now;
                }
                break;
        }

        return result;
    }

    private double calculateSpeechDensity() {
        if (window.isEmpty())
            return 0.0;

        int speechCount = 0;
        for (AggregationMetrics m : window) {
            if (m.isSpeech)
                speechCount++;
        }

        return (double) speechCount / window.size();
    }

    private double calculateLoudDensity() {
        if (window.isEmpty())
            return 0.0;

        int loudCount = 0;
        for (AggregationMetrics m : window) {
            if (m.isLoud)
                loudCount++;
        }

        return (double) loudCount / window.size();
    }

    public void reset() {
        window.clear();
        state = State.IDLE;
        stateStartTime = System.currentTimeMillis();
        silenceStartTime = 0;
        isManualRecording = false;
        noiseFloor = -50.0;
        adaptiveNoiseFloor.reset();
        Log.i(TAG, "DiscussionDetector hard reset (including adaptive noise floor)");
    }

    /**
     * Soft reset the detector state (clears window and resets to IDLE)
     * but KEEPS the adapted noise floor calibration intact.
     * Prevents false triggers in noisy environments after stopping a
     * panic/recording.
     */
    public void resetDetectionState() {
        window.clear();
        state = State.IDLE;
        stateStartTime = System.currentTimeMillis();
        silenceStartTime = 0;
        isManualRecording = false;
        Log.i(TAG, String.format("DiscussionDetector soft reset (Preserved noise floor at %.1f dB)", noiseFloor));
    }

    public State getState() {
        return state;
    }

    public String getStateString() {
        return state.name();
    }

    public double getNoiseFloor() {
        return noiseFloor;
    }

    /**
     * Get current calibration status
     * 
     * @return true if noise floor is calibrated
     */
    public boolean isCalibrated() {
        return adaptiveNoiseFloor.isCalibrated();
    }

    /**
     * Force state to RECORDING_STARTED (called when recording starts externally)
     * This prevents false alarm when simulated silence aggregations begin
     * 
     * @param isManual true if this recording was started by the manual button
     */
    public void forceRecordingStarted(boolean isManual) {
        state = State.RECORDING_STARTED;
        stateStartTime = System.currentTimeMillis();
        silenceStartTime = 0;
        this.isManualRecording = isManual;
        Log.i(TAG, "State forced to RECORDING_STARTED (isManual=" + isManual + ")");
    }

    // Getters para tela técnica de debug
    /** Tempo no estado atual em milissegundos */
    public long getTimeInStateMs() {
        return System.currentTimeMillis() - stateStartTime;
    }

    /** Duração de silêncio contínuo durante gravação (ms), 0 se não em silêncio */
    public long getContinuousSilenceMs() {
        if (silenceStartTime == 0)
            return 0;
        return System.currentTimeMillis() - silenceStartTime;
    }

    /** Se a gravação atual é manual */
    public boolean getIsManualRecording() {
        return isManualRecording;
    }

    /** Threshold de confirmação para iniciar gravação (segundos) */
    public int getStartHoldSeconds() {
        return config.startHoldSeconds;
    }

    /** Threshold de countdown para parar gravação (segundos) */
    public int getEndHoldSeconds() {
        return isManualRecording ? 120 : config.endHoldSeconds;
    }

    /** Período de silêncio necessário antes de iniciar countdown (segundos) */
    public int getSilenceDecaySeconds() {
        return config.silenceDecaySeconds;
    }

    /** Período de cooldown após gravação parar (segundos) */
    public int getCooldownSeconds() {
        return config.cooldownSeconds;
    }

    /** Threshold mínimo de fala para detectar (início) */
    public double getSpeechDensityMin() {
        return config.speechDensityMin;
    }

    /** Threshold mínimo de volume para detectar (início) */
    public double getLoudDensityMin() {
        return config.loudDensityMin;
    }

    /** Threshold de fala para considerar silêncio (fim) */
    public double getSpeechDensityEnd() {
        return config.speechDensityEnd;
    }

    /** Threshold de volume para considerar silêncio (fim) */
    public double getLoudDensityEnd() {
        return config.loudDensityEnd;
    }
}
