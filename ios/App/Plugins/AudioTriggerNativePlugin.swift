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
    private var emailUsuario: String?
    private var origemGravacao: String = "monitoramento_automatico"
    
    // Amplitude analysis
    private var amplitudeBuffer: [Float] = []
    private let amplitudeBufferSize = 100 // 1 second at 100 samples/sec
    private var baselineAmplitude: Float = 0.0
    private var currentAmplitude: Float = 0.0 // Current RMS amplitude
    private var calibrationSamples: [Float] = []
    private let calibrationDuration = 30 // 30 seconds
    private var calibrationStartTime: Date?
    
    // Fight detection thresholds
    private let fightThresholdMultiplier: Float = 4.5 // 4.5x baseline
    private let fightDurationThreshold: TimeInterval = 10.0 // 10 seconds minimum
    private var fightDetectedTime: Date?
    private var isFightDetected = false
    private var lastFightEndTime: Date? // Cooldown tracking
    
    // Monitoring periods
    private var monitoringPeriods: [[String: String]] = []
    
    // Continuous calibration
    private var continuousCalibrationEnabled = true
    private var lastCalibrationUpdate: Date?
    private let calibrationUpdateInterval: TimeInterval = 300.0 // 5 minutes
    private var recentAmplitudes: [Float] = []
    private let recentAmplitudesMaxSize = 300 // 5 minutes at 1 sample/sec
    
    // Recording state
    private var recordingStartTime: Date?
    private var segmentIndex = 0
    private var segmentTimer: Timer?
    private let segmentDuration: TimeInterval = 30.0 // 30 seconds
    private var uploader: AudioSegmentUploader?
    private var recordingBuffer: AVAudioPCMBuffer?
    
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
        print("\n\n")
        print("========================================")
        print("[AudioTriggerNative-iOS] 🟢 START CALLED (monitoring only)")
        print("========================================")
        print("\n\n")
        
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
        if let email = call.getString("emailUsuario") {
            emailUsuario = email
        }
        
        // Request microphone permission for monitoring
        AVAudioSession.sharedInstance().requestRecordPermission { [weak self] granted in
            guard let self = self else { return }
            
            if granted {
                print("[AudioTriggerNative-iOS] OK Microphone permission granted for monitoring")
                
                DispatchQueue.main.async {
                    // Start audio engine for monitoring (calibration + detection)
                    // but DO NOT start recording
                    do {
                        try self.startMonitoring()
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
        stopRecordingInternal()
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
        
        // Determine state based on fight detection and calibration
        var state = "IDLE"
        if !isCalibrated {
            state = "CALIBRATING"
        } else if isFightDetected {
            state = "DISCUSSION_DETECTED"
        } else {
            state = "MONITORING"
        }
        
        // Calculate score (0-1) based on current amplitude vs baseline
        var score: Float = 0.0
        if baselineAmplitude > 0 {
            score = min(currentAmplitude / (baselineAmplitude * fightThresholdMultiplier), 1.0)
        }
        
        call.resolve([
            "state": state,
            "score": score,
            "amplitude": currentAmplitude,
            "baseline": baselineAmplitude,
            "isCalibrated": isCalibrated,
            "isSpeech": currentAmplitude > baselineAmplitude * 1.5,
            "isLoud": currentAmplitude > baselineAmplitude * fightThresholdMultiplier,
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
        try audioSession.setActive(true)
        
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
        
        print("[AudioTriggerNative-iOS] ✅ Monitoring started (calibrating...) - NOT recording")
    }
    
    private func stopMonitoring() {
        print("[AudioTriggerNative-iOS] 🛑 Stopping monitoring...")
        
        // Stop metrics timer
        stopMetricsTimer()
        
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
        
        // If already monitoring, stop monitoring first
        if audioEngine != nil && !isRecording {
            print("[AudioTriggerNative-iOS] 🔄 Stopping monitoring to start recording")
            stopMonitoring()
        }
        
        // Configure audio session for background recording
        let audioSession = AVAudioSession.sharedInstance()
        try audioSession.setCategory(.playAndRecord, mode: .default, options: [.allowBluetooth, .defaultToSpeaker])
        try audioSession.setActive(true)
        
        // Create audio engine
        audioEngine = AVAudioEngine()
        guard let engine = audioEngine else {
            throw NSError(domain: "AudioTriggerNative", code: -1, userInfo: [NSLocalizedDescriptionKey: "Failed to create audio engine"])
        }
        
        let inputNode = engine.inputNode
        let inputFormat = inputNode.outputFormat(forBus: 0)
        
        // Create recording format (mono, 44.1kHz)
        guard let recordingFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: sampleRate, channels: channels, interleaved: false) else {
            throw NSError(domain: "AudioTriggerNative", code: -2, userInfo: [NSLocalizedDescriptionKey: "Failed to create recording format"])
        }
        
        // Install tap on input node for amplitude analysis
        inputNode.installTap(onBus: 0, bufferSize: 4096, format: inputFormat) { [weak self] buffer, time in
            self?.processAudioBuffer(buffer)
        }
        
        // Start engine
        try engine.start()
        
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
        calibrationStartTime = Date()
        calibrationSamples = []
        isCalibrated = false
        
        // Start background task
        startBackgroundTask()
        
        // Start background task renewal timer (renew every 25s)
        startBackgroundTaskRenewalTimer()
        
        // Start segment timer
        startSegmentTimer()
        
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
        
        // Finish and upload last segment
        if let uploader = uploader {
            uploader.finishSegment { success in
                if success {
                    print("[AudioTriggerNative-iOS] ✅ Final segment uploaded")
                } else {
                    print("[AudioTriggerNative-iOS] ❌ Failed to upload final segment")
                }
            }
        }
        
        // Cleanup uploader
        uploader?.cleanup()
        uploader = nil
        
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
        
        // Report to server
        reportRecordingStatus("finalizada")
        
        // Update state
        isRecording = false
        isCalibrated = false
        calibrationSamples = []
        
        // Notify JS
        notifyEvent("nativeRecordingStopped", data: [:])
        
        print("[AudioTriggerNative-iOS] OK Recording stopped")
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
        let channelDataValueArray = stride(from: 0, to: Int(buffer.frameLength), by: buffer.stride).map { channelDataValue[$0] }
        
        // Calculate RMS amplitude
        let rms = sqrt(channelDataValueArray.map { $0 * $0 }.reduce(0, +) / Float(buffer.frameLength))
        let amplitude = rms * 100 // Scale to 0-100
        
        // Update current amplitude for getMetrics()
        currentAmplitude = amplitude
        
        // Store amplitude for continuous calibration
        recentAmplitudes.append(amplitude)
        if recentAmplitudes.count > recentAmplitudesMaxSize {
            recentAmplitudes.removeFirst()
        }
        
        // Calibration phase (first 30 seconds)
        if !isCalibrated {
            calibrationSamples.append(amplitude)
            
            if let startTime = calibrationStartTime, Date().timeIntervalSince(startTime) >= TimeInterval(calibrationDuration) {
                // Calculate baseline (average of calibration samples)
                baselineAmplitude = calibrationSamples.reduce(0, +) / Float(calibrationSamples.count)
                isCalibrated = true
                lastCalibrationUpdate = Date()
                
                print("[AudioTriggerNative-iOS] 🎯 Initial calibration complete, baseline: \(baselineAmplitude)")
                
                // Notify JS
                notifyEvent("calibrationStatus", data: ["isCalibrated": true, "baseline": baselineAmplitude])
            }
            return
        }
        
        // Continuous calibration (every 5 minutes)
        if continuousCalibrationEnabled {
            if let lastUpdate = lastCalibrationUpdate, Date().timeIntervalSince(lastUpdate) >= calibrationUpdateInterval {
                if recentAmplitudes.count >= 60 { // At least 1 minute of data
                    let newBaseline = recentAmplitudes.reduce(0, +) / Float(recentAmplitudes.count)
                    let oldBaseline = baselineAmplitude
                    baselineAmplitude = newBaseline
                    lastCalibrationUpdate = Date()
                    
                    print("[AudioTriggerNative-iOS] 🔄 Continuous calibration: baseline updated from \(oldBaseline) to \(newBaseline)")
                    
                    // Notify JS
                    notifyEvent("calibrationStatus", data: ["isCalibrated": true, "baseline": baselineAmplitude, "continuous": true])
                }
            }
        }
        
        // Fight detection (after calibration, only within monitoring period)
        detectFight(amplitude: amplitude)
    }
    
    private func detectFight(amplitude: Float) {
        let threshold = baselineAmplitude * fightThresholdMultiplier
        
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
        if let lastEnd = lastFightEndTime, Date().timeIntervalSince(lastEnd) < 20.0 {
            // Still in cooldown, don't detect new fights
            return
        }
        
        if amplitude > threshold {
            // High amplitude detected
            if fightDetectedTime == nil {
                fightDetectedTime = Date()
                print("[AudioTriggerNative-iOS] ⚠️ High amplitude detected: \(amplitude) > \(threshold)")
            } else if let detectedTime = fightDetectedTime, Date().timeIntervalSince(detectedTime) >= fightDurationThreshold {
                // Fight confirmed (high amplitude for 10+ seconds)
                if !isFightDetected {
                    isFightDetected = true
                    print("[AudioTriggerNative-iOS] 🚨 FIGHT DETECTED!")
                    
                    // Notify JS
                    notifyEvent("fightDetected", data: [
                        "amplitude": amplitude,
                        "threshold": threshold,
                        "timestamp": Int(Date().timeIntervalSince1970 * 1000)
                    ])
                }
            }
        } else {
            // Normal amplitude
            if isFightDetected {
                print("[AudioTriggerNative-iOS] OK Fight ended")
                isFightDetected = false
                lastFightEndTime = Date() // Start cooldown
                
                // Notify JS
                notifyEvent("fightEnded", data: [:])
            }
            fightDetectedTime = nil
        }
    }
    
    // MARK: - Segment Upload
    
    private func startSegmentTimer() {
        // MUST run on main thread for Timer to work
        DispatchQueue.main.async { [weak self] in
            self?.segmentTimer = Timer.scheduledTimer(withTimeInterval: self?.segmentDuration ?? 30.0, repeats: true) { [weak self] _ in
                self?.uploadSegment()
            }
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
            body["motivo_parada"] = "manual"
            
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
        let threshold = baselineAmplitude * fightThresholdMultiplier
        
        // Calculate score (0-1)
        var score: Float = 0.0
        if baselineAmplitude > 0 {
            score = min(currentAmplitude / threshold, 1.0)
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
            "score": score,
            "rmsDb": currentAmplitude,
            "amplitude": currentAmplitude,
            "baseline": baselineAmplitude,
            "threshold": threshold,
            "isSpeech": currentAmplitude > baselineAmplitude * 1.5,
            "isLoud": currentAmplitude > threshold,
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
                        print("[AudioTriggerNative-iOS] ✅ Monitoring resumed")
                    } catch {
                        print("[AudioTriggerNative-iOS] ❌ Failed to resume monitoring: \(error)")
                    }
                }
                
                // Resume recording if it was active
                if wasRecordingBeforeInterruption {
                    do {
                        try startRecording()
                        print("[AudioTriggerNative-iOS] ✅ Recording resumed")
                        
                        // Notify JS
                        notifyEvent("audioResumed", data: [:])
                    } catch {
                        print("[AudioTriggerNative-iOS] ❌ Failed to resume recording: \(error)")
                    }
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
    
    // MARK: - Event Notification
    
    private func notifyEvent(_ event: String, data: [String: Any]) {
        var eventData = data
        eventData["event"] = event
        
        notifyListeners("audioTriggerEvent", data: eventData)
    }
}

