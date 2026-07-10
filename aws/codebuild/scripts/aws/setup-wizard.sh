#!/usr/bin/env bash
# Interactive setup wizard: collects every value one-go provisioning needs,
# stores GitHub tokens in Secrets Manager (never on disk), writes the config
# file, and offers to run provision.sh immediately.
#   setup-wizard.sh [CONFIG_PATH]      (default ~/.config/kompile-codebuild.env)
# Rerunnable: existing config values become the prompt defaults, and existing
# secrets can be kept or rotated.
set -euo pipefail
root="$(cd "$(dirname "$0")/../../../.." && pwd)"
here="$root/aws/codebuild/scripts/aws"
config="${1:-$HOME/.config/kompile-codebuild.env}"
example="$root/aws/codebuild/parameters.env.example"

DEFAULT_GRAAL_X64="https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-21.0.2/graalvm-community-jdk-21.0.2_linux-x64_bin.tar.gz"
DEFAULT_GRAAL_ARM="https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-21.0.2/graalvm-community-jdk-21.0.2_linux-aarch64_bin.tar.gz"
DEFAULT_JDK11_X64="https://github.com/adoptium/temurin11-binaries/releases/download/jdk-11.0.25%2B9/OpenJDK11U-jdk_x64_linux_hotspot_11.0.25_9.tar.gz"
DEFAULT_JDK11_ARM="https://github.com/adoptium/temurin11-binaries/releases/download/jdk-11.0.25%2B9/OpenJDK11U-jdk_aarch64_linux_hotspot_11.0.25_9.tar.gz"

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

ask_required() { # loop until non-empty
  ask "$@"
  while [ -z "$REPLY" ]; do
    [ "$READ_EOF" = 1 ] && { echo "input closed with a required value missing" >&2; exit 2; }
    echo "   a value is required"
    ask "$@"
  done
}

ask_yn() { # ask_yn PROMPT DEFAULT(y|n) -> return 0 for yes
  local prompt="$1" default="${2:-n}" hint answer
  [ "$default" = y ] && hint="Y/n" || hint="y/N"
  read -r -p "$prompt [$hint]: " answer || true
  answer="${answer:-$default}"
  case "$answer" in y|Y|yes|YES) return 0 ;; *) return 1 ;; esac
}

ask_secret() { # ask_secret PROMPT -> SECRET (input hidden; empty = skip)
  read -rs -p "$1 (input hidden, empty to skip): " SECRET || true
  echo
}

check_url() {
  command -v curl >/dev/null 2>&1 || return 0
  curl -sfIL -o /dev/null "$1"
}

ask_url() { # ask_url PROMPT DEFAULT -> REPLY (HEAD-validated, may keep anyway)
  while :; do
    ask "$1" "$2"
    if check_url "$REPLY"; then return 0; fi
    echo "   WARNING: could not fetch $REPLY"
    ask_yn "   keep it anyway?" n && return 0
  done
}

set_kv() { # set_kv KEY VALUE FILE — replace or append, preserving comments
  local key="$1" value="$2" file="$3" tmp
  tmp="$(mktemp)"
  awk -v k="$key" -v v="$value" '
    index($0, k"=") == 1 { print k "=" v; done = 1; next }
    { print }
    END { if (!done) print k "=" v }
  ' "$file" > "$tmp" && mv "$tmp" "$file"
}

store_secret() { # store_secret SECRET_ID VALUE — create or rotate
  local id="$1" value="$2"
  if aws secretsmanager describe-secret --region "$AWS_REGION" --secret-id "$id" >/dev/null 2>&1; then
    printf '%s' "$value" | aws secretsmanager put-secret-value --region "$AWS_REGION" \
      --secret-id "$id" --secret-string file:///dev/stdin >/dev/null
  else
    printf '%s' "$value" | aws secretsmanager create-secret --region "$AWS_REGION" \
      --name "$id" --secret-string file:///dev/stdin >/dev/null
  fi
  note "stored secret '$id'"
}

secret_exists() {
  aws secretsmanager describe-secret --region "$AWS_REGION" --secret-id "$1" >/dev/null 2>&1
}

# ---------------------------------------------------------------- greeting
cat <<'EOF'
kompile CodeBuild setup wizard
------------------------------
Walks through everything one-go provisioning needs: AWS credentials, source
repositories, toolchain archives, GitHub tokens (stored in Secrets Manager,
never in the config file), publishing, and target selection.
EOF
note "config file: $config"

# Existing config becomes the defaults for every prompt.
if [ -f "$config" ]; then
  note "existing config found; its values are the defaults below"
  set +u
  # shellcheck disable=SC1090
  source "$config"
  set -u
fi

