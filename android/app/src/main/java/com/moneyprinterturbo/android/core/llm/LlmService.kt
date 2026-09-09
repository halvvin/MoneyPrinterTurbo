package com.moneyprinterturbo.android.core.llm

import com.moneyprinterturbo.android.core.model.AppSettings
import com.moneyprinterturbo.android.core.model.LlmKind
import com.moneyprinterturbo.android.core.model.LlmProvider
import com.moneyprinterturbo.android.core.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Result of a provider connectivity test — parity with upstream llm.test_connection. */
data class ConnectionTest(val ok: Boolean, val message: String, val latencyMs: Long)

/**
 * LLM access over OpenAI-compatible chat/completions and Gemini generateContent.
 * Never logs API keys (keys are passed but never included in exceptions).
 */
class LlmService(private val http: OkHttpClient, private val json: Json) {

    suspend fun chat(provider: LlmProvider, model: String, system: String, user: String): String =
        withContext(Dispatchers.IO) {
            when (provider.kind) {
                LlmKind.OPENAI_COMPATIBLE -> chatOpenAi(provider, model, system, user)
                LlmKind.GEMINI -> chatGemini(provider, model, system, user)
                LlmKind.QWEN_DASHSCOPE -> chatQwen(provider, model, system, user)
                LlmKind.AZURE_OPENAI -> chatAzure(provider, model, system, user)
                LlmKind.CLOUDFLARE_GATEWAY -> chatCloudflare(provider, model, system, user)
                LlmKind.CUSTOM_HTTP -> chatCustomHttp(provider, model, system, user)
            }
        }

