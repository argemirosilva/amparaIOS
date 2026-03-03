package tech.orizon.ampara;

import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.util.Log;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "IconChanger")
public class IconChangerPlugin extends Plugin {

    private static final String TAG = "IconChanger";
    private static final String MAIN_ACTIVITY = ".MainActivity";
    private static final String[] ICON_ALIASES = {
        ".MainActivityAmpara",
        ".MainActivityWorkout",
        ".MainActivitySteps",
        ".MainActivityYoga",
        ".MainActivityCycle",
        ".MainActivityBeauty",
        ".MainActivityFashion",
        ".MainActivityPuzzle",
        ".MainActivityCards",
        ".MainActivityCasual"
    };

    @PluginMethod
    public void changeIcon(PluginCall call) {
        String targetAlias = call.getString("alias");
        Log.d(TAG, "changeIcon called with alias: " + targetAlias);

        if (targetAlias == null) {
            call.reject("Alias is required");
            return;
        }

        try {
            PackageManager packageManager = getContext().getPackageManager();
            String packageName = getContext().getPackageName();

            // 1. Habilitar/Desabilitar MainActivity principal
            int mainState = targetAlias.equals(".MainActivityAmpara") 
                ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED 
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            
            packageManager.setComponentEnabledSetting(
                new ComponentName(packageName, packageName + MAIN_ACTIVITY),
                mainState,
                PackageManager.DONT_KILL_APP
            );
            Log.d(TAG, "MainActivity state set to: " + mainState);

            // 2. Gerenciar Aliases
            for (String alias : ICON_ALIASES) {
                int state = alias.equals(targetAlias) 
                    ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
                
                packageManager.setComponentEnabledSetting(
                    new ComponentName(packageName, packageName + alias),
                    state,
                    PackageManager.DONT_KILL_APP
                );
                if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                    Log.d(TAG, "Enabled alias: " + alias);
                }
            }

            JSObject ret = new JSObject();
            ret.put("success", true);
            call.resolve(ret);
        } catch (Exception e) {
            Log.e(TAG, "Error in changeIcon", e);
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void getCurrentIcon(PluginCall call) {
        try {
            PackageManager packageManager = getContext().getPackageManager();
            String packageName = getContext().getPackageName();
            String activeAlias = ".MainActivityAmpara";

            int mainState = packageManager.getComponentEnabledSetting(new ComponentName(packageName, packageName + MAIN_ACTIVITY));
            if (mainState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED || mainState == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                activeAlias = ".MainActivityAmpara";
            } else {
                for (String alias : ICON_ALIASES) {
                    int state = packageManager.getComponentEnabledSetting(new ComponentName(packageName, packageName + alias));
                    if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                        activeAlias = alias;
                        break;
                    }
                }
            }

            JSObject ret = new JSObject();
            ret.put("alias", activeAlias);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }
}
