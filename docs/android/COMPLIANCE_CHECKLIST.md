# Checklist de Conformidade - Google Play Store

**Projeto:** Ampara  
**Data:** 01/02/2026  
**Responsável:** [Seu Nome]

---

## 📋 **Status Geral**

| Categoria | Status | Observações |
|-----------|--------|-------------|
| Tela de Consentimento | ✅ Implementado | Commit eb2d1f8 |
| Política de Privacidade | ✅ Criado | PRIVACY_POLICY.md |
| Descrição da Play Store | ✅ Criado | PLAY_STORE_DESCRIPTION.md |
| Permissões Declaradas | ✅ Correto | AndroidManifest.xml |
| Foreground Services | ✅ Conforme | Tipos corretos |
| Background Location | ✅ Justificado | Segurança pessoal |
| AudioFocus | ✅ Implementado | Commit 29fbf8c |

---

## ✅ **1. Tela de Consentimento**

### **Implementado:**
- [x] Tela aparece ANTES do login
- [x] Explica monitoramento de áudio contínuo
- [x] Detalha uso de localização em background
- [x] Lista direitos do usuário (LGPD/GDPR)
- [x] Checkbox de concordância obrigatório
- [x] Botão "Concordo" desabilitado até marcar checkbox
- [x] Opção "Não Concordo - Sair"
- [x] Links para Termos e Política de Privacidade
- [x] Salva consentimento em Preferences
- [x] Salva timestamp de aceitação
- [x] Design responsivo

### **Localização:**
- `src/pages/Consent.tsx`
- `src/pages/Consent.css`
- Integrado em `src/App.tsx`

### **Fluxo:**
```
App Abre → Verifica Consentimento
  ↓ (se não tem)
Tela de Consentimento → Usuário Lê e Aceita
  ↓
Tela de Login → Usuário Faz Login
  ↓
Tela Principal (Home)
```

---

## ✅ **2. Política de Privacidade**

### **Documento Criado:**
- [x] Arquivo: `PRIVACY_POLICY.md`
- [x] Versão: 1.0.0
- [x] Data: 01/02/2026
- [x] Idioma: Português (Brasil)

### **Conteúdo Incluído:**
- [x] Introdução e propósito
- [x] Dados coletados (áudio, localização, conta, contatos)
- [x] Como coletamos (métodos e frequência)
- [x] Por que coletamos (finalidades)
- [x] Como usamos (processamento)
- [x] Com quem compartilhamos (terceiros)
- [x] Medidas de segurança (criptografia, controles)
- [x] Retenção de dados (prazos)
- [x] Direitos do usuário (LGPD/GDPR)
- [x] Como exercer direitos (contatos)
- [x] Consentimento e controle
- [x] Menores de idade (16+)
- [x] Cookies e tecnologias
- [x] Atualizações da política
- [x] Contato do DPO
- [x] Jurisdição e lei aplicável
- [x] Uso responsável
- [x] Glossário

### **Conformidade:**
- [x] LGPD (Lei 13.709/2018)
- [x] GDPR (Regulamento UE 2016/679)
- [x] Marco Civil da Internet
- [x] Código de Defesa do Consumidor

### **Ações Necessárias:**
- [ ] Hospedar em https://ampara.app/privacy-policy
- [ ] Adicionar link no app (Configurações → Privacidade)
- [ ] Adicionar link na Play Store
- [ ] Traduzir para inglês (opcional)

---

## ✅ **3. Descrição da Play Store**

### **Documento Criado:**
- [x] Arquivo: `PLAY_STORE_DESCRIPTION.md`
- [x] Título curto (30 chars)
- [x] Descrição curta (80 chars)
- [x] Descrição completa (4000 chars)
- [x] Tags/palavras-chave
- [x] Sugestões de screenshots
- [x] Roteiro de vídeo promocional
- [x] Classificação etária (16+)

### **Destaques:**
- [x] Transparência sobre permissões
- [x] Explicação clara de funcionalidades
- [x] Casos de uso detalhados
- [x] Avisos sobre monitoramento contínuo
- [x] Conformidade legal destacada
- [x] Uso responsável enfatizado

### **Ações Necessárias:**
- [ ] Copiar para console da Play Store
- [ ] Criar screenshots (8 sugestões fornecidas)
- [ ] Gravar vídeo promocional (30s)
- [ ] Traduzir para inglês (opcional)
- [ ] Configurar classificação etária (16+)

---

## ✅ **4. Permissões no AndroidManifest.xml**

