/*
 * Copyright (c) 2022 Konduit K.K.
 *
 *     This program and the accompanying materials are made available under the
 *     terms of the Apache License, Version 2.0 which is available at
 *     https://www.apache.org/licenses/LICENSE-2.0.
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *     WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *     License for the specific language governing permissions and limitations
 *     under the License.
 *
 *     SPDX-License-Identifier: Apache-2.0
 */

package ai.konduit.pipelinegenerator.main.install;

import picocli.CommandLine;

import java.io.File;
import java.util.concurrent.Callable;
@CommandLine.Command(name = "native-tools",mixinStandardHelpOptions = false,description = "Installs native tools like cmake, make, gcc for used with compiling c++ tools.")
public class NativeToolsCompilation implements Callable<Integer> {

    @CommandLine.Option(names = {"--platform"},description = "Platform to install tools for. Can specify more than once for different toolsets.",required = true)
    private String[] platforms;

    /*
    TODO; each module should have a download url, command to install/extract directory
    file name when downloading.
    Implementation is as follows:
    1. Each dependency's properties can be specified in the following format: module.property.
    2. If no properties are found, load the dependency from module.dependency.properties.
    3. Ensure to load an optional modulename.dependencies as a CSV from the file if the property is found.
    This will mean checking for the dependencies if they are installed and then if not potentially installing them.

     */
    /**
     *
     * @param downloadUrl
     * @param fileName
     * @param directory
     * @throws Exception
     */
    private void downloadAndExtract(String downloadUrl,String fileName,String directory) throws Exception {
        //TODO: potentially add versions to allow users to control which version is used

        File archive = InstallMain.downloadAndLoadFrom(downloadUrl,fileName,false);
        ArchiveUtils.unzipFileTo(archive.getAbsolutePath(),directory,true);
    }

    private void runCmakeInstall() {
        // curl -fsSL http://cmake.org/files/v3.19/cmake-3.19.0.tar.gz | tar xz && cd cmake-3.19.0 && \
        //                              ./configure --prefix=/opt/cmake && make -j2 && make install && cd .. && rm -r cmake-3.19.0
    }


