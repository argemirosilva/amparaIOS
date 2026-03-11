import Foundation

/// DTW (Dynamic Time Warping) para comparação de sequências MFCC no iOS.
/// Paridade funcional com DTWMatcher.java (Android).
class DTWMatcher {
    /// Distância euclidiana entre dois vetores de features
    private static func euclideanDistance(_ a: [Float], _ b: [Float]) -> Double {
        var sum = 0.0
        for i in 0..<min(a.count, b.count) {
            let diff = Double(a[i] - b[i])
            sum += diff * diff
        }
        return sqrt(sum)
    }

    /// Calcula distância DTW entre duas sequências com early abandoning
    /// - Parameters:
    ///   - seq1: sequência de referência [frames][features]
    ///   - seq2: sequência de query [frames][features]
    ///   - abandonThreshold: distância máxima antes de cancelar (0 = sem limite)
    /// - Returns: distância DTW normalizada, ou Double.infinity se abandonada
    static func computeDTW(seq1: [[Float]], seq2: [[Float]], abandonThreshold: Double = 0) -> Double {
        let n = seq1.count
        let m = seq2.count
        guard n > 0 && m > 0 else { return Double.infinity }

        // Otimização: usar apenas 2 linhas de memória
        var prev = [Double](repeating: Double.infinity, count: m + 1)
        var curr = [Double](repeating: Double.infinity, count: m + 1)
        prev[0] = 0.0

        for i in 1...n {
            curr[0] = Double.infinity
            var rowMin = Double.infinity

            for j in 1...m {
                let cost = euclideanDistance(seq1[i - 1], seq2[j - 1])
                curr[j] = cost + min(prev[j], prev[j - 1], curr[j - 1])
                rowMin = min(rowMin, curr[j])
            }

            // Early abandoning
            if abandonThreshold > 0 && rowMin > abandonThreshold {
                return Double.infinity
            }

            swap(&prev, &curr)
        }

        // Normalizar pela soma dos comprimentos
        let rawDistance = prev[m]
        return rawDistance / Double(n + m)
    }

    /// Calcula confidence a partir da distância DTW e threshold
    /// - Parameters:
    ///   - distance: distância DTW normalizada
    ///   - threshold: threshold de aceitação
    /// - Returns: confidence 0.0 a 1.0
    static func calculateConfidence(distance: Double, threshold: Double) -> Double {
        guard threshold > 0 else { return 0 }
        if distance >= threshold { return 0 }
        return max(0, min(1.0, 1.0 - (distance / threshold)))
    }

    /// Resultado de matching contra múltiplos templates
    struct MatchResult {
        let matched: Bool
        let bestDistance: Double
        let confidence: Double
        let templateIndex: Int
    }

    /// Faz matching de uma query contra todos os templates de uma frase
    static func matchAgainstTemplates(
        query: [[Float]],
        templates: [[[Float]]],
        thresholds: [Double],
        abandonFactor: Double = 1.5
    ) -> MatchResult {
        var bestDistance = Double.infinity
        var bestIndex = -1

        for (i, template) in templates.enumerated() {
            let threshold = i < thresholds.count ? thresholds[i] : (thresholds.last ?? 100.0)
            let abandon = threshold * abandonFactor
            let dist = computeDTW(seq1: template, seq2: query, abandonThreshold: abandon)
            if dist < bestDistance {
                bestDistance = dist
                bestIndex = i
            }
        }

        let bestThreshold = bestIndex >= 0 && bestIndex < thresholds.count
            ? thresholds[bestIndex] : (thresholds.last ?? 100.0)
        let confidence = calculateConfidence(distance: bestDistance, threshold: bestThreshold)
        let matched = bestDistance < bestThreshold

        return MatchResult(matched: matched, bestDistance: bestDistance,
                          confidence: confidence, templateIndex: bestIndex)
    }
}
