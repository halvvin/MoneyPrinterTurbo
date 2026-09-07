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
        }
    }

    private fun safeError(body: String): String =
        try {
            json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                ?: body.take(160)
        } catch (e: Exception) { body.take(160) }
}

class LlmException(message: String) : Exception(message)
