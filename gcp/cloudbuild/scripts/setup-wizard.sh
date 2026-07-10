#!/usr/bin/env bash
# Interactive setup wizard for the GCP TPU lanes: collects project/zone/TPU
# settings, stores the GitHub release token in Secret Manager (never on
# disk), writes the config, and offers to provision + submit builds.
#   setup-wizard.sh [CONFIG_PATH]   (default ~/.config/kompile-cloudbuild-gcp.env)
#   setup-wizard.sh --teardown [CONFIG_PATH]
# Rerunnable: existing config values become the prompt defaults.
set -euo pipefail
root="$(cd "$(dirname "$0")/../../.." && pwd)"
here="$root/gcp/cloudbuild/scripts"
mode=setup
if [ "${1:-}" = --teardown ]; then mode=teardown; shift; fi
config="${1:-$HOME/.config/kompile-cloudbuild-gcp.env}"
example="$root/gcp/cloudbuild/parameters.env.example"

DEFAULT_GRAAL_X64="https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-21.0.2/graalvm-community-jdk-21.0.2_linux-x64_bin.tar.gz"
DEFAULT_JDK11_X64="https://github.com/adoptium/temurin11-binaries/releases/download/jdk-11.0.25%2B9/OpenJDK11U-jdk_x64_linux_hotspot_11.0.25_9.tar.gz"

say()  { printf '\n== %s ==\n' "$*"; }
note() { printf '   %s\n' "$*"; }

ask() { # ask PROMPT [DEFAULT] -> REPLY
  local prompt="$1" default="${2:-}"
  READ_EOF=0
  if [ -n "$default" ]; then
    read -r -p "$prompt [$default]: " REPLY || READ_EOF=1
    REPLY="${REPLY:-$default}"
  else
    read -r -p "$prompt: " REPLY || READ_EOF=1
  fi
}

ask_required() {
  ask "$@"
  while [ -z "$REPLY" ]; do
    [ "$READ_EOF" = 1 ] && { echo "input closed with a required value missing" >&2; exit 2; }
    echo "   a value is required"
    ask "$@"
  done
}

ask_yn() { # ask_yn PROMPT DEFAULT(y|n)
  local prompt="$1" default="${2:-n}" hint answer
  [ "$default" = y ] && hint="Y/n" || hint="y/N"
  read -r -p "$prompt [$hint]: " answer || true
  answer="${answer:-$default}"
  case "$answer" in y|Y|yes|YES) return 0 ;; *) return 1 ;; esac
}

ask_secret() {
  read -rs -p "$1 (input hidden, empty to skip): " SECRET || true
  echo
}

check_url() {
  command -v curl >/dev/null 2>&1 || return 0
  curl -sfIL -o /dev/null "$1"
}

ask_url() {
  while :; do
    ask "$1" "$2"
    if check_url "$REPLY"; then return 0; fi
    echo "   WARNING: could not fetch $REPLY"
    ask_yn "   keep it anyway?" n && return 0
  done
}

set_kv() { # set_kv KEY VALUE FILE
  local key="$1" value="$2" file="$3" tmp
  tmp="$(mktemp)"
  awk -v k="$key" -v v="$value" '
    index($0, k"=") == 1 { print k "=" v; done = 1; next }
    { print }
    END { if (!done) print k "=" v }
  ' "$file" > "$tmp" && mv "$tmp" "$file"
}

# ---------------------------------------------------------------- teardown
if [ "$mode" = teardown ]; then
  [ -f "$config" ] || { echo "config not found: $config" >&2; exit 2; }
  set +u
  # shellcheck disable=SC1090
  source "$config"
  set -u
  : "${GCP_PROJECT_ID:?config has no GCP_PROJECT_ID}"
  prefix="${NAME_PREFIX:-kompile}"
  cat <<EOF
kompile Cloud Build (GCP) teardown
----------------------------------
Removes TPU-lane resources in project '$GCP_PROJECT_ID'.
EOF
  flags=(); need_confirm=0
  if ask_yn "Delete stray kompile-tpu-* TPU VMs (the expensive leak)?" y; then flags+=(--tpus); fi
  if ask_yn "Delete the Artifact Registry repository (builder images)?" n; then flags+=(--images); fi
  if ask_yn "Delete the private worker pool (if configured)?" n; then flags+=(--pool); fi
  if ask_yn "Delete the release token secret (GCP secrets have NO recovery)?" n; then flags+=(--secrets); need_confirm=1; fi
  if ask_yn "Delete the artifact bucket (ALL releases)?" n; then flags+=(--buckets); need_confirm=1; fi
  if ask_yn "Delete the build service account?" n; then flags+=(--iam); fi
  if [ "${#flags[@]}" -eq 0 ]; then
    echo "nothing selected; nothing deleted"
    exit 0
  fi
  echo
  echo "Selected: ${flags[*]}"
  if [ "$need_confirm" = 1 ]; then
    ask_required "Type the name prefix ('$prefix') to confirm unrecoverable deletion"
    [ "$REPLY" = "$prefix" ] || { echo "confirmation mismatch; aborting" >&2; exit 2; }
    flags+=(--yes)
  elif ! ask_yn "Proceed?" n; then
    echo "aborted; nothing deleted"
    exit 0
  fi
  exec "$here/teardown.sh" "$config" "${flags[@]}"
