import Foundation
import Capacitor

/**
 * PanicPlugin - Bridge between JavaScript and PanicManager
 * 
 * Equivalent to Android's PanicPlugin.java
 * Exposes panic management methods to JavaScript
 */
@objc(PanicPlugin)
public class PanicPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "PanicPlugin"
    public let jsName = "Panic"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "activate", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "cancel", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getStatus", returnType: CAPPluginReturnPromise),
    ]
    
    private let panicManager = PanicManager.shared
    
    // MARK: - Capacitor Methods
    
    @objc func activate(_ call: CAPPluginCall) {
        guard let protocolNumber = call.getString("protocol") else {
            call.reject("Missing 'protocol' parameter")
            return
        }
        
        let activationType = call.getString("activationType") ?? "manual"
        
        // Activate panic
        panicManager.activatePanic(protocol: protocolNumber, activationType: activationType)
        
        // Notify listeners
        notifyListeners("panicActivated", data: [
            "protocol": protocolNumber,
            "activationType": activationType,
            "timestamp": Int(Date().timeIntervalSince1970 * 1000)
        ])
        
        call.resolve([
            "success": true,
            "protocol": protocolNumber,
            "activationType": activationType
        ])
    }
    
    @objc func cancel(_ call: CAPPluginCall) {
        let cancelType = call.getString("cancelType") ?? "manual"
        
        // Get current panic info before cancelling
        let wasActive = panicManager.isPanicActive
        let protocolNumber = panicManager.protocolNumber
        
        // Cancel panic
        panicManager.cancelPanic()
        
        // Notify listeners
        if wasActive {
            notifyListeners("panicCancelled", data: [
                "cancelType": cancelType,
                "protocol": protocolNumber ?? "",
                "timestamp": Int(Date().timeIntervalSince1970 * 1000)
            ])
        }
        
        call.resolve([
            "success": true,
            "wasActive": wasActive,
            "cancelType": cancelType
        ])
    }
    
    @objc func getStatus(_ call: CAPPluginCall) {
        let isActive = panicManager.isPanicActive
        
        var result: [String: Any] = [
            "isActive": isActive
        ]
        
        if isActive {
            if let protocolNumber = panicManager.protocolNumber {
                result["protocol"] = protocolNumber
            }
            if let activationType = panicManager.activationType {
                result["activationType"] = activationType
            }
            if let startTime = panicManager.startTime {
                result["startTime"] = startTime
            }
        }
        
        call.resolve(result)
    }
}
