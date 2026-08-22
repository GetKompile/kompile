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
#
# Environment variables:
#   KOMPILE_INSTALL_DIR   Install directory (default: ~/.kompile)
#   KOMPILE_BASE_URL      Base URL for downloads (default: GitHub Releases)
#   KOMPILE_VERSION       Version to install (default: latest)
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
GITHUB_REPO="GetKompile/kompile"
VERBOSE=false
MODIFY_PATH="${KOMPILE_MODIFY_PATH:-0}"

# ── Argument parsing ─────────────────────────────────────────────────────────

while [ $# -gt 0 ]; do
    case "$1" in
        --version|-v)   VERSION="$2"; shift 2 ;;
        --variant)      VARIANT="$2"; shift 2 ;;
        --backend-profile) BACKEND_PROFILE="$2"; shift 2 ;;
        --dir|-d)       INSTALL_DIR="$2"; shift 2 ;;
        --url|-u)       BASE_URL="$2"; shift 2 ;;
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
            echo "                          Examples: cpu, cpu-avx2, cuda-12.9, cuda-12.6, zluda"
            echo "  --dir, -d DIR           Install directory (default: ~/.kompile)"
            echo "  --url, -u URL           Base URL for distribution archives"
            echo "  --modify-path           Append the PATH export line to your shell profile idempotently"
            echo "                          (same effect as setting KOMPILE_MODIFY_PATH=1)"
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
        *) echo "${PLATFORM}-${BACKEND_PROFILE}" ;;
    esac
}

RELEASE_PLATFORM="$(release_platform)"

# ── Version resolution ───────────────────────────────────────────────────────

resolve_version() {
    if [ -n "${VERSION}" ]; then
        echo "${VERSION}"
        return
    fi

    # Fetch latest release tag from GitHub API
    local latest
    if command -v curl &>/dev/null; then
        latest=$(curl -fsSL "https://api.github.com/repos/${GITHUB_REPO}/releases/latest" \
            2>/dev/null | grep '"tag_name"' | head -1 | sed 's/.*"v\([^"]*\)".*/\1/')
    elif command -v wget &>/dev/null; then
        latest=$(wget -qO- "https://api.github.com/repos/${GITHUB_REPO}/releases/latest" \
            2>/dev/null | grep '"tag_name"' | head -1 | sed 's/.*"v\([^"]*\)".*/\1/')
    fi

    if [ -z "${latest}" ]; then
        echo "Could not determine latest version. Specify with --version." >&2
        exit 1
    fi

    echo "${latest}"
}

VERSION="$(resolve_version)"

# ── Download URL ─────────────────────────────────────────────────────────────

# Check whether a URL is reachable (HEAD request).
url_exists() {
    local url="$1"
    if command -v curl &>/dev/null; then
        curl -fsS --head -o /dev/null "${url}" 2>/dev/null
    elif command -v wget &>/dev/null; then
        wget -q --spider "${url}" 2>/dev/null
    else
        return 1
    fi
}

resolve_download_url() {
    local variant="$1"
    local name="kompile-dist-${VERSION}-${variant}-${RELEASE_PLATFORM}.${ARCHIVE_EXT}"
    if [ -n "${BASE_URL}" ]; then
        echo "${BASE_URL%/}/${name}"
    else
        echo "https://github.com/${GITHUB_REPO}/releases/download/v${VERSION}/${name}"
    fi
}

if [ -z "${VARIANT}" ]; then
    # Auto: try full variant first, fall back to cli-only.
    FULL_URL="$(resolve_download_url full)"
    if url_exists "${FULL_URL}"; then
        VARIANT="full"
    else
        echo "  (full distribution not published for ${PLATFORM} — installing cli-only; server components unavailable)"
        VARIANT="cli-only"
    fi
fi

ARCHIVE_NAME="kompile-dist-${VERSION}-${VARIANT}-${RELEASE_PLATFORM}.${ARCHIVE_EXT}"
DOWNLOAD_URL="$(resolve_download_url "${VARIANT}")"

# ── Progress display ─────────────────────────────────────────────────────────

info()  { echo "  $*"; }
step()  { echo ""; echo "==> $*"; }
error() { echo "ERROR: $*" >&2; }

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
mkdir -p "${INSTALL_DIR}"

# Download
step "Downloading ${ARCHIVE_NAME}"
TEMP_DIR="$(mktemp -d)"
TEMP_ARCHIVE="${TEMP_DIR}/${ARCHIVE_NAME}"

