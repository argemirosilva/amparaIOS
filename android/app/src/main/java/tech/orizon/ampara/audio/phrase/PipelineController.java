package tech.orizon.ampara.audio.phrase;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Controlador do pipeline de detecção multiestágio (SPEC seções 5, 6, 7).
 * 
 * Orquestra os 4 layers, gerencia a state machine, aplica regras de segurança
 * por comando, e mantém métricas de observabilidade.
 * 
 * Fluxo principal:
 * L1 (existente) → trigger → L2 (buffer) → L3 (phrase detection) → L4 (scoring)
 * → ação
 */
public class PipelineController {
    private static final String TAG = "PipelineCtrl";

    // Dependências
    private final Context context;
    private final RingAudioBuffer ringBuffer;
    private final PhraseDetector phraseDetector;
    private final RiskScorer riskScorer;
    private final ScoringConfig config;
    private final PipelineMetrics metrics;
    private final PhraseEnrollmentManager enrollmentManager;

    // Estado
    private PipelineState currentState = PipelineState.IDLE_MONITORING;
    private boolean modeBEnabled = false;

    // Trackers de repetição por comando
    private final Map<String, CommandRepetitionTracker> commandTrackers;

    // Post-trigger: controla a coleta de áudio pós-trigger
    private boolean collectingPostTrigger = false;
    private long postTriggerStartTime;
    private short[] preTriggerAudio;

    // Callback para ações
    private PipelineActionListener actionListener;

    /**
     * Interface para comunicar ações do pipeline ao AudioTriggerService
     */
    public interface PipelineActionListener {
        void onStartRecording(String reason);

        void onStopRecording(String reason);

        void onStartPanic(String reason);

        void onCancelPanic(String reason);

        void onElevateSensitivity(String reason);

        void onRiskScoreUpdate(double score, String level, List<String> reasons);

        void onPhraseDetected(String phraseId, double confidence, String type);
    }

    public PipelineController(Context context, ScoringConfig config,
            PhraseEnrollmentManager enrollmentManager) {
        this.context = context;
        this.config = config;
        this.enrollmentManager = enrollmentManager;

        this.ringBuffer = new RingAudioBuffer(
                config.preTriggerSeconds + config.postTriggerSeconds, 16000);
        this.phraseDetector = new PhraseDetector(enrollmentManager, config);
        this.riskScorer = new RiskScorer(config);
        this.metrics = new PipelineMetrics();

        // Criar trackers para comandos operacionais
        this.commandTrackers = new HashMap<>();
        commandTrackers.put("start_recording", new CommandRepetitionTracker(
                "start_recording", config.repetitionHistoryMaxEntries, config.repetitionHistoryMaxAgeMs));
        commandTrackers.put("stop_recording", new CommandRepetitionTracker(
                "stop_recording", config.repetitionHistoryMaxEntries, config.repetitionHistoryMaxAgeMs));
        commandTrackers.put("start_panic", new CommandRepetitionTracker(
                "start_panic", config.repetitionHistoryMaxEntries, config.repetitionHistoryMaxAgeMs));
        commandTrackers.put("cancel_panic", new CommandRepetitionTracker(
                "cancel_panic", config.repetitionHistoryMaxEntries, config.repetitionHistoryMaxAgeMs));

        Log.i(TAG, "[INIT] PipelineController criado | estado=IDLE_MONITORING");
    }

    /**
     * Registra listener de ações
     */
    public void setActionListener(PipelineActionListener listener) {
        this.actionListener = listener;
    }

    // ========== Alimentação contínua de áudio ==========

    /**
     * Chamado continuamente pelo AudioTriggerService a cada frame de áudio.
     * Alimenta o ring buffer e, se em post-trigger, coleta a janela.
     *
     * @param samples áudio PCM
     * @param offset  início
     * @param length  tamanho
     */
    public void feedAudio(short[] samples, int offset, int length) {
        // Alimentar ring buffer continuamente
        ringBuffer.write(samples, offset, length);

        // Se estamos coletando pós-trigger
        if (collectingPostTrigger) {
            long elapsed = System.currentTimeMillis() - postTriggerStartTime;
            if (elapsed >= config.postTriggerSeconds * 1000L) {
                // Post-trigger completo: montar janela e analisar
                collectingPostTrigger = false;
                onConfirmationWindowReady();
            }
        }
    }

    /**
     * Alimenta RMS em dB para o detector de padrão estável
     */
    public void feedRmsDb(double rmsDb) {
        riskScorer.updateRmsHistory(rmsDb);
    }

    // ========== Trigger de L1 (evento acústico) ==========

