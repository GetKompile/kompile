#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# Kompile Installer
#
# Downloads and installs the kompile distribution for the current platform.
# The distribution includes CLI binaries, the web application JAR, and default
# configuration.
#
# Usage:
#   curl -fsSL https://get.kompile.ai/install.sh | bash
#   curl -fsSL https://get.kompile.ai/install.sh | bash -s -- --version 1.0.0
#   bash install.sh --dev
#
#   --dev installs straight from the local checkout: the newest shaded/exec JAR
#   of each component is copied (and renamed) from the repository target/ dirs,
#   falling back to the local Maven repository (~/.m2) when a checkout artifact
#   is missing. No download, no archive, and no manifest cleanup is performed.
#
# Environment variables:
#   KOMPILE_INSTALL_DIR   Install directory (default: ~/.kompile)
#   KOMPILE_BASE_URL      Base URL for downloads (default: GitHub Releases)
#   KOMPILE_VERSION       Version to install (default: latest)
#   KOMPILE_DEV           Set to 1 to enable --dev developer mode
# ──────────────────────────────────────────────────────────────────────────────
set -euo pipefail

# ── Defaults ─────────────────────────────────────────────────────────────────

INSTALL_DIR="${KOMPILE_INSTALL_DIR:-${HOME}/.kompile}"
BASE_URL="${KOMPILE_BASE_URL:-}"
VERSION="${KOMPILE_VERSION:-}"
# Default: try the `full` variant first (CLI + server jars + bundled runtime),
# fall back to `cli-only` if the full variant is not published for this platform.
# Override with --variant or KOMPILE_VARIANT.
VARIANT="${KOMPILE_VARIANT:-}"       # empty = auto (try full then cli-only)
BACKEND_PROFILE="${KOMPILE_BACKEND_PROFILE:-}"
DISTRIBUTION_CLASSIFIER=""
GITHUB_REPO="GetKompile/kompile"
VERBOSE=false
MODIFY_PATH="${KOMPILE_MODIFY_PATH:-0}"
DEV_MODE="${KOMPILE_DEV:-0}"
UPDATE_MODE=false
FORCE=false
ARCHIVE_URL=""
CHECKSUM_URL=""
TEMP_DIR=""
UPDATE_WORK_DIR=""
UPDATE_MUTATION_STARTED=false
UPDATE_COMMITTED=false
UPDATE_LOCK_DIR=""
UPDATE_LOCK_HELD=false
UPDATE_INSTALLED_PATHS=""
UPDATE_BACKED_PATHS=""
UPDATE_BACKUP_ROOT=""
PRESERVE_UPDATE_RECOVERY=false

cleanup() {
    local status=$?
    trap - EXIT INT TERM
    set +e
    if [ "${UPDATE_MUTATION_STARTED}" = true ] && [ "${UPDATE_COMMITTED}" != true ] \
            && declare -F rollback_update_payload >/dev/null; then
        if rollback_update_payload "${UPDATE_INSTALLED_PATHS}" \
                "${UPDATE_BACKED_PATHS}" "${UPDATE_BACKUP_ROOT}"; then
            UPDATE_MUTATION_STARTED=false
            info "Previous managed payload restored after update failure"
        else
            PRESERVE_UPDATE_RECOVERY=true
            error "Rollback was incomplete; recovery files were retained at ${UPDATE_WORK_DIR}"
            status=1
        fi
    fi
    if [ -n "${TEMP_DIR}" ] && [ -d "${TEMP_DIR}" ]; then
        if ! rm -rf -- "${TEMP_DIR}"; then
            error "Could not remove installer temporary directory: ${TEMP_DIR}"
            status=1
        fi
    fi
    if [ "${PRESERVE_UPDATE_RECOVERY}" != true ] \
            && [ -n "${UPDATE_WORK_DIR}" ] && [ -d "${UPDATE_WORK_DIR}" ]; then
        if ! rm -rf -- "${UPDATE_WORK_DIR}"; then
            error "Could not remove completed update workspace: ${UPDATE_WORK_DIR}"
            status=1
        fi
    fi
    if [ "${UPDATE_LOCK_HELD}" = true ] && [ "${PRESERVE_UPDATE_RECOVERY}" != true ]; then
        rm -f -- "${UPDATE_LOCK_DIR}/pid" 2>/dev/null
        if ! rmdir -- "${UPDATE_LOCK_DIR}" 2>/dev/null; then
            error "Could not release update lock: ${UPDATE_LOCK_DIR}"
            status=1
        fi
    fi
    exit "${status}"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# ── Argument parsing ─────────────────────────────────────────────────────────

while [ $# -gt 0 ]; do
    case "$1" in
        --version|-v)   VERSION="$2"; shift 2 ;;
        --variant)      VARIANT="$2"; shift 2 ;;
        --backend-profile) BACKEND_PROFILE="$2"; shift 2 ;;
        --distribution-classifier) DISTRIBUTION_CLASSIFIER="$2"; shift 2 ;;
        --dir|-d)       INSTALL_DIR="$2"; shift 2 ;;
        --url|-u)       BASE_URL="$2"; shift 2 ;;
        --dev|--developer) DEV_MODE=1; shift ;;
        --update)       UPDATE_MODE=true; shift ;;
        --archive-url)  ARCHIVE_URL="$2"; shift 2 ;;
        --checksum-url) CHECKSUM_URL="$2"; shift 2 ;;
        --force)        FORCE=true; shift ;;
        --verbose)      VERBOSE=true; shift ;;
        --modify-path)  MODIFY_PATH=1; shift ;;
        --help|-h)
            echo "Usage: install.sh [OPTIONS]"
            echo ""
            echo "Options:"
            echo "  --version, -v VERSION   Version to install (default: latest)"
            echo "  --variant VARIANT       Distribution variant (default: auto)"
            echo "                          Auto tries 'full' first, falls back to 'cli-only'."
            echo "                          Options: full, local, cli-only, hosted, cpu-intel, cpu-arm, cuda, amd-zluda"
            echo "                          'local' contains the CLI and native folder-local MCP/model workers."
            echo "                          The 'full' variant includes server jars and a bundled Java runtime."
            echo "                          'kompile project init' works fully with full or local distributions."
            echo "  --backend-profile P     Select a backend-qualified archive lane."
            echo "                          Examples: cpu, cpu-avx2, cuda-12.9, zluda-rocm-7.2.4, zluda-rocm-10.0.0"
            echo "  --dir, -d DIR           Install directory (default: ~/.kompile)"
            echo "  --url, -u URL           Base URL for distribution archives"
            echo "  --modify-path           Append the PATH export line to your shell profile idempotently"
            echo "                          (same effect as setting KOMPILE_MODIFY_PATH=1)"
            echo "  --dev, --developer      Developer install: copy the newest shaded/exec JARs directly"
            echo "                          from the repo checkout (fallback: ~/.m2) into <dir>/lib —"
            echo "                          no download, no archive, no manifest cleanup."
            echo "  --force                  Replace modified managed files during update/reinstall"
            echo "  --verbose               Show detailed output"
            echo "  --help, -h              Show this help"
            exit 0
            ;;
        *)
            echo "Unknown option: $1" >&2
            exit 1
            ;;
    esac
