package tech.orizon.ampara.plugins;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import tech.orizon.ampara.KeepAliveService;

/**
 * Plugin Capacitor para controlar o KeepAliveService
 */
@CapacitorPlugin(name = "KeepAlive")
public class KeepAlivePlugin extends Plugin {
    
    private static final String TAG = "KeepAlivePlugin";
    
    @PluginMethod
    public void start(PluginCall call) {
        try {
            // Recebe o device_id do JavaScript e salva no SharedPreferences
            String deviceId = call.getString("deviceId");
            if (deviceId != null && !deviceId.isEmpty()) {
                SharedPreferences prefs = getContext().getSharedPreferences("ampara_secure_storage", Context.MODE_PRIVATE);
                prefs.edit().putString("ampara_device_id", deviceId).apply();
                Log.d(TAG, "Device ID synchronized: " + deviceId);
            } else {
                Log.w(TAG, "No device_id provided, using fallback");
            }
            
            Intent serviceIntent = new Intent(getContext(), KeepAliveService.class);
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getContext().startForegroundService(serviceIntent);
            } else {
                getContext().startService(serviceIntent);
            }
            
            Log.d(TAG, "KeepAlive service started");
            call.resolve();
        } catch (Exception e) {
            Log.e(TAG, "Error starting KeepAlive service", e);
            call.reject("Failed to start service: " + e.getMessage());
        }
    }
    
    @PluginMethod
    public void stop(PluginCall call) {
        try {
            Intent serviceIntent = new Intent(getContext(), KeepAliveService.class);
            getContext().stopService(serviceIntent);
            
            Log.d(TAG, "KeepAlive service stopped");
            call.resolve();
        } catch (Exception e) {
            Log.e(TAG, "Error stopping KeepAlive service", e);
            call.reject("Failed to stop service: " + e.getMessage());
        }
    }
}
