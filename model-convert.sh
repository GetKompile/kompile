#!/bin/bash

# Kompile Model Conversion Setup Script
# This script helps set up and run the model conversion utility

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$SCRIPT_DIR"
CONVERSION_UTILITY_DIR="$PROJECT_ROOT/model-conversion-utility"
CONFIG_FILE="$CONVERSION_UTILITY_DIR/src/main/resources/models-to-convert.yaml"

print_header() {
    echo -e "${BLUE}================================================${NC}"
    echo -e "${BLUE}       Kompile Model Conversion Utility       ${NC}"
    echo -e "${BLUE}================================================${NC}"
    echo
}

print_usage() {
    echo "Usage: $0 [COMMAND] [OPTIONS]"
    echo
    echo "Commands:"
    echo "  setup           Build the model conversion utility"
    echo "  convert         Convert all models defined in configuration"
    echo "  convert-model   Convert a specific model by ID"
    echo "  validate        Validate configuration file"
    echo "  list            List available models in configuration"
    echo "  clean           Clean build artifacts"
    echo "  help            Show this help message"
    echo
    echo "Options:"
    echo "  --model-id ID   Specify model ID for convert-model command"
    echo "  --config FILE   Use custom configuration file"
    echo "  --dry-run       Show what would be done without executing"
    echo "  --verbose       Enable verbose output"
    echo "  --skip-upload   Skip uploading to GitHub (convert locally only)"
    echo
    echo "Environment Variables:"
    echo "  GITHUB_TOKEN    Required for uploading to GitHub releases"
    echo "  KOMPILE_MODEL_CACHE_DIR  Override model cache directory"
    echo
    echo "Examples:"
    echo "  $0 setup"
    echo "  $0 convert --dry-run"
    echo "  $0 convert-model --model-id bge-base-en-v1.5"
    echo "  $0 validate --config my-models.yaml"
}

check_prerequisites() {
    echo -e "${BLUE}Checking prerequisites...${NC}"
    
    # Check Java
    if ! command -v java &> /dev/null; then
        echo -e "${RED}Error: Java is not installed or not in PATH${NC}"
        exit 1
    fi
    
    # Check Maven
    if ! command -v mvn &> /dev/null; then
        echo -e "${RED}Error: Maven is not installed or not in PATH${NC}"
        exit 1
    fi
    
    # Check Java version
    JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f 2 | cut -d'.' -f 1-2)
    if [[ "$JAVA_VERSION" < "11" ]]; then
        echo -e "${RED}Error: Java 11 or higher is required (found: $JAVA_VERSION)${NC}"
        exit 1
    fi
    
    echo -e "${GREEN}✓ Prerequisites check passed${NC}"
}

setup_utility() {
    echo -e "${BLUE}Building model conversion utility...${NC}"
    
    cd "$PROJECT_ROOT"
    
    # Build the parent project first to ensure dependencies are available
    echo -e "${YELLOW}Building parent project...${NC}"
    mvn clean install -DskipTests -q
    
    # Build the conversion utility specifically
    echo -e "${YELLOW}Building conversion utility...${NC}"
    cd "$CONVERSION_UTILITY_DIR"
    mvn clean package -q
    
    if [ -f "target/model-conversion-utility-"*"-cli.jar" ]; then
        echo -e "${GREEN}✓ Model conversion utility built successfully${NC}"
        
        # Find the JAR file
        JAR_FILE=$(find target -name "model-conversion-utility-*-cli.jar" | head -n 1)
        echo -e "${GREEN}✓ CLI JAR: $JAR_FILE${NC}"
    else
        echo -e "${RED}Error: Failed to build model conversion utility${NC}"
        exit 1
    fi
}

