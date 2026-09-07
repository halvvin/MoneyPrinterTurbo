package com.moneyprinterturbo.android.ui.screens

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.moneyprinterturbo.android.MptApplication
import com.moneyprinterturbo.android.R
import com.moneyprinterturbo.android.core.db.DbJson
import com.moneyprinterturbo.android.core.model.*
import com.moneyprinterturbo.android.ui.components.*
import com.moneyprinterturbo.android.ui.navigation.Routes

/** Project detail/editor. Project configuration is persisted in Room, not merely displayed. */
@Composable
fun ProjectScreen(nav: NavController, id: String) {
    val app = LocalContext.current.applicationContext as MptApplication
    val scope = rememberCoroutineScope()
    var project by remember { mutableStateOf<com.moneyprinterturbo.android.core.db.ProjectEntity?>(null) }
    var tasks by remember { mutableStateOf(listOf<com.moneyprinterturbo.android.core.db.TaskEntity>()) }
    var editName by remember { mutableStateOf(false) }
    var editConfig by remember { mutableStateOf(false) }

    suspend fun reload() {
        project = app.repository.project(id)
        tasks = app.repository.projectTasks(id)
    }
    LaunchedEffect(id) { reload() }

    val p = project ?: return
    val config = remember(p.configJson) { DbJson.configFromString(p.configJson) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(p.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { editName = true }) { Icon(Icons.Filled.Edit, stringResource(R.string.rename)) }
            IconButton(onClick = { editConfig = true }) { Icon(Icons.Filled.Tune, "Edit project settings") }
            IconButton(onClick = { scope.launch { app.repository.duplicateProject(id) } }) {
                Icon(Icons.Filled.ContentCopy, stringResource(R.string.duplicate))
            }
            IconButton(onClick = {
                scope.launch { app.repository.deleteProject(id) }
                nav.popBackStack()
            }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
        }
        if (editName) {
            var name by remember { mutableStateOf(p.name) }
            OutlinedTextFieldMpt(name, { name = it }, stringResource(R.string.project_name))
            Button(onClick = {
                editName = false
                scope.launch { app.repository.renameProject(id, name); reload() }
            }) { Text(stringResource(R.string.save)) }
        }
        SectionCard("Project configuration") {
            Text("Topic: ${config.videoSubject.ifBlank { "(none)" }}")
            Text("Aspect: ${config.videoAspect.value}  •  Clips: ${config.videoCount}")
            Text("Source: ${config.videoSource.vValue}  •  Clip duration: ${config.videoClipDuration}s")
            Text("Voice: ${if (config.voiceName.isBlank()) "default / none" else config.voiceName}")
            Text("Subtitles: ${if (config.subtitleEnabled) "on" else "off"}  •  BGM: ${config.bgmType.vValue}")
            Spacer(Modifier.height(4.dp))
            Text(config.videoScript.ifBlank { "No script saved yet." }, style = MaterialTheme.typography.bodyMedium)
        }
        Button(onClick = { editConfig = true }, modifier = Modifier.fillMaxWidth()) { Text("Edit project settings") }
        Button(onClick = { scope.launch { app.repository.queueTask(p) } }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.rerun_generate))
        }
        Text(stringResource(R.string.tasks), style = MaterialTheme.typography.titleMedium)
        LazyColumn(Modifier.weight(1f)) {
            items(tasks) { t ->
                ListItem(
                    headlineContent = { Text(t.stage + " · " + t.progress + "%") },
                    supportingContent = {
                        Column {
                            StatusChip(t.status, t.progress)
                            t.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        }
                    },
                    trailingContent = {
                        if (t.status == TaskStatus.RUNNING.code) {
                            TextButton(onClick = { scope.launch { app.repository.cancelTask(t.id); reload() } }) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    },
                    modifier = Modifier.clickable { nav.navigate(Routes.task(t.id)) },
                )
            }
        }
    }

    if (editConfig) {
        ProjectConfigEditor(
            initial = config,
            onDismiss = { editConfig = false },
            onSave = { edited ->
                editConfig = false
                scope.launch { app.repository.updateProjectConfig(id, edited); reload() }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectConfigEditor(
    initial: TaskConfig,
    onDismiss: () -> Unit,
    onSave: (TaskConfig) -> Unit,
) {
    var subject by remember(initial) { mutableStateOf(initial.videoSubject) }
    var script by remember(initial) { mutableStateOf(initial.videoScript) }
    var aspect by remember(initial) { mutableStateOf(initial.videoAspect) }
    var source by remember(initial) { mutableStateOf(initial.videoSource) }
    var count by remember(initial) { mutableStateOf(initial.videoCount.toString()) }
    var clipDuration by remember(initial) { mutableStateOf(initial.videoClipDuration.toString()) }
    var speed by remember(initial) { mutableStateOf(initial.videoClipSpeed.toString()) }
    var subtitles by remember(initial) { mutableStateOf(initial.subtitleEnabled) }
    var bgm by remember(initial) { mutableStateOf(initial.bgmType) }
    var expandedAspect by remember { mutableStateOf(false) }
    var expandedSource by remember { mutableStateOf(false) }
    var expandedBgm by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit project") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(subject, { subject = it }, label = { Text("Topic") }, singleLine = true)
                OutlinedTextField(script, { script = it }, label = { Text("Script") }, minLines = 4, maxLines = 8)
                ExposedDropdownMenuBox(expanded = expandedAspect, onExpandedChange = { expandedAspect = !expandedAspect }) {
                    OutlinedTextField(aspect.value, {}, label = { Text("Aspect") }, readOnly = true, modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(expanded = expandedAspect, onDismissRequest = { expandedAspect = false }) {
                        VideoAspect.entries.forEach { item -> DropdownMenuItem(text = { Text(item.value) }, onClick = { aspect = item; expandedAspect = false }) }
                    }
                }
                ExposedDropdownMenuBox(expanded = expandedSource, onExpandedChange = { expandedSource = !expandedSource }) {
                    OutlinedTextField(source.vValue, {}, label = { Text("Material source") }, readOnly = true, modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(expanded = expandedSource, onDismissRequest = { expandedSource = false }) {
                        VideoSource.entries.filter { it != VideoSource.OPENAI_IMAGE }.forEach { item ->
                            DropdownMenuItem(text = { Text(item.vValue) }, onClick = { source = item; expandedSource = false })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(count, { count = it.filter(Char::isDigit).take(2) }, label = { Text("Outputs") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(clipDuration, { clipDuration = it.filter(Char::isDigit).take(3) }, label = { Text("Clip seconds") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(speed, { speed = it.filter { c -> c.isDigit() || c == '.' }.take(4) }, label = { Text("Clip speed") }, singleLine = true)
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(subtitles, { subtitles = it })
                    Text("Subtitles")
                }
                ExposedDropdownMenuBox(expanded = expandedBgm, onExpandedChange = { expandedBgm = !expandedBgm }) {
                    OutlinedTextField(bgm.vValue, {}, label = { Text("Background music") }, readOnly = true, modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(expanded = expandedBgm, onDismissRequest = { expandedBgm = false }) {
                        BgmType.entries.forEach { item -> DropdownMenuItem(text = { Text(item.vValue) }, onClick = { bgm = item; expandedBgm = false }) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(initial.copy(
                    videoSubject = subject,
                    videoScript = script,
                    videoAspect = aspect,
                    videoSource = source,
                    videoCount = count.toIntOrNull()?.coerceIn(1, 10) ?: initial.videoCount,
                    videoClipDuration = clipDuration.toIntOrNull()?.coerceIn(1, 120) ?: initial.videoClipDuration,
                    videoClipSpeed = speed.toFloatOrNull()?.coerceIn(0.25f, 4f) ?: initial.videoClipSpeed,
                    subtitleEnabled = subtitles,
                    bgmType = bgm,
                ))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** History: completed tasks with output preview + export. */
@Composable
fun HistoryScreen(nav: NavController) {
    val app = LocalContext.current.applicationContext as MptApplication
    var tasks by remember { mutableStateOf(listOf<com.moneyprinterturbo.android.core.db.TaskEntity>()) }
    LaunchedEffect(Unit) { tasks = app.repository.tasks() }
    val done = tasks.filter { it.status == TaskStatus.COMPLETED.code }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(stringResource(R.string.nav_history), style = MaterialTheme.typography.headlineSmall)
        if (done.isEmpty()) {
            EmptyState(stringResource(R.string.no_completed))
        } else {
            LazyColumn {
                items(done) { t ->
                    ListItem(
                        headlineContent = { Text(t.subject.ifBlank { t.id.take(8) }) },
                        supportingContent = { Text(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date(t.createdAt))) },
                        trailingContent = {
                            t.videoPath?.let { path ->
                                TextButton(onClick = {
                                    val intent = Intent(android.content.Intent.ACTION_VIEW)
                                        .setDataAndType(
                                            androidx.core.content.FileProvider.getUriForFile(
                                                app,
                                                app.packageName + ".fileprovider",
                                                java.io.File(path),
                                            ),
                                            "video/mp4",
                                        )
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    app.startActivity(Intent.createChooser(intent, "Play video"))
                                }) { Text(stringResource(R.string.play)) }
                            }
                        },
                        modifier = Modifier.clickable { nav.navigate(Routes.task(t.id)) },
                    )
                }
            }
        }
    }
}
