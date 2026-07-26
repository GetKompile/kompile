# KompileChatLocal — iOS

SwiftUI source for fully local graph-aware chat through the Kompile reasoning C ABI and
the SDX AOT LLM C ABI. Chat inference runs through the selected on-device SDX accelerator
runtime. The app has no in-app HTTP inference or download client; it does not send prompts or credentials.
Model and knowledge graph data stay on device.

## Build prerequisites

- macOS 14+ with Xcode 16+
- iOS 16+
- XcodeGen 2.x
- KompileReasoning.xcframework
- an SDX LLM xcframework built from ABI v2 (sdx_llm_c.h) for the selected Apple target

Generate the project on macOS:

    cd kompile-chat-local/mobile/ios
    xcodegen generate
    xcodebuild test -scheme KompileChatLocal \
      -destination 'platform=iOS Simulator,name=iPhone 16'
    open KompileChatLocal.xcodeproj

The unit-test target covers staging URL privacy/path rules and transactional manual imports.
The generated .xcodeproj is intentionally not checked in.

## Accelerator builds

project.yml defaults `SDX_TARGET_PROFILE` to `ios-arm64-metal`. This production path uses
the shared libnd4j MLX/DSP replay provider (`sdx.metal.v1`) and a canonical SDZ. The model
does not carry a private `.metallib`: MLX performs device specialization on the Apple GPU and
stores only driver/device compilation data in the app-owned cache. It never falls back to CPU
or a different accelerator.

`ios-arm64-coreml-ane` is reserved for the Core ML/ANE spin, but is not yet an executable chat
profile. The exact `sdx.coreml-ane.v1` provider still needs its framework-specific text-session
bridge; the generic SDX runtime rejects that backend explicitly instead of silently running on
CPU. Enable this override only after that bridge and its matching xcframework are integrated:

    xcodebuild ... SDX_TARGET_PROFILE=ios-arm64-coreml-ane

## Native frameworks

For a release-consumer build, pin the intended release version independently,
then require the canonical `sdx-sdk-manifest.json` to carry that exact
`releaseVersion` and `releaseTag=sdk-v<releaseVersion>`. Select the framework using
exactly `component=runtime`, `packageRole=apple-xcframework`, the target Apple
`platform`, and provider `variant`. Require its classifier to be
`<platform>-<variant>` and packaging to be `xcframework` or `xcframework.zip`.
Preserve the record's exact `fileName`; verify its safe basename, exact byte size,
and SHA-256 before extracting it into `mobile/ios/Frameworks/`. Zero or multiple
matches are release errors. Do not guess an XCFramework filename.

KompileReasoning/KGR remains a separate Kompile-owned framework and is not part of
the SDX manifest selection. After placing both independently verified frameworks in
mobile/ios/Frameworks/, enable their dependency entries in project.yml, then
regenerate the Xcode project. Source-built Apple provider publication is documented
separately in `../APPLE_IOS_SOURCE_SDK.md`.

1. kompile-reasoning-ios-arm64.xcframework
   - local kgr_* graph session and tool dispatch
2. sdx-llm-ios.xcframework
   - libsdx_llm ABI v2
   - canonical SDZ resolution through SdxModelCache
   - compiled bundle execution through the existing JavaCPP SdxRuntime/SdxTextSession
   - explicit tokenizer loading
   - tokenizer-owned chat-template rendering
   - one long-lived OS-thread executor for the Graal isolate handle
   - local generation through the SDX/libnd4j runtime

The source still compiles with guarded imports when frameworks are absent, but chat remains
MODEL REQUIRED; it never switches to a remote inference route.

## Model bootstrap paths

### Preferred: complete compiled SDZ

Settings → Local Model → **Import complete compiled SDZ**:

1. The selected .sdz is copied transactionally into app-owned storage.
2. sdxLlmResolveModelBundle selects the build target (ios-arm64-metal or
   ios-arm64-coreml-ane).
3. Shared SdxModelCache validates bounded archive entries, hashes, cache containment,
   and the selected target; bundle resolution does not compile or fall back.
4. requireTextModelAssets() requires the runtime model, tokenizer.json,
   normalized tokenizer_config.json, and the SDX text-generation graph contract.
