cmake_minimum_required(VERSION 3.20)

include("${CMAKE_CURRENT_LIST_DIR}/FinalOfflineDistributionContract.cmake")
include("${CMAKE_CURRENT_LIST_DIR}/SdxReleaseArtifactResolver.cmake")

if(NOT DEFINED MODE OR "${MODE}" STREQUAL "")
  message(FATAL_ERROR
    "MODE is required (PLAN, VALIDATE, STAGE, VERIFY_STAGE, PACKAGE, or VERIFY_ARCHIVE)")
endif()
string(TOUPPER "${MODE}" MODE)
if(NOT MODE MATCHES "^(PLAN|VALIDATE|STAGE|VERIFY_STAGE|PACKAGE|VERIFY_ARCHIVE)$")
  message(FATAL_ERROR "Unknown MODE: ${MODE}")
endif()

# id|source variable|input checksum variable|expected checksum variable|kind|
# staged path|write staged checksum|required text
set(_artifact_contracts
  "vulkan-apk|VULKAN_APK|VULKAN_APK_SHA256|EXPECTED_VULKAN_SHA256|zip|artifacts/android/kompile-offline-graph-chat-vulkan.apk|ON|NONE"
  "tensor-g3-apk|TENSOR_G3_APK|TENSOR_G3_APK_SHA256|EXPECTED_TENSOR_G3_SHA256|zip|artifacts/android/kompile-offline-graph-chat-tensor-g3-pixel-8a.apk|ON|NONE"
  "vulkan-aar|VULKAN_AAR|NONE|EXPECTED_VULKAN_AAR_SHA256|zip|artifacts/runtime/sdx-runtime-android-arm64-vulkan.aar|ON|NONE"
  "tensor-g3-aar|TENSOR_G3_AAR|NONE|EXPECTED_TENSOR_G3_AAR_SHA256|zip|artifacts/runtime/sdx-runtime-android-arm64-tensor-g3.aar|ON|NONE"
  "staging-exec|STAGING_EXEC_JAR|NONE|EXPECTED_STAGING_JAR_SHA256|zip|artifacts/staging/kompile-model-staging-exec.jar|ON|NONE"
  "accelerators|ACCELERATOR_CONFIG|NONE|NONE|text|artifacts/contracts/accelerators.json|OFF|NONE"
  "offline-assets|OFFLINE_ASSETS_CONFIG|NONE|NONE|text|artifacts/contracts/offline-assets.json|OFF|NONE"
  "vulkan-provider|VULKAN_PROVIDER_MANIFEST|NONE|NONE|text|artifacts/contracts/provider-vulkan.json|OFF|NONE"
  "tensor-g3-provider|TENSOR_G3_PROVIDER_MANIFEST|NONE|NONE|text|artifacts/contracts/provider-tensor-g3.json|OFF|NONE"
  "graph-sdk|GRAPH_SDK|NONE|NONE|zip|artifacts/graph/kompile-graph-reasoning-native-sdk.zip|ON|NONE"
  "graph-runtime|GRAPH_LIBRARY|NONE|NONE|elf|artifacts/graph/android-arm64-v8a/libkompile_reasoning_android.so|ON|NONE"
  "graph-javacpp|GRAPH_JAVACPP_LIBRARY|NONE|NONE|elf|artifacts/graph/android-arm64-v8a/libjnikompile_graph.so|ON|NONE"
  "graph-header|GRAPH_HEADER|NONE|NONE|text|artifacts/graph/include/kompile_reasoning.h|OFF|#define KGR_ABI_VERSION 1"
  "graph-fixture|GRAPH_FIXTURE|NONE|NONE|binary|artifacts/graph/fixture.kgraph|OFF|NONE"
  "canonical-sdz|CANONICAL_SDZ|NONE|NONE|zip|artifacts/models/canonical-mlp.sdz|ON|NONE"
  "canonical-sdz-readme|CANONICAL_SDZ_README|NONE|NONE|text|artifacts/models/canonical-mlp.README.md|OFF|NONE")

# id|manifest variable|providerId|artifactFormat|target|requires AOT|runtime JIT|CPU fallback
set(_provider_contracts
  "vulkan|VULKAN_PROVIDER_MANIFEST|sdx.vulkan.v1|sdx-vulkan-plan|Android_Vulkan_1_1|ON|OFF|OFF"
  "tensor-g3|TENSOR_G3_PROVIDER_MANIFEST|sdx.nnapi-tensor-g3.v1|sdx-nnapi-plan|Tensor_G3|ON|OFF|OFF")

# id|APK verifier variant|APK variable|runtime AAR variable
set(_apk_contracts
  "vulkan|vulkan|VULKAN_APK|VULKAN_AAR"
  "tensor-g3|tensorG3|TENSOR_G3_APK|TENSOR_G3_AAR")

# id|source variable|stable logical source path
set(_build_provenance_contracts
  "vulkan-apk|VULKAN_APK|artifacts/android/kompile-offline-graph-chat-vulkan.apk"
  "tensor-g3-apk|TENSOR_G3_APK|artifacts/android/kompile-offline-graph-chat-tensor-g3-pixel-8a.apk"
  "vulkan-aar|VULKAN_AAR|artifacts/runtime/sdx-runtime-android-arm64-vulkan.aar"
  "tensor-g3-aar|TENSOR_G3_AAR|artifacts/runtime/sdx-runtime-android-arm64-tensor-g3.aar"
  "staging-exec|STAGING_EXEC_JAR|artifacts/staging/kompile-model-staging-exec.jar")

function(_final_validate_archive_member_hash archive member expected label)
  fod_validate_hash_literal("${expected}" "${label} expected SHA-256")
  if(DEFINED ENV{TMPDIR} AND NOT "$ENV{TMPDIR}" STREQUAL "")
    set(temp_root "$ENV{TMPDIR}")
  else()
    set(temp_root "/tmp")
  endif()
  string(RANDOM LENGTH 16 ALPHABET 0123456789abcdef nonce)
  set(extract_root "${temp_root}/kompile-aar-member-${nonce}")
  file(REMOVE_RECURSE "${extract_root}")
  file(MAKE_DIRECTORY "${extract_root}")
  file(ARCHIVE_EXTRACT INPUT "${archive}" DESTINATION "${extract_root}"
    PATTERNS "${member}")
  fod_validate_exact_sha256("${extract_root}/${member}" "${expected}" "${label}")
  file(REMOVE_RECURSE "${extract_root}")