    @Override
    public Integer call() throws Exception {
        /**
         * Managed tools:
         * cmake
         * make
         * cross compilation tools
         * gcc
         * ncc?
         * various cuda installs
         * Current idea:
         * put all under .kompile directory and auto set paths for compilation.
         * Set PATH to .kompile then run build.
         *
         *
         * Another idea:
         * software suites: dedicated versions of cmake, make,gcc, cross compilation per configuration.
         * Configurations would be per classifier and download specific versions of software based on
         * the needed outputs. Only covers linux for now.
         * User would call something like:
         * ./kompile install native-tools --platform=linux-x86_64 --platform=linux-arm64 --platform=android-arm64 ...
         *
         * Then for building, the CLI would default to the managed environment rather than whatever is on the OS.
         */
      /*
       64 bit


       FROM ubuntu:18.04
        RUN apt-get update && apt-get -yq  install git cmake wget unzip openjdk-11-jdk curl python3
        RUN    apt-get -yq  update && apt-get install -y build-essential unzip libssl-dev  && \
        curl -fsSL http://cmake.org/files/v3.19/cmake-3.19.0.tar.gz | tar xz && cd cmake-3.19.0 && \
                              ./configure --prefix=/opt/cmake && make -j2 && make install && cd .. && rm -r cmake-3.19.0

        RUN    curl -fsSL https://github.com/google/protobuf/releases/download/v3.8.0/protobuf-cpp-3.8.0.tar.gz \
                                   | tar xz && \
        cd protobuf-3.8.0 && \
                                   ./configure --prefix=/opt/protobuf && \
        make -j2 && \
        make install && \
        cd .. && \
        rm -rf protobuf-3.8.0
        RUN cd deeplearning4j && mkdir openblas_home  && \
        wget https://repo1.maven.org/maven2/org/bytedeco/openblas/0.3.19-1.5.7/openblas-0.3.19-1.5.7-android-arm64.jar && \
        unzip openblas-0.3.19-1.5.7-android-arm64.jar
        ENV OPENBLAS_PATH=/root/deeplearning4j/openblas_home/lib/arm64-v8a
        ENV PATH=/root/mvn/bin:/opt/protobuf/bin:/opt/cmake/bin:${PATH}
        ENV NDK_VERSION=r21d
        ENV CURRENT_TARGET=android-arm64
        ENV LIBND4J_CLASSIFIER=android-arm64
        ENV MODULES="-Dlibnd4j.operations='softmax;add,matmul' -Dlibnd4j.datatypes=float -Dlibnd4j.lto=ON"
        RUN cd /root/deeplearning4j && ./libnd4j/pi_build.sh*/



      /*
      32 bit

      FROM ubuntu:18.04
        RUN apt-get update && apt-get -yq  install git  wget unzip openjdk-11-jdk curl python3
        RUN    apt-get -yq  update && apt-get install -y build-essential unzip libssl-dev  && \
        curl -fsSL http://cmake.org/files/v3.19/cmake-3.19.0.tar.gz | tar xz && cd cmake-3.19.0 && \
                              ./configure --prefix=/opt/cmake && make -j2 && make install && cd .. && rm -r cmake-3.19.0

        RUN    curl -fsSL https://github.com/google/protobuf/releases/download/v3.8.0/protobuf-cpp-3.8.0.tar.gz \
                                   | tar xz && \
        cd protobuf-3.8.0 && \
                                   ./configure --prefix=/opt/protobuf && \
        make -j2 && \
        make install && \
        cd .. && \
        rm -rf protobuf-3.8.0
        RUN wget  https://dlcdn.apache.org/maven/maven-3/3.8.6/binaries/apache-maven-3.8.6-bin.tar.gz && tar xvf apache-maven-3.8.6-bin.tar.gz && mv apache-maven-3.8.6 /root/mvn
        RUN cd /root && git clone https://github.com/deeplearning4j/deeplearning4j
        RUN cd /root/deeplearning4j && mkdir openblas_home  && cd openblas_home &&  \
        wget https://repo1.maven.org/maven2/org/bytedeco/openblas/0.3.19-1.5.7/openblas-0.3.19-1.5.7-android-arm.jar && \
        unzip openblas-0.3.19-1.5.7-android-arm.jar

        ENV OPENBLAS_PATH=/root/deeplearning4j/openblas_home/lib/armeabi-v7a
        ENV BUILD_USING_MAVEN=1
        ENV PATH=/root/mvn/bin:/opt/protobuf/bin:/opt/cmake/bin:${PATH}
        ENV NDK_VERSION=r21d
        ENV CURRENT_TARGET=android-arm
        ENV LIBND4J_CLASSIFIER=android-arm
        ENV MODULES="-Dlibnd4j.operations='softmax;add,matmul' -Dlibnd4j.datatypes=float -Dlibnd4j.lto=ON"
        RUN cd /root/deeplearning4j/libnd4j && ./pi_build.sh*/


              /*
      x86 32 bit

      FROM ubuntu:18.04
        RUN apt-get update && apt-get -yq  install git  wget unzip openjdk-11-jdk curl python3
        RUN    apt-get -yq  update && apt-get install -y build-essential unzip libssl-dev  && \
        curl -fsSL http://cmake.org/files/v3.19/cmake-3.19.0.tar.gz | tar xz && cd cmake-3.19.0 && \
                              ./configure --prefix=/opt/cmake && make -j2 && make install && cd .. && rm -r cmake-3.19.0

        RUN    curl -fsSL https://github.com/google/protobuf/releases/download/v3.8.0/protobuf-cpp-3.8.0.tar.gz \
                                   | tar xz && \
        cd protobuf-3.8.0 && \
                                   ./configure --prefix=/opt/protobuf && \
        make -j2 && \
        make install && \
        cd .. && \
        rm -rf protobuf-3.8.0
        RUN wget  https://dlcdn.apache.org/maven/maven-3/3.8.6/binaries/apache-maven-3.8.6-bin.tar.gz && tar xvf apache-maven-3.8.6-bin.tar.gz && mv apache-maven-3.8.6 /root/mvn
        RUN cd /root && git clone https://github.com/deeplearning4j/deeplearning4j
        RUN cd /root/deeplearning4j && mkdir openblas_home  && cd openblas_home &&  \
        wget https://repo1.maven.org/maven2/org/bytedeco/openblas/0.3.19-1.5.7/openblas-0.3.19-1.5.7-android-x86.jar && \
        unzip openblas-0.3.19-1.5.7-android-x86.jar

        ENV OPENBLAS_PATH=/root/deeplearning4j/openblas_home/lib/x86
        ENV BUILD_USING_MAVEN=1
        ENV PATH=/root/mvn/bin:/opt/protobuf/bin:/opt/cmake/bin:${PATH}
        ENV NDK_VERSION=r21d
        ENV CURRENT_TARGET=android-x86
        ENV LIBND4J_CLASSIFIER=android-x86
        ENV MODULES="-Dlibnd4j.operations='softmax;add,matmul' -Dlibnd4j.datatypes=float -Dlibnd4j.lto=ON"
        RUN cd /root/deeplearning4j/libnd4j && ./pi_build.sh*/

               /*
      android x86 64 bit

      FROM ubuntu:18.04
        RUN apt-get update && apt-get -yq  install git  wget unzip openjdk-11-jdk curl python3
        RUN    apt-get -yq  update && apt-get install -y build-essential unzip libssl-dev  && \
        curl -fsSL http://cmake.org/files/v3.19/cmake-3.19.0.tar.gz | tar xz && cd cmake-3.19.0 && \
                              ./configure --prefix=/opt/cmake && make -j2 && make install && cd .. && rm -r cmake-3.19.0

        RUN    curl -fsSL https://github.com/google/protobuf/releases/download/v3.8.0/protobuf-cpp-3.8.0.tar.gz \
                                   | tar xz && \
        cd protobuf-3.8.0 && \
                                   ./configure --prefix=/opt/protobuf && \
        make -j2 && \
        make install && \
        cd .. && \
        rm -rf protobuf-3.8.0
        RUN wget  https://dlcdn.apache.org/maven/maven-3/3.8.6/binaries/apache-maven-3.8.6-bin.tar.gz && tar xvf apache-maven-3.8.6-bin.tar.gz && mv apache-maven-3.8.6 /root/mvn
        RUN cd /root && git clone https://github.com/deeplearning4j/deeplearning4j
        RUN cd /root/deeplearning4j && mkdir openblas_home  && cd openblas_home &&  \
        wget https://repo1.maven.org/maven2/org/bytedeco/openblas/0.3.19-1.5.7/openblas-0.3.19-1.5.7-android-x86_64.jar && \
        unzip openblas-0.3.19-1.5.7-android-x86_64.jar

        ENV OPENBLAS_PATH=/root/deeplearning4j/openblas_home/lib/x86_64
        ENV BUILD_USING_MAVEN=1
        ENV PATH=/root/mvn/bin:/opt/protobuf/bin:/opt/cmake/bin:${PATH}
        ENV NDK_VERSION=r21d
        ENV CURRENT_TARGET=android-x86_64
        ENV LIBND4J_CLASSIFIER=android-x86_64
        ENV MODULES="-Dlibnd4j.operations='softmax;add,matmul' -Dlibnd4j.datatypes=float -Dlibnd4j.lto=ON"
        RUN cd /root/deeplearning4j/libnd4j && ./pi_build.sh*/



               /*
      linux 32 arm bit

      FROM ubuntu:18.04
        RUN apt-get update && apt-get -yq  install git  wget unzip openjdk-11-jdk curl python3
        RUN    apt-get -yq  update && apt-get install -y build-essential unzip libssl-dev  && \
        curl -fsSL http://cmake.org/files/v3.19/cmake-3.19.0.tar.gz | tar xz && cd cmake-3.19.0 && \
                              ./configure --prefix=/opt/cmake && make -j2 && make install && cd .. && rm -r cmake-3.19.0

        RUN    curl -fsSL https://github.com/google/protobuf/releases/download/v3.8.0/protobuf-cpp-3.8.0.tar.gz \
                                   | tar xz && \
        cd protobuf-3.8.0 && \
                                   ./configure --prefix=/opt/protobuf && \
        make -j2 && \
        make install && \
        cd .. && \
        rm -rf protobuf-3.8.0
        RUN wget  https://dlcdn.apache.org/maven/maven-3/3.8.6/binaries/apache-maven-3.8.6-bin.tar.gz && tar xvf apache-maven-3.8.6-bin.tar.gz && mv apache-maven-3.8.6 /root/mvn
        RUN cd /root && git clone https://github.com/deeplearning4j/deeplearning4j
        RUN cd /root/deeplearning4j && mkdir openblas_home  && cd openblas_home &&  \
        wget https://repo1.maven.org/maven2/org/bytedeco/openblas/0.3.19-1.5.7/openblas-0.3.19-1.5.7-linux-armhf.jar && \
        unzip openblas-0.3.19-1.5.7-linux-armhf.jar

        ENV OPENBLAS_PATH=/root/deeplearning4j/openblas_home/lib/linux-armhf
        ENV BUILD_USING_MAVEN=1
        ENV PATH=/root/mvn/bin:/opt/protobuf/bin:/opt/cmake/bin:${PATH}
        ENV CURRENT_TARGET=linux-armhf
        ENV LIBND4J_CLASSIFIER=linux-armhf
        ENV MODULES="-Dlibnd4j.operations='softmax;add,matmul' -Dlibnd4j.datatypes=float -Dlibnd4j.lto=ON"
        RUN cd /root/deeplearning4j/libnd4j && ./pi_build.sh*/




          /*
      linux 64 arm bit

      FROM ubuntu:18.04
        RUN apt-get update && apt-get -yq  install git  wget unzip openjdk-11-jdk curl python3
        RUN    apt-get -yq  update && apt-get install -y build-essential unzip libssl-dev  && \
        curl -fsSL http://cmake.org/files/v3.19/cmake-3.19.0.tar.gz | tar xz && cd cmake-3.19.0 && \
                              ./configure --prefix=/opt/cmake && make -j2 && make install && cd .. && rm -r cmake-3.19.0

        RUN    curl -fsSL https://github.com/google/protobuf/releases/download/v3.8.0/protobuf-cpp-3.8.0.tar.gz \
                                   | tar xz && \
        cd protobuf-3.8.0 && \
                                   ./configure --prefix=/opt/protobuf && \
        make -j2 && \
        make install && \
        cd .. && \
        rm -rf protobuf-3.8.0
        RUN wget  https://dlcdn.apache.org/maven/maven-3/3.8.6/binaries/apache-maven-3.8.6-bin.tar.gz && tar xvf apache-maven-3.8.6-bin.tar.gz && mv apache-maven-3.8.6 /root/mvn
        RUN cd /root && git clone https://github.com/deeplearning4j/deeplearning4j
        RUN cd /root/deeplearning4j && mkdir openblas_home  && cd openblas_home &&  \
        wget https://repo1.maven.org/maven2/org/bytedeco/openblas/0.3.19-1.5.7/openblas-0.3.19-1.5.7-linux-arm64.jar && \
        unzip openblas-0.3.19-1.5.7-linux-arm64.jar

        ENV OPENBLAS_PATH=/root/deeplearning4j/openblas_home/lib/linux-arm64
        ENV BUILD_USING_MAVEN=1
        ENV PATH=/root/mvn/bin:/opt/protobuf/bin:/opt/cmake/bin:${PATH}
        ENV CURRENT_TARGET=linux-arm64
        ENV LIBND4J_CLASSIFIER=linux-arm64
        ENV MODULES="-Dlibnd4j.operations='softmax;add,matmul' -Dlibnd4j.datatypes=float -Dlibnd4j.lto=ON"
        RUN cd /root/deeplearning4j/libnd4j && ./pi_build.sh*/




          /*
      linux jetson nano arm bit

      FROM ubuntu:18.04
        RUN apt-get update && apt-get -yq  install git  wget unzip openjdk-11-jdk curl python3
        RUN    apt-get -yq  update && apt-get install -y build-essential unzip libssl-dev  && \
        curl -fsSL http://cmake.org/files/v3.19/cmake-3.19.0.tar.gz | tar xz && cd cmake-3.19.0 && \
                              ./configure --prefix=/opt/cmake && make -j2 && make install && cd .. && rm -r cmake-3.19.0

        RUN    curl -fsSL https://github.com/google/protobuf/releases/download/v3.8.0/protobuf-cpp-3.8.0.tar.gz \
                                   | tar xz && \
        cd protobuf-3.8.0 && \
                                   ./configure --prefix=/opt/protobuf && \
        make -j2 && \
        make install && \
        cd .. && \
        rm -rf protobuf-3.8.0


        ENV BUILD_USING_MAVEN=1
        ENV PATH=/root/mvn/bin:/opt/protobuf/bin:/opt/cmake/bin:${PATH}
        ENV CUDA_VER=10.2
        ENV CURRENT_TARGET=jetson-arm64
        ENV LIBND4J_CLASSIFIER=linux-arm64
        ENV MODULES="-Dlibnd4j.operations='softmax;add,matmul' -Dlibnd4j.datatypes=float -Dlibnd4j.lto=ON"
        RUN cd /root/deeplearning4j/libnd4j && ./pi_build.sh*/


        return null;
    }
}
