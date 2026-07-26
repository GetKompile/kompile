cmake_minimum_required(VERSION 3.20)

if(NOT DEFINED IOS_ROOT OR "${IOS_ROOT}" STREQUAL "")
  get_filename_component(IOS_ROOT "${CMAKE_CURRENT_LIST_DIR}/../.." ABSOLUTE)
endif()

set(_app_root "${IOS_ROOT}/KompileChatLocal")
set(_settings "${_app_root}/App/AppSettings.swift")
set(_router "${_app_root}/Services/InferenceRouter.swift")
set(_sdx_service "${_app_root}/Services/SdxLlmService.swift")
set(_graph_service "${_app_root}/Services/GraphReasoningService.swift")
set(_remote_service "${_app_root}/Services/RemoteChatService.swift")
set(_settings_view "${_app_root}/Views/SettingsView.swift")
set(_chat_view "${_app_root}/Views/ChatView.swift")
set(_bundle "${_app_root}/Models/LocalModelBundle.swift")
set(_import_store "${_app_root}/Services/ModelImportStore.swift")
set(_staging_handoff "${_app_root}/Services/ModelStagingHandoff.swift")
set(_diagnostics "${_app_root}/Services/ImportDiagnostics.swift")
set(_project "${IOS_ROOT}/project.yml")
set(_plist "${_app_root}/Info.plist")
set(_readme "${IOS_ROOT}/README.md")
set(_bridging_header
  "${_app_root}/BridgingHeader/KompileChatLocal-Bridging-Header.h")
set(_staging_tests "${IOS_ROOT}/KompileChatLocalTests/ModelStagingHandoffTests.swift")
set(_import_tests "${IOS_ROOT}/KompileChatLocalTests/ModelImportStoreTests.swift")
get_filename_component(_kompile_root "${IOS_ROOT}/../../.." ABSOLUTE)
set(_canonical_sdx_header
  "${_kompile_root}/../deeplearning4j/nd4j/sdx-aot/include/sdx_llm_c.h")

function(_ios_require_file path label)
  if(NOT EXISTS "${path}" OR IS_DIRECTORY "${path}")
    message(FATAL_ERROR "${label} is missing: ${path}")
  endif()
endfunction()

function(_ios_assert_contains path needle label)
  _ios_require_file("${path}" "${label}")
  file(READ "${path}" content)
  string(FIND "${content}" "${needle}" found)
  if(found EQUAL -1)
    message(FATAL_ERROR "${label} is missing required marker '${needle}' in ${path}")
  endif()
endfunction()

function(_ios_assert_absent path needle label)
  _ios_require_file("${path}" "${label}")
  file(READ "${path}" content)
  string(FIND "${content}" "${needle}" found)
  if(NOT found EQUAL -1)
    message(FATAL_ERROR "${label} contains forbidden marker '${needle}' in ${path}")
  endif()
endfunction()

foreach(required IN ITEMS
    "${_settings}"
    "${_router}"
    "${_sdx_service}"
    "${_graph_service}"
    "${_settings_view}"
    "${_chat_view}"
    "${_bundle}"
    "${_import_store}"
    "${_staging_handoff}"
    "${_diagnostics}"
    "${_project}"
    "${_plist}"
    "${_readme}"
    "${_bridging_header}"
    "${_staging_tests}"
    "${_import_tests}"
    "${_canonical_sdx_header}")
  _ios_require_file("${required}" "iOS offline source")
endforeach()

if(EXISTS "${_remote_service}")
  message(FATAL_ERROR
    "Direct remote chat client must not be present in the local-only iOS app: ${_remote_service}")
endif()

file(GLOB_RECURSE _swift_sources "${_app_root}/*.swift")
if(NOT _swift_sources)
  message(FATAL_ERROR "No Swift sources found below ${_app_root}")
endif()

# Reject in-app chat transports. An explicit external-browser model-staging handoff
# may still use UIApplication.shared.open or SwiftUI Link; neither is rejected here.
set(_direct_chat_network_markers
  "URLSession"
  "URLRequest"
  "RemoteChatService"
  "/v1/chat/completions"
  "forHTTPHeaderField: \"Authorization\""
  "Bearer "
  "SecureField(\"API Key"
  "Text(\"Remote Endpoint\")")
