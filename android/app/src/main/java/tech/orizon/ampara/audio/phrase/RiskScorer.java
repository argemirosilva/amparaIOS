package tech.orizon.ampara.audio.phrase;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Calculador de risco do pipeline (Layer 4).
 * 
 * Combina sinais acústicos (L1) e semânticos (L3) para produzir
 * um riskScore explicável com reasons[] e classificação de risco.
 * 
 * Conforme SPEC v3, seção 10.
 */
public class RiskScorer {
    private static final String TAG = "RiskScorer";

    private final ScoringConfig config;

    /** Buffer de variância RMS para detecção de padrão estável (TV/música) */
    private final double[] rmsHistory;
    private int rmsHistoryPos;
    private int rmsHistoryCount;

    /**
     * Resultado do cálculo de risco
     */
    public static class RiskResult {
        public final double score;
        public final String level; // LOW, MEDIUM, HIGH, CRITICAL
        public final List<String> reasons;
        public final String triggeredAction; // null se nenhuma

        public RiskResult(double score, String level, List<String> reasons, String triggeredAction) {
            this.score = score;
            this.level = level;
            this.reasons = reasons;
            this.triggeredAction = triggeredAction;
        }

        @Override
        public String toString() {
            return String.format("Risk{score=%.1f, level=%s, action=%s, reasons=%d}",
                    score, level, triggeredAction, reasons.size());
        }
    }

    public RiskScorer(ScoringConfig config) {
        this.config = config;
        // Buffer para variância RMS (1 amostra por segundo × janela de 30s)
        this.rmsHistory = new double[config.stablePatternWindowSeconds];
        this.rmsHistoryPos = 0;
        this.rmsHistoryCount = 0;

        Log.i(TAG, "[INIT] RiskScorer criado");
    }

    /**
     * Calcula riskScore combinando sinais acústicos e resultados de phrase
     * detection.
     *
     * @param acousticSuspicionScore score acústico de L1 (0.0-1.0)
     * @param eventType              tipo de evento acústico detectado
     * @param detectionResult        resultado da análise de frases de L3 (pode ser
     *                               null)
     * @param currentRmsDb           RMS atual em dB (para detecção de padrão
     *                               estável)
     * @return resultado com score, nível, razões e ação
     */
    public RiskResult calculateRisk(double acousticSuspicionScore, String eventType,
            PhraseDetector.DetectionResult detectionResult,
            double currentRmsDb) {
        double score = 0.0;
        List<String> reasons = new ArrayList<>();

        // ==================== Fatores acústicos ====================

        if (eventType != null) {
            double acousticWeight = getAcousticWeight(eventType);
            if (acousticWeight > 0) {
                score += acousticWeight;
                reasons.add(String.format("Acústico: %s (+%.1f)", eventType, acousticWeight));
            }
        }

        // ==================== Fatores semânticos (L3) ====================

        if (detectionResult != null && detectionResult.hasMatches()) {
            for (PhraseDetector.PhraseMatch match : detectionResult.matches) {
                double weight;
                switch (match.type) {
                    case OPERATIONAL:
                        weight = config.weightOperationalHigh * match.confidence;
                        break;
                    case CONTEXTUAL:
                        weight = config.weightContextualAlert * match.confidence;
                        break;
                    case GENERIC:
                        // Vocabulário genérico: peso via tabela, mas NUNCA dispara ação sozinho
                        Double kwWeight = config.genericKeywordWeights.get(match.phraseId);
                        weight = (kwWeight != null ? kwWeight : 1.0) * match.confidence;
                        break;
                    default:
                        weight = 0;
                }

                score += weight;
                reasons.add(String.format("Frase: %s (conf=%.2f, +%.1f)",
                        match.phraseId, match.confidence, weight));

                // Bônus por combinação acústico + semântico
                if (acousticSuspicionScore > 0.5) {
                    score += config.acousticSemanticComboBonus;
                    reasons.add(String.format("Combo acústico+semântico (+%.1f)",
                            config.acousticSemanticComboBonus));
                }
            }
        }

        // ==================== Penalizações ====================

        // Padrão estável (TV/música): variância baixa do RMS
        updateRmsHistory(currentRmsDb);
        if (isStablePattern()) {
            score += config.penaltyStablePattern; // negativo
            reasons.add(String.format("Padrão estável detectado (%.1f)",
                    config.penaltyStablePattern));
        }

        // Evento isolado sem persistência nem frase
        if (detectionResult != null && !detectionResult.hasMatches()
                && acousticSuspicionScore < 0.5) {
            score += config.penaltyIsolatedEvent;
            reasons.add(String.format("Evento isolado sem frase (%.1f)",
                    config.penaltyIsolatedEvent));
        }

        // Score marginal sem match semântico
        if (score > 0 && score < config.bandLow
                && (detectionResult == null || !detectionResult.hasMatches())) {
            score += config.penaltyMarginalNoPhrase;
            reasons.add(String.format("Score marginal sem semântica (%.1f)",
                    config.penaltyMarginalNoPhrase));
        }

        // Floor em zero
        score = Math.max(0.0, score);

        // Classificar nível
        String level = config.classifyRiskLevel(score);

        // Determinar ação (baseada no score, não em comandos operacionais)
        String action = determineAction(score, level);

        RiskResult result = new RiskResult(score, level, reasons, action);

        Log.i(TAG, String.format("[SCORE] %.1f (%s) | reasons=%d | action=%s",
                score, level, reasons.size(), action));

        return result;
    }

