# 📋 Captura de Logs Noturna - Ampara

## 🎯 **Objetivo**

Capturar logs durante a noite para validar:
- ✅ Pings nativos funcionando (a cada 30s)
- ✅ GPS sendo enviado
- ✅ Manutenção de sessão (renovação automática)
- ✅ Monitoramento de áudio em background
- ✅ AudioFocus (liberação de microfone)

---

## 📁 **Scripts Disponíveis**

### **1. capture_overnight_logs.ps1** (COMPLETO)
**Captura:** Pings + Sessão + AudioTrigger + AudioFocus

**Uso:**
```powershell
.\capture_overnight_logs.ps1
```

**Logs Capturados:**
- KeepAliveService (pings, GPS)
- KeepAliveAlarmReceiver (alarmes)
- SessionExpiredListener (renovação de sessão)
- AudioTriggerService (monitoramento de áudio)
- AudioTriggerPlugin (comandos JS)
- Capacitor/Console (logs JavaScript)

**Arquivo Gerado:**
```
overnight_logs\ampara_debug_YYYYMMDD_HHMMSS.txt
```

---

### **2. capture_ping_session_logs.ps1** (LIMPO)
**Captura:** APENAS Pings + Sessão (SEM AudioTrigger)

**Uso:**
```powershell
.\capture_ping_session_logs.ps1
```

**Logs Capturados:**
- Pings nativos
- GPS (latitude, longitude)
- Resposta do servidor (200/401)
- Renovação de token

**Filtros Aplicados:**
- ✅ Ping, GPS, Token, Session
- ❌ SEM AudioTrigger (limpo)

**Arquivo Gerado:**
```
overnight_logs\ping_session_YYYYMMDD_HHMMSS.txt
```

---

## 🚀 **Como Usar**

### **Passo 1: Preparar App**
```powershell
# Abrir app e fazer login
# Iniciar monitoramento (se necessário)
# Deixar app em background
```

### **Passo 2: Iniciar Captura**
```powershell
# Opção A: Logs completos (com AudioTrigger)
.\capture_overnight_logs.ps1

# Opção B: Apenas pings e sessão (limpo)
.\capture_ping_session_logs.ps1
```

### **Passo 3: Deixar Rodando**
- Script roda continuamente
- Salva em arquivo `.txt`
- Mostra logs em tempo real na tela

### **Passo 4: Parar Captura**
```
Pressione Ctrl+C
```

### **Passo 5: Analisar Logs**
```powershell
# Abrir arquivo gerado
notepad .\overnight_logs\ampara_debug_YYYYMMDD_HHMMSS.txt

# Ou buscar por palavras-chave
Select-String -Path ".\overnight_logs\ampara_debug_*.txt" -Pattern "401|refresh"
```

---

## 🔍 **O Que Procurar nos Logs**

### **✅ Pings Funcionando (a cada 30s):**
```
[KeepAliveAlarmReceiver] Alarm received! Triggering native ping...
[KeepAliveService] Executing native ping...
[KeepAliveService] Location obtained: -8.729130, -63.856534
[KeepAliveService] Ping payload: {...,"latitude":-8.729130,...}
[KeepAliveService] Native ping response code: 200
[KeepAliveService] Next ping scheduled in 30s
```

### **✅ GPS Sendo Enviado:**
```
[KeepAliveService] Location obtained: -8.729130, -63.856534
[KeepAliveService] Ping payload: {...,"latitude":-8.729130,"longitude":-63.856534,...}
```

### **✅ Renovação de Sessão (401 → Refresh):**
```
[KeepAliveService] Native ping response code: 401
[KeepAliveService] Session expired confirmed!
[SessionExpiredListener] Session expired event received
[Capacitor/Console] [TokenRefresh] Starting token refresh...
[Capacitor/Console] [TokenRefresh] Token refresh complete!
[KeepAliveService] Native ping response code: 200  ← Sucesso!
```

### **✅ AudioFocus (WhatsApp, Chamadas):**
```
[AudioTriggerService] [AudioFocus] LOSS_TRANSIENT - Microphone requested temporarily
[AudioTriggerService] [AudioFocus] Handling focus loss
[AudioTriggerService] [MicState] MONITORING -> (paused)
[AudioTriggerService] [AudioFocus] GAIN - Microphone available again
[AudioTriggerService] [MicState] (paused) -> MONITORING
```

---

## ❌ **Problemas Comuns**

### **Problema: "adb não reconhecido"**
**Solução:** Editar caminho do ADB no script:
```powershell
$ADB_PATH = "C:\Seu\Caminho\Para\adb.exe"
```

### **Problema: Logs vazios**
**Solução:** Verificar se app está rodando:
```powershell
C:\Users\Argemiro\AppData\Local\Android\Sdk\platform-tools\adb.exe shell "ps | grep ampara"
```

### **Problema: Muitos logs de AudioTrigger**
**Solução:** Usar `capture_ping_session_logs.ps1` (filtrado)

---

## 📊 **Análise Pós-Captura**

### **Contar Pings:**
```powershell
Select-String -Path ".\overnight_logs\*.txt" -Pattern "Executing native ping" | Measure-Object
```

### **Verificar GPS:**
```powershell
Select-String -Path ".\overnight_logs\*.txt" -Pattern "Location obtained"
```

### **Verificar Renovação de Sessão:**
```powershell
Select-String -Path ".\overnight_logs\*.txt" -Pattern "401|Token refresh"
```

### **Verificar Erros:**
```powershell
Select-String -Path ".\overnight_logs\*.txt" -Pattern "ERROR|Exception|Failed"
```

---

## 🎯 **Testes Recomendados**

### **Teste 1: Pings Durante a Noite (8 horas)**
```powershell
.\capture_ping_session_logs.ps1
# Deixar rodando 8 horas
# Esperar: ~960 pings (8h * 120 pings/h)
```

### **Teste 2: Renovação de Sessão**
```powershell
.\capture_ping_session_logs.ps1
# Aguardar até ver 401 → refresh → 200
```

### **Teste 3: AudioFocus (WhatsApp)**
```powershell
.\capture_overnight_logs.ps1
# Gravar áudio no WhatsApp
# Verificar: LOSS_TRANSIENT → GAIN
```

---

## 📁 **Estrutura de Arquivos**

```
amparamobile/
├── capture_overnight_logs.ps1          ← Script completo
├── capture_ping_session_logs.ps1       ← Script limpo (só ping/sessão)
├── OVERNIGHT_LOGGING.md                ← Esta documentação
└── overnight_logs/                     ← Logs gerados
    ├── ampara_debug_20260201_190000.txt
    ├── ping_session_20260201_200000.txt
    └── ...
```

---

## ✅ **Checklist Pré-Teste**

- [ ] App instalado e atualizado
- [ ] Login realizado
- [ ] Monitoramento iniciado (se necessário)
- [ ] Celular conectado via USB
- [ ] Depuração USB ativada
- [ ] Script PowerShell pronto
- [ ] Diretório `overnight_logs` criado

---

**Pronto para capturar logs! 🚀**
