package tech.orizon.ampara.plugins;

import android.Manifest;
import android.content.pm.PackageManager;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

/**
 * Audio Permission Plugin
 * Handles native RECORD_AUDIO permission for Android
 */
@CapacitorPlugin(
    name = "AudioPermission",
    permissions = {
        @Permission(
            strings = { Manifest.permission.RECORD_AUDIO },
            alias = "microphone"
        )
    }
)
public class AudioPermissionPlugin extends Plugin {
    private static final String TAG = "AudioPermissionPlugin";

    @PluginMethod
    public void checkPermission(PluginCall call) {
        boolean granted = ContextCompat.checkSelfPermission(
            getContext(), 
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED;

        JSObject ret = new JSObject();
        ret.put("granted", granted);
        call.resolve(ret);
    }

    @PluginMethod
    public void requestPermission(PluginCall call) {
        // Check if already granted
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.RECORD_AUDIO) 
                == PackageManager.PERMISSION_GRANTED) {
            JSObject ret = new JSObject();
            ret.put("granted", true);
            call.resolve(ret);
            return;
        }

        // Request permission using Capacitor's permission system
        if (getPermissionState("microphone") != com.getcapacitor.PermissionState.GRANTED) {
            requestPermissionForAlias("microphone", call, "handlePermissionResult");
        } else {
            JSObject ret = new JSObject();
            ret.put("granted", true);
            call.resolve(ret);
        }
    }

    @PermissionCallback
    private void handlePermissionResult(PluginCall call) {
        boolean granted = getPermissionState("microphone") == com.getcapacitor.PermissionState.GRANTED;
        
        JSObject ret = new JSObject();
        ret.put("granted", granted);
        call.resolve(ret);
    }
}
