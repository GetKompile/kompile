# TPU/PJRT build image: standard Linux toolchain plus an optional PJRT plugin
# library (libtpu or another PJRT .so) for runtime experiments. Compilation of
# the nd4j-tpu backend does not require TPU hardware.
ARG BASE_IMAGE=public.ecr.aws/amazonlinux/amazonlinux:2023
FROM ${BASE_IMAGE}
ARG GRAALVM_ARCHIVE_URL
ARG JDK11_ARCHIVE_URL
ARG PJRT_PLUGIN_URL=
RUN dnf install -y gcc gcc-c++ gcc-gfortran make git tar gzip unzip zip curl wget which findutils \
      patch diffutils perl python3 python3-pip ruby maven cmake protobuf-compiler ccache jq \
      libarchive-devel zlib-devel libuuid-devel libxml2-devel elfutils-libelf-devel \
    && (dnf install -y awscli-2 || pip3 install --no-cache-dir awscli) \
    && dnf clean all
RUN curl -fsSL "${GRAALVM_ARCHIVE_URL}" -o /tmp/graal.tgz \
    && mkdir -p /opt/graalvm && tar -xzf /tmp/graal.tgz -C /opt/graalvm --strip-components=1 \
    && curl -fsSL "${JDK11_ARCHIVE_URL}" -o /tmp/jdk.tgz \
    && mkdir -p /opt/jdk11 && tar -xzf /tmp/jdk.tgz -C /opt/jdk11 --strip-components=1 \
    && rm /tmp/*.tgz
RUN mkdir -p /opt/pjrt \
    && if [ -n "${PJRT_PLUGIN_URL}" ]; then curl -fsSL "${PJRT_PLUGIN_URL}" -o /opt/pjrt/libtpu.so; fi
ENV GRAALVM_HOME=/opt/graalvm JAVA11_HOME=/opt/jdk11 \
    PJRT_LIBRARY_PATH=/opt/pjrt/libtpu.so CCACHE_DIR=/root/.ccache
RUN /opt/jdk11/bin/java -version && mvn --version && cmake --version