endfunction()

function(_final_verify_staging_jar jar)
  fod_require_nonempty_file("${jar}" "model-staging executable JAR")
  execute_process(
    COMMAND "${CMAKE_COMMAND}" -E tar tf "${jar}"
    RESULT_VARIABLE list_result
    OUTPUT_VARIABLE names_text
    ERROR_VARIABLE list_error)
  if(NOT list_result EQUAL 0)
    message(FATAL_ERROR
      "cannot list model-staging JAR (rc=${list_result}): ${list_error}")
  endif()
  string(REPLACE "\r\n" "\n" names_text "${names_text}")
  string(REPLACE "\n" ";" names "${names_text}")
  foreach(required_exec_member IN ITEMS
      "META-INF/MANIFEST.MF"
      "BOOT-INF/classes/ai/kompile/staging/ModelStagingApplication.class"
      "org/springframework/boot/loader/launch/JarLauncher.class")
    list(FIND names "${required_exec_member}" required_exec_index)
    if(required_exec_index EQUAL -1)
      message(FATAL_ERROR
        "model-staging JAR is not the executable Spring Boot artifact; missing ${required_exec_member}")
    endif()
  endforeach()
  set(static_prefix "BOOT-INF/classes/static/model-staging/")
  foreach(family IN ITEMS main polyfills styles)
    set(matches "")
    foreach(name IN LISTS names)
      if(name MATCHES "^${static_prefix}${family}-[^/]+\\.(js|css)$")
        list(APPEND matches "${name}")
      endif()
    endforeach()
    list(LENGTH matches match_count)
    if(NOT match_count EQUAL 1)
      message(FATAL_ERROR
        "model-staging JAR must contain exactly one current ${family} bundle, found ${match_count}: ${matches}")
    endif()
    set(_jar_${family} "${matches}")
  endforeach()
  set(root_code "")
  foreach(name IN LISTS names)
    if(name MATCHES "^${static_prefix}[^/]+\\.(js|css)$")
      list(APPEND root_code "${name}")
    endif()
  endforeach()
  list(LENGTH root_code root_code_count)
  if(NOT root_code_count EQUAL 3)
    message(FATAL_ERROR
      "model-staging JAR contains stale or unexpected root bundles: ${root_code}")
  endif()

  if(DEFINED ENV{TMPDIR} AND NOT "$ENV{TMPDIR}" STREQUAL "")
    set(temp_root "$ENV{TMPDIR}")
  else()
    set(temp_root "/tmp")
  endif()
  string(RANDOM LENGTH 16 ALPHABET 0123456789abcdef nonce)
  set(extract_root "${temp_root}/kompile-staging-jar-${nonce}")
  file(REMOVE_RECURSE "${extract_root}")
  file(MAKE_DIRECTORY "${extract_root}")
  file(ARCHIVE_EXTRACT INPUT "${jar}" DESTINATION "${extract_root}"
    PATTERNS "META-INF/MANIFEST.MF" "${static_prefix}*")
  fod_require_text("${extract_root}/META-INF/MANIFEST.MF"
    "Main-Class: org.springframework.boot.loader.launch.JarLauncher"
    "model-staging executable manifest")
  fod_require_text("${extract_root}/META-INF/MANIFEST.MF"
    "Start-Class: ai.kompile.staging.ModelStagingApplication"
    "model-staging executable manifest")
  set(static_root "${extract_root}/${static_prefix}")
  fod_require_nonempty_file("${static_root}/index.html"
    "model-staging packaged index")
  file(READ "${static_root}/index.html" index_text)
  foreach(family IN ITEMS main polyfills styles)
    get_filename_component(bundle_name "${_jar_${family}}" NAME)
    string(FIND "${index_text}" "${bundle_name}" reference_index)
    if(reference_index EQUAL -1)
      message(FATAL_ERROR
        "model-staging index.html does not reference current ${family} bundle ${bundle_name}")
    endif()
  endforeach()
  get_filename_component(main_name "${_jar_main}" NAME)
  set(required_staging_ui_text
    "owner/repo or https://huggingface.co/owner/repo"
    "Discover GGUF and tokenizer files"
    "Select one GGUF/GGML model"
    "tokenizer.json"
    "tokenizer_config.json"
    "Import diagnostics"
    "Sanitized server history"
    "Next step:")
  foreach(required_text IN LISTS required_staging_ui_text)
    fod_require_text("${static_root}/${main_name}" "${required_text}"
      "model-staging Hugging Face/text-model diagnostics UI")
  endforeach()
  file(REMOVE_RECURSE "${extract_root}")
endfunction()

