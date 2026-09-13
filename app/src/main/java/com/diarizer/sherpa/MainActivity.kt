package com.diarizer.sherpa

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// ─── Colors ───────────────────────────────────────────────────────────────────

private val Primary      = Color(0xFF818CF8)
private val Background   = Color(0xFF060D1A)
private val Surface      = Color(0xFF0C1829)
private val SurfaceVar   = Color(0xFF162438)
private val OnSurface    = Color(0xFFE2E8F0)
private val OnSurfaceVar = Color(0xFF94A3B8)
private val AccentIndigo = Color(0xFF4F46E5)
private val AccentGreen  = Color(0xFF34D399)
private val ErrorRed     = Color(0xFFF87171)

private val SpeakerPalette = listOf(
    Color(0xFF818CF8), Color(0xFFA78BFA), Color(0xFF34D399),
    Color(0xFFFBBF24), Color(0xFF60A5FA), Color(0xFFFB923C),
    Color(0xFFF472B6), Color(0xFF4ADE80),
)

private val AppColorScheme = darkColorScheme(
    primary          = Primary,
    secondary        = Color(0xFFA78BFA),
    tertiary         = AccentGreen,
    error            = ErrorRed,
    background       = Background,
    surface          = Surface,
    surfaceVariant   = SurfaceVar,
    onPrimary        = Color(0xFF1E1B4B),
    onSecondary      = Color(0xFF2E1065),
    onBackground     = OnSurface,
    onSurface        = OnSurface,
    onSurfaceVariant = OnSurfaceVar,
)

// ─── Helpers ──────────────────────────────────────────────────────────────────

private fun fmtDuration(sec: Long): String {
    val m = sec / 60; val s = sec % 60
    return if (m > 0) "%d:%02d".format(m, s) else "${s}с"
}

private fun fmtDate(ms: Long): String =
    SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(Date(ms))

private fun buildSpeakerText(
    segments: List<ServerApi.Segment>,
    names: Map<String, String>,
): String {
    val sb = StringBuilder()
    var last: String? = null
    for (seg in segments) {
        val name = names[seg.speaker] ?: seg.speaker
        if (name != last) {
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append("$name:\n")
            last = name
        }
        sb.append(seg.text.trim()).append(" ")
    }
    return sb.toString().trimEnd()
}

private fun speakerColor(rawId: String, allSpeakers: List<String>): Color {
    val idx = allSpeakers.indexOf(rawId).coerceAtLeast(0)
    return SpeakerPalette[idx % SpeakerPalette.size]
}

private fun saveStepLog(prefs: SharedPreferences, log: List<StepEntry>) {
    val arr = JSONArray()
    log.forEach { arr.put(JSONObject().put("label", it.label).put("elapsed", it.elapsedSec)) }
    prefs.edit().putString("step_log", arr.toString()).apply()
}

private fun loadStepLog(prefs: SharedPreferences): List<StepEntry> {
    val json = prefs.getString("step_log", null) ?: return emptyList()
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            StepEntry(o.getString("label"), o.getLong("elapsed"))
        }
    } catch (_: Exception) { emptyList() }
}

// ─── Data classes ─────────────────────────────────────────────────────────────

private data class StepEntry(val label: String, val elapsedSec: Long)

// ─── UI State ─────────────────────────────────────────────────────────────────

private sealed class UiState {
    object Idle : UiState()
    data class FileSelected(val name: String, val sizeMb: Float) : UiState()
    object Uploading : UiState()
    data class Processing(val step: String, val progress: Float) : UiState()
    data class Done(
        val segments: List<ServerApi.Segment>,
        val fullText: String,
    ) : UiState()
    data class Error(val message: String) : UiState()
}

// ─── Activity ─────────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = AppColorScheme) {
                MainScreen()
            }
        }
    }
}

// ─── Main Screen ──────────────────────────────────────────────────────────────

