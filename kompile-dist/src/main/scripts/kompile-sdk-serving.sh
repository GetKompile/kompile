#!/usr/bin/env bash
#
#   Copyright 2025 Kompile Inc.
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#  http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
# limitations under the License.
#
# Launcher for kompile-sdk-serving (Vert.x OpenAI-compatible inference server).
# The server ships as a JVM shaded JAR (not a native image), so this wrapper
# locates the JAR relative to the distribution's bin/ directory and execs java.

set -e

SCRIPT_DIR="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}" 2>/dev/null || echo "${BASH_SOURCE[0]}")")" && pwd)"
DIST_HOME="$(cd "${SCRIPT_DIR}/.." && pwd)"
JAR="${DIST_HOME}/lib/kompile-sdk-serving.jar"

if [ ! -f "${JAR}" ]; then
    echo "error: ${JAR} not found" >&2
    exit 1
fi

# Java resolution order:
#   $KOMPILE_JAVA → <dist>/runtime/bin/java → $JAVA_HOME/bin/java → java on PATH
if [ -x "${KOMPILE_JAVA:-}" ]; then
    JAVA_BIN="${KOMPILE_JAVA}"
elif [ -x "${DIST_HOME}/runtime/bin/java" ]; then
    JAVA_BIN="${DIST_HOME}/runtime/bin/java"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
    JAVA_BIN="${JAVA_HOME}/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVA_BIN="java"
else
    echo "error: no Java runtime found. Install Java 21+, set JAVA_HOME, or rebuild the dist" >&2
    echo "       with a bundled runtime (see build-dist.sh --variant <variant>)." >&2
    exit 1
fi

# Prepend the dist lib dir to the native library search path so the Vert.x
# server can load libkompile_pipelines and libkompile_c_library if needed.
export LD_LIBRARY_PATH="${DIST_HOME}/lib${LD_LIBRARY_PATH:+:${LD_LIBRARY_PATH}}"

exec "${JAVA_BIN}" -Djava.library.path="${DIST_HOME}/lib" -jar "${JAR}" "$@"
