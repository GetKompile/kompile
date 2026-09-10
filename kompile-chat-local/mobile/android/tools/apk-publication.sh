#!/usr/bin/env bash
# Source-safe publication functions. Callers supply the resolved build/artifact
# variables, sha256_file, and TEMPORARY_FILES; sourcing performs no work.

verify_apk() {
  local apk="$1"
  local variant="$2"
  local normalized_variant=""
  local runtime_aar=""
  local verifier_args=(
    --apk "$apk"
    --variant "$variant"
    --config "$SCRIPT_DIR/accelerators.json"
    --android-sdk "$ANDROID_SDK"
    --android-ndk "$ANDROID_NDK_ARG"
    --sdx-sdk "$SDX_LLM_SDK"
    --expected-build-id "$APK_BUILD_ID"
    --expected-version-code "$APK_VERSION_CODE"
  )
  case "$variant" in
    vulkan) normalized_variant=vulkan ;;
    hexagon) normalized_variant=hexagon ;;
    tensorG3) normalized_variant=tensor-g3 ;;
    tensorG5) normalized_variant=tensor-g5 ;;
    *) echo "Unsupported APK verification variant: $variant" >&2; return 1 ;;
  esac
  # Audit the exact normalized AAR consumed by this build, not a source fallback.
  runtime_aar="$APP_BUILD_ROOT/sdx-normalized-aar/$normalized_variant/sdx-runtime-$normalized_variant.aar"
  [[ -s "$runtime_aar" ]] || {
    echo "Normalized runtime AAR used by this Gradle build is missing: $runtime_aar" >&2
    return 1
  }
  if [[ "$variant" == "tensorG3" &&
        "$(sha256_file "$runtime_aar")" != "$TENSOR_G3_SOURCE_SHA256" ]]; then
    echo "Tensor G3 runtime AAR was rewritten after its producer receipt was verified" >&2
    return 1
  fi
  verifier_args+=(--runtime-aar "$runtime_aar")
  if [[ "$variant" == "tensorG3" ]]; then
    verifier_args+=(
      --expected-source-runtime-aar-sha256 "$TENSOR_G3_SOURCE_SHA256"
      --expected-runtime-provenance-sha256 "$TENSOR_G3_PROVENANCE_SHA256"
      --expected-sdx-aot-provenance-sha256 "$SDX_AOT_PROVENANCE_SHA256"
    )
  fi
  "$SCRIPT_DIR/tools/verify-offline-apk.sh" "${verifier_args[@]}"
}

verify_packaging_provenance_anchors() {
  [[ -n "$SDX_AOT_PROVENANCE_SHA256" &&
     -s "$SDX_AOT_RECEIPT" &&
     "$(sha256_file "$SDX_AOT_RECEIPT")" == "$SDX_AOT_PROVENANCE_SHA256" ]] || {
    echo "SDX AOT provenance receipt changed during APK packaging" >&2
    return 1
  }
  if [[ "$VARIANT" == tensor-g3 || "$VARIANT" == all ]]; then
    [[ -n "$TENSOR_G3_SOURCE_SHA256" &&
       -n "$TENSOR_G3_PROVENANCE_SHA256" &&
       -s "$TENSOR_G3_AAR" &&
       -s "$TENSOR_G3_FULL_RECEIPT" &&
       "$(sha256_file "$TENSOR_G3_AAR")" == "$TENSOR_G3_SOURCE_SHA256" &&
       "$(sha256_file "$TENSOR_G3_FULL_RECEIPT")" == "$TENSOR_G3_PROVENANCE_SHA256" ]] || {
      echo "Tensor G3 AAR or provenance receipt changed during APK packaging" >&2
      return 1
    }
  fi
}