@Composable
private fun MainScreen() {
    val context = LocalContext.current
    val activity = context as MainActivity
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("astaro_prefs", android.content.Context.MODE_PRIVATE) }

    var uiState by remember { mutableStateOf<UiState>(UiState.Idle) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedName by remember { mutableStateOf("") }
    var jobId by remember { mutableStateOf<String?>(null) }
    var startMs by remember { mutableStateOf(0L) }
    var elapsedSec by remember { mutableStateOf(0L) }
    var stepLog by remember { mutableStateOf(listOf<StepEntry>()) }
    var lastLoggedStep by remember { mutableStateOf("") }
    var activeUploadJob by remember { mutableStateOf<Job?>(null) }
    var speakerNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    // ── Permission: POST_NOTIFICATIONS (Android 13+) ───────────────────────
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not — we tried */ }

    // ── Restore saved job on app start ─────────────────────────────────────
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        val savedId = prefs.getString("job_id", null) ?: return@LaunchedEffect
        selectedName = prefs.getString("file_name", "") ?: ""
        startMs = prefs.getLong("start_ms", System.currentTimeMillis())
        stepLog = loadStepLog(prefs)
        uiState = UiState.Processing("Восстановление соединения...", 0f)
        jobId = savedId
        activity.startForegroundService(
            Intent(activity, TranscriberService::class.java)
                .putExtra("status_text", "Обработка продолжается...")
        )
    }

    // ── Elapsed timer ──────────────────────────────────────────────────────
    LaunchedEffect(startMs) {
        if (startMs == 0L) { elapsedSec = 0L; return@LaunchedEffect }
        while (true) {
            elapsedSec = (System.currentTimeMillis() - startMs) / 1000
            delay(1_000)
        }
    }

    // ── Status polling ─────────────────────────────────────────────────────
    LaunchedEffect(jobId) {
        val id = jobId ?: return@LaunchedEffect
        while (true) {
            delay(5_000)
            try {
                val s = ServerApi.pollStatus(id)
                when (s.status) {
                    "done" -> {
                        prefs.edit().remove("job_id").remove("start_ms").remove("file_name").remove("step_log").apply()
                        activity.stopService(Intent(activity, TranscriberService::class.java))
                        val segs = s.segments ?: emptyList()
                        val rawSpeakers = segs.map { it.speaker }.distinct().sorted()
                        speakerNames = rawSpeakers.mapIndexed { i, id2 -> id2 to "Спикер ${i + 1}" }.toMap()
                        val initialSpeakerText = buildSpeakerText(segs, speakerNames)
                        HistoryManager.add(prefs, HistoryEntry(
                            id = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            fileName = selectedName,
                            speakerText = initialSpeakerText,
                            fullText = s.fullText ?: "",
                        ))
                        uiState = UiState.Done(segs, s.fullText ?: "")
                        jobId = null
                        break
                    }
                    "error" -> {
                        prefs.edit().remove("job_id").remove("start_ms").remove("file_name").remove("step_log").apply()
                        activity.stopService(Intent(activity, TranscriberService::class.java))
                        uiState = UiState.Error(s.errorMessage ?: "Ошибка на сервере")
                        jobId = null; break
                    }
                    "not_found", "cancelled" -> {
                        prefs.edit().remove("job_id").remove("start_ms").remove("file_name").remove("step_log").apply()
                        activity.stopService(Intent(activity, TranscriberService::class.java))
                        uiState = UiState.Error(s.errorMessage ?: "Задача не найдена или отменена")
                        jobId = null; break
                    }
                    else -> {
                        if (s.step != lastLoggedStep) {
                            lastLoggedStep = s.step
                            val elapsed = if (startMs > 0L) (System.currentTimeMillis() - startMs) / 1000 else 0L
                            stepLog = stepLog + StepEntry(s.step, elapsed)
                            saveStepLog(prefs, stepLog)
                        }
                        uiState = UiState.Processing(s.step, s.progress)
                        TranscriberService.postProgress(context, s.step, (s.progress * 100).toInt(), s.progress < 0.05f)
                    }
                }
            } catch (_: Exception) { /* keep polling on transient errors */ }
        }
    }

    // ── File picker ────────────────────────────────────────────────────────
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedUri = uri
            selectedName = uri.lastPathSegment?.substringAfterLast('/') ?: "audio"
            val sizeMb = context.contentResolver.openFileDescriptor(uri, "r")
                ?.use { it.statSize / 1_048_576f } ?: 0f
            uiState = UiState.FileSelected(selectedName, sizeMb)
        }
    }

    // ── Cancel ─────────────────────────────────────────────────────────────
    fun cancelCurrentJob() {
        activeUploadJob?.cancel(); activeUploadJob = null
        val id = jobId
        if (id != null) {
            scope.launch { runCatching { ServerApi.cancelJob(id) } }
            jobId = null
        }
        prefs.edit().remove("job_id").remove("start_ms").remove("file_name").remove("step_log").apply()
        activity.stopService(Intent(activity, TranscriberService::class.java))
        startMs = 0L; stepLog = emptyList(); lastLoggedStep = ""
        uiState = if (selectedUri != null) {
            val sz = context.contentResolver.openFileDescriptor(selectedUri!!, "r")
                ?.use { it.statSize / 1_048_576f } ?: 0f
            UiState.FileSelected(selectedName, sz)
        } else UiState.Idle
    }

    // ── Upload ─────────────────────────────────────────────────────────────
    fun startUpload() {
        val uri = selectedUri ?: return
        startMs = System.currentTimeMillis()
        stepLog = emptyList(); lastLoggedStep = ""
        uiState = UiState.Uploading
        activity.startForegroundService(
            Intent(activity, TranscriberService::class.java)
                .putExtra("status_text", "Загрузка на сервер...")
        )
        activeUploadJob = scope.launch {
            try {
                val id = ServerApi.submitJob(context, uri)
                if (!isActive) return@launch
                activeUploadJob = null
                prefs.edit().putString("job_id", id).putLong("start_ms", startMs).putString("file_name", selectedName).apply()
                jobId = id
                uiState = UiState.Processing("Задача принята, обрабатывается...", 0f)
            } catch (e: Exception) {
                if (!isActive) return@launch
                activity.stopService(Intent(activity, TranscriberService::class.java))
                uiState = UiState.Error(e.message ?: "Ошибка загрузки")
            }
        }
    }

    // ── Dialogs ────────────────────────────────────────────────────────────
    var showVersionHistory by remember { mutableStateOf(false) }
    var showHistoryDialog by remember { mutableStateOf(false) }

    val versionHistory = listOf(
        "v7.0 · Sumer · Server-side pipeline",
        "v6.9 · Phoenicia · Whisper Small INT8",
        "v6.8 · Hittite · Whisper Small INT8",
        "v6.7 · Assyria · Whisper Small INT8",
        "v6.6 · Babylon · Whisper Small INT8",
        "v6.5 · Persia · Whisper Small INT8",
    )

    if (showVersionHistory) {
        AlertDialog(
            onDismissRequest = { showVersionHistory = false },
            title = { Text("История версий") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    versionHistory.forEach { entry ->
                        Text(entry,
                            style = TextStyle(fontSize = 13.sp, color = OnSurface, fontFamily = FontFamily.Monospace),
                            modifier = Modifier.padding(vertical = 3.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showVersionHistory = false }) { Text("Закрыть") } },
            containerColor = Surface,
        )
    }

    if (showHistoryDialog) {
        HistoryDialog(prefs = prefs) { showHistoryDialog = false }
    }

    // ── Layout ─────────────────────────────────────────────────────────────
    androidx.compose.material3.Surface(modifier = Modifier.fillMaxSize(), color = Background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(56.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("Astaro Scribe",
                        style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold,
                            color = Primary, letterSpacing = 0.3.sp))
                    Text("v7.0 · Sumer",
                        style = TextStyle(fontSize = 12.sp, color = OnSurfaceVar),
                        modifier = Modifier.clickable { showVersionHistory = true })
                }
                TextButton(onClick = { showHistoryDialog = true }) {
                    Text("История", fontSize = 13.sp, color = OnSurfaceVar)
                }
            }
            Spacer(Modifier.height(28.dp))

            when (val s = uiState) {
                is UiState.Idle ->
                    PickerCard { filePicker.launch("audio/*") }

                is UiState.FileSelected -> {
                    FileCard(s.name, s.sizeMb) { filePicker.launch("audio/*") }
                    Spacer(Modifier.height(16.dp))
                    ActionButton("Загрузить и обработать") { startUpload() }
                }

                is UiState.Uploading -> {
                    ProgressCard("Загрузка на сервер...", 0f, elapsedSec, true)
                    Spacer(Modifier.height(8.dp))
                    CancelButton { cancelCurrentJob() }
                }

                is UiState.Processing -> {
                    FileCard(selectedName, 0f, compact = true, onChangeTap = null)
                    Spacer(Modifier.height(12.dp))
                    ProgressCard(s.step, s.progress, elapsedSec, s.progress < 0.01f)
                    if (stepLog.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        StepLogCard(stepLog)
                    }
                    Spacer(Modifier.height(8.dp))
                    CancelButton { cancelCurrentJob() }
                }

                is UiState.Done -> {
                    val speakerText = remember(s.segments, speakerNames) {
                        buildSpeakerText(s.segments, speakerNames)
                    }
                    ResultBlock(
                        state = s,
                        speakerText = speakerText,
                        speakerNames = speakerNames,
                        audioUri = selectedUri,
                        onRenameConfirm = { speakerNames = it },
                        onReset = {
                            selectedUri = null; selectedName = ""
                            jobId = null; startMs = 0L; stepLog = emptyList()
                            prefs.edit().remove("job_id").remove("start_ms").remove("file_name").remove("step_log").apply()
                            speakerNames = emptyMap()
                            uiState = UiState.Idle
                        },
                    )
                }

                is UiState.Error ->
                    ErrorCard(s.message) {
                        uiState = if (selectedUri != null) {
                            val sz = context.contentResolver.openFileDescriptor(selectedUri!!, "r")
                                ?.use { it.statSize / 1_048_576f } ?: 0f
                            UiState.FileSelected(selectedName, sz)
                        } else UiState.Idle
                    }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

// ─── Player ───────────────────────────────────────────────────────────────────

@Composable
private fun PlayerCard(
    uri: Uri?,
    segments: List<ServerApi.Segment>,
    speakerNames: Map<String, String>,
    currentSegIdx: Int,
    onCurrentSegChange: (Int) -> Unit,
) {
    if (uri == null) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var playerPos by remember { mutableStateOf(0L) }
    var playerDuration by remember { mutableStateOf(0L) }

    // Init MediaPlayer
    DisposableEffect(uri) {
        val mp = MediaPlayer()
        val job = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    mp.setDataSource(context, uri)
                    mp.prepare()
                }
                playerDuration = mp.duration.toLong()
                mp.setOnCompletionListener {
                    isPlaying = false
                    playerPos = 0L
                    onCurrentSegChange(-1)
                }
                mediaPlayer = mp
            } catch (_: Exception) { mp.release() }
        }
        onDispose {
            job.cancel()
            runCatching { if (mp.isPlaying) mp.stop() }
            mp.release()
            mediaPlayer = null
            isPlaying = false
            playerPos = 0L
        }
    }

    // Position tracker
    LaunchedEffect(isPlaying, mediaPlayer) {
        val mp = mediaPlayer ?: return@LaunchedEffect
        if (!isPlaying) return@LaunchedEffect
        while (isActive) {
            val pos = mp.currentPosition.toLong()
            playerPos = pos
            val idx = segments.indexOfFirst { pos >= (it.start * 1000).toLong() && pos <= (it.end * 1000).toLong() }
            if (idx != currentSegIdx) onCurrentSegChange(idx)
            delay(150)
        }
    }

    val mp = mediaPlayer
    if (mp != null && playerDuration > 0L) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Surface),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, SurfaceVar),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val segIdx = if (currentSegIdx >= 0 && currentSegIdx < segments.size) currentSegIdx else -1
                    val speakerColor = if (segIdx >= 0) {
                        val allSpeakers = segments.map { it.speaker }.distinct().sorted()
                        speakerColor(segments[segIdx].speaker, allSpeakers)
                    } else OnSurfaceVar

                    TextButton(
                        onClick = {
                            if (isPlaying) { mp.pause(); isPlaying = false }
                            else { mp.start(); isPlaying = true }
                        },
                        modifier = Modifier.size(44.dp),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(if (isPlaying) "⏸" else "▶", fontSize = 20.sp, color = speakerColor)
                    }

                    Column(Modifier.weight(1f)) {
                        Slider(
                            value = playerPos.toFloat() / playerDuration.toFloat(),
                            onValueChange = { v ->
                                val seekTo = (v * playerDuration).toLong()
                                mp.seekTo(seekTo.toInt())
                                playerPos = seekTo
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = Primary,
                                activeTrackColor = Primary,
                                inactiveTrackColor = SurfaceVar,
                            ),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(fmtDuration(playerPos / 1000), fontSize = 10.sp, color = OnSurfaceVar)
                            if (segIdx >= 0) {
                                val name = speakerNames[segments[segIdx].speaker] ?: segments[segIdx].speaker
                                Text(name, fontSize = 10.sp, color = speakerColor)
                            }
                            Text(fmtDuration(playerDuration / 1000), fontSize = 10.sp, color = OnSurfaceVar)
                        }
                    }
                }
            }
        }
    } else {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Surface),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, SurfaceVar),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), color = OnSurfaceVar, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Загрузка плеера...", fontSize = 13.sp, color = OnSurfaceVar)
            }
        }
    }
}

