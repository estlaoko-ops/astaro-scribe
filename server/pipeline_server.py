"""
Astaro Scribe — Pipeline Gateway

POST /pipeline/submit?mode=diarize   — диаризация + транскрибация (~2-3ч на 1ч аудио)
POST /pipeline/submit?mode=fast      — только транскрибация (~15-25мин на 1ч аудио)
POST /pipeline/cancel/<id>
GET  /pipeline/status/<id>

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

MAX_SEG_DURATION = 25.0   # max merged/chunk segment for Whisper
MAX_MERGE_GAP    = 1.0    # max gap between same-speaker segs to merge
WHISPER_TIMEOUT  = 120    # seconds per segment

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
    """Merge adjacent same-speaker segments → reduce Whisper API calls 4-8x."""
    if not segments:
        return segments
    merged = [dict(segments[0])]
    for seg in segments[1:]:
        last = merged[-1]
        gap = seg["start"] - last["end"]
        would_be = seg["end"] - last["start"]
        if (seg["speaker"] == last["speaker"]
                and gap <= MAX_MERGE_GAP
                and would_be <= MAX_SEG_DURATION):
            last["end"] = seg["end"]
        else:
            merged.append(dict(seg))
    return merged


def get_audio_duration(wav_path: str) -> float:
    r = subprocess.run(
        ["ffprobe", "-v", "quiet", "-show_entries", "format=duration",
         "-of", "csv=p=0", wav_path],
        capture_output=True, text=True,
    )
    return float(r.stdout.strip())


def make_time_chunks(total_dur: float) -> list[dict]:
    """Fixed-size chunks for fast (no-diarization) mode."""
    chunks = []
    t = 0.0
    while t < total_dur:
        end = min(t + MAX_SEG_DURATION, total_dur)
        if end - t >= 0.3:
            chunks.append({"speaker": "", "start": round(t, 2), "end": round(end, 2)})
        t += MAX_SEG_DURATION
    return chunks


# ─── Endpoints ────────────────────────────────────────────────────────────────

@app.route("/pipeline/health", methods=["GET"])
def health():
    return jsonify({"status": "ok", "service": "pipeline-gateway"})


@app.route("/pipeline/submit", methods=["POST"])
def submit():
    if "audio" not in request.files:
        return jsonify({"error": "audio file required"}), 400

    mode = request.args.get("mode", "diarize")  # "diarize" | "fast"
    if mode not in ("diarize", "fast"):
        mode = "diarize"

    start_sec = request.args.get("start_sec", type=float)
    end_sec   = request.args.get("end_sec",   type=float)

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
            "mode": mode,
        }

    t = threading.Thread(
        target=process_job,
        args=(job_id, tmp.name, mode),
        kwargs={"start_sec": start_sec, "end_sec": end_sec},
        daemon=True,
    )
    t.start()
    log(f"submit: job_id={job_id} mode={mode} trim={start_sec}→{end_sec}")
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
        "mode": job.get("mode", "diarize"),
    }
    if job["status"] == "done":
        resp.update(job["result"])
    elif job["status"] == "error":
        resp["message"] = job["error"]
    return jsonify(resp)


# ─── Worker ───────────────────────────────────────────────────────────────────

def process_job(job_id: str, audio_path: str, mode: str,
                start_sec: float = None, end_sec: float = None):
    extra_paths: list[str] = []
    try:
        # ── Pre-trim if requested ──────────────────────────────────────────
        if start_sec is not None or end_sec is not None:
            update_job(job_id, step="Обрезка фрагмента...", progress=0.02)
            suffix = os.path.splitext(audio_path)[1] or ".audio"
            trim_path = audio_path + "_trim" + suffix
            extra_paths.append(trim_path)
            ff_args = ["ffmpeg", "-y"]
            if start_sec and start_sec > 0:
                ff_args += ["-ss", str(start_sec)]
            ff_args += ["-i", audio_path]
            if end_sec and end_sec > 0:
                duration = end_sec - (start_sec or 0.0)
                ff_args += ["-t", str(max(duration, 0.5))]
            ff_args += ["-c", "copy", trim_path]
            res = subprocess.run(ff_args, capture_output=True)
            if res.returncode == 0 and os.path.exists(trim_path):
                audio_path = trim_path
                log(f"[{job_id}] trimmed {start_sec}s → {end_sec}s")
            else:
                log(f"[{job_id}] trim failed, using full file")

        wav_path = audio_path + "_16k.wav"
        extra_paths.append(wav_path)

        if mode == "fast":
            # ── Fast: no diarization, fixed-size chunks ────────────────────
            update_job(job_id, step="Конвертация аудио...", progress=0.05,
                       phase_start=time.time())
            res = subprocess.run(
                ["ffmpeg", "-y", "-i", audio_path, "-ar", "16000", "-ac", "1", wav_path],
                capture_output=True,
            )
            if res.returncode != 0:
                raise RuntimeError(f"ffmpeg failed: {res.stderr.decode()[:500]}")

            total_dur = get_audio_duration(wav_path)
            segments = make_time_chunks(total_dur)
            has_diarization = False
            log(f"[{job_id}] fast: {len(segments)} chunks, {total_dur:.0f}s")
            update_job(job_id,
                       step=f"Транскрибация: 0/{len(segments)}...",
                       progress=0.10,
                       seg_total=len(segments),
                       seg_done=0,
                       phase_start=time.time())

        else:
            # ── Diarize: Pyannote → merge → convert → transcribe ───────────
            update_job(job_id, step="Диаризация (определение спикеров)...",
                       progress=0.05, phase_start=time.time())
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
            raw_segs = data.get("segments", [])
            num_speakers = data.get("num_speakers", "?")
            log(f"[{job_id}] diarization raw: {len(raw_segs)} segs, {num_speakers} speakers")

            if not raw_segs:
                raise RuntimeError("Диаризация не нашла ни одного сегмента")

            with jobs_lock:
                if jobs[job_id].get("cancelled"):
                    return

            segments = merge_diarization_segments(raw_segs)
            log(f"[{job_id}] after merge: {len(raw_segs)} → {len(segments)} segs, "
                f"{num_speakers} speakers")
            update_job(job_id,
                       step=f"Диаризация: {num_speakers} спикеров, {len(segments)} сегментов",
                       progress=0.28)

            update_job(job_id, step="Конвертация аудио...", progress=0.30,
                       phase_start=time.time())
            res = subprocess.run(
                ["ffmpeg", "-y", "-i", audio_path, "-ar", "16000", "-ac", "1", wav_path],
                capture_output=True,
            )
            if res.returncode != 0:
                raise RuntimeError(f"ffmpeg failed: {res.stderr.decode()[:500]}")

            has_diarization = True
            update_job(job_id,
                       seg_total=len(segments),
                       seg_done=0,
                       phase_start=time.time())

        # ── Transcribe segments (shared for both modes) ────────────────────
        transcribed: list[dict] = []
        full_text_parts: list[str] = []
        seg_errors: list[str] = []

        for i, seg in enumerate(segments):
            with jobs_lock:
                if jobs[job_id].get("cancelled"):
                    return

            prog = 0.35 + 0.60 * (i / len(segments)) if mode == "diarize" \
                   else 0.10 + 0.88 * (i / len(segments))
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
                err = f"seg {i}: timeout ({WHISPER_TIMEOUT}s)"
                log(f"[{job_id}] {err}")
                seg_errors.append(err)
            except Exception as e:
                err = f"seg {i}: {e}"
                log(f"[{job_id}] {err}")
                seg_errors.append(err)

            if seg_errors:
                update_job(job_id, seg_errors=list(seg_errors))

        update_job(job_id, seg_done=len(segments))

        # ── Merge consecutive same-speaker ────────────────────────────────
        merged: list[dict] = []
        for seg in transcribed:
            if merged and merged[-1]["speaker"] == seg["speaker"]:
                merged[-1]["text"] += " " + seg["text"]
                merged[-1]["end"] = seg["end"]
            else:
                merged.append(dict(seg))

        if not merged:
            raise RuntimeError(
                f"Транскрибация не дала результата. "
                f"Обработано сегментов: {len(segments)}, ошибок: {len(seg_errors)}. "
                + (f"Первая ошибка: {seg_errors[0]}" if seg_errors else "")
            )

        update_job(job_id,
                   status="done", step="Готово", progress=1.0,
                   result={
                       "segments": merged,
                       "full_text": " ".join(full_text_parts),
                       "has_diarization": has_diarization,
                   })
        log(f"[{job_id}] done: {len(merged)} segs, mode={mode}, "
            f"{len(seg_errors)} errors")

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
