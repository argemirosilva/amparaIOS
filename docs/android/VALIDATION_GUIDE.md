# Guia de Validação Manual - Ampara

Este guia descreve os passos necessários para validar as funcionalidades críticas do Ampara no dispositivo real.

## 1. Fluxo de Login e Sincronização
- [ ] Abra o app e faça login.
- [ ] Verifique se a tela inicial carrega os períodos de monitoramento.
- [ ] Feche e abra o app novamente; verifique se a sessão é mantida e as configurações são carregadas do cache.

## 2. Pânico Manual (Botão)
- [ ] Clique no botão de pânico.
- [ ] Confirme se o status muda para "Em Pânico".
- [ ] Cancele o pânico usando a senha de desativação.
- [ ] Verifique no console (ou logcat) se a ação `cancelarPanicoMobile` foi enviada.

## 3. Pânico por Voz (Gatilho)
- [ ] Com o monitoramento ativo, pronuncie a frase de gatilho (se configurada).
- [ ] Verifique se a gravação inicia automaticamente.
- [ ] Verifique se o evento de pânico aparece no histórico/logs.

## 4. Resiliência em Background (Crítico)
- [ ] Inicie um pânico ou deixe o monitoramento ativo.
- [ ] Bloqueie a tela do celular.
- [ ] Aguarde 5 minutos.
- [ ] Desbloqueie e verifique nos logs se o app continuou enviando Pings/GPS durante o período de bloqueio.
- [ ] *Nota: O app deve usar WakeLock e Foreground Service para se manter ativo.*

## 5. Falha de Conexão
- [ ] Coloque o celular em Modo Avião durante uma gravação/pânico.
- [ ] Aguarde 1 minuto e desative o Modo Avião.
- [ ] Verifique se o app retoma o envio dos arquivos de áudio/GPS pendentes automaticamente.

---
**Comandos de Debug Úteis:**
- Visualizar logs nativos: `adb logcat | grep Ampara`
- Verificar status da bateria via ADB: `adb shell dumpsys battery`