// ─── Result ───────────────────────────────────────────────────────────────────

@Composable
private fun ResultBlock(
    state: UiState.Done,
    speakerText: String,
    speakerNames: Map<String, String>,
    audioUri: Uri?,
    onRenameConfirm: (Map<String, String>) -> Unit,
    onReset: () -> Unit,
) {
    val context = LocalContext.current
    var expandSpeaker by remember { mutableStateOf(false) }
    var expandPlain by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var currentSegIdx by remember { mutableStateOf(-1) }

    if (showRenameDialog) {
        SpeakerRenameDialog(
            speakerNames = speakerNames,
            onSave = { onRenameConfirm(it); showRenameDialog = false },
            onDismiss = { showRenameDialog = false },
        )
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("✓", fontWeight = FontWeight.Bold, color = AccentGreen, fontSize = 16.sp)
        Spacer(Modifier.width(6.dp))
        Text("Готово", fontWeight = FontWeight.SemiBold, color = AccentGreen, fontSize = 15.sp)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onReset) { Text("↩ Новый файл", color = OnSurfaceVar, fontSize = 13.sp) }
    }
    Spacer(Modifier.height(10.dp))

    // ── Audio player ───────────────────────────────────────────────────────
    if (audioUri != null) {
        PlayerCard(
            uri = audioUri,
            segments = state.segments,
            speakerNames = speakerNames,
            currentSegIdx = currentSegIdx,
            onCurrentSegChange = { currentSegIdx = it },
        )
        Spacer(Modifier.height(8.dp))
    }

    // ── По спикерам ────────────────────────────────────────────────────────
    val allSpeakers = remember(state.segments) { state.segments.map { it.speaker }.distinct().sorted() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, SurfaceVar),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { expandSpeaker = !expandSpeaker }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("По спикерам", fontSize = 14.sp, color = OnSurface, modifier = Modifier.weight(1f))
                if (speakerNames.isNotEmpty()) {
                    TextButton(
                        onClick = { showRenameDialog = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) { Text("✏ Переименовать", fontSize = 11.sp, color = Primary) }
                }
                Text(if (expandSpeaker) "▲" else "▼", fontSize = 11.sp, color = OnSurfaceVar)
            }
            AnimatedVisibility(visible = expandSpeaker) {
                Column {
                    Divider(color = SurfaceVar, thickness = 1.dp)
                    // Segment list with highlighting
                    val segsScrollState = rememberScrollState()
                    val density = LocalDensity.current
                    LaunchedEffect(currentSegIdx) {
                        if (currentSegIdx > 1) {
                            val px = with(density) { ((currentSegIdx - 1) * 68).dp.roundToPx() }
                            segsScrollState.animateScrollTo(px)
                        }
                    }
                    Column(
                        modifier = Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(segsScrollState),
                    ) {
                        state.segments.forEachIndexed { i, seg ->
                            val color = speakerColor(seg.speaker, allSpeakers)
                            val name = speakerNames[seg.speaker] ?: seg.speaker
                            val isActive = i == currentSegIdx
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(if (isActive) color.copy(alpha = 0.10f) else Color.Transparent)
                                    .clickable { /* tap to seek handled via player */ }
                                    .padding(horizontal = 12.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(with(density) { 48.dp })
                                        .background(if (isActive) color else color.copy(alpha = 0.35f),
                                            RoundedCornerShape(2.dp))
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(name, fontSize = 11.sp, color = color, fontWeight = FontWeight.SemiBold)
                                    Text(seg.text, fontSize = 12.sp, color = OnSurface, lineHeight = 17.sp)
                                }
                                Text(fmtDuration(seg.start.toLong()), fontSize = 10.sp, color = OnSurfaceVar,
                                    modifier = Modifier.padding(start = 8.dp, top = 2.dp))
                            }
                        }
                    }
                    Divider(color = SurfaceVar, thickness = 1.dp)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { copyText(context, speakerText) }) {
                            Text("Копировать", color = Primary, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))

    // ── Скачать ────────────────────────────────────────────────────────────
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, SurfaceVar),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Скачать файл", fontSize = 14.sp, color = OnSurface, modifier = Modifier.weight(1f))
            TextButton(onClick = { saveToDownloads(context, speakerText) }) {
                Text("⬇ .txt", color = Primary, fontSize = 13.sp)
            }
        }
    }
    Spacer(Modifier.height(8.dp))

    // ── Сплошной текст ─────────────────────────────────────────────────────
    ResultPanel("Сплошной текст", expandPlain, { expandPlain = !expandPlain }, state.fullText) {
        copyText(context, state.fullText)
    }
}

