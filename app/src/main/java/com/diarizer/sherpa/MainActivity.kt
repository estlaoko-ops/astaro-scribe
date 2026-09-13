package com.diarizer.sherpa

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

// ─── UI State ─────────────────────────────────────────────────────────────────

private sealed class UiState {
    object Idle : UiState()
    data class FileSelected(val name: String, val sizeMb: Float) : UiState()
    object Uploading : UiState()
    data class Processing(val step: String, val progress: Float) : UiState()
    data class Done(
        val segments: List<ServerApi.Segment>,
        val fullText: String,
        val speakerText: String,
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

    var uiState by remember { mutableStateOf<UiState>(UiState.Idle) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedName by remember { mutableStateOf("") }
    var jobId by remember { mutableStateOf<String?>(null) }
    var startMs by remember { mutableStateOf(0L) }
    var elapsedSec by remember { mutableStateOf(0L) }

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
                        activity.stopService(Intent(activity, TranscriberService::class.java))
                        val segs = s.segments ?: emptyList()
                        uiState = UiState.Done(segs, s.fullText ?: "", ServerApi.formatSpeakerText(segs))
                        jobId = null
                        break
                    }
                    "error" -> {
                        activity.stopService(Intent(activity, TranscriberService::class.java))
                        uiState = UiState.Error(s.errorMessage ?: "Ошибка на сервере")
                        jobId = null
                        break
                    }
                    else -> uiState = UiState.Processing(s.step, s.progress)
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

    // ── Upload action ──────────────────────────────────────────────────────
    fun startUpload() {
        val uri = selectedUri ?: return
        startMs = System.currentTimeMillis()
        uiState = UiState.Uploading

        activity.startForegroundService(
            Intent(activity, TranscriberService::class.java)
                .putExtra("status_text", "Загрузка на сервер...")
        )

        scope.launch {
            try {
                val id = ServerApi.submitJob(context, uri)
                jobId = id
                uiState = UiState.Processing("Задача принята, обрабатывается...", 0f)
            } catch (e: Exception) {
                activity.stopService(Intent(activity, TranscriberService::class.java))
                uiState = UiState.Error(e.message ?: "Ошибка загрузки")
            }
        }
    }

    // ── Version history ────────────────────────────────────────────────────
    var showVersionHistory by remember { mutableStateOf(false) }
    val versionHistory = listOf(
        "v7.0 — Server-side pipeline, thin client",
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
                Column {
                    versionHistory.forEach { entry ->
                        Text(
                            entry,
                            style = TextStyle(fontSize = 13.sp, color = OnSurface,
                                fontFamily = FontFamily.Monospace),
                            modifier = Modifier.padding(vertical = 3.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showVersionHistory = false }) { Text("Закрыть") }
            },
            containerColor = Surface,
        )
    }

    // ── Layout ─────────────────────────────────────────────────────────────
    Surface(modifier = Modifier.fillMaxSize(), color = Background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(56.dp))

            Text(
                "Astaro Scribe",
                style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    color = Primary, letterSpacing = 0.3.sp),
            )
            Text(
                "v7.0",
                style = TextStyle(fontSize = 12.sp, color = OnSurfaceVar),
                modifier = Modifier.clickable { showVersionHistory = true },
            )
            Spacer(Modifier.height(28.dp))

