FROM ghcr.io/graalvm/graalvm-ce:20.3.6
ENV JAVA_HOME=/usr/java/latest
ENV GRAALVM_HOME=/usr/java/latest
RUN microdnf install git gcc cmake
RUN /usr/java/latest/bin/gu install native-image
RUN mkdir /kompile
RUN curl https://dlcdn.apache.org/maven/maven-3/3.8.6/binaries/apache-maven-3.8.6-bin.tar.gz --output /kompile/mvn.tar.gz
RUN cd /kompile/ && tar xvf mvn.tar.gz && mv apache-maven-3.8.6 mvn
ENV PATH="/kompile/mvn/bin/:${PATH}"
ARG BACKEND_PROFILE=cpu
ARG JAVCPP_PLATFORM=linux-x86_64
ARG DL4J_BACKEND=1.0.0-SNAPSHOT
RUN cd /kompile && git clone https://github.com/eclipse/deeplearning4j
RUN cd /kompile/deeplearning4j/libnd4j && mvn -Djavacpp.platform=${JAVCPP_PLATFORM} clean install -Dmaven.test.skip=true
RUN cd /kompile/deeplearning4j && cd nd4j && mvn -P${BACKEND_PROFILE} -Djavacpp.platform=${JAVCPP_PLATFORM}  install -Dmaven.test.skip=true
RUN cd /kompile/deeplearning4j && cd datavec && mvn -Djavacpp.platform=${JAVCPP_PLATFORM} install -Dmaven.test.skip=true
RUN cd /kompile/deeplearning4j && cd python4j && mvn -Djavacpp.platform=${JAVCPP_PLATFORM}  install -Dmaven.test.skip=true
RUN cd /kompile/deeplearning4j && cd deeplearning4j && mvn -pl :deeplearning4j-modelimport   install -Dmaven.test.skip=true --also-make -Djavacpp.platform=linux-x86_64
RUN cd /kompile && git clone https://github.com/KonduitAI/konduit-serving
RUN cd /kompile/konduit-serving && mvn -Ddl4j.version=1.0.0-SNAPSHOT -Djavacpp.platform=${JAVCPP_PLATFORM} -Dchip=cpu clean install -Dmaven.test.skip=true
COPY ./kompile-c-library /kompile/kompile-c-library
COPY ./kompile-python /kompile/kompile-python
COPY ./src /kompile/src
COPY ./pipeline /kompile/pipeline
COPY pom.xml /kompile/pom.xml
RUN cd /kompile && mvn -Pnative clean package
RUN mv /kompile/target/kompile /kompile
RUN chmod +x /kompile/kompile
RUN rm -rf /kompile/deeplearning4j /kompile/konduit-serving
RUN microdnf install wget
RUN cd /kompile && wget https://repo.anaconda.com/miniconda/Miniconda3-py39_4.9.2-Linux-x86_64.sh
RUN mv /kompile/Miniconda3-py39_4.9.2-Linux-x86_64.sh /kompile/miniconda3.sh
RUN chmod +x /kompile/miniconda3.sh &&  /kompile/miniconda3.sh -b -p /kompile/miniconda3
RUN /kompile/miniconda3/bin/pip install Cython numpy
ENV PATH="/kompile/miniconda3/bin:${PATH}"
