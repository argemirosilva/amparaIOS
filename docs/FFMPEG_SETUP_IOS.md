# Instalação do FFmpegKit no iOS

## 📦 Adicionar FFmpegKit via Swift Package Manager

### Passo 1: Abrir Xcode
```bash
cd /Users/argemironogueira/amparaapp
npx cap open ios
```

### Passo 2: Adicionar Package

1. No Xcode, clicar em **File → Add Package Dependencies...**

2. Na barra de busca, colar:
   ```
   https://github.com/arthenica/ffmpeg-kit
   ```

3. Selecionar **ffmpeg-kit-ios-audio** (versão audio-only, mais leve)

4. Clicar em **Add Package**

5. Selecionar target **App** e clicar em **Add Package**

### Passo 3: Verificar Instalação

1. No navegador do Xcode (lado esquerdo), verificar se aparece:
   - **Package Dependencies**
     - **ffmpeg-kit-ios-audio**

2. Build o projeto (Cmd+B) para baixar e compilar

---

## ✅ Após Instalação

O código Swift já está pronto para usar FFmpegKit.

Apenas faça:
```bash
git pull origin main
npx cap sync ios
```

E compile novamente no Xcode.
