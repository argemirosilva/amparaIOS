package tech.orizon.ampara.audio.phrase;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gerenciador de enrollment de frases personalizadas.
 * 
 * Responsável pelo fluxo completo de cadastro:
 * - Gravação de amostras (via áudio recebido do serviço)
 * - Extração de MFCC de cada amostra
 * - Validação de consistência intra-amostras
 * - Validação de conflito inter-frases operacionais
 * - Cálculo de threshold adaptativo por frase
 * - Persistência segura de templates
 * - Carregamento de templates no boot
 * - Verificação de templates obsoletos (stale)
 * 
 * Conforme SPEC v3, seções 7 e 9.
 */
public class PhraseEnrollmentManager {
    private static final String TAG = "PhraseEnrollMgr";

    /** Diretório de armazenamento de templates */
    private static final String TEMPLATES_DIR = "phrases/templates";

    private final Context context;
    private final MFCCExtractor mfccExtractor;
    private final DTWMatcher dtwMatcher;
    private final ScoringConfig config;

    /** Templates carregados em memória, agrupados por phraseId */
    private final Map<String, List<PhraseTemplate>> loadedTemplates;

    /** Metadados de enrollment pendente (durante gravação de amostras) */
    private String pendingPhraseId;
    private PhraseTemplate.PhraseType pendingType;
    private final List<PhraseTemplate> pendingSamples;

    public PhraseEnrollmentManager(Context context, ScoringConfig config) {
        this.context = context;
        this.mfccExtractor = new MFCCExtractor();
        this.dtwMatcher = new DTWMatcher();
        this.config = config;
        this.loadedTemplates = new HashMap<>();
        this.pendingSamples = new ArrayList<>();

        Log.i(TAG, "[INIT] PhraseEnrollmentManager criado");
    }

    // ========== Enrollment ==========

    /**
     * Inicia processo de enrollment para uma frase
     *
     * @param phraseId identificador (ex: "panic_start", "alert_1")
     * @param type     tipo de frase
     * @return resultado da validação inicial
     */
    public EnrollmentResult startEnrollment(String phraseId, PhraseTemplate.PhraseType type) {
        // Verificar limites
        int currentCount = countPhrasesByType(type);
        int max = type == PhraseTemplate.PhraseType.OPERATIONAL
                ? config.maxOperationalPhrases
                : config.maxContextualPhrases;

        // Permitir re-enrollment de frase existente
        if (!loadedTemplates.containsKey(phraseId) && currentCount >= max) {
            Log.w(TAG, String.format("[LIMIT] Tipo %s já tem %d/%d frases", type, currentCount, max));
            return new EnrollmentResult(false, "Limite de frases atingido para esta categoria");
        }

        pendingPhraseId = phraseId;
        pendingType = type;
        pendingSamples.clear();

        Log.i(TAG, String.format("[START] Enrollment de '%s' tipo=%s", phraseId, type));
        return new EnrollmentResult(true, "Enrollment iniciado. Grave a primeira amostra.");
    }