trap 'rm -rf "${TEMP_DIR}"' EXIT

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

# Download checksum if available
CHECKSUM_URL="${DOWNLOAD_URL}.sha256"
CHECKSUM_FILE="${TEMP_DIR}/${ARCHIVE_NAME}.sha256"
if command -v curl &>/dev/null; then
    curl -fsSL -o "${CHECKSUM_FILE}" "${CHECKSUM_URL}" 2>/dev/null || true
elif command -v wget &>/dev/null; then
    wget -q -O "${CHECKSUM_FILE}" "${CHECKSUM_URL}" 2>/dev/null || true
fi

if [ -f "${CHECKSUM_FILE}" ] && [ -s "${CHECKSUM_FILE}" ]; then
    step "Verifying checksum"
    if command -v sha256sum &>/dev/null; then
        (cd "${TEMP_DIR}" && sha256sum -c "${CHECKSUM_FILE}")
    elif command -v shasum &>/dev/null; then
        EXPECTED=$(awk '{print $1}' "${CHECKSUM_FILE}")
        ACTUAL=$(shasum -a 256 "${TEMP_ARCHIVE}" | awk '{print $1}')
        if [ "${EXPECTED}" = "${ACTUAL}" ]; then
            info "Checksum OK"
        else
            error "Checksum mismatch! Expected ${EXPECTED}, got ${ACTUAL}"
            exit 1
        fi
    else
        info "No sha256sum or shasum found, skipping checksum verification"
    fi
fi

# Clean files owned by the previous distribution before every extraction.
# This makes same-version reinstalls deterministic while preserving user state and
# unrelated utilities (for example xet/git-xet) that may share INSTALL_DIR/bin.
OLD_VERSION=""
if [ -f "${INSTALL_DIR}/.version" ]; then
    OLD_VERSION=$(cat "${INSTALL_DIR}/.version" 2>/dev/null || true)
fi

if [ -f "${INSTALL_DIR}/manifest.sha256" ]; then
    step "Cleaning previous distribution payload"
    while IFS= read -r manifest_line; do
        managed_path="${manifest_line#*  }"
        case "${managed_path}" in
            ""|/*|../*|*/../*|*/..) continue ;;
        esac
        rm -f -- "${INSTALL_DIR}/${managed_path}" 2>/dev/null || true
    done < "${INSTALL_DIR}/manifest.sha256"
elif [ -n "${OLD_VERSION}" ] && [ "${OLD_VERSION}" != "${VERSION}" ]; then
    # Compatibility with old installations created before manifest.sha256 existed.
    step "Upgrading from ${OLD_VERSION} to ${VERSION}"
    info "Cleaning stale binaries from previous version..."
    rm -rf "${INSTALL_DIR}/bin" "${INSTALL_DIR}/lib" "${INSTALL_DIR}/conf"
fi

# Known files from pre-manifest layouts. New canonical artifacts have unversioned
# names, so these legacy aliases must not linger and confuse manual tooling.
rm -f -- "${INSTALL_DIR}/bin/kompile-app-main.jar"
rm -f -- "${INSTALL_DIR}/lib/kompile-sdk-serving-"*-shaded.jar

# Extract
step "Extracting to ${INSTALL_DIR}"
if [[ "${ARCHIVE_EXT}" == "tar.gz" ]]; then
    tar -xzf "${TEMP_ARCHIVE}" -C "${INSTALL_DIR}" --strip-components=1
elif [[ "${ARCHIVE_EXT}" == "zip" ]]; then
    if ! command -v unzip &>/dev/null; then
        error "unzip is required but not found."
        error "Install it via: pacman -S unzip (MSYS2), apt install unzip (Debian), or your package manager."
        exit 1
    fi
    # Extract to temp, then move contents up one level to strip the top-level prefix
    UNZIP_TEMP="$(mktemp -d)"
    unzip -o -q "${TEMP_ARCHIVE}" -d "${UNZIP_TEMP}"
    # Discover the top-level directory name dynamically (usually "kompile")
    TOP_DIR=$(ls -1 "${UNZIP_TEMP}" | head -1)
    if [ -z "${TOP_DIR}" ] || [ ! -d "${UNZIP_TEMP}/${TOP_DIR}" ]; then
        error "Unexpected archive structure — no top-level directory found"
        exit 1
    fi
    cp -r "${UNZIP_TEMP}/${TOP_DIR}/." "${INSTALL_DIR}/"
    rm -rf "${UNZIP_TEMP}"
fi

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
