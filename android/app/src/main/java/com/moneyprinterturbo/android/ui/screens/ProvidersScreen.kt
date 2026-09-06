package com.moneyprinterturbo.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.moneyprinterturbo.android.MptApplication
import com.moneyprinterturbo.android.R
import com.moneyprinterturbo.android.core.model.LlmKind
import com.moneyprinterturbo.android.core.model.LlmProvider
import com.moneyprinterturbo.android.core.model.StockKeys
import com.moneyprinterturbo.android.core.llm.LlmService
import com.moneyprinterturbo.android.ui.components.*
import com.moneyprinterturbo.android.ui.navigation.Routes
import kotlinx.coroutines.launch
import java.util.UUID

/** Provider manager: add/edit/delete/enable/set-default/test — parity with upstream provider registry. */
@Composable
fun ProvidersScreen(nav: NavController) {
    val app = LocalContext.current.applicationContext as MptApplication
    val scope = rememberCoroutineScope()
    var providers by remember { mutableStateOf(listOf<LlmProvider>()) }
    var testMsg by remember { mutableStateOf<String?>(null) }
    var keyStates by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

    suspend fun reload() {
        providers = app.prefs.providersNow()
        keyStates = providers.associate { it.id to (app.prefs.resolve(it).apiKey.isNotBlank()) }
    }
    LaunchedEffect(Unit) { reload() }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.providers), style = MaterialTheme.typography.headlineSmall)
        testMsg?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
        LazyColumn(Modifier.weight(1f)) {
            items(providers) { p ->
                val hasKey = keyStates[p.id] == true
                ListItem(
                    headlineContent = {
                        Text(
                            p.name + (if (p.isDefault) "  ★" else "") +
                                (if (hasKey) "  🔑" else "  ⚠ " + stringResource(R.string.no_key)),
                        )
                    },
                    supportingContent = { Text(p.baseUrl + " · " + p.model) },
                    trailingContent = {
                        Row {
                            val testingStr = stringResource(R.string.testing)
                            TextButton(onClick = {
                                scope.launch {
                                    testMsg = testingStr
                                    val result = LlmService(
                                        com.moneyprinterturbo.android.core.net.Http.client(), com.moneyprinterturbo.android.core.db.DbJson.json,
                                    ).testConnection(app.prefs.resolve(p))
                                    testMsg = (if (result.ok) "✔ " else "✘ ") + result.message + " (${result.latencyMs}ms)"
                                }
                            }) { Text(stringResource(R.string.test)) }
                            IconButton(onClick = {
                                scope.launch { app.prefs.deleteProvider(p.id); reload() }
                            }) {
                                Icon(Icons.Filled.Delete, stringResource(R.string.delete))
                            }
                        }
                    },
                    modifier = Modifier.clickable { nav.navigate(Routes.providerEdit(p.id)) },
                )
            }
        }
        Button(onClick = { nav.navigate(Routes.providerEdit("new")) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.add_provider))
        }
        OutlinedButton(onClick = { nav.navigate(Routes.STOCK_KEYS) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.stock_api_keys))
        }
    }
}

/**
 * Provider editor. Key behavior:
 * - New provider: typed key is stored encrypted on Save.
 * - Existing provider: stored key is never displayed; leaving the field blank keeps it.
 * - Model: free-text OR picked from the provider's live /models list.
 */
