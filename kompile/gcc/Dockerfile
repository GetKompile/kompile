ARG CENTOS_VERSION=7
ARG OS_NAME=centos
FROM ${OS_NAME}:${CENTOS_VERSION}
ARG GLIBC_VERSION=2.17
ARG GCC_VERSION=4.9.4
ENV PATH=/glibc-${GLIBC_VERSION}-build-output/bin:/gcc-${GCC_VERSION}-build-output/bin:${PATH}
ENV LD_LIBRARY_PATH=/glibc-${GLIBC_VERSION}-build-output/lib64:/gcc-${GCC_VERSION}-build-output/lib64
ENV JAVA_HOME=/usr/java/
ENV GRAALVM_HOME=/usr/java/
RUN yum -y install wget
ADD bootstrap-gcc-glibc.sh /bootstrap-gcc-glibc.sh
RUN chmod +x /bootstrap-gcc-glibc.sh && /bootstrap-gcc-glibc.sh --gcc-version ${GCC_VERSION} --glibc-version ${GLIBC_VERSION}
