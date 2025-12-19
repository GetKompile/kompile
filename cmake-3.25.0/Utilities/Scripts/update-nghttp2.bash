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

readonly name="nghttp2"
readonly ownership="nghttp2 upstream <kwrobot@kitware.com>"
readonly subtree="Utilities/cmnghttp2"
readonly repo="https://github.com/nghttp2/nghttp2.git"
readonly tag="v1.40.0"
readonly shortlog=false
readonly paths="
  COPYING
  lib/*.c
  lib/*.h
  lib/includes/nghttp2/nghttp2.h
  lib/includes/nghttp2/nghttp2ver.h.in
"

extract_source () {
    git_archive
    pushd "${extractdir}/${name}-reduced"
    echo "* -whitespace" > .gitattributes
    mv lib/includes/nghttp2/nghttp2ver.h.in lib/includes/nghttp2/nghttp2ver.h
    sed -i 's/@PACKAGE_VERSION@/1.40.0/;s/@PACKAGE_VERSION_NUM@/0x012800/' lib/includes/nghttp2/nghttp2ver.h
    popd
}

. "${BASH_SOURCE%/*}/update-third-party.bash"
