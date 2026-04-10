#!/bin/bash
#
# Maven wrapper script
# Downloads and uses local Maven if not found on PATH
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MAVEN_WRAPPER_DIR="$SCRIPT_DIR/.maven/wrapper"
MAVEN_WRAPPER_JAR="$MAVEN_WRAPPER_DIR/maven-wrapper.jar"
MAVEN_VERSION="3.9.6"

# Function to download Maven
download_maven() {
    echo "Downloading Maven $MAVEN_VERSION..."
    mkdir -p "$MAVEN_WRAPPER_DIR"
    
    local maven_url="https://dlcdn.apache.org/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz"
    local maven_tar="$MAVEN_WRAPPER_DIR/maven.tar.gz"
    
    curl -fsSL "$maven_url" -o "$maven_tar" || {
        echo "Failed to download Maven from $maven_url"
        echo "Trying alternative mirror..."
        maven_url="https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz"
        curl -fsSL "$maven_url" -o "$maven_tar" || {
            echo "Failed to download Maven from alternative mirror"
            return 1
        }
    }
    
    tar -xzf "$maven_tar" -C "$MAVEN_WRAPPER_DIR"
    mv "$MAVEN_WRAPPER_DIR/apache-maven-${MAVEN_VERSION}" "$MAVEN_WRAPPER_DIR/maven"
    rm "$maven_tar"
    
    echo "✓ Maven downloaded to $MAVEN_WRAPPER_DIR/maven"
}

# Check if Maven is on PATH
if command -v mvn &> /dev/null; then
    MVN_CMD="mvn"
elif [ -x "$MAVEN_WRAPPER_DIR/maven/bin/mvn" ]; then
    MVN_CMD="$MAVEN_WRAPPER_DIR/maven/bin/mvn"
else
    # Try to download Maven
    if download_maven; then
        MVN_CMD="$MAVEN_WRAPPER_DIR/maven/bin/mvn"
    else
        echo "Error: Maven not found and could not be downloaded"
        echo ""
        echo "Options:"
        echo "  1. Install Maven: sdk install maven"
        echo "  2. Install Maven: sudo apt install maven"
        echo "  3. Download manually from: https://maven.apache.org/download.cgi"
        exit 1
    fi
fi

# Show Java info
JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java))))
echo "Using Java: $(java -version 2>&1 | head -1)"
echo "Using Maven: $($MVN_CMD --version 2>&1 | head -1)"
echo ""

# Execute Maven with all arguments
exec "$MVN_CMD" "$@"