    /**
     * Chamado quando o detector acústico existente (L1) gera um trigger.
     * Inicia o processo de confirmação.
     *
     * @param acousticSuspicionScore score acústico de L1 (0.0-1.0)
     * @param eventType              tipo de evento ("SPEECH_BURST", "IMPACT", etc.)
     */
    public void onAcousticTrigger(double acousticSuspicionScore, String eventType) {
        metrics.recordL1Activation();

        // Verificar se deve escalar
        if (acousticSuspicionScore < 0.3 && !"IMPACT".equals(eventType)) {
            return; // L1 ativou mas score baixo demais para escalar
        }

        metrics.recordL1Escalation();
        Log.i(TAG, String.format("[TRIGGER] L1 escalou | score=%.2f | event=%s | estado=%s",
                acousticSuspicionScore, eventType, currentState));

        // Capturar pre-trigger do buffer
        preTriggerAudio = ringBuffer.snapshot(config.preTriggerSeconds);
        if (preTriggerAudio == null) {
            Log.w(TAG, "[TRIGGER] Buffer insuficiente para snapshot");
            return;
        }

        // Iniciar coleta pós-trigger
        collectingPostTrigger = true;
        postTriggerStartTime = System.currentTimeMillis();
        currentState = PipelineState.CONFIRMATION_WINDOW;

        Log.d(TAG, String.format("[TRIGGER] Pre-trigger=%d amostras | aguardando %ds pós-trigger",
                preTriggerAudio.length, config.postTriggerSeconds));
    }

    /**
     * Chamado quando a confirmation window está completa (pre + post trigger)
     */
    private void onConfirmationWindowReady() {
        currentState = PipelineState.PHRASE_ANALYSIS;

        // Montar janela completa: snapshot do buffer (que agora inclui o áudio
        // pós-trigger)
        short[] fullWindow = ringBuffer.snapshot(
                config.preTriggerSeconds + config.postTriggerSeconds);

        if (fullWindow == null) {
            Log.w(TAG, "[WINDOW] Janela de confirmação vazia");
            currentState = PipelineState.IDLE_MONITORING;
            return;
        }

        metrics.recordL2Window(fullWindow.length * 2);

        Log.d(TAG, String.format("[WINDOW] Janela pronta: %d amostras (%.1fs)",
                fullWindow.length, (double) fullWindow.length / 16000));

        // L3: Análise de frases (Modo A — triggered completo)
        PhraseDetector.DetectionResult detectionResult = phraseDetector.analyzeTriggered(fullWindow, 0,
                fullWindow.length);

        metrics.recordL3Result(detectionResult);

        // Processar comandos operacionais ANTES do scoring (SPEC seção 6.2)
        boolean commandExecuted = processOperationalCommands(detectionResult);

        if (!commandExecuted) {
            // L4: Scoring
            currentState = PipelineState.RISK_SCORING;
            // Usar score acústico estimado baseado na existência do trigger
            RiskScorer.RiskResult riskResult = riskScorer.calculateRisk(
                    0.5, "SPEECH_BURST", detectionResult, 0);

            metrics.recordL4Score(riskResult.score, riskResult.triggeredAction != null);

            // Notificar listener
            if (actionListener != null) {
                actionListener.onRiskScoreUpdate(
                        riskResult.score, riskResult.level, riskResult.reasons);

                if (riskResult.triggeredAction != null) {
                    executeAction(riskResult.triggeredAction, riskResult.reasons.toString());
                }
            }
        }

        // Voltar ao idle
        currentState = PipelineState.IDLE_MONITORING;
    }

    // ========== Modo B — Escuta contínua ==========

    /**
     * Chamado periodicamente (a cada ~3s) quando Modo B está ativo.
     * Analisa uma janela curta buscando apenas comandos operacionais.
     *
     * @param samples áudio PCM dos últimos 3s
     * @param offset  início
     * @param length  tamanho
     */
    public void analyzeModeBWindow(short[] samples, int offset, int length) {
        if (!modeBEnabled)
            return;

        metrics.recordModeBWindow();

        PhraseDetector.DetectionResult result = phraseDetector.analyzeContinuous(samples, offset, length);

        if (result.hasMatches()) {
            processOperationalCommands(result);
        }
    }

    // ========== Processamento de comandos operacionais ==========

