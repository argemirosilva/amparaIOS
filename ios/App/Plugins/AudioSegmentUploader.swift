import Foundation
import AVFoundation
import CoreLocation

/**
 * AudioSegmentUploader - Handles audio segment recording and upload
 * 
 * Features:
 * - Records audio to M4A files (AAC codec)
 * - Converts M4A to MP3 using LAME encoder (via ExtAudioConverter)
 * - Uploads segments to server every 30 seconds
 * - Includes timezone in requests
 * - Automatic cleanup of uploaded files
 */

class AudioSegmentUploader: NSObject {
    private struct PendingUpload: Codable {
        let filePath: String
        let segmentIndex: Int
        let duration: Int
    }
    
    // MARK: - Properties
    
    private var audioFile: AVAudioFile?
    private var audioFileURL: URL?
    private var segmentIndex = 1
    private var sessionId: String
    private var sessionToken: String
    private var emailUsuario: String
    private var origemGravacao: String
    
    private let segmentDuration: TimeInterval = 30.0
    private let minSegmentBytesForUpload: Int64 = 1024
    private let minFirstSegmentDurationSeconds: TimeInterval = 15.0
    private let uploadTimeoutSeconds: TimeInterval = 30.0
    private let maxUploadRetries = 3
    private var segmentStartTime: Date?
    
    // Location manager for GPS
    private var locationManager: CLLocationManager?
    private var currentLocation: CLLocation?
    
    // Reference to plugin to share GPS location
    weak var plugin: AudioTriggerNativePlugin?
    private let uploadStateQueue = DispatchQueue(label: "tech.orizon.ampara.segment-upload.state")
    private var uploadCompletions: [Int: (Bool) -> Void] = [:]
    private var uploadTempFiles: [Int: URL] = [:]
    private var uploadResponseData: [Int: Data] = [:]
    private var gpsUpdatesStarted = false
    private let pendingQueueKey = "ampara_pending_upload_queue_v1"
    private var pendingUploads: [PendingUpload] = []
    private var isProcessingPendingQueue = false

    // Use a background session so uploads can continue with app in background/locked state
    private lazy var uploadSession: URLSession = {
        let config = URLSessionConfiguration.background(withIdentifier: "tech.orizon.ampara.segment-upload.\(UUID().uuidString)")
        config.isDiscretionary = false
        config.waitsForConnectivity = true
        config.sessionSendsLaunchEvents = true
        config.allowsCellularAccess = true
        config.allowsExpensiveNetworkAccess = true
        config.allowsConstrainedNetworkAccess = true
        config.timeoutIntervalForRequest = uploadTimeoutSeconds
        config.timeoutIntervalForResource = uploadTimeoutSeconds
        return URLSession(configuration: config, delegate: self, delegateQueue: nil)
    }()
    
    // MARK: - Initialization
    
    init(sessionId: String, sessionToken: String, emailUsuario: String, origemGravacao: String) {
        self.sessionId = sessionId
        self.sessionToken = sessionToken
        self.emailUsuario = emailUsuario
        self.origemGravacao = origemGravacao
        super.init()
        loadPendingUploads()
        processPendingUploadsIfNeeded()
        
        // Setup location manager (MUST be on main thread for CLLocationManager)
        DispatchQueue.main.async { [weak self] in
            self?.setupLocationManager()
        }
    }
    