foreach(source IN LISTS _swift_sources)
  foreach(marker IN LISTS _direct_chat_network_markers)
    _ios_assert_absent("${source}" "${marker}" "iOS direct-chat network contract")
  endforeach()
endforeach()

foreach(marker IN ITEMS
    "@AppStorage(\"remoteBaseUrl\")"
    "@AppStorage(\"remoteModel\")"
    "@AppStorage(\"remoteApiKey\")"
    "var remoteBaseUrl"
    "var remoteModel"
    "var remoteApiKey")
  _ios_assert_absent("${_settings}" "${marker}" "legacy remote setting surface")
endforeach()

_ios_assert_contains("${_settings}" "settingsMigration.localOnly"
  "local-only settings migration")
_ios_assert_contains("${_settings}" "legacyRemoteKeys"
  "local-only settings migration")
_ios_assert_contains("${_settings}" "remoteBaseUrl"
  "legacy endpoint cleanup")
_ios_assert_contains("${_settings}" "remoteModel"
  "legacy model cleanup")
_ios_assert_contains("${_settings}" "remoteApiKey"
  "legacy credential cleanup")
_ios_assert_contains("${_settings}" "defaults.removeObject(forKey: key)"
  "legacy setting deletion")

foreach(source IN LISTS _swift_sources)
  if(NOT source STREQUAL "${_settings}")
    foreach(marker IN ITEMS "remoteBaseUrl" "remoteModel" "remoteApiKey")
      _ios_assert_absent("${source}" "${marker}" "legacy remote setting isolation")
    endforeach()
  endif()
endforeach()

_ios_assert_contains("${_sdx_service}" "import Combine"
  "SDX ObservableObject compile contract")
_ios_assert_contains("${_graph_service}" "import Combine"
  "graph ObservableObject compile contract")
_ios_assert_contains("${_sdx_service}" "sdxLlmResolveModelBundle"
  "canonical SDZ resolver binding")
_ios_assert_contains("${_sdx_service}" "sdxLlmRenderChatPrompt"
  "tokenizer-owned prompt binding")
_ios_assert_contains("${_sdx_service}" "sdxLlmLoadCompiledModel("
  "canonical JavaCPP SDX text-session load")
_ios_assert_contains("${_sdx_service}" "case .manualComponents:"
  "advanced loose-model import branch")
_ios_assert_contains("${_sdx_service}" "handle = sdxLlmLoadModel("
  "advanced loose-model runtime load")
_ios_assert_contains("${_sdx_service}" "deviceCompilationCacheDirectory"
  "app-owned device-driver cache")
_ios_assert_contains("${_sdx_service}" "SdxAffineExecutor"
  "Graal isolate thread-affine executor")
_ios_assert_contains("${_sdx_service}" "Thread.current === worker"
  "Graal isolate same-thread reentrancy")
_ios_assert_contains("${_router}" "loadModel(bundle: bundle)"
  "complete model-bundle load routing")
_ios_assert_contains("${_router}" "renderChatPrompt(messages: messages)"
  "tokenizer-owned chat routing")
_ios_assert_contains("${_router}" "sdxService.isModelLoaded"
  "loaded-model route gate")
foreach(marker IN ITEMS "<|system|>" "<|assistant|>" "buildLocalPrompt")
  _ios_assert_absent("${_router}" "${marker}" "hard-coded iOS prompt template")
endforeach()
_ios_assert_contains("${_import_store}" "installManualComponents"
  "manual component installer")
_ios_assert_contains("${_import_store}" "tokenizer_config.json"
  "manual tokenizer configuration")
_ios_assert_contains("${_import_store}" "chat_template.jinja"
  "manual chat-template component")
_ios_assert_contains("${_bundle}" "textGenerationConfigPath"
  "complete local model contract")
_ios_assert_contains("${_staging_handoff}" "percentEncodedFragment"
  "transient staging source fragment")
_ios_assert_contains("${_staging_handoff}" "components.percentEncodedQuery == nil"
  "credential-free staging base")
_ios_assert_contains("${_staging_handoff}" "scheme == \"https\" || (scheme == \"http\" && isLoopback(host))"
  "HTTPS staging transport")
