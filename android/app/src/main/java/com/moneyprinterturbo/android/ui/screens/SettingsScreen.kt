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
import com.moneyprinterturbo.android.ui.components.*
import kotlinx.coroutines.launch

/** Settings: execution mode, network, storage info, advanced. */
@Composable
fun SettingsScreen(nav: NavController) {
    val app = LocalContext.current.applicationContext as MptApplication
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(AppSettings()) }
    LaunchedEffect(Unit) { settings = app.prefs.settingsNow() }

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
                OutlinedTextFieldMpt(settings.remoteApiToken, { save(settings.copy(remoteApiToken = it)) },
                    stringResource(R.string.api_token))
            }
        }

        SectionCard(stringResource(R.string.section_network)) {
            NumberField(stringResource(R.string.timeout_sec), settings.networkTimeoutSec) {
                save(settings.copy(networkTimeoutSec = it.coerceIn(10, 600)))
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
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                scope.launch {
                    app.cacheDir.deleteRecursively()
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.clear_cache)) }
        }

        Text("MoneyPrinterTurbo Android 1.0.0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        Text("Upstream: github.com/harry0703/MoneyPrinterTurbo", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}
