package com.moneyprinterturbo.android.pipeline

import android.content.Context
import com.moneyprinterturbo.android.core.db.MptDatabase
import com.moneyprinterturbo.android.core.db.TaskEntity
import com.moneyprinterturbo.android.core.logging.AppLogger
import com.moneyprinterturbo.android.core.llm.LlmService
import com.moneyprinterturbo.android.core.media.Cue
import com.moneyprinterturbo.android.core.media.FfmpegExecutor
import com.moneyprinterturbo.android.core.media.FontManager
import com.moneyprinterturbo.android.core.media.MediaComposer
import com.moneyprinterturbo.android.core.media.StockMediaClient
import com.moneyprinterturbo.android.core.media.SubtitleBuilder
import com.moneyprinterturbo.android.core.media.SubtitleStyle
import com.moneyprinterturbo.android.core.media.WhisperClient
import com.moneyprinterturbo.android.core.media.Srt
import com.moneyprinterturbo.android.core.model.*
import com.moneyprinterturbo.android.core.net.RemoteMptClient
import com.moneyprinterturbo.android.core.net.SoniloClient
import com.moneyprinterturbo.android.core.storage.PrefsStore
import com.moneyprinterturbo.android.core.tts.TtsService
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import kotlinx.serialization.encodeToString
import java.util.UUID

/**
 * Full pipeline — Kotlin port of upstream app/services/task.py::_run_pipeline.
 * Stages: preflight → script → terms → materials → audio → subtitle → combine.
 * Real progress (0..100) written to Room after every step.
 */
