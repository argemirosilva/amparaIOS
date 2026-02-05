import Foundation

/**
 * PanicManager - Manages panic state persistence for iOS
 * 
 * Equivalent to Android's PanicManager.java
 * Stores panic state in UserDefaults (iOS equivalent of SharedPreferences)
 */
class PanicManager {
    // Singleton instance
    static let shared = PanicManager()
    
    // UserDefaults keys
    private let KEY_IS_ACTIVE = "panic_is_active"
    private let KEY_START_TIME = "panic_start_time"
    private let KEY_PROTOCOL = "panic_protocol"
    private let KEY_ACTIVATION_TYPE = "panic_activation_type"
    
    private let defaults = UserDefaults.standard
    
    private init() {}
    
    // MARK: - Public API
    
    /**
     * Check if panic is currently active
     */
    var isPanicActive: Bool {
        return defaults.bool(forKey: KEY_IS_ACTIVE)
    }
    
    /**
     * Get panic start time (milliseconds since epoch)
     */
    var startTime: Int64? {
        let time = defaults.object(forKey: KEY_START_TIME) as? Int64
        return time == 0 ? nil : time
    }
    
    /**
     * Get panic protocol number
     */
    var protocolNumber: String? {
        return defaults.string(forKey: KEY_PROTOCOL)
    }
    
    /**
     * Get panic activation type
     */
    var activationType: String? {
        return defaults.string(forKey: KEY_ACTIVATION_TYPE)
    }
    
    /**
     * Activate panic mode
     */
    func activatePanic(protocol: String, activationType: String) {
        let startTime = Int64(Date().timeIntervalSince1970 * 1000)
        
        defaults.set(true, forKey: KEY_IS_ACTIVE)
        defaults.set(startTime, forKey: KEY_START_TIME)
        defaults.set(`protocol`, forKey: KEY_PROTOCOL)
        defaults.set(activationType, forKey: KEY_ACTIVATION_TYPE)
        
        print("[PanicManager-iOS] 🚨 Panic ACTIVATED - protocol: \(`protocol`), type: \(activationType)")
    }
    
    /**
     * Cancel panic mode
     */
    func cancelPanic() {
        defaults.set(false, forKey: KEY_IS_ACTIVE)
        defaults.removeObject(forKey: KEY_START_TIME)
        defaults.removeObject(forKey: KEY_PROTOCOL)
        defaults.removeObject(forKey: KEY_ACTIVATION_TYPE)
        
        print("[PanicManager-iOS] ✅ Panic CANCELLED")
    }
    
    /**
     * Clear all panic data
     */
    func clearPanicData() {
        defaults.removeObject(forKey: KEY_IS_ACTIVE)
        defaults.removeObject(forKey: KEY_START_TIME)
        defaults.removeObject(forKey: KEY_PROTOCOL)
        defaults.removeObject(forKey: KEY_ACTIVATION_TYPE)
        
        print("[PanicManager-iOS] 🗑️ Panic data cleared")
    }
}
