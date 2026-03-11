package tech.orizon.ampara;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import android.content.pm.PackageManager;
import android.Manifest;

import org.json.JSONObject;

import tech.orizon.ampara.audio.AudioDSP;
import tech.orizon.ampara.audio.AudioTriggerConfig;
import tech.orizon.ampara.audio.AudioTriggerDefaults;
import tech.orizon.ampara.audio.DiscussionDetector;
import tech.orizon.ampara.audio.NativeRecorder;
import tech.orizon.ampara.audio.AudioUploader;
import tech.orizon.ampara.audio.LocationManager;
import tech.orizon.ampara.audio.SilenceDetector;
import tech.orizon.ampara.audio.UploadQueue;

import tech.orizon.ampara.audio.phrase.PipelineController;
import tech.orizon.ampara.audio.phrase.PhraseEnrollmentManager;
import tech.orizon.ampara.audio.phrase.ScoringConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Native Audio Trigger Service
 * Captures and analyzes audio in background to detect discussions
 */
public class AudioTriggerService extends Service {
    private static final String TAG = "AudioTriggerService";
    private static final String CHANNEL_ID = "AudioTriggerChannel";
    private static final String EVENTS_CHANNEL_ID = "AmparaEventsChannel";
    private static final int NOTIFICATION_ID = 1001;
    // IDs de notificações de eventos começam em 2000 e incrementam
    private int eventNotificationId = 2000;

    // Referência estática para acesso pelo plugin
    private static AudioTriggerService instance;

    // Trilha 1: segurança de energia/retry
    private static final long WAKELOCK_TIMEOUT_MS = 2 * 60 * 1000L; // 2 min
    private static final long UPLOAD_RETRY_INTERVAL_MS = 5 * 60 * 1000L; // 5 min

    // Microphone state machine for mutual exclusion
    private enum MicrophoneState {
        IDLE, // Service not started
        MONITORING, // AudioRecord active (detection)
        RECORDING // MediaRecorder active (recording)
    }

    private MicrophoneState currentMicState = MicrophoneState.IDLE;

    private AudioRecord audioRecord;
    private Thread processingThread;
    private volatile boolean isRunning = false;
    private PowerManager.WakeLock wakeLock;

    // AudioFocus management
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest; // API 26+
    private boolean audioFocusGranted = false;

    private AudioTriggerConfig config;
    private DiscussionDetector detector;
    private NativeRecorder recorder;
    private AudioUploader uploader;
    private LocationManager locationManager;
    private SilenceDetector silenceDetector;
    private UploadQueue uploadQueue;
    private PanicManager panicManager;
    private ConnectivityManager.NetworkCallback networkCallback;

    // Pipeline híbrido de detecção (SPEC v3)
    private PipelineController pipelineController;
    private PhraseEnrollmentManager phraseEnrollmentManager;
    private ScoringConfig scoringConfig;

    // Getters estáticos para acesso pelo plugin Capacitor
    public static AudioTriggerService getInstance() {
        return instance;
    }

    public PhraseEnrollmentManager getPhraseEnrollmentManager() {
        return phraseEnrollmentManager;
    }

    public PipelineController getPipelineController() {
        return pipelineController;
    }

    private String sessionToken;
    private String emailUsuario;
    private String deviceId;
    private String currentOrigemGravacao = "automatico";

    private short[] frameBuffer;
    private List<DiscussionDetector.AggregationMetrics> aggregationBuffer;
    private int frameCounter = 0;
    private int aggregationCounter = 0;
    private long lastDiagnosticLog = 0;
    private long lastWakeLockCheck = 0;
    private long manualStopCooldownUntil = 0;

    // Simulated aggregations during recording (when AudioRecord is paused)
    private android.os.Handler recordingHandler;
    private Runnable recordingAggregationRunnable;

    // Trilha 1: retry periódico de uploads pendentes (offline/timeout)
    private final android.os.Handler uploadRetryHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable uploadRetryRunnable;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Log.d(TAG, "AudioTriggerService created - Build: 2026-03-06 22:00");