    private func setupLocationManager() {
        print("[AudioSegmentUploader] 🔧 setupLocationManager() called")
        
        locationManager = CLLocationManager()
        locationManager?.desiredAccuracy = kCLLocationAccuracyBest
        locationManager?.distanceFilter = 10 // Update every 10 meters
        locationManager?.delegate = self
        
        print("[AudioSegmentUploader] 🔧 LocationManager created, delegate set")
        
        // Enable background location updates (CRITICAL for long-term background operation)
        locationManager?.allowsBackgroundLocationUpdates = true
        locationManager?.pausesLocationUpdatesAutomatically = false
        locationManager?.showsBackgroundLocationIndicator = true // Show blue bar when using location
        
        print("[AudioSegmentUploader] 🔧 Background location updates enabled")
        
        // Request location permission (prefer instance status to avoid main-thread warning)
        let authStatus = locationManager?.authorizationStatus ?? .notDetermined
        print("[AudioSegmentUploader] 🔐 Current GPS authorization status: \(authStatus.rawValue)")
        
        switch authStatus {
        case .notDetermined:
            print("[AudioSegmentUploader] 🔐 Status: Not Determined - requesting Always authorization")
            print("[AudioSegmentUploader] 🔐 Will poll authorization status every 1s for 10s")
            locationManager?.requestAlwaysAuthorization()
            // Start polling to check if authorization changed (iOS sometimes doesn't call didChangeAuthorization)
            startAuthorizationPolling()
            return
        case .restricted:
            print("[AudioSegmentUploader] 🔐 Status: Restricted - cannot use GPS")
            return
        case .denied:
            print("[AudioSegmentUploader] 🔐 Status: Denied - cannot use GPS")
            return
        case .authorizedAlways:
            print("[AudioSegmentUploader] 🔐 Status: Authorized Always - starting GPS")
        case .authorizedWhenInUse:
            print("[AudioSegmentUploader] ⚠️ Status: Authorized When In Use - background GPS is not guaranteed on lock screen")
            return
        @unknown default:
            print("[AudioSegmentUploader] 🔐 Status: Unknown (\(authStatus.rawValue))")
            return
        }
        
        // Start monitoring location (only if authorized)
        startGPSUpdates()
    }
    
    private func startGPSUpdates() {
        if gpsUpdatesStarted {
            print("[AudioSegmentUploader] 📍 GPS updates already active")
            return
        }

        print("[AudioSegmentUploader] 📍 Starting GPS updates...")
        
        // Request immediate location first
        locationManager?.requestLocation()
        print("[AudioSegmentUploader] 📍 Requesting immediate GPS location")
        
        // Then start continuous updates
        locationManager?.startUpdatingLocation()
        gpsUpdatesStarted = true
        print("[AudioSegmentUploader] 📍 GPS continuous updates enabled")
    }
    
    private var authPollingCount = 0
    private var authPollingTimer: Timer?
    
    private func startAuthorizationPolling() {
        authPollingCount = 0
        authPollingTimer?.invalidate()
        
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            
            self.authPollingTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] timer in
                guard let self = self else {
                    timer.invalidate()
                    return
                }
                
                self.authPollingCount += 1
                let currentStatus = self.locationManager?.authorizationStatus ?? .notDetermined
                
                print("[AudioSegmentUploader] 🔍 Polling authorization status (\(self.authPollingCount)/30): \(currentStatus.rawValue)")
                
                // Check if authorization changed from notDetermined
                if currentStatus != .notDetermined {
                    print("[AudioSegmentUploader] 🔍 Authorization changed detected! Status: \(currentStatus.rawValue)")
                    timer.invalidate()
                    self.authPollingTimer = nil
                    
                    // Manually trigger what didChangeAuthorization should have done
                    if currentStatus == .authorizedAlways {
                        print("[AudioSegmentUploader] 🔍 Starting GPS after authorization")
                        self.startGPSUpdates()
                    } else {
                        print("[AudioSegmentUploader] 🔍 Background GPS requires 'Always' authorization")
                    }
                    return
                }
                