function(_final_verify_stage stage)
  fod_require_directory("${stage}" "distribution stage")
  cmake_path(ABSOLUTE_PATH stage NORMALIZE OUTPUT_VARIABLE stage_path)
  set(manifest_path "${stage_path}/MANIFEST.json")
  fod_require_nonempty_file("${manifest_path}" "distribution manifest")

  fod_json_expect("${manifest_path}" "2" schemaVersion)
  fod_json_expect("${manifest_path}" "kompile-offline-graph-chat" name)
  fod_json_expect("${manifest_path}" "pixel-8a-research" distributionProfile)
  fod_json_expect("${manifest_path}" "1980-01-01T00:00:00" archiveTimestamp)
  fod_json_expect("${manifest_path}" "ZIP-DOS-wall-clock" archiveTimestampBasis)
  fod_json_expect("${manifest_path}" "OFF" networkPermission)
  fod_json_expect("${manifest_path}" "OFF" modelsIncluded)
  fod_json_expect("${manifest_path}" "ON" stagingExecutableIncluded)
  fod_json_expect("${manifest_path}" "2" android apkCount)
  fod_json_expect("${manifest_path}" "arm64-v8a" android abi)
  fod_json_expect("${manifest_path}" "sdx.vulkan.v1" android providers 0)
  fod_json_expect("${manifest_path}" "sdx.nnapi-tensor-g3.v1" android providers 1)
  fod_json_expect("${manifest_path}" "OFF" android cpuFallback)
  fod_json_expect("${manifest_path}" "OFF" android openBlas)
  fod_json_expect("${manifest_path}" "OFF" android physicalDeviceExecutionPerformed)
  fod_json_expect("${manifest_path}" "ON" appleIos sourceOnly)
  fod_json_expect("${manifest_path}" "OFF" appleIos builtOnThisLinuxHost)
  fod_json_expect("${manifest_path}" "ON" appleIos metalProviderSourceIncluded)
  fod_json_expect("${manifest_path}" "ON" appleIos metalAotProviderSourceIncluded)
  fod_json_expect("${manifest_path}" "ON" appleIos coreMlAneProviderSourceIncluded)
  fod_json_expect("${manifest_path}" "ON" appleIos swiftIntegrationSourceIncluded)
  fod_json_expect("${manifest_path}" "OFF" appleIos defaultMetallibIncluded)
  fod_json_expect("${manifest_path}" "ON"
    appleIos defaultMetallibBuildAndStagingContractIncluded)
  fod_json_expect("${manifest_path}" "OFF" appleIos mlxBinaryIncluded)
  fod_json_expect("${manifest_path}" "OFF" appleIos coreMlPackageIncluded)
  fod_json_expect("${manifest_path}" "ON"
    appleIos licenseMaterializationContractIncluded)
  fod_json_expect("${manifest_path}" "MLX-LICENSE.txt"
    appleIos appleBuildLicenseResources 0)
  fod_json_expect("${manifest_path}" "METAL-CPP-LICENSE.txt"
    appleIos appleBuildLicenseResources 1)
  fod_json_expect("${manifest_path}" "ON" appleIos provenanceIncluded)

  file(READ "${manifest_path}" manifest)
  string(JSON provenance_count ERROR_VARIABLE provenance_error LENGTH
    "${manifest}" buildInputs)
  list(LENGTH _build_provenance_contracts expected_provenance_count)
  if(provenance_error OR NOT provenance_count EQUAL expected_provenance_count)
    message(FATAL_ERROR
      "manifest buildInputs must contain ${expected_provenance_count} records (${provenance_error})")
  endif()
  set(provenance_ids "")
  if(provenance_count GREATER 0)
    math(EXPR provenance_last "${provenance_count} - 1")
    foreach(index RANGE 0 ${provenance_last})
      foreach(field IN ITEMS id sourcePath sha256)
        string(JSON value ERROR_VARIABLE field_error GET "${manifest}"
          buildInputs ${index} ${field})
        if(field_error OR "${value}" STREQUAL "")
          message(FATAL_ERROR
            "manifest buildInputs record ${index} field '${field}' is invalid: ${field_error}")
        endif()
        set(provenance_${field} "${value}")
      endforeach()
      list(GET _build_provenance_contracts ${index} expected_provenance_row)
      fod_parse_row("${expected_provenance_row}" expected_provenance 3)
      if(NOT provenance_id STREQUAL expected_provenance_0 OR
         NOT provenance_sourcePath STREQUAL expected_provenance_2)
        message(FATAL_ERROR
          "manifest build input ${index} identity/path mismatch: ${provenance_id}|${provenance_sourcePath}")
      endif()
      fod_validate_hash_literal("${provenance_sha256}"
        "manifest build input ${provenance_id} SHA-256")
      list(APPEND provenance_ids "${provenance_id}")
      if(provenance_id STREQUAL "vulkan-aar")
        string(JSON member_path ERROR_VARIABLE member_path_error GET "${manifest}"
          buildInputs ${index} verifiedMember path)
        string(JSON member_hash ERROR_VARIABLE member_hash_error GET "${manifest}"
          buildInputs ${index} verifiedMember sha256)
        if(member_path_error OR member_hash_error OR
           NOT member_path STREQUAL "jni/arm64-v8a/libjnisdx.so")
          message(FATAL_ERROR
            "manifest Vulkan AAR member provenance is missing or invalid")
        endif()
        fod_validate_hash_literal("${member_hash}"
          "manifest Vulkan libjnisdx SHA-256")
      endif()
    endforeach()
  endif()
  set(expected_provenance_ids "")
  foreach(row IN LISTS _build_provenance_contracts)
    fod_parse_row("${row}" provenance 3)
    list(APPEND expected_provenance_ids "${provenance_0}")
  endforeach()
  if(NOT provenance_ids STREQUAL expected_provenance_ids)
    message(FATAL_ERROR
      "manifest build input IDs/order mismatch: ${provenance_ids}")
  endif()

  set(required_members
    "README.md"
    "docs/MODEL_IMPORT.md"
    "docs/APPLE_IOS_SOURCE_SDK.md"
    "docs/ANDROID_ACCELERATORS.md"
    "docs/MODEL_STAGING.md"
    "licenses/apple/MLX-PROVENANCE.md"
    "licenses/apple/LICENSE-MATERIALIZATION.md"
    "source/deeplearning4j/libnd4j/cmake/SdxAppleProviderTargets.cmake"
    "source/deeplearning4j/libnd4j/cmake/patches/mlx-0.31.1-ios.patch"
    "source/deeplearning4j/libnd4j/include/dsp/runtime/apple/SdxMetalProvider.mm"
    "source/deeplearning4j/libnd4j/include/dsp/runtime/apple/SdxMetalAotProvider.mm"
    "source/deeplearning4j/libnd4j/include/dsp/runtime/apple/SdxCoreMlAneProvider.mm"
    "source/deeplearning4j/libnd4j/include/dsp/runtime/apple/swift/SdxAppleProvider.swift")
  foreach(row IN LISTS _artifact_contracts)
    fod_parse_row("${row}" artifact 8)
    list(APPEND required_members "${artifact_5}")
    if(artifact_6 STREQUAL "ON")
      list(APPEND required_members "${artifact_5}.sha256")
    endif()
  endforeach()
  foreach(required_member IN LISTS required_members)
    fod_require_nonempty_file("${stage_path}/${required_member}"
      "required staged distribution member")
  endforeach()
  fod_verify_source_payload_policy("${stage_path}")
  fod_verify_source_only_apple_tree("${stage_path}")

  string(JSON file_count ERROR_VARIABLE length_error LENGTH "${manifest}" files)
  if(length_error)
    message(FATAL_ERROR "manifest files array is invalid: ${length_error}")
  endif()
  set(listed "")
  if(file_count GREATER 0)
    math(EXPR last_index "${file_count} - 1")
    foreach(index RANGE 0 ${last_index})
      foreach(field IN ITEMS path role size sha256)
        string(JSON value ERROR_VARIABLE field_error GET "${manifest}"
          files ${index} ${field})
        if(field_error)
          message(FATAL_ERROR
            "manifest file record ${index} field '${field}' is invalid: ${field_error}")
        endif()
        set(${field} "${value}")
      endforeach()
      fod_validate_relative_path("${path}" "manifest member")
      list(FIND listed "${path}" duplicate_index)
      if(NOT duplicate_index EQUAL -1)
        message(FATAL_ERROR "duplicate manifest path: ${path}")
      endif()
      fod_role_for("${path}" expected_role)
      if(NOT role STREQUAL expected_role)
        message(FATAL_ERROR
          "manifest role mismatch for ${path}: expected ${expected_role}, got ${role}")
      endif()
      fod_validate_hash_literal("${sha256}" "manifest SHA-256 for ${path}")
      list(APPEND listed "${path}")
      fod_require_nonempty_file("${stage_path}/${path}" "manifest member")
      file(SIZE "${stage_path}/${path}" actual_size)
      fod_file_sha256("${stage_path}/${path}" actual_sha)
      if(NOT actual_size EQUAL size OR NOT actual_sha STREQUAL sha256)
        message(FATAL_ERROR "manifest integrity mismatch: ${path}")
      endif()
    endforeach()
  endif()

  file(GLOB_RECURSE actual RELATIVE "${stage_path}" LIST_DIRECTORIES false
    "${stage_path}/*")
  list(REMOVE_ITEM actual "MANIFEST.json")
  list(SORT actual)
  list(SORT listed)
  if(NOT actual STREQUAL listed)
    message(FATAL_ERROR
      "stage contains unmanifested or missing members\nactual=${actual}\nmanifest=${listed}")
  endif()
endfunction()

if(MODE STREQUAL "VERIFY_STAGE")
  fod_require_variable(STAGE " for MODE=VERIFY_STAGE")
  _final_verify_stage("${STAGE}")
  message(STATUS
    "Final Pixel 8a stage verified: two Android APKs and truthful source-only Apple SDK.")
  return()
endif()

if(MODE STREQUAL "PACKAGE")
  fod_require_variable(STAGE " for MODE=PACKAGE")
  fod_require_variable(OUTPUT_ARCHIVE " for MODE=PACKAGE")
  _final_verify_stage("${STAGE}")
  fod_create_deterministic_archive("${STAGE}" "${OUTPUT_ARCHIVE}")
  fod_file_sha256("${OUTPUT_ARCHIVE}" archive_hash)
  message(STATUS "Created deterministic archive ${OUTPUT_ARCHIVE}")
  message(STATUS "SHA-256 ${archive_hash}")
  return()
endif()

if(MODE STREQUAL "VERIFY_ARCHIVE")
  fod_require_variable(BUNDLE " for MODE=VERIFY_ARCHIVE")
  if(NOT DEFINED BUNDLE_SHA256 OR "${BUNDLE_SHA256}" STREQUAL "")
    set(BUNDLE_SHA256 "${BUNDLE}.sha256")
  endif()
  fod_validate_self_sidecar("${BUNDLE}" "${BUNDLE_SHA256}"
    "final distribution archive")
  fod_verify_archive_layout("${BUNDLE}" archive_names)
  fod_verify_archive_metadata("${BUNDLE}")
  if(DEFINED ENV{TMPDIR} AND NOT "$ENV{TMPDIR}" STREQUAL "")
    set(temp_root "$ENV{TMPDIR}")
  else()
    set(temp_root "/tmp")
  endif()
  string(RANDOM LENGTH 16 ALPHABET 0123456789abcdef nonce)
  set(extract_root "${temp_root}/kompile-final-verify-${nonce}")
  file(REMOVE_RECURSE "${extract_root}")
  file(MAKE_DIRECTORY "${extract_root}")
  file(ARCHIVE_EXTRACT INPUT "${BUNDLE}" DESTINATION "${extract_root}")
  _final_verify_stage("${extract_root}")
  foreach(member IN LISTS archive_names)
    file(TIMESTAMP "${extract_root}/${member}" member_timestamp
      "%Y-%m-%dT%H:%M:%S")
    if(NOT member_timestamp STREQUAL "1980-01-01T00:00:00")
      message(FATAL_ERROR
        "archive member timestamp is not normalized: ${member}=${member_timestamp}")
    endif()
  endforeach()
  file(REMOVE_RECURSE "${extract_root}")
  fod_file_sha256("${BUNDLE}" archive_hash)
  message(STATUS "Verified final distribution archive ${BUNDLE}")
  message(STATUS "SHA-256 ${archive_hash}")
  return()
endif()

foreach(required_root IN ITEMS KOMPILE_ROOT DL4J_ROOT EXAMPLES_ROOT)
  fod_require_variable(${required_root} " for MODE=${MODE}")
  cmake_path(ABSOLUTE_PATH ${required_root} NORMALIZE)
endforeach()

set(_mobile_root "${KOMPILE_ROOT}/kompile-chat-local/mobile")
set(_android_root "${_mobile_root}/android")
set(_staging_root
  "${KOMPILE_ROOT}/kompile-app/kompile-models/kompile-model-staging")
set(_graph_local_root
  "${KOMPILE_ROOT}/kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning-local")
set(_graph_reasoning_root
  "${KOMPILE_ROOT}/kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning")
set(_project_store_root
  "${KOMPILE_ROOT}/kompile-app/kompile-data/kompile-project-store")
set(_sdx_modules_root
  "${DL4J_ROOT}/nd4j/nd4j-backends/nd4j-backend-impls")
set(_libnd4j_root "${DL4J_ROOT}/libnd4j")
set(_examples_root "${EXAMPLES_ROOT}")

set(ACCELERATOR_CONFIG "${_android_root}/accelerators.json")
set(OFFLINE_ASSETS_CONFIG
  "${_android_root}/app/src/main/assets/offline-assets.json")
set(GRAPH_FIXTURE
  "${_android_root}/app/src/main/assets/graphs/fixture.kgraph")

if(NOT DEFINED SDX_ARTIFACT_MODE OR SDX_ARTIFACT_MODE STREQUAL "")
  set(SDX_ARTIFACT_MODE source-build)
