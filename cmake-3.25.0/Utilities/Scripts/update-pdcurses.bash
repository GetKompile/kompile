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

readonly name="PDCurses"
readonly ownership="PDCurses Upstream <kwrobot@kitware.com>"
readonly subtree="Utilities/cmpdcurses"
readonly repo="https://github.com/wmcbrine/PDCurses.git"
readonly tag="f1cd4f4569451a5028ddf3d3c202f0ad6b1ae446"
readonly shortlog=false
readonly paths="
  README.md
  *.h
  common/acs437.h
  common/acsuni.h
  pdcurses/README.md
  pdcurses/*.c
  wincon/README.md
  wincon/*.c
  wincon/*.h
"

extract_source () {
    git_archive
    pushd "${extractdir}/${name}-reduced"
    echo "* -whitespace" > .gitattributes
    popd
}

. "${BASH_SOURCE%/*}/update-third-party.bash"
