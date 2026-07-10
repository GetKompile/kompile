#!/usr/bin/env bash
# Toolchain install for LINUX_EC2 reserved-fleet AMIs (builds run directly on
# the instance, so the AMI must carry everything the container images carry).
# bake-ami.sh runs this via SSM, then your --sdk-script (ROCm/ZLUDA/vendor
# drivers), then images/accelerators/bootstrap.sh as validation.
# Requires GRAALVM_ARCHIVE_URL / JDK11_ARCHIVE_URL in the environment.
set -euo pipefail
: "${GRAALVM_ARCHIVE_URL:?}" "${JDK11_ARCHIVE_URL:?}"

if command -v dnf >/dev/null 2>&1; then
  dnf install -y gcc gcc-c++ gcc-gfortran make git tar gzip unzip zip curl wget which findutils \
    patch diffutils perl python3 python3-pip ruby maven cmake protobuf-compiler ccache jq \
    libarchive-devel zlib-devel libuuid-devel libxml2-devel elfutils-libelf-devel
  dnf install -y awscli-2 || pip3 install --no-cache-dir awscli
elif command -v apt-get >/dev/null 2>&1; then
  export DEBIAN_FRONTEND=noninteractive
  apt-get update
  apt-get install -y build-essential gfortran git curl wget unzip zip tar patch perl \
    python3 python3-pip ruby maven cmake protobuf-compiler ccache jq awscli \
    zlib1g-dev libdw-dev libelf-dev ca-certificates
else
  echo "Unsupported distribution: need dnf or apt-get" >&2
  exit 2
fi

mkdir -p /opt/graalvm /opt/jdk11
curl -fsSL "$GRAALVM_ARCHIVE_URL" -o /tmp/graal.tgz
tar -xzf /tmp/graal.tgz -C /opt/graalvm --strip-components=1
curl -fsSL "$JDK11_ARCHIVE_URL" -o /tmp/jdk.tgz
tar -xzf /tmp/jdk.tgz -C /opt/jdk11 --strip-components=1
rm -f /tmp/graal.tgz /tmp/jdk.tgz

/opt/graalvm/bin/native-image --version
/opt/jdk11/bin/java -version
mvn --version
cmake --version
