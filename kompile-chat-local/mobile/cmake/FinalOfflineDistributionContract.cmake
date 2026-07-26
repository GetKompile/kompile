cmake_minimum_required(VERSION 3.20)

# Shared, side-effect-free contract helpers for FinalOfflineDistribution.cmake.
# The only top-level side effect is the explicitly requested archive worker.

function(fod_require_variable variable_name context)
  if(NOT DEFINED ${variable_name} OR "${${variable_name}}" STREQUAL "")
    message(FATAL_ERROR "${variable_name} is required${context}")
  endif()
endfunction()

function(fod_require_nonempty_file path label)
  if(NOT EXISTS "${path}" OR IS_DIRECTORY "${path}")
    message(FATAL_ERROR "${label} is missing: ${path}")
  endif()
  if(IS_SYMLINK "${path}")
    message(FATAL_ERROR "${label} must not be a symbolic link: ${path}")
  endif()
  file(SIZE "${path}" size)
  if(size EQUAL 0)
    message(FATAL_ERROR "${label} is empty: ${path}")
  endif()
endfunction()

function(fod_require_directory path label)
  if(NOT IS_DIRECTORY "${path}")
    message(FATAL_ERROR "${label} directory is missing: ${path}")
  endif()
  if(IS_SYMLINK "${path}")
    message(FATAL_ERROR "${label} directory must not be a symbolic link: ${path}")
  endif()
endfunction()

function(fod_validate_relative_path path label)
  string(FIND "${path}" "\n" newline_index)
  string(FIND "${path}" "\r" carriage_return_index)
  if("${path}" STREQUAL "" OR "${path}" STREQUAL "." OR
     path MATCHES "^/" OR path MATCHES "^[A-Za-z]:" OR
     path MATCHES "(^|/)\\.\\.?(/|$)" OR path MATCHES "//" OR
     path MATCHES "\\\\" OR path MATCHES ";" OR
     NOT newline_index EQUAL -1 OR NOT carriage_return_index EQUAL -1)
    message(FATAL_ERROR "${label} path is unsafe: ${path}")
  endif()
endfunction()

function(fod_require_text path needle label)
  fod_require_nonempty_file("${path}" "${label}")
  file(READ "${path}" text)
  string(FIND "${text}" "${needle}" found)
  if(found EQUAL -1)
    message(FATAL_ERROR
      "${label} does not contain required text '${needle}': ${path}")
  endif()
endfunction()

function(fod_json_quote input output)
  set(value "${input}")
  string(REPLACE "\\" "\\\\" value "${value}")
  string(REPLACE "\"" "\\\"" value "${value}")
  string(REPLACE "\n" "\\n" value "${value}")
  string(REPLACE "\r" "\\r" value "${value}")
  string(REPLACE "\t" "\\t" value "${value}")
  set(${output} "\"${value}\"" PARENT_SCOPE)
endfunction()

function(fod_parse_row row prefix expected_fields)
  string(REPLACE "|" ";" fields "${row}")
  list(LENGTH fields field_count)
  if(NOT field_count EQUAL expected_fields)
    message(FATAL_ERROR
      "contract row '${row}' has ${field_count} fields; expected ${expected_fields}")
  endif()
  math(EXPR last_field "${expected_fields} - 1")
  foreach(index RANGE 0 ${last_field})
    list(GET fields ${index} value)
    set(${prefix}_${index} "${value}" PARENT_SCOPE)
  endforeach()
endfunction()

function(fod_validate_hash_literal value label)
  string(LENGTH "${value}" length)
  if(NOT length EQUAL 64 OR NOT "${value}" MATCHES "^[0-9a-fA-F]+$")
    message(FATAL_ERROR "${label} must be exactly 64 hexadecimal characters")
  endif()
endfunction()

function(fod_file_sha256 path output)
  fod_require_nonempty_file("${path}" "SHA-256 input")
  file(SHA256 "${path}" hash)
  string(TOLOWER "${hash}" hash)
  set(${output} "${hash}" PARENT_SCOPE)
