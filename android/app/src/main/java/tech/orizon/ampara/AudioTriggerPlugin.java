package tech.orizon.ampara.plugins;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;

import tech.orizon.ampara.AudioTriggerService;

/**
 * Capacitor plugin to control native AudioTrigger service
 */
@CapacitorPlugin(name = "AudioTriggerNative")
public class AudioTriggerPlugin extends Plugin {
    private static final String TAG = "AudioTriggerPlugin";

    private BroadcastReceiver eventReceiver;
    private static boolean serviceRunning = false;

    @Override
    public void load() {
        super.load();

        // Register broadcast receiver for events from native service
        eventReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String event = intent.getStringExtra("event");

                Log.d(TAG, "Received event from native: " + event);

                // Notify JavaScript
                JSObject ret = new JSObject();
                ret.put("event", event);
                ret.put("timestamp", intent.getLongExtra("timestamp", 0));

                // Add event-specific fields
                if (intent.hasExtra("reason")) {
                    ret.put("reason", intent.getStringExtra("reason"));
                }
                if (intent.hasExtra("sessionId")) {
                    ret.put("sessionId", intent.getStringExtra("sessionId"));
                }
                if (intent.hasExtra("segmentIndex")) {
                    ret.put("segmentIndex", intent.getIntExtra("segmentIndex", 0));
                }
                if (intent.hasExtra("pending")) {
                    ret.put("pending", intent.getIntExtra("pending", 0));
                }
                if (intent.hasExtra("startedAt")) {
                    ret.put("startedAt", intent.getLongExtra("startedAt", 0));
                }
                if (intent.hasExtra("success")) {
                    ret.put("success", intent.getIntExtra("success", 0));
                }
                if (intent.hasExtra("failure")) {
                    ret.put("failure", intent.getIntExtra("failure", 0));
                }
                if (intent.hasExtra("isCalibrated")) {
                    ret.put("isCalibrated", intent.getBooleanExtra("isCalibrated", false));
                }
                if (intent.hasExtra("isRecording")) {
                    ret.put("isRecording", intent.getBooleanExtra("isRecording", false));
                }
                if (intent.hasExtra("status")) {
                    ret.put("status", intent.getStringExtra("status"));
                }
                if (intent.hasExtra("noiseFloorDb")) {
                    ret.put("noiseFloorDb", intent.getDoubleExtra("noiseFloorDb", 0.0));
                }
                // Audio metrics fields
                if (intent.hasExtra("rmsDb")) {
                    ret.put("rmsDb", intent.getDoubleExtra("rmsDb", 0.0));
                }
                if (intent.hasExtra("zcr")) {
                    ret.put("zcr", intent.getDoubleExtra("zcr", 0.0));
                }
                if (intent.hasExtra("isSpeech")) {
                    ret.put("isSpeech", intent.getBooleanExtra("isSpeech", false));
                }
                if (intent.hasExtra("isLoud")) {
                    ret.put("isLoud", intent.getBooleanExtra("isLoud", false));
                }
                if (intent.hasExtra("state")) {
                    ret.put("state", intent.getStringExtra("state"));
                }
                if (intent.hasExtra("score")) {
                    ret.put("score", intent.getDoubleExtra("score", 0.0));
                }

                notifyListeners("audioTriggerEvent", ret);
            }
        };

        IntentFilter filter = new IntentFilter("tech.orizon.ampara.AUDIO_TRIGGER_EVENT");
        getContext().registerReceiver(eventReceiver, filter, Context.RECEIVER_NOT_EXPORTED);

        Log.d(TAG, "AudioTriggerPlugin loaded and receiver registered");
    }

    @PluginMethod
    public void start(PluginCall call) {
        try {
            // IDEMPOTENT: If service already running, just return success
            if (serviceRunning) {
                Log.d(TAG, "AudioTrigger service already running, skipping start (idempotent)");
                JSObject ret = new JSObject();
                ret.put("success", true);
                ret.put("alreadyRunning", true);
                call.resolve(ret);
                return;
            }

            Intent intent = new Intent(getContext(), AudioTriggerService.class);

            // Pass configuration if provided
            JSObject config = call.getObject("config");
            if (config != null) {
                intent.putExtra("config", config.toString());
                Log.d(TAG, "Starting AudioTrigger service with config: " + config.toString());
            }

            // Use startForegroundService on Android 8+ to ensure service runs in background
            // IMPORTANT: This must be called while app is in FOREGROUND (eligible state)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                Log.i(TAG, "NATIVE_START_REQUEST: Starting foreground service (Android 8+)");
                getContext().startForegroundService(intent);
                Log.i(TAG, "NATIVE_START_SENT: startForegroundService called");
            } else {
                getContext().startService(intent);
                Log.d(TAG, "AudioTrigger service started");
            }

            serviceRunning = true;

            JSObject ret = new JSObject();
            ret.put("success", true);
            ret.put("alreadyRunning", false);
            call.resolve(ret);

        } catch (Exception e) {
            Log.e(TAG, "NATIVE_START_ERROR: " + e.getMessage(), e);
            call.reject("Failed to start AudioTrigger service: " + e.getMessage());
        }
    }

    @PluginMethod
    public void updateConfig(PluginCall call) {
        try {
            JSObject config = call.getObject("config");
            if (config == null) {
                call.reject("Config is required");
                return;
            }

            Intent intent = new Intent(getContext(), AudioTriggerService.class);
            intent.setAction("UPDATE_CONFIG");
            intent.putExtra("config", config.toString());

            getContext().startService(intent);

            Log.d(TAG, "AudioTrigger config updated: " + config.toString());

            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);

        } catch (Exception e) {
            Log.e(TAG, "Error updating AudioTrigger config", e);
            call.reject("Failed to update config: " + e.getMessage());
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        try {
            Intent intent = new Intent(getContext(), AudioTriggerService.class);
            getContext().stopService(intent);

            serviceRunning = false;
            Log.d(TAG, "AudioTrigger service stopped");

            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);

        } catch (Exception e) {
            Log.e(TAG, "Error stopping AudioTrigger service", e);
            call.reject("Failed to stop AudioTrigger service: " + e.getMessage());
        }
    }

    @PluginMethod
    public void isRunning(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("isRunning", serviceRunning);
        call.resolve(ret);
    }

    @PluginMethod
    public void getStatus(PluginCall call) {
        try {
            // Request current status from service (will trigger calibrationStatus
            // broadcast)
            Intent intent = new Intent(getContext(), AudioTriggerService.class);
            intent.setAction("GET_STATUS");
            getContext().startService(intent);

            Log.d(TAG, "Status request sent");

            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);

        } catch (Exception e) {
            Log.e(TAG, "Error requesting status", e);
            call.reject("Failed to get status: " + e.getMessage());
        }
    }

    @PluginMethod
    public void startRecording(PluginCall call) {
        try {
            Intent intent = new Intent(getContext(), AudioTriggerService.class);
            intent.setAction("START_RECORDING");

            // Pass credentials and origem from call
            String sessionToken = call.getString("sessionToken");
            String emailUsuario = call.getString("emailUsuario");
            String origemGravacao = call.getString("origemGravacao", "manual");

            if (sessionToken != null && emailUsuario != null) {
                intent.putExtra("sessionToken", sessionToken);
                intent.putExtra("emailUsuario", emailUsuario);
                intent.putExtra("origemGravacao", origemGravacao);

                Log.d(TAG, "Start recording with credentials: " + emailUsuario + ", origem: " + origemGravacao);
            } else {
                Log.w(TAG, "Start recording without credentials");
            }

            getContext().startService(intent);

            Log.d(TAG, "Start recording command sent");

            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);

        } catch (Exception e) {
            Log.e(TAG, "Error sending start recording command", e);
            call.reject("Failed to start recording: " + e.getMessage());
        }
    }

    @PluginMethod
    public void stopRecording(PluginCall call) {
        try {
            Intent intent = new Intent(getContext(), AudioTriggerService.class);
            intent.setAction("STOP_RECORDING");
            getContext().startService(intent);

            Log.d(TAG, "Stop recording command sent");

            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);

        } catch (Exception e) {
            Log.e(TAG, "Error sending stop recording command", e);
            call.reject("Failed to stop recording: " + e.getMessage());
        }
    }

    @PluginMethod
    public void activatePanic(PluginCall call) {
        try {
            String protocolNumber = call.getString("protocolNumber");
            String activationType = call.getString("activationType", "manual");

            Intent intent = new Intent(getContext(), AudioTriggerService.class);
            intent.setAction("PANIC_ACTIVATED");
            intent.putExtra("protocolNumber", protocolNumber);
            intent.putExtra("activationType", activationType);

            getContext().startService(intent);

            Log.d(TAG, "Activate panic command sent: " + protocolNumber);

            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        } catch (Exception e) {
            Log.e(TAG, "Error activating panic", e);
            call.reject("Failed to activate panic: " + e.getMessage());
        }
    }

    @PluginMethod
    public void deactivatePanic(PluginCall call) {
        try {
            String cancelType = call.getString("cancelType", "manual");

            Intent intent = new Intent(getContext(), AudioTriggerService.class);
            intent.setAction("PANIC_DEACTIVATED");
            intent.putExtra("cancelType", cancelType);

            getContext().startService(intent);

            Log.d(TAG, "Deactivate panic command sent");

            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        } catch (Exception e) {
            Log.e(TAG, "Error deactivating panic", e);
            call.reject("Failed to deactivate panic: " + e.getMessage());
        }
    }

    @PluginMethod
    public void reportStatus(PluginCall call) {
        String status = call.getString("status");
        Boolean isMonitoring = call.getBoolean("isMonitoring", true);
        String motivo = call.getString("motivo");

        if (status == null) {
            call.reject("Status is required");
            return;
        }

        Intent intent = new Intent(getContext(), AudioTriggerService.class);
        intent.setAction("REPORT_STATUS");
        intent.putExtra("status", status);
        intent.putExtra("isMonitoring", isMonitoring);
        intent.putExtra("motivo", motivo);

        getContext().startService(intent);

        JSObject ret = new JSObject();
        ret.put("success", true);
        call.resolve(ret);
    }

    @PluginMethod
    public void getPendingRecordings(PluginCall call) {
        try {
            File recordingsDir = new File(getContext().getFilesDir(), "recordings");
            if (!recordingsDir.exists()) {
                JSObject ret = new JSObject();
                ret.put("success", true);
                ret.put("recordings", new JSArray());
                call.resolve(ret);
                return;
            }

            File[] files = recordingsDir.listFiles((dir, name) -> name.endsWith(".ogg"));
            JSArray recordings = new JSArray();
            long currentTime = System.currentTimeMillis();

            if (files != null) {
                for (File file : files) {
                    // Ignore files modified less than 60 seconds ago.
                    // These are either currently being recorded or actively uploading.
                    if (currentTime - file.lastModified() < 60000) {
                        continue;
                    }

                    // Filename format: sessionID_segmentIndex.ogg
                    String name = file.getName();
                    String nameWithoutExt = name.substring(0, name.lastIndexOf('.'));
                    String[] parts = nameWithoutExt.split("_");

                    if (parts.length >= 2) {
                        JSObject rec = new JSObject();
                        rec.put("filePath", file.getAbsolutePath());
                        rec.put("fileName", name);
                        rec.put("fileSize", file.length());
                        rec.put("sessionId", parts[0] + "_" + parts[1]); // Handling session with underscore if needed,
                                                                         // or just parts[0]

                        // Robust segment index parsing
                        try {
                            String segmentPart = parts[parts.length - 1];
                            rec.put("segmentIndex", Integer.parseInt(segmentPart));
                        } catch (Exception e) {
                            rec.put("segmentIndex", 1);
                        }

                        // Use sessionId correctly based on NativeRecorder format:
                        // yyyyMMdd_HHmmss_001.ogg
                        if (parts.length >= 3) {
                            rec.put("sessionId", parts[0] + "_" + parts[1]);
                        }

                        rec.put("createdAt", file.lastModified());
                        recordings.put(rec);
                    }
                }
            }

            JSObject ret = new JSObject();
            ret.put("success", true);
            ret.put("recordings", recordings);
            call.resolve(ret);

        } catch (Exception e) {
            Log.e(TAG, "Error listing recordings", e);
            call.reject("Failed to list recordings: " + e.getMessage());
        }
    }

    @PluginMethod
    public void deleteRecording(PluginCall call) {
        String filePath = call.getString("filePath");
        if (filePath == null) {
            call.reject("filePath is required");
            return;
        }

        try {
            File file = new File(filePath);
            boolean deleted = false;
            if (file.exists()) {
                deleted = file.delete();
            }

            JSObject ret = new JSObject();
            ret.put("success", deleted);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Failed to delete file: " + e.getMessage());
        }
    }

    @PluginMethod
    public void uploadRecording(PluginCall call) {
        String filePath = call.getString("filePath");
        Integer segmentIndex = call.getInt("segmentIndex");
        String sessionId = call.getString("sessionId");
        String origemGravacao = call.getString("origemGravacao", "manual");

        if (filePath == null || segmentIndex == null || sessionId == null) {
            call.reject("filePath, segmentIndex, and sessionId are required");
            return;
        }

        try {
            Intent intent = new Intent(getContext(), AudioTriggerService.class);
            intent.setAction("START_RECORDING"); // We use a trick: if we send START_RECORDING with extras but don't
                                                 // start actual recording if already busy?
            // Better yet, I should add a specific UPLOAD_FILE action to AudioTriggerService

            intent.setAction("UPLOAD_FILE");
            intent.putExtra("filePath", filePath);
            intent.putExtra("segmentIndex", segmentIndex);
            intent.putExtra("sessionId", sessionId);
            intent.putExtra("origemGravacao", origemGravacao);

            // Re-use current credentials from plugin call if available, otherwise service
            // uses its own
            String sessionToken = call.getString("sessionToken");
            String emailUsuario = call.getString("emailUsuario");
            if (sessionToken != null)
                intent.putExtra("sessionToken", sessionToken);
            if (emailUsuario != null)
                intent.putExtra("emailUsuario", emailUsuario);

            getContext().startService(intent);

            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Failed to trigger upload: " + e.getMessage());
        }
    }

    @Override
    protected void handleOnDestroy() {
        if (eventReceiver != null) {
            try {
                getContext().unregisterReceiver(eventReceiver);
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering receiver", e);
            }
        }
        super.handleOnDestroy();
    }
}
