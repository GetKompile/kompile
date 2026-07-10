#!/usr/bin/env bash
# Shared parser for targets.yml (strict one-line inline-map format).
# Sourced by deploy-all.sh, deploy-target.sh, start-all.sh, teardown.sh.

# list_targets FILE [kind] -> lines of "<target> <kind>"
list_targets() {
  local file="$1" wanted="${2:-all}"
  awk -v wanted="$wanted" '
    /^  [a-zA-Z0-9_.-]+: *\{/ {
      t=$1; sub(/:$/, "", t)
      k=$0; sub(/.*kind: */, "", k); sub(/[,}].*/, "", k)
      if (wanted == "all" || wanted == k) print t, k
    }' "$file"
}

# target_field FILE TARGET FIELD -> value (empty when absent)
target_field() {
  local file="$1" target="$2" field="$3"
  awk -v t="$target" -v f="$field" '
    $1 == t":" {
      if (match($0, f": *[^,}]+")) {
        v = substr($0, RSTART, RLENGTH)
        sub(f": *", "", v)
        gsub(/^ +| +$/, "", v)
        print v
      }
    }' "$file"
}

# targets_matching FILE KIND ENABLED -> filtered "<target> <kind>" lines.
# ENABLED is a space/comma separated allowlist; empty = everything of KIND.
targets_matching() {
  local file="$1" kind="$2" enabled="${3:-}"
  if [ -z "$enabled" ]; then
    list_targets "$file" "$kind"
    return 0
  fi
  local normalized=" ${enabled//,/ } "
  local line target k
  while read -r target k; do
    case "$normalized" in
      *" $target "*) printf '%s %s\n' "$target" "$k" ;;
    esac
  done < <(list_targets "$file" "$kind")
}
