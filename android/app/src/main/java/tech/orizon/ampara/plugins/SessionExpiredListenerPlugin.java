package tech.orizon.ampara.plugins;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Plugin para escutar broadcasts nativos de sessão expirada
 * Permite que o JavaScript seja notificado quando o ping nativo detecta HTTP 401
 */
@CapacitorPlugin(name = "SessionExpiredListener")
public class SessionExpiredListenerPlugin extends Plugin {
    
    private static final String TAG = "SessionExpiredListener";
    private BroadcastReceiver sessionExpiredReceiver;
    
    @Override
    public void load() {
        super.load();
        registerSessionExpiredReceiver();
    }
    
    @Override
    protected void handleOnDestroy() {
        super.handleOnDestroy();
        unregisterSessionExpiredReceiver();
    }
    
    private void registerSessionExpiredReceiver() {
        sessionExpiredReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.d(TAG, "Session expired broadcast received from native");
                
                // Notificar o JavaScript via evento
                JSObject ret = new JSObject();
                ret.put("source", intent.getStringExtra("source"));
                notifyListeners("sessionExpired", ret);
            }
        };
        
        IntentFilter filter = new IntentFilter("tech.orizon.ampara.SESSION_EXPIRED");
        getContext().registerReceiver(sessionExpiredReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        Log.d(TAG, "Session expired receiver registered");
    }
    
    private void unregisterSessionExpiredReceiver() {
        if (sessionExpiredReceiver != null) {
            try {
                getContext().unregisterReceiver(sessionExpiredReceiver);
                sessionExpiredReceiver = null;
                Log.d(TAG, "Session expired receiver unregistered");
            } catch (IllegalArgumentException e) {
                // Receiver já foi desregistrado
                Log.w(TAG, "Receiver was already unregistered");
            }
        }
    }
    
    @PluginMethod
    public void echo(PluginCall call) {
        String value = call.getString("value");
        JSObject ret = new JSObject();
        ret.put("value", value);
        call.resolve(ret);
    }
    
    /**
     * Método público para ser chamado diretamente pelo KeepAliveService
     * Notifica o JavaScript sobre sessão expirada
     */
    public void notifySessionExpiredFromService(String source) {
        Log.d(TAG, "Session expired notification received from service: " + source);
        JSObject ret = new JSObject();
        ret.put("source", source);
        notifyListeners("sessionExpired", ret);
        Log.d(TAG, "Session expired event sent to JavaScript");
    }
}