done

if [ "${UPDATE_MODE}" = true ] && [ "${DEV_MODE}" = "1" ]; then
    echo "--update and --dev cannot be used together" >&2
    exit 1
fi
validate_token() {
    local name="$1" value="$2"
    if [ -z "${value}" ] || ! [[ "${value}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]; then
        echo "Invalid ${name}: ${value}" >&2
        exit 1
    fi
}

if [ -n "${VERSION}" ]; then validate_token "version" "${VERSION#v}"; VERSION="${VERSION#v}"; fi
if [ -n "${VARIANT}" ]; then validate_token "variant" "${VARIANT}"; fi
if [ -n "${BACKEND_PROFILE}" ]; then validate_token "backend profile" "${BACKEND_PROFILE}"; fi
if [ -n "${DISTRIBUTION_CLASSIFIER}" ]; then validate_token "distribution classifier" "${DISTRIBUTION_CLASSIFIER}"; fi

# The product ZLUDA variant tracks the currently qualified default. Exact ROCm
# candidates remain opt-in through --backend-profile.
if [ -z "${BACKEND_PROFILE}" ] && [ "${VARIANT}" = "amd-zluda" ]; then
    BACKEND_PROFILE="zluda-rocm-7.2.4"
fi

# ── Platform detection ───────────────────────────────────────────────────────

detect_platform() {
    local os arch

    case "$(uname -s)" in
        Linux*)     os="linux" ;;
        Darwin*)    os="macosx" ;;
        CYGWIN*|MINGW*|MSYS*) os="windows" ;;
        *)
            echo "Unsupported operating system: $(uname -s)" >&2
            exit 1
            ;;
    esac

    case "$(uname -m)" in
        x86_64|amd64)   arch="x86_64" ;;
        aarch64|arm64)  arch="arm64" ;;
        armv7l|armhf)   arch="armhf" ;;
        *)
            echo "Unsupported architecture: $(uname -m)" >&2
            exit 1
            ;;
    esac

    echo "${os}-${arch}"
}

PLATFORM="$(detect_platform)"
if [ "${BACKEND_PROFILE}" = "zluda-rocm-10.0.0" ] \
        && [ "${PLATFORM}" != "linux-x86_64" ]; then
    echo "ROCm 10 ZLUDA distributions are supported only on linux-x86_64 (got ${PLATFORM})" >&2
    exit 1
fi
ARCHIVE_EXT="tar.gz"
if [[ "${PLATFORM}" == windows* ]]; then
    ARCHIVE_EXT="zip"
fi

release_platform() {
    case "${BACKEND_PROFILE}" in
        ""|cpu) echo "${PLATFORM}" ;;
        cpu-*) echo "${PLATFORM}-${BACKEND_PROFILE#cpu-}" ;;
        cuda-*) echo "${PLATFORM}-${BACKEND_PROFILE}" ;;
        zluda) echo "${PLATFORM}-cuda-12.9-zluda" ;;
        zluda-rocm-*) echo "${PLATFORM}-cuda-12.9-${BACKEND_PROFILE}" ;;
        *) echo "${PLATFORM}-${BACKEND_PROFILE}" ;;
    esac
}

RELEASE_PLATFORM="$(release_platform)"

# ── Progress display ─────────────────────────────────────────────────────────
# (Defined early: --dev below runs before version resolution.)

info()  { echo "  $*"; }
step()  { echo ""; echo "==> $*"; }
error() { echo "ERROR: $*" >&2; }

# ── Developer mode: direct jar copy from checkout / local Maven repo ─────────
# No download, no archive, no manifest cleanup. Copies the newest shaded/exec
# JAR of each component from the repository target/ directory, falling back to
# ~/.m2/repository/ai/kompile when the checkout artifact is absent, and renames
# to the canonical lib/ names used by build-dist.sh.

dev_pick_jar() {
    local dir="$1" classifier="$2" best="" f
    for f in "${dir}"/*-"${classifier}".jar; do
        [ -f "$f" ] || continue
        case "$f" in *original-*|*-sources.jar|*-javadoc.jar) continue ;; esac
        if [ -z "$best" ] || [ "$f" -nt "$best" ]; then best="$f"; fi
    done
    # Fallback (same rule as build-dist.sh): some modules shade in place, so the
    # distributable artifact is the plain jar (original-* is the pre-shade copy).
    if [ -z "$best" ]; then
        for f in "${dir}"/*.jar; do
            [ -f "$f" ] || continue
            case "$f" in *original-*|*-sources.jar|*-javadoc.jar) continue ;; esac
            if [ -z "$best" ] || [ "$f" -nt "$best" ]; then best="$f"; fi
        done
    fi
    if [ -n "$best" ]; then printf '%s' "$best"; fi
    return 0
}

dev_copy() {
    local repo_dir="$1" artifact="$2" classifier="$3" lib_name="$4"
    local src cand m2_dir
    src="$(dev_pick_jar "${repo_dir}" "${classifier}")"
    if [ -z "${src}" ]; then
        for m2_dir in "${HOME}/.m2/repository/ai/kompile/${artifact}"/*/; do
            [ -d "${m2_dir}" ] || continue
            cand="$(dev_pick_jar "${m2_dir}" "${classifier}")"
            if [ -n "${cand}" ] && { [ -z "${src}" ] || [ "${cand}" -nt "${src}" ]; }; then
                src="${cand}"
            fi
        done
    fi
    if [ -n "${src}" ]; then
        cp -f "${src}" "${INSTALL_DIR}/lib/${lib_name}"
        info "  ${lib_name}  <-  ${src} ($(du -h "${src}" | cut -f1))"
    else
        info "  SKIP ${lib_name}  (no ${classifier} jar in ${repo_dir} or ~/.m2/repository/ai/kompile/${artifact})"
    fi
}