fi

# ---------------------------------------------------------------- greeting
cat <<'EOF'
kompile Cloud Build (GCP) setup wizard — TPU lanes
--------------------------------------------------
Sets up the TPU backend build on Cloud Build and hardware smoke tests on
real Cloud TPU VMs (the lane AWS cannot host). Tokens go to Secret Manager
only; the config file stores just names.
EOF
note "config file: $config"
if [ -f "$config" ]; then
  note "existing config found; its values are the defaults below"
  set +u
  # shellcheck disable=SC1090
  source "$config"
  set -u
fi

say "1/6 gcloud credentials"
command -v gcloud >/dev/null 2>&1 || {
  echo "The gcloud CLI is required: https://cloud.google.com/sdk/docs/install" >&2
  exit 2
}
while [ -z "$(gcloud auth list --filter=status:ACTIVE --format='value(account)' 2>/dev/null | head -n1)" ]; do
  echo "   no active gcloud account."
  if ask_yn "   run 'gcloud auth login' now?" y; then
    gcloud auth login
  else
    echo "   authenticate and rerun." >&2
    exit 2
  fi
done
ask_required "GCP project id" "${GCP_PROJECT_ID:-$(gcloud config get-value project 2>/dev/null || true)}"
GCP_PROJECT_ID="$REPLY"
ask_required "Region (Artifact Registry / GCS)" "${GCP_REGION:-us-central1}"
GCP_REGION="$REPLY"

say "2/6 TPU settings"
note "zones with capacity: gcloud compute tpus accelerator-types list --zone <zone>"
ask_required "TPU zone" "${TPU_ZONE:-}"
TPU_ZONE="$REPLY"
echo "   1) v5litepod-1 (cheapest v5e)   2) v4-8   3) v6e-1   4) other"
ask "Accelerator" "1"
case "$REPLY" in
  2) TPU_ACCELERATOR_TYPE=v4-8 ;;
  3) TPU_ACCELERATOR_TYPE=v6e-1 ;;
  4) ask_required "Accelerator type"; TPU_ACCELERATOR_TYPE="$REPLY" ;;
  *) TPU_ACCELERATOR_TYPE="${TPU_ACCELERATOR_TYPE:-v5litepod-1}" ;;
esac
ask_required "TPU runtime version" "${TPU_RUNTIME_VERSION:-tpu-ubuntu2204-base}"
TPU_RUNTIME_VERSION="$REPLY"

say "3/6 deeplearning4j source"
dl4j_default_repo="${DL4J_REPOSITORY:-}"
dl4j_default_ref="${DL4J_REF:-master}"
if [ -z "$dl4j_default_repo" ] && [ -d "$root/../deeplearning4j/.git" ]; then
  dl4j_default_repo="$(git -C "$root/../deeplearning4j" remote get-url origin 2>/dev/null || true)"
  dl4j_default_repo="${dl4j_default_repo/#git@github.com:/https://github.com/}"
  dl4j_default_ref="${DL4J_REF:-$(git -C "$root/../deeplearning4j" rev-parse --abbrev-ref HEAD 2>/dev/null || echo master)}"
fi
ask_required "DL4J clone URL" "${dl4j_default_repo:-https://github.com/deeplearning4j/deeplearning4j.git}"
DL4J_REPOSITORY="$REPLY"
ask_required "DL4J default ref" "$dl4j_default_ref"
DL4J_REF="$REPLY"

say "4/6 toolchain archives (baked into the builder image)"
ask_url "GraalVM 21 linux-x86_64 tar.gz" "${GRAALVM_ARCHIVE_URL:-$DEFAULT_GRAAL_X64}"
GRAALVM_ARCHIVE_URL="$REPLY"
ask_url "JDK 11 linux-x86_64 tar.gz" "${JDK11_ARCHIVE_URL:-$DEFAULT_JDK11_X64}"
JDK11_ARCHIVE_URL="$REPLY"
BUILD_KOMPILE="${BUILD_KOMPILE:-true}"
if ask_yn "Build the kompile cli-only distribution after the TPU backend?" \
          "$([ "$BUILD_KOMPILE" = true ] && echo y || echo n)"; then
  BUILD_KOMPILE=true
else
  BUILD_KOMPILE=false
