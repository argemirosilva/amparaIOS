package tech.orizon.ampara;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import android.location.Location;
import android.location.LocationManager;
import androidx.core.app.ActivityCompat;
import android.content.pm.PackageManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Serviço de foreground para manter o app ativo em Doze Mode profundo.
 * Implementa ping nativo e usa AlarmManager para acordar a rede.
 */
public class KeepAliveService extends Service {

    private static final String TAG = "KeepAliveService";
    private static final String CHANNEL_ID = "ampara_keepalive";
    private static final int NOTIFICATION_ID = 9999;
    private static final String WAKELOCK_TAG = "Ampara::KeepAliveLock";
    // Trilha 1: evitar wakelock infinito em caso de crash
    private static final long WAKELOCK_TIMEOUT_MS = 2 * 60 * 1000L; // 2 min

    // Tempo máximo de vida do service antes de restart (5 minutos para evitar
    // timeout do dataSync)
    private static final long MAX_SERVICE_LIFETIME_MS = 5 * 60 * 1000; // 5 minutos
    private long serviceStartTime;

    // Nome das SharedPreferences usado pelo SecureStoragePlugin
    private static final String PREFS_NAME = "ampara_secure_storage";
    private static final String API_URL = "https://uogenwcycqykfsuongrl.supabase.co/functions/v1/mobile-api";
    private static final String API_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InVvZ2Vud2N5Y3F5a2ZzdW9uZ3JsIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzA4Mjg2NjIsImV4cCI6MjA4NjQwNDY2Mn0.hncTs6DDS-sbb8sT_QBOBf1mTcTu0e_Pc5yXo4tHZwE";

    private PowerManager.WakeLock wakeLock;
    private ExecutorService executorService;
    private AlarmManager alarmManager;
    private PendingIntent alarmIntent;
    private PanicManager panicManager;
    private final android.os.Handler loopHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean isLoopRunning = false;
    private int loopCounter = 0; // Contador de execuções para debouncing do alarme

    private final Runnable pingLoopRunnable = new Runnable() {
        @Override
        public void run() {
            boolean isPanic = panicManager.isPanicActive();
            boolean isCritical = panicManager.isCriticalAlert();

            // Determinar intervalo dinâmico
            long interval;
            if (isPanic) {
                interval = 1000; // 1s pânico
            } else if (isCritical) {
                interval = 30000; // 30s alerta
            } else {
                interval = 60000; // 60s normal (Android 15 resilience)
            }

            Log.d(TAG, "Loop execution - Interval: " + (interval / 1000) + "s (Panic=" + isPanic + ", Alert="
                    + isCritical + ")");

            executeNativePing();
            executeNativeLocationUpdate();

            // Sincronizar alarme de segurança a cada ciclo (Safety Net)
            // Agendamos para 5 minutos no futuro. Se o loop rodar em 60s, ele "empurra" o
            // alarme.
            // Se o loop travar, o alarme ressucita o app em 5 minutos.
            maintainAlarmFallback(300000); // 5 minutos fixos de margem

            loopHandler.removeCallbacks(this);
            loopHandler.postDelayed(this, interval);
        }
    };

    // Para aguardar atualização de GPS
    private volatile Location freshLocation = null;
    private java.util.concurrent.CountDownLatch locationLatch;

    // Cache persistente de GPS (válido por 5 minutos)
    private volatile Location cachedLocation = null;
    private volatile long cachedLocationTime = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service created - Build: 2026-02-27 09:10");
        // Migrar para pool de threads para suportar ping + gps em paralelo
        executorService = Executors.newFixedThreadPool(3);
        alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        panicManager = new PanicManager(this);
        serviceStartTime = System.currentTimeMillis();

        createNotificationChannel();
        acquireWakeLock();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        Log.d(TAG, "Service started with action: " + action);

        Notification notification = createNotification();
        boolean startedForeground = false;

