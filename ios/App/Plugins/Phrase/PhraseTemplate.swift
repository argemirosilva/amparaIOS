import Foundation

/// Representa uma amostra de frase cadastrada, com features MFCC e metadados.
/// Suporta persistência binária e detecção de obsolescência.
class PhraseTemplate {
    /// Tipo de frase
    enum PhraseType: String, Codable {
        case operational = "OPERATIONAL"
        case contextual = "CONTEXTUAL"
        case generic = "GENERIC"
    }

    let phraseId: String
    let sampleIndex: Int
    let type: PhraseType
    let extractorVersion: Int
    let createdAt: Date
    var features: [[Float]]  // [numFrames][39]
    var threshold: Double

    init(phraseId: String, sampleIndex: Int, type: PhraseType,
         features: [[Float]], extractorVersion: Int, threshold: Double = 100.0) {
        self.phraseId = phraseId
        self.sampleIndex = sampleIndex
        self.type = type
        self.features = features
        self.extractorVersion = extractorVersion
        self.createdAt = Date()
        self.threshold = threshold
    }

    /// Verifica se o template está obsoleto (versão do extrator diferente)
    var isStale: Bool {
        extractorVersion != MFCCExtractor.extractorVersion
    }

    /// Tamanho estimado em bytes
    var estimatedSizeBytes: Int {
        features.count * features.first?.count ?? 0 * MemoryLayout<Float>.size
    }

    // MARK: - Persistência

    /// Salva template no diretório de frases
    func save(to directory: URL) throws {
        let filename = "\(phraseId)_\(sampleIndex).phrasetemplate"
        let fileURL = directory.appendingPathComponent(filename)

        let data: [String: Any] = [
            "phraseId": phraseId,
            "sampleIndex": sampleIndex,
            "type": type.rawValue,
            "extractorVersion": extractorVersion,
            "createdAt": createdAt.timeIntervalSince1970,
            "threshold": threshold,
            "numFrames": features.count,
            "numFeatures": features.first?.count ?? 39,
            "features": features.flatMap { $0 }.map { NSNumber(value: $0) }
        ]

        let jsonData = try JSONSerialization.data(withJSONObject: data)
        try jsonData.write(to: fileURL)
    }

    /// Carrega template de arquivo
    static func load(from fileURL: URL) throws -> PhraseTemplate {
        let jsonData = try Data(contentsOf: fileURL)
        guard let dict = try JSONSerialization.jsonObject(with: jsonData) as? [String: Any] else {
            throw NSError(domain: "PhraseTemplate", code: 1, userInfo: [NSLocalizedDescriptionKey: "Formato inválido"])
        }

        let phraseId = dict["phraseId"] as? String ?? ""
        let sampleIndex = dict["sampleIndex"] as? Int ?? 0
        let typeStr = dict["type"] as? String ?? "OPERATIONAL"
        let type = PhraseType(rawValue: typeStr) ?? .operational
        let extractorVersion = dict["extractorVersion"] as? Int ?? 1
        let threshold = dict["threshold"] as? Double ?? 100.0
        let numFrames = dict["numFrames"] as? Int ?? 0
        let numFeatures = dict["numFeatures"] as? Int ?? 39
        let flatFeatures = (dict["features"] as? [NSNumber])?.map { $0.floatValue } ?? []

        var features = [[Float]]()
        for i in 0..<numFrames {
            let start = i * numFeatures
            let end = min(start + numFeatures, flatFeatures.count)
            if start < flatFeatures.count {
                features.append(Array(flatFeatures[start..<end]))
            }
        }

        let template = PhraseTemplate(phraseId: phraseId, sampleIndex: sampleIndex,
                                       type: type, features: features,
                                       extractorVersion: extractorVersion, threshold: threshold)
        return template
    }

    /// Diretório de armazenamento de templates
    static func templatesDirectory() -> URL {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let dir = appSupport.appendingPathComponent("phrase_templates")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }
}