run_conversion() {
    local dry_run=$1
    local verbose=$2
    local skip_upload=$3
    local config_file=${4:-$CONFIG_FILE}
    
    echo -e "${BLUE}Running model conversion...${NC}"
    
    # Check if utility is built
    JAR_FILE=$(find "$CONVERSION_UTILITY_DIR/target" -name "model-conversion-utility-*-cli.jar" 2>/dev/null | head -n 1)
    if [ ! -f "$JAR_FILE" ]; then
        echo -e "${YELLOW}Conversion utility not found. Building first...${NC}"
        setup_utility
        JAR_FILE=$(find "$CONVERSION_UTILITY_DIR/target" -name "model-conversion-utility-*-cli.jar" | head -n 1)
    fi
    
    # Check for GitHub token if not skipping upload
    if [ "$skip_upload" != "true" ]; then
        if [ -z "$GITHUB_TOKEN" ]; then
            echo -e "${RED}Warning: GITHUB_TOKEN environment variable not set${NC}"
            echo -e "${YELLOW}Models will be converted but not uploaded to GitHub${NC}"
            echo -e "${YELLOW}Set GITHUB_TOKEN to enable upload functionality${NC}"
            skip_upload="true"
        fi
    fi
    
    # Build command
    local cmd="java -jar $JAR_FILE convert $config_file"
    
    if [ "$dry_run" = "true" ]; then
        cmd="$cmd --dry-run"
    fi
    
    if [ "$verbose" = "true" ]; then
        cmd="$cmd --verbose"
    fi
    
    if [ "$skip_upload" = "true" ]; then
        cmd="$cmd --skip-upload"
    fi
    
    echo -e "${YELLOW}Executing: $cmd${NC}"
    eval "$cmd"
}

convert_single_model() {
    local model_id=$1
    local dry_run=$2
    local verbose=$3
    local skip_upload=$4
    local config_file=${5:-$CONFIG_FILE}
    
    echo -e "${BLUE}Converting single model: $model_id${NC}"
    
    # Check if utility is built
    JAR_FILE=$(find "$CONVERSION_UTILITY_DIR/target" -name "model-conversion-utility-*-cli.jar" 2>/dev/null | head -n 1)
    if [ ! -f "$JAR_FILE" ]; then
        echo -e "${YELLOW}Conversion utility not found. Building first...${NC}"
        setup_utility
        JAR_FILE=$(find "$CONVERSION_UTILITY_DIR/target" -name "model-conversion-utility-*-cli.jar" | head -n 1)
    fi
    
    # Build command
    local cmd="java -jar $JAR_FILE convert $config_file --model $model_id"
    
    if [ "$dry_run" = "true" ]; then
        cmd="$cmd --dry-run"
    fi
    
    if [ "$verbose" = "true" ]; then
        cmd="$cmd --verbose"
    fi
    
    if [ "$skip_upload" = "true" ]; then
        cmd="$cmd --skip-upload"
    fi
    
    echo -e "${YELLOW}Executing: $cmd${NC}"
    eval "$cmd"
}

validate_config() {
    local config_file=${1:-$CONFIG_FILE}
    local check_urls=$2
    
    echo -e "${BLUE}Validating configuration: $config_file${NC}"
    
    # Check if utility is built
    JAR_FILE=$(find "$CONVERSION_UTILITY_DIR/target" -name "model-conversion-utility-*-cli.jar" 2>/dev/null | head -n 1)
    if [ ! -f "$JAR_FILE" ]; then
        echo -e "${YELLOW}Conversion utility not found. Building first...${NC}"
        setup_utility
        JAR_FILE=$(find "$CONVERSION_UTILITY_DIR/target" -name "model-conversion-utility-*-cli.jar" | head -n 1)
    fi
    
    local cmd="java -jar $JAR_FILE validate $config_file"
    
    if [ "$check_urls" = "true" ]; then
        cmd="$cmd --check-urls"
    fi
    
    echo -e "${YELLOW}Executing: $cmd${NC}"
    eval "$cmd"
}