stage_apk_exactly() {
  local source_apk="$1"
  local staging_apk="$2"
  local source_digest staged_digest source_digest_after attempt

  # GNU `sync FILE` issues a file-level fsync. Do not use `sync -f FILE` here:
  # that invokes syncfs for the entire backing filesystem and can report an
  # unrelated filesystem-wide EIO after this APK inode was already durable.
  # This function is called in a conditional command substitution, where Bash
  # disables errexit. Explicitly propagate every durability and digest failure.
  sync "$source_apk" || return 1
  source_digest="$(sha256sum "$source_apk" | cut -d ' ' -f 1)" || return 1
  [[ "$source_digest" =~ ^[0-9a-f]{64}$ ]] || return 1
  for attempt in 1 2 3; do
    rm -f -- "$staging_apk" || return 1
    # Never publish the Gradle output inode itself. A userspace stream into RAM
    # severs publication from delayed Zip/Gradle writes and Btrfs copy behavior.
    if ! dd if="$source_apk" of="$staging_apk" bs=16M conv=fsync status=none; then
      printf 'APK RAM staging copy failed (attempt %d/3): %s\n' "$attempt" "$source_apk" >&2
      continue
    fi
    sync "$staging_apk" || return 1
    staged_digest="$(sha256sum "$staging_apk" | cut -d ' ' -f 1)" || return 1
    [[ "$staged_digest" =~ ^[0-9a-f]{64}$ ]] || return 1
    source_digest_after="$(sha256sum "$source_apk" | cut -d ' ' -f 1)" || return 1
    [[ "$source_digest_after" =~ ^[0-9a-f]{64}$ ]] || return 1
    if [[ "$source_digest_after" != "$source_digest" ]]; then
      printf 'Gradle APK changed during publication staging: before=%s after=%s file=%s\n' \
        "$source_digest" "$source_digest_after" "$source_apk" >&2
      return 1
    fi
    if [[ "$staged_digest" == "$source_digest" ]]; then
      printf '%s\n' "$staged_digest"
      return 0
    fi
    printf 'APK RAM staging mismatch (attempt %d/3): source=%s staged=%s\n' \
      "$attempt" "$source_digest" "$staged_digest" >&2
  done
  return 1
}