if [ "${DEV_MODE}" = "1" ]; then
    step "Developer install (direct copy from checkout / ~/.m2 — nothing is deleted)"
    echo ""
    info "Install:   ${INSTALL_DIR}"
    mkdir -p "${INSTALL_DIR}/lib"

    # Component table mirrors build-dist.sh exactly (source target -> lib name).
    dev_copy "kompile-cli/kompile-cli-main/target"                                                          kompile-cli                       shaded kompile-cli.jar
    dev_copy "kompile-cli/kompile-agent-cli/target"                                                         kompile-agent                     shaded kompile-agent.jar
    dev_copy "kompile-cli/kompile-app-cli/target"                                                           kompile-app-cli                   shaded kompile-app-cli.jar
    dev_copy "kompile-cli/kompile-model-cli/target"                                                         kompile-model                     shaded kompile-model.jar
    dev_copy "kompile-cli/kompile-component-cli/target"                                                     kompile-component                 shaded kompile-component.jar
    dev_copy "kompile-app/kompile-app-parent/kompile-app-subprocess/kompile-app-subprocess-serving/target"  kompile-app-subprocess-serving    exec   kompile-model-serving.jar
    dev_copy "kompile-app/kompile-data/kompile-pipelines/kompile-pipeline-serving/target"                   kompile-pipeline-serving          exec   kompile-pipeline-serving.jar

    # Optional extras: copied only when present.
    dev_copy "kompile-app/kompile-data/kompile-compute-graphs/kompile-compute-graph-scripting/target"       kompile-compute-graph-scripting   exec   kompile-scripting-worker.jar
    dev_copy "kompile-app/kompile-middleware/kompile-sdk-serving/target"                                    kompile-sdk-serving               shaded kompile-sdk-serving.jar

    # Swapped exec jars invalidate any previously extracted subprocess classpath.
    if [ -d "${INSTALL_DIR}/lib/.boot-inf-extracted" ]; then
        rm -rf "${INSTALL_DIR}/lib/.boot-inf-extracted"
        info "Removed stale ${INSTALL_DIR}/lib/.boot-inf-extracted"
    fi

    step "Developer installation complete!"
    KOMPILE_BIN="${INSTALL_DIR}/bin/kompile"
    if [ -x "${KOMPILE_BIN}" ]; then
        echo ""
        echo "── Running post-install diagnostics ──────────────────────────────"
        KOMPILE_INSTALL_DIR="${INSTALL_DIR}" "${KOMPILE_BIN}" doctor --no-color || true
    fi
    exit 0
fi

# ── Version resolution ───────────────────────────────────────────────────────

resolve_version() {
    if [ -n "${VERSION}" ]; then
        echo "${VERSION}"
        return
    fi

    # The repository also hosts model/data releases (for example `opennlp`), so
    # GitHub's generic /releases/latest endpoint is not a Kompile product channel.
    # Read releases in publication order and select the first version-shaped tag.
    local response versions latest
    if command -v curl &>/dev/null; then
        response=$(curl -fsSL "https://api.github.com/repos/${GITHUB_REPO}/releases?per_page=100" \
            2>/dev/null || true)
    elif command -v wget &>/dev/null; then
        response=$(wget -qO- "https://api.github.com/repos/${GITHUB_REPO}/releases?per_page=100" \
            2>/dev/null || true)
    else
        response=""
    fi
    versions=$(printf '%s\n' "${response}" \
        | sed -n 's/.*"tag_name"[[:space:]]*:[[:space:]]*"v\([0-9][^"]*\)".*/\1/p')
    latest=$(printf '%s\n' "${versions}" | sed -n '1p')

    if [ -z "${latest}" ]; then
        echo "Could not determine latest version. Specify with --version." >&2
        exit 1
    fi

    echo "${latest}"
}

VERSION="$(resolve_version)"
validate_token "version" "${VERSION}"

# ── Download URL ─────────────────────────────────────────────────────────────

# Check whether a URL is reachable (HEAD request).
url_exists() {
    local url="$1"
    if command -v curl &>/dev/null; then
        curl -fsSL --head -o /dev/null "${url}" 2>/dev/null
    elif command -v wget &>/dev/null; then
        wget -q --spider "${url}" 2>/dev/null
    else
        return 1
    fi
}

resolve_download_url() {
    local variant="$1"
    local extension="$2"
    local name="kompile-dist-${VERSION}-${variant}-${RELEASE_PLATFORM}.${extension}"
    if [ -n "${BASE_URL}" ]; then
        echo "${BASE_URL%/}/${name}"
    else
        echo "https://github.com/${GITHUB_REPO}/releases/download/v${VERSION}/${name}"
    fi
}

select_download_url() {
    local variant="$1" extension candidate
    if [ "${UPDATE_MODE}" = true ]; then
        local extensions=(zip)
    elif [[ "${PLATFORM}" == windows* ]]; then
        local extensions=(zip tar.gz)
    else
        local extensions=(tar.gz zip)
    fi
    for extension in "${extensions[@]}"; do
        candidate=$(resolve_download_url "${variant}" "${extension}")
        if url_exists "${candidate}"; then
            printf '%s|%s\n' "${extension}" "${candidate}"
            return 0
        fi
    done
    return 1
}

if [ -z "${VARIANT}" ]; then
    # Auto: try full variant first, fall back to cli-only.
    if select_download_url full >/dev/null; then
        VARIANT="full"
    else
        echo "  (full distribution not published for ${PLATFORM} — installing cli-only; server components unavailable)"
        VARIANT="cli-only"
    fi
fi
if [ -z "${DISTRIBUTION_CLASSIFIER}" ]; then
    DISTRIBUTION_CLASSIFIER="${VARIANT}-${RELEASE_PLATFORM}"
fi

if [ -n "${ARCHIVE_URL}" ]; then
    DOWNLOAD_URL="${ARCHIVE_URL}"
    ARCHIVE_PATH="${ARCHIVE_URL%%\?*}"
    ARCHIVE_NAME="${ARCHIVE_PATH##*/}"
    case "${ARCHIVE_NAME}" in
        *.tar.gz) ARCHIVE_EXT="tar.gz" ;;
        *.zip) ARCHIVE_EXT="zip" ;;
        *) echo "Unsupported archive URL (expected .tar.gz or .zip): ${ARCHIVE_URL}" >&2; exit 1 ;;
    esac
else
    if ! DOWNLOAD_SELECTION=$(select_download_url "${VARIANT}"); then
        echo "No ${VARIANT}-${RELEASE_PLATFORM} distribution archive was found for version ${VERSION}" >&2
        exit 1
    fi
    ARCHIVE_EXT="${DOWNLOAD_SELECTION%%|*}"
    DOWNLOAD_URL="${DOWNLOAD_SELECTION#*|}"
    ARCHIVE_NAME="kompile-dist-${VERSION}-${VARIANT}-${RELEASE_PLATFORM}.${ARCHIVE_EXT}"
