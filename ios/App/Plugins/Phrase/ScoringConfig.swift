import Foundation

/// Configuração centralizada de pesos, thresholds, cooldowns e limites do pipeline.
/// Paridade com ScoringConfig.java (Android).
class ScoringConfig {
    // MARK: - Fatores acústicos
    var acousticRmsWeight: Double = 0.3
    var acousticSpeechBurstWeight: Double = 0.4
    var acousticDurationWeight: Double = 0.3

    // MARK: - Fatores semânticos (frases detectadas)
    var semanticGenericWeight: Double = 0.2
    var semanticPersonalizedWeight: Double = 0.5
    var semanticOperationalWeight: Double = 0.0  // Operacionais não somam score

    // MARK: - Penalizações
    var stablePatternPenalty: Double = -0.3
    var stablePatternRmsVarianceThreshold: Double = 3.0
    var stablePatternWindowSeconds: Int = 30

    // MARK: - Thresholds de risco
    var riskLowMax: Double = 0.3
    var riskMediumMax: Double = 0.6
    var riskHighMin: Double = 0.6

    // MARK: - DTW matching
    var dtwDefaultThreshold: Double = 80.0
    var dtwConfidenceMin: Double = 0.5
    var dtwAbandonFactor: Double = 1.5

    // MARK: - Limites de frases (SPEC v3 §9.4)
    var maxOperationalPhrases: Int = 4
    var maxContextualPhrases: Int = 10
    var maxGenericPhrases: Int = 8
    var minSamplesPerPhrase: Int = 3
    var maxSamplesPerPhrase: Int = 5

    // MARK: - Enrollment
    var enrollmentConsistencyMaxDistance: Double = 120.0
    var enrollmentConflictMinDistance: Double = 2.0
    var enrollmentThresholdFactor: Double = 4.0

    // MARK: - Segurança por comando (SPEC v3 §10)
    struct CommandSecurity {
        let confidenceMin: Double
        let repetitionsRequired: Int
        let cooldownSeconds: Int
    }

    var commandSecurity: [String: CommandSecurity] = [
        "start_recording": CommandSecurity(confidenceMin: 0.55, repetitionsRequired: 1, cooldownSeconds: 5),
        "stop_recording":  CommandSecurity(confidenceMin: 0.65, repetitionsRequired: 1, cooldownSeconds: 10),
        "start_panic":     CommandSecurity(confidenceMin: 0.85, repetitionsRequired: 2, cooldownSeconds: 30),
        "cancel_panic":    CommandSecurity(confidenceMin: 0.9, repetitionsRequired: 3, cooldownSeconds: 60),
    ]

    // MARK: - Repetição
    var repetitionBufferMaxAge: TimeInterval = 15.0
    var repetitionBufferMaxEntries: Int = 8

    // MARK: - Confirmation window
    var confirmationWindowPreSeconds: Double = 3.0
    var confirmationWindowPostSeconds: Double = 3.0

    // MARK: - Mode B (escuta contínua leve)
    var modeBEnabled: Bool = true
    var modeBIntervalMs: Int = 500
    var modeBMaxPhrases: Int = 4
}
