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

usage='usage: consolidate-relnotes.bash <new-release-version> <prev-release-version>'

die() {
    echo "$@" 1>&2; exit 1
}

test "$#" = 2 || die "$usage"

files="$(ls Help/release/dev/* | grep -v Help/release/dev/0-sample-topic.rst)"
title="CMake $1 Release Notes"
underline="$(echo "$title" | sed 's/./*/g')"
echo "$title
$underline

.. only:: html

  .. contents::

Changes made since CMake $2 include the following." > Help/release/"$1".rst
tail -q -n +3 $files >> Help/release/"$1".rst
sed -i "/^   $2 / i\\
   $1 <$1>" Help/release/index.rst
rm $files