fi
if [ "${UPDATE_MODE}" = true ] && [ "${ARCHIVE_EXT}" != "zip" ]; then
    echo "Managed updates require a ZIP archive so link-free extraction can be validated safely" >&2
    exit 1
fi
if [ -z "${CHECKSUM_URL}" ]; then
    CHECKSUM_URL="${DOWNLOAD_URL}.sha256"
fi
if [ "${UPDATE_MODE}" = true ]; then
    case "${DOWNLOAD_URL}" in https://*|file://*) ;; *) echo "Update archive URL must use https or file" >&2; exit 1 ;; esac
    case "${CHECKSUM_URL}" in https://*|file://*) ;; *) echo "Update checksum URL must use https or file" >&2; exit 1 ;; esac
fi

# ── Update validation and promotion helpers ─────────────────────────────────

sha256_file() {
    if command -v sha256sum &>/dev/null; then
        sha256sum "$1" | awk '{print $1}'
    elif command -v shasum &>/dev/null; then
        shasum -a 256 "$1" | awk '{print $1}'
    else
        return 1
    fi
}

safe_relative_path() {
    local path="$1"
    case "${path}" in
        ""|.|./*|/*|[A-Za-z]:*|../*|*/../*|*/..|*/./*|*/.|*//*|*\\*) return 1 ;;
    esac
    return 0
}

state_owned_path() {
    case "$1" in
        config|config/*|data|data/*|models|models/*|components|components/*|\
        fact-sheets|fact-sheets/*|archives|archives/*|anserini|anserini/*) return 0 ;;
        *) return 1 ;;
    esac
}

reserved_update_path() {
    case "$1" in
        .update.lock|.update.lock/*|.manifest.sha256.update.*) return 0 ;;
        *) return 1 ;;
    esac
}

validate_install_target_parent() {
    local path="$1" parent current segment
    parent="${path%/*}"
    [ "${parent}" != "${path}" ] || return 0
    current="${INSTALL_DIR}"
    while [ -n "${parent}" ]; do
        segment="${parent%%/*}"
        current="${current}/${segment}"
        if [ -L "${current}" ]; then
            error "Managed path traverses a symbolic-link directory: ${path}"
            return 1
        fi
        if [ -e "${current}" ] && [ ! -d "${current}" ]; then
            error "Managed path parent is not a directory: ${path}"
            return 1
        fi
        [ "${parent}" = "${segment}" ] && break
        parent="${parent#*/}"
    done
}

manifest_path() {
    local line="$1" path
    path="${line#*  }"
    if [ "${path}" = "${line}" ]; then return 1; fi
    printf '%s' "${path}"
}

verify_manifest() {
    local root="$1" manifest="$2" ignore_state="$3"
    local line expected path checked_manifest
    [ -f "${manifest}" ] || { error "Missing internal manifest: ${manifest}"; return 1; }
    checked_manifest=$(mktemp "${TEMP_DIR}/verify-manifest.XXXXXX") || return 1
    while IFS= read -r line || [ -n "${line}" ]; do
        expected="${line%% *}"
        path=$(manifest_path "${line}") || { error "Malformed manifest line: ${line}"; return 1; }
        safe_relative_path "${path}" || { error "Unsafe manifest path: ${path}"; return 1; }
        reserved_update_path "${path}" \
            && { error "Manifest uses an updater-reserved path: ${path}"; return 1; }
        if [ "${ignore_state}" = true ] && state_owned_path "${path}"; then continue; fi
        [[ "${expected}" =~ ^[0-9A-Fa-f]{64}$ ]] \
            || { error "Invalid manifest checksum for ${path}"; rm -f "${checked_manifest}"; return 1; }
        printf '%s\n' "${line}" >> "${checked_manifest}" || return 1
    done < "${manifest}"
    if command -v sha256sum &>/dev/null; then
        (cd "${root}" && sha256sum -c "${checked_manifest}" >/dev/null) \
            || { error "Manifest checksum validation failed"; rm -f "${checked_manifest}"; return 1; }
    elif command -v shasum &>/dev/null; then
        (cd "${root}" && shasum -a 256 -c "${checked_manifest}" >/dev/null) \
            || { error "Manifest checksum validation failed"; rm -f "${checked_manifest}"; return 1; }
    else
        error "No SHA-256 utility is available"
        rm -f "${checked_manifest}"
        return 1
    fi
    rm -f "${checked_manifest}"
}

collect_manifest_paths() {
    local manifest="$1" output="$2" ignore_state="$3" line path
    : > "${output}"
    while IFS= read -r line || [ -n "${line}" ]; do
        path=$(manifest_path "${line}") || { error "Malformed manifest line: ${line}"; return 1; }
        safe_relative_path "${path}" || { error "Unsafe manifest path: ${path}"; return 1; }
        reserved_update_path "${path}" \
            && { error "Manifest uses an updater-reserved path: ${path}"; return 1; }
        if [ "${ignore_state}" = true ] && state_owned_path "${path}"; then continue; fi
        printf '%s\n' "${path}" >> "${output}"
    done < "${manifest}"
}

validate_archive_entries() {
    local entries_file="${TEMP_DIR}/archive.entries" entry first top=""
    if [ "${ARCHIVE_EXT}" = "tar.gz" ]; then
        tar -tzf "${TEMP_ARCHIVE}" > "${entries_file}"
    else
        command -v unzip &>/dev/null || { error "unzip is required but not found"; return 1; }
        if [ "${UPDATE_MODE}" = true ]; then
            while IFS= read -r detail; do
                detail="${detail#"${detail%%[![:space:]]*}"}"
                case "${detail}" in
                    l*) error "Update ZIP contains a symbolic link; link entries are not allowed"; return 1 ;;
                esac
            done < <(unzip -Z -l "${TEMP_ARCHIVE}")
        fi
        unzip -Z1 "${TEMP_ARCHIVE}" > "${entries_file}"
    fi
    while IFS= read -r entry || [ -n "${entry}" ]; do
        entry="${entry%/}"
        [ -n "${entry}" ] || continue
        safe_relative_path "${entry}" || { error "Unsafe archive entry: ${entry}"; return 1; }
        first="${entry%%/*}"
        if [ -z "${top}" ]; then
            top="${first}"
        elif [ "${top}" != "${first}" ]; then
            error "Archive contains multiple top-level entries: ${top}, ${first}"
            return 1
        fi
    done < "${entries_file}"
    [ -n "${top}" ] || { error "Archive is empty"; return 1; }
    ARCHIVE_TOP_DIR="${top}"
}

