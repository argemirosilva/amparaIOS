package tech.orizon.ampara.audio.phrase;

import android.util.Log;

/**
 * Extrator de coeficientes MFCC (Mel-Frequency Cepstral Coefficients).
 * 
 * Pipeline: PCM 16kHz → pré-ênfase → janelamento Hamming → FFT → Mel FilterBank
 * → log energia → DCT → 13 MFCC → Delta + Delta-Delta → 39 features/frame
 * 
 * Totalmente local, sem dependências externas.
 */
public class MFCCExtractor {
    private static final String TAG = "MFCCExtractor";

    // Versão do extrator — incrementar quando mudar parâmetros que invalidem
    // templates
    public static final int EXTRACTOR_VERSION = 1;

    // Parâmetros de frame
    private final int sampleRate;
    private final int frameSizeMs;
    private final int frameHopMs;
    private final int frameSizeSamples;
    private final int frameHopSamples;

    // Parâmetros MFCC
    private final int fftSize;
    private final int numMelFilters;
    private final int numMfccCoeffs;
    private final double preEmphasis;
    private final double melLowFreq;
    private final double melHighFreq;

    // Mel filter bank pré-calculado
    private final double[][] melFilterBank;

    // Janela Hamming pré-calculada
    private final double[] hammingWindow;

    // Coeficientes DCT pré-calculados
    private final double[][] dctMatrix;

    /**
     * Construtor com parâmetros padrão do SPEC
     */
    public MFCCExtractor() {
        this(16000, 25, 10, 512, 26, 13, 0.97, 300.0, 8000.0);
    }

    /**
     * Construtor completo com parâmetros configuráveis
     */
    public MFCCExtractor(int sampleRate, int frameSizeMs, int frameHopMs,
            int fftSize, int numMelFilters, int numMfccCoeffs,
            double preEmphasis, double melLowFreq, double melHighFreq) {
        this.sampleRate = sampleRate;
        this.frameSizeMs = frameSizeMs;
        this.frameHopMs = frameHopMs;
        this.frameSizeSamples = (sampleRate * frameSizeMs) / 1000;
        this.frameHopSamples = (sampleRate * frameHopMs) / 1000;
        this.fftSize = fftSize;
        this.numMelFilters = numMelFilters;
        this.numMfccCoeffs = numMfccCoeffs;
        this.preEmphasis = preEmphasis;
        this.melLowFreq = melLowFreq;
        this.melHighFreq = melHighFreq;

        // Pré-calcular estruturas fixas para performance
        this.hammingWindow = createHammingWindow(frameSizeSamples);
        this.melFilterBank = createMelFilterBank();
        this.dctMatrix = createDCTMatrix();

        Log.i(TAG, String.format(
                "[INIT] v%d | sampleRate=%d | frame=%dms/%dms | fft=%d | mel=%d | mfcc=%d",
                EXTRACTOR_VERSION, sampleRate, frameSizeMs, frameHopMs,
                fftSize, numMelFilters, numMfccCoeffs));
    }

    /**
     * Extrai MFCC completo de áudio PCM (39 features/frame: 13 MFCC + 13 delta + 13
     * delta-delta).
     * Inclui CMN (Cepstral Mean Normalization).
     *
     * @param samples áudio PCM 16-bit mono
     * @param offset  início no array
     * @param length  quantidade de amostras
     * @return float[numFrames][39] com features MFCC + deltas, ou null se áudio
     *         insuficiente
     */
    public float[][] extract(short[] samples, int offset, int length) {
        if (length < frameSizeSamples) {
            Log.w(TAG, "Áudio insuficiente para extração MFCC: " + length + " amostras");
            return null;
        }

        long startTime = System.nanoTime();

        // 1. Pré-ênfase
        double[] preEmphasized = applyPreEmphasis(samples, offset, length);

        // 2. Calcular número de frames
        int numFrames = 1 + (length - frameSizeSamples) / frameHopSamples;
        if (numFrames < 3) {
            Log.w(TAG, "Frames insuficientes para calcular deltas: " + numFrames);
            return null;
        }

        // 3. Extrair MFCC base (13 coeficientes) para cada frame
        double[][] baseMfcc = new double[numFrames][numMfccCoeffs];
        for (int i = 0; i < numFrames; i++) {
            int frameStart = i * frameHopSamples;
            baseMfcc[i] = extractFrameMFCC(preEmphasized, frameStart);
        }

        // 4. CMN (Cepstral Mean Normalization) — normaliza por utterance
        applyCMN(baseMfcc);

        // 5. Calcular deltas e delta-deltas
        double[][] deltas = computeDeltas(baseMfcc, 2);
        double[][] deltaDeltas = computeDeltas(deltas, 2);

        // 6. Concatenar: 13 MFCC + 13 delta + 13 delta-delta = 39 features
        float[][] result = new float[numFrames][numMfccCoeffs * 3];
        for (int i = 0; i < numFrames; i++) {
            for (int j = 0; j < numMfccCoeffs; j++) {
                result[i][j] = (float) baseMfcc[i][j];
                result[i][j + numMfccCoeffs] = (float) deltas[i][j];
                result[i][j + numMfccCoeffs * 2] = (float) deltaDeltas[i][j];
            }
        }

        long elapsed = (System.nanoTime() - startTime) / 1_000_000;
        Log.d(TAG, String.format("[EXTRACT] %d frames x %d features em %dms (%.1fs de áudio)",
                numFrames, numMfccCoeffs * 3, elapsed, (double) length / sampleRate));

        return result;
    }

