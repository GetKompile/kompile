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
mkdir -p /opt/cmake/src/cmake-ninja
cd /opt/cmake/src/cmake-ninja
echo >CMakeCache.txt '
CMAKE_Fortran_COMPILER:STRING=
CMake_TEST_IPO_WORKS_C:BOOL=ON
CMake_TEST_IPO_WORKS_CXX:BOOL=ON
CMake_TEST_NO_NETWORK:BOOL=ON
CMake_TEST_Qt5:BOOL=ON
'
cmake ../cmake -DCMake_TEST_HOST_CMAKE=1 -G "Ninja"
ninja
ctest --output-on-failure -j $(nproc)