metadata_value() {
    local file="$1" key="$2"
    sed -n "s/.*\"${key}\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" "${file}" \
        | sed -n '1p'
}

validate_staged_distribution() {
    local root="$1"
    local metadata="${root}/.dist-info.json"
    local actual_version actual_variant actual_platform actual_backend actual_classifier expected_classifier
    [ -f "${metadata}" ] || { error "Archive is missing .dist-info.json"; return 1; }
    actual_version=$(metadata_value "${metadata}" version)
    actual_variant=$(metadata_value "${metadata}" variant)
    actual_platform=$(metadata_value "${metadata}" platform)
    actual_backend=$(metadata_value "${metadata}" backendProfile)
    actual_classifier=$(metadata_value "${metadata}" distributionClassifier)
    expected_classifier="${DISTRIBUTION_CLASSIFIER}"
    [ "${actual_version}" = "${VERSION}" ] \
        || { error "Archive version mismatch: expected ${VERSION}, got ${actual_version}"; return 1; }
    [ "${actual_variant}" = "${VARIANT}" ] \
        || { error "Archive variant mismatch: expected ${VARIANT}, got ${actual_variant}"; return 1; }
    [ "${actual_platform}" = "${PLATFORM}" ] \
        || { error "Archive platform mismatch: expected ${PLATFORM}, got ${actual_platform}"; return 1; }
    [ "${actual_backend:-none}" = "${BACKEND_PROFILE:-none}" ] \
        || { error "Archive backend mismatch: expected ${BACKEND_PROFILE:-none}, got ${actual_backend:-none}"; return 1; }
    [ "${actual_classifier}" = "${expected_classifier}" ] \
        || { error "Archive classifier mismatch: expected ${expected_classifier}, got ${actual_classifier}"; return 1; }
    verify_manifest "${root}" "${root}/manifest.sha256" false
}

stage_update_archive() {
    local parent stage_dir
    parent=$(dirname "${INSTALL_DIR}")
    mkdir -p "${parent}"
    UPDATE_WORK_DIR=$(mktemp -d "${INSTALL_DIR%/}.update.XXXXXX")
    stage_dir="${UPDATE_WORK_DIR}/stage"
    mkdir -p "${stage_dir}"
    if [ "${ARCHIVE_EXT}" = "tar.gz" ]; then
        tar -xzf "${TEMP_ARCHIVE}" -C "${stage_dir}"
    else
        unzip -q "${TEMP_ARCHIVE}" -d "${stage_dir}"
    fi
    STAGED_ROOT="${stage_dir}/${ARCHIVE_TOP_DIR}"
    [ -d "${STAGED_ROOT}" ] || { error "Archive top-level directory is missing"; return 1; }
    validate_staged_distribution "${STAGED_ROOT}"
}

rollback_update_payload() {
    local installed_paths="$1" backed_paths="$2" backup_root="$3" path
    local rollback_ok=true
    if [ -f "${installed_paths}" ]; then
        while IFS= read -r path || [ -n "${path}" ]; do
            [ -n "${path}" ] || continue
            rm -rf -- "${INSTALL_DIR}/${path}" 2>/dev/null || rollback_ok=false
        done < "${installed_paths}"
    fi
    if [ -f "${backed_paths}" ]; then
        while IFS= read -r path || [ -n "${path}" ]; do
            [ -n "${path}" ] || continue
            if [ -e "${backup_root}/${path}" ] || [ -L "${backup_root}/${path}" ]; then
                mkdir -p "$(dirname "${INSTALL_DIR}/${path}")" || rollback_ok=false
                mv -- "${backup_root}/${path}" "${INSTALL_DIR}/${path}" 2>/dev/null \
                    || rollback_ok=false
            elif [ ! -e "${INSTALL_DIR}/${path}" ] && [ ! -L "${INSTALL_DIR}/${path}" ]; then
                rollback_ok=false
            fi
        done < "${backed_paths}"
    fi
    [ "${rollback_ok}" = true ]
}