@Composable
fun ProviderEditScreen(nav: NavController, id: String) {
    val app = LocalContext.current.applicationContext as MptApplication
    val scope = rememberCoroutineScope()
    val isNew = id == "new"
    var provider by remember {
        mutableStateOf(LlmProvider(id = if (isNew) UUID.randomUUID().toString() else id, name = "", baseUrl = "https://api.openai.com/v1"))
    }
    var storedKey by remember { mutableStateOf("") }
    var saveError by remember { mutableStateOf<String?>(null) }
    var showModelPicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!isNew) {
            app.prefs.providersNow().firstOrNull { it.id == id }?.let { provider = it }
            storedKey = app.prefs.resolve(LlmProvider(id = id, name = "", baseUrl = "")).apiKey ?: ""
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (isNew) stringResource(R.string.add_provider) else stringResource(R.string.edit_provider), style = MaterialTheme.typography.headlineSmall)
        OutlinedTextFieldMpt(provider.name, { provider = provider.copy(name = it) }, stringResource(R.string.provider_name))
        OutlinedTextFieldMpt(provider.baseUrl, { provider = provider.copy(baseUrl = it) }, stringResource(R.string.base_url))
        OutlinedTextFieldMpt(
            provider.apiKey, { provider = provider.copy(apiKey = it) },
            stringResource(R.string.api_key),
            supporting = if (storedKey.isNotBlank()) stringResource(R.string.key_stored_hint) else stringResource(R.string.key_secure_hint),
        )
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextFieldMpt(provider.model, { provider = provider.copy(model = it) }, stringResource(R.string.model), modifier = Modifier.weight(1f))
            TextButton(onClick = { showModelPicker = true }, enabled = provider.baseUrl.isNotBlank()) {
                Text(stringResource(R.string.fetch_models))
            }
        }
        DropdownField(stringResource(R.string.kind), listOf("openai_compatible", "gemini"), provider.kind.name.lowercase()) {
            provider = provider.copy(kind = if (it == "gemini") LlmKind.GEMINI else LlmKind.OPENAI_COMPATIBLE,
                baseUrl = if (it == "gemini" && provider.baseUrl.contains("openai")) "https://generativelanguage.googleapis.com/v1beta" else provider.baseUrl)
        }
        LabeledSwitch(stringResource(R.string.default_provider), provider.isDefault) { provider = provider.copy(isDefault = it) }
        LabeledSwitch(stringResource(R.string.enabled), provider.enabled) { provider = provider.copy(enabled = it) }

        saveError?.let { ErrorBanner(it) }

        Button(onClick = {
            scope.launch {
                try {
                    // Blank key on an existing provider = keep the stored one (don't wipe).
                    val final = if (provider.apiKey.isBlank() && storedKey.isNotBlank())
                        provider.copy(apiKey = storedKey) else provider
                    app.prefs.saveProvider(final)
                    nav.popBackStack()
                } catch (e: Exception) {
                    saveError = "save failed: ${e.message}"
                }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.save)) }
    }

    if (showModelPicker) {
        ModelPickerDialog(
            provider = provider.copy(apiKey = provider.apiKey.ifBlank { storedKey }),
            onPick = { provider = provider.copy(model = it); showModelPicker = false },
            onDismiss = { showModelPicker = false },
        )
    }
}

/** Live model picker: fetches {baseUrl}/models from the provider; manual entry fallback. */
@Composable
fun ModelPickerDialog(provider: LlmProvider, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var models by remember { mutableStateOf<List<String>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(provider.id) {
        try {
            models = LlmService(
                com.moneyprinterturbo.android.core.net.Http.client(), com.moneyprinterturbo.android.core.db.DbJson.json,
            ).listModels(provider)
        } catch (e: Exception) {
            err = e.message
            models = emptyList()
        }
    }
    val all = models.orEmpty()
    val filtered = all.filter { it.contains(query, ignoreCase = true) }
        .sortedByDescending { it.endsWith(":free") }   // free tiers first

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.model_list)) },
        text = {
            Column {
                OutlinedTextFieldMpt(query, { query = it }, stringResource(R.string.search))
                when {
                    models == null -> {
                        Text(stringResource(R.string.loading_models), style = MaterialTheme.typography.bodySmall)
                    }
                    filtered.isEmpty() && err != null -> {
                        Text(stringResource(R.string.models_failed) + "\n" + err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { onPick(query) }) { Text(stringResource(R.string.use_typed)) }
                    }
                    else -> {
                        if (query.isNotBlank() && all.isNotEmpty() && !all.contains(query)) {
                            TextButton(onClick = { onPick(query) }) { Text(stringResource(R.string.use_typed) + ": " + query) }
                        }
                        LazyColumn(Modifier.height(380.dp)) {
                            items(filtered) { m ->
                                ListItem(
                                    headlineContent = { Text(m) },
                                    modifier = Modifier.clickable { onPick(m) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Stock API keys (pexels/pixabay/coverr) — stored encrypted. */
@Composable
fun StockKeysScreen(nav: NavController) {
    val app = LocalContext.current.applicationContext as MptApplication
    val scope = rememberCoroutineScope()
    var keys by remember { mutableStateOf(StockKeys()) }
    LaunchedEffect(Unit) { keys = app.prefs.stockKeys() }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.stock_api_keys), style = MaterialTheme.typography.headlineSmall)
        OutlinedTextFieldMpt(keys.pexelsApiKey, { keys = keys.copy(pexelsApiKey = it) }, "Pexels API key")
        OutlinedTextFieldMpt(keys.pixabayApiKey, { keys = keys.copy(pixabayApiKey = it) }, "Pixabay API key")
        OutlinedTextFieldMpt(keys.coverrApiKey, { keys = keys.copy(coverrApiKey = it) }, "Coverr API key")
        Button(onClick = { scope.launch { app.prefs.saveStockKeys(keys); nav.popBackStack() } }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.save))
        }
    }
}

/** Voice browser: search all azure voices (bundled catalog). */
@Composable
fun VoicesScreen(nav: NavController) {
    VoicePickerDialog(current = "", onPick = { nav.popBackStack() }, onDismiss = { nav.popBackStack() })
}