    private fun chatOpenAi(provider: LlmProvider, model: String, system: String, user: String): String {
        val key = provider.apiKey.trim()
        if (key.isBlank()) throw LlmException(
            "provider '${provider.name}' has no API key stored — open Settings → Providers, " +
                "tap '${provider.name}', paste the key and Save"
        )
        val body = buildJsonObject {
            put("model", model.ifBlank { provider.model })
            put("temperature", provider.temperature)
            put("max_tokens", provider.maxTokens)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", user) })
            })
        }.toString().toRequestBody("application/json".toMediaType())

        val url = provider.baseUrl.trimEnd('/') + "/chat/completions"
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer $key")
            .header("User-Agent", "MoneyPrinterTurbo-Android/1.0")
            .header("HTTP-Referer", "https://github.com/harry0703/MoneyPrinterTurbo")
            .header("X-Title", "MoneyPrinterTurbo Android")
            .post(body).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw LlmException("provider returned HTTP ${resp.code}: ${safeError(text)}")
            val content = json.parseToJsonElement(text).jsonObject["choices"]
                ?.jsonArray?.firstOrNull()?.jsonObject?.get("message")
                ?.jsonObject?.get("content")?.jsonPrimitive?.content
                ?: throw LlmException("provider response missing choices[0].message.content")
            return Prompts.normalizeTextResponse(content)
        }
    }

    /**
     * P2.3 Qwen DashScope (upstream adapter "qwen"): native Generation API.
     * Default endpoint = international (dashscope-intl). Body mirrors upstream:
     * {model, input:{messages:[…]}}, response = output.choices[0].message.content
     * (older completion shape falls back to output.text).
     */
    private fun chatQwen(provider: LlmProvider, model: String, system: String, user: String): String {
        val key = requireKey(provider)
        val base = provider.baseUrl.trim().trimEnd('/').ifBlank { "https://dashscope-intl.aliyuncs.com" }
        val url = when {
            base.contains("text-generation") -> base
            base.endsWith("/api/v1") -> "$base/services/aigc/text-generation/generation"
            else -> "$base/api/v1/services/aigc/text-generation/generation"
        }
        val body = buildJsonObject {
            put("model", model.ifBlank { provider.model })
            put("input", buildJsonObject {
                put("messages", buildJsonArray {
                    add(buildJsonObject { put("role", "system"); put("content", system) })
                    add(buildJsonObject { put("role", "user"); put("content", user) })
                })
            })
            put("parameters", buildJsonObject { put("result_format", "message") })
        }.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer $key")
            .header("User-Agent", "MoneyPrinterTurbo-Android/1.0")
            .post(body).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw LlmException("qwen returned HTTP ${resp.code}: ${safeError(text)}")
            val root = json.parseToJsonElement(text).jsonObject
            root["code"]?.jsonPrimitive?.content?.let { code ->
                throw LlmException("qwen error $code: ${root["message"]?.jsonPrimitive?.content ?: ""}")
            }
            val content = root["output"]?.jsonObject?.get("choices")?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
                ?: root["output"]?.jsonObject?.get("text")?.jsonPrimitive?.content
                ?: throw LlmException("qwen: empty output")
            return Prompts.normalizeTextResponse(content)
        }
    }

    /**
     * P2.3 Azure OpenAI (upstream adapter "azure"): deployment-based endpoint —
     * {base}/openai/deployments/{deployment}/chat/completions?api-version={v},
     * auth via the `api-key` header (deployment name = provider.model).
     */
    private fun chatAzure(provider: LlmProvider, model: String, system: String, user: String): String {
        val key = requireKey(provider)
        val base = provider.baseUrl.trim().trimEnd('/')
        if (base.isBlank()) throw LlmException("azure: base URL required (e.g. https://<resource>.openai.azure.com)")
        val deployment = java.net.URLEncoder.encode(model.ifBlank { provider.model }, "UTF-8")
        val apiVersion = provider.apiVersion.ifBlank { "2024-02-15-preview" }
        val url = "$base/openai/deployments/$deployment/chat/completions?api-version=$apiVersion"
        val body = buildJsonObject {
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", user) })
            })
            put("temperature", provider.temperature)
            put("max_tokens", provider.maxTokens)
        }.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(url)
            .header("api-key", key)
            .header("User-Agent", "MoneyPrinterTurbo-Android/1.0")
            .post(body).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw LlmException("azure returned HTTP ${resp.code}: ${safeError(text)}")
            val content = json.parseToJsonElement(text).jsonObject["choices"]
                ?.jsonArray?.firstOrNull()?.jsonObject?.get("message")
                ?.jsonObject?.get("content")?.jsonPrimitive?.content
                ?: throw LlmException("azure: empty choices")
            return Prompts.normalizeTextResponse(content)
        }
    }

    /**
     * P2.3 Cloudflare AI Gateway (upstream adapter "cloudflare_ai_gateway"):
     * OpenAI-compatible REST behind your own gateway:
     * {base}/accounts/{accountId}/ai/v1/chat/completions + `cf-aig-gateway-id` header.
     */
    private fun chatCloudflare(provider: LlmProvider, model: String, system: String, user: String): String {
        val key = requireKey(provider)
        val account = provider.accountId.trim()
        if (account.isBlank()) throw LlmException("cloudflare: account ID required")
        val base = provider.baseUrl.trim().trimEnd('/').ifBlank { "https://api.cloudflare.com/client/v4" }
        val url = "$base/accounts/$account/ai/v1/chat/completions"
        val body = buildJsonObject {
            put("model", model.ifBlank { provider.model })
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", user) })
            })
        }.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer $key")
            .apply { if (provider.gatewayId.isNotBlank()) header("cf-aig-gateway-id", provider.gatewayId) }
            .header("User-Agent", "MoneyPrinterTurbo-Android/1.0")
            .post(body).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw LlmException("cloudflare returned HTTP ${resp.code}: ${safeError(text)}")
            val content = json.parseToJsonElement(text).jsonObject["choices"]
                ?.jsonArray?.firstOrNull()?.jsonObject?.get("message")
                ?.jsonObject?.get("content")?.jsonPrimitive?.content
                ?: throw LlmException("cloudflare: empty choices")
            return Prompts.normalizeTextResponse(content)
        }
    }

    /**
     * P2.3 CUSTOM_HTTP: works with ANY API. User writes the request template with
     * placeholders {{URL}} {{MODEL}} {{SYSTEM}} {{USER}} {{API_KEY}} and a dot-path
     * to the text in the response (e.g. "choices[0].message.content").
     */
    private fun chatCustomHttp(provider: LlmProvider, model: String, system: String, user: String): String {
        val key = requireKey(provider)
        val modelId = model.ifBlank { provider.model }
        val url = fillTemplate(provider.customUrlTemplate, provider.baseUrl, modelId, system, user, key)
        if (url.isBlank() || !url.startsWith("http")) throw LlmException(
            "custom provider: set the URL template, e.g. {{BASE_URL}}/chat/completions"
        )
        val bodyText = fillTemplate(
            provider.customBodyTemplate.ifBlank {
                // Sensible default = OpenAI chat shape (works for most APIs).
                "{\"model\":\"{{MODEL}}\",\"messages\":[{\"role\":\"system\",\"content\":\"{{SYSTEM}}\"},{\"role\":\"user\",\"content\":\"{{USER}}\"}]}"
            },
            provider.baseUrl, modelId, system, user, key,
        )
        val method = provider.customMethod.uppercase().ifBlank { "POST" }
        val builder = Request.Builder().url(url)
        // headers: one per line "Name: value" (templates allowed)
        var hasAuth = false
        provider.customHeaders.lineSequence().forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) {
                val name = line.substring(0, idx).trim()
                val value = fillTemplate(line.substring(idx + 1).trim(), provider.baseUrl, modelId, system, user, key)
                if (name.isNotBlank() && value.isNotBlank()) {
                    if (name.equals("Authorization", ignoreCase = true)) hasAuth = true
                    builder.header(name, value)
                }
            }
        }
        if (!hasAuth && key.isNotBlank()) builder.header("Authorization", "Bearer $key")
        builder.header("User-Agent", "MoneyPrinterTurbo-Android/1.0")
        if (method != "GET") builder.method(method, bodyText.toRequestBody("application/json".toMediaType()))
        http.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw LlmException("custom provider returned HTTP ${resp.code}: ${safeError(text)}")
            val path = provider.customResponsePath.ifBlank { "choices[0].message.content" }
            val content = readJsonPath(text, path)
                ?: throw LlmException("custom provider: responsePath '$path' not found in response")
            return Prompts.normalizeTextResponse(content)
        }
    }

    private fun requireKey(provider: LlmProvider): String {
        val key = provider.apiKey.trim()
        if (key.isBlank()) throw LlmException(
            "provider '${provider.name}' has no API key stored — open Settings → Providers, " +
                "tap '${provider.name}', paste the key and Save"
        )
        return key
    }

    private fun fillTemplate(
        tpl: String, baseUrl: String, model: String, system: String, user: String, key: String,
    ): String = tpl
        .replace("{{BASE_URL}}", baseUrl.trimEnd('/'))
        .replace("{{URL}}", baseUrl.trimEnd('/'))
        .replace("{{MODEL}}", model)
        .replace("{{SYSTEM}}", system.replace("$", "\\$"))
        .replace("{{USER}}", user.replace("$", "\\$"))
        .replace("{{API_KEY}}", key)

    /** Minimal dot/bracket path reader: "choices[0].message.content", "output.text", "data.0.content". */
    private fun readJsonPath(text: String, path: String): String? = try {
        var node: kotlinx.serialization.json.JsonElement = json.parseToJsonElement(text)
        val regex = Regex("([^\\.\\[\\]]+)|\\[(\\d+)\\]")
        regex.findAll(path).forEach { m ->
            val key = m.groupValues[1]
            val idx = m.groupValues[2]
            node = when {
                idx.isNotBlank() -> node.jsonArray[idx.toInt()]
                key.isNotBlank() -> node.jsonObject[key] ?: return null
                else -> return null
            }
        }
        (node as? kotlinx.serialization.json.JsonPrimitive)?.content ?: node.toString()
    } catch (e: Exception) { null }

    private fun chatGemini(provider: LlmProvider, model: String, system: String, user: String): String {
        val url = provider.baseUrl.trimEnd('/') +
            "/models/$model:generateContent?key=${provider.apiKey}"
        val body = buildJsonObject {
            put("system_instruction", buildJsonObject {
                put("parts", buildJsonArray { add(buildJsonObject { put("text", system) }) })
            })
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", user) }) })
                })
            })
            put("generationConfig", buildJsonObject {
                put("temperature", provider.temperature)
                put("maxOutputTokens", provider.maxTokens)
            })
        }.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(url).post(body).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw LlmException("provider returned HTTP ${resp.code}: ${safeError(text)}")
            val candidates = json.parseToJsonElement(text).jsonObject["candidates"]?.jsonArray ?: throw LlmException("gemini: no candidates")
            val content = candidates.firstOrNull()?.jsonObject?.get("content")?.jsonObject
                ?.get("parts")?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
                ?: throw LlmException("gemini: empty candidate")
            return Prompts.normalizeTextResponse(content)
        }
    }

    /** Same prompts as upstream: script generation. */
    suspend fun generateScript(
        provider: LlmProvider,
        subject: String,
        language: String,
        paragraphNumber: Int,
        videoScriptPrompt: String,
        customSystemPrompt: String,
        settings: AppSettings,
    ): String {
        val model = settings.scriptModel.ifBlank { provider.model }
        val prompt = Prompts.buildScriptPrompt(subject, language, paragraphNumber, videoScriptPrompt, customSystemPrompt)
        return chat(provider, model, prompt, "Generate a video script for the subject above.").trim()
    }

    suspend fun generateTerms(
        provider: LlmProvider,
        subject: String,
        script: String,
        amount: Int,
        matchScriptOrder: Boolean,
        settings: AppSettings,
    ): List<String> {
        val model = settings.termsModel.ifBlank { provider.model }
        val prompt = Prompts.buildTermsPrompt(amount, matchScriptOrder, subject, script)
        val raw = chat(provider, model, prompt, "Return only the JSON array of search terms.")
        val terms = Prompts.parseTerms(raw)
        if (terms.isEmpty()) throw LlmException("model returned no parseable search terms")
        return terms.take(amount.coerceAtLeast(1))
    }

    suspend fun testConnection(provider: LlmProvider): ConnectionTest {
        val t0 = System.currentTimeMillis()
        return try {
            val answer = chat(provider, provider.model, "You are a ping service.", "ping")
            ConnectionTest(true, answer.take(60), System.currentTimeMillis() - t0)
        } catch (e: LlmException) {
            val msg = if ("429" in e.message.toString())
                "rate limited (429) — free-tier models are shared capacity; try again in a minute or pick another model"
            else e.message.toString()
            ConnectionTest(false, msg, System.currentTimeMillis() - t0)
        } catch (e: Exception) {
            ConnectionTest(false, e.message ?: e.javaClass.simpleName, System.currentTimeMillis() - t0)
        }
    }

    /**
     * Fetch the provider's available model list — GET {baseUrl}/models (OpenAI format:
     * {data:[{id}]}, Gemini: {models:[{name:"models/…"}]}). Used by the model picker UI.
     */
    suspend fun listModels(provider: LlmProvider): List<String> = withContext(Dispatchers.IO) {
        when (provider.kind) {
            LlmKind.OPENAI_COMPATIBLE -> {
                val url = provider.baseUrl.trimEnd('/') + "/models"
                val req = Request.Builder().url(url)
                    .apply { if (provider.apiKey.isNotBlank()) header("Authorization", "Bearer ${provider.apiKey}") }
                    .header("User-Agent", "MoneyPrinterTurbo-Android/1.0")
                    .build()
                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) throw LlmException("model list failed: HTTP ${resp.code}")
                    val obj = json.parseToJsonElement(text).jsonObject
                    val arr = (obj["data"] ?: obj["models"])?.jsonArray
                        ?: throw LlmException("unexpected model-list response")
                    arr.mapNotNull { el ->
                        val o = el.jsonObject
                        (o["id"] ?: o["name"])?.jsonPrimitive?.content
                    }.map { it.removePrefix("models/") }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .sorted()
                }
            }
            LlmKind.GEMINI -> {
                val url = provider.baseUrl.trimEnd('/') +
                    "/models?key=${provider.apiKey.trim().ifBlank { "MISSING" }}"
                val req = Request.Builder().url(url).build()
                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) throw LlmException("model list failed: HTTP ${resp.code}")
                    val obj = json.parseToJsonElement(text).jsonObject
                    val arr = obj["models"]?.jsonArray ?: throw LlmException("unexpected gemini response")
                    arr.mapNotNull { el ->
                        el.jsonObject["name"]?.jsonPrimitive?.content?.removePrefix("models/")
                    }.distinct().sorted()
                }
            }
            // P2.3: Qwen DashScope native API has no portable model-list endpoint here;
            // Azure/Cloudflare/Custom return empty so the user types the model manually.
            LlmKind.QWEN_DASHSCOPE -> listOf(
                "qwen-plus", "qwen-turbo", "qwen-max", "qwen-plus-latest", "qwen-max-latest",
                "qwen2.5-72b-instruct", "qwen2.5-32b-instruct", "qwen2.5-14b-instruct", "qwen2.5-7b-instruct",
            )
            LlmKind.AZURE_OPENAI, LlmKind.CLOUDFLARE_GATEWAY, LlmKind.CUSTOM_HTTP -> emptyList()
        }
    }

    private fun safeError(body: String): String =
        try {
            json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                ?: body.take(160)
        } catch (e: Exception) { body.take(160) }
}

class LlmException(message: String) : Exception(message)
