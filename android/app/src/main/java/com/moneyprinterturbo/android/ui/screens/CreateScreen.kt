package com.moneyprinterturbo.android.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.moneyprinterturbo.android.MptApplication
import com.moneyprinterturbo.android.R
import com.moneyprinterturbo.android.core.db.DbJson
import com.moneyprinterturbo.android.core.llm.LlmService
import com.moneyprinterturbo.android.core.logging.AppLogger
import com.moneyprinterturbo.android.core.media.StockMediaClient
import com.moneyprinterturbo.android.core.model.*
import com.moneyprinterturbo.android.core.tts.TtsService
import com.moneyprinterturbo.android.pipeline.RenderWorker
import com.moneyprinterturbo.android.ui.components.*
import com.moneyprinterturbo.android.ui.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Video creation — full parity with the upstream WebUI form:
 * topic/script/keywords/media/voice/subtitles/BGM/output + per-stage buttons + Generate Everything.
 * Batch mode: one topic per line → N queued tasks.
 */
@Composable
fun CreateScreen(nav: NavController) {
    val app = LocalContext.current.applicationContext as MptApplication
    val scope = rememberCoroutineScope()
    var config by remember {
        mutableStateOf(TaskConfig(videoSubject = "", voiceName = "en-US-AnaNeural-Female"))
    }
    var batchMode by remember { mutableStateOf(false) }
    var batchTopics by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var showVoicePicker by remember { mutableStateOf(false) }

    fun set(c: TaskConfig) { config = c }

    fun importUri(uri: android.net.Uri, folder: String): String? = try {
        val resolver = app.contentResolver
        val name = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "import_${UUID.randomUUID()}"
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "imports/$folder").apply { mkdirs() }
        val out = File(dir, "${UUID.randomUUID()}_$safe")
        val copied = resolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
        if (copied == null) {
            AppLogger.log(app, "IMPORT", "no stream for $uri")
            null
        } else out.absolutePath
    } catch (e: Exception) {
        AppLogger.log(app, "IMPORT", "failed folder=$folder error=${e.message}")
        null
    }

    val localMediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val paths = uris.mapNotNull { importUri(it, "media") }
        if (paths.isNotEmpty()) {
            set(config.copy(videoSource = VideoSource.LOCAL, videoMaterials = config.videoMaterials + paths.map { MaterialInfo(provider = "local", localPath = it) }))
            info = "Imported ${paths.size} local media file(s)."
        }
    }
    val customAudioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        importUri(uri, "audio")?.let { set(config.copy(customAudioFile = it)); info = "Custom audio selected." }
    }
    val bgmPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        importUri(uri, "bgm")?.let { set(config.copy(bgmType = BgmType.CUSTOM, bgmFile = it)); info = "Custom BGM selected." }
    }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Text(stringResource(R.string.new_video), style = MaterialTheme.typography.headlineSmall) }

        // ---- Topic / Script ----
        item {
            SectionCard(stringResource(R.string.section_script)) {
                OutlinedTextFieldMpt(
                    config.videoSubject, { set(config.copy(videoSubject = it)) },
                    stringResource(R.string.video_topic),
                )
                Spacer(Modifier.height(8.dp))
                LabeledSwitch(stringResource(R.string.batch_mode), batchMode) { batchMode = it }
                if (batchMode) {
                    OutlinedTextFieldMpt(
                        batchTopics, { batchTopics = it },
                        stringResource(R.string.batch_hint), minLines = 4, singleLine = false,
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextFieldMpt(
                    config.videoScript, { set(config.copy(videoScript = it)) },
                    stringResource(R.string.script_optional), minLines = 3, singleLine = false,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextFieldMpt(
                    config.videoLanguage, { set(config.copy(videoLanguage = it)) },
                    stringResource(R.string.language_hint),
                    supporting = stringResource(R.string.language_support),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val errNoProvider = stringResource(R.string.err_no_provider)
                    Button(
                        onClick = {
                            busy = "script"; error = null; info = null
                            scope.launch {
                                try {
                                    val llm = LlmService(com.moneyprinterturbo.android.core.net.Http.client(), DbJson.json)
                                    val provider = app.prefs.providerFor(app.prefs.settingsNow(), "script")
                                        ?: throw IllegalStateException(errNoProvider)
                                    val s = llm.generateScript(
                                        app.prefs.resolve(provider), config.videoSubject, config.videoLanguage,
                                        config.paragraphNumber, config.videoScriptPrompt, config.customSystemPrompt,
                                        app.prefs.settingsNow(),
                                    )
                                    set(config.copy(videoScript = s))
                                    info = app.getString(R.string.script_generated)
                                } catch (e: Exception) { error = e.message }
                                busy = null
                            }
                        },
                        enabled = busy == null && config.videoSubject.isNotBlank(),
                    ) { Text(stringResource(R.string.gen_script)) }
                }
            }
        }

        // ---- Keywords ----
        item {
            SectionCard(stringResource(R.string.section_keywords)) {
                OutlinedTextFieldMpt(
                    config.videoTerms.joinToString(", "), { v ->
                        set(config.copy(videoTerms = v.split(',').map { it.trim() }.filter { it.isNotBlank() }))
                    },
                    stringResource(R.string.keywords_comma),
                )
                Spacer(Modifier.height(8.dp))
                LabeledSwitch(
                    stringResource(R.string.match_script_order), config.matchMaterialsToScript,
                ) { set(config.copy(matchMaterialsToScript = it)) }
                Spacer(Modifier.height(8.dp))
                val errNoProvider2 = stringResource(R.string.err_no_provider)
                Button(
                    onClick = {
                        busy = "terms"; error = null; info = null
                        scope.launch {
                            try {
                                val llm = LlmService(com.moneyprinterturbo.android.core.net.Http.client(), DbJson.json)
                                val provider = app.prefs.providerFor(app.prefs.settingsNow(), "terms")
                                    ?: throw IllegalStateException(errNoProvider2)
                                config.videoTerms = llm.generateTerms(
                                    app.prefs.resolve(provider), config.videoSubject, config.videoScript,
                                    5, config.matchMaterialsToScript, app.prefs.settingsNow(),
                                )
                                set(config.copy(videoTerms = config.videoTerms))
                                info = config.videoTerms.joinToString(", ")
                            } catch (e: Exception) { error = e.message }
                            busy = null
                        }
                    },
                    enabled = busy == null && config.videoScript.isNotBlank(),
                ) { Text(stringResource(R.string.gen_terms)) }
            }
        }

        // ---- Media ----
        item {
            SectionCard(stringResource(R.string.section_media)) {
                DropdownField(
                    stringResource(R.string.media_source),
                    listOf("pexels", "pixabay", "coverr", "local"),
                    config.videoSource.vValue,
                ) { set(config.copy(videoSource = VideoSource.from(it))) }
                Spacer(Modifier.height(8.dp))
                if (config.videoSource == VideoSource.LOCAL) {
                    OutlinedButton(onClick = { localMediaPicker.launch(arrayOf("video/*", "image/*")) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Select local video/images")
                    }
                    config.videoMaterials.forEachIndexed { index, material ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(material.localPath?.substringAfterLast('/') ?: "local media", Modifier.weight(1f))
                            TextButton(onClick = { set(config.copy(videoMaterials = config.videoMaterials.filterIndexed { i, _ -> i != index })) }) { Text("Remove") }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                DropdownField(
                    stringResource(R.string.aspect),
                    listOf("9:16", "16:9", "1:1"), config.videoAspect.value,
                ) { set(config.copy(videoAspect = VideoAspect.from(it))) }
                Spacer(Modifier.height(8.dp))
                DropdownField(
                    stringResource(R.string.fit_mode),
                    listOf("cover", "contain"), config.videoFitMode.name.lowercase(),
                ) { set(config.copy(videoFitMode = if (it == "cover") FitMode.COVER else FitMode.CONTAIN)) }
                Spacer(Modifier.height(8.dp))
                DropdownField(
                    stringResource(R.string.concat_mode),
                    listOf("random", "sequential"), config.videoConcatMode.name.lowercase(),
                ) { set(config.copy(videoConcatMode = if (it == "sequential") ConcatMode.SEQUENTIAL else ConcatMode.RANDOM)) }
            }
        }

        // ---- Voice ----
        item {
            SectionCard(stringResource(R.string.section_voice)) {
                OutlinedButton(onClick = { showVoicePicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.voice) + ": " + config.voiceName.ifBlank { "No voice" })
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { set(config.copy(voiceName = "")) }) { Text("No voice") }
                    OutlinedButton(onClick = { customAudioPicker.launch(arrayOf("audio/*")) }) { Text("Custom audio") }
                }
                config.customAudioFile?.let { path ->
                    Text("Audio: ${path.substringAfterLast('/')}", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { set(config.copy(customAudioFile = null)) }) { Text("Remove custom audio") }
                }
                Spacer(Modifier.height(8.dp))
                SliderField(stringResource(R.string.voice_rate), config.voiceRate, 0.5f..2.0f) {
                    set(config.copy(voiceRate = it))
                }
                SliderField(stringResource(R.string.voice_volume), config.voiceVolume, 0.1f..3.0f) {
                    set(config.copy(voiceVolume = it))
                }
            }
        }

        // ---- Subtitles ----
        item {
            SectionCard(stringResource(R.string.section_subtitle)) {
                LabeledSwitch(stringResource(R.string.subtitle_enabled), config.subtitleEnabled) {
                    set(config.copy(subtitleEnabled = it))
                }
                Spacer(Modifier.height(8.dp))
                DropdownField(
                    stringResource(R.string.subtitle_position),
                    listOf("bottom", "top", "center", "two_thirds_bottom", "custom"),
                    config.subtitlePosition.vValue,
                ) { set(config.copy(subtitlePosition = SubtitlePosition.from(it))) }
                Spacer(Modifier.height(8.dp))
                DropdownField(
                    stringResource(R.string.subtitle_display),
                    listOf("sentence", "word_by_word"), config.subtitleDisplayMode.vValue,
                ) { set(config.copy(subtitleDisplayMode = SubtitleDisplayMode.from(it))) }
                Spacer(Modifier.height(8.dp))
                NumberField(stringResource(R.string.font_size), config.fontSize) { set(config.copy(fontSize = it)) }
                Spacer(Modifier.height(8.dp))
                ColorField(stringResource(R.string.text_color), config.textForeColor) { set(config.copy(textForeColor = it)) }
                Spacer(Modifier.height(8.dp))
                ColorField("Subtitle background", config.textBackgroundColor) { set(config.copy(textBackgroundColor = it)) }
                Spacer(Modifier.height(8.dp))
                ColorField("Subtitle stroke", config.strokeColor) { set(config.copy(strokeColor = it)) }
                NumberField("Stroke width", config.strokeWidth.toInt()) { set(config.copy(strokeWidth = it.coerceIn(0, 20).toFloat())) }
                LabeledSwitch("Rounded subtitle background", config.roundedSubtitleBackground) { set(config.copy(roundedSubtitleBackground = it)) }
                if (config.subtitlePosition == SubtitlePosition.CUSTOM) {
                    SliderField("Custom position", config.customPosition, 0f..100f) { set(config.copy(customPosition = it)) }
                }
            }
        }

        // ---- BGM ----
        item {
            SectionCard(stringResource(R.string.section_bgm)) {
                DropdownField(
                    "BGM", listOf("none", "random", "preset", "custom", "sonilo"), config.bgmType.vValue,
                ) { set(config.copy(bgmType = BgmType.from(it))) }
                if (config.bgmType == BgmType.PRESET) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextFieldMpt(config.bgmFile, { set(config.copy(bgmFile = it)) }, "Bundled song file")
                }
                if (config.bgmType == BgmType.SONILO) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextFieldMpt(
                        config.soniloBgmPrompt,
                        { set(config.copy(soniloBgmPrompt = it.take(2000))) },
                        "Sonilo music prompt (optional)",
                        supporting = "Leave empty to let Sonilo match the video automatically.",
                    )
                }
                if (config.bgmType == BgmType.CUSTOM) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { bgmPicker.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Select custom BGM") }
                    if (config.bgmFile.isNotBlank()) Text("BGM: ${config.bgmFile.substringAfterLast('/')}", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                SliderField(stringResource(R.string.bgm_volume), config.bgmVolume, 0f..1f) {
                    set(config.copy(bgmVolume = it))
                }
            }
        }

        // ---- Output ----
        item {
            SectionCard(stringResource(R.string.section_output)) {
                NumberField(stringResource(R.string.clip_duration), config.videoClipDuration) {
                    set(config.copy(videoClipDuration = it.coerceIn(1, 120)))
                }
                Spacer(Modifier.height(8.dp))
                NumberField(stringResource(R.string.video_count), config.videoCount) {
                    set(config.copy(videoCount = it.coerceIn(1, 10)))
                }
                Spacer(Modifier.height(8.dp))
                // P2.2 stop_at: generate up to a stage, then stop for inspection/editing.
                DropdownField(
                    "Stop at (intermediate)",
                    StopAt.entries.map { it.vValue }, config.stopAt.vValue,
                ) { set(config.copy(stopAt = StopAt.from(it))) }
                Spacer(Modifier.height(8.dp))
                DropdownField(
                    stringResource(R.string.transition),
                    listOf("none", "fade", "slide", "zoom"), config.videoTransition.name.lowercase(),
                ) { set(config.copy(videoTransition = when (it) { "fade" -> Transition.FADE; "slide" -> Transition.SLIDE; "zoom" -> Transition.ZOOM; else -> Transition.NONE })) }
                SliderField("Clip speed", config.videoClipSpeed, 0.5f..2.0f) { set(config.copy(videoClipSpeed = it)) }
                NumberField("Paragraphs", config.paragraphNumber) { set(config.copy(paragraphNumber = it.coerceIn(1, 10))) }
                NumberField("Render threads", config.nThreads) { set(config.copy(nThreads = it.coerceIn(1, 16))) }
                OutlinedTextFieldMpt(config.videoScriptPrompt, { set(config.copy(videoScriptPrompt = it)) }, "Custom script prompt", minLines = 2, singleLine = false)
                OutlinedTextFieldMpt(config.customSystemPrompt, { set(config.copy(customSystemPrompt = it)) }, "Custom system prompt", minLines = 2, singleLine = false)
            }
        }

        error?.let { item { ErrorBanner(it) { error = null } } }
        info?.let { item { Text(info!!, color = MaterialTheme.colorScheme.primary) } }

        // ---- Action buttons ----
        item {
            val errNoTopics = stringResource(R.string.err_no_topics)
            Button(
                onClick = {
                    scope.launch {
                        try {
                            if (batchMode) {
                                val topics = batchTopics.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
                                if (topics.isEmpty()) throw IllegalStateException(errNoTopics)
                                topics.forEach { topic ->
                                    val proj = app.repository.createProject(config.copy(videoSubject = topic))
                                    app.repository.queueTask(proj)
                                }
                                info = app.getString(R.string.batch_queued, topics.size)
                            } else {
                                val proj = app.repository.createProject(config)
                                app.repository.queueTask(proj)
                                nav.navigate(Routes.project(proj.id))
                            }
                        } catch (e: Exception) { error = e.message }
                    }
                },
                enabled = busy == null,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text(if (batchMode) stringResource(R.string.generate_batch) else stringResource(R.string.generate_everything)) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (showVoicePicker) {
        VoicePickerDialog(config.voiceName, onPick = {
            set(config.copy(voiceName = it)); showVoicePicker = false
        }, onDismiss = { showVoicePicker = false })
    }
}

@Composable
fun SliderField(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onValue: (Float) -> Unit) {
    Text("$label: ${"%.2f".format(value)}", style = MaterialTheme.typography.bodySmall)
    Slider(value = value, onValueChange = onValue, valueRange = range, steps = 0)
}

@Composable
fun NumberField(label: String, value: Int, onValue: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextFieldMpt(text, { v ->
        text = v; v.toIntOrNull()?.let(onValue)
    }, label)
}

@Composable
fun ColorField(label: String, value: String, onValue: (String) -> Unit) {
    OutlinedTextFieldMpt(value, onValue, label, supporting = "#RRGGBB")
}

/** Voice picker over azure_voices.json with locale grouping + search. */
@Composable
fun VoicePickerDialog(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val voices = remember {
        try {
            context.assets.open("azure_voices.json").bufferedReader().use { it.readText() }
                .let { com.moneyprinterturbo.android.core.tts.VoiceCatalog.load(it) }
        } catch (e: Exception) { emptyList<com.moneyprinterturbo.android.core.tts.Voice>() }
    }
    var query by remember { mutableStateOf("") }
    val filtered = voices.filter { it.name.contains(query, ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_voice)) },
        text = {
            Column {
                OutlinedTextFieldMpt(query, { query = it }, stringResource(R.string.search))
                LazyColumn(Modifier.height(400.dp)) {
                    items(filtered.size) { i ->
                        val v = filtered[i]
                        ListItem(
                            headlineContent = { Text(v.name) },
                            supportingContent = { Text(v.locale) },
                            modifier = Modifier.clickable { onPick(v.name) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

