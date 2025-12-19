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

readonly name="zstd"
readonly ownership="zstd upstream <kwrobot@kitware.com>"
readonly subtree="Utilities/cmzstd"
readonly repo="https://github.com/facebook/zstd.git"
readonly tag="v1.5.0"
readonly shortlog=false
readonly paths="
  LICENSE
  README.md
  lib/common/*.c
  lib/common/*.h
  lib/compress/*.c
  lib/compress/*.h
  lib/decompress/*.c
  lib/decompress/*.h
  lib/deprecated/*.c
  lib/deprecated/*.h
  lib/dictBuilder/*.c
  lib/dictBuilder/*.h
  lib/*.h
"

extract_source () {
    git_archive
    pushd "${extractdir}/${name}-reduced"
    echo "* -whitespace" > .gitattributes
    popd
}

. "${BASH_SOURCE%/*}/update-third-party.bash"
