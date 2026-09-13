"""
Astaro Scribe — Pipeline Gateway
Оркестрирует существующие сервисы: diarization-gateway + whisper-gateway.

Развёрнуто по адресу: /opt/apps/local-models/pipeline-gateway/server.py

Сервис:
    POST /pipeline/submit   — принять аудиофайл, вернуть {"job_id": "..."}
    GET  /pipeline/status/<job_id> — статус + результат

Вызывает:
    diarization-gateway:8070/diarize  — Pyannote speaker diarization
    whisper-gateway:8050/transcribe   — Whisper Turbo per-segment

Поднять / обновить на сервере:
    ssh root@2.25.155.237
    cd /opt/apps/local-models/pipeline-gateway
    docker compose build --no-cache && docker compose up -d
"""

from flask import Flask, request, jsonify
import os
import requests
import subprocess
import tempfile
import threading
import time
import uuid

app = Flask(__name__)

WHISPER_URL = "http://whisper-gateway:8050/transcribe"
DIARIZE_URL = "http://diarization-gateway:8070/diarize"

jobs: dict[str, dict] = {}
jobs_lock = threading.Lock()


def log(msg: str):
    ts = time.strftime("%Y-%m-%d %H:%M:%S")
    print(f"[{ts}] [PIPELINE] {msg}", flush=True)


def update_job(job_id: str, **kwargs):
    with jobs_lock:
        if job_id in jobs:
            jobs[job_id].update(kwargs)


@app.route("/pipeline/health", methods=["GET"])
def health():
    return jsonify({"status": "ok", "service": "pipeline-gateway"})


@app.route("/pipeline/submit", methods=["POST"])
def submit():
    if "audio" not in request.files:
        return jsonify({"error": "audio file required"}), 400

    audio = request.files["audio"]
    job_id = str(uuid.uuid4())

    suffix = os.path.splitext(audio.filename or "")[1] or ".audio"
    tmp = tempfile.NamedTemporaryFile(suffix=suffix, delete=False)
    audio.save(tmp.name)
    tmp.close()

    with jobs_lock:
        jobs[job_id] = {
            "status": "processing",
            "step": "В очереди...",
            "progress": 0.0,
            "result": None,
            "error": None,
        }

    t = threading.Thread(target=process_job, args=(job_id, tmp.name), daemon=True)
    t.start()
    log(f"submit: job_id={job_id}")
    return jsonify({"job_id": job_id})


@app.route("/pipeline/status/<job_id>", methods=["GET"])
def status(job_id):
    with jobs_lock:
        job = jobs.get(job_id)
    if job is None:
        return jsonify({"error": "not found"}), 404

    resp = {"status": job["status"], "step": job["step"], "progress": job["progress"]}
    if job["status"] == "done":
        resp.update(job["result"])
    elif job["status"] == "error":
        resp["message"] = job["error"]
    return jsonify(resp)


def process_job(job_id: str, audio_path: str):
    extra_paths: list[str] = []
    try:
        # 1. Diarization
        update_job(job_id, step="Диаризация (определение спикеров)...", progress=0.05)
        log(f"[{job_id}] diarizing...")

        with open(audio_path, "rb") as f:
            r = requests.post(
                DIARIZE_URL,
                files={"audio": (os.path.basename(audio_path), f)},
                timeout=14400,
            )

        if r.status_code != 200:
            raise RuntimeError(f"Diarization failed {r.status_code}: {r.text[:300]}")

        data = r.json()
        segments = data.get("segments", [])
        log(f"[{job_id}] diarization: {len(segments)} segs, {data.get('num_speakers')} speakers")

        if not segments:
            raise RuntimeError("Диаризация не нашла ни одного сегмента")

        # 2. Convert to 16 kHz WAV for slicing
        update_job(job_id, step="Подготовка аудио для транскрибации...", progress=0.30)
        wav_path = audio_path + "_16k.wav"
        extra_paths.append(wav_path)
        res = subprocess.run(
            ["ffmpeg", "-y", "-i", audio_path, "-ar", "16000", "-ac", "1", wav_path],
            capture_output=True,
        )
        if res.returncode != 0:
            raise RuntimeError(f"ffmpeg failed: {res.stderr.decode()[:500]}")

        # 3. Transcribe each segment
        transcribed: list[dict] = []
        full_text_parts: list[str] = []

        for i, seg in enumerate(segments):
            prog = 0.35 + 0.60 * (i / len(segments))
            update_job(job_id,
                step=f"Транскрибация: {i+1}/{len(segments)}...",
                progress=round(prog, 3))

            duration = round(seg["end"] - seg["start"], 3)
            if duration < 0.3:
                continue

            seg_path = audio_path + f"_s{i}.wav"
            extra_paths.append(seg_path)

            r2 = subprocess.run([
                "ffmpeg", "-y", "-i", wav_path,
                "-ss", str(seg["start"]),
                "-t", str(duration),
                seg_path,
            ], capture_output=True)

            if r2.returncode != 0 or not os.path.exists(seg_path):
                continue

            try:
                with open(seg_path, "rb") as sf:
                    wr = requests.post(
                        WHISPER_URL,
                        files={"audio": ("segment.wav", sf, "audio/wav")},
                        timeout=600,
                    )
                if wr.status_code == 200:
                    text = wr.json().get("text", "").strip()
                    if text:
                        transcribed.append({
                            "speaker": seg["speaker"],
                            "start": seg["start"],
                            "end": seg["end"],
                            "text": text,
                        })
                        full_text_parts.append(text)
            except Exception as e:
                log(f"[{job_id}] seg {i} failed: {e}")

        # 4. Merge consecutive same-speaker segments
        merged: list[dict] = []
        for seg in transcribed:
            if merged and merged[-1]["speaker"] == seg["speaker"]:
                merged[-1]["text"] += " " + seg["text"]
                merged[-1]["end"] = seg["end"]
            else:
                merged.append(dict(seg))

        update_job(job_id,
            status="done", step="Готово", progress=1.0,
            result={"segments": merged, "full_text": " ".join(full_text_parts)})
        log(f"[{job_id}] done: {len(merged)} merged segments")

    except Exception as e:
        log(f"[{job_id}] error: {e}")
        update_job(job_id, status="error", step="Ошибка", error=str(e))

    finally:
        for p in [audio_path] + extra_paths:
            try:
                if os.path.exists(p):
                    os.unlink(p)
            except Exception:
                pass


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8090, threaded=True)
