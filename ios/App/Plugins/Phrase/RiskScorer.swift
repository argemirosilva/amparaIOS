import Foundation

/// Calcula risk score final combinando sinais acústicos e semânticos no iOS.
/// Paridade com RiskScorer.java.
class RiskScorer {
    /// Nível de risco
    enum RiskLevel: String {
        case low = "LOW"
        case medium = "MEDIUM"
        case high = "HIGH"
        case critical = "CRITICAL"
    }

    /// Resultado do scoring
    struct ScoringResult {
        let score: Double
        let level: RiskLevel
        let reasons: [String]
        let stablePatternDetected: Bool
    }

    private let config: ScoringConfig

    // Histórico de RMS para detecção de padrão estável
    private var rmsHistory: [Double] = []
    private let rmsHistoryMaxSize: Int

    init(config: ScoringConfig) {
        self.config = config
        self.rmsHistoryMaxSize = config.stablePatternWindowSeconds
    }

    /// Alimenta RMS para detecção de padrão estável (TV/música)
    func feedRmsDb(_ rmsDb: Double) {
        rmsHistory.append(rmsDb)
        if rmsHistory.count > rmsHistoryMaxSize {
            rmsHistory.removeFirst()
        }
    }

    /// Calcula risk score
    func calculateScore(
        speechDensity: Double,
        loudDensity: Double,
        rmsDb: Double,
        detectedPhrases: [PhraseDetector.DetectionResult]
    ) -> ScoringResult {
        var score = 0.0
        var reasons = [String]()

        // 1. Componente acústico
        let acousticScore = (speechDensity * config.acousticSpeechBurstWeight
            + loudDensity * config.acousticRmsWeight)
        score += acousticScore
        if acousticScore > 0.2 {
            reasons.append("Atividade acústica: \(String(format: "%.0f%%", acousticScore * 100))")
        }

        // 2. Componente semântico (frases detectadas)
        for phrase in detectedPhrases {
            let weight: Double
            switch phrase.type {
            case .generic: weight = config.semanticGenericWeight
            case .contextual: weight = config.semanticPersonalizedWeight
            case .operational: weight = config.semanticOperationalWeight
            }
            let phraseContribution = phrase.confidence * weight
            score += phraseContribution
            if phraseContribution > 0 {
                reasons.append("Frase '\(phrase.phraseId)': conf=\(String(format: "%.0f%%", phrase.confidence * 100))")
            }
        }

        // 3. Penalização de padrão estável
        let stableDetected = isStablePattern()
        if stableDetected {
            score += config.stablePatternPenalty
            reasons.append("Padrão estável detectado (TV/música): \(String(format: "%.0f", config.stablePatternPenalty * 100))%")
        }

        // Clampar entre 0 e 1
        score = max(0, min(1.0, score))

        // Determinar nível de risco
        let level: RiskLevel
        if score >= 0.85 { level = .critical }
        else if score >= config.riskHighMin { level = .high }
        else if score >= config.riskLowMax { level = .medium }
        else { level = .low }

        return ScoringResult(score: score, level: level,
                            reasons: reasons, stablePatternDetected: stableDetected)
    }

    /// Reseta estado
    func reset() {
        rmsHistory.removeAll()
    }

    // MARK: - Privado

    /// Detecta padrão estável via variância RMS
    private func isStablePattern() -> Bool {
        guard rmsHistory.count >= config.stablePatternWindowSeconds / 2 else { return false }
        let mean = rmsHistory.reduce(0, +) / Double(rmsHistory.count)
        let variance = rmsHistory.reduce(0) { $0 + ($1 - mean) * ($1 - mean) } / Double(rmsHistory.count)
        return variance < config.stablePatternRmsVarianceThreshold
    }
}
