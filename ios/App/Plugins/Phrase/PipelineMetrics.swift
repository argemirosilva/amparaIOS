import Foundation

/// Coleta métricas de observabilidade do pipeline no iOS.
/// Paridade com PipelineMetrics.java.
class PipelineMetrics {
    // Layer 1 — detecção acústica
    var l1FramesProcessed: Int = 0
    var l1AcousticTriggers: Int = 0

    // Layer 2 — buffer
    var l2BufferSnapshots: Int = 0

    // Layer 3 — phrase spotting
    var l3AnalysesModeA: Int = 0
    var l3AnalysesModeB: Int = 0
    var l3MatchesModeA: Int = 0
    var l3MatchesModeB: Int = 0

    // Layer 4 — scoring
    var l4ScoreCalculations: Int = 0
    var l4ActionsTriggered: Int = 0

    // Timestamps
    var startTime: Date = Date()
    var lastTriggerTime: Date?
    var lastMatchTime: Date?
    var lastActionTime: Date?

    /// Reseta todos os contadores
    func reset() {
        l1FramesProcessed = 0
        l1AcousticTriggers = 0
        l2BufferSnapshots = 0
        l3AnalysesModeA = 0
        l3AnalysesModeB = 0
        l3MatchesModeA = 0
        l3MatchesModeB = 0
        l4ScoreCalculations = 0
        l4ActionsTriggered = 0
        startTime = Date()
        lastTriggerTime = nil
        lastMatchTime = nil
        lastActionTime = nil
    }

    /// Retorna resumo para log
    func summary() -> String {
        let uptime = Int(Date().timeIntervalSince(startTime))
        return "[PipelineMetrics-iOS] uptime=\(uptime)s | L1: frames=\(l1FramesProcessed) triggers=\(l1AcousticTriggers) | L3: A=\(l3AnalysesModeA)/\(l3MatchesModeA) B=\(l3AnalysesModeB)/\(l3MatchesModeB) | L4: scores=\(l4ScoreCalculations) actions=\(l4ActionsTriggered)"
    }
}
