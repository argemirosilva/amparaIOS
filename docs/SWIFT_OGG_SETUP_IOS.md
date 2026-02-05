# Instalação do swift-ogg no iOS

## Sobre

O **swift-ogg** é uma biblioteca da Element (Matrix) que converte áudio entre formatos M4A (AAC) e OGG (Opus).

- **Repositório:** https://github.com/element-hq/swift-ogg
- **Licença:** Apache 2.0
- **Dependências:** libopus, libogg (incluídas como XCFrameworks)

## Instalação via Swift Package Manager

### 1. Abrir projeto no Xcode

```bash
cd /Users/argemironogueira/amparaapp
npx cap open ios
```

### 2. Adicionar Package Dependency

No Xcode:

1. Menu: **File → Add Package Dependencies...**

2. Colar URL no campo de busca:
   ```
   https://github.com/element-hq/swift-ogg
   ```

3. **Dependency Rule:** Up to Next Major Version (padrão)

4. Clicar em **Add Package**

5. Selecionar **SwiftOgg** (marcar checkbox)

6. **Add to Target:** App

7. Clicar em **Add Package** novamente

### 3. Aguardar Download

O Xcode vai baixar:
- swift-ogg
- opus-swift (XCFramework com libopus)
- ogg-swift (XCFramework com libogg)

Pode demorar alguns minutos.

### 4. Build

Pressionar **Cmd+B** para compilar e verificar se não há erros.

### 5. Run

Pressionar **Cmd+R** para executar no simulador ou dispositivo.

## Uso no Código

```swift
import SwiftOgg

// Converter M4A para OGG
let inputURL = URL(fileURLWithPath: "/tmp/audio.m4a")
let outputURL = URL(fileURLWithPath: "/tmp/audio.ogg")

try OGGConverter.convertM4aFileToOpusOGG(src: inputURL, dest: outputURL)
```

## Verificação

Após instalação, o arquivo `AudioSegmentUploader.swift` deve compilar sem erros:

```swift
import SwiftOgg  // ✅ Deve importar sem erro
```

## Troubleshooting

### Erro: "No such module 'SwiftOgg'"

**Solução:**
1. Verificar se o package foi adicionado corretamente em **Project Navigator → App → Package Dependencies**
2. Fazer **Clean Build Folder** (Shift+Cmd+K)
3. Fazer **Build** novamente (Cmd+B)

### Erro: "Failed to resolve dependencies"

**Solução:**
1. Verificar conexão com internet
2. Menu: **File → Packages → Reset Package Caches**
3. Menu: **File → Packages → Update to Latest Package Versions**

### Erro de compilação em opus/ogg

**Solução:**
- Os XCFrameworks (opus-swift, ogg-swift) são baixados automaticamente
- Se houver erro, deletar pasta `~/Library/Developer/Xcode/DerivedData` e fazer **Clean Build**

## Compatibilidade

- **iOS:** 13.0+
- **Xcode:** 14.0+
- **Swift:** 5.0+

## Formato de Saída

- **Codec:** Opus (alta qualidade, baixo bitrate)
- **Container:** OGG
- **Extensão:** `.ogg`
- **MIME Type:** `audio/ogg`
