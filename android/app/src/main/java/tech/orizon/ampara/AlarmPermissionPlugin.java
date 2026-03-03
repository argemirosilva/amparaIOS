package tech.orizon.ampara;

import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "AlarmPermission")
public class AlarmPermissionPlugin extends Plugin {
    private static final String TAG = "AlarmPermission";

    @PluginMethod
    public void canScheduleExactAlarms(PluginCall call) {
        boolean canSchedule = true;
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager alarmManager = (AlarmManager) getContext().getSystemService(Context.ALARM_SERVICE);
            canSchedule = alarmManager.canScheduleExactAlarms();
            Log.d(TAG, "Can schedule exact alarms: " + canSchedule);
        } else {
            // Android < 12 doesn't need this permission
            Log.d(TAG, "Android < 12 - permission not required");
        }
        
        JSObject ret = new JSObject();
        ret.put("canSchedule", canSchedule);
        call.resolve(ret);
    }

    @PluginMethod
    public void requestScheduleExactAlarms(PluginCall call) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                intent.setData(Uri.parse("package:" + getContext().getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(intent);
                Log.i(TAG, "Opened alarm permission settings");
                call.resolve();
            } catch (Exception e) {
                Log.e(TAG, "Error opening alarm permission settings", e);
                call.reject("Failed to open settings", e);
            }
        } else {
            // Android < 12 doesn't need this permission
            Log.d(TAG, "Android < 12 - no action needed");
            call.resolve();
        }
    }
}
