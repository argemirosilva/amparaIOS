package tech.orizon.ampara.plugins;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import tech.orizon.ampara.AudioTriggerService;
import tech.orizon.ampara.PanicManager;

/**
 * Capacitor Plugin for Panic State Management
 */
@CapacitorPlugin(name = "Panic")
public class PanicPlugin extends Plugin {
    
    private static final String TAG = "PanicPlugin";
    private PanicManager panicManager;
    
    @Override
    public void load() {
        panicManager = new PanicManager(getContext());
    }
    
    /**
     * Activate panic mode
     */
    @PluginMethod
    public void activate(PluginCall call) {
        try {
            String protocolNumber = call.getString("protocolNumber", "");
            String activationType = call.getString("activationType", "manual");
            String sessionToken = call.getString("sessionToken");
            String emailUsuario = call.getString("emailUsuario");
            String deviceId = call.getString("deviceId");
            
            // Set credentials
            panicManager.setCredentials(sessionToken, emailUsuario, deviceId);
            
            // Activate panic
            panicManager.activatePanic(protocolNumber, activationType);
            
            // Notify AudioTriggerService
            Intent intent = new Intent(getContext(), AudioTriggerService.class);
            intent.setAction("PANIC_ACTIVATED");
            intent.putExtra("protocolNumber", protocolNumber);
            intent.putExtra("activationType", activationType);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Android 8+: avoid background service start restrictions
                getContext().startForegroundService(intent);
            } else {
                getContext().startService(intent);
            }
            
            JSObject result = new JSObject();
            result.put("success", true);
            call.resolve(result);
            
            Log.i(TAG, "Panic activated via plugin");
            
        } catch (Exception e) {
            Log.e(TAG, "Error activating panic", e);
            call.reject("Failed to activate panic", e);
        }
    }
    
    /**
     * Deactivate panic mode
     */
    @PluginMethod
    public void deactivate(PluginCall call) {
        try {
            String cancelType = call.getString("cancelType", "manual");
            
            // Deactivate panic
            panicManager.deactivatePanic(cancelType);
            
            // Notify AudioTriggerService
            Intent intent = new Intent(getContext(), AudioTriggerService.class);
            intent.setAction("PANIC_DEACTIVATED");
            intent.putExtra("cancelType", cancelType);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Android 8+: avoid background service start restrictions
                getContext().startForegroundService(intent);
            } else {
                getContext().startService(intent);
            }
            
            JSObject result = new JSObject();
            result.put("success", true);
            call.resolve(result);
            
            Log.i(TAG, "Panic deactivated via plugin");
            
        } catch (Exception e) {
            Log.e(TAG, "Error deactivating panic", e);
            call.reject("Failed to deactivate panic", e);
        }
    }
    
    /**
     * Get current panic state
     */
    @PluginMethod
    public void getState(PluginCall call) {
        try {
            JSObject state = new JSObject();
            state.put("isPanicActive", panicManager.isPanicActive());
            state.put("panicStartTime", panicManager.getPanicStartTime());
            state.put("protocolNumber", panicManager.getProtocolNumber());
            
            call.resolve(state);
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting panic state", e);
            call.reject("Failed to get panic state", e);
        }
    }
}