promote_update_payload() {
    local old_manifest="${INSTALL_DIR}/manifest.sha256"
    local new_manifest="${STAGED_ROOT}/manifest.sha256"
    local old_paths="${UPDATE_WORK_DIR}/old-paths"
    local new_paths="${UPDATE_WORK_DIR}/new-paths"
    local installed_paths="${UPDATE_WORK_DIR}/installed-paths"
    local backed_paths="${UPDATE_WORK_DIR}/backed-paths"
    local backup_root="${UPDATE_WORK_DIR}/backup"
    local new_only_paths="${UPDATE_WORK_DIR}/new-only-paths"
    local path source target backup line is_metadata metadata_pass
    local installed_count=0 fault_after="${KOMPILE_UPDATE_TEST_FAIL_AFTER:-0}"
    : > "${old_paths}" || return 1
    : > "${backed_paths}" || return 1
    : > "${installed_paths}" || return 1
    mkdir -p "${backup_root}" || return 1

    if [ -f "${old_manifest}" ]; then
        collect_manifest_paths "${old_manifest}" "${old_paths}" true || return 1
        if ! verify_manifest "${INSTALL_DIR}" "${old_manifest}" true; then
            if [ "${FORCE}" != true ]; then
                error "Installed managed files have changed; rerun 'kompile update --force' to replace them"
                return 1
            fi
            info "Continuing despite modified managed files (--force)"
        fi
    elif [ "${FORCE}" != true ]; then
        error "The existing install has no manifest; rerun 'kompile update --force' to replace it"
        return 1
    fi
    collect_manifest_paths "${new_manifest}" "${new_paths}" true || return 1
    LC_ALL=C sort -u -o "${old_paths}" "${old_paths}" || return 1
    LC_ALL=C sort -u -o "${new_paths}" "${new_paths}" || return 1
    comm -13 "${old_paths}" "${new_paths}" > "${new_only_paths}" || return 1

    # A managed or state-seed path may not escape through a pre-existing
    # symlinked parent in the live installation.
    while IFS= read -r path || [ -n "${path}" ]; do
        [ -n "${path}" ] || continue
        validate_install_target_parent "${path}" || return 1
    done < "${old_paths}"
    while IFS= read -r line || [ -n "${line}" ]; do
        path=$(manifest_path "${line}") || return 1
        validate_install_target_parent "${path}" || return 1
    done < "${new_manifest}"

    # A newly introduced managed path must not silently replace an unrelated user file.
    while IFS= read -r path || [ -n "${path}" ]; do
        [ -n "${path}" ] || continue
        target="${INSTALL_DIR}/${path}"
        if { [ -e "${target}" ] || [ -L "${target}" ]; } \
                && [ "${FORCE}" != true ]; then
            error "New distribution path conflicts with an unmanaged file: ${path} (use --force)"
            return 1
        fi
    done < "${new_only_paths}"

    # Back up old managed files and any forced conflicts on the same filesystem.
    UPDATE_INSTALLED_PATHS="${installed_paths}"
    UPDATE_BACKED_PATHS="${backed_paths}"
    UPDATE_BACKUP_ROOT="${backup_root}"
    UPDATE_MUTATION_STARTED=true
    while IFS= read -r path || [ -n "${path}" ]; do
        [ -n "${path}" ] || continue
        target="${INSTALL_DIR}/${path}"
        backup="${backup_root}/${path}"
        if [ -e "${target}" ] || [ -L "${target}" ]; then
            mkdir -p "$(dirname "${backup}")" || return 1
            printf '%s\n' "${path}" >> "${backed_paths}" || return 1
            mv -- "${target}" "${backup}" || return 1
        fi
    done < "${old_paths}"
    while IFS= read -r path || [ -n "${path}" ]; do
        [ -n "${path}" ] || continue
        target="${INSTALL_DIR}/${path}"
        backup="${backup_root}/${path}"
        if { [ -e "${target}" ] || [ -L "${target}" ]; } \
                && [ ! -e "${backup}" ] && [ ! -L "${backup}" ]; then
            mkdir -p "$(dirname "${backup}")" || return 1
            printf '%s\n' "${path}" >> "${backed_paths}" || return 1
            mv -- "${target}" "${backup}" || return 1
        fi
    done < "${new_paths}"
    if [ -f "${old_manifest}" ]; then
        printf '%s\n' "manifest.sha256" >> "${backed_paths}" || return 1
        mv -- "${old_manifest}" "${backup_root}/manifest.sha256" || return 1
    fi

    # Move payload files into place. Metadata goes last so readers never observe
    # a new version marker before the corresponding binaries are installed.
    for metadata_pass in false true; do
        while IFS= read -r path || [ -n "${path}" ]; do
            [ -n "${path}" ] || continue
            case "${path}" in
                .version|.variant|.dist-info.json) is_metadata=true ;;
                *) is_metadata=false ;;
            esac
            [ "${is_metadata}" = "${metadata_pass}" ] || continue
            source="${STAGED_ROOT}/${path}"
            target="${INSTALL_DIR}/${path}"
            mkdir -p "$(dirname "${target}")" || return 1
            printf '%s\n' "${path}" >> "${installed_paths}" || return 1
            mv -- "${source}" "${target}" || return 1
            installed_count=$((installed_count + 1))
            if [ "${fault_after}" -gt 0 ] 2>/dev/null \
                    && [ "${installed_count}" -ge "${fault_after}" ]; then
                error "Injected update promotion failure after ${installed_count} files"
                return 1
            fi
        done < "${new_paths}"
    done

    # State-owned files are seeds, not distribution payload. Preserve existing
    # state and install a seed only when the destination is absent.
    while IFS= read -r line || [ -n "${line}" ]; do
        path=$(manifest_path "${line}") || return 1
        state_owned_path "${path}" || continue
        source="${STAGED_ROOT}/${path}"
        target="${INSTALL_DIR}/${path}"
        if [ ! -e "${target}" ] && [ ! -L "${target}" ]; then
            mkdir -p "$(dirname "${target}")" || return 1
            printf '%s\n' "${path}" >> "${installed_paths}" || return 1
            cp -a -- "${source}" "${target}" || return 1
        fi
    done < "${new_manifest}"

    # The installed manifest deliberately excludes mutable state seeds.
    local installed_manifest="${INSTALL_DIR}/.manifest.sha256.update.$$"
    : > "${installed_manifest}" || return 1
    while IFS= read -r line || [ -n "${line}" ]; do
        path=$(manifest_path "${line}") || return 1
        state_owned_path "${path}" || printf '%s\n' "${line}" >> "${installed_manifest}"
    done < "${new_manifest}"
    printf '%s\n' "manifest.sha256" >> "${installed_paths}" || return 1
    mv -- "${installed_manifest}" "${INSTALL_DIR}/manifest.sha256" || return 1

    if ! verify_manifest "${INSTALL_DIR}" "${INSTALL_DIR}/manifest.sha256" false; then
        return 1
    fi
    if [ -e "${INSTALL_DIR}/lib/.boot-inf-extracted" ]; then
        rm -rf -- "${INSTALL_DIR}/lib/.boot-inf-extracted" || return 1
    fi
}

