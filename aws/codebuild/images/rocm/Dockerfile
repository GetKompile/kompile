# AMD image: ROCm userland plus a CUDA toolkit so ZLUDA targets can compile
# CUDA sources (compilation needs nvcc, not a physical GPU). ZLUDA itself is
# an optional archive; without it the image still builds ROCm/HIP lanes.
ARG BASE_IMAGE=rocm/dev-ubuntu-22.04:6.4-complete
FROM ${BASE_IMAGE}
ARG GRAALVM_ARCHIVE_URL
ARG JDK11_ARCHIVE_URL
ARG CUDA_TOOLKIT_VERSION=12.9
# ZLUDA bootstrap mirrors DL4J's build-deploy-linux-zluda.yml: a pinned
# vosen/ZLUDA release tag, linux asset resolved via the GitHub API (asset
# names vary per release), libcuda.so verified after extraction.
# ZLUDA_ARCHIVE_URL overrides the resolved URL; empty ZLUDA_VERSION disables.
ARG ZLUDA_VERSION=v6
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
RUN set -e; mkdir -p /opt/zluda; url="${ZLUDA_ARCHIVE_URL}"; \
    if [ -z "$url" ] && [ -n "${ZLUDA_VERSION}" ]; then \
      url="$(curl -fsSL "https://api.github.com/repos/vosen/ZLUDA/releases/tags/${ZLUDA_VERSION}" \
        | grep -o '"browser_download_url": *"[^"]*linux[^"]*"' | head -n1 | sed 's/.*"\(https[^"]*\)"/\1/')"; \
      [ -n "$url" ] || { echo "no linux asset on vosen/ZLUDA ${ZLUDA_VERSION}" >&2; exit 1; }; \
    fi; \
    if [ -n "$url" ]; then \
      echo "ZLUDA: $url"; \
      curl -fsSL "$url" -o /tmp/zluda.tar.gz; \
      tar -xzf /tmp/zluda.tar.gz -C /opt/zluda; rm /tmp/zluda.tar.gz; \
      if [ -z "$(find /opt/zluda -maxdepth 1 -name 'libcuda.so*')" ]; then \
        sub="$(find /opt/zluda -maxdepth 1 -mindepth 1 -type d | head -n1)"; \
        if [ -n "$sub" ]; then mv "$sub"/* /opt/zluda/ && rmdir "$sub" 2>/dev/null || true; fi; \
      fi; \
      find /opt/zluda -name 'libcuda.so*' | grep -q . \
        || { echo "libcuda.so missing from ZLUDA archive" >&2; exit 1; }; \
    fi
# setup_zluda() in libnd4j reads ZLUDA_PATH; ZLUDA_HOME kept for kit config.
ENV GRAALVM_HOME=/opt/graalvm JAVA11_HOME=/opt/jdk11 ROCM_HOME=/opt/rocm \
    ZLUDA_HOME=/opt/zluda ZLUDA_PATH=/opt/zluda CUDA_HOME=/usr/local/cuda CCACHE_DIR=/root/.ccache
RUN nvcc --version && test -d /opt/rocm && /opt/jdk11/bin/java -version && jq --version
