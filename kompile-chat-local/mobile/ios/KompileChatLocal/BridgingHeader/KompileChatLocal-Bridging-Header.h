/*
 * KompileChatLocal-Bridging-Header.h
 *
 * Exposes C libraries to Swift.  Both libraries are OPTIONAL — the project
 * compiles without native frameworks, but local chat remains unavailable until they are linked.
 * The #if __has_include guards prevent build errors on machines that do not
 * yet have the framework artifacts.
 *
 * When the xcframeworks ARE present, drop them into:
 *   mobile/ios/Frameworks/kompile-reasoning-ios-arm64.xcframework
 *   mobile/ios/Frameworks/sdx-llm-ios.xcframework
 * then re-run `xcodegen generate` and embed+sign them in Xcode.
 */

/* ── 1. Kompile graph-reasoning library ────────────────────────────────────
 *
 * Public header is the checked-in canonical ABI:
 *   kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning-local/
 *     include/kompile_reasoning.h
 *
 * Swift sees: kgr_create_isolate, kgr_attach_thread, kgr_detach_thread,
 *             kgr_tear_down_isolate, kgr_abi_version, kgr_open, kgr_tools,
 *             kgr_dispatch, kgr_save, kgr_free, kgr_close  (as C functions)
 *
 * The module name used in #if canImport() checks is "KompileReasoning"
 * (matches the xcframework's module map — see Frameworks/README inside the
 * xcframework when it ships).
 */
#if __has_include(<KompileReasoning/kompile_reasoning.h>)
#  include <KompileReasoning/kompile_reasoning.h>
#elif __has_include("kompile_reasoning.h")
#  include "kompile_reasoning.h"
#endif

/* ── 2. SDX LLM text-generation library ────────────────────────────────────
 *
 * Binds the sdxLlm* text-generation ABI only.  The old tensor/DSP API
 * (dsp_runtime_c.h / DspRuntimeC) is intentionally NOT imported here;
 * this project binds ONLY the text API described in SdxLlmAbi.java.
 *
 * Swift sees the ABI v2 canonical resolver and tokenizer-aware text surface:
 *             sdxLlmResolveModelBundle, sdxLlmLoadModel,
 *             sdxLlmRenderChatPrompt, sdxLlmGenerate, sdxLlmFree,
 *             sdxLlmGetLastError
 *
 * The module name used in #if canImport() checks is "SdxLlm".
 */
#if __has_include(<SdxLlm/sdx_llm_c.h>)
#  include <SdxLlm/sdx_llm_c.h>
#elif __has_include("sdx_llm_c.h")
#  include "sdx_llm_c.h"
#endif
