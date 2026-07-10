# Hexagon build image. The Hexagon SDK is Qualcomm-proprietary and cannot be
# fetched anonymously: HEXAGON_SDK_ARCHIVE_URL must point at a private or
# presigned archive (zip or tar.gz) you are licensed to use.
ARG BASE_IMAGE=public.ecr.aws/amazonlinux/amazonlinux:2023
FROM ${BASE_IMAGE}
ARG GRAALVM_ARCHIVE_URL
ARG JDK11_ARCHIVE_URL
ARG HEXAGON_SDK_ARCHIVE_URL
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
RUN test -n "${HEXAGON_SDK_ARCHIVE_URL}" \
    && curl -fsSL "${HEXAGON_SDK_ARCHIVE_URL}" -o /tmp/hexagon-sdk \
    && mkdir -p /opt/hexagon \
    && { unzip -q /tmp/hexagon-sdk -d /opt/hexagon 2>/dev/null \
         || tar -xzf /tmp/hexagon-sdk -C /opt/hexagon --strip-components=1; } \
    && rm /tmp/hexagon-sdk
ENV GRAALVM_HOME=/opt/graalvm JAVA11_HOME=/opt/jdk11 \
    HEXAGON_SDK_ROOT=/opt/hexagon CCACHE_DIR=/root/.ccache
RUN /opt/jdk11/bin/java -version && test -d /opt/hexagon
