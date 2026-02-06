import Foundation
import Capacitor
import AVFoundation

/**
 * AudioTriggerNativePlugin - iOS Native Audio Recording & Fight Detection
 * 
 * Features:
 * - Background audio recording using AVAudioEngine
 * - Real-time amplitude analysis for fight detection
 * - Automatic segment upload every 30 seconds
 * - Works in background with screen locked
 * - Zero impact on Android code
 */

public class AudioTriggerNativePlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "AudioTriggerNativePlugin"
    public let jsName = "AudioTriggerNative"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "start", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stop", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "startRecording", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stopManualRecording", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getStatus", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "updateConfig", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getMetrics", returnType: CAPPluginReturnPromise),
    ]

    // MARK: - Properties
    
    private var audioEngine: AVAudioEngine?
    private var audioFile: AVAudioFile?
    private var isRecording = false
    private var isCalibrated = false
    private var sessionId: String?
    private var sessionToken: String?
    private var refreshToken: String?
    private var emailUsuario: String?
    private var origemGravacao: String = "monitoramento_automatico"
    
    // Audio analysis (Android-compatible)
    private var noiseFloor: Double = -40.0 // dB, updated during calibration
    private var currentRmsDb: Double = -100.0 // Current RMS in dB
    private var calibrationSamples: [Double] = []
    private let calibrationDuration = 30 // 30 seconds
    private var calibrationStartTime: Date?
    
    // Frame aggregation (40 frames = 1 second)
    private var frameBuffer: [(rmsDb: Double, zcr: Double)] = []
    private let framesPerSecond = 40 // 25ms per frame
    private var speechCount = 0
    private var loudCount = 0
    
    // Discussion detection (10-second sliding window)
    private var secondsWindow: [(isSpeech: Bool, isLoud: Bool)] = []
    private let windowSize = 10 // 10 seconds
    
    // Thresholds (Android defaults)
    private let vadDeltaDb: Double = 7.0  // Speech threshold above noise floor
    private let loudDeltaDb: Double = 18.0 // Loud threshold above noise floor
    private let zcrMinVoice: Double = 0.02
    private let zcrMaxVoice: Double = 0.35
    private let speechDensityMin: Double = 0.50 // 50%
    private let loudDensityMin: Double = 0.30   // 30%
    
    // Fight detection thresholds
    private let fightThresholdMultiplier: Float = 4.5 // 4.5x baseline
    private let fightDurationThreshold: TimeInterval = 10.0 // 10 seconds minimum
    private var fightDetectedTime: Date?
    private var isFightDetected = false
    private var lastFightEndTime: Date? // Cooldown tracking
    
    // Auto-recording on fight detection
    private var autoRecordingActive = false
    
    // Panic manager (shared state)
    private let panicManager = PanicManager.shared
    
    // Discussion ending detection (Android-compatible)
    private var silenceStartTime: Date? // When silence started
    private let silenceDecaySeconds: TimeInterval = 10.0 // Confirmation phase
    private var endHoldTimer: Timer? // 60-second safety buffer timer
    private let endHoldSeconds: TimeInterval = 60.0 // Safety buffer phase
    private var inEndHoldPhase = false
    
    // Absolute silence timeout (fallback - 10 minutes)
    private var absoluteSilenceTimer: Timer?
    private let absoluteSilenceTimeout: TimeInterval = 600.0 // 10 minutes
    
    // Monitoring periods
    private var monitoringPeriods: [[String: String]] = []
    
    // Continuous calibration
    private var continuousCalibrationEnabled = true
    private var lastCalibrationUpdate: Date?
    private let calibrationUpdateInterval: TimeInterval = 300.0 // 5 minutes
    
    // Ping/Heartbeat (background keep-alive)
    private var pingTimer: Timer?
    private let pingInterval: TimeInterval = 30.0 // 30 seconds
    private var lastPingTime: Date?
    private var recentAmplitudes: [Float] = []
    private let recentAmplitudesMaxSize = 300 // 5 minutes at 1 sample/sec
    
    // Recording state
    private var recordingStartTime: Date?
    private var segmentIndex = 0
    private var segmentTimer: Timer?
    private let segmentDuration: TimeInterval = 30.0 // 30 seconds
    private var uploader: AudioSegmentUploader?
    private var recordingBuffer: AVAudioPCMBuffer?
    private var stopReason: String = "manual" // Track why recording stopped
    
    // Audio format
    private let sampleRate: Double = 44100.0
    private let channels: AVAudioChannelCount = 1
    
    // Background task
    private var backgroundTaskID: UIBackgroundTaskIdentifier = .invalid
    private var backgroundTaskRenewalTimer: Timer?
    
    // Metrics update timer
    private var metricsTimer: Timer?
    private let metricsUpdateInterval: TimeInterval = 0.5 // 500ms (2x per second)
    
    // Audio interruption handling
    private var wasRecordingBeforeInterruption = false
    private var wasMonitoringBeforeInterruption = false
    
    // MARK: - Capacitor Methods
    
    @objc func start(_ call: CAPPluginCall) {
        print("\n\n\n\n\n")
        print("🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴")
        print("🔴 START() FOI CHAMADO DO JAVASCRIPT! 🔴")
        print("🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴")
        print("\n\n\n\n\n")
        
        // Send notification to JavaScript to show alert
        self.notifyListeners("debugStartCalled", data: ["message": "start() foi chamado!"])
        
        // Enable battery monitoring
        UIDevice.current.isBatteryMonitoringEnabled = true
        
        // Setup audio interruption observers
        setupAudioInterruptionObservers()
        
        // iOS: start() apenas inicia MONITORAMENTO (calibração + detecção)
        // NÃO inicia gravação automaticamente
        // Gravação só inicia quando:
        // 1. Usuário clica no botão (startRecording)
        // 2. Detecção automática de briga (dentro do período)
        
        // Store credentials for future use
        if let token = call.getString("sessionToken") {
            sessionToken = token
        }
        if let refresh = call.getString("refreshToken") {
            refreshToken = refresh
        }
        if let email = call.getString("emailUsuario") {
            emailUsuario = email
        }
        
        // Request microphone permission for monitoring
        AVAudioSession.sharedInstance().requestRecordPermission { [weak self] granted in
            guard let self = self else { return }
            
            if granted {
                print("[AudioTriggerNative-iOS] OK Microphone permission granted for monitoring")
                
                DispatchQueue.main.async {
                    // Send notification that permission was granted
                    self.notifyListeners("debugPermissionGranted", data: ["message": "Permissão concedida!"])
                    
                    // Start audio engine for monitoring (calibration + detection)
                    // but DO NOT start recording
                    do {
                        try self.startMonitoring()
                        
                        // Send notification that monitoring started
                        self.notifyListeners("debugMonitoringStarted", data: ["message": "Monitoramento iniciado!"])
                        
                        call.resolve(["success": true])
                    } catch {
                        print("[AudioTriggerNative-iOS] ❌ Failed to start monitoring: \(error)")
                        call.reject("Failed to start monitoring: \(error.localizedDescription)")
                    }
                }
            } else {
                print("[AudioTriggerNative-iOS] ❌ Microphone permission denied")
                call.reject("Microphone permission denied")
            }
        }
    }
    
    @objc func stop(_ call: CAPPluginCall) {
        print("[AudioTriggerNative-iOS] 🛑 stop() called (stop monitoring)")
        
        // Remove audio interruption observers
        removeAudioInterruptionObservers()
        
        // Stop monitoring (calibration + detection)
        stopMonitoring()
        
        // If recording is active, stop it too
        if isRecording {
            stopRecordingInternal()
        }
        
        call.resolve(["success": true])
    }
    
    @objc func startRecording(_ call: CAPPluginCall) {
        print("[AudioTriggerNative-iOS] 🟢 startRecording() called")
        
        // Check if already recording
        if isRecording {
            print("[AudioTriggerNative-iOS] OK Already recording")
            call.resolve(["success": true, "alreadyRecording": true])
            return
        }
        
        // Get credentials from call
        if let token = call.getString("sessionToken") {
            sessionToken = token
        }
        if let refresh = call.getString("refreshToken") {
            refreshToken = refresh
        }
        if let email = call.getString("emailUsuario") {
            emailUsuario = email
        }
        if let origem = call.getString("origemGravacao") {
            origemGravacao = origem
        }
        
        // Validate credentials
        guard sessionToken != nil, emailUsuario != nil else {
            print("[AudioTriggerNative-iOS] ❌ Missing credentials")
            call.reject("Missing sessionToken or emailUsuario")
            return
        }
        
        // Start recording
        do {
            try self.startRecording()
            call.resolve(["success": true])
        } catch {
            print("[AudioTriggerNative-iOS] ❌ Failed to start recording: \(error)")
            call.reject("Failed to start recording: \(error.localizedDescription)")
        }
    }
    
    @objc func stopManualRecording(_ call: CAPPluginCall) {
        print("[AudioTriggerNative-iOS] 🛑 stopManualRecording() called")
        
        // Verificar se pode parar
        // PODE parar: gravacao manual (botao_manual) ou automatica (deteccao de briga)
        // NÃO PODE parar: gravacao de pânico (botao_panico)
        if origemGravacao == "botao_panico" {
            print("[AudioTriggerNative-iOS] ⚠️ Cannot stop panic recording")
            call.reject("Cannot stop panic recording")
            return
        }
        
        if !isRecording {
            print("[AudioTriggerNative-iOS] ⚠️ Not recording")
            call.resolve(["success": true, "wasRecording": false])
            return
        }
        
        print("[AudioTriggerNative-iOS] ✅ Stopping recording (origem: \(origemGravacao))")
        
        // Cancel end timers if stopping auto-recording manually
        if autoRecordingActive {
            cancelEndTimers()
            autoRecordingActive = false
        }
        
        stopReason = "manual"
        stopRecordingInternal()
        
        // Restart monitoring after 1s delay (Android behavior)
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
            guard let self = self else { return }
            do {
                try self.startMonitoring()
                print("[AudioTriggerNative-iOS] ✅ Monitoring restarted after manual stop")
            } catch {
                print("[AudioTriggerNative-iOS] ❌ Failed to restart monitoring: \(error)")
            }
        }
        
        call.resolve(["success": true, "wasRecording": true])
    }
    
    @objc func getStatus(_ call: CAPPluginCall) {
        print("[AudioTriggerNative-iOS] 📊 getStatus() called")
        
        call.resolve([
            "isRecording": isRecording,
            "isCalibrated": isCalibrated,
            "sessionId": sessionId ?? ""
        ])
    }
    
    @objc func updateConfig(_ call: CAPPluginCall) {
        print("[AudioTriggerNative-iOS] 🔧 updateConfig() called")
        
        // Update monitoring periods if provided
        if let periodsArray = call.getArray("monitoringPeriods") {
            // Convert JSArray to [[String: String]]
            var periods: [[String: String]] = []
            for item in periodsArray {
                if let dict = item as? [String: String] {
                    periods.append(dict)
                }
            }
            monitoringPeriods = periods
            print("[AudioTriggerNative-iOS] 📅 Updated monitoring periods: \(periods.count) periods")
            for (index, period) in periods.enumerated() {
                if let inicio = period["inicio"], let fim = period["fim"] {
                    print("[AudioTriggerNative-iOS] 📅   Period \(index): \(inicio) - \(fim)")
                }
            }
        }
        
        call.resolve(["success": true])
    }
    
    @objc func getMetrics(_ call: CAPPluginCall) {
        print("[AudioTriggerNative-iOS] 📊 getMetrics() called")
        
        // Calculate densities from sliding window
        let speechDensity = Double(secondsWindow.filter { $0.isSpeech }.count) / Double(max(secondsWindow.count, 1))
        let loudDensity = Double(secondsWindow.filter { $0.isLoud }.count) / Double(max(secondsWindow.count, 1))
        
        // Calculate discussion score (0.0 to 1.0) - Android-compatible
        let speechNorm = min(speechDensity / speechDensityMin, 1.0)
        let loudNorm = min(loudDensity / loudDensityMin, 1.0)
        let discussionScore = (speechNorm + loudNorm) / 2.0
        
        // Thresholds
        let vadThreshold = noiseFloor + vadDeltaDb
        let loudThreshold = max(noiseFloor + loudDeltaDb, -20.0)
        
        // Determine state
        var state = "IDLE"
        if !isCalibrated {
            state = "CALIBRATING"
        } else if isFightDetected {
            state = "DISCUSSION_DETECTED"
        } else {
            state = "MONITORING"
        }
        
        call.resolve([
            "state": state,
            "score": discussionScore,
            "rmsDb": currentRmsDb,
            "noiseFloor": noiseFloor,
            "vadThreshold": vadThreshold,
            "loudThreshold": loudThreshold,
            "isCalibrated": isCalibrated,
            "isSpeech": currentRmsDb > vadThreshold,
            "isLoud": currentRmsDb > loudThreshold,
            "speechDensity": speechDensity,
            "loudDensity": loudDensity,
            "timestamp": Int(Date().timeIntervalSince1970 * 1000)
        ])
    }
    

    // MARK: - Monitoring Period Check
    
    private func isWithinMonitoringPeriod() -> Bool {
        // If no periods configured, always allow monitoring
        guard !monitoringPeriods.isEmpty else {
            return true
        }
        
        let now = Date()
        let calendar = Calendar.current
        let currentHour = calendar.component(.hour, from: now)
        let currentMinute = calendar.component(.minute, from: now)
        let currentMinutes = currentHour * 60 + currentMinute
        
        // Check if within any period
        for period in monitoringPeriods {
            guard let inicioStr = period["inicio"],
                  let fimStr = period["fim"] else {
                continue
            }
            
            // Parse "HH:MM" format
            let inicioComponents = inicioStr.split(separator: ":").compactMap { Int($0) }
            let fimComponents = fimStr.split(separator: ":").compactMap { Int($0) }
            
            guard inicioComponents.count == 2, fimComponents.count == 2 else {
                continue
            }
            
            let startMinutes = inicioComponents[0] * 60 + inicioComponents[1]
            let endMinutes = fimComponents[0] * 60 + fimComponents[1]
            
            if currentMinutes >= startMinutes && currentMinutes < endMinutes {
                return true
            }
        }
        
        return false
    }
    
    // MARK: - Monitoring Methods
    
    private func startMonitoring() throws {
        print("[AudioTriggerNative-iOS] 👂 Starting monitoring (calibration + detection only)...")
        
        // Check if already monitoring
        if audioEngine != nil {
            print("[AudioTriggerNative-iOS] ⚠️ Already monitoring")
            return
        }
        
        // Configure audio session
        let audioSession = AVAudioSession.sharedInstance()
        try audioSession.setCategory(.playAndRecord, mode: .default, options: [.allowBluetooth, .defaultToSpeaker])
        try audioSession.setActive(true, options: .notifyOthersOnDeactivation)
        
        // Create audio engine
        audioEngine = AVAudioEngine()
        guard let engine = audioEngine else {
            throw NSError(domain: "AudioTriggerNative", code: -1, userInfo: [NSLocalizedDescriptionKey: "Failed to create audio engine"])
        }
        
        let inputNode = engine.inputNode
        let inputFormat = inputNode.outputFormat(forBus: 0)
        
        // Install tap for amplitude analysis (monitoring only, no recording)
        inputNode.installTap(onBus: 0, bufferSize: 4096, format: inputFormat) { [weak self] buffer, time in
            self?.processAudioBuffer(buffer)
        }
        
        // Start engine
        try engine.start()
        
        // Start calibration (NO sessionId, NO recording, NO server reporting)
        calibrationStartTime = Date()
        calibrationSamples = []
        isCalibrated = false
        
        // IMPORTANTE: NÃO setar isRecording = true
        // NÃO gerar sessionId
        // NÃO iniciar segment timer
        // NÃO reportar ao servidor
        
        // Start metrics update timer
        startMetricsTimer()
        
        // Start ping timer for background keep-alive
        startPingTimer()
        
        print("[AudioTriggerNative-iOS] ✅ Monitoring started (calibrating...) - NOT recording")
        print("[AudioTriggerNative-iOS] 📊 Metrics timer should now be sending audioMetrics every 0.5s")
    }
    
    private func stopMonitoring() {
        print("[AudioTriggerNative-iOS] 🛑 Stopping monitoring...")
        
        // Stop metrics timer
        stopMetricsTimer()
        
        // Stop ping timer
        stopPingTimer()
        
        audioEngine?.stop()
        audioEngine?.inputNode.removeTap(onBus: 0)
        audioEngine = nil
        
        isCalibrated = false
        calibrationSamples = []
        
        print("[AudioTriggerNative-iOS] ✅ Monitoring stopped")
    }
    
    // MARK: - Recording Methods
    
    private func startRecording() throws {
        print("[AudioTriggerNative-iOS] 🎤 Starting recording...")
        
        // If already monitoring, reuse the audioEngine to keep detection active
        let reusingEngine = (audioEngine != nil && !isRecording)
        if reusingEngine {
            print("[AudioTriggerNative-iOS] ♻️ Reusing existing audioEngine to keep detection active")
        }
        
        // Configure audio session for background recording
        let audioSession = AVAudioSession.sharedInstance()
        try audioSession.setCategory(.playAndRecord, mode: .default, options: [.allowBluetooth, .defaultToSpeaker])
        try audioSession.setActive(true, options: .notifyOthersOnDeactivation)
        
        // Preserve calibration state if reusing engine
        let wasCalibrated = isCalibrated
        let savedNoiseFloor = noiseFloor
        
        // Create or reuse audio engine
        if !reusingEngine {
            audioEngine = AVAudioEngine()
            guard let engine = audioEngine else {
                throw NSError(domain: "AudioTriggerNative", code: -1, userInfo: [NSLocalizedDescriptionKey: "Failed to create audio engine"])
            }
            
            let inputNode = engine.inputNode
            let inputFormat = inputNode.outputFormat(forBus: 0)
            
            // Install tap on input node for amplitude analysis
            inputNode.installTap(onBus: 0, bufferSize: 4096, format: inputFormat) { [weak self] buffer, time in
                self?.processAudioBuffer(buffer)
            }
            
            // Start engine
            try engine.start()
        }
        
        guard let engine = audioEngine else {
            throw NSError(domain: "AudioTriggerNative", code: -1, userInfo: [NSLocalizedDescriptionKey: "Audio engine not available"])
        }
        
        let inputNode = engine.inputNode
        let inputFormat = inputNode.outputFormat(forBus: 0)
        
        // Create recording format (mono, 44.1kHz)
        guard let recordingFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: sampleRate, channels: channels, interleaved: false) else {
            throw NSError(domain: "AudioTriggerNative", code: -2, userInfo: [NSLocalizedDescriptionKey: "Failed to create recording format"])
        }
        
        // Generate session ID
        sessionId = "ios_\(Date().timeIntervalSince1970)_\(UUID().uuidString.prefix(8))"
        
        // Create uploader
        guard let token = sessionToken, let email = emailUsuario else {
            throw NSError(domain: "AudioTriggerNative", code: -3, userInfo: [NSLocalizedDescriptionKey: "Missing credentials"])
        }
        uploader = AudioSegmentUploader(
            sessionId: sessionId!,
            sessionToken: token,
            emailUsuario: email,
            origemGravacao: origemGravacao
        )
        
        // Start first segment
        try uploader?.startNewSegment(format: recordingFormat)
        
        // Update state
        isRecording = true
        recordingStartTime = Date()
        segmentIndex = 0
        
        // Preserve calibration if reusing engine, otherwise recalibrate
        if reusingEngine && wasCalibrated {
            isCalibrated = true
            noiseFloor = savedNoiseFloor
            print("[AudioTriggerNative-iOS] ✅ Keeping calibration: noiseFloor=\(noiseFloor) dB")
        } else {
            calibrationStartTime = Date()
            calibrationSamples = []
            isCalibrated = false
            print("[AudioTriggerNative-iOS] 🔄 Starting new calibration")
        }
        
        // Start background task
        startBackgroundTask()
        
        // Start background task renewal timer (renew every 25s)
        startBackgroundTaskRenewalTimer()
        
        // Start segment timer
        startSegmentTimer()
        
        // Start absolute silence timeout (10 minutes fallback)
        startAbsoluteSilenceTimer()
        
        // Notify JS
        notifyEvent("nativeRecordingStarted", data: [
            "sessionId": sessionId ?? "",
            "startedAt": Int(Date().timeIntervalSince1970 * 1000),
            "origemGravacao": origemGravacao
        ])
        
        // Report to server
        reportRecordingStatus("iniciada")
        
        let sid = sessionId ?? ""
        print("[AudioTriggerNative-iOS] OK Recording started, sessionId: \(sid)")
    }
    
    private func stopRecordingInternal() {
        print("[AudioTriggerNative-iOS] 🛑 Stopping recording...")
        
        // Notify JS that stopping is in progress
        notifyEvent("recordingStopping", data: [:])
        
        // Finish and upload last segment
        if let uploader = uploader {
            uploader.finishSegment { [weak self] success in
                guard let self = self else { return }
                
                if success {
                    print("[AudioTriggerNative-iOS] ✅ Final segment uploaded")
                } else {
                    print("[AudioTriggerNative-iOS] ❌ Failed to upload final segment")
                }
                
                // Cleanup uploader AFTER upload completes
                self.uploader?.cleanup()
                self.uploader = nil
                
                // Report to server AFTER upload completes
                self.reportRecordingStatus("finalizada")
                
                // Notify JS that recording is fully stopped
                self.notifyEvent("recordingStopped", data: [:])
                
                print("[AudioTriggerNative-iOS] OK Recording stopped")
            }
        } else {
            // No uploader, report immediately
            reportRecordingStatus("finalizada")
            notifyEvent("recordingStopped", data: [:])
            print("[AudioTriggerNative-iOS] OK Recording stopped (no uploader)")
        }
        
        // Stop audio engine
        audioEngine?.stop()
        audioEngine?.inputNode.removeTap(onBus: 0)
        audioEngine = nil
        
        // Stop segment timer
        segmentTimer?.invalidate()
        segmentTimer = nil
        
        // Stop background task renewal timer
        stopBackgroundTaskRenewalTimer()
        
        // Stop background task
        endBackgroundTask()
        
        // Update state
        isRecording = false
        
        // Partial reset: Clear frame buffers but preserve sliding window history
        print("[AudioTriggerNative-iOS] 🔄 Resetting detector (partial reset)")
        isCalibrated = false
        calibrationSamples = []
        frameBuffer.removeAll()
        // DO NOT reset secondsWindow - preserve history for immediate score calculation
        speechCount = 0
        loudCount = 0
        currentRmsDb = -100.0
        // Note: noiseFloor and secondsWindow are preserved for next monitoring session
        
        // Notify JS
        notifyEvent("nativeRecordingStopped", data: [:])
    }
    
    // MARK: - Audio Processing
    
    private func processAudioBuffer(_ buffer: AVAudioPCMBuffer) {
        guard let channelData = buffer.floatChannelData else { return }
        
        // Write buffer to uploader if recording
        if isRecording {
            do {
                try uploader?.writeBuffer(buffer)
            } catch {
                print("[AudioTriggerNative-iOS] ❌ Failed to write buffer: \(error)")
            }
        }
        
        let channelDataValue = channelData.pointee
        let samples = stride(from: 0, to: Int(buffer.frameLength), by: buffer.stride).map { channelDataValue[$0] }
        
        // Calculate RMS in dB (Android-compatible)
        let rmsDb = calculateRMSdB(samples: samples)
        currentRmsDb = rmsDb
        
        // Calculate Zero Crossing Rate
        let zcr = calculateZCR(samples: samples)
        
        // Calibration phase (first 30 seconds)
        if !isCalibrated {
            calibrationSamples.append(rmsDb)
            
            if let startTime = calibrationStartTime, Date().timeIntervalSince(startTime) >= TimeInterval(calibrationDuration) {
                // Calculate noise floor (average of calibration samples)
                noiseFloor = calibrationSamples.reduce(0, +) / Double(calibrationSamples.count)
                isCalibrated = true
                
                print("[AudioTriggerNative-iOS] 🎯 Initial calibration complete, noiseFloor: \(noiseFloor) dB")
                
                // Notify JS
                notifyEvent("calibrationStatus", data: ["isCalibrated": true, "noiseFloor": noiseFloor])
            }
            return
        }
        
        // Add frame to buffer (aggregation)
        frameBuffer.append((rmsDb: rmsDb, zcr: zcr))
        
        // Check if frame is speech or loud
        let isSpeech = isSpeechLike(rmsDb: rmsDb, zcr: zcr)
        let isLoud = isLoudFrame(rmsDb: rmsDb)
        
        if isSpeech { speechCount += 1 }
        if isLoud { loudCount += 1 }
        
        // Process aggregated second (40 frames)
        if frameBuffer.count >= framesPerSecond {
            processAggregatedSecond()
            frameBuffer.removeAll()
            speechCount = 0
            loudCount = 0
        }
    }
    
    // MARK: - DSP Functions (Android-compatible)
    
    private func calculateRMSdB(samples: [Float]) -> Double {
        var sum: Double = 0.0
        for sample in samples {
            let normalized = Double(sample) // Already normalized -1.0 to 1.0
            sum += normalized * normalized
        }
        let rms = sqrt(sum / Double(samples.count))
        
        if rms > 0 {
            return 20 * log10(rms)
        } else {
            return -100.0 // Silence
        }
    }
    
    private func calculateZCR(samples: [Float]) -> Double {
        var crossings = 0
        for i in 1..<samples.count {
            if (samples[i-1] >= 0 && samples[i] < 0) || (samples[i-1] < 0 && samples[i] >= 0) {
                crossings += 1
            }
        }
        return Double(crossings) / Double(samples.count)
    }
    
    private func isSpeechLike(rmsDb: Double, zcr: Double) -> Bool {
        let vadThreshold = noiseFloor + vadDeltaDb
        let hasEnergy = rmsDb > vadThreshold
        let hasVoiceZCR = zcr >= zcrMinVoice && zcr <= zcrMaxVoice
        return hasEnergy && hasVoiceZCR
    }
    
    private func isLoudFrame(rmsDb: Double) -> Bool {
        let relativeThreshold = noiseFloor + loudDeltaDb
        let absoluteThreshold = -20.0
        let threshold = max(relativeThreshold, absoluteThreshold)
        return rmsDb > threshold
    }
    
    private func processAggregatedSecond() {
        // Android behavior: During recording, simulate silence for detection logic
        let discussionScore: Double
        
        if isRecording {
            // During recording: Calculate REAL score for silence detection
            let isSpeechAggregated = speechCount > (framesPerSecond / 2)
            let isLoudAggregated = loudCount > (framesPerSecond / 2)
            
            // Add to sliding window
            secondsWindow.append((isSpeech: isSpeechAggregated, isLoud: isLoudAggregated))
            if secondsWindow.count > windowSize {
                secondsWindow.removeFirst()
            }
            
            // Calculate densities
            let speechDensity = Double(secondsWindow.filter { $0.isSpeech }.count) / Double(windowSize)
            let loudDensity = Double(secondsWindow.filter { $0.isLoud }.count) / Double(windowSize)
            
            // Calculate REAL score (for silence detection)
            let speechNorm = min(speechDensity / speechDensityMin, 1.0)
            let loudNorm = min(loudDensity / loudDensityMin, 1.0)
            let realScore = (speechNorm + loudNorm) / 2.0
            
            // Detect silence during auto-recording (for 10s + 60s timers)
            if autoRecordingActive {
                if realScore < 0.3 { // Low score = silence
                    if silenceStartTime == nil {
                        silenceStartTime = Date()
                        print("[AudioTriggerNative-iOS] 🔇 Silence detected during recording, starting confirmation phase (10s)")
                    } else if let startTime = silenceStartTime {
                        let silenceDuration = Date().timeIntervalSince(startTime)
                        if silenceDuration >= silenceDecaySeconds && !inEndHoldPhase {
                            startEndHoldTimer()
                        }
                    }
                } else {
                    // Discussion resumed during recording
                    if silenceStartTime != nil || inEndHoldPhase {
                        print("[AudioTriggerNative-iOS] 🔊 Discussion resumed during recording! Cancelling end timers")
                        cancelEndTimers()
                    }
                    
                    // Reset 10-minute absolute silence timer (audio detected above noise floor)
                    resetAbsoluteSilenceTimer()
                }
            }
            
            // Send score=0.0 to UI (Android behavior - graph shows silence during recording)
            discussionScore = 0.0
            return
        } else {
            // During monitoring: Calculate real score
            // Check if more than 50% of frames were speech/loud
            let isSpeechAggregated = speechCount > (framesPerSecond / 2)
            let isLoudAggregated = loudCount > (framesPerSecond / 2)
            
            // Add to sliding window
            secondsWindow.append((isSpeech: isSpeechAggregated, isLoud: isLoudAggregated))
            if secondsWindow.count > windowSize {
                secondsWindow.removeFirst()
            }
            
            // Calculate densities (for score)
            let speechDensity = Double(secondsWindow.filter { $0.isSpeech }.count) / Double(windowSize)
            let loudDensity = Double(secondsWindow.filter { $0.isLoud }.count) / Double(windowSize)
            
            // Calculate discussion score (0.0 to 1.0)
            let speechNorm = min(speechDensity / speechDensityMin, 1.0)
            let loudNorm = min(loudDensity / loudDensityMin, 1.0)
            discussionScore = (speechNorm + loudNorm) / 2.0
            
            // DEBUG: Log detection thresholds (every 5 seconds)
            if Int(Date().timeIntervalSince1970) % 5 == 0 {
                print("[AudioTriggerNative-iOS] 📊 Detection: speechD=\(String(format: "%.2f", speechDensity)) (min=\(speechDensityMin)), loudD=\(String(format: "%.2f", loudDensity)) (min=\(loudDensityMin)), score=\(String(format: "%.2f", discussionScore))")
            }
            
            // Fight detection (if densities exceed thresholds)
            if speechDensity >= speechDensityMin && loudDensity >= loudDensityMin {
                detectFight(score: discussionScore)
                return
            }
        }
        
        // Call detectFight with normal score (only during monitoring)
        detectFight(score: discussionScore)
    }
    
    private func detectFight(score: Double) {
        // Check if within monitoring period
        let withinPeriod = isWithinMonitoringPeriod()
        if !withinPeriod {
            // Outside monitoring period - reset detection state but keep monitoring
            if isFightDetected {
                print("[AudioTriggerNative-iOS] ⏸️ Fight detection paused (outside monitoring period)")
                isFightDetected = false
            }
            fightDetectedTime = nil
            return
        }
        
        // Check cooldown period (20 seconds after last fight)
        if let lastEnd = lastFightEndTime {
            let cooldownRemaining = 20.0 - Date().timeIntervalSince(lastEnd)
            if cooldownRemaining > 0 {
                // Still in cooldown, don't detect new fights
                if Int(Date().timeIntervalSince1970) % 5 == 0 {
                    print("[AudioTriggerNative-iOS] ⏳ Cooldown active: \(Int(cooldownRemaining))s remaining (score=\(score))")
                }
                return
            }
        }
        
        // High score indicates discussion
        if score > 0.7 { // Threshold for fight detection
            // Cancel silence tracking if discussion resumes
            if silenceStartTime != nil || inEndHoldPhase {
                print("[AudioTriggerNative-iOS] 🔊 Discussion resumed! Cancelling end timers")
                cancelEndTimers()
            }
            
            if fightDetectedTime == nil {
                fightDetectedTime = Date()
            } else if let detectedTime = fightDetectedTime, Date().timeIntervalSince(detectedTime) >= fightDurationThreshold {
                // Fight confirmed (high score for 10+ seconds)
                if !isFightDetected {
                    isFightDetected = true
                    print("[AudioTriggerNative-iOS] 🚨 FIGHT DETECTED! score=\(score)")
                    
                    // Start auto-recording if not already recording
                    if !isRecording && sessionToken != nil && emailUsuario != nil {
                        do {
                            origemGravacao = "deteccao_automatica"
                            try startRecording()
                            autoRecordingActive = true
                            print("[AudioTriggerNative-iOS] 🎥 Auto-recording started (fight detected)")
                        } catch {
                            print("[AudioTriggerNative-iOS] ❌ Failed to start auto-recording: \(error)")
                        }
                    }
                    
                    // Notify JS
                    notifyEvent("fightDetected", data: [
                        "score": score,
                        "timestamp": Int(Date().timeIntervalSince1970 * 1000)
                    ])
                }
            }
        } else {
            // Normal score - handle discussion ending logic
            if isFightDetected {
                print("[AudioTriggerNative-iOS] OK Fight ended")
                isFightDetected = false
                lastFightEndTime = Date() // Start cooldown
                
                // Notify JS
                notifyEvent("fightEnded", data: [:])
            }
            fightDetectedTime = nil
            
            // Discussion ending detection (Android-compatible)
            if autoRecordingActive && isRecording {
                // Low score = silence detected
                if silenceStartTime == nil {
                    // Start tracking silence
                    silenceStartTime = Date()
                    print("[AudioTriggerNative-iOS] 🔇 Silence detected, starting confirmation phase (10s)")
                } else if let startTime = silenceStartTime {
                    let silenceDuration = Date().timeIntervalSince(startTime)
                    
                    // Check if confirmation phase completed (10s)
                    if silenceDuration >= silenceDecaySeconds && !inEndHoldPhase {
                        // Start safety buffer phase (60s)
                        startEndHoldTimer()
                    }
                }
            }
        }
    }
    
    // MARK: - Discussion Ending Detection (Android-compatible)
    
    private func startEndHoldTimer() {
        inEndHoldPhase = true
        print("[AudioTriggerNative-iOS] ⏱️ Confirmation phase complete (10s), starting safety buffer (60s)")
        
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            
            self.endHoldTimer = Timer.scheduledTimer(withTimeInterval: self.endHoldSeconds, repeats: false) { [weak self] _ in
                guard let self = self else { return }
                
                print("[AudioTriggerNative-iOS] ⏰ Safety buffer complete (60s) - total 70s silence - stopping auto-recording")
                
                if self.autoRecordingActive && self.isRecording {
                    self.stopReason = "silencio"
                    self.stopRecordingInternal()
                    self.autoRecordingActive = false
                    self.cancelEndTimers()
                    
                    // Restart monitoring after 1s delay (ensure audioEngine is fully released)
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
                        guard let self = self else { return }
                        do {
                            try self.startMonitoring()
                            print("[AudioTriggerNative-iOS] ✅ Monitoring restarted after auto-recording")
                        } catch {
                            print("[AudioTriggerNative-iOS] ❌ Failed to restart monitoring: \(error)")
                        }
                    }
                }
            }
        }
    }
    
    private func cancelEndTimers() {
        silenceStartTime = nil
        endHoldTimer?.invalidate()
        endHoldTimer = nil
        inEndHoldPhase = false
        
        // Also cancel absolute silence timeout
        absoluteSilenceTimer?.invalidate()
        absoluteSilenceTimer = nil
        
        print("[AudioTriggerNative-iOS] ❌ End timers cancelled (discussion resumed)")
    }
    
    private func startAbsoluteSilenceTimer() {
        // Cancel existing timer
        absoluteSilenceTimer?.invalidate()
        
        // Start 10-minute timeout
        absoluteSilenceTimer = Timer.scheduledTimer(withTimeInterval: absoluteSilenceTimeout, repeats: false) { [weak self] _ in
            guard let self = self else { return }
            
            // Ignore timeout if panic is active
            if self.panicManager.isPanicActive {
                print("[AudioTriggerNative-iOS] ⏰ 10min timeout reached but IGNORED (panic active)")
                // Restart timer for another 10 minutes
                self.startAbsoluteSilenceTimer()
                return
            }
            
            print("[AudioTriggerNative-iOS] ⏰ 10min absolute silence timeout reached - stopping recording")
            
            // Stop recording with timeout reason
            self.stopReason = "timeout"
            self.stopRecordingInternal()
            self.autoRecordingActive = false
            self.cancelEndTimers()
            
            // Restart monitoring after 1s delay
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
                guard let self = self else { return }
                do {
                    try self.startMonitoring()
                    print("[AudioTriggerNative-iOS] ✅ Monitoring restarted after timeout")
                } catch {
                    print("[AudioTriggerNative-iOS] ❌ Failed to restart monitoring: \(error)")
                }
            }
        }
        
        print("[AudioTriggerNative-iOS] ⏱️ Absolute silence timer started (10 minutes)")
    }
    
    private func resetAbsoluteSilenceTimer() {
        // Only reset if recording and timer is active
        guard isRecording, absoluteSilenceTimer != nil else { return }
        
        // Restart timer (reset countdown)
        startAbsoluteSilenceTimer()
    }
    
    // MARK: - Segment Upload
    
    private func startSegmentTimer() {
        print("[AudioTriggerNative-iOS] ⏱️ startSegmentTimer() called, segmentDuration: \(segmentDuration)s")
        
        // MUST run on main thread for Timer to work
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            
            print("[AudioTriggerNative-iOS] ⏱️ Creating segment timer on main thread...")
            
            self.segmentTimer = Timer.scheduledTimer(withTimeInterval: self.segmentDuration, repeats: true) { [weak self] _ in
                print("[AudioTriggerNative-iOS] ⏰ Segment timer FIRED! Calling uploadSegment()...")
                self?.uploadSegment()
            }
            
            print("[AudioTriggerNative-iOS] ✅ Segment timer created successfully, will fire every \(self.segmentDuration)s")
        }
    }
    
    private func uploadSegment() {
        guard let uploader = uploader else {
            print("[AudioTriggerNative-iOS] ⚠️ No uploader available")
            return
        }
        
        print("[AudioTriggerNative-iOS] 📤 Finishing segment \(segmentIndex)...")
        
        // End previous background task before starting upload
        endBackgroundTask()
        
        // Start new background task for upload
        startBackgroundTask()
        
        // Finish current segment and upload
        uploader.finishSegment { [weak self] success in
            guard let self = self else { return }
            
            if success {
                print("[AudioTriggerNative-iOS] ✅ Segment \(self.segmentIndex) uploaded")
                
                // Notify JS
                self.notifyEvent("nativeRecordingProgress", data: [
                    "segmentIndex": self.segmentIndex,
                    "uploaded": true
                ])
                
                // Start new segment if still recording
                if self.isRecording {
                    do {
                        // Get recording format from audio engine
                        if let engine = self.audioEngine {
                            let inputNode = engine.inputNode
                            let inputFormat = inputNode.outputFormat(forBus: 0)
                            
                            // Create recording format
                            if let recordingFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: self.sampleRate, channels: self.channels, interleaved: false) {
                                try self.uploader?.startNewSegment(format: recordingFormat)
                            }
                        }
                    } catch {
                        print("[AudioTriggerNative-iOS] ❌ Failed to start new segment: \(error)")
                    }
                }
            } else {
                print("[AudioTriggerNative-iOS] ❌ Failed to upload segment \(self.segmentIndex)")
                
                self.notifyEvent("nativeRecordingProgress", data: [
                    "segmentIndex": self.segmentIndex,
                    "uploaded": false,
                    "error": "Upload failed"
                ])
            }
            
            // End background task after upload completes (success or failure)
            self.endBackgroundTask()
            
            // Restart background task if still recording
            if self.isRecording {
                self.startBackgroundTask()
            }
        }
        
        segmentIndex += 1
    }
    
    // MARK: - Server Communication
    
    private func reportRecordingStatus(_ status: String) {
        guard let sessionId = sessionId, let token = sessionToken else {
            print("[AudioTriggerNative-iOS] ⚠️ Cannot report status: missing sessionId or token")
            return
        }
        
        print("[AudioTriggerNative-iOS] 📡 Reporting status: \(status)")
        print("[AudioTriggerNative-iOS] 📡 Session ID: \(sessionId)")
        print("[AudioTriggerNative-iOS] 📡 Email: \(emailUsuario ?? "nil")")
        
        // Build URL - usando endpoint Supabase
        let url = URL(string: "https://ilikiajeduezvvanjejz.supabase.co/functions/v1/mobile-api")!
        
        // Get timezone info
        let timezone = TimeZone.current.identifier
        let timezoneOffset = TimeZone.current.secondsFromGMT() / 60  // Convert to minutes
        
        // Get device info
        let device = UIDevice.current
        let batteryLevel = Int(device.batteryLevel * 100)
        let deviceInfo = "\(device.systemName) \(device.systemVersion) - \(device.model)"
        let appVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "unknown"
        
        // Build body JSON matching API specification
        var body: [String: Any] = [
            "action": "reportarStatusGravacao",
            "device_id": sessionId,
            "timezone": timezone,
            "timezone_offset_minutes": timezoneOffset,
            "session_token": token,
            "email_usuario": emailUsuario ?? "",
            "status_gravacao": status,
            "origem_gravacao": origemGravacao,
            "bateria_percentual": batteryLevel,
            "dispositivo_info": deviceInfo,
            "versao_app": appVersion
        ]
        
        // Add segmento_idx if we have sent segments
        if segmentIndex > 0 {
            body["segmento_idx"] = segmentIndex
        }
        
        // Add fields specific to "finalizada" status
        if status == "finalizada" {
            body["total_segments"] = segmentIndex
            body["motivo_parada"] = stopReason
            
            // Calculate duracao_atual_segundos
            if let startTime = recordingStartTime {
                let duration = Int(Date().timeIntervalSince(startTime))
                body["duracao_atual_segundos"] = duration
            }
        }
        
        guard let jsonData = try? JSONSerialization.data(withJSONObject: body) else { return }
        
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = jsonData
        
        print("[AudioTriggerNative-iOS] 📡 Sending request to: \(url.absoluteString)")
        print("[AudioTriggerNative-iOS] 📡 Request body: \(String(data: jsonData, encoding: .utf8) ?? "invalid")")
        
        URLSession.shared.dataTask(with: request) { data, response, error in
            if let error = error {
                print("[AudioTriggerNative-iOS] ❌ Failed to report status: \(error.localizedDescription)")
                return
            }
            
            if let httpResponse = response as? HTTPURLResponse {
                print("[AudioTriggerNative-iOS] 📊 HTTP Status: \(httpResponse.statusCode)")
                
                if let data = data, let responseBody = String(data: data, encoding: .utf8) {
                    print("[AudioTriggerNative-iOS] 📊 Response body: \(responseBody)")
                }
                
                if httpResponse.statusCode == 200 {
                    print("[AudioTriggerNative-iOS] ✅ Status '\(status)' reported successfully")
                } else {
                    print("[AudioTriggerNative-iOS] ❌ Status report failed with code: \(httpResponse.statusCode)")
                }
            }
        }.resume()
    }
    
    // MARK: - Background Task
    
    private func startBackgroundTask() {
        backgroundTaskID = UIApplication.shared.beginBackgroundTask { [weak self] in
            self?.endBackgroundTask()
        }
    }
    
    private func endBackgroundTask() {
        if backgroundTaskID != .invalid {
            UIApplication.shared.endBackgroundTask(backgroundTaskID)
            backgroundTaskID = .invalid
        }
    }
    
    private func startBackgroundTaskRenewalTimer() {
        // Renew background task every 25 seconds (before iOS 30s limit)
        // MUST run on main thread for Timer to work
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.backgroundTaskRenewalTimer?.invalidate()
            self.backgroundTaskRenewalTimer = Timer.scheduledTimer(withTimeInterval: 25.0, repeats: true) { [weak self] _ in
                guard let self = self else { return }
                print("[AudioTriggerNative-iOS] 🔄 Renewing background task...")
                self.endBackgroundTask()
                self.startBackgroundTask()
            }
        }
    }
    
    private func stopBackgroundTaskRenewalTimer() {
        DispatchQueue.main.async { [weak self] in
            self?.backgroundTaskRenewalTimer?.invalidate()
            self?.backgroundTaskRenewalTimer = nil
        }
    }
    
    // MARK: - Metrics Timer
    
    private func startMetricsTimer() {
        // Stop existing timer if any
        stopMetricsTimer()
        
        // Create timer on main thread
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            
            self.metricsTimer = Timer.scheduledTimer(withTimeInterval: self.metricsUpdateInterval, repeats: true) { [weak self] _ in
                self?.sendMetricsUpdate()
            }
            
            print("[AudioTriggerNative-iOS] ⏱️ Metrics timer started (every \(self.metricsUpdateInterval)s)")
        }
    }
    
    private func stopMetricsTimer() {
        metricsTimer?.invalidate()
        metricsTimer = nil
        print("[AudioTriggerNative-iOS] ⏹️ Metrics timer stopped")
    }
    
    private func sendMetricsUpdate() {
        // Android behavior: Send score=0 during recording (simulated silence)
        let discussionScore: Double
        let speechDensity: Double
        let loudDensity: Double
        
        if isRecording {
            // During recording: Send simulated silence (score = 0.0)
            discussionScore = 0.0
            speechDensity = 0.0
            loudDensity = 0.0
        } else {
            // During monitoring: Calculate real densities from sliding window
            speechDensity = Double(secondsWindow.filter { $0.isSpeech }.count) / Double(max(secondsWindow.count, 1))
            loudDensity = Double(secondsWindow.filter { $0.isLoud }.count) / Double(max(secondsWindow.count, 1))
            
            // Calculate discussion score (0.0 to 1.0) - Android-compatible
            let speechNorm = min(speechDensity / speechDensityMin, 1.0)
            let loudNorm = min(loudDensity / loudDensityMin, 1.0)
            discussionScore = (speechNorm + loudNorm) / 2.0
        }
        
        // Thresholds for isSpeech and isLoud
        let vadThreshold = noiseFloor + vadDeltaDb
        let loudThreshold = max(noiseFloor + loudDeltaDb, -20.0)
        
        // DEBUG: Log metrics being sent (every 5 seconds to avoid spam)
        let now = Date().timeIntervalSince1970
        if Int(now) % 5 == 0 {
            print("[AudioTriggerNative-iOS] 📊 Metrics: rmsDb=\(currentRmsDb), noiseFloor=\(noiseFloor), score=\(discussionScore), speechDensity=\(speechDensity), loudDensity=\(loudDensity)")
        }
        
        // Determine state
        var state = "IDLE"
        if !isCalibrated {
            state = "CALIBRATING"
        } else if isFightDetected {
            state = "DISCUSSION_DETECTED"
        } else {
            state = "MONITORING"
        }
        
        // Send metrics event to JavaScript
        notifyEvent("audioMetrics", data: [
            "score": discussionScore, // 0.0 to 1.0 (UI multiplies by 7)
            "rmsDb": currentRmsDb,
            "noiseFloor": noiseFloor,
            "vadThreshold": vadThreshold,
            "loudThreshold": loudThreshold,
            "isSpeech": currentRmsDb > vadThreshold,
            "isLoud": currentRmsDb > loudThreshold,
            "speechDensity": speechDensity,
            "loudDensity": loudDensity,
            "isCalibrated": isCalibrated,
            "state": state,
            "timestamp": Int(Date().timeIntervalSince1970 * 1000)
        ])
    }
    
    // MARK: - Audio Interruption Handling
    
    private func setupAudioInterruptionObservers() {
        // Observe audio session interruptions (calls, other apps)
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleAudioInterruption(_:)),
            name: AVAudioSession.interruptionNotification,
            object: AVAudioSession.sharedInstance()
        )
        
        // Observe route changes (headphones, bluetooth)
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleRouteChange(_:)),
            name: AVAudioSession.routeChangeNotification,
            object: AVAudioSession.sharedInstance()
        )
        
        // Observe app lifecycle (background/foreground)
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleAppWillResignActive(_:)),
            name: UIApplication.willResignActiveNotification,
            object: nil
        )
        
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleAppDidBecomeActive(_:)),
            name: UIApplication.didBecomeActiveNotification,
            object: nil
        )
        
        print("[AudioTriggerNative-iOS] 🔔 Audio interruption observers setup")
    }
    
    private func removeAudioInterruptionObservers() {
        NotificationCenter.default.removeObserver(
            self,
            name: AVAudioSession.interruptionNotification,
            object: AVAudioSession.sharedInstance()
        )
        
        NotificationCenter.default.removeObserver(
            self,
            name: AVAudioSession.routeChangeNotification,
            object: AVAudioSession.sharedInstance()
        )
        
        NotificationCenter.default.removeObserver(
            self,
            name: UIApplication.willResignActiveNotification,
            object: nil
        )
        
        NotificationCenter.default.removeObserver(
            self,
            name: UIApplication.didBecomeActiveNotification,
            object: nil
        )
        
        print("[AudioTriggerNative-iOS] 🔕 Audio interruption observers removed")
    }
    
    @objc private func handleAudioInterruption(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let typeValue = userInfo[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: typeValue) else {
            return
        }
        
        switch type {
        case .began:
            // Interruption began (call, WhatsApp, etc.)
            print("[AudioTriggerNative-iOS] ☎️ Audio interruption began (call/WhatsApp/etc)")
            
            // Save current state
            wasRecordingBeforeInterruption = isRecording
            wasMonitoringBeforeInterruption = (audioEngine != nil)
            
            // Stop recording if active
            if isRecording {
                print("[AudioTriggerNative-iOS] ⏸️ Pausing recording due to interruption")
                
                // Set stop reason BEFORE stopping
                stopReason = "mic_solicitado"
                
                // Stop recording (will upload current segment and report status)
                stopRecordingInternal()
                
                // Notify JS
                notifyEvent("audioInterrupted", data: [
                    "reason": "mic_solicitado",
                    "wasRecording": true
                ])
            }
            
            // Stop monitoring if active
            if wasMonitoringBeforeInterruption {
                print("[AudioTriggerNative-iOS] ⏸️ Pausing monitoring due to interruption")
                stopMonitoring()
            }
            
        case .ended:
            // Interruption ended
            print("[AudioTriggerNative-iOS] ✅ Audio interruption ended")
            
            guard let optionsValue = userInfo[AVAudioSessionInterruptionOptionKey] as? UInt else {
                return
            }
            
            let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)
            
            if options.contains(.shouldResume) {
                print("[AudioTriggerNative-iOS] 🔄 Resuming after interruption")
                
                // Resume monitoring if it was active
                if wasMonitoringBeforeInterruption {
                    do {
                        try startMonitoring()
                        print("[AudioTriggerNative-iOS] ✅ Monitoring resumed after interruption")
                        
                        // Notify JS that monitoring resumed
                        notifyEvent("audioResumed", data: [
                            "monitoringResumed": true,
                            "recordingResumed": false
                        ])
                    } catch {
                        print("[AudioTriggerNative-iOS] ❌ Failed to resume monitoring: \(error)")
                    }
                }
                
                // DON'T auto-resume recording - let detection or user decide
                // (Android behavior: interruption stops recording permanently)
                if wasRecordingBeforeInterruption {
                    print("[AudioTriggerNative-iOS] ℹ️ Recording was interrupted - NOT auto-resuming (user/detection must restart)")
                }
            } else {
                print("[AudioTriggerNative-iOS] ⚠️ Interruption ended but should NOT resume")
            }
            
            // Reset flags
            wasRecordingBeforeInterruption = false
            wasMonitoringBeforeInterruption = false
            
        @unknown default:
            break
        }
    }
    
    @objc private func handleRouteChange(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let reasonValue = userInfo[AVAudioSessionRouteChangeReasonKey] as? UInt,
              let reason = AVAudioSession.RouteChangeReason(rawValue: reasonValue) else {
            return
        }
        
        switch reason {
        case .oldDeviceUnavailable:
            // Headphones/Bluetooth disconnected
            print("[AudioTriggerNative-iOS] 🎧 Audio device disconnected")
            // Continue using built-in microphone
            
        case .newDeviceAvailable:
            // Headphones/Bluetooth connected
            print("[AudioTriggerNative-iOS] 🎧 New audio device connected")
            // iOS will automatically switch to new device
            
        default:
            break
        }
    }
    
    @objc private func handleAppWillResignActive(_ notification: Notification) {
        print("[AudioTriggerNative-iOS] 🔹 App will resign active (going to background/lock)")
        // Don't stop anything - background audio should continue
    }
    
    @objc private func handleAppDidBecomeActive(_ notification: Notification) {
        print("[AudioTriggerNative-iOS] 🔸 App did become active (returning from background/lock)")
        
        // Restart audio engine if it stopped
        if audioEngine == nil || audioEngine?.isRunning == false {
            print("[AudioTriggerNative-iOS] ⚠️ Audio engine stopped - restarting")
            
            do {
                try startMonitoring()
                print("[AudioTriggerNative-iOS] ✅ Monitoring restarted after returning from background")
            } catch {
                print("[AudioTriggerNative-iOS] ❌ Failed to restart monitoring: \(error)")
            }
        }
    }
    
    // MARK: - Ping Timer (Background Keep-Alive)
    
    private func startPingTimer() {
        // Stop existing timer if any
        stopPingTimer()
        
        // Send immediate ping
        sendPing()
        
        // Create timer on main thread
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            
            self.pingTimer = Timer.scheduledTimer(withTimeInterval: self.pingInterval, repeats: true) { [weak self] _ in
                self?.sendPing()
            }
            
            print("[AudioTriggerNative-iOS] 🏓 Ping timer started (interval: \(self.pingInterval)s)")
        }
    }
    
    private func stopPingTimer() {
        pingTimer?.invalidate()
        pingTimer = nil
        print("[AudioTriggerNative-iOS] ⏹️ Ping timer stopped")
    }
    
    private func refreshAccessToken(completion: @escaping (Bool) -> Void) {
        guard let refresh = refreshToken else {
            print("[AudioTriggerNative-iOS] ❌ No refresh token available")
            completion(false)
            return
        }
        
        guard let apiUrl = getApiUrl() else {
            print("[AudioTriggerNative-iOS] ❌ Cannot refresh token: API URL not configured")
            completion(false)
            return
        }
        
        guard let url = URL(string: apiUrl) else {
            print("[AudioTriggerNative-iOS] ❌ Invalid API URL: \(apiUrl)")
            completion(false)
            return
        }
        
        // Build payload
        let payload: [String: Any] = [
            "action": "refresh_token",
            "refresh_token": refresh
        ]
        
        // Create request
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        
        do {
            request.httpBody = try JSONSerialization.data(withJSONObject: payload)
        } catch {
            print("[AudioTriggerNative-iOS] ❌ Failed to serialize refresh payload: \(error)")
            completion(false)
            return
        }
        
        // Send request
        URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
            guard let self = self else {
                completion(false)
                return
            }
            
            if let error = error {
                print("[AudioTriggerNative-iOS] ❌ Refresh token request failed: \(error.localizedDescription)")
                completion(false)
                return
            }
            
            guard let httpResponse = response as? HTTPURLResponse else {
                print("[AudioTriggerNative-iOS] ❌ Invalid refresh response")
                completion(false)
                return
            }
            
            if httpResponse.statusCode == 200 {
                // Parse response to get new tokens
                guard let data = data else {
                    print("[AudioTriggerNative-iOS] ❌ No data in refresh response")
                    completion(false)
                    return
                }
                
                do {
                    if let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                       let newAccessToken = json["access_token"] as? String,
                       let newRefreshToken = json["refresh_token"] as? String {
                        
                        // Update tokens
                        self.sessionToken = newAccessToken
                        self.refreshToken = newRefreshToken
                        
                        // Notify JavaScript to update tokens
                        self.notifyEvent("tokensRefreshed", data: [
                            "access_token": newAccessToken,
                            "refresh_token": newRefreshToken
                        ])
                        
                        print("[AudioTriggerNative-iOS] ✅ Tokens refreshed successfully")
                        completion(true)
                    } else {
                        print("[AudioTriggerNative-iOS] ❌ Invalid refresh response format")
                        completion(false)
                    }
                } catch {
                    print("[AudioTriggerNative-iOS] ❌ Failed to parse refresh response: \(error)")
                    completion(false)
                }
            } else {
                print("[AudioTriggerNative-iOS] ❌ Refresh token failed with status \(httpResponse.statusCode)")
                completion(false)
            }
        }.resume()
    }
    
    private func sendPing() {
        guard let token = sessionToken, let email = emailUsuario else {
            print("[AudioTriggerNative-iOS] ⚠️ Cannot send ping: missing session token or email")
            return
        }
        
        // Get device info
        let device = UIDevice.current
        let deviceModel = "\(device.model) (iOS \(device.systemVersion))"
        let deviceId = UserDefaults.standard.string(forKey: "device_id") ?? "unknown"
        let deviceName = device.name // Nome configurado pelo usuário (ex: "iPhone de Maria")
        
        // Get battery info
        device.isBatteryMonitoringEnabled = true
        let batteryLevel = Int(device.batteryLevel * 100) // 0-100
        let isCharging = device.batteryState == .charging || device.batteryState == .full
        
        // Get app version
        let appVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "unknown"
        
        // Get timezone
        let timezone = TimeZone.current.identifier // IANA timezone (ex: "America/Sao_Paulo")
        let timezoneOffset = TimeZone.current.secondsFromGMT() / 60
        
        // Build payload with all fields
        var payload: [String: Any] = [
            "action": "pingMobile",
            "session_token": token,
            "email_usuario": email,
            "device_id": deviceId,
            "is_recording": isRecording,
            "is_monitoring": !isRecording, // If not recording, then monitoring
            "timezone": timezone,
            "timezone_offset_minutes": timezoneOffset
        ]
        
        // Add optional fields
        if batteryLevel >= 0 {
            payload["bateria_percentual"] = batteryLevel
        }
        payload["is_charging"] = isCharging
        payload["dispositivo_info"] = deviceName
        payload["versao_app"] = appVersion
        
        // Get API URL from config
        guard let apiUrl = getApiUrl() else {
            print("[AudioTriggerNative-iOS] ⚠️ Cannot send ping: API URL not configured")
            return
        }
        
        // Create request
        guard let url = URL(string: apiUrl) else {
            print("[AudioTriggerNative-iOS] ❌ Invalid API URL: \(apiUrl)")
            return
        }
        
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        
        do {
            request.httpBody = try JSONSerialization.data(withJSONObject: payload)
        } catch {
            print("[AudioTriggerNative-iOS] ❌ Failed to serialize ping payload: \(error)")
            return
        }
        
        // Send request (background-safe)
        let task = URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
            guard let self = self else { return }
            
            if let error = error {
                print("[AudioTriggerNative-iOS] ❌ Ping failed: \(error.localizedDescription)")
                return
            }
            
            if let httpResponse = response as? HTTPURLResponse {
                if httpResponse.statusCode == 200 {
                    self.lastPingTime = Date()
                    print("[AudioTriggerNative-iOS] 🏓 Ping sent successfully (recording: \(self.isRecording), monitoring: \(!self.isRecording))")
                } else if httpResponse.statusCode == 401 {
                    // Token expired - try to refresh
                    print("[AudioTriggerNative-iOS] 🔒 Token expired (401) - attempting refresh")
                    
                    // Try to refresh token
                    self.refreshAccessToken { success in
                        if success {
                            print("[AudioTriggerNative-iOS] ✅ Token refreshed successfully")
                            // Token refreshed, next ping will use new token
                        } else {
                            print("[AudioTriggerNative-iOS] ❌ Token refresh failed - notifying JavaScript")
                            
                            // Stop ping timer (no point in continuing)
                            self.stopPingTimer()
                            
                            // Notify JavaScript that session expired
                            self.notifyEvent("sessionExpired", data: [
                                "reason": "refresh_failed",
                                "message": "Session expired and refresh failed, please login again"
                            ])
                        }
                    }
                } else {
                    print("[AudioTriggerNative-iOS] ⚠️ Ping returned status \(httpResponse.statusCode)")
                }
            }
        }
        
        task.resume()
    }
    
    private func getApiUrl() -> String? {
        // Try to get from UserDefaults (set by JavaScript)
        if let url = UserDefaults.standard.string(forKey: "api_url") {
            return url
        }
        
        // Fallback to production URL
        return "https://amparamulher.com.br/api/mobile.php"
    }
    
    // MARK: - Event Notification
    
    private func notifyEvent(_ event: String, data: [String: Any]) {
        var eventData = data
        eventData["event"] = event
        
        notifyListeners("audioTriggerEvent", data: eventData)
    }
}

