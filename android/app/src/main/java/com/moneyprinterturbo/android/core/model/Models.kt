package com.moneyprinterturbo.android.core.model

import kotlinx.serialization.Serializable

/** Mirrors upstream app/models/schema.py VideoParams — the functional source of truth. */
@Serializable
data class TaskConfig(
    val videoSubject: String = "",
    var videoScript: String = "",
    var videoTerms: List<String> = emptyList(),
    val videoAspect: VideoAspect = VideoAspect.PORTRAIT,
    val videoFitMode: FitMode = FitMode.COVER,
    val videoConcatMode: ConcatMode = ConcatMode.RANDOM,
    val videoTransition: Transition = Transition.NONE,
    val videoClipDuration: Int = 5,
    val videoClipSpeed: Float = 1.0f,
    val matchMaterialsToScript: Boolean = false,
    val videoCount: Int = 1,
    val videoSource: VideoSource = VideoSource.PEXELS,
    var videoMaterials: List<MaterialInfo> = emptyList(),
    var customAudioFile: String? = null,
    val videoLanguage: String = "",
    val voiceName: String = "",
    val voiceVolume: Float = 1.0f,
    val voiceRate: Float = 1.0f,
    val bgmType: BgmType = BgmType.RANDOM,
    val bgmFile: String = "",
    val bgmVolume: Float = 0.2f,
    val subtitleEnabled: Boolean = true,
    val subtitlePosition: SubtitlePosition = SubtitlePosition.BOTTOM,
    val subtitleDisplayMode: SubtitleDisplayMode = SubtitleDisplayMode.SENTENCE,
    val customPosition: Float = 70.0f,
    val fontName: String = "",
    val textForeColor: String = "#FFFFFF",
    val textBackgroundColor: String = "",
    val fontSize: Int = 60,
    val strokeColor: String = "#000000",
    val strokeWidth: Float = 1.5f,
    val roundedSubtitleBackground: Boolean = false,
    val paragraphNumber: Int = 1,
    val nThreads: Int = 2,
    val soniloBgmPrompt: String = "",
    val videoScriptPrompt: String = "",
    val customSystemPrompt: String = "",
    // Execution mode for this task
    val executionMode: Mode = Mode.LOCAL,
    // P2.2 stop_at (upstream parity): pipeline stops after this stage and marks the
    // task STOPPED_AT so the user can inspect/edit the intermediate output. "video"
    // means run everything (default).
    val stopAt: StopAt = StopAt.VIDEO,
)

/** Upstream _run_pipeline stop points. Order matters: script→terms→audio→subtitle→materials→video. */
@Serializable
enum class StopAt(val vValue: String) {
    VIDEO("video"), SCRIPT("script"), TERMS("terms"), AUDIO("audio"), SUBTITLE("subtitle"), MATERIALS("materials");
    companion object { fun from(v: String?) = entries.firstOrNull { it.vValue == v } ?: VIDEO }
}

@Serializable
data class MaterialInfo(
    val provider: String = "pexels",
    val url: String = "",
    val duration: Int = 0,
    val localPath: String? = null,
    val creator: String? = null,
    val creatorUrl: String? = null,
)

enum class Mode { LOCAL, REMOTE, CLOUD_API }

enum class VideoAspect(val value: String, val width: Int, val height: Int) {
    LANDSCAPE("16:9", 1920, 1080),
    PORTRAIT("9:16", 1080, 1920),
    SQUARE("1:1", 1080, 1080);

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.value == value } ?: PORTRAIT
    }
}

