package tech.orizon.ampara.audio.phrase;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Detector de frases personalizadas e vocabulário genérico (Layer 3).
 * 
 * Orquestra extração MFCC + matching DTW contra todos os templates cadastrados.
 * Opera em dois modos:
 * - Modo A (Triggered): análise completa da confirmation window
 * - Modo B (Continuous): análise leve só de comandos operacionais
 * 
 * Conforme SPEC v3, seções 4.3 e 5.
 */
public class PhraseDetector {
    private static final String TAG = "PhraseDetector";

    private final MFCCExtractor mfccExtractor;
    private final DTWMatcher dtwMatcher;
    private final PhraseEnrollmentManager enrollmentManager;
    private final ScoringConfig config;

    /**
     * Resultado de detecção de uma frase
     */
    public static class PhraseMatch {
        public final String phraseId;
        public final PhraseTemplate.PhraseType type;
        public final double confidence;
        public final double dtwDistance;
        public final double threshold;
        public final int templateIndex;
        public final long processingTimeMs;

        public PhraseMatch(String phraseId, PhraseTemplate.PhraseType type,
                double confidence, double dtwDistance, double threshold,
                int templateIndex, long processingTimeMs) {
            this.phraseId = phraseId;
            this.type = type;
            this.confidence = confidence;
            this.dtwDistance = dtwDistance;
            this.threshold = threshold;
            this.templateIndex = templateIndex;
            this.processingTimeMs = processingTimeMs;
        }

        @Override
        public String toString() {
            return String.format("PhraseMatch{%s, type=%s, conf=%.2f, dist=%.2f}",
                    phraseId, type, confidence, dtwDistance);
        }
    }

    /**
     * Resultado completo de análise da confirmation window
     */
    public static class DetectionResult {
        public final List<PhraseMatch> matches;
        public final int segmentsAnalyzed;
        public final long totalProcessingTimeMs;
        public final boolean isTriggeredMode;

        public DetectionResult(List<PhraseMatch> matches, int segmentsAnalyzed,
                long totalProcessingTimeMs, boolean isTriggeredMode) {
            this.matches = matches;
            this.segmentsAnalyzed = segmentsAnalyzed;
            this.totalProcessingTimeMs = totalProcessingTimeMs;
            this.isTriggeredMode = isTriggeredMode;
        }

        public boolean hasMatches() {
            return !matches.isEmpty();
        }

        /** Retorna o match com maior confiança */
        public PhraseMatch getBestMatch() {
            PhraseMatch best = null;
            for (PhraseMatch m : matches) {
                if (best == null || m.confidence > best.confidence)
                    best = m;
            }
            return best;
        }

        /** Retorna matches operacionais */
        public List<PhraseMatch> getOperationalMatches() {
            List<PhraseMatch> ops = new ArrayList<>();
            for (PhraseMatch m : matches) {
                if (m.type == PhraseTemplate.PhraseType.OPERATIONAL)
                    ops.add(m);
            }
            return ops;
        }
    }

    public PhraseDetector(PhraseEnrollmentManager enrollmentManager, ScoringConfig config) {
        this.mfccExtractor = new MFCCExtractor();
        this.dtwMatcher = new DTWMatcher();
        this.enrollmentManager = enrollmentManager;
        this.config = config;

        Log.i(TAG, "[INIT] PhraseDetector criado");
    }

