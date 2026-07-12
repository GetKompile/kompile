# CUDA build image — one tag per CUDA line, selected via BASE_IMAGE
# (nvidia/cuda:12.6.3-cudnn-devel-ubuntu22.04, 12.9.1-cudnn-devel, ...).
# The toolkit version MUST match the target's change-cuda-versions.sh value.
ARG BASE_IMAGE=nvidia/cuda:12.9.1-cudnn-devel-ubuntu22.04
FROM ${BASE_IMAGE}
ARG GRAALVM_ARCHIVE_URL
ARG JDK11_ARCHIVE_URL
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
      build-essential gfortran git curl wget unzip zip tar patch perl python3 ruby \
      maven cmake protobuf-compiler ccache jq awscli \
      zlib1g-dev libdw-dev libelf-dev libdwarf-dev ca-certificates \
      libvulkan1 libvulkan-dev vulkan-tools glslang-tools spirv-tools \
    && rm -rf /var/lib/apt/lists/*
RUN curl -fsSL "${GRAALVM_ARCHIVE_URL}" -o /tmp/graal.tgz \
    && mkdir -p /opt/graalvm && tar -xzf /tmp/graal.tgz -C /opt/graalvm --strip-components=1 \
    && curl -fsSL "${JDK11_ARCHIVE_URL}" -o /tmp/jdk.tgz \
    && mkdir -p /opt/jdk11 && tar -xzf /tmp/jdk.tgz -C /opt/jdk11 --strip-components=1 \
    && rm /tmp/*.tgz
ENV GRAALVM_HOME=/opt/graalvm JAVA11_HOME=/opt/jdk11 CUDA_HOME=/usr/local/cuda CCACHE_DIR=/root/.ccache
RUN nvcc --version && /opt/graalvm/bin/native-image --version && /opt/jdk11/bin/java -version \
    && aws --version && jq --version