// ─── Speaker Rename Dialog ────────────────────────────────────────────────────

@Composable
private fun SpeakerRenameDialog(
    speakerNames: Map<String, String>,
    onSave: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var edits by remember { mutableStateOf(speakerNames.toMap()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Переименовать спикеров") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                speakerNames.keys.sorted().forEach { rawId ->
                    OutlinedTextField(
                        value = edits[rawId] ?: "",
                        onValueChange = { v -> edits = edits.toMutableMap().also { it[rawId] = v } },
                        label = { Text(rawId, fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Primary,
                            unfocusedBorderColor = SurfaceVar,
                            focusedTextColor = OnSurface,
                            unfocusedTextColor = OnSurface,
                        ),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(edits) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        containerColor = Surface,
    )
}

// ─── History Dialog ───────────────────────────────────────────────────────────

@Composable
private fun HistoryDialog(prefs: SharedPreferences, onDismiss: () -> Unit) {
    var entries by remember { mutableStateOf(HistoryManager.load(prefs)) }
    var viewEntry by remember { mutableStateOf<HistoryEntry?>(null) }

    if (viewEntry != null) {
        val e = viewEntry!!
        AlertDialog(
            onDismissRequest = { viewEntry = null },
            title = { Text(e.fileName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(fmtDate(e.timestamp), fontSize = 11.sp, color = OnSurfaceVar)
                    Spacer(Modifier.height(8.dp))
                    Text(e.speakerText, fontSize = 12.sp, color = OnSurface,
                        fontFamily = FontFamily.Monospace, lineHeight = 18.sp)
                }
            },
            confirmButton = {
                val ctx = LocalContext.current
                TextButton(onClick = { copyText(ctx, e.speakerText); viewEntry = null }) { Text("Копировать") }
            },
            dismissButton = { TextButton(onClick = { viewEntry = null }) { Text("Закрыть") } },
            containerColor = Surface,
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("История транскрибаций") },
        text = {
            if (entries.isEmpty()) {
                Text("Нет сохранённых транскрибаций", fontSize = 13.sp, color = OnSurfaceVar)
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    entries.forEach { e ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { viewEntry = e },
                            colors = CardDefaults.cardColors(containerColor = SurfaceVar),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(e.fileName, fontSize = 13.sp, color = OnSurface,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                    Text(fmtDate(e.timestamp), fontSize = 11.sp, color = OnSurfaceVar)
                                    Text(e.speakerText.take(80) + if (e.speakerText.length > 80) "…" else "",
                                        fontSize = 11.sp, color = OnSurfaceVar, maxLines = 2)
                                }
                                TextButton(
                                    onClick = { HistoryManager.delete(prefs, e.id); entries = HistoryManager.load(prefs) },
                                    contentPadding = PaddingValues(4.dp),
                                ) { Text("✕", color = ErrorRed, fontSize = 14.sp) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        containerColor = Surface,
    )
}

// ─── Common composables ───────────────────────────────────────────────────────

@Composable
private fun PickerCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().height(140.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.5.dp, SurfaceVar),
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Text("+", fontSize = 32.sp, color = OnSurfaceVar)
            Spacer(Modifier.height(6.dp))
            Text("Выбрать аудиофайл", fontSize = 14.sp, color = OnSurfaceVar)
            Text("MP3  M4A  WAV  OGG", fontSize = 11.sp, color = OnSurfaceVar.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun FileCard(name: String, sizeMb: Float, compact: Boolean = false, onChangeTap: (() -> Unit)?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, SurfaceVar),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = if (compact) 10.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("🎵", fontSize = 20.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, fontSize = 13.sp, color = OnSurface, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sizeMb > 0) Text("%.1f МБ".format(sizeMb), fontSize = 11.sp, color = OnSurfaceVar)
            }
            if (onChangeTap != null) {
                TextButton(onClick = onChangeTap) { Text("↩", fontSize = 16.sp, color = OnSurfaceVar) }
            }
        }
    }
}

@Composable
private fun ProgressCard(step: String, progress: Float, elapsed: Long, indeterminate: Boolean) {
    val timeStr = if (elapsed > 0) "  ⏱ ${fmtDuration(elapsed)}" else ""
    val etaSec = if (progress > 0.05f && elapsed > 5L)
        (elapsed * (1.0 - progress) / progress).toLong() else -1L
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, SurfaceVar),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Primary, strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(step + timeStr, fontSize = 13.sp, color = OnSurface,
                    modifier = Modifier.weight(1f), maxLines = 2)
            }
            if (!indeterminate && progress > 0.01f) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(progress = progress.coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = Primary, trackColor = SurfaceVar)
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${(progress * 100).toInt()}%", fontSize = 11.sp, color = OnSurfaceVar)
                    if (etaSec >= 0)
                        Text("~ещё ${fmtDuration(etaSec)}", fontSize = 11.sp, color = OnSurfaceVar)
                }
            }
        }
    }
}

