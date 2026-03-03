package tech.orizon.ampara.audio;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;

/**
 * Native Audio Uploader
 * Uploads audio segments to API using multipart/form-data
 */
public class AudioUploader {
    private static final String TAG = "AudioUploader";
    private static final String API_URL = "https://uogenwcycqykfsuongrl.supabase.co/functions/v1/mobile-api";
    private static final String API_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InVvZ2Vud2N5Y3F5a2ZzdW9uZ3JsIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzA4Mjg2NjIsImV4cCI6MjA4NjQwNDY2Mn0.hncTs6DDS-sbb8sT_QBOBf1mTcTu0e_Pc5yXo4tHZwE";
    private static final int TIMEOUT_MS = 30000; // 30 seconds
    private static final int MAX_RETRIES = 3;

    private Context context;
    private String sessionToken;
    private String emailUsuario;
    private String deviceId;

    public interface UploadCallback {
        void onSuccess(int segmentIndex, String sessionId);

        void onFailure(int segmentIndex, String sessionId, String error);
    }

    public AudioUploader(Context context) {
        this.context = context;
        this.deviceId = getDeviceId();
        ensureCredentials();
    }

    /**
     * Set authentication credentials
     */
    public void setCredentials(String sessionToken, String emailUsuario) {
        this.sessionToken = sessionToken;
        this.emailUsuario = emailUsuario;

        // Persist for future recovery if needed
        persistCredentials(sessionToken, emailUsuario);
    }

    /**
     * Ensure credentials are loaded from SharedPreferences
     */
    public boolean ensureCredentials() {
        if (sessionToken != null && emailUsuario != null && deviceId != null && !deviceId.startsWith("native-")) {
            return true;
        }

        try {
            android.content.SharedPreferences prefs = context.getSharedPreferences(
                    "ampara_secure_storage", Context.MODE_PRIVATE);

            if (sessionToken == null) {
                sessionToken = prefs.getString("ampara_token", null);
            }

            if (deviceId == null || deviceId.startsWith("native-")) {
                deviceId = prefs.getString("ampara_device_id", deviceId);
            }

            if (emailUsuario == null) {
                String userJson = prefs.getString("ampara_user", null);
                if (userJson != null) {
                    JSONObject userObj = new JSONObject(userJson);
                    emailUsuario = userObj.optString("email", null);
                }
            }

            Log.d(TAG, "Credentials recovered: token=" + (sessionToken != null) +
                    ", email=" + emailUsuario + ", deviceId=" + deviceId);

            return sessionToken != null && emailUsuario != null;
        } catch (Exception e) {
            Log.e(TAG, "Error recovering credentials", e);
            return false;
        }
    }

    private void persistCredentials(String token, String email) {
        // Usually handled by JS side, but good to have a backup if we update them here
    }

    /**
     * Upload audio segment with retry
     */
    public void uploadSegment(
            String filePath,
            int segmentIndex,
            String sessionId,
            double latitude,
            double longitude,
            String origemGravacao,
            UploadCallback callback) {
        new Thread(() -> {
            try {
                boolean success = uploadSegmentSync(filePath, segmentIndex, sessionId, latitude, longitude,
                        origemGravacao);
                if (success) {
                    if (callback != null)
                        callback.onSuccess(segmentIndex, sessionId);
                } else {
                    if (callback != null)
                        callback.onFailure(segmentIndex, sessionId, "Upload failed");
                }
            } catch (Exception e) {
                if (callback != null)
                    callback.onFailure(segmentIndex, sessionId, e.getMessage());
            }
        }).start();
    }

