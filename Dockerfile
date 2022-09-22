FROM rockylinux/rockylinux:8.4 AS builder
LABEL org.opencontainers.image.source="https://github.com/KonduitAI/kompile"
RUN yum -y install wget  && wget https://github.com/graalvm/graalvm-ce-dev-builds/releases/download/22.3.0-dev-20220915_2039/graalvm-ce-java11-linux-amd64-dev.tar.gz && tar xvf graalvm-ce-java11-linux-amd64-dev.tar.gz && mv graalvm-ce-java11-22.3.0-dev/ /usr/java

ENV JAVA_HOME=/usr/java/
ENV GRAALVM_HOME=/usr/java/
RUN yum -y install git gcc gcc-c++ cmake zlib* xz
RUN /usr/java/bin/gu install native-image
RUN mkdir /kompile
RUN curl https://dlcdn.apache.org/maven/maven-3/3.8.6/binaries/apache-maven-3.8.6-bin.tar.gz --output /kompile/mvn.tar.gz
RUN cd /kompile/ && tar xvf mvn.tar.gz && mv apache-maven-3.8.6 mvn
ENV PATH="/kompile/mvn/bin/:/root/.kompile/python/bin/:${PATH}:/usr/java/bin/"
ARG BACKEND_PROFILE=cpu
ARG JAVCPP_PLATFORM=linux-x86_64
ARG DL4J_BACKEND=1.0.0-SNAPSHOT
ARG LTO=OFF
COPY ./kompile-c-library /kompile/kompile-c-library
COPY ./kompile-python /kompile/kompile-python
COPY ./src /kompile/src
ENV KOMPILE_PREFIX=/kompile
COPY pom.xml /kompile/pom.xml

RUN cd /kompile && git clone  https://github.com/eclipse/deeplearning4j && \
    cd /kompile/deeplearning4j && cd libnd4j && mvn -P${BACKEND_PROFILE} -Dlibnd4j.lto=${LTO} -Djavacpp.platform=${JAVCPP_PLATFORM}  install -Dmaven.test.skip=true && \
    cd /kompile/deeplearning4j && cd nd4j && mvn -P${BACKEND_PROFILE} -Djavacpp.platform=${JAVCPP_PLATFORM}  install -Dmaven.test.skip=true && \cd /kompile/deeplearning4j && cd nd4j && mvn -P${BACKEND_PROFILE} -Djavacpp.platform=${JAVCPP_PLATFORM}  install -Dmaven.test.skip=true && \
    cd /kompile/deeplearning4j && cd datavec && mvn -Djavacpp.platform=${JAVCPP_PLATFORM} install -Dmaven.test.skip=true && \
    cd /kompile/deeplearning4j && cd python4j && mvn -Djavacpp.platform=${JAVCPP_PLATFORM}  install -Dmaven.test.skip=true && \
    cd /kompile/deeplearning4j && cd deeplearning4j && mvn -pl :deeplearning4j-modelimport   install -Dmaven.test.skip=true --also-make -Djavacpp.platform=linux-x86_64 && \
    cd /kompile && git clone https://github.com/KonduitAI/konduit-serving  && \
    cd /kompile/konduit-serving && mvn -Ddl4j.version=1.0.0-SNAPSHOT -Djavacpp.platform=${JAVCPP_PLATFORM} -Dchip=cpu clean install -Dmaven.test.skip=true && \
    cd /kompile && mvn -Djavacpp.platform=linux-x86_64 -Pnative clean package -Dmaven.test.skip=true &&\
    mv /kompile/target/kompile /kompile && \
    chmod +x /kompile/kompile && \
    rm -rf /kompile/deeplearning4j /kompile/konduit-serving && \
   chmod -R 755 /kompile && \
    rm -rf /root/* && \
     rm -rf /kompile/mvn \
               /kompile/mvn.tar.gz \
              /kompile/miniconda3 \
              /kompile/miniconda3.sh \
              /kompile/target \
              /root/.javacpp \
              /root/.conda \
              /root/.m2 \
              /kompile/src \
              /kompile/pom.xml


FROM rockylinux:8.5
RUN mkdir /kompile && yum -y install sed findutils
COPY --from=builder /kompile/kompile /kompile/kompile
COPY --from=builder /kompile/kompile-c-library /kompile/kompile-c-library
COPY --from=builder /kompile/kompile-python /kompile/kompile-python
COPY   /src/main/resources/META-INF/native-image /kompile/native-image
ENV JAVA_HOME=/root/.kompile/graalvm
RUN yum -y install git gcc gcc-c++ cmake zlib zlib-devel xz
ENV PATH="/root/.kompile/mvn/bin/:/root/.kompile/python/bin/:${PATH}:/usr/java/bin/:/root/.kompile/graalvm/bin"
ENTRYPOINT ["/kompile/kompile"]