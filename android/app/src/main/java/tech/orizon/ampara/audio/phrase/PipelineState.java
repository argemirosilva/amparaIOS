package tech.orizon.ampara.audio.phrase;

/**
 * Estados do pipeline de detecção (SPEC seção 7).
 * 
 * Descreve cada estágio com entrada, saída, timeout e comportamento.
 */
public enum PipelineState {

    /** Monitoramento idle — L1 roda contínuo */
    IDLE_MONITORING,

    /** L1 detectou evento acústico suspeito — preparando escalação */
    ACOUSTIC_SUSPICION,

    /** Buffer de confirmação está sendo montado (pre + post trigger) */
    CONFIRMATION_WINDOW,

    /** L3 analisando frases na janela de confirmação */
    PHRASE_ANALYSIS,

    /** L4 calculando riskScore e determinando ação */
    RISK_SCORING,

    /** Ação operacional sendo executada (gravação, pânico, etc.) */
    ACTION_TRIGGERED,

    /** Período de cooldown após ação executada */
    COOLDOWN,

    /** Modo B — escuta contínua low-power de comandos operacionais */
    COMMAND_LISTENING
}
