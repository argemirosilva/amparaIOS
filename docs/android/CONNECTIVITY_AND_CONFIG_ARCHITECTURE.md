# Arquitetura de Conectividade e Configuração Remota - Ampara Mobile

**Autor:** Manus AI
**Data:** 26 de Janeiro de 2026

## 1. Desenho dos Endpoints

Os endpoints serão ações dentro do `mobile-api` existente, garantindo consistência e segurança através da autenticação já implementada.

### 1.1. Ping (`pingMobile`)

Endpoint para healthcheck, verificação de conectividade e sincronia de tempo.

- **Ação:** `pingMobile`
- **Método:** `POST`
- **Autenticação:** **Obrigatória**

#### Request

O payload é mínimo, contendo apenas informações de autenticação e a versão atual do app para diagnóstico.

```json
{
  "action": "pingMobile",
  "session_token": "<user_session_token>",
  "device_id": "<unique_device_id>",
  "app_version": "1.0.5" // Adicionado para tracking
}
```

#### Response (Sucesso)

Retorna um status de sucesso, a hora atual do servidor (para detectar dessincronia de relógio) e a versão mínima recomendada do app.

```json
{
  "status": "ok",
  "server_timestamp": "2026-01-26T14:30:00Z",
  "min_required_app_version": "1.0.2"
}
```

#### Response (Falha)

```json
{
  "error": "Sessão inválida" 
}
```

### 1.2. GetConfig (`syncConfigMobile`)

Endpoint para obter a configuração operacional do app. Otimizado para retornar a configuração apenas se ela for mais nova que a versão que o cliente já possui.

- **Ação:** `syncConfigMobile`
- **Método:** `POST`
- **Autenticação:** **Obrigatória**

#### Request

O cliente envia a versão da configuração que possui atualmente. Se não tiver nenhuma, envia `0`.

```json
{
  "action": "syncConfigMobile",
  "session_token": "<user_session_token>",
  "device_id": "<unique_device_id>",
  "current_config_version": 1674758400 // Timestamp da última config recebida
}
```

#### Response (Sucesso - Nova Configuração)

Retorna a nova configuração completa se a versão do cliente for mais antiga.

```json
{
  "status": "updated",
  "config": {
    "version": 1674762000, // Novo timestamp
    "ttl_seconds": 3600, // 1 hora
    "monitoring_enabled": true,
    "monitoring_periods": [
      { "start": "08:00", "end": "18:00" }
    ],
    "audio_trigger": {
      "sensitivity": "high",
      "min_score": 5
    }
  }
}
```

#### Response (Sucesso - Sem Mudanças)

Retorna um status `not_modified` se a versão do cliente for a mais recente, economizando banda.

```json
{
  "status": "not_modified",
  "next_check_in_seconds": 3600 // Cliente pode esperar este tempo para checar de novo
}
```

---

## 2. Pseudocódigo do Fluxo no App

Serão criados dois serviços principais: `ConnectivityService` e `ConfigService`.

### 2.1. `ConnectivityService.ts`

```typescript
// State
let isOnline = false;
let lastLatency = -1;
let retryAttempt = 0;

// Função principal de Ping
async function executePing() {
  const startTime = Date.now();
  
  try {
    // Usa um timeout agressivo (ex: 5 segundos)
    const response = await api.pingMobile({ timeout: 5000 });
    
    if (response.status === 'ok') {
      isOnline = true;
      lastLatency = Date.now() - startTime;
      retryAttempt = 0; // Reseta tentativas em sucesso
      log("Ping success", { latency: lastLatency });
    } else {
      // Falha de autenticação ou outro erro de servidor
      isOnline = false;
      log("Ping failed", { error: response.error });
    }
    
  } catch (error) {
    // Timeout ou falha de rede
    isOnline = false;
    log("Ping network error", { error });
    
    // Inicia lógica de retry com backoff exponencial
    scheduleNextPingWithBackoff();
  }
}

// Inicia o ciclo de pings
function startPinging() {
  // Roda em um loop controlado pelo Foreground Service
  // para garantir execução em background.
  setInterval(executePing, 30000); // 30 segundos
}

function scheduleNextPingWithBackoff() {
  const delay = Math.pow(2, retryAttempt) * 1000; // 1s, 2s, 4s, 8s...
  retryAttempt++;
  setTimeout(executePing, delay);
}
```

### 2.2. `ConfigService.ts`

