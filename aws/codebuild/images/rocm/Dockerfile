# AMD image: ROCm userland plus a CUDA toolkit so ZLUDA targets can compile
# CUDA sources (compilation needs nvcc, not a physical GPU). ZLUDA itself is
# an optional archive; without it the image still builds ROCm/HIP lanes.
ARG BASE_IMAGE=rocm/dev-ubuntu-22.04:6.4-complete
FROM ${BASE_IMAGE}
ARG GRAALVM_ARCHIVE_URL
ARG JDK11_ARCHIVE_URL
ARG CUDA_TOOLKIT_VERSION=12.9
ARG ZLUDA_ARCHIVE_URL=
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
      build-essential gfortran git curl wget unzip zip tar patch perl python3 ruby \
      maven cmake protobuf-compiler ccache jq awscli \
      zlib1g-dev libdw-dev libelf-dev ca-certificates gnupg \
    && rm -rf /var/lib/apt/lists/*
RUN curl -fsSL https://developer.download.nvidia.com/compute/cuda/repos/ubuntu2204/x86_64/cuda-keyring_1.1-1_all.deb \
      -o /tmp/cuda-keyring.deb \
    && dpkg -i /tmp/cuda-keyring.deb && rm /tmp/cuda-keyring.deb \
    && apt-get update \
    && apt-get install -y --no-install-recommends "cuda-toolkit-$(echo "${CUDA_TOOLKIT_VERSION}" | tr . -)" \
    && rm -rf /var/lib/apt/lists/*
RUN curl -fsSL "${GRAALVM_ARCHIVE_URL}" -o /tmp/graal.tgz \
    && mkdir -p /opt/graalvm && tar -xzf /tmp/graal.tgz -C /opt/graalvm --strip-components=1 \
    && curl -fsSL "${JDK11_ARCHIVE_URL}" -o /tmp/jdk.tgz \
    && mkdir -p /opt/jdk11 && tar -xzf /tmp/jdk.tgz -C /opt/jdk11 --strip-components=1 \
    && rm /tmp/*.tgz
RUN mkdir -p /opt/zluda \
    && if [ -n "${ZLUDA_ARCHIVE_URL}" ]; then \
         curl -fsSL "${ZLUDA_ARCHIVE_URL}" -o /tmp/zluda.tar.gz \
         && tar -xzf /tmp/zluda.tar.gz -C /opt/zluda --strip-components=1 \
         && rm /tmp/zluda.tar.gz; \
       fi
ENV GRAALVM_HOME=/opt/graalvm JAVA11_HOME=/opt/jdk11 ROCM_HOME=/opt/rocm \
    ZLUDA_HOME=/opt/zluda CUDA_HOME=/usr/local/cuda CCACHE_DIR=/root/.ccache
RUN nvcc --version && test -d /opt/rocm && /opt/jdk11/bin/java -version && jq --version
