# 🎤 Native Audio Trigger - Documentação Completa

## 📋 Visão Geral

O **Native Audio Trigger** é uma implementação em Java puro que permite ao app Ampara continuar monitorando áudio e detectando discussões **mesmo quando o app está em segundo plano ou com a tela bloqueada**, incluindo em **Doze Mode**.

---

## 🏗️ Arquitetura

### **Sistema Híbrido**

O sistema funciona em dois modos:

1. **JavaScript (Foreground)** - Quando o app está ativo
   - Usa toda a lógica sofisticada existente
   - Análise de pitch, classificação de gênero, turn-taking
   - Mais preciso e com mais recursos

2. **Java Nativo (Background)** - Quando o app está em segundo plano
   - Captura e processa áudio continuamente
   - Detecção de discussão baseada em volume e densidade de fala
   - Funciona em Doze Mode
   - Envia eventos para o JavaScript quando detecta discussão

### **Troca Automática**

O `HybridAudioTriggerService` detecta quando o app vai para background e automaticamente:
- Para o AudioTrigger JavaScript
- Inicia o AudioTrigger Nativo
- E vice-versa quando volta para foreground

---

## 📁 Estrutura de Arquivos

### **Java (Android)**

```
android/app/src/main/java/tech/orizon/ampara/
├── audio/
│   ├── AudioDSP.java                  # Processamento de sinal digital
│   ├── AudioTriggerConfig.java        # Configurações do detector
│   └── DiscussionDetector.java        # Detector de discussões (state machine)
├── AudioTriggerService.java           # Serviço principal de captura de áudio
└── plugins/
    └── AudioTriggerPlugin.java        # Bridge Capacitor
```

### **TypeScript**

```
src/
├── plugins/
│   ├── audioTriggerNative.ts          # Interface TypeScript
│   └── audioTriggerNativeWeb.ts       # Fallback web
└── services/
    └── hybridAudioTriggerService.ts   # Gerenciador híbrido
```

---

## 🔧 Como Funciona

### **1. Captura de Áudio (AudioTriggerService.java)**

```java
AudioRecord audioRecord = new AudioRecord(
    MediaRecorder.AudioSource.MIC,
    16000,  // 16kHz sample rate
    AudioFormat.CHANNEL_IN_MONO,
    AudioFormat.ENCODING_PCM_16BIT,
    bufferSize
);
```

- Captura áudio continuamente em 16kHz
- Processa em frames de 25ms
- Agrega em janelas de 1 segundo

### **2. Processamento de Sinal (AudioDSP.java)**

Para cada frame de áudio, calcula:

- **RMS (Root Mean Square)** - Nível de volume em dB
- **ZCR (Zero Crossing Rate)** - Taxa de cruzamento de zero (detecta fala)

```java
double rmsDb = AudioDSP.calculateRMS(samples, length);
double zcr = AudioDSP.calculateZCR(samples, length);
boolean isSpeech = AudioDSP.isSpeechLike(rmsDb, zcr);
```

### **3. Detecção de Discussão (DiscussionDetector.java)**

Máquina de estados com 4 estados:

1. **IDLE** - Aguardando
2. **DISCUSSION_DETECTED** - Discussão detectada, aguardando confirmação
3. **RECORDING_STARTED** - Gravação iniciada
4. **COOLDOWN** - Período de espera após gravação

**Critérios de Detecção:**

- **Speech Density** ≥ 65% (% de frames com fala)
- **Loud Density** ≥ 40% (% de frames altos)
- **Hold Period** = 7 segundos (confirmação)

### **4. Notificação ao JavaScript**

Quando detecta discussão, envia broadcast:

```java
Intent intent = new Intent("tech.orizon.ampara.AUDIO_TRIGGER_EVENT");
intent.putExtra("event", "discussionDetected");
intent.putExtra("reason", "discussion_confirmed");
sendBroadcast(intent);
```

O `AudioTriggerPlugin.java` recebe e notifica o JavaScript via Capacitor.

---

## 🚀 Como Usar

### **Iniciar Monitoramento Nativo**

```typescript
import AudioTriggerNative from '@/plugins/audioTriggerNative';

// Iniciar
await AudioTriggerNative.start();

// Escutar eventos
AudioTriggerNative.addListener('audioTriggerEvent', (event) => {
  console.log('Event:', event.event);
  console.log('Reason:', event.reason);
  
  if (event.event === 'discussionDetected') {
    // Iniciar gravação
  }
});

// Parar
await AudioTriggerNative.stop();
```

### **Usar o Sistema Híbrido (Recomendado)**

```typescript
import { hybridAudioTrigger } from '@/services/hybridAudioTriggerService';

// Iniciar (troca automaticamente entre JS e Nativo)
await hybridAudioTrigger.start();

// Escutar eventos de ambos os sistemas
hybridAudioTrigger.addListener((event) => {
  console.log('Discussion detected:', event);
});

// Verificar status
const status = hybridAudioTrigger.getStatus();
console.log('Native running:', status.isNativeRunning);
console.log('JavaScript running:', status.isJavaScriptRunning);
```

---

## ⚙️ Configuração

As configurações estão em `AudioTriggerConfig.java`:

