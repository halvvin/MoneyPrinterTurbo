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
    var testMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (!isNew) {
            app.prefs.providersNow().firstOrNull { it.id == id }?.let { provider = it }
            storedKey = app.prefs.resolve(LlmProvider(id = id, name = "", baseUrl = "")).apiKey ?: ""
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (isNew) stringResource(R.string.add_provider) else stringResource(R.string.edit_provider), style = MaterialTheme.typography.headlineSmall)

        // ── 1. Identity ──────────────────────────────────────────────
        OutlinedTextFieldMpt(provider.name, { provider = provider.copy(name = it) }, stringResource(R.string.provider_name))
        DropdownField(stringResource(R.string.kind), listOf("openai_compatible", "gemini", "qwen_dashscope", "azure_openai", "cloudflare_gateway", "custom_http"), provider.kind.name.lowercase()) {
            val newKind = when (it) {
                "gemini" -> LlmKind.GEMINI
                "qwen_dashscope" -> LlmKind.QWEN_DASHSCOPE
                "azure_openai" -> LlmKind.AZURE_OPENAI
                "cloudflare_gateway" -> LlmKind.CLOUDFLARE_GATEWAY
                "custom_http" -> LlmKind.CUSTOM_HTTP
                else -> LlmKind.OPENAI_COMPATIBLE
            }
            if (newKind != provider.kind) {
                val presetBase = when (newKind) {
                    LlmKind.GEMINI -> "https://generativelanguage.googleapis.com/v1beta"
                    LlmKind.QWEN_DASHSCOPE -> "https://dashscope-intl.aliyuncs.com"
                    LlmKind.AZURE_OPENAI -> "https://YOUR-RESOURCE.openai.azure.com"
                    LlmKind.CLOUDFLARE_GATEWAY -> "https://api.cloudflare.com/client/v4"
                    LlmKind.OPENAI_COMPATIBLE -> "https://api.openai.com/v1"
                    else -> provider.baseUrl
                }
                provider = provider.copy(kind = newKind, baseUrl = presetBase)
            }
        }

        // ── 2. Connection — ONLY the fields this kind actually needs ──
        val keyOk = provider.apiKey.isNotBlank() || storedKey.isNotBlank()
        when (provider.kind) {
            LlmKind.OPENAI_COMPATIBLE, LlmKind.GEMINI, LlmKind.QWEN_DASHSCOPE -> {
                OutlinedTextFieldMpt(provider.baseUrl, { provider = provider.copy(baseUrl = it) }, stringResource(R.string.base_url))
            }
            LlmKind.AZURE_OPENAI -> {
                OutlinedTextFieldMpt(provider.baseUrl, { provider = provider.copy(baseUrl = it) }, "Resource URL (https://<name>.openai.azure.com)")
                OutlinedTextFieldMpt(provider.apiVersion, { provider = provider.copy(apiVersion = it) }, "api-version (default 2024-02-15-preview)")
            }
            LlmKind.CLOUDFLARE_GATEWAY -> {
                OutlinedTextFieldMpt(provider.accountId, { provider = provider.copy(accountId = it) }, "Cloudflare Account ID")
                OutlinedTextFieldMpt(provider.gatewayId, { provider = provider.copy(gatewayId = it) }, "AI Gateway ID")
                OutlinedTextFieldMpt(provider.baseUrl, { provider = provider.copy(baseUrl = it) }, "API base (default https://api.cloudflare.com/client/v4)")
            }
            LlmKind.CUSTOM_HTTP -> {
                OutlinedTextFieldMpt(provider.customUrlTemplate, { provider = provider.copy(customUrlTemplate = it) }, "URL template — e.g. {{BASE_URL}}/chat/completions", minLines = 1)
                OutlinedTextFieldMpt(provider.baseUrl, { provider = provider.copy(baseUrl = it) }, "{{BASE_URL}} value", minLines = 1)
            }
        }

        // --- API key (masked, shared by all kinds) ---
        Text(
            stringResource(if (keyOk) R.string.key_is_set else R.string.key_is_missing),
            color = if (keyOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!keyOk) {
            OutlinedTextFieldMpt(provider.apiKey, { provider = provider.copy(apiKey = it) }, stringResource(R.string.paste_key_hint))
        } else {
            Text(
                stringResource(R.string.key_masked_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        if (!isNew && storedKey.isNotBlank()) {
            TextButton(onClick = {
                scope.launch {
                    try {
                        app.prefs.saveProvider(provider.copy(apiKey = ""))
                        storedKey = ""
                        provider = provider.copy(apiKey = "")
                        saveError = null
                    } catch (e: Exception) { saveError = "remove key failed: ${e.message}" }
                }
            }) { Text(stringResource(R.string.remove_stored_key)) }
        }

        // --- Model (all kinds except custom_http need a model/deployment name) ---
        if (provider.kind != LlmKind.CUSTOM_HTTP) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val modelLabel = if (provider.kind == LlmKind.AZURE_OPENAI) "Deployment (= Model)" else stringResource(R.string.model)
                OutlinedTextFieldMpt(provider.model, { provider = provider.copy(model = it) }, modelLabel, modifier = Modifier.weight(1f))
                val canFetch = provider.kind == LlmKind.OPENAI_COMPATIBLE || provider.kind == LlmKind.GEMINI
                TextButton(onClick = { showModelPicker = true }, enabled = canFetch && provider.baseUrl.isNotBlank()) {
                    Text(stringResource(R.string.fetch_models))
                }
            }
        }

        // --- CUSTOM_HTTP: method / headers / body / response path ---
        if (provider.kind == LlmKind.CUSTOM_HTTP) {
            OutlinedTextFieldMpt(provider.customMethod, { provider = provider.copy(customMethod = it) }, "HTTP method (default POST)", minLines = 1)
            OutlinedTextFieldMpt(provider.customHeaders, { provider = provider.copy(customHeaders = it) }, "Extra headers (one per line \"Name: value\")", minLines = 2, singleLine = false)
            OutlinedTextFieldMpt(provider.customBodyTemplate, { provider = provider.copy(customBodyTemplate = it) }, "Body JSON template ({{MODEL}} {{SYSTEM}} {{USER}})", minLines = 3, singleLine = false)
            OutlinedTextFieldMpt(provider.customResponsePath, { provider = provider.copy(customResponsePath = it) }, "Response path (default choices[0].message.content)", minLines = 1)
            Text("Placeholders: {{BASE_URL}} {{MODEL}} {{SYSTEM}} {{USER}} {{API_KEY}} — works with ANY OpenAI-style or custom API.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }

        // ── 3. Flags + Save/Test ────────────────────────────────────
        LabeledSwitch(stringResource(R.string.default_provider), provider.isDefault) { provider = provider.copy(isDefault = it) }
        LabeledSwitch(stringResource(R.string.enabled), provider.enabled) { provider = provider.copy(enabled = it) }

        saveError?.let { ErrorBanner(it) }
        testMsg?.let { Text(testMsg!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }

        // Capture compose-resolved strings BEFORE the onClick lambda (stringResource
        // is @Composable and cannot run inside click handlers / try-catch).
        val strBaseUrl = stringResource(R.string.base_url)
        val strModel = stringResource(R.string.model)
        val strNoKey = stringResource(R.string.no_key)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    try {
                        // Per-kind validation BEFORE save — clear message, no more
                        // mystery "no key / wrong URL" when the task actually runs.
                        val missing = mutableListOf<String>()
                        if (provider.name.isBlank()) missing += "Name"
                        when (provider.kind) {
                            LlmKind.AZURE_OPENAI -> {
                                if (provider.baseUrl.isBlank() || "YOUR-RESOURCE" in provider.baseUrl) missing += "Resource URL"
                                if (provider.model.isBlank()) missing += "Deployment (Model)"
                            }
                            LlmKind.CLOUDFLARE_GATEWAY -> {
                                if (provider.accountId.isBlank()) missing += "Account ID"
                                if (provider.gatewayId.isBlank()) missing += "Gateway ID"
                                if (provider.model.isBlank()) missing += "Model"
                            }
                            LlmKind.CUSTOM_HTTP -> {
                                if (provider.customUrlTemplate.isBlank()) missing += "URL template"
                            }
                            else -> {
                                if (provider.baseUrl.isBlank()) missing += strBaseUrl
                                if (provider.model.isBlank()) missing += strModel
                            }
                        }
                        if (provider.apiKey.isBlank() && storedKey.isBlank()) {
                            // All six kinds are key-based in this app (gemini uses key in URL too).
                            missing += strNoKey
                        }
                        if (missing.isNotEmpty()) {
                            saveError = "missing: " + missing.joinToString(", ")
                            return@launch
                        }
                        // Blank key on an existing provider = keep the stored one (don't wipe).
                        val final = if (provider.apiKey.isBlank() && storedKey.isNotBlank())
                            provider.copy(apiKey = storedKey) else provider
                        app.prefs.saveProvider(final)
                        nav.popBackStack()
                    } catch (e: Exception) {
                        saveError = "save failed: ${e.message}"
                    }
                }
            }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.save)) }

            val testingStr = stringResource(R.string.testing)
            val noKeyStr = stringResource(R.string.no_key)
            OutlinedButton(onClick = {
                scope.launch {
                    val effective = provider.copy(apiKey = provider.apiKey.trim().ifBlank { storedKey })
                    if (effective.apiKey.isBlank() && effective.kind != LlmKind.GEMINI) {
                        testMsg = "✘ $noKeyStr — paste the key first"
                    } else {
                        testMsg = testingStr
                        val r = LlmService(
                            com.moneyprinterturbo.android.core.net.Http.client(), com.moneyprinterturbo.android.core.db.DbJson.json,
                        ).testConnection(effective)
                        testMsg = (if (r.ok) "✔ " else "✘ ") + r.message + " (${r.latencyMs}ms)"
                    }
                }
            }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.test)) }
        }
    }

    if (showModelPicker) {
        ModelPickerDialog(
            provider = provider.copy(apiKey = provider.apiKey.ifBlank { storedKey }),
            onPick = { provider = provider.copy(model = it); showModelPicker = false },
            onDismiss = { showModelPicker = false },
        )
    }
}

/** Live model picker: fetches {baseUrl}/models from the provider; per-model test button. */
@Composable
fun ModelPickerDialog(provider: LlmProvider, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var models by remember { mutableStateOf<List<String>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var testResults by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val noKeyWarning = provider.apiKey.isBlank() && provider.kind != LlmKind.GEMINI
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
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.model_list)) },
        text = {
            Column {
                if (noKeyWarning) {
                    Text(
                        stringResource(R.string.no_key_warning),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(4.dp))
                }
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
                                val res = testResults[m]
                                ListItem(
                                    headlineContent = { Text(m) },
                                    supportingContent = res?.let {
                                        { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                                    },
                                    trailingContent = {
                                        TextButton(onClick = {
                                            testResults = testResults + (m to "…")
                                            scope.launch {
                                                val r = LlmService(
                                                    com.moneyprinterturbo.android.core.net.Http.client(), com.moneyprinterturbo.android.core.db.DbJson.json,
                                                ).testConnection(provider.copy(model = m))
                                                testResults = testResults + (m to ((if (r.ok) "✔" else "✘ ") + r.message.take(60) + " ${r.latencyMs}ms"))
                                            }
                                        }) { Text(stringResource(R.string.test)) }
                                    },
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