endif()
if(SDX_ARTIFACT_MODE STREQUAL "release-consumer")
  foreach(required IN ITEMS SDX_RELEASE_VERSION SDX_RELEASE_MANIFEST SDX_RELEASE_ARTIFACT_ROOT)
    fod_require_variable(${required} " for SDX release-consumer mode")
  endforeach()
  file(READ "${ACCELERATOR_CONFIG}" accelerator_json)
  foreach(resolution IN ITEMS "vulkan;VULKAN" "tensorG3;TENSOR_G3")
    list(GET resolution 0 selector_key)
    list(GET resolution 1 output_prefix)
    foreach(field IN ITEMS packageRole platform variant)
      string(JSON selector_${field} ERROR_VARIABLE selector_error
        GET "${accelerator_json}" variants ${selector_key} releaseSelector ${field})
      if(selector_error)
        message(FATAL_ERROR
          "accelerators.json missing ${selector_key}.releaseSelector.${field}: ${selector_error}")
      endif()
    endforeach()
    sdx_resolve_release_artifact(
      "${SDX_RELEASE_MANIFEST}" "${SDX_RELEASE_ARTIFACT_ROOT}" runtime
      "${selector_packageRole}" "${selector_platform}" "${selector_variant}"
      "${SDX_RELEASE_VERSION}" ${output_prefix}_RESOLVED)
    set(${output_prefix}_AAR "${${output_prefix}_RESOLVED_PATH}")
  endforeach()
  string(REPLACE
    "artifacts/runtime/sdx-runtime-android-arm64-vulkan.aar"
    "artifacts/runtime/${VULKAN_RESOLVED_FILE_NAME}"
    _artifact_contracts "${_artifact_contracts}")
  string(REPLACE
    "artifacts/runtime/sdx-runtime-android-arm64-tensor-g3.aar"
    "artifacts/runtime/${TENSOR_G3_RESOLVED_FILE_NAME}"
    _artifact_contracts "${_artifact_contracts}")
  string(REPLACE
    "artifacts/runtime/sdx-runtime-android-arm64-vulkan.aar"
    "artifacts/runtime/${VULKAN_RESOLVED_FILE_NAME}"
    _build_provenance_contracts "${_build_provenance_contracts}")
  string(REPLACE
    "artifacts/runtime/sdx-runtime-android-arm64-tensor-g3.aar"
    "artifacts/runtime/${TENSOR_G3_RESOLVED_FILE_NAME}"
    _build_provenance_contracts "${_build_provenance_contracts}")
elseif(NOT SDX_ARTIFACT_MODE STREQUAL "source-build")
  message(FATAL_ERROR
    "SDX_ARTIFACT_MODE must be source-build or release-consumer, got: ${SDX_ARTIFACT_MODE}")
endif()

set(_required_source_files
  "${_mobile_root}/FINAL_DISTRIBUTION.md"
  "${_mobile_root}/MODEL_IMPORT.md"
  "${_mobile_root}/APPLE_IOS_SOURCE_SDK.md"
  "${_mobile_root}/package-offline-graph-chat.sh"
  "${_mobile_root}/verify-offline-graph-chat-bundle.sh"
  "${_mobile_root}/cmake/FinalOfflineDistribution.cmake"
  "${_mobile_root}/cmake/FinalOfflineDistributionContract.cmake"
  "${_mobile_root}/cmake/SdxReleaseArtifactResolver.cmake"
  "${_mobile_root}/cmake/tests/FinalOfflineDistributionContractTest.cmake"
  "${_mobile_root}/cmake/tests/SdxReleaseArtifactResolverContractTest.cmake"
  "${_android_root}/README.md"
  "${ACCELERATOR_CONFIG}"
  "${OFFLINE_ASSETS_CONFIG}"
  "${_android_root}/app/src/main/java/ai/kompile/chat/local/android/staging/ModelStagingHandoff.kt"
  "${_staging_root}/README.md"
  "${_examples_root}/README.md"
  "${_examples_root}/pom.xml"
  "${_examples_root}/java/pom.xml"
  "${_examples_root}/fixture/pom.xml"
  "${DL4J_ROOT}/LICENSE"
  "${_libnd4j_root}/CMakeLists.txt"
  "${_libnd4j_root}/cmake/Dependencies.cmake"
  "${_libnd4j_root}/cmake/MainBuildFlow.cmake"
  "${_libnd4j_root}/cmake/Options.cmake"
  "${_libnd4j_root}/cmake/SdxApplyPinnedPatch.cmake"
  "${_libnd4j_root}/cmake/SdxAppleProviderTargets.cmake"
  "${_libnd4j_root}/cmake/SdxPlatformProviders.cmake"
  "${_libnd4j_root}/cmake/SdxRuntimePackage.cmake"
  "${_libnd4j_root}/cmake/patches/mlx-0.31.1-ios.patch"
  "${_libnd4j_root}/cmake/tests/SdxAppleProviderContractTest.cmake"
  "${_libnd4j_root}/cmake/tests/SdxMlxStrictRuntimeContractTest.cmake"
  "${_libnd4j_root}/include/dsp/runtime/dsp_runtime_c.h"
  "${_libnd4j_root}/include/dsp/runtime/apple/README.md"
  "${_libnd4j_root}/include/dsp/runtime/apple/MLX_PROVENANCE.md"
  "${_libnd4j_root}/include/dsp/runtime/apple/sdx_apple_provider_c.h"
  "${_libnd4j_root}/include/dsp/runtime/apple/SdxAppleProviderManifest.h"
  "${_libnd4j_root}/include/dsp/runtime/apple/SdxAppleProviderManifest.mm"
  "${_libnd4j_root}/include/dsp/runtime/apple/SdxMetalProvider.mm"
  "${_libnd4j_root}/include/dsp/runtime/apple/SdxMetalAotProvider.mm"
  "${_libnd4j_root}/include/dsp/runtime/apple/SdxCoreMlAneProvider.mm"
  "${_libnd4j_root}/include/dsp/runtime/apple/swift/SdxAppleProvider.swift"
  "${_libnd4j_root}/include/dsp/runtime/bindings/swift/Package.swift"
  "${_libnd4j_root}/include/dsp/runtime/bindings/swift/Sources/SdxRuntime/SdxRuntime.swift"
  "${_libnd4j_root}/include/graph/cpu/MlxGraphBackend.h"
  "${_libnd4j_root}/include/graph/cpu/MlxGraphBackend.cpp"
  "${_libnd4j_root}/include/graph/cpu/MlxIRBuilder.h"
  "${_libnd4j_root}/include/graph/cpu/MlxIRBuilder.cpp"
  "${_libnd4j_root}/include/graph/NativeDynamicShapePlan.h"
  "${_libnd4j_root}/include/graph/impl/NativeDynamicShapePlan.cpp"
  "${_libnd4j_root}/include/graph/impl/NativeDynamicShapePlan_segments.cpp")
