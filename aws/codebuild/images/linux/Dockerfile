# Linux build image (also cross-built for linux/arm64 with arm archive URLs).
ARG BASE_IMAGE=public.ecr.aws/amazonlinux/amazonlinux:2023
FROM ${BASE_IMAGE}
ARG GRAALVM_ARCHIVE_URL
ARG JDK11_ARCHIVE_URL
RUN dnf install -y gcc gcc-c++ gcc-gfortran make git tar gzip unzip zip curl wget which findutils \
      patch diffutils perl python3 python3-pip ruby maven cmake protobuf-compiler ccache jq \
      libarchive-devel zlib-devel libuuid-devel libxml2-devel elfutils-libelf-devel \
    && (dnf install -y awscli-2 || pip3 install --no-cache-dir awscli) \
    && dnf clean all
# Vulkan backend (nd4j-vulkan, ADR-0110): headers/loader/SPIR-V tooling.
# Tolerated if a package is missing on this base — only linux-vulkan needs it.
RUN dnf install -y vulkan-headers vulkan-loader-devel vulkan-tools glslang spirv-tools mesa-vulkan-drivers 2>/dev/null \
    || echo "WARN: vulkan toolchain packages unavailable; the linux-vulkan lane needs them"
RUN test -n "${GRAALVM_ARCHIVE_URL}" && curl -fsSL "${GRAALVM_ARCHIVE_URL}" -o /tmp/graal.tgz \
    && mkdir -p /opt/graalvm && tar -xzf /tmp/graal.tgz -C /opt/graalvm --strip-components=1 && rm /tmp/graal.tgz
RUN test -n "${JDK11_ARCHIVE_URL}" && curl -fsSL "${JDK11_ARCHIVE_URL}" -o /tmp/jdk.tgz \
    && mkdir -p /opt/jdk11 && tar -xzf /tmp/jdk.tgz -C /opt/jdk11 --strip-components=1 && rm /tmp/jdk.tgz
ENV GRAALVM_HOME=/opt/graalvm JAVA11_HOME=/opt/jdk11 CCACHE_DIR=/root/.ccache
RUN /opt/graalvm/bin/native-image --version && /opt/jdk11/bin/java -version \
    && mvn --version && cmake --version && protoc --version && aws --version && jq --version
