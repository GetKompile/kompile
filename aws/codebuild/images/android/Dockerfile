# Android cross-build image (NDK builds run on linux-x86_64 hosts).
ARG BASE_IMAGE=public.ecr.aws/amazonlinux/amazonlinux:2023
FROM ${BASE_IMAGE}
ARG GRAALVM_ARCHIVE_URL
ARG JDK11_ARCHIVE_URL
# NDK direct-download mirrors DL4J's android workflows (no sdkmanager):
# https://dl.google.com/android/repository/android-ndk-<ver>-linux.zip
ARG ANDROID_NDK_VERSION=r27d
RUN dnf install -y gcc gcc-c++ make git curl wget unzip zip tar which findutils patch diffutils \
      maven cmake protobuf-compiler ccache jq python3 python3-pip ruby zlib-devel \
    && (dnf install -y awscli-2 || pip3 install --no-cache-dir awscli) \
    && dnf clean all
RUN curl -fsSL "${GRAALVM_ARCHIVE_URL}" -o /tmp/graal.tgz \
    && mkdir -p /opt/graalvm && tar -xzf /tmp/graal.tgz -C /opt/graalvm --strip-components=1 \
    && curl -fsSL "${JDK11_ARCHIVE_URL}" -o /tmp/jdk.tgz \
    && mkdir -p /opt/jdk11 && tar -xzf /tmp/jdk.tgz -C /opt/jdk11 --strip-components=1 \
    && rm /tmp/*.tgz
RUN curl -fsSL "https://dl.google.com/android/repository/android-ndk-${ANDROID_NDK_VERSION}-linux.zip" \
      -o /tmp/ndk.zip \
    && unzip -q /tmp/ndk.zip -d /opt && rm /tmp/ndk.zip \
    && ln -s "/opt/android-ndk-${ANDROID_NDK_VERSION}" /opt/android-ndk
ENV ANDROID_NDK_HOME=/opt/android-ndk NDK_HOME=/opt/android-ndk \
    GRAALVM_HOME=/opt/graalvm JAVA11_HOME=/opt/jdk11 CCACHE_DIR=/root/.ccache
RUN /opt/jdk11/bin/java -version && test -d /opt/android-ndk
