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

readonly name="vim-cmake-syntax"
readonly ownership="vim-cmake-syntax upstream <kwrobot@kitware.com>"
readonly subtree="Auxiliary/vim"
readonly repo="https://github.com/pboettch/vim-cmake-syntax.git"
readonly tag="master"
readonly shortlog=true
readonly paths="
  indent
  syntax
  cmake.vim.in
  extract-upper-case.pl
"

extract_source () {
    git_archive
}

. "${BASH_SOURCE%/*}/update-third-party.bash"
