#!/usr/bin/env bash
# Run on the mac2 instance while baking the MAC_ARM reserved-fleet AMI.
# Requires GRAALVM_ARCHIVE_URL / JDK11_ARCHIVE_URL (macOS aarch64 archives).
# After baking, grant the CodeBuild organization launch permission on the AMI
# (bake-ami.sh does this automatically for the Linux/Windows lanes).
set -euo pipefail
: "${GRAALVM_ARCHIVE_URL:?}" "${JDK11_ARCHIVE_URL:?}"
brew update
brew install maven cmake protobuf ccache coreutils gnu-sed wget llvm jq awscli
sudo mkdir -p /opt/graalvm /opt/jdk11
curl -fsSL "$GRAALVM_ARCHIVE_URL" -o /tmp/graal.tgz
sudo tar -xzf /tmp/graal.tgz -C /opt/graalvm --strip-components=1
curl -fsSL "$JDK11_ARCHIVE_URL" -o /tmp/jdk.tgz
sudo tar -xzf /tmp/jdk.tgz -C /opt/jdk11 --strip-components=1
rm -f /tmp/graal.tgz /tmp/jdk.tgz
# GraalVM macOS archives nest Contents/Home; normalize so /opt/graalvm/bin works.
if [ ! -x /opt/graalvm/bin/native-image ] && [ -d /opt/graalvm/Contents/Home ]; then
  sudo ln -sfn /opt/graalvm/Contents/Home/bin /opt/graalvm/bin
fi
if [ ! -x /opt/jdk11/bin/java ] && [ -d /opt/jdk11/Contents/Home ]; then
  sudo ln -sfn /opt/jdk11/Contents/Home/bin /opt/jdk11/bin
fi
/opt/graalvm/bin/native-image --version
/opt/jdk11/bin/java -version
mvn --version
aws --version
