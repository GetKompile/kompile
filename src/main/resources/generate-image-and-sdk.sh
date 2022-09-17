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

set -eu

# Use > 1 to consume two arguments per pass in the loop (e.g. each
# argument has a corresponding value to go with it).
# Use > 0 to consume one or more arguments per pass in the loop (e.g.
# some arguments don't have a corresponding value to go with it such
# as in the --default example).
# note: if this is set to > 0 the /etc/hosts part is not recognized ( may be a bug )
PIPELINE_FILE=
KOMPILE_PYTHON_PATH="../kompile-python"
KOMPILE_C_PATH="../kompile-c-library"
IMAGE_NAME="konduit-serving"
# http,grpc
PROTOCOL=
POM_GENERATE_OUTPUT_PATH="pom2.xml"
# Where to put the common libraries output from native image, c libraries
LIB_OUTPUT_PATH="./lib"
INCLUDE_PATH="./include"
# Where to output final  bundle with python sdk, compiled binaries, install script, pipeline file/model assets
BUNDLE_OUTPUT_PATH="${IMAGE_NAME}-pipeline-bundle.zip"
MAVEN_HOME=
BUILD_PLATFORM=
BINARY_EXTENSION=
ND4J_BACKEND="nd4j-native"
ND4J_CLASSIFIER=
ND4J_HELPER=
ND4J_DATATYPES=
ND4J_OPERATIONS=
ND4J_USE_LTO="false"
ENABLE_JETSON_NANO="false"
BUILD_SHARED_LIBRARY="true"
MAIN_CLASS=
MIN_RAM_MEGS=2000
MAX_RAM_MEGS=2000
NO_GC="false"
NATIVE_IMAGE_FILE_PATH=
KOMPILE_PREFIX="./"
PYTHON_EXEC="python"
BUILD_CUDA_BACKEND="false"
BUILD_CPU_BACKEND="false"
IS_SERVER="false"
DL4J_BRANCH="master"
KONDUIT_SERVING_BRANCH="master"
BUILD_HEAP_SPACE=

while [[ $# -gt 0 ]]
do
key="$1"
value="${2:-}"
case $key in
    -db|--dl4j-branch)
    DL4J_BRANCH="$value"
    shift # past argument
    ;;
    -ksb|--konduit-serving-branch)
      KONDUIT_SERVING_BRANCH="$value"
      shift # past argument
      ;;
       -hs|--native-image-heap-space)
          BUILD_HEAP_SPACE="$value"
          shift # past argument
          ;;
    -p|--pipeline-file)
    PIPELINE_FILE="$value"
    shift # past argument
    ;;
     -dt|--nd4j-datatypes)
      ND4J_DATATYPES="$value"
      shift # past argument
      ;;
     -s|--server)
        IS_SERVER="$value"
        shift # past argument
      ;;
      -op|--nd4j-operations)
        ND4J_OPERATIONS="$value"
        shift # past argument
      ;;
    -py|-python-sdk|--python-sdk)
    KOMPILE_PYTHON_PATH="$value"
    shift # past argument
    ;;
    -c|--c-library)
    KOMPILE_C_PATH="$value"
    shift # past argument
    ;;
    -pe|--python-exec)
      PYTHON_EXEC="$value"
      shift # past argument
      ;;
    -i|--image-name)
    IMAGE_NAME="$value"
    shift # past argument
    ;;
    -pl|--protocol)
    PROTOCOL="$value"
    shift # past argument
    ;;
    -pom|--pom-path)
    POM_GENERATE_OUTPUT_PATH="$value"
    shift # past argument
    ;;
    -lp|--lib-path)
    LIB_OUTPUT_PATH="$value"
    shift # past argument
    ;;
    -mh|--maven-home)
    MAVEN_HOME="$value"
    shift # past argument
    ;;
    -lto|--use-lto)
      ND4J_USE_LTO="$value"
      shift # past argument
    ;;
     -nb|--nd4j-backend)
    ND4J_BACKEND="$value"
    shift # past argument
    ;;
     -nc|--nd4j-classifier)
    ND4J_CLASSIFIER="$value"
    shift # past argument
    ;;
    -nh|--nd4j-helper)
      ND4J_HELPER="$value"
      shift # past argument
      ;;
    -en|--enable-jetson-nano)
    ENABLE_JETSON_NANO="$value"
    shift # past argument
    ;;
    -bs|--build-shared)
    BUILD_SHARED_LIBRARY="$value"
    shift # past argument
    ;;
    -as|--assembly)
      ASSEMBLY="$value"
      shift # past argument
      ;;
     -mc|--main-class)
    MAIN_CLASS="$value"
    shift # past argument
    ;;
    -mr|--min-ram)
    MIN_RAM_MEGS="$value"
    shift # past argument
    ;;
    -mar|--max-ram)
    MAX_RAM_MEGS="$value"
    shift # past argument
    ;;
    -nifp|--native-image-file-path)
    NATIVE_IMAGE_FILE_PATH="$value"
    shift # past argument
    ;;
    -kp|--kompile-prefix)
      KOMPILE_PREFIX="$value"
      shift # past argument
      ;;
   -ngc|--no-garbage-collection)
    NO_GC="$value"
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

