package com.moneyprinterturbo.android.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.moneyprinterturbo.android.MptApplication
import com.moneyprinterturbo.android.R
import com.moneyprinterturbo.android.core.model.TaskStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import com.moneyprinterturbo.android.ui.components.*

/** Task detail: live progress, stage, selectable logs w/ copy+share, error + retry/cancel. */
@Composable
fun TaskScreen(nav: NavController, id: String) {
    val app = LocalContext.current.applicationContext as MptApplication
    val scope = rememberCoroutineScope()
    var task by remember { mutableStateOf<com.moneyprinterturbo.android.core.db.TaskEntity?>(null) }
    LaunchedEffect(id) {
        while (true) {
            val latest = app.repository.task(id)
            task = latest
            if (latest == null || latest.status !in setOf(TaskStatus.QUEUED.code, TaskStatus.RUNNING.code)) break
            kotlinx.coroutines.delay(1000)
        }
    }
    val t = task ?: return
    val clipboard = LocalClipboardManager.current

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(t.subject.ifBlank { t.id.take(8) }, style = MaterialTheme.typography.titleLarge)
        StatusChip(t.status, t.progress)
        if (t.status == TaskStatus.STOPPED_AT.code) {
            // Show which artifacts are available for inspection/editing.
            val cfg = remember(t.configJson) { runCatching {
                com.moneyprinterturbo.android.core.db.DbJson.configFromString(t.configJson) }.getOrNull() }
            Text(
                buildString {
                    append("Stopped at stage: ${t.stage}. ")
                    if (cfg?.videoScript?.isNotBlank() == true) append("Script: ${cfg.videoScript.length} chars. ")
                    if (!cfg?.videoTerms.isNullOrEmpty()) append("Terms: ${cfg.videoTerms.size}. ")
                    if (!cfg?.videoMaterials.isNullOrEmpty()) append("Materials: ${cfg.videoMaterials.size}. ")
                    append("Edit them in the project, then press Continue.")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        LinearProgressIndicator(progress = { t.progress / 100f }, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.stage) + ": " + t.stage, style = MaterialTheme.typography.bodyMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (t.status == TaskStatus.RUNNING.code) {
                Button(onClick = { scope.launch { app.repository.cancelTask(t.id) } }) {
                    Text(stringResource(R.string.cancel))
                }
            }
            if (t.status == TaskStatus.FAILED.code) {
                Button(onClick = {
                    scope.launch { app.repository.retryTask(t.id) }
                }) { Text(stringResource(R.string.retry)) }
            }
            if (t.status == TaskStatus.STOPPED_AT.code) {
                // P2.2: pipeline stopped at an intermediate stage — continue to finish.
                Button(onClick = {
                    scope.launch {
                        runCatching { app.repository.continueStoppedTask(t.id) }
                            .onFailure { Toast.makeText(app, it.message ?: "error", Toast.LENGTH_LONG).show() }
                    }
                }) { Text(stringResource(R.string.continue_task)) }
            }
            if (t.videoPath != null) {
                Button(onClick = {
                    val file = java.io.File(t.videoPath!!)
                    if (!file.exists()) {
                        Toast.makeText(app, app.getString(R.string.file_missing), Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                        .setDataAndType(
                            androidx.core.content.FileProvider.getUriForFile(
                                app, app.packageName + ".fileprovider", file,
                            ),
                            "video/mp4",
                        )
                        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        app.startActivity(android.content.Intent.createChooser(intent, "Play video"))
                    } catch (e: Exception) {
                        Toast.makeText(app, app.getString(R.string.no_player_app), Toast.LENGTH_LONG).show()
                    }
                }) { Text(stringResource(R.string.play)) }
                // Real download: stream-copy the MP4 into the public Movies dir via
                // MediaStore so it shows in Gallery — no external player involved.
                Button(onClick = {
                    scope.launch {
                        try {
                            val path = saveToGallery(app, java.io.File(t.videoPath!!))
                            Toast.makeText(app, app.getString(R.string.saved_to, path), Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(app, app.getString(R.string.save_failed, e.message), Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text(stringResource(R.string.download)) }
            }
        }

        val outputPaths = remember(t.outputPathsJson) {
            try { Json.decodeFromString(ListSerializer(String.serializer()), t.outputPathsJson) } catch (_: Exception) {
                listOfNotNull(t.videoPath)
            }
        }.distinct().filter { it.isNotBlank() }
        if (outputPaths.isNotEmpty()) {
            SectionCard("Generated videos (${outputPaths.size})") {
                outputPaths.forEachIndexed { index, path ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Video ${index + 1}", Modifier.weight(1f))
                        TextButton(onClick = {
                            val file = java.io.File(path)
                            if (!file.exists()) {
                                Toast.makeText(app, app.getString(R.string.file_missing), Toast.LENGTH_SHORT).show()
                                return@TextButton
                            }
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                                .setDataAndType(
                                    androidx.core.content.FileProvider.getUriForFile(app, app.packageName + ".fileprovider", file),
                                    "video/mp4",
                                ).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            try {
                                app.startActivity(android.content.Intent.createChooser(intent, "Play video"))
                            } catch (e: Exception) {
                                Toast.makeText(app, app.getString(R.string.no_player_app), Toast.LENGTH_LONG).show()
                            }
                        }) { Text("Play") }
                        // Real download to public Movies dir (MediaStore), visible in Gallery.
                        TextButton(onClick = {
                            scope.launch {
                                try {
                                    val out = saveToGallery(app, java.io.File(path))
                                    Toast.makeText(app, app.getString(R.string.saved_to, out), Toast.LENGTH_LONG).show()
                                } catch (e: Exception) {
                                    Toast.makeText(app, app.getString(R.string.save_failed, e.message), Toast.LENGTH_LONG).show()
                                }
                            }
                        }) { Text(stringResource(R.string.download)) }
                    }
                }
            }
        }

        t.error?.let { ErrorBanner(it) }

        SectionCard(stringResource(R.string.logs)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(t.log))
                    Toast.makeText(app, R.string.copied, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.copy)) }
                TextButton(onClick = {
                    val shareTitle = app.getString(R.string.share)
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_TEXT, t.log)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        app.startActivity(android.content.Intent.createChooser(send, shareTitle))
                    } catch (e: Exception) {
                        clipboard.setText(AnnotatedString(t.log))
                        Toast.makeText(app, app.getString(R.string.no_share_target), Toast.LENGTH_LONG).show()
                    }
                }) { Text(stringResource(R.string.share)) }
                // Real download of the task log to Downloads via MediaStore.
                TextButton(onClick = {
                    scope.launch {
                        try {
                            val out = saveLogToDownloads(app, t.log, "mpt-task-${t.id.take(8)}.txt")
                            Toast.makeText(app, app.getString(R.string.saved_to, out), Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(app, app.getString(R.string.save_failed, e.message), Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text(stringResource(R.string.download)) }
            }
            // Selectable long-press text — user can select ranges manually too.
            SelectionContainer {
                Text(
                    t.log.ifBlank { stringResource(R.string.no_logs) },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * Copy a rendered video into the PUBLIC Movies folder via MediaStore — the file then
 * appears in Gallery / any file manager. App needs no storage permission for its own
 * contributions on API 29+. Returns the display path shown to the user.
 */
private suspend fun saveToGallery(context: android.content.Context, src: java.io.File): String =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (!src.exists() || src.length() == 0L) throw java.io.IOException("source video is empty")
        val name = "MoneyPrinterTurbo_${System.currentTimeMillis()}.mp4"
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_MOVIES + "/MoneyPrinterTurbo")
            put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(
            android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values,
        ) ?: throw java.io.IOException("MediaStore rejected the insert")
        try {
            resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                ?: throw java.io.IOException("cannot open output stream")
            values.clear()
            values.put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        "Movies/MoneyPrinterTurbo/$name"
    }

/** Save the task log into the public Downloads folder. Returns the display path. */
suspend fun saveLogToDownloads(context: android.content.Context, log: String, name: String): String =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw java.io.IOException("MediaStore rejected the insert")
        try {
            resolver.openOutputStream(uri)?.use { out -> out.write(log.toByteArray(Charsets.UTF_8)) }
                ?: throw java.io.IOException("cannot open output stream")
            values.clear()
            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        "Download/$name"
    }
