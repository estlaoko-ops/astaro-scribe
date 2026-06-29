# Разработка Astaro

## Сборка APK

### 1. Установи JDK (один раз)
```bash
brew install openjdk@17
echo 'export PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc
```

### 2. Собери APK
```bash
cd ~/Projects/astaro-scribe
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

---

## Git push без токена каждый раз

### Настройка Keychain (один раз навсегда)

**Шаг 1** — включи хранилище:
```bash
git config --global credential.helper osxkeychain
```

**Шаг 2** — получи новый токен:
- Открой: https://github.com/settings/tokens/new
- Note: `astaro-scribe`
- Expiration: `No expiration`
- Scope: поставь галку **repo**
- Нажми **Generate token**, скопируй

**Шаг 3** — введи токен один раз:
```bash
cd ~/Projects/astaro-scribe
git push https://ВАШ_ТОКЕН@github.com/estlaoko-ops/astaro-scribe.git
```

После этого токен сохранится в macOS Keychain — `git push` будет работать без токена автоматически.

---

## Структура проекта

```
app/src/main/java/com/diarizer/sherpa/
├── MainActivity.kt       — весь UI (Jetpack Compose)
├── Pipeline.kt           — ASR + диаризация + remote Whisper
├── AudioDecoder.kt       — MediaCodec → 16kHz PCM
├── ModelDownloader.kt    — загрузка ONNX-моделей
├── TranscriberService.kt — foreground service
└── FileLogger.kt         — логирование в файл
```

## Credentials (не коммитить!)

Хранятся в `local.properties` (gitignored):
```
whisper.server.url=https://turbo-whisper.attilaleo.uk/transcribe
whisper.auth=Basic <base64>
```

Бакаются в APK через `BuildConfig.WHISPER_SERVER_URL` и `BuildConfig.WHISPER_AUTH`.
