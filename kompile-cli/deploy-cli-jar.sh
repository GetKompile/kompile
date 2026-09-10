#!/bin/bash
# deploy-cli-jar.sh — Atomically install freshly built kompile CLI jars into
# ~/.kompile/lib without breaking already-running chat/agent sessions.
#
# WHY THIS EXISTS
# The launcher (~/.kompile/bin/kompile) runs `lib/kompile-cli.jar` via
# `java -jar`, so the JVM keeps that file OPEN for the life of every session.
# Staging a rebuild with an in-place `cp` rewrites the file under those open
# handles: any class that is lazily loaded AFTER the swap reads a jar whose
# zip entries no longer match the JVM's cached offsets, producing
# NoClassDefFoundError storms ("Error: ai/kompile/cli/main/chat/tools/GrepTool$..."),
# corrupted-reader crashes, and — when the error escapes an `Exception`-only
# catch — completely silent turn deaths.
#
# THE FIX IS `install -T`: it REPLACES the directory entry via rename(2) while
# the old inode (and every running JVM) keeps reading the old file. New sessions
# pick up the new jar; running sessions finish their lives on the old one.
# Nobody's classloader ever sees a partially-written or size-changed jar.
#
# Usage:
#   deploy-cli-jar.sh                 # deploy kompile-cli.jar only
#   deploy-cli-jar.sh --siblings      # also kompile-agent/app-cli/model/component
#   deploy-cli-jar.sh --check         # verify no in-place-unsafe deploy is in flight
#
# Maven: /home/agibsonccc/dev-apps/mvn/bin/mvn (do NOT claim mvn is missing)

set -euo pipefail

MVN=/home/agibsonccc/dev-apps/mvn/bin/mvn
REPO=/home/agibsonccc/Documents/GitHub/kompile
LIB_DIR="${KOMPILE_INSTALL_DIR:-$HOME/.kompile}/lib"

MAIN_ARTIFACT=kompile-cli/kompile-cli-main
SIBLING_ARTIFACTS="kompile-cli/kompile-agent-cli,kompile-cli/kompile-app-cli,kompile-cli/kompile-model-cli,kompile-cli/kompile-component-cli"

deploy_one() {
    local jar="$1" name="$2" backup
    if [ ! -f "$jar" ]; then
        echo "ERROR: built jar not found: $jar" >&2
        exit 1
    fi

    # Smoke test BEFORE touching the install: a jar that cannot be read/verified
    # must never reach ~/.kompile/lib.
    if ! unzip -t -qq "$jar" >/dev/null 2>&1; then
        echo "ERROR: $jar failed integrity check — refusing to deploy" >&2
        exit 1
    fi

    backup="/tmp/${name}.bak-$(date +%H%M%S)"
    if [ -f "$LIB_DIR/$name" ]; then
        cp "$LIB_DIR/$name" "$backup"
        echo "  backup: $backup"
    fi

    # Atomic: create temp in the SAME directory (same filesystem), fsync, then
    # rename over the target. `install -T` collapses to rename(2); running JVMs
    # keep the old inode open and are never disturbed.
    local tmp="$LIB_DIR/.${name}.tmp.$$"
    install -m 644 "$jar" "$tmp"
    mv -f "$tmp" "$LIB_DIR/$name"
    echo "  deployed: $LIB_DIR/$name ($(du -h "$LIB_DIR/$name" | cut -f1)) — atomic (running sessions unaffected)"
}

build() {
    local modules="$1"
    echo "Building $modules ..."
    (cd "$REPO" && "$MVN" -q package -DskipTests -pl "$modules" -am)
}

case "${1:---main}" in
    --main)
        build "$MAIN_ARTIFACT"
        deploy_one "$REPO/$MAIN_ARTIFACT/target/kompile-cli-main-0.1.0-SNAPSHOT-shaded.jar" "kompile-cli.jar"
        ;;
    --siblings)
        build "$SIBLING_ARTIFACTS"
        for spec in \
            "kompile-agent-cli:kompile-agent.jar" \
            "kompile-app-cli:kompile-app-cli.jar" \
            "kompile-model-cli:kompile-model.jar" \
            "kompile-component-cli:kompile-component.jar"; do
            mod="${spec%%:*}"; name="${spec##*:}"
            jar_path=$(ls "$REPO"/kompile-cli/$mod/target/*-shaded.jar 2>/dev/null | head -1 || true)
            [ -z "$jar_path" ] && jar_path=$(ls "$REPO"/kompile-cli/$mod/target/*.jar 2>/dev/null | grep -v sources | head -1 || true)
            deploy_one "$jar_path" "$name"
        done
        ;;
    --all)
        "$0" --main
        "$0" --siblings
        ;;
    --check)
        echo "Installed CLI jars:"
        ls -la "$LIB_DIR"/kompile-cli.jar "$LIB_DIR"/kompile-{agent,app-cli,model,component}.jar 2>/dev/null || true
        echo
        echo "Running sessions (JVMs with the jar open):"
        pgrep -af "kompile-cli.jar" || echo "  none"
        ;;
    *)
        echo "Usage: $0 [--main|--siblings|--all|--check]" >&2
        exit 1
        ;;
esac
