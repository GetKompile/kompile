cmake_minimum_required(VERSION 3.20)

function(sdx_require_manifest_token value label)
  if(NOT "${value}" MATCHES "^[A-Za-z0-9][A-Za-z0-9._+-]*$")
    message(FATAL_ERROR "Invalid sdx-sdk-manifest.json: ${label} must be a safe non-empty token")
  endif()
endfunction()

# Resolve and verify one canonical sdx-sdk-manifest.json artifact.
# Selection identity is exactly component/packageRole/platform/variant, and the
# caller must pin the release version independently of the downloaded manifest.
function(sdx_resolve_release_artifact manifest_path artifact_root component package_role platform variant expected_release_version out_prefix)
  foreach(required_value IN ITEMS manifest_path artifact_root component package_role platform variant expected_release_version)
    if("${${required_value}}" STREQUAL "")
      message(FATAL_ERROR "SDX release resolver requires ${required_value}")
    endif()
  endforeach()
  foreach(selector IN ITEMS component package_role platform variant expected_release_version)
    sdx_require_manifest_token("${${selector}}" "requested ${selector}")
  endforeach()
  get_filename_component(manifest_name "${manifest_path}" NAME)
  if(NOT manifest_name STREQUAL "sdx-sdk-manifest.json")
    message(FATAL_ERROR
      "SDX release manifest must be the canonical sdx-sdk-manifest.json: ${manifest_path}")
  endif()
  if(NOT EXISTS "${manifest_path}" OR IS_DIRECTORY "${manifest_path}" OR
     IS_SYMLINK "${manifest_path}")
    message(FATAL_ERROR "SDX release manifest is missing or unsafe: ${manifest_path}")
  endif()
  if(NOT IS_DIRECTORY "${artifact_root}" OR IS_SYMLINK "${artifact_root}")
    message(FATAL_ERROR "SDX release artifact root is missing or unsafe: ${artifact_root}")
  endif()

  file(READ "${manifest_path}" manifest_json)
  string(JSON schema ERROR_VARIABLE schema_error GET "${manifest_json}" schemaVersion)
  if(schema_error OR NOT schema STREQUAL "1")
    message(FATAL_ERROR "Invalid sdx-sdk-manifest.json: schemaVersion must be integer 1")
  endif()
  string(JSON release_version ERROR_VARIABLE release_version_error
    GET "${manifest_json}" releaseVersion)
  if(release_version_error)
    message(FATAL_ERROR "Invalid sdx-sdk-manifest.json: releaseVersion: ${release_version_error}")
  endif()
  sdx_require_manifest_token("${release_version}" "releaseVersion")
  if(NOT release_version STREQUAL expected_release_version)
    message(FATAL_ERROR
      "Invalid sdx-sdk-manifest.json: releaseVersion '${release_version}' does not match requested version '${expected_release_version}'")
  endif()
  string(JSON release_tag ERROR_VARIABLE release_tag_error GET "${manifest_json}" releaseTag)
  if(release_tag_error)
    message(FATAL_ERROR "Invalid sdx-sdk-manifest.json: releaseTag: ${release_tag_error}")
  endif()
  set(expected_release_tag "sdk-v${release_version}")
  if(NOT release_tag STREQUAL expected_release_tag)
    message(FATAL_ERROR
      "Invalid sdx-sdk-manifest.json: releaseTag must be '${expected_release_tag}'")
  endif()
  string(JSON artifact_count ERROR_VARIABLE artifacts_error LENGTH "${manifest_json}" artifacts)
  if(artifacts_error OR artifact_count LESS 1)
    message(FATAL_ERROR "Invalid sdx-sdk-manifest.json: artifacts must be a non-empty array")
  endif()

  set(match_count 0)
  set(selection_identities)
  set(manifest_file_names)
  math(EXPR last_artifact "${artifact_count} - 1")
  foreach(index RANGE 0 ${last_artifact})
    foreach(field IN ITEMS component packageRole platform variant classifier fileName packaging sha256 size)
      string(JSON candidate_${field} ERROR_VARIABLE field_error
        GET "${manifest_json}" artifacts ${index} ${field})
      if(field_error)
        message(FATAL_ERROR
          "Invalid sdx-sdk-manifest.json: artifacts[${index}].${field}: ${field_error}")
      endif()
    endforeach()

    sdx_require_manifest_token("${candidate_component}" "artifacts[${index}].component")
    sdx_require_manifest_token("${candidate_packageRole}" "artifacts[${index}].packageRole")
    sdx_require_manifest_token("${candidate_packaging}" "artifacts[${index}].packaging")
    if(candidate_component STREQUAL "java")
      if(NOT candidate_packageRole STREQUAL "java" OR
         NOT candidate_packaging STREQUAL "jar" OR
         NOT candidate_platform STREQUAL "" OR NOT candidate_variant STREQUAL "" OR
         NOT candidate_classifier STREQUAL "")
        message(FATAL_ERROR
          "Invalid sdx-sdk-manifest.json: artifacts[${index}] Java artifacts require packageRole=java, packaging=jar, and empty platform/variant/classifier")
      endif()
    else()
      sdx_require_manifest_token("${candidate_platform}" "artifacts[${index}].platform")
      sdx_require_manifest_token("${candidate_variant}" "artifacts[${index}].variant")
      sdx_require_manifest_token("${candidate_classifier}" "artifacts[${index}].classifier")
      set(expected_classifier "${candidate_platform}-${candidate_variant}")
      if(NOT candidate_classifier STREQUAL expected_classifier)
        message(FATAL_ERROR
          "Invalid sdx-sdk-manifest.json: artifacts[${index}].classifier must be '${expected_classifier}'")
      endif()

      set(role_packaging_valid FALSE)
      if(candidate_component STREQUAL "runtime")
        if((candidate_packageRole STREQUAL "platform-sdk" OR
            candidate_packageRole STREQUAL "runtime-bindings") AND
           candidate_packaging STREQUAL "zip")
          set(role_packaging_valid TRUE)
        elseif(candidate_packageRole STREQUAL "android-aar" AND
               candidate_packaging STREQUAL "aar")
          set(role_packaging_valid TRUE)
        elseif(candidate_packageRole STREQUAL "apple-xcframework" AND
               (candidate_packaging STREQUAL "xcframework" OR
                candidate_packaging STREQUAL "xcframework.zip"))
          set(role_packaging_valid TRUE)
        endif()
      elseif(candidate_component STREQUAL "aot" AND
             candidate_packageRole STREQUAL "aot-sdk" AND
             candidate_packaging STREQUAL "zip")
        set(role_packaging_valid TRUE)
      endif()
      if(NOT role_packaging_valid)
        message(FATAL_ERROR
          "Invalid sdx-sdk-manifest.json: artifacts[${index}] has incompatible component/packageRole/packaging")
      endif()

      set(selection_identity
        "${candidate_component}/${candidate_packageRole}/${candidate_platform}/${candidate_variant}")
      list(FIND selection_identities "${selection_identity}" identity_index)
      if(NOT identity_index EQUAL -1)
        message(FATAL_ERROR
          "Invalid sdx-sdk-manifest.json: duplicate selection identity ${selection_identity}")
      endif()
      list(APPEND selection_identities "${selection_identity}")
    endif()

    if(NOT candidate_fileName MATCHES "^[A-Za-z0-9][A-Za-z0-9._+-]*$")
      message(FATAL_ERROR
        "Invalid sdx-sdk-manifest.json: artifacts[${index}].fileName must be a safe basename")
    endif()
    list(FIND manifest_file_names "${candidate_fileName}" file_name_index)
    if(NOT file_name_index EQUAL -1)
      message(FATAL_ERROR
        "Invalid sdx-sdk-manifest.json: duplicate fileName ${candidate_fileName}")
    endif()
    list(APPEND manifest_file_names "${candidate_fileName}")
    if(NOT candidate_size MATCHES "^[1-9][0-9]*$")
      message(FATAL_ERROR
        "Invalid sdx-sdk-manifest.json: artifacts[${index}].size must be a positive integer")
    endif()
    string(LENGTH "${candidate_sha256}" candidate_sha_length)
    if(NOT candidate_sha_length EQUAL 64 OR
       NOT candidate_sha256 MATCHES "^[0-9a-fA-F]+$")
      message(FATAL_ERROR
        "Invalid sdx-sdk-manifest.json: artifacts[${index}].sha256 must contain 64 hexadecimal characters")
    endif()

    if(candidate_component STREQUAL component AND
       candidate_packageRole STREQUAL package_role AND
       candidate_platform STREQUAL platform AND
       candidate_variant STREQUAL variant)
      math(EXPR match_count "${match_count} + 1")
      set(match_index "${index}")
    endif()
  endforeach()

  if(NOT match_count EQUAL 1)
    message(FATAL_ERROR
      "Expected exactly one SDK artifact for ${component}/${package_role}/${platform}/${variant}, found ${match_count}")
  endif()

  foreach(field IN ITEMS classifier fileName packaging sha256 size)
    string(JSON selected_${field} ERROR_VARIABLE field_error
      GET "${manifest_json}" artifacts ${match_index} ${field})
    if(field_error)
      message(FATAL_ERROR
        "Invalid sdx-sdk-manifest.json: artifacts[${match_index}].${field}: ${field_error}")
    endif()
  endforeach()

  if(selected_fileName STREQUAL "" OR selected_fileName STREQUAL "." OR
     selected_fileName STREQUAL ".." OR selected_fileName MATCHES "[/\\\\;\r\n]")
    message(FATAL_ERROR "Invalid sdx-sdk-manifest.json: fileName must be a safe basename")
  endif()
  get_filename_component(selected_basename "${selected_fileName}" NAME)
  if(NOT selected_basename STREQUAL selected_fileName)
    message(FATAL_ERROR "Invalid sdx-sdk-manifest.json: fileName must be a safe basename")
  endif()
  if(NOT selected_size MATCHES "^[1-9][0-9]*$")
    message(FATAL_ERROR "Invalid sdx-sdk-manifest.json: size must be a positive integer")
  endif()
  string(LENGTH "${selected_sha256}" sha_length)
  if(NOT sha_length EQUAL 64 OR NOT selected_sha256 MATCHES "^[0-9a-fA-F]+$")
    message(FATAL_ERROR "Invalid sdx-sdk-manifest.json: sha256 must contain 64 hexadecimal characters")
  endif()
  string(TOLOWER "${selected_sha256}" selected_sha256)

  set(resolved_path "${artifact_root}/${selected_fileName}")
  if(NOT EXISTS "${resolved_path}" OR IS_DIRECTORY "${resolved_path}" OR
     IS_SYMLINK "${resolved_path}")
    message(FATAL_ERROR "Manifest-selected SDX artifact is missing or unsafe: ${resolved_path}")
  endif()
  file(SIZE "${resolved_path}" actual_size)
  if(NOT actual_size EQUAL selected_size)
    message(FATAL_ERROR
      "Manifest-selected SDX artifact size mismatch for ${selected_fileName}: expected ${selected_size}, got ${actual_size}")
  endif()
  file(SHA256 "${resolved_path}" actual_sha256)
  string(TOLOWER "${actual_sha256}" actual_sha256)
  if(NOT actual_sha256 STREQUAL selected_sha256)
    message(FATAL_ERROR
      "Manifest-selected SDX artifact SHA-256 mismatch for ${selected_fileName}")
  endif()

  set(${out_prefix}_PATH "${resolved_path}" PARENT_SCOPE)
  set(${out_prefix}_FILE_NAME "${selected_fileName}" PARENT_SCOPE)
  set(${out_prefix}_CLASSIFIER "${selected_classifier}" PARENT_SCOPE)
  set(${out_prefix}_PACKAGING "${selected_packaging}" PARENT_SCOPE)
  set(${out_prefix}_RELEASE_VERSION "${release_version}" PARENT_SCOPE)
  set(${out_prefix}_SIZE "${selected_size}" PARENT_SCOPE)
  set(${out_prefix}_SHA256 "${selected_sha256}" PARENT_SCOPE)
endfunction()

if(CMAKE_SCRIPT_MODE_FILE STREQUAL CMAKE_CURRENT_LIST_FILE AND DEFINED SDX_MANIFEST)
  foreach(required IN ITEMS SDX_ARTIFACT_ROOT SDX_COMPONENT SDX_PACKAGE_ROLE
      SDX_PLATFORM SDX_VARIANT SDX_RELEASE_VERSION SDX_OUTPUT_FILE)
    if(NOT DEFINED ${required} OR "${${required}}" STREQUAL "")
      message(FATAL_ERROR "${required} is required")
    endif()
  endforeach()
  sdx_resolve_release_artifact(
    "${SDX_MANIFEST}" "${SDX_ARTIFACT_ROOT}" "${SDX_COMPONENT}"
    "${SDX_PACKAGE_ROLE}" "${SDX_PLATFORM}" "${SDX_VARIANT}"
    "${SDX_RELEASE_VERSION}" resolved)
  file(WRITE "${SDX_OUTPUT_FILE}" "${resolved_PATH}")
  message(STATUS
    "Resolved SDX release artifact ${resolved_FILE_NAME} (${resolved_SIZE} bytes, ${resolved_SHA256})")
endif()
