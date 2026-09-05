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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.moneyprinterturbo.android.MptApplication
import com.moneyprinterturbo.android.R
import com.moneyprinterturbo.android.core.model.AppSettings
import com.moneyprinterturbo.android.core.model.Mode
import com.moneyprinterturbo.android.core.model.TaskConfig
import com.moneyprinterturbo.android.ui.components.*
import com.moneyprinterturbo.android.ui.navigation.Routes
import kotlinx.coroutines.launch

/** Dashboard: New Video, quick stats, running tasks, quick links. */
@Composable
fun HomeScreen(nav: NavController) {
    val app = LocalContext.current.applicationContext as MptApplication
    val scope = rememberCoroutineScope()
    var tasks by remember { mutableStateOf(listOf<com.moneyprinterturbo.android.core.db.TaskEntity>()) }
    var projects by remember { mutableStateOf(listOf<com.moneyprinterturbo.android.core.db.ProjectEntity>()) }
    var settings by remember { mutableStateOf(AppSettings()) }

    LaunchedEffect(Unit) {
        tasks = app.repository.tasks()
        projects = app.repository.projects()
        app.prefs.settings.collect { settings = it }
    }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("MoneyPrinterTurbo", style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.home_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        item {
            val running = tasks.count { it.status == 4 }
            val done = tasks.count { it.status == 1 }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard(Modifier.weight(1f), running.toString(), stringResource(R.string.stat_running))
                StatCard(Modifier.weight(1f), done.toString(), stringResource(R.string.stat_completed))
                StatCard(Modifier.weight(1f), projects.size.toString(), stringResource(R.string.stat_projects))
            }
        }
        item {
            Button(
                onClick = { nav.navigate(Routes.CREATE) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Icon(Icons.Filled.VideoCall, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.new_video))
            }
        }
        item {
            val modeLabel = stringResource(when (settings.mode) {
                Mode.LOCAL -> R.string.mode_local
                Mode.REMOTE -> R.string.mode_remote
                Mode.CLOUD_API -> R.string.mode_cloud
            })
            Text(
                stringResource(R.string.current_mode, modeLabel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        if (tasks.isNotEmpty()) {
            item { Text(stringResource(R.string.recent_tasks), style = MaterialTheme.typography.titleMedium) }
            items(tasks.take(5)) { t ->
                ListItem(
                    headlineContent = { Text(t.subject.ifBlank { t.id.take(8) }) },
                    supportingContent = { StatusChip(t.status, t.progress) },
                    modifier = Modifier.clickable { nav.navigate(Routes.task(t.id)) },
                )
            }
        } else {
            item { EmptyState(stringResource(R.string.no_tasks_yet)) }
        }
    }
}

@Composable
private fun StatCard(modifier: Modifier, value: String, label: String) {
    Card(modifier) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}
