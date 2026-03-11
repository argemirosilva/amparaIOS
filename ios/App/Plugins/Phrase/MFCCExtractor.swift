import Foundation
import Accelerate

/// Extrator de coeficientes MFCC (Mel-Frequency Cepstral Coefficients) para iOS.
///
/// Pipeline: PCM 16kHz → pré-ênfase → janelamento Hamming → FFT (vDSP) → Mel FilterBank
/// → log energia → DCT → 13 MFCC → Delta + Delta-Delta → 39 features/frame.
///
/// Usa Accelerate.framework (vDSP) para FFT nativa com performance otimizada.
class MFCCExtractor {
    /// Versão do extrator — deve coincidir com Android para paridade de templates
    static let extractorVersion = 1

    // Parâmetros de frame
    let sampleRate: Int
    let frameSizeMs: Int
    let frameHopMs: Int
    let frameSizeSamples: Int
    let frameHopSamples: Int

    // Parâmetros MFCC
    let fftSize: Int
    let numMelFilters: Int
    let numMfccCoeffs: Int
    let preEmphasisCoeff: Double
    let melLowFreq: Double
    let melHighFreq: Double

    // Estruturas pré-calculadas
    private let hammingWindow: [Double]
    private let melFilterBank: [[Double]]
    private let dctMatrix: [[Double]]

    // vDSP FFT setup (Accelerate)
    private let fftSetup: vDSP.FFT<DSPDoubleSplitComplex>
    private let log2n: Int

    /// Construtor com parâmetros padrão do SPEC (idênticos ao Android)
    init() {
        self.init(sampleRate: 16000, frameSizeMs: 25, frameHopMs: 10,
                  fftSize: 512, numMelFilters: 26, numMfccCoeffs: 13,
                  preEmphasis: 0.97, melLowFreq: 300.0, melHighFreq: 8000.0)
    }

    /// Construtor completo com parâmetros configuráveis
    init(sampleRate: Int, frameSizeMs: Int, frameHopMs: Int,
         fftSize: Int, numMelFilters: Int, numMfccCoeffs: Int,
         preEmphasis: Double, melLowFreq: Double, melHighFreq: Double) {
        self.sampleRate = sampleRate
        self.frameSizeMs = frameSizeMs
        self.frameHopMs = frameHopMs
        self.frameSizeSamples = (sampleRate * frameSizeMs) / 1000
        self.frameHopSamples = (sampleRate * frameHopMs) / 1000
        self.fftSize = fftSize
        self.numMelFilters = numMelFilters
        self.numMfccCoeffs = numMfccCoeffs
        self.preEmphasisCoeff = preEmphasis
        self.melLowFreq = melLowFreq
        self.melHighFreq = melHighFreq

        // Log2 do FFT size para vDSP
        self.log2n = Int(log2(Double(fftSize)))

        // Criar FFT setup do Accelerate
        self.fftSetup = vDSP.FFT(log2n: UInt(log2n), radix: .radix2, ofType: DSPDoubleSplitComplex.self)!

        // Pré-calcular estruturas fixas
        self.hammingWindow = MFCCExtractor.createHammingWindow(size: frameSizeSamples)
        self.melFilterBank = MFCCExtractor.createMelFilterBank(
            fftSize: fftSize, sampleRate: sampleRate,
            numFilters: numMelFilters, lowFreq: melLowFreq, highFreq: melHighFreq)
        self.dctMatrix = MFCCExtractor.createDCTMatrix(
            numCoeffs: numMfccCoeffs, numFilters: numMelFilters)

        print("[MFCCExtractor-iOS] v\(MFCCExtractor.extractorVersion) | sr=\(sampleRate) | frame=\(frameSizeMs)ms/\(frameHopMs)ms | fft=\(fftSize) | mel=\(numMelFilters) | mfcc=\(numMfccCoeffs)")
    }