fi

say "5/6 publishing"
note "GCS publishing is always on: gs://<artifact-bucket>/releases/<tag-or-adhoc>/"
GITHUB_RELEASE_REPO="${GITHUB_RELEASE_REPO:-}"
GITHUB_RELEASE_TOKEN_SECRET="${GITHUB_RELEASE_TOKEN_SECRET:-}"
release_token=""
prefix="${NAME_PREFIX:-kompile}"
if ask_yn "Also upload dists to GitHub Releases?" "$([ -n "$GITHUB_RELEASE_REPO" ] && echo y || echo n)"; then
  ask_required "GitHub repo for releases (owner/repo)" "$GITHUB_RELEASE_REPO"
  GITHUB_RELEASE_REPO="$REPLY"
  ask "Secret Manager name for the release token" "${GITHUB_RELEASE_TOKEN_SECRET:-$prefix-release-token}"
  GITHUB_RELEASE_TOKEN_SECRET="$REPLY"
  if ! gcloud secrets describe "$GITHUB_RELEASE_TOKEN_SECRET" --project "$GCP_PROJECT_ID" >/dev/null 2>&1 \
     || ask_yn "   secret exists — rotate it?" n; then
    ask_secret "   GitHub PAT with contents:write on $GITHUB_RELEASE_REPO"
    release_token="$SECRET"
  fi
else
  GITHUB_RELEASE_REPO=""
  GITHUB_RELEASE_TOKEN_SECRET=""
fi

say "6/6 writing config"
mkdir -p "$(dirname "$config")"
[ -f "$config" ] || cp "$example" "$config"
chmod 600 "$config"
set_kv GCP_PROJECT_ID "$GCP_PROJECT_ID" "$config"
set_kv GCP_REGION "$GCP_REGION" "$config"
set_kv TPU_ZONE "$TPU_ZONE" "$config"
set_kv TPU_ACCELERATOR_TYPE "$TPU_ACCELERATOR_TYPE" "$config"
set_kv TPU_RUNTIME_VERSION "$TPU_RUNTIME_VERSION" "$config"
set_kv DL4J_REPOSITORY "$DL4J_REPOSITORY" "$config"
set_kv DL4J_REF "$DL4J_REF" "$config"
set_kv GRAALVM_ARCHIVE_URL "$GRAALVM_ARCHIVE_URL" "$config"
set_kv JDK11_ARCHIVE_URL "$JDK11_ARCHIVE_URL" "$config"
set_kv BUILD_KOMPILE "$BUILD_KOMPILE" "$config"
set_kv GITHUB_RELEASE_REPO "$GITHUB_RELEASE_REPO" "$config"
set_kv GITHUB_RELEASE_TOKEN_SECRET "$GITHUB_RELEASE_TOKEN_SECRET" "$config"
note "wrote $config (chmod 600; tokens are NOT in this file)"

if [ -n "$release_token" ]; then
  if gcloud secrets describe "$GITHUB_RELEASE_TOKEN_SECRET" --project "$GCP_PROJECT_ID" >/dev/null 2>&1; then
    printf '%s' "$release_token" | gcloud secrets versions add "$GITHUB_RELEASE_TOKEN_SECRET" \
      --project "$GCP_PROJECT_ID" --data-file=- >/dev/null
  else
    printf '%s' "$release_token" | gcloud secrets create "$GITHUB_RELEASE_TOKEN_SECRET" \
      --project "$GCP_PROJECT_ID" --data-file=- >/dev/null
  fi
  note "stored secret '$GITHUB_RELEASE_TOKEN_SECRET'"
fi
unset release_token

say "summary"
cat <<EOF
   config:      $config
   project:     $GCP_PROJECT_ID ($GCP_REGION)
   tpu:         $TPU_ACCELERATOR_TYPE @ $TPU_ZONE ($TPU_RUNTIME_VERSION)
   dl4j:        $DL4J_REPOSITORY @ $DL4J_REF
   kompile:     $BUILD_KOMPILE (cli-only)
   gh releases: ${GITHUB_RELEASE_REPO:-disabled}
EOF

if ask_yn "Run provisioning now (provision.sh $config)?" y; then
  "$here/provision.sh" "$config"
  if ask_yn "Submit the TPU backend build now?" n; then
    "$here/start-build.sh" "$config" build
  fi
  if ask_yn "Submit a TPU hardware smoke now (creates a billed TPU VM)?" n; then
    "$here/start-build.sh" "$config" smoke
  fi
else
  echo "When ready:"
  echo "  $here/provision.sh $config"
  echo "  $here/start-build.sh $config build [DL4J_REF] [RELEASE_TAG]"
  echo "  $here/start-build.sh $config smoke [DL4J_REF] [RELEASE_TAG]"
fi
