#!/usr/bin/env bash
# Entry point for kompile spin images. Resolves the launch command for
# KOMPILE_PRODUCT from whatever the packaged dist provides: a native binary
# first, else an exec jar on the bundled jlink runtime (falls back to any
# java on PATH). Extra args pass through.
set -euo pipefail
home="${KOMPILE_HOME:-/opt/kompile}"
product="${KOMPILE_PRODUCT:?KOMPILE_PRODUCT is required (cli|app-main|model-staging)}"

java_bin="java"
[ -x "$home/runtime/bin/java" ] && java_bin="$home/runtime/bin/java"

run_jar() { # run_jar GLOB
  local jar
  jar="$(find "$home" -maxdepth 3 -name "$1" -type f 2>/dev/null | head -n 1)"
  [ -n "$jar" ] || return 1
  exec "$java_bin" ${JAVA_OPTS:-} -jar "$jar" "$@"
}

case "$product" in
  cli)
    [ -x "$home/bin/kompile" ] && exec "$home/bin/kompile" "$@"
    run_jar 'kompile-cli-main-*-shaded.jar' "$@" || true ;;
  app-main)
    [ -x "$home/bin/kompile-server" ] && exec "$home/bin/kompile-server" "$@"
    run_jar 'kompile-app-main-*-exec.jar' "$@" || true ;;
  model-staging)
    [ -x "$home/bin/kompile-model-staging" ] && exec "$home/bin/kompile-model-staging" "$@"
    run_jar 'kompile-model-staging-*-exec.jar' "$@" || true ;;
  *) echo "Unknown KOMPILE_PRODUCT: $product" >&2; exit 2 ;;
esac
echo "No runnable artifact for '$product' in this dist (looked for native bin and exec jar under $home)" >&2
exit 2
