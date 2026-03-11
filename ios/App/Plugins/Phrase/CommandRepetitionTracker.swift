import Foundation

/// Rastreia repetições e cooldowns de comandos operacionais no iOS.
/// Paridade com CommandRepetitionTracker.java.
class CommandRepetitionTracker {
    /// Evento de match com timestamp
    private struct MatchEvent {
        let timestamp: Date
        let confidence: Double
    }

    /// Buffer circular de eventos por comando
    private var history: [String: [MatchEvent]] = [:]
    /// Timestamp da última ação executada por comando
    private var lastActionTime: [String: Date] = [:]

    private let maxAge: TimeInterval
    private let maxEntries: Int

    init(config: ScoringConfig) {
        self.maxAge = config.repetitionBufferMaxAge
        self.maxEntries = config.repetitionBufferMaxEntries
    }

    /// Registra um match de comando
    func recordMatch(commandId: String, confidence: Double) {
        cleanupOldEntries(commandId: commandId)
        var events = history[commandId] ?? []
        events.append(MatchEvent(timestamp: Date(), confidence: confidence))
        // Limitar tamanho
        if events.count > maxEntries {
            events.removeFirst(events.count - maxEntries)
        }
        history[commandId] = events
    }

    /// Conta repetições recentes dentro da janela de tempo
    func getRecentRepetitions(commandId: String) -> Int {
        cleanupOldEntries(commandId: commandId)
        return history[commandId]?.count ?? 0
    }

    /// Verifica se o comando está em cooldown
    func isInCooldown(commandId: String, cooldownSeconds: Int) -> Bool {
        guard let lastAction = lastActionTime[commandId] else { return false }
        return Date().timeIntervalSince(lastAction) < Double(cooldownSeconds)
    }

    /// Registra que uma ação foi executada
    func recordAction(commandId: String) {
        lastActionTime[commandId] = Date()
        history[commandId] = [] // Limpar buffer após ação
    }

    /// Reseta tudo
    func reset() {
        history.removeAll()
        lastActionTime.removeAll()
    }

    // MARK: - Privado

    private func cleanupOldEntries(commandId: String) {
        guard var events = history[commandId] else { return }
        let cutoff = Date().addingTimeInterval(-maxAge)
        events.removeAll { $0.timestamp < cutoff }
        history[commandId] = events
    }
}
