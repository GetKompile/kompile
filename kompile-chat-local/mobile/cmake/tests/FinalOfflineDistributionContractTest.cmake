cmake_minimum_required(VERSION 3.20)

set(_self "${CMAKE_CURRENT_LIST_FILE}")
get_filename_component(_cmake_dir "${CMAKE_CURRENT_LIST_DIR}/.." ABSOLUTE)
set(_contract "${_cmake_dir}/FinalOfflineDistributionContract.cmake")
set(_driver "${_cmake_dir}/FinalOfflineDistribution.cmake")
include("${_contract}")

if(DEFINED FOD_TEST_CASE AND NOT "${FOD_TEST_CASE}" STREQUAL "")
  if(FOD_TEST_CASE STREQUAL "missing")
    fod_require_nonempty_file("${FOD_TEST_ROOT}/missing.txt" "missing fixture")
  elseif(FOD_TEST_CASE STREQUAL "empty")
    fod_require_nonempty_file("${FOD_TEST_ROOT}/empty.txt" "empty fixture")
  elseif(FOD_TEST_CASE STREQUAL "hash")
    fod_validate_exact_sha256(
      "${FOD_TEST_ROOT}/hash.txt"
      "0000000000000000000000000000000000000000000000000000000000000000"
      "hash fixture")
  elseif(FOD_TEST_CASE STREQUAL "apple")
    fod_verify_source_only_apple_tree("${FOD_TEST_ROOT}/apple-stage")
  elseif(FOD_TEST_CASE STREQUAL "unsafe-path")
    fod_validate_relative_path("../escape.txt" "fixture")
  elseif(FOD_TEST_CASE STREQUAL "source-policy-tools")
    fod_verify_source_payload_policy("${FOD_TEST_ROOT}/source-policy-bad-tools")
  elseif(FOD_TEST_CASE STREQUAL "source-policy-python")
    fod_verify_source_payload_policy("${FOD_TEST_ROOT}/source-policy-bad-python")
  else()
    message(FATAL_ERROR "Unknown FOD_TEST_CASE: ${FOD_TEST_CASE}")
  endif()
  message(FATAL_ERROR "FOD_TEST_CASE unexpectedly succeeded: ${FOD_TEST_CASE}")
endif()

function(_expect_worker_failure test_case expected_text)
  execute_process(
    COMMAND "${CMAKE_COMMAND}"
      "-DFOD_TEST_CASE=${test_case}"
      "-DFOD_TEST_ROOT=${_test_root}"
      -P "${_self}"
    RESULT_VARIABLE result
    OUTPUT_VARIABLE stdout
    ERROR_VARIABLE stderr)
  if(result EQUAL 0)
    message(FATAL_ERROR "Expected worker case '${test_case}' to fail")
  endif()
  string(CONCAT log "${stdout}" "${stderr}")
  string(FIND "${log}" "${expected_text}" found)
  if(found EQUAL -1)
    message(FATAL_ERROR
      "Worker case '${test_case}' did not report '${expected_text}':\n${log}")
  endif()
endfunction()

if(NOT DEFINED KOMPILE_ROOT OR "${KOMPILE_ROOT}" STREQUAL "")
  get_filename_component(KOMPILE_ROOT
    "${CMAKE_CURRENT_LIST_DIR}/../../../.." ABSOLUTE)
endif()
if(NOT DEFINED DL4J_ROOT OR "${DL4J_ROOT}" STREQUAL "")
  get_filename_component(DL4J_ROOT "${KOMPILE_ROOT}/../deeplearning4j" ABSOLUTE)
endif()
if(NOT DEFINED EXAMPLES_ROOT OR "${EXAMPLES_ROOT}" STREQUAL "")
  get_filename_component(EXAMPLES_ROOT
    "${DL4J_ROOT}/nd4j/sdx-runtime-examples" ABSOLUTE)
endif()

set(_ios_offline_contract
  "${KOMPILE_ROOT}/kompile-chat-local/mobile/ios/cmake/tests/IosOfflineContractTest.cmake")
execute_process(
  COMMAND "${CMAKE_COMMAND}"
    "-DIOS_ROOT=${KOMPILE_ROOT}/kompile-chat-local/mobile/ios"
    -P "${_ios_offline_contract}"
  RESULT_VARIABLE ios_contract_result
  OUTPUT_VARIABLE ios_contract_stdout
  ERROR_VARIABLE ios_contract_stderr)
