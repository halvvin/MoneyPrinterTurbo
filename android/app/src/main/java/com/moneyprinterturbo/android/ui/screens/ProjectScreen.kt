package com.moneyprinterturbo.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.moneyprinterturbo.android.core.model.TaskStatus
import com.moneyprinterturbo.android.ui.components.*
import com.moneyprinterturbo.android.ui.navigation.Routes

/** Projects list + detail with tasks, actions (rename/duplicate/delete/re-run). */
@Composable
fun ProjectScreen(nav: NavController, id: String) {
    val app = LocalContext.current.applicationContext as MptApplication
    var project by remember { mutableStateOf<com.moneyprinterturbo.android.core.db.ProjectEntity?>(null) }
    var tasks by remember { mutableStateOf(listOf<com.moneyprinterturbo.android.core.db.TaskEntity>()) }
    var editName by remember { mutableStateOf(false) }

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
            IconButton(onClick = {
                kotlinx.coroutines.runBlocking { app.repository.duplicateProject(id) }
            }) { Icon(Icons.Filled.ContentCopy, stringResource(R.string.duplicate)) }
            IconButton(onClick = {
                kotlinx.coroutines.runBlocking { app.repository.deleteProject(id) }
                nav.popBackStack()
            }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
        }
        if (editName) {
            var name by remember { mutableStateOf(p.name) }
            OutlinedTextFieldMpt(name, { name = it }, stringResource(R.string.project_name))
            Button(onClick = {
                editName = false
                kotlinx.coroutines.runBlocking { app.repository.renameProject(id, name); reload() }
            }) { Text(stringResource(R.string.save)) }
        }
        SectionCard(stringResource(R.string.section_script)) {
            Text(config.videoScript.ifBlank { stringResource(R.string.no_script) }, style = MaterialTheme.typography.bodyMedium)
        }
        Button(onClick = {
            kotlinx.coroutines.runBlocking { app.repository.queueTask(p) }
        }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.rerun_generate)) }
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
                            TextButton(onClick = { kotlinx.coroutines.runBlocking { app.repository.cancelTask(t.id) } }) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    },
                    modifier = Modifier.clickable { nav.navigate(Routes.task(t.id)) },
                )
            }
        }
    }
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
