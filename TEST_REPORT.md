# TEST REPORT

## Scope
Per TEST plan: unit tests (JVM) run in CI; instrumented/device tests require a physical device (not available in CI) — documented below with the manual smoke-test checklist.

## Unit tests (run: `./gradlew testDebugUnitTest`)

`PipelineTest.kt` — 15 assertions across 13 tests:

| Test | What it proves |
|---|---|
| srt_timestamp_format | SRT timestamps byte-exact (`00:00:01,500`, `01:02:03,004`) |
| srt_write_parse_roundtrip | write→parse preserves cues/timing/text |
| sentence_grouping_by_punctuation | edge-tts word boundaries → sentence cues (CJK + latin punctuation) |
| word_by_word_mode | word-by-word display mode |
| color_converts_rgb_to_bgr | #RRGGBB → ASS &H00BBGGRR conversion (libass) |
| force_style_px_exact_with_playres | PlayResX/Y = video size → FontSize is px-exact (upstream parity) |
| position_alignment_mapping | top/center/bottom → ASS alignment 8/5/2 |
| script_prompt_contains_upstream_markers | prompts are verbatim upstream templates |
| terms_prompt_json_array | terms prompt parity |
| terms_parser_strips_code_fence | non-OpenAI fence handling (upstream _strip_code_fence) |
| normalize_strips_thinking_block | reasoning-model output sanitization (upstream _normalize_text_response) |
| aspect_resolution_parity | 9:16/16:9/1:1 → exact upstream resolutions |
| config_json_roundtrip | TaskConfig (de)serialization lossless |
| fit_filter_cover_and_contain | scale/crop/pad filtergraph strings (upstream _fit_clip_to_canvas) |
| concat_list_quotes_single_quotes | concat demuxer escaping (upstream _escape_ffmpeg_concat_path) |
| subtitles_filter_escapes_paths | subtitles filter path/font/style escaping |
| duration_parsing | ffmpeg `-i` duration extraction |

## CI gates (android.yml)
1. FFmpeg cross-compile succeeds + ELF/aarch64 verification
2. Unit tests pass
3. assembleDebug + assembleRelease succeed
4. APK contains `lib/*/libffmpeg_exec.so` (verified by unzip listing)

## Device smoke test checklist (requires a real device — NOT RUN in CI)

1. Launch → Home renders, bottom nav works
2. Create project → appears in Projects
3. Configure provider (base URL + key) → Test = ✔
4. Set Pexels key
5. New video: topic → Generate Script (needs LLM API)
6. Generate Keywords
7. Queue video (Generate Everything) → task runs in background notification
8. Progress advances stage by stage (script→terms→materials→audio→subtitle→combine)
9. Completed → History shows entry → Play opens the video
10. Cancel mid-render → task CANCELLED
11. Batch mode: 3 topics → 3 queued tasks
12. Dark mode toggle → UI adapts
13. Language switch (fa/zh/en) → strings localize, RTL works

Status: ⬜ pending on-device run (APK artifact will be produced by CI; results to be appended).
