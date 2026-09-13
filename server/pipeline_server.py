"""
Astaro Scribe — Pipeline Server
Добавь этот файл к существующему Flask-серверу:

    from pipeline_server import pipeline_bp
    app.register_blueprint(pipeline_bp)

Или запусти standalone:
    python pipeline_server.py

Требования (pip install):
    pyannote.audio==3.1.*
    faster-whisper
    torch  (уже должен быть)
    ffmpeg  (системный: apt install ffmpeg)

Переменные окружения:
    HF_TOKEN    — HuggingFace токен (нужен для pyannote/speaker-diarization-3.1)
    WHISPER_MODEL — имя модели (по умолчанию: large-v3-turbo)
    WHISPER_LANG  — язык (по умолчанию: ru; поставь "" для авто-определения)
"""

import os
import uuid
import threading
import tempfile
import subprocess
import wave
import logging
from functools import wraps
from flask import Flask, Blueprint, request, jsonify

log = logging.getLogger(__name__)

# ─── Хранилище задач (в памяти) ──────────────────────────────────────────────
jobs: dict[str, dict] = {}
jobs_lock = threading.Lock()

# ─── Кэш моделей (загружаются один раз) ──────────────────────────────────────
_whisper_model = None
_diarization_pipeline = None
_models_lock = threading.Lock()


def _get_whisper():
    global _whisper_model
    with _models_lock:
        if _whisper_model is None:
            from faster_whisper import WhisperModel
            import torch
            model_size = os.environ.get("WHISPER_MODEL", "large-v3-turbo")
            device = "cuda" if torch.cuda.is_available() else "cpu"
            compute = "float16" if device == "cuda" else "int8"
            log.info(f"Loading Whisper {model_size} on {device}/{compute}")
            _whisper_model = WhisperModel(model_size, device=device, compute_type=compute)
    return _whisper_model


def _get_diarization():
    global _diarization_pipeline
    with _models_lock:
        if _diarization_pipeline is None:
            from pyannote.audio import Pipeline as PyannotePipeline
            import torch
            hf_token = os.environ.get("HF_TOKEN", "")
            if not hf_token:
                raise RuntimeError(
                    "HF_TOKEN не задан. Получи токен на https://huggingface.co/settings/tokens "
                    "и прими условия использования pyannote/speaker-diarization-3.1"
                )
            log.info("Loading Pyannote speaker-diarization-3.1")
            pipe = PyannotePipeline.from_pretrained(
                "pyannote/speaker-diarization-3.1",
                use_auth_token=hf_token,
            )
            if torch.cuda.is_available():
                pipe = pipe.to(torch.device("cuda"))
            _diarization_pipeline = pipe
    return _diarization_pipeline


# ─── Auth ─────────────────────────────────────────────────────────────────────
_AUTH_USER = os.environ.get("SCRIBE_USER", "scribe")
_AUTH_PASS = os.environ.get("SCRIBE_PASS", "Volyna")


def requires_auth(f):
    @wraps(f)
    def decorated(*args, **kwargs):
        auth = request.authorization
        if not auth or auth.username != _AUTH_USER or auth.password != _AUTH_PASS:
            return (
                jsonify({"error": "Unauthorized"}),
                401,
                {"WWW-Authenticate": 'Basic realm="Astaro Scribe"'},
            )
        return f(*args, **kwargs)
    return decorated


# ─── Blueprint ────────────────────────────────────────────────────────────────
pipeline_bp = Blueprint("pipeline", __name__)


@pipeline_bp.route("/pipeline/submit", methods=["POST"])
@requires_auth
def submit():
    if "audio" not in request.files:
        return jsonify({"error": "Поле 'audio' обязательно"}), 400

    audio_file = request.files["audio"]
    original_name = audio_file.filename or "audio"
    suffix = os.path.splitext(original_name)[1] or ".audio"

    tmp = tempfile.NamedTemporaryFile(suffix=suffix, delete=False)
    audio_file.save(tmp.name)
    tmp.close()

    job_id = str(uuid.uuid4())
    with jobs_lock:
        jobs[job_id] = {
            "status": "processing",
            "step": "В очереди...",
            "progress": 0.0,
            "result": None,
            "error": None,
        }

    t = threading.Thread(target=_process_job, args=(job_id, tmp.name), daemon=True)
    t.start()

    return jsonify({"job_id": job_id})


@pipeline_bp.route("/pipeline/status/<job_id>", methods=["GET"])
@requires_auth
def status(job_id):
    with jobs_lock:
        job = jobs.get(job_id)
    if job is None:
        return jsonify({"error": "Задача не найдена"}), 404

    resp: dict = {
        "status": job["status"],
        "step": job["step"],
        "progress": job["progress"],
    }
    if job["status"] == "done":
        resp["segments"] = job["result"]["segments"]
        resp["full_text"] = job["result"]["full_text"]
    elif job["status"] == "error":
        resp["message"] = job["error"]
    return jsonify(resp)