foreach(source_file IN LISTS _required_source_files)
  fod_require_nonempty_file("${source_file}" "required final-distribution source")
endforeach()

# id|source directory|staged directory
set(_source_tree_contracts
  "chat-local|${KOMPILE_ROOT}/kompile-chat-local|source/kompile/kompile-chat-local"
  "graph-local|${_graph_local_root}|source/kompile/kompile-graph-reasoning-local"
  "graph-reasoning|${_graph_reasoning_root}|source/kompile/kompile-graph-reasoning"
  "project-store|${_project_store_root}|source/kompile/kompile-project-store"
  "model-staging|${_staging_root}|source/kompile/kompile-model-staging"
  "sdx-core|${_sdx_modules_root}/nd4j-sdx|source/deeplearning4j/nd4j-sdx/nd4j-sdx"
  "sdx-model|${_sdx_modules_root}/nd4j-sdx-model|source/deeplearning4j/nd4j-sdx/nd4j-sdx-model"
  "sdx-preset|${_sdx_modules_root}/nd4j-sdx-preset|source/deeplearning4j/nd4j-sdx/nd4j-sdx-preset"
  "sdx-litertlm|${_sdx_modules_root}/nd4j-sdx-litertlm|source/deeplearning4j/nd4j-sdx/nd4j-sdx-litertlm"
  "mobile-tools|${_libnd4j_root}/tools/mobile|source/deeplearning4j/libnd4j/tools/mobile"
  "dsp-runtime|${_libnd4j_root}/include/dsp/runtime|source/deeplearning4j/libnd4j/include/dsp/runtime"
  "graph-vulkan|${_libnd4j_root}/include/graph/vulkan|source/deeplearning4j/libnd4j/include/graph/vulkan"
  "graph-hexagon|${_libnd4j_root}/include/graph/hexagon|source/deeplearning4j/libnd4j/include/graph/hexagon"
  "graph-tpu|${_libnd4j_root}/include/graph/tpu|source/deeplearning4j/libnd4j/include/graph/tpu"
  "graph-cpu|${_libnd4j_root}/include/graph/cpu|source/deeplearning4j/libnd4j/include/graph/cpu"
  "graph-impl|${_libnd4j_root}/include/graph/impl|source/deeplearning4j/libnd4j/include/graph/impl"
  "example-java|${_examples_root}/java/src|source/deeplearning4j/nd4j/sdx-runtime-examples/java/src"
  "example-kotlin|${_examples_root}/kotlin|source/deeplearning4j/nd4j/sdx-runtime-examples/kotlin"
  "example-swift|${_examples_root}/swift|source/deeplearning4j/nd4j/sdx-runtime-examples/swift"
  "example-fixture|${_examples_root}/fixture/src|source/deeplearning4j/nd4j/sdx-runtime-examples/fixture/src")
foreach(row IN LISTS _source_tree_contracts)
  fod_parse_row("${row}" tree 3)
  fod_require_directory("${tree_1}" "required source tree ${tree_0}")
endforeach()

fod_require_text("${_mobile_root}/MODEL_IMPORT.md" "GGUF/GGML model URL"
  "model import guide")
fod_require_text("${_mobile_root}/MODEL_IMPORT.md" "tokenizer_config.json"
  "model import guide")
fod_require_text("${_mobile_root}/APPLE_IOS_SOURCE_SDK.md" "source-only"
  "Apple source SDK guide")
fod_require_text("${_libnd4j_root}/include/dsp/runtime/apple/MLX_PROVENANCE.md"
  "4df3c078b9aadcb516212e9cb03004cbc5ce9a3e9c068fa3144d021db585a3a4"
  "MLX/Metal C++ provenance")
fod_require_text("${_libnd4j_root}/cmake/patches/mlx-0.31.1-ios.patch"
  "URL_HASH" "pinned MLX iOS patch")
fod_require_text("${_libnd4j_root}/cmake/SdxAppleProviderTargets.cmake"
  "METAL-CPP-LICENSE.txt" "Apple license staging contract")
fod_require_text("${_libnd4j_root}/cmake/SdxAppleProviderTargets.cmake"
  "default.metallib" "Apple metallib staging contract")

if(MODE STREQUAL "PLAN")
  message(STATUS "Final distribution profile: pixel-8a-research")
  message(STATUS "Planned archive: ${OUTPUT_ARCHIVE}")
  message(STATUS "PLAN performs no artifact copies and creates no archive.")
  message(STATUS
    "Apple/iOS payload is source-only on Linux; no XCFramework, Core ML package, provider archive, or default.metallib is claimed.")
  foreach(row IN LISTS _artifact_contracts)
    fod_parse_row("${row}" artifact 8)
    if(DEFINED ${artifact_1} AND NOT "${${artifact_1}}" STREQUAL "" AND
       EXISTS "${${artifact_1}}" AND NOT IS_DIRECTORY "${${artifact_1}}")
      file(SIZE "${${artifact_1}}" artifact_size)
      message(STATUS
        "READY   ${artifact_0}: ${${artifact_1}} (${artifact_size} bytes)")
    else()
      message(STATUS "PENDING ${artifact_0}: ${${artifact_1}}")
    endif()
    if(NOT artifact_3 STREQUAL "NONE")
      if(DEFINED ${artifact_3} AND
         "${${artifact_3}}" MATCHES "^[0-9a-fA-F]{64}$")
        message(STATUS "READY   ${artifact_3}=${${artifact_3}}")
      else()
        message(STATUS "PENDING ${artifact_3}=<fresh build SHA-256>")
      endif()
    endif()
  endforeach()
  return()
endif()

foreach(row IN LISTS _artifact_contracts)
  fod_parse_row("${row}" artifact 8)
  fod_require_variable(${artifact_1} " for MODE=${MODE}")
  set(artifact_source "${${artifact_1}}")
  fod_validate_magic("${artifact_source}" "${artifact_4}" "${artifact_0}")
  if(NOT artifact_7 STREQUAL "NONE")
    fod_require_text("${artifact_source}" "${artifact_7}" "${artifact_0}")
  endif()
  if(NOT artifact_3 STREQUAL "NONE")
    fod_require_variable(${artifact_3} " for MODE=${MODE}")
    if(NOT artifact_2 STREQUAL "NONE")
      fod_require_variable(${artifact_2} " for MODE=${MODE}")
      fod_validate_sha256_sidecar("${artifact_source}" "${${artifact_2}}"
        "${${artifact_3}}" "${artifact_0}")
    else()
      fod_validate_exact_sha256("${artifact_source}" "${${artifact_3}}"
        "${artifact_0}")
    endif()
  endif()