                // Stop polling after 30 attempts (30 seconds - user may take time to respond)
                if self.authPollingCount >= 30 {
                    print("[AudioSegmentUploader] 🔍 Polling timeout - authorization still not determined")
                    timer.invalidate()
                    self.authPollingTimer = nil
                }
            }
        }
    }
    
    // MARK: - Recording
    
    func startNewSegment(format: AVAudioFormat) throws {
        // Create temporary file for segment
        let tempDir = FileManager.default.temporaryDirectory
        let fileName = "segment_\(segmentIndex).m4a"
        audioFileURL = tempDir.appendingPathComponent(fileName)
        
        guard let url = audioFileURL else {
            throw NSError(domain: "AudioSegmentUploader", code: -1, userInfo: [NSLocalizedDescriptionKey: "Failed to create file URL"])
        }
        
        // Delete existing file if any
        try? FileManager.default.removeItem(at: url)
        
        // Create audio file
        audioFile = try AVAudioFile(forWriting: url, settings: format.settings)
        segmentStartTime = Date()
        
        print("[AudioSegmentUploader] 📝 Started new segment \(segmentIndex): \(fileName)")
    }
    
    func writeBuffer(_ buffer: AVAudioPCMBuffer) throws {
        try audioFile?.write(from: buffer)
    }
    
    func finishSegment(completion: @escaping (Bool) -> Void) {
        guard let url = audioFileURL else {
            completion(false)
            return
        }
        
        // Close audio file and wait for file system to finalize
        audioFile = nil
        
        // Calculate duration
        let duration = segmentStartTime.map { Date().timeIntervalSince($0) } ?? segmentDuration
        
        // print("[AudioSegmentUploader] 📤 Uploading segment \(segmentIndex), duration: \(Int(duration))s")
        
        // Wait 200ms for file system to finalize M4A file
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { [weak self] in
            guard let self = self else { return }

            if self.segmentIndex == 1 && duration < self.minFirstSegmentDurationSeconds {
                print("[AudioSegmentUploader] ⚠️ First segment duration \(Int(duration))s < \(Int(self.minFirstSegmentDurationSeconds))s - marking as noise and skipping upload")
                try? FileManager.default.removeItem(at: url)
                self.segmentIndex += 1
                completion(true)
                return
            }
            
            // Validate M4A file before conversion
            guard FileManager.default.fileExists(atPath: url.path) else {
                print("[AudioSegmentUploader] ❌ M4A file not found: \(url.path)")
                completion(false)
                return
            }
            
            // Check file size
            if let attributes = try? FileManager.default.attributesOfItem(atPath: url.path),
               let fileSize = attributes[.size] as? Int64 {
                print("[AudioSegmentUploader] 📊 Input file size: \(fileSize) bytes")
                
                if fileSize == 0 {
                    print("[AudioSegmentUploader] ❌ M4A file is empty")
                    completion(false)
                    return
                }

                if fileSize < self.minSegmentBytesForUpload {
                    print("\u{001B}[1;31m[AudioSegmentUploader] 🚨 SEGMENT SKIPPED: too short (\(fileSize) bytes) < \(self.minSegmentBytesForUpload). Upload nao sera chamado.\u{001B}[0m")
                    try? FileManager.default.removeItem(at: url)
                    self.segmentIndex += 1
                    completion(true)
                    return
                }
            }
            
            // Convert M4A to MP3 before upload (API currently accepts MP3/Ogg only).
            let mp3URL = FileManager.default.temporaryDirectory.appendingPathComponent("segment_\(self.segmentIndex).mp3")
            self.convertM4AtoMP3(inputURL: url, outputURL: mp3URL) { convertSuccess in
                guard convertSuccess else {
                    print("[AudioSegmentUploader] ❌ Failed to convert segment \(self.segmentIndex) to MP3")
                    completion(false)
                    return
                }

                print("[AudioSegmentUploader] ✅ Converted segment \(self.segmentIndex) to MP3")

                let uploadURL = self.preparePersistentPendingFile(from: mp3URL, segmentIndex: self.segmentIndex) ?? mp3URL
                self.uploadSegmentWithRetry(fileURL: uploadURL, segmentIndex: self.segmentIndex, duration: Int(duration), retriesLeft: self.maxUploadRetries) { success in
                    if success {
                        try? FileManager.default.removeItem(at: url)
                        try? FileManager.default.removeItem(at: mp3URL)
                        if uploadURL.path != mp3URL.path {
                            try? FileManager.default.removeItem(at: uploadURL)
                        }
                    } else {
                        print("[AudioSegmentUploader] ⚠️ Upload failed after retries. Queueing segment \(self.segmentIndex) for later.")
                        self.enqueuePendingUpload(fileURL: uploadURL, segmentIndex: self.segmentIndex, duration: Int(duration))
                        try? FileManager.default.removeItem(at: url)
                        if uploadURL.path != mp3URL.path {
                            try? FileManager.default.removeItem(at: mp3URL)
                        }
                    }

                    self.segmentIndex += 1
                    completion(true)
                }
            }
        }
    }
    
    // MARK: - Upload
    
    private func uploadSegment(fileURL: URL, segmentIndex: Int, duration: Int, completion: @escaping (Bool) -> Void) {
        // Build URL from dynamic config (with safe fallback)
        let baseURL = getApiUrl()
        guard let components = URLComponents(string: baseURL) else {
            completion(false)
            return
        }
        
        // Get timezone
        let timezone = TimeZone.current.identifier
        
        // No query params needed - all data goes in FormData body
        
        guard let url = components.url else {
            completion(false)
            return
        }
        
        // Create multipart form data
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        
        let boundary = "Boundary-\(UUID().uuidString)"
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        
        // Read audio file
        guard let audioData = try? Data(contentsOf: fileURL) else {
            print("[AudioSegmentUploader] ❌ Failed to read audio file")
            completion(false)
            return
        }
        
        // Build multipart body
        var body = Data()
        
        // Add action field (REQUIRED!)
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"action\"\r\n\r\n".data(using: .utf8)!)
        body.append("receberAudioMobile\r\n".data(using: .utf8)!)
        
        // Add session_token field (CRITICAL!)
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"session_token\"\r\n\r\n".data(using: .utf8)!)
        body.append("\(sessionToken)\r\n".data(using: .utf8)!)
        
        // Add email_usuario field (REQUIRED!)
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"email_usuario\"\r\n\r\n".data(using: .utf8)!)
        body.append("\(emailUsuario)\r\n".data(using: .utf8)!)
        
        // Add segmento_idx field
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"segmento_idx\"\r\n\r\n".data(using: .utf8)!)
        body.append("\(segmentIndex)\r\n".data(using: .utf8)!)
        
        // Add duracao_segundos field (SEM casas decimais)
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"duracao_segundos\"\r\n\r\n".data(using: .utf8)!)
        body.append("\(Int(duration))\r\n".data(using: .utf8)!)
        
        // Add origem_gravacao field
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"origem_gravacao\"\r\n\r\n".data(using: .utf8)!)
        body.append("\(origemGravacao)\r\n".data(using: .utf8)!)
        
        // Add device_id field
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"device_id\"\r\n\r\n".data(using: .utf8)!)
        body.append("\(sessionId)\r\n".data(using: .utf8)!)
        
        // Add timezone field
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"timezone\"\r\n\r\n".data(using: .utf8)!)
        body.append("\(timezone)\r\n".data(using: .utf8)!)
        
        // Add timezone_offset_minutes field
        let timezoneOffset = TimeZone.current.secondsFromGMT() / 60
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"timezone_offset_minutes\"\r\n\r\n".data(using: .utf8)!)
        body.append("\(timezoneOffset)\r\n".data(using: .utf8)!)
        
        // Add GPS location if available
        if let location = currentLocation {
            // Add latitude
            body.append("--\(boundary)\r\n".data(using: .utf8)!)
            body.append("Content-Disposition: form-data; name=\"latitude\"\r\n\r\n".data(using: .utf8)!)
            body.append("\(location.coordinate.latitude)\r\n".data(using: .utf8)!)
            
            // Add longitude
            body.append("--\(boundary)\r\n".data(using: .utf8)!)
            body.append("Content-Disposition: form-data; name=\"longitude\"\r\n\r\n".data(using: .utf8)!)
            body.append("\(location.coordinate.longitude)\r\n".data(using: .utf8)!)
            
            // Add accuracy (precisao_metros)
            body.append("--\(boundary)\r\n".data(using: .utf8)!)
            body.append("Content-Disposition: form-data; name=\"precisao_metros\"\r\n\r\n".data(using: .utf8)!)
            body.append("\(Int(location.horizontalAccuracy))\r\n".data(using: .utf8)!)
            
            print("[AudioSegmentUploader] 📍 Including GPS in upload: lat=\(location.coordinate.latitude), lon=\(location.coordinate.longitude), accuracy=\(Int(location.horizontalAccuracy))m")
        } else {
            print("[AudioSegmentUploader] ⚠️ No GPS location available for this segment")
        }
        
        // Add audio file
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        let fileName = fileURL.lastPathComponent
        body.append("Content-Disposition: form-data; name=\"audio\"; filename=\"\(fileName)\"\r\n".data(using: .utf8)!)
        body.append("Content-Type: audio/mpeg\r\n\r\n".data(using: .utf8)!)
        body.append(audioData)
        body.append("\r\n".data(using: .utf8)!)
        
        // Close boundary
        body.append("--\(boundary)--\r\n".data(using: .utf8)!)
        
        // Persist multipart to disk and upload via background URLSession
        let tempFileURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("upload_\(UUID().uuidString).multipart")
        do {
            try body.write(to: tempFileURL, options: .atomic)
        } catch {
            print("[AudioSegmentUploader] ❌ Failed to persist multipart body: \(error)")
            completion(false)
            return
        }

        request.timeoutInterval = uploadTimeoutSeconds
        print("\u{001B}[1;31m[AudioSegmentUploader] 🚨 ENVIANDO SEGMENTO -> action=receberAudioMobile | url=\(url.absoluteString) | segment=\(segmentIndex) | file=\(fileName) | bytes=\(audioData.count)\u{001B}[0m")
        let task = uploadSession.uploadTask(with: request, fromFile: tempFileURL)
        uploadStateQueue.sync {
            uploadCompletions[task.taskIdentifier] = completion
            uploadTempFiles[task.taskIdentifier] = tempFileURL
            uploadResponseData[task.taskIdentifier] = Data()
        }
        task.resume()
    }

    private func uploadSegmentWithRetry(fileURL: URL, segmentIndex: Int, duration: Int, retriesLeft: Int, completion: @escaping (Bool) -> Void) {
        uploadSegment(fileURL: fileURL, segmentIndex: segmentIndex, duration: duration) { [weak self] success in
            guard let self = self else {
                completion(false)
                return
            }

            if success {
                completion(true)
                return
            }

            if retriesLeft <= 1 {
                completion(false)
                return
            }

            let attempt = self.maxUploadRetries - retriesLeft + 1
            let backoffSeconds = Double(1 << min(attempt - 1, 3))
            print("[AudioSegmentUploader] 🔁 Upload retry \(attempt + 1)/\(self.maxUploadRetries) in \(Int(backoffSeconds))s")
            DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + backoffSeconds) {
                self.uploadSegmentWithRetry(fileURL: fileURL, segmentIndex: segmentIndex, duration: duration, retriesLeft: retriesLeft - 1, completion: completion)
            }
        }
    }
    
    // MARK: - Conversion
    
    private func convertM4AtoMP3(inputURL: URL, outputURL: URL, completion: @escaping (Bool) -> Void) {
        // Validate input file exists
        guard FileManager.default.fileExists(atPath: inputURL.path) else {
            print("[AudioSegmentUploader] ❌ Input file does not exist: \(inputURL.path)")
            completion(false)
            return
        }
        
        // Check file size
        if let attributes = try? FileManager.default.attributesOfItem(atPath: inputURL.path),
           let fileSize = attributes[.size] as? Int64 {
            print("[AudioSegmentUploader] 📊 Input file size: \(fileSize) bytes")
            
            if fileSize == 0 {
                print("[AudioSegmentUploader] ❌ Input file is empty")
                completion(false)
                return
            }
        }
        
        // Delete output file if exists
        try? FileManager.default.removeItem(at: outputURL)
        
        // Validate input file before conversion
        guard let fileAttributes = try? FileManager.default.attributesOfItem(atPath: inputURL.path),
              let fileSize = fileAttributes[.size] as? UInt64 else {
            print("[AudioSegmentUploader] ⚠️ Cannot get file attributes for \(inputURL.path)")
            completion(false)
            return
        }
        
        // Check if file is too small (likely corrupted or incomplete)
        if fileSize < 1024 {
            print("[AudioSegmentUploader] ⚠️ Input file too small (\(fileSize) bytes), likely corrupted - skipping conversion")
            completion(false)
            return
        }
        
        print("[AudioSegmentUploader] 🔄 Converting M4A to MP3 using LAME encoder")
        print("[AudioSegmentUploader] 📂 Input: \(inputURL.path)")
        print("[AudioSegmentUploader] 📂 Output: \(outputURL.path)")
        
        DispatchQueue.global(qos: .userInitiated).async {
            let converter = ExtAudioConverter()
            converter.inputFile = inputURL.path
            converter.outputFile = outputURL.path
            converter.outputFileType = kAudioFileMP3Type
            converter.outputFormatID = kAudioFormatMPEGLayer3
            converter.outputSampleRate = 8000  // 8 kHz (telefone)
            converter.outputNumberChannels = 1  // Mono
            
            let success = converter.convert()
            
            DispatchQueue.main.async {
                if success {
                    print("[AudioSegmentUploader] ✅ LAME conversion successful")
                    completion(true)
                } else {
                    print("[AudioSegmentUploader] ❌ LAME conversion failed")
                    completion(false)
                }
            }
        }
    }
    
    // MARK: - Cleanup
    
    func cleanup() {
        audioFile = nil
        if let url = audioFileURL {
            try? FileManager.default.removeItem(at: url)
        }
        audioFileURL = nil
        
        // Stop location updates
        locationManager?.stopUpdatingLocation()
        gpsUpdatesStarted = false
        locationManager = nil
    }
    
    func getCurrentLocation() -> CLLocation? {
        return currentLocation
    }

    private func getApiUrl() -> String {
        let fallback = "https://uogenwcycqykfsuongrl.supabase.co/functions/v1/mobile-api"
        return UserDefaults.standard.string(forKey: "api_url") ?? fallback
    }

    private func pendingUploadsDirectory() -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first ?? FileManager.default.temporaryDirectory
        let dir = base.appendingPathComponent("PendingUploads", isDirectory: true)
        if !FileManager.default.fileExists(atPath: dir.path) {
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        }
        return dir
    }

    private func preparePersistentPendingFile(from sourceURL: URL, segmentIndex: Int) -> URL? {
        let targetURL = pendingUploadsDirectory().appendingPathComponent("segment_\(segmentIndex)_\(UUID().uuidString).mp3")
        do {
            if FileManager.default.fileExists(atPath: targetURL.path) {
                try FileManager.default.removeItem(at: targetURL)
            }
            try FileManager.default.copyItem(at: sourceURL, to: targetURL)
            return targetURL
        } catch {
            print("[AudioSegmentUploader] ⚠️ Failed to create persistent pending file: \(error.localizedDescription)")
            return nil
        }
    }

    private func loadPendingUploads() {
        guard let data = UserDefaults.standard.data(forKey: pendingQueueKey) else {
            pendingUploads = []
            return
        }
        if let decoded = try? JSONDecoder().decode([PendingUpload].self, from: data) {
            pendingUploads = decoded
            if !decoded.isEmpty {
                print("[AudioSegmentUploader] 📦 Loaded \(decoded.count) pending upload(s)")
            }
        } else {
            pendingUploads = []
            UserDefaults.standard.removeObject(forKey: pendingQueueKey)
        }
    }

    private func savePendingUploads() {
        if pendingUploads.isEmpty {
            UserDefaults.standard.removeObject(forKey: pendingQueueKey)
            return
        }
        if let encoded = try? JSONEncoder().encode(pendingUploads) {
            UserDefaults.standard.set(encoded, forKey: pendingQueueKey)
        }
    }

    private func enqueuePendingUpload(fileURL: URL, segmentIndex: Int, duration: Int) {
        let pending = PendingUpload(filePath: fileURL.path, segmentIndex: segmentIndex, duration: duration)
        pendingUploads.append(pending)
        savePendingUploads()
        print("[AudioSegmentUploader] 📥 Segment queued for later upload: idx=\(segmentIndex)")
        processPendingUploadsIfNeeded()
    }

    private func processPendingUploadsIfNeeded() {
        guard !isProcessingPendingQueue else { return }
        guard !pendingUploads.isEmpty else { return }

        isProcessingPendingQueue = true
        processNextPendingUpload()
    }

    private func processNextPendingUpload() {
        guard !pendingUploads.isEmpty else {
            isProcessingPendingQueue = false
            savePendingUploads()
            return
        }

        let current = pendingUploads[0]
        let fileURL = URL(fileURLWithPath: current.filePath)
        if !FileManager.default.fileExists(atPath: fileURL.path) {
            print("[AudioSegmentUploader] ⚠️ Pending file not found, dropping from queue: \(fileURL.lastPathComponent)")
            pendingUploads.removeFirst()
            savePendingUploads()
            processNextPendingUpload()
            return
        }

        uploadSegmentWithRetry(fileURL: fileURL, segmentIndex: current.segmentIndex, duration: current.duration, retriesLeft: maxUploadRetries) { [weak self] success in
            guard let self = self else { return }
            if success {
                print("[AudioSegmentUploader] ✅ Pending upload sent: idx=\(current.segmentIndex)")
                try? FileManager.default.removeItem(at: fileURL)
                self.pendingUploads.removeFirst()
                self.savePendingUploads()
                self.processNextPendingUpload()
            } else {
                print("[AudioSegmentUploader] ⚠️ Pending upload still failing. Will keep in queue.")
                self.isProcessingPendingQueue = false
                self.savePendingUploads()
            }
        }
    }

}