publish_candidate() {
  local source_apk="$1"
  local stable_filename="$2"
  local variant="$3"
  # Explicit call argument only: normal builds retain their deletion/pruning policy.
  local publication_mode="${4:-build}" expected_digest="${5:-}"
  [[ "$publication_mode" == build || "$publication_mode" == retained ]] || return 1
  if [[ "$publication_mode" == retained ]]; then
    [[ "$expected_digest" =~ ^[0-9a-f]{64}$ &&
       "$(sha256_file "$source_apk")" == "$expected_digest" ]] || {
      echo "Retained APK expected SHA-256 mismatch" >&2; return 1;
    }
  fi
  local base="${stable_filename%.apk}"
  local stable_apk="$OUTPUT_DIR/$stable_filename"
  local ram_staging output_staging digest verified_digest published_digest final_digest
  local unique_apk unique_hash
  local source_bytes available_bytes source_real disposable_outputs_real
  local -a capacity_lines=()

  if [[ ! -d "$APK_STAGING_ROOT" || ! -w "$APK_STAGING_ROOT" ]]; then
    echo "APK RAM staging root must be an existing writable directory: $APK_STAGING_ROOT" >&2
    return 1
  fi
  mapfile -t capacity_lines < <(df -B1 --output=avail "$APK_STAGING_ROOT")
  available_bytes="${capacity_lines[1]//[[:space:]]/}"
  source_bytes="$(stat -c %s "$source_apk")" || return 1
  if [[ ! "$available_bytes" =~ ^[0-9]+$ ]] ||
      (( available_bytes < source_bytes + 67108864 )); then
    printf 'APK RAM staging lacks capacity: required=%s available=%s root=%s\n' \
      "$((source_bytes + 67108864))" "${available_bytes:-unknown}" "$APK_STAGING_ROOT" >&2
    return 1
  fi

  ram_staging="$(mktemp "$APK_STAGING_ROOT/.${base}.ram.XXXXXX.apk")" || return 1
  TEMPORARY_FILES+=("$ram_staging")
  if ! digest="$(stage_apk_exactly "$source_apk" "$ram_staging")"; then
    echo "Could not stage an exact APK in RAM for verification: $source_apk" >&2
    return 1
  fi
  if [[ "$publication_mode" == retained && "$digest" != "$expected_digest" ]]; then
    echo "Retained APK changed from its expected SHA-256 during staging" >&2; return 1
  fi
  if ! verify_apk "$ram_staging" "$variant"; then
    return 1
  fi
  sync "$ram_staging" || return 1
  verified_digest="$(sha256sum "$ram_staging" | cut -d ' ' -f 1)" || return 1
  if [[ "$verified_digest" != "$digest" ]]; then
    printf 'Verified APK bytes changed in RAM: before=%s after=%s\n' \
      "$digest" "$verified_digest" >&2
    return 1
  fi
  unzip -tq "$ram_staging" >/dev/null || {
    echo "RAM-staged APK failed its post-verification Zip integrity check: $ram_staging" >&2
    return 1
  }
  verify_packaging_provenance_anchors || return 1

  # Normal builds remove only the disposable Gradle APK. Recovery never removes
  # caller-owned retained inputs, even after successful verification/publication.
  source_real="$(realpath -e -- "$source_apk")" || return 1
  disposable_outputs_real="$(realpath -m -- "$APP_BUILD_ROOT/outputs")" || return 1
  if [[ -L "$source_apk" || "$source_real" != "$disposable_outputs_real/"* ]]; then
    echo "Refusing non-canonical Gradle APK after RAM verification: $source_apk" >&2
    return 1
  fi
  if [[ "$publication_mode" == build ]]; then
    rm -f -- "$source_real" || return 1
    echo "Removed verified disposable Gradle APK: $source_real"
  fi

  output_staging="$(mktemp "$OUTPUT_DIR/.${base}.publish.XXXXXX.apk")" || return 1
  TEMPORARY_FILES+=("$output_staging")
  if ! dd if="$ram_staging" of="$output_staging" bs=16M conv=fsync status=none; then
    echo "Could not stream the verified APK into a fresh publication inode: $output_staging" >&2
    return 1
  fi
  sync "$output_staging" || return 1
  published_digest="$(sha256sum "$output_staging" | cut -d ' ' -f 1)" || return 1
  if [[ "$published_digest" != "$digest" ]]; then
    printf 'Published APK copy differs from verified RAM bytes: verified=%s published=%s\n' \
      "$digest" "$published_digest" >&2
    return 1
  fi
  unzip -tq "$output_staging" >/dev/null || {
    echo "Published APK staging inode failed its Zip integrity check: $output_staging" >&2
    return 1
  }

  unique_apk="$OUTPUT_DIR/${base}-${APK_BUILD_ID}-${digest:0:12}.apk"
  mv -f -- "$output_staging" "$unique_apk" || return 1
  sync "$unique_apk" || return 1
  final_digest="$(sha256sum "$unique_apk" | cut -d ' ' -f 1)" || return 1
  if [[ "$final_digest" != "$digest" ]]; then
    printf 'APK changed after final rename: verified=%s final=%s file=%s\n' \
      "$digest" "$final_digest" "$unique_apk" >&2
    return 1
  fi
  unzip -tq "$unique_apk" >/dev/null || {
    echo "Final APK failed its Zip integrity check: $unique_apk" >&2
    return 1
  }

  unique_hash="$(mktemp "$OUTPUT_DIR/.${base}.candidate-sha256.XXXXXX")" || return 1
  TEMPORARY_FILES+=("$unique_hash")
  printf '%s  %s\n' "$digest" "$unique_apk" >"$unique_hash" || return 1
  sync "$unique_hash" || return 1
  mv -f -- "$unique_hash" "$unique_apk.sha256" || return 1

  local package_name test_source test_candidate test_digest normalized_aar normalized_sha normalized_candidate normalized_tmp
  local source_aar source_aar_sha provenance provenance_sha aot_sdk aot_provenance aot_provenance_sha candidate_manifest manifest_tmp
  package_name="none"
  test_source="none"
  test_candidate="none"
  test_digest="none"
  source_aar="none"
  source_aar_sha="none"
  provenance="none"
  provenance_sha="none"
  aot_sdk="none"
  aot_provenance="none"
  aot_provenance_sha="none"
  case "$variant" in
    vulkan) package_name="ai.kompile.chat.local.android.vulkan.debug" ;;
    hexagon) package_name="ai.kompile.chat.local.android.hexagon.debug" ;;
    tensorG3)
      package_name="ai.kompile.chat.local.android.tensorg3.debug"
      test_source="$APP_BUILD_ROOT/outputs/apk/androidTest/tensorG3/debug/app-tensorG3-debug-androidTest.apk"
      [[ -s "$test_source" ]] || {
        echo "Tensor G3 qualification test APK is missing: $test_source" >&2
        return 1
      }
      test_digest="$(sha256_file "$test_source")" || return 1
      test_candidate="$OUTPUT_DIR/${base}-$APK_BUILD_ID-${test_digest:0:12}-androidTest.apk"
      dd if="$test_source" of="$test_candidate" bs=16M conv=fsync status=none || return 1
      [[ "$(sha256_file "$test_candidate")" == "$test_digest" ]] || {
        echo "Tensor G3 qualification test APK changed during candidate publication" >&2
        return 1
      }
      source_aar="$(realpath -e -- "$TENSOR_G3_AAR")" || return 1
      source_aar_sha="$TENSOR_G3_SOURCE_SHA256"
      provenance="$TENSOR_G3_FULL_RECEIPT"
      provenance_sha="$TENSOR_G3_PROVENANCE_SHA256"
      aot_sdk="$(realpath -e -- "$SDX_LLM_SDK")" || return 1
      aot_provenance="$SDX_AOT_RECEIPT"
      aot_provenance_sha="$SDX_AOT_PROVENANCE_SHA256"
      ;;
    tensorG5) package_name="ai.kompile.chat.local.android.tensorg5.debug" ;;
  esac
  case "$variant" in
    vulkan) normalized_aar="$APP_BUILD_ROOT/sdx-normalized-aar/vulkan/sdx-runtime-vulkan.aar" ;;
    hexagon) normalized_aar="$APP_BUILD_ROOT/sdx-normalized-aar/hexagon/sdx-runtime-hexagon.aar" ;;
    tensorG3) normalized_aar="$APP_BUILD_ROOT/sdx-normalized-aar/tensor-g3/sdx-runtime-tensor-g3.aar" ;;
    tensorG5) normalized_aar="$APP_BUILD_ROOT/sdx-normalized-aar/tensor-g5/sdx-runtime-tensor-g5.aar" ;;
  esac
  normalized_sha="$(sha256_file "$normalized_aar")" || return 1
  normalized_candidate="$OUTPUT_DIR/${base}-${APK_BUILD_ID}-${normalized_sha:0:12}-runtime.aar"
  normalized_tmp="$(mktemp "$OUTPUT_DIR/.${base}.normalized-runtime.XXXXXX")" || return 1
  TEMPORARY_FILES+=("$normalized_tmp")
  dd if="$normalized_aar" of="$normalized_tmp" bs=16M conv=fsync status=none || return 1
  [[ "$(sha256_file "$normalized_tmp")" == "$normalized_sha" ]] || {
    echo "Normalized runtime AAR changed during candidate publication" >&2
    return 1
  }
  mv -f -- "$normalized_tmp" "$normalized_candidate" || return 1
  sync "$normalized_candidate" || return 1
  printf '%s  %s\n' "$normalized_sha" "$normalized_candidate" >"$normalized_candidate.sha256" || return 1
  sync "$normalized_candidate.sha256" || return 1
  candidate_manifest="$unique_apk.candidate"
  manifest_tmp="$(mktemp "$OUTPUT_DIR/.${base}.candidate-manifest.XXXXXX")" || return 1
  TEMPORARY_FILES+=("$manifest_tmp")
  {
    printf 'format=3\n'
    printf 'variant=%s\n' "$variant"
    printf 'package=%s\n' "$package_name"
    printf 'build_id=%s\n' "$APK_BUILD_ID"
    printf 'version_code=%s\n' "$APK_VERSION_CODE"
    printf 'candidate_apk=%s\n' "$(realpath -e -- "$unique_apk")"
    printf 'candidate_sha256=%s\n' "$digest"
    printf 'stable_apk=%s\n' "$stable_apk"
    printf 'test_apk=%s\n' "$test_candidate"
    printf 'test_apk_sha256=%s\n' "$test_digest"
    printf 'source_runtime_aar=%s\n' "$source_aar"
    printf 'source_runtime_aar_sha256=%s\n' "$source_aar_sha"
    printf 'normalized_runtime_aar=%s\n' "$(realpath -e -- "$normalized_candidate")"
    printf 'normalized_runtime_aar_sha256=%s\n' "$normalized_sha"
    printf 'runtime_provenance=%s\n' "$provenance"
    printf 'runtime_provenance_sha256=%s\n' "$provenance_sha"
    printf 'sdx_aot_sdk=%s\n' "$aot_sdk"
    printf 'sdx_aot_provenance=%s\n' "$aot_provenance"
    printf 'sdx_aot_provenance_sha256=%s\n' "$aot_provenance_sha"
    printf 'host_verification=PASS\n'
  } >"$manifest_tmp" || return 1
  sync "$manifest_tmp" || return 1
  mv -f -- "$manifest_tmp" "$candidate_manifest" || return 1
  printf '%s  %s\n' "$(sha256_file "$candidate_manifest")" "$candidate_manifest" >"$candidate_manifest.sha256" || return 1
  sync "$candidate_manifest" "$candidate_manifest.sha256" || return 1
  if [[ "$publication_mode" == build ]]; then
    "$SCRIPT_DIR/tools/prune-offline-apk-candidates.sh" \
      --output "$OUTPUT_DIR" \
      --base "$base" \
      --keep-manifest "$candidate_manifest" || return 1
  fi
  rm -f -- "$ram_staging" || return 1
  echo "Published host-verified candidate only; stable alias is unchanged: variant=$variant file=$unique_apk manifest=$candidate_manifest sha256=$digest"
}