    /**
     * Modo A — Análise completa da confirmation window (triggered).
     * Compara contra TODOS os templates: operacionais, contextuais e genéricos.
     *
     * @param audioSamples áudio PCM 16kHz da confirmation window
     * @param offset       início
     * @param length       quantidade de amostras
     * @return resultado com todos os matches encontrados
     */
    public DetectionResult analyzeTriggered(short[] audioSamples, int offset, int length) {
        long startTime = System.nanoTime();

        // Extrair MFCC do áudio capturado
        float[][] queryMfcc = mfccExtractor.extract(audioSamples, offset, length);
        if (queryMfcc == null) {
            Log.w(TAG, "[TRIGGERED] Falha na extração MFCC");
            return new DetectionResult(new ArrayList<>(), 0, 0, true);
        }

        List<PhraseMatch> matches = new ArrayList<>();
        int segmentsChecked = 0;

        // Comparar contra todos os templates cadastrados
        Map<String, List<PhraseTemplate>> allTemplates = enrollmentManager.getAllTemplates();
        for (Map.Entry<String, List<PhraseTemplate>> entry : allTemplates.entrySet()) {
            String phraseId = entry.getKey();
            List<PhraseTemplate> templates = entry.getValue();
            if (templates.isEmpty())
                continue;

            PhraseTemplate.PhraseType type = templates.get(0).getType();
            double minConf = config.getMinConfidence(phraseId);

            // Comparar com todos os templates da frase
            DTWMatcher.MatchResult best = dtwMatcher.matchBest(
                    queryMfcc,
                    templates.toArray(new PhraseTemplate[0]),
                    minConf);

            segmentsChecked++;

            if (best != null && best.isMatch) {
                PhraseTemplate usedTemplate = templates.get(best.templateIndex - 1);
                matches.add(new PhraseMatch(
                        phraseId, type, best.confidence, best.distance,
                        usedTemplate.getThreshold(), best.templateIndex,
                        best.processingTimeMs));

                Log.i(TAG, String.format(
                        "[TRIGGERED] MATCH: %s | conf=%.2f | dist=%.2f | threshold=%.2f | t%d",
                        phraseId, best.confidence, best.distance,
                        usedTemplate.getThreshold(), best.templateIndex));
            } else if (best != null) {
                Log.d(TAG, String.format(
                        "[TRIGGERED] NO_MATCH: %s | bestConf=%.2f | dist=%.2f | reason=BELOW_THRESHOLD",
                        phraseId, best.confidence, best.distance));
            }
        }

        long elapsed = (System.nanoTime() - startTime) / 1_000_000;
        Log.i(TAG, String.format(
                "[TRIGGERED] Análise completa | %d matches em %d frases | %dms",
                matches.size(), segmentsChecked, elapsed));

        return new DetectionResult(matches, segmentsChecked, elapsed, true);
    }

    /**
     * Modo B — Análise leve contínua (só comandos operacionais).
     * Usa janela curta (3s) e thresholds mais altos.
     *
     * @param audioSamples áudio PCM 16kHz (janela curta ~3s)
     * @param offset       início
     * @param length       quantidade de amostras
     * @return resultado com matches operacionais apenas
     */
    public DetectionResult analyzeContinuous(short[] audioSamples, int offset, int length) {
        long startTime = System.nanoTime();

        float[][] queryMfcc = mfccExtractor.extract(audioSamples, offset, length);
        if (queryMfcc == null) {
            return new DetectionResult(new ArrayList<>(), 0, 0, false);
        }

        List<PhraseMatch> matches = new ArrayList<>();
        int segmentsChecked = 0;

        // Apenas templates operacionais
        Map<String, List<PhraseTemplate>> opTemplates = enrollmentManager
                .getTemplatesByType(PhraseTemplate.PhraseType.OPERATIONAL);

        for (Map.Entry<String, List<PhraseTemplate>> entry : opTemplates.entrySet()) {
            String phraseId = entry.getKey();
            List<PhraseTemplate> templates = entry.getValue();
            if (templates.isEmpty())
                continue;

            // Modo B usa confidence boost (mais restritivo)
            double minConf = config.getMinConfidence(phraseId)
                    + config.continuousModeConfidenceBoost;

            DTWMatcher.MatchResult best = dtwMatcher.matchBest(
                    queryMfcc,
                    templates.toArray(new PhraseTemplate[0]),
                    minConf);

            segmentsChecked++;

            if (best != null && best.isMatch) {
                PhraseTemplate usedTemplate = templates.get(best.templateIndex - 1);
                matches.add(new PhraseMatch(
                        phraseId, PhraseTemplate.PhraseType.OPERATIONAL,
                        best.confidence, best.distance,
                        usedTemplate.getThreshold(), best.templateIndex,
                        best.processingTimeMs));

                Log.i(TAG, String.format(
                        "[CONTINUOUS] MATCH: %s | conf=%.2f | dist=%.2f | t%d",
                        phraseId, best.confidence, best.distance, best.templateIndex));
            }
        }

        long elapsed = (System.nanoTime() - startTime) / 1_000_000;

        return new DetectionResult(matches, segmentsChecked, elapsed, false);
    }

    /**
     * Retorna informações de diagnóstico
     */
    public String getDiagnostics() {
        Map<String, List<PhraseTemplate>> all = enrollmentManager.getAllTemplates();
        int ops = 0, ctx = 0, gen = 0;
        for (List<PhraseTemplate> templates : all.values()) {
            if (templates.isEmpty())
                continue;
            switch (templates.get(0).getType()) {
                case OPERATIONAL:
                    ops++;
                    break;
                case CONTEXTUAL:
                    ctx++;
                    break;
                case GENERIC:
                    gen++;
                    break;
            }
        }
        return String.format("Operacionais=%d Contextuais=%d Genéricas=%d", ops, ctx, gen);
    }
}
