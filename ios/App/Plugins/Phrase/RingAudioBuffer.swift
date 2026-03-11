import Foundation

/// Buffer circular thread-safe para áudio PCM no iOS.
/// Armazena a 'confirmation window' (pré e pós-trigger).
class RingAudioBuffer {
    private let capacity: Int
    private var buffer: [Int16]
    private var writePosition: Int = 0
    private var totalWritten: Int = 0
    private let lock = NSLock()

    /// Inicializa com capacidade em amostras (ex: 16000 * 10 = 160000 para 10s a 16kHz)
    init(capacityInSamples: Int) {
        self.capacity = capacityInSamples
        self.buffer = [Int16](repeating: 0, count: capacityInSamples)
    }

    /// Inicializa com capacidade em segundos
    convenience init(durationSeconds: Double, sampleRate: Int = 16000) {
        self.init(capacityInSamples: Int(durationSeconds * Double(sampleRate)))
    }

    /// Escreve amostras no buffer (thread-safe)
    func write(samples: [Int16], offset: Int, length: Int) {
        lock.lock()
        defer { lock.unlock() }

        for i in 0..<length {
            buffer[writePosition] = samples[offset + i]
            writePosition = (writePosition + 1) % capacity
        }
        totalWritten += length
    }

    /// Captura snapshot dos últimos N samples (thread-safe)
    func snapshot(length: Int) -> [Int16] {
        lock.lock()
        defer { lock.unlock() }

        let available = min(length, min(totalWritten, capacity))
        var result = [Int16](repeating: 0, count: available)

        var readPos = (writePosition - available + capacity) % capacity
        for i in 0..<available {
            result[i] = buffer[readPos]
            readPos = (readPos + 1) % capacity
        }

        return result
    }

    /// Retorna todo o conteúdo disponível
    func snapshotAll() -> [Int16] {
        return snapshot(length: capacity)
    }

    /// Reseta o buffer
    func reset() {
        lock.lock()
        defer { lock.unlock() }
        writePosition = 0
        totalWritten = 0
        buffer = [Int16](repeating: 0, count: capacity)
    }

    var availableSamples: Int {
        lock.lock()
        defer { lock.unlock() }
        return min(totalWritten, capacity)
    }
}