### **Permissões Declaradas:**
- [x] `RECORD_AUDIO` - Monitoramento de áudio
- [x] `ACCESS_FINE_LOCATION` - GPS preciso
- [x] `ACCESS_COARSE_LOCATION` - Localização aproximada
- [x] `ACCESS_BACKGROUND_LOCATION` - Localização em background
- [x] `FOREGROUND_SERVICE` - Serviços em foreground
- [x] `FOREGROUND_SERVICE_MICROPHONE` - Tipo específico
- [x] `FOREGROUND_SERVICE_DATA_SYNC` - Tipo específico
- [x] `WAKE_LOCK` - Manter CPU ativa
- [x] `SCHEDULE_EXACT_ALARM` - Alarmes precisos
- [x] `USE_EXACT_ALARM` - Alarmes exatos (Android 14+)

### **Justificativas (Obrigatórias na Play Store):**

**RECORD_AUDIO:**
> "Necessário para monitorar áudio ambiente continuamente e detectar automaticamente situações de risco (brigas, discussões) para proteção do usuário. Gravações são feitas apenas quando há detecção de perigo."

**ACCESS_BACKGROUND_LOCATION:**
> "Essencial para rastreamento GPS em tempo real durante emergências, permitindo que contatos de confiança localizem o usuário em situações de risco. Funciona apenas quando o monitoramento está ativo (notificação persistente visível)."

**FOREGROUND_SERVICE_MICROPHONE:**
> "Permite análise contínua de áudio ambiente mesmo com a tela bloqueada, garantindo proteção 24/7. Notificação persistente sempre visível quando ativo."

**FOREGROUND_SERVICE_DATA_SYNC:**
> "Mantém conexão com servidor para enviar pings periódicos com localização GPS e renovar sessão automaticamente, garantindo que alertas de emergência sejam entregues."

### **Ações Necessárias:**
- [ ] Adicionar justificativas no console da Play Store
- [ ] Preencher formulário de Background Location
- [ ] Anexar vídeo demonstrando uso das permissões

---

## ✅ **5. Foreground Services**

### **Services Implementados:**

**KeepAliveService:**
- [x] Tipo: `FOREGROUND_SERVICE_DATA_SYNC`
- [x] Notificação persistente: "Ampara - Conectado"
- [x] Função: Pings com GPS a cada 30s
- [x] Justificativa: Manter sessão ativa e enviar localização

**AudioTriggerService:**
- [x] Tipo: `FOREGROUND_SERVICE_MICROPHONE`
- [x] Notificação persistente: "Ampara - Monitorando"
- [x] Função: Análise de áudio contínua
- [x] Justificativa: Detecção automática de brigas

### **Conformidade:**
- [x] Tipos de serviço corretos (Android 14+)
- [x] Notificações sempre visíveis
- [x] Usuário controla início/fim
- [x] Não inicia automaticamente no boot (sem BOOT_COMPLETED)

---

## ✅ **6. AudioFocus (Liberação de Microfone)**

### **Implementado:**
- [x] Solicita AudioFocus ao iniciar monitoramento
- [x] Libera microfone quando outros apps solicitam
- [x] Pausa monitoramento temporariamente
- [x] Retoma automaticamente quando microfone liberado
- [x] Finaliza gravação com motivo "mic_solicitado"

### **Benefícios:**
- [x] WhatsApp áudio funciona
- [x] Chamadas telefônicas funcionam
- [x] Câmera grava com áudio
- [x] Melhor experiência do usuário

### **Commit:** 29fbf8c

---

## ✅ **7. Transparência e Controle do Usuário**

### **Implementado:**

**Notificações Persistentes:**
- [x] "Ampara - Monitorando" (AudioTriggerService)
- [x] "Ampara - Conectado" (KeepAliveService)
- [x] Sempre visíveis quando serviços ativos
- [x] Não podem ser removidas enquanto ativo

**Controles no App:**
- [x] Botão "Iniciar/Parar Monitoramento"
- [x] Botão "Parar Gravação"
- [x] Ver histórico de gravações
- [x] Deletar gravações individuais
- [x] Ver histórico de localizações
- [x] Gerenciar contatos de emergência
- [x] Configurações de privacidade

**Controles no Sistema:**
- [x] Revogar permissão de microfone (Settings)
- [x] Revogar permissão de localização (Settings)
- [x] Desativar notificações (Settings)
- [x] Forçar parada do app (Settings)

---

## ✅ **8. Documentação Técnica**