class TaskPipeline(
    private val context: Context,
    private val db: MptDatabase,
    private val prefs: PrefsStore,
    private val http: OkHttpClient,
    private val json: Json,
) {
    data class Progress(val status: TaskStatus, val stage: Stage, val progress: Int, val message: String? = null)

    class CancelledException : Exception("task cancelled")

    @Volatile var cancelled: Boolean = false
        private set

    fun cancel() { cancelled = true }

    private fun checkCancel() { if (cancelled) throw CancelledException() }

    private fun outDir(taskId: String): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "tasks/$taskId").apply { mkdirs() }

    private suspend fun update(taskId: String, status: TaskStatus, stage: Stage, progress: Int, message: String? = null) {
        val now = System.currentTimeMillis()
        db.taskDao().updateProgress(taskId, status.code, progress.coerceIn(0, 100), stage.name, now)
        if (message != null) {
            db.taskDao().appendLog(taskId, "\n[${now}] $message", now)
        }
    }

    private data class PipelineResult(val first: File, val outputs: List<File>)

    suspend fun run(task: TaskEntity): TaskEntity {
        cancelled = false
        val config = com.moneyprinterturbo.android.core.db.DbJson.configFromString(task.configJson)
        try {
            val result = runInternal(task, config)
            val outputJson = json.encodeToString(result.outputs.map { it.absolutePath })
            db.taskDao().markComplete(task.id, result.first.absolutePath, outputJson, System.currentTimeMillis())
            db.projectDao().get(task.projectId)?.let { p ->
                db.projectDao().upsert(p.copy(lastStatus = TaskStatus.COMPLETED.code, lastVideoPath = result.first.absolutePath, updatedAt = System.currentTimeMillis()))
            }
            return db.taskDao().get(task.id)!!
        } catch (e: CancelledException) {
            db.taskDao().updateProgress(task.id, TaskStatus.CANCELLED.code, 0, Stage.QUEUED.name, System.currentTimeMillis())
            throw e
        } catch (e: Exception) {
            AppLogger.exception(context, "PIPELINE_ERROR", "task=${task.id} stage failure", e)
            db.taskDao().markFailed(task.id, e.message ?: e.javaClass.simpleName, System.currentTimeMillis())
            db.projectDao().get(task.projectId)?.let { p ->
                db.projectDao().upsert(p.copy(lastStatus = TaskStatus.FAILED.code, updatedAt = System.currentTimeMillis()))
            }
            throw e
        }
    }

    private suspend fun runInternal(task: TaskEntity, config: TaskConfig): PipelineResult {
        val dir = outDir(task.id)
        if (config.executionMode == Mode.CLOUD_API) {
            throw Exception("CLOUD_API execution mode is not implemented yet; choose LOCAL or REMOTE instead of silently falling back to local execution")
        }
        if (config.executionMode == Mode.REMOTE) {
            AppLogger.log(context, "PIPELINE", "using remote backend for task=${task.id}")
            val remote = RemoteMptClient(context)
            val final = remote.generate(config, dir, { p, stage ->
                // RemoteMptClient's progress callback is a plain (Int,String)->Unit lambda;
                // bridge the suspend Room write with a short runBlocking on the IO caller.
                kotlinx.coroutines.runBlocking {
                    update(task.id, TaskStatus.RUNNING, Stage.COMBINE, p, "remote: $stage")
                }
            }) { remoteTaskId ->
                kotlinx.coroutines.runBlocking {
                    db.taskDao().setRemoteTaskId(task.id, remoteTaskId, System.currentTimeMillis())
                }
            }
            return PipelineResult(final, listOf(final))
        }
        val ffmpeg = FfmpegExecutor(context)
        val composer = MediaComposer(ffmpeg, dir)
        var progress = 0

        // ---------- PREFLIGHT ----------
        update(task.id, TaskStatus.RUNNING, Stage.QUEUED, 1, "preflight")
        if (!ffmpeg.isAvailable()) ffmpeg.tryRecoverFromApk()
        if (!ffmpeg.isAvailable()) throw Exception(
            "ffmpeg binary is not available on this device [${ffmpeg.diagnose()}]"
        )
        val stat = android.os.StatFs(dir.absolutePath)
        val freeMb = stat.availableBytes / (1024 * 1024)
        if (freeMb < 250) throw Exception("insufficient storage: ${freeMb}MB free, at least 250MB required")
        if (config.executionMode == Mode.LOCAL && config.voiceName.isBlank() && config.customAudioFile == null) {
            // Upstream supports no-voice generation. Keep the task valid and synthesize silence later.
            AppLogger.log(context, "PIPELINE", "no voice configured; using no-voice mode")
        }
        // Fail fast on stock keys BEFORE burning LLM/TTS quota (upstream parity).
        if (config.videoMaterials.isEmpty() && config.videoSource != VideoSource.LOCAL) {
            keyFor(config.videoSource, prefs.stockKeys())
            update(task.id, TaskStatus.RUNNING, Stage.QUEUED, 2,
                "preflight OK — stock=${config.videoSource.vValue}, ffmpeg=${"%.1f".format(ffmpeg.binary.length() / 1048576.0)}MB")
        }

        // ---------- 1. SCRIPT ----------
        if (config.videoScript.isBlank()) {
            val provider = prefs.providerFor(prefs.settingsNow(), "script")
                ?: throw Exception("no LLM provider configured — add one in Settings → Providers")
            val resolved = prefs.resolve(provider)
            update(task.id, TaskStatus.RUNNING, Stage.SCRIPT, 5,
                "generating script on ${provider.name} / ${prefs.settingsNow().scriptModel.ifBlank { provider.model }}")
            checkCancel()
            val llm = LlmService(http, json)
            config.videoScript = llm.generateScript(
                resolved, config.videoSubject, config.videoLanguage,
                config.paragraphNumber, config.videoScriptPrompt, config.customSystemPrompt,
                prefs.settingsNow(),
            )
            update(task.id, TaskStatus.RUNNING, Stage.SCRIPT, 10,
                "script generated (${config.videoScript.length} chars) via ${provider.name}")
        }

        // ---------- 2. TERMS ----------
        AppLogger.log(context, "PIPELINE", "stage=TERMS task=${task.id}")
        if (config.videoTerms.isEmpty() && config.videoSource != VideoSource.LOCAL && config.videoMaterials.isEmpty()) {
            update(task.id, TaskStatus.RUNNING, Stage.TERMS, 12, "generating search terms")
            checkCancel()
            val provider = prefs.providerFor(prefs.settingsNow(), "terms")
                ?: throw Exception("no LLM provider configured — add one in Settings → Providers")
            val llm = LlmService(http, json)
            config.videoTerms = llm.generateTerms(
                prefs.resolve(provider), config.videoSubject, config.videoScript,
                amount = 5, matchScriptOrder = config.matchMaterialsToScript,
                settings = prefs.settingsNow(),
            )
            update(task.id, TaskStatus.RUNNING, Stage.TERMS, 20, "terms: ${config.videoTerms.joinToString()}")
        }

        // ---------- 3. MATERIALS ----------
        AppLogger.log(context, "PIPELINE", "stage=MATERIALS task=${task.id}")
        val materials: List<MaterialInfo> = if (config.videoMaterials.isNotEmpty()) {
            update(task.id, TaskStatus.RUNNING, Stage.MATERIALS, 25, "using ${config.videoMaterials.size} pre-selected materials")
            config.videoMaterials
        } else {
            update(task.id, TaskStatus.RUNNING, Stage.MATERIALS, 20, "searching stock media")
            checkCancel()
            val keys = prefs.stockKeys()
            val stock = StockMediaClient(http, cacheDir = File(context.cacheDir, "mpt-material-cache"))
            val results = mutableListOf<MaterialInfo>()
            val perTerm = ((40 - 20) / config.videoTerms.size.coerceAtLeast(1))
            var p = 20
            for ((i, term) in config.videoTerms.withIndex()) {
                checkCancel()
                try {
                    val vids = stock.searchCached(config.videoSource.vValue, keyFor(config.videoSource, keys), term, config.videoAspect.value, 8)
                    vids.forEach { v ->
                        results += MaterialInfo(
                            provider = config.videoSource.vValue, url = v.url, duration = v.durationSec,
                            creator = v.creator, creatorUrl = v.creatorUrl,
                        )
                    }
                } catch (e: Exception) {
                    update(task.id, TaskStatus.RUNNING, Stage.MATERIALS, p, "term '$term' failed: ${e.message}")
                }
                p += perTerm
                update(task.id, TaskStatus.RUNNING, Stage.MATERIALS, p, null)
            }
            if (results.isEmpty()) throw Exception("no stock materials found — check API keys, network, or pick local media")
            results
        }

        // ---------- 4. AUDIO ----------
        AppLogger.log(context, "PIPELINE", "stage=AUDIO task=${task.id}")
        update(task.id, TaskStatus.RUNNING, Stage.AUDIO, 40, "generating voiceover")
        checkCancel()
        val audioFile: File?
        var words: List<com.moneyprinterturbo.android.core.media.WordBoundary> = emptyList()
        if (config.customAudioFile != null) {
            audioFile = File(config.customAudioFile!!)
            if (!audioFile.exists()) throw Exception("custom audio file missing: ${config.customAudioFile}")
        } else if (config.voiceName.isBlank()) {
            audioFile = composer.silentAudio((config.videoClipDuration.coerceAtLeast(1) * config.videoCount.coerceAtLeast(1)).toDouble())
            words = emptyList()
        } else {
            val tts = TtsService(http, prefs)
            val voiceOut = File(dir, "audio.mp3")
            val r = tts.synthesize(config.voiceName, config.videoScript, config.voiceRate, config.voiceVolume, voiceOut)
            audioFile = r.file
            words = r.words
        }
        val audioDuration = composer.probeDuration(audioFile) ?: 30.0
        update(task.id, TaskStatus.RUNNING, Stage.AUDIO, 50, "voiceover ready (${audioDuration.toInt()}s)")

        // ---------- 5. SUBTITLE ----------
        AppLogger.log(context, "PIPELINE", "stage=SUBTITLE task=${task.id}")
        var srtFile: File? = null
        if (config.subtitleEnabled) {
            val subtitleProvider = prefs.settingsNow().subtitleProvider
            update(task.id, TaskStatus.RUNNING, Stage.SUBTITLE, 50, "subtitle provider=${subtitleProvider.name.lowercase()}")
            checkCancel()
            var cues: List<Cue> = emptyList()
            if (subtitleProvider == SubtitleProvider.WHISPER_OPENAI_COMPATIBLE) {
                // Whisper works directly on custom audio too, matching upstream's intended behavior.
                // It is an explicit API adapter here; no fake local model is bundled into the APK.
                cues = try {
                    WhisperClient(http, prefs).transcribe(audioFile).let { Srt.fromSegments(it) }
                } catch (e: Exception) {
                    AppLogger.exception(context, "WHISPER", "transcription failed; falling back to available timing", e)
                    emptyList()
                }
            } else {
                cues = when {
                    words.isNotEmpty() && config.subtitleDisplayMode == SubtitleDisplayMode.WORD_BY_WORD -> SubtitleBuilder.wordByWord(words)
                    words.isNotEmpty() -> SubtitleBuilder.sentences(words)
                    else -> emptyList()
                }
            }
            if (cues.isEmpty()) {
                // Safe fallback when the selected provider is unavailable or TTS has no word timings.
                val segments = config.videoScript.split(Regex("[.。!！?？\n]+" )).filter { it.isNotBlank() }
                val segDur = audioDuration / segments.size.coerceAtLeast(1)
                var t = 0.0
                cues = segments.map { seg ->
                    val c = Cue((t * 1000).toLong(), ((t + segDur) * 1000).toLong(), seg.trim())
                    t += segDur
                    c
                }
            }
            srtFile = File(dir, "subtitle.srt").also { it.writeText(Srt.write(cues)) }
            update(task.id, TaskStatus.RUNNING, Stage.SUBTITLE, 60, "subtitle cues: ${cues.size}")
        }
        // ---------- 6. COMBINE ----------
        AppLogger.log(context, "PIPELINE", "stage=COMBINE task=${task.id}")
        update(task.id, TaskStatus.RUNNING, Stage.COMBINE, 60, "composing video")
        checkCancel()
        val (w, h) = config.videoAspect.width to config.videoAspect.height
        val fps = 30
        val scenes = mutableListOf<File>()

        // Determine scene count and durations: cover the audio duration
        val sceneCount = Math.max(1, Math.ceil(audioDuration / config.videoClipDuration).toInt())
        val downloadClient = http
        val downloaded = mutableListOf<Pair<File, Boolean>>() // file, isImage

        for (i in 0 until sceneCount) {
            checkCancel()
            val material = pickMaterial(materials, i, config.videoConcatMode)
            val local: File
            var isImage = material.url.substringAfterLast('.').lowercase() in listOf("jpg", "jpeg", "png", "webp")
            if (material.localPath != null) {
                local = File(material.localPath)
            } else {
                update(task.id, TaskStatus.RUNNING, Stage.COMBINE, 60 + (i * 15 / sceneCount), "loading material ${i + 1}/$sceneCount")
                if (material.provider in setOf("pexels", "pixabay", "coverr")) {
                    val stock = StockMediaClient(downloadClient, cacheDir = File(context.cacheDir, "mpt-material-cache"))
                    val cached = stock.downloadCached(
                        StockMediaClient.StockVideo(
                            provider = material.provider,
                            id = material.url.hashCode().toString(),
                            url = material.url,
                            width = w,
                            height = h,
                            durationSec = material.duration,
                            creator = material.creator,
                            creatorUrl = material.creatorUrl,
                        )
                    )
                    local = File(dir, "material_$i.${cached.extension.ifBlank { if (isImage) "jpg" else "mp4" }}")
                    if (!local.exists() || local.length() != cached.length()) cached.copyTo(local, overwrite = true)
                    AppLogger.log(context, "MATERIAL", "cache hit/download provider=${material.provider} index=$i bytes=${local.length()}")
                } else {
                    local = File(dir, "material_$i.${if (isImage) "jpg" else "mp4"}")
                    val started = System.currentTimeMillis()
                    AppLogger.network(context, "GET", material.url)
                    downloadClient.newCall(okhttp3.Request.Builder().url(material.url).build()).execute().use { resp ->
                        AppLogger.network(context, "GET", material.url, resp.code, System.currentTimeMillis() - started)
                        if (!resp.isSuccessful) throw Exception("material download failed: HTTP ${resp.code}")
                        resp.body!!.byteStream().use { input -> local.outputStream().use { input.copyTo(it) } }
                    }
                }
            }
            downloaded += local to isImage
        }

        val progressRange = 75..90
        downloaded.forEachIndexed { i, (file, isImage) ->
            checkCancel()
            val sceneDur = if (isImage) config.videoClipDuration.toDouble()
                else minOf(config.videoClipDuration.toDouble(),
                    composer.probeDuration(file) ?: config.videoClipDuration.toDouble())
            val p = progressRange.start + (i * (progressRange.endInclusive - progressRange.start)) / sceneCount
            update(task.id, TaskStatus.RUNNING, Stage.COMBINE, p, "normalizing scene ${i + 1}/$sceneCount")
            scenes += composer.makeScene(
                file, isImage, w, h, sceneDur, fps, config.videoFitMode.name.lowercase(),
                config.videoClipSpeed, config.videoTransition.name, null,
            )
        }

        // Concat order: sequential or random (parity with upstream concat mode)
        val ordered = if (config.videoConcatMode == ConcatMode.SEQUENTIAL) scenes
            else scenes.shuffled(kotlin.random.Random(task.id.hashCode()))
        update(task.id, TaskStatus.RUNNING, Stage.COMBINE, 90, "concatenating ${ordered.size} scenes")
        var current = composer.concat(ordered)

        update(task.id, TaskStatus.RUNNING, Stage.COMBINE, 92, "mixing audio")
        current = applyBackgroundMusic(task.id, current, audioFile, config, composer, dir, 0)

        if (srtFile != null) {
            update(task.id, TaskStatus.RUNNING, Stage.COMBINE, 95, "burning subtitles")
            val fontName = config.fontName.ifBlank { FontManager.DEFAULT_FONT }
            FontManager.ensureBundled(context)
            val style = SubtitleStyle.Style(
                fontName = fontName, fontSize = config.fontSize, videoWidth = w, videoHeight = h,
                position = config.subtitlePosition.vValue, customPosition = config.customPosition,
                foreColor = config.textForeColor, backColor = config.textBackgroundColor,
                strokeColor = config.strokeColor, strokeWidth = config.strokeWidth,
            )
            // Stage the SRT INSIDE the ffmpeg process CWD (cacheDir/ffmpeg-cwd/burn.srt)
            // and burn via the RELATIVE name: the most primitive possible fopen for
            // libass. State is reported into the TASK LOG so any skip is visible to the
            // user; a staging failure degrades to subtitle-less output, not task failure.
            val staged = ffmpeg.stagedSrtFile()
            val content: String? = try {
                srtFile!!.readText()
            } catch (e: Exception) {
                update(task.id, TaskStatus.RUNNING, Stage.COMBINE, 95, "subtitles skipped: cannot read SRT (${e.message})")
                null
            }
            if (content != null && content.isNotBlank()) {
                try {
                    staged.writeText(content)
                    update(task.id, TaskStatus.RUNNING, Stage.COMBINE, 95, "burning subtitles (srt=${content.length}B staged=${staged.length()}B)")
                    current = composer.burnSubtitles(current, staged, FontManager.fontsDir(context), SubtitleStyle.forceStyle(style))
                } catch (e: Exception) {
                    update(task.id, TaskStatus.RUNNING, Stage.COMBINE, 95, "subtitles staged but burn failed: ${e.message}")
                    throw e
                }
            } else if (content != null) {
                update(task.id, TaskStatus.RUNNING, Stage.COMBINE, 95, "subtitles skipped: SRT empty (src=${srtFile!!.length()}B)")
            }
        }

        update(task.id, TaskStatus.RUNNING, Stage.COMBINE, 98, "finalizing ${config.videoCount} output(s)")
        val outputs = mutableListOf<File>()
        val count = config.videoCount.coerceIn(1, 10)
        if (count == 1) {
            val final = File(dir, "final.mp4")
            if (current.absolutePath != final.absolutePath) {
                if (!current.renameTo(final)) current.copyTo(final, overwrite = true)
            }
            outputs += final
        } else {
            for (index in 0 until count) {
                checkCancel()
                val final = File(dir, "final_${index + 1}.mp4")
                if (!current.renameTo(final)) current.copyTo(final, overwrite = true)
                outputs += final
                if (index != count - 1) {
                    // Re-run the same source composition for additional outputs with a stable,
                    // different ordering. This keeps video_count functional rather than merely
                    // accepting the setting in the UI.
                    val reordered = scenes.shuffled(kotlin.random.Random(task.id.hashCode() + index + 1))
                    current = composer.concat(reordered)
                    current = applyBackgroundMusic(task.id, current, audioFile, config, composer, dir, index + 1)
                    if (srtFile != null) {
                        val fontName = config.fontName.ifBlank { FontManager.DEFAULT_FONT }
                        val style = SubtitleStyle.Style(
                            fontName = fontName, fontSize = config.fontSize, videoWidth = w, videoHeight = h,
                            position = config.subtitlePosition.vValue, customPosition = config.customPosition,
                            foreColor = config.textForeColor, backColor = config.textBackgroundColor,
                            strokeColor = config.strokeColor, strokeWidth = config.strokeWidth,
                        )
                        val staged = ffmpeg.stagedSrtFile()
                        val content: String? = try { srtFile!!.readText() } catch (_: Exception) { null }
                        if (!content.isNullOrBlank()) {
                            staged.writeText(content)
                            current = composer.burnSubtitles(current, staged, FontManager.fontsDir(context), SubtitleStyle.forceStyle(style))
                        }
                    }
                }
            }
        }
        update(task.id, TaskStatus.RUNNING, Stage.DONE, 99, "video ready: ${outputs.size} output(s)")
        AppLogger.log(context, "PIPELINE", "complete task=${task.id} outputs=${outputs.joinToString { it.absolutePath }}")
        return PipelineResult(outputs.first(), outputs)
    }

    private suspend fun applyBackgroundMusic(
        taskId: String,
        video: File,
        audioFile: File,
        config: TaskConfig,
        composer: MediaComposer,
        dir: File,
        outputIndex: Int,
    ): File {
        checkCancel()
        if (config.bgmType != BgmType.SONILO) {
            return composer.muxAudio(video, audioFile, bgmFile(context, config), config.bgmVolume)
        }
        // Sonilo analyzes the completed visual composition plus voice, then returns music.
        val voiceVideo = composer.muxAudio(video, audioFile, null, config.bgmVolume)
        checkCancel()
        update(taskId, TaskStatus.RUNNING, Stage.COMBINE, 94, "preparing Sonilo music analysis video")
        val proxy = composer.makeSoniloProxy(voiceVideo)
        return try {
            update(taskId, TaskStatus.RUNNING, Stage.COMBINE, 95, "generating Sonilo background music")
            val soniloAudio = SoniloClient(context, prefs).generateBgm(
                proxy, config.soniloBgmPrompt, File(dir, "sonilo-${outputIndex}.m4a")
            )
            composer.muxAudio(voiceVideo, null, soniloAudio, config.bgmVolume)
        } finally {
            proxy.delete()
        }
    }

    private fun pickMaterial(list: List<MaterialInfo>, index: Int, mode: ConcatMode): MaterialInfo {
        val src = list.filter { m -> m.url.isNotBlank() || m.localPath != null }
            .distinctBy { it.localPath ?: it.url }
        if (src.isEmpty()) throw Exception("no usable materials")
        // Prefer an unused candidate while enough material exists. This mirrors the
        // upstream engine's de-duplication before it starts reusing clips.
        val unique = if (index < src.size) src[index] else null
        if (mode == ConcatMode.SEQUENTIAL) return unique ?: src[index % src.size]
        if (unique != null) return unique
        return src.random(kotlin.random.Random(index * 9973 + src.size))
    }

    private fun keyFor(source: VideoSource, keys: StockKeys): String = when (source) {
        VideoSource.PEXELS -> keys.pexelsApiKey
        VideoSource.PIXABAY -> keys.pixabayApiKey
        VideoSource.COVERR -> keys.coverrApiKey
        else -> ""
    }.also { if (it.isBlank()) throw Exception("${source.name.lowercase()} API key missing — set it in Settings → Providers") }

    /** Resolve BGM: preset from assets or user file; null = no music. */
    private fun bgmFile(context: Context, config: TaskConfig): File? = when (config.bgmType) {
        BgmType.NONE -> null
        BgmType.CUSTOM -> {
            val f = File(config.bgmFile)
            if (!f.exists()) throw Exception("BGM file not found: ${config.bgmFile}")
            f
        }
        BgmType.PRESET -> {
            val name = config.bgmFile.ifBlank { "output002.mp3" }
            val out = File(context.cacheDir, "bgm_$name")
            if (!out.exists()) {
                context.assets.open("songs/$name").use { input -> out.outputStream().use { input.copyTo(it) } }
            }
            out
        }
        BgmType.SONILO -> null
        BgmType.RANDOM -> {
            val assets = context.assets.list("songs") ?: emptyArray()
            if (assets.isEmpty()) null
            else {
                val name = assets.random()
                val out = File(context.cacheDir, "bgm_$name")
                if (!out.exists()) context.assets.open("songs/$name").use { input -> out.outputStream().use { input.copyTo(it) } }
                out
            }
        }
    }
}
