cmake_minimum_required(VERSION 3.20)
include("${CMAKE_CURRENT_LIST_DIR}/../SdxReleaseArtifactResolver.cmake")

if(DEFINED ENV{TMPDIR} AND NOT "$ENV{TMPDIR}" STREQUAL "")
  set(temp_parent "$ENV{TMPDIR}")
else()
  set(temp_parent "/tmp")
endif()
string(RANDOM LENGTH 16 ALPHABET 0123456789abcdef nonce)
set(root "${temp_parent}/kompile-sdx-release-resolver-${nonce}")
file(MAKE_DIRECTORY "${root}/artifacts")
set(nonstandard_name "producer-surprise-v9.aar")
file(WRITE "${root}/artifacts/${nonstandard_name}" "synthetic canonical release bytes\n")
file(SIZE "${root}/artifacts/${nonstandard_name}" artifact_size)
file(SHA256 "${root}/artifacts/${nonstandard_name}" artifact_sha)
set(manifest "${root}/sdx-sdk-manifest.json")
file(WRITE "${manifest}"
"{\"schemaVersion\":1,\"releaseVersion\":\"9.8.7\",\"releaseTag\":\"sdk-v9.8.7\",\"artifacts\":[{\"component\":\"runtime\",\"packageRole\":\"android-aar\",\"platform\":\"android-arm64\",\"variant\":\"vulkan\",\"classifier\":\"android-arm64-vulkan\",\"fileName\":\"${nonstandard_name}\",\"packaging\":\"aar\",\"sha256\":\"${artifact_sha}\",\"size\":${artifact_size}}]}")

sdx_resolve_release_artifact(
  "${manifest}" "${root}/artifacts" runtime android-aar android-arm64 vulkan
  "9.8.7" selected)
if(NOT selected_FILE_NAME STREQUAL nonstandard_name OR
   NOT selected_PATH STREQUAL "${root}/artifacts/${nonstandard_name}" OR
   NOT selected_SIZE EQUAL artifact_size OR NOT selected_SHA256 STREQUAL artifact_sha)
  message(FATAL_ERROR "resolver did not retain the manifest-selected exact fileName contract")
endif()

function(expect_failure case_name expected)
  execute_process(
    COMMAND "${CMAKE_COMMAND}" ${ARGN}
      "-DSDX_OUTPUT_FILE=${root}/${case_name}.out"
      -P "${CMAKE_CURRENT_LIST_DIR}/../SdxReleaseArtifactResolver.cmake"
    RESULT_VARIABLE result OUTPUT_VARIABLE stdout ERROR_VARIABLE stderr)
  if(result EQUAL 0)
    message(FATAL_ERROR "${case_name} unexpectedly succeeded")
  endif()
  string(CONCAT log "${stdout}" "${stderr}")
  string(FIND "${log}" "${expected}" marker)
  if(marker EQUAL -1)
    message(FATAL_ERROR "${case_name} did not fail with '${expected}':\n${log}")
  endif()
endfunction()

set(selection
  "-DSDX_ARTIFACT_ROOT=${root}/artifacts"
  "-DSDX_COMPONENT=runtime"
  "-DSDX_PACKAGE_ROLE=android-aar"
  "-DSDX_PLATFORM=android-arm64")
set(common
  "-DSDX_MANIFEST=${manifest}"
  ${selection}
  "-DSDX_RELEASE_VERSION=9.8.7")
expect_failure(missing "found 0" ${common} "-DSDX_VARIANT=missing")
expect_failure(wrong-release-version "does not match"
  "-DSDX_MANIFEST=${manifest}" ${selection} "-DSDX_RELEASE_VERSION=9.8.8"
  "-DSDX_VARIANT=vulkan")

file(READ "${manifest}" valid_manifest)
string(REPLACE "\"artifacts\":[" "\"artifacts\":[" duplicate_prefix "${valid_manifest}")
string(REGEX REPLACE "]}" "," duplicate_manifest "${valid_manifest}")
string(JSON record GET "${valid_manifest}" artifacts 0)
file(MAKE_DIRECTORY "${root}/ambiguous")
file(WRITE "${root}/ambiguous/sdx-sdk-manifest.json" "${duplicate_manifest}${record}]}")
expect_failure(ambiguous "duplicate selection identity"
  "-DSDX_MANIFEST=${root}/ambiguous/sdx-sdk-manifest.json" ${selection}
  "-DSDX_RELEASE_VERSION=9.8.7" "-DSDX_VARIANT=vulkan")

file(MAKE_DIRECTORY "${root}/bad-tag")
string(REPLACE "sdk-v9.8.7" "sdk-v9.8.6" bad_tag_manifest "${valid_manifest}")
file(WRITE "${root}/bad-tag/sdx-sdk-manifest.json" "${bad_tag_manifest}")
expect_failure(bad-tag "releaseTag must be 'sdk-v9.8.7'"
  "-DSDX_MANIFEST=${root}/bad-tag/sdx-sdk-manifest.json" ${selection}
  "-DSDX_RELEASE_VERSION=9.8.7" "-DSDX_VARIANT=vulkan")

file(MAKE_DIRECTORY "${root}/bad-classifier")
string(REPLACE "\"classifier\":\"android-arm64-vulkan\""
  "\"classifier\":\"android-arm64-cpu\"" bad_classifier_manifest "${valid_manifest}")
file(WRITE "${root}/bad-classifier/sdx-sdk-manifest.json" "${bad_classifier_manifest}")
expect_failure(bad-classifier "artifacts[0].classifier"
  "-DSDX_MANIFEST=${root}/bad-classifier/sdx-sdk-manifest.json" ${selection}
  "-DSDX_RELEASE_VERSION=9.8.7" "-DSDX_VARIANT=vulkan")

file(MAKE_DIRECTORY "${root}/missing-classifier")
string(REPLACE "\"classifier\":\"android-arm64-vulkan\"," ""
  missing_classifier_manifest "${valid_manifest}")
file(WRITE "${root}/missing-classifier/sdx-sdk-manifest.json" "${missing_classifier_manifest}")
expect_failure(missing-classifier "classifier"
  "-DSDX_MANIFEST=${root}/missing-classifier/sdx-sdk-manifest.json" ${selection}
  "-DSDX_RELEASE_VERSION=9.8.7" "-DSDX_VARIANT=vulkan")

file(MAKE_DIRECTORY "${root}/bad-packaging")
string(REPLACE "\"packaging\":\"aar\"" "\"packaging\":\"zip\""
  bad_packaging_manifest "${valid_manifest}")
file(WRITE "${root}/bad-packaging/sdx-sdk-manifest.json" "${bad_packaging_manifest}")
expect_failure(bad-packaging "component/packageRole/packaging"
  "-DSDX_MANIFEST=${root}/bad-packaging/sdx-sdk-manifest.json" ${selection}
  "-DSDX_RELEASE_VERSION=9.8.7" "-DSDX_VARIANT=vulkan")

file(APPEND "${root}/artifacts/${nonstandard_name}" "corruption")
expect_failure(corrupt-size "size mismatch" ${common} "-DSDX_VARIANT=vulkan")
file(WRITE "${root}/artifacts/${nonstandard_name}" "synthetic canonical release byteS\n")
expect_failure(corrupt-sha "SHA-256 mismatch" ${common} "-DSDX_VARIANT=vulkan")

file(REMOVE_RECURSE "${root}")
message(STATUS "Synthetic nonstandard-filename SDX release resolver contract passed")