    /**
     * Adiciona uma amostra de áudio ao enrollment em andamento.
     * Extrai MFCC e valida duração.
     *
     * @param samples áudio PCM 16kHz mono
     * @param offset  início do áudio
     * @param length  quantidade de amostras
     * @return resultado da validação da amostra
     */
    public EnrollmentResult addSample(short[] samples, int offset, int length) {
        if (pendingPhraseId == null) {
            return new EnrollmentResult(false, "Nenhum enrollment em andamento");
        }

        if (pendingSamples.size() >= config.maxSamplesPerPhrase) {
            return new EnrollmentResult(false, "Número máximo de amostras atingido");
        }

        // Verificar duração
        float durationMs = (float) length / mfccExtractor.getFrameHopSamples() *
                mfccExtractor.getFrameSizeSamples() / 16.0f;
        float durationSeconds = (float) length / 16000.0f;

        if (length < (config.enrollMinDurationMs * 16)) {
            return new EnrollmentResult(false,
                    "Frase muito curta. Tente algo com 2-5 palavras.");
        }

        if (length > (config.enrollMaxDurationMs * 16)) {
            return new EnrollmentResult(false,
                    "Frase muito longa. Máximo de 5 segundos.");
        }

        // Extrair MFCC
        float[][] mfcc = mfccExtractor.extract(samples, offset, length);
        if (mfcc == null) {
            return new EnrollmentResult(false,
                    "Não foi possível processar o áudio. Tente novamente.");
        }

        // Criar template
        int templateIndex = pendingSamples.size() + 1;
        PhraseTemplate template = new PhraseTemplate(
                pendingPhraseId, pendingType, templateIndex, mfcc,
                MFCCExtractor.EXTRACTOR_VERSION, durationSeconds, 0.0);

        pendingSamples.add(template);

        int remaining = config.minSamplesPerPhrase - pendingSamples.size();
        String msg;
        if (remaining > 0) {
            msg = String.format("Amostra %d aceita. Grave mais %d amostra(s).",
                    templateIndex, remaining);
        } else if (pendingSamples.size() < config.maxSamplesPerPhrase) {
            msg = String.format("Amostra %d aceita. Pode gravar mais ou finalizar.",
                    templateIndex);
        } else {
            msg = "Última amostra aceita. Finalize o enrollment.";
        }

        Log.i(TAG, String.format("[SAMPLE] Amostra %d/%d aceita (%.1fs, %d frames)",
                templateIndex, config.maxSamplesPerPhrase, durationSeconds, mfcc.length));

        return new EnrollmentResult(true, msg, pendingSamples.size(),
                config.minSamplesPerPhrase, config.maxSamplesPerPhrase);
    }

    /**
     * Finaliza o enrollment: valida consistência, calcula thresholds, persiste.
     *
     * @return resultado final do enrollment
     */
    public EnrollmentResult finishEnrollment() {
        if (pendingPhraseId == null || pendingSamples.isEmpty()) {
            return new EnrollmentResult(false, "Nenhum enrollment em andamento");
        }

        if (pendingSamples.size() < config.minSamplesPerPhrase) {
            return new EnrollmentResult(false,
                    String.format("Mínimo de %d amostras necessárias. Grave mais.",
                            config.minSamplesPerPhrase));
        }

        // 1. Validar consistência entre amostras (DTW intra-amostras)
        double maxIntraDistance = 0;
        for (int i = 0; i < pendingSamples.size(); i++) {
            for (int j = i + 1; j < pendingSamples.size(); j++) {
                double dist = dtwMatcher.templateDistance(
                        pendingSamples.get(i), pendingSamples.get(j));
                maxIntraDistance = Math.max(maxIntraDistance, dist);
            }
        }

        if (maxIntraDistance > config.enrollConsistencyThreshold) {
            Log.w(TAG, String.format("[INCONSISTENT] maxIntraDist=%.2f > threshold=%.2f",
                    maxIntraDistance, config.enrollConsistencyThreshold));
            return new EnrollmentResult(false,
                    "As amostras estão muito diferentes entre si. Tente regravar de forma mais consistente.");
        }

        // 2. Validar conflito com outras frases operacionais
        if (pendingType == PhraseTemplate.PhraseType.OPERATIONAL) {
            for (Map.Entry<String, List<PhraseTemplate>> entry : loadedTemplates.entrySet()) {
                if (entry.getKey().equals(pendingPhraseId))
                    continue;

                List<PhraseTemplate> otherTemplates = entry.getValue();
                if (otherTemplates.isEmpty())
                    continue;
                if (otherTemplates.get(0).getType() != PhraseTemplate.PhraseType.OPERATIONAL)
                    continue;

                // Comparar com primeira amostra da outra frase
                double interDist = dtwMatcher.templateDistance(
                        pendingSamples.get(0), otherTemplates.get(0));

                if (interDist < config.enrollDivergenceThreshold) {
                    Log.w(TAG, String.format("[CONFLICT] Com '%s' dist=%.2f < divergence=%.2f",
                            entry.getKey(), interDist, config.enrollDivergenceThreshold));
                    return new EnrollmentResult(false,
                            String.format("Esta frase é muito parecida com '%s'. Use uma frase diferente.",
                                    entry.getKey()));
                }
            }
        }

        // 3. Calcular threshold adaptativo: média das distâncias intra × fator
        double avgIntraDistance = 0;
        int pairs = 0;
        for (int i = 0; i < pendingSamples.size(); i++) {
            for (int j = i + 1; j < pendingSamples.size(); j++) {
                avgIntraDistance += dtwMatcher.templateDistance(
                        pendingSamples.get(i), pendingSamples.get(j));
                pairs++;
            }
        }
        avgIntraDistance /= Math.max(pairs, 1);

        // Threshold = 4x a distância média intra (margem de segurança amigável ao vivo)
        double threshold = avgIntraDistance * 4.0;
        threshold = Math.max(threshold, 20.0); // piso bem seguro para evitar confidence < 0.5 em distâncias de ~12.0

        // Aplicar threshold a todos os templates
        for (PhraseTemplate template : pendingSamples) {
            template.setThreshold(threshold);
        }

        // 4. Persistir templates
        try {
            saveTemplates(pendingPhraseId, pendingSamples);
        } catch (IOException e) {
            Log.e(TAG, "[ERROR] Falha ao salvar templates", e);
            return new EnrollmentResult(false, "Erro ao salvar. Tente novamente.");
        }

        // 5. Atualizar memória
        loadedTemplates.put(pendingPhraseId, new ArrayList<>(pendingSamples));

        Log.i(TAG, String.format(
                "[DONE] Enrollment '%s' completo | %d amostras | threshold=%.2f | intraMax=%.2f",
                pendingPhraseId, pendingSamples.size(), threshold, maxIntraDistance));

        // Limpar estado pendente
        String phraseId = pendingPhraseId;
        pendingPhraseId = null;
        pendingSamples.clear();

        return new EnrollmentResult(true,
                String.format("Frase '%s' cadastrada com sucesso!", phraseId));
    }