    /// Extrai MFCC completo de áudio PCM (39 features/frame)
    /// - Parameters:
    ///   - samples: áudio PCM 16-bit mono como Int16
    ///   - offset: início no array
    ///   - length: quantidade de amostras
    /// - Returns: [[Float]] com [numFrames][39] features, ou nil se insuficiente
    func extract(samples: [Int16], offset: Int, length: Int) -> [[Float]]? {
        guard length >= frameSizeSamples else {
            print("[MFCCExtractor-iOS] Áudio insuficiente: \(length) amostras")
            return nil
        }

        let startTime = CFAbsoluteTimeGetCurrent()

        // 1. Pré-ênfase
        let preEmphasized = applyPreEmphasis(samples: samples, offset: offset, length: length)

        // 2. Calcular número de frames
        let numFrames = 1 + (length - frameSizeSamples) / frameHopSamples
        guard numFrames >= 3 else {
            print("[MFCCExtractor-iOS] Frames insuficientes para deltas: \(numFrames)")
            return nil
        }

        // 3. Extrair MFCC base (13 coeficientes) para cada frame
        var baseMfcc = [[Double]](repeating: [Double](repeating: 0, count: numMfccCoeffs), count: numFrames)
        for i in 0..<numFrames {
            let frameStart = i * frameHopSamples
            baseMfcc[i] = extractFrameMFCC(signal: preEmphasized, frameStart: frameStart)
        }

        // 4. CMN (Cepstral Mean Normalization)
        applyCMN(mfcc: &baseMfcc)

        // 5. Calcular deltas e delta-deltas
        let deltas = computeDeltas(features: baseMfcc, windowSize: 2)
        let deltaDeltas = computeDeltas(features: deltas, windowSize: 2)

        // 6. Concatenar: 13 MFCC + 13 delta + 13 delta-delta = 39 features
        var result = [[Float]](repeating: [Float](repeating: 0, count: numMfccCoeffs * 3), count: numFrames)
        for i in 0..<numFrames {
            for j in 0..<numMfccCoeffs {
                result[i][j] = Float(baseMfcc[i][j])
                result[i][j + numMfccCoeffs] = Float(deltas[i][j])
                result[i][j + numMfccCoeffs * 2] = Float(deltaDeltas[i][j])
            }
        }

        let elapsed = (CFAbsoluteTimeGetCurrent() - startTime) * 1000
        print("[MFCCExtractor-iOS] \(numFrames) frames x \(numMfccCoeffs * 3) features em \(Int(elapsed))ms (\(String(format: "%.1f", Double(length) / Double(sampleRate)))s de áudio)")

        return result
    }

    // MARK: - Processamento interno

    /// Extrai MFCC de um único frame usando vDSP FFT
    private func extractFrameMFCC(signal: [Double], frameStart: Int) -> [Double] {
        // Janelamento Hamming
        var frame = [Double](repeating: 0, count: frameSizeSamples)
        for i in 0..<frameSizeSamples {
            frame[i] = signal[frameStart + i] * hammingWindow[i]
        }

        // FFT via Accelerate/vDSP
        var fftReal = [Double](repeating: 0, count: fftSize)
        var fftImag = [Double](repeating: 0, count: fftSize)
        for i in 0..<min(frameSizeSamples, fftSize) {
            fftReal[i] = frame[i]
        }

        // Split complex para vDSP
        let halfN = fftSize / 2
        var splitReal = [Double](repeating: 0, count: halfN)
        var splitImag = [Double](repeating: 0, count: halfN)

        // Converter para split complex
        fftReal.withUnsafeBufferPointer { realPtr in
            fftImag.withUnsafeBufferPointer { imagPtr in
                splitReal.withUnsafeMutableBufferPointer { splitRPtr in
                    splitImag.withUnsafeMutableBufferPointer { splitIPtr in
                        var input = DSPDoubleSplitComplex(
                            realp: UnsafeMutablePointer(mutating: realPtr.baseAddress!),
                            imagp: UnsafeMutablePointer(mutating: imagPtr.baseAddress!))
                        var output = DSPDoubleSplitComplex(
                            realp: splitRPtr.baseAddress!,
                            imagp: splitIPtr.baseAddress!)
                        vDSP_ctozD(&input, 2, &output, 1, vDSP_Length(halfN))
                    }
                }
            }
        }

        // FFT forward
        splitReal.withUnsafeMutableBufferPointer { rPtr in
            splitImag.withUnsafeMutableBufferPointer { iPtr in
                var splitComplex = DSPDoubleSplitComplex(realp: rPtr.baseAddress!, imagp: iPtr.baseAddress!)
                fftSetup.forward(input: splitComplex, output: &splitComplex)
            }
        }

        // Magnitude spectrum (power)
        let spectrumSize = fftSize / 2 + 1
        var powerSpectrum = [Double](repeating: 0, count: spectrumSize)
        for i in 0..<min(halfN, spectrumSize) {
            powerSpectrum[i] = splitReal[i] * splitReal[i] + splitImag[i] * splitImag[i]
        }

        // Mel FilterBank
        var melEnergies = [Double](repeating: 0, count: numMelFilters)
        for i in 0..<numMelFilters {
            var sum = 0.0
            for j in 0..<spectrumSize {
                sum += powerSpectrum[j] * melFilterBank[i][j]
            }
            melEnergies[i] = log(max(sum, 1e-22))
        }

        // DCT → coeficientes MFCC
        var mfcc = [Double](repeating: 0, count: numMfccCoeffs)
        for i in 0..<numMfccCoeffs {
            var sum = 0.0
            for j in 0..<numMelFilters {
                sum += dctMatrix[i][j] * melEnergies[j]
            }
            mfcc[i] = sum
        }

        return mfcc
    }

