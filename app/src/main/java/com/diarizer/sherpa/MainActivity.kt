package com.diarizer.sherpa

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val TAG = "MainActivity"
    private var pipeline: Pipeline? = null
    private var savedDecodedAudio: Pipeline.DecodedAudio? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pipeline = Pipeline(this)

        setContent {
            MaterialTheme(
                colorScheme = DarkColorScheme
            ) {
                MainScreen(
                    pipeline = pipeline,
                    context = this,
                    onRunSpeakerTimeline = { uri, filename, onProgress, onLog, onResult, onTimelineObject, onError ->
                        startService(Intent(this@MainActivity, TranscriberService::class.java))
                        lifecycleScope.launch {
                            try {
                                onProgress("Декодирую аудио...")
                                val decoded = withContext(Dispatchers.IO) {
                                    AudioDecoder.decodeToAudio(this@MainActivity, uri)
                                }
                                if (decoded == null) {
                                    onError("Не удалось декодировать аудио")
                                    return@launch
                                }
                                savedDecodedAudio = decoded
                                onLog("[ДЕКОДЕР] Аудио: ${(decoded.samples?.size ?: decoded.numSamples)} семплов, ${decoded.sampleRate}Гц, длительность ${"%.1f".format((decoded.samples?.size ?: decoded.numSamples).toFloat() / decoded.sampleRate)}с")

                                onProgress("Загружаю модели диаризации...")
                                if (pipeline?.isDiarizationLoaded() != true) {
                                    val loaded = withContext(Dispatchers.IO) {
                                        pipeline?.loadDiarizationModels() ?: false
                                    }
                                    if (!loaded) {
                                        onError("Модели диаризации не загружены")
                                        return@launch
                                    }
                                }
                                onProgress("Извлекаю эмбеддинги...")
                                val timeline = withContext(Dispatchers.IO) {
                                    pipeline?.runSpeakerTimeline(
                                        audio = decoded,
                                        onProgress = { msg -> onProgress(msg) },
                                        onLog = { msg -> onLog(msg) }
                                    )
                                }
                                if (timeline != null) {
                                    // Format the result string
                                    val sb = StringBuilder()
                                    sb.appendLine("Найдено спикеров: ${timeline.speakerCount}")
                                    sb.appendLine()
                                    sb.appendLine("Таймлайн:")
                                    for (seg in timeline.segments) {
                                        val spkName = "Спикер ${seg.speakerId + 1}"
                                        sb.appendLine("[${"%.1fс".format(seg.startSec)} — ${"%.1fс".format(seg.endSec)}] $spkName")
                                    }
                                    onResult(sb.toString().trimEnd())
                                    onTimelineObject(timeline)
                                    stopService(Intent(this@MainActivity, TranscriberService::class.java))
                                } else {
                                    onError("Ошибка ML-диаризации")
                                    stopService(Intent(this@MainActivity, TranscriberService::class.java))
                                }
                                // Не чистим savedDecodedAudio — он нужен для onTranscribeDiarizationSegments
                            } catch (e: Exception) {
                                onError("Ошибка: ${e.message}")
                                stopService(Intent(this@MainActivity, TranscriberService::class.java))
                            }
                        }
                    },
                    onStartProcessing = { uri, filename, logger, onProgress, onLog, onResult, onError ->
                        startService(Intent(this@MainActivity, TranscriberService::class.java))
                        lifecycleScope.launch {
                            try {
                                onProgress("Декодирую аудио...")
                                val decoded = AudioDecoder.decodeToAudio(this@MainActivity, uri)

                                if (decoded != null) {
                                    savedDecodedAudio = decoded  // Save for later diarization
                                    onLog("[ДЕКОДЕР] ✅ Аудио: ${(decoded.samples?.size ?: decoded.numSamples)} семплов, ${decoded.sampleRate}Гц")
                                    logger?.log("Аудио декодировано: ${(decoded.samples?.size ?: decoded.numSamples)} семплов, ${decoded.sampleRate}Гц")

                                    onProgress("Распознаю речь...")
                                    val pipelineResult = withContext(Dispatchers.IO) {
                                        pipeline?.runAsr(
                                            audio = decoded,
                                            onProgress = { msg -> onProgress(msg) },
                                            onLog = { msg ->
                                                onLog(msg)
                                                logger?.log(msg)
                                            }
                                        )
                                    }

                                    if (pipelineResult != null && pipelineResult.isNotEmpty()) {
                                        onResult(pipelineResult)
                                    } else if (pipelineResult != null) {
                                        onResult("Речь не распознана")
                                    } else {
                                        onError("Ошибка ASR. Смотрите логи.")
                                    }
                                } else {
                                    onError("Не удалось декодировать аудио")
                                }
                            } catch (e: Exception) {
                                onError("Ошибка: ${e.message}")
                                logger?.logError("Исключение", e)
                            } finally {
                                stopService(Intent(this@MainActivity, TranscriberService::class.java))
                                onProgress("")
                            }
                        }
                    },
                    onStartDiarization = { resultText, onProgress, onLog, onResult, onError ->
                        lifecycleScope.launch {
                            try {
                                val audio = savedDecodedAudio
                                if (audio == null) {
                                    onError("Нет декодированного аудио. Сначала распознайте речь.")
                                    return@launch
                                }
                                onProgress("Разделяю спикеров...")
                                val diarResult = withContext(Dispatchers.IO) {
                                    pipeline?.runDiarization(
                                        audio = audio,
                                        text = resultText,
                                        onProgress = { msg -> onProgress(msg) },
                                        onLog = { msg ->
                                            onLog(msg)
                                        }
                                    )
                                }
                                if (diarResult != null) {
                                    onResult(diarResult)
                                } else {
                                    onError("Ошибка диаризации")
                                }
                            } catch (e: Exception) {
                                onError("Ошибка: ${e.message}")
                            } finally {
                                // Не чистим savedDecodedAudio — он нужен для onTranscribeDiarizationSegments
                                onProgress("")
                            }
                        }
                    },
                    onTranscribeDiarizationSegments = { segments, onProgress, onLog, onComplete, onError ->
                        startService(Intent(this@MainActivity, TranscriberService::class.java))
                        lifecycleScope.launch {
                            try {
                                val audio = savedDecodedAudio
                                if (audio == null) {
                                    onError("Нет декодированного аудио. Сначала выполните диаризацию (альфа).")
                                    return@launch
                                }
                                val cacheDir = java.io.File(cacheDir, "segments")
                                cacheDir.mkdirs()
                                cacheDir.listFiles()?.forEach { it.delete() }
                                val result = withContext(Dispatchers.IO) {
                                    pipeline?.transcribeSegments(
                                        audio = audio,
                                        segments = segments,
                                        cacheDir = cacheDir,
                                        onProgress = { msg -> onProgress(msg) },
                                        onLog = { msg -> onLog(msg) }
                                    ) ?: emptyList()
                                }
                                onComplete(result)
                            } catch (e: Exception) {
                                onError("Ошибка: ${e.message}")
                            } finally {
                                savedDecodedAudio?.cleanup()
                                savedDecodedAudio = null
                                stopService(Intent(this@MainActivity, TranscriberService::class.java))
                                onProgress("")
                            }
                        }
                    },
                    onClearAudio = {
                        savedDecodedAudio?.cleanup()
                        savedDecodedAudio = null
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        savedDecodedAudio?.cleanup()
        savedDecodedAudio = null
        pipeline?.release()
    }
}

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF90CAF9),
    secondary = Color(0xFFCE93D8),
    tertiary = Color(0xFFA5D6A7),
    background = Color(0xFF1A1A2E),
    surface = Color(0xFF16213E),
    surfaceVariant = Color(0xFF0F3460),
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = Color(0xFFE0E0E0),
    onSurface = Color(0xFFE0E0E0),
    onSurfaceVariant = Color(0xFFB0BEC5),
)

