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

forced=1
if [[ "${1}" = "make" ]]; then
    forced=0
fi

pushd "${BASH_SOURCE%/*}/../../Source/LexerParser" > /dev/null

for parser in            \
    CommandArgument     \
    DependsJava         \
    Expr                \
    Fortran
do
    in_file=cm${parser}Parser.y
    cxx_file=cm${parser}Parser.cxx
    h_file=cm${parser}ParserTokens.h
    prefix=cm${parser}_yy

    if [[ (${in_file} -nt ${cxx_file}) || (${in_file} -nt ${h_file}) || (${forced} -gt 0) ]]; then
        echo "Generating Parser ${parser}"
          bison --name-prefix=${prefix} --defines=${h_file} -o${cxx_file}  ${in_file}
    else
        echo "Skipped generating Parser ${parser}"
    fi
done


popd > /dev/null