        // Android 14+ (API 34+) requer tipo de foreground service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                // Prioriza SPECIAL_USE conforme definido no Manifest para o Keep-Alive.
                // Inclui LOCATION apenas se a permissão estiver garantida.
                int fgsTypes = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;

                if (ActivityCompat.checkSelfPermission(this,
                        android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                        ActivityCompat.checkSelfPermission(this,
                                android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    fgsTypes |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
                    Log.d(TAG, "Location permission granted, adding LOCATION type to FGS");
                }

                startForeground(NOTIFICATION_ID, notification, fgsTypes);
                startedForeground = true;
                Log.d(TAG, "Foreground service started with types: " + fgsTypes);
            } catch (SecurityException se) {
                Log.e(TAG, "SecurityException starting FGS: " + se.getMessage());
                // Fallback para o modo mais básico (SPECIAL_USE) que já tem propriedade no
                // Manifest
                try {
                    startForeground(NOTIFICATION_ID, notification,
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                    startedForeground = true;
                    Log.d(TAG, "Fallback: Foreground service started with SPECIAL_USE type only");
                } catch (SecurityException se2) {
                    Log.e(TAG, "Fatal SecurityException starting FGS fallback: " + se2.getMessage());
                }
            }
        } else {
            try {
                startForeground(NOTIFICATION_ID, notification);
                startedForeground = true;
                Log.d(TAG, "Foreground service started (Legacy)");
            } catch (Exception e) {
                Log.e(TAG, "Error starting foreground service: " + e.getMessage());
            }
        }

        if (!startedForeground) {
            Log.e(TAG, "FAILED to start foreground service explicitly. Attempting to continue in grace period.");
            // No Android 14+, não chamamos stopSelf() imediatamente para permitir que o
            // AlarmManager
            // tente recuperar o serviço no próximo ciclo ou que o sistema processe o
            // estado.
        }

        // Debouncer: Se o loop já está rodando e recebemos um start genérico, ignoramos
        // mas garantimos que o foreground está OK.
        if (isLoopRunning && (action == null || "ACTION_EXECUTE_PING".equals(action))) {
            Log.d(TAG, "Service update: Loop already running, skipping redundant init");
            return START_STICKY;
        }

        if (!isLoopRunning) {
            isLoopRunning = true;
            Log.d(TAG, "Starting unified persistence loop...");
            loopHandler.post(pingLoopRunnable);
        }

        return START_STICKY;
    }

    private void maintainAlarmFallback(long nextTriggerInMs) {
        // Usa BroadcastReceiver ao invés de reiniciar o serviço
        Intent intent = new Intent(this, KeepAliveAlarmReceiver.class);
        intent.setAction("tech.orizon.ampara.KEEPALIVE_ALARM");

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        alarmIntent = PendingIntent.getBroadcast(this, 0, intent, flags);

        long triggerAtMillis = System.currentTimeMillis() + nextTriggerInMs;
        setAlarm(triggerAtMillis, nextTriggerInMs);
    }

