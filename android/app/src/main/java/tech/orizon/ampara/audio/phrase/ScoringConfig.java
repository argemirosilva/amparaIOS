package tech.orizon.ampara.audio.phrase;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuração centralizada de scoring e thresholds do pipeline.
 * 
 * TODOS os pesos, limiares e limites ficam aqui.
 * Nenhum hardcode disperso pelo código.
 * Conforme SPEC v3, seção 10.
 */
public class ScoringConfig {

    // ========== Pesos acústicos ==========
    public double weightSpeechDetected = 0.5;
    public double weightAnomalousSequence = 1.5;
    public double weightAbruptChange = 1.0;
    // weightVoiceOverlap: REMOVIDO da v1 (candidato v2/v3)
    public double weightImpactEvent = 2.5;
    public double weightMultipleEvents = 1.5;

    // ========== Padrão estável (TV/música) — heurística v1 ==========
    // Detecção via variância do RMS em janela de 30s
    public double stablePatternVarianceThreshold = 3.0; // dB²
    public int stablePatternWindowSeconds = 30;

    // ========== Pesos semânticos genéricos (APOIO CONTEXTUAL APENAS) ==========
    public Map<String, Double> genericKeywordWeights;
    /** Vocabulário genérico NUNCA dispara ações críticas sozinho */
    public boolean genericCanTriggerAction = false;

    // ========== Pesos de frases personalizadas ==========
    public double weightOperationalHigh = 8.0;
    public double weightContextualAlert = 3.0;
    public double repetitionBonus = 2.0;
    public double acousticSemanticComboBonus = 1.5;

    // ========== Penalizações ==========
    public double penaltyIsolatedEvent = -1.0;
    public double penaltyMarginalNoPhrase = -1.0;
    /** Aplicada SOMENTE se variância < stablePatternVarianceThreshold */
    public double penaltyStablePattern = -1.5;

    // ========== Faixas de score ==========
    public double bandLow = 2.9;
    public double bandMedium = 6.9;
    public double bandHigh = 10.9;
    // 11+ = crítico

    // ========== Segurança por comando (SPEC seção 6) ==========
    public double startRecordingConfidence = 0.55;
    public double stopRecordingConfidence = 0.65;
    /** Gravação não pode ser parada nos primeiros N ms */
    public int stopRecordingMinDurationMs = 30000;
    public double startPanicConfidence = 0.85;
    /** Se confidence >= instantConfidence, dispara sem exigir repetição */
    public double startPanicInstantConfidence = 0.92;
    public int startPanicRepetitionWindowMs = 15000;
    public double cancelPanicConfidence = 0.90;
    /** Cancelar pânico SEMPRE exige repetição */
    public int cancelPanicRepetitions = 2;
    public int cancelPanicRepetitionWindowMs = 20000;

    // ========== Cooldowns por comando ==========
    public int startRecordingCooldownMs = 5000;
    public int stopRecordingCooldownMs = 10000;
    public int startPanicCooldownMs = 30000;
    public int cancelPanicCooldownMs = 60000;

    // ========== Modo B (escuta contínua) ==========
    /** Boost nos thresholds quando em escuta contínua (evita falso positivo) */
    public double continuousModeConfidenceBoost = 0.10;

    // ========== Limites de frases (SPEC seção 9.4) ==========
    public int maxOperationalPhrases = 4;
    public int maxContextualPhrases = 10;
    public int minSamplesPerPhrase = 3;
    public int maxSamplesPerPhrase = 5;

    // ========== Histórico de repetição (SPEC seção 10.2) ==========
    /** Limpa entradas mais velhas que N ms */
    public int repetitionHistoryMaxAgeMs = 15000;
    public int repetitionHistoryMaxEntries = 8;

    // ========== Confirmation window (L2) ==========
    public int preTriggerSeconds = 3;
    public int postTriggerSeconds = 3;

    // ========== Enrollment ==========
    /** Duração mínima de frase para aceitar (ms) */
    public int enrollMinDurationMs = 500;
    /** Duração máxima de frase para aceitar (ms) */
    public int enrollMaxDurationMs = 5000;
    /** DTW máxima intra-amostras (consistência - AUMENTADO PARA NÃO FALHAR TANTO) */
    public double enrollConsistencyThreshold = 120.0;
    /** DTW mínima inter-frases operacionais (conflito - DIMINUIDO AO EXTREMO PARA PERMITIR 'INICIAR GRAVACAO' E 'PARAR GRAVACAO') */
    public double enrollDivergenceThreshold = 2.0;

    /**
     * Construtor com valores padrão e vocabulário genérico
     */
    public ScoringConfig() {
        genericKeywordWeights = new HashMap<>();
        genericKeywordWeights.put("nao", 1.0);
        genericKeywordWeights.put("para", 2.0);
        genericKeywordWeights.put("chega", 1.5);
        genericKeywordWeights.put("me_deixa", 2.5);
        genericKeywordWeights.put("ajuda", 3.5);
        genericKeywordWeights.put("policia", 3.0);
        genericKeywordWeights.put("socorro", 4.5);
        genericKeywordWeights.put("me_solta", 4.5);
    }

    /**
     * Retorna a confiança mínima para um tipo de comando
     */
    public double getMinConfidence(String commandId) {
        switch (commandId) {
            case "start_recording":
                return startRecordingConfidence;
            case "stop_recording":
                return stopRecordingConfidence;
            case "start_panic":
                return startPanicConfidence;
            case "cancel_panic":
                return cancelPanicConfidence;
            default:
                return 0.75; // padrão para contextuais
        }
    }

    /**
     * Retorna o cooldown para um tipo de comando (ms)
     */
    public int getCooldownMs(String commandId) {
        switch (commandId) {
            case "start_recording":
                return startRecordingCooldownMs;
            case "stop_recording":
                return stopRecordingCooldownMs;
            case "start_panic":
                return startPanicCooldownMs;
            case "cancel_panic":
                return cancelPanicCooldownMs;
            default:
                return 5000;
        }
    }

    /**
     * Classifica nível de risco com base no score
     */
    public String classifyRiskLevel(double score) {
        if (score > bandHigh)
            return "CRITICAL";
        if (score > bandMedium)
            return "HIGH";
        if (score > bandLow)
            return "MEDIUM";
        return "LOW";
    }
}
