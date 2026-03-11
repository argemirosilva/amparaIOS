import Foundation

/// Detector de frases (Layer 3) — Modos A e B no iOS.
/// Paridade com PhraseDetector.java.
class PhraseDetector {
    /// Resultado de detecção
    struct DetectionResult {
        let phraseId: String
        let type: PhraseTemplate.PhraseType
        let confidence: Double
        let distance: Double
        let mode: String  // "MODE_A" ou "MODE_B"
    }

    private let mfccExtractor = MFCCExtractor()
    private let config: ScoringConfig

    init(config: ScoringConfig) {
        self.config = config
    }

    /// Mode A: análise completa da confirmation window (triggered)
    func analyzeModeA(audioSamples: [Int16], templates: [String: [PhraseTemplate]]) -> [DetectionResult] {
        guard let queryFeatures = mfccExtractor.extract(samples: audioSamples, offset: 0, length: audioSamples.count) else {
            return []
        }

        var results = [DetectionResult]()

        for (phraseId, phraseTemplates) in templates {
            guard let firstTemplate = phraseTemplates.first else { continue }

            let templateFeatures = phraseTemplates.map { $0.features }
            let thresholds = phraseTemplates.map { $0.threshold }

            let match = DTWMatcher.matchAgainstTemplates(
                query: queryFeatures,
                templates: templateFeatures,
                thresholds: thresholds,
                abandonFactor: config.dtwAbandonFactor)

            if match.matched && match.confidence >= config.dtwConfidenceMin {
                results.append(DetectionResult(
                    phraseId: phraseId,
                    type: firstTemplate.type,
                    confidence: match.confidence,
                    distance: match.bestDistance,
                    mode: "MODE_A"))
            }
        }

        // Ordenar por confidence decrescente
        return results.sorted { $0.confidence > $1.confidence }
    }

    /// Mode B: análise leve contínua de comandos operacionais
    func analyzeModeB(audioSamples: [Int16], templates: [String: [PhraseTemplate]]) -> [DetectionResult] {
        // Filtrar apenas operacionais para Mode B
        let operationalTemplates = templates.filter { _, templates in
            templates.first?.type == .operational
        }

        // Limitar número de frases analisadas
        let limited = Dictionary(uniqueKeysWithValues:
            operationalTemplates.prefix(config.modeBMaxPhrases).map { ($0.key, $0.value) })

        guard let queryFeatures = mfccExtractor.extract(samples: audioSamples, offset: 0, length: audioSamples.count) else {
            return []
        }

        var results = [DetectionResult]()

        for (phraseId, phraseTemplates) in limited {
            let templateFeatures = phraseTemplates.map { $0.features }
            let thresholds = phraseTemplates.map { $0.threshold }

            let match = DTWMatcher.matchAgainstTemplates(
                query: queryFeatures,
                templates: templateFeatures,
                thresholds: thresholds,
                abandonFactor: config.dtwAbandonFactor)

            if match.matched && match.confidence >= config.dtwConfidenceMin {
                results.append(DetectionResult(
                    phraseId: phraseId,
                    type: .operational,
                    confidence: match.confidence,
                    distance: match.bestDistance,
                    mode: "MODE_B"))
            }
        }

        return results.sorted { $0.confidence > $1.confidence }
    }
}
