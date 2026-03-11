package tech.orizon.ampara.audio.phrase;

import android.util.Log;

/**
 * Comparador DTW (Dynamic Time Warping) para matching de templates MFCC.
 * 
 * Compara sequências de features MFCC de comprimentos diferentes,
 * encontrando o alinhamento ótimo com distância mínima.
 * Inclui early abandoning para performance.
 * 
 * Totalmente local, sem dependências externas.
 */
public class DTWMatcher {
    private static final String TAG = "DTWMatcher";

    /**
     * Resultado de uma comparação DTW
     */
    public static class MatchResult {
        /** Distância DTW normalizada (menor = mais similar) */
        public final double distance;

        /**
         * Confiança calculada: 1.0 - (distance / threshold). Valores >= 0 indicam match
         */
        public final double confidence;

        /** Se houve match (confidence >= minConfidence) */
        public final boolean isMatch;

        /** ID da frase do template comparado */
        public final String phraseId;

        /** Índice do template usado */
        public final int templateIndex;

        /** Tempo de processamento em ms */
        public final long processingTimeMs;

        public MatchResult(double distance, double confidence, boolean isMatch,
                String phraseId, int templateIndex, long processingTimeMs) {
            this.distance = distance;
            this.confidence = confidence;
            this.isMatch = isMatch;
            this.phraseId = phraseId;
            this.templateIndex = templateIndex;
            this.processingTimeMs = processingTimeMs;
        }

        @Override
        public String toString() {
            return String.format("DTW{phrase=%s, dist=%.2f, conf=%.2f, match=%b, t%d, %dms}",
                    phraseId, distance, confidence, isMatch, templateIndex, processingTimeMs);
        }
    }

    /**
     * Calcula distância DTW entre duas sequências de features MFCC.
     * Usa distância euclidiana como custo local e early abandoning.
     *
     * @param query       features do áudio capturado [frames][features]
     * @param template    features do template cadastrado [frames][features]
     * @param maxDistance limite para early abandoning (-1 para desabilitar)
     * @return distância DTW normalizada pelo comprimento do caminho, ou
     *         Double.MAX_VALUE se abandonou
     */
    public double computeDistance(float[][] query, float[][] template, double maxDistance) {
        int n = query.length;
        int m = template.length;

        if (n == 0 || m == 0)
            return Double.MAX_VALUE;

        // Matriz de custo acumulado — usar apenas 2 linhas para economizar memória
        double[] prevRow = new double[m + 1];
        double[] currRow = new double[m + 1];

        // Inicialização
        for (int j = 0; j <= m; j++) {
            prevRow[j] = Double.MAX_VALUE;
        }
        prevRow[0] = 0.0;

        for (int i = 1; i <= n; i++) {
            currRow[0] = Double.MAX_VALUE;
            double rowMin = Double.MAX_VALUE;

            for (int j = 1; j <= m; j++) {
                double cost = euclideanDistance(query[i - 1], template[j - 1]);

                // Custo mínimo entre 3 vizinhos
                double minPrev = Math.min(prevRow[j - 1],
                        Math.min(prevRow[j], currRow[j - 1]));

                if (minPrev == Double.MAX_VALUE) {
                    currRow[j] = Double.MAX_VALUE;
                } else {
                    currRow[j] = cost + minPrev;
                }

                rowMin = Math.min(rowMin, currRow[j]);
            }

            // Early abandoning: se toda a linha excede o limite, abortar
            if (maxDistance > 0 && rowMin > maxDistance * (n + m)) {
                return Double.MAX_VALUE;
            }

            // Trocar linhas
            double[] temp = prevRow;
            prevRow = currRow;
            currRow = temp;
        }

        // Normalizar pelo comprimento do caminho
        double normalizedDistance = prevRow[m] / (n + m);
        return normalizedDistance;
    }

    /**
     * Compara áudio query contra um template específico e calcula confidence.
     *
     * @param queryMfcc     MFCC do áudio capturado
     * @param template      template cadastrado
     * @param minConfidence confiança mínima para considerar match
     * @return resultado do matching
     */
    public MatchResult match(float[][] queryMfcc, PhraseTemplate template, double minConfidence) {
        long startTime = System.nanoTime();

        double maxDist = template.getThreshold() * 1.5; // early abandoning generoso
        double distance = computeDistance(queryMfcc, template.getMfccFrames(), maxDist);

        double confidence;
        if (distance == Double.MAX_VALUE) {
            confidence = 0.0;
        } else {
            confidence = 1.0 - (distance / template.getThreshold());
            confidence = Math.max(0.0, Math.min(1.0, confidence));
        }

        boolean isMatch = confidence >= minConfidence;
        long elapsed = (System.nanoTime() - startTime) / 1_000_000;

        MatchResult result = new MatchResult(
                distance, confidence, isMatch,
                template.getPhraseId(), template.getTemplateIndex(), elapsed);

        if (isMatch) {
            Log.d(TAG, String.format("[MATCH] %s | conf=%.2f | dist=%.2f | threshold=%.2f | %dms",
                    template.getPhraseId(), confidence, distance, template.getThreshold(), elapsed));
        }

        return result;
    }

    /**
     * Compara áudio query contra múltiplos templates da mesma frase.
     * Retorna o melhor match (maior confidence).
     *
     * @param queryMfcc     MFCC do áudio capturado
     * @param templates     array de templates da mesma frase
     * @param minConfidence confiança mínima
     * @return melhor resultado, ou resultado sem match se nenhum template bateu
     */
    public MatchResult matchBest(float[][] queryMfcc, PhraseTemplate[] templates,
            double minConfidence) {
        MatchResult best = null;

        for (PhraseTemplate template : templates) {
            MatchResult result = match(queryMfcc, template, minConfidence);

            if (best == null || result.confidence > best.confidence) {
                best = result;
            }

            // Se encontrou match com alta confiança, pode parar cedo
            if (result.confidence > 0.95)
                break;
        }

        return best;
    }

    /**
     * Distância euclidiana entre dois vetores de features
     */
    private static double euclideanDistance(float[] a, float[] b) {
        double sum = 0.0;
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            double diff = a[i] - b[i];
            sum += diff * diff;
        }
        return Math.sqrt(sum);
    }

    /**
     * Calcula distância DTW entre dois templates para validação de enrollment.
     * Usado para verificar consistência intra-amostras e conflito inter-frases.
     *
     * @return distância normalizada
     */
    public double templateDistance(PhraseTemplate a, PhraseTemplate b) {
        return computeDistance(a.getMfccFrames(), b.getMfccFrames(), -1);
    }
}