# ---------------------------------------------------------------- step 1: AWS
say "1/8 AWS credentials"
command -v aws >/dev/null 2>&1 || {
  echo "The aws CLI is required: https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html" >&2
  exit 2
}
while ! aws sts get-caller-identity >/dev/null 2>&1; do
  echo "   AWS credentials are not usable (aws sts get-caller-identity failed)."
  if ask_yn "   run 'aws configure' now?" y; then
    aws configure
  else
    echo "   configure credentials (aws configure / AWS_PROFILE) and rerun." >&2
    exit 2
  fi
done
account="$(aws sts get-caller-identity --query Account --output text)"
note "authenticated to account $account"
ask_required "AWS region" "${AWS_REGION:-$(aws configure get region 2>/dev/null || true)}"
AWS_REGION="$REPLY"

# ------------------------------------------------------- step 2: kompile source
say "2/8 kompile source (what CodeBuild clones)"
origin="$(git -C "$root" remote get-url origin 2>/dev/null || true)"
origin="${origin/#git@github.com:/https://github.com/}"
ask_required "kompile clone URL" "${SOURCE_LOCATION:-$origin}"
SOURCE_LOCATION="$REPLY"
branch="$(git -C "$root" rev-parse --abbrev-ref HEAD 2>/dev/null || true)"
ask_required "kompile ref (branch/tag containing aws/codebuild)" "${KOMPILE_REF:-$branch}"
KOMPILE_REF="$REPLY"
if [ -n "$(git -C "$root" status --porcelain aws/codebuild 2>/dev/null)" ]; then
  note "WARNING: aws/codebuild has uncommitted changes — commit and push before building"
elif ! git -C "$root" merge-base --is-ancestor HEAD "@{upstream}" 2>/dev/null; then
  note "reminder: push '$KOMPILE_REF' so CodeBuild can see aws/codebuild"
fi

# --------------------------------------------------------- step 3: DL4J source
say "3/8 deeplearning4j source"
dl4j_default_repo="${DL4J_REPOSITORY:-}"
dl4j_default_ref="${DL4J_REF:-master}"
if [ -z "$dl4j_default_repo" ] && [ -d "$root/../deeplearning4j/.git" ]; then
  dl4j_default_repo="$(git -C "$root/../deeplearning4j" remote get-url origin 2>/dev/null || true)"
  dl4j_default_repo="${dl4j_default_repo/#git@github.com:/https://github.com/}"
  dl4j_default_ref="${DL4J_REF:-$(git -C "$root/../deeplearning4j" rev-parse --abbrev-ref HEAD 2>/dev/null || echo master)}"
fi
ask_required "DL4J clone URL" "${dl4j_default_repo:-https://github.com/deeplearning4j/deeplearning4j.git}"
DL4J_REPOSITORY="$REPLY"
ask_required "DL4J default ref (override per build later)" "$dl4j_default_ref"
DL4J_REF="$REPLY"

# ----------------------------------------------------- step 4: toolchain archives
say "4/8 toolchain archives baked into build images"
ask_url "GraalVM 21 linux-x86_64 tar.gz" "${GRAALVM_ARCHIVE_URL:-$DEFAULT_GRAAL_X64}"
GRAALVM_ARCHIVE_URL="$REPLY"
ask_url "JDK 11 linux-x86_64 tar.gz" "${JDK11_ARCHIVE_URL:-$DEFAULT_JDK11_X64}"
JDK11_ARCHIVE_URL="$REPLY"
GRAALVM_ARM_ARCHIVE_URL="${GRAALVM_ARM_ARCHIVE_URL:-}"
JDK11_ARM_ARCHIVE_URL="${JDK11_ARM_ARCHIVE_URL:-}"
if ask_yn "Enable the linux-arm64 lane (arm image via buildx/QEMU)?" "$([ -n "$GRAALVM_ARM_ARCHIVE_URL" ] && echo y || echo n)"; then
  ask_url "GraalVM 21 linux-aarch64 tar.gz" "${GRAALVM_ARM_ARCHIVE_URL:-$DEFAULT_GRAAL_ARM}"
  GRAALVM_ARM_ARCHIVE_URL="$REPLY"
  ask_url "JDK 11 linux-aarch64 tar.gz" "${JDK11_ARM_ARCHIVE_URL:-$DEFAULT_JDK11_ARM}"
  JDK11_ARM_ARCHIVE_URL="$REPLY"
fi

