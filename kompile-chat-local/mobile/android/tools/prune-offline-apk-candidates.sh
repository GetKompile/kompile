#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: prune-offline-apk-candidates.sh --output <dir> --base <name> --keep-manifest <file> [--dry-run]

Remove only superseded artifacts described by format-3 candidate manifests for one
APK family. The kept manifest and every path are validated inside the exact output
directory before any deletion. Malformed, symlinked, cross-family, or current
artifact references fail closed.
USAGE
}

OUTPUT_DIR=""
BASE=""
KEEP_MANIFEST=""
DRY_RUN=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --output) OUTPUT_DIR="${2:?missing value for --output}"; shift 2 ;;
    --base) BASE="${2:?missing value for --base}"; shift 2 ;;
    --keep-manifest) KEEP_MANIFEST="${2:?missing value for --keep-manifest}"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

[[ -n "$OUTPUT_DIR" && -n "$BASE" && -n "$KEEP_MANIFEST" ]] || {
  usage >&2
  exit 2
}
[[ "$BASE" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]] || {
  echo "Candidate base contains unsupported characters: $BASE" >&2
  exit 2
}
[[ "$DRY_RUN" == "0" || "$DRY_RUN" == "1" ]] || exit 2
[[ -d "$OUTPUT_DIR" && ! -L "$OUTPUT_DIR" ]] || {
  echo "Candidate output must be an existing non-symlink directory: $OUTPUT_DIR" >&2
  exit 2
}
OUTPUT_REAL="$(realpath -e -- "$OUTPUT_DIR")"

manifest_value() {
  local manifest="$1"
  local wanted="$2"
  local key value result=""
  local found=0

  while IFS='=' read -r key value || [[ -n "$key$value" ]]; do
    if [[ "$key" == "$wanted" ]]; then
      ((found += 1))
      result="$value"
    fi
  done <"$manifest"
  [[ "$found" == "1" ]] || {
    echo "Candidate manifest must contain exactly one $wanted field: $manifest" >&2
    return 1
  }
  printf '%s\n' "$result"
}

candidate_member() {
  local path="$1"
  local label="$2"
  local path_real path_name

  [[ -n "$path" && "$path" != "none" ]] || {
    echo "Missing $label path in candidate manifest" >&2
    return 1
  }
  [[ ! -L "$path" ]] || {
    echo "Refusing symlinked $label: $path" >&2
    return 1
  }
  path_real="$(realpath -m -- "$path")" || return 1
  path_name="$(basename -- "$path_real")"
  [[ "$(dirname -- "$path_real")" == "$OUTPUT_REAL" && "$path_name" == "$BASE"-* ]] || {
    echo "Refusing $label outside the exact output family: base=$BASE path=$path_real" >&2
    return 1
  }
  printf '%s\n' "$path_real"
}

validate_digest_name() {
  local path="$1"
  local build_id="$2"
  local kind="$3"
  local name token prefix suffix

  name="$(basename -- "$path")"
  prefix="$BASE-$build_id-"
  case "$kind" in
    apk) suffix=".apk" ;;
    test) suffix="-androidTest.apk" ;;
    runtime) suffix="-runtime.aar" ;;
    *) return 1 ;;
  esac
  [[ "$name" == "$prefix"*"$suffix" ]] || {
    echo "Candidate artifact name does not match its build identity: kind=$kind path=$path" >&2
    return 1
  }
  token="${name#"$prefix"}"
  token="${token%"$suffix"}"
  [[ "$token" =~ ^[0-9a-f]{12}$ ]] || {
    echo "Candidate artifact name has an invalid digest token: kind=$kind path=$path" >&2
    return 1
  }
}

