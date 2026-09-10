#!/usr/bin/env bash
# Remove only the six abandoned August 20 Android ZIP assembly fragments.
# Run when no Android build is active. No recursive deletion or cache cleanup.
set -euo pipefail

root='/home/agibsonccc/Documents/GitHub/kompile/kompile-chat-local/mobile/android/build/sdx-android-build/tmp'
names=(
  scatterzipfragment4104287836017979574zip
  scatterzipfragment1744296475984076284zip
  scatterzipfragment2469986579345240733zip
  scatterzipfragment12585653295452368257zip
  scatterzipfragment5087046827163991776zip
  scatterzipfragment3491825963089037965zip
)
sizes=(236778591 257198702 334483143 341892236 351417415 357540616)

[[ -d "$root" && "$(realpath -e -- "$root")" == "$root" ]] || {
  printf 'Refusing unexpected or symlinked temp directory: %s\n' "$root" >&2
  exit 1
}
files=()
bytes=0
for i in "${!names[@]}"; do
  file="$root/${names[$i]}"
  if [[ ! -e "$file" && ! -L "$file" ]]; then
    printf 'Already absent: %s\n' "${names[$i]}"
    continue
  fi
  [[ -f "$file" && ! -L "$file" ]] || { printf 'Refusing non-regular file: %s\n' "$file" >&2; exit 1; }
  [[ "$(stat -c %s -- "$file")" == "${sizes[$i]}" ]] || {
    printf 'File size changed; refusing cleanup: %s\n' "$file" >&2
    exit 1
  }
  files+=("$file")
  bytes=$((bytes + sizes[i]))
done
if (( ${#files[@]} == 0 )); then
  printf 'Nothing to remove.\n'
  exit 0
fi
printf 'Will remove only these abandoned ZIP fragments (%s bytes total):\n' "$bytes"
printf '  %s\n' "${files[@]}"
printf '\nConfirm no Android build is running. Type DELETE to proceed: '
read -r confirmation
[[ "$confirmation" == DELETE ]] || { printf 'Cancelled.\n'; exit 0; }
# Recheck all targets before deleting any of them.
for i in "${!names[@]}"; do
  file="$root/${names[$i]}"
  [[ -e "$file" || -L "$file" ]] || continue
  [[ -f "$file" && ! -L "$file" && "$(stat -c %s -- "$file")" == "${sizes[$i]}" ]] || {
    printf 'Target changed; aborting: %s\n' "$file" >&2
    exit 1
  }
done
rm -v -- "${files[@]}"
printf '\nRemaining disk space:\n'
df -h /home
