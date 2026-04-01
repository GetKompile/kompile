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
set -x
shopt -s dotglob

readonly name="libuv"
readonly ownership="libuv upstream <libuv@googlegroups.com>"
readonly subtree="Utilities/cmlibuv"
readonly repo="https://github.com/libuv/libuv.git"
readonly tag="v1.44.2"
readonly shortlog=false
readonly paths="
  LICENSE
  include
  src
"

extract_source () {
    git_archive
    pushd "${extractdir}/${name}-reduced"
    echo "* -whitespace" > .gitattributes
    echo >> src/unix/aix-common.c
    echo >> src/unix/ibmi.c
    popd
}

. "${BASH_SOURCE%/*}/update-third-party.bash"
