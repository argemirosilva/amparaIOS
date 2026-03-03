# Filtros de Log - Monitoramento de Conectividade e Sessão

**Objetivo:** Monitorar pings nativos, renovação de sessão e tudo que mantém o app conectado ao servidor.

---

## 🎯 **FILTRO COMPLETO - TUDO EM UM**

### **Android Studio (Logcat):**

Cole na barra de pesquisa do Logcat:

```
package:tech.orizon.ampara tag:KeepAliveService|tag:KeepAlivePlugin|tag:KeepAliveAlarmReceiver|tag:SessionExpiredListener|tag:Capacitor/Console (Ping|ping|GPS|location|latitude|longitude|Token|token|Session|session|401|refresh|Refresh)
```

### **PowerShell (ADB):**

```powershell
C:\Users\Argemiro\AppData\Local\Android\Sdk\platform-tools\adb.exe logcat -s "KeepAliveService:*" "KeepAlivePlugin:*" "KeepAliveAlarmReceiver:*" "SessionExpiredListener:*" "Capacitor/Console:*" | Select-String -Pattern "Ping|ping|GPS|location|latitude|longitude|Token|token|Session|session|401|refresh|Refresh"
```

---

## 📡 **FILTROS ESPECÍFICOS**

### **1. PINGS NATIVOS (GPS + Conectividade)**

**Android Studio:**
```
package:tech.orizon.ampara tag:KeepAliveService (Ping payload|ping response|location|latitude|longitude|Next ping|Executing native)
```

**PowerShell:**
```powershell
adb logcat -s "KeepAliveService:*" | Select-String "Ping|location|latitude|longitude"
```

**Logs Esperados:**
```
[KeepAliveService] Service started with action: ACTION_EXECUTE_PING
[KeepAliveService] Executing native ping...
[KeepAliveService] Waiting up to 3s for GPS update...
[KeepAliveService] Fresh location update received: -8.729130, -63.856534
[KeepAliveService] Fresh GPS received within timeout
[KeepAliveService] Location obtained: -8.729130, -63.856534
[KeepAliveService] Sending location with ping: -8.729130, -63.856534
[KeepAliveService] Ping payload: {"action":"pingMobile",...,"latitude":-8.729130,"longitude":-63.856534,...}
[KeepAliveService] Native ping response code: 200
[KeepAliveService] Ping response (200): {"success":true,...}
[KeepAliveService] Next ping scheduled in 30s
```

---

### **2. RENOVAÇÃO DE SESSÃO (Token Refresh)**

**Android Studio:**
```
package:tech.orizon.ampara tag:KeepAliveService|tag:SessionExpiredListener|tag:Capacitor/Console (401|Session expired|Token|refresh|Refresh)
```

**PowerShell:**
```powershell
adb logcat -s "KeepAliveService:*" "SessionExpiredListener:*" "Capacitor/Console:*" | Select-String "401|Session|Token|refresh"
```

**Logs Esperados (quando sessão expira):**
```
[KeepAliveService] Native ping response code: 401
[KeepAliveService] Session expired (401): {"session_expired":true,...}
[KeepAliveService] Session expired confirmed! Notifying JavaScript to refresh token...
[KeepAliveService] Session expired notification sent directly to MainActivity
[SessionExpiredListener] Session expired event received from: native-ping
[Capacitor/Console] [App] Session expired event from Native: {...}
[Capacitor/Console] [App] Attempting to refresh token...
[Capacitor/Console] [TokenRefresh] Starting token refresh...
[Capacitor/Console] [TokenRefresh] Tokens refreshed successfully, updating storage...
[Capacitor/Console] [TokenRefresh] Token refresh complete!
[Capacitor/Console] [App] Token refreshed successfully, session restored
[KeepAliveService] Executing native ping...
[KeepAliveService] Native ping response code: 200
```

---

### **3. INICIALIZAÇÃO DO KEEPALIVE**

**Android Studio:**
```
package:tech.orizon.ampara tag:KeepAlivePlugin|tag:KeepAliveService|tag:Capacitor/Console (Starting KeepAlive|KeepAlive service|Service created|Service started)
```

**PowerShell:**
```powershell
adb logcat -s "KeepAlivePlugin:*" "KeepAliveService:*" "Capacitor/Console:*" | Select-String "KeepAlive|Service"
```

**Logs Esperados (após login):**
```
[Capacitor/Console] [App] Login success, updating auth state
[Capacitor/Console] [App] 🚀 Starting KeepAlive service after login...
[KeepAlivePlugin] Device ID synchronized: d4413360-1576-4421-aa3c-9e7e988d21c2
[KeepAlivePlugin] KeepAlive service started
[KeepAliveService] Service created
[KeepAliveService] WakeLock acquired and held: true
[KeepAliveService] Service started with action: null
[KeepAliveService] Foreground service started with type DATA_SYNC
[KeepAliveService] Executing native ping...
[KeepAliveService] Next ping scheduled in 30s
```

---

### **4. ALARMES E AGENDAMENTO**

**Android Studio:**
```
package:tech.orizon.ampara tag:KeepAliveAlarmReceiver|tag:KeepAliveService (Alarm|alarm|scheduled|triggered)
```

