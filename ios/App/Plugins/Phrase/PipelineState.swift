import Foundation

/// Estados do pipeline de detecção — paridade com PipelineState.java
enum PipelineState: String {
    /// Monitoramento idle — apenas Layer 1 (acústica leve)
    case idleMonitoring = "IDLE_MONITORING"
    /// Suspeita acústica detectada — preparando buffer
    case acousticSuspicion = "ACOUSTIC_SUSPICION"
    /// Janela de confirmação aberta — coletando áudio
    case confirmationWindow = "CONFIRMATION_WINDOW"
    /// Análise de frase em andamento — Layer 3 (DTW)
    case phraseAnalysis = "PHRASE_ANALYSIS"
    /// Cálculo de risk score — Layer 4
    case riskScoring = "RISK_SCORING"
    /// Ação disparada — aguardando resolução
    case actionTriggered = "ACTION_TRIGGERED"
    /// Cooldown após ação
    case cooldown = "COOLDOWN"
    /// Escuta contínua de comandos (Mode B)
    case commandListening = "COMMAND_LISTENING"
}
