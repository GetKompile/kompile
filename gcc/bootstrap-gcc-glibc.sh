#!/bin/bash

#
# Copyright (c) 2022 Konduit K.K.
#
#     This program and the accompanying materials are made available under the
#     terms of the Apache License, Version 2.0 which is available at
#     https://www.apache.org/licenses/LICENSE-2.0.
#
#     Unless required by applicable law or agreed to in writing, software
#     distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
#     WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
#     License for the specific language governing permissions and limitations
#     under the License.
#
#     SPDX-License-Identifier: Apache-2.0
#

while [[ $# -gt 0 ]]
do
key="$1"
value="${2:-}"
case $key in
    -gcc|--gcc-version)
    GCC_VERSION="$value"
    shift # past argument
    ;;
  -glibc|--glibc-version)
      GLIBC_VERSION="$value"
      shift # past argument
      ;;
    *)
            # unknown option
    ;;
esac
if [[ $# -gt 0 ]]; then
    shift # past argument or value
fi
done

yum -y group install "Development Tools"
yum -y install wget python3
wget --no-check-certificate https://ftp.gnu.org/gnu/gcc/gcc-${GCC_VERSION}/gcc-${GCC_VERSION}.tar.gz
wget --no-check-certificate https://ftp.gnu.org/gnu/glibc/glibc-${GLIBC_VERSION}.tar.gz
tar xvf gcc-"${GCC_VERSION}".tar.gz
mkdir /gcc-"${GCC_VERSION}"-build && \
    cd /gcc-"${GCC_VERSION}" && \
    ./contrib/download_prerequisites && \
    cd /gcc-"${GCC_VERSION}"-build && \
    ../gcc-"$GCC_VERSION"/configure --prefix=/gcc-${GCC_VERSION}-build-output \
                                   --disable-multilib \
                                    --enable-languages=c,c++ && \
     cd /gcc-"${GCC_VERSION}"-build && make && make install


cd / && tar xvf glibc-"${GLIBC_VERSION}".tar.gz
mkdir /glibc-"${GLIBC_VERSION}"-build && cd /glibc-${GLIBC_VERSION}-build && \
      /glibc-"${GLIBC_VERSION}"/configure --prefix=/glibc-${GLIBC_VERSION}-build-output && PATH=/gcc-${GCC_VERSION}-build-output/bin:$PATH make && make install
