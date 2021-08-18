#!/bin/bash

set -eu


# Use > 1 to consume two arguments per pass in the loop (e.g. each
# argument has a corresponding value to go with it).
# Use > 0 to consume one or more arguments per pass in the loop (e.g.
# some arguments don't have a corresponding value to go with it such
# as in the --default example).
# note: if this is set to > 0 the /etc/hosts part is not recognized ( may be a bug )
PIPELINE_FILE="true"
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
ND4J_CLASSIFIER=""
ENABLE_JETSON_NANO="false"
BUILD_SHARED_LIBRARY="true"
MAIN_CLASS=

while [[ $# -gt 0 ]]
do
key="$1"
value="${2:-}"
#Build type (release/debug), packaging type, chip: cpu,cuda, lib type (static/dynamic)
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

if [ -z "${MAIN_CLASS}" ]; then
   if [ "${PLATFORM}" == "arm64" ]; then
     MAIN_CLASS="ai.konduit.pipelinegenerator.main.NumpyEntryPointArm"
    else
      MAIN_CLASS="ai.konduit.pipelinegenerator.main.NumpyEntryPoint"
   fi
fi

ND4J_CLASSIFIER="${BUILD_PLATFORM}"

set_binary_extension

# Set this for cmake environment so it automatically detects needed variables
export LIB_OUTPUT_PATH
export INCLUDE_PATH

if test -f "$PIPELINE_FILE"; then
     echo "Processing pipeline file $PIPELINE_FILE"
    echo "Outputting pom file for build to ${POM_GENERATE_OUTPUT_PATH}"
    POM_GENERATE_COMMAND="pom-generate   --mainClass=${MAIN_CLASS} --numpySharedLibrary=${BUILD_SHARED_LIBRARY}  --nd4jBackend=${ND4J_BACKEND}  --nd4jBackendClassifier=${ND4J_CLASSIFIER}  --enableJetsonNano=${ENABLE_JETSON_NANO} --pipelinePath=${PIPELINE_FILE}   --imageName=${IMAGE_NAME}  --outputFile=${POM_GENERATE_OUTPUT_PATH}"
    echo "Command pom generate command was ${POM_GENERATE_COMMAND}"
    eval "./pipeline ${POM_GENERATE_COMMAND}"
    ./pipeline native-image-generate  \
                --imageName="${IMAGE_NAME}" \
                --outputFile="${POM_GENERATE_OUTPUT_PATH}" \
                --pipelinePath="${PIPELINE_FILE}" \
                --mavenHome="${MAVEN_HOME}" \
                --numpySharedLibrary="${BUILD_SHARED_LIBRARY}" \
                --javacppPlatform="${BUILD_PLATFORM}" \
                --mainClass="${MAIN_CLASS}"
    echo "Creating library directory ${LIB_OUTPUT_PATH} and include directory ${INCLUDE_PATH} if not exists"

    mkdir -p "${LIB_OUTPUT_PATH}"
    BUILD_DIR="$(pwd)"
    cd "${LIB_OUTPUT_PATH}"
    # Resolve absolute path in case relative path is specified
    REAL_LIB_PATH="$(pwd)"
    cd "${BUILD_DIR}"
    mkdir -p "${INCLUDE_PATH}"
    cd "${INCLUDE_PATH}"
    # Capture absolute path of include directory as well in case relative path is specified
    REAL_INCLUDE_PATH="$(pwd)"
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
    cp lib/* "${REAL_LIB_PATH}"
    cd ..
    # Ensure link path is set for compiling the right python libraries
    export LD_LIBRARY_PATH="${LIB_OUTPUT_PATH}"
    if test  -f './kompile-python' ; then
         rm -rf './kompile-python'
    fi

    cp -rf "${KOMPILE_PYTHON_PATH}" ./kompile-python
    cd ./kompile-python
    python setup.py build_ext --inplace
    # Work around for bundling not working properly with wheel.
    # Allow  artifacts to automatically be specified so they can be bundled.
    cp -rf ${REAL_LIB_PATH}/* ./kompile/interface/native/
    python setup.py bdist_wheel
    cd ..
    echo "Creating bundle directory ${IMAGE_NAME}-bundle"
    mkdir -p "${IMAGE_NAME}-bundle"
    # Copy the include directory, library directory, python sdk, pipeline file in to the bundle
    cp -rf kompile-python/dist/*.whl "${IMAGE_NAME}-bundle"
    cp -rf "${REAL_INCLUDE_PATH}" "${IMAGE_NAME}-bundle"
    echo "Real library path is ${REAL_LIB_PATH}"
    cp -rf "${REAL_LIB_PATH}" "${IMAGE_NAME}-bundle/lib"
    cp -rf ./kompile-python "${IMAGE_NAME}-bundle"
    mv "${IMAGE_NAME}-bundle/lib/${IMAGE_NAME}.${BINARY_EXTENSION}" "${IMAGE_NAME}-bundle/lib/lib${IMAGE_NAME}.${BINARY_EXTENSION}"
    cp "${PIPELINE_FILE}" "${IMAGE_NAME}-bundle"
    tar cvf "${IMAGE_NAME}-bundle.tar" "${IMAGE_NAME}-bundle"
    echo "Bundle built for image name "

    else
        echo "${PIPELINE_FILE} not found. Please specify a pre existing file."
        exit 1
fi