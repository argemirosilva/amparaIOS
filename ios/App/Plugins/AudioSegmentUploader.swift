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
    
    // MARK: - Properties
    
    private var audioFile: AVAudioFile?
    private var audioFileURL: URL?
    private var segmentIndex = 0
    private var sessionId: String
    private var sessionToken: String
    private var emailUsuario: String
    private var origemGravacao: String
    
    private let segmentDuration: TimeInterval = 30.0
    private var segmentStartTime: Date?
    
    // Location manager for GPS
    private var locationManager: CLLocationManager?
    private var currentLocation: CLLocation?
    
    // MARK: - Initialization
    
    init(sessionId: String, sessionToken: String, emailUsuario: String, origemGravacao: String) {
        self.sessionId = sessionId
        self.sessionToken = sessionToken
        self.emailUsuario = emailUsuario
        self.origemGravacao = origemGravacao
        super.init()
        
        // Setup location manager
        setupLocationManager()
    }
    
    private func setupLocationManager() {
        locationManager = CLLocationManager()
        locationManager?.desiredAccuracy = kCLLocationAccuracyBest
        locationManager?.distanceFilter = 10 // Update every 10 meters
        locationManager?.delegate = self
        
        // Enable background location updates (CRITICAL for long-term background operation)
        locationManager?.allowsBackgroundLocationUpdates = true
        locationManager?.pausesLocationUpdatesAutomatically = false
        locationManager?.showsBackgroundLocationIndicator = true // Show blue bar when using location
        
        // Request location permission
        let authStatus = CLLocationManager.authorizationStatus()
        if authStatus == .notDetermined {
            locationManager?.requestAlwaysAuthorization()
            print("[AudioSegmentUploader] 📍 Requesting GPS permission (Always)")
        }
        
        // Start monitoring location
        if CLLocationManager.locationServicesEnabled() {
            locationManager?.startUpdatingLocation()
            print("[AudioSegmentUploader] 📍 GPS started with background updates enabled")
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
        
        print("[AudioSegmentUploader] 📤 Uploading segment \(segmentIndex), duration: \(Int(duration))s")
        
        // Wait 200ms for file system to finalize M4A file
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { [weak self] in
            guard let self = self else { return }
            
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
            }
            
            // Convert M4A to MP3 before upload
            let mp3URL = url.deletingPathExtension().appendingPathExtension("mp3")
            self.convertM4AtoMP3(inputURL: url, outputURL: mp3URL) { [weak self] convertSuccess in
            guard let self = self else { return }
            
            if !convertSuccess {
                print("[AudioSegmentUploader] ❌ Failed to convert segment \(self.segmentIndex) to MP3")
                completion(false)
                return
            }
            
            print("[AudioSegmentUploader] ✅ Converted segment \(self.segmentIndex) to MP3")
            
            // Upload MP3 file to server
            self.uploadSegment(fileURL: mp3URL, segmentIndex: self.segmentIndex, duration: Int(duration)) { success in
                if success {
                    // Delete both M4A and MP3 files after successful upload
                    try? FileManager.default.removeItem(at: url)
                    try? FileManager.default.removeItem(at: mp3URL)
                    print("[AudioSegmentUploader] ✅ Segment \(self.segmentIndex) uploaded and deleted")
                } else {
                    print("[AudioSegmentUploader] ❌ Failed to upload segment \(self.segmentIndex)")
                }
                
                self.segmentIndex += 1
                completion(success)
            }
            }
        }
    }
    
    // MARK: - Upload
    
    private func uploadSegment(fileURL: URL, segmentIndex: Int, duration: Int, completion: @escaping (Bool) -> Void) {
        // Build URL - usando endpoint Supabase correto
        let baseURL = "https://ilikiajeduezvvanjejz.supabase.co/functions/v1/mobile-api"
        guard var components = URLComponents(string: baseURL) else {
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
        
        request.httpBody = body
        
        // Configure timeout (60 seconds)
        request.timeoutInterval = 60.0
        
        // Send request
        URLSession.shared.dataTask(with: request) { data, response, error in
            if let error = error {
                print("[AudioSegmentUploader] ❌ Upload error: \(error)")
                completion(false)
                return
            }
            
            if let httpResponse = response as? HTTPURLResponse {
                print("[AudioSegmentUploader] 📊 HTTP Status: \(httpResponse.statusCode)")
                
                // Log response body for debugging
                if let data = data, let responseBody = String(data: data, encoding: .utf8) {
                    print("[AudioSegmentUploader] 📊 Response body: \(responseBody)")
                }
                
                if httpResponse.statusCode == 200 {
                    print("[AudioSegmentUploader] ✅ Segment \(segmentIndex) uploaded successfully")
                    completion(true)
                } else {
                    print("[AudioSegmentUploader] ❌ Upload failed with status: \(httpResponse.statusCode)")
                    completion(false)
                }
            } else {
                print("[AudioSegmentUploader] ❌ No HTTP response received")
                completion(false)
            }
        }.resume()
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
        locationManager = nil
    }
}

// MARK: - CLLocationManagerDelegate

extension AudioSegmentUploader: CLLocationManagerDelegate {
    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        if let location = locations.last {
            currentLocation = location
            print("[AudioSegmentUploader] 📍 GPS updated: lat=\(location.coordinate.latitude), lon=\(location.coordinate.longitude), accuracy=\(location.horizontalAccuracy)m")
        }
    }
    
    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        print("[AudioSegmentUploader] ⚠️ GPS error: \(error.localizedDescription)")
    }
}