enum class FitMode { COVER, CONTAIN }
enum class ConcatMode { RANDOM, SEQUENTIAL }
enum class Transition { NONE, FADE, SLIDE, ZOOM }
enum class SubtitlePosition(val vValue: String) {
    TOP("top"), BOTTOM("bottom"), CENTER("center"), CUSTOM("custom"), TWO_THIRDS_BOTTOM("two_thirds_bottom");
    companion object { fun from(v: String?) = entries.firstOrNull { it.vValue == v } ?: BOTTOM }
}
enum class SubtitleDisplayMode(val vValue: String) {
    SENTENCE("sentence"), WORD_BY_WORD("word_by_word");
    companion object { fun from(v: String?) = entries.firstOrNull { it.vValue == v } ?: SENTENCE }
}
enum class VideoSource(val vValue: String) {
    PEXELS("pexels"), PIXABAY("pixabay"), COVERR("coverr"), LOCAL("local"), OPENAI_IMAGE("openai_image");
    companion object { fun from(v: String?) = entries.firstOrNull { it.vValue == v } ?: PEXELS }
}
enum class BgmType(val vValue: String) {
    NONE("none"), RANDOM("random"), PRESET("preset"), CUSTOM("custom"), SONILO("sonilo");
    companion object { fun from(v: String?) = entries.firstOrNull { it.vValue == v } ?: NONE }
}

/** Task state machine — parity with upstream const.py: -1 failed / 1 complete / 4 processing. */
enum class TaskStatus(val code: Int) {
    QUEUED(0), RUNNING(4), COMPLETED(1), FAILED(-1), CANCELLED(-2),
    // P2.2: pipeline stopped at an intermediate stop_at stage; resumable via "Continue".
    STOPPED_AT(2);

    companion object { fun fromCode(code: Int) = entries.firstOrNull { it.code == code } ?: QUEUED }
}

enum class Stage { QUEUED, SCRIPT, TERMS, MATERIALS, AUDIO, SUBTITLE, COMBINE, DONE }

/** An LLM provider configured by the user (OpenAI-compatible unless baseUrl ends with gemini). */
@Serializable
data class LlmProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String = "",
    val model: String = "",
    val temperature: Double = 0.7,
    val maxTokens: Int = 2048,
    val kind: LlmKind = LlmKind.OPENAI_COMPATIBLE,
    val enabled: Boolean = true,
    val isDefault: Boolean = false,
    // P2.3 adapter extras (upstream provider extra_fields parity):
    // Azure: apiVersion (deployment = model). Cloudflare: accountId + gatewayId.
    // CUSTOM_HTTP: a fully user-defined request template for ANY API:
    //   placeholders {{URL}}, {{MODEL}}, {{SYSTEM}}, {{USER}}, {{API_KEY}};
    //   response is read via responsePath (dot path, e.g. "data[0].content" / "output.text").
    val apiVersion: String = "",
    val accountId: String = "",
    val gatewayId: String = "",
    val customMethod: String = "POST",
    val customHeaders: String = "",   // one per line: "Header-Name: value"
    val customBodyTemplate: String = "",
    val customUrlTemplate: String = "",
    val customResponsePath: String = "",
)

enum class LlmKind { OPENAI_COMPATIBLE, GEMINI, QWEN_DASHSCOPE, AZURE_OPENAI, CLOUDFLARE_GATEWAY, CUSTOM_HTTP }

enum class TtsProvider { EDGE, OPENAI_COMPATIBLE }
enum class SubtitleProvider { EDGE, WHISPER_OPENAI_COMPATIBLE }

/** Stock media provider keys. */
@Serializable
data class StockKeys(
    val pexelsApiKey: String = "",
    val pixabayApiKey: String = "",
    val coverrApiKey: String = "",
)

/** Global app settings (DataStore-backed). */
@Serializable
data class AppSettings(
    val mode: Mode = Mode.LOCAL,
    val remoteBackendUrl: String = "",
    val remoteApiToken: String = "",
    // Per-function model assignment (Model Manager)
    val scriptProviderId: String = "",
    val scriptModel: String = "",
    val termsProviderId: String = "",
    val termsModel: String = "",
    val defaultVoice: String = "",
    val ttsProvider: TtsProvider = TtsProvider.EDGE,
    val ttsModel: String = "tts-1",
    val ttsBaseUrl: String = "https://tts.dptech.fun/v1/tts",
    val soniloBaseUrl: String = "https://api.sonilo.com",
    val subtitleProvider: SubtitleProvider = SubtitleProvider.EDGE,
    val whisperBaseUrl: String = "",
    val whisperModel: String = "whisper-1",
    val defaultBgmVolume: Float = 0.2f,
    val defaultFontSize: Int = 60,
    val defaultFont: String = "",
    val networkTimeoutSec: Int = 120,
    val debugMode: Boolean = false,
)
