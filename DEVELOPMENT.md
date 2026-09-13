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

### Серверная инфраструктура

Docker-контейнеры на `n8n_default` сети:

| Сервис | Порт | Что делает |
|---|---|---|
| `whisper-gateway` | 8050 | Прокси к `whisper:9000/asr` (Whisper Turbo) |
| `diarization-gateway` | 8070 | Прокси к `diarization-server:8080` (Pyannote 3.1) |
| `pipeline-gateway` | 8090 | **Новый**: оркестрирует диаризацию + транскрибацию |
| `caddy` | 443 | Reverse proxy, Basic Auth |

`pipeline-gateway` делает:
1. `diarization-gateway:8070/diarize` → список сегментов по спикерам
2. ffmpeg → конвертация в 16 kHz WAV
3. Для каждого сегмента: ffmpeg slice + `whisper-gateway:8050/transcribe`
4. Склейка соседних реплик одного спикера

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

> `whisper.server.url` — **базовый URL** без пути.
> Приложение добавляет `/pipeline/submit` и `/pipeline/status/{id}` само.

### 3. Собери APK
```bash
cd ~/Projects/astaro-scribe
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

---

## Деплой/обновление pipeline-gateway на сервере

```bash
ssh root@сервер
cd /opt/apps/local-models/pipeline-gateway

# После изменения server.py — пересобрать образ:
docker compose build --no-cache && docker compose up -d

# Логи:
docker compose logs -f
```

### Файлы на сервере (`/opt/apps/local-models/pipeline-gateway/`)
- `server.py` — код gateway (копия `server/pipeline_server.py` из этого репо)
- `Dockerfile` — `python:3.11-slim` + ffmpeg + flask/requests
- `docker-compose.yml` — сервис + подключение к `n8n_default`

### Проверка
```bash
# Health check
curl -u scribe:Volyna https://turbo-whisper.attilaleo.uk/pipeline/health
# → {"service":"pipeline-gateway","status":"ok"}

# Загрузить файл
curl -u scribe:Volyna \
     -F "audio=@test.mp3" \
     https://turbo-whisper.attilaleo.uk/pipeline/submit
# → {"job_id": "abc-123"}

# Статус
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
└── pipeline_server.py    — Код pipeline-gateway (деплоится на сервер)
```

## Credentials (не коммитить!)

Хранятся в `local.properties` (gitignored):
```
whisper.server.url=https://turbo-whisper.attilaleo.uk
whisper.auth=Basic <base64>
```
