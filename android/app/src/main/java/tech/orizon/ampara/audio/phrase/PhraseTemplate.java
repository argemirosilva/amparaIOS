package tech.orizon.ampara.audio.phrase;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import android.util.Log;

/**
 * Template acústico de uma amostra de frase.
 * 
 * Armazena os coeficientes MFCC extraídos de uma gravação da usuária,
 * junto com metadados de identificação e threshold de matching.
 * 
 * Cada frase cadastrada tem 3-5 PhraseTemplates (uma por amostra).
 */
public class PhraseTemplate {
    private static final String TAG = "PhraseTemplate";

    /**
     * Tipos de frase suportados
     */
    public enum PhraseType {
        /** Comandos operacionais críticos (gravação, pânico) */
        OPERATIONAL,
        /** Alertas contextuais de risco */
        CONTEXTUAL,
        /** Vocabulário genérico do sistema */
        GENERIC
    }

    // Identificação
    private final String phraseId;
    private final PhraseType type;
    private final int templateIndex;

    // Features acústicas extraídas
    private final float[][] mfccFrames;

    // Metadados
    private final int extractorVersion;
    private final float durationSeconds;
    private double threshold;
    private final long enrolledAt;

    /**
     * Cria um novo template a partir de MFCC extraídos
     *
     * @param phraseId         identificador único da frase (ex: "panic_start")
     * @param type             tipo da frase
     * @param templateIndex    índice da amostra (1 a 5)
     * @param mfccFrames       features MFCC [frames][39]
     * @param extractorVersion versão do MFCCExtractor usado
     * @param durationSeconds  duração do áudio original em segundos
     * @param threshold        threshold DTW para matching (0 = auto-calculado)
     */
    public PhraseTemplate(String phraseId, PhraseType type, int templateIndex,
            float[][] mfccFrames, int extractorVersion,
            float durationSeconds, double threshold) {
        this.phraseId = phraseId;
        this.type = type;
        this.templateIndex = templateIndex;
        this.mfccFrames = mfccFrames;
        this.extractorVersion = extractorVersion;
        this.durationSeconds = durationSeconds;
        this.threshold = threshold;
        this.enrolledAt = System.currentTimeMillis();
    }

    // ========== Persistência ==========

    /**
     * Salva o template em arquivo binário.
     * Formato: [phraseId][type][templateIndex][extractorVersion][durationSeconds]
     * [threshold][enrolledAt][numFrames][numFeatures][float data...]
     *
     * @param file arquivo de destino
     */
    public void saveTo(File file) throws IOException {
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(file))) {
            // Header
            dos.writeUTF(phraseId);
            dos.writeInt(type.ordinal());
            dos.writeInt(templateIndex);
            dos.writeInt(extractorVersion);
            dos.writeFloat(durationSeconds);
            dos.writeDouble(threshold);
            dos.writeLong(enrolledAt);

            // Dimensões
            int numFrames = mfccFrames.length;
            int numFeatures = numFrames > 0 ? mfccFrames[0].length : 0;
            dos.writeInt(numFrames);
            dos.writeInt(numFeatures);

            // Dados MFCC
            for (int i = 0; i < numFrames; i++) {
                for (int j = 0; j < numFeatures; j++) {
                    dos.writeFloat(mfccFrames[i][j]);
                }
            }

            Log.d(TAG, String.format("[SAVE] %s_t%d → %s (%d bytes, %d frames)",
                    phraseId, templateIndex, file.getName(), file.length(), numFrames));
        }
    }

    /**
     * Carrega um template de arquivo binário
     *
     * @param file arquivo fonte
     * @return template carregado
     */
    public static PhraseTemplate loadFrom(File file) throws IOException {
        try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
            // Header
            String phraseId = dis.readUTF();
            PhraseType type = PhraseType.values()[dis.readInt()];
            int templateIndex = dis.readInt();
            int extractorVersion = dis.readInt();
            float durationSeconds = dis.readFloat();
            double threshold = dis.readDouble();
            long enrolledAt = dis.readLong();

            // Dimensões
            int numFrames = dis.readInt();
            int numFeatures = dis.readInt();

            // Dados MFCC
            float[][] mfccFrames = new float[numFrames][numFeatures];
            for (int i = 0; i < numFrames; i++) {
                for (int j = 0; j < numFeatures; j++) {
                    mfccFrames[i][j] = dis.readFloat();
                }
            }

            PhraseTemplate template = new PhraseTemplate(
                    phraseId, type, templateIndex, mfccFrames,
                    extractorVersion, durationSeconds, threshold);

            Log.d(TAG, String.format("[LOAD] %s_t%d ← %s (%d frames, v%d)",
                    phraseId, templateIndex, file.getName(), numFrames, extractorVersion));

            return template;
        }
    }

    /**
     * Verifica se o template está obsoleto (versão do extractor diferente)
     */
    public boolean isStale(int currentExtractorVersion) {
        return this.extractorVersion != currentExtractorVersion;
    }

    // ========== Getters ==========

    public String getPhraseId() {
        return phraseId;
    }

    public PhraseType getType() {
        return type;
    }

    public int getTemplateIndex() {
        return templateIndex;
    }

    public float[][] getMfccFrames() {
        return mfccFrames;
    }

    public int getExtractorVersion() {
        return extractorVersion;
    }

    public float getDurationSeconds() {
        return durationSeconds;
    }

    public double getThreshold() {
        return threshold;
    }

    public long getEnrolledAt() {
        return enrolledAt;
    }

    /**
     * Atualiza o threshold (usado no recalcular após enrollment ou mudança global)
     */
    public void setThreshold(double threshold) {
        this.threshold = threshold;
    }

    /**
     * Retorna tamanho estimado em bytes do template serializado
     */
    public int estimatedSizeBytes() {
        // Header ~50 bytes + frames * features * 4 bytes
        int numFrames = mfccFrames.length;
        int numFeatures = numFrames > 0 ? mfccFrames[0].length : 0;
        return 50 + numFrames * numFeatures * 4;
    }

    @Override
    public String toString() {
        return String.format("PhraseTemplate{%s_t%d, type=%s, frames=%d, dur=%.1fs, threshold=%.2f, v%d}",
                phraseId, templateIndex, type.name(), mfccFrames.length,
                durationSeconds, threshold, extractorVersion);
    }
}
