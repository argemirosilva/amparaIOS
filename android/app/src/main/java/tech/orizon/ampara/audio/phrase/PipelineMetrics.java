package tech.orizon.ampara.audio.phrase;

import android.util.Log;

/**
 * Métricas de observabilidade do pipeline (SPEC seção 11).
 * 
 * Registra contadores e taxas por estágio para calibração e debug.
 */
public class PipelineMetrics {
    private static final String TAG = "PipelineMetrics";

    // Contadores L1
    private long l1Activations;
    private long l1Escalations;

    // Contadores L2
    private long l2WindowsBuilt;
    private long l2BytesProcessed;

    // Contadores L3
    private long l3MatchesFound;
    private long l3TotalComparisons;
    private long l3TotalProcessingTimeMs;

    // Contadores L4
    private long l4ScoresCalculated;
    private long l4ActionsTriggered;
    private double l4ScoreSum;

    // Contadores Modo B
    private long modeBWindowsAnalyzed;
    private long modeBFalseWakes;

    // Timestamp de início
    private long startTimestamp;

    public PipelineMetrics() {
        reset();
    }

    // ========== Registro de eventos ==========

    public void recordL1Activation() {
        l1Activations++;
    }

    public void recordL1Escalation() {
        l1Escalations++;
    }

    public void recordL2Window(int sizeBytes) {
        l2WindowsBuilt++;
        l2BytesProcessed += sizeBytes;
    }

    public void recordL3Result(PhraseDetector.DetectionResult result) {
        l3TotalComparisons += result.segmentsAnalyzed;
        l3MatchesFound += result.matches.size();
        l3TotalProcessingTimeMs += result.totalProcessingTimeMs;
    }

    public void recordL4Score(double score, boolean actionTriggered) {
        l4ScoresCalculated++;
        l4ScoreSum += score;
        if (actionTriggered)
            l4ActionsTriggered++;
    }

    public void recordModeBWindow() {
        modeBWindowsAnalyzed++;
    }

    public void recordModeBFalseWake() {
        modeBFalseWakes++;
    }

    // ========== Getters ==========

    /** Taxa de escalação (L1→L2) como percentual */
    public double getEscalationRate() {
        return l1Activations > 0 ? (double) l1Escalations / l1Activations * 100 : 0;
    }

    /** Taxa de match L3 por comparação */
    public double getL3MatchRate() {
        return l3TotalComparisons > 0 ? (double) l3MatchesFound / l3TotalComparisons * 100 : 0;
    }

    /** Tempo médio de processamento L3 (ms) */
    public double getL3AvgProcessingMs() {
        return l3TotalComparisons > 0 ? (double) l3TotalProcessingTimeMs / l3TotalComparisons : 0;
    }

    /** Score médio L4 */
    public double getL4AvgScore() {
        return l4ScoresCalculated > 0 ? l4ScoreSum / l4ScoresCalculated : 0;
    }

    /** Tempo de operação em minutos */
    public double getUptimeMinutes() {
        return (System.currentTimeMillis() - startTimestamp) / 60000.0;
    }

    // ========== Reset e Log ==========

    public void reset() {
        l1Activations = l1Escalations = 0;
        l2WindowsBuilt = l2BytesProcessed = 0;
        l3MatchesFound = l3TotalComparisons = l3TotalProcessingTimeMs = 0;
        l4ScoresCalculated = l4ActionsTriggered = 0;
        l4ScoreSum = 0;
        modeBWindowsAnalyzed = modeBFalseWakes = 0;
        startTimestamp = System.currentTimeMillis();
    }

    /**
     * Log completo das métricas
     */
    public void logSummary() {
        Log.i(TAG, String.format(
                "[METRICS] uptime=%.1fmin | L1: act=%d esc=%d (%.1f%%) | L2: win=%d | " +
                        "L3: matches=%d comps=%d (%.1f%%) avgMs=%.1f | L4: scores=%d actions=%d avgScore=%.1f | " +
                        "ModeB: win=%d falseWake=%d",
                getUptimeMinutes(),
                l1Activations, l1Escalations, getEscalationRate(),
                l2WindowsBuilt,
                l3MatchesFound, l3TotalComparisons, getL3MatchRate(), getL3AvgProcessingMs(),
                l4ScoresCalculated, l4ActionsTriggered, getL4AvgScore(),
                modeBWindowsAnalyzed, modeBFalseWakes));
    }

    /**
     * Retorna snapshot das métricas como string para UI
     */
    public String toDisplayString() {
        return String.format(
                "L1: %d/%d (%.0f%%) | L3: %d matches | L4: avg=%.1f | ModeB: %d",
                l1Escalations, l1Activations, getEscalationRate(),
                l3MatchesFound, getL4AvgScore(), modeBWindowsAnalyzed);
    }

    // ========== Getters diretos ==========

    public long getL1Activations() {
        return l1Activations;
    }

    public long getL1Escalations() {
        return l1Escalations;
    }

    public long getL2WindowsBuilt() {
        return l2WindowsBuilt;
    }

    public long getL3MatchesFound() {
        return l3MatchesFound;
    }

    public long getL4ActionsTriggered() {
        return l4ActionsTriggered;
    }

    public long getModeBWindowsAnalyzed() {
        return modeBWindowsAnalyzed;
    }
}