    // MARK: - Funções auxiliares

    /// Pré-ênfase: y[n] = x[n] - α * x[n-1]
    private func applyPreEmphasis(samples: [Int16], offset: Int, length: Int) -> [Double] {
        var result = [Double](repeating: 0, count: length)
        result[0] = Double(samples[offset]) / 32768.0
        for i in 1..<length {
            result[i] = (Double(samples[offset + i]) / 32768.0)
                - preEmphasisCoeff * (Double(samples[offset + i - 1]) / 32768.0)
        }
        return result
    }

    /// CMN — subtrai a média de cada coeficiente
    private func applyCMN(mfcc: inout [[Double]]) {
        let numFrames = mfcc.count
        let numCoeffs = mfcc[0].count
        for j in 0..<numCoeffs {
            var mean = 0.0
            for i in 0..<numFrames { mean += mfcc[i][j] }
            mean /= Double(numFrames)
            for i in 0..<numFrames { mfcc[i][j] -= mean }
        }
    }

    /// Calcula deltas via regressão linear local
    private func computeDeltas(features: [[Double]], windowSize N: Int) -> [[Double]] {
        let numFrames = features.count
        let numCoeffs = features[0].count
        var deltas = [[Double]](repeating: [Double](repeating: 0, count: numCoeffs), count: numFrames)
        var denominator = 0.0
        for n in 1...N { denominator += 2.0 * Double(n * n) }
        for t in 0..<numFrames {
            for j in 0..<numCoeffs {
                var numerator = 0.0
                for n in 1...N {
                    let tPlusN = min(t + n, numFrames - 1)
                    let tMinusN = max(t - n, 0)
                    numerator += Double(n) * (features[tPlusN][j] - features[tMinusN][j])
                }
                deltas[t][j] = numerator / denominator
            }
        }
        return deltas
    }

    // MARK: - Criação de estruturas pré-calculadas

    private static func createHammingWindow(size: Int) -> [Double] {
        var window = [Double](repeating: 0, count: size)
        for i in 0..<size {
            window[i] = 0.54 - 0.46 * cos(2.0 * .pi * Double(i) / Double(size - 1))
        }
        return window
    }

    private static func createMelFilterBank(fftSize: Int, sampleRate: Int, numFilters: Int, lowFreq: Double, highFreq: Double) -> [[Double]] {
        let spectrumSize = fftSize / 2 + 1
        var filterBank = [[Double]](repeating: [Double](repeating: 0, count: spectrumSize), count: numFilters)
        let melLow = hzToMel(lowFreq)
        let melHigh = hzToMel(highFreq)
        var melPoints = [Double](repeating: 0, count: numFilters + 2)
        for i in 0..<melPoints.count {
            melPoints[i] = melLow + (melHigh - melLow) * Double(i) / Double(numFilters + 1)
        }
        var fftBins = [Int](repeating: 0, count: melPoints.count)
        for i in 0..<melPoints.count {
            let hz = melToHz(melPoints[i])
            fftBins[i] = min(Int(floor(Double(fftSize + 1) * hz / Double(sampleRate))), spectrumSize - 1)
        }
        for i in 0..<numFilters {
            let left = fftBins[i], center = fftBins[i + 1], right = fftBins[i + 2]
            for j in left..<center {
                if center != left { filterBank[i][j] = Double(j - left) / Double(center - left) }
            }
            for j in center...right {
                if right != center { filterBank[i][j] = Double(right - j) / Double(right - center) }
            }
        }
        return filterBank
    }

    private static func createDCTMatrix(numCoeffs: Int, numFilters: Int) -> [[Double]] {
        var dct = [[Double]](repeating: [Double](repeating: 0, count: numFilters), count: numCoeffs)
        let normFactor = sqrt(2.0 / Double(numFilters))
        for i in 0..<numCoeffs {
            for j in 0..<numFilters {
                dct[i][j] = normFactor * cos(.pi * Double(i) * (Double(j) + 0.5) / Double(numFilters))
            }
        }
        return dct
    }

    private static func hzToMel(_ hz: Double) -> Double { 2595.0 * log10(1.0 + hz / 700.0) }
    private static func melToHz(_ mel: Double) -> Double { 700.0 * (pow(10.0, mel / 2595.0) - 1.0) }

    // MARK: - Getters
    var featuresPerFrame: Int { numMfccCoeffs * 3 }
}