if(NOT ios_contract_result EQUAL 0)
  message(FATAL_ERROR
    "iOS local-only contract failed (rc=${ios_contract_result}):\n"
    "${ios_contract_stdout}\n${ios_contract_stderr}")
endif()

set(_release_resolver_contract
  "${KOMPILE_ROOT}/kompile-chat-local/mobile/cmake/tests/SdxReleaseArtifactResolverContractTest.cmake")
execute_process(
  COMMAND "${CMAKE_COMMAND}" -P "${_release_resolver_contract}"
  RESULT_VARIABLE release_resolver_result
  OUTPUT_VARIABLE release_resolver_stdout
  ERROR_VARIABLE release_resolver_stderr)
if(NOT release_resolver_result EQUAL 0)
  message(FATAL_ERROR
    "SDX release resolver contract failed (rc=${release_resolver_result}):\n"
    "${release_resolver_stdout}\n${release_resolver_stderr}")
endif()

if(DEFINED ENV{TMPDIR} AND NOT "$ENV{TMPDIR}" STREQUAL "")
  set(_temp_parent "$ENV{TMPDIR}")
else()
  set(_temp_parent "/tmp")
endif()
string(RANDOM LENGTH 16 ALPHABET 0123456789abcdef _nonce)
set(_test_root "${_temp_parent}/kompile-final-distribution-contract-${_nonce}")
file(REMOVE_RECURSE "${_test_root}")
file(MAKE_DIRECTORY
  "${_test_root}/archive-stage/nested"
  "${_test_root}/apple-stage/Bad.xcframework"
  "${_test_root}/source-policy/mobile/tools"
  "${_test_root}/source-policy/mobile/android/tools"
  "${_test_root}/source-policy/__pycache__"
  "${_test_root}/libnd4j-mobile-tools"
  "${_test_root}/source-policy-bad-tools/source/kompile/kompile-chat-local/mobile/tools"
  "${_test_root}/source-policy-bad-python/source/kompile/kompile-chat-local")
file(WRITE "${_test_root}/empty.txt" "")
file(WRITE "${_test_root}/hash.txt" "content whose digest is not all zeros\n")
file(WRITE "${_test_root}/apple-stage/Bad.xcframework/Info.plist" "forbidden\n")
file(WRITE "${_test_root}/archive-stage/z.txt" "z\n")
file(WRITE "${_test_root}/archive-stage/a.txt" "a\n")
file(WRITE "${_test_root}/archive-stage/nested/run.sh" "#!/usr/bin/env sh\nexit 0\n")
file(WRITE "${_test_root}/source-policy/keep.txt" "keep\n")
file(WRITE "${_test_root}/source-policy/mobile/tools/legacy.sh" "legacy\n")
file(WRITE "${_test_root}/source-policy/mobile/android/tools/verify.sh" "allowed\n")
file(WRITE "${_test_root}/source-policy/script.py" "forbidden\n")
file(WRITE "${_test_root}/source-policy/__pycache__/cache.pyc" "forbidden\n")
file(WRITE "${_test_root}/libnd4j-mobile-tools/build-mobile.sh" "allowed\n")
file(WRITE
  "${_test_root}/source-policy-bad-tools/source/kompile/kompile-chat-local/mobile/tools/legacy.sh"
  "forbidden\n")
file(WRITE
  "${_test_root}/source-policy-bad-python/source/kompile/kompile-chat-local/payload.py"
  "forbidden\n")

_expect_worker_failure("missing" "missing fixture is missing")
_expect_worker_failure("empty" "empty fixture is empty")
_expect_worker_failure("hash" "hash fixture SHA-256 mismatch")
_expect_worker_failure("apple" "forbidden built output")
_expect_worker_failure("unsafe-path" "fixture path is unsafe")
_expect_worker_failure("source-policy-tools"
  "forbidden retired chat-local mobile/tools payload")
_expect_worker_failure("source-policy-python"
  "forbidden Python/cache payload")

set(_source_policy_stage "${_test_root}/source-policy-stage")
fod_stage_tree(
  "${_source_policy_stage}"
  "${_test_root}/source-policy"
  "source/kompile/kompile-chat-local")