function set_platform () {
  OS=
  if [[ "$OSTYPE" == "linux-gnu"* ]]; then
        OS="linux"
elif [[ "$OSTYPE" == "darwin"* ]]; then
        OS="mac-osx"
elif [[ "$OSTYPE" == "cygwin" ]]; then
       OS="windows"
elif [[ "$OSTYPE" == "msys" ]]; then
       OS="windows"
elif [[ "$OSTYPE" == "win32" ]]; then
        OS="windows"
elif [[ "$OSTYPE" == "freebsd"* ]]; then
        OS="linux"
else
        OS="linux"
fi

#Set a platform default if one isn't found
if [ -z "${ND4J_CLASSIFIER}" ]; then
  PLATFORM="$(lscpu | grep Architecture | tr -d ':'  | sed 's/Architecture//' | xargs echo -n)"
if [ "${PLATFORM}" == "aarch64" ];then
      PLATFORM="arm64"
fi
      BUILD_PLATFORM="${OS}-${PLATFORM}"
   else
        BUILD_PLATFORM="${ND4J_CLASSIFIER}"
        IFS=- read -r OS ARCHITECTURE <<< "${ND4J_CLASSIFIER}"

        echo "READ ARCHITECTURE AND OS AS ${OS} ${ARCHITECTURE}"
fi


}




function set_binary_extension {
if [[ "$OSTYPE" == "linux-gnu"* ]]; then
        BINARY_EXTENSION="so"
elif [[ "$OSTYPE" == "darwin"* ]]; then
        BINARY_EXTENSION="dylib"
elif [[ "$OSTYPE" == "cygwin" ]]; then
       BINARY_EXTENSION="dll"
elif [[ "$OSTYPE" == "msys" ]]; then
       BINARY_EXTENSION="dll"
elif [[ "$OSTYPE" == "win32" ]]; then
        BINARY_EXTENSION="dll"
elif [[ "$OSTYPE" == "freebsd"* ]]; then
        BINARY_EXTENSION="so"
else
         BINARY_EXTENSION="so"
fi

}

#Set a platform default if one isn't found
if [ -z "${BUILD_PLATFORM}" ]; then
  set_platform
fi


set_binary_extension

# Set this for cmake environment so it automatically detects needed variables
export LIB_OUTPUT_PATH
export INCLUDE_PATH