endforeach()

fod_require_variable(EXPECTED_VULKAN_LIBJNISDX_SHA256 " for MODE=${MODE}")
_final_validate_archive_member_hash("${VULKAN_AAR}"
  "jni/arm64-v8a/libjnisdx.so" "${EXPECTED_VULKAN_LIBJNISDX_SHA256}"
  "canonical Vulkan AAR libjnisdx")

foreach(row IN LISTS _provider_contracts)
  fod_parse_row("${row}" provider 8)
  set(provider_manifest "${${provider_1}}")
  fod_json_expect("${provider_manifest}" "${provider_2}" providerId)
  fod_json_expect("${provider_manifest}" "${provider_3}" artifactFormat)
  fod_json_expect("${provider_manifest}" "${provider_4}" defaultTargetSoc)
  fod_json_expect("${provider_manifest}" "${provider_5}" requiresAotArtifact)
  fod_json_expect("${provider_manifest}" "${provider_6}" allowRuntimeJit)
  fod_json_expect("${provider_manifest}" "${provider_7}" allowCpuFallback)
endforeach()

_final_verify_staging_jar("${STAGING_EXEC_JAR}")
fod_require_variable(ANDROID_SDK " for MODE=${MODE}")
fod_require_variable(ANDROID_NDK " for MODE=${MODE}")
fod_require_directory("${ANDROID_SDK}" "Android SDK")
fod_require_directory("${ANDROID_NDK}" "Android NDK")
set(_apk_verifier "${_android_root}/tools/verify-offline-apk.sh")
fod_require_nonempty_file("${_apk_verifier}" "offline APK verifier")
foreach(row IN LISTS _apk_contracts)
  fod_parse_row("${row}" apk 4)
  execute_process(
    COMMAND bash "${_apk_verifier}"
      --apk "${${apk_2}}"
      --variant "${apk_1}"
      --config "${ACCELERATOR_CONFIG}"
      --runtime-aar "${${apk_3}}"
      --android-sdk "${ANDROID_SDK}"
      --android-ndk "${ANDROID_NDK}"
    RESULT_VARIABLE verifier_result
    OUTPUT_VARIABLE verifier_stdout
    ERROR_VARIABLE verifier_stderr)
  if(NOT verifier_result EQUAL 0)
    message(FATAL_ERROR
      "${apk_0} APK verifier failed (rc=${verifier_result})\n${verifier_stdout}\n${verifier_stderr}")
  endif()
  string(STRIP "${verifier_stdout}" verifier_stdout)
  message(STATUS "${verifier_stdout}")
endforeach()

if(MODE STREQUAL "VALIDATE")
  message(STATUS
    "Final Pixel 8a inputs validated; no files were copied and no archive was created.")
  return()
endif()

fod_require_variable(STAGE " for MODE=STAGE")
cmake_path(ABSOLUTE_PATH STAGE NORMALIZE)
if(DEFINED RESET_STAGE AND RESET_STAGE)
  file(REMOVE_RECURSE "${STAGE}")
endif()
file(MAKE_DIRECTORY "${STAGE}")
file(GLOB existing RELATIVE "${STAGE}" "${STAGE}/*")
if(existing)
  message(FATAL_ERROR "STAGE must be empty (or pass RESET_STAGE=ON): ${STAGE}")
endif()
foreach(source_id IN ITEMS KOMPILE_SOURCE_ID DL4J_SOURCE_ID EXAMPLES_SOURCE_ID)
  fod_require_variable(${source_id} " for MODE=STAGE")
endforeach()

# source|destination
set(_document_contracts
  "${_mobile_root}/FINAL_DISTRIBUTION.md|README.md"
  "${_mobile_root}/MODEL_IMPORT.md|docs/MODEL_IMPORT.md"
  "${_mobile_root}/APPLE_IOS_SOURCE_SDK.md|docs/APPLE_IOS_SOURCE_SDK.md"
  "${_android_root}/README.md|docs/ANDROID_ACCELERATORS.md"
  "${_staging_root}/README.md|docs/MODEL_STAGING.md"
  "${_examples_root}/README.md|docs/SDX_RUNTIME_EXAMPLES.md")
foreach(row IN LISTS _document_contracts)
  fod_parse_row("${row}" document 2)
  fod_stage_file("${STAGE}" "${document_0}" "${document_1}")
endforeach()

foreach(row IN LISTS _artifact_contracts)
  fod_parse_row("${row}" artifact 8)
  fod_stage_file("${STAGE}" "${${artifact_1}}" "${artifact_5}")
  if(artifact_6 STREQUAL "ON")
    fod_write_sidecar("${STAGE}" "${artifact_5}")
  endif()
endforeach()

fod_stage_file("${STAGE}" "${DL4J_ROOT}/LICENSE"
  "licenses/deeplearning4j-LICENSE.txt")
fod_stage_file("${STAGE}"
  "${_libnd4j_root}/include/dsp/runtime/apple/MLX_PROVENANCE.md"
  "licenses/apple/MLX-PROVENANCE.md")
fod_stage_file("${STAGE}" "${_mobile_root}/APPLE_IOS_SOURCE_SDK.md"
  "licenses/apple/LICENSE-MATERIALIZATION.md")

foreach(row IN LISTS _source_tree_contracts)
  fod_parse_row("${row}" tree 3)
  fod_stage_tree("${STAGE}" "${tree_1}" "${tree_2}")
endforeach()
foreach(row IN ITEMS
    "${_examples_root}/README.md|source/deeplearning4j/nd4j/sdx-runtime-examples/README.md"
    "${_examples_root}/pom.xml|source/deeplearning4j/nd4j/sdx-runtime-examples/pom.xml"
    "${_examples_root}/java/pom.xml|source/deeplearning4j/nd4j/sdx-runtime-examples/java/pom.xml"
    "${_examples_root}/fixture/pom.xml|source/deeplearning4j/nd4j/sdx-runtime-examples/fixture/pom.xml")
  fod_parse_row("${row}" example_file 2)
  fod_stage_file("${STAGE}" "${example_file_0}" "${example_file_1}")
endforeach()