    // ========== Detecção de padrão estável (TV/música) ==========

    /**
     * Atualiza o histórico de RMS para cálculo de variância
     */
    public void updateRmsHistory(double rmsDb) {
        rmsHistory[rmsHistoryPos] = rmsDb;
        rmsHistoryPos = (rmsHistoryPos + 1) % rmsHistory.length;
        if (rmsHistoryCount < rmsHistory.length)
            rmsHistoryCount++;
    }

    /**
     * Detecta padrão estável via variância do RMS.
     * TV/música têm variância mais baixa que conversas reais.
     */
    private boolean isStablePattern() {
        if (rmsHistoryCount < rmsHistory.length / 2)
            return false; // precisa de histórico mínimo

        double mean = 0;
        for (int i = 0; i < rmsHistoryCount; i++) {
            mean += rmsHistory[i];
        }
        mean /= rmsHistoryCount;

        double variance = 0;
        for (int i = 0; i < rmsHistoryCount; i++) {
            double diff = rmsHistory[i] - mean;
            variance += diff * diff;
        }
        variance /= rmsHistoryCount;

        return variance < config.stablePatternVarianceThreshold;
    }

    /**
     * Determina ação com base no score (não em comandos operacionais)
     */
    private String determineAction(double score, String level) {
        switch (level) {
            case "CRITICAL":
                return "START_PANIC";
            case "HIGH":
                return "START_RECORDING";
            case "MEDIUM":
                return "ELEVATE_SENSITIVITY";
            default:
                return null;
        }
    }

    /**
     * Retorna peso acústico pelo tipo de evento
     */
    private double getAcousticWeight(String eventType) {
        switch (eventType) {
            case "SPEECH_BURST":
                return config.weightSpeechDetected;
            case "ANOMALOUS_SEQ":
                return config.weightAnomalousSequence;
            case "ABRUPT_CHANGE":
                return config.weightAbruptChange;
            case "IMPACT":
                return config.weightImpactEvent;
            case "MULTIPLE_EVENTS":
                return config.weightMultipleEvents;
            default:
                return 0.0;
        }
    }

    /**
     * Reseta o estado do scorer
     */
    public void reset() {
        rmsHistoryPos = 0;
        rmsHistoryCount = 0;
        java.util.Arrays.fill(rmsHistory, 0);
    }
}
