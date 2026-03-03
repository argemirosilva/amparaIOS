package tech.orizon.ampara;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * BroadcastReceiver que recebe alarmes do AlarmManager e acorda o KeepAliveService
 * para executar pings mesmo em Doze Mode profundo.
 */
public class KeepAliveAlarmReceiver extends BroadcastReceiver {
    
    private static final String TAG = "KeepAliveAlarmReceiver";
    
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "Alarm received! Triggering native ping...");
        
        // Envia Intent para o KeepAliveService executar o ping
        Intent serviceIntent = new Intent(context, KeepAliveService.class);
        serviceIntent.setAction("ACTION_EXECUTE_PING");
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Android 8+: start as foreground service to avoid background-start restrictions
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
            Log.d(TAG, "KeepAliveService triggered successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error starting KeepAliveService: " + e.getMessage());
        }
    }
}
