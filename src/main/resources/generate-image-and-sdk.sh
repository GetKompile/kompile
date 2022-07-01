#!/bin/bash

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
ENABLE_JETSON_NANO="false"
BUILD_SHARED_LIBRARY="true"
MAIN_CLASS=
MIN_RAM_MEGS=2000
MAX_RAM_MEGS=2000
NO_GC="false"
NATIVE_IMAGE_FILE_PATH=

while [[ $# -gt 0 ]]
do
key="$1"
value="${2:-}"
case $key in
    -p|--pipeline-file)
    PIPELINE_FILE="$value"
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
     -nb|--nd4j-backend)
    ND4J_BACKEND="$value"
    shift # past argument
    ;;
     -nc|--nd4j-classifier)
    ND4J_CLASSIFIER="$value"
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

PLATFORM="$(lscpu | grep Architecture | tr -d ':'  | sed 's/Architecture//' | xargs echo -n)"
if [ "${PLATFORM}" == "aarch64" ];then
      PLATFORM="arm64"
fi
BUILD_PLATFORM="${OS}-${PLATFORM}"
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

#if [ -z "${MAIN_CLASS}" ]; then
#   if [ "${PLATFORM}" == "arm64" ]; then
#     MAIN_CLASS="ai.konduit.pipelinegenerator.main.NumpyEntryPointArm"
#    else
#      MAIN_CLASS="ai.konduit.pipelinegenerator.main.NumpyEntryPoint"
#   fi
#fi
if [ -z "${BUILD_PLATFORM}" ]; then
  ND4J_CLASSIFIER="${BUILD_PLATFORM}"
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
echo "ND4J_CLASSIFIER ${ND4J_CLASSIFIER}"
echo "ENABLE_JETSON_NANO ${ENABLE_JETSON_NANO}"
echo "BUILD_SHARED_LIBRARY ${BUILD_SHARED_LIBRARY}"
echo "MAIN_CLASS ${MAIN_CLASS}"
echo "MIN_RAM_MEGS ${MIN_RAM_MEGS}"
echo "MAX_RAM_MEGS ${MAX_RAM_MEGS}"
echo "NO_GC ${NO_GC}"
echo "NATIVE_IMAGE_FILE_PATH ${NATIVE_IMAGE_FILE_PATH}"


if test -f "$PIPELINE_FILE"; then
     echo "Processing pipeline file $PIPELINE_FILE"
    echo "Outputting pom file for build to ${POM_GENERATE_OUTPUT_PATH}"
    POM_GENERATE_COMMAND="$(./kompile build  pipeline-command-generate  --nd4jBackend=${ND4J_BACKEND} --nd4jBackendClassifier=${ND4J_CLASSIFIER} --mainClass=${MAIN_CLASS}   --pipelineFile=${PIPELINE_FILE}  --numpySharedLibrary=${BUILD_SHARED_LIBRARY}  --imageName=${IMAGE_NAME}  --outputFile=${POM_GENERATE_OUTPUT_PATH})"
    echo "Command pom generate command was ${POM_GENERATE_COMMAND}"
    eval "./kompile ${POM_GENERATE_COMMAND}"
    BUILD_DIR="$(pwd)"
    export NATIVE_LIB_DIR="${BUILD_DIR}/${IMAGE_NAME}/target"
    ./kompile build native-image-generate  \
                --nativeImageFilesPath="${NATIVE_IMAGE_FILE_PATH}" \
                --imageName="${IMAGE_NAME}" \
                --outputFile="${POM_GENERATE_OUTPUT_PATH}" \
                --pipelinePath="${PIPELINE_FILE}" \
                --mavenHome="${MAVEN_HOME}" \
                --numpySharedLibrary="${BUILD_SHARED_LIBRARY}" \
                --javacppPlatform="${BUILD_PLATFORM}" \
                --mainClass="${MAIN_CLASS}"
    cp "${NATIVE_LIB_DIR}/${IMAGE_NAME}.so" "${NATIVE_LIB_DIR}/lib${IMAGE_NAME}.so"
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
    cp "./${IMAGE_NAME}/target/"*.h "${INCLUDE_PATH}"
    cp "./src/main/resources/numpy_struct.h" "${INCLUDE_PATH}"
    cp "${IMAGE_NAME}/target/"*.${BINARY_EXTENSION} "${LIB_OUTPUT_PATH}"
     if  test -f './kompile-c' ; then
         rm -rf './kompile-c'
    fi
    # Sometimes CMakeCache.txt maybe present. Remove it before copying to ensure a build proceeds.
    if  test -f "${KOMPILE_C_PATH}/CMakeCache.txt" ; then
         rm -rf "${KOMPILE_C_PATH}/CMakeCache.txt"
    fi
    cp -rf "${KOMPILE_C_PATH}" ./kompile-c

    cd ./kompile-c
    cmake .
    make
    # Note we don't quote here so it resolves the binary extension properly
    cp *."${BINARY_EXTENSION}" "${REAL_LIB_PATH}"
    export LIB_OUTPUT_PATH="${REAL_LIB_PATH}"
    cd ..
    # Ensure link path is set for compiling the right python libraries
    export LD_LIBRARY_PATH=""
    export LD_LIBRARY_PATH="${KOMPILE_C_PATH}:${NATIVE_LIB_DIR}:${LD_LIBRARY_PATH}"
    if test  -f './kompile-python' ; then
         rm -rf './kompile-python'
    fi

    #cp -rf "${KOMPILE_PYTHON_PATH}" ./kompile-python
    cd ./kompile-python
    mkdir -p lib
    python setup.py build_ext --inplace
    # Work around for bundling not working properly with wheel.
    # Allow  artifacts to automatically be specified so they can be bundled.
    cp -rf ${REAL_LIB_PATH}/* ./kompile/interface/native/
    python setup.py bdist_wheel
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
        echo "${PIPELINE_FILE} not found. Please specify a pre existing file."
        exit 1
fi
