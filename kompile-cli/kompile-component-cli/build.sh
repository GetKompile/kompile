#!/bin/bash
#
# Build script for kompile-component-cli
# Supports JAR and native-image builds with GraalVM 21
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}Kompile Component CLI Builder${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""

# Function to check if GraalVM is installed
check_graalvm() {
    if command -v native-image &> /dev/null; then
        local version=$(native-image --version 2>&1 | head -n 1)
        echo -e "${GREEN}✓ GraalVM found: $version${NC}"
        return 0
    else
        echo -e "${YELLOW}⚠ GraalVM native-image tool not found${NC}"
        return 1
    fi
}

# Function to check Java version
check_java() {
    if command -v java &> /dev/null; then
        local java_version=$(java -version 2>&1 | head -n 1)
        echo -e "${GREEN}✓ Java found: $java_version${NC}"
        return 0
    else
        echo -e "${RED}✗ Java not found${NC}"
        return 1
    fi
}

# Parse arguments
BUILD_NATIVE=false
SKIP_TESTS=true
CLEAN=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --native)
            BUILD_NATIVE=true
            shift
            ;;
        --with-tests)
            SKIP_TESTS=false
            shift
            ;;
        --clean)
            CLEAN=true
            shift
            ;;
        --help)
            echo "Usage: $0 [OPTIONS]"
            echo ""
            echo "Options:"
            echo "  --native        Build native image (requires GraalVM)"
            echo "  --with-tests    Run tests during build"
            echo "  --clean         Clean before building"
            echo "  --help          Show this help message"
            exit 0
            ;;
        *)
            echo -e "${RED}Unknown option: $1${NC}"
            exit 1
            ;;
    esac
done

# Check prerequisites
echo "Checking prerequisites..."
check_java || {
    echo -e "${RED}Error: Java is required but not found${NC}"
    exit 1
}

if [ "$BUILD_NATIVE" = true ]; then
    check_graalvm || {
        echo -e "${RED}Error: GraalVM native-image tool is required for native builds${NC}"
        echo ""
        echo "Install GraalVM 21 with SDKMAN:"
        echo "  sdk install java 21.0.2-graal"
        echo "  sdk use java 21.0.2-graal"
        exit 1
    }
fi

echo ""

# Build command
if [ "$CLEAN" = true ]; then
    echo -e "${YELLOW}Cleaning...${NC}"
    mvn clean
    echo ""
fi

if [ "$BUILD_NATIVE" = true ]; then
    echo -e "${YELLOW}Building native image...${NC}"
    echo -e "${YELLOW}(This may take several minutes and requires 4GB+ RAM)${NC}"
    echo ""
    
    if [ "$SKIP_TESTS" = true ]; then
        mvn -Pnative package -DskipTests
    else
        mvn -Pnative package
    fi
    
    echo ""
    echo -e "${GREEN}========================================${NC}"
    echo -e "${GREEN}Native image built successfully!${NC}"
    echo -e "${GREEN}========================================${NC}"
    echo ""
    echo "Binary location: target/kompile-component"
    echo "Binary size: $(du -h target/kompile-component | cut -f1)"
    echo ""
    echo "Test it:"
    echo "  ./target/kompile-component --help"
    echo "  ./target/kompile-component list"
    echo "  ./target/kompile-component list --format json"
    
else
    echo -e "${YELLOW}Building JAR...${NC}"
    echo ""
    
    if [ "$SKIP_TESTS" = true ]; then
        mvn package -DskipTests
    else
        mvn package
    fi
    
    echo ""
    echo -e "${GREEN}========================================${NC}"
    echo -e "${GREEN}JAR built successfully!${NC}"
    echo -e "${GREEN}========================================${NC}"
    echo ""
    echo "JAR location: target/kompile-component-cli-0.1.0-SNAPSHOT.jar"
    echo "JAR size: $(du -h target/kompile-component-cli-0.1.0-SNAPSHOT.jar | cut -f1)"
    echo ""
    echo "Test it:"
    echo "  java -jar target/kompile-component-cli-0.1.0-SNAPSHOT.jar --help"
    echo "  java -jar target/kompile-component-cli-0.1.0-SNAPSHOT.jar list"
fi

echo ""
echo -e "${GREEN}Done!${NC}"