list_models() {
    local config_file=${1:-$CONFIG_FILE}
    local detailed=$2
    
    echo -e "${BLUE}Listing models in configuration: $config_file${NC}"
    
    # Check if utility is built
    JAR_FILE=$(find "$CONVERSION_UTILITY_DIR/target" -name "model-conversion-utility-*-cli.jar" 2>/dev/null | head -n 1)
    if [ ! -f "$JAR_FILE" ]; then
        echo -e "${YELLOW}Conversion utility not found. Building first...${NC}"
        setup_utility
        JAR_FILE=$(find "$CONVERSION_UTILITY_DIR/target" -name "model-conversion-utility-*-cli.jar" | head -n 1)
    fi
    
    local cmd="java -jar $JAR_FILE list $config_file"
    
    if [ "$detailed" = "true" ]; then
        cmd="$cmd --detailed"
    fi
    
    echo -e "${YELLOW}Executing: $cmd${NC}"
    eval "$cmd"
}

clean_build() {
    echo -e "${BLUE}Cleaning build artifacts...${NC}"
    
    cd "$PROJECT_ROOT"
    mvn clean -q
    
    echo -e "${GREEN}✓ Build artifacts cleaned${NC}"
}

# Parse command line arguments
COMMAND=""
MODEL_ID=""
CONFIG_FILE_ARG=""
DRY_RUN="false"
VERBOSE="false"
SKIP_UPLOAD="false"
CHECK_URLS="false"
DETAILED="false"

while [[ $# -gt 0 ]]; do
    case $1 in
        setup|convert|convert-model|validate|list|clean|help)
            COMMAND="$1"
            shift
            ;;
        --model-id)
            MODEL_ID="$2"
            shift 2
            ;;
        --config)
            CONFIG_FILE_ARG="$2"
            shift 2
            ;;
        --dry-run)
            DRY_RUN="true"
            shift
            ;;
        --verbose)
            VERBOSE="true"
            shift
            ;;
        --skip-upload)
            SKIP_UPLOAD="true"
            shift
            ;;
        --check-urls)
            CHECK_URLS="true"
            shift
            ;;
        --detailed)
            DETAILED="true"
            shift
            ;;
        *)
            echo -e "${RED}Error: Unknown option $1${NC}"
            print_usage
            exit 1
            ;;
    esac
done

# Use custom config file if provided
if [ ! -z "$CONFIG_FILE_ARG" ]; then
    CONFIG_FILE="$CONFIG_FILE_ARG"
fi

# Main execution
print_header

case $COMMAND in
    "setup")
        check_prerequisites
        setup_utility
        echo -e "${GREEN}✓ Setup completed successfully${NC}"
        echo
        echo -e "${BLUE}You can now run model conversion with:${NC}"
        echo -e "${YELLOW}  $0 convert --dry-run${NC} (to see what would be done)"
        echo -e "${YELLOW}  $0 convert${NC} (to perform actual conversion)"
        ;;
    "convert")
        check_prerequisites
        run_conversion "$DRY_RUN" "$VERBOSE" "$SKIP_UPLOAD" "$CONFIG_FILE"
        ;;
    "convert-model")
        if [ -z "$MODEL_ID" ]; then
            echo -e "${RED}Error: --model-id is required for convert-model command${NC}"
            exit 1
        fi
        check_prerequisites
        convert_single_model "$MODEL_ID" "$DRY_RUN" "$VERBOSE" "$SKIP_UPLOAD" "$CONFIG_FILE"
        ;;
    "validate")
        check_prerequisites
        validate_config "$CONFIG_FILE" "$CHECK_URLS"
        ;;
    "list")
        check_prerequisites
        list_models "$CONFIG_FILE" "$DETAILED"
        ;;
    "clean")
        clean_build
        ;;
    "help"|"")
        print_usage
        ;;
    *)
        echo -e "${RED}Error: Unknown command '$COMMAND'${NC}"
        print_usage
        exit 1
        ;;
esac
