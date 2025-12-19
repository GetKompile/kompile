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

set -e

usage='usage: files.bash [<options>] [--]

Options:

    --version <ver>            CMake <major>.<minor> version number to push.
                               Defaults to version of source tree.
'

die() {
    echo "$@" 1>&2; exit 1
}

readonly cmake_source_dir="${BASH_SOURCE%/*}/../.."

cmake_version_component()
{
  sed -n "
/^set(CMake_VERSION_${1}/ {s/set(CMake_VERSION_${1} *\([0-9]*\))/\1/;p;}
" "${cmake_source_dir}/Source/CMakeVersion.cmake"
}


version=''
while test "$#" != 0; do
    case "$1" in
    --version) shift; version="$1" ;;
    --) shift ; break ;;
    -*) die "$usage" ;;
    *) break ;;
    esac
    shift
done
test "$#" = 0 || die "$usage"

if test -z "$version"; then
    cmake_version_major="$(cmake_version_component MAJOR)"
    cmake_version_minor="$(cmake_version_component MINOR)"
    cmake_version_patch="$(cmake_version_component PATCH)"
    cmake_version_rc="$(cmake_version_component RC)"
    version="${cmake_version_major}.${cmake_version_minor}.${cmake_version_patch}"
    if test -n "$cmake_version_rc"; then
      version="$version-rc$cmake_version_rc"
    fi
fi
readonly version

IFS='.-' read version_major version_minor version_patch version_suffix <<< "$version"
readonly version_major
readonly version_minor
readonly version_patch
readonly version_suffix

if test -n "$version_suffix"; then
  maybe_version_suffix='"suffix": "'"$version_suffix"'",'
else
  maybe_version_suffix=''
fi
readonly maybe_version_suffix

readonly files_v1_in="${BASH_SOURCE%/*}/files-v1.json.in"
sed "
  s/@version@/$version/g
  s/@version_major@/$version_major/g
  s/@version_minor@/$version_minor/g
  s/@version_patch@/$version_patch/g
  s/@maybe_version_suffix@/$maybe_version_suffix/g
" "$files_v1_in" \
  | jq . \
  > "cmake-$version-files-v1.json"

readonly algos='
  256
'
for algo in $algos; do
  shasum -a $algo \
    "cmake-$version-files-v1.json" \
    $(jq -r '.files[].name' "cmake-$version-files-v1.json") \
  | LC_ALL=C sort -k 2 \
  > "cmake-$version-SHA-$algo.txt"
done