@Composable
fun MainScreen(
    pipeline: Pipeline?,
    context: Context,
    onRunSpeakerTimeline: (
        uri: Uri,
        filename: String,
        onProgress: (String) -> Unit,
        onLog: (String) -> Unit,
        onResult: (String) -> Unit,
        onTimelineObject: (Pipeline.SpeakerTimeline) -> Unit,
        onError: (String) -> Unit
    ) -> Unit = { _, _, _, _, _, _, _ -> },
    onStartProcessing: (
        uri: Uri,
        filename: String,
        logger: FileLogger?,
        onProgress: (String) -> Unit,
        onLog: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) -> Unit = { _, _, _, _, _, _, _ -> },
    onStartDiarization: (
        resultText: String,
        onProgress: (String) -> Unit,
        onLog: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) -> Unit = { _, _, _, _, _ -> },
    onTranscribeDiarizationSegments: (
        segments: List<Pipeline.SpeakerSegment>,
        onProgress: (String) -> Unit,
        onLog: (String) -> Unit,
        onComplete: (List<Pipeline.TranscribedSegment>) -> Unit,
        onError: (String) -> Unit
    ) -> Unit = { _, _, _, _, _ -> },
    onClearAudio: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // Compact mode preference (persists across restarts)
    val prefs = context.getSharedPreferences("astaro_prefs", Context.MODE_PRIVATE)
    var isCompactMode by remember { mutableStateOf(prefs.getBoolean("compact_mode", false)) }

    var isInitialized by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var selectedFilename by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var initError by remember { mutableStateOf("") }
    var logFileCreated by remember { mutableStateOf(false) }
    var fileLogger by remember { mutableStateOf<FileLogger?>(null) }

    // ASR done state — shows diarization button
    var isAsrDone by remember { mutableStateOf(false) }

    // Logs dialog state
    var showLogsDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var settingsThresholdText by remember { mutableStateOf("") }
    var settingsThresholdError by remember { mutableStateOf(false) }
    var previousLogs by remember { mutableStateOf<List<FileLogger.SavedLog>>(emptyList()) }
    var selectedLog by remember { mutableStateOf<Pair<String, String>?>(null) }

    val logLines = remember { mutableStateListOf<String>() }
    val clipboardManager = LocalClipboardManager.current

    // Diarization in progress
    var diarizationInProgress by remember { mutableStateOf(false) }

    // Raw ASR result (without diarization) for toggle in modal
    var rawAsrResult by remember { mutableStateOf("") }
    var diarizedResult by remember { mutableStateOf("") }
    var showDiarizedView by remember { mutableStateOf(false) }

    // Speaker timeline modal (временная кнопка для тестов)
    var speakerTimelineResult by remember { mutableStateOf("") }
    var showSpeakerTimelineModal by remember { mutableStateOf(false) }
    // Structured timeline data for player
    var timelineSegments by remember { mutableStateOf<List<Pipeline.SpeakerSegment>>(emptyList()) }
    var timelineSpeakerCount by remember { mutableStateOf(0) }
    var timelineAudioUri by remember { mutableStateOf<Uri?>(null) }

    // Transcribed segments (after slicing + transcribing each diarization segment)
    var transcribedSegments by remember { mutableStateOf<List<Pipeline.TranscribedSegment>>(emptyList()) }
    var showTranscribedModal by remember { mutableStateOf(false) }
    var transcribingSegments by remember { mutableStateOf(false) }

    // Speaker config persistence (survives modal close, resets on new diarization)
    data class SpeakerConfig(val id: Int, val name: String, val mergedInto: Int?)
    var speakerConfigs by remember(transcribedSegments) {
        val ids = transcribedSegments.map { it.speakerId }.distinct().sorted()
        mutableStateOf(ids.map { SpeakerConfig(it, "", null) })
    }
    var showSpeakerConfigDialog by remember { mutableStateOf(false) }
    var editConfigs by remember { mutableStateOf<List<SpeakerConfig>>(emptyList()) }
    // Mini speaker config dialog (single speaker)
    var miniConfigSpeakerId by remember { mutableIntStateOf(-1) }
    // Dialogue text for main screen result (recomputed when configs change)
    val dialogueText = remember(transcribedSegments, speakerConfigs) {
        val cfgs = speakerConfigs
        transcribedSegments.filter { it.text.isNotEmpty() }.joinToString("\n\n") { seg ->
            val cfg = cfgs.find { c -> c.id == seg.speakerId }
            val effId = cfg?.mergedInto ?: seg.speakerId
            val effCfg = cfgs.find { c -> c.id == effId }
            val name = effCfg?.name?.takeIf { it.isNotBlank() } ?: "Спикер ${effId + 1}"
            "$name: ${seg.text}"
        }
    }

    // Version history dialog
    var showVersionHistory by remember { mutableStateOf(false) }
    val versionHistory = listOf(
        "v6.8 · Hittite · Whisper Small INT8 · Astaro",
        "v6.7 · Assyria · Whisper Small INT8",
        "v6.6 · Babylon · Whisper Small INT8",
        "v6.5 · Sumer · Whisper Small INT8",
        "v6.4 · Bactria · Whisper Small INT8",
        "v6.3 · Carthage · Whisper Small INT8",
        "v6.2 · Akkad · Whisper Small INT8",
        "v6.1 · Elam · Whisper Small INT8",
        "v6.0 · Songhai · Whisper Small INT8",
    )

    // Timer for real-time elapsed time display
    var processingStartMs by remember { mutableStateOf(0L) }
    var baseProgress by remember { mutableStateOf("") }

    // Auto-scroll logs
    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty()) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    // Real-time timer: ticks every second during processing
    LaunchedEffect(isProcessing, processingStartMs) {
        if (isProcessing && processingStartMs > 0L) {
            while (isProcessing) {
                val elapsed = System.currentTimeMillis() - processingStartMs
                val min = elapsed / 60000; val sec = (elapsed % 60000) / 1000
                val elapsedStr = "$min:${"%02d".format(sec)}"
                progress = if (baseProgress.isNotEmpty()) {
                    "$baseProgress ⏱$elapsedStr"
                } else {
                    "⏱ $elapsedStr"
                }
                delay(1000)
            }
        }
    }

    // Reactively update main screen result when speaker configs change
    LaunchedEffect(dialogueText) {
        if (transcribedSegments.isNotEmpty() && dialogueText.isNotEmpty()) {
            result = dialogueText
        }
    }

    // Check if models exist, create log file
    LaunchedEffect(Unit) {
        val logger = FileLogger(context)
        fileLogger = logger
        pipeline?.setFileLogger(logger)

        previousLogs = logger.getPreviousLogs()

        logLines.add("[СИСТЕМА] Лог-файл: ${logger.getPath()}")
        logger.log("=== ПРИЛОЖЕНИЕ ЗАПУЩЕНО ===")
        logger.log("Модели загружены: ${ModelDownloader.areAllModelsDownloaded(context)}")

        if (ModelDownloader.areAllModelsDownloaded(context)) {
            logger.log("Модели найдены, инициализирую Pipeline...")
            val success = withContext(Dispatchers.IO) {
                pipeline?.loadModels() ?: false
            }
            if (success) {
                logger.log("Pipeline инициализирован успешно")
                                logLines.add("[СИСТЕМА] ✅ Whisper Small INT8 + ML-диаризация (бета) готовы")
            } else {
                logger.logError("Pipeline НЕ инициализирован")
                logLines.add("[СИСТЕМА] ❌ Ошибка инициализации Pipeline")
                initError = "Ошибка загрузки моделей. Скачайте их заново."
            }
            isInitialized = success
        } else {
            logger.log("Модели не найдены — требуется загрузка")
            logLines.add("[СИСТЕМА] Модели не найдены. Нажмите «Загрузить модели».")
        }

        logFileCreated = true
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            onClearAudio()
            val docFile = DocumentFile.fromSingleUri(context, it)
            selectedFilename = docFile?.name ?: "audio file"
            selectedUri = it
            result = ""
            error = ""
            logLines.clear()
            isAsrDone = false
            rawAsrResult = ""
            diarizedResult = ""
            showDiarizedView = false
            speakerTimelineResult = ""
            showSpeakerTimelineModal = false
            transcribedSegments = emptyList()
            showTranscribedModal = false
            transcribingSegments = false
            logLines.add("[СИСТЕМА] Выбран файл: ${docFile?.name}")
            fileLogger?.log("Выбран файл: ${docFile?.name}")
        }
    }

    // ===== LOGS DIALOG =====
    if (showLogsDialog) {
        AlertDialog(
            onDismissRequest = {
                showLogsDialog = false
                selectedLog = null
            },
            title = {
                Text(
                    text = if (selectedLog != null) "📄 ${selectedLog!!.first}" else "📋 Сохранённые логи",
                    color = MaterialTheme.colorScheme.primary
                )
            },
            text = {
                if (selectedLog != null) {
                    Column {
                        Text(
                            text = "Нажмите, чтобы скопировать",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        val isTruncated = selectedLog!!.second.length > 3000
                        val displayText = if (isTruncated)
                            "... (показаны последние 3000 символов)\n\n${
                                selectedLog!!.second.takeLast(3000)
                            }"
                        else
                            selectedLog!!.second

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(400.dp)
                                .verticalScroll(rememberScrollState())
                                .clickable {
                                    clipboardManager.setText(AnnotatedString(displayText))
                                    Toast
                                        .makeText(context, "Скопировано то, что на экране!", Toast.LENGTH_SHORT)
                                        .show()
                                },
                            color = Color(0xFF0D1117),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = displayText,
                                modifier = Modifier.padding(8.dp),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 14.sp,
                                color = Color(0xFF58A6FF),
                                softWrap = true
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "👆 Тап — скопировать экран",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                            if (isTruncated) {
                                TextButton(
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(selectedLog!!.second))
                                        Toast.makeText(context, "Весь лог скопирован!", Toast.LENGTH_SHORT).show()
                                    },
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                                ) {
                                    Text(
                                        text = "📋 Копировать всё",
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    }
                } else if (previousLogs.isEmpty()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Нет сохранённых логов от предыдущих запусков.",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Логи появляются после обработки аудио.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                } else {
                    Column {
                        Text(
                            text = "Выберите лог, чтобы посмотреть его содержимое:",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        previousLogs.forEach { log ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .clickable {
                                        selectedLog = Pair(
                                            "${log.date} — ${log.name}",
                                            log.content
                                        )
                                    },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (log.name.contains("CRASH"))
                                        MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                                    else
                                        MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (log.name.contains("CRASH")) "💥" else "📄",
                                        fontSize = 16.sp
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = log.date,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = log.name,
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (selectedLog != null) {
                    TextButton(onClick = { selectedLog = null }) {
                        Text("← Назад")
                    }
                } else {
                    TextButton(onClick = {
                        showLogsDialog = false
                        selectedLog = null
                    }) {
                        Text("Закрыть")
                    }
                }
            }
        )
    }

    // ===== VERSION HISTORY DIALOG =====
    if (showVersionHistory) {
        AlertDialog(
            onDismissRequest = { showVersionHistory = false },
            title = {
                Text(
                    "🏛 История версий",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    versionHistory.forEachIndexed { i, ver ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            shape = MaterialTheme.shapes.small,
                            color = if (i == 0)
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            else
                                Color.Transparent
                        ) {
                            Text(
                                text = if (i == 0) "⭐ $ver (текущая)" else ver,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showVersionHistory = false }) {
                    Text("Закрыть")
                }
            },
            containerColor = MaterialTheme.colorScheme.background
        )
    }

    // ===== SETTINGS DIALOG =====
    if (showSettingsDialog) {
        val currentThreshold = pipeline?.clusterThreshold ?: 0.45f
        LaunchedEffect(showSettingsDialog) {
            settingsThresholdText = currentThreshold.toString()
            settingsThresholdError = false
        }
        AlertDialog(
            onDismissRequest = {
                showSettingsDialog = false
                settingsThresholdText = ""
                settingsThresholdError = false
            },
            title = {
                Text(
                    text = "⚙ Настройки",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Compact mode switch
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Компактный режим",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Скрывает кнопку «Распознать речь (old)», имя лог-файла и поле логов на главном экране",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = isCompactMode,
                            onCheckedChange = { enabled ->
                                isCompactMode = enabled
                                prefs.edit().putBoolean("compact_mode", enabled).apply()
                            }
                        )
                    }

                    Divider(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(bottom = 8.dp))

                    Text(
                        text = "Порог кластеризации (clusterThreshold)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = settingsThresholdText,
                        onValueChange = { newVal ->
                            settingsThresholdText = newVal
                            settingsThresholdError = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = settingsThresholdError,
                        supportingText = if (settingsThresholdError) {
                            { Text("Введите число от 0.01 до 0.99", color = MaterialTheme.colorScheme.error) }
                        } else null
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Рекомендуется 0.45. Меньше число = больше спикеров",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Текущее значение: ${currentThreshold}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val threshold = settingsThresholdText.toFloatOrNull()
                    if (threshold != null && threshold > 0f && threshold < 1f) {
                        pipeline?.setClusterThreshold(threshold)
                        showSettingsDialog = false
                        settingsThresholdText = ""
                        settingsThresholdError = false
                        logLines.add("[НАСТРОЙКИ] Порог кластеризации = $threshold")
                    } else {
                        settingsThresholdError = true
                    }
                }) {
                    Text("💾 Сохранить", fontSize = 14.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSettingsDialog = false
                    settingsThresholdText = ""
                    settingsThresholdError = false
                }) {
                    Text("Отмена", fontSize = 14.sp)
                }
            },
            containerColor = MaterialTheme.colorScheme.background
        )
    }

    // ===== MAIN UI =====
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ===== HEADER =====
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Column(
                    modifier = Modifier.weight(1f).clickable { showVersionHistory = true }
                ) {
                    Text(
                        text = "🎙 Astaro",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "v6.8 · Hittite · Whisper Small INT8",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                // Settings button
                Button(
                    onClick = { showSettingsDialog = true },
                    modifier = Modifier.size(40.dp),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Настройки",
                        tint = Color.White
                    )
                }
                // Small log button top-right
                Button(
                    onClick = {
                        fileLogger?.let { previousLogs = it.getPreviousLogs() }
                        showLogsDialog = true
                        selectedLog = null
                    },
                    modifier = Modifier.size(40.dp),
                    contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(text = "📋", fontSize = 14.sp)
                }
            }

            if (logFileCreated && !isCompactMode) {
                fileLogger?.let { logger ->
                    Text(
                        text = "📄 ${logger.getPath().substringAfterLast("/")}",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }

            Divider(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Error display
            if (error.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .clickable {
                            clipboardManager.setText(AnnotatedString(error))
                            Toast.makeText(context, "Текст ошибки скопирован!", Toast.LENGTH_SHORT).show()
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
                    )
                ) {
                    Text(
                        text = "[нажмите, чтобы скопировать]\n$error",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // ===== DOWNLOAD MODELS =====
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!isInitialized && !isDownloading) {
                    Button(
                        onClick = {
                            context.startService(Intent(context, TranscriberService::class.java))
                            isDownloading = true
                            scope.launch {
                                try {
                                    fileLogger?.log("=== ЗАГРУЗКА МОДЕЛЕЙ ===")
                                    ModelDownloader.downloadModels(context) { msg, _ ->
                                        downloadProgress = msg
                                        fileLogger?.log("[DL] $msg")
                                    }
                                    fileLogger?.log("Загрузка завершена, инициализирую...")

                                    val success = withContext(Dispatchers.IO) {
                                        pipeline?.loadModels() ?: false
                                    }
                                    isInitialized = success
                                    isDownloading = false
                                    downloadProgress = ""

                                    if (success) {
                                        logLines.add("[СИСТЕМА] ✅ Whisper Small INT8 + ML-диаризация (бета) готовы")
                                        fileLogger?.log("✅ Whisper Small INT8 + ML-диаризация (бета) готовы")

                                        Toast.makeText(context, "Модели загружены!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        error = "Не удалось загрузить модели"
                                        fileLogger?.logError("Не удалось загрузить модели")
                                    }
                                } catch (e: Exception) {
                                    error = "Ошибка загрузки: ${e.message}"
                                    fileLogger?.logError("Ошибка загрузки", e)
                                    isDownloading = false
                                    downloadProgress = ""
                                } finally {
                                    context.stopService(Intent(context, TranscriberService::class.java))
                                }
                            }
                        },
                        modifier = Modifier.weight(2f)
                    ) {
                        Text("📥 Загрузить модели (~358 МБ)", fontSize = 13.sp)
                    }
                }
            }

            if (isDownloading && downloadProgress.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = downloadProgress,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }

            if (isInitialized) {
                Text(
                    text = "✅ Whisper Small INT8 + ML-диаризация (бета)",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                )

                // File select + Play in one row
                var isPlaying by remember { mutableStateOf(false) }
                val mediaPlayer = remember { mutableStateOf<android.media.MediaPlayer?>(null) }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            filePickerLauncher.launch(arrayOf("audio/*"))
                        },
                        enabled = !isProcessing && !diarizationInProgress,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("📂 Выбрать аудио", fontSize = 13.sp)
                    }

                    if (selectedFilename.isNotEmpty()) {
                        Button(
                            onClick = {
                                if (isPlaying) {
                                    mediaPlayer.value?.stop()
                                    mediaPlayer.value?.release()
                                    mediaPlayer.value = null
                                    isPlaying = false
                                } else {
                                    val uri = selectedUri ?: return@Button
                                    try {
                                        val mp = android.media.MediaPlayer()
                                        mp.setDataSource(context, uri)
                                        mp.setOnCompletionListener {
                                            mp.release()
                                            mediaPlayer.value = null
                                            isPlaying = false
                                        }
                                        mp.setOnErrorListener { _, _, _ ->
                                            mp.release()
                                            mediaPlayer.value = null
                                            isPlaying = false
                                            true
                                        }
                                        mp.prepare()
                                        mp.start()
                                        mediaPlayer.value = mp
                                        isPlaying = true
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Не удалось воспроизвести", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            enabled = !isProcessing && !diarizationInProgress,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isPlaying) Color(0xFFEF5350) else MaterialTheme.colorScheme.secondary
                            )
                        ) {
                            Text(if (isPlaying) "⏹" else "▶ Проверить", fontSize = 13.sp)
                        }
                    }
                }

                if (selectedFilename.isNotEmpty()) {
                    Text(
                        text = "Файл: $selectedFilename",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.padding(vertical = 8.dp)
                    )

                    // Alpha button — сразу при выборе файла (временная для тестов)
                    if (!isProcessing && !diarizationInProgress) {
                        Button(
                            onClick = {
                                diarizationInProgress = true
                                onRunSpeakerTimeline(
                                    selectedUri!!,
                                    selectedFilename,
                                    { msg -> baseProgress = msg },
                                    { msg ->
                                        logLines.add(msg)
                                        fileLogger?.log(msg)
                                    },
                                    { timelineResult ->
                                        speakerTimelineResult = timelineResult
                                        result = timelineResult
                                        showSpeakerTimelineModal = true
                                        diarizationInProgress = false
                                        logLines.add("[ДИАР.] ✅ ML-таймлайн готов")
                                        fileLogger?.log("ML-таймлайн готов")
                                    },
                                    { timelineObj ->
                                        timelineSegments = timelineObj.segments
                                        timelineSpeakerCount = timelineObj.speakerCount
                                        timelineAudioUri = selectedUri
                                    },
                                    { err ->
                                        error = err
                                        diarizationInProgress = false
                                        logLines.add("[ДИАР.] ❌ $err")
                                        fileLogger?.logError(err)
                                    }
                                )
                            },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF2EA043)
                            )
                        ) {
                            Text("🔬 Разделить на спикеров (New)", fontSize = 12.sp, color = Color.White)
                        }
                    }

                    if (!isCompactMode) {
                    Button(
                        onClick = {
                            val uri = selectedUri ?: return@Button
                            isProcessing = true
                            processingStartMs = System.currentTimeMillis()
                            isAsrDone = false
                            rawAsrResult = ""
                            diarizedResult = ""
                            showDiarizedView = false
                            baseProgress = ""
                            progress = "Подготовка..."
                            result = ""
                            error = ""
                            logLines.clear()

                            fileLogger?.log("=== ЗАПУСК ОБРАБОТКИ ===")
                            fileLogger?.log("Файл: $selectedFilename, URI: $uri")

                            onStartProcessing(
                                uri,
                                selectedFilename,
                                fileLogger,
                                { msg -> baseProgress = msg },
                                { msg ->
                                    logLines.add(msg)
                                    fileLogger?.log(msg)
                                },
                                { text ->
                                    result = text
                                    rawAsrResult = text
                                    diarizedResult = ""
                                    showDiarizedView = false
                                    isAsrDone = true
                                    logLines.add("[PIPELINE] ✅ ASR завершён")
                                    fileLogger?.log("ASR результат: \"${text.take(100)}...\"")
                                    if (text.isEmpty() || text == "Речь не распознана") {
                                        result = "Речь не распознана"
                                        logLines.add("[РЕЗУЛЬТАТ] Речь не распознана")
                                        fileLogger?.log("Речь не распознана")
                                    }
                                    isProcessing = false
                                    fileLogger?.log("=== ОБРАБОТКА ЗАВЕРШЕНА ===")
                                },
                                { err ->
                                    error = err
                                    logLines.add("[ОШИБКА] $err")
                                    fileLogger?.logError(err)
                                    isProcessing = false
                                    fileLogger?.log("=== ОБРАБОТКА ЗАВЕРШЕНА ===")
                                }
                            )
                        },
                        enabled = !isProcessing && !diarizationInProgress,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("▶ Распознать речь (old)")
                    }
                    }
                }

                if (isProcessing || diarizationInProgress || transcribingSegments) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = progress,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // Log area
                if (logLines.isNotEmpty() && !isCompactMode) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "📋 Текущая сессия (нажмите, чтобы скопировать)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(133.dp)
                            .verticalScroll(scrollState)
                            .clickable {
                                val fullLog = logLines.joinToString("\n")
                                clipboardManager.setText(AnnotatedString(fullLog))
                                Toast.makeText(context, "Логи скопированы!", Toast.LENGTH_SHORT).show()
                            },
                        color = Color(0xFF0D1117),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = logLines.joinToString("\n"),
                            modifier = Modifier.padding(8.dp),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 16.sp,
                            color = Color(0xFF58A6FF),
                            softWrap = true
                        )
                    }
                }

                if (result.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(result))
                                Toast.makeText(context, "Скопировано!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("📋 Копировать результат")
                        }

                        // Diarization buttons — only if ASR done
                        if (isAsrDone && !isProcessing && !diarizationInProgress) {
                            Button(
                                onClick = {
                                    diarizationInProgress = true
                                    scope.launch {
                                        logLines.add("[ДИАР.] Запускаю диаризацию...")
                                        fileLogger?.log("=== ДИАРИЗАЦИЯ ===")
                                        onStartDiarization(
                                            result,
                                            { msg -> baseProgress = msg },
                                            { msg ->
                                                logLines.add(msg)
                                                fileLogger?.log(msg)
                                            },
                                            { diarResult ->
                                                diarizedResult = diarResult
                                                result = diarResult
                                                showDiarizedView = true
                                                logLines.add("[ДИАР.] ✅ Диаризация завершена")
                                                fileLogger?.log("Диаризация завершена")
                                                diarizationInProgress = false
                                            },
                                            { err ->
                                                error = err
                                                logLines.add("[ДИАР.] ❌ $err")
                                                fileLogger?.logError(err)
                                                diarizationInProgress = false
                                            }
                                        )
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.tertiary
                                )
                            ) {
                                Text("🗣 Разделить по спикерам")
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "👇 Нажмите на текст для полноэкранного просмотра",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                    Spacer(Modifier.height(4.dp))

                    // Clickable result preview — tap to open full-screen
                    var showFullScreen by remember { mutableStateOf(false) }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .clickable { 
                                if (transcribedSegments.isNotEmpty()) {
                                    showTranscribedModal = true
                                } else {
                                    showFullScreen = true 
                                }
                            },
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium,
                        tonalElevation = 2.dp
                    ) {
                        Text(
                            text = result,
                            modifier = Modifier
                                .padding(12.dp)
                                .verticalScroll(rememberScrollState()),
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 22.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // ===== FULL-SCREEN RESULT DIALOG =====
                    if (showFullScreen) {
                        AlertDialog(
                            onDismissRequest = { showFullScreen = false },
                            title = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "📄 Результат",
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp
                                    )
                                    IconButton(onClick = { showFullScreen = false }) {
                                        Text("✕", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            },
                            text = {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    // Copy button at top
                                    Button(
                                        onClick = {
                                            val copyText = if (showDiarizedView && diarizedResult.isNotEmpty()) diarizedResult else rawAsrResult
                                            clipboardManager.setText(AnnotatedString(copyText))
                                            Toast.makeText(context, "Весь текст скопирован!", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("📋 Копировать весь текст")
                                    }

                                    Spacer(Modifier.height(8.dp))

                                    // ===== TOGGLE: Transcription vs Speakers =====
                                    if (rawAsrResult.isNotEmpty()) {
                                        Surface(
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = MaterialTheme.shapes.small,
                                            color = MaterialTheme.colorScheme.surfaceVariant
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.Center
                                            ) {
                                                // Transcribe mode
                                                TextButton(
                                                    onClick = { showDiarizedView = false },
                                                    modifier = Modifier.weight(1f),
                                                    colors = ButtonDefaults.textButtonColors(
                                                        containerColor = if (!showDiarizedView)
                                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                                        else Color.Transparent
                                                    )
                                                ) {
                                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                        Text("🎤", fontSize = 16.sp)
                                                        Text(
                                                            "Транскрибация",
                                                            fontWeight = if (!showDiarizedView) FontWeight.Bold else FontWeight.Normal,
                                                            fontSize = 11.sp
                                                        )
                                                    }
                                                }
                                                // Speakers mode
                                                TextButton(
                                                    onClick = { showDiarizedView = true },
                                                    modifier = Modifier.weight(1f),
                                                    enabled = diarizedResult.isNotEmpty(),
                                                    colors = ButtonDefaults.textButtonColors(
                                                        containerColor = if (showDiarizedView && diarizedResult.isNotEmpty())
                                                            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
                                                        else Color.Transparent
                                                    )
                                                ) {
                                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                        Text("🗣", fontSize = 16.sp)
                                                        Text(
                                                            "Спикеры",
                                                            fontWeight = if (showDiarizedView && diarizedResult.isNotEmpty()) FontWeight.Bold else FontWeight.Normal,
                                                            fontSize = 11.sp
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        Spacer(Modifier.height(6.dp))
                                    }

                                    // Full-screen scrollable text — shows toggled content
                                    val displayResult = if (showDiarizedView && diarizedResult.isNotEmpty()) diarizedResult else rawAsrResult

                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(420.dp)
                                            .verticalScroll(rememberScrollState())
                                            .clickable {
                                                clipboardManager.setText(AnnotatedString(displayResult))
                                                Toast.makeText(context, "Скопировано!", Toast.LENGTH_SHORT).show()
                                            },
                                        color = Color(0xFF0D1117),
                                        shape = MaterialTheme.shapes.small
                                    ) {
                                        Text(
                                            text = displayResult,
                                            modifier = Modifier.padding(12.dp),
                                            fontSize = 14.sp,
                                            fontFamily = FontFamily.Monospace,
                                            lineHeight = 22.sp,
                                            color = Color(0xFFE0E0E0),
                                            softWrap = true
                                        )
                                    }

                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = "👆 Нажмите на текст, чтобы скопировать",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                    )
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { showFullScreen = false }) {
                                    Text("✕ Закрыть", fontSize = 14.sp)
                                }
                            },
                            containerColor = MaterialTheme.colorScheme.background
                        )
                    }

                    // ===== SPEAKER TIMELINE MODAL with player =====
                    if (showSpeakerTimelineModal && speakerTimelineResult.isNotEmpty()) {
                        AlertDialog(
                            onDismissRequest = { showSpeakerTimelineModal = false },
                            title = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "🎙 Таймлайн спикеров",
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp
                                    )
                                    IconButton(onClick = { showSpeakerTimelineModal = false }) {
                                        Text("✕", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            },
                            text = {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    // === Player with seekbar ===
                                    val speakerColors = remember {
                                        listOf(
                                            Color(0xFF58A6FF), // синий
                                            Color(0xFFDA3633), // красный
                                            Color(0xFF3FB950), // зелёный
                                            Color(0xFFD29922), // жёлтый
                                            Color(0xFFBC8CFF), // фиолетовый
                                            Color(0xFFF0883E), // оранжевый
                                            Color(0xFF79C0FF), // голубой
                                            Color(0xFFF85149), // розовый
                                        )
                                    }
                                    var isPlayingTimeline by remember { mutableStateOf(false) }
                                    var currentTimeSec by remember { mutableFloatStateOf(0f) }
                                    val timelinePlayer = remember { mutableStateOf<android.media.MediaPlayer?>(null) }
                                    val totalSec = timelineSegments.maxOfOrNull { it.endSec } ?: 0f
                                    
                                    // Cleanup player when dialog closes
                                    DisposableEffect(showSpeakerTimelineModal) {
                                        onDispose {
                                            timelinePlayer.value?.stop()
                                            timelinePlayer.value?.release()
                                            timelinePlayer.value = null
                                        }
                                    }
                                    
                                    // LaunchEffect to tick during playback
                                    LaunchedEffect(isPlayingTimeline) {
                                        if (isPlayingTimeline && timelinePlayer.value != null) {
                                            while (isPlayingTimeline) {
                                                val mp = timelinePlayer.value
                                                if (mp != null && mp.isPlaying) {
                                                    currentTimeSec = mp.currentPosition / 1000f
                                                } else if (mp != null && !mp.isPlaying && currentTimeSec > 0f) {
                                                    isPlayingTimeline = false
                                                }
                                                kotlinx.coroutines.delay(100)
                                            }
                                        }
                                    }
                                    
                                    // Player controls row
                                    Surface(
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                        color = Color(0xFF161B22),
                                        shape = MaterialTheme.shapes.small
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            // Play/Pause + Seekbar + Time
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                IconButton(
                                                    onClick = {
                                                        if (isPlayingTimeline) {
                                                            timelinePlayer.value?.pause()
                                                            isPlayingTimeline = false
                                                        } else {
                                                            val uri = timelineAudioUri ?: return@IconButton
                                                            try {
                                                                if (timelinePlayer.value == null) {
                                                                    val mp = android.media.MediaPlayer()
                                                                    mp.setDataSource(context, uri)
                                                                    mp.setOnCompletionListener {
                                                                        mp.release()
                                                                        timelinePlayer.value = null
                                                                        isPlayingTimeline = false
                                                                        currentTimeSec = 0f
                                                                    }
                                                                    mp.setOnErrorListener { _, _, _ ->
                                                                        mp.release()
                                                                        timelinePlayer.value = null
                                                                        isPlayingTimeline = false
                                                                        true
                                                                    }
                                                                    mp.prepare()
                                                                    timelinePlayer.value = mp
                                                                }
                                                                val mp = timelinePlayer.value
                                                                if (mp != null && !mp.isPlaying) {
                                                                    if (currentTimeSec >= totalSec) {
                                                                        mp.seekTo(0)
                                                                        currentTimeSec = 0f
                                                                    }
                                                                    mp.start()
                                                                    isPlayingTimeline = true
                                                                }
                                                            } catch (e: Exception) {
                                                                Toast.makeText(context, "Ошибка плеера", Toast.LENGTH_SHORT).show()
                                                            }
                                                        }
                                                    },
                                                    modifier = Modifier.size(40.dp)
                                                ) {
                                                    Text(
                                                        if (isPlayingTimeline) "⏸" else "▶",
                                                        fontSize = 20.sp
                                                    )
                                                }
                                                
                                                Slider(
                                                    value = currentTimeSec,
                                                    onValueChange = { 
                                                        currentTimeSec = it
                                                        timelinePlayer.value?.seekTo((it * 1000).toInt())
                                                    },
                                                    valueRange = 0f..maxOf(totalSec, 1f),
                                                    modifier = Modifier.weight(1f).height(32.dp),
                                                    colors = SliderDefaults.colors(
                                                        thumbColor = MaterialTheme.colorScheme.primary,
                                                        activeTrackColor = MaterialTheme.colorScheme.primary,
                                                        inactiveTrackColor = Color(0xFF30363D)
                                                    )
                                                )
                                                
                                                Text(
                                                    text = "${formatTime(currentTimeSec)} / ${formatTime(totalSec)}",
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                                    modifier = Modifier.padding(start = 4.dp).width(80.dp)
                                                )
                                            }
                                            
                                            // Current speaker display
                                            if (timelineSegments.isNotEmpty()) {
                                                Spacer(Modifier.height(8.dp))
                                                val currentSpeaker = timelineSegments.find { seg ->
                                                    currentTimeSec >= seg.startSec && currentTimeSec < seg.endSec
                                                }
                                                if (currentSpeaker != null) {
                                                    val col = speakerColors[currentSpeaker.speakerId % speakerColors.size]
                                                    Text(
                                                        text = "▶ Сейчас говорит Спикер ${currentSpeaker.speakerId + 1}   [${
                                                            formatTime(currentSpeaker.startSec)} — ${formatTime(currentSpeaker.endSec)}]",
                                                        fontSize = 14.sp,
                                                        color = col,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                } else if (currentTimeSec > 0f) {
                                                    Text(
                                                        text = "⏸ Пауза",
                                                        fontSize = 14.sp,
                                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    
Spacer(Modifier.height(8.dp))

                                    // ===== TRANSCRIBE SEGMENTS BUTTON =====
                                    Button(
                                        onClick = {
                                            showSpeakerTimelineModal = false
                                            transcribingSegments = true
                                            onTranscribeDiarizationSegments(
                                                timelineSegments,
                                                { msg -> baseProgress = msg },
                                                { msg ->
                                                    logLines.add(msg)
                                                    fileLogger?.log(msg)
                                                },
                                                { segments ->
                                                    transcribedSegments = segments
                                                    transcribingSegments = false
                                                    showTranscribedModal = true
                                                    // Build dialogue text for main screen result
                                                    result = segments.filter { it.text.isNotEmpty() }.joinToString("\n\n") { seg ->
                                                        val name = "Спикер ${seg.speakerId + 1}"
                                                        "$name: ${seg.text}"
                                                    }
                                                    logLines.add("[ТРАНСКР.] ✅ Транскрибация сегментов завершена (${segments.size} сегментов)")
                                                    fileLogger?.log("Транскрибация сегментов завершена")
                                                },
                                                { err ->
                                                    error = err
                                                    transcribingSegments = false
                                                    logLines.add("[ТРАНСКР.] ❌ $err")
                                                    fileLogger?.logError(err)
                                                }
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth().height(48.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFF2EA043)
                                        )
                                    ) {
                                        Text("Далее (тр. по репликам)", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                    Spacer(Modifier.height(8.dp))

                                    Button(
                                        onClick = {
                                            clipboardManager.setText(AnnotatedString(speakerTimelineResult))
                                            Toast.makeText(context, "Таймлайн скопирован!", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("📋 Копировать таймлайн")
                                    }
                                    Spacer(Modifier.height(8.dp))

                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(300.dp)
                                            .verticalScroll(rememberScrollState())
                                            .clickable {
                                                clipboardManager.setText(AnnotatedString(speakerTimelineResult))
                                                Toast.makeText(context, "Скопировано!", Toast.LENGTH_SHORT).show()
                                            },
                                        color = Color(0xFF0D1117),
                                        shape = MaterialTheme.shapes.small
                                    ) {
                                        Text(
                                            text = speakerTimelineResult,
                                            modifier = Modifier.padding(12.dp),
                                            fontSize = 13.sp,
                                            fontFamily = FontFamily.Monospace,
                                            lineHeight = 20.sp,
                                            color = Color(0xFF58A6FF),
                                            softWrap = true
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = "👆 Нажмите на текст, чтобы скопировать",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                    )
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { showSpeakerTimelineModal = false }) {
                                    Text("✕ Закрыть", fontSize = 14.sp)
                                }
                            },
                            containerColor = MaterialTheme.colorScheme.background
                        )
                    }
                    // ===== TRANSCRIBED SEGMENTS MODAL =====
                    if (showTranscribedModal && transcribedSegments.isNotEmpty()) {
                        val speakerColors = remember {
                            listOf(
                                Color(0xFF58A6FF), Color(0xFFDA3633), Color(0xFF3FB950),
                                Color(0xFFD29922), Color(0xFFBC8CFF), Color(0xFFF0883E),
                                Color(0xFF79C0FF), Color(0xFFF85149),
                            )
                        }
                        var currentPlayingIdx by remember { mutableIntStateOf(-1) }
                        val segPlayers = remember { mutableStateMapOf<Int, android.media.MediaPlayer>() }

                        DisposableEffect(showTranscribedModal) {
                            onDispose {
                                segPlayers.values.forEach { it.release() }
                                segPlayers.clear()
                            }
                        }

                        AlertDialog(
                            onDismissRequest = {
                                segPlayers.values.forEach { it.stop(); it.release() }
                                segPlayers.clear()
                                showTranscribedModal = false
                            },
                            title = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "📝 Реплики по спикерам",
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp
                                    )
                                    IconButton(onClick = {
                                        segPlayers.values.forEach { it.stop(); it.release() }
                                        segPlayers.clear()
                                        showTranscribedModal = false
                                    }) {
                                        Text("✕", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            },
                            text = {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    // Tab toggle
                                    var showAudioTab by remember { mutableStateOf(true) }
                                    val speakerColorsLocal = speakerColors

                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = MaterialTheme.shapes.small,
                                        color = MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.Center
                                        ) {
                                            TextButton(
                                                onClick = { showAudioTab = true },
                                                modifier = Modifier.weight(1f),
                                                colors = ButtonDefaults.textButtonColors(
                                                    containerColor = if (showAudioTab)
                                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                                    else Color.Transparent
                                                )
                                            ) {
                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                    Text("🎵", fontSize = 16.sp)
                                                    Text(
                                                        "Аудио реплики",
                                                        fontWeight = if (showAudioTab) FontWeight.Bold else FontWeight.Normal,
                                                        fontSize = 11.sp
                                                    )
                                                }
                                            }
                                            TextButton(
                                                onClick = { showAudioTab = false },
                                                modifier = Modifier.weight(1f),
                                                colors = ButtonDefaults.textButtonColors(
                                                    containerColor = if (!showAudioTab)
                                                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
                                                    else Color.Transparent
                                                )
                                            ) {
                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                    Text("💬", fontSize = 16.sp)
                                                    Text(
                                                        "Весь диалог",
                                                        fontWeight = if (!showAudioTab) FontWeight.Bold else FontWeight.Normal,
                                                        fontSize = 11.sp
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(6.dp))

                                    // ===== SPEAKER CONFIG CHIP (state in MainScreen level) =====
                                    Row(modifier = Modifier.fillMaxWidth()) {
                                        AssistChip(
                                            onClick = {
                                                editConfigs = speakerConfigs.map { it.copy() }
                                                showSpeakerConfigDialog = true
                                            },
                                            label = { Text("Спикеры", fontSize = 12.sp) },
                                            leadingIcon = {
                                                Text("👥", fontSize = 14.sp)
                                            },
                                            colors = AssistChipDefaults.assistChipColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                                            )
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))

                                    if (showAudioTab) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(max = 420.dp)
                                                .verticalScroll(rememberScrollState())
                                        ) {
                                            transcribedSegments.forEachIndexed { i, seg ->
                                                val cfg = speakerConfigs.find { it.id == seg.speakerId }
                                                val effectiveSpkId = cfg?.mergedInto ?: seg.speakerId
                                                val effectiveCfg = speakerConfigs.find { it.id == effectiveSpkId }
                                                val displayName = effectiveCfg?.name?.takeIf { it.isNotBlank() } ?: "Спикер ${effectiveSpkId + 1}"
                                                val labelColor = speakerColorsLocal[effectiveSpkId % speakerColorsLocal.size]
                                                val isThisPlaying = currentPlayingIdx == i

                                                Card(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(vertical = 4.dp),
                                                    colors = CardDefaults.cardColors(
                                                        containerColor = Color(0xFF161B22)
                                                    )
                                                ) {
                                                    Column(modifier = Modifier.padding(10.dp)) {
                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.SpaceBetween
                                                        ) {
                                                            Column(modifier = Modifier.weight(1f)) {
                                                                Text(
                                                                    text = displayName,
                                                                    color = labelColor,
                                                                    fontWeight = FontWeight.Bold,
                                                                    fontSize = 14.sp,
                                                                    modifier = Modifier.clickable {
                                                                        miniConfigSpeakerId = seg.speakerId
                                                                        editConfigs = speakerConfigs.map { SpeakerConfig(it.id, it.name, it.mergedInto) }
                                                                    }
                                                                )
                                                                Text(
                                                                    text = "${formatTime(seg.startSec)} — ${formatTime(seg.endSec)}",
                                                                    fontSize = 11.sp,
                                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                                                )
                                                            }
                                                            IconButton(
                                                                onClick = {
                                                                    if (isThisPlaying) {
                                                                        segPlayers[i]?.pause()
                                                                        currentPlayingIdx = -1
                                                                    } else {
                                                                        segPlayers.values.forEach { it.stop(); it.release() }
                                                                        segPlayers.clear()
                                                                        if (seg.audioFile.isNotEmpty() && java.io.File(seg.audioFile).exists()) {
                                                                            try {
                                                                                val mp = android.media.MediaPlayer()
                                                                                mp.setDataSource(seg.audioFile)
                                                                                mp.setOnCompletionListener {
                                                                                    mp.release()
                                                                                    segPlayers.remove(i)
                                                                                    currentPlayingIdx = -1
                                                                                }
                                                                                mp.setOnErrorListener { _, _, _ ->
                                                                                    mp.release()
                                                                                    segPlayers.remove(i)
                                                                                    currentPlayingIdx = -1
                                                                                    true
                                                                                }
                                                                                mp.prepare()
                                                                                mp.start()
                                                                                segPlayers[i] = mp
                                                                                currentPlayingIdx = i
                                                                            } catch (_: Exception) {}
                                                                        }
                                                                    }
                                                                },
                                                                modifier = Modifier.size(36.dp)
                                                            ) {
                                                                Text(
                                                                    if (isThisPlaying) "⏸" else "▶",
                                                                    fontSize = 18.sp
                                                                )
                                                            }
                                                        }
                                                        if (seg.text.isNotEmpty()) {
                                                            Spacer(Modifier.height(6.dp))
                                                            Surface(
                                                                modifier = Modifier.clickable {
                                                                    clipboardManager.setText(AnnotatedString(seg.text))
                                                                    Toast.makeText(context, "Текст скопирован!", Toast.LENGTH_SHORT).show()
                                                                },
                                                                color = Color(0xFF0D1117),
                                                                shape = MaterialTheme.shapes.small
                                                            ) {
                                                                Text(
                                                                    text = seg.text,
                                                                    modifier = Modifier.padding(8.dp),
                                                                    fontSize = 13.sp,
                                                                    fontFamily = FontFamily.Monospace,
                                                                    lineHeight = 20.sp,
                                                                    color = Color(0xFFE0E0E0),
                                                                    softWrap = true
                                                                )
                                                            }
                                                        } else if (seg.audioFile.isEmpty()) {
                                                            Spacer(Modifier.height(6.dp))
                                                            Text(
                                                                text = "⏭ Сегмент слишком короткий для распознавания",
                                                                fontSize = 11.sp,
                                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        // ===== DIALOGUE TAB WITH PLAYER =====
                                        var isPlayingDialogue by remember { mutableStateOf(false) }
                                        var currentPosSec by remember { mutableFloatStateOf(0f) }
                                        var is2x by remember { mutableStateOf(false) }
                                        val dialoguePlayer = remember { mutableStateOf<android.media.MediaPlayer?>(null) }
                                        val totalAudioSec = timelineSegments.maxOfOrNull { it.endSec } ?: 0f

                                        DisposableEffect(showTranscribedModal) {
                                            onDispose {
                                                dialoguePlayer.value?.stop()
                                                dialoguePlayer.value?.release()
                                                dialoguePlayer.value = null
                                            }
                                        }

                                        LaunchedEffect(isPlayingDialogue) {
                                            if (isPlayingDialogue && dialoguePlayer.value != null) {
                                                while (isPlayingDialogue) {
                                                    val mp = dialoguePlayer.value
                                                    if (mp != null && mp.isPlaying) {
                                                        currentPosSec = mp.currentPosition / 1000f
                                                    } else if (mp != null && !mp.isPlaying && currentPosSec > 0f) {
                                                        isPlayingDialogue = false
                                                    }
                                                    kotlinx.coroutines.delay(100)
                                                }
                                            }
                                        }

                                        Column {
                                            // Player controls
                                            Surface(
                                                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                                                color = Color(0xFF161B22),
                                                shape = MaterialTheme.shapes.small
                                            ) {
                                                Column(modifier = Modifier.padding(8.dp)) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier.fillMaxWidth()
                                                    ) {
                                                        IconButton(
                                                            onClick = {
                                                                if (isPlayingDialogue) {
                                                                    dialoguePlayer.value?.pause()
                                                                    isPlayingDialogue = false
                                                                } else {
                                                                    val uri = timelineAudioUri ?: return@IconButton
                                                                    try {
                                                                        if (dialoguePlayer.value == null) {
                                                                            val mp = android.media.MediaPlayer()
                                                                            mp.setDataSource(context, uri)
                                                                            mp.setOnCompletionListener {
                                                                                mp.release()
                                                                                dialoguePlayer.value = null
                                                                                isPlayingDialogue = false
                                                                                currentPosSec = 0f
                                                                            }
                                                                            mp.setOnErrorListener { _, _, _ ->
                                                                                mp.release()
                                                                                dialoguePlayer.value = null
                                                                                isPlayingDialogue = false
                                                                                true
                                                                            }
                                                                            mp.prepare()
                                                                            if (is2x) {
                                                                                val params = mp.playbackParams
                                                                                params.speed = 2.0f
                                                                                mp.playbackParams = params
                                                                            }
                                                                            dialoguePlayer.value = mp
                                                                        }
                                                                        val mp = dialoguePlayer.value
                                                                        if (mp != null && !mp.isPlaying) {
                                                                            mp.start()
                                                                            isPlayingDialogue = true
                                                                        }
                                                                    } catch (_: Exception) {}
                                                                }
                                                            },
                                                            modifier = Modifier.size(32.dp)
                                                        ) {
                                                            Text(if (isPlayingDialogue) "⏸" else "▶", fontSize = 16.sp)
                                                        }
                                                        Slider(
                                                            value = currentPosSec,
                                                            onValueChange = {
                                                                currentPosSec = it
                                                                dialoguePlayer.value?.seekTo((it * 1000).toInt())
                                                            },
                                                            valueRange = 0f..maxOf(totalAudioSec, 1f),
                                                            modifier = Modifier.weight(1f).height(28.dp),
                                                            colors = SliderDefaults.colors(
                                                                thumbColor = MaterialTheme.colorScheme.primary,
                                                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                                                inactiveTrackColor = Color(0xFF30363D)
                                                            )
                                                        )
                                                        Text(
                                                            text = formatTime(currentPosSec),
                                                            fontSize = 11.sp,
                                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                                            modifier = Modifier.padding(horizontal = 4.dp).width(36.dp)
                                                        )
                                                        // 2x speed toggle
                                                        Surface(
                                                            modifier = Modifier.size(28.dp).clickable {
                                                                is2x = !is2x
                                                                dialoguePlayer.value?.let { mp ->
                                                                    if (mp.isPlaying) {
                                                                        val params = mp.playbackParams
                                                                        params.speed = if (is2x) 2.0f else 1.0f
                                                                        mp.playbackParams = params
                                                                    }
                                                                }
                                                            },
                                                            shape = MaterialTheme.shapes.small,
                                                            color = if (is2x) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else Color(0xFF30363D)
                                                        ) {
                                                            Text(
                                                                text = "x2",
                                                                fontSize = 10.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = if (is2x) MaterialTheme.colorScheme.primary else Color.White,
                                                                modifier = Modifier.padding(3.dp)
                                                            )
                                                        }
                                                    }
                                                    // Current speaker
                                                    val currentSpeaker = timelineSegments.find {
                                                        currentPosSec >= it.startSec && currentPosSec < it.endSec
                                                    }
                                                    if (currentSpeaker != null) {
                                                        val col = speakerColorsLocal[currentSpeaker.speakerId % speakerColorsLocal.size]
                                                        val cfg = speakerConfigs.find { c -> c.id == currentSpeaker.speakerId }
                                                        val effId = cfg?.mergedInto ?: currentSpeaker.speakerId
                                                        val effCfg = speakerConfigs.find { c -> c.id == effId }
                                                        val name = effCfg?.name?.takeIf { it.isNotBlank() } ?: "Спикер ${effId + 1}"
                                                        Text(
                                                            text = "▶ $name   [${formatTime(currentSpeaker.startSec)} — ${formatTime(currentSpeaker.endSec)}]",
                                                            fontSize = 11.sp,
                                                            color = col,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                }
                                            }

                                            // Dialogue text
                                            Surface(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(max = 370.dp)
                                                    .verticalScroll(rememberScrollState())
                                                    .clickable {
                                                        clipboardManager.setText(AnnotatedString(dialogueText))
                                                        Toast.makeText(context, "Диалог скопирован!", Toast.LENGTH_SHORT).show()
                                                    },
                                                color = Color(0xFF0D1117),
                                                shape = MaterialTheme.shapes.small
                                            ) {
                                                Text(
                                                    text = dialogueText,
                                                    modifier = Modifier.padding(12.dp),
                                                    fontSize = 14.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    lineHeight = 22.sp,
                                                    color = Color(0xFFE0E0E0),
                                                    softWrap = true
                                                )
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                text = "👆 Нажмите на текст, чтобы скопировать диалог",
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                            )
                                        }
                                    }

                                    // ===== SPEAKER CONFIG DIALOG =====
                                    if (showSpeakerConfigDialog) {
                                        AlertDialog(
                                            onDismissRequest = {
                                                showSpeakerConfigDialog = false
                                            },
                                            title = {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = "👥 Настройка спикеров",
                                                        color = MaterialTheme.colorScheme.primary,
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 18.sp
                                                    )
                                                    IconButton(onClick = { showSpeakerConfigDialog = false }) {
                                                        Text("✕", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface)
                                                    }
                                                }
                                            },
                                            text = {
                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .heightIn(max = 400.dp)
                                                        .verticalScroll(rememberScrollState())
                                                ) {
                                                    Text(
                                                        text = "Настройки действуют только на текущую сессию.",
                                                        fontSize = 11.sp,
                                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                                        modifier = Modifier.padding(bottom = 8.dp)
                                                    )
                                                    editConfigs.forEachIndexed { idx, cfg ->
                                                        val col = speakerColorsLocal[cfg.id % speakerColorsLocal.size]
                                                        val otherConfigs = editConfigs.filter { it.id != cfg.id }

                                                        Card(
                                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                                            colors = CardDefaults.cardColors(
                                                                containerColor = Color(0xFF161B22)
                                                            )
                                                        ) {
                                                            Column(modifier = Modifier.padding(10.dp)) {
                                                                Row(
                                                                    verticalAlignment = Alignment.CenterVertically
                                                                ) {
                                                                    Text(
                                                                        text = "Спикер ${cfg.id + 1}",
                                                                        color = col,
                                                                        fontWeight = FontWeight.Bold,
                                                                        fontSize = 13.sp,
                                                                        modifier = Modifier.width(72.dp)
                                                                    )
                                                                    OutlinedTextField(
                                                                        value = cfg.name,
                                                                        onValueChange = { newName ->
                                                                            editConfigs = editConfigs.toMutableList().also {
                                                                                it[idx] = cfg.copy(name = newName)
                                                                            }
                                                                        },
                                                                        modifier = Modifier.weight(1f).height(48.dp),
                                                                        singleLine = true,
                                                                        placeholder = { Text("Новое имя...", fontSize = 12.sp) },
                                                                        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                                                                        colors = OutlinedTextFieldDefaults.colors(
                                                                            focusedBorderColor = col,
                                                                            unfocusedBorderColor = Color(0xFF30363D)
                                                                        )
                                                                    )
                                                                }
                                                                if (otherConfigs.isNotEmpty()) {
                                                                    Spacer(Modifier.height(4.dp))
                                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                                        Text(
                                                                            text = "Дубль:",
                                                                            fontSize = 11.sp,
                                                                            color = MaterialTheme.colorScheme.onSurface.copy(0.4f),
                                                                            modifier = Modifier.width(72.dp)
                                                                        )
                                                                        var expanded by remember { mutableStateOf(false) }
                                                                        val selectedMerge = cfg.mergedInto
                                                                        val mergeLabel = if (selectedMerge != null) {
                                                                            val otherCfg = otherConfigs.find { it.id == selectedMerge }
                                                                            otherCfg?.name?.takeIf { it.isNotBlank() } ?: "Спикер ${selectedMerge + 1}"
                                                                        } else "— не выбран —"

                                                                        Box(modifier = Modifier.weight(1f)) {
                                                                            OutlinedTextField(
                                                                                value = mergeLabel,
                                                                                onValueChange = {},
                                                                                modifier = Modifier.fillMaxWidth().height(40.dp),
                                                                                singleLine = true,
                                                                                readOnly = true,
                                                                                enabled = true,
                                                                                textStyle = LocalTextStyle.current.copy(fontSize = 11.sp),
                                                                                trailingIcon = {
                                                                                    IconButton(onClick = { expanded = true },
                                                                                        Modifier.size(20.dp)) {
                                                                                        Text("▾", fontSize = 12.sp)
                                                                                    }
                                                                                },
                                                                                colors = OutlinedTextFieldDefaults.colors(
                                                                                    unfocusedBorderColor = Color(0xFF30363D)
                                                                                )
                                                                            )
                                                                            DropdownMenu(
                                                                                expanded = expanded,
                                                                                onDismissRequest = { expanded = false }
                                                                            ) {
                                                                                DropdownMenuItem(
                                                                                    text = { Text("— не выбран —", fontSize = 11.sp) },
                                                                                    onClick = {
                                                                                        editConfigs = editConfigs.toMutableList().also {
                                                                                            it[idx] = cfg.copy(mergedInto = null)
                                                                                        }
                                                                                        expanded = false
                                                                                    }
                                                                                )
                                                                                otherConfigs.forEach { other ->
                                                                                    val otherName = other.name.takeIf { it.isNotBlank() } ?: "Спикер ${other.id + 1}"
                                                                                    DropdownMenuItem(
                                                                                        text = { Text(otherName, fontSize = 11.sp) },
                                                                                        onClick = {
                                                                                            editConfigs = editConfigs.toMutableList().also {
                                                                                                it[idx] = cfg.copy(mergedInto = other.id)
                                                                                            }
                                                                                            expanded = false
                                                                                        }
                                                                                    )
                                                                                }
                                                                            }
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            },
                                            confirmButton = {
                                                TextButton(onClick = {
                                                    speakerConfigs = editConfigs
                                                    showSpeakerConfigDialog = false
                                                }) {
                                                    Text("💾 Сохранить", fontSize = 14.sp)
                                                }
                                            },
                                            dismissButton = {
                                                TextButton(onClick = {
                                                    showSpeakerConfigDialog = false
                                                }) {
                                                    Text("Отмена", fontSize = 14.sp)
                                                }
                                            },
                                            containerColor = MaterialTheme.colorScheme.background
                                        )
                                    }

                                    // ===== MINI SPEAKER CONFIG DIALOG =====
                                    if (miniConfigSpeakerId >= 0) {
                                        val miniCfg = editConfigs.find { it.id == miniConfigSpeakerId }
                                        val miniIdx = editConfigs.indexOfFirst { it.id == miniConfigSpeakerId }
                                        val miniOtherConfigs = editConfigs.filter { it.id != miniConfigSpeakerId }
                                        val colMini = speakerColorsLocal[(miniConfigSpeakerId) % speakerColorsLocal.size]

                                        AlertDialog(
                                            onDismissRequest = { miniConfigSpeakerId = -1 },
                                            title = {
                                                Text(
                                                    text = "👤 Спикер ${miniConfigSpeakerId + 1}",
                                                    color = colMini,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 18.sp
                                                )
                                            },
                                            text = {
                                                if (miniCfg != null && miniIdx >= 0) {
                                                    Column(modifier = Modifier.fillMaxWidth()) {
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            Text(
                                                                text = "Имя:",
                                                                fontSize = 13.sp,
                                                                color = MaterialTheme.colorScheme.onSurface,
                                                                modifier = Modifier.width(50.dp)
                                                            )
                                                            OutlinedTextField(
                                                                value = miniCfg.name,
                                                                onValueChange = { newName ->
                                                                    editConfigs = editConfigs.toMutableList().also {
                                                                        it[miniIdx] = miniCfg.copy(name = newName)
                                                                    }
                                                                },
                                                                modifier = Modifier.weight(1f).height(48.dp),
                                                                singleLine = true,
                                                                placeholder = { Text("Новое имя...", fontSize = 12.sp) },
                                                                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                                                                colors = OutlinedTextFieldDefaults.colors(
                                                                    focusedBorderColor = colMini,
                                                                    unfocusedBorderColor = Color(0xFF30363D)
                                                                )
                                                            )
                                                        }
                                                        Spacer(Modifier.height(8.dp))
                                                        if (miniOtherConfigs.isNotEmpty()) {
                                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                                Text(
                                                                    text = "Дубль:",
                                                                    fontSize = 13.sp,
                                                                    color = MaterialTheme.colorScheme.onSurface,
                                                                    modifier = Modifier.width(50.dp)
                                                                )
                                                                var miniExpanded by remember { mutableStateOf(false) }
                                                                val selectedMerge = miniCfg.mergedInto
                                                                val miniMergeLabel = if (selectedMerge != null) {
                                                                    val otherCfg = miniOtherConfigs.find { it.id == selectedMerge }
                                                                    otherCfg?.name?.takeIf { it.isNotBlank() } ?: "Спикер ${selectedMerge + 1}"
                                                                } else "— не выбран —"

                                                                Box(modifier = Modifier.weight(1f)) {
                                                                    OutlinedTextField(
                                                                        value = miniMergeLabel,
                                                                        onValueChange = {},
                                                                        modifier = Modifier.fillMaxWidth().height(40.dp),
                                                                        singleLine = true,
                                                                        readOnly = true,
                                                                        textStyle = LocalTextStyle.current.copy(fontSize = 11.sp),
                                                                        trailingIcon = {
                                                                            IconButton(onClick = { miniExpanded = true },
                                                                                Modifier.size(20.dp)) {
                                                                                Text("▾", fontSize = 12.sp)
                                                                            }
                                                                        },
                                                                        colors = OutlinedTextFieldDefaults.colors(
                                                                            unfocusedBorderColor = Color(0xFF30363D)
                                                                        )
                                                                    )
                                                                    DropdownMenu(
                                                                        expanded = miniExpanded,
                                                                        onDismissRequest = { miniExpanded = false }
                                                                    ) {
                                                                        DropdownMenuItem(
                                                                            text = { Text("— не выбран —", fontSize = 11.sp) },
                                                                            onClick = {
                                                                                editConfigs = editConfigs.toMutableList().also {
                                                                                    it[miniIdx] = miniCfg.copy(mergedInto = null)
                                                                                }
                                                                                miniExpanded = false
                                                                            }
                                                                        )
                                                                        miniOtherConfigs.forEach { other ->
                                                                            val otherName = other.name.takeIf { it.isNotBlank() } ?: "Спикер ${other.id + 1}"
                                                                            DropdownMenuItem(
                                                                                text = { Text(otherName, fontSize = 11.sp) },
                                                                                onClick = {
                                                                                    editConfigs = editConfigs.toMutableList().also {
                                                                                        it[miniIdx] = miniCfg.copy(mergedInto = other.id)
                                                                                    }
                                                                                    miniExpanded = false
                                                                                }
                                                                            )
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        } else {
                                                            Text(
                                                                text = "Нет других спикеров для объединения.",
                                                                fontSize = 11.sp,
                                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                                            )
                                                        }
                                                    }
                                                }
                                            },
                                            confirmButton = {
                                                TextButton(onClick = {
                                                    speakerConfigs = editConfigs
                                                    miniConfigSpeakerId = -1
                                                }) {
                                                    Text("💾 Сохранить", fontSize = 14.sp)
                                                }
                                            },
                                            dismissButton = {
                                                TextButton(onClick = {
                                                    miniConfigSpeakerId = -1
                                                }) {
                                                    Text("Отмена", fontSize = 14.sp)
                                                }
                                            },
                                            containerColor = MaterialTheme.colorScheme.background
                                        )
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    segPlayers.values.forEach { it.stop(); it.release() }
                                    segPlayers.clear()
                                    showTranscribedModal = false
                                }) {
                                    Text("✕ Закрыть", fontSize = 14.sp)
                                }
                            },
                            containerColor = MaterialTheme.colorScheme.background
                        )
                    }
                }
            }

            if (initError.isNotEmpty()) {
                Text(
                    text = initError,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
    }
}

private fun formatTime(seconds: Float): String {
    val totalSecs = seconds.toInt()
    val mins = totalSecs / 60
    val secs = totalSecs % 60
    return "%d:%02d".format(mins, secs)
}