endfunction()

function(fod_validate_exact_sha256 path expected label)
  fod_require_nonempty_file("${path}" "${label}")
  fod_validate_hash_literal("${expected}" "${label} expected SHA-256")
  string(TOLOWER "${expected}" expected_lower)
  fod_file_sha256("${path}" actual)
  if(NOT actual STREQUAL expected_lower)
    message(FATAL_ERROR
      "${label} SHA-256 mismatch: expected ${expected_lower}, got ${actual}: ${path}")
  endif()
endfunction()

function(fod_read_sha256_sidecar sidecar expected_basename output_hash)
  fod_require_nonempty_file("${sidecar}" "checksum sidecar")
  file(READ "${sidecar}" sidecar_text LIMIT 4096)
  string(STRIP "${sidecar_text}" sidecar_text)
  string(REGEX MATCH "^([0-9a-fA-F]+)[ \t]+[*]?([^ \t\r\n]+)$" parsed
    "${sidecar_text}")
  if(NOT parsed)
    message(FATAL_ERROR "checksum sidecar is malformed: ${sidecar}")
  endif()
  string(TOLOWER "${CMAKE_MATCH_1}" sidecar_hash)
  set(sidecar_name "${CMAKE_MATCH_2}")
  fod_validate_hash_literal("${sidecar_hash}" "sidecar SHA-256")
  get_filename_component(sidecar_basename "${sidecar_name}" NAME)
  if(NOT sidecar_basename STREQUAL expected_basename)
    message(FATAL_ERROR
      "checksum sidecar names '${sidecar_basename}', expected '${expected_basename}'")
  endif()
  set(${output_hash} "${sidecar_hash}" PARENT_SCOPE)
endfunction()

function(fod_validate_sha256_sidecar artifact sidecar expected label)
  fod_validate_exact_sha256("${artifact}" "${expected}" "${label}")
  get_filename_component(artifact_basename "${artifact}" NAME)
  fod_read_sha256_sidecar("${sidecar}" "${artifact_basename}" sidecar_hash)
  string(TOLOWER "${expected}" expected_lower)
  if(NOT sidecar_hash STREQUAL expected_lower)
    message(FATAL_ERROR
      "${label} checksum sidecar is stale: expected ${expected_lower}, got ${sidecar_hash}")
  endif()
endfunction()

function(fod_validate_self_sidecar artifact sidecar label)
  fod_require_nonempty_file("${artifact}" "${label}")
  get_filename_component(artifact_basename "${artifact}" NAME)
  fod_read_sha256_sidecar("${sidecar}" "${artifact_basename}" expected)
  fod_validate_exact_sha256("${artifact}" "${expected}" "${label}")
endfunction()

function(fod_validate_magic path kind label)
  fod_require_nonempty_file("${path}" "${label}")
  if(kind STREQUAL "zip")
    file(READ "${path}" magic OFFSET 0 LIMIT 4 HEX)
    string(TOLOWER "${magic}" magic)
    if(NOT magic MATCHES "^504b(0304|0506|0708)$")
      message(FATAL_ERROR "${label} is not a ZIP/JAR/APK/AAR payload: ${path}")
    endif()
  elseif(kind STREQUAL "elf")
    file(READ "${path}" magic OFFSET 0 LIMIT 4 HEX)
    string(TOLOWER "${magic}" magic)
    if(NOT magic STREQUAL "7f454c46")
      message(FATAL_ERROR "${label} is not an ELF payload: ${path}")
    endif()
  elseif(NOT kind MATCHES "^(text|binary)$")
    message(FATAL_ERROR "unknown artifact kind '${kind}' for ${label}")
  endif()
endfunction()

function(fod_json_expect json_file expected)
  set(path ${ARGN})
  fod_require_nonempty_file("${json_file}" "JSON contract")
  file(READ "${json_file}" json)
  string(JSON actual ERROR_VARIABLE error GET "${json}" ${path})
  if(error OR NOT "${actual}" STREQUAL "${expected}")
    message(FATAL_ERROR
      "${json_file} field '${path}' expected '${expected}', got '${actual}' (${error})")
  endif()
