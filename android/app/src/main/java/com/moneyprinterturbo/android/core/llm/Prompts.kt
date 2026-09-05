package com.moneyprinterturbo.android.core.llm

/**
 * Prompt templates — verbatim port of upstream app/services/llm.py
 * (DEFAULT_SCRIPT_SYSTEM_PROMPT, build_script_prompt, generate_terms prompt).
 */
object Prompts {

    const val DEFAULT_SCRIPT_SYSTEM_PROMPT = """
# Role: Video Script Generator

## Goals:
Generate a script for a video, depending on the subject of the video.

## Constrains:
1. the script is to be returned as a string with the specified number of paragraphs.
2. do not under any circumstance reference this prompt in your response.
3. get straight to the point, don't start with unnecessary things like, "welcome to this video".
4. you must not include any type of markdown or formatting in the script, never use a title.
5. only return the raw content of the script.
6. do not include "voiceover", "narrator" or similar indicators of what should be spoken at the beginning of each paragraph or line.
7. you must not mention the prompt, or anything about the script itself. also, never talk about the amount of paragraphs or lines. just write the script.
8. respond in the same language as the video subject.
    """.trim()

    fun buildScriptPrompt(
        videoSubject: String,
        language: String = "",
        paragraphNumber: Int = 1,
        videoScriptPrompt: String = "",
        customSystemPrompt: String = "",
    ): String {
        val p = (customSystemPrompt.ifBlank { DEFAULT_SCRIPT_SYSTEM_PROMPT }) +
            "\n\n# Initialization:\n- video subject: $videoSubject\n- number of paragraphs: ${paragraphNumber.coerceAtLeast(1)}"
        val lang = if (language.isNotBlank()) "\n- language: $language" else ""
        val extra = if (videoScriptPrompt.isNotBlank()) "\n\n# Additional User Requirements:\n$videoScriptPrompt" else ""
        return p + lang + extra
    }

    fun buildTermsPrompt(amount: Int, matchScriptOrder: Boolean, subject: String, script: String): String {
        val goal = if (matchScriptOrder)
            "Generate $amount chronological stock-video search terms that follow the order of topics in the video script."
        else
            "Generate $amount search terms for stock videos, depending on the subject of a video."
        val ordering = if (matchScriptOrder)
            "6. keep the terms in the same order as the script narration; earlier terms must describe earlier visual moments.\n" else ""
        val example = if (matchScriptOrder) {
            val terms = (listOf("opening visual topic") + (2..maxOf(amount, 1)).map { "script visual topic $it" }).take(amount)
            "[${terms.joinToString(", ") { "\"$it\"" }}]"
        } else {
            "[\"search term 1\", \"search term 2\", \"search term 3\", \"search term 4\", \"search term 5\"]"
        }
        return """
# Role: Video Search Terms Generator

## Goals:
$goal

## Constrains:
1. the search terms are to be returned as a json-array of strings.
2. each search term should consist of 1-3 words, always add the main subject of the video.
3. you must only return the json-array of strings. you must not return anything else. you must not return the script.
4. the search terms must be related to the subject of the video: $subject.
5. reply with english search terms only.
${ordering}
## Output Example:
$example
""".trim() + "\n\n## Video Script:\n$script"
    }

    /**
     * Parse the JSON array from the model response.
     * Parity with upstream _strip_code_fence: non-OpenAI providers wrap JSON in ``` fences.
     */
    fun parseTerms(response: String): List<String> {
        var s = response.trim()
        if (s.startsWith("```")) {
            s = s.removePrefix("```json").removePrefix("```").trim()
            if (s.endsWith("```")) s = s.removeSuffix("```").trim()
        }
        val start = s.indexOf('[')
        val end = s.lastIndexOf(']')
        if (start >= 0 && end > start) s = s.substring(start, end + 1)
        val terms = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(s).map { it.groupValues[1] }.toList()
        return terms.filter { it.isNotBlank() }.map { it.replace("\\\"", "\"") }
    }

    /** Strip reasoning-model wrappers (<think>…</think>) — parity with _normalize_text_response. */
    fun normalizeTextResponse(content: String): String {
        var s = content.trim()
        s = s.replace(Regex("(?s)<think>.*?</think>"), "").trim()
        if (s.startsWith("```")) {
            s = s.removePrefix("```json").removePrefix("```").trim()
            if (s.endsWith("```")) s = s.removeSuffix("```").trim()
        }
        return s.trim()
    }
}
