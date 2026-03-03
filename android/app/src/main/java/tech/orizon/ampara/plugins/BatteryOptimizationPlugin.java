package tech.orizon.ampara.plugins;

import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.JSObject;

/**
 * Plugin para gerenciar otimização de bateria do Android e permissões de alarme
 */
@CapacitorPlugin(name = "BatteryOptimization")
public class BatteryOptimizationPlugin extends Plugin {
    
    private static final String TAG = "BatteryOptimization";
    
    /**
     * Verifica se o app está na whitelist de otimização de bateria
     */
    @PluginMethod
    public void isIgnoringBatteryOptimizations(PluginCall call) {
        try {
            JSObject ret = new JSObject();
            
            // 1. Check Battery Optimization
            boolean isIgnoring = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
                String packageName = getContext().getPackageName();
                isIgnoring = pm.isIgnoringBatteryOptimizations(packageName);
            }
            
            // 2. Check Exact Alarm Permission (Android 12+)
            boolean canScheduleExactAlarms = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AlarmManager alarmManager = (AlarmManager) getContext().getSystemService(Context.ALARM_SERVICE);
                canScheduleExactAlarms = alarmManager.canScheduleExactAlarms();
            }
            
            ret.put("isIgnoring", isIgnoring);
            ret.put("canScheduleExactAlarms", canScheduleExactAlarms);
            call.resolve(ret);
            
        } catch (Exception e) {
            Log.e(TAG, "Error checking battery/alarm status", e);
            call.reject("Failed to check status: " + e.getMessage());
        }
    }
    
    /**
     * Abre as configurações do sistema para o usuário desativar a otimização de bateria
     */
    @PluginMethod
    public void requestIgnoreBatteryOptimizations(PluginCall call) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent intent = new Intent();
                intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getContext().getPackageName()));
                getActivity().startActivity(intent);
                Log.d(TAG, "Opened battery optimization settings");
            }
            call.resolve();
        } catch (Exception e) {
            Log.e(TAG, "Error requesting battery optimization exemption", e);
            call.reject("Failed to open battery settings: " + e.getMessage());
        }
    }

    /**
     * Abre as configurações para permitir alarmes exatos (Android 12+)
     */
    @PluginMethod
    public void requestExactAlarmPermission(PluginCall call) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent intent = new Intent();
                intent.setAction(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                intent.setData(Uri.parse("package:" + getContext().getPackageName()));
                getActivity().startActivity(intent);
                Log.d(TAG, "Opened exact alarm settings");
            }
            call.resolve();
        } catch (Exception e) {
            Log.e(TAG, "Error requesting exact alarm permission", e);
            call.reject("Failed to open alarm settings: " + e.getMessage());
        }
    }
}