endfunction()

function(fod_stage_file stage source relative_path)
  fod_require_nonempty_file("${source}" "staged file")
  fod_validate_relative_path("${relative_path}" "staged file")
  get_filename_component(destination_dir "${stage}/${relative_path}" DIRECTORY)
  file(MAKE_DIRECTORY "${destination_dir}")
  configure_file("${source}" "${stage}/${relative_path}" COPYONLY)
endfunction()

function(fod_write_sidecar stage relative_path)
  fod_file_sha256("${stage}/${relative_path}" hash)
  get_filename_component(name "${relative_path}" NAME)
  file(WRITE "${stage}/${relative_path}.sha256" "${hash}  ${name}\n")
endfunction()

function(fod_stage_tree stage source destination)
  fod_require_directory("${source}" "staged source tree")
  file(GLOB_RECURSE source_files RELATIVE "${source}" LIST_DIRECTORIES false
    "${source}/*")
  list(SORT source_files)
  foreach(relative_path IN LISTS source_files)
    # The retired chat-local mobile/tools lane must never enter a distribution.
    # Keep this destination-scoped: mobile/android/tools and libnd4j/tools/mobile
    # contain legitimate reproducible build helpers.
    if(destination STREQUAL "source/kompile/kompile-chat-local" AND
       relative_path MATCHES "^mobile/tools(/|$)")
      continue()
    endif()
    if(relative_path MATCHES
       "(^|/)(\\.git|\\.gradle|\\.idea|\\.kotlin|__pycache__|build|target|node_modules|libs|jniLibs|blasbuild|python|python-end-to-end)(/|$)")
      continue()
    endif()
    if(relative_path MATCHES "\\.(py|pyc|pyo|class|o|obj)$" OR
       relative_path MATCHES "(^|/)local\\.properties$" OR
       relative_path MATCHES "(^|/)\\.DS_Store$")
      continue()
    endif()
    if(IS_SYMLINK "${source}/${relative_path}")
      message(FATAL_ERROR
        "symbolic links are forbidden in staged source trees: ${source}/${relative_path}")
    endif()
    fod_stage_file("${stage}" "${source}/${relative_path}"
      "${destination}/${relative_path}")
  endforeach()
endfunction()

function(fod_verify_source_payload_policy stage)
  fod_require_directory("${stage}" "source payload policy stage")
  file(GLOB_RECURSE staged_paths
    LIST_DIRECTORIES true
    RELATIVE "${stage}"
    "${stage}/*")
  foreach(relative_path IN LISTS staged_paths)
    if(relative_path MATCHES "(^|/)__pycache__(/|$)" OR
       relative_path MATCHES "\\.(py|pyc|pyo)$")
      message(FATAL_ERROR
        "forbidden Python/cache payload in final distribution: ${relative_path}")
    endif()
    if(relative_path MATCHES
       "^source/kompile/kompile-chat-local/mobile/tools(/|$)")
      message(FATAL_ERROR
        "forbidden retired chat-local mobile/tools payload in final distribution: ${relative_path}")
    endif()
  endforeach()
endfunction()