# ------------------------------------------------------------ step 5: tokens
say "5/8 GitHub access tokens"
note "tokens go to Secrets Manager only; the config stores just the secret ids"
GITHUB_TOKEN_SECRET="${GITHUB_TOKEN_SECRET:-}"
DL4J_TOKEN_SECRET="${DL4J_TOKEN_SECRET:-}"
source_token="" dl4j_token=""
prefix="${PROJECT_PREFIX:-kompile}"
if ask_yn "Are the kompile/DL4J repos private (CodeBuild needs a GitHub PAT)?" \
          "$([ -n "$GITHUB_TOKEN_SECRET" ] && echo y || echo n)"; then
  ask "Secrets Manager id for the source token" "${GITHUB_TOKEN_SECRET:-$prefix-github-token}"
  GITHUB_TOKEN_SECRET="$REPLY"
  if secret_exists "$GITHUB_TOKEN_SECRET"; then
    if ask_yn "   secret '$GITHUB_TOKEN_SECRET' exists — rotate it?" n; then
      ask_secret "   new GitHub PAT (repo read access)"
      source_token="$SECRET"
    fi
  else
    ask_secret "   GitHub PAT (repo read access)"
    source_token="$SECRET"
    [ -n "$source_token" ] || note "WARNING: no token entered and the secret does not exist yet"
  fi
  if ask_yn "Use the same token/secret for the in-build DL4J checkout?" y; then
    DL4J_TOKEN_SECRET="$GITHUB_TOKEN_SECRET"
  else
    ask "Secrets Manager id for the DL4J token" "${DL4J_TOKEN_SECRET:-$prefix-dl4j-token}"
    DL4J_TOKEN_SECRET="$REPLY"
    if ! secret_exists "$DL4J_TOKEN_SECRET" || ask_yn "   secret exists — rotate it?" n; then
      ask_secret "   DL4J GitHub PAT"
      dl4j_token="$SECRET"
    fi
  fi
else
  GITHUB_TOKEN_SECRET=""
  DL4J_TOKEN_SECRET=""
fi

# --------------------------------------------------------- step 6: publishing
say "6/8 publishing"
note "S3 publishing is always on: s3://<artifact-bucket>/releases/<tag-or-sha>/<target>/"
GITHUB_RELEASE_REPO="${GITHUB_RELEASE_REPO:-}"
GITHUB_RELEASE_TOKEN_SECRET="${GITHUB_RELEASE_TOKEN_SECRET:-}"
release_token=""
if ask_yn "Also upload dists to GitHub Releases?" "$([ -n "$GITHUB_RELEASE_REPO" ] && echo y || echo n)"; then
  ask_required "GitHub repo for releases (owner/repo)" "$GITHUB_RELEASE_REPO"
  GITHUB_RELEASE_REPO="$REPLY"
  ask "Secrets Manager id for the release token" "${GITHUB_RELEASE_TOKEN_SECRET:-$prefix-release-token}"
  GITHUB_RELEASE_TOKEN_SECRET="$REPLY"
  if ! secret_exists "$GITHUB_RELEASE_TOKEN_SECRET" || ask_yn "   secret exists — rotate it?" n; then
    ask_secret "   GitHub PAT with contents:write on $GITHUB_RELEASE_REPO"
    release_token="$SECRET"
    [ -n "$release_token" ] || note "WARNING: no token entered and the secret does not exist yet"
  fi
  note "start builds with a RELEASE_TAG to attach assets (start-all.sh ... v1.2.3)"
else
  GITHUB_RELEASE_REPO=""
  GITHUB_RELEASE_TOKEN_SECRET=""
fi

# ------------------------------------------------------ step 7: targets/compute
say "7/8 targets and compute"
echo "   1) all build targets (unhostable lanes skip automatically)"
echo "   2) linux starter set: linux-x86_64 linux-cuda-12.9"
echo "   3) custom list"
ask "Choice" "1"
case "$REPLY" in
  2) ENABLED_TARGETS="linux-x86_64 linux-cuda-12.9" ;;
  3) ask_required "Space-separated target names (see targets.yml)"; ENABLED_TARGETS="$REPLY" ;;
  *) ENABLED_TARGETS="" ;;
esac
echo "   Linux compute (GraalVM native-image wants big boxes):"
echo "   1) BUILD_GENERAL1_2XLARGE (144 GiB, 72 vCPU)   2) _XLARGE (72 GiB)   3) _LARGE (16 GiB)"
ask "Choice" "1"
case "$REPLY" in
  2) LINUX_COMPUTE_TYPE=BUILD_GENERAL1_XLARGE ;;
  3) LINUX_COMPUTE_TYPE=BUILD_GENERAL1_LARGE ;;
  *) LINUX_COMPUTE_TYPE=BUILD_GENERAL1_2XLARGE ;;
