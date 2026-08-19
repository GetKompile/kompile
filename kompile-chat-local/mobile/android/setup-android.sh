#!/usr/bin/env bash
# setup-android.sh — idempotent Android toolchain bootstrap for kompile-chat-local
# Installs under ~/dev-apps (the box's dev-tools home) as per project convention.
# Mirrors the dl4j android workflow approach: manual cmdline-tools download + sdkmanager.
#
# Usage: bash setup-android.sh
# Re-run safely: each block is skip-if-present.

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_HOME="${HOME}/dev-apps/android-sdk"
SETUP_TMP_ROOT="${ANDROID_SETUP_TMPDIR:-${SCRIPT_DIR}/build/setup-android-tmp}"
mkdir -p -- "${SETUP_TMP_ROOT}"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
CMDLINE_TOOLS_DIR="${ANDROID_HOME}/cmdline-tools/latest"

# ── JDK: use GraalVM 17 from sdkman (matches dl4j convention: explicit JAVA_HOME) ──
export JAVA_HOME="${HOME}/.sdkman/candidates/java/17.0.12-graal"
export PATH="${JAVA_HOME}/bin:${PATH}"

echo "=== Using JAVA_HOME: ${JAVA_HOME} ==="
java -version

# ── 1. cmdline-tools ──────────────────────────────────────────────────────────
if [ ! -f "${CMDLINE_TOOLS_DIR}/bin/sdkmanager" ]; then
    echo "=== Installing Android cmdline-tools ==="
    mkdir -p "${ANDROID_HOME}/cmdline-tools"
    TMP_ZIP=$(mktemp "${SETUP_TMP_ROOT}/cmdline-tools-XXXXXX.zip")
    EXTRACT_DIR=$(mktemp -d "${SETUP_TMP_ROOT}/cmdline-tools-extracted.XXXXXX")
    curl -L -o "${TMP_ZIP}" "${CMDLINE_TOOLS_URL}"
    unzip -q "${TMP_ZIP}" -d "${EXTRACT_DIR}"
    mv "${EXTRACT_DIR}/cmdline-tools" "${CMDLINE_TOOLS_DIR}"
    rm -f "${TMP_ZIP}"
    rm -rf "${EXTRACT_DIR}"
    echo "cmdline-tools installed to ${CMDLINE_TOOLS_DIR}"
else
    echo "cmdline-tools already present, skipping."
fi

export PATH="${CMDLINE_TOOLS_DIR}/bin:${ANDROID_HOME}/platform-tools:${ANDROID_HOME}/emulator:${PATH}"
export ANDROID_SDK_ROOT="${ANDROID_HOME}"

# ── 2. Accept licenses ────────────────────────────────────────────────────────
echo "=== Accepting SDK licenses ==="
yes | sdkmanager --licenses 2>&1 | grep -E "(Accept|All SDK)" || true

# ── 3. Platform tools, platform-35, build-tools-35 ───────────────────────────
PKGS_TO_INSTALL=()
[ ! -d "${ANDROID_HOME}/platform-tools" ] && PKGS_TO_INSTALL+=("platform-tools")
[ ! -d "${ANDROID_HOME}/platforms/android-35" ] && PKGS_TO_INSTALL+=("platforms;android-35")
[ ! -d "${ANDROID_HOME}/build-tools/35.0.0" ] && PKGS_TO_INSTALL+=("build-tools;35.0.0")
[ ! -d "${ANDROID_HOME}/emulator" ] && PKGS_TO_INSTALL+=("emulator")

if [ ${#PKGS_TO_INSTALL[@]} -gt 0 ]; then
    echo "=== Installing: ${PKGS_TO_INSTALL[*]} ==="
    sdkmanager "${PKGS_TO_INSTALL[@]}"
else
    echo "platform-tools/platform/build-tools/emulator already present, skipping."
fi

# ── 4. System image (android-35 google_apis x86_64) ──────────────────────────
SYSIMG="system-images;android-35;google_apis;x86_64"
if [ ! -d "${ANDROID_HOME}/system-images/android-35/google_apis/x86_64" ]; then
    echo "=== Installing system image: ${SYSIMG} ==="
    sdkmanager "${SYSIMG}"
else
    echo "System image already present, skipping."
fi

# ── 5. AVD ────────────────────────────────────────────────────────────────────
AVD_NAME="kompile_test_35"
if ! avdmanager list avd 2>/dev/null | grep -q "${AVD_NAME}"; then
    echo "=== Creating AVD: ${AVD_NAME} ==="
    avdmanager create avd -n "${AVD_NAME}" -k "${SYSIMG}" --device "pixel_4" --force
else
    echo "AVD ${AVD_NAME} already exists, skipping."
fi

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo "=== Android toolchain ready ==="
echo "ANDROID_HOME=${ANDROID_HOME}"
echo "JAVA_HOME=${JAVA_HOME}"
echo ""
echo "To use in your shell, add to ~/.bashrc or source this file's exports:"
echo "  export ANDROID_HOME=${ANDROID_HOME}"
echo "  export JAVA_HOME=${JAVA_HOME}"
echo "  export PATH=\${ANDROID_HOME}/cmdline-tools/latest/bin:\${ANDROID_HOME}/platform-tools:\${ANDROID_HOME}/emulator:\${JAVA_HOME}/bin:\${PATH}"
echo ""
echo "Emulator AVD: ${AVD_NAME}"
echo "Start with: emulator -avd ${AVD_NAME} -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 2048 -cores 2"
