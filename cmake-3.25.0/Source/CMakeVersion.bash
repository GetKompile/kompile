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

# Update the version component if it looks like a date or -f is given.
if test "x$1" = "x-f"; then shift ; n='*' ; else n='\{8\}' ; fi
if test "$#" -gt 0; then echo 1>&2 "usage: CMakeVersion.bash [-f]"; exit 1; fi
sed -i -e '
s/\(^set(CMake_VERSION_PATCH\) [0-9]'"$n"'\(.*\)/\1 '"$(date +%Y%m%d)"'\2/
' "${BASH_SOURCE%/*}/CMakeVersion.cmake"
