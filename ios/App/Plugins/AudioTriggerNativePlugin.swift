import Foundation
import Capacitor
import AVFoundation
import CoreLocation

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
    private var isMonitoring = false // Track if actively monitoring (not just audio engine running)
    private var isCalibrated = false
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

    // Recording countdown tracking for UI (separate from recording logic)
    private var countdownTimeoutType: String = "none"  // "absolute", "silence", "panic"
    private var countdownSilenceStartTime: Date?  // Separate from silenceStartTime used for end timers

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
    private var autoTriggerCooldownUntil: Date?
    private let autoTriggerCooldownSeconds: TimeInterval = 60.0
    
    // Panic manager (shared state)
    private let panicManager = PanicManager.shared
    
    // Discussion ending detection (Android-compatible)
    private var silenceStartTime: Date? // When silence started
    private let silenceDecaySeconds: TimeInterval = 10.0 // Confirmation phase
    private var endHoldTimer: DispatchSourceTimer? // 60-second safety buffer timer
    private let endHoldSeconds: TimeInterval = 110.0 // 10s + 110s = 120s total silence
    private var inEndHoldPhase = false
    
    // Absolute silence timeout (fallback - 10 minutes)
    private var absoluteSilenceTimer: DispatchSourceTimer?
    private let absoluteSilenceTimeout: TimeInterval = 600.0 // 10 minutes
    private let panicMaxDuration: TimeInterval = 3600.0 // 60 minutes
    
    // Monitoring periods
    // FIX: Store the full week schedule in addition to today's periods.
    // When the app stays in background past midnight, monitoringPeriods (today-only) becomes stale.
    // periodosSemana lets isWithinMonitoringPeriod() always derive today's periods at query time.
    private var monitoringPeriods: [[String: String]] = []
    private var periodosSemana: [String: [[String: String]]] = [:]  // e.g. ["seg": [["inicio":"18:00","fim":"23:00"]]]
    
    // Continuous calibration
    private var continuousCalibrationEnabled = true
    private var lastCalibrationUpdate: Date?
    private let calibrationUpdateInterval: TimeInterval = 300.0 // 5 minutes
    
    // Ping/Heartbeat (background keep-alive)
    private var pingTimer: DispatchSourceTimer?
    private let pingInterval: TimeInterval = 30.0 // 30 seconds
    private var lastPingTime: Date?
    private var gpsNoDeviceRecoveryInProgress = false
    private var gpsNoDeviceRecoveryAttempts = 0
    private let maxGpsNoDeviceRecoveryAttempts = 1
    private var gpsMismatchBlockedUntil: Date?
    private var recentAmplitudes: [Float] = []
    private let recentAmplitudesMaxSize = 300 // 5 minutes at 1 sample/sec
    
    // Recording state
    private var recordingStartTime: Date?
    private var segmentIndex = 0
    private var segmentTimer: DispatchSourceTimer?
    private let segmentDuration: TimeInterval = 30.0 // 30 seconds
    private var uploader: AudioSegmentUploader?
    private var recordingBuffer: AVAudioPCMBuffer?
    private var stopReason: String = "manual" // Track why recording stopped

    // FIX: Dedicated URLSession for status/monitoring reports.
    // URLSession.shared is cancelled by iOS as soon as the app is suspended.
    // Using URLSessionConfiguration.default with waitsForConnectivity=true ensures
    // requests survive brief suspensions when called inside an active UIBackgroundTask.
    private lazy var statusSession: URLSession = {
        let config = URLSessionConfiguration.default
        config.waitsForConnectivity = true
        config.allowsCellularAccess = true
        config.allowsConstrainedNetworkAccess = true
        config.allowsExpensiveNetworkAccess = true
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 60
        return URLSession(configuration: config)
    }()
    
    // Audio format
    private let sampleRate: Double = 44100.0
    private let channels: AVAudioChannelCount = 1
    
    // Background task
    private var backgroundTaskID: UIBackgroundTaskIdentifier = .invalid
    private var backgroundTaskRenewalTimer: DispatchSourceTimer?
    private var appTransitionTaskID: UIBackgroundTaskIdentifier = .invalid
    
    // Metrics update timer — must use DispatchSourceTimer so it keeps firing in background
    // Timer.scheduledTimer is tied to RunLoop which iOS freezes when app enters background
    private var metricsTimer: DispatchSourceTimer?
    private let metricsUpdateInterval: TimeInterval = 0.5 // 500ms (2x per second)
    
    // Audio interruption handling
    private var wasRecordingBeforeInterruption = false
    private var wasMonitoringBeforeInterruption = false
    private var internalAudioSessionDeactivationUntil: Date?
    private let internalInterruptionGraceWindow: TimeInterval = 0.8
    private var isRecordingPausedByInterruption = false
    private var interruptionTimeoutTimer: DispatchSourceTimer?
    private let interruptionMaxPauseSeconds: TimeInterval = 300.0
    private var interruptionObserversConfigured = false
    private var isStartingMonitoring = false
    
    // GPS location timer
    private var gpsTimer: DispatchSourceTimer?
    private var gpsIntervalOutsidePeriod: TimeInterval = 1800.0 // 30 minutes outside monitoring period
    private var gpsIntervalMonitoring: TimeInterval = 60.0 // 1 minute during monitoring period
    private var gpsUpdateCounter: Int = 0 // Counter for throttling GPS logs
    private var gpsIntervalRecording: TimeInterval = 10.0 // 10 seconds during recording/panic
    private var locationManager: CLLocationManager?
    private var currentLocation: CLLocation?
    
    // Monitoring period tracking
    private var periodCheckTimer: DispatchSourceTimer?
    private var lastPeriodStatus: Bool = false // Track if we were in period last check

    // Heartbeat timer - reports status periodically even without changes
    private var heartbeatTimer: DispatchSourceTimer?
    private let heartbeatInterval: TimeInterval = 300.0 // 5 minutes

    // Config sync timer - fetches configuration periodically
    private var configSyncTimer: DispatchSourceTimer?
    private let configSyncInterval: TimeInterval = 300.0 // 5 minutes

    // Countdown timer - updates remaining recording time for UI
    private var countdownTimer: DispatchSourceTimer?
    private let countdownUpdateInterval: TimeInterval = 1.0 // Update every 1 second

    // MARK: - Capacitor Methods

    @objc func getDeviceId(_ call: CAPPluginCall) {
        let deviceId = getOrCreateDeviceId()
        call.resolve([
            "deviceId": deviceId
        ])
    }

    @objc func start(_ call: CAPPluginCall) {
        print("[AudioTriggerNative-iOS] 🔴 START() chamado do JavaScript")
        if isStartingMonitoring {
            print("[AudioTriggerNative-iOS] ⏳ START ignored: monitoring start already in progress")
            call.resolve(["success": true, "starting": true])
            return
        }
        gpsNoDeviceRecoveryAttempts = 0
        gpsNoDeviceRecoveryInProgress = false

        // DEBUG: Print only parameter keys (never log token values)
        print("[AudioTriggerNative-iOS] 🔍 PARAMETERS RECEIVED (keys only):")
        if let options = call.options {
            for (key, value) in options {
                let keyString = String(describing: key)
                if keyString == "sessionToken" || keyString == "refreshToken" {
                    print("[AudioTriggerNative-iOS]   - \(keyString): [REDACTED]")
                } else if keyString == "config" {
                    print("[AudioTriggerNative-iOS]   - config: [OBJECT_REDACTED]")
                } else {
                    print("[AudioTriggerNative-iOS]   - \(keyString): \(value)")
                }
            }
        } else {
            print("[AudioTriggerNative-iOS]   - NO OPTIONS PROVIDED")
        }

        // Send notification to JavaScript to show alert
        self.notifyListeners("debugStartCalled", data: ["message": "start() foi chamado!"])

        // Enable battery monitoring
        UIDevice.current.isBatteryMonitoringEnabled = true

        // Setup audio interruption observers
        setupAudioInterruptionObservers()

        // Store credentials for future use
        print("[AudioTriggerNative-iOS] 🔍 Checking for tokens in start() call...")

        // Check if credentials are in a "config" object (new format)
        if let config = call.getObject("config") {
            print("[AudioTriggerNative-iOS] 📦 Found config object, extracting credentials...")

            if let token = config["sessionToken"] as? String {
                sessionToken = token
                print("[AudioTriggerNative-iOS] ✅ sessionToken received from config")
            } else {
                print("[AudioTriggerNative-iOS] ❌ sessionToken NOT found in config")
            }

            if let refresh = config["refreshToken"] as? String {
                refreshToken = refresh
                print("[AudioTriggerNative-iOS] ✅ refreshToken received from config")
            } else {
                print("[AudioTriggerNative-iOS] ❌ refreshToken NOT found in config")
            }

            if let email = config["emailUsuario"] as? String {
                emailUsuario = email
                print("[AudioTriggerNative-iOS] ✅ emailUsuario received from config: \(email)")
            } else {
                print("[AudioTriggerNative-iOS] ❌ emailUsuario NOT found in config")
            }

            if let rawPeriods = config["monitoringPeriods"] as? [Any] {
                applyMonitoringPeriods(rawPeriods, source: "start(config)")
            } else {
                print("[AudioTriggerNative-iOS] ⚠️ monitoringPeriods NOT found in start(config)")
            }

            // FIX: Store full week schedule if provided in start() so midnight transitions work
            if let weekDict = config["periodosSemana"] as? [String: Any] {
                applyPeriodosSemana(weekDict, source: "start(config)")
            }
        } else {
            // Fallback: check for credentials directly in call (old format)
            print("[AudioTriggerNative-iOS] 📦 No config object, checking direct parameters...")

            if let token = call.getString("sessionToken") {
                sessionToken = token
                print("[AudioTriggerNative-iOS] ✅ sessionToken received")
            } else {
                print("[AudioTriggerNative-iOS] ❌ sessionToken NOT provided in start() call")
            }

            if let refresh = call.getString("refreshToken") {
                refreshToken = refresh
                print("[AudioTriggerNative-iOS] ✅ refreshToken received")
            } else {
                print("[AudioTriggerNative-iOS] ❌ refreshToken NOT provided in start() call")
            }

            if let email = call.getString("emailUsuario") {
                emailUsuario = email
                print("[AudioTriggerNative-iOS] ✅ emailUsuario received: \(email)")
            } else {
                print("[AudioTriggerNative-iOS] ❌ emailUsuario NOT provided in start() call")
            }

            if let rawPeriods = call.getArray("monitoringPeriods") {
                applyMonitoringPeriods(rawPeriods, source: "start(direct)")
            } else {
                print("[AudioTriggerNative-iOS] ⚠️ monitoringPeriods NOT provided directly in start() call")
            }
        }

        // iOS generates and manages device_id internally
        let deviceId = getOrCreateDeviceId()
        print("[AudioTriggerNative-iOS] 🆔 Using device_id: \(deviceId)")
        
        // Request microphone permission for monitoring
        AVAudioSession.sharedInstance().requestRecordPermission { [weak self] granted in
            guard let self = self else { return }
            
            if granted {
                print("[AudioTriggerNative-iOS] OK Microphone permission granted for monitoring")
                
                DispatchQueue.main.async {
                    self.notifyListeners("debugPermissionGranted", data: ["message": "Permissão concedida!"])

                    self.isStartingMonitoring = true

                    let startWork = {
                        self.startMonitoringWithRetry(maxAttempts: 5, delay: 2.0) { success, error in
                            self.isStartingMonitoring = false
                            if success {
                                self.notifyListeners("debugMonitoringStarted", data: ["message": "Monitoramento iniciado!"])
                                call.resolve(["success": true])
                            } else {
                                print("[AudioTriggerNative-iOS] ❌ Failed to start monitoring after retries: \(error?.localizedDescription ?? "unknown")")
                                call.reject("Failed to start monitoring: \(error?.localizedDescription ?? "unknown")")
                            }
                        }
                    }

                    // If app is not active yet (lock/background transition), delay start to avoid AURemoteIO 2003329396
                    if UIApplication.shared.applicationState != .active {
                        print("[AudioTriggerNative-iOS] ⏸️ App not active - deferring monitoring start")
                        DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
                            startWork()
                        }
                    } else {
                        // Start monitoring with retry (handles stale AVAudioSession after swipe-up kill)
                        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
                            startWork()
                        }
                    }
                }
            } else {
                print("[AudioTriggerNative-iOS] ❌ Microphone permission denied")
                call.reject("Microphone permission denied")
            }
        }
    }
    
    /// Attempts to start monitoring with retries and increasing delay between attempts.
    /// After a swipe-up kill, iOS may keep the AVAudioSession locked for several seconds.
    /// Retrying with increasing delays gives the OS time to release it.
    private func startMonitoringWithRetry(maxAttempts: Int, delay: TimeInterval, attempt: Int = 1, completion: @escaping (Bool, Error?) -> Void) {
        do {
            try startMonitoring()
            print("[AudioTriggerNative-iOS] ✅ Monitoring started on attempt \(attempt)")
            completion(true, nil)
        } catch {
            print("[AudioTriggerNative-iOS] ⚠️ Attempt \(attempt)/\(maxAttempts) failed: \(error.localizedDescription)")
            
            if attempt < maxAttempts {
                // Increasing delay: 1s, 2s, 3s, 4s...
                let nextDelay = delay * Double(attempt)
                print("[AudioTriggerNative-iOS] ⏳ Retrying in \(nextDelay)s (attempt \(attempt + 1)/\(maxAttempts))...")
                DispatchQueue.main.asyncAfter(deadline: .now() + nextDelay) { [weak self] in
                    self?.startMonitoringWithRetry(maxAttempts: maxAttempts, delay: delay, attempt: attempt + 1, completion: completion)
                }
            } else {
                completion(false, error)
            }
        }
    }
    
    @objc func stop(_ call: CAPPluginCall) {
        print("[AudioTriggerNative-iOS] 🛑 stop() called (stop monitoring)")
        isStartingMonitoring = false
        
        // Remove audio interruption observers
        removeAudioInterruptionObservers()
        
        // Stop monitoring (calibration + detection)
        stopMonitoring()
        
        // If recording is active, stop it too
        if isRecording {
            stopRecordingInternal()
        } else if isRecordingPausedByInterruption {
            stopReason = "manual"
            finalizeInterruptedRecordingSession()
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
        
        if !isRecording && !isRecordingPausedByInterruption {
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
        
        // Set stop reason based on origem_gravacao
        if origemGravacao == "botao_panico" {
            stopReason = "panico_cancelado"
            print("[AudioTriggerNative-iOS] 🛑 Panic recording cancelled by user")
        } else {
            stopReason = "manual"
        }
        
        if isRecordingPausedByInterruption {
            finalizeInterruptedRecordingSession()
        } else {
            stopRecordingInternal()
        }
        
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
        
        let deviceId = getOrCreateDeviceId()
        call.resolve([
            "isRecording": isRecording,
            "isCalibrated": isCalibrated,
            "deviceId": deviceId
        ])
    }
    
    @objc func updateConfig(_ call: CAPPluginCall) {
        print("[AudioTriggerNative-iOS] 🔧 updateConfig() called")
        
        // Update credentials if provided
        var credentialsUpdated = false
        if let token = call.getString("sessionToken") {
            sessionToken = token
            credentialsUpdated = true
        }
        if let refresh = call.getString("refreshToken") {
            refreshToken = refresh
        }
        if let email = call.getString("emailUsuario") {
            emailUsuario = email
            credentialsUpdated = true
        }
        
        // If credentials were just updated and we're monitoring, start timers
        if credentialsUpdated && audioEngine != nil && sessionToken != nil && emailUsuario != nil {
            // Start ping timer if not already running
            if pingTimer == nil {
                startPingTimer()
            }
            
            // Setup location manager if not already setup
            if locationManager == nil {
                setupLocationManager()
            }
            
            // Start GPS timer if not already running
            if gpsTimer == nil {
                let interval: TimeInterval
                if isRecording {
                    interval = gpsIntervalRecording
                } else if isWithinMonitoringPeriod() {
                    interval = gpsIntervalMonitoring
                } else {
                    interval = gpsIntervalOutsidePeriod
                }
                startGpsTimer(interval: interval)
                print("[AudioTriggerNative-iOS] 📍 GPS timer started with \(interval)s interval")
            }
            
            print("[AudioTriggerNative-iOS] ✅ Credentials updated, timers started")
        }
        
        // Update monitoring periods if provided
        if let periodsArray = call.getArray("monitoringPeriods") {
            applyMonitoringPeriods(periodsArray, source: "updateConfig")
        }

        // FIX: Also store full week schedule when JS pushes periodos_semana via updateConfig
        if let weekDict = call.getObject("periodosSemana") as? [String: Any] {
            applyPeriodosSemana(weekDict, source: "updateConfig")
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

    private func applyMonitoringPeriods(_ periodsArray: [Any], source: String) {
        // Convert JSArray to [[String: String]] tolerating [String: Any] inputs.
        var periods: [[String: String]] = []
        for item in periodsArray {
            if let dict = item as? [String: String],
               let inicio = dict["inicio"],
               let fim = dict["fim"] {
                periods.append(["inicio": inicio, "fim": fim])
                continue
            }
            if let dictAny = item as? [String: Any] {
                let inicio = dictAny["inicio"] as? String
                let fim = dictAny["fim"] as? String
                if let inicio, let fim {
                    periods.append(["inicio": inicio, "fim": fim])
                    continue
                }
            }
            print("[AudioTriggerNative-iOS] ⚠️ Ignoring invalid monitoring period item (\(source)): \(item)")
        }

        monitoringPeriods = periods
        print("[AudioTriggerNative-iOS] 📅 Updated monitoring periods from \(source): \(periods.count) periods (received: \(periodsArray.count))")
        for (index, period) in periods.enumerated() {
            if let inicio = period["inicio"], let fim = period["fim"] {
                print("[AudioTriggerNative-iOS] 📅   Period \(index): \(inicio) - \(fim)")
            }
        }
        if periods.isEmpty {
            print("[AudioTriggerNative-iOS] ⚠️ monitoringPeriods is empty after parsing - schedule checks will be treated as OUTSIDE WINDOW")
        }
    }

    /// Store the full week schedule received from the server or JS config.
    /// Keys are Brazilian weekday abbreviations: "dom","seg","ter","qua","qui","sex","sab"
    private func applyPeriodosSemana(_ weekDict: [String: Any], source: String) {
        var parsed: [String: [[String: String]]] = [:]
        let dayKeys = ["dom", "seg", "ter", "qua", "qui", "sex", "sab"]
        for key in dayKeys {
            guard let dayArray = weekDict[key] as? [Any] else { continue }
            var dayPeriods: [[String: String]] = []
            for item in dayArray {
                if let d = item as? [String: String], let i = d["inicio"], let f = d["fim"] {
                    dayPeriods.append(["inicio": i, "fim": f])
                } else if let d = item as? [String: Any], let i = d["inicio"] as? String, let f = d["fim"] as? String {
                    dayPeriods.append(["inicio": i, "fim": f])
                }
            }
            if !dayPeriods.isEmpty {
                parsed[key] = dayPeriods
            }
        }
        periodosSemana = parsed
        print("[AudioTriggerNative-iOS] 📅 Updated periodosSemana from \(source): \(parsed.keys.joined(separator: ", "))")

        // Also refresh today's monitoringPeriods so the running check is immediately consistent
        let todayKey = todayWeekdayKey()
        if let todayPeriods = parsed[todayKey] {
            monitoringPeriods = todayPeriods
            print("[AudioTriggerNative-iOS] 📅 Refreshed today's (\(todayKey)) monitoringPeriods: \(todayPeriods.count) period(s)")
        }
    }

    /// Returns the Brazilian weekday abbreviation for today ("dom","seg","ter","qua","qui","sex","sab")
    private func todayWeekdayKey() -> String {
        // Calendar.weekday: 1=Sunday, 2=Monday, ..., 7=Saturday
        let keys = ["dom", "seg", "ter", "qua", "qui", "sex", "sab"]
        let weekdayIndex = Calendar.current.component(.weekday, from: Date()) - 1 // 0-based
        return keys[weekdayIndex]
    }

    private func isWithinMonitoringPeriod() -> Bool {
        // FIX: Always derive today's periods dynamically so the check stays correct
        // after midnight without requiring a JS restart or config push.
        //
        // Priority:
        //   1. periodosSemana[todayKey]  — full week schedule (most accurate, day-aware)
        //   2. monitoringPeriods         — fallback: periods pushed by JS for "today" on start()
        let todayKey = todayWeekdayKey()
        let periodsToCheck: [[String: String]]
        if !periodosSemana.isEmpty, let todayFromWeek = periodosSemana[todayKey] {
            periodsToCheck = todayFromWeek
        } else if !monitoringPeriods.isEmpty {
            periodsToCheck = monitoringPeriods
        } else {
            // Fail-safe: no periods configured → treat as outside monitoring window
            return false
        }

        let now = Date()
        let calendar = Calendar.current
        let currentHour = calendar.component(.hour, from: now)
        let currentMinute = calendar.component(.minute, from: now)
        let currentMinutes = currentHour * 60 + currentMinute

        for period in periodsToCheck {
            guard let inicioStr = period["inicio"], let fimStr = period["fim"] else { continue }

            let inicioComponents = inicioStr.split(separator: ":").compactMap { Int($0) }
            let fimComponents   = fimStr.split(separator: ":").compactMap { Int($0) }
            guard inicioComponents.count == 2, fimComponents.count == 2 else { continue }

            let startMinutes = inicioComponents[0] * 60 + inicioComponents[1]
            let endMinutes   = fimComponents[0]   * 60 + fimComponents[1]

            if currentMinutes >= startMinutes && currentMinutes < endMinutes {
                return true
            }
        }
        return false
    }
    
    // MARK: - Monitoring Methods
    
    private func startMonitoring() throws {
        print("[AudioTriggerNative-iOS] 👂 Starting monitoring (calibration + detection only)...")
        
        // Set monitoring state
        isMonitoring = true
        
        // Force cleanup any existing audioEngine (stale state from previous session)
        if let existingEngine = audioEngine {
            print("[AudioTriggerNative-iOS] 🧹 Cleaning up stale audioEngine before restart")
            existingEngine.stop()
            existingEngine.inputNode.removeTap(onBus: 0)
            audioEngine = nil
        }
        
        // STEP 1: Force deactivate audio session (ignore errors - session may not be active)
        deactivateAudioSession(context: "startMonitoring(force)")
        print("[AudioTriggerNative-iOS] 🔇 Audio session force-deactivated")
        
        // STEP 2: Small pause to let iOS release audio resources
        Thread.sleep(forTimeInterval: 0.3)
        
        // STEP 3/4: Configure and activate audio session for capture
        try configureAudioSessionForCapture()
        
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
        
        // Start ping timer for background keep-alive (only if logged in)
        if sessionToken != nil && emailUsuario != nil {
            startPingTimer()
            
            // Setup location manager if not already setup
            if locationManager == nil {
                setupLocationManager()
            }
            
            // Start GPS timer with appropriate interval
            let interval = isWithinMonitoringPeriod() ? gpsIntervalMonitoring : gpsIntervalOutsidePeriod
            startGpsTimer(interval: interval)
            print("[AudioTriggerNative-iOS] 📍 GPS timer started with \(interval)s interval (within period: \(isWithinMonitoringPeriod()))")
            
            // Start period check timer to detect when entering/exiting monitoring periods
            startPeriodCheckTimer()

            // Start config sync timer to fetch configuration periodically
            startConfigSyncTimer()

            // Sync immediately once at startup (do not wait 5 minutes)
            syncConfigurationFromServer()
        } else {
            print("[AudioTriggerNative-iOS] ⚠️ Skipping ping/GPS/config timers: user not logged in")
        }
        
        print("[AudioTriggerNative-iOS] ✅ Monitoring started (calibrating...) - NOT recording")
        print("[AudioTriggerNative-iOS] 📊 Metrics timer should now be sending audioMetrics every 0.5s")
    }
    
    private func stopMonitoring() {
        print("[AudioTriggerNative-iOS] 🛑 Stopping monitoring...")
        
        // Set monitoring state
        isMonitoring = false
        
        // Stop metrics timer
        stopMetricsTimer()
        
        // Stop ping timer
        stopPingTimer()

        // Stop GPS timer
        stopGpsTimer()

        // Stop period check timer
        stopPeriodCheckTimer()

        // Stop heartbeat timer
        stopHeartbeatTimer()

        // Stop config sync timer
        stopConfigSyncTimer()

        audioEngine?.stop()
        audioEngine?.inputNode.removeTap(onBus: 0)
        audioEngine = nil
        
        // Deactivate audio session to fully release microphone
        deactivateAudioSession(context: "stopMonitoring")
        print("[AudioTriggerNative-iOS] 🔇 Audio session deactivated")
        
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
        // Deactivate first to release any stale locks
        deactivateAudioSession(context: "startRecording(prep)")
        try configureAudioSessionForCapture()
        
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
        } else if let engine = audioEngine {
            // Reinstall tap and ensure engine is running after audio session reconfiguration.
            // Without this, input callbacks can stop and segments stay header-only (~520 bytes).
            let inputNode = engine.inputNode
            let inputFormat = inputNode.outputFormat(forBus: 0)
            inputNode.removeTap(onBus: 0)
            inputNode.installTap(onBus: 0, bufferSize: 4096, format: inputFormat) { [weak self] buffer, _ in
                self?.processAudioBuffer(buffer)
            }

            if !engine.isRunning {
                try engine.start()
                print("[AudioTriggerNative-iOS] 🔄 Reused engine was stopped; restarted for recording")
            } else {
                print("[AudioTriggerNative-iOS] ✅ Reused engine is running with refreshed input tap")
            }
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
        
        // Use device_id from JavaScript (no longer generate sessionId)
        // sessionId is now replaced by device_id for consistency
        
        // Create uploader
        guard let token = sessionToken, let email = emailUsuario else {
            throw NSError(domain: "AudioTriggerNative", code: -3, userInfo: [NSLocalizedDescriptionKey: "Missing credentials"])
        }
        let deviceId = getOrCreateDeviceId()
        uploader = AudioSegmentUploader(
            sessionId: deviceId,
            sessionToken: token,
            emailUsuario: email,
            origemGravacao: origemGravacao
        )
        
        // Set plugin reference so uploader can share GPS location
        uploader?.plugin = self
        
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
        startBackgroundTaskRenewalTimer()
        
        // Start segment timer
        startSegmentTimer()

        // Start absolute silence timeout (10 minutes fallback)
        startAbsoluteSilenceTimer()

        // Initialize countdown tracking
        countdownTimeoutType = "absolute"
        countdownSilenceStartTime = nil
        startCountdownTimer()
        
        // Notify JS
        notifyEvent("nativeRecordingStarted", data: [
            "deviceId": deviceId,
            "startedAt": Int(Date().timeIntervalSince1970 * 1000),
            "origemGravacao": origemGravacao
        ])
        
        // Report to server
        reportRecordingStatus("iniciada")
        
        // Restart GPS timer with 10-second interval during recording
        startGpsTimer(interval: gpsIntervalRecording)
        
        print("[AudioTriggerNative-iOS] ✅ Recording started, device_id: \(deviceId)")
    }
    
    private func stopRecordingInternal() {
        print("[AudioTriggerNative-iOS] 🛑 Stopping recording...")
        cancelInterruptionTimeoutTimer()
        isRecordingPausedByInterruption = false
        
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
        
        // Deactivate audio session to fully release microphone
        deactivateAudioSession(context: "stopRecordingInternal")
        print("[AudioTriggerNative-iOS] 🔇 Audio session deactivated after recording")
        
        // Stop segment timer
        segmentTimer?.cancel()
        segmentTimer = nil
        
        // Stop countdown timer
        stopCountdownTimer()
        countdownTimeoutType = "none"
        countdownSilenceStartTime = nil

        // Stop background task
        stopBackgroundTaskRenewalTimer()
        endBackgroundTask()

        // Update state
        isRecording = false
        autoTriggerCooldownUntil = Date().addingTimeInterval(autoTriggerCooldownSeconds)
        print("[AudioTriggerNative-iOS] ⏳ Auto-trigger cooldown started (\(Int(autoTriggerCooldownSeconds))s)")
        
        // Restart GPS timer only if monitoring remains active.
        if isMonitoring {
            let interval = isWithinMonitoringPeriod() ? gpsIntervalMonitoring : gpsIntervalOutsidePeriod
            startGpsTimer(interval: interval)
            print("[AudioTriggerNative-iOS] 📍 GPS timer restarted with \(interval)s interval (within period: \(isWithinMonitoringPeriod()))")
        } else {
            stopGpsTimer()
            print("[AudioTriggerNative-iOS] ⏹️ GPS timer remains stopped (monitoring inactive)")
        }
        
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

            // Panic global validity: 60 minutes max.
            if panicManager.isPanicActive, let panicStartMs = panicManager.startTime {
                let elapsed = Date().timeIntervalSince1970 - (Double(panicStartMs) / 1000.0)
                if elapsed >= panicMaxDuration {
                    print("[AudioTriggerNative-iOS] ⏰ Panic validity timeout reached (60min) - cancelling panic")
                    stopReason = "timeout_panic"
                    panicManager.cancelPanic()
                    stopRecordingInternal()
                    autoRecordingActive = false
                    cancelEndTimers()
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
                        guard let self = self else { return }
                        do { try self.startMonitoring() } catch {
                            print("[AudioTriggerNative-iOS] ❌ Failed to restart monitoring after panic validity timeout: \(error)")
                        }
                    }
                    return
                }
            }

            // Absolute silence timeout is based on detected activity (panic and non-panic).
            if realScore >= 0.3 {
                resetAbsoluteSilenceTimer()
            }

            // Panic shield: while panic is active, ignore discussion-end logic.
            if panicManager.isPanicActive {
                discussionScore = 0.0
                return
            }

            // Detect silence during auto-recording (for 10s + 110s timers = 2 minutes)
            if autoRecordingActive {
                if realScore < 0.3 { // Low score = silence
                    if silenceStartTime == nil {
                        silenceStartTime = Date()

                        // Update countdown timer for silence phase
                        if countdownSilenceStartTime == nil {
                            countdownSilenceStartTime = Date()
                            countdownTimeoutType = "silence"
                            print("[AudioTriggerNative-iOS] 🔇 Silêncio detectado - iniciando countdown de 120s")
                        }

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

                        // Reset countdown to absolute timeout
                        countdownSilenceStartTime = nil
                        countdownTimeoutType = "absolute"
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

            // Detection logs removed to avoid console spam

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
        // Panic shield: discussion AI must not stop/start recordings during active panic.
        if panicManager.isPanicActive {
            return
        }

        if let cooldownUntil = autoTriggerCooldownUntil, Date() < cooldownUntil {
            let remaining = Int(cooldownUntil.timeIntervalSinceNow)
            if Int(Date().timeIntervalSince1970) % 5 == 0 {
                print("[AudioTriggerNative-iOS] ⏳ Auto-trigger cooldown active: \(max(remaining, 0))s remaining")
            }
            return
        }

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
        print("[AudioTriggerNative-iOS] ⏱️ Confirmation phase complete (10s), starting safety buffer (110s)")
        
        // Use DispatchSourceTimer - works in background during recording
        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .utility))
        timer.schedule(deadline: .now() + endHoldSeconds)
        timer.setEventHandler { [weak self] in
            guard let self = self else { return }
            
            print("[AudioTriggerNative-iOS] ⏰ Safety buffer complete (110s) - total 120s silence - stopping auto-recording")
            
            DispatchQueue.main.async {
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
        timer.resume()
        endHoldTimer = timer
    }
    
    private func cancelEndTimers() {
        silenceStartTime = nil
        endHoldTimer?.cancel()
        endHoldTimer = nil
        inEndHoldPhase = false
        
        // Also cancel absolute silence timeout
        absoluteSilenceTimer?.cancel()
        absoluteSilenceTimer = nil
        
        print("[AudioTriggerNative-iOS] ❌ End timers cancelled (discussion resumed)")
    }
    
    private func startAbsoluteSilenceTimer() {
        // Cancel existing timer
        absoluteSilenceTimer?.cancel()
        absoluteSilenceTimer = nil
        
        // Use DispatchSourceTimer - works in background during recording
        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .utility))
        timer.schedule(deadline: .now() + absoluteSilenceTimeout)
        timer.setEventHandler { [weak self] in
            guard let self = self else { return }

            print("[AudioTriggerNative-iOS] ⏰ 10min absolute silence timeout reached - stopping recording")
            
            DispatchQueue.main.async {
                // Stop recording with timeout reason
                self.stopReason = self.panicManager.isPanicActive ? "timeout_silencio" : "timeout"
                if self.panicManager.isPanicActive {
                    self.panicManager.cancelPanic()
                }
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
        }
        timer.resume()
        absoluteSilenceTimer = timer
        
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
        
        // Stop existing timer if any
        segmentTimer?.cancel()
        segmentTimer = nil
        
        // Use DispatchSourceTimer - works in background (unlike Timer.scheduledTimer)
        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .userInitiated))
        timer.schedule(deadline: .now() + segmentDuration, repeating: segmentDuration)
        timer.setEventHandler { [weak self] in
            print("[AudioTriggerNative-iOS] ⏰ Segment timer FIRED! Calling uploadSegment()...")
            DispatchQueue.main.async {
                self?.uploadSegment()
            }
        }
        timer.resume()
        segmentTimer = timer
        
        print("[AudioTriggerNative-iOS] ✅ Segment timer created with DispatchSourceTimer, will fire every \(segmentDuration)s - works in background")
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
        guard let token = sessionToken else {
            print("[AudioTriggerNative-iOS] ⚠️ Cannot report status: missing token")
            return
        }
        
        let deviceId = getOrCreateDeviceId()
        
        print("[AudioTriggerNative-iOS] 📡 Reporting status: \(status)")
        print("[AudioTriggerNative-iOS] 📡 Device ID: \(deviceId)")
        print("[AudioTriggerNative-iOS] 📡 Email: \(emailUsuario ?? "nil")")
        
        guard let apiUrl = getApiUrl(), let url = URL(string: apiUrl) else {
            print("[AudioTriggerNative-iOS] ❌ Cannot report status: API URL not configured")
            return
        }
        
        // Get timezone info
        let timezone = TimeZone.current.identifier
        let timezoneOffset = TimeZone.current.secondsFromGMT() / 60  // Convert to minutes
        
        // Get device info
        let device = UIDevice.current
        device.isBatteryMonitoringEnabled = true
        let batteryLevel = Int(device.batteryLevel * 100)
        let deviceInfo = "\(device.systemName) \(device.systemVersion) - \(device.model)"
        let appVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "unknown"
        
        // Build body JSON matching API specification
        var body: [String: Any] = [
            "action": "reportarStatusGravacao",
            "device_id": deviceId,
            "timezone": timezone,
            "timezone_offset_minutes": timezoneOffset,
            "session_token": token,
            "email_usuario": emailUsuario ?? "",
            "status_gravacao": status,
            "origem_gravacao": origemGravacao,
            "dispositivo_info": deviceInfo,
            "versao_app": appVersion
        ]

        if batteryLevel >= 0 {
            body["bateria_percentual"] = batteryLevel
        }
        
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
        print("[AudioTriggerNative-iOS] 📡 Request body: \(redactSensitiveJsonString(from: jsonData))")

        // FIX: Use statusSession (not URLSession.shared) so the request survives background suspension
        statusSession.dataTask(with: request) { data, response, error in
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
    
    private func reportMonitoringStatus(_ status: String, isMonitoring: Bool, motivo: String = "janela_agendada", isRetry: Bool = false) {
        guard let email = emailUsuario else {
            print("[AudioTriggerNative-iOS] ⚠️ Cannot report monitoring status: missing email")
            return
        }

        guard let token = sessionToken else {
            print("[AudioTriggerNative-iOS] ⚠️ Cannot report monitoring status: missing session_token")
            return
        }

        let deviceId = getOrCreateDeviceId()

        print("[AudioTriggerNative-iOS] 📡 Reporting monitoring status: \(status), is_monitoring: \(isMonitoring)")
        print("[AudioTriggerNative-iOS] 📡 Device ID: \(deviceId)")
        print("[AudioTriggerNative-iOS] 📡 Email: \(email)")

        guard let apiUrl = getApiUrl(), let url = URL(string: apiUrl) else {
            print("[AudioTriggerNative-iOS] ❌ Cannot report monitoring status: API URL not configured")
            return
        }

        // Build body JSON
        let body: [String: Any] = [
            "action": "reportarStatusMonitoramento",
            "email_usuario": email,
            "device_id": deviceId,
            "session_token": token,
            "status_monitoramento": status,
            "is_monitoring": isMonitoring,
            "motivo": motivo
        ]
        
        guard let jsonData = try? JSONSerialization.data(withJSONObject: body) else { return }
        
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = jsonData
        
        print("[AudioTriggerNative-iOS] 📡 Sending monitoring status to: \(url.absoluteString)")
        print("[AudioTriggerNative-iOS] 📡 Request body: \(redactSensitiveJsonString(from: jsonData))")

        // FIX: Use statusSession (not URLSession.shared) so the request survives background suspension
        statusSession.dataTask(with: request) { data, response, error in
            if let error = error {
                print("[AudioTriggerNative-iOS] ❌ Failed to report monitoring status: \(error.localizedDescription)")
                return
            }
            
            if let httpResponse = response as? HTTPURLResponse {
                print("[AudioTriggerNative-iOS] 📊 HTTP Status: \(httpResponse.statusCode)")
                
                if let data = data, let responseBody = String(data: data, encoding: .utf8) {
                    print("[AudioTriggerNative-iOS] 📊 Response body: \(responseBody)")
                }
                
                if httpResponse.statusCode == 200 {
                    print("[AudioTriggerNative-iOS] ✅ Monitoring status '\(status)' reported successfully")
                } else if httpResponse.statusCode == 401 {
                    print("[AudioTriggerNative-iOS] 🔒 Token expired (401) - attempting refresh")

                    // Only try to refresh if this is not already a retry
                    if !isRetry {
                        self.refreshAccessToken { success in
                            if success {
                                print("[AudioTriggerNative-iOS] ✅ Token refreshed - retrying monitoring status report")
                                self.reportMonitoringStatus(status, isMonitoring: isMonitoring, motivo: motivo, isRetry: true)
                            } else {
                                print("[AudioTriggerNative-iOS] ❌ Token refresh failed for monitoring status report")
                            }
                        }
                    } else {
                        print("[AudioTriggerNative-iOS] ❌ Already retried - stopping to prevent infinite loop")
                    }
                } else {
                    print("[AudioTriggerNative-iOS] ❌ Monitoring status report failed with code: \(httpResponse.statusCode)")
                }
            }
        }.resume()
    }
    
    // MARK: - Background Task
    
    private func startBackgroundTask() {
        if backgroundTaskID != .invalid {
            UIApplication.shared.endBackgroundTask(backgroundTaskID)
            backgroundTaskID = .invalid
        }
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
        // Use DispatchSourceTimer - works in background
        backgroundTaskRenewalTimer?.cancel()
        backgroundTaskRenewalTimer = nil
        
        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .utility))
        timer.schedule(deadline: .now() + 25.0, repeating: 25.0)
        timer.setEventHandler { [weak self] in
            guard let self = self else { return }
            print("[AudioTriggerNative-iOS] 🔄 Renewing background task...")
            DispatchQueue.main.async {
                self.endBackgroundTask()
                self.startBackgroundTask()
            }
        }
        timer.resume()
        backgroundTaskRenewalTimer = timer
    }
    
    private func stopBackgroundTaskRenewalTimer() {
        backgroundTaskRenewalTimer?.cancel()
        backgroundTaskRenewalTimer = nil
    }
    
    // MARK: - Metrics Timer
    
    private func startMetricsTimer() {
        // Stop existing timer if any
        stopMetricsTimer()

        // FIX: Use DispatchSourceTimer instead of Timer.scheduledTimer.
        // Timer.scheduledTimer is tied to the main RunLoop which iOS suspends when
        // the app enters background, causing metrics updates (and any state logic
        // inside sendMetricsUpdate) to silently stop.
        // DispatchSourceTimer runs on a DispatchQueue and is NOT affected by RunLoop suspension.
        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .userInteractive))
        timer.schedule(deadline: .now() + metricsUpdateInterval, repeating: metricsUpdateInterval)
        timer.setEventHandler { [weak self] in
            self?.sendMetricsUpdate()
        }
        timer.resume()
        metricsTimer = timer

        print("[AudioTriggerNative-iOS] ⏱️ Metrics timer started with DispatchSourceTimer (every \(metricsUpdateInterval)s) — works in background")
    }
    
    private func stopMetricsTimer() {
        metricsTimer?.cancel()
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
        // Commented out to reduce log noise
        // let now = Date().timeIntervalSince1970
        // if Int(now) % 5 == 0 {
        //     print("[AudioTriggerNative-iOS] 📊 Metrics: rmsDb=\(currentRmsDb), noiseFloor=\(noiseFloor), score=\(discussionScore), speechDensity=\(speechDensity), loudDensity=\(loudDensity)")
        // }
        
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
        if interruptionObserversConfigured {
            return
        }
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
            selector: #selector(handleAppDidEnterBackground(_:)),
            name: UIApplication.didEnterBackgroundNotification,
            object: nil
        )

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleAppWillEnterForeground(_:)),
            name: UIApplication.willEnterForegroundNotification,
            object: nil
        )
        
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleAppDidBecomeActive(_:)),
            name: UIApplication.didBecomeActiveNotification,
            object: nil
        )
        
        interruptionObserversConfigured = true
        print("[AudioTriggerNative-iOS] 🔔 Audio interruption observers setup")
    }
    
    private func removeAudioInterruptionObservers() {
        guard interruptionObserversConfigured else { return }
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
            name: UIApplication.didEnterBackgroundNotification,
            object: nil
        )

        NotificationCenter.default.removeObserver(
            self,
            name: UIApplication.willEnterForegroundNotification,
            object: nil
        )
        
        NotificationCenter.default.removeObserver(
            self,
            name: UIApplication.didBecomeActiveNotification,
            object: nil
        )
        
        interruptionObserversConfigured = false
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
            if let reasonValue = userInfo[AVAudioSessionInterruptionReasonKey] as? UInt,
               let reason = AVAudioSession.InterruptionReason(rawValue: reasonValue) {
                print("[AudioTriggerNative-iOS] 📎 Interruption reason: \(reason.rawValue)")
            }

            if let until = internalAudioSessionDeactivationUntil, Date() < until {
                let remainingMs = Int(max(until.timeIntervalSinceNow, 0) * 1000)
                print("[AudioTriggerNative-iOS] ℹ️ Ignoring likely self-induced interruption (\(remainingMs)ms)")
                return
            }

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
                
                // Pause recording session without finalizing it
                pauseRecordingForInterruption()
                
                // Notify JS
                notifyEvent("audioInterrupted", data: [
                    "reason": "mic_solicitado",
                    "wasRecording": true
                ])
            }
            
            // Stop monitoring if active
            if wasMonitoringBeforeInterruption && !wasRecordingBeforeInterruption {
                print("[AudioTriggerNative-iOS] ⏸️ Pausing monitoring due to interruption")
                stopMonitoring()
            }
            
        case .ended:
            // Interruption ended
            print("[AudioTriggerNative-iOS] ✅ Audio interruption ended")

            let optionsValue = userInfo[AVAudioSessionInterruptionOptionKey] as? UInt
            let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue ?? 0)
            let shouldResume = options.contains(.shouldResume)

            if shouldResume {
                print("[AudioTriggerNative-iOS] 🔄 Resuming after interruption")
                
                // Resume monitoring if it was active
                if wasMonitoringBeforeInterruption && !wasRecordingBeforeInterruption {
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
                if wasRecordingBeforeInterruption {
                    print("[AudioTriggerNative-iOS] 🔄 Resuming paused recording after interruption")
                    resumeRecordingAfterInterruption()
                }
            } else {
                print("[AudioTriggerNative-iOS] ⚠️ Interruption ended but should NOT resume")

                // Some media interruptions end without .shouldResume; force recovery so
                // monitoring does not stay down permanently.
                if wasMonitoringBeforeInterruption {
                    print("[AudioTriggerNative-iOS] 🔄 Attempting forced monitoring recovery")
                    DispatchQueue.global(qos: .userInitiated).asyncAfter(deadline: .now() + 0.5) { [weak self] in
                        guard let self = self else { return }
                        if self.audioEngine?.isRunning == true { return }
                        self.startMonitoringWithRetry(maxAttempts: 3, delay: 0.8) { success, error in
                            if success {
                                print("[AudioTriggerNative-iOS] ✅ Forced monitoring recovery succeeded")
                                self.notifyEvent("audioResumed", data: [
                                    "monitoringResumed": true,
                                    "recordingResumed": false
                                ])
                            } else {
                                print("[AudioTriggerNative-iOS] ❌ Forced monitoring recovery failed: \(error?.localizedDescription ?? "unknown")")
                            }
                        }
                    }
                }

                if wasRecordingBeforeInterruption {
                    print("[AudioTriggerNative-iOS] 🔄 Attempting forced recording resume after interruption")
                    DispatchQueue.global(qos: .userInitiated).asyncAfter(deadline: .now() + 0.5) { [weak self] in
                        self?.resumeRecordingAfterInterruption()
                    }
                }
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

    private func pauseRecordingForInterruption() {
        guard isRecording else { return }

        isRecordingPausedByInterruption = true
        isRecording = false

        // Pause timers and session resources, but keep recording session alive.
        segmentTimer?.cancel()
        segmentTimer = nil
        stopCountdownTimer()
        stopBackgroundTaskRenewalTimer()
        endBackgroundTask()
        stopGpsTimer()
        deactivateAudioSession(context: "pauseRecordingForInterruption")

        // Stop capture pipeline; session will be resumed on interruption end.
        audioEngine?.stop()
        audioEngine?.inputNode.removeTap(onBus: 0)
        audioEngine = nil

        // Flush current segment without reporting "finalizada".
        if let uploader = uploader {
            uploader.finishSegment { [weak self] success in
                guard let self = self else { return }
                if success {
                    print("[AudioTriggerNative-iOS] ✅ Segment flushed on interruption pause")
                } else {
                    print("[AudioTriggerNative-iOS] ⚠️ Failed to flush segment on interruption pause")
                }
                self.reportRecordingStatus("pausada_interrupcao")
            }
        } else {
            reportRecordingStatus("pausada_interrupcao")
        }

        startInterruptionTimeoutTimer()
    }

    private func resumeRecordingAfterInterruption() {
        guard isRecordingPausedByInterruption else { return }
        guard sessionToken != nil, emailUsuario != nil else { return }

        do {
            try configureAudioSessionForCapture()

            if audioEngine == nil {
                audioEngine = AVAudioEngine()
                guard let engine = audioEngine else { return }
                let inputNode = engine.inputNode
                let inputFormat = inputNode.outputFormat(forBus: 0)
                inputNode.installTap(onBus: 0, bufferSize: 4096, format: inputFormat) { [weak self] buffer, _ in
                    self?.processAudioBuffer(buffer)
                }
                try engine.start()
            }

            if uploader == nil, let token = sessionToken, let email = emailUsuario {
                let deviceId = getOrCreateDeviceId()
                uploader = AudioSegmentUploader(
                    sessionId: deviceId,
                    sessionToken: token,
                    emailUsuario: email,
                    origemGravacao: origemGravacao
                )
                uploader?.plugin = self
            }

            guard let recordingFormat = AVAudioFormat(
                commonFormat: .pcmFormatFloat32,
                sampleRate: sampleRate,
                channels: channels,
                interleaved: false
            ) else { return }

            try uploader?.startNewSegment(format: recordingFormat)

            isRecording = true
            isRecordingPausedByInterruption = false
            cancelInterruptionTimeoutTimer()

            startBackgroundTask()
            startBackgroundTaskRenewalTimer()
            startSegmentTimer()
            startAbsoluteSilenceTimer()
            countdownTimeoutType = "absolute"
            countdownSilenceStartTime = nil
            startCountdownTimer()
            startGpsTimer(interval: gpsIntervalRecording)

            reportRecordingStatus("retomada_interrupcao")
            notifyEvent("audioResumed", data: [
                "monitoringResumed": false,
                "recordingResumed": true
            ])
            print("[AudioTriggerNative-iOS] ✅ Recording resumed after interruption")
        } catch {
            print("[AudioTriggerNative-iOS] ❌ Failed to resume recording after interruption: \(error.localizedDescription)")
        }
    }

    private func startInterruptionTimeoutTimer() {
        cancelInterruptionTimeoutTimer()
        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .utility))
        timer.schedule(deadline: .now() + interruptionMaxPauseSeconds)
        timer.setEventHandler { [weak self] in
            guard let self = self else { return }
            if self.isRecordingPausedByInterruption {
                print("[AudioTriggerNative-iOS] ⏱️ Interruption pause timeout reached, finalizing recording session")
                self.stopReason = "interrupcao_timeout"
                self.finalizeInterruptedRecordingSession()
            }
        }
        timer.resume()
        interruptionTimeoutTimer = timer
    }

    private func cancelInterruptionTimeoutTimer() {
        interruptionTimeoutTimer?.cancel()
        interruptionTimeoutTimer = nil
    }

    private func finalizeInterruptedRecordingSession() {
        guard isRecordingPausedByInterruption else { return }

        isRecordingPausedByInterruption = false
        cancelInterruptionTimeoutTimer()
        uploader?.cleanup()
        uploader = nil
        stopBackgroundTaskRenewalTimer()
        endBackgroundTask()
        reportRecordingStatus("finalizada")
        notifyEvent("recordingStopped", data: [:])
        notifyEvent("nativeRecordingStopped", data: [:])
    }
    
    @objc private func handleAppWillResignActive(_ notification: Notification) {
        print("[AudioTriggerNative-iOS] 🔹 App will resign active (going to background/lock)")
        if isMonitoring || isRecording {
            beginAppTransitionBackgroundTask()
        }
    }

    @objc private func handleAppDidEnterBackground(_ notification: Notification) {
        print("[AudioTriggerNative-iOS] 🌙 App entered background/lock - reinforcing background services")
        ensureBackgroundServicesRunning()
        if isRecording {
            startBackgroundTask()
            startBackgroundTaskRenewalTimer()
        }
    }

    @objc private func handleAppWillEnterForeground(_ notification: Notification) {
        print("[AudioTriggerNative-iOS] 🌅 App will enter foreground")
        endAppTransitionBackgroundTask()
    }
    
    @objc private func handleAppDidBecomeActive(_ notification: Notification) {
        print("[AudioTriggerNative-iOS] 🔸 App did become active (returning from background/lock)")
        endAppTransitionBackgroundTask()
        
        // Only restart if we had credentials (user was logged in)
        guard sessionToken != nil else {
            print("[AudioTriggerNative-iOS] ⚠️ No session token - skipping auto-restart (waiting for JS start() call)")
            return
        }

        // If recording was paused by interruption, prioritize recording resume.
        if isRecordingPausedByInterruption {
            print("[AudioTriggerNative-iOS] 🔄 Recording is paused by interruption - attempting resume first")
            DispatchQueue.global(qos: .userInitiated).asyncAfter(deadline: .now() + 0.4) { [weak self] in
                self?.resumeRecordingAfterInterruption()
            }
            return
        }
        
        // Restart audio engine if it stopped
        if audioEngine == nil || audioEngine?.isRunning == false {
            print("[AudioTriggerNative-iOS] ⚠️ Audio engine stopped - restarting with retry (5 attempts, increasing delay)")
            
            startMonitoringWithRetry(maxAttempts: 5, delay: 1.0) { success, error in
                if success {
                    print("[AudioTriggerNative-iOS] ✅ Monitoring restarted after returning from background")
                    self.ensureBackgroundServicesRunning()
                } else {
                    print("[AudioTriggerNative-iOS] ❌ Failed to restart monitoring after 5 retries: \(error?.localizedDescription ?? "unknown")")
                    // Notify JS so the UI can show an error and let user retry
                    self.notifyListeners("monitoringError", data: ["error": "Failed to restart monitoring after app reopen. Please try again."])
                }
            }
        } else {
            ensureBackgroundServicesRunning()
        }
    }

    private func configureAudioSessionForCapture() throws {
        let audioSession = AVAudioSession.sharedInstance()
        let options: AVAudioSession.CategoryOptions = [.allowBluetooth, .defaultToSpeaker, .mixWithOthers]
        try audioSession.setCategory(.playAndRecord, mode: .default, options: options)
        try audioSession.setActive(true)
    }

    private func deactivateAudioSession(context: String) {
        internalAudioSessionDeactivationUntil = Date().addingTimeInterval(internalInterruptionGraceWindow)
        do {
            try AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        } catch {
            print("[AudioTriggerNative-iOS] ⚠️ Could not deactivate audio session (\(context)): \(error.localizedDescription)")
        }
    }

    private func ensureBackgroundServicesRunning() {
        guard isMonitoring else { return }

        if sessionToken != nil && emailUsuario != nil {
            if pingTimer == nil {
                print("[AudioTriggerNative-iOS] 🔁 Restarting ping timer after app state transition")
                startPingTimer()
            }
            if periodCheckTimer == nil {
                print("[AudioTriggerNative-iOS] 🔁 Restarting period check timer after app state transition")
                startPeriodCheckTimer()
            }
            if configSyncTimer == nil {
                print("[AudioTriggerNative-iOS] 🔁 Restarting config sync timer after app state transition")
                startConfigSyncTimer()
            }
        }

        if gpsTimer == nil {
            let interval: TimeInterval = isRecording
                ? gpsIntervalRecording
                : (isWithinMonitoringPeriod() ? gpsIntervalMonitoring : gpsIntervalOutsidePeriod)
            print("[AudioTriggerNative-iOS] 🔁 Restarting GPS timer after app state transition (\(interval)s)")
            startGpsTimer(interval: interval)
        }

        if locationManager == nil {
            setupLocationManager()
        } else {
            let authStatus = locationManager?.authorizationStatus ?? .notDetermined
            if authStatus == .authorizedAlways {
                locationManager?.startUpdatingLocation()
                if CLLocationManager.significantLocationChangeMonitoringAvailable() {
                    locationManager?.startMonitoringSignificantLocationChanges()
                }
            }
        }
    }

    private func beginAppTransitionBackgroundTask() {
        if appTransitionTaskID != .invalid { return }
        appTransitionTaskID = UIApplication.shared.beginBackgroundTask { [weak self] in
            self?.endAppTransitionBackgroundTask()
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 12.0) { [weak self] in
            self?.endAppTransitionBackgroundTask()
        }
    }

    private func endAppTransitionBackgroundTask() {
        if appTransitionTaskID != .invalid {
            UIApplication.shared.endBackgroundTask(appTransitionTaskID)
            appTransitionTaskID = .invalid
        }
    }
    
    // MARK: - Ping Timer (Background Keep-Alive)
    
    private func startPingTimer() {
        // Stop existing timer if any
        stopPingTimer()
        
        // Send immediate ping
        sendPing()
        
        // Use DispatchSourceTimer instead of Timer.scheduledTimer
        // DispatchSourceTimer works in background because it runs on a DispatchQueue,
        // not on the RunLoop (which is suspended in background)
        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .utility))
        timer.schedule(deadline: .now() + pingInterval, repeating: pingInterval)
        timer.setEventHandler { [weak self] in
            self?.sendPing()
        }
        timer.resume()
        pingTimer = timer
        
        print("[AudioTriggerNative-iOS] 🏓 Ping timer started with DispatchSourceTimer (interval: \(pingInterval)s) - works in background")
    }
    
    private func stopPingTimer() {
        pingTimer?.cancel()
        pingTimer = nil
        print("[AudioTriggerNative-iOS] ⏹️ Ping timer stopped")
    }
    
    private func refreshAccessToken(completion: @escaping (Bool) -> Void) {
        print("[AudioTriggerNative-iOS] 🔄 refreshAccessToken() called")

        guard let refresh = refreshToken else {
            print("[AudioTriggerNative-iOS] ❌ No refresh token available")
            completion(false)
            return
        }

        print("[AudioTriggerNative-iOS] 🔑 Have refresh token: [REDACTED]")

        guard let apiUrl = getApiUrl() else {
            print("[AudioTriggerNative-iOS] ❌ Cannot refresh token: API URL not configured")
            completion(false)
            return
        }

        print("[AudioTriggerNative-iOS] 🌐 API URL: \(apiUrl)")

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

        print("[AudioTriggerNative-iOS] 📤 Sending refresh request...")
        
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
        
        // FIX: Use statusSession (not URLSession.shared) so the request survives background suspension
        statusSession.dataTask(with: request) { [weak self] data, response, error in
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
            
            print("[AudioTriggerNative-iOS] 📥 Refresh response status: \(httpResponse.statusCode)")

            if httpResponse.statusCode == 200 {
                // Parse response to get new tokens
                guard let data = data else {
                    print("[AudioTriggerNative-iOS] ❌ No data in refresh response")
                    completion(false)
                    return
                }

                do {
                    if let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] {
                        print("[AudioTriggerNative-iOS] 🔍 Response keys: \(json.keys)")

                        if let newAccessToken = json["access_token"] as? String,
                           let newRefreshToken = json["refresh_token"] as? String {

                            print("[AudioTriggerNative-iOS] 🔑 Got new access_token and refresh_token")

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
                            print("[AudioTriggerNative-iOS] ❌ Invalid refresh response format - missing access_token or refresh_token")
                            print("[AudioTriggerNative-iOS] ❌ Has access_token: \(json["access_token"] != nil)")
                            print("[AudioTriggerNative-iOS] ❌ Has refresh_token: \(json["refresh_token"] != nil)")
                            completion(false)
                        }
                    } else {
                        print("[AudioTriggerNative-iOS] ❌ Response is not a JSON object")
                        completion(false)
                    }
                } catch {
                    print("[AudioTriggerNative-iOS] ❌ Failed to parse refresh response: \(error)")
                    completion(false)
                }
            } else {
                print("[AudioTriggerNative-iOS] ❌ Refresh token failed with status \(httpResponse.statusCode)")

                // Log response body for debugging failures
                if let data = data, let responseBody = String(data: data, encoding: .utf8) {
                    print("[AudioTriggerNative-iOS] ❌ Error response: \(responseBody)")
                }

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
        let deviceId = getOrCreateDeviceId()
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
        // IMPORTANT: for backend semantics, "is_monitoring" must represent scheduled-window state,
        // not just local engine active state.
        let withinPeriod = isWithinMonitoringPeriod()
        let isMonitoringForServer = isMonitoring && withinPeriod
        var payload: [String: Any] = [
            "action": "pingMobile",
            "session_token": token,
            "email_usuario": email,
            "device_id": deviceId,
            "is_recording": isRecording,
            "is_monitoring": isMonitoringForServer,
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

        // Include GPS data in ping payload when available (server spec alignment)
        if let location = currentLocation {
            payload["latitude"] = location.coordinate.latitude
            payload["longitude"] = location.coordinate.longitude
            let gpsTs = ISO8601DateFormatter().string(from: location.timestamp)
            payload["timestamp_gps"] = gpsTs
            payload["location_timestamp"] = gpsTs

            if location.horizontalAccuracy >= 0 {
                payload["precisao_metros"] = location.horizontalAccuracy
                payload["location_accuracy"] = location.horizontalAccuracy
            }
            if location.speed >= 0 {
                payload["speed"] = location.speed
            }
            if location.course >= 0 {
                payload["heading"] = Int(location.course)
            }
        }
        
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
            if let body = request.httpBody {
                print("[AudioTriggerNative-iOS] 🏓 pingMobile request body: \(redactSensitiveJsonString(from: body))")
            }
        } catch {
            print("[AudioTriggerNative-iOS] ❌ Failed to serialize ping payload: \(error)")
            return
        }
        
        // FIX: Use statusSession (not URLSession.shared) so the ping survives background suspension
        let task = statusSession.dataTask(with: request) { [weak self] data, response, error in
            guard let self = self else { return }
            
            if let error = error {
                print("[AudioTriggerNative-iOS] ❌ Ping failed: \(error.localizedDescription)")
                return
            }
            
            if let httpResponse = response as? HTTPURLResponse {
                if httpResponse.statusCode == 200 {
                    self.lastPingTime = Date()
                    let monitoringForServer = self.isMonitoring && self.isWithinMonitoringPeriod()
                    print("[AudioTriggerNative-iOS] 🏓 Ping sent successfully (recording: \(self.isRecording), monitoring_local: \(self.isMonitoring), monitoring_server: \(monitoringForServer), periods=\(self.monitoringPeriods.count))")
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
                } else if httpResponse.statusCode == 403 {
                    // Keep ping read-only: never force logout/device rotation from ping endpoint.
                    var rawBody = ""
                    var errorCode = ""
                    if let data = data {
                        rawBody = String(data: data, encoding: .utf8) ?? ""
                        if let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                           let code = json["error"] as? String {
                            errorCode = code
                        }
                    }

                    if errorCode.isEmpty {
                        print("[AudioTriggerNative-iOS] ⚠️ Ping returned 403 (soft-fail). Keeping session active.")
                    } else {
                        print("[AudioTriggerNative-iOS] ⚠️ Ping returned 403 (soft-fail), error=\(errorCode). Keeping session active.")
                    }
                    if !rawBody.isEmpty {
                        print("[AudioTriggerNative-iOS] ⚠️ Ping 403 body: \(rawBody)")
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
        
        // Fallback to Supabase URL
        return "https://uogenwcycqykfsuongrl.supabase.co/functions/v1/mobile-api"
    }
    
    private func getOrCreateDeviceId() -> String {
        let keychainKey = "ampara_device_id"

        // Try to get existing device_id from Keychain (survives app reinstalls)
        if let existingId = KeychainHelper.shared.get(keychainKey), !existingId.isEmpty {
            print("[AudioTriggerNative-iOS] 🆔 Using existing device_id from Keychain: \(existingId)")
            return existingId
        }

        // Migrate from UserDefaults if exists (backward compatibility)
        if let legacyId = UserDefaults.standard.string(forKey: "device_id"), !legacyId.isEmpty {
            print("[AudioTriggerNative-iOS] 🔄 Migrating device_id from UserDefaults to Keychain")
            _ = KeychainHelper.shared.set(keychainKey, value: legacyId)
            UserDefaults.standard.removeObject(forKey: "device_id")
            return legacyId
        }

        // Generate new device_id - use random UUID (NOT identifierForVendor)
        // identifierForVendor can change if all vendor apps are uninstalled
        let deviceId = UUID().uuidString

        // Save to Keychain (survives app reinstalls and OS updates)
        let saved = KeychainHelper.shared.set(keychainKey, value: deviceId)

        if saved {
            print("[AudioTriggerNative-iOS] 🆔 Generated new device_id and saved to Keychain: \(deviceId)")
            print("[AudioTriggerNative-iOS] 🆔 This device_id will persist across app reinstalls")
        } else {
            print("[AudioTriggerNative-iOS] ⚠️ Failed to save device_id to Keychain - using in-memory only")
        }

        return deviceId
    }

    @objc func setDeviceId(_ call: CAPPluginCall) {
        guard let newDeviceId = call.getString("deviceId") else {
            call.reject("Missing deviceId parameter")
            return
        }

        let keychainKey = "ampara_device_id"
        let saved = KeychainHelper.shared.set(keychainKey, value: newDeviceId)

        if saved {
            print("[AudioTriggerNative-iOS] 🔄 Device ID replaced in Keychain: \(newDeviceId)")
            call.resolve(["success": true, "deviceId": newDeviceId])
        } else {
            print("[AudioTriggerNative-iOS] ❌ Failed to save device_id to Keychain")
            call.reject("Failed to save device_id to Keychain")
        }
    }
    
    // MARK: - Event Notification
    
    private func notifyEvent(_ event: String, data: [String: Any]) {
        var eventData = data
        eventData["event"] = event
        
        notifyListeners("audioTriggerEvent", data: eventData)
    }

    private func redactSensitiveJsonString(from data: Data) -> String {
        guard var json = (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) else {
            return "invalid"
        }
        if json["session_token"] != nil { json["session_token"] = "[REDACTED]" }
        if json["refresh_token"] != nil { json["refresh_token"] = "[REDACTED]" }
        guard let redactedData = try? JSONSerialization.data(withJSONObject: json),
              let redactedString = String(data: redactedData, encoding: .utf8) else {
            return "invalid"
        }
        return redactedString
    }
    
    // MARK: - GPS Location Timer
    
    private func startGpsTimer(interval: TimeInterval) {
        // Stop existing timer if any
        stopGpsTimer()
        
        // Send immediate location
        sendGpsLocation()
        
        // Use DispatchSourceTimer - works in background
        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .utility))
        timer.schedule(deadline: .now() + interval, repeating: interval)
        timer.setEventHandler { [weak self] in
            self?.sendGpsLocation()
        }
        timer.resume()
        gpsTimer = timer
        
        print("[AudioTriggerNative-iOS] 📍 GPS timer started with DispatchSourceTimer (interval: \(interval)s) - works in background")
    }
    
    private func stopGpsTimer() {
        gpsTimer?.cancel()
        gpsTimer = nil
        print("[AudioTriggerNative-iOS] ⏹️ GPS timer stopped")
    }
    
    // MARK: - Monitoring Period Check Timer
    
    private func startPeriodCheckTimer() {
        // Stop existing timer if any
        stopPeriodCheckTimer()

        // Initialize last period status
        lastPeriodStatus = isWithinMonitoringPeriod()

        // Immediately report current period status to server
        if lastPeriodStatus {
            print("[AudioTriggerNative-iOS] 📡 Initial check: Within monitoring period - reporting janela_iniciada")
            reportMonitoringStatus("janela_iniciada", isMonitoring: true, motivo: "janela_agendada")

            // Start heartbeat timer if within monitoring period
            startHeartbeatTimer()
        } else {
            print("[AudioTriggerNative-iOS] 📡 Initial check: Outside monitoring period - reporting janela_finalizada")
            reportMonitoringStatus("janela_finalizada", isMonitoring: false, motivo: "fora_da_janela")
        }

        // Check every 30 seconds for period changes
        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .utility))
        timer.schedule(deadline: .now() + 30.0, repeating: 30.0)
        timer.setEventHandler { [weak self] in
            self?.checkPeriodChange()
        }
        timer.resume()
        periodCheckTimer = timer

        print("[AudioTriggerNative-iOS] 🕒 Period check timer started (checking every 30s)")
    }
    
    private func stopPeriodCheckTimer() {
        periodCheckTimer?.cancel()
        periodCheckTimer = nil
        print("[AudioTriggerNative-iOS] ⏹️ Period check timer stopped")
    }
    
    private func checkPeriodChange() {
        let currentStatus = isWithinMonitoringPeriod()

        // Check if status changed
        if currentStatus != lastPeriodStatus {
            print("[AudioTriggerNative-iOS] 🔄 Monitoring period status changed: \(lastPeriodStatus) → \(currentStatus)")

            if currentStatus {
                // Entered monitoring period
                print("[AudioTriggerNative-iOS] ▶️ Entered monitoring period")
                reportMonitoringStatus("janela_iniciada", isMonitoring: true, motivo: "janela_agendada")

                // Start heartbeat timer when entering monitoring period
                startHeartbeatTimer()

                // Adjust GPS interval to 1 minute
                if !isRecording {
                    startGpsTimer(interval: gpsIntervalMonitoring)
                }
            } else {
                // Exited monitoring period
                print("[AudioTriggerNative-iOS] ⏹️ Exited monitoring period")
                reportMonitoringStatus("janela_finalizada", isMonitoring: false, motivo: "fora_da_janela")

                // Stop heartbeat timer when exiting monitoring period
                stopHeartbeatTimer()

                // Adjust GPS interval to 30 minutes
                if !isRecording {
                    startGpsTimer(interval: gpsIntervalOutsidePeriod)
                }
            }

            lastPeriodStatus = currentStatus
        }
    }
    
    // MARK: - Heartbeat Timer

    private func startHeartbeatTimer() {
        // Stop existing timer if any
        stopHeartbeatTimer()

        // Create heartbeat timer that sends status reports periodically
        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .utility))
        timer.schedule(deadline: .now() + heartbeatInterval, repeating: heartbeatInterval)
        timer.setEventHandler { [weak self] in
            guard let self = self else { return }

            let currentStatus = self.isWithinMonitoringPeriod()

            // Send heartbeat report even if status hasn't changed
            if currentStatus {
                print("[AudioTriggerNative-iOS] 💓 Heartbeat: Confirming janela_iniciada (inside monitoring period)")
                self.reportMonitoringStatus("janela_iniciada", isMonitoring: true, motivo: "janela_agendada")
            } else {
                print("[AudioTriggerNative-iOS] 💓 Heartbeat: Confirming janela_finalizada (outside monitoring period)")
                self.reportMonitoringStatus("janela_finalizada", isMonitoring: false, motivo: "fora_da_janela")
            }
        }
        timer.resume()
        heartbeatTimer = timer

        print("[AudioTriggerNative-iOS] 💓 Heartbeat timer started (reporting every \(Int(heartbeatInterval))s = \(Int(heartbeatInterval/60)) minutes)")
    }

    private func stopHeartbeatTimer() {
        heartbeatTimer?.cancel()
        heartbeatTimer = nil
        print("[AudioTriggerNative-iOS] 💓 Heartbeat timer stopped")
    }

    // MARK: - Config Sync Timer

    private func startConfigSyncTimer() {
        stopConfigSyncTimer()

        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .utility))
        timer.schedule(deadline: .now() + configSyncInterval, repeating: configSyncInterval)
        timer.setEventHandler { [weak self] in
            self?.syncConfigurationFromServer()
        }
        timer.resume()
        configSyncTimer = timer

        print("[AudioTriggerNative-iOS] 🔄 Config sync timer started (syncing every \(Int(configSyncInterval))s = \(Int(configSyncInterval/60)) minutes)")
    }

    private func stopConfigSyncTimer() {
        configSyncTimer?.cancel()
        configSyncTimer = nil
        print("[AudioTriggerNative-iOS] 🔄 Config sync timer stopped")
    }

    // MARK: - Recording Countdown Timer

    private func startCountdownTimer() {
        stopCountdownTimer()

        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .utility))
        timer.schedule(deadline: .now(), repeating: countdownUpdateInterval)
        timer.setEventHandler { [weak self] in
            self?.updateCountdown()
        }
        timer.resume()
        countdownTimer = timer

        print("[AudioTriggerNative-iOS] ⏱️ Countdown timer started")
    }

    private func stopCountdownTimer() {
        countdownTimer?.cancel()
        countdownTimer = nil
        print("[AudioTriggerNative-iOS] ⏱️ Countdown timer stopped")
    }

    private func updateCountdown() {
        guard isRecording else {
            stopCountdownTimer()
            return
        }

        // Product rule: show countdown only after silence/inactivity is detected.
        guard let silenceTime = countdownSilenceStartTime else {
            notifyListeners("recordingCountdown", data: [
                "remainingSeconds": 0,
                "timeoutType": "none",
                "isRecording": isRecording
            ])
            return
        }

        let silenceElapsed = Date().timeIntervalSince(silenceTime)
        let remainingSeconds = max(0, 70 - silenceElapsed) // 10s confirm + 60s safety

        notifyListeners("recordingCountdown", data: [
            "remainingSeconds": Int(remainingSeconds),
            "timeoutType": "silence",
            "isRecording": isRecording
        ])
    }

    private func syncConfigurationFromServer(isRetry: Bool = false) {
        guard let token = sessionToken, let email = emailUsuario else {
            print("[AudioTriggerNative-iOS] ⚠️ Cannot sync config: missing credentials")
            return
        }

        guard let apiUrl = getApiUrl() else {
            print("[AudioTriggerNative-iOS] ⚠️ Cannot sync config: missing API URL")
            return
        }

        guard let url = URL(string: apiUrl) else {
            print("[AudioTriggerNative-iOS] ❌ Invalid API URL: \(apiUrl)")
            return
        }

        let deviceId = getOrCreateDeviceId()

        let payload: [String: Any] = [
            "action": "syncConfigMobile",
            "device_id": deviceId,
            "session_token": token,
            "email_usuario": email
        ]

        guard let jsonData = try? JSONSerialization.data(withJSONObject: payload) else {
            print("[AudioTriggerNative-iOS] ❌ Failed to serialize config sync payload")
            return
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = jsonData

        print("[AudioTriggerNative-iOS] 🔄 Syncing configuration from server...")
        print("[AudioTriggerNative-iOS] 🔄 syncConfigMobile request url: \(url.absoluteString)")
        print("[AudioTriggerNative-iOS] 🔄 syncConfigMobile request body: \(redactSensitiveJsonString(from: jsonData))")

        // FIX: Use statusSession (not URLSession.shared) so the config sync survives background suspension
        statusSession.dataTask(with: request) { [weak self] data, response, error in
            if let error = error {
                print("[AudioTriggerNative-iOS] ❌ Config sync failed: \(error.localizedDescription)")
                return
            }

            if let httpResponse = response as? HTTPURLResponse {
                print("[AudioTriggerNative-iOS] 📊 Config sync HTTP Status: \(httpResponse.statusCode)")
                if let data = data, let rawBody = String(data: data, encoding: .utf8) {
                    print("[AudioTriggerNative-iOS] 📊 Config sync raw body: \(rawBody)")
                }

                if httpResponse.statusCode == 200, let data = data {
                    do {
                        if let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] {
                            // FIX: Store full week schedule so isWithinMonitoringPeriod() stays
                            // correct after midnight without needing a JS push or app restart.
                            if let periodosSemanaDict = json["periodos_semana"] as? [String: Any] {
                                self?.applyPeriodosSemana(periodosSemanaDict, source: "syncConfigurationFromServer")
                            } else if let monitoramento = json["monitoramento"] as? [String: Any],
                                      let periodosSemanaDict = monitoramento["periodos_semana"] as? [String: Any] {
                                self?.applyPeriodosSemana(periodosSemanaDict, source: "syncConfigurationFromServer(monitoramento)")
                            } else if let periodsArray = self?.extractMonitoringPeriodsFromSyncResponse(json),
                                      !periodsArray.isEmpty {
                                // Fallback: only today's periods available
                                self?.applyMonitoringPeriods(periodsArray, source: "syncConfigurationFromServer")
                            }

                            var configEventPayload: [String: Any] = [:]
                            if let configuracoes = json["configuracoes"] as? [String: Any] {
                                configEventPayload["configuracoes"] = configuracoes
                            }
                            if let periodosHoje = json["periodos_hoje"] as? [Any] {
                                configEventPayload["periodos_hoje"] = periodosHoje
                            }
                            if let periodosSemana = json["periodos_semana"] as? [String: Any] {
                                configEventPayload["periodos_semana"] = periodosSemana
                            } else if let monitoramento = json["monitoramento"] as? [String: Any],
                                      let periodosSemana = monitoramento["periodos_semana"] as? [String: Any] {
                                configEventPayload["periodos_semana"] = periodosSemana
                            }
                            if let dentroHorario = json["dentro_horario"] as? Bool {
                                configEventPayload["dentro_horario"] = dentroHorario
                            }

                            // Notify JavaScript with full sync payload fields relevant for UI config refresh.
                            self?.notifyEvent("configUpdated", data: configEventPayload)
                            print("[AudioTriggerNative-iOS] ✅ Configuration synced successfully")
                        }
                    } catch {
                        print("[AudioTriggerNative-iOS] ❌ Failed to parse config response: \(error)")
                    }
                } else if httpResponse.statusCode == 401 {
                    print("[AudioTriggerNative-iOS] 🔒 Token expired (401) - attempting refresh")

                    // Only try to refresh if this is not already a retry
                    if !isRetry {
                        self?.refreshAccessToken { success in
                            if success {
                                print("[AudioTriggerNative-iOS] ✅ Token refreshed - retrying config sync")
                                self?.syncConfigurationFromServer(isRetry: true)
                            } else {
                                print("[AudioTriggerNative-iOS] ❌ Token refresh failed for config sync")
                            }
                        }
                    } else {
                        print("[AudioTriggerNative-iOS] ❌ Already retried - stopping to prevent infinite loop")
                    }
                } else {
                    print("[AudioTriggerNative-iOS] ❌ Config sync failed with status: \(httpResponse.statusCode)")
                }
            }
        }.resume()
    }

    private func extractMonitoringPeriodsFromSyncResponse(_ json: [String: Any]) -> [Any]? {
        if let today = json["periodos_hoje"] as? [Any], !today.isEmpty {
            return today
        }

        if let cfg = json["configuracoes"] as? [String: Any],
           let today = cfg["periodos_hoje"] as? [Any],
           !today.isEmpty {
            return today
        }

        let weekdayKeys = ["dom", "seg", "ter", "qua", "qui", "sex", "sab"]
        let todayKey = weekdayKeys[Calendar.current.component(.weekday, from: Date()) - 1]

        if let week = json["periodos_semana"] as? [String: Any],
           let periods = week[todayKey] as? [Any],
           !periods.isEmpty {
            return periods
        }

        if let cfg = json["configuracoes"] as? [String: Any],
           let week = cfg["periodos_semana"] as? [String: Any],
           let periods = week[todayKey] as? [Any],
           !periods.isEmpty {
            return periods
        }

        return nil
    }

    // Called by AudioSegmentUploader when GPS location is updated
    func updateLocation(_ location: CLLocation) {
        currentLocation = location
        print("[AudioTriggerNative-iOS] 📍 GPS location received from uploader: lat=\(location.coordinate.latitude), lon=\(location.coordinate.longitude)")
    }
    
    private func sendGpsLocation(isRetry: Bool = false, isRecoveryRetry: Bool = false) {
        print("[AudioTriggerNative-iOS] 📍🔄 sendGpsLocation() called - isMonitoring: \(isMonitoring), isRecording: \(isRecording)")

        if let blockedUntil = gpsMismatchBlockedUntil, Date() < blockedUntil {
            let remaining = Int(blockedUntil.timeIntervalSinceNow)
            print("[AudioTriggerNative-iOS] ⏭️ GPS send temporarily blocked after mismatch (\(max(remaining, 0))s remaining)")
            return
        }

        // Avoid sending stale GPS after monitoring/recording has been stopped.
        if !isMonitoring && !isRecording {
            print("[AudioTriggerNative-iOS] ⏭️ Skipping GPS send: monitoring and recording are both inactive")
            return
        }
        
        guard let email = emailUsuario else {
            print("[AudioTriggerNative-iOS] ⚠️ Cannot send GPS: missing email")
            return
        }
        
        // Get current location
        guard let location = currentLocation else {
            print("[AudioTriggerNative-iOS] ⚠️ Cannot send GPS: no location available (locationManager may not have updated yet)")
            return
        }
        
        print("[AudioTriggerNative-iOS] 📍✅ GPS available - preparing to send to server")
        
        // Get device ID
        let deviceId = getOrCreateDeviceId()
        
        // Get battery info
        let device = UIDevice.current
        device.isBatteryMonitoringEnabled = true
        let batteryLevel = Int(device.batteryLevel * 100)
        
        // Build payload (conforme especificação do backend)
        var payload: [String: Any] = [
            "action": "enviarLocalizacaoGPS",
            "email_usuario": email,
            "latitude": location.coordinate.latitude,
            "longitude": location.coordinate.longitude,
            "device_id": deviceId,
            "timestamp_gps": ISO8601DateFormatter().string(from: location.timestamp)
        ]
        
        // Add alerta_id if panic is active
        if panicManager.isPanicActive, let protocolNumber = panicManager.protocolNumber {
            payload["alerta_id"] = protocolNumber
            print("[AudioTriggerNative-iOS] 📍🚨 GPS during panic - including alerta_id: \(protocolNumber)")
        }
        
        // Add optional fields
        if location.horizontalAccuracy >= 0 {
            payload["precisao_metros"] = location.horizontalAccuracy
        }
        
        if batteryLevel >= 0 {
            payload["bateria_percentual"] = batteryLevel
        }
        
        if location.speed >= 0 {
            payload["speed"] = location.speed
        }
        
        if location.course >= 0 {
            payload["heading"] = Int(location.course)
        }
        
        // Get API URL from config
        guard let apiUrl = getApiUrl() else {
            print("[AudioTriggerNative-iOS] ⚠️ Cannot send GPS: API URL not configured")
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
            print("[AudioTriggerNative-iOS] ❌ Failed to serialize GPS payload: \(error)")
            return
        }
        
        print("[AudioTriggerNative-iOS] 📍🚀 Sending GPS to server: lat=\(location.coordinate.latitude), lon=\(location.coordinate.longitude), accuracy=\(location.horizontalAccuracy)m, battery=\(batteryLevel)%")
        
        // FIX: Use statusSession (not URLSession.shared) so the GPS update survives background suspension
        let task = statusSession.dataTask(with: request) { data, response, error in
            if let error = error {
                print("[AudioTriggerNative-iOS] ❌ GPS send failed: \(error.localizedDescription)")
                return
            }
            
            if let httpResponse = response as? HTTPURLResponse {
                if httpResponse.statusCode == 200 {
                    // GPS location sent successfully (log removed to avoid console spam)
                    self.gpsNoDeviceRecoveryAttempts = 0
                } else if httpResponse.statusCode == 401 {
                    print("[AudioTriggerNative-iOS] 🔒 Token expired (401) - attempting refresh")

                    // Only try to refresh if this is not already a retry
                    if !isRetry {
                        self.refreshAccessToken { success in
                            if success {
                                print("[AudioTriggerNative-iOS] ✅ Token refreshed - retrying GPS location send")
                                self.sendGpsLocation(isRetry: true, isRecoveryRetry: isRecoveryRetry)
                            } else {
                                print("[AudioTriggerNative-iOS] ❌ Token refresh failed for GPS send")
                            }
                        }
                    } else {
                        print("[AudioTriggerNative-iOS] ❌ Already retried - stopping to prevent infinite loop")
                    }
                } else if httpResponse.statusCode == 403 {
                    // Device mismatch or permission error
                    print("[AudioTriggerNative-iOS] 🚫 GPS rejected (403)")

                    // Parse message if backend returned one
                    var errorMessage = "forbidden"
                    var rawResponseBody = ""
                    if let data = data, let bodyString = String(data: data, encoding: .utf8) {
                        rawResponseBody = bodyString
                    }
                    if let data = data,
                       let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                        if let message = json["message"] as? String {
                            errorMessage = message
                        } else if let error = json["error"] as? String {
                            errorMessage = error
                        }
                    }

                    let normalized = errorMessage.lowercased()
                    print("[AudioTriggerNative-iOS] 🚫 GPS 403 details: \(rawResponseBody.isEmpty ? errorMessage : rawResponseBody)")
                    let isDeviceMismatch = normalized.contains("device_mismatch")
                        || normalized.contains("device mismatch")
                        || normalized.contains("no_device_registered")
                        || normalized.contains("dispositivo_nao_registrado")
                        || normalized.contains("device not registered")

                    if isDeviceMismatch {
                        let isNoDeviceRegistered = normalized.contains("no_device_registered")
                            || normalized.contains("device not registered")
                            || normalized.contains("dispositivo_nao_registrado")

                        if isNoDeviceRegistered && !isRecoveryRetry {
                            if self.gpsNoDeviceRecoveryInProgress {
                                print("[AudioTriggerNative-iOS] ⚠️ GPS recovery already in progress - skipping duplicate recovery")
                                return
                            }
                            if self.gpsNoDeviceRecoveryAttempts >= self.maxGpsNoDeviceRecoveryAttempts {
                                self.gpsMismatchBlockedUntil = Date().addingTimeInterval(180)
                                print("[AudioTriggerNative-iOS] ⚠️ GPS NO_DEVICE_REGISTERED recovery limit reached - keeping session and blocking GPS for 180s")
                                return
                            }

                            self.gpsNoDeviceRecoveryInProgress = true
                            self.gpsNoDeviceRecoveryAttempts += 1
                            print("[AudioTriggerNative-iOS] 🔄 Attempting one-time NO_DEVICE_REGISTERED recovery (\(self.gpsNoDeviceRecoveryAttempts)/\(self.maxGpsNoDeviceRecoveryAttempts))")
                            self.attemptDeviceRegistrationRecovery { recovered in
                                self.gpsNoDeviceRecoveryInProgress = false
                                if recovered {
                                    print("[AudioTriggerNative-iOS] ✅ Recovery succeeded - retrying GPS send")
                                    self.sendGpsLocation(isRetry: false, isRecoveryRetry: true)
                                } else {
                                    self.gpsMismatchBlockedUntil = Date().addingTimeInterval(180)
                                    print("[AudioTriggerNative-iOS] ⚠️ Recovery failed - keeping session and blocking GPS for 180s")
                                }
                            }
                        } else {
                            // GPS mismatch alone has shown backend inconsistency while session is still valid.
                            // Keep user logged in and temporarily suppress GPS sends to avoid logout loops.
                            self.gpsMismatchBlockedUntil = Date().addingTimeInterval(180)
                            self.gpsNoDeviceRecoveryAttempts = self.maxGpsNoDeviceRecoveryAttempts
                            print("[AudioTriggerNative-iOS] ⚠️ DEVICE_MISMATCH came only from GPS - keeping session and blocking GPS for 180s")
                        }
                    } else {
                        print("[AudioTriggerNative-iOS] ⚠️ GPS returned 403 (no device mismatch marker): \(errorMessage)")
                    }
                } else {
                    print("[AudioTriggerNative-iOS] ⚠️ GPS send returned status \(httpResponse.statusCode)")
                }
            }
        }
        task.resume()
    }

    private func attemptDeviceRegistrationRecovery(completion: @escaping (Bool) -> Void) {
        guard let token = sessionToken, let email = emailUsuario else {
            print("[AudioTriggerNative-iOS] ❌ Recovery aborted: missing credentials")
            completion(false)
            return
        }

        guard let apiUrl = getApiUrl(), let url = URL(string: apiUrl) else {
            print("[AudioTriggerNative-iOS] ❌ Recovery aborted: missing/invalid API URL")
            completion(false)
            return
        }

        // Re-sync device_id between Keychain and UserDefaults used by other plugins.
        let deviceId = getOrCreateDeviceId()
        UserDefaults.standard.set(deviceId, forKey: "ampara_device_id")
        UserDefaults.standard.set(deviceId, forKey: "device_id")

        let device = UIDevice.current
        device.isBatteryMonitoringEnabled = true
        let batteryLevel = Int(device.batteryLevel * 100)
        let isCharging = device.batteryState == .charging || device.batteryState == .full
        let appVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "unknown"
        let timezone = TimeZone.current.identifier
        let timezoneOffset = TimeZone.current.secondsFromGMT() / 60
        let withinPeriod = isWithinMonitoringPeriod()
        let isMonitoringForServer = isMonitoring && withinPeriod

        var payload: [String: Any] = [
            "action": "pingMobile",
            "session_token": token,
            "email_usuario": email,
            "device_id": deviceId,
            "is_recording": isRecording,
            "is_monitoring": isMonitoringForServer,
            "timezone": timezone,
            "timezone_offset_minutes": timezoneOffset,
            "is_charging": isCharging,
            "dispositivo_info": device.name,
            "versao_app": appVersion
        ]

        if batteryLevel >= 0 {
            payload["bateria_percentual"] = batteryLevel
        }

        if let location = currentLocation {
            payload["latitude"] = location.coordinate.latitude
            payload["longitude"] = location.coordinate.longitude
            let gpsTs = ISO8601DateFormatter().string(from: location.timestamp)
            payload["timestamp_gps"] = gpsTs
            payload["location_timestamp"] = gpsTs

            if location.horizontalAccuracy >= 0 {
                payload["precisao_metros"] = location.horizontalAccuracy
                payload["location_accuracy"] = location.horizontalAccuracy
            }
            if location.speed >= 0 {
                payload["speed"] = location.speed
            }
            if location.course >= 0 {
                payload["heading"] = Int(location.course)
            }
        }

        guard let jsonData = try? JSONSerialization.data(withJSONObject: payload) else {
            print("[AudioTriggerNative-iOS] ❌ Recovery aborted: failed to serialize payload")
            completion(false)
            return
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = jsonData

        print("[AudioTriggerNative-iOS] 🧰 Recovery pingMobile request body: \(redactSensitiveJsonString(from: jsonData))")

        // FIX: Use statusSession (not URLSession.shared) so the recovery ping survives background suspension
        statusSession.dataTask(with: request) { [weak self] data, response, error in
            guard let self = self else {
                completion(false)
                return
            }

            if let error = error {
                print("[AudioTriggerNative-iOS] ❌ Recovery ping failed: \(error.localizedDescription)")
                completion(false)
                return
            }

            guard let httpResponse = response as? HTTPURLResponse else {
                print("[AudioTriggerNative-iOS] ❌ Recovery ping failed: invalid HTTP response")
                completion(false)
                return
            }

            if httpResponse.statusCode == 200 {
                // Trigger config sync to let backend refresh device bindings, then continue.
                self.syncConfigurationFromServer()
                completion(true)
                return
            }

            print("[AudioTriggerNative-iOS] ❌ Recovery ping returned status \(httpResponse.statusCode)")
            if let data = data, let responseBody = String(data: data, encoding: .utf8) {
                print("[AudioTriggerNative-iOS] ❌ Recovery ping response: \(responseBody)")
            }
            completion(false)
        }.resume()
    }
    
    private func setupLocationManager() {
        // MUST be called on main thread
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            
            self.locationManager = CLLocationManager()
            self.locationManager?.desiredAccuracy = kCLLocationAccuracyBest
            self.locationManager?.distanceFilter = kCLDistanceFilterNone // Update continuously (no distance threshold)
            self.locationManager?.delegate = self
            
            // Enable background location updates
            self.locationManager?.allowsBackgroundLocationUpdates = true
            self.locationManager?.pausesLocationUpdatesAutomatically = false
            self.locationManager?.showsBackgroundLocationIndicator = true
            
            // Request location permission
            let authStatus = self.locationManager?.authorizationStatus ?? .notDetermined
            print("[AudioTriggerNative-iOS] 📍 Current GPS authorization status: \(authStatus.rawValue)")
            
            if authStatus == .notDetermined {
                self.locationManager?.requestAlwaysAuthorization()
                print("[AudioTriggerNative-iOS] 📍 Requesting GPS permission (Always)")
            } else if authStatus == .authorizedAlways {
                print("[AudioTriggerNative-iOS] ✅ GPS permission already granted (Always)")
            } else if authStatus == .authorizedWhenInUse {
                print("[AudioTriggerNative-iOS] ⚠️ GPS permission is only When In Use - lock-screen background GPS is not guaranteed")
            } else {
                print("[AudioTriggerNative-iOS] ❌ GPS permission denied or restricted")
            }
            
            // Start monitoring location only when Always authorization is granted
            if authStatus == .authorizedAlways {
                self.locationManager?.startUpdatingLocation()
                
                // Also monitor significant location changes to keep GPS active in background
                // This prevents iOS from suspending location updates
                if CLLocationManager.significantLocationChangeMonitoringAvailable() {
                    self.locationManager?.startMonitoringSignificantLocationChanges()
                    print("[AudioTriggerNative-iOS] 📍 GPS started with background updates + significant changes monitoring")
                } else {
                    print("[AudioTriggerNative-iOS] 📍 GPS started with background updates enabled")
                }
            } else {
                print("[AudioTriggerNative-iOS] ⚠️ Background GPS not started (requires Always authorization + enabled location services)")
            }
        }
    }
}