    /**
     * Extrai MFCC de um único frame
     */
    private double[] extractFrameMFCC(double[] signal, int frameStart) {
        // Janelamento Hamming
        double[] frame = new double[frameSizeSamples];
        for (int i = 0; i < frameSizeSamples; i++) {
            frame[i] = signal[frameStart + i] * hammingWindow[i];
        }

        // FFT
        double[] fftReal = new double[fftSize];
        double[] fftImag = new double[fftSize];
        System.arraycopy(frame, 0, fftReal, 0, Math.min(frameSizeSamples, fftSize));
        computeFFT(fftReal, fftImag);

        // Magnitude spectrum (metade do FFT — simetria)
        int spectrumSize = fftSize / 2 + 1;
        double[] powerSpectrum = new double[spectrumSize];
        for (int i = 0; i < spectrumSize; i++) {
            powerSpectrum[i] = fftReal[i] * fftReal[i] + fftImag[i] * fftImag[i];
        }

        // Mel FilterBank
        double[] melEnergies = new double[numMelFilters];
        for (int i = 0; i < numMelFilters; i++) {
            double sum = 0.0;
            for (int j = 0; j < spectrumSize; j++) {
                sum += powerSpectrum[j] * melFilterBank[i][j];
            }
            // Log energia (com floor para evitar log(0))
            melEnergies[i] = Math.log(Math.max(sum, 1e-22));
        }

        // DCT → coeficientes MFCC
        double[] mfcc = new double[numMfccCoeffs];
        for (int i = 0; i < numMfccCoeffs; i++) {
            double sum = 0.0;
            for (int j = 0; j < numMelFilters; j++) {
                sum += dctMatrix[i][j] * melEnergies[j];
            }
            mfcc[i] = sum;
        }

        return mfcc;
    }

    // ========== Funções auxiliares ==========

    /**
     * Pré-ênfase: y[n] = x[n] - α * x[n-1]
     * Realça frequências altas, comum em processamento de fala
     */
    private double[] applyPreEmphasis(short[] samples, int offset, int length) {
        double[] result = new double[length];
        result[0] = samples[offset] / 32768.0;
        for (int i = 1; i < length; i++) {
            result[i] = (samples[offset + i] / 32768.0)
                    - preEmphasis * (samples[offset + i - 1] / 32768.0);
        }
        return result;
    }

    /**
     * Cria janela Hamming: w[n] = 0.54 - 0.46 * cos(2πn / (N-1))
     */
    private static double[] createHammingWindow(int size) {
        double[] window = new double[size];
        for (int i = 0; i < size; i++) {
            window[i] = 0.54 - 0.46 * Math.cos(2.0 * Math.PI * i / (size - 1));
        }
        return window;
    }

    /**
     * Cria banco de filtros Mel triangulares
     */
    private double[][] createMelFilterBank() {
        int spectrumSize = fftSize / 2 + 1;
        double[][] filterBank = new double[numMelFilters][spectrumSize];

        // Converter limites de frequência para escala Mel
        double melLow = hzToMel(melLowFreq);
        double melHigh = hzToMel(melHighFreq);

        // Pontos centrais uniformemente espaçados na escala Mel
        double[] melPoints = new double[numMelFilters + 2];
        for (int i = 0; i < melPoints.length; i++) {
            melPoints[i] = melLow + (melHigh - melLow) * i / (numMelFilters + 1);
        }

        // Converter pontos Mel de volta para Hz e depois para bins FFT
        int[] fftBins = new int[melPoints.length];
        for (int i = 0; i < melPoints.length; i++) {
            double hz = melToHz(melPoints[i]);
            fftBins[i] = (int) Math.floor((fftSize + 1) * hz / sampleRate);
            // Limitar ao tamanho do espectro
            fftBins[i] = Math.min(fftBins[i], spectrumSize - 1);
        }

        // Criar filtros triangulares
        for (int i = 0; i < numMelFilters; i++) {
            int left = fftBins[i];
            int center = fftBins[i + 1];
            int right = fftBins[i + 2];

            // Rampa ascendente
            for (int j = left; j < center; j++) {
                if (center != left) {
                    filterBank[i][j] = (double) (j - left) / (center - left);
                }
            }
            // Rampa descendente
            for (int j = center; j <= right; j++) {
                if (right != center) {
                    filterBank[i][j] = (double) (right - j) / (right - center);
                }
            }
        }

        return filterBank;
    }