_ios_assert_contains("${_staging_handoff}" "last == \"download\""
  "exact staging endpoint path")
_ios_assert_contains("${_settings_view}" "UIApplication.shared.open"
  "external Safari staging handoff")
_ios_assert_contains("${_settings_view}" "startAccessingSecurityScopedResource()"
  "synchronous document access acquisition")
_ios_assert_contains("${_settings_view}" "replaceItemAt("
  "transactional knowledge graph replacement")
_ios_assert_contains("${_diagnostics}" "maxEntries = 32"
  "bounded durable diagnostics")
_ios_assert_contains("${_project}" "SDX_TARGET_PROFILE: ios-arm64-metal"
  "default iOS accelerator target")
_ios_assert_contains("${_project}" "KompileChatLocalTests:"
  "iOS unit test target")
_ios_assert_contains("${_staging_tests}" "testRemoteHttpAndEveryExistingQueryAreRejected"
  "staging transport and query tests")
_ios_assert_contains("${_staging_tests}" "testDownloadPathComparisonUsesWholeFinalComponent"
  "staging endpoint path test")
_ios_assert_contains("${_import_tests}" "testInvalidTokenizerRollsBackPartialInstall"
  "manual import rollback test")
_ios_assert_contains("${_plist}" "$(SDX_TARGET_PROFILE)"
  "build-selected iOS accelerator target")
_ios_assert_contains("${_router}" "case local = \"LOCAL\""
  "local route")
_ios_assert_absent("${_router}" "case remote"
  "remote inference route")
_ios_assert_absent("${_settings_view}" "Remote Endpoint"
  "remote settings UI")
_ios_assert_contains("${_chat_view}" "Import a compatible local model in Settings"
  "local model bootstrap guidance")
_ios_assert_absent("${_chat_view}" "REMOTE"
  "remote route badge")

foreach(path IN ITEMS "${_project}" "${_plist}")
  foreach(marker IN ITEMS
      "CODE_SIGN_ENTITLEMENTS"
      "com.apple.security.network.client"
      "NSAppTransportSecurity"
      "NSAllowsArbitraryLoads"
      "NSLocalNetworkUsageDescription"
      "NSBonjourServices")
    _ios_assert_absent("${path}" "${marker}" "iOS network entitlement contract")
  endforeach()
endforeach()

foreach(marker IN ITEMS
    "Remote chat (OpenAI-compat)"
    "remote-only mode"
    "RemoteChatService")
  _ios_assert_absent("${_readme}" "${marker}" "iOS local-only documentation")
endforeach()
_ios_assert_contains("${_readme}" "Chat inference"
  "iOS local-only documentation")
_ios_assert_contains("${_readme}" "does not send prompts or credentials"
  "iOS local-only documentation")
_ios_assert_contains("${_bridging_header}" "<SdxLlm/sdx_llm_c.h>"
  "canonical SDX bridging header")
_ios_assert_absent("${_bridging_header}" "sdx_llm.h"
  "legacy SDX bridging header fallback")

_ios_assert_contains("${_canonical_sdx_header}" "SDX_LLM_ABI_VERSION = 2"
  "canonical SDX AOT ABI v2 header")
_ios_assert_contains("${_canonical_sdx_header}" "SDX_LLM_HAS_MODEL_BUNDLE_API 1"
  "canonical SDX model-bundle feature gate")
_ios_assert_contains("${_canonical_sdx_header}" "SDX_LLM_HAS_COMPILED_TEXT_SESSION_API 1"
  "canonical SDX compiled text-session feature gate")
_ios_assert_contains("${_canonical_sdx_header}" "sdxLlmResolveModelBundle("
  "canonical SDX resolver declaration")
_ios_assert_contains("${_canonical_sdx_header}" "sdxLlmLoadCompiledModel("
  "canonical SDX compiled model declaration")
_ios_assert_contains("${_canonical_sdx_header}" "sdxLlmRenderChatPrompt("
  "canonical SDX tokenizer prompt declaration")

message(STATUS
  "iOS local-only canonical JavaCPP text-session/manual import, tokenizer chat-template, Safari handoff, diagnostics, and no-direct-chat-network contract passed")