### **Criados:**
- [x] `DOZE_MODE_AUDIT.md` - Auditoria de Doze Mode
- [x] `LOG_FILTERS.md` - Filtros de log
- [x] `OVERNIGHT_LOGGING.md` - Logging noturno
- [x] `PRIVACY_POLICY.md` - Política de privacidade
- [x] `PLAY_STORE_DESCRIPTION.md` - Descrição da Play Store
- [x] `COMPLIANCE_CHECKLIST.md` - Este documento

### **Commits Principais:**
- `65a829e` - Documentos de auditoria e filtros
- `c5265a4` - Scripts de captura de logs
- `eb2d1f8` - Tela de consentimento e conformidade

---

## ⚠️ **9. Ações Pendentes Antes da Publicação**

### **Alta Prioridade:**
- [ ] Hospedar Política de Privacidade em https://ampara.app/privacy-policy
- [ ] Criar Termos de Uso (https://ampara.app/terms)
- [ ] Preencher formulário de Background Location na Play Store
- [ ] Gravar vídeo demonstrando uso de permissões (obrigatório)
- [ ] Criar screenshots (mínimo 2, recomendado 8)
- [ ] Adicionar ícone de alta resolução (512x512px)
- [ ] Adicionar banner promocional (1024x500px)

### **Média Prioridade:**
- [ ] Adicionar link "Política de Privacidade" no app (Settings)
- [ ] Adicionar link "Termos de Uso" no app (Settings)
- [ ] Traduzir Política de Privacidade para inglês
- [ ] Traduzir descrição da Play Store para inglês
- [ ] Configurar classificação etária (16+)
- [ ] Preencher questionário de conteúdo da Play Store

### **Baixa Prioridade:**
- [ ] Criar página de FAQ
- [ ] Criar vídeo tutorial de uso
- [ ] Preparar materiais de marketing
- [ ] Configurar Google Analytics (opcional)

---

## 📝 **10. Checklist de Submissão**

### **Antes de Enviar para Revisão:**

**Documentos:**
- [x] Política de Privacidade criada
- [x] Descrição da Play Store pronta
- [x] Tela de consentimento implementada
- [ ] Termos de Uso criados
- [ ] Links hospedados e funcionando

**Assets:**
- [ ] Ícone 512x512px
- [ ] Banner 1024x500px
- [ ] Screenshots (mínimo 2)
- [ ] Vídeo promocional (opcional mas recomendado)
- [ ] Vídeo de demonstração de permissões (obrigatório)

**Formulários:**
- [ ] Questionário de conteúdo preenchido
- [ ] Formulário de Background Location preenchido
- [ ] Justificativas de permissões adicionadas
- [ ] Classificação etária configurada (16+)
- [ ] Categoria selecionada (Ferramentas/Segurança)

**Testes:**
- [ ] Testar fluxo de consentimento
- [ ] Testar permissões (aceitar/negar)
- [ ] Testar monitoramento em background
- [ ] Testar gravação automática
- [ ] Testar botão de pânico
- [ ] Testar renovação de sessão
- [ ] Testar em Android 14+
- [ ] Testar em diferentes fabricantes (Samsung, Xiaomi, etc)

**Conformidade:**
- [x] Código fonte revisado
- [x] Sem violações de políticas identificadas
- [x] Transparência garantida
- [x] Controles do usuário implementados
- [x] Documentação completa

---

## 🚀 **11. Próximos Passos**

### **Imediato (Hoje):**
1. Criar Termos de Uso
2. Hospedar Política de Privacidade e Termos
3. Criar ícone e banner

### **Esta Semana:**
1. Gravar vídeo de demonstração
2. Criar screenshots
3. Preencher formulários da Play Store
4. Testar em múltiplos dispositivos

### **Próxima Semana:**
1. Submeter para revisão
2. Aguardar feedback do Google
3. Corrigir problemas (se houver)
4. Publicar!

---

## 📞 **12. Contatos Úteis**

**Suporte Google Play:**
- https://support.google.com/googleplay/android-developer

**Políticas da Play Store:**
- https://play.google.com/about/developer-content-policy/

**LGPD (ANPD):**
- https://www.gov.br/anpd

**Consultor Jurídico (se necessário):**
- [Adicionar contato]

---

**Status Geral:** 🟡 **80% Completo**

**Próxima Revisão:** [Data]  
**Responsável:** [Seu Nome]

---

✅ = Completo  
⚠️ = Em Andamento  
❌ = Pendente  
🟢 = Baixo Risco  
🟡 = Médio Risco  
🔴 = Alto Risco