validate_manifest_identity() {
  local manifest="$1"
  local expected_variant="${2:-}"
  local manifest_real format variant build_id apk test runtime

  [[ -f "$manifest" && ! -L "$manifest" ]] || {
    echo "Candidate manifest must be a regular non-symlink file: $manifest" >&2
    return 1
  }
  manifest_real="$(candidate_member "$manifest" "candidate manifest")" || return 1
  format="$(manifest_value "$manifest_real" format)" || return 1
  variant="$(manifest_value "$manifest_real" variant)" || return 1
  build_id="$(manifest_value "$manifest_real" build_id)" || return 1
  [[ "$format" == "3" ]] || {
    echo "Unsupported candidate manifest format: format=$format manifest=$manifest_real" >&2
    return 1
  }
  [[ -z "$expected_variant" || "$variant" == "$expected_variant" ]] || {
    echo "Candidate variant mismatch: expected=$expected_variant actual=$variant manifest=$manifest_real" >&2
    return 1
  }
  [[ "$build_id" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]] || {
    echo "Invalid candidate build identity: $build_id" >&2
    return 1
  }

  apk="$(candidate_member "$(manifest_value "$manifest_real" candidate_apk)" "candidate APK")" || return 1
  validate_digest_name "$apk" "$build_id" apk || return 1
  [[ "$manifest_real" == "$apk.candidate" ]] || {
    echo "Candidate manifest does not own its APK: manifest=$manifest_real apk=$apk" >&2
    return 1
  }

  test="$(manifest_value "$manifest_real" test_apk)" || return 1
  if [[ "$test" != "none" ]]; then
    test="$(candidate_member "$test" "qualification APK")" || return 1
    validate_digest_name "$test" "$build_id" test || return 1
  fi

  runtime="$(candidate_member "$(manifest_value "$manifest_real" normalized_runtime_aar)" "normalized runtime AAR")" || return 1
  validate_digest_name "$runtime" "$build_id" runtime || return 1
  printf '%s\n' "$variant"
}

remove_exact_member() {
  local path="$1"
  local label="$2"
  local validated

  validated="$(candidate_member "$path" "$label")" || return 1
  if [[ "$DRY_RUN" == "1" ]]; then
    [[ -e "$validated" || -L "$validated" ]] &&
      echo "Would remove superseded $label: $validated"
    return 0
  fi
  if [[ -e "$validated" || -L "$validated" ]]; then
    [[ ! -L "$validated" ]] || {
      echo "Refusing symlinked superseded $label: $validated" >&2
      return 1
    }
    rm -f -- "$validated"
    echo "Removed superseded $label: $validated"
  fi
}

KEEP_REAL="$(candidate_member "$KEEP_MANIFEST" "kept candidate manifest")"
KEEP_VARIANT="$(validate_manifest_identity "$KEEP_REAL")"
KEEP_BUILD_ID="$(manifest_value "$KEEP_REAL" build_id)"
KEEP_APK="$(candidate_member "$(manifest_value "$KEEP_REAL" candidate_apk)" "kept candidate APK")"
KEEP_TEST="$(manifest_value "$KEEP_REAL" test_apk)"
if [[ "$KEEP_TEST" != "none" ]]; then
  KEEP_TEST="$(candidate_member "$KEEP_TEST" "kept qualification APK")"
fi
KEEP_RUNTIME="$(candidate_member "$(manifest_value "$KEEP_REAL" normalized_runtime_aar)" "kept normalized runtime AAR")"

while IFS= read -r -d '' manifest; do
  manifest_real="$(candidate_member "$manifest" "superseded candidate manifest")" || exit 1
  [[ "$manifest_real" == "$KEEP_REAL" ]] && continue
  validate_manifest_identity "$manifest_real" "$KEEP_VARIANT" >/dev/null || exit 1

  build_id="$(manifest_value "$manifest_real" build_id)"
  apk="$(candidate_member "$(manifest_value "$manifest_real" candidate_apk)" "superseded candidate APK")"
  test="$(manifest_value "$manifest_real" test_apk)"
  runtime="$(candidate_member "$(manifest_value "$manifest_real" normalized_runtime_aar)" "superseded normalized runtime AAR")"

  [[ "$apk" != "$KEEP_APK" && "$runtime" != "$KEEP_RUNTIME" ]] || {
    echo "Refusing older manifest that aliases a kept artifact: $manifest_real" >&2
    exit 1
  }
  if [[ "$test" != "none" ]]; then
    test="$(candidate_member "$test" "superseded qualification APK")"
    [[ "$test" != "$KEEP_TEST" ]] || {
      echo "Refusing older manifest that aliases the kept qualification APK: $manifest_real" >&2
      exit 1
    }
  fi

  remove_exact_member "$apk" "candidate APK"
  remove_exact_member "$apk.sha256" "candidate APK checksum"
  if [[ "$test" != "none" ]]; then
    remove_exact_member "$test" "qualification APK"
    remove_exact_member "$test.sha256" "qualification APK checksum"
  fi
  remove_exact_member "$runtime" "normalized runtime AAR"
  remove_exact_member "$runtime.sha256" "normalized runtime AAR checksum"
  remove_exact_member "$manifest_real.sha256" "candidate manifest checksum"
  remove_exact_member "$manifest_real" "candidate manifest"
done < <(find "$OUTPUT_REAL" -maxdepth 1 -type f -name "$BASE-*.apk.candidate" -print0)

echo "Candidate retention complete: base=$BASE keep=$KEEP_REAL dry_run=$DRY_RUN"