function(fod_role_for path output)
  if(path MATCHES "^artifacts/android/.*\\.apk\\.sha256$")
    set(role "apk-sha256")
  elseif(path MATCHES "^artifacts/android/.*\\.apk$")
    set(role "android-apk")
  elseif(path MATCHES "^artifacts/staging/.*\\.jar\\.sha256$")
    set(role "staging-jar-sha256")
  elseif(path MATCHES "^artifacts/staging/.*\\.jar$")
    set(role "model-staging-executable")
  elseif(path MATCHES "^artifacts/runtime/.*\\.sha256$")
    set(role "runtime-sha256")
  elseif(path MATCHES "^artifacts/runtime/")
    set(role "android-accelerator-runtime")
  elseif(path MATCHES "^artifacts/contracts/provider-")
    set(role "sdx-provider-manifest")
  elseif(path MATCHES "^artifacts/contracts/")
    set(role "application-accelerator-contract")
  elseif(path MATCHES "^artifacts/graph/.*\\.so$")
    set(role "android-graph-aot-runtime")
  elseif(path MATCHES "^artifacts/graph/")
    set(role "graph-aot-sdk")
  elseif(path MATCHES "^artifacts/models/")
    set(role "canonical-sdz-fixture")
  elseif(path MATCHES "^docs/")
    set(role "operator-documentation")
  elseif(path MATCHES "^licenses/")
    set(role "license-and-provenance")
  elseif(path MATCHES "^source/deeplearning4j/libnd4j/include/dsp/runtime/apple/" OR
         path MATCHES "^source/deeplearning4j/libnd4j/cmake/.*Apple" OR
         path MATCHES "^source/deeplearning4j/libnd4j/cmake/patches/mlx")
    set(role "apple-source-only-sdk")
  elseif(path MATCHES "^source/")
    set(role "reproducible-source")
  elseif(path STREQUAL "README.md")
    set(role "distribution-readme")
  else()
    set(role "distribution-content")
  endif()
  set(${output} "${role}" PARENT_SCOPE)
endfunction()

function(fod_verify_source_only_apple_tree stage)
  file(GLOB_RECURSE apple_tree_paths
    LIST_DIRECTORIES true
    RELATIVE "${stage}"
    "${stage}/*")
  foreach(relative_path IN LISTS apple_tree_paths)
    if(relative_path MATCHES "(^|/)[^/]*\\.xcframework(/|$)" OR
       relative_path MATCHES "(^|/)default\\.metallib$" OR
       relative_path MATCHES "(^|/)libmlx\\.a$" OR
       relative_path MATCHES "(^|/)[^/]*\\.mlpackage(/|$)" OR
       relative_path MATCHES "(^|/)[^/]*\\.mlmodelc(/|$)")
      message(FATAL_ERROR
        "source-only Apple distribution contains forbidden built output: ${stage}/${relative_path}")
    endif()
  endforeach()
endfunction()

function(fod_verify_archive_layout archive output_names)
  fod_require_nonempty_file("${archive}" "distribution archive")
  execute_process(
    COMMAND "${CMAKE_COMMAND}" -E tar tf "${archive}"
    RESULT_VARIABLE list_result
    OUTPUT_VARIABLE names_text
    ERROR_VARIABLE list_error)
  if(NOT list_result EQUAL 0)
    message(FATAL_ERROR
      "cannot list distribution archive (rc=${list_result}): ${list_error}")
  endif()
  string(REPLACE "\r\n" "\n" names_text "${names_text}")
  string(REGEX REPLACE "\n+$" "" names_text "${names_text}")
  if(names_text STREQUAL "")
    message(FATAL_ERROR "distribution archive is empty: ${archive}")
  endif()
  string(REPLACE "\n" ";" names "${names_text}")
  set(seen "")
  foreach(name IN LISTS names)
    fod_validate_relative_path("${name}" "archive member")
    if(name MATCHES "/$")
      message(FATAL_ERROR "archive member must be a regular file: ${name}")
    endif()
    list(FIND seen "${name}" duplicate_index)
    if(NOT duplicate_index EQUAL -1)
      message(FATAL_ERROR "duplicate archive member: ${name}")
    endif()
    list(APPEND seen "${name}")
  endforeach()
  set(sorted "${names}")
  list(SORT sorted)
  if(NOT names STREQUAL sorted)
    message(FATAL_ERROR "distribution archive members are not sorted")
  endif()
  set(${output_names} "${names}" PARENT_SCOPE)
endfunction()

