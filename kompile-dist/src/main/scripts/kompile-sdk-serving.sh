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

JAVA_BIN="${JAVA_HOME:+${JAVA_HOME}/bin/}java"

# Prepend the dist lib dir to the native library search path so the Vert.x
# server can load libkompile_pipelines and libkompile_c_library if needed.
export LD_LIBRARY_PATH="${DIST_HOME}/lib${LD_LIBRARY_PATH:+:${LD_LIBRARY_PATH}}"

exec "${JAVA_BIN}" -Djava.library.path="${DIST_HOME}/lib" -jar "${JAR}" "$@"