**PowerShell:**
```powershell
adb logcat -s "KeepAliveAlarmReceiver:*" "KeepAliveService:*" | Select-String "Alarm|scheduled"
```

**Logs Esperados:**
```
[KeepAliveService] Next ping scheduled in 30s (ExactAndAllowWhileIdle, panic=false)
[KeepAliveAlarmReceiver] KeepAlive alarm triggered
[KeepAliveAlarmReceiver] KeepAliveService triggered successfully
[KeepAliveService] Service started with action: ACTION_EXECUTE_PING
```

---

### **5. MODO PÂNICO**

**Android Studio:**
```
package:tech.orizon.ampara tag:KeepAliveService (panic|Panic|10s)
```

**PowerShell:**
```powershell
adb logcat -s "KeepAliveService:*" | Select-String "panic|10s"
```

**Logs Esperados:**
```
[KeepAliveService] Next ping scheduled in 10s (ExactAndAllowWhileIdle, panic=true)
[KeepAliveService] Ping payload: {...,"is_panic_active":true,...}
```

---

### **6. ERROS E WARNINGS**

**Android Studio:**
```
package:tech.orizon.ampara level:warn|level:error tag:KeepAliveService|tag:KeepAlivePlugin|tag:SessionExpiredListener
```

**PowerShell:**
```powershell
adb logcat -s "KeepAliveService:*" "KeepAlivePlugin:*" "SessionExpiredListener:*" "*:W" "*:E" | Select-String "KeepAlive|Session|Token"
```

**Logs a Investigar:**
```
[KeepAliveService] W  No location available
[KeepAliveService] W  GPS timeout, using last known location
[KeepAliveService] E  Error requesting single location update
[TokenRefresh] E  No refresh token available
[TokenRefresh] E  Refresh token invalid/expired, clearing session
[API] E  Token refresh failed, session expired
```

---

## 🔍 **FILTROS POR CENÁRIO**

### **Cenário 1: Testar Pings com Tela Bloqueada**

**Passos:**
1. Abrir app e fazer login
2. Bloquear tela
3. Aguardar 1-2 minutos
4. Desbloquear e verificar logs

**Filtro:**
```
package:tech.orizon.ampara tag:KeepAliveService (Executing|Ping payload|response code|latitude|Next ping)
```

**Validar:**
- ✅ Pings executados a cada 30s
- ✅ GPS enviado em todos os pings
- ✅ Response code 200

---

### **Cenário 2: Testar Renovação de Sessão**

**Passos:**
1. Esperar token expirar (ou forçar 401 no backend)
2. Verificar logs de refresh automático

**Filtro:**
```
package:tech.orizon.ampara tag:KeepAliveService|tag:SessionExpiredListener|tag:Capacitor/Console (401|Session expired|Token refresh|refreshed successfully)
```

**Validar:**
- ✅ 401 detectado
- ✅ Notificação enviada ao JavaScript
- ✅ Token refreshed
- ✅ Próximo ping com 200

---

### **Cenário 3: Testar Modo Pânico**

**Passos:**
1. Ativar modo pânico no app
2. Bloquear tela
3. Verificar pings a cada 10s

**Filtro:**
```
package:tech.orizon.ampara tag:KeepAliveService (panic|10s|Ping payload)
```

**Validar:**
- ✅ Pings a cada 10s
- ✅ `is_panic_active: true` no payload

---

### **Cenário 4: Monitoramento Contínuo (24/7)**

**PowerShell (salvar em arquivo):**
```powershell
adb logcat -s "KeepAliveService:*" "KeepAlivePlugin:*" "SessionExpiredListener:*" | Select-String "Ping|Token|Session|Error" | Tee-Object -FilePath "C:\logs\ampara_$(Get-Date -Format 'yyyyMMdd_HHmmss').log"
```

---

## 📊 **RESUMO DE TAGS**

| Tag | Função |
|-----|--------|
| `KeepAliveService` | Pings nativos, GPS, detecção de 401 |
| `KeepAlivePlugin` | Interface Java ↔ JavaScript |
| `KeepAliveAlarmReceiver` | Agendamento de alarmes |
| `SessionExpiredListener` | Notificação de sessão expirada |
| `Capacitor/Console` | Logs JavaScript (TokenRefresh, API) |

---

## 🎯 **FILTRO RECOMENDADO PARA USO DIÁRIO**

**Android Studio:**
```
package:tech.orizon.ampara tag:KeepAliveService|tag:Capacitor/Console (Ping payload|response code|latitude|Token refresh|Session expired) | level:warn | level:error
```

**PowerShell:**
```powershell
adb logcat -s "KeepAliveService:*" "Capacitor/Console:*" | Select-String "Ping|GPS|Token|Session|Error|Warning"
```

---

## 💡 **DICAS**

1. **Limpar logs antes de testar:**
   ```powershell
   adb logcat -c
   ```

2. **Salvar logs em arquivo:**
   ```powershell
   adb logcat > ampara_logs.txt
   ```

3. **Filtrar apenas erros críticos:**
   ```
   package:tech.orizon.ampara level:error
   ```

4. **Ver logs em tempo real com timestamp:**
   ```powershell
   adb logcat -v time
   ```

---

**Última atualização:** 01/02/2026
