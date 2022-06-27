FROM ghcr.io/graalvm/graalvm-ce:20.3.6
# setup NEC environment and so on
#WORK_FOLDER
RUN mkdir /kompile
#YUM
ADD dnf.conf /etc/dnf
RUN microdnf install yum
#NEC SDK
ADD TSUBASA-soft-release-*.noarch.rpm /tmp
ADD TSUBASA-repo.repo /tmp
ADD TSUBASA-restricted.repo /tmp
ARG RELEASE_RPM=/tmp/TSUBASA-soft-release-*.noarch.rpm
RUN yum -y -q install $RELEASE_RPM
RUN cp /tmp/*.repo /etc/yum.repos.d
RUN rm /tmp/*.repo
RUN yum clean all
RUN yum -y -q group install ve-container nec-sdk-devel
#VEDA/AVEO source build environment
RUN yum  -y -q install  systemd-devel libsysve-devel.x86_64 glibc-ve-devel kheaders-ve  veos-devel veos-headers
RUN yum  -y -q install libgcc-ve-static.x86_64
# LICENSE
#PLEASE COPY your license.dat and conf into the folder
RUN  yum -y -q install aurlic-lib
ADD  license.dat /opt/nec/aur_license/license.dat
ADD  aur_license.conf /opt/nec/aur_license/aur_license.conf
#ET CETERA
RUN yum -y -q install git  cmake  wget grep
# JAVA
ENV JAVA_HOME=/usr/java/latest
ENV GRAALVM_HOME=/usr/java/latest
RUN /usr/java/latest/bin/gu install native-image
RUN curl https://dlcdn.apache.org/maven/maven-3/3.8.6/binaries/apache-maven-3.8.6-bin.tar.gz --output /kompile/mvn.tar.gz
RUN cd /kompile/ && tar xvf mvn.tar.gz && mv apache-maven-3.8.6 mvn
ENV PATH="/kompile/mvn/bin/:${PATH}"
RUN cd /kompile && git clone --single-branch  --depth 1 --branch master  https://github.com/eclipse/deeplearning4j
#BUILD Vednn/VEDA accelerator prerequisites
RUN cd /kompile/deeplearning4j/libnd4j
RUN rm -rf /kompile/deeplearning4j/libnd4j/vednn
#BUILD VEDA/AVEO from the source
RUN git clone --single-branch  --depth 1 --branch  v1.2.0 https://github.com/SX-Aurora/veda.git
RUN mkdir -p veda/src/build && cd veda/src/build && cmake .. && make && make install && cd -
#patch to force the installation of the LLVME
RUN sed -i 's|isLLVMVE=|isLLVMVE="is not installed" #|' /kompile/deeplearning4j/libnd4j/build_ve_prerequisites.sh
#remove sudo keywords
RUN sed -i 's|sudo||' /kompile/deeplearning4j/libnd4j/build_ve_prerequisites.sh
RUN bash  /kompile/deeplearning4j/libnd4j/build_ve_prerequisites.sh 1>/dev/null
#install only appropriate modules
RUN sed -i 's|-DskipTests|-DskipTests -pl ":nd4j-native,:nd4j-native-preset,:libnd4j" |' /kompile/deeplearning4j/libnd4j/build_veda.sh
#turn off USE_OLD_VEDA
RUN sed -i 's|option(USE_OLD_VEDA|option(USE_OLD_VEDA "" OFF ) #|' /kompile/deeplearning4j/libnd4j/CMakeLists.txt
RUN bash /kompile/deeplearning4j/libnd4j/build_veda.sh
#CHECK
RUN ls -l /kompile/deeplearning4j/libnd4j/blasbuild/cpu/blas
RUN find /root/.m2 -iname *vednn*.jar
#install Normal dl4j. As its needed alongside
RUN cd /kompile/deeplearning4j/ && mvn clean install -DskipTests -Pcpu
#konduit-server
RUN cd /kompile && git clone https://github.com/KonduitAI/konduit-serving
RUN cd /kompile/konduit-serving && mvn -Ddl4j.version=1.0.0-SNAPSHOT -Dchip=cpu clean install -Dmaven.test.skip=true
USER root