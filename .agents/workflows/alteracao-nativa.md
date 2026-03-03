---
description: Fluxo para alterações nativas que podem afetar iOS, Android ou ambos
---

# Alteração Nativa — Fluxo de Trabalho

## Regra Principal

> **SEMPRE perguntar ao usuário qual plataforma deve ser afetada se ele não especificar:**
> - 🍎 Apenas iOS
> - 🤖 Apenas Android
> - ✅ Ambas (iOS + Android)

## Passos por cenário

### Apenas TypeScript (`src/`)
1. Alterar arquivos em `src/`
2. Afeta iOS e Android automaticamente após build

### Apenas uma plataforma nativa
1. Alterar o arquivo nativo (Swift ou Java)
2. Se necessário, atualizar a interface TypeScript em `src/plugins/`

### Ambas plataformas nativas — OBRIGATÓRIO: Plano antes de implementar

**ANTES de qualquer alteração**, apresentar um plano completo contendo:

#### 1. Análise de Viabilidade
- A funcionalidade é viável em ambas plataformas?
- Existem limitações ou restrições de cada sistema operacional?
- APIs nativas necessárias existem em iOS (Swift) e Android (Java)?

#### 2. Comportamento por Plataforma
- Como a funcionalidade se comportará no **iOS** (descrever detalhadamente)
- Como a funcionalidade se comportará no **Android** (descrever detalhadamente)
- Diferenças de comportamento entre as plataformas (se houver)

#### 3. Plano de Implementação
- Arquivos que serão criados ou modificados em cada plataforma
- Interface TypeScript compartilhada (`src/plugins/`)
- Código Swift necessário (`ios/App/Plugins/`)
- Código Java necessário (`android/app/src/main/java/`)
- Registros necessários (PluginRegistration.swift / MainActivity.java)

#### 4. Riscos e Observações
- Pontos que podem quebrar funcionalidade existente
- Diferenças de permissões entre iOS e Android
- Necessidade de testes específicos por plataforma

**Só implementar APÓS aprovação explícita do plano pelo usuário.**

## Estrutura de referência

| Camada | iOS | Android |
|---|---|---|
| Plugins nativos | `ios/App/Plugins/*.swift` | `android/.../plugins/*.java` |
| Registro | `ios/App/PluginRegistration.swift` | `MainActivity.java` |
| Interfaces TS | `src/plugins/*.ts` | `src/plugins/*.ts` |
| Fallback Web | `src/plugins/*Web.ts` | `src/plugins/*Web.ts` |
| Detecção plataforma | `Capacitor.getPlatform() === 'ios'` | `Capacitor.getPlatform() === 'android'` |

## Após alteração
1. `npm run build`
2. `npx cap sync`
3. Testar no Xcode (iOS) e/ou Android Studio (Android)
