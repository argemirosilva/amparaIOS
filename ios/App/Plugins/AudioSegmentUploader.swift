import Foundation
import AVFoundation
import ffmpegkit

/**
 * AudioSegmentUploader - Handles audio segment recording and upload
 * 
 * Features:
 * - Records audio to M4A files (AAC codec)
 * - Uploads segments to server every 30 seconds
 * - Includes timezone in requests
 * - Automatic cleanup of uploaded files
 */

class AudioSegmentUploader {
    
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
    
    // MARK: - Initialization
    
    init(sessionId: String, sessionToken: String, emailUsuario: String, origemGravacao: String) {
        self.sessionId = sessionId
        self.sessionToken = sessionToken
        self.emailUsuario = emailUsuario
        self.origemGravacao = origemGravacao
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
        
        // Close audio file
        audioFile = nil
        
        // Calculate duration
        let duration = segmentStartTime.map { Date().timeIntervalSince($0) } ?? segmentDuration
        
        print("[AudioSegmentUploader] 📤 Uploading segment \(segmentIndex), duration: \(Int(duration))s")
        
        // Convert M4A to OGG before upload
        let oggURL = url.deletingPathExtension().appendingPathExtension("ogg")
        convertM4AtoOGG(inputURL: url, outputURL: oggURL) { [weak self] convertSuccess in
            guard let self = self else { return }
            
            if !convertSuccess {
                print("[AudioSegmentUploader] ❌ Failed to convert segment \(self.segmentIndex) to OGG")
                completion(false)
                return
            }
            
            print("[AudioSegmentUploader] ✅ Converted segment \(self.segmentIndex) to OGG")
            
            // Upload OGG file to server
            self.uploadSegment(fileURL: oggURL, segmentIndex: self.segmentIndex, duration: Int(duration)) { success in
                if success {
                    // Delete both M4A and OGG files after successful upload
                    try? FileManager.default.removeItem(at: url)
                    try? FileManager.default.removeItem(at: oggURL)
                    print("[AudioSegmentUploader] ✅ Segment \(self.segmentIndex) uploaded and deleted")
                } else {
                    print("[AudioSegmentUploader] ❌ Failed to upload segment \(self.segmentIndex)")
                }
                
                self.segmentIndex += 1
                completion(success)
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
        
        // Add e        // Add email_usuario field (REQUIRED!)
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"email_usuario\"\r\n\r\n".data(using: .utf8)!)
        body.append("\(emailUsuario)\r\n".data(using: .utf8)!)
        
        // Add segment_index field
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"segment_index\"\r\n\r\n".data(using: .utf8)!)
        body.append("\(segmentIndex)\r\n".data(using: .utf8)!)
        
        // Add duration_seconds field (SEM casas decimais)
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"duration_seconds\"\r\n\r\n".data(using: .utf8)!)
        body.append("\(Int(duration))\r\n".data(using: .utf8)!)
        
        // Add origem_gravacao field
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"origem_gravacao\"\r\n\r\n".data(using: .utf8)!)
        body.append("\(origemGravacao)\r\n".data(using: .utf8)!)
        
        // Add timezone field
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"timezone\"\r\n\r\n".data(using: .utf8)!)
        body.append("\(timezone)\r\n".data(using: .utf8)!)
        
        // Add audio file
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        let fileName = fileURL.lastPathComponent
        let mimeType = fileName.hasSuffix(".ogg") ? "audio/ogg" : "audio/mp4"
        body.append("Content-Disposition: form-data; name=\"audio\"; filename=\"\(fileName)\"\r\n".data(using: .utf8)!)
        body.append("Content-Type: \(mimeType)\r\n\r\n".data(using: .utf8)!)
        body.append(audioData)
        body.append("\r\n".data(using: .utf8)!)
        
        // Close boundary
        body.append("--\(boundary)--\r\n".data(using: .utf8)!)
        
        request.httpBody = body
        
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
    
    private func convertM4AtoOGG(inputURL: URL, outputURL: URL, completion: @escaping (Bool) -> Void) {
        // Delete output file if exists
        try? FileManager.default.removeItem(at: outputURL)
        
        // Build FFmpeg command
        // -i input.m4a: input file
        // -c:a libvorbis: use Vorbis codec for OGG
        // -q:a 4: quality level (0-10, 4 is good balance)
        let command = "-i \"\(inputURL.path)\" -c:a libvorbis -q:a 4 \"\(outputURL.path)\""
        
        print("[AudioSegmentUploader] 🔄 Converting M4A to OGG: \(command)")
        
        // Execute FFmpeg command
        FFmpegKit.executeAsync(command) { session in
            guard let session = session else {
                print("[AudioSegmentUploader] ❌ FFmpeg session is nil")
                completion(false)
                return
            }
            
            let returnCode = session.getReturnCode()
            
            if ReturnCode.isSuccess(returnCode) {
                print("[AudioSegmentUploader] ✅ FFmpeg conversion successful")
                completion(true)
            } else {
                print("[AudioSegmentUploader] ❌ FFmpeg conversion failed with code: \(String(describing: returnCode))")
                if let output = session.getOutput() {
                    print("[AudioSegmentUploader] FFmpeg output: \(output)")
                }
                completion(false)
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
    }
}