    /**
     * Cria matriz DCT-II para transformar energias Mel em coeficientes MFCC
     */
    private double[][] createDCTMatrix() {
        double[][] dct = new double[numMfccCoeffs][numMelFilters];
        double normFactor = Math.sqrt(2.0 / numMelFilters);
        for (int i = 0; i < numMfccCoeffs; i++) {
            for (int j = 0; j < numMelFilters; j++) {
                dct[i][j] = normFactor * Math.cos(Math.PI * i * (j + 0.5) / numMelFilters);
            }
        }
        return dct;
    }

    /**
     * CMN — Cepstral Mean Normalization
     * Subtrai a média de cada coeficiente ao longo de todos os frames
     */
    private void applyCMN(double[][] mfcc) {
        int numFrames = mfcc.length;
        int numCoeffs = mfcc[0].length;

        for (int j = 0; j < numCoeffs; j++) {
            double mean = 0.0;
            for (int i = 0; i < numFrames; i++) {
                mean += mfcc[i][j];
            }
            mean /= numFrames;

            for (int i = 0; i < numFrames; i++) {
                mfcc[i][j] -= mean;
            }
        }
    }

    /**
     * Calcula coeficientes delta (velocidade) usando regressão linear local
     *
     * @param features matriz de features [frames][coeffs]
     * @param N        largura da janela de regressão
     * @return deltas com mesma dimensão
     */
    private double[][] computeDeltas(double[][] features, int N) {
        int numFrames = features.length;
        int numCoeffs = features[0].length;
        double[][] deltas = new double[numFrames][numCoeffs];

        double denominator = 0;
        for (int n = 1; n <= N; n++) {
            denominator += 2.0 * n * n;
        }

        for (int t = 0; t < numFrames; t++) {
            for (int j = 0; j < numCoeffs; j++) {
                double numerator = 0;
                for (int n = 1; n <= N; n++) {
                    int tPlusN = Math.min(t + n, numFrames - 1);
                    int tMinusN = Math.max(t - n, 0);
                    numerator += n * (features[tPlusN][j] - features[tMinusN][j]);
                }
                deltas[t][j] = numerator / denominator;
            }
        }

        return deltas;
    }

    /**
     * FFT in-place (Cooley-Tukey radix-2 DIT)
     * Opera sobre arrays de tamanho potência de 2
     */
    private void computeFFT(double[] real, double[] imag) {
        int n = real.length;

        // Bit-reversal permutation
        int j = 0;
        for (int i = 0; i < n; i++) {
            if (j > i) {
                double tempR = real[j];
                double tempI = imag[j];
                real[j] = real[i];
                imag[j] = imag[i];
                real[i] = tempR;
                imag[i] = tempI;
            }
            int m = n >> 1;
            while (m >= 1 && j >= m) {
                j -= m;
                m >>= 1;
            }
            j += m;
        }

        // Butterfly operations
        for (int step = 2; step <= n; step <<= 1) {
            int halfStep = step >> 1;
            double angle = -2.0 * Math.PI / step;
            double wReal = Math.cos(angle);
            double wImag = Math.sin(angle);

            for (int group = 0; group < n; group += step) {
                double curReal = 1.0;
                double curImag = 0.0;

                for (int pair = 0; pair < halfStep; pair++) {
                    int even = group + pair;
                    int odd = even + halfStep;

                    double tR = curReal * real[odd] - curImag * imag[odd];
                    double tI = curReal * imag[odd] + curImag * real[odd];

                    real[odd] = real[even] - tR;
                    imag[odd] = imag[even] - tI;
                    real[even] = real[even] + tR;
                    imag[even] = imag[even] + tI;

                    double newCurReal = curReal * wReal - curImag * wImag;
                    curImag = curReal * wImag + curImag * wReal;
                    curReal = newCurReal;
                }
            }
        }
    }

    // ========== Conversões Mel ↔ Hz ==========

    /** Converte frequência em Hz para escala Mel */
    private static double hzToMel(double hz) {
        return 2595.0 * Math.log10(1.0 + hz / 700.0);
    }

    /** Converte escala Mel para frequência em Hz */
    private static double melToHz(double mel) {
        return 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0);
    }

    // ========== Getters para benchmark/debug ==========

    public int getExtractorVersion() {
        return EXTRACTOR_VERSION;
    }

    public int getFrameSizeSamples() {
        return frameSizeSamples;
    }

    public int getFrameHopSamples() {
        return frameHopSamples;
    }

    public int getNumMfccCoeffs() {
        return numMfccCoeffs;
    }

    public int getFeaturesPerFrame() {
        return numMfccCoeffs * 3;
    }
}