function(fod_verify_archive_metadata archive)
  fod_require_nonempty_file("${archive}" "distribution archive")
  find_program(fod_zipinfo_executable NAMES zipinfo REQUIRED)
  execute_process(
    COMMAND "${CMAKE_COMMAND}" -E env TZ=UTC
      "${fod_zipinfo_executable}" -T -l "${archive}"
    RESULT_VARIABLE metadata_result
    OUTPUT_VARIABLE metadata_text
    ERROR_VARIABLE metadata_error)
  if(NOT metadata_result EQUAL 0)
    message(FATAL_ERROR
      "cannot inspect distribution archive metadata (rc=${metadata_result}): ${metadata_error}")
  endif()
  string(REPLACE "\r\n" "\n" metadata_text "${metadata_text}")
  string(REPLACE "\n" ";" metadata_lines "${metadata_text}")
  set(metadata_file_count 0)
  foreach(line IN LISTS metadata_lines)
    if(line MATCHES "^..........[ \t]")
      if(NOT line MATCHES "^-")
        message(FATAL_ERROR
          "distribution archive contains a non-regular member: ${line}")
      endif()
      math(EXPR metadata_file_count "${metadata_file_count} + 1")
      if(NOT line MATCHES "^-rw-r--r--[ \t]" AND
         NOT line MATCHES "^-rwxr-xr-x[ \t]")
        message(FATAL_ERROR
          "distribution archive member mode is not normalized: ${line}")
      endif()
      if(NOT line MATCHES "[ \t]19800101\\.000000[ \t]")
        message(FATAL_ERROR
          "distribution archive member timestamp is not normalized: ${line}")
      endif()
    endif()
  endforeach()
  if(metadata_file_count EQUAL 0)
    message(FATAL_ERROR "distribution archive metadata contains no regular files")
  endif()
endfunction()

function(fod_create_deterministic_archive stage output_archive)
  fod_require_directory("${stage}" "distribution stage")
  cmake_path(ABSOLUTE_PATH stage NORMALIZE OUTPUT_VARIABLE absolute_stage)
  cmake_path(ABSOLUTE_PATH output_archive NORMALIZE OUTPUT_VARIABLE absolute_output)
  cmake_path(IS_PREFIX absolute_stage "${absolute_output}" NORMALIZE output_inside_stage)
  if(output_inside_stage)
    message(FATAL_ERROR "archive output must be outside its stage: ${absolute_output}")
  endif()
  get_filename_component(output_directory "${absolute_output}" DIRECTORY)
  file(MAKE_DIRECTORY "${output_directory}")
  execute_process(
    COMMAND "${CMAKE_COMMAND}" -E chdir "${absolute_stage}"
      "${CMAKE_COMMAND}"
      -DFOD_ARCHIVE_WORKER=ON
      "-DFOD_ARCHIVE_STAGE=${absolute_stage}"
      "-DFOD_ARCHIVE_OUTPUT=${absolute_output}"
      -P "${CMAKE_CURRENT_FUNCTION_LIST_FILE}"
    RESULT_VARIABLE archive_result
    OUTPUT_VARIABLE archive_stdout
    ERROR_VARIABLE archive_stderr)
  if(NOT archive_result EQUAL 0)
    message(FATAL_ERROR
      "deterministic archive creation failed (rc=${archive_result})\n${archive_stdout}\n${archive_stderr}")
  endif()
  fod_validate_self_sidecar("${absolute_output}" "${absolute_output}.sha256"
    "distribution archive")
  fod_verify_archive_layout("${absolute_output}" archive_names)
  fod_verify_archive_metadata("${absolute_output}")
endfunction()

