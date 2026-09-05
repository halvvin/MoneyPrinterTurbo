package com.moneyprinterturbo.android.pipeline

import android.content.Context
import com.moneyprinterturbo.android.core.db.MptDatabase
import com.moneyprinterturbo.android.core.db.TaskEntity
import com.moneyprinterturbo.android.core.llm.LlmService
import com.moneyprinterturbo.android.core.media.Cue
import com.moneyprinterturbo.android.core.media.FfmpegExecutor
import com.moneyprinterturbo.android.core.media.FontManager
import com.moneyprinterturbo.android.core.media.MediaComposer
import com.moneyprinterturbo.android.core.media.StockMediaClient
import com.moneyprinterturbo.android.core.media.SubtitleBuilder
import com.moneyprinterturbo.android.core.media.SubtitleStyle
import com.moneyprinterturbo.android.core.media.Srt
import com.moneyprinterturbo.android.core.model.*
import com.moneyprinterturbo.android.core.storage.PrefsStore
import com.moneyprinterturbo.android.core.tts.TtsService
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
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
            db.taskDao().appendLog(taskId, "[${now}] $message", now)
        }
    }

    suspend fun run(task: TaskEntity): TaskEntity {
        cancelled = false
        val config = com.moneyprinterturbo.android.core.db.DbJson.configFromString(task.configJson)
        try {
            val video = runInternal(task, config)
            db.taskDao().markComplete(task.id, video.absolutePath, System.currentTimeMillis())
            db.projectDao().get(task.projectId)?.let { p ->
                db.projectDao().upsert(p.copy(lastStatus = TaskStatus.COMPLETED.code, lastVideoPath = video.absolutePath, updatedAt = System.currentTimeMillis()))
            }
            return db.taskDao().get(task.id)!!
        } catch (e: CancelledException) {
            db.taskDao().updateProgress(task.id, TaskStatus.CANCELLED.code, 0, Stage.QUEUED.name, System.currentTimeMillis())
            throw e
        } catch (e: Exception) {
            db.taskDao().markFailed(task.id, e.message ?: e.javaClass.simpleName, System.currentTimeMillis())
            db.projectDao().get(task.projectId)?.let { p ->
                db.projectDao().upsert(p.copy(lastStatus = TaskStatus.FAILED.code, updatedAt = System.currentTimeMillis()))
            }
            throw e
        }
    }

    private suspend fun runInternal(task: TaskEntity, config: TaskConfig): File {
        val dir = outDir(task.id)
        val ffmpeg = FfmpegExecutor(context)
        val composer = MediaComposer(ffmpeg, dir)
        var progress = 0

        // ---------- PREFLIGHT ----------
        update(task.id, TaskStatus.RUNNING, Stage.QUEUED, 1, "preflight")
        if (!ffmpeg.isAvailable()) throw Exception("ffmpeg binary is not available on this device")
        val stat = android.os.StatFs(dir.absolutePath)
        val freeMb = stat.availableBytes / (1024 * 1024)
        if (freeMb < 250) throw Exception("insufficient storage: ${freeMb}MB free, at least 250MB required")
        if (config.executionMode == Mode.LOCAL && config.voiceName.isBlank() && config.customAudioFile == null) {
            throw Exception("no voice configured — select a voice or provide custom audio")
        }

        // ---------- 1. SCRIPT ----------
        if (config.videoScript.isBlank()) {
            update(task.id, TaskStatus.RUNNING, Stage.SCRIPT, 5, "generating script")
            checkCancel()
            val provider = prefs.providerFor(prefs.settingsNow(), "script")
                ?: throw Exception("no LLM provider configured — add one in Settings → Providers")
            val llm = LlmService(http, json)
            config.videoScript = llm.generateScript(
                prefs.resolve(provider), config.videoSubject, config.videoLanguage,
                config.paragraphNumber, config.videoScriptPrompt, config.customSystemPrompt,
                prefs.settingsNow(),
            )
            update(task.id, TaskStatus.RUNNING, Stage.SCRIPT, 10, "script generated (${config.videoScript.length} chars)")
        }

        // ---------- 2. TERMS ----------
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
        val materials: List<MaterialInfo> = if (config.videoMaterials.isNotEmpty()) {
            update(task.id, TaskStatus.RUNNING, Stage.MATERIALS, 25, "using ${config.videoMaterials.size} pre-selected materials")
            config.videoMaterials
        } else {
            update(task.id, TaskStatus.RUNNING, Stage.MATERIALS, 20, "searching stock media")
            checkCancel()
            val keys = prefs.stockKeys()
            val stock = StockMediaClient(http)
            val results = mutableListOf<MaterialInfo>()
            val perTerm = ((40 - 20) / config.videoTerms.size.coerceAtLeast(1))
            var p = 20
            for ((i, term) in config.videoTerms.withIndex()) {
                checkCancel()
                try {
                    val vids = stock.search(config.videoSource.vValue, keyFor(config.videoSource, keys), term, config.videoAspect.value, 5)
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
        update(task.id, TaskStatus.RUNNING, Stage.AUDIO, 40, "generating voiceover")
        checkCancel()
        val audioFile: File?
        var words: List<com.moneyprinterturbo.android.core.media.WordBoundary> = emptyList()
        if (config.customAudioFile != null) {
            audioFile = File(config.customAudioFile!!)
            if (!audioFile.exists()) throw Exception("custom audio file missing: ${config.customAudioFile}")
        } else {
            val tts = TtsService(http)
            val voiceOut = File(dir, "audio.mp3")
            val r = tts.synthesize(config.voiceName, config.videoScript, config.voiceRate, config.voiceVolume, voiceOut)
            audioFile = r.file
            words = r.words
        }
        val audioDuration = composer.probeDuration(audioFile) ?: 30.0
        update(task.id, TaskStatus.RUNNING, Stage.AUDIO, 50, "voiceover ready (${audioDuration.toInt()}s)")

        // ---------- 5. SUBTITLE ----------
        var srtFile: File? = null
        if (config.subtitleEnabled) {
            update(task.id, TaskStatus.RUNNING, Stage.SUBTITLE, 50, "building subtitles from TTS word boundaries")
            checkCancel()
            val cues = when {
                words.isNotEmpty() && config.subtitleDisplayMode == SubtitleDisplayMode.WORD_BY_WORD ->
                    SubtitleBuilder.wordByWord(words)
                words.isNotEmpty() -> SubtitleBuilder.sentences(words)
                else -> SubtitleBuilder.grouped(emptyList()) // custom audio: no boundaries
            }
            if (cues.isEmpty()) {
                // Equal-segment estimation for custom audio (documented fallback)
                val segments = config.videoScript.split(Regex("[.。!！?？\n]+")).filter { it.isNotBlank() }
                val segDur = audioDuration / segments.size.coerceAtLeast(1)
                var t = 0.0
                val est = segments.map { seg ->
                    val c = Cue((t * 1000).toLong(), ((t + segDur) * 1000).toLong(), seg.trim())
                    t += segDur; c
                }
                srtFile = File(dir, "subtitle.srt").also { it.writeText(Srt.write(est)) }
            } else {
                srtFile = File(dir, "subtitle.srt").also { it.writeText(Srt.write(cues)) }
            }
            update(task.id, TaskStatus.RUNNING, Stage.SUBTITLE, 60, "subtitle cues: ${cues.size}")
        }

        // ---------- 6. COMBINE ----------
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
                local = File(dir, "material_$i.${if (isImage) "jpg" else "mp4"}")
                update(task.id, TaskStatus.RUNNING, Stage.COMBINE, 60 + (i * 15 / sceneCount), "downloading material ${i + 1}/$sceneCount")
                downloadClient.newCall(
                    okhttp3.Request.Builder().url(material.url).build()
                ).execute().use { resp ->
                    if (!resp.isSuccessful) throw Exception("material download failed: HTTP ${resp.code}")
                    resp.body!!.byteStream().use { input -> local.outputStream().use { input.copyTo(it) } }
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
        current = composer.muxAudio(current, if (config.customAudioFile == null) audioFile else null, bgmFile(context, config), config.bgmVolume)

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
            current = composer.burnSubtitles(current, srtFile, FontManager.fontsDir(context), SubtitleStyle.forceStyle(style))
        }

        update(task.id, TaskStatus.RUNNING, Stage.COMBINE, 98, "finalizing")
        val final = File(dir, "final.mp4")
        current.renameTo(final)
        update(task.id, TaskStatus.RUNNING, Stage.DONE, 99, "video ready: ${final.name}")
        return final
    }

    private fun pickMaterial(list: List<MaterialInfo>, index: Int, mode: ConcatMode): MaterialInfo {
        val src = list.filter { m -> m.url.isNotBlank() || m.localPath != null }
        if (src.isEmpty()) throw Exception("no usable materials")
        return if (mode == ConcatMode.RANDOM) src.random() else src[index % src.size]
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
