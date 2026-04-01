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

readonly name="curl"
readonly ownership="Curl Upstream <curl-library@lists.haxx.se>"
readonly subtree="Utilities/cmcurl"
readonly repo="https://github.com/curl/curl.git"
readonly tag="curl-7_86_0"
readonly shortlog=false
readonly paths="
  CMake/*
  CMakeLists.txt
  COPYING
  include/curl/*.h
  lib/*.c
  lib/*.h
  lib/CMakeLists.txt
  lib/Makefile.inc
  lib/curl_config.h.cmake
  lib/libcurl.rc
  lib/vauth/*.c
  lib/vauth/*.h
  lib/vquic/*.c
  lib/vquic/*.h
  lib/vssh/*.c
  lib/vssh/*.h
  lib/vtls/*.c
  lib/vtls/*.h
"

extract_source () {
    git_archive
    pushd "${extractdir}/${name}-reduced"
    rm lib/config-*.h
    chmod a-x lib/connect.c
    for f in \
      lib/cookie.c \
      lib/krb5.c \
      lib/security.c \
      ; do
        iconv -f LATIN1 -t UTF8 $f -o $f.utf-8
        mv $f.utf-8 $f
    done
    echo "* -whitespace" > .gitattributes
    popd
}

. "${BASH_SOURCE%/*}/update-third-party.bash"