    /**
     * Synchronous upload with retry logic
     */
    public boolean uploadSegmentSync(
            String filePath,
            int segmentIndex,
            String sessionId,
            double latitude,
            double longitude,
            String origemGravacao) throws Exception {

        ensureCredentials();

        int retries = 0;
        String lastError = null;

        while (retries < MAX_RETRIES) {
            try {
                // Check if file exists before each attempt
                File audioFile = new File(filePath);
                if (!audioFile.exists()) {
                    Log.e(TAG, "Cancelling upload: Audio file not found: " + filePath);
                    return false;
                }

                boolean success = uploadSegmentInternal(
                        filePath, segmentIndex, sessionId,
                        latitude, longitude, origemGravacao);

                if (success) {
                    Log.i(TAG, String.format("Segment %d uploaded successfully (attempt %d)",
                            segmentIndex, retries + 1));
                    return true;
                } else {
                    lastError = "Upload failed with non-200 response";
                }

            } catch (Exception e) {
                lastError = e.getMessage();
                Log.e(TAG, String.format("Upload attempt %d failed: %s", retries + 1, lastError));

                // If it's a file not found error, don't retry
                if (e.getMessage() != null && e.getMessage().contains("Audio file not found")) {
                    throw e;
                }
            }

            retries++;
            if (retries < MAX_RETRIES) {
                try {
                    Thread.sleep(2000 * retries); // Exponential backoff
                } catch (InterruptedException e) {
                    break;
                }
            }
        }

        Log.e(TAG, String.format("Segment %d upload failed after %d attempts: %s",
                segmentIndex, MAX_RETRIES, lastError));
        return false;
    }

