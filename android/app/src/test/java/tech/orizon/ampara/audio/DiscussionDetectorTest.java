package tech.orizon.ampara.audio;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DiscussionDetectorTest {

    private AudioTriggerConfig config;
    private DiscussionDetector detector;

    @Before
    public void setUp() {
        config = new AudioTriggerConfig();
        config.discussionWindowSeconds = 10;
        config.aggregationMs = 1000;
        config.startHoldSeconds = 6;
        config.endHoldSeconds = 30;
        config.silenceDecaySeconds = 10;
        config.speechDensityMin = 0.65;
        config.loudDensityMin = 0.30;
        config.speechDensityEnd = 0.10;
        config.loudDensityEnd = 0.09;

        detector = new DiscussionDetector(config);
    }

    private DiscussionDetector.DetectionResult feedAggregations(int count, double rmsDb, boolean isSpeech, boolean isLoud) {
        DiscussionDetector.DetectionResult lastResult = null;
        for (int i = 0; i < count; i++) {
            lastResult = detector.process(new DiscussionDetector.AggregationMetrics(rmsDb, 0.0, isSpeech, isLoud));
            try {
                Thread.sleep(1005); 
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        return lastResult;
    }

    @Test
    public void testDiscussionDetectionThresholds() {
        // IDLE -> DISCUSSION_DETECTED with 1 sample (100% density in size 1 window)
        feedAggregations(1, -10.0, true, true);
        assertEquals("DISCUSSION_DETECTED", detector.getStateString());
        
        // Wait for startHoldSeconds (6 seconds) to transition to RECORDING_STARTED
        DiscussionDetector.DetectionResult result = feedAggregations(6, -10.0, true, true);
        
        assertTrue("Recording should start", result.shouldStartRecording);
        assertEquals("RECORDING_STARTED", detector.getStateString());
    }

    @Test
    public void testStrongSpeechInterruptsSilence() {
        // Setup to RECORDING_STARTED
        feedAggregations(8, -10.0, true, true);
        assertEquals("RECORDING_STARTED", detector.getStateString());
        
        // Feed silence (10 iterations) to push speech density to 0%
        feedAggregations(10, -60.0, false, false);
        
        long firstContinuousSilence = detector.getContinuousSilenceMs();
        assertTrue("Silence timer should have started: " + firstContinuousSilence, firstContinuousSilence > 0);
        
        // Now interrupt with strong speech for >= 0.65 density
        // Window currently has 10 silence. We feed 7 speech items.
        feedAggregations(7, -10.0, true, true);
        
        assertEquals("RECORDING_STARTED", detector.getStateString());
        assertEquals("Silence timer should be reset to 0 by strong speech", 0, detector.getContinuousSilenceMs());
    }

    @Test
    public void testStrongSpeechCancelsEndingCountdown() {
        // Setup to RECORDING_STARTED
        feedAggregations(8, -10.0, true, true);
        assertEquals("RECORDING_STARTED", detector.getStateString());
        
        // Feed 10 silence to empty window + 10 silence to trigger 10s silenceDecaySeconds
        feedAggregations(20, -60.0, false, false);
        assertEquals("DISCUSSION_ENDING", detector.getStateString());
        
        // In countdown. Now resume strong speech (needs >= 65% in the 10s window).
        // Since we fed 20 silence, density is 0%. We feed 7 speech.
        feedAggregations(7, -10.0, true, true);
        
        assertEquals("State should be back to RECORDING_STARTED after strong speech cancels ending countdown", "RECORDING_STARTED", detector.getStateString());
        assertEquals("Timer should be reset and state back to tracking", 0, detector.getContinuousSilenceMs());
    }

    @Test
    public void testAdaptiveNoiseFloorCalibration() {
        assertFalse(detector.isCalibrated());
        assertEquals(-50.0, detector.getNoiseFloor(), 0.01);
        
        for (int i = 0; i < 50; i++) {
            detector.process(new DiscussionDetector.AggregationMetrics(-20.0, 0.0, false, false));
        }
        
        assertTrue("Should be calibrated after receiving enough valid samples", detector.isCalibrated());
        assertTrue("Noise floor should adapt upwards towards -20dB", detector.getNoiseFloor() > -45.0);
    }
}
