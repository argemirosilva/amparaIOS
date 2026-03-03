package tech.orizon.ampara;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Native Panic State Manager
 * Persists panic state and ensures server notification even if JavaScript dies
 */
public class PanicManager {
    private static final String TAG = "PanicManager";
    private static final String PREFS_NAME = "ampara_panic_state";
    private static final String KEY_IS_ACTIVE = "is_panic_active";
    private static final String KEY_START_TIME = "panic_start_time";
    private static final String KEY_PROTOCOL_NUMBER = "protocol_number";
    private static final String KEY_ACTIVATION_TYPE = "activation_type";
    private static final String KEY_IS_CRITICAL = "is_critical_alert";

    private static final String API_URL = "https://uogenwcycqykfsuongrl.supabase.co/functions/v1/mobile-api";
    private static final String API_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InVvZ2Vud2N5Y3F5a2ZzdW9uZ3JsIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzA4Mjg2NjIsImV4cCI6MjA4NjQwNDY2Mn0.hncTs6DDS-sbb8sT_QBOBf1mTcTu0e_Pc5yXo4tHZwE";
    private static final int TIMEOUT_MS = 30000;

    private Context context;
    private SharedPreferences prefs;
    private String sessionToken;
    private String emailUsuario;
    private String deviceId;

    public PanicManager(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Set authentication credentials
     */
    public void setCredentials(String sessionToken, String emailUsuario, String deviceId) {
        this.sessionToken = sessionToken;
        this.emailUsuario = emailUsuario;
        this.deviceId = deviceId;
    }

    /**
     * Check if panic is currently active
     */
    public boolean isPanicActive() {
        return prefs.getBoolean(KEY_IS_ACTIVE, false);
    }

    /**
     * Get panic start timestamp
     */
    public long getPanicStartTime() {
        return prefs.getLong(KEY_START_TIME, 0);
    }

    /**
     * Get protocol number
     */
    public String getProtocolNumber() {
        return prefs.getString(KEY_PROTOCOL_NUMBER, null);
    }

    /**
     * Activate panic mode (called from JavaScript)
     */
    public void activatePanic(String protocolNumber, String activationType) {
        Log.i(TAG, String.format("Panic activated: protocol=%s, type=%s", protocolNumber, activationType));

        prefs.edit()
                .putBoolean(KEY_IS_ACTIVE, true)
                .putLong(KEY_START_TIME, System.currentTimeMillis())
                .putString(KEY_PROTOCOL_NUMBER, protocolNumber)
                .putString(KEY_ACTIVATION_TYPE, activationType)
                .commit(); // Use commit for immediate effect
    }

    /**
     * Set critical alert state (discussion detected)
     */
    public void setCriticalAlert(boolean active) {
        Log.i(TAG, "Critical alert state changed: " + active);
        prefs.edit().putBoolean(KEY_IS_CRITICAL, active).apply();
    }

    /**
     * Check if critical alert is active
     */
    public boolean isCriticalAlert() {
        return prefs.getBoolean(KEY_IS_CRITICAL, false);
    }

    /**
     * Deactivate panic mode (called from JavaScript or native)
     */
    public void deactivatePanic(String cancelType) {
        if (!isPanicActive()) {
            Log.w(TAG, "Panic not active, ignoring deactivation");
            return;
        }

        Log.i(TAG, String.format("Panic deactivated: type=%s", cancelType));

        // Notify server before clearing state
        notifyPanicCancellation(cancelType);

        // Clear state
        prefs.edit()
                .putBoolean(KEY_IS_ACTIVE, false)
                .remove(KEY_START_TIME)
                .remove(KEY_PROTOCOL_NUMBER)
                .remove(KEY_ACTIVATION_TYPE)
                .commit(); // Use commit for immediate effect
    }

    /**
     * Sync panic state from native to JavaScript
     * Called when JavaScript initializes after process restart
     */
    public JSONObject getPanicState() {
        try {
            JSONObject state = new JSONObject();
            state.put("isPanicActive", isPanicActive());
            state.put("isCriticalAlert", isCriticalAlert());
            state.put("panicStartTime", getPanicStartTime());
            state.put("protocolNumber", getProtocolNumber());

            return state;
        } catch (Exception e) {
            Log.e(TAG, "Error building panic state", e);
            return new JSONObject();
        }
    }

    /**
     * Notify server of panic cancellation
     */
    private void notifyPanicCancellation(String cancelType) {
        new Thread(() -> {
            try {
                Log.i(TAG, String.format("Notifying server: panic cancelled - type=%s", cancelType));

                JSONObject payload = new JSONObject();
                payload.put("action", "cancelarPanicoMobile");
                payload.put("session_token", sessionToken);
                payload.put("device_id", deviceId);
                payload.put("email_usuario", emailUsuario);
                payload.put("tipo_cancelamento", cancelType);
                payload.put("timestamp", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                        .format(new java.util.Date()));

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

                    Log.i(TAG, String.format("Panic cancellation notified successfully: %s",
                            response.toString()));
                } else {
                    Log.e(TAG, String.format("Failed to notify panic cancellation: HTTP %d",
                            responseCode));
                }

                connection.disconnect();

            } catch (Exception e) {
                Log.e(TAG, "Error notifying panic cancellation", e);
            }
        }).start();
    }

    /**
     * Check if panic has timed out (30 minutes)
     * Should be called periodically by AudioTriggerService
     */
    public boolean checkTimeout() {
        if (!isPanicActive()) {
            return false;
        }

        long startTime = getPanicStartTime();
        long elapsed = System.currentTimeMillis() - startTime;
        long sixtyMinutes = 60 * 60 * 1000;

        if (elapsed > sixtyMinutes) {
            Log.w(TAG, "Panic timeout reached (60 minutes), auto-cancelling");
            deactivatePanic("timeout");
            return true;
        }

        return false;
    }
}
