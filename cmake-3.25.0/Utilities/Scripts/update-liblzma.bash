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

readonly name="liblzma"
readonly ownership="liblzma upstream <xz-devel@tukaani.org>"
readonly subtree="Utilities/cmliblzma"
readonly repo="https://git.tukaani.org/xz.git"
readonly tag="v5.2.5"
readonly shortlog=false
readonly paths="
  COPYING
  src/common/common_w32res.rc
  src/common/mythread.h
  src/common/sysdefs.h
  src/common/tuklib_common.h
  src/common/tuklib_config.h
  src/common/tuklib_cpucores.c
  src/common/tuklib_cpucores.h
  src/common/tuklib_integer.h
  src/liblzma/
"

extract_source () {
    git_archive
    pushd "${extractdir}/${name}-reduced"
    mv src/common .
    mv src/liblzma .
    rmdir src
    rm liblzma/Makefile.*
    rm liblzma/*/Makefile.*
    rm liblzma/liblzma.map
    rm liblzma/validate_map.sh
    popd
}

. "${BASH_SOURCE%/*}/update-third-party.bash"