// MARK: - CLLocationManagerDelegate

extension AudioSegmentUploader: CLLocationManagerDelegate {
    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        print("[AudioSegmentUploader] 📡 didUpdateLocations called with \(locations.count) location(s)")
        if let location = locations.last {
            currentLocation = location
            print("[AudioSegmentUploader] 📍 GPS updated: lat=\(location.coordinate.latitude), lon=\(location.coordinate.longitude), accuracy=\(location.horizontalAccuracy)m")
            
            // Share location with AudioTriggerNativePlugin
            plugin?.updateLocation(location)
        } else {
            print("[AudioSegmentUploader] ⚠️ didUpdateLocations called but locations array is empty")
        }
    }
    
    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        print("[AudioSegmentUploader] ❌ GPS error: \(error.localizedDescription)")
        print("[AudioSegmentUploader] ❌ Error code: \((error as NSError).code)")
    }
    
    func locationManager(_ manager: CLLocationManager, didChangeAuthorization status: CLAuthorizationStatus) {
        print("[AudioSegmentUploader] 🔐 GPS authorization changed: \(status.rawValue)")
        switch status {
        case .notDetermined:
            print("[AudioSegmentUploader] 🔐 Status: Not Determined - waiting for user decision")
        case .restricted:
            print("[AudioSegmentUploader] 🔐 Status: Restricted - GPS unavailable")
        case .denied:
            print("[AudioSegmentUploader] 🔐 Status: Denied - user rejected GPS permission")
        case .authorizedAlways:
            print("[AudioSegmentUploader] 🔐 Status: Authorized Always - starting GPS")
            startGPSUpdates()
        case .authorizedWhenInUse:
            print("[AudioSegmentUploader] ⚠️ Status: Authorized When In Use - background GPS is not guaranteed on lock screen")
        @unknown default:
            print("[AudioSegmentUploader] 🔐 Status: Unknown (\(status.rawValue))")
        }
    }
}