            when (val s = uiState) {
                is UiState.Idle ->
                    PickerCard { filePicker.launch("audio/*") }

                is UiState.FileSelected -> {
                    FileCard(s.name, s.sizeMb) { filePicker.launch("audio/*") }
                    Spacer(Modifier.height(16.dp))
                    ActionButton("Загрузить и обработать") { startUpload() }
                }

                is UiState.Uploading ->
                    ProgressCard("Загрузка на сервер...", 0f, elapsedSec, true)

                is UiState.Processing -> {
                    FileCard(selectedName, 0f, compact = true, onChangeTap = null)
                    Spacer(Modifier.height(12.dp))
                    ProgressCard(s.step, s.progress, elapsedSec, s.progress < 0.01f)
                }

                is UiState.Done ->
                    ResultBlock(s) {
                        selectedUri = null
                        selectedName = ""
                        jobId = null
                        startMs = 0L
                        uiState = UiState.Idle
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

// ─── Cards ────────────────────────────────────────────────────────────────────

@Composable
private fun PickerCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().height(140.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.5.dp, SurfaceVar),
    ) {
        Column(Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Text("+", fontSize = 32.sp, color = OnSurfaceVar)
            Spacer(Modifier.height(6.dp))
            Text("Выбрать аудиофайл", fontSize = 14.sp, color = OnSurfaceVar)
            Text("MP3  M4A  WAV  OGG", fontSize = 11.sp, color = OnSurfaceVar.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun FileCard(
    name: String,
    sizeMb: Float,
    compact: Boolean = false,
    onChangeTap: (() -> Unit)?,
) {
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
                if (sizeMb > 0)
                    Text("%.1f МБ".format(sizeMb), fontSize = 11.sp, color = OnSurfaceVar)
            }
            if (onChangeTap != null) {
                TextButton(onClick = onChangeTap) {
                    Text("↩", fontSize = 16.sp, color = OnSurfaceVar)
                }
            }
        }
    }
}

@Composable
private fun ProgressCard(step: String, progress: Float, elapsed: Long, indeterminate: Boolean) {
    val min = elapsed / 60; val sec = elapsed % 60
    val timeStr = if (elapsed > 0) "  ⏱ %d:%02d".format(min, sec) else ""
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
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = Primary, trackColor = SurfaceVar,
                )
                Spacer(Modifier.height(4.dp))
                Text("${(progress * 100).toInt()}%", fontSize = 11.sp, color = OnSurfaceVar)
            }
        }
    }
}

@Composable
private fun ActionButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AccentIndigo),
        shape = RoundedCornerShape(14.dp),
    ) {
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Color.White)
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A0A0A)),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, ErrorRed.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Ошибка", fontWeight = FontWeight.SemiBold, color = ErrorRed, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            Text(message, fontSize = 12.sp, color = OnSurfaceVar)
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onRetry) { Text("↩ Попробовать снова", color = Primary) }
        }
    }
}

// ─── Result ───────────────────────────────────────────────────────────────────

@Composable
private fun ResultBlock(state: UiState.Done, onReset: () -> Unit) {
    val context = LocalContext.current
    var expandSpeaker by remember { mutableStateOf(false) }
    var expandPlain by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("✓", fontWeight = FontWeight.Bold, color = AccentGreen, fontSize = 16.sp)
        Spacer(Modifier.width(6.dp))
        Text("Готово", fontWeight = FontWeight.SemiBold, color = AccentGreen, fontSize = 15.sp)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onReset) {
            Text("↩ Новый файл", color = OnSurfaceVar, fontSize = 13.sp)
        }
    }
    Spacer(Modifier.height(12.dp))

    ResultPanel("По спикерам", expandSpeaker, { expandSpeaker = !expandSpeaker }, state.speakerText) {
        copyText(context, state.speakerText)
    }
    Spacer(Modifier.height(8.dp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, SurfaceVar),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Скачать файл", fontSize = 14.sp, color = OnSurface, modifier = Modifier.weight(1f))
            TextButton(onClick = { saveToDownloads(context, state.speakerText) }) {
                Text("⬇ .txt", color = Primary, fontSize = 13.sp)
            }
        }
    }
    Spacer(Modifier.height(8.dp))

    ResultPanel("Сплошной текст", expandPlain, { expandPlain = !expandPlain }, state.fullText) {
        copyText(context, state.fullText)
    }
}

@Composable
private fun ResultPanel(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: String,
    onCopy: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, SurfaceVar),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggle)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, fontSize = 14.sp, color = OnSurface, modifier = Modifier.weight(1f))
                Text(if (expanded) "▲" else "▼", fontSize = 11.sp, color = OnSurfaceVar)
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    HorizontalDivider(color = SurfaceVar, thickness = 1.dp)
                    Text(
                        text = content.ifEmpty { "(пусто)" },
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        fontSize = 12.sp, color = OnSurface,
                        fontFamily = FontFamily.Monospace, lineHeight = 18.sp,
                    )
                    HorizontalDivider(color = SurfaceVar, thickness = 1.dp)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onCopy) {
                            Text("Копировать", color = Primary, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

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