    /**
     * Processa matches operacionais aplicando a matriz de segurança (SPEC seção 6).
     * Vocabulário genérico NUNCA aciona comandos (regra formal).
     *
     * @return true se algum comando foi executado
     */
    private boolean processOperationalCommands(PhraseDetector.DetectionResult result) {
        List<PhraseDetector.PhraseMatch> opMatches = result.getOperationalMatches();
        if (opMatches.isEmpty())
            return false;

        for (PhraseDetector.PhraseMatch match : opMatches) {
            // Vocabulário genérico NUNCA dispara ação
            if (match.type == PhraseTemplate.PhraseType.GENERIC)
                continue;

            CommandRepetitionTracker tracker = commandTrackers.get(match.phraseId);
            if (tracker == null)
                continue;

            // Registrar match no tracker
            tracker.recordMatch(match.confidence, match.dtwDistance);

            // Verificar cooldown
            int cooldown = config.getCooldownMs(match.phraseId);
            if (!tracker.isCooldownExpired(cooldown)) {
                Log.d(TAG, String.format("[COMMAND] %s em cooldown, ignorando", match.phraseId));
                continue;
            }

            // Aplicar regras de segurança por tipo de comando
            if (isCommandAuthorized(match, tracker)) {
                tracker.markActionExecuted();
                executeAction(match.phraseId, String.format("conf=%.2f", match.confidence));

                // Notificar phrase detected
                if (actionListener != null) {
                    actionListener.onPhraseDetected(
                            match.phraseId, match.confidence, match.type.name());
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Aplica a matriz de segurança por tipo de comando (SPEC seção 6.1)
     */
    private boolean isCommandAuthorized(PhraseDetector.PhraseMatch match,
            CommandRepetitionTracker tracker) {
        switch (match.phraseId) {
            case "start_recording":
                // 🟢 Permissivo: confidence >= 0.70
                return match.confidence >= config.startRecordingConfidence;

            case "stop_recording":
                // 🟡 Moderado: confidence >= 0.80
                return match.confidence >= config.stopRecordingConfidence;

            case "start_panic":
                // 🔴 Restritivo: confidence >= 0.92 (instantâneo)
                // OU confidence >= 0.85 + repetição 2x em 15s
                if (match.confidence >= config.startPanicInstantConfidence) {
                    return true;
                }
                return match.confidence >= config.startPanicConfidence
                        && tracker.hasRepetitions(2, config.startPanicRepetitionWindowMs);

            case "cancel_panic":
                // 🔴🔴 Ultra-restritivo: SEMPRE exige confidence >= 0.90 + 2x em 20s
                return match.confidence >= config.cancelPanicConfidence
                        && tracker.hasRepetitions(
                                config.cancelPanicRepetitions,
                                config.cancelPanicRepetitionWindowMs);

            default:
                return match.confidence >= 0.75;
        }
    }

    /**
     * Executa uma ação operacional ou baseada em score
     */
    private void executeAction(String action, String reason) {
        if (actionListener == null)
            return;

        Log.i(TAG, String.format("[ACTION] %s | reason=%s", action, reason));

        switch (action) {
            case "start_recording":
            case "START_RECORDING":
                actionListener.onStartRecording(reason);
                break;
            case "stop_recording":
                actionListener.onStopRecording(reason);
                break;
            case "start_panic":
            case "START_PANIC":
                actionListener.onStartPanic(reason);
                break;
            case "cancel_panic":
                actionListener.onCancelPanic(reason);
                break;
            case "ELEVATE_SENSITIVITY":
                actionListener.onElevateSensitivity(reason);
                break;
        }
    }

    // ========== Controle de estado ==========

    public void setModeBEnabled(boolean enabled) {
        this.modeBEnabled = enabled;
        Log.i(TAG, "[MODE] Modo B (continuous) = " + enabled);
    }

    public boolean isModeBEnabled() {
        return modeBEnabled;
    }

    public PipelineState getCurrentState() {
        return currentState;
    }

    public PipelineMetrics getMetrics() {
        return metrics;
    }

    /**
     * Pausa o pipeline (ex: interrupção de áudio no iOS)
     */
    public void pause(String reason) {
        Log.i(TAG, String.format("[PAUSE] Pipeline pausado | reason=%s | estado=%s",
                reason, currentState));
        collectingPostTrigger = false;
    }

    /**
     * Retoma o pipeline após pausa
     */
    public void resume() {
        Log.i(TAG, "[RESUME] Pipeline retomado");
        currentState = PipelineState.IDLE_MONITORING;
    }

    /**
     * Registra um gap de monitoramento (iOS interrupção definitiva)
     */
    public void logGap(long startedAt) {
        long duration = System.currentTimeMillis() - startedAt;
        Log.w(TAG, String.format("[GAP] Gap de monitoramento: %.1fs", duration / 1000.0));
    }

    /**
     * Reseta todo o estado do pipeline
     */
    public void reset() {
        currentState = PipelineState.IDLE_MONITORING;
        collectingPostTrigger = false;
        ringBuffer.reset();
        riskScorer.reset();
        metrics.reset();
        for (CommandRepetitionTracker tracker : commandTrackers.values()) {
            tracker.reset();
        }
        Log.i(TAG, "[RESET] Pipeline resetado");
    }

    /**
     * Retorna informações de diagnóstico para o painel técnico
     */
    public String getDiagnostics() {
        return String.format("State=%s | ModeB=%b | %s | %s",
                currentState, modeBEnabled,
                metrics.toDisplayString(),
                phraseDetector.getDiagnostics());
    }
}
