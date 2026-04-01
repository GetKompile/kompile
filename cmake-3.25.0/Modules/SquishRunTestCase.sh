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

echo "Starting the squish server...$1 --daemon"
$1 --daemon

echo "Running the test case...$2 --testcase $3 --wrapper $4 --aut $5"
$2 --testcase $3 --wrapper $4 --aut $5
returnValue=$?

echo "Stopping the squish server...$1 --stop"
$1 --stop

exit $returnValue