set(_libnd4j_reproducible_files
  "CMakeLists.txt"
  "buildnativeoperations.sh"
  "cmake/Dependencies.cmake"
  "cmake/MainBuildFlow.cmake"
  "cmake/Options.cmake"
  "cmake/SdxApplyPinnedPatch.cmake"
  "cmake/SdxAppleProviderTargets.cmake"
  "cmake/SdxPlatformProviders.cmake"
  "cmake/SdxRuntimePackage.cmake"
  "cmake/patches/mlx-0.31.1-ios.patch"
  "cmake/tests/SdxAppleProviderContractTest.cmake"
  "cmake/tests/SdxMlxStrictRuntimeContractTest.cmake"
  "include/dsp/NativeOpsDsp.h"
  "include/graph/GraphReplayHandle.h"
  "include/graph/NativeDynamicShapePlan.h"
  "include/graph/ReplayCacheManager.h")
foreach(relative_path IN LISTS _libnd4j_reproducible_files)
  fod_stage_file("${STAGE}" "${_libnd4j_root}/${relative_path}"
    "source/deeplearning4j/libnd4j/${relative_path}")
endforeach()

file(GLOB_RECURSE manifest_files RELATIVE "${STAGE}" LIST_DIRECTORIES false
  "${STAGE}/*")
list(REMOVE_ITEM manifest_files "MANIFEST.json")
list(SORT manifest_files)
set(records "")
foreach(path IN LISTS manifest_files)
  file(SIZE "${STAGE}/${path}" size)
  fod_file_sha256("${STAGE}/${path}" sha)
  fod_role_for("${path}" role)
  fod_json_quote("${path}" qpath)
  fod_json_quote("${role}" qrole)
  string(APPEND records
    "    {\"path\": ${qpath}, \"role\": ${qrole}, \"size\": ${size}, \"sha256\": \"${sha}\"},\n")
endforeach()
string(REGEX REPLACE ",\n$" "\n" records "${records}")

if(DEFINED ENV{SOURCE_DATE_EPOCH} AND NOT "$ENV{SOURCE_DATE_EPOCH}" STREQUAL "")
  set(source_date_epoch "$ENV{SOURCE_DATE_EPOCH}")
else()
  set(source_date_epoch 0)
endif()
if(NOT source_date_epoch MATCHES "^[0-9]+$")
  message(FATAL_ERROR "SOURCE_DATE_EPOCH must be a non-negative integer")
endif()
fod_json_quote("${KOMPILE_SOURCE_ID}" q_kompile_source_id)
fod_json_quote("${DL4J_SOURCE_ID}" q_dl4j_source_id)
fod_json_quote("${EXAMPLES_SOURCE_ID}" q_examples_source_id)
set(build_input_records "")
foreach(row IN LISTS _build_provenance_contracts)
  fod_parse_row("${row}" provenance 3)
  set(provenance_source_file "${${provenance_1}}")
  fod_file_sha256("${provenance_source_file}" provenance_sha)
  fod_json_quote("${provenance_0}" q_provenance_id)
  fod_json_quote("${provenance_2}" q_provenance_source_path)
  if(provenance_0 STREQUAL "vulkan-aar")
    string(APPEND build_input_records
      "    {\"id\": ${q_provenance_id}, \"sourcePath\": ${q_provenance_source_path}, \"sha256\": \"${provenance_sha}\", \"verifiedMember\": {\"path\": \"jni/arm64-v8a/libjnisdx.so\", \"sha256\": \"${EXPECTED_VULKAN_LIBJNISDX_SHA256}\"}},\n")
  else()
    string(APPEND build_input_records
      "    {\"id\": ${q_provenance_id}, \"sourcePath\": ${q_provenance_source_path}, \"sha256\": \"${provenance_sha}\"},\n")
  endif()
endforeach()
string(REGEX REPLACE ",\n$" "\n" build_input_records "${build_input_records}")

set(manifest
"{
  \"schemaVersion\": 2,
  \"name\": \"kompile-offline-graph-chat\",
  \"distributionProfile\": \"pixel-8a-research\",
  \"sourceDateEpoch\": ${source_date_epoch},
  \"archiveTimestamp\": \"1980-01-01T00:00:00\",
  \"archiveTimestampBasis\": \"ZIP-DOS-wall-clock\",
  \"sourceIdentity\": {
    \"kompile\": ${q_kompile_source_id},
    \"deeplearning4j\": ${q_dl4j_source_id},
    \"deeplearning4jExamples\": ${q_examples_source_id}
  },
  \"buildInputs\": [
${build_input_records}  ],
  \"networkPermission\": false,
  \"modelsIncluded\": false,
  \"canonicalSdzFixtureIncluded\": true,
  \"stagingExecutableIncluded\": true,
  \"android\": {
    \"abi\": \"arm64-v8a\",
    \"apkCount\": 2,
    \"providers\": [\"sdx.vulkan.v1\", \"sdx.nnapi-tensor-g3.v1\"],
    \"cpuFallback\": false,
    \"openBlas\": false,
    \"graphRuntime\": \"stock-GraalVM-native-AOT-C-ABI-v1-via-JavaCPP\",
    \"apkInputSidecarsVerified\": true,
    \"physicalDeviceExecutionPerformed\": false
  },
  \"appleIos\": {
    \"sourceOnly\": true,
    \"builtOnThisLinuxHost\": false,
    \"metalProviderSourceIncluded\": true,
    \"metalAotProviderSourceIncluded\": true,
    \"coreMlAneProviderSourceIncluded\": true,
    \"swiftIntegrationSourceIncluded\": true,
    \"defaultMetallibIncluded\": false,
    \"defaultMetallibBuildAndStagingContractIncluded\": true,
    \"mlxBinaryIncluded\": false,
    \"coreMlPackageIncluded\": false,
    \"licenseMaterializationContractIncluded\": true,
    \"appleBuildLicenseResources\": [\"MLX-LICENSE.txt\", \"METAL-CPP-LICENSE.txt\"],
    \"provenanceIncluded\": true
  },
  \"verificationScope\": \"artifact SHA-256, stale sidecar rejection, APK signature/ABI/provider/dependency/offline policy, staging UI hash hygiene, graph AOT identity, provider manifests, source integrity; physical-device execution and Apple compilation not performed\",
  \"files\": [
${records}  ]
}
")
file(WRITE "${STAGE}/MANIFEST.json" "${manifest}")
_final_verify_stage("${STAGE}")
message(STATUS "Final Pixel 8a distribution staged and verified at ${STAGE}")