// MARK: - URLSession Delegates (required for background sessions)

extension AudioSegmentUploader: URLSessionTaskDelegate, URLSessionDataDelegate {
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        uploadStateQueue.sync {
            var current = uploadResponseData[dataTask.taskIdentifier] ?? Data()
            current.append(data)
            uploadResponseData[dataTask.taskIdentifier] = current
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let taskId = task.taskIdentifier

        var completion: ((Bool) -> Void)?
        var tempFileURL: URL?
        var responseData: Data?
        uploadStateQueue.sync {
            completion = uploadCompletions.removeValue(forKey: taskId)
            tempFileURL = uploadTempFiles.removeValue(forKey: taskId)
            responseData = uploadResponseData.removeValue(forKey: taskId)
        }

        if let tempFileURL = tempFileURL {
            try? FileManager.default.removeItem(at: tempFileURL)
        }

        guard let completion = completion else {
            print("[AudioSegmentUploader] ⚠️ No completion found for upload task \(taskId)")
            return
        }

        if let error = error {
            print("[AudioSegmentUploader] ❌ Upload error: \(error.localizedDescription)")
            completion(false)
            return
        }

        guard let httpResponse = task.response as? HTTPURLResponse else {
            print("[AudioSegmentUploader] ❌ No HTTP response received")
            completion(false)
            return
        }

        print("[AudioSegmentUploader] 📊 HTTP Status: \(httpResponse.statusCode)")
        if let responseData = responseData,
           !responseData.isEmpty,
           let responseBody = String(data: responseData, encoding: .utf8) {
            print("[AudioSegmentUploader] 📊 Response body: \(responseBody)")
        }

        completion(httpResponse.statusCode == 200)
    }
}
