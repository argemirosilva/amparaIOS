package tech.orizon.ampara.audio;

import android.content.Context;
import android.util.Log;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Upload Queue with retry mechanism
 * Manages pending uploads and retries failed ones
 */
public class UploadQueue {
    private static final String TAG = "UploadQueue";

    private Context context;
    private AudioUploader uploader;
    private BlockingQueue<UploadTask> queue;
    private Thread workerThread;
    // De-dupe in-flight tasks to avoid enqueuing the same file multiple times
    private final java.util.Set<String> inFlightPaths = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile boolean isRunning = false;
    private AtomicInteger pendingCount = new AtomicInteger(0);
    private AtomicInteger successCount = new AtomicInteger(0);
    private AtomicInteger failureCount = new AtomicInteger(0);

    public interface UploadProgressCallback {
        void onProgress(int pending, int success, int failure);
    }

    private UploadProgressCallback progressCallback;

    public static class UploadTask {
        String filePath;
        int segmentIndex;
        String sessionId;
        double latitude;
        double longitude;
        String origemGravacao;

        public UploadTask(String filePath, int segmentIndex, String sessionId,
                double latitude, double longitude, String origemGravacao) {
            this.filePath = filePath;
            this.segmentIndex = segmentIndex;
            this.sessionId = sessionId;
            this.latitude = latitude;
            this.longitude = longitude;
            this.origemGravacao = origemGravacao;
        }
    }

    public UploadQueue(Context context, AudioUploader uploader) {
        this.context = context;
        this.uploader = uploader;
        this.queue = new LinkedBlockingQueue<>();
    }

    /**
     * Set progress callback
     */
    public void setProgressCallback(UploadProgressCallback callback) {
        this.progressCallback = callback;
    }

    /**
     * Start upload worker
     */
    public void start() {
        if (isRunning) {
            Log.w(TAG, "Upload queue already running");
            return;
        }

        isRunning = true;
        workerThread = new Thread(this::processQueue);
        workerThread.start();

        Log.i(TAG, "Upload queue started");
    }

    /**
     * Stop upload worker
     */
    public void stop() {
        if (!isRunning)
            return;

        isRunning = false;
        if (workerThread != null) {
            workerThread.interrupt();
            try {
                workerThread.join(5000);
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping worker thread", e);
            }
        }

        Log.i(TAG, "Upload queue stopped");
    }

    /**
     * Add upload task to queue
     */
    public void enqueue(UploadTask task) {
        if (task == null || task.filePath == null) {
            Log.w(TAG, "Ignoring enqueue: null task/filePath");
            return;
        }

        // Prevent duplicate enqueues for the same file while it's already in-flight
        if (!inFlightPaths.add(task.filePath)) {
            Log.d(TAG, "Duplicate task ignored (already in-flight): " + task.filePath);
            return;
        }

        try {
            queue.put(task);
            pendingCount.incrementAndGet();
            notifyProgress();

            Log.d(TAG, String.format("Task enqueued: segment %d, queue size: %d",
                    task.segmentIndex, queue.size()));

        } catch (InterruptedException e) {
            inFlightPaths.remove(task.filePath);
            Log.e(TAG, "Error enqueueing task", e);
        }
    }

