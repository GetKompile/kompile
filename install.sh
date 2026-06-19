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
VARIANT="${KOMPILE_VARIANT:-hosted}"
GITHUB_REPO="GetKompile/kompile"
VERBOSE=false

# ── Argument parsing ─────────────────────────────────────────────────────────

while [ $# -gt 0 ]; do
    case "$1" in
        --version|-v)   VERSION="$2"; shift 2 ;;
        --variant)      VARIANT="$2"; shift 2 ;;
        --dir|-d)       INSTALL_DIR="$2"; shift 2 ;;
        --url|-u)       BASE_URL="$2"; shift 2 ;;
        --verbose)      VERBOSE=true; shift ;;
        --help|-h)
            echo "Usage: install.sh [OPTIONS]"
            echo ""
            echo "Options:"
            echo "  --version, -v VERSION   Version to install (default: latest)"
            echo "  --variant VARIANT       Distribution variant (default: hosted)"
            echo "                          Options: cli-only, hosted, cpu-intel, cpu-arm, cuda, amd-zluda"
            echo "  --dir, -d DIR           Install directory (default: ~/.kompile)"
            echo "  --url, -u URL           Base URL for distribution archives"
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

if [ -n "${BASE_URL}" ]; then
    # Custom base URL: expect <base>/<filename>
    ARCHIVE_NAME="kompile-dist-${VERSION}-${VARIANT}-${PLATFORM}.${ARCHIVE_EXT}"
    DOWNLOAD_URL="${BASE_URL%/}/${ARCHIVE_NAME}"
else
    # GitHub Releases
    ARCHIVE_NAME="kompile-dist-${VERSION}-${VARIANT}-${PLATFORM}.${ARCHIVE_EXT}"
    DOWNLOAD_URL="https://github.com/${GITHUB_REPO}/releases/download/v${VERSION}/${ARCHIVE_NAME}"
fi

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
info "Install:   ${INSTALL_DIR}"
info "Archive:   ${ARCHIVE_NAME}"

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

# Clean stale files from previous install (bin/, lib/, conf/ only — data/ is preserved)
if [ -f "${INSTALL_DIR}/.version" ]; then
    OLD_VERSION=$(cat "${INSTALL_DIR}/.version" 2>/dev/null || true)
    if [ -n "${OLD_VERSION}" ] && [ "${OLD_VERSION}" != "${VERSION}" ]; then
        step "Upgrading from ${OLD_VERSION} to ${VERSION}"
        info "Cleaning stale binaries from previous version..."
        rm -rf "${INSTALL_DIR}/bin" "${INSTALL_DIR}/lib" "${INSTALL_DIR}/conf"
    fi
fi

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
    info "App JARs:     ${INSTALL_DIR}/lib/"
    ls "${INSTALL_DIR}/lib/"*.jar 2>/dev/null | while read -r f; do
        info "  - $(basename "${f}")"
    done
fi

echo ""

# Check if bin is on PATH
BIN_DIR="${INSTALL_DIR}/bin"
if [[ ":${PATH}:" != *":${BIN_DIR}:"* ]]; then
    echo "Add kompile to your PATH by adding this to your shell profile:"
    echo ""

    SHELL_NAME="$(basename "${SHELL:-/bin/bash}")"
    case "${SHELL_NAME}" in
        zsh)   PROFILE_FILE="~/.zshrc" ;;
        fish)  PROFILE_FILE="~/.config/fish/config.fish" ;;
        *)     PROFILE_FILE="~/.bashrc" ;;
    esac

    if [ "${SHELL_NAME}" = "fish" ]; then
        echo "  echo 'set -gx PATH ${BIN_DIR} \$PATH' >> ${PROFILE_FILE}"
    else
        echo "  echo 'export PATH=\"${BIN_DIR}:\$PATH\"' >> ${PROFILE_FILE}"
    fi
    echo ""
    echo "Then reload your shell:"
    echo "  source ${PROFILE_FILE}"
    echo ""
fi

echo "Get started:"
echo "  kompile project init                  # scaffold a Kompile project in the current directory"
echo "  kompile project init --crawl --push   # scaffold + serve + index docs + push to git (one shot)"
echo "  kompile chat                          # start an AI chat"
echo "  kompile web                           # launch the web UI"
echo "  kompile --help                        # see all commands"
echo ""