```typescript
// State
let currentConfig = null;
let lastValidConfig = null; // Cache

// Função principal de GetConfig
async function fetchAndApplyConfig() {
  const cachedConfig = await SecureStorage.get('cached_config');
  if (cachedConfig) {
    lastValidConfig = JSON.parse(cachedConfig.value);
    // Aplica a config do cache imediatamente na UI
    applyConfig(lastValidConfig);
  }

  // Verifica se o cache expirou (TTL)
  if (cacheHasExpired(lastValidConfig)) {
    try {
      const response = await api.syncConfigMobile({
        current_config_version: lastValidConfig?.version || 0
      });

      if (response.status === 'updated') {
        const newConfig = response.config;
        log("Config updated", { from: lastValidConfig?.version, to: newConfig.version });
        
        // Salva no cache seguro
        await SecureStorage.set('cached_config', JSON.stringify(newConfig));
        
        // Aplica a nova config
        applyConfig(newConfig);
        lastValidConfig = newConfig;
        
      } else if (response.status === 'not_modified') {
        log("Config not modified");
      }
      
    } catch (error) {
      log("GetConfig failed, using cached version", { error });
      // Falha na rede, continua usando a `lastValidConfig` do cache.
      // Se não houver cache, usa um default hardcoded.
      if (!lastValidConfig) {
        applyConfig(DEFAULT_CONFIG);
      }
    }
  }
}

// Inicia o ciclo de atualização de config
function startConfigSync() {
  // Roda na inicialização do app e depois periodicamente
  // via Foreground Service.
  fetchAndApplyConfig();
  setInterval(fetchAndApplyConfig, 3600 * 1000); // 1 hora
}
```

---

## 3. Estratégia de Cache, TTL e Retry/Backoff

| Serviço     | Estratégia de Cache                               | TTL (Time-To-Live)                               | Retry / Backoff                                                                 |
|-------------|---------------------------------------------------|--------------------------------------------------|---------------------------------------------------------------------------------|
| **Ping**    | Não aplicável (estado volátil `isOnline`)         | Não aplicável                                    | **Exponencial:** Tentativas com delay crescente (1s, 2s, 4s, 8s, ... max 60s).   |
| **GetConfig** | **Cache Local Persistente:** Usa `SecureStorage` (SharedPreferences nativo) para guardar a última configuração válida. | **Definido pelo Servidor:** O payload de resposta da config inclui um campo `ttl_seconds`. | **Sem Retry Imediato:** Em caso de falha, o sistema faz **fallback** para a versão em cache. Uma nova tentativa só ocorrerá no próximo ciclo de sincronização (ex: 1 hora depois). |

---

## 4. Checklist de Testes

### Unitários
- [ ] `ConnectivityService`: `executePing` trata corretamente sucesso, falha de API e erro de rede.
- [ ] `ConnectivityService`: `scheduleNextPingWithBackoff` calcula o delay corretamente.
- [ ] `ConfigService`: `fetchAndApplyConfig` carrega corretamente do cache.
- [ ] `ConfigService`: `fetchAndApplyConfig` faz fallback para o cache em caso de falha de rede.
- [ ] `ConfigService`: `fetchAndApplyConfig` faz fallback para config default se não houver cache e a rede falhar.
- [ ] `ConfigService`: `fetchAndApplyConfig` atualiza o cache após receber uma nova config.

### Integração
- [ ] **Cenário Online:** App abre, `ping` sucede, `getConfig` busca a config mais nova.
- [ ] **Cenário Offline:** App abre sem internet, `ping` falha, `getConfig` usa a versão em cache (se existir).
- [ ] **Transição Offline -> Online:** App fica online, `ping` volta a ter sucesso, `getConfig` sincroniza na próxima oportunidade.
- [ ] **Config Desatualizada:** Servidor tem uma nova config, app baixa e aplica a nova versão, atualizando o cache.
- [ ] **Config Atualizada:** Servidor não tem nova config, app recebe `not_modified` e economiza dados.
- [ ] **Teste em Background:** Com o app em segundo plano, verificar (via logs) se os ciclos de Ping e GetConfig continuam executando através do Foreground Service.

---

## 5. Lista de Métricas e Logs Recomendados

Logs devem ser estruturados para fácil parseamento em ferramentas de monitoramento.

### Ping
- `ping_success`: `{ "latency_ms": 120 }`
- `ping_api_failure`: `{ "error": "Sessão inválida" }`
- `ping_network_failure`: `{ "error": "Timeout de 5000ms excedido" }`
- `ping_retry_scheduled`: `{ "attempt": 3, "delay_ms": 4000 }`

### GetConfig
- `get_config_success_updated`: `{ "from_version": 123, "to_version": 456, "latency_ms": 350 }`
- `get_config_success_not_modified`: `{ "current_version": 456, "latency_ms": 150 }`
- `get_config_cache_hit`: `{ "version": 123, "source": "SecureStorage" }`
- `get_config_cache_miss`: `{}`
- `get_config_fallback_cache`: `{ "error": "Falha de rede", "fallback_version": 123 }`
- `get_config_fallback_default`: `{ "error": "Falha de rede, sem cache" }`
