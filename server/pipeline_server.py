"""
Astaro Scribe — Pipeline Gateway
Оркестрирует существующие сервисы: diarization-gateway + whisper-gateway.

Развёрнуто по адресу: /opt/apps/local-models/pipeline-gateway/server.py

Сервис:
    POST /pipeline/submit        — принять аудиофайл, вернуть {"job_id": "..."}
    POST /pipeline/cancel/<id>   — отменить задачу
    GET  /pipeline/status/<id>   — статус + результат

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

# Max merged segment duration sent to Whisper (seconds)
MAX_SEG_DURATION = 25.0
# Max gap between same-speaker segments to merge (seconds)
MAX_MERGE_GAP = 1.0
# Timeout per Whisper call
WHISPER_TIMEOUT = 120

jobs: dict[str, dict] = {}
jobs_lock = threading.Lock()


def log(msg: str):
    ts = time.strftime("%Y-%m-%d %H:%M:%S")
    print(f"[{ts}] [PIPELINE] {msg}", flush=True)


def update_job(job_id: str, **kwargs):
    with jobs_lock:
        if job_id in jobs:
            jobs[job_id].update(kwargs)


def merge_diarization_segments(segments: list[dict]) -> list[dict]:
    """
    Merge adjacent same-speaker segments to reduce Whisper API call count.
    888 short segments from Pyannote → ~150-200 longer ones.
    Constraints: gap ≤ MAX_MERGE_GAP and total duration ≤ MAX_SEG_DURATION.
    """
    if not segments:
        return segments
    merged = [dict(segments[0])]
    for seg in segments[1:]:
        last = merged[-1]
        gap = seg["start"] - last["end"]
        would_be_duration = seg["end"] - last["start"]
        if (seg["speaker"] == last["speaker"]
                and gap <= MAX_MERGE_GAP
                and would_be_duration <= MAX_SEG_DURATION):
            last["end"] = seg["end"]
        else:
            merged.append(dict(seg))
    return merged


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
            "cancelled": False,
            "result": None,
            "error": None,
            "seg_done": 0,
            "seg_total": 0,
            "phase_start": time.time(),
            "seg_errors": [],
        }

    t = threading.Thread(target=process_job, args=(job_id, tmp.name), daemon=True)
    t.start()
    log(f"submit: job_id={job_id}")
    return jsonify({"job_id": job_id})


@app.route("/pipeline/cancel/<job_id>", methods=["POST"])
def cancel(job_id):
    with jobs_lock:
        job = jobs.get(job_id)
    if job is None:
        return jsonify({"error": "not found"}), 404
    update_job(job_id, cancelled=True, status="cancelled", step="Отменено пользователем")
    log(f"cancel: job_id={job_id}")
    return jsonify({"ok": True})


@app.route("/pipeline/status/<job_id>", methods=["GET"])
def status(job_id):
    with jobs_lock:
        job = jobs.get(job_id)
    if job is None:
        return jsonify({"error": "not found"}), 404

    phase_elapsed = int(time.time() - job.get("phase_start", time.time()))
    resp = {
        "status": job["status"],
        "step": job["step"],
        "progress": job["progress"],
        "seg_done": job.get("seg_done", 0),
        "seg_total": job.get("seg_total", 0),
        "phase_elapsed_sec": phase_elapsed,
        "seg_errors": job.get("seg_errors", []),
    }
    if job["status"] == "done":
        resp.update(job["result"])
    elif job["status"] == "error":
        resp["message"] = job["error"]
    return jsonify(resp)


def process_job(job_id: str, audio_path: str):
    extra_paths: list[str] = []
    try:
        # ── 1. Diarization ────────────────────────────────────────────────────
        update_job(job_id,
                   step="Диаризация (определение спикеров)...",
                   progress=0.05,
                   phase_start=time.time())
        log(f"[{job_id}] diarizing...")

        with open(audio_path, "rb") as f:
            r = requests.post(
                DIARIZE_URL,
                files={"audio": (os.path.basename(audio_path), f)},
                timeout=14400,
            )

        if r.status_code != 200:
            raise RuntimeError(f"Ошибка диаризации ({r.status_code}): {r.text[:300]}")

        data = r.json()
        raw_segments = data.get("segments", [])
        num_speakers = data.get("num_speakers", "?")
        log(f"[{job_id}] diarization raw: {len(raw_segments)} segs, {num_speakers} speakers")

        if not raw_segments:
            raise RuntimeError("Диаризация не нашла ни одного сегмента в аудио")

        with jobs_lock:
            if jobs[job_id].get("cancelled"):
                return

        # ── 2. Pre-merge segments ─────────────────────────────────────────────
        segments = merge_diarization_segments(raw_segments)
        log(f"[{job_id}] after merge: {len(raw_segments)} → {len(segments)} segs "
            f"(max {MAX_SEG_DURATION}s, gap ≤ {MAX_MERGE_GAP}s), {num_speakers} speakers")
        update_job(job_id, step=f"Диаризация: {num_speakers} спикеров, {len(segments)} сегментов",
                   progress=0.28)

        # ── 3. Convert to 16 kHz WAV ──────────────────────────────────────────
        update_job(job_id,
                   step="Конвертация аудио...",
                   progress=0.30,
                   phase_start=time.time())
        wav_path = audio_path + "_16k.wav"
        extra_paths.append(wav_path)
        res = subprocess.run(
            ["ffmpeg", "-y", "-i", audio_path, "-ar", "16000", "-ac", "1", wav_path],
            capture_output=True,
        )
        if res.returncode != 0:
            raise RuntimeError(f"ffmpeg failed: {res.stderr.decode()[:500]}")

        # ── 4. Transcribe each segment ────────────────────────────────────────
        transcribed: list[dict] = []
        full_text_parts: list[str] = []
        seg_errors: list[str] = []

        update_job(job_id,
                   seg_total=len(segments),
                   seg_done=0,
                   phase_start=time.time())

        for i, seg in enumerate(segments):
            with jobs_lock:
                if jobs[job_id].get("cancelled"):
                    return

            prog = 0.35 + 0.60 * (i / len(segments))
            update_job(job_id,
                       step=f"Транскрибация: {i + 1}/{len(segments)}...",
                       progress=round(prog, 3),
                       seg_done=i)

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
                err = f"seg {i}: ffmpeg slice failed"
                log(f"[{job_id}] {err}")
                seg_errors.append(err)
                continue

            try:
                with open(seg_path, "rb") as sf:
                    wr = requests.post(
                        WHISPER_URL,
                        files={"audio": ("segment.wav", sf, "audio/wav")},
                        timeout=WHISPER_TIMEOUT,
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
                else:
                    err = f"seg {i}: whisper HTTP {wr.status_code}"
                    log(f"[{job_id}] {err}")
                    seg_errors.append(err)
            except requests.exceptions.Timeout:
                err = f"seg {i}: whisper timeout ({WHISPER_TIMEOUT}s)"
                log(f"[{job_id}] {err}")
                seg_errors.append(err)
            except Exception as e:
                err = f"seg {i}: {e}"
                log(f"[{job_id}] {err}")
                seg_errors.append(err)

            if seg_errors:
                update_job(job_id, seg_errors=list(seg_errors))

        update_job(job_id, seg_done=len(segments))

        # ── 5. Merge consecutive same-speaker transcribed segments ────────────
        merged: list[dict] = []
        for seg in transcribed:
            if merged and merged[-1]["speaker"] == seg["speaker"]:
                merged[-1]["text"] += " " + seg["text"]
                merged[-1]["end"] = seg["end"]
            else:
                merged.append(dict(seg))

        if not merged:
            raise RuntimeError(
                f"Транскрибация не дала результата. Сегментов обработано: {len(segments)}, "
                f"ошибок: {len(seg_errors)}. "
                + (f"Первая ошибка: {seg_errors[0]}" if seg_errors else "")
            )

        update_job(job_id,
                   status="done", step="Готово", progress=1.0,
                   result={"segments": merged, "full_text": " ".join(full_text_parts)})
        log(f"[{job_id}] done: {len(merged)} merged segments, {len(seg_errors)} seg errors")

    except Exception as e:
        log(f"[{job_id}] FATAL: {e}")
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