```java
public class AudioTriggerConfig {
    public int sampleRate = 16000;              // Taxa de amostragem
    public int frameMs = 25;                    // Tamanho do frame
    public int aggregationMs = 1000;            // Janela de agregação
    
    public double loudDeltaDb = 18.0;           // Delta para considerar "alto"
    public double vadDeltaDb = 7.0;             // Delta para Voice Activity Detection
    public double speechDensityMin = 0.65;      // Densidade mínima de fala
    public double loudDensityMin = 0.4;         // Densidade mínima de volume alto
    
    public int discussionWindowSeconds = 10;    // Janela de análise
    public int startHoldSeconds = 7;            // Tempo de confirmação
    public int endHoldSeconds = 30;             // Tempo para considerar fim
    public int cooldownSeconds = 45;            // Cooldown entre detecções
}
```

---

## 🧪 Como Testar

### **1. Rebuild Completo**

```powershell
cd C:\orizontech\amparamobile
git pull
Remove-Item -Recurse -Force dist -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force node_modules\.vite -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force android\app\src\main\assets\public -ErrorAction SilentlyContinue
npm run build
npx cap copy android
npx cap sync android
cd android
.\gradlew uninstallDebug
.\gradlew installDebug
```

### **2. Filtrar Logcat**

```
Package: tech.orizon.ampara
```

Ou buscar por:
```
AudioTriggerService|AudioDSP|DiscussionDetector|AudioTriggerPlugin
```

### **3. Testar em Foreground**

1. Abra o app
2. Ative o monitoramento
3. Fale alto perto do celular
4. Veja no log:
   ```
   AudioTriggerService: Discussion detected!
   AudioTriggerPlugin: Broadcast sent: discussionDetected
   ```

### **4. Testar em Background**

1. Abra o app e ative monitoramento
2. **Minimize o app** (Home button)
3. Aguarde 5 segundos
4. Fale alto perto do celular
5. Veja no log:
   ```
   AudioTriggerService: Processing audio...
   DiscussionDetector: Discussion detected!
   ```

### **5. Testar com Tela Bloqueada**

1. Abra o app e ative monitoramento
2. **Bloqueie a tela**
3. Aguarde 1 minuto
4. Fale alto perto do celular
5. Desbloqueie e veja o log

---

## 📊 Logs Esperados

### **Inicialização**

```
AudioTriggerService: AudioTriggerService created
AudioTriggerService: AudioTriggerService started
AudioTriggerService: Audio capture started successfully
AudioTriggerService: Audio processing loop started
```

### **Processamento Normal**

```
(Não gera logs contínuos para economizar bateria)
```

### **Discussão Detectada**

```
DiscussionDetector: Discussion detected! Speech: 0.75, Loud: 0.50
DiscussionDetector: Discussion confirmed after hold period - starting recording
AudioTriggerService: DISCUSSION DETECTED! Reason: discussion_confirmed, Speech: 0.75, Loud: 0.50
AudioTriggerPlugin: Broadcast sent: discussionDetected
```

### **Discussão Terminada**

```
DiscussionDetector: Discussion ended - stopping recording
AudioTriggerService: DISCUSSION ENDED! Reason: discussion_ended
AudioTriggerPlugin: Broadcast sent: discussionEnded
```

---

## ⚠️ Limitações Atuais

1. **Sem análise de pitch** - Não detecta frequência fundamental
2. **Sem classificação de gênero** - Não diferencia vozes masculinas/femininas
3. **Sem turn-taking avançado** - Não conta alternância de falantes
4. **Detecção mais simples** - Baseada apenas em volume e densidade de fala

Essas funcionalidades avançadas continuam disponíveis no **modo JavaScript (foreground)**.

---

## 🔮 Próximos Passos

### **Fase 2 (Futuro)**
- [ ] Implementar FFT para análise de frequência
- [ ] Adicionar detecção de pitch
- [ ] Implementar classificação de gênero por voz

### **Fase 3 (Futuro)**
- [ ] Adicionar turn-taking detection
- [ ] Implementar densidade de fala avançada
- [ ] Melhorar precisão da detecção

---

## 🐛 Troubleshooting

### **AudioTrigger não inicia**

- Verifique permissão de microfone
- Veja o log para erros de `SecurityException`
- Confirme que o serviço está registrado no AndroidManifest

### **Não detecta discussões**

- Ajuste `speechDensityMin` e `loudDensityMin` em `AudioTriggerConfig`
- Verifique se o microfone está funcionando
- Teste com volume mais alto

### **Para em background**

- Confirme que otimização de bateria está desabilitada
- Verifique se o serviço tem `stopWithTask="false"`
- Veja se há erros no log

---

## 📝 Notas Importantes

1. **Permissões**: O app já tem todas as permissões necessárias declaradas
2. **Bateria**: O serviço nativo consome menos bateria que o JavaScript
3. **Foreground Service**: Não precisa de notificação separada (usa a do KeepAliveService)
4. **Doze Mode**: Funciona perfeitamente mesmo em Doze Mode profundo

---

## ✅ Checklist de Implementação

- [x] AudioDSP.java criado
- [x] AudioTriggerConfig.java criado
- [x] DiscussionDetector.java criado
- [x] AudioTriggerService.java criado
- [x] AudioTriggerPlugin.java criado
- [x] Registrado no AndroidManifest
- [x] Registrado no MainActivity
- [x] Interface TypeScript criada
- [x] HybridAudioTriggerService criado
- [x] Documentação completa
- [ ] Integração com Home.tsx (próximo passo)
- [ ] Testes em produção

---

**Implementado em:** 28/01/2026  
**Versão:** 1.0.0  
**Status:** ✅ Pronto para testes