fod_stage_tree(
  "${_source_policy_stage}"
  "${_test_root}/libnd4j-mobile-tools"
  "source/deeplearning4j/libnd4j/tools/mobile")
fod_require_nonempty_file(
  "${_source_policy_stage}/source/kompile/kompile-chat-local/keep.txt"
  "ordinary staged source")
fod_require_nonempty_file(
  "${_source_policy_stage}/source/kompile/kompile-chat-local/mobile/android/tools/verify.sh"
  "Android reproducibility helper")
fod_require_nonempty_file(
  "${_source_policy_stage}/source/deeplearning4j/libnd4j/tools/mobile/build-mobile.sh"
  "libnd4j reproducibility helper")
foreach(forbidden IN ITEMS
    "source/kompile/kompile-chat-local/mobile/tools/legacy.sh"
    "source/kompile/kompile-chat-local/script.py"
    "source/kompile/kompile-chat-local/__pycache__/cache.pyc")
  if(EXISTS "${_source_policy_stage}/${forbidden}")
    message(FATAL_ERROR "Source policy staged forbidden fixture: ${forbidden}")
  endif()
endforeach()
fod_verify_source_payload_policy("${_source_policy_stage}")

set(_archive_one "${_test_root}/distribution-one.zip")
set(_archive_two "${_test_root}/distribution-two.zip")
fod_create_deterministic_archive(
  "${_test_root}/archive-stage" "${_archive_one}")
fod_create_deterministic_archive(
  "${_test_root}/archive-stage" "${_archive_two}")
file(SHA256 "${_archive_one}" _archive_one_sha)
file(SHA256 "${_archive_two}" _archive_two_sha)
if(NOT _archive_one_sha STREQUAL _archive_two_sha)
  message(FATAL_ERROR
    "Deterministic archive hashes differ: ${_archive_one_sha} != ${_archive_two_sha}")
endif()

fod_verify_archive_layout("${_archive_one}" _archive_names)
set(_expected_names "a.txt;nested/run.sh;z.txt")
if(NOT _archive_names STREQUAL _expected_names)
  message(FATAL_ERROR
    "Unexpected deterministic archive members: ${_archive_names}")
endif()

set(_extract_root "${_test_root}/extracted")
file(MAKE_DIRECTORY "${_extract_root}")
file(ARCHIVE_EXTRACT INPUT "${_archive_one}" DESTINATION "${_extract_root}")
foreach(member IN LISTS _archive_names)
  file(TIMESTAMP "${_extract_root}/${member}" member_timestamp
    "%Y-%m-%dT%H:%M:%S")
  if(NOT member_timestamp STREQUAL "1980-01-01T00:00:00")
    message(FATAL_ERROR
      "Archive member timestamp is not normalized: ${member}=${member_timestamp}")
  endif()
endforeach()

set(_planned_archive "${_test_root}/plan-must-not-exist.zip")
execute_process(
  COMMAND "${CMAKE_COMMAND}"
    -DMODE=PLAN
    "-DKOMPILE_ROOT=${KOMPILE_ROOT}"
    "-DDL4J_ROOT=${DL4J_ROOT}"
    "-DEXAMPLES_ROOT=${EXAMPLES_ROOT}"
    "-DOUTPUT_ARCHIVE=${_planned_archive}"
    -P "${_driver}"
  RESULT_VARIABLE plan_result
  OUTPUT_VARIABLE plan_stdout
  ERROR_VARIABLE plan_stderr)
if(NOT plan_result EQUAL 0)
  message(FATAL_ERROR
    "Real-root PLAN contract failed (rc=${plan_result}):\n${plan_stdout}\n${plan_stderr}")
endif()
string(CONCAT plan_log "${plan_stdout}" "${plan_stderr}")
string(FIND "${plan_log}" "PLAN performs no artifact copies" plan_marker)
if(plan_marker EQUAL -1)
  message(FATAL_ERROR "PLAN did not report its no-write contract:\n${plan_log}")
endif()
if(EXISTS "${_planned_archive}" OR EXISTS "${_planned_archive}.sha256")
  message(FATAL_ERROR "PLAN created an archive or checksum sidecar")
endif()

file(REMOVE_RECURSE "${_test_root}")
message(STATUS
  "Final offline distribution helper failures, synthetic SDX release resolver, scoped source policy, iOS contract, real-root PLAN, and deterministic ZIP contract passed")