        // Check RECORD_AUDIO permission before starting foreground service
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted! Cannot start foreground service.");
            stopSelf();
            return;
        }

        // Prepare notification channel (but don't start foreground yet)
        createNotificationChannel();
        createEventsNotificationChannel();

        acquireWakeLock();

        // Initialize AudioManager for AudioFocus
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        // Use fixed local defaults (NEVER from API)
        config = AudioTriggerDefaults.getDefaultConfig();
        AudioTriggerDefaults.logConfigSource(TAG);
        detector = new DiscussionDetector(config);
        recorder = new NativeRecorder(this);
        uploader = new AudioUploader(this);
        panicManager = new PanicManager(this);
        locationManager = new LocationManager(this);
        silenceDetector = new SilenceDetector();
        uploadQueue = new UploadQueue(this, uploader);
        recordingHandler = new android.os.Handler(android.os.Looper.getMainLooper());

        // Inicializar pipeline híbrido (SPEC v3)
        scoringConfig = new ScoringConfig();
        phraseEnrollmentManager = new PhraseEnrollmentManager(this, scoringConfig);
        int phrasesLoaded = phraseEnrollmentManager.loadAllTemplates();
        pipelineController = new PipelineController(this, scoringConfig, phraseEnrollmentManager);
        
        // Conectar o Pipeline de ASR (Keyword Spotting) com o Capacitor/React
        pipelineController.setActionListener(new PipelineController.PipelineActionListener() {
            @Override
            public void onStartRecording(String reason) {
                // Já tratado via detector ou intenção
            }

            @Override
            public void onStopRecording(String reason) {
                 // Já tratado
            }

            @Override
            public void onStartPanic(String reason) {
                // Já tratado
            }

            @Override
            public void onCancelPanic(String reason) {
                // Já tratado
            }

            @Override
            public void onElevateSensitivity(String reason) {
                // Futura implementação
            }

            @Override
            public void onRiskScoreUpdate(double score, String level, List<String> reasons) {
                // Futura implementação (enviar os motivos pro painel técnico)
            }

            @Override
            public void onPhraseDetected(String phraseId, double confidence, String type) {
                // Repassa EXATAMENTE o match da frase para o App no painel Híbrido!
                Intent intent = new Intent("tech.orizon.ampara.AUDIO_TRIGGER_EVENT");
                intent.setPackage(getPackageName());
                intent.putExtra("event", "phraseDetected");
                intent.putExtra("phraseId", phraseId);
                intent.putExtra("confidence", confidence);
                intent.putExtra("phraseType", type);
                intent.putExtra("timestamp", System.currentTimeMillis());
                sendBroadcast(intent);
                Log.d(TAG, "[ASR Match Broadcast] Enviado pro Capacitor. word=" + phraseId + ", conf=" + confidence);
            }
        });

        Log.i(TAG, "[PIPELINE] Pipeline híbrido inicializado | frases=" + phrasesLoaded);

        // Initialize credentials and IDs
        loadInitialCredentials();

        // Setup calibration callback
        detector.setCalibrationCallback(isCalibrated -> {
            notifyCalibrationStatus(isCalibrated);
        });

        // Setup recorder callback
        recorder.setSegmentCallback((filePath, segmentIndex, sessionId) -> {
            Log.i(TAG, String.format("Segment complete: %d", segmentIndex));

            // Enqueue for upload
            UploadQueue.UploadTask task = new UploadQueue.UploadTask(
                    filePath,
                    segmentIndex,
                    sessionId,
                    locationManager.getLatitude(),
                    locationManager.getLongitude(),
                    currentOrigemGravacao);
            uploadQueue.enqueue(task);

            // Notify JavaScript
            notifyRecordingProgress(sessionId, segmentIndex);
        });

        // Setup upload progress callback
        uploadQueue.setProgressCallback((pending, success, failure) -> {
            Log.d(TAG, String.format("Upload progress: pending=%d, success=%d, failure=%d",
                    pending, success, failure));
            notifyUploadProgress(pending, success, failure);
        });

        int frameSamples = config.getFrameSamples();
        frameBuffer = new short[frameSamples];
        aggregationBuffer = new ArrayList<>();

        // Register connectivity listener
        registerNetworkCallback();

        // Trilha 1: NÃO apagar pendências. Em vez disso, retentar o que já está em
        // disco.
        if (uploadQueue != null) {
            uploadQueue.start();
            // defaultSessionId/origem só são usados se o parse do filename falhar
            uploadQueue.retryPendingUploads("recovery", currentOrigemGravacao);

            // Retry periódico (ex.: aparelho ficou offline por muito tempo)
            uploadRetryRunnable = new Runnable() {
                @Override
                public void run() {
                    try {
                        if (uploadQueue != null) {
                            uploadQueue.retryPendingUploads("recovery", currentOrigemGravacao);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error during periodic upload retry", e);
                    } finally {
                        uploadRetryHandler.removeCallbacks(this);
                        uploadRetryHandler.postDelayed(this, UPLOAD_RETRY_INTERVAL_MS);
                    }
                }
            };
            uploadRetryHandler.postDelayed(uploadRetryRunnable, UPLOAD_RETRY_INTERVAL_MS);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "AudioTriggerService onStartCommand");
        Log.i(TAG, "FGS_MICROPHONE_START_REQUEST");

        // Start as Foreground Service IMMEDIATELY on first command
        // This ensures we're in "eligible state" (app recently interacted)
        // CRITICAL: Must call startForeground() within 5 seconds of service start
        try {
            Notification notification = createNotification();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ requires foregroundServiceType
                // Android 14/15 requires app to be in eligible state
                int foregroundServiceTypes = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;

                // Add LOCATION type if we have permission
                if (ContextCompat.checkSelfPermission(this,
                        Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    foregroundServiceTypes |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
                    Log.d(TAG, "Added LOCATION type to Foreground Service");
                }

                startForeground(NOTIFICATION_ID, notification, foregroundServiceTypes);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }

            Log.i(TAG, "FGS_MICROPHONE_STARTED_OK");
            Log.d(TAG, "[MicState] Foreground Service started with microphone type");
        } catch (SecurityException se) {
            // Android 14/15: App not in eligible state to start microphone FGS
            Log.e(TAG, "FGS_MICROPHONE_SECURITY_EXCEPTION: " + se.getMessage());
            Log.e(TAG, "FGS_MIC_NOT_ELIGIBLE: App must be in foreground to start microphone service");

            // Notify JavaScript about the failure
            notifyFgsNotEligible();

            // Stop gracefully without crashing
            stopSelf();
            return START_NOT_STICKY;
        } catch (Exception e) {
            Log.e(TAG, "FGS_MICROPHONE_START_FAILED: " + e.getMessage(), e);
            // If we can't start foreground, stop the service
            stopSelf();
            return START_NOT_STICKY;
        }

        // Handle commands
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();

            if ("START_RECORDING".equals(action)) {
                Log.i(TAG, "Manual recording start requested");

                // Get credentials and origem from intent
                if (intent.hasExtra("sessionToken")) {
                    sessionToken = intent.getStringExtra("sessionToken");
                    emailUsuario = intent.getStringExtra("emailUsuario");
                    currentOrigemGravacao = intent.getStringExtra("origemGravacao");

                    uploader.setCredentials(sessionToken, emailUsuario);
                    panicManager.setCredentials(sessionToken, emailUsuario, deviceId);
                }

                // Pause monitoring to release microphone (only if already monitoring)
                if (currentMicState == MicrophoneState.MONITORING) {
                    pauseMonitoring();
                }
                currentMicState = MicrophoneState.RECORDING;

                // Start recording, location tracking, and upload queue
                String sessionId = recorder.startRecording();
                if (sessionId != null) {
                    // Fail-safe: Se a origem for pânico, ativar o estado de pânico nativo
                    // imediatamente
                    if ("botao_panico".equals(currentOrigemGravacao)) {
                        Log.i(TAG, "Fail-safe: Activating native panic state because origin is botao_panico");
                        panicManager.activatePanic("manual", "manual");
                    }

                    // Garantir WakeLock ativo durante a gravação manual
                    acquireWakeLock();

                    locationManager.startTracking();
                    uploadQueue.start();
                    uploadQueue.resetStats();
                    silenceDetector.reset();

                    // Update notification to show recording state
                    updateNotificationForRecording();

                    Log.i(TAG, "Manual recording started: " + sessionId);
                    notifyRecordingStarted(sessionId);

                    // Notify server that recording started
                    uploader.notifyRecordingStarted(sessionId, currentOrigemGravacao);

                    // Force detector to RECORDING_STARTED state to prevent false alarm
                    // true means this is a manual recording (longer timeout)
                    detector.forceRecordingStarted(true);

                    // Start simulated aggregations (1 per second) to continue detection logic
                    startRecordingAggregations();

                    // Increase GPS frequency during manual recording if panic is active
                    if (panicManager.isPanicActive()) {
                        locationManager.stopTracking();
                        locationManager.startTracking(1000); // 1s
                    }
                }

                return START_STICKY;
            }

            if ("STOP_RECORDING".equals(action)) {
                Log.i(TAG, "Manual recording stop requested");

                String sessionId = recorder.stopRecording();
                if (sessionId != null) {
                    int totalSegments = recorder.getSegmentIndex();
                    locationManager.stopTracking();

                    Log.i(TAG, "Manual recording stopped: " + sessionId);
                    notifyRecordingStopped(sessionId);

                    // Notify server that recording is complete (manual stop)
                    uploader.notifyRecordingComplete(sessionId, totalSegments, "manual");
                }

                // Set cooldown to prevent immediate re-trigger
                manualStopCooldownUntil = System.currentTimeMillis() + 60000; // 60s cooldown
                Log.i(TAG, "Manual stop cooldown set for 60s");

                // Stop simulated aggregations
                stopRecordingAggregations();

                // Reset DiscussionDetector to IDLE state but keep calibration
                detector.resetDetectionState();
                Log.i(TAG, "DiscussionDetector soft reset to IDLE after manual stop");

                // Update notification back to monitoring state
                updateNotificationForMonitoring();

                // Resume monitoring after recording stops
                resumeMonitoring();

                // WakeLock só deve ser solto se não estivermos mais gravando nem monitorando
                // (IDLE)
                // Mas resumeMonitoring() vai iniciar o capture que pegará o WakeLock de novo.
                // Para evitar "gaps", não soltamos aqui.

                return START_STICKY;
            }

            if ("UPLOAD_FILE".equals(action)) {
                Log.i(TAG, "Manual file upload requested");

                String filePath = intent.getStringExtra("filePath");
                int segmentIndex = intent.getIntExtra("segmentIndex", 1);
                String sessionId = intent.getStringExtra("sessionId");
                String origemGravacao = intent.getStringExtra("origemGravacao");

                if (intent.hasExtra("sessionToken")) {
                    sessionToken = intent.getStringExtra("sessionToken");
                    emailUsuario = intent.getStringExtra("emailUsuario");
                    uploader.setCredentials(sessionToken, emailUsuario);
                }

                if (filePath != null && sessionId != null) {
                    // Ensure upload queue is running
                    uploadQueue.start();

                    // Enqueue the task
                    uploadQueue.enqueue(new UploadQueue.UploadTask(
                            filePath, segmentIndex, sessionId,
                            locationManager.getLatitude(), locationManager.getLongitude(),
                            origemGravacao));

                    Log.i(TAG, "File enqueued for upload: " + filePath);
                }

                return START_STICKY;
            }

            if ("REPORT_STATUS".equals(action)) {
                Log.i(TAG, "Native status report requested");

                String status = intent.getStringExtra("status");
                boolean isMonitoring = intent.getBooleanExtra("isMonitoring", false);
                String motivo = intent.getStringExtra("motivo");

                if (uploader != null) {
                    uploader.reportarStatusMonitoramento(status, isMonitoring, motivo);
                }
                return START_STICKY;
            }

            if ("PANIC_ACTIVATED".equals(action)) {
                Log.i(TAG, "Panic activated natively");
                String protocolNumber = intent.getStringExtra("protocolNumber");
                String activationType = intent.getStringExtra("activationType");
                if (activationType == null)
                    activationType = "manual";

                Log.i(TAG, String.format("Panic state persisted: protocol=%s", protocolNumber));

                // Garantir persistência do estado no PanicManager para blindagem
                panicManager.activatePanic(protocolNumber, activationType);

                // Furar fila da IA: Iniciar gravação imediatamente sem aguardar os thresholds
                if (currentMicState != MicrophoneState.RECORDING || !recorder.isRecording()) {
                    Log.i(TAG, "Panic is active but not recording. Forcing START_RECORDING immediately.");

                    // Pause monitoring if active
                    if (currentMicState == MicrophoneState.MONITORING) {
                        pauseMonitoring();
                    }

                    currentMicState = MicrophoneState.RECORDING;
                    currentOrigemGravacao = "botao_panico"; // Trata o pânico como uma ordem direta para acionar
                                                            // blindagem

                    String sessionId = recorder.startRecording();
                    if (sessionId != null) {
                        panicManager.setCriticalAlert(true);

                        locationManager.stopTracking();
                        locationManager.startTracking(1000); // 1s during panic

                        uploadQueue.start();
                        uploadQueue.resetStats();
                        silenceDetector.reset(); // Zera o silencio preventivamente

                        Log.i(TAG, "Native recording FORCED by Panic: " + sessionId);
                        notifyRecordingStarted(sessionId);
                        uploader.notifyRecordingStarted(sessionId, currentOrigemGravacao);

                        // Force detector to RECORDING_STARTED state to prevent false alarm timeout
                        // closures
                        detector.forceRecordingStarted(true);
                        startRecordingAggregations();
                    }
                }

                return START_STICKY;
            }

            if ("PANIC_DEACTIVATED".equals(action)) {
                Log.i(TAG, "Panic deactivated");
                String cancelType = intent.getStringExtra("cancelType");
                if (cancelType == null)
                    cancelType = "manual";

                // Limpar estado persistente
                panicManager.deactivatePanic(cancelType);

                Log.i(TAG, "Panic state cleared");

                // Set cooldown to prevent immediate re-trigger from noise floor
                // misinterpretation
                manualStopCooldownUntil = System.currentTimeMillis() + 60000; // 60s cooldown
                Log.i(TAG, "Panic deactivated cooldown set for 60s");

                // Reset DiscussionDetector to IDLE state but keep calibration
                detector.resetDetectionState();

                return START_STICKY;
            }

            if ("UPDATE_CONFIG".equals(action)) {
                Log.i(TAG, "Config update requested");
                if (intent.hasExtra("config")) {
                    String configJson = intent.getStringExtra("config");
                    applyConfiguration(configJson);
                }
            }

            // Tuning local — aplica parâmetros individuais em runtime
            if ("UPDATE_CONFIG_TUNING".equals(action)) {
                Log.i(TAG, "[Tuning] Config tuning update requested");
                applyTuningConfig(intent);
            }

            if ("GET_STATUS".equals(action)) {
                Log.d(TAG, "Status request received");
                // Proteção contra null: detector pode não estar inicializado ainda
                if (detector != null) {
                    boolean isCalibrated = detector.isCalibrated();
                    notifyCalibrationStatus(isCalibrated);
                }
                notifyPanicState();
                notifyRecordingState();
            }
        }

        if (!isRunning) {
            startAudioCapture();
        }

        return START_STICKY;
    }

    private void applyConfiguration(String configJson) {
        // REGRA MESTRE: IGNORAR audio_trigger_config da API
        // Thresholds são SEMPRE os defaults locais (AudioTriggerDefaults)
        Log.w(TAG, "[AudioTriggerService] Remote audio_trigger_config received -> IGNORED BY DESIGN");
        Log.i(TAG, "[AudioTriggerService] Thresholds source = LOCAL DEFAULTS (AudioTriggerDefaults)");

        // API pode enviar apenas monitoringEnabled + monitoringPeriods (não
        // implementado aqui)
        // Thresholds NÃO mudam
    }

    /**
     * Aplica parâmetros de tuning vindos do painel técnico.
     * Atualiza o config em memória e o detector em runtime.
     */
    private void applyTuningConfig(Intent intent) {
        if (config == null || detector == null) {
            Log.w(TAG, "[Tuning] Config or detector is null, ignoring tuning");
            return;
        }

        // Aplica cada parâmetro se presente no intent
        if (intent.hasExtra("vadDeltaDb")) {
            config.vadDeltaDb = intent.getDoubleExtra("vadDeltaDb", config.vadDeltaDb);
            Log.i(TAG, "[Tuning] vadDeltaDb = " + config.vadDeltaDb);
        }
        if (intent.hasExtra("loudDeltaDb")) {
            config.loudDeltaDb = intent.getDoubleExtra("loudDeltaDb", config.loudDeltaDb);
            Log.i(TAG, "[Tuning] loudDeltaDb = " + config.loudDeltaDb);
        }
        if (intent.hasExtra("speechDensityMin")) {
            config.speechDensityMin = intent.getDoubleExtra("speechDensityMin", config.speechDensityMin);
            Log.i(TAG, "[Tuning] speechDensityMin = " + config.speechDensityMin);
        }
        if (intent.hasExtra("loudDensityMin")) {
            config.loudDensityMin = intent.getDoubleExtra("loudDensityMin", config.loudDensityMin);
            Log.i(TAG, "[Tuning] loudDensityMin = " + config.loudDensityMin);
        }
        if (intent.hasExtra("discussionWindowSeconds")) {
            config.discussionWindowSeconds = intent.getIntExtra("discussionWindowSeconds",
                    config.discussionWindowSeconds);
            Log.i(TAG, "[Tuning] discussionWindowSeconds = " + config.discussionWindowSeconds);
        }
        if (intent.hasExtra("startHoldSeconds")) {
            config.startHoldSeconds = intent.getIntExtra("startHoldSeconds", config.startHoldSeconds);
            Log.i(TAG, "[Tuning] startHoldSeconds = " + config.startHoldSeconds);
        }
        if (intent.hasExtra("endHoldSeconds")) {
            config.endHoldSeconds = intent.getIntExtra("endHoldSeconds", config.endHoldSeconds);
            Log.i(TAG, "[Tuning] endHoldSeconds = " + config.endHoldSeconds);
        }
        if (intent.hasExtra("silenceDecaySeconds")) {
            config.silenceDecaySeconds = intent.getIntExtra("silenceDecaySeconds", config.silenceDecaySeconds);
            Log.i(TAG, "[Tuning] silenceDecaySeconds = " + config.silenceDecaySeconds);
        }
        if (intent.hasExtra("speechDensityEnd")) {
            config.speechDensityEnd = intent.getDoubleExtra("speechDensityEnd", config.speechDensityEnd);
            Log.i(TAG, "[Tuning] speechDensityEnd = " + config.speechDensityEnd);
        }
        if (intent.hasExtra("loudDensityEnd")) {
            config.loudDensityEnd = intent.getDoubleExtra("loudDensityEnd", config.loudDensityEnd);
            Log.i(TAG, "[Tuning] loudDensityEnd = " + config.loudDensityEnd);
        }
        if (intent.hasExtra("cooldownSeconds")) {
            config.cooldownSeconds = intent.getIntExtra("cooldownSeconds", config.cooldownSeconds);
            Log.i(TAG, "[Tuning] cooldownSeconds = " + config.cooldownSeconds);
        }

        Log.i(TAG, "[Tuning] Config applied successfully");
    }

    private void startAudioCapture() {
        // Request audio focus before starting capture
        if (!requestAudioFocus()) {
            Log.e(TAG, "Failed to get audio focus, cannot start audio capture");
            return;
        }

        try {
            int bufferSize = AudioRecord.getMinBufferSize(
                    config.sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);

            if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "Invalid buffer size: " + bufferSize);
                return;
            }

            // Use larger buffer for stability
            bufferSize = Math.max(bufferSize, config.getFrameSamples() * 4);

            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    config.sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize);

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized");
                return;
            }

            audioRecord.startRecording();
            isRunning = true;
            currentMicState = MicrophoneState.MONITORING;

            processingThread = new Thread(this::processAudioLoop);

            // Fortalecer a CPU inteira antes de pular pra thread
            acquireWakeLock();

            processingThread.start();

            Log.i(TAG, "[MicState] IDLE -> MONITORING: AudioRecord started");

        } catch (SecurityException e) {
            Log.e(TAG, "Microphone permission not granted", e);
        } catch (Exception e) {
            Log.e(TAG, "Error starting audio capture", e);
        }
    }

    private void processAudioLoop() {
        Log.d(TAG, "Audio processing loop started");
        Log.i(TAG, String.format("[CONFIG] ZCR range: %.2f-%.2f, VAD delta: %.1f dB, Loud delta: %.1f dB",
                config.zcrMinVoice, config.zcrMaxVoice, config.vadDeltaDb, config.loudDeltaDb));

        int frameSamples = config.getFrameSamples();
        int aggregationFrames = config.getAggregationFrames();

        while (isRunning && audioRecord != null) {
            try {
                // Trilha 1: renovar wakelock com baixo overhead
                long now = System.currentTimeMillis();
                if (now - lastWakeLockCheck > 10000) { // 10s
                    acquireWakeLock();
                    lastWakeLockCheck = now;
                }
                // Read one frame
                int samplesRead = audioRecord.read(frameBuffer, 0, frameSamples);

                if (samplesRead < 0) {
                    Log.e(TAG, "Error reading audio: " + samplesRead);
                    break;
                }

                if (samplesRead != frameSamples) {
                    continue; // Skip incomplete frames
                }

                // Process frame
                processFrame(frameBuffer, frameSamples);
                frameCounter++;

                // Check if we have enough frames for aggregation
                if (aggregationBuffer.size() >= aggregationFrames) {
                    processAggregation();
                    aggregationBuffer.clear();
                    aggregationCounter++;

                    // Log every 10 aggregations (~10 seconds)
                    if (aggregationCounter % 10 == 0) {
                        Log.d(TAG, String.format("[ALIVE] Processed %d frames, %d aggregations, NoiseFloor: %.1f dB",
                                frameCounter, aggregationCounter, detector.getNoiseFloor()));
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "Error in audio processing loop", e);
                break;
            }
        }

        Log.d(TAG, "Audio processing loop ended");

        // Só desliga quando parar efetivamente
        releaseWakeLock();
    }

    private void processFrame(short[] samples, int length) {
        // Calculate metrics
        double rmsDb = AudioDSP.calculateRMS(samples, length);
        double zcr = AudioDSP.calculateZCR(samples, length);

        // Detect speech and loudness
        double noiseFloor = detector.getNoiseFloor();
        boolean isSpeech = AudioDSP.isSpeechLike(rmsDb, zcr, config, noiseFloor);

        // Diagnostic: log when RMS is high but speech is not detected
        if (rmsDb > -55 && !isSpeech && System.currentTimeMillis() - lastDiagnosticLog > 3000) {
            double vadThreshold = noiseFloor + config.vadDeltaDb;
            boolean hasEnergy = rmsDb > vadThreshold;
            boolean hasVoiceZCR = zcr >= config.zcrMinVoice && zcr <= config.zcrMaxVoice;
            String reason = !hasEnergy ? String.format("RMS too low (%.1f < %.1f)", rmsDb, vadThreshold)
                    : !hasVoiceZCR ? "ZCR out of range" : "unknown";
            Log.w(TAG, String.format(
                    "[DIAGNOSTIC] Speech=false: RMS=%.1f dB (threshold=%.1f), ZCR=%.3f (range: %.2f-%.2f), NoiseFloor=%.1f, Reason: %s",
                    rmsDb, vadThreshold, zcr, config.zcrMinVoice, config.zcrMaxVoice, noiseFloor, reason));
            lastDiagnosticLog = System.currentTimeMillis();
        }

        // Hybrid threshold: relative (noiseFloor + delta) OR absolute minimum
        // Ensures detection even in very noisy environments
        double relativeLoudThreshold = noiseFloor + config.loudDeltaDb;
        double absoluteLoudThreshold = -20.0; // Absolute minimum for loud detection
        double loudThreshold = Math.max(relativeLoudThreshold, absoluteLoudThreshold);
        boolean isLoud = AudioDSP.isLoud(rmsDb, loudThreshold);

        // Add to aggregation buffer
        DiscussionDetector.AggregationMetrics metrics = new DiscussionDetector.AggregationMetrics(rmsDb, zcr, isSpeech,
                isLoud);
        aggregationBuffer.add(metrics);

        // Alimentar ring buffer do pipeline híbrido (L2)
        if (pipelineController != null) {
            pipelineController.feedAudio(samples, 0, length);
        }
    }

    private void processAggregation() {
        // Calculate aggregated metrics
        double avgRmsDb = 0;
        double avgZcr = 0;
        int speechCount = 0;
        int loudCount = 0;

        for (DiscussionDetector.AggregationMetrics m : aggregationBuffer) {
            avgRmsDb += m.rmsDb;
            avgZcr += m.zcr;
            if (m.isSpeech)
                speechCount++;
            if (m.isLoud)
                loudCount++;
        }

        int count = aggregationBuffer.size();
        avgRmsDb /= count;
        avgZcr /= count;

        boolean isSpeech = speechCount > (count / 2);
        boolean isLoud = loudCount > (count / 2);

        // Process with detector
        DiscussionDetector.AggregationMetrics aggregated = new DiscussionDetector.AggregationMetrics(avgRmsDb, avgZcr,
                isSpeech, isLoud);

        DiscussionDetector.DetectionResult result = detector.process(aggregated);

        // Log every aggregation for debugging
        if (aggregationCounter % 5 == 0) {
            Log.d(TAG, String.format("[METRICS] RMS: %.1f dB, ZCR: %.3f, Speech: %b, Loud: %b, State: %s",
                    avgRmsDb, avgZcr, isSpeech, isLoud, detector.getState()));
        }

        // Send metrics to JS for UI updates (every aggregation = ~1s)
        // Calculate discussion score normalized to thresholds (0.5 speech, 0.3 loud)
        // Score reaches 1.0 when both thresholds are met
        double speechNorm = Math.min(result.speechDensity / 0.5, 1.0);
        double loudNorm = Math.min(result.loudDensity / 0.3, 1.0);
        double discussionScore = (speechNorm + loudNorm) / 2.0;
        notifyMetrics(avgRmsDb, avgZcr, isSpeech, isLoud, detector.getStateString(), discussionScore,
                detector.isCalibrated(), result.speechDensity, result.loudDensity, detector.getNoiseFloor());

        // Alimentar RMS para detecção de padrão estável (TV/música) no pipeline
        if (pipelineController != null) {
            pipelineController.feedRmsDb(avgRmsDb);

            // Escalar para pipeline quando detector detecta fala significativa
            if (isSpeech && result.speechDensity >= config.speechDensityMin) {
                pipelineController.onAcousticTrigger(
                        Math.min(result.speechDensity, 1.0), "SPEECH_BURST");
            }
        }

        // Log detection
        if (result.shouldStartRecording) {
            // Check cooldown period after manual stop
            long now = System.currentTimeMillis();
            if (now < manualStopCooldownUntil) {
                long remainingSeconds = (manualStopCooldownUntil - now) / 1000;
                Log.i(TAG, String.format("DISCUSSION DETECTED but in cooldown period (%ds remaining) - ignoring",
                        remainingSeconds));
                return;
            }

            Log.i(TAG, String.format("DISCUSSION DETECTED! Reason: %s, Speech: %.2f, Loud: %.2f",
                    result.reason, result.speechDensity, result.loudDensity));

            // Pause monitoring to release microphone for MediaRecorder
            if (currentMicState == MicrophoneState.MONITORING) {
                pauseMonitoring();
            }
            currentMicState = MicrophoneState.RECORDING;

            // Start native recording with auto mode
            if (!"botao_panico".equals(currentOrigemGravacao)) {
                currentOrigemGravacao = "automatico";
            }
            String sessionId = recorder.startRecording();
            if (sessionId != null) {
                // Set critical alert state
                panicManager.setCriticalAlert(true);

                locationManager.stopTracking();
                locationManager.startTracking(1000); // 1s during critical alert

                uploadQueue.start();

                uploadQueue.resetStats();
                silenceDetector.reset();

                Log.i(TAG, "Native recording started: " + sessionId);
                notifyRecordingStarted(sessionId);

                // Notificação de evento: gravação iniciada
                showEventNotification("Ampara — Gravação Iniciada",
                        "Discussão detectada. Gravação automática em andamento.");

                // Notify server that recording started
                uploader.notifyRecordingStarted(sessionId, currentOrigemGravacao);

                // Force detector to RECORDING_STARTED state to prevent false alarm
                // false means this is an automatic recording (normal timeout)
                detector.forceRecordingStarted(false);

                // Start simulated aggregations (1 per second) to continue detection logic
                startRecordingAggregations();
            }

            notifyJavaScript("discussionDetected", result.reason);
        }

        // Check silence detector during recording
        if (recorder.isRecording()) {
            if (silenceDetector.processFrame(avgRmsDb)) {
                // If panic is active, do NOT stop recording - it will resume when sound returns
                if (panicManager.isPanicActive()) {
                    Log.i(TAG,
                            "Silence timeout (10 min) reached during panic - auto-cancelling full panic protocol and stopping recording");

                    // 1. Para Gravação Nativa
                    String sessionId = recorder.stopRecording();
                    if (sessionId != null) {
                        int totalSegments = recorder.getSegmentIndex();
                        locationManager.stopTracking();
                        Log.i(TAG, "Recording stopped due to silence in panic: " + sessionId);
                        notifyRecordingStopped(sessionId);

                        // Notify server that recording is complete (silence timeout)
                        uploader.notifyRecordingComplete(sessionId, totalSegments, "timeout_silencio");
                    }

                    // 2. Limpar o estado do Pânico
                    panicManager.setCriticalAlert(false);
                    panicManager.deactivatePanic("timeout_silencio");

                    // 3. Informa o Capacitor/React do desarme pacífico
                    notifyJavaScript("panicEnded", "timeout_silencio");

                    // 4. Retorna a passividade
                    resumeMonitoring();

                } else {
                    Log.i(TAG, "Silence timeout detected during regular recording, stopping");

                    String sessionId = recorder.stopRecording();
                    if (sessionId != null) {
                        int totalSegments = recorder.getSegmentIndex();
                        locationManager.stopTracking();
                        Log.i(TAG, "Recording stopped due to silence: " + sessionId);
                        notifyRecordingStopped(sessionId);

                        // Notificação de evento: gravação finalizada por silêncio (pânico)
                        showEventNotification("Ampara — Gravação Finalizada",
                                "Gravação encerrada por silêncio prolongado.");

                        // Notify server that recording is complete (silence timeout)
                        uploader.notifyRecordingComplete(sessionId, totalSegments, "timeout");
                    }

                    // Resume monitoring after recording stops
                    resumeMonitoring();
                }
            }
        }

        if (result.shouldStopRecording) {
            boolean isPanic = panicManager.isPanicActive();
            boolean isPanicOrigin = "botao_panico".equals(currentOrigemGravacao);

            // BLINDAGEM DO PÂNICO: Ignorar a IA de áudio se for emergência declarada ou
            // origem pânico
            if (isPanic || isPanicOrigin) {
                Log.i(TAG, String.format("DISCUSSION ENDED ignorada! Blindagem Ativa: PanicActive=%b, Origem=%s",
                        isPanic, currentOrigemGravacao));
            } else {
                Log.i(TAG, String.format("DISCUSSION ENDED! Reason: %s (PanicActive=%b, Origem=%s)",
                        result.reason, isPanic, currentOrigemGravacao));

                // Stop native recording
                String sessionId = recorder.stopRecording();
                if (sessionId != null) {
                    // Clear critical alert state
                    panicManager.setCriticalAlert(false);

                    int totalSegments = recorder.getSegmentIndex();
                    locationManager.stopTracking();

                    Log.i(TAG, "Native recording stopped: " + sessionId);
                    notifyRecordingStopped(sessionId);

                    // Notificação de evento: gravação finalizada por detecção
                    showEventNotification("Ampara — Gravação Finalizada",
                            "A discussão cessou. Áudio sendo enviado.");

                    // Notify server that recording is complete (automatic detection ended)
                    uploader.notifyRecordingComplete(sessionId, totalSegments, "silencio");
                }

                // Resume monitoring after recording stops
                resumeMonitoring();
                notifyJavaScript("discussionEnded", result.reason);
            }
        }
    }

    private void notifyMetrics(double rmsDb, double zcr, boolean isSpeech, boolean isLoud, String state, double score,
            boolean isCalibrated, double speechDensity, double loudDensity, double noiseFloor) {
        Intent intent = new Intent("tech.orizon.ampara.AUDIO_TRIGGER_EVENT");
        intent.setPackage(getPackageName());
        intent.putExtra("event", "audioMetrics");
        intent.putExtra("rmsDb", rmsDb);
        intent.putExtra("zcr", zcr);
        intent.putExtra("isSpeech", isSpeech);
        intent.putExtra("isLoud", isLoud);
        intent.putExtra("state", state);
        intent.putExtra("score", score);
        intent.putExtra("isCalibrated", isCalibrated);
        // Dados extras para a tela técnica de debug
        intent.putExtra("speechDensity", speechDensity);
        intent.putExtra("loudDensity", loudDensity);
        intent.putExtra("noiseFloor", noiseFloor);
        // Dados de silêncio para diagnóstico na tela técnica
        intent.putExtra("isSilent", silenceDetector.isSilent());
        intent.putExtra("silenceDurationMs", silenceDetector.getSilenceDuration());
        intent.putExtra("silenceTimeoutMs", 600000L); // 10 minutos
        intent.putExtra("silenceThresholdDb", -40.0);
        intent.putExtra("isRecording", recorder.isRecording());
        // Timers e contadores do DiscussionDetector para tela técnica
        intent.putExtra("timeInStateMs", detector.getTimeInStateMs());
        intent.putExtra("continuousSilenceMs", detector.getContinuousSilenceMs());
        intent.putExtra("isManualRecording", detector.getIsManualRecording());
        intent.putExtra("startHoldSeconds", detector.getStartHoldSeconds());
        intent.putExtra("endHoldSeconds", detector.getEndHoldSeconds());
        intent.putExtra("silenceDecaySeconds", detector.getSilenceDecaySeconds());
        intent.putExtra("cooldownSeconds", detector.getCooldownSeconds());
        intent.putExtra("speechDensityMin", detector.getSpeechDensityMin());
        intent.putExtra("loudDensityMin", detector.getLoudDensityMin());
        intent.putExtra("speechDensityEnd", detector.getSpeechDensityEnd());
        intent.putExtra("loudDensityEnd", detector.getLoudDensityEnd());
        intent.putExtra("timestamp", System.currentTimeMillis());
        sendBroadcast(intent);
    }

    private void notifyJavaScript(String event, String reason) {
        // Send explicit broadcast to JavaScript (required for Android 14+)
        Intent intent = new Intent("tech.orizon.ampara.AUDIO_TRIGGER_EVENT");
        intent.setPackage(getPackageName()); // Make it explicit
        intent.putExtra("event", event);
        intent.putExtra("reason", reason);
        intent.putExtra("timestamp", System.currentTimeMillis());
        sendBroadcast(intent);

        Log.d(TAG, "Broadcast sent: " + event);
    }

    private void notifyRecordingStarted(String sessionId) {
        Intent intent = new Intent("tech.orizon.ampara.AUDIO_TRIGGER_EVENT");
        intent.setPackage(getPackageName());
        long now = System.currentTimeMillis();
        intent.putExtra("event", "nativeRecordingStarted");
        intent.putExtra("sessionId", sessionId);
        intent.putExtra("origemGravacao", currentOrigemGravacao);
        intent.putExtra("startedAt", now);
        intent.putExtra("timestamp", now);
        sendBroadcast(intent);

        Log.d(TAG, "Recording started broadcast sent: " + sessionId + ", origem: " + currentOrigemGravacao);
    }

    private void notifyRecordingStopped(String sessionId) {
        Intent intent = new Intent("tech.orizon.ampara.AUDIO_TRIGGER_EVENT");
        intent.setPackage(getPackageName());
        intent.putExtra("event", "nativeRecordingStopped");
        intent.putExtra("sessionId", sessionId);
        intent.putExtra("timestamp", System.currentTimeMillis());
        sendBroadcast(intent);

        Log.d(TAG, "Recording stopped broadcast sent: " + sessionId);
    }

    private void notifyRecordingProgress(String sessionId, int segmentIndex) {
        Intent intent = new Intent("tech.orizon.ampara.AUDIO_TRIGGER_EVENT");
        intent.setPackage(getPackageName());
        intent.putExtra("event", "nativeRecordingProgress");
        intent.putExtra("sessionId", sessionId);
        intent.putExtra("segmentIndex", segmentIndex);
        intent.putExtra("timestamp", System.currentTimeMillis());
        sendBroadcast(intent);

        Log.d(TAG, String.format("Recording progress broadcast sent: %s segment %d", sessionId, segmentIndex));
    }

    private void notifyUploadProgress(int pending, int success, int failure) {
        Intent intent = new Intent("tech.orizon.ampara.AUDIO_TRIGGER_EVENT");
        intent.setPackage(getPackageName());
        intent.putExtra("event", "nativeUploadProgress");
        intent.putExtra("pending", pending);
        intent.putExtra("success", success);
        intent.putExtra("failure", failure);
        intent.putExtra("timestamp", System.currentTimeMillis());
        sendBroadcast(intent);
    }

    private void notifyCalibrationStatus(boolean isCalibrated) {
        Intent intent = new Intent("tech.orizon.ampara.AUDIO_TRIGGER_EVENT");
        intent.setPackage(getPackageName());
        intent.putExtra("event", "calibrationStatus");
        intent.putExtra("isCalibrated", isCalibrated);
        intent.putExtra("timestamp", System.currentTimeMillis());
        sendBroadcast(intent);
    }

    private void notifyPanicState() {
        // Proteção: panicManager pode ser null se serviço não inicializou completamente
        if (panicManager == null) {
            Log.w(TAG, "notifyPanicState called but panicManager is null, skipping");
            return;
        }
        Intent intent = new Intent("tech.orizon.ampara.AUDIO_TRIGGER_EVENT");
        intent.setPackage(getPackageName());
        intent.putExtra("event", "panicState");
        intent.putExtra("isPanicActive", panicManager.isPanicActive());
        intent.putExtra("panicStartTime", panicManager.getPanicStartTime());
        intent.putExtra("protocolNumber", panicManager.getProtocolNumber());
        intent.putExtra("timestamp", System.currentTimeMillis());
        sendBroadcast(intent);

        Log.d(TAG, String.format("Panic state broadcast sent: active=%s", panicManager.isPanicActive()));
    }

    private void notifyRecordingState() {
        // Proteção: recorder pode ser null se serviço não inicializou completamente
        if (recorder == null) {
            Log.w(TAG, "notifyRecordingState called but recorder is null, skipping");
            return;
        }
        boolean isRecording = recorder.isRecording();
        String currentSessionId = recorder.getSessionId();
        long sessionStartTime = recorder.getSessionStartTime();

        Intent intent = new Intent("tech.orizon.ampara.AUDIO_TRIGGER_EVENT");
        intent.setPackage(getPackageName());
        intent.putExtra("event", "recordingState");
        intent.putExtra("isRecording", isRecording);
        intent.putExtra("sessionId", currentSessionId);
        intent.putExtra("startedAt", sessionStartTime);
        intent.putExtra("timestamp", System.currentTimeMillis());
        sendBroadcast(intent);

        Log.d(TAG, String.format("Recording state broadcast sent: isRecording=%s, sessionId=%s, startedAt=%d",
                isRecording, currentSessionId, sessionStartTime));
    }

    private void notifyFgsNotEligible() {
        Intent intent = new Intent("tech.orizon.ampara.AUDIO_TRIGGER_EVENT");
        intent.setPackage(getPackageName());
        intent.putExtra("event", "fgsNotEligible");
        intent.putExtra("reason", "App must be in foreground to start microphone service on Android 14+");
        intent.putExtra("timestamp", System.currentTimeMillis());
        sendBroadcast(intent);

        Log.d(TAG, "FGS not eligible broadcast sent");
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Proteção Ativa",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Ampara está monitorando áudio em segundo plano");
            channel.setShowBadge(false);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    /**
     * Cria canal de notificações de eventos (silencioso, sem vibração)
     * Usado para alertar sobre entradas/saídas de período e gravações
     */
    private void createEventsNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    EVENTS_CHANNEL_ID,
                    "Eventos Ampara",
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Notificações silenciosas de eventos do Ampara");
            channel.setShowBadge(true);
            channel.enableVibration(false);
            channel.setSound(null, null);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    /**
     * Exibe notificação de evento silenciosa (visível na tela bloqueada)
     * Respeita a preferência do usuário salva em SharedPreferences
     */
    private void showEventNotification(String title, String message) {
        try {
            // Verificar preferência do usuário
            android.content.SharedPreferences prefs = getSharedPreferences("AmparaPrefs", MODE_PRIVATE);
            boolean enabled = prefs.getBoolean("notifications_enabled", true);
            if (!enabled) {
                Log.d(TAG, "Event notification suppressed (disabled by user): " + title);
                return;
            }

            Intent intent = new Intent(this, MainActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

            Notification notification = new NotificationCompat.Builder(this, EVENTS_CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .build();

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.notify(eventNotificationId++, notification);
                Log.d(TAG, "Event notification shown: " + title);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error showing event notification", e);
        }
    }

    private Notification createNotification() {
        return createNotification("Monitorando áudio em segundo plano");
    }

    private Notification createNotification(String contentText) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Ampara - Proteção Ativa")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotificationForRecording() {
        Notification notification = createNotification("🔴 Gravando discussão...");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, notification);
            Log.d(TAG, "Notification updated: Recording");
        }
    }

    private void updateNotificationForMonitoring() {
        Notification notification = createNotification("Monitorando áudio em segundo plano");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, notification);
            Log.d(TAG, "Notification updated: Monitoring");
        }
    }

    private void acquireWakeLock() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            if (powerManager != null && (wakeLock == null || !wakeLock.isHeld())) {
                if (wakeLock == null) {
                    wakeLock = powerManager.newWakeLock(
                            PowerManager.PARTIAL_WAKE_LOCK,
                            "Ampara::AudioTriggerWakeLock");
                }
                // Trilha 1: sempre com timeout (evita wakelock infinito em crashes)
                wakeLock.acquire(WAKELOCK_TIMEOUT_MS);
                Log.d(TAG, "WakeLock acquired (timeout ms=" + WAKELOCK_TIMEOUT_MS + ")");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error acquiring WakeLock", e);
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                wakeLock = null;
                Log.d(TAG, "WakeLock released");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error releasing WakeLock", e);
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "AudioTriggerService destroyed");

        // Stop recording if active
        if (recorder != null) {
            recorder.destroy();
        }

        // Stop location tracking
        if (locationManager != null) {
            locationManager.stopTracking();
        }

        // Stop upload queue
        if (uploadQueue != null) {
            uploadQueue.stop();
        }

        // Cancelar retry periódico
        if (uploadRetryRunnable != null) {
            uploadRetryHandler.removeCallbacks(uploadRetryRunnable);
            uploadRetryRunnable = null;
        }

        stopAudioCapture();
        abandonAudioFocus();
        unregisterNetworkCallback();
        releaseWakeLock();
        super.onDestroy();
    }

    private void stopAudioCapture() {
        isRunning = false;

        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
                audioRecord.release();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping audio record", e);
            }
            audioRecord = null;
        }

        if (processingThread != null) {
            try {
                processingThread.join(1000);
            } catch (InterruptedException e) {
                Log.e(TAG, "Error joining processing thread", e);
            }
            processingThread = null;
        }

        Log.d(TAG, "Audio capture stopped");
        currentMicState = MicrophoneState.IDLE;
    }

    /**
     * Pause monitoring (stop AudioRecord) to allow MediaRecorder to access
     * microphone
     * Transition: MONITORING -> (paused)
     */
    private void pauseMonitoring() {
        if (currentMicState != MicrophoneState.MONITORING) {
            Log.w(TAG, "[MicState] Cannot pause monitoring, current state: " + currentMicState);
            return;
        }

        Log.i(TAG, "[MicState] MONITORING -> (paused): Stopping AudioRecord for recording");

        isRunning = false;

        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
                audioRecord.release();
                audioRecord = null;
                Log.d(TAG, "[MicState] AudioRecord stopped and released");
            } catch (Exception e) {
                Log.e(TAG, "[MicState] Error stopping AudioRecord", e);
            }
        }

        if (processingThread != null) {
            try {
                processingThread.join(1000);
            } catch (InterruptedException e) {
                Log.e(TAG, "[MicState] Error joining processing thread", e);
            }
            processingThread = null;
        }
    }

    /**
     * Resume monitoring (restart AudioRecord) after MediaRecorder finishes
     * Transition: (paused) -> MONITORING
     */
    private void resumeMonitoring() {
        if (currentMicState == MicrophoneState.MONITORING) {
            Log.w(TAG, "[MicState] Already in MONITORING state");
            return;
        }

        Log.i(TAG, "[MicState] (paused) -> MONITORING: Restarting AudioRecord after recording");
        startAudioCapture();
    }

    /**
     * Start simulated aggregations during recording (when AudioRecord is paused)
     * Sends silence metrics every 1s to allow DiscussionDetector to detect end of
     * discussion
     */
    private void startRecordingAggregations() {
        stopRecordingAggregations(); // Stop any existing timer

        recordingAggregationRunnable = new Runnable() {
            @Override
            public void run() {
                if (recorder.isRecording()) {
                    // Get real amplitude from MediaRecorder (0-32767)
                    int amplitude = recorder.getMaxAmplitude();

                    // Convert to estimated RMS dB (Reference 32768 for 16-bit)
                    // Floor at -100 dB for silence
                    double estimatedRms = amplitude > 0 ? 20 * Math.log10(amplitude / 32768.0) : -100.0;

                    // Simulated stable ZCR (0.1) since we don't have raw samples
                    double simulatedZcr = 0.1;

                    // Create metrics for detector
                    DiscussionDetector.AggregationMetrics recordingMetrics = new DiscussionDetector.AggregationMetrics(
                            estimatedRms, simulatedZcr, estimatedRms > -50.0, estimatedRms > -30.0);

                    // Process with detector to get a real score based on loudness
                    DiscussionDetector.DetectionResult result = detector.process(recordingMetrics);

                    // Send metrics to JS for UI (real estimated score)
                    double speechNorm = Math.min(result.speechDensity / 0.5, 1.0);
                    double loudNorm = Math.min(result.loudDensity / 0.3, 1.0);
                    double discussionScore = (speechNorm + loudNorm) / 2.0;

                    notifyMetrics(estimatedRms, simulatedZcr, recordingMetrics.isSpeech, recordingMetrics.isLoud,
                            detector.getStateString(), discussionScore, detector.isCalibrated(),
                            result.speechDensity, result.loudDensity, detector.getNoiseFloor());

                    // Check if should stop recording
                    if (result.shouldStopRecording) {
                        boolean isPanic = panicManager.isPanicActive();
                        boolean isPanicOrigin = "botao_panico".equals(currentOrigemGravacao);

                        // BLINDAGEM DO PÂNICO: Ignorar a IA de áudio se for emergência declarada ou
                        // origem pânico
                        if (isPanic || isPanicOrigin) {
                            Log.i(TAG, String.format(
                                    "DISCUSSION ENDED ignorada em simulação! Blindagem Ativa: PanicActive=%b, Origem=%s",
                                    isPanic, currentOrigemGravacao));
                            // Schedule next aggregation to keep simulation alive
                            recordingHandler.postDelayed(this, 1000);
                        } else {
                            Log.i(TAG, String.format("DISCUSSION ENDED! Reason: %s (PanicActive=%b, Origem=%s)",
                                    result.reason, isPanic, currentOrigemGravacao));

                            // Stop native recording
                            String sessionId = recorder.stopRecording();
                            if (sessionId != null) {
                                int totalSegments = recorder.getSegmentIndex();
                                locationManager.stopTracking();
                                Log.i(TAG, "Native recording stopped: " + sessionId);
                                notifyRecordingStopped(sessionId);

                                // Notify server that recording is complete (silence detected)
                                uploader.notifyRecordingComplete(sessionId, totalSegments, "silencio");
                            }

                            // Stop simulated aggregations
                            stopRecordingAggregations();

                            // Resume monitoring
                            resumeMonitoring();

                            notifyJavaScript("discussionEnded", result.reason);
                        }
                    } else {
                        // Schedule next aggregation
                        recordingHandler.postDelayed(this, 1000);
                    }
                }
            }
        };

        // Start first aggregation after 1s
        recordingHandler.postDelayed(recordingAggregationRunnable, 1000);
        Log.d(TAG, "Started simulated aggregations during recording");
    }

    /**
     * Stop simulated aggregations
     */
    private void stopRecordingAggregations() {
        if (recordingAggregationRunnable != null) {
            recordingHandler.removeCallbacks(recordingAggregationRunnable);
            recordingAggregationRunnable = null;
            Log.d(TAG, "Stopped simulated aggregations");
        }
    }

    /**
     * Request audio focus to access microphone
     * Returns true if focus granted, false otherwise
     */
    private boolean requestAudioFocus() {
        if (audioManager == null) {
            Log.e(TAG, "AudioManager not initialized");
            return false;
        }

        if (audioFocusGranted) {
            Log.d(TAG, "Audio focus already granted");
            return true;
        }

        AudioManager.OnAudioFocusChangeListener focusChangeListener = new AudioManager.OnAudioFocusChangeListener() {
            @Override
            public void onAudioFocusChange(int focusChange) {
                switch (focusChange) {
                    case AudioManager.AUDIOFOCUS_LOSS:
                        // Permanent loss - another app took focus
                        Log.w(TAG, "[AudioFocus] LOSS - Microphone requested by another app");
                        handleAudioFocusLoss("mic_solicitado_permanente");
                        break;

                    case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                        // Temporary loss - phone call, WhatsApp audio, etc.
                        Log.w(TAG, "[AudioFocus] LOSS_TRANSIENT - Microphone requested temporarily");
                        handleAudioFocusLoss("mic_solicitado");
                        break;

                    case AudioManager.AUDIOFOCUS_GAIN:
                        // Focus regained
                        Log.i(TAG, "[AudioFocus] GAIN - Microphone available again");
                        handleAudioFocusGain();
                        break;
                }
            }
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Android 8.0+ (API 26+)
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();

            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(audioAttributes)
                    .setOnAudioFocusChangeListener(focusChangeListener)
                    .build();

            int result = audioManager.requestAudioFocus(audioFocusRequest);
            audioFocusGranted = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);

            if (audioFocusGranted) {
                Log.i(TAG, "[AudioFocus] Granted (API 26+)");
            } else {
                Log.e(TAG, "[AudioFocus] Denied (API 26+)");
            }
        } else {
            // Android 7.1 and below
            int result = audioManager.requestAudioFocus(
                    focusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN);
            audioFocusGranted = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);

            if (audioFocusGranted) {
                Log.i(TAG, "[AudioFocus] Granted (legacy API)");
            } else {
                Log.e(TAG, "[AudioFocus] Denied (legacy API)");
            }
        }

        return audioFocusGranted;
    }

    /**
     * Abandon audio focus when stopping service
     */
    private void abandonAudioFocus() {
        if (audioManager == null || !audioFocusGranted) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
            Log.i(TAG, "[AudioFocus] Abandoned (API 26+)");
        } else {
            // Legacy API doesn't need explicit abandon with listener
            Log.i(TAG, "[AudioFocus] Abandoned (legacy API)");
        }

        audioFocusGranted = false;
        audioFocusRequest = null;
    }

    /**
     * Handle audio focus loss - pause monitoring and/or stop recording
     */
    private void handleAudioFocusLoss(String motivo) {
        Log.w(TAG, "[AudioFocus] Handling focus loss, current state: " + currentMicState);

        // If recording, stop recording with motivo
        if (currentMicState == MicrophoneState.RECORDING && recorder != null && recorder.isRecording()) {
            Log.w(TAG, "[AudioFocus] Stopping recording due to: " + motivo);
            String sessionId = recorder.stopRecording();

            if (sessionId != null && uploader != null) {
                int totalSegments = recorder.getSegmentIndex();
                uploader.notifyRecordingComplete(sessionId, totalSegments, motivo);
                Log.i(TAG, "[AudioFocus] Recording stopped and queued with motivo: " + motivo);
            }

            currentMicState = MicrophoneState.MONITORING;
        }

        // Pause monitoring (stop AudioRecord)
        if (currentMicState == MicrophoneState.MONITORING) {
            pauseMonitoring();
            currentMicState = MicrophoneState.IDLE;
        }

        audioFocusGranted = false;
    }

    /**
     * Handle audio focus gain - resume monitoring
     */
    private void handleAudioFocusGain() {
        Log.i(TAG, "[AudioFocus] Handling focus gain, current state: " + currentMicState);

        audioFocusGranted = true;

        // Resume monitoring if idle
        if (currentMicState == MicrophoneState.IDLE) {
            resumeMonitoring();
        }
    }

    private void registerNetworkCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(
                    Context.CONNECTIVITY_SERVICE);
            if (connectivityManager != null) {
                networkCallback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        super.onAvailable(network);
                        Log.i(TAG, "Internet connection available - triggering upload retry scan");
                        if (uploadQueue != null) {
                            uploadQueue.start();
                            uploadQueue.retryPendingUploads("recovery", currentOrigemGravacao);
                        }
                    }
                };

                NetworkRequest networkRequest = new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build();
                connectivityManager.registerNetworkCallback(networkRequest, networkCallback);
                Log.d(TAG, "Network callback registered (Android 7+)");
            }
        } else {
            // Fallback for older devices (if any) could use a BroadcastReceiver
            Log.w(TAG, "Network monitoring not implemented for Android < 7");
        }
    }

    private void unregisterNetworkCallback() {
        if (networkCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(
                    Context.CONNECTIVITY_SERVICE);
            if (connectivityManager != null) {
                try {
                    connectivityManager.unregisterNetworkCallback(networkCallback);
                    Log.d(TAG, "Network callback unregistered");
                } catch (Exception e) {
                    Log.e(TAG, "Error unregistering network callback", e);
                }
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Load initial credentials from shared preferences
     */
    private void loadInitialCredentials() {
        android.content.SharedPreferences prefs = getSharedPreferences("ampara_secure_storage", Context.MODE_PRIVATE);
        this.deviceId = prefs.getString("ampara_device_id", "native-android-" + System.currentTimeMillis());
        this.sessionToken = prefs.getString("ampara_token", null);

        String userJson = prefs.getString("ampara_user", null);
        if (userJson != null) {
            try {
                org.json.JSONObject userObj = new org.json.JSONObject(userJson);
                this.emailUsuario = userObj.optString("email", null);
            } catch (Exception e) {
                Log.e(TAG, "Error parsing user JSON for credentials", e);
            }
        }

        if (sessionToken != null && emailUsuario != null) {
            Log.d(TAG, "Initial credentials loaded: email=" + emailUsuario + ", deviceId=" + deviceId);
            panicManager.setCredentials(sessionToken, emailUsuario, deviceId);
        }
    }
}