// MARK: - CLLocationManagerDelegate

extension AudioTriggerNativePlugin: CLLocationManagerDelegate {
    public func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        // GPS logs removed to avoid console spam - GPS still works silently
        if let location = locations.last {
            currentLocation = location
        } else {
            print("[AudioTriggerNative-iOS] ⚠️ didUpdateLocations called but locations array is empty")
        }
    }
    
    public func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        print("[AudioTriggerNative-iOS] ❌ GPS error: \(error.localizedDescription)")
        print("[AudioTriggerNative-iOS] ❌ Error code: \((error as NSError).code)")
    }
    
    public func locationManager(_ manager: CLLocationManager, didChangeAuthorization status: CLAuthorizationStatus) {
        print("[AudioTriggerNative-iOS] 🔐 GPS authorization changed: \(status.rawValue)")
        switch status {
        case .notDetermined:
            print("[AudioTriggerNative-iOS] 🔐 Status: Not Determined")
        case .restricted:
            print("[AudioTriggerNative-iOS] 🔐 Status: Restricted")
        case .denied:
            print("[AudioTriggerNative-iOS] 🔐 Status: Denied")
        case .authorizedAlways:
            print("[AudioTriggerNative-iOS] 🔐 Status: Authorized Always")
            // Restart location updates if authorized
            locationManager?.startUpdatingLocation()
            print("[AudioTriggerNative-iOS] 📍 Restarted GPS updates after authorization")
        case .authorizedWhenInUse:
            print("[AudioTriggerNative-iOS] ⚠️ Status: Authorized When In Use - lock-screen background GPS is not guaranteed")
        @unknown default:
            print("[AudioTriggerNative-iOS] 🔐 Status: Unknown (\(status.rawValue))")
        }
    }
}
