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
      -centos|--centos-version)
          CENTOS_VERSION="$value"
          shift # past argument
          ;;
        -os|--os-name)
                OS_NAME="$value"
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

function help() {
   echo "Usage: $0 --gcc-version 4.9.3 --glibc-version 2.17 --centos-version 7"
}


if [ -z "$CENTOS_VERSION" ]
then
      CENTOS_VERSION="7"

fi

if [ -z "$GCC_VERSION" ]
then
       help
       exit 1

fi

if [ -z "$GLIBC_VERSION" ]
then
       help
       exit 1

fi

docker build  -t "${OS_NAME}-${CENTOS_VERSION}"-glibc-"${GLIBC_VERSION}"-gcc-"${GCC_VERSION}" --build-arg GCC_VERSION="${GCC_VERSION}" --build-arg GLIBC_VERSION="${GLIBC_VERSION}" --build-arg CENTOS_VERSION="${CENTOS_VERSION}" --build-arg OS_NAME="${OS_NAME}" .
docker run --rm  -v $(pwd):/mnt -it ${OS_NAME}-${CENTOS_VERSION}-glibc-"${GLIBC_VERSION}"-gcc-"${GCC_VERSION}"
