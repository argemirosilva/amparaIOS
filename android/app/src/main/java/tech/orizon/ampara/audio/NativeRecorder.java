package tech.orizon.ampara.audio;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Native Audio Recorder with 30-second segmentation
 * Records audio in background using MediaRecorder (OGG/Opus format)
 */
public class NativeRecorder {
    private static final String TAG = "NativeRecorder";
    private static final int SEGMENT_DURATION_MS = 30000; // 30 seconds

    private MediaRecorder mediaRecorder;
    private String currentFilePath;
    private String sessionId;
    private long sessionStartTime = 0;
    private int segmentIndex = 0;
    private boolean isRecording = false;
    private Context context;
    private Handler handler;
    private Runnable segmentRunnable;
    private SegmentCallback segmentCallback;

    public interface SegmentCallback {
        void onSegmentComplete(String filePath, int segmentIndex, String sessionId);
    }

    public NativeRecorder(Context context) {
        this.context = context;
        this.handler = new Handler(Looper.getMainLooper());
    }

    /**
     * Set callback for segment completion
     */
    public void setSegmentCallback(SegmentCallback callback) {
        this.segmentCallback = callback;
    }

    /**
     * Start recording audio with automatic segmentation
     * 
     * @return Session ID, or null if failed
     */
    public String startRecording() {
        if (isRecording) {
            Log.w(TAG, "Already recording");
            return sessionId;
        }

        // Generate session ID
        sessionId = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        sessionStartTime = System.currentTimeMillis();
        segmentIndex = 1;
        isRecording = true;

        Log.i(TAG, "Starting recording session: " + sessionId);

        // Start first segment
        startSegment();

        return sessionId;
    }

    /**
     * Start a new segment
     */
    private void startSegment() {
        if (!isRecording)
            return;

        try {
            // Create recordings directory
            File recordingsDir = new File(context.getFilesDir(), "recordings");
            if (!recordingsDir.exists()) {
                recordingsDir.mkdirs();
            }

            // Generate filename: session_segment.ogg
            String filename = String.format(Locale.US, "%s_%03d.ogg", sessionId, segmentIndex);
            File outputFile = new File(recordingsDir, filename);
            currentFilePath = outputFile.getAbsolutePath();

            // Initialize MediaRecorder for OGG/Opus
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);

            // Use OGG container with Opus codec (Android 10+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.OGG);
                mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS);
            } else {
                // Fallback for older Android (shouldn't happen, app requires Android 14+)
                Log.w(TAG, "OGG not supported, falling back to AAC");
                mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            }

            mediaRecorder.setAudioSamplingRate(16000); // 16 kHz
            mediaRecorder.setAudioChannels(1); // Mono
            mediaRecorder.setAudioEncodingBitRate(24000); // 24 kbps (good quality for speech)
            mediaRecorder.setOutputFile(currentFilePath);

            mediaRecorder.prepare();
            mediaRecorder.start();

            Log.i(TAG, String.format("Segment %d started: %s", segmentIndex, currentFilePath));

            // Schedule next segment
            segmentRunnable = new Runnable() {
                @Override
                public void run() {
                    if (isRecording) {
                        stopSegment();
                        segmentIndex++;
                        startSegment();
                    }
                }
            };
            handler.postDelayed(segmentRunnable, SEGMENT_DURATION_MS);

        } catch (IOException e) {
            Log.e(TAG, "Failed to start segment", e);
            releaseRecorder();
            isRecording = false;
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error starting segment", e);
            releaseRecorder();
            isRecording = false;
        }
    }

    /**
     * Stop current segment
     */
    private void stopSegment() {
        if (mediaRecorder == null)
            return;

        try {
            mediaRecorder.stop();
            String filePath = currentFilePath;
            int index = segmentIndex;

            releaseRecorder();

            // Verify file exists and has content
            File file = new File(filePath);
            if (file.exists() && file.length() > 0) {
                Log.i(TAG, String.format("Segment %d stopped: %s (%d bytes)",
                        index, filePath, file.length()));

                // Notify callback
                if (segmentCallback != null) {
                    segmentCallback.onSegmentComplete(filePath, index, sessionId);
                }
            } else {
                Log.e(TAG, "Segment file is empty or doesn't exist");
            }

        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to stop segment", e);
            releaseRecorder();
        }
    }

    /**
     * Stop recording completely
     * 
     * @return Session ID, or null if not recording
     */
    public String stopRecording() {
        if (!isRecording) {
            Log.w(TAG, "Not recording");
            return null;
        }

        Log.i(TAG, "Stopping recording session: " + sessionId);

        // Cancel scheduled segment
        if (segmentRunnable != null) {
            handler.removeCallbacks(segmentRunnable);
            segmentRunnable = null;
        }

        // Stop current segment
        stopSegment();

        isRecording = false;
        String completedSessionId = sessionId;
        sessionId = null;
        // Do not reset segmentIndex here so caller can read totalSegments
        // segmentIndex will be overwritten on next startRecording()

        return completedSessionId;
    }

    /**
     * Check if currently recording
     */
    public boolean isRecording() {
        return isRecording;
    }

    /**
     * Get current session ID
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * Get current session start time (ms)
     */
    public long getSessionStartTime() {
        return sessionStartTime;
    }

    /**
     * Get the maximum absolute amplitude that was sampled since the last call to
     * this method.
     * 
     * @return maximum amplitude (0-32767), or 0 if not recording
     */
    public int getMaxAmplitude() {
        if (mediaRecorder != null) {
            try {
                return mediaRecorder.getMaxAmplitude();
            } catch (Exception e) {
                Log.e(TAG, "Error getting max amplitude", e);
                return 0;
            }
        }
        return 0;
    }

    /**
     * Get current segment index
     */
    public int getSegmentIndex() {
        return segmentIndex;
    }

    /**
     * Release MediaRecorder resources
     */
    private void releaseRecorder() {
        if (mediaRecorder != null) {
            try {
                mediaRecorder.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing MediaRecorder", e);
            }
            mediaRecorder = null;
        }
    }

    /**
     * Cleanup on destroy
     */
    public void destroy() {
        if (isRecording) {
            stopRecording();
        }
        releaseRecorder();
    }
}
