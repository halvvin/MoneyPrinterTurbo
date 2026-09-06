package com.moneyprinterturbo.android.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.moneyprinterturbo.android.ui.components.*

/** Task detail: live progress, stage, selectable logs w/ copy+share, error + retry/cancel. */
@Composable
fun TaskScreen(nav: NavController, id: String) {
    val app = LocalContext.current.applicationContext as MptApplication
    var task by remember { mutableStateOf<com.moneyprinterturbo.android.core.db.TaskEntity?>(null) }
    LaunchedEffect(id) {
        while (true) {
            task = app.repository.task(id)
            kotlinx.coroutines.delay(1500)
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
        LinearProgressIndicator(progress = { t.progress / 100f }, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.stage) + ": " + t.stage, style = MaterialTheme.typography.bodyMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (t.status == TaskStatus.RUNNING.code) {
                Button(onClick = { kotlinx.coroutines.runBlocking { app.repository.cancelTask(t.id) } }) {
                    Text(stringResource(R.string.cancel))
                }
            }
            if (t.status == TaskStatus.FAILED.code) {
                Button(onClick = {
                    kotlinx.coroutines.runBlocking { app.repository.retryTask(t.id) }
                }) { Text(stringResource(R.string.retry)) }
            }
            if (t.videoPath != null) {
                Button(onClick = {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                        .setDataAndType(
                            androidx.core.content.FileProvider.getUriForFile(
                                app, app.packageName + ".fileprovider", java.io.File(t.videoPath!!),
                            ),
                            "video/mp4",
                        )
                        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    app.startActivity(android.content.Intent.createChooser(intent, "Play video"))
                }) { Text(stringResource(R.string.play)) }
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
                    app.startActivity(android.content.Intent.createChooser(send, shareTitle))
                }) { Text(stringResource(R.string.share)) }
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