5. sdxLlmLoadCompiledModel opens the immutable modelPath with strict target-specific
   SdxRuntime options and creates the shared metadata-driven native SdxTextSession. It does
   not reopen the bundle as a SameDiff file or fall back to the loose GGUF importer.
6. On Metal, MLX specializes the canonical model for the current GPU and a separate
   app-owned cache stores only that device-driver compilation data. Provider selection and
   canonical model identity remain fixed by the SDZ target.
7. The route changes to LOCAL only after the runtime loads that exact bundle and tokenizer.

Kompile staging is responsible for normalizing raw Hugging Face config.json,
generation_config.json, special tokens, and chat-template inputs into the canonical
complete SDZ.

### Manual GGUF/GGML research import

Settings → Local Model → **Import manual GGUF/GGML components** opens one multi-file
picker. Select:

- exactly one .gguf or .ggml;
- tokenizer.json;
- tokenizer_config.json or chat_template.jinja;
- config.json or text-generation.json;
- optional generation_config.json.

The files are copied into one transactional app-owned directory. JSON inputs are validated.
A separate chat template is merged into normalized tokenizer_config.json, and SDX receives
the real tokenizer path. This path is intended for manual model research; the compiled SDZ
path remains the reproducible accelerator build.

## Hugging Face and component staging

The staging section accepts either:

- owner/repository or a canonical public huggingface.co repository/tree/blob/resolve URL;
- advanced public HTTPS component URLs for the same model/tokenizer/config set described
  above.

The app validates the values and opens the Kompile staging /download route in Safari.
Repository and component values are placed only in the URI fragment, then cleared after a
successful handoff. Browsers do not send the fragment in the HTTP request. Only a
credential-free staging base may be saved. There is no URLSession, stored token, API key,
or in-app model downloader.

After the staging service exports a complete target SDZ, save it to Files and import it with
the preferred path.

## Import diagnostics

Settings → **Import log** shows the most recent 32 staging, validation, and model-load events.
Messages are length-bounded and sanitized for URLs, credentials, and local paths, and are
stored in a UserDefaults suite separate from normal settings. Failures include a remediation
hint instead of surfacing a generic “native session cannot be opened” message.

Typical remediation:

- target missing: restage for the accelerator target shown in Settings;
- tokenizer/config missing: export a complete SDZ or select all manual components;
- ABI too old: link the ABI v2 SDX xcframework;
- load failure: inspect Import Log, verify the runtime spin matches the SDZ, then restage.

## Knowledge graph

Import a .kgraph file in Settings. The file is copied into the app container and opened by
the guarded KompileReasoning service. Graph tool calls and OVERVIEW reasoning remain local.

## Architecture

    KompileChatLocalApp
    ├── ContentView (Chat | Settings)
    ├── ChatView
    ├── SettingsView
    │   ├── canonical SDZ picker
    │   ├── manual multi-component picker
    │   ├── external Safari staging handoff
    │   └── sanitized Import Log
    └── Services
        ├── ModelImportStore       transactional app-owned files/manifests
        ├── ModelStagingHandoff    URL validation + fragment handoff
        ├── ImportDiagnostics      bounded durable error/remediation log
        ├── InferenceRouter        complete-bundle load gate
        ├── SdxLlmService          SDX ABI v2 resolver/template/generation
        ├── GraphReasoningService  local kgr_* bridge
        └── ChatEngine             graph tool loop

InferenceRouter no longer contains model-specific prompt tokens. It serializes ordered
role/content messages and calls sdxLlmRenderChatPrompt; SDX applies the chat template loaded
with the tokenizer. Tool results are represented as user content consistently with the local
chat protocol.

## Linux validation boundary

Linux CI can compile/test the Java SDX resolver, JavaCPP runtime policy, public C header,
and `cmake/tests/IosOfflineContractTest.cmake`. It cannot produce or execute an iOS
xcframework/app. The remaining macOS gates are Xcode/Swift compilation, the Graal Apple
cross-build and xcframework assembly, codesigning, and physical-device MLX/Metal
execution. Core ML additionally remains gated on the exact provider text-session bridge.
