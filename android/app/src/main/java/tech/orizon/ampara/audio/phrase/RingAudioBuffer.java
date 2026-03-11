package tech.orizon.ampara.audio.phrase;

import android.util.Log;

/**
 * Buffer circular de áudio PCM para a confirmation window (Layer 2).
 * 
 * Armazena os últimos N segundos de áudio para recuperar contexto
 * quando o detector acústico (Layer 1) gera um trigger.
 * 
 * Thread-safe para escrita contínua e leitura sob demanda.
 */
public class RingAudioBuffer {
    private static final String TAG = "RingAudioBuffer";

    /** Buffer circular de amostras PCM 16-bit */
    private final short[] buffer;

    /** Capacidade total em amostras */
    private final int capacity;

    /** Posição de escrita (ponteiro circular) */
    private int writePos;

    /** Número total de amostras escritas desde a criação/reset */
    private long totalWritten;

    /** Sample rate do áudio */
    private final int sampleRate;

    /** Lock para thread-safety */
    private final Object lock = new Object();

    /**
     * Cria buffer circular com capacidade em segundos
     *
     * @param durationSeconds duração do buffer (padrão: 10s)
     * @param sampleRate      taxa de amostragem (padrão: 16000)
     */
    public RingAudioBuffer(int durationSeconds, int sampleRate) {
        this.sampleRate = sampleRate;
        this.capacity = durationSeconds * sampleRate;
        this.buffer = new short[capacity];
        this.writePos = 0;
        this.totalWritten = 0;

        Log.i(TAG, String.format("[INIT] %ds buffer | %d amostras | %.1fKB",
                durationSeconds, capacity, capacity * 2.0 / 1024));
    }

    /**
     * Construtor padrão (10 segundos, 16kHz)
     */
    public RingAudioBuffer() {
        this(10, 16000);
    }

    /**
     * Escreve amostras no buffer circular.
     * Chamado continuamente pelo loop de captura de áudio.
     *
     * @param samples amostras PCM
     * @param offset  início no array
     * @param length  quantidade de amostras
     */
    public void write(short[] samples, int offset, int length) {
        synchronized (lock) {
            for (int i = 0; i < length; i++) {
                buffer[writePos] = samples[offset + i];
                writePos = (writePos + 1) % capacity;
            }
            totalWritten += length;
        }
    }

    /**
     * Captura um snapshot dos últimos N segundos do buffer.
     * Usado quando L1 gera trigger para montar a confirmation window.
     *
     * @param durationSeconds quantos segundos recuperar (máx = capacidade do
     *                        buffer)
     * @return cópia das amostras do período solicitado, ou null se insuficiente
     */
    public short[] snapshot(int durationSeconds) {
        int requestedSamples = Math.min(durationSeconds * sampleRate, capacity);

        synchronized (lock) {
            // Verificar se temos amostras suficientes
            int available = (int) Math.min(totalWritten, capacity);
            if (available < requestedSamples) {
                Log.w(TAG, String.format("Snapshot insuficiente: pedido=%d disponível=%d",
                        requestedSamples, available));
                if (available == 0)
                    return null;
                requestedSamples = available;
            }

            short[] result = new short[requestedSamples];

            // Calcular posição de início de leitura
            int readPos = (writePos - requestedSamples + capacity) % capacity;

            // Copiar do buffer circular para array linear
            if (readPos + requestedSamples <= capacity) {
                // Cópia contígua
                System.arraycopy(buffer, readPos, result, 0, requestedSamples);
            } else {
                // Cópia em duas partes (wrap-around)
                int firstPart = capacity - readPos;
                System.arraycopy(buffer, readPos, result, 0, firstPart);
                System.arraycopy(buffer, 0, result, firstPart, requestedSamples - firstPart);
            }

            Log.d(TAG, String.format("[SNAPSHOT] %d amostras (%.1fs) capturadas | total escrito: %d",
                    requestedSamples, (double) requestedSamples / sampleRate, totalWritten));

            return result;
        }
    }

    /**
     * Captura snapshot dos últimos N segundos MAIS aguarda M segundos adicionais de
     * áudio.
     * Retorna o áudio combinado (pre-trigger + post-trigger).
     *
     * Nota: O post-trigger é montado pelo PipelineController que continua
     * alimentando
     * o buffer e depois coleta. Este método retorna apenas o snapshot atual.
     *
     * @return snapshot completo do buffer
     */
    public short[] snapshotFull() {
        return snapshot((int) (capacity / sampleRate));
    }

    /**
     * Reseta o buffer (limpa todo o conteúdo)
     */
    public void reset() {
        synchronized (lock) {
            writePos = 0;
            totalWritten = 0;
            java.util.Arrays.fill(buffer, (short) 0);
            Log.i(TAG, "[RESET] Buffer limpo");
        }
    }

    /**
     * Retorna a quantidade de segundos de áudio disponível no buffer
     */
    public double getAvailableSeconds() {
        synchronized (lock) {
            long available = Math.min(totalWritten, capacity);
            return (double) available / sampleRate;
        }
    }

    /**
     * Retorna se o buffer tem pelo menos a duração solicitada
     */
    public boolean hasMinDuration(double seconds) {
        return getAvailableSeconds() >= seconds;
    }

    public int getCapacity() {
        return capacity;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public long getTotalWritten() {
        return totalWritten;
    }
}