echo "PIPELINE_FILE ${PIPELINE_FILE}"
echo "KOMPILE_PYTHON_PATH= ${KOMPILE_PYTHON_PATH}"
echo "KOMPILE_C_PATH ${KOMPILE_C_PATH}"
echo "IMAGE_NAME ${IMAGE_NAME}"
echo "PROTOCOL ${PROTOCOL}"
echo "POM_GENERATE_OUTPUT_PATH ${POM_GENERATE_OUTPUT_PATH}"
echo "LIB_OUTPUT_PATH ${LIB_OUTPUT_PATH}"
echo "INCLUDE_PATH ${INCLUDE_PATH}"
echo "BUNDLE_OUTPUT_PATH ${BUNDLE_OUTPUT_PATH}"
echo "MAVEN_HOME ${MAVEN_HOME}"
echo "BUILD_PLATFORM ${BUILD_PLATFORM}"
echo "BINARY_EXTENSION ${BINARY_EXTENSION}"
echo "ND4J_BACKEND ${ND4J_BACKEND}"
echo "ND4J_HELPER ${ND4J_HELPER}"
echo "ND4J_CLASSIFIER ${ND4J_CLASSIFIER}"
echo "ENABLE_JETSON_NANO ${ENABLE_JETSON_NANO}"
echo "BUILD_SHARED_LIBRARY ${BUILD_SHARED_LIBRARY}"
echo "MAIN_CLASS ${MAIN_CLASS}"
echo "MIN_RAM_MEGS ${MIN_RAM_MEGS}"
echo "MAX_RAM_MEGS ${MAX_RAM_MEGS}"
echo "NO_GC ${NO_GC}"
echo "NATIVE_IMAGE_FILE_PATH ${NATIVE_IMAGE_FILE_PATH}"
echo "IS_SERVER ${IS_SERVER}"
echo "DL4J_BRANCH ${DL4J_BRANCH}"
echo "KONDUIT_SERVING_BRANCH ${KONDUIT_SERVING_BRANCH}"
echo "ND4J_USE_LTO ${ND4J_USE_LTO}"
echo "BUILD_HEAP_SPACE ${BUILD_HEAP_SPACE}"
echo "ASSEMBLY ${ASSEMBLY}"

function source_backend_end() {
     if [ -z "${ND4J_HELPER}" ]; then
           if test -f "$HOME/.kompile/backend-envs/${ND4J_BACKEND}/${ND4J_CLASSIFIER}.env"; then
              echo "Loading environment for backend ${ND4J_BACKEND}"
              source "$HOME/.kompile/backend-envs/${ND4J_BACKEND}/${ND4J_CLASSIFIER}.env"
         elif test -f "$HOME/.kompile/backend-envs/${ND4J_BACKEND}/${ND4J_CLASSIFIER}${ND4J_HELPER}.env"; then
                echo "Loading environment for backend ${ND4J_BACKEND} and helper ${ND4J_HELPER}"
                source "$HOME/.kompile/backend-envs/${ND4J_BACKEND}/${ND4J_CLASSIFIER}${ND4J_HELPER}.env"
            else
                     echo "No environment found for backend ${ND4J_BACKEND}"
     fi
    fi
}


if [ "${ND4J_BACKEND}"  = "nd4j-native" ]; then
      BUILD_CPU_BACKEND="true"
    else
        BUILD_CPU_BACKEND="false"
fi

if [[ "${ND4J_BACKEND}" == *"nd4j-cuda"* ]]; then
      BUILD_CUDA_BACKEND="true"
    else
        BUILD_CUDA_BACKEND="false"