    /**
     * Process upload queue
     */
    private void processQueue() {
        while (isRunning) {
            UploadTask task = null;
            try {
                task = queue.take();

                Log.d(TAG, String.format("Processing upload: segment %d (session=%s)",
                        task.segmentIndex, task.sessionId));

                // Check if file still exists before attempting upload
                java.io.File audioFile = new java.io.File(task.filePath);
                if (!audioFile.exists()) {
                    Log.w(TAG, "File no longer exists, skipping: " + task.filePath);
                    inFlightPaths.remove(task.filePath);
                    pendingCount.decrementAndGet();
                    notifyProgress();
                    continue;
                }

                // Process task sequentially using sync method
                boolean success = uploader.uploadSegmentSync(
                        task.filePath,
                        task.segmentIndex,
                        task.sessionId,
                        task.latitude,
                        task.longitude,
                        task.origemGravacao);

                if (success) {
                    pendingCount.decrementAndGet();
                    successCount.incrementAndGet();
                    notifyProgress();

                    Log.i(TAG, String.format("Upload successful: segment %d", task.segmentIndex));

                    // Delete file on success
                    try {
                        java.io.File file = new java.io.File(task.filePath);
                        if (file.exists()) {
                            boolean deleted = file.delete();
                            if (deleted) {
                                Log.i(TAG, "File deleted successfully after upload: " + task.filePath);
                            } else {
                                Log.e(TAG, "Failed to delete file after successful upload (returns false): "
                                        + task.filePath);
                            }
                        } else {
                            Log.w(TAG, "File was already deleted by something else: " + task.filePath);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Exception while attempting to delete file after upload: " + task.filePath, e);
                    }
                } else {
                    // Failures already logged in uploader
                    pendingCount.decrementAndGet();
                    failureCount.incrementAndGet();
                    notifyProgress();

                    // RE-ENQUEUE POLICY: If it's a persistent failure (e.g. 5xx or repeated
                    // timeout),
                    // we might want to keep it on disk for next auto-scan.
                    // Since it's still on disk, it will be found by next scan or manual retry.
                    Log.w(TAG, "Task removed from memory queue but remains on disk for future retry: " + task.filePath);
                }

                // Task finished (success or failure) -> allow future retries
                inFlightPaths.remove(task.filePath);

            } catch (InterruptedException e) {
                if (!isRunning)
                    break;
                Log.e(TAG, "Queue processing interrupted", e);
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error in queue processing", e);
                if (task != null) {
                    if (task.filePath != null) {
                        inFlightPaths.remove(task.filePath);
                    }
                    pendingCount.decrementAndGet();
                    failureCount.incrementAndGet();
                    notifyProgress();
                }
            }
        }
    }

    /**
     * Notify progress callback
     */
    private void notifyProgress() {
        if (progressCallback != null) {
            progressCallback.onProgress(
                    pendingCount.get(),
                    successCount.get(),
                    failureCount.get());
        }
    }

    /**
     * Get queue statistics
     */
    public int getPendingCount() {
        return pendingCount.get();
    }

    public int getSuccessCount() {
        return successCount.get();
    }

    public int getFailureCount() {
        return failureCount.get();
    }

    /**
     * Reset statistics
     */
    public void resetStats() {
        successCount.set(0);
        failureCount.set(0);
    }

    /**
     * Delete all pending ogg files in recordings directory to avoid accumulation
     */
    public void clearPendingUploads() {
        try {
            java.io.File recordingsDir = new java.io.File(context.getFilesDir(), "recordings");
            if (!recordingsDir.exists()) {
                return;
            }

            java.io.File[] files = recordingsDir.listFiles((dir, name) -> name.endsWith(".ogg"));
            if (files == null || files.length == 0) {
                Log.i(TAG, "No pending files to clear");
                return;
            }

            Log.i(TAG, "Cleaning " + files.length + " pending files from previous sessions");

            for (java.io.File file : files) {
                try {
                    if (file.exists()) {
                        boolean deleted = file.delete();
                        if (deleted) {
                            Log.d(TAG, "Deleted pending file: " + file.getName());
                        } else {
                            Log.w(TAG, "Could not delete pending file: " + file.getName());
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error deleting file: " + file.getName(), e);
                }
            }
            Log.i(TAG, "Pending files cleanup complete");
        } catch (Exception e) {
            Log.e(TAG, "Error during pending files cleanup", e);
        }
    }

    /**
     * Scan recordings directory and enqueue any pending files
     * 
     * @deprecated Use clearPendingUploads instead as per user request to avoid
     *             accumulation
     */
    @Deprecated
    public void autoScanAndEnqueue() {
        // Keeping for reference but it won't be called anymore
    }

    /**
     * Scan recordings directory and enqueue files that are stuck (older than 60s)
     * To be called periodically for retry logic
     */
    public void retryPendingUploads(String defaultSessionId, String defaultOrigemGravacao) {
        try {
            java.io.File recordingsDir = new java.io.File(context.getFilesDir(), "recordings");
            if (!recordingsDir.exists()) {
                return;
            }

            java.io.File[] files = recordingsDir.listFiles((dir, name) -> name.endsWith(".ogg"));
            if (files == null || files.length == 0) {
                return;
            }

            long currentTime = System.currentTimeMillis();
            int enqueuedCount = 0;

            for (java.io.File file : files) {
                // Only retry files older than 60 seconds (not currently being written)
                if (currentTime - file.lastModified() < 60000) {
                    continue;
                }

                String filename = file.getName();
                String nameWithoutExt = filename.substring(0, filename.lastIndexOf('.'));
                String[] parts = nameWithoutExt.split("_");

                int segmentIndex = 1;
                String sessionId = defaultSessionId;

                if (parts.length >= 3) {
                    sessionId = parts[0] + "_" + parts[1];
                    try {
                        segmentIndex = Integer.parseInt(parts[parts.length - 1]);
                    } catch (Exception e) {
                        segmentIndex = 1;
                    }
                }

                enqueue(new UploadTask(
                        file.getAbsolutePath(),
                        segmentIndex,
                        sessionId,
                        0.0, // dummy lat
                        0.0, // dummy lng
                        defaultOrigemGravacao));
                enqueuedCount++;
            }

            if (enqueuedCount > 0) {
                Log.i(TAG, "Retrying " + enqueuedCount + " stuck upload(s)");
            }

        } catch (Exception e) {
            Log.e(TAG, "Error in retryPendingUploads", e);
        }
    }
}