@Composable
private fun StepLogCard(log: List<StepEntry>) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, SurfaceVar),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Детали обработки", fontSize = 12.sp, color = OnSurfaceVar, modifier = Modifier.weight(1f))
                Text(if (expanded) "▲" else "▼", fontSize = 10.sp, color = OnSurfaceVar)
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                    log.forEachIndexed { i, entry ->
                        val duration = if (i + 1 < log.size) log[i + 1].elapsedSec - entry.elapsedSec else null
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                            Text(fmtDuration(entry.elapsedSec), fontSize = 11.sp, color = Primary,
                                fontFamily = FontFamily.Monospace, modifier = Modifier.width(48.dp))
                            Text(entry.label + if (duration != null) "  (+${fmtDuration(duration)})" else "",
                                fontSize = 11.sp, color = OnSurface, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionButton(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AccentIndigo),
        shape = RoundedCornerShape(14.dp)) {
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Color.White)
    }
}

@Composable
private fun CancelButton(onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
        border = BorderStroke(1.dp, ErrorRed.copy(alpha = 0.4f)),
        shape = RoundedCornerShape(14.dp),
    ) { Text("Остановить обработку", fontSize = 14.sp) }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A0A0A)),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, ErrorRed.copy(alpha = 0.5f))) {
        Column(Modifier.padding(16.dp)) {
            Text("Ошибка", fontWeight = FontWeight.SemiBold, color = ErrorRed, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            Text(message, fontSize = 12.sp, color = OnSurfaceVar)
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onRetry) { Text("↩ Попробовать снова", color = Primary) }
        }
    }
}

