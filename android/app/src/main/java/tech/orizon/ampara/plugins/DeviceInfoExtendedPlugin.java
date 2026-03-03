package tech.orizon.ampara.plugins;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Plugin para obter informações estendidas do dispositivo
 */
@CapacitorPlugin(name = "DeviceInfoExtended")
public class DeviceInfoExtendedPlugin extends Plugin {
    
    private static final String TAG = "DeviceInfoExtended";
    
    @PluginMethod
    public void getExtendedInfo(PluginCall call) {
        try {
            JSObject result = new JSObject();
            Context context = getContext();
            
            // Nome do modelo do dispositivo
            String deviceModel = Build.MANUFACTURER + " " + Build.MODEL;
            result.put("deviceModel", deviceModel);
            
            // Informações de bateria
            BatteryManager batteryManager = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            int batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            result.put("batteryLevel", batteryLevel);
            
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = context.registerReceiver(null, ifilter);
            int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                status == BatteryManager.BATTERY_STATUS_FULL;
            result.put("isCharging", isCharging);
            
            // Versão do Android
            result.put("androidVersion", Build.VERSION.RELEASE);
            
            // Versão do app
            try {
                String appVersion = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
                result.put("appVersion", appVersion);
            } catch (Exception e) {
                result.put("appVersion", "unknown");
            }
            
            // Status de otimização de bateria
            PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            boolean isIgnoringBatteryOptimization = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                isIgnoringBatteryOptimization = powerManager.isIgnoringBatteryOptimizations(context.getPackageName());
            }
            result.put("isIgnoringBatteryOptimization", isIgnoringBatteryOptimization);
            
            // Tipo de conexão e força do sinal
            ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo activeNetwork = connectivityManager.getActiveNetworkInfo();
            
            String connectionType = "none";
            Integer wifiSignalStrength = null;
            
            if (activeNetwork != null && activeNetwork.isConnected()) {
                int type = activeNetwork.getType();
                if (type == ConnectivityManager.TYPE_WIFI) {
                    connectionType = "wifi";
                    
                    // Obter força do sinal WiFi
                    WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                    int rssi = wifiInfo.getRssi();
                    
                    // Converter RSSI para porcentagem (0-100)
                    // RSSI típico: -100 (fraco) a -50 (forte)
                    wifiSignalStrength = Math.max(0, Math.min(100, (rssi + 100) * 2));
                } else if (type == ConnectivityManager.TYPE_MOBILE) {
                    connectionType = "cellular";
                }
            }
            
            result.put("connectionType", connectionType);
            if (wifiSignalStrength != null) {
                result.put("wifiSignalStrength", wifiSignalStrength);
            } else {
                result.put("wifiSignalStrength", JSObject.NULL);
            }
            
            Log.d(TAG, "Device info collected: " + result.toString());
            call.resolve(result);
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting device info", e);
            call.reject("Failed to get device info: " + e.getMessage());
        }
    }
}
