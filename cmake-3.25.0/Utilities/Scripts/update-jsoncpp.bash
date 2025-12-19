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

readonly name="jsoncpp"
readonly ownership="JsonCpp Upstream <kwrobot@kitware.com>"
readonly subtree="Utilities/cmjsoncpp"
readonly repo="https://github.com/open-source-parsers/jsoncpp.git"
readonly tag="42e892d96e47b1f6e29844cc705e148ec4856448"
readonly shortlog=false
readonly paths="
  LICENSE
  include/json
  src/lib_json
"
readonly remove="
  include/json/autolink.h
  src/lib_json/CMakeLists.txt
  src/lib_json/sconscript
  src/lib_json/version.h.in
"

extract_source () {
    git_archive
    pushd "${extractdir}/${name}-reduced"
    rm $remove
    echo "* -whitespace" > .gitattributes
    popd
}

. "${BASH_SOURCE%/*}/update-third-party.bash"
