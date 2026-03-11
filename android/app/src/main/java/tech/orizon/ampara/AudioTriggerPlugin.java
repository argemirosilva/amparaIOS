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
                // Dados extras para tela técnica
                if (intent.hasExtra("speechDensity")) {
                    ret.put("speechDensity", intent.getDoubleExtra("speechDensity", 0.0));
                }
                if (intent.hasExtra("loudDensity")) {
                    ret.put("loudDensity", intent.getDoubleExtra("loudDensity", 0.0));
                }
                if (intent.hasExtra("noiseFloor")) {
                    ret.put("noiseFloor", intent.getDoubleExtra("noiseFloor", 0.0));
                }
                // Dados de silêncio para tela técnica
                if (intent.hasExtra("isSilent")) {
                    ret.put("isSilent", intent.getBooleanExtra("isSilent", false));
                }
                if (intent.hasExtra("silenceDurationMs")) {
                    ret.put("silenceDurationMs", intent.getLongExtra("silenceDurationMs", 0));
                }
                if (intent.hasExtra("silenceTimeoutMs")) {
                    ret.put("silenceTimeoutMs", intent.getLongExtra("silenceTimeoutMs", 0));
                }
                if (intent.hasExtra("silenceThresholdDb")) {
                    ret.put("silenceThresholdDb", intent.getDoubleExtra("silenceThresholdDb", 0.0));
                }
                if (intent.hasExtra("isRecording")) {
                    ret.put("isRecording", intent.getBooleanExtra("isRecording", false));
                }
                // Timers e contadores do DiscussionDetector
                if (intent.hasExtra("timeInStateMs")) {
                    ret.put("timeInStateMs", intent.getLongExtra("timeInStateMs", 0));
                }
                if (intent.hasExtra("continuousSilenceMs")) {
                    ret.put("continuousSilenceMs", intent.getLongExtra("continuousSilenceMs", 0));
                }
                if (intent.hasExtra("isManualRecording")) {
                    ret.put("isManualRecording", intent.getBooleanExtra("isManualRecording", false));
                }
                if (intent.hasExtra("startHoldSeconds")) {
                    ret.put("startHoldSeconds", intent.getIntExtra("startHoldSeconds", 0));
                }
                if (intent.hasExtra("endHoldSeconds")) {
                    ret.put("endHoldSeconds", intent.getIntExtra("endHoldSeconds", 0));
                }
                if (intent.hasExtra("silenceDecaySeconds")) {
                    ret.put("silenceDecaySeconds", intent.getIntExtra("silenceDecaySeconds", 0));
                }
                if (intent.hasExtra("cooldownSeconds")) {
                    ret.put("cooldownSeconds", intent.getIntExtra("cooldownSeconds", 0));
                }
                if (intent.hasExtra("speechDensityMin")) {
                    ret.put("speechDensityMin", intent.getDoubleExtra("speechDensityMin", 0));
                }
                if (intent.hasExtra("loudDensityMin")) {
                    ret.put("loudDensityMin", intent.getDoubleExtra("loudDensityMin", 0));
                }
                if (intent.hasExtra("speechDensityEnd")) {
                    ret.put("speechDensityEnd", intent.getDoubleExtra("speechDensityEnd", 0));
                }
                if (intent.hasExtra("loudDensityEnd")) {
                    ret.put("loudDensityEnd", intent.getDoubleExtra("loudDensityEnd", 0));
                }

                // Campos extras do ASR (Keyword Spotting) proveniente do PipelineController
                if (intent.hasExtra("phraseId")) {
                    ret.put("phraseId", intent.getStringExtra("phraseId"));
                }
                if (intent.hasExtra("confidence")) {
                    ret.put("confidence", intent.getDoubleExtra("confidence", 0.0));
                }
                if (intent.hasExtra("phraseType")) {
                    ret.put("phraseType", intent.getStringExtra("phraseType"));
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

    /**
     * Atualiza parâmetros individuais de detecção em tempo real (painel de tuning).
     */
    @PluginMethod
    public void updateTuning(PluginCall call) {
        try {
            Intent intent = new Intent(getContext(), AudioTriggerService.class);
            intent.setAction("UPDATE_CONFIG_TUNING");

            // Mapeia cada parâmetro recebido do JS para o intent
            if (call.hasOption("vadDeltaDb")) {
                intent.putExtra("vadDeltaDb", call.getDouble("vadDeltaDb"));
            }
            if (call.hasOption("loudDeltaDb")) {
                intent.putExtra("loudDeltaDb", call.getDouble("loudDeltaDb"));
            }
            if (call.hasOption("speechDensityMin")) {
                intent.putExtra("speechDensityMin", call.getDouble("speechDensityMin"));
            }
            if (call.hasOption("loudDensityMin")) {
                intent.putExtra("loudDensityMin", call.getDouble("loudDensityMin"));
            }
            if (call.hasOption("discussionWindowSeconds")) {
                intent.putExtra("discussionWindowSeconds", call.getInt("discussionWindowSeconds"));
            }
            if (call.hasOption("startHoldSeconds")) {
                intent.putExtra("startHoldSeconds", call.getInt("startHoldSeconds"));
            }
            if (call.hasOption("endHoldSeconds")) {
                intent.putExtra("endHoldSeconds", call.getInt("endHoldSeconds"));
            }
            if (call.hasOption("silenceDecaySeconds")) {
                intent.putExtra("silenceDecaySeconds", call.getInt("silenceDecaySeconds"));
            }
            if (call.hasOption("speechDensityEnd")) {
                intent.putExtra("speechDensityEnd", call.getDouble("speechDensityEnd"));
            }
            if (call.hasOption("loudDensityEnd")) {
                intent.putExtra("loudDensityEnd", call.getDouble("loudDensityEnd"));
            }
            if (call.hasOption("cooldownSeconds")) {
                intent.putExtra("cooldownSeconds", call.getInt("cooldownSeconds"));
            }

            getContext().startService(intent);

            Log.d(TAG, "[Tuning] Parameters sent to service");

            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);

        } catch (Exception e) {
            Log.e(TAG, "[Tuning] Error updating parameters", e);
            call.reject("Failed to update tuning: " + e.getMessage());
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

    /**
     * Salva preferência de notificações de eventos no SharedPreferences nativo
     */
    @PluginMethod
    public void setNotificationPreference(PluginCall call) {
        boolean enabled = call.getBoolean("enabled", true);
        android.content.SharedPreferences prefs = getContext().getSharedPreferences("AmparaPrefs",
                Context.MODE_PRIVATE);
        prefs.edit().putBoolean("notifications_enabled", enabled).apply();

        Log.d(TAG, "Notification preference set: " + enabled);

        JSObject ret = new JSObject();
        ret.put("success", true);
        call.resolve(ret);
    }

    /**
     * Obtém preferência de notificações de eventos do SharedPreferences nativo
     */
    @PluginMethod
    public void getNotificationPreference(PluginCall call) {
        android.content.SharedPreferences prefs = getContext().getSharedPreferences("AmparaPrefs",
                Context.MODE_PRIVATE);
        boolean enabled = prefs.getBoolean("notifications_enabled", true);

        JSObject ret = new JSObject();
        ret.put("enabled", enabled);
        call.resolve(ret);
    }

    // ========== Pipeline Híbrido — Métodos de Enrollment (SPEC v3) ==========

    /**
     * Inicia enrollment de uma frase personalizada.
     * Params: { phraseId: string, type: "OPERATIONAL" | "CONTEXTUAL" }
     */
    @PluginMethod
    public void startEnrollment(PluginCall call) {
        try {
            AudioTriggerService service = AudioTriggerService.getInstance();
            if (service == null || service.getPhraseEnrollmentManager() == null) {
                call.reject("Serviço não está rodando");
                return;
            }

            String phraseId = call.getString("phraseId");
            String typeStr = call.getString("type", "OPERATIONAL");

            if (phraseId == null || phraseId.isEmpty()) {
                call.reject("phraseId é obrigatório");
                return;
            }

            tech.orizon.ampara.audio.phrase.PhraseTemplate.PhraseType type;
            switch (typeStr.toUpperCase()) {
                case "CONTEXTUAL":
                    type = tech.orizon.ampara.audio.phrase.PhraseTemplate.PhraseType.CONTEXTUAL;
                    break;
                case "GENERIC":
                    type = tech.orizon.ampara.audio.phrase.PhraseTemplate.PhraseType.GENERIC;
                    break;
                default:
                    type = tech.orizon.ampara.audio.phrase.PhraseTemplate.PhraseType.OPERATIONAL;
                    break;
            }

            tech.orizon.ampara.audio.phrase.PhraseEnrollmentManager.EnrollmentResult result = service
                    .getPhraseEnrollmentManager().startEnrollment(phraseId, type);

            JSObject ret = new JSObject();
            ret.put("success", result.success);
            ret.put("message", result.message);
            call.resolve(ret);
        } catch (Exception e) {
            Log.e(TAG, "startEnrollment error", e);
            call.reject("Erro ao iniciar enrollment: " + e.getMessage());
        }
    }

    /**
     * Adiciona uma amostra de áudio ao enrollment em andamento.
     * O áudio é capturado diretamente do microfone por N segundos.
     * Params: { durationMs: number (padrão 3000) }
     */
    @PluginMethod
    public void addEnrollmentSample(PluginCall call) {
        try {
            AudioTriggerService service = AudioTriggerService.getInstance();
            if (service == null || service.getPhraseEnrollmentManager() == null) {
                call.reject("Serviço não está rodando");
                return;
            }

            int durationMs = call.getInt("durationMs", 3000);
            int sampleRate = 16000;
            int totalSamples = (sampleRate * durationMs) / 1000;

            // Gravar áudio do microfone para enrollment
            // Usa thread separada para não bloquear a UI
            new Thread(() -> {
                try {
                    int minBufferSize = android.media.AudioRecord.getMinBufferSize(
                            sampleRate,
                            android.media.AudioFormat.CHANNEL_IN_MONO,
                            android.media.AudioFormat.ENCODING_PCM_16BIT);

                    android.media.AudioRecord recorder = new android.media.AudioRecord(
                            android.media.MediaRecorder.AudioSource.MIC,
                            sampleRate,
                            android.media.AudioFormat.CHANNEL_IN_MONO,
                            android.media.AudioFormat.ENCODING_PCM_16BIT,
                            Math.max(minBufferSize, totalSamples * 2));

                    short[] buffer = new short[totalSamples];
                    recorder.startRecording();

                    int offset = 0;
                    while (offset < totalSamples) {
                        int read = recorder.read(buffer, offset, totalSamples - offset);
                        if (read < 0)
                            break;
                        offset += read;
                    }

                    recorder.stop();
                    recorder.release();

                    // Processar amostra no enrollment manager
                    tech.orizon.ampara.audio.phrase.PhraseEnrollmentManager.EnrollmentResult result = service
                            .getPhraseEnrollmentManager().addSample(buffer, 0, offset);

                    JSObject ret = new JSObject();
                    ret.put("success", result.success);
                    ret.put("message", result.message);
                    ret.put("currentSamples", result.currentSamples);
                    ret.put("minRequired", result.minRequired);
                    ret.put("maxAllowed", result.maxAllowed);
                    call.resolve(ret);
                } catch (Exception e) {
                    Log.e(TAG, "addEnrollmentSample recording error", e);
                    call.reject("Erro ao gravar amostra: " + e.getMessage());
                }
            }).start();
        } catch (Exception e) {
            Log.e(TAG, "addEnrollmentSample error", e);
            call.reject("Erro: " + e.getMessage());
        }
    }

    /**
     * Finaliza enrollment: valida consistência, calcula thresholds, persiste.
     */
    @PluginMethod
    public void finishEnrollment(PluginCall call) {
        try {
            AudioTriggerService service = AudioTriggerService.getInstance();
            if (service == null || service.getPhraseEnrollmentManager() == null) {
                call.reject("Serviço não está rodando");
                return;
            }

            tech.orizon.ampara.audio.phrase.PhraseEnrollmentManager.EnrollmentResult result = service
                    .getPhraseEnrollmentManager().finishEnrollment();

            // CRÍTICO: Recarregar pra memória do Detector ler
            if (result.success) {
                service.getPhraseEnrollmentManager().loadAllTemplates();
            }

            JSObject ret = new JSObject();
            ret.put("success", result.success);
            ret.put("message", result.message);
            call.resolve(ret);
        } catch (Exception e) {
            Log.e(TAG, "finishEnrollment error", e);
            call.reject("Erro: " + e.getMessage());
        }
    }

    /**
     * Cancela enrollment em andamento.
     */
    @PluginMethod
    public void cancelEnrollment(PluginCall call) {
        try {
            AudioTriggerService service = AudioTriggerService.getInstance();
            if (service == null || service.getPhraseEnrollmentManager() == null) {
                call.reject("Serviço não está rodando");
                return;
            }

            service.getPhraseEnrollmentManager().cancelEnrollment();

            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Erro: " + e.getMessage());
        }
    }

    /**
     * Remove uma frase cadastrada.
     * Params: { phraseId: string }
     */
    @PluginMethod
    public void removePhrase(PluginCall call) {
        try {
            AudioTriggerService service = AudioTriggerService.getInstance();
            if (service == null || service.getPhraseEnrollmentManager() == null) {
                call.reject("Serviço não está rodando");
                return;
            }

            String phraseId = call.getString("phraseId");
            if (phraseId == null || phraseId.isEmpty()) {
                call.reject("phraseId é obrigatório");
                return;
            }

            boolean removed = service.getPhraseEnrollmentManager().removePhrase(phraseId);

            // CRÍTICO: Limpar a memória pro Detector parar de ouvir
            if (removed) {
                service.getPhraseEnrollmentManager().loadAllTemplates();
            }

            JSObject ret = new JSObject();
            ret.put("success", removed);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Erro: " + e.getMessage());
        }
    }

    /**
     * Retorna diagnósticos do pipeline e enrollment.
     */
    @PluginMethod
    public void getPipelineDiagnostics(PluginCall call) {
        try {
            AudioTriggerService service = AudioTriggerService.getInstance();
            if (service == null) {
                call.reject("Serviço não está rodando");
                return;
            }

            JSObject ret = new JSObject();

            if (service.getPipelineController() != null) {
                ret.put("pipeline", service.getPipelineController().getDiagnostics());
            }
            if (service.getPhraseEnrollmentManager() != null) {
                ret.put("enrollment", service.getPhraseEnrollmentManager().getDiagnostics());
                ret.put("hasStaleTemplates", service.getPhraseEnrollmentManager().hasStaleTemplates());
            }

            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Erro: " + e.getMessage());
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