esac
MACOS_FLEET_COMPUTE="${MACOS_FLEET_COMPUTE:-}"
case "$AWS_REGION" in
  us-east-1|us-east-2|us-west-2|ap-southeast-2|eu-central-1)
    if ask_yn "Enable the macOS lane (reserved M2 fleet, bills 24h minimum while it exists)?" \
              "$([ -n "$MACOS_FLEET_COMPUTE" ] && echo y || echo n)"; then
      MACOS_FLEET_COMPUTE="${MACOS_FLEET_COMPUTE:-BUILD_GENERAL1_MEDIUM}"
    else
      MACOS_FLEET_COMPUTE=""
    fi ;;
  *) note "macOS lane unavailable in $AWS_REGION (MAC_ARM regions: us-east-1/2, us-west-2, ap-southeast-2, eu-central-1)" ;;
esac
note "Windows/AMD-GPU lanes need baked AMIs — see 'bake-ami.sh' in the README; skipping here"

# ------------------------------------------------------------ step 8: write
say "8/8 writing config"
mkdir -p "$(dirname "$config")"
[ -f "$config" ] || cp "$example" "$config"
chmod 600 "$config"
set_kv AWS_REGION "$AWS_REGION" "$config"
set_kv SOURCE_LOCATION "$SOURCE_LOCATION" "$config"
set_kv KOMPILE_REF "$KOMPILE_REF" "$config"
set_kv DL4J_REPOSITORY "$DL4J_REPOSITORY" "$config"
set_kv DL4J_REF "$DL4J_REF" "$config"
set_kv GRAALVM_ARCHIVE_URL "$GRAALVM_ARCHIVE_URL" "$config"
set_kv JDK11_ARCHIVE_URL "$JDK11_ARCHIVE_URL" "$config"
set_kv GRAALVM_ARM_ARCHIVE_URL "$GRAALVM_ARM_ARCHIVE_URL" "$config"
set_kv JDK11_ARM_ARCHIVE_URL "$JDK11_ARM_ARCHIVE_URL" "$config"
set_kv GITHUB_TOKEN_SECRET "$GITHUB_TOKEN_SECRET" "$config"
set_kv DL4J_TOKEN_SECRET "$DL4J_TOKEN_SECRET" "$config"
set_kv GITHUB_RELEASE_REPO "$GITHUB_RELEASE_REPO" "$config"
set_kv GITHUB_RELEASE_TOKEN_SECRET "$GITHUB_RELEASE_TOKEN_SECRET" "$config"
set_kv ENABLED_TARGETS "$ENABLED_TARGETS" "$config"
set_kv LINUX_COMPUTE_TYPE "$LINUX_COMPUTE_TYPE" "$config"
set_kv MACOS_FLEET_COMPUTE "$MACOS_FLEET_COMPUTE" "$config"
note "wrote $config (chmod 600; tokens are NOT in this file)"

if [ -n "$source_token" ]; then
  printf '%s' "$source_token" | "$here/set-github-token.sh" "$config" "$GITHUB_TOKEN_SECRET"
elif [ -n "$GITHUB_TOKEN_SECRET" ] && secret_exists "$GITHUB_TOKEN_SECRET"; then
  "$here/configure-source.sh" "$config" "$GITHUB_TOKEN_SECRET"
fi
if [ -n "$dl4j_token" ]; then store_secret "$DL4J_TOKEN_SECRET" "$dl4j_token"; fi
if [ -n "$release_token" ]; then store_secret "$GITHUB_RELEASE_TOKEN_SECRET" "$release_token"; fi
unset source_token dl4j_token release_token

say "summary"
cat <<EOF
   config:      $config
   region:      $AWS_REGION (account $account)
   kompile:     $SOURCE_LOCATION @ $KOMPILE_REF
   dl4j:        $DL4J_REPOSITORY @ $DL4J_REF
   targets:     ${ENABLED_TARGETS:-all build targets}
   compute:     $LINUX_COMPUTE_TYPE
   arm64 lane:  $([ -n "$GRAALVM_ARM_ARCHIVE_URL" ] && echo enabled || echo disabled)
   macOS lane:  $([ -n "$MACOS_FLEET_COMPUTE" ] && echo "$MACOS_FLEET_COMPUTE" || echo disabled)
   gh releases: ${GITHUB_RELEASE_REPO:-disabled}
EOF

if ask_yn "Run one-go provisioning now (provision.sh $config build)?" y; then
  "$here/provision.sh" "$config" build
  if ask_yn "Start all builds now?" n; then
    "$here/start-all.sh" "$config" build
  fi
else
  echo "When ready:"
  echo "  $here/provision.sh $config build"
  echo "  $here/start-all.sh $config build [KOMPILE_REF] [DL4J_REF] [RELEASE_TAG]"
fi
