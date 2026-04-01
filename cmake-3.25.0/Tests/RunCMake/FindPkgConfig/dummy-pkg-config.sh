#!/bin/sh

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

# This is a replacement for pkg-config that compares the string passed
# to the --exists argument with the PKG_CONFIG_PATH environment variable
# and returns 1 if they are different.

# variables to get around `--static --version` printing the received
# message and then version
static=false
print_errors=false

while [ $# -gt 0 ]; do
  case $1 in
    --version)
      echo "0.0-cmake-dummy"
      exit 0
      ;;
    --exists)
      shift
      eval last=\${$#}
      echo "Expected: ${last}"
      echo "Found:    ${PKG_CONFIG_PATH}"
      [ "${last}" = "${PKG_CONFIG_PATH}" ] && exit 0 || exit 1
      ;;
    --static)
      static=true
      ;;
    --print-errors)
      print_errors=true
      ;;
  esac
  shift
done

$static && echo "Received --static"
$print_errors && echo "Received --print-errors"

if $static || $print_errors; then
  exit 0
fi

exit 255
