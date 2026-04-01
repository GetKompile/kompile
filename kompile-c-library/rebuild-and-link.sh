#!/bin/bash
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

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NATIVE_LIB_DIR="${SCRIPT_DIR}/../kompile-pipelines-framework/kompile-pipelines-framework-runtime/target"

# Copy GraalVM-generated headers and shared library
if [ -d "${NATIVE_LIB_DIR}" ]; then
    echo "Copying native image headers and library from ${NATIVE_LIB_DIR}"
    cp -f "${NATIVE_LIB_DIR}"/*.h "${SCRIPT_DIR}/include/" 2>/dev/null || true
    mkdir -p "${SCRIPT_DIR}/lib"
    cp -f "${NATIVE_LIB_DIR}"/libkompile_pipelines*.so "${SCRIPT_DIR}/lib/" 2>/dev/null || true
else
    echo "Warning: Native library directory not found at ${NATIVE_LIB_DIR}"
    echo "Build the native shared library first:"
    echo "  cd ../kompile-pipelines-framework/kompile-pipelines-framework-runtime"
    echo "  mvn clean package -Pnative-library -DskipTests"
fi

# Build the C wrapper library
cd "${SCRIPT_DIR}"
cmake .
make

# Copy to Python lib directory
PYTHON_LIB_DIR="${SCRIPT_DIR}/../kompile-python/lib"
mkdir -p "${PYTHON_LIB_DIR}"
cp libkompile_c_library.so "${PYTHON_LIB_DIR}/"
echo "Copied libkompile_c_library.so to ${PYTHON_LIB_DIR}/"

# Also copy the native shared library for Python to load
if [ -f "${SCRIPT_DIR}/lib/libkompile_pipelines.so" ]; then
    cp "${SCRIPT_DIR}/lib/libkompile_pipelines.so" "${PYTHON_LIB_DIR}/"
    echo "Copied libkompile_pipelines.so to ${PYTHON_LIB_DIR}/"
fi