# ─── Фоновая обработка ────────────────────────────────────────────────────────
def _update(job_id: str, **kwargs):
    with jobs_lock:
        if job_id in jobs:
            jobs[job_id].update(kwargs)


def _process_job(job_id: str, audio_path: str):
    wav_path = audio_path + "_16k.wav"
    seg_paths: list[str] = []

    try:
        # 1. Конвертация в 16 kHz mono WAV
        _update(job_id, step="Конвертация аудио...", progress=0.03)
        result = subprocess.run(
            ["ffmpeg", "-y", "-i", audio_path,
             "-ar", "16000", "-ac", "1", "-f", "wav", wav_path],
            capture_output=True,
        )
        if result.returncode != 0:
            raise RuntimeError(f"ffmpeg: {result.stderr.decode(errors='replace')}")

        # 2. Диаризация
        _update(job_id, step="Диаризация (определение спикеров)...", progress=0.08)
        diar_pipeline = _get_diarization()
        diarization = diar_pipeline(wav_path)

        raw_segments = [
            {"speaker": speaker, "start": round(turn.start, 3), "end": round(turn.end, 3)}
            for turn, _, speaker in diarization.itertracks(yield_label=True)
        ]
        log.info(f"[{job_id}] Diarization: {len(raw_segments)} segments")

        if not raw_segments:
            raise RuntimeError("Диаризация не нашла ни одного сегмента")

        # 3. Транскрибация по сегментам
        _update(job_id,
                step=f"Транскрибация {len(raw_segments)} сегментов...",
                progress=0.35)

        whisper = _get_whisper()
        lang = os.environ.get("WHISPER_LANG", "ru") or None  # None = auto

        with wave.open(wav_path, "rb") as wf:
            sample_rate = wf.getframerate()
            n_channels = wf.getnchannels()
            sampwidth = wf.getsampwidth()
            raw_data = wf.readframes(wf.getnframes())

        bytes_per_frame = n_channels * sampwidth
        transcribed: list[dict] = []
        full_text_parts: list[str] = []

        for i, seg in enumerate(raw_segments):
            prog = 0.35 + 0.60 * (i / len(raw_segments))
            _update(job_id,
                    step=f"Транскрибация: {i + 1}/{len(raw_segments)}...",
                    progress=round(prog, 3))

            start_f = int(seg["start"] * sample_rate)
            end_f = int(seg["end"] * sample_rate)
            chunk = raw_data[start_f * bytes_per_frame: end_f * bytes_per_frame]
            if not chunk:
                continue

            seg_path = f"{audio_path}_s{i}.wav"
            seg_paths.append(seg_path)
            with wave.open(seg_path, "wb") as sw:
                sw.setnchannels(n_channels)
                sw.setsampwidth(sampwidth)
                sw.setframerate(sample_rate)
                sw.writeframes(chunk)

            try:
                segs_gen, _ = whisper.transcribe(
                    seg_path, beam_size=1, language=lang, vad_filter=True
                )
                text = " ".join(s.text.strip() for s in segs_gen).strip()
            except Exception as e:
                log.warning(f"[{job_id}] seg {i} transcription failed: {e}")
                text = ""

            if text:
                transcribed.append({
                    "speaker": seg["speaker"],
                    "start": seg["start"],
                    "end": seg["end"],
                    "text": text,
                })
                full_text_parts.append(text)

        # Слияние соседних реплик одного спикера
        merged: list[dict] = []
        for seg in transcribed:
            if merged and merged[-1]["speaker"] == seg["speaker"]:
                merged[-1]["text"] += " " + seg["text"]
                merged[-1]["end"] = seg["end"]
            else:
                merged.append(dict(seg))

        _update(job_id,
                status="done",
                step="Готово",
                progress=1.0,
                result={
                    "segments": merged,
                    "full_text": " ".join(full_text_parts),
                })
        log.info(f"[{job_id}] Done — {len(merged)} merged segments")

    except Exception as e:
        log.exception(f"[{job_id}] Pipeline error")
        _update(job_id, status="error", step="Ошибка", error=str(e))

    finally:
        for p in [audio_path, wav_path] + seg_paths:
            try:
                if os.path.exists(p):
                    os.unlink(p)
            except Exception:
                pass


# ─── Standalone режим ─────────────────────────────────────────────────────────
if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    app = Flask(__name__)
    app.register_blueprint(pipeline_bp)

    # Базовый health check
    @app.route("/health")
    def health():
        return jsonify({"ok": True})

    port = int(os.environ.get("PORT", 5001))
    log.info(f"Pipeline server starting on :{port}")
    app.run(host="0.0.0.0", port=port, threaded=True)
