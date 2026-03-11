import Foundation

/// Gerencia enrollment de frases personalizadas no iOS.
/// Paridade com PhraseEnrollmentManager.java.
class PhraseEnrollmentManager {
    /// Resultado de operação de enrollment
    struct EnrollmentResult {
        let success: Bool
        let message: String
        var currentSamples: Int = 0
        var minRequired: Int = 0
        var maxAllowed: Int = 0
    }

    private let mfccExtractor = MFCCExtractor()
    private let config: ScoringConfig
    private let templatesDir: URL

    // Estado do enrollment em andamento
    private var currentPhraseId: String?
    private var currentType: PhraseTemplate.PhraseType?
    private var currentSamples: [[[Float]]] = []  // features MFCC de cada amostra

    // Templates carregados por phraseId
    private(set) var loadedTemplates: [String: [PhraseTemplate]] = [:]

    init(config: ScoringConfig) {
        self.config = config
        self.templatesDir = PhraseTemplate.templatesDirectory()
    }

    /// Carrega todos os templates do disco
    @discardableResult
    func loadAllTemplates() -> Int {
        loadedTemplates.removeAll()
        let fm = FileManager.default
        guard let files = try? fm.contentsOfDirectory(at: templatesDir,
                includingPropertiesForKeys: nil) else { return 0 }

        var count = 0
        for file in files where file.pathExtension == "phrasetemplate" {
            if let template = try? PhraseTemplate.load(from: file) {
                var list = loadedTemplates[template.phraseId] ?? []
                list.append(template)
                loadedTemplates[template.phraseId] = list
                count += 1
            }
        }
        print("[PhraseEnrollment-iOS] Carregados \(count) templates de \(loadedTemplates.count) frases")
        return count
    }

    /// Inicia enrollment de uma frase
    func startEnrollment(phraseId: String, type: PhraseTemplate.PhraseType) -> EnrollmentResult {
        // Verificar limites
        let existingCount = countPhrasesByType(type)
        let limit: Int
        switch type {
        case .operational: limit = config.maxOperationalPhrases
        case .contextual: limit = config.maxContextualPhrases
        case .generic: limit = config.maxGenericPhrases
        }

        if existingCount >= limit && loadedTemplates[phraseId] == nil {
            return EnrollmentResult(success: false,
                message: "Limite de \(limit) frases \(type.rawValue) atingido")
        }

        currentPhraseId = phraseId
        currentType = type
        currentSamples = []

        return EnrollmentResult(success: true,
            message: "Enrollment iniciado para '\(phraseId)'. Grave \(config.minSamplesPerPhrase)-\(config.maxSamplesPerPhrase) amostras.",
            currentSamples: 0, minRequired: config.minSamplesPerPhrase,
            maxAllowed: config.maxSamplesPerPhrase)
    }

    /// Adiciona uma amostra de áudio
    func addSample(samples: [Int16], offset: Int, length: Int) -> EnrollmentResult {
        guard currentPhraseId != nil else {
            return EnrollmentResult(success: false, message: "Nenhum enrollment em andamento")
        }

        guard currentSamples.count < config.maxSamplesPerPhrase else {
            return EnrollmentResult(success: false,
                message: "Máximo de \(config.maxSamplesPerPhrase) amostras atingido",
                currentSamples: currentSamples.count,
                minRequired: config.minSamplesPerPhrase,
                maxAllowed: config.maxSamplesPerPhrase)
        }

        // Extrair MFCC
        guard let features = mfccExtractor.extract(samples: samples, offset: offset, length: length) else {
            return EnrollmentResult(success: false, message: "Áudio insuficiente ou inválido")
        }

        currentSamples.append(features)
        return EnrollmentResult(success: true,
            message: "Amostra \(currentSamples.count) adicionada",
            currentSamples: currentSamples.count,
            minRequired: config.minSamplesPerPhrase,
            maxAllowed: config.maxSamplesPerPhrase)
    }

    /// Finaliza enrollment: valida consistência, calcula thresholds, persiste
    func finishEnrollment() -> EnrollmentResult {
        guard let phraseId = currentPhraseId, let type = currentType else {
            return EnrollmentResult(success: false, message: "Nenhum enrollment em andamento")
        }

        guard currentSamples.count >= config.minSamplesPerPhrase else {
            return EnrollmentResult(success: false,
                message: "Mínimo de \(config.minSamplesPerPhrase) amostras necessário (atual: \(currentSamples.count))")
        }

        // Validar consistência intra-amostras
        var maxIntraDistance = 0.0
        for i in 0..<currentSamples.count {
            for j in (i+1)..<currentSamples.count {
                let dist = DTWMatcher.computeDTW(seq1: currentSamples[i], seq2: currentSamples[j])
                maxIntraDistance = max(maxIntraDistance, dist)
            }
        }

        if maxIntraDistance > config.enrollmentConsistencyMaxDistance {
            return EnrollmentResult(success: false,
                message: "Amostras inconsistentes (distância \(String(format: "%.1f", maxIntraDistance)) > \(config.enrollmentConsistencyMaxDistance)). Grave novamente.")
        }

        // Calcular threshold adaptativo
        let threshold = maxIntraDistance * config.enrollmentThresholdFactor

        // Remover templates antigos desta frase
        removePhrase(phraseId: phraseId)

        // Salvar novos templates
        for (i, features) in currentSamples.enumerated() {
            let template = PhraseTemplate(phraseId: phraseId, sampleIndex: i,
                                          type: type, features: features,
                                          extractorVersion: MFCCExtractor.extractorVersion,
                                          threshold: threshold)
            try? template.save(to: templatesDir)

            var list = loadedTemplates[phraseId] ?? []
            list.append(template)
            loadedTemplates[phraseId] = list
        }

        // Limpar estado
        currentPhraseId = nil
        currentType = nil
        currentSamples = []

        return EnrollmentResult(success: true,
            message: "Frase '\(phraseId)' cadastrada com \(loadedTemplates[phraseId]?.count ?? 0) amostras (threshold: \(String(format: "%.1f", threshold)))")
    }

    /// Cancela enrollment em andamento
    func cancelEnrollment() {
        currentPhraseId = nil
        currentType = nil
        currentSamples = []
    }

    /// Remove uma frase e seus templates do disco
    @discardableResult
    func removePhrase(phraseId: String) -> Bool {
        loadedTemplates.removeValue(forKey: phraseId)
        let fm = FileManager.default
        guard let files = try? fm.contentsOfDirectory(at: templatesDir,
                includingPropertiesForKeys: nil) else { return false }
        var removed = false
        for file in files where file.lastPathComponent.hasPrefix("\(phraseId)_") {
            try? fm.removeItem(at: file)
            removed = true
        }
        return removed
    }

    /// Verifica se há templates obsoletos
    func hasStaleTemplates() -> Bool {
        loadedTemplates.values.flatMap { $0 }.contains { $0.isStale }
    }

    /// Diagnósticos em texto
    func getDiagnostics() -> String {
        let totalTemplates = loadedTemplates.values.reduce(0) { $0 + $1.count }
        let stale = loadedTemplates.values.flatMap { $0 }.filter { $0.isStale }.count
        return "[Enrollment-iOS] frases=\(loadedTemplates.count) | templates=\(totalTemplates) | stale=\(stale) | enrolling=\(currentPhraseId ?? "none")"
    }

    // MARK: - Privado

    private func countPhrasesByType(_ type: PhraseTemplate.PhraseType) -> Int {
        loadedTemplates.values.filter { templates in
            templates.first?.type == type
        }.count
    }
}
