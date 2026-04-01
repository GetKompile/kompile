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

readonly name="GitSetup"
readonly ownership="GitSetup Upstream <kwrobot@kitware.com>"
readonly subtree="Utilities/GitSetup"
readonly repo="https://gitlab.kitware.com/utils/gitsetup.git"
readonly tag="setup"
readonly shortlog=false
readonly paths="
  .gitattributes
  LICENSE
  NOTICE
  README
  setup-hooks
  setup-user
  tips
"

extract_source () {
    git_archive
}

. "${BASH_SOURCE%/*}/update-third-party.bash"