@Composable
private fun ResultPanel(title: String, expanded: Boolean, onToggle: () -> Unit, content: String, onCopy: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, SurfaceVar)) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 14.sp, color = OnSurface, modifier = Modifier.weight(1f))
                Text(if (expanded) "▲" else "▼", fontSize = 11.sp, color = OnSurfaceVar)
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    Divider(color = SurfaceVar, thickness = 1.dp)
                    Text(text = content.ifEmpty { "(пусто)" },
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        fontSize = 12.sp, color = OnSurface,
                        fontFamily = FontFamily.Monospace, lineHeight = 18.sp)
                    Divider(color = SurfaceVar, thickness = 1.dp)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onCopy) { Text("Копировать", color = Primary, fontSize = 13.sp) }
                    }
                }
            }
        }
    }
}

// ─── Utils ────────────────────────────────────────────────────────────────────

private fun copyText(context: android.content.Context, text: String) {
    val cb = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
    cb.setPrimaryClip(ClipData.newPlainText("transcript", text))
    Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
}

private fun saveToDownloads(context: android.content.Context, text: String) {
    try {
        val name = "astaro_${System.currentTimeMillis()}.txt"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let { context.contentResolver.openOutputStream(it)?.use { os -> os.write(text.toByteArray()) } }
        } else {
            @Suppress("DEPRECATION")
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name)
                .writeText(text)
        }
        Toast.makeText(context, "Сохранено: $name", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
