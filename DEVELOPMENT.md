# Astaro Scribe v7.0 — Разработка

## Архитектура

```
Телефон                      Сервер (turbo-whisper.attilaleo.uk)
─────────────────            ──────────────────────────────────
Выбрать файл →               POST /pipeline/submit
Загрузить →                  ← job_id
Поллинг каждые 5 сек →       GET /pipeline/status/{job_id}
                             ← {"status": "processing", "step": "...", "progress": 0.45}
                             ← {"status": "done", "segments": [...], "full_text": "..."}
Показать результат
```

Приложение — тонкий клиент. Вся обработка (диаризация + транскрибация) происходит на сервере.

---

## Сборка APK

### 1. Установи JDK (один раз)
```bash
brew install openjdk@17
echo 'export PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc
```

### 2. Убедись что local.properties содержит правильный URL
```
sdk.dir=/Users/airbenayoun/Library/Android/sdk
whisper.server.url=https://turbo-whisper.attilaleo.uk
whisper.auth=Basic c2NyaWJlOlZvbHluYQ==
```

> Внимание: `whisper.server.url` теперь должен быть **базовым URL** без `/transcribe` на конце.
> Приложение само добавляет пути `/pipeline/submit` и `/pipeline/status/{id}`.

### 3. Собери APK
```bash
cd ~/Projects/astaro-scribe
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

---

## Деплой серверного кода

### Что нужно установить на сервере

```bash
pip install "pyannote.audio==3.1.*" faster-whisper
# ffmpeg уже должен быть
```

### HuggingFace токен (для Pyannote)
1. Создай аккаунт на https://huggingface.co
2. Прими условия использования модели: https://huggingface.co/pyannote/speaker-diarization-3.1
3. Создай токен: https://huggingface.co/settings/tokens
4. Добавь в окружение сервера: `export HF_TOKEN=hf_...`

### Интеграция с существующим сервером

Добавь в `app.py` (или как он называется):
```python
from pipeline_server import pipeline_bp
app.register_blueprint(pipeline_bp)
```

Или запусти как отдельный процесс:
```bash
PORT=5001 HF_TOKEN=hf_... python server/pipeline_server.py
```

### Проверка (curl)
```bash
# Загрузить файл
curl -u scribe:Volyna \
     -F "audio=@test.mp3" \
     https://turbo-whisper.attilaleo.uk/pipeline/submit
# → {"job_id": "abc-123"}

# Проверить статус
curl -u scribe:Volyna \
     https://turbo-whisper.attilaleo.uk/pipeline/status/abc-123
# → {"status": "done", "segments": [...], "full_text": "..."}
```

---

## Git push

### Настройка Keychain (один раз)
```bash
git config --global credential.helper osxkeychain
```

Получи токен: https://github.com/settings/tokens/new  
Scope: **repo** · Expiration: No expiration

```bash
git push https://ВАШ_ТОКЕН@github.com/estlaoko-ops/astaro-scribe.git
```

---

## Структура проекта

```
app/src/main/java/com/diarizer/sherpa/
├── MainActivity.kt       — UI (Jetpack Compose, thin client)
├── ServerApi.kt          — HTTP: upload + polling
├── TranscriberService.kt — Wake lock (телефон не засыпает при загрузке)
├── DiarizerApp.kt        — Crash handler
└── FileLogger.kt         — Логирование

server/
└── pipeline_server.py    — Flask blueprint: /pipeline/submit + /pipeline/status
```

## Credentials (не коммитить!)

Хранятся в `local.properties` (gitignored):
```
whisper.server.url=https://turbo-whisper.attilaleo.uk
whisper.auth=Basic <base64>
```
