# Android cross-build image (NDK builds run on linux-x86_64 hosts).
ARG BASE_IMAGE=public.ecr.aws/amazonlinux/amazonlinux:2023
FROM ${BASE_IMAGE}
ARG GRAALVM_ARCHIVE_URL
ARG JDK11_ARCHIVE_URL
ARG ANDROID_COMMAND_LINE_TOOLS_URL
ARG ANDROID_NDK_VERSION
RUN dnf install -y gcc gcc-c++ make git curl wget unzip zip tar which findutils patch diffutils \
      maven cmake protobuf-compiler ccache jq python3 python3-pip ruby zlib-devel \
    && (dnf install -y awscli-2 || pip3 install --no-cache-dir awscli) \
    && dnf clean all
RUN curl -fsSL "${GRAALVM_ARCHIVE_URL}" -o /tmp/graal.tgz \
    && mkdir -p /opt/graalvm && tar -xzf /tmp/graal.tgz -C /opt/graalvm --strip-components=1 \
    && curl -fsSL "${JDK11_ARCHIVE_URL}" -o /tmp/jdk.tgz \
    && mkdir -p /opt/jdk11 && tar -xzf /tmp/jdk.tgz -C /opt/jdk11 --strip-components=1 \
    && rm /tmp/*.tgz
RUN mkdir -p /opt/android-sdk/cmdline-tools \
    && curl -fsSL "${ANDROID_COMMAND_LINE_TOOLS_URL}" -o /tmp/android.zip \
    && unzip -q /tmp/android.zip -d /opt/android-sdk/cmdline-tools \
    && mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest \
    && rm /tmp/android.zip \
    && yes | JAVA_HOME=/opt/jdk11 /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --licenses >/dev/null \
    && JAVA_HOME=/opt/jdk11 /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager "ndk;${ANDROID_NDK_VERSION}" \
    && ln -s "/opt/android-sdk/ndk/${ANDROID_NDK_VERSION}" /opt/android-ndk
ENV ANDROID_SDK_ROOT=/opt/android-sdk ANDROID_NDK_HOME=/opt/android-ndk \
    GRAALVM_HOME=/opt/graalvm JAVA11_HOME=/opt/jdk11 CCACHE_DIR=/root/.ccache
RUN /opt/jdk11/bin/java -version && test -d /opt/android-ndk