fi

      echo "Processing pipeline file $PIPELINE_FILE"
      echo "Outputting pom file for build to ${POM_GENERATE_OUTPUT_PATH}"
      # shellcheck disable=SC2236
      if  [ ! -z "${BUILD_HEAP_SPACE}" ] && [ "$BUILD_HEAP_SPACE" != "" ]; then
               POM_GENERATE_COMMAND=$(./kompile build  pipeline-command-generate --assembly=${ASSEMBLY} --server=${IS_SERVER} --nd4jBackend=${ND4J_BACKEND} --nd4jBackendClassifier=${ND4J_CLASSIFIER} --mainClass=${MAIN_CLASS}   --pipelineFile=${PIPELINE_FILE}  --numpySharedLibrary=${BUILD_SHARED_LIBRARY}  --imageName=${IMAGE_NAME}  --outputFile=${POM_GENERATE_OUTPUT_PATH} --nativeImageJvmArg=-Xmx${BUILD_HEAP_SPACE})
               elif [ "$ASSEMBLY" != "true" ]; then
                    POM_GENERATE_COMMAND=$(./kompile build  pipeline-command-generate --assembly=${ASSEMBLY}   --server=${IS_SERVER} --nd4jBackend=${ND4J_BACKEND} --nd4jBackendClassifier=${ND4J_CLASSIFIER} --mainClass=${MAIN_CLASS}   --pipelineFile=${PIPELINE_FILE}  --numpySharedLibrary=${BUILD_SHARED_LIBRARY}  --imageName=${IMAGE_NAME}  --outputFile=${POM_GENERATE_OUTPUT_PATH})
              else
                  echo "Running assembly pom generate"
                  POM_GENERATE_COMMAND=$(./kompile build  pipeline-command-generate --assembly=${ASSEMBLY}  --server=false --nd4jBackend=${ND4J_BACKEND} --nd4jBackendClassifier=${ND4J_CLASSIFIER}   --outputFile=${POM_GENERATE_OUTPUT_PATH})

      fi


    BUILD_DIR="$(pwd)"
    if [ "$ASSEMBLY" == "false" ]; then
        ./kompile install install-requisites --os="${OS}" \
                                              --architecture="${ARCHITECTURE}" \
                                              --nd4jBackend="${ND4J_BACKEND}" \

         source_backend_end

        ./kompile build clone-build \
                     --nd4jBackend=${ND4J_BACKEND} \
                     --libnd4jUseLto=${ND4J_USE_LTO} \
                     --dl4jBranchName=${DL4J_BRANCH} \
                     --konduitServingBranchName=${KONDUIT_SERVING_BRANCH} \
                     --dl4jDirectory=${KOMPILE_PREFIX}/deeplearning4j \
                     --konduitServingDirectory=${KOMPILE_PREFIX}/konduit-serving \
                     --buildDl4j \
                     --buildKonduitServing="${ASSEMBLY}" \
                     --libnd4jClassifier="${ND4J_CLASSIFIER}" \
                       --nd4jBackend="${ND4J_BACKEND}" \
                      --libnd4jHelper="${ND4J_HELPER}" \
                      --libnd4jOperations="${ND4J_OPERATIONS}" \
                      --libnd4jDataTypes="${ND4J_DATATYPES}"
        echo "Command pom generate command was ${POM_GENERATE_COMMAND}"
        eval "./kompile ${POM_GENERATE_COMMAND}"
        echo "Generated pom contents:"
        cat "${POM_GENERATE_OUTPUT_PATH}"
        export NATIVE_LIB_DIR="${BUILD_DIR}/${IMAGE_NAME}/target"
      ./kompile build native-image-generate  \
                      --server="${IS_SERVER}" \
                      --nativeImageFilesPath="${NATIVE_IMAGE_FILE_PATH}" \
                      --imageName="${IMAGE_NAME}" \
                      --outputFile="${POM_GENERATE_OUTPUT_PATH}" \
                      --pipelinePath="${PIPELINE_FILE}" \
                      --mavenHome="${MAVEN_HOME}" \
                      --numpySharedLibrary="${BUILD_SHARED_LIBRARY}" \
                      --javacppPlatform="${BUILD_PLATFORM}" \
                      --mainClass="${MAIN_CLASS}"
                      cd "${NATIVE_LIB_DIR}"

                        if [ "${IS_SERVER}" == "false" ]; then
                             ln -s ./"${IMAGE_NAME}.so" "lib${IMAGE_NAME}.so"
                             cd -
                             echo "Creating library directory ${LIB_OUTPUT_PATH} and include directory ${INCLUDE_PATH} if not exists"

                             mkdir -p "${LIB_OUTPUT_PATH}"
                             cd "${LIB_OUTPUT_PATH}"
                             # Resolve absolute path in case relative path is specified
                             REAL_LIB_PATH="$(pwd)"
                             echo "Set library path to ${REAL_LIB_PATH}"
                             cd "${BUILD_DIR}"
                             mkdir -p "${INCLUDE_PATH}"
                             cd "${INCLUDE_PATH}"
                             # Capture absolute path of include directory as well in case relative path is specified
                             REAL_INCLUDE_PATH="$(pwd)"
                             echo "Set real include path to ${REAL_INCLUDE_PATH}"
                             cd "${BUILD_DIR}"
                             cp "${KOMPILE_PREFIX}/${IMAGE_NAME}/target/"*.h "${INCLUDE_PATH}"
                             cp "${KOMPILE_PREFIX}/src/main/resources/numpy_struct.h" "${INCLUDE_PATH}"
                             cp "${KOMPILE_PREFIX}/${IMAGE_NAME}/target/"*.${BINARY_EXTENSION} "${LIB_OUTPUT_PATH}"
                             # Sometimes CMakeCache.txt maybe present. Remove it before copying to ensure a build proceeds.
                             if  test -f "${KOMPILE_PREFIX}/${KOMPILE_C_PATH}/CMakeCache.txt" ; then
                                  rm -rf "${KOMPILE_PREFIX}/${KOMPILE_C_PATH}/CMakeCache.txt"
                             fi
                             cp -rf "${KOMPILE_C_PATH}" ./kompile-c

                             cd ${KOMPILE_C_PATH}
                             cmake .
                             make
                             # Note we don't quote here so it resolves the binary extension properly
                             cp *."${BINARY_EXTENSION}" "${REAL_LIB_PATH}"
                             export LIB_OUTPUT_PATH="${REAL_LIB_PATH}"
                             cd ..

                             cd ${KOMPILE_PYTHON_PATH}
                             mkdir -p lib
                             ${PYTHON_EXEC} setup.py build_ext --inplace
                             # Work around for bundling not working properly with wheel.
                             # Allow  artifacts to automatically be specified so they can be bundled.
                             cp -rf ${REAL_LIB_PATH}/* ./kompile/interface/native/
                              ${PYTHON_EXEC} setup.py bdist_wheel
                             cd ..
                             echo "Creating bundle directory ${IMAGE_NAME}-bundle"
                             # Ensure old copies are removed
                             if test -f "${IMAGE_NAME}-bundle" ; then
                                 rm -rf "${IMAGE_NAME}-bundle"
                             fi
                             mkdir -p "${IMAGE_NAME}-bundle"
                             # Copy the include directory, library directory, python sdk, pipeline file in to the bundle
                             cp -rf kompile-python/dist/*.whl "${IMAGE_NAME}-bundle"
                             cp -rf "${REAL_INCLUDE_PATH}" "${IMAGE_NAME}-bundle"
                             echo "Real library path is ${REAL_LIB_PATH}"
                             cp -rf "${REAL_LIB_PATH}" "${IMAGE_NAME}-bundle/lib"
                             if test -f "${IMAGE_NAME}-bundle/lib/${IMAGE_NAME}.${BINARY_EXTENSION}"; then
                                    mv "${IMAGE_NAME}-bundle/lib/${IMAGE_NAME}.${BINARY_EXTENSION}" "${IMAGE_NAME}-bundle/lib/lib${IMAGE_NAME}.${BINARY_EXTENSION}"
                             fi
                             cp "${PIPELINE_FILE}" "${IMAGE_NAME}-bundle"
                             tar cvf "${IMAGE_NAME}-bundle.tar" "${IMAGE_NAME}-bundle"
                             echo "Bundle built for image name"
                          else
                              echo "Built serving library. Please find executable in target/${IMAGE_NAME}"
                        fi

                  elif [ "$ASSEMBLY" == "true" ]; then
                    source_backend_end
                   echo "Building dl4j distribution"
                    echo "Installing Pre requisites for OS ${OS} and architecture ${ARCHITECTURE}"
                    ./kompile install install-requisites --os="${OS}" \
                                                                 --architecture="${ARCHITECTURE}" \
                                                                 --nd4jBackend="${ND4J_BACKEND}" \

                   ./kompile build clone-build \
                                --libnd4jUseLto=${ND4J_USE_LTO} \
                                --dl4jBranchName=${DL4J_BRANCH} \
                                --dl4jDirectory=${KOMPILE_PREFIX}/deeplearning4j \
                                --konduitServingDirectory=${KOMPILE_PREFIX}/konduit-serving \
                                --buildDl4j \
                                --platform="${ND4J_CLASSIFIER}" \
                                --libnd4jClassifier="${ND4J_CLASSIFIER}" \
                                --nd4jBackend="${ND4J_BACKEND}" \
                                 --libnd4jHelper="${ND4J_HELPER}" \
                                 --libnd4jOperations="${ND4J_OPERATIONS}" \
                                 --libnd4jDataTypes="${ND4J_DATATYPES}"
                      echo "Command pom generate command was ${POM_GENERATE_COMMAND}"
                      eval "./kompile ${POM_GENERATE_COMMAND}"
                      echo "Generated pom contents:"
                      cat "${POM_GENERATE_OUTPUT_PATH}"
                      ./kompile build dl4j-build-generate  \
                                  --pomFile="${POM_GENERATE_OUTPUT_PATH}" \
                                  --mavenHome="${MAVEN_HOME}" \
                                  --javacppPlatform="${BUILD_PLATFORM}"


           fi



