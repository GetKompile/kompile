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
RUN cd /root && git clone https://github.com/eclipse/deeplearning4j --branch ag_importcache_graalvm
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
RUN cd /root/deeplearning4j/libnd4j && ./pi_build.sh