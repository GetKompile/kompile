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

readonly name="librhash"
readonly ownership="librhash upstream <kwrobot@kitware.com>"
readonly subtree="Utilities/cmlibrhash"
readonly repo="https://github.com/rhash/rhash.git"
readonly tag="v1.3.9"
readonly shortlog=false
readonly paths="
  COPYING
  librhash/algorithms.c
  librhash/algorithms.h
  librhash/byte_order.c
  librhash/byte_order.h
  librhash/hex.c
  librhash/hex.h
  librhash/md5.c
  librhash/md5.h
  librhash/rhash.c
  librhash/rhash.h
  librhash/sha1.c
  librhash/sha1.h
  librhash/sha256.c
  librhash/sha256.h
  librhash/sha3.c
  librhash/sha3.h
  librhash/sha512.c
  librhash/sha512.h
  librhash/ustd.h
  librhash/util.h
"

extract_source () {
    git_archive
    pushd "${extractdir}/${name}-reduced"
    echo "* -whitespace" > .gitattributes
    popd
}

. "${BASH_SOURCE%/*}/update-third-party.bash"