set_update_permissions() {
    local path
    while IFS= read -r path || [ -n "${path}" ]; do
        case "${path}" in
            bin/*|runtime/bin/*)
                [ ! -f "${INSTALL_DIR}/${path}" ] \
                    || chmod +x "${INSTALL_DIR}/${path}" \
                    || { error "Could not make update payload executable: ${path}"; return 1; }
                ;;
        esac
    done < "${UPDATE_WORK_DIR}/new-paths"
}

# ── Main ─────────────────────────────────────────────────────────────────────

echo ""
echo "Kompile Installer"
echo "=================="
echo ""
info "Version:   ${VERSION}"
info "Variant:   ${VARIANT}"
info "Platform:  ${PLATFORM}"
info "Backend:   ${BACKEND_PROFILE:-default}"
info "Install:   ${INSTALL_DIR}"
info "Archive:   ${ARCHIVE_NAME}"
info "URL:       ${DOWNLOAD_URL}"

step "Creating install directory"
if [ "${UPDATE_MODE}" = true ] && [ -L "${INSTALL_DIR}" ]; then
    error "Update install root must not be a symbolic link: ${INSTALL_DIR}"
    exit 1
fi
mkdir -p "${INSTALL_DIR}"
if [ "${UPDATE_MODE}" = true ]; then
    UPDATE_LOCK_DIR="${INSTALL_DIR}/.update.lock"
    if ! mkdir "${UPDATE_LOCK_DIR}" 2>/dev/null; then
        error "Another update is active or recovery is required: ${UPDATE_LOCK_DIR}"
        exit 1
    fi
    UPDATE_LOCK_HELD=true
    printf '%s\n' "$$" > "${UPDATE_LOCK_DIR}/pid"
fi

# Download
step "Downloading ${ARCHIVE_NAME}"
TEMP_DIR="$(mktemp -d)"
TEMP_ARCHIVE="${TEMP_DIR}/${ARCHIVE_NAME}"

if command -v curl &>/dev/null; then
    if [ "${VERBOSE}" = true ]; then
        curl -fSL -o "${TEMP_ARCHIVE}" "${DOWNLOAD_URL}"
    else
        curl -fsSL -o "${TEMP_ARCHIVE}" --progress-bar "${DOWNLOAD_URL}"
    fi
elif command -v wget &>/dev/null; then
    if [ "${VERBOSE}" = true ]; then
        wget -O "${TEMP_ARCHIVE}" "${DOWNLOAD_URL}"
    else
        wget -q --show-progress -O "${TEMP_ARCHIVE}" "${DOWNLOAD_URL}"
    fi
else
    error "Neither curl nor wget found. Install one and retry."
    exit 1
fi

info "Downloaded $(du -h "${TEMP_ARCHIVE}" | cut -f1) to ${TEMP_ARCHIVE}"

# Download checksum. Updates fail closed when the sidecar or a SHA-256 tool is
# unavailable; the legacy fresh-install path retains its optional-sidecar behavior.
CHECKSUM_FILE="${TEMP_DIR}/${ARCHIVE_NAME}.sha256"
if command -v curl &>/dev/null; then
    curl -fsSL -o "${CHECKSUM_FILE}" "${CHECKSUM_URL}" 2>/dev/null || true
elif command -v wget &>/dev/null; then
    wget -q -O "${CHECKSUM_FILE}" "${CHECKSUM_URL}" 2>/dev/null || true
fi

if [ ! -s "${CHECKSUM_FILE}" ] && [ "${UPDATE_MODE}" = true ]; then
    error "A checksum is required for updates but was not found: ${CHECKSUM_URL}"
    exit 1
fi

if [ -s "${CHECKSUM_FILE}" ]; then
    step "Verifying checksum"
    EXPECTED=$(awk 'NR == 1 { print $1 }' "${CHECKSUM_FILE}")
    [[ "${EXPECTED}" =~ ^[0-9A-Fa-f]{64}$ ]] \
        || { error "Invalid checksum sidecar: ${CHECKSUM_URL}"; exit 1; }
    EXPECTED=$(printf '%s' "${EXPECTED}" | tr '[:upper:]' '[:lower:]')
    if ACTUAL=$(sha256_file "${TEMP_ARCHIVE}"); then
        if [ "${EXPECTED}" != "${ACTUAL}" ]; then
            error "Checksum mismatch! Expected ${EXPECTED}, got ${ACTUAL}"
            exit 1
        fi
        info "Checksum OK"
    elif [ "${UPDATE_MODE}" = true ]; then
        error "No sha256sum or shasum found; updates require checksum verification"
        exit 1
    else
        info "No sha256sum or shasum found, skipping checksum verification"
    fi
fi

step "Validating archive paths"
validate_archive_entries

if [ "${UPDATE_MODE}" = true ]; then
    step "Staging and validating update payload"
    stage_update_archive
    step "Promoting update payload"
    if ! promote_update_payload; then
        error "Update promotion failed; rollback will run before exit"
        exit 1
    fi
    set_update_permissions
    UPDATE_COMMITTED=true
    step "Update complete!"
    info "Updated ${VARIANT}-${RELEASE_PLATFORM} to ${VERSION} in ${INSTALL_DIR}"
    exit 0
else
    # Clean files owned by the previous distribution before every extraction.
    # This keeps fresh/reinstall behavior compatible while preserving unrelated utilities.
    OLD_VERSION=""
    PRESERVED_MCP_CONFIG=""
    if [ -f "${INSTALL_DIR}/.version" ]; then
        OLD_VERSION=$(cat "${INSTALL_DIR}/.version" 2>/dev/null || true)
    fi
    if [ -f "${INSTALL_DIR}/data/mcp-config.json" ]; then
        PRESERVED_MCP_CONFIG="${TEMP_DIR}/preserved-mcp-config.json"
        cp -p "${INSTALL_DIR}/data/mcp-config.json" "${PRESERVED_MCP_CONFIG}"
    fi

    if [ -f "${INSTALL_DIR}/manifest.sha256" ]; then
        step "Cleaning previous distribution payload"
        while IFS= read -r manifest_line; do
            managed_path="${manifest_line#*  }"
            safe_relative_path "${managed_path}" || continue
            state_owned_path "${managed_path}" && continue
            rm -f -- "${INSTALL_DIR}/${managed_path}" 2>/dev/null || true
        done < "${INSTALL_DIR}/manifest.sha256"
    elif [ -n "${OLD_VERSION}" ] && [ "${OLD_VERSION}" != "${VERSION}" ]; then
        step "Upgrading from ${OLD_VERSION} to ${VERSION}"
        info "Cleaning stale binaries from previous version..."
        rm -rf "${INSTALL_DIR}/bin" "${INSTALL_DIR}/lib" "${INSTALL_DIR}/conf"
    fi

    step "Extracting to ${INSTALL_DIR}"
    if [ "${ARCHIVE_EXT}" = "tar.gz" ]; then
        tar -xzf "${TEMP_ARCHIVE}" -C "${INSTALL_DIR}" --strip-components=1
    else
        UNZIP_TEMP="${TEMP_DIR}/unzip"
        mkdir -p "${UNZIP_TEMP}"
        unzip -o -q "${TEMP_ARCHIVE}" -d "${UNZIP_TEMP}"
        cp -a "${UNZIP_TEMP}/${ARCHIVE_TOP_DIR}/." "${INSTALL_DIR}/"
    fi
    if [ -n "${PRESERVED_MCP_CONFIG}" ]; then
        mkdir -p "${INSTALL_DIR}/data"
        cp -p "${PRESERVED_MCP_CONFIG}" "${INSTALL_DIR}/data/mcp-config.json"
    fi
    if [ -f "${INSTALL_DIR}/manifest.sha256" ]; then
        FILTERED_MANIFEST="${TEMP_DIR}/installed-manifest.sha256"
        : > "${FILTERED_MANIFEST}"
        while IFS= read -r manifest_line || [ -n "${manifest_line}" ]; do
            managed_path=$(manifest_path "${manifest_line}") || continue
            state_owned_path "${managed_path}" \
                || printf '%s\n' "${manifest_line}" >> "${FILTERED_MANIFEST}"
        done < "${INSTALL_DIR}/manifest.sha256"
        mv -- "${FILTERED_MANIFEST}" "${INSTALL_DIR}/manifest.sha256"
    fi
fi

# Known files from pre-manifest layouts. New canonical artifacts have unversioned
# names, so these legacy aliases must not linger and confuse manual tooling.
rm -f -- "${INSTALL_DIR}/bin/kompile-app-main.jar"
rm -f -- "${INSTALL_DIR}/lib/kompile-sdk-serving-"*-shaded.jar

# Set executable permissions on binaries
if [ -d "${INSTALL_DIR}/bin" ]; then
    chmod +x "${INSTALL_DIR}/bin/"* 2>/dev/null || true
fi

# Make bundled Java runtime executable and report its version.
if [ -f "${INSTALL_DIR}/runtime/bin/java" ]; then
    chmod +x "${INSTALL_DIR}/runtime/bin/java"
    # Also mark the rest of the runtime bin/ executables.
    chmod +x "${INSTALL_DIR}/runtime/bin/"* 2>/dev/null || true
    BUNDLED_JAVA_VERSION=$("${INSTALL_DIR}/runtime/bin/java" -version 2>&1 | head -1 || true)
    echo "  Bundled Java runtime: ${BUNDLED_JAVA_VERSION}"
fi

# Create standard directory structure
step "Initializing kompile home directory"
for dir in config data/input_documents/uploads data/shared_files data/prompt-templates \
           data/models/.staging data/logs data/tool-definitions data/folders \
           data/mcp-servers data/mcp-bridges data/pids anserini/indexes components; do
    mkdir -p "${INSTALL_DIR}/${dir}"
done

# Write version marker
echo "${VERSION}" > "${INSTALL_DIR}/.version"

# ── PATH setup ───────────────────────────────────────────────────────────────

step "Installation complete!"
echo ""
info "Installed to: ${INSTALL_DIR}"
if [ -d "${INSTALL_DIR}/bin" ]; then
    info "Binaries:     ${INSTALL_DIR}/bin/"
    ls "${INSTALL_DIR}/bin/" 2>/dev/null | while read -r f; do
        info "  - ${f}"
    done
fi
if [ -d "${INSTALL_DIR}/lib" ]; then
    APP_JAR_COUNT=0
    for f in "${INSTALL_DIR}/lib/"*.jar; do
        [ -f "${f}" ] || continue
        if [ "${APP_JAR_COUNT}" -eq 0 ]; then
            info "App JARs:     ${INSTALL_DIR}/lib/"
        fi
        info "  - $(basename "${f}")"
        APP_JAR_COUNT=$((APP_JAR_COUNT + 1))
    done
    if [ "${APP_JAR_COUNT}" -eq 0 ] && [ -f "${INSTALL_DIR}/lib/shared-runtime-manifest.txt" ]; then
        info "Native libs:  ${INSTALL_DIR}/lib/ (manifest validated)"
    fi
fi

echo ""

# Check if bin is on PATH
BIN_DIR="${INSTALL_DIR}/bin"

SHELL_NAME="$(basename "${SHELL:-/bin/bash}")"
case "${SHELL_NAME}" in
    zsh)   PROFILE_FILE="${HOME}/.zshrc" ;;
    fish)  PROFILE_FILE="${HOME}/.config/fish/config.fish" ;;
    *)     PROFILE_FILE="${HOME}/.bashrc" ;;
esac

if [[ ":${PATH}:" != *":${BIN_DIR}:"* ]]; then
    echo "Add kompile to your PATH by adding this to your shell profile:"
    echo ""

    if [ "${SHELL_NAME}" = "fish" ]; then
        PATH_LINE="set -gx PATH ${BIN_DIR} \$PATH"
        echo "  echo '${PATH_LINE}' >> ${PROFILE_FILE}"
    else
        PATH_LINE="export PATH=\"${BIN_DIR}:\$PATH\""
        echo "  echo '${PATH_LINE}' >> ${PROFILE_FILE}"
    fi
    echo ""
    echo "Then reload your shell:"
    echo "  source ${PROFILE_FILE}"
    echo ""

    # --modify-path / KOMPILE_MODIFY_PATH=1: append idempotently.
    if [ "${MODIFY_PATH}" = "1" ]; then
        if [ "${SHELL_NAME}" = "fish" ]; then
            if ! grep -qF "${BIN_DIR}" "${PROFILE_FILE}" 2>/dev/null; then
                echo "  # Added by kompile installer" >> "${PROFILE_FILE}"
                echo "set -gx PATH ${BIN_DIR} \$PATH" >> "${PROFILE_FILE}"
                echo "  PATH export appended to ${PROFILE_FILE}"
            else
                echo "  PATH already present in ${PROFILE_FILE} — not duplicated"
            fi
        else
            if ! grep -qF "${BIN_DIR}" "${PROFILE_FILE}" 2>/dev/null; then
                echo "" >> "${PROFILE_FILE}"
                echo "# Added by kompile installer" >> "${PROFILE_FILE}"
                echo "export PATH=\"${BIN_DIR}:\$PATH\"" >> "${PROFILE_FILE}"
                echo "  PATH export appended to ${PROFILE_FILE}"
            else
                echo "  PATH already present in ${PROFILE_FILE} — not duplicated"
            fi
        fi
    fi
fi

echo "Get started:"
echo "  kompile project init                  # scaffold a Kompile project in the current directory"
echo "  kompile project init --crawl --push   # scaffold + serve + index docs + push to git (one shot)"
echo "  kompile chat                          # start an AI chat"
if [ "${VARIANT}" != "local" ] && [ "${VARIANT}" != "cli-only" ]; then
    echo "  kompile web                           # launch the web UI"
fi
echo "  kompile --help                        # see all commands"
echo ""
if [ "${VARIANT}" = "cli-only" ]; then
    if [ -n "${BACKEND_PROFILE}" ]; then
        echo "Note: installed the backend-qualified cli-only variant. It includes JAR-based"
        echo "model and pipeline workers for ${BACKEND_PROFILE}, but no web/application server."
        echo "A compatible Java runtime and system accelerator drivers are required."
    else
        echo "Note: installed the plain cli-only variant. Folder-local model execution requires"
        echo "a backend-qualified cli-only archive, or the local/full distribution."
    fi
    echo ""
elif [ "${VARIANT}" = "local" ]; then
    echo "Note: installed the native local-execution variant. It includes project-local"
    echo "model and pipeline workers, but intentionally excludes the web/application server."
    echo ""
fi

# ── Post-install diagnostics ──────────────────────────────────────────────────
# Run 'kompile doctor' so users see an immediate health summary.  Failures do
# NOT abort the installer — the exit is always clean even if probes warn.
KOMPILE_BIN="${INSTALL_DIR}/bin/kompile"
if [ -x "${KOMPILE_BIN}" ]; then
    echo ""
    echo "── Running post-install diagnostics ──────────────────────────────────"
    # Forward the install dir so doctor's component lookups check THIS install,
    # not the ~/.kompile default (matters for --dir / custom KOMPILE_INSTALL_DIR).
    KOMPILE_INSTALL_DIR="${INSTALL_DIR}" "${KOMPILE_BIN}" doctor --no-color || true
fi
