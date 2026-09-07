package com.moneyprinterturbo.android.ui.screens

import androidx.compose.foundation.layout.*
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
import com.moneyprinterturbo.android.core.model.AppSettings
import com.moneyprinterturbo.android.core.model.Mode
import com.moneyprinterturbo.android.core.model.TtsProvider
import com.moneyprinterturbo.android.core.logging.AppLogger
import com.moneyprinterturbo.android.ui.components.*
import kotlinx.coroutines.launch

/** Settings: execution mode, network, storage info, advanced. */
@Composable
fun SettingsScreen(nav: NavController) {
    val app = LocalContext.current.applicationContext as MptApplication
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(AppSettings()) }
    var remoteToken by remember { mutableStateOf("") }
    var ttsApiKey by remember { mutableStateOf("") }
    var whisperApiKey by remember { mutableStateOf("") }
    var soniloApiKey by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        settings = app.prefs.settingsNow()
        remoteToken = app.prefs.remoteApiTokenNow()
        ttsApiKey = app.prefs.ttsApiKeyNow()
        whisperApiKey = app.prefs.whisperApiKeyNow()
        soniloApiKey = app.prefs.soniloApiKeyNow()
    }

    fun save(s: AppSettings) {
        settings = s
        scope.launch { app.prefs.updateSettings(s) }
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.nav_settings), style = MaterialTheme.typography.headlineSmall)

        SectionCard(stringResource(R.string.section_ai)) {
            Button(onClick = { nav.navigate("providers") }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.providers))
            }
        }

        SectionCard(stringResource(R.string.section_execution)) {
            DropdownField(
                stringResource(R.string.mode),
                listOf("local", "remote", "cloud_api"), settings.mode.name.lowercase(),
            ) { save(settings.copy(mode = Mode.valueOf(it.uppercase()))) }
            if (settings.mode == Mode.REMOTE) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextFieldMpt(settings.remoteBackendUrl, { save(settings.copy(remoteBackendUrl = it)) },
                    stringResource(R.string.backend_url), supporting = stringResource(R.string.backend_hint))
                OutlinedTextFieldMpt(remoteToken, {
                    remoteToken = it
                    scope.launch { app.prefs.saveRemoteApiToken(it) }
                }, stringResource(R.string.api_token), supporting = "Stored encrypted in Android Keystore")
            }
        }

        SectionCard(stringResource(R.string.section_network)) {
            NumberField(stringResource(R.string.timeout_sec), settings.networkTimeoutSec) {
                save(settings.copy(networkTimeoutSec = it.coerceIn(10, 600)))
            }
        }

        SectionCard("Voice synthesis (TTS)") {
            DropdownField(
                "Provider",
                listOf("edge", "openai_compatible"),
                settings.ttsProvider.name.lowercase(),
            ) { value ->
                save(settings.copy(ttsProvider = TtsProvider.valueOf(value.uppercase())))
            }
            if (settings.ttsProvider == TtsProvider.OPENAI_COMPATIBLE) {
                Spacer(Modifier.height(6.dp))
                OutlinedTextFieldMpt(
                    settings.ttsBaseUrl,
                    { save(settings.copy(ttsBaseUrl = it)) },
                    "TTS endpoint",
                    supporting = "Use a provider exposing the OpenAI-compatible /audio/speech API, or enter the full /tts endpoint.",
                )
                OutlinedTextFieldMpt(
                    settings.ttsModel,
                    { save(settings.copy(ttsModel = it)) },
                    "TTS model",
                )
                OutlinedTextFieldMpt(
                    ttsApiKey,
                    {
                        ttsApiKey = it
                        scope.launch { app.prefs.saveTtsApiKey(it) }
                    },
                    "TTS API key",
                    supporting = "Stored encrypted in Android Keystore",
                )
            } else {
                Text(
                    "Edge TTS is the default and provides word timing data for subtitles.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        SectionCard("Sonilo background music") {
            OutlinedTextFieldMpt(
                settings.soniloBaseUrl,
                { save(settings.copy(soniloBaseUrl = it)) },
                "Sonilo endpoint",
                supporting = "Default: https://api.sonilo.com",
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextFieldMpt(
                soniloApiKey,
                { soniloApiKey = it; scope.launch { app.prefs.saveSoniloApiKey(it) } },
                "Sonilo API key",
                supporting = "Stored encrypted in Android Keystore. Used only when BGM = Sonilo.",
            )
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = {
                scope.launch {
                    try {
                        com.moneyprinterturbo.android.core.net.SoniloClient(app.applicationContext, app.prefs).testConnection()
                        android.widget.Toast.makeText(app, "Sonilo connection OK", android.widget.Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        AppLogger.exception(app, "SONILO_TEST", "connection test failed", e)
                        android.widget.Toast.makeText(app, "Sonilo: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Test Sonilo connection") }
        }

        SectionCard("Subtitle engine") {
            DropdownField(
                "Provider",
                listOf("edge", "whisper_openai_compatible"),
                settings.subtitleProvider.name.lowercase(),
            ) { value ->
                save(settings.copy(subtitleProvider = com.moneyprinterturbo.android.core.model.SubtitleProvider.valueOf(value.uppercase())))
            }
            if (settings.subtitleProvider == com.moneyprinterturbo.android.core.model.SubtitleProvider.WHISPER_OPENAI_COMPATIBLE) {
                Spacer(Modifier.height(6.dp))
                OutlinedTextFieldMpt(
                    settings.whisperBaseUrl,
                    { save(settings.copy(whisperBaseUrl = it)) },
                    "Whisper endpoint",
                    supporting = "OpenAI-compatible /audio/transcriptions endpoint or a /v1 base URL.",
                )
                OutlinedTextFieldMpt(
                    settings.whisperModel,
                    { save(settings.copy(whisperModel = it)) },
                    "Whisper model",
                )
                OutlinedTextFieldMpt(
                    whisperApiKey,
                    { whisperApiKey = it; scope.launch { app.prefs.saveWhisperApiKey(it) } },
                    "Whisper API key",
                    supporting = "Stored encrypted in Android Keystore. The Android build does not bundle the desktop faster-whisper model.",
                )
            } else {
                Text(
                    "Edge mode uses TTS word timings and needs no transcription model.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        SectionCard(stringResource(R.string.section_storage)) {
            val dir = app.getExternalFilesDir(null)
            Text(stringResource(R.string.output_dir) + ": " + (dir?.absolutePath ?: "-"), style = MaterialTheme.typography.bodySmall)
            dir?.let { d ->
                val stat = android.os.StatFs(d.absolutePath)
                Text(
                    stringResource(R.string.free_space) + ": " + (stat.availableBytes / (1024 * 1024)) + " MB",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val ff = remember {
                com.moneyprinterturbo.android.core.media.FfmpegExecutor(app.applicationContext)
            }
            Text(
                "FFmpeg: " + if (ff.isAvailable()) "OK (" + ff.binary.length() / (1024 * 1024) + " MB)" else "missing — " + ff.diagnose(),
                style = MaterialTheme.typography.bodySmall,
                color = if (ff.isAvailable()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }

        SectionCard(stringResource(R.string.section_advanced)) {
            LabeledSwitch(stringResource(R.string.debug_mode), settings.debugMode) {
                save(settings.copy(debugMode = it))
                AppLogger.setEnabled(it)
                AppLogger.log(app, "SETTINGS", "diagnostic logging enabled=$it")
            }
            Text(
                stringResource(R.string.logging_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = {
                    scope.launch {
                        val file = AppLogger.export(app)
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            app, app.packageName + ".fileprovider", file
                        )
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        app.startActivity(android.content.Intent.createChooser(intent, "Export diagnostic log"))
                    }
                }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.export_logs)) }
                OutlinedButton(onClick = { AppLogger.clear(app) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.clear_logs))
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                scope.launch {
                    app.cacheDir.deleteRecursively()
                    AppLogger.log(app, "SETTINGS", "cache cleared")
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.clear_cache)) }
        }

        Text("MoneyPrinterTurbo Android 1.0.0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        Text("Upstream: github.com/harry0703/MoneyPrinterTurbo", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}
