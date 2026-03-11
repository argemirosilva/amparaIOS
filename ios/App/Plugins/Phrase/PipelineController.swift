import Foundation

/// Orquestrador principal do pipeline de detecção no iOS.
/// Gerencia state machine, integra 4 layers, aplica segurança. Paridade com PipelineController.java.
class PipelineController {
    /// Protocolo para ações do pipeline
    protocol ActionListener: AnyObject {
        func onPipelineAction(action: String, phraseId: String?, confidence: Double, reasons: [String])
    }

    private let config: ScoringConfig
    private let enrollmentManager: PhraseEnrollmentManager
    private let phraseDetector: PhraseDetector
    private let riskScorer: RiskScorer
    private let repetitionTracker: CommandRepetitionTracker
    private let ringBuffer: RingAudioBuffer
    private let metrics = PipelineMetrics()

    private(set) var currentState: PipelineState = .idleMonitoring
    weak var actionListener: ActionListener?

    init(config: ScoringConfig, enrollmentManager: PhraseEnrollmentManager) {
        self.config = config
        self.enrollmentManager = enrollmentManager
        self.phraseDetector = PhraseDetector(config: config)
        self.riskScorer = RiskScorer(config: config)
        self.repetitionTracker = CommandRepetitionTracker(config: config)
        self.ringBuffer = RingAudioBuffer(durationSeconds: config.confirmationWindowPreSeconds + config.confirmationWindowPostSeconds)

        print("[PipelineController-iOS] Inicializado | estado=\(currentState.rawValue)")
    }

    // MARK: - Entradas do pipeline (chamados pelo AudioTriggerNativePlugin)

    /// Alimenta áudio bruto no ring buffer (Layer 2)
    func feedAudio(samples: [Int16], offset: Int, length: Int) {
        ringBuffer.write(samples: samples, offset: offset, length: length)
        metrics.l1FramesProcessed += 1
    }

    /// Alimenta RMS para detecção de padrão estável
    func feedRmsDb(_ rmsDb: Double) {
        riskScorer.feedRmsDb(rmsDb)
    }

    /// Trigger acústico do detector existente (Layer 1 → Layer 3)
    func onAcousticTrigger(speechDensity: Double, reason: String) {
        metrics.l1AcousticTriggers += 1
        metrics.lastTriggerTime = Date()

        guard !enrollmentManager.loadedTemplates.isEmpty else { return }
        guard currentState == .idleMonitoring || currentState == .commandListening else { return }

        // Transição de estado
        currentState = .confirmationWindow
        metrics.l2BufferSnapshots += 1

        // Capturar snapshot do buffer
        let audioSnapshot = ringBuffer.snapshotAll()
        guard audioSnapshot.count > 0 else {
            currentState = .idleMonitoring
            return
        }

        // Layer 3: Análise Mode A (triggered)
        currentState = .phraseAnalysis
        metrics.l3AnalysesModeA += 1

        let detections = phraseDetector.analyzeModeA(
            audioSamples: audioSnapshot,
            templates: enrollmentManager.loadedTemplates)

        if !detections.isEmpty {
            metrics.l3MatchesModeA += detections.count
            metrics.lastMatchTime = Date()

            // Layer 4: Risk scoring
            currentState = .riskScoring
            metrics.l4ScoreCalculations += 1

            let scoringResult = riskScorer.calculateScore(
                speechDensity: speechDensity,
                loudDensity: 0,
                rmsDb: 0,
                detectedPhrases: detections)

            // Processar comandos operacionais
            processOperationalCommands(detections: detections)

            // Notificar ação se score alto
            if scoringResult.level == .high || scoringResult.level == .critical {
                metrics.l4ActionsTriggered += 1
                metrics.lastActionTime = Date()
                currentState = .actionTriggered

                actionListener?.onPipelineAction(
                    action: "RISK_\(scoringResult.level.rawValue)",
                    phraseId: detections.first?.phraseId,
                    confidence: scoringResult.score,
                    reasons: scoringResult.reasons)
            }
        }

        // Voltar ao idle
        currentState = .idleMonitoring
    }

    // MARK: - Comandos operacionais

    private func processOperationalCommands(detections: [PhraseDetector.DetectionResult]) {
        for detection in detections where detection.type == .operational {
            let commandId = detection.phraseId

            // Verificar segurança do comando
            guard let security = config.commandSecurity[commandId] else { continue }

            // Verificar cooldown
            if repetitionTracker.isInCooldown(commandId: commandId, cooldownSeconds: security.cooldownSeconds) {
                print("[PipelineController-iOS] Comando '\(commandId)' em cooldown — ignorado")
                continue
            }

            // Verificar confidence mínima
            guard detection.confidence >= security.confidenceMin else {
                print("[PipelineController-iOS] Comando '\(commandId)' conf=\(String(format: "%.2f", detection.confidence)) < \(security.confidenceMin) — ignorado")
                continue
            }

            // Registrar repetição
            repetitionTracker.recordMatch(commandId: commandId, confidence: detection.confidence)

            // Verificar repetições necessárias
            let repetitions = repetitionTracker.getRecentRepetitions(commandId: commandId)
            if repetitions >= security.repetitionsRequired {
                // Executar ação
                repetitionTracker.recordAction(commandId: commandId)
                metrics.l4ActionsTriggered += 1
                metrics.lastActionTime = Date()

                print("[PipelineController-iOS] Comando '\(commandId)' executado | conf=\(String(format: "%.2f", detection.confidence)) | reps=\(repetitions)")

                actionListener?.onPipelineAction(
                    action: "COMMAND",
                    phraseId: commandId,
                    confidence: detection.confidence,
                    reasons: ["Comando operacional reconhecido"])
            }
        }
    }

    // MARK: - Diagnósticos

    func getDiagnostics() -> String {
        return "[Pipeline-iOS] state=\(currentState.rawValue) | \(metrics.summary())"
    }

    func reset() {
        currentState = .idleMonitoring
        riskScorer.reset()
        repetitionTracker.reset()
        ringBuffer.reset()
        metrics.reset()
    }
}