    private void setAlarm(long triggerAtMillis, long intervalMillis) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Para backup, usamos setAndAllowWhileIdle (menos agressivo que setExact)
                // Isso evita bloqueios de cota por excesso de "Exact Alarms".
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, alarmIntent);
                Log.d(TAG, "Backup alarm pushed to " + (intervalMillis / 1000) + "s (AllowWhileIdle)");
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, alarmIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error setting backup alarm", e);
        }
    }

    private void executeNativePing() {
        executorService.execute(() -> {
            Log.d(TAG, "Executing native ping...");

            // Garantir que o WakeLock está ativo durante o ping
            acquireWakeLock();

            HttpURLConnection conn = null;
            try {
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                String token = prefs.getString("ampara_token", null);
                String userJson = prefs.getString("ampara_user", null);
                String deviceId = prefs.getString("ampara_device_id", "native-android-fallback");

                String email = null;
                if (userJson != null) {
                    try {
                        JSONObject userObj = new JSONObject(userJson);
                        email = userObj.getString("email");
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing user JSON: " + e.getMessage());
                    }
                }

                if (token == null) {
                    Log.w(TAG, "No token found in SecureStorage, skipping native ping");
                    return;
                }

                URL url = new URL(API_URL);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("apikey", API_KEY);
                conn.setDoOutput(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);

                // Coletar informações do dispositivo
                JSONObject deviceInfo = collectDeviceInfo();

                JSONObject payload = new JSONObject();
                payload.put("action", "pingMobile");
                payload.put("session_token", token);
                payload.put("device_id", deviceId);
                if (email != null) {
                    payload.put("email_usuario", email);
                }

                // Adicionar informações do dispositivo
                payload.put("device_model", deviceInfo.getString("device_model"));
                payload.put("battery_level", deviceInfo.getInt("battery_level")); // Mantendo por compatibilidade
                payload.put("bateria_percentual", deviceInfo.getInt("battery_level")); // Novo padrão
                payload.put("is_charging", deviceInfo.getBoolean("is_charging")); // Mantendo por compatibilidade
                payload.put("bateria_carregando", deviceInfo.getBoolean("is_charging")); // Novo padrão
                payload.put("android_version", deviceInfo.getString("android_version"));
                payload.put("app_version", deviceInfo.getString("app_version"));
                payload.put("is_ignoring_battery_optimization",
                        deviceInfo.getBoolean("is_ignoring_battery_optimization"));
                payload.put("connection_type", deviceInfo.getString("connection_type"));
                if (!deviceInfo.isNull("wifi_signal_strength")) {
                    payload.put("wifi_signal_strength", deviceInfo.getInt("wifi_signal_strength"));
                }

                // Adicionar localização GPS
                Location location = getLastLocation();
                if (location != null) {
                    payload.put("latitude", location.getLatitude());
                    payload.put("longitude", location.getLongitude());
                    payload.put("location_accuracy", location.getAccuracy());
                    payload.put("location_timestamp", location.getTime());
                    Log.d(TAG,
                            "Sending location with ping: " + location.getLatitude() + ", " + location.getLongitude());
                } else {
                    Log.w(TAG, "No location available to send with ping");
                }

                String jsonInputString = payload.toString();
                Log.d(TAG, "Ping payload: " + jsonInputString);

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = jsonInputString.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                int code = conn.getResponseCode();
                Log.d(TAG, "Native ping response code: " + code);

                // Tratamento especial para HTTP 401 ou 403 (Sessão Expirada ou Token Inválido)
                if (code == 401 || code == 403) {
                    Log.e(TAG, "Session unauthorized (" + code + "). Attempting native token refresh...");

                    // Tentar renovar token direto no native (funciona com tela bloqueada)
                    boolean refreshed = refreshTokenNative();

                    if (refreshed) {
                        Log.d(TAG, "Token refreshed successfully! Retrying ping with new token...");
                        // Reexecutar ping com novo token (apenas 1 tentativa)
                        executeNativePingRetry();
                    } else {
                        Log.e(TAG, "Native token refresh failed, notifying JavaScript session expired...");
                        notifyJavaScriptSessionExpired();
                    }
                    return;
                } else if (code == 200) {
                    // Log successful response
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        StringBuilder response = new StringBuilder();
                        String responseLine;
                        while ((responseLine = br.readLine()) != null) {
                            response.append(responseLine.trim());
                        }
                        Log.d(TAG, "Ping response (200): " + response.toString());
                    }
                } else {
                    // Log error response
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                        StringBuilder response = new StringBuilder();
                        String responseLine;
                        while ((responseLine = br.readLine()) != null) {
                            response.append(responseLine.trim());
                        }
                        Log.e(TAG, "Native ping error response: " + response.toString());
                    }
                }

            } catch (Exception e) {
                Log.e(TAG, "Error in native ping: " + e.getMessage());
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }

    /**
     * Renova o token de acesso usando o refresh_token (direto no native, funciona
     * com tela bloqueada)
     * 
     * @return true se renovado com sucesso, false caso contrário
     */
    private boolean refreshTokenNative() {
        HttpURLConnection conn = null;
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String refreshToken = prefs.getString("ampara_refresh_token", null);
            String deviceId = prefs.getString("ampara_device_id", "native-android-fallback");

            if (refreshToken == null) {
                Log.e(TAG, "No refresh token found in storage");
                return false;
            }

            Log.d(TAG, "Attempting to refresh token with refresh_token...");

            URL url = new URL(API_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("apikey", API_KEY);
            conn.setDoOutput(true);
            conn.setConnectTimeout(60000); // 60 segundos para conectar
            conn.setReadTimeout(60000); // 60 segundos para ler resposta

            JSONObject payload = new JSONObject();
            payload.put("action", "refresh_token");
            payload.put("refresh_token", refreshToken);
            payload.put("device_id", deviceId);

            String jsonInputString = payload.toString();
            Log.d(TAG, "Refresh token payload: " + jsonInputString);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonInputString.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int code = conn.getResponseCode();
            Log.d(TAG, "Refresh token response code: " + code);

            if (code == 200) {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        response.append(responseLine.trim());
                    }

                    JSONObject responseJson = new JSONObject(response.toString());

                    if (responseJson.optBoolean("success", false)) {
                        String newAccessToken = responseJson.optString("access_token", null);
                        String newRefreshToken = responseJson.optString("refresh_token", null);

                        if (newAccessToken != null && newRefreshToken != null) {
                            // Salvar novos tokens no SharedPreferences
                            SharedPreferences.Editor editor = prefs.edit();
                            editor.putString("ampara_token", newAccessToken);
                            editor.putString("ampara_refresh_token", newRefreshToken);
                            editor.apply();

                            Log.d(TAG, "Tokens refreshed and saved successfully!");
                            return true;
                        } else {
                            Log.e(TAG, "Refresh response missing tokens");
                            return false;
                        }
                    } else {
                        Log.e(TAG, "Refresh response success=false: " + response.toString());
                        return false;
                    }
                }
            } else {
                // Log error response
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        response.append(responseLine.trim());
                    }
                    Log.e(TAG, "Refresh token error (" + code + "): " + response.toString());
                }
                return false;
            }

        } catch (Exception e) {
            Log.e(TAG, "Exception during token refresh: " + e.getMessage(), e);
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Reexecuta o ping após renovação de token (sem recursividade infinita)
     */
    private void executeNativePingRetry() {
        executorService.execute(() -> {
            Log.d(TAG, "Retrying ping with refreshed token...");

            HttpURLConnection conn = null;
            try {
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                String token = prefs.getString("ampara_token", null);
                String userJson = prefs.getString("ampara_user", null);
                String deviceId = prefs.getString("ampara_device_id", "native-android-fallback");

                String email = null;
                if (userJson != null) {
                    try {
                        JSONObject userObj = new JSONObject(userJson);
                        email = userObj.getString("email");
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing user JSON: " + e.getMessage());
                    }
                }

                if (token == null) {
                    Log.w(TAG, "No token found after refresh, aborting retry");
                    return;
                }

                URL url = new URL(API_URL);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("apikey", API_KEY);
                conn.setDoOutput(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);

                JSONObject deviceInfo = collectDeviceInfo();

                JSONObject payload = new JSONObject();
                payload.put("action", "pingMobile");
                payload.put("session_token", token);
                payload.put("device_id", deviceId);
                if (email != null) {
                    payload.put("email_usuario", email);
                }

                payload.put("device_model", deviceInfo.getString("device_model"));
                payload.put("battery_level", deviceInfo.getInt("battery_level")); // Mantendo por compatibilidade
                payload.put("bateria_percentual", deviceInfo.getInt("battery_level")); // Novo padrão
                payload.put("is_charging", deviceInfo.getBoolean("is_charging")); // Mantendo por compatibilidade
                payload.put("bateria_carregando", deviceInfo.getBoolean("is_charging")); // Novo padrão
                payload.put("android_version", deviceInfo.getString("android_version"));
                payload.put("app_version", deviceInfo.getString("app_version"));
                payload.put("is_ignoring_battery_optimization",
                        deviceInfo.getBoolean("is_ignoring_battery_optimization"));
                payload.put("connection_type", deviceInfo.getString("connection_type"));
                if (!deviceInfo.isNull("wifi_signal_strength")) {
                    payload.put("wifi_signal_strength", deviceInfo.getInt("wifi_signal_strength"));
                }

                Location location = getLastLocation();
                if (location != null) {
                    payload.put("latitude", location.getLatitude());
                    payload.put("longitude", location.getLongitude());
                    payload.put("location_accuracy", location.getAccuracy());
                    payload.put("location_timestamp", location.getTime());
                }

                String jsonInputString = payload.toString();
                Log.d(TAG, "Retry ping payload: " + jsonInputString);

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = jsonInputString.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                int code = conn.getResponseCode();
                Log.d(TAG, "Retry ping response code: " + code);

                if (code == 200) {
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        StringBuilder response = new StringBuilder();
                        String responseLine;
                        while ((responseLine = br.readLine()) != null) {
                            response.append(responseLine.trim());
                        }
                        Log.d(TAG, "Retry ping success (200): " + response.toString());
                    }
                } else {
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                        StringBuilder response = new StringBuilder();
                        String responseLine;
                        while ((responseLine = br.readLine()) != null) {
                            response.append(responseLine.trim());
                        }
                        Log.e(TAG, "Retry ping failed (" + code + "): " + response.toString());
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in retry ping: " + e.getMessage());
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }

    /**
     * Executa a atualização de localização GPS nativa (action:
     * enviarLocalizacaoGPS)
     * Garante o rastro completo (velocidade, direção, precisão) mesmo em
     * background.
     */
    private void executeNativeLocationUpdate() {
        executorService.execute(() -> {
            Log.d(TAG, "Executing native location update (enviarLocalizacaoGPS)...");

            // Garantir que o WakeLock está ativo durante a transmissão
            acquireWakeLock();

            HttpURLConnection conn = null;
            try {
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                String token = prefs.getString("ampara_token", null);
                String userJson = prefs.getString("ampara_user", null);
                String deviceId = prefs.getString("ampara_device_id", "native-android-fallback");

                String email = null;
                if (userJson != null) {
                    try {
                        JSONObject userObj = new JSONObject(userJson);
                        email = userObj.optString("email", null);
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing user JSON for location update: " + e.getMessage());
                    }
                }

                if (token == null) {
                    Log.w(TAG, "No token found for native location update");
                    return;
                }

                Location location = getLastLocation();
                if (location == null) {
                    Log.w(TAG, "No location available for native update");
                    return;
                }

                URL url = new URL(API_URL);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("apikey", API_KEY);
                conn.setDoOutput(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);

                JSONObject payload = new JSONObject();
                payload.put("action", "enviarLocalizacaoGPS");
                payload.put("session_token", token);
                payload.put("device_id", deviceId);
                if (email != null) {
                    payload.put("email_usuario", email);
                }

                // Dados de telemetria
                payload.put("latitude", location.getLatitude());
                payload.put("longitude", location.getLongitude());
                payload.put("speed", location.hasSpeed() ? location.getSpeed() : JSONObject.NULL);
                payload.put("heading", location.hasBearing() ? location.getBearing() : JSONObject.NULL);
                payload.put("precisao_metros", location.getAccuracy());

                // Coletar informações extras via collectDeviceInfo
                JSONObject deviceInfo = collectDeviceInfo();
                payload.put("bateria_percentual", deviceInfo.getInt("battery_level"));
                payload.put("timezone", deviceInfo.getString("timezone"));
                payload.put("timezone_offset_minutes", deviceInfo.getInt("timezone_offset_minutes"));

                // Timestamp GPS formatado em ISO 8601 (exato para o backend)
                String isoTimestamp = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                        java.util.Locale.US)
                        .format(new java.util.Date(location.getTime()));
                payload.put("timestamp_gps", isoTimestamp);

                String jsonInputString = payload.toString();
                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = jsonInputString.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                int code = conn.getResponseCode();
                Log.d(TAG, "Native location update response: " + code);

                // Se falhar por token, tenta renovar (o próximo ciclo usará o novo token)
                if (code == 401 || code == 403) {
                    Log.e(TAG, "Location update unauthorized (" + code + "). Refreshing token...");
                    refreshTokenNative();
                }

            } catch (Exception e) {
                Log.e(TAG, "Error in native location update: " + e.getMessage());
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Service destroyed");
        if (alarmManager != null && alarmIntent != null) {
            alarmManager.cancel(alarmIntent);
        }
        if (loopHandler != null) {
            loopHandler.removeCallbacks(pingLoopRunnable);
        }
        isLoopRunning = false;
        if (executorService != null) {

            executorService.shutdown();
        }
        releaseWakeLock();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d(TAG, "Task removed from recents. Ensuring persistence...");
        // Garantir alarme de backup para reinício
        maintainAlarmFallback(30000);
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Sistema",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Processamento interno do sistema");
            channel.setShowBadge(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, flags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Sistema")
                .setContentText("Processamento em execução")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }

    private void acquireWakeLock() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager != null && (wakeLock == null || !wakeLock.isHeld())) {
                if (wakeLock == null) {
                    wakeLock = powerManager.newWakeLock(
                            PowerManager.PARTIAL_WAKE_LOCK,
                            WAKELOCK_TAG);
                }
                wakeLock.acquire(WAKELOCK_TIMEOUT_MS);
                Log.d(TAG, "WakeLock acquired (timeout ms=" + WAKELOCK_TIMEOUT_MS + ") held=" + wakeLock.isHeld());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error acquiring WakeLock", e);
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                wakeLock = null;
                Log.d(TAG, "WakeLock released");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error releasing WakeLock", e);
        }
    }

    /**
     * Coleta informações do dispositivo para enviar no ping
     */
    private JSONObject collectDeviceInfo() {
        JSONObject info = new JSONObject();
        try {
            // Nome do modelo do dispositivo
            String deviceModel = Build.MANUFACTURER + " " + Build.MODEL;
            info.put("device_model", deviceModel);

            // Informações de bateria
            BatteryManager batteryManager = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
            int batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            info.put("battery_level", batteryLevel);

            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = registerReceiver(null, ifilter);
            int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL;
            info.put("is_charging", isCharging);

            // Versão do Android
            info.put("android_version", Build.VERSION.RELEASE);

            // Versão do app
            try {
                String appVersion = getPackageManager()
                        .getPackageInfo(getPackageName(), 0).versionName;
                info.put("app_version", appVersion);
            } catch (Exception e) {
                info.put("app_version", "unknown");
            }

            // Status de otimização de bateria
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            boolean isIgnoringBatteryOptimization = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                isIgnoringBatteryOptimization = powerManager.isIgnoringBatteryOptimizations(getPackageName());
            }
            info.put("is_ignoring_battery_optimization", isIgnoringBatteryOptimization);

            // Tipo de conexão e força do sinal
            String connectionType = "none";
            Integer wifiSignalStrength = null;

            try {
                ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(
                        Context.CONNECTIVITY_SERVICE);
                NetworkInfo activeNetwork = connectivityManager.getActiveNetworkInfo();

                if (activeNetwork != null && activeNetwork.isConnected()) {
                    int type = activeNetwork.getType();
                    if (type == ConnectivityManager.TYPE_WIFI) {
                        connectionType = "wifi";

                        // Obter força do sinal WiFi
                        try {
                            WifiManager wifiManager = (WifiManager) getApplicationContext()
                                    .getSystemService(Context.WIFI_SERVICE);
                            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                            int rssi = wifiInfo.getRssi();

                            // Converter RSSI para porcentagem (0-100)
                            wifiSignalStrength = Math.max(0, Math.min(100, (rssi + 100) * 2));
                        } catch (SecurityException e) {
                            Log.w(TAG, "WiFi signal strength unavailable (permission denied)");
                        }
                    } else if (type == ConnectivityManager.TYPE_MOBILE) {
                        connectionType = "cellular";
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Error getting connection type", e);
            }

            info.put("connection_type", connectionType);
            if (wifiSignalStrength != null) {
                info.put("wifi_signal_strength", wifiSignalStrength);
            } else {
                info.put("wifi_signal_strength", JSONObject.NULL);
            }

            // Timezone do dispositivo
            try {
                java.util.TimeZone tz = java.util.TimeZone.getDefault();
                String timezone = tz.getID(); // Ex: "America/Porto_Velho"
                int offsetMillis = tz.getRawOffset();
                int timezone_offset_minutes = offsetMillis / (1000 * 60); // Converter para minutos

                info.put("timezone", timezone);
                info.put("timezone_offset_minutes", timezone_offset_minutes);

                Log.d(TAG, "[TZ] Timezone: " + timezone + ", offset: " + timezone_offset_minutes + " minutes");
            } catch (Exception e) {
                Log.w(TAG, "Error getting timezone", e);
                // Fallback: UTC
                info.put("timezone", "UTC");
                info.put("timezone_offset_minutes", 0);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error collecting device info", e);
        }
        return info;
    }

    /**
     * Obtém a última localização conhecida do dispositivo
     * Retorna null se não houver permissão ou localização disponível
     * PRIORIZA GPS real sobre localização por rede/WiFi
     */
    private Location getLastLocation() {
        try {
            // Verificar permissões
            if (ActivityCompat.checkSelfPermission(this,
                    android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(this,
                            android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Location permission not granted");
                return null;
            }

            LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (locationManager == null) {
                Log.w(TAG, "LocationManager not available");
                return null;
            }

            // Verificar cache persistente primeiro (válido por 60 segundos)
            long now = System.currentTimeMillis();
            if (cachedLocation != null && (now - cachedLocationTime) < 60000) {
                Log.d(TAG, "Using cached location from " + ((now - cachedLocationTime) / 1000) + "s ago" +
                        " provider=" + cachedLocation.getProvider());
                return cachedLocation;
            }

            // Tentar GPS e Network
            Location gpsLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location networkLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);

            // Determinar a melhor localização disponível agora
            Location bestCurrent = null;
            if (gpsLocation != null && (now - gpsLocation.getTime()) < 120000) {
                bestCurrent = gpsLocation; // GPS fresco é o melhor
            } else if (networkLocation != null && (now - networkLocation.getTime()) < 60000) {
                bestCurrent = networkLocation; // Network fresca é a segunda melhor
            }

            // Se não tem localização realmente recente, solicitar atualização fresca
            if (bestCurrent == null) {
                Log.d(TAG, "No recent location available, requesting fresh GPS/Network fix...");

                // Reset latch e fresh location
                freshLocation = null;
                locationLatch = new java.util.concurrent.CountDownLatch(1);

                requestSingleLocationUpdate(locationManager);

                // Aguardar até 8 segundos
                try {
                    Log.d(TAG, "Waiting up to 8s for fresh location...");
                    boolean received = locationLatch.await(8, java.util.concurrent.TimeUnit.SECONDS);
                    if (received && freshLocation != null) {
                        bestCurrent = freshLocation;
                    } else {
                        Log.w(TAG, "Location timeout after 8s, picking most recent available...");
                        // FALLBACK: Comparar GPS e Network e pegar o mais RECENTE se um for muito
                        // antigo
                        if (gpsLocation != null && networkLocation != null) {
                            bestCurrent = (gpsLocation.getTime() > networkLocation.getTime()) ? gpsLocation
                                    : networkLocation;
                        } else {
                            bestCurrent = (gpsLocation != null) ? gpsLocation : networkLocation;
                        }
                    }
                } catch (InterruptedException e) {
                    Log.w(TAG, "Location wait interrupted", e);
                }
            }
            Location location = bestCurrent;

            if (location != null) {
                Log.d(TAG, String.format("Location obtained: %.6f, %.6f (provider=%s, accuracy=%.1fm)",
                        location.getLatitude(), location.getLongitude(),
                        location.getProvider(), location.getAccuracy()));
                // Atualizar cache persistente
                cachedLocation = location;
                cachedLocationTime = System.currentTimeMillis();
            } else {
                Log.w(TAG, "No location available");
            }

            return location;
        } catch (Exception e) {
            Log.e(TAG, "Error getting location", e);
            return null;
        }
    }

    /**
     * Solicita uma única atualização de localização para atualizar o cache
     * Solicita de AMBOS os provedores (GPS + Network) - o primeiro a responder
     * ganha
     */
    private void requestSingleLocationUpdate(LocationManager locationManager) {
        try {
            boolean gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
            boolean networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);

            if (!gpsEnabled && !networkEnabled) {
                Log.w(TAG, "No location providers enabled");
                if (locationLatch != null)
                    locationLatch.countDown();
                return;
            }

            if (ActivityCompat.checkSelfPermission(this,
                    android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this,
                            android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {

                android.location.LocationListener listener = new android.location.LocationListener() {
                    @Override
                    public void onLocationChanged(Location location) {
                        Log.d(TAG, "Fresh location update: " + location.getLatitude() + ", " +
                                location.getLongitude() + " (provider=" + location.getProvider() +
                                ", accuracy=" + location.getAccuracy() + "m)");
                        // Preferir GPS sobre Network se já temos uma resposta
                        if (freshLocation == null || LocationManager.GPS_PROVIDER.equals(location.getProvider())) {
                            freshLocation = location;
                        }
                        if (locationLatch != null) {
                            locationLatch.countDown();
                        }
                        // Remover listener para não receber mais updates
                        try {
                            locationManager.removeUpdates(this);
                        } catch (SecurityException e) {
                            // ignore
                        }
                    }

                    @Override
                    public void onStatusChanged(String provider, int status, android.os.Bundle extras) {
                    }

                    @Override
                    public void onProviderEnabled(String provider) {
                    }

                    @Override
                    public void onProviderDisabled(String provider) {
                    }
                };

                // Solicitar de ambos os provedores no main looper
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    try {
                        if (gpsEnabled) {
                            locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, null);
                            Log.d(TAG, "Requested GPS provider update");
                        }
                        if (networkEnabled) {
                            locationManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, null);
                            Log.d(TAG, "Requested Network provider update");
                        }
                    } catch (SecurityException e) {
                        Log.e(TAG, "Location permission denied during requestSingleUpdate", e);
                        if (locationLatch != null)
                            locationLatch.countDown();
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "Error requesting single location update", e);
            if (locationLatch != null)
                locationLatch.countDown();
        }
    }

    /**
     * Notifica o JavaScript que a sessão expirou via Broadcast global
     */
    private void notifyJavaScriptSessionExpired() {
        try {
            // Notificar diretamente via MainActivity (mais confiável que broadcast)
            tech.orizon.ampara.MainActivity.notifySessionExpired("native-ping");
            Log.d(TAG, "Session expired notification sent directly to MainActivity");
        } catch (Exception e) {
            Log.e(TAG, "Error sending session expired notification", e);
        }
    }
}