if(DEFINED FOD_ARCHIVE_WORKER AND FOD_ARCHIVE_WORKER)
  fod_require_variable(FOD_ARCHIVE_STAGE " for archive worker")
  fod_require_variable(FOD_ARCHIVE_OUTPUT " for archive worker")
  fod_require_directory("${FOD_ARCHIVE_STAGE}" "archive worker stage")
  cmake_path(ABSOLUTE_PATH FOD_ARCHIVE_STAGE NORMALIZE)
  cmake_path(ABSOLUTE_PATH FOD_ARCHIVE_OUTPUT NORMALIZE)
  file(GLOB_RECURSE archive_files RELATIVE "${FOD_ARCHIVE_STAGE}"
    LIST_DIRECTORIES false "${FOD_ARCHIVE_STAGE}/*")
  list(SORT archive_files)
  if(NOT archive_files)
    message(FATAL_ERROR "archive stage contains no files: ${FOD_ARCHIVE_STAGE}")
  endif()
  foreach(relative_path IN LISTS archive_files)
    fod_validate_relative_path("${relative_path}" "archive member")
    if(IS_SYMLINK "${FOD_ARCHIVE_STAGE}/${relative_path}")
      message(FATAL_ERROR "symbolic links are forbidden in archive: ${relative_path}")
    endif()
    if(relative_path MATCHES "\\.sh$")
      file(CHMOD "${FOD_ARCHIVE_STAGE}/${relative_path}"
        PERMISSIONS OWNER_READ OWNER_WRITE OWNER_EXECUTE GROUP_READ GROUP_EXECUTE
                    WORLD_READ WORLD_EXECUTE)
    else()
      file(CHMOD "${FOD_ARCHIVE_STAGE}/${relative_path}"
        PERMISSIONS OWNER_READ OWNER_WRITE GROUP_READ WORLD_READ)
    endif()
    list(APPEND touch_batch "${FOD_ARCHIVE_STAGE}/${relative_path}")
    list(LENGTH touch_batch touch_batch_size)
    if(touch_batch_size GREATER_EQUAL 200)
      find_program(fod_touch_executable NAMES touch REQUIRED)
      execute_process(
        COMMAND "${CMAKE_COMMAND}" -E env TZ=UTC
          "${fod_touch_executable}" -t 198001010000.00 ${touch_batch}
        RESULT_VARIABLE touch_result
        ERROR_VARIABLE touch_error)
      if(NOT touch_result EQUAL 0)
        message(FATAL_ERROR
          "cannot normalize archive input timestamps (rc=${touch_result}): ${touch_error}")
      endif()
      set(touch_batch "")
    endif()
  endforeach()
  if(touch_batch)
    find_program(fod_touch_executable NAMES touch REQUIRED)
    execute_process(
      COMMAND "${CMAKE_COMMAND}" -E env TZ=UTC
        "${fod_touch_executable}" -t 198001010000.00 ${touch_batch}
      RESULT_VARIABLE touch_result
      ERROR_VARIABLE touch_error)
    if(NOT touch_result EQUAL 0)
      message(FATAL_ERROR
        "cannot normalize archive input timestamps (rc=${touch_result}): ${touch_error}")
    endif()
  endif()
  set(temporary_output "${FOD_ARCHIVE_OUTPUT}.tmp")
  set(member_list "${temporary_output}.members")
  file(REMOVE "${temporary_output}" "${member_list}")
  string(REPLACE ";" "\n" member_lines "${archive_files}")
  file(WRITE "${member_list}" "${member_lines}\n")
  find_program(fod_zip_executable NAMES zip REQUIRED)
  execute_process(
    COMMAND "${CMAKE_COMMAND}" -E env TZ=UTC
      "${fod_zip_executable}" -X -q -9 "${temporary_output}" -@
    WORKING_DIRECTORY "${FOD_ARCHIVE_STAGE}"
    INPUT_FILE "${member_list}"
    RESULT_VARIABLE zip_result
    OUTPUT_VARIABLE zip_stdout
    ERROR_VARIABLE zip_stderr)
  file(REMOVE "${member_list}")
  if(NOT zip_result EQUAL 0)
    message(FATAL_ERROR
      "deterministic ZIP creation failed (rc=${zip_result})\n${zip_stdout}\n${zip_stderr}")
  endif()
  fod_require_nonempty_file("${temporary_output}" "temporary distribution archive")
  file(RENAME "${temporary_output}" "${FOD_ARCHIVE_OUTPUT}")
  fod_file_sha256("${FOD_ARCHIVE_OUTPUT}" archive_hash)
  get_filename_component(archive_name "${FOD_ARCHIVE_OUTPUT}" NAME)
  file(WRITE "${FOD_ARCHIVE_OUTPUT}.sha256"
    "${archive_hash}  ${archive_name}\n")
endif()