    /**
     * Cancela enrollment em andamento
     */
    public void cancelEnrollment() {
        if (pendingPhraseId != null) {
            Log.i(TAG, String.format("[CANCEL] Enrollment '%s' cancelado", pendingPhraseId));
            pendingPhraseId = null;
            pendingSamples.clear();
        }
    }

    /**
     * Remove uma frase cadastrada
     */
    public boolean removePhrase(String phraseId) {
        loadedTemplates.remove(phraseId);

        // Remover arquivos
        File dir = getTemplatesDir();
        File[] files = dir.listFiles((d, name) -> name.startsWith(phraseId + "_t"));
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }

        Log.i(TAG, String.format("[REMOVE] Frase '%s' removida", phraseId));
        return true;
    }

    // ========== Carregamento ==========

    /**
     * Carrega todos os templates do disco para memória.
     * Deve ser chamado no boot do serviço.
     *
     * @return número de frases carregadas
     */
    public int loadAllTemplates() {
        loadedTemplates.clear();

        File dir = getTemplatesDir();
        if (!dir.exists()) {
            Log.i(TAG, "[LOAD] Diretório de templates não existe. Nenhuma frase cadastrada.");
            return 0;
        }

        File[] files = dir.listFiles((d, name) -> name.endsWith(".bin"));
        if (files == null || files.length == 0) {
            Log.i(TAG, "[LOAD] Nenhum template encontrado.");
            return 0;
        }

        int staleCount = 0;
        for (File file : files) {
            try {
                PhraseTemplate template = PhraseTemplate.loadFrom(file);

                // Verificar se template está obsoleto
                if (template.isStale(MFCCExtractor.EXTRACTOR_VERSION)) {
                    staleCount++;
                    Log.w(TAG, String.format("[STALE] Template obsoleto: %s (v%d, atual v%d)",
                            file.getName(), template.getExtractorVersion(),
                            MFCCExtractor.EXTRACTOR_VERSION));
                    continue;
                }

                loadedTemplates.computeIfAbsent(template.getPhraseId(), k -> new ArrayList<>())
                        .add(template);
            } catch (IOException e) {
                Log.e(TAG, "[ERROR] Falha ao carregar " + file.getName(), e);
            }
        }

        int phrases = loadedTemplates.size();
        int totalTemplates = loadedTemplates.values().stream()
                .mapToInt(List::size).sum();

        Log.i(TAG, String.format("[LOAD] %d frases, %d templates carregados (%d stale)",
                phrases, totalTemplates, staleCount));

        return phrases;
    }

    /**
     * Retorna todos os templates de uma frase específica
     */
    public PhraseTemplate[] getTemplates(String phraseId) {
        List<PhraseTemplate> list = loadedTemplates.get(phraseId);
        if (list == null)
            return new PhraseTemplate[0];
        return list.toArray(new PhraseTemplate[0]);
    }

    /**
     * Retorna todos os templates carregados, agrupados por phraseId
     */
    public Map<String, List<PhraseTemplate>> getAllTemplates() {
        return new HashMap<>(loadedTemplates);
    }

    /**
     * Retorna templates apenas de um tipo específico
     */
    public Map<String, List<PhraseTemplate>> getTemplatesByType(PhraseTemplate.PhraseType type) {
        Map<String, List<PhraseTemplate>> filtered = new HashMap<>();
        for (Map.Entry<String, List<PhraseTemplate>> entry : loadedTemplates.entrySet()) {
            if (!entry.getValue().isEmpty() && entry.getValue().get(0).getType() == type) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        return filtered;
    }

    /**
     * Verifica se existem templates obsoletos que precisam de regravação
     */
    public boolean hasStaleTemplates() {
        File dir = getTemplatesDir();
        if (!dir.exists())
            return false;

        File[] files = dir.listFiles((d, name) -> name.endsWith(".bin"));
        if (files == null)
            return false;

        for (File file : files) {
            try {
                PhraseTemplate template = PhraseTemplate.loadFrom(file);
                if (template.isStale(MFCCExtractor.EXTRACTOR_VERSION)) {
                    return true;
                }
            } catch (IOException ignored) {
            }
        }
        return false;
    }

    // ========== Helpers ==========

    /**
     * Conta frases de um tipo específico
     */
    private int countPhrasesByType(PhraseTemplate.PhraseType type) {
        int count = 0;
        for (List<PhraseTemplate> templates : loadedTemplates.values()) {
            if (!templates.isEmpty() && templates.get(0).getType() == type) {
                count++;
            }
        }
        return count;
    }

    /**
     * Salva templates no disco
     */
    private void saveTemplates(String phraseId, List<PhraseTemplate> templates) throws IOException {
        File dir = getTemplatesDir();
        if (!dir.exists())
            dir.mkdirs();

        // Remover templates antigos desta frase
        File[] oldFiles = dir.listFiles((d, name) -> name.startsWith(phraseId + "_t"));
        if (oldFiles != null) {
            for (File f : oldFiles)
                f.delete();
        }

        // Salvar novos
        for (PhraseTemplate template : templates) {
            File file = new File(dir,
                    String.format("%s_t%d.bin", phraseId, template.getTemplateIndex()));
            template.saveTo(file);
        }
    }

    /**
     * Retorna diretório de templates
     */
    private File getTemplatesDir() {
        return new File(context.getFilesDir(), TEMPLATES_DIR);
    }

    /**
     * Retorna informações de diagnóstico
     */
    public String getDiagnostics() {
        int totalTemplates = loadedTemplates.values().stream()
                .mapToInt(List::size).sum();
        return String.format("Frases=%d Templates=%d Pending=%s",
                loadedTemplates.size(), totalTemplates,
                pendingPhraseId != null ? pendingPhraseId : "none");
    }

    // ========== Resultado de Enrollment ==========

    /**
     * Resultado de uma operação de enrollment
     */
    public static class EnrollmentResult {
        public final boolean success;
        public final String message;
        public final int currentSamples;
        public final int minRequired;
        public final int maxAllowed;

        public EnrollmentResult(boolean success, String message) {
            this(success, message, 0, 0, 0);
        }

        public EnrollmentResult(boolean success, String message,
                int currentSamples, int minRequired, int maxAllowed) {
            this.success = success;
            this.message = message;
            this.currentSamples = currentSamples;
            this.minRequired = minRequired;
            this.maxAllowed = maxAllowed;
        }
    }
}