    /**
     * Calculate audio file duration in seconds
     * Uses MediaMetadataRetriever to support multiple formats (OGG/Opus, WAV, MP3,
     * etc)
     * 
     * IMPORTANT: NativeRecorder generates OGG/Opus files (.ogg), NOT WAV files
     */
    private double calculateAudioDuration(File audioFile) {
        android.media.MediaMetadataRetriever retriever = null;
        try {
            // Wait a bit to ensure file is fully written to disk
            Thread.sleep(200);

            retriever = new android.media.MediaMetadataRetriever();
            retriever.setDataSource(audioFile.getAbsolutePath());

            // Get duration in milliseconds
            String durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationStr == null) {
                Log.w(TAG, "Could not extract duration from audio file");
                return 0.0;
            }

            long durationMs = Long.parseLong(durationStr);
            double durationSeconds = durationMs / 1000.0;

            Log.d(TAG, String.format("Audio duration calculated: %.2fs (size=%d bytes, format=OGG/Opus)",
                    durationSeconds, audioFile.length()));

            return durationSeconds;

        } catch (Exception e) {
            Log.e(TAG, "Error calculating audio duration: " + e.getMessage());
            // Fallback: estimate based on file size
            // OGG/Opus has variable bitrate, but roughly 16-32 kbps for speech
            // Assume 24 kbps average = 3000 bytes/second
            long fileSize = audioFile.length();
            double estimatedDuration = (double) fileSize / 3000.0;
            Log.w(TAG, String.format("Using estimated duration: %.2fs", estimatedDuration));
            return estimatedDuration;
        } finally {
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (Exception e) {
                    // Ignore
                }
            }
        }
    }

    /**
     * Internal upload implementation
     */
    private boolean uploadSegmentInternal(
            String filePath,
            int segmentIndex,
            String sessionId,
            double latitude,
            double longitude,
            String origemGravacao) throws Exception {

        File audioFile = new File(filePath);
        if (!audioFile.exists()) {
            throw new Exception("Audio file not found: " + filePath);
        }

        // Calculate actual duration from audio file (OGG/Opus)
        double durationSeconds = calculateAudioDuration(audioFile);

        // REGRA: Se for o primeiro segmento (1) e durar menos de 15 segundos,
        // enviar como segmento 0 para o servidor ignorar a gravação curta.
        int effectiveSegmentIndex = segmentIndex;
        if (segmentIndex == 1 && durationSeconds < 15.0) {
            Log.i(TAG, String.format("First segment too short (%.2fs < 15s). Sending as segment 0.", durationSeconds));
            effectiveSegmentIndex = 0;
        }

        String boundary = "----Boundary" + System.currentTimeMillis();

        HttpURLConnection connection = (HttpURLConnection) new URL(API_URL).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setDoInput(true);
        connection.setUseCaches(false);
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        connection.setRequestProperty("apikey", API_KEY);

        DataOutputStream output = new DataOutputStream(connection.getOutputStream());

        // Add form fields
        addFormField(output, boundary, "action", "receberAudioMobile");
        addFormField(output, boundary, "session_token", sessionToken);
        addFormField(output, boundary, "device_id", deviceId);
        addFormField(output, boundary, "email_usuario", emailUsuario);
        addFormField(output, boundary, "segmento_idx", String.valueOf(effectiveSegmentIndex));
        addFormField(output, boundary, "session_id", sessionId);
        addFormField(output, boundary, "duracao_segundos", String.valueOf(Math.round(durationSeconds)));
        addFormField(output, boundary, "tamanho_mb",
                String.format(java.util.Locale.US, "%.5f", (audioFile.length() / (1024.0 * 1024.0))));
        addFormField(output, boundary, "origem_gravacao", origemGravacao);

        // ISO 8601 Timestamp (matching JS)
        String isoTimestamp = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                .format(new java.util.Date());
        addFormField(output, boundary, "timestamp", isoTimestamp);

        // Add location if available
        if (latitude != 0 && longitude != 0) {
            addFormField(output, boundary, "latitude", String.valueOf(latitude));
            addFormField(output, boundary, "longitude", String.valueOf(longitude));
        }

        // Add timezone
        try {
            java.util.TimeZone tz = java.util.TimeZone.getDefault();
            String timezone = tz.getID();
            int offsetMillis = tz.getOffset(System.currentTimeMillis());
            int timezone_offset_minutes = offsetMillis / (1000 * 60);

            addFormField(output, boundary, "timezone", timezone);
            addFormField(output, boundary, "timezone_offset_minutes", String.valueOf(timezone_offset_minutes));

            Log.d(TAG, "[TZ] Attached to segment upload: " + timezone + ", offset: " + timezone_offset_minutes);
        } catch (Exception e) {
            Log.w(TAG, "Error adding timezone to segment upload", e);
        }

        // Add audio file
        addFileField(output, boundary, "audio", audioFile);

        // End of multipart
        output.writeBytes("--" + boundary + "--\r\n");
        output.flush();
        output.close();

        // Get response
        int responseCode = connection.getResponseCode();

        if (responseCode == HttpURLConnection.HTTP_OK) {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();

            Log.d(TAG, "Upload response: " + response.toString());
            return true;
        } else {
            // Read error response
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(connection.getErrorStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                StringBuilder response = new StringBuilder();
                String responseLine;
                while ((responseLine = br.readLine()) != null) {
                    response.append(responseLine.trim());
                }
                Log.e(TAG, "Upload failed with response code: " + responseCode + " - Error: " + response.toString());
            } catch (Exception e) {
                Log.e(TAG, "Upload failed with response code: " + responseCode + " (Could not read error stream)");
            }
            return false;
        }
    }

    /**
     * Add form field to multipart request
     */
    private void addFormField(DataOutputStream output, String boundary, String name, String value)
            throws Exception {
        output.writeBytes("--" + boundary + "\r\n");
        output.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n");
        output.writeBytes("\r\n");
        output.writeBytes(value + "\r\n");
    }

    /**
     * Add file field to multipart request
     */
    private void addFileField(DataOutputStream output, String boundary, String fieldName, File file)
            throws Exception {
        output.writeBytes("--" + boundary + "\r\n");
        output.writeBytes("Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" +
                file.getName() + "\"\r\n");
        output.writeBytes("Content-Type: audio/ogg\r\n");
        output.writeBytes("\r\n");

        FileInputStream fileInputStream = new FileInputStream(file);
        byte[] buffer = new byte[4096];
        int bytesRead;
        while ((bytesRead = fileInputStream.read(buffer)) != -1) {
            output.write(buffer, 0, bytesRead);
        }
        fileInputStream.close();

        output.writeBytes("\r\n");
    }

    /**
     * Notify server that recording session has started
     */
    public void notifyRecordingStarted(String sessionId, String origemGravacao) {
        new Thread(() -> {
            try {
                Log.i(TAG, String.format("Notifying server: recording started - session=%s, origem=%s",
                        sessionId, origemGravacao));
                Log.d(TAG, String.format("Credentials: token=%s, email=%s, deviceId=%s",
                        sessionToken != null ? "present" : "NULL",
                        emailUsuario != null ? emailUsuario : "NULL",
                        deviceId != null ? deviceId : "NULL"));

                JSONObject payload = new JSONObject();
                payload.put("action", "reportarStatusGravacao");
                payload.put("session_token", sessionToken);
                payload.put("device_id", deviceId);
                payload.put("email_usuario", emailUsuario);
                payload.put("status_gravacao", "iniciada");
                payload.put("session_id", sessionId);
                payload.put("origem_gravacao", origemGravacao);

                // ISO 8601 Timestamp
                String isoTimestamp = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                        java.util.Locale.US)
                        .format(new java.util.Date());
                payload.put("timestamp", isoTimestamp);

                // Add timezone
                try {
                    java.util.TimeZone tz = java.util.TimeZone.getDefault();
                    String timezone = tz.getID();
                    int offsetMillis = tz.getOffset(System.currentTimeMillis());
                    int timezone_offset_minutes = offsetMillis / (1000 * 60);

                    payload.put("timezone", timezone);
                    payload.put("timezone_offset_minutes", timezone_offset_minutes);

                    Log.d(TAG,
                            "[TZ] Attached to reportarStatusGravacao: " + timezone + ", offset: "
                                    + timezone_offset_minutes);
                } catch (Exception e) {
                    Log.w(TAG, "Error adding timezone to reportarStatusGravacao", e);
                }

                Log.d(TAG, "Payload: " + payload.toString());

                HttpURLConnection connection = (HttpURLConnection) new URL(API_URL).openConnection();
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setDoInput(true);
                connection.setConnectTimeout(TIMEOUT_MS);
                connection.setReadTimeout(TIMEOUT_MS);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("apikey", API_KEY);

                DataOutputStream output = new DataOutputStream(connection.getOutputStream());
                output.writeBytes(payload.toString());
                output.flush();
                output.close();

                int responseCode = connection.getResponseCode();

                if (responseCode == 200) {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(connection.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();

                    Log.i(TAG, String.format("Recording start notified successfully: %s",
                            response.toString()));
                } else {
                    // Read error response
                    try {
                        BufferedReader errorReader = new BufferedReader(
                                new InputStreamReader(connection.getErrorStream()));
                        StringBuilder errorResponse = new StringBuilder();
                        String line;
                        while ((line = errorReader.readLine()) != null) {
                            errorResponse.append(line);
                        }
                        errorReader.close();

                        Log.e(TAG, String.format("Failed to notify recording start: HTTP %d - %s",
                                responseCode, errorResponse.toString()));
                    } catch (Exception e) {
                        Log.e(TAG, String.format("Failed to notify recording start: HTTP %d",
                                responseCode));
                    }
                }

                connection.disconnect();

            } catch (Exception e) {
                Log.e(TAG, "Error notifying recording start", e);
            }
        }).start();
    }

    /**
     * Notify server that recording session is complete
     */
    public void notifyRecordingComplete(String sessionId, int totalSegments, String motivoParada) {
        new Thread(() -> {
            try {
                Log.i(TAG, String.format("Notifying server: recording complete - session=%s, segments=%d",
                        sessionId, totalSegments));
                Log.d(TAG, String.format("Credentials: token=%s, email=%s, deviceId=%s",
                        sessionToken != null ? "present" : "NULL",
                        emailUsuario != null ? emailUsuario : "NULL",
                        deviceId != null ? deviceId : "NULL"));

                JSONObject payload = new JSONObject();
                payload.put("action", "reportarStatusGravacao");
                payload.put("session_token", sessionToken);
                payload.put("device_id", deviceId);
                payload.put("email_usuario", emailUsuario);
                payload.put("status_gravacao", "finalizada");
                payload.put("session_id", sessionId);
                payload.put("total_segmentos", totalSegments);
                payload.put("motivo_parada", motivoParada);

                // ISO 8601 Timestamp
                String isoTimestamp = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                        java.util.Locale.US)
                        .format(new java.util.Date());
                payload.put("timestamp", isoTimestamp);

                // Add timezone
                try {
                    java.util.TimeZone tz = java.util.TimeZone.getDefault();
                    String timezone = tz.getID();
                    int offsetMillis = tz.getOffset(System.currentTimeMillis());
                    int timezone_offset_minutes = offsetMillis / (1000 * 60);

                    payload.put("timezone", timezone);
                    payload.put("timezone_offset_minutes", timezone_offset_minutes);

                    Log.d(TAG,
                            "[TZ] Attached to reportarStatusGravacao (complete): " + timezone + ", offset: "
                                    + timezone_offset_minutes);
                } catch (Exception e) {
                    Log.w(TAG, "Error adding timezone to reportarStatusGravacao (complete)", e);
                }

                Log.d(TAG, "Payload: " + payload.toString());

                HttpURLConnection connection = (HttpURLConnection) new URL(API_URL).openConnection();
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setDoInput(true);
                connection.setConnectTimeout(TIMEOUT_MS);
                connection.setReadTimeout(TIMEOUT_MS);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("apikey", API_KEY);

                DataOutputStream output = new DataOutputStream(connection.getOutputStream());
                output.writeBytes(payload.toString());
                output.flush();
                output.close();

                int responseCode = connection.getResponseCode();

                if (responseCode == 200) {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(connection.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();

                    Log.i(TAG, String.format("Recording completion notified successfully: %s",
                            response.toString()));
                } else {
                    // Read error response
                    try {
                        BufferedReader errorReader = new BufferedReader(
                                new InputStreamReader(connection.getErrorStream()));
                        StringBuilder errorResponse = new StringBuilder();
                        String line;
                        while ((line = errorReader.readLine()) != null) {
                            errorResponse.append(line);
                        }
                        errorReader.close();

                        Log.e(TAG, String.format("Failed to notify recording completion: HTTP %d - %s",
                                responseCode, errorResponse.toString()));
                    } catch (Exception e) {
                        Log.e(TAG, String.format("Failed to notify recording completion: HTTP %d",
                                responseCode));
                    }
                }

                connection.disconnect();

            } catch (Exception e) {
                Log.e(TAG, "Error notifying recording completion", e);
            }
        }).start();
    }

    /**
     * Report monitoring status (window start/end, service start/stop)
     * Payload structure based on user request:
     * {
     * "action": "reportarStatusMonitoramento",
     * "email_usuario": "...",
     * "device_id": "...",
     * "status_monitoramento": "...",
     * "is_monitoring": true/false,
     * "motivo": "..."
     * }
     */
    public void reportarStatusMonitoramento(String status, boolean isMonitoring, String motivo) {
        new Thread(() -> {
            try {
                ensureCredentials();
                Log.i(TAG, String.format("Reporting monitoring status: %s (is_monitoring=%b, motivo=%s)",
                        status, isMonitoring, motivo));

                JSONObject payload = new JSONObject();
                payload.put("action", "reportarStatusMonitoramento");
                // Note: sessionToken might be null if called before auth, but email_usuario
                // usually set
                payload.put("email_usuario", emailUsuario != null ? emailUsuario : "");

                // Ensure reliable device_id
                String persistDeviceId = getDeviceId();
                if (persistDeviceId == null) {
                    persistDeviceId = deviceId; // fallback to field
                }
                payload.put("device_id", persistDeviceId);

                payload.put("status_monitoramento", status);
                payload.put("is_monitoring", isMonitoring);
                payload.put("motivo", motivo);
                payload.put("timestamp", System.currentTimeMillis());

                Log.d(TAG, "Payload: " + payload.toString());

                HttpURLConnection connection = (HttpURLConnection) new URL(API_URL).openConnection();
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setDoInput(true);
                connection.setConnectTimeout(TIMEOUT_MS);
                connection.setReadTimeout(TIMEOUT_MS);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("apikey", API_KEY);

                DataOutputStream output = new DataOutputStream(connection.getOutputStream());
                output.writeBytes(payload.toString());
                output.flush();
                output.close();

                int responseCode = connection.getResponseCode();

                if (responseCode >= 200 && responseCode < 300) {
                    Log.i(TAG, "Monitoring status reported successfully");
                } else {
                    Log.e(TAG, String.format("Failed to report monitoring status: HTTP %d", responseCode));
                }

                connection.disconnect();

            } catch (Exception e) {
                Log.e(TAG, "Error reporting monitoring status", e);
            }
        }).start();
    }

    /**
     * Get or generate device ID
     * CRITICAL: Must use same SharedPreferences as KeepAlivePlugin
     */
    private String getDeviceId() {
        // Use same SharedPreferences as KeepAlivePlugin
        android.content.SharedPreferences prefs = context.getSharedPreferences(
                "ampara_secure_storage", Context.MODE_PRIVATE);

        String deviceId = prefs.getString("ampara_device_id", null);
        if (deviceId == null) {
            // Fallback: generate new ID (should not happen if KeepAlive started first)
            deviceId = UUID.randomUUID().toString();
            prefs.edit().putString("ampara_device_id", deviceId).apply();
            Log.w(TAG, "Device ID not found, generated new: " + deviceId);
        }

        return deviceId;
    }
}
