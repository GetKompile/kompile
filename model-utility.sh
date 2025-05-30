#!/bin/bash

# Enhanced Model Management Script
# This script provides easy access to all model management functions via the uber JAR

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
UTILITY_DIR="$PROJECT_ROOT/model-conversion-utility"
CONFIG_FILE="$UTILITY_DIR/src/main/resources/models-to-convert.yaml"

print_header() {
    echo -e "${BLUE}================================================${NC}"
    echo -e "${BLUE}      Kompile Model Management Utility        ${NC}"
    echo -e "${BLUE}================================================${NC}"
    echo
}

print_usage() {
    echo "Usage: $0 [COMMAND] [OPTIONS]"
    echo
    echo "Commands:"
    echo "  build               Build the model utility uber JAR"
    echo "  convert             Convert models using configuration file"
    echo "  convert-model       Convert specific model by ID"
    echo "  download-anserini   Download and convert Anserini models"
    echo "  validate            Validate configuration file"
    echo "  list                List available models"
    echo "  clean               Clean build artifacts"
    echo "  help                Show this help message"
    echo
    echo "Convert Options:"
    echo "  --config FILE       Use custom configuration file"
    echo "  --model-id ID       Convert specific model only"
    echo "  --dry-run           Show what would be done without executing"
    echo "  --verbose           Enable verbose output"
    echo "  --skip-upload       Skip uploading to GitHub"
    echo
    echo "Download Anserini Options:"
    echo "  --output-dir DIR    Output directory for models"
    echo "  --model NAME        Download specific model only"
    echo "  --parallel          Use parallel processing"
    echo "  --dense-only        Download dense models only"
    echo "  --sparse-only       Download sparse models only"
    echo "  --list              List available Anserini models"
    echo
    echo "Environment Variables:"
    echo "  GITHUB_TOKEN        Required for uploading to GitHub releases"
    echo "  KOMPILE_MODEL_CACHE_DIR  Override model cache directory"
    echo
    echo "Examples:"
    echo "  $0 build"
    echo "  $0 convert --dry-run"
    echo "  $0 convert-model --model-id bge-base-en-v1.5"
    echo "  $0 download-anserini --parallel --dense-only"
    echo "  $0 download-anserini --model bge-base-en-v1.5"
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
    JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f 2 | cut -d'.' -f 1)
    if [[ "$JAVA_VERSION" -lt "11" ]]; then
        echo -e "${RED}Error: Java 11 or higher is required (found: Java $JAVA_VERSION)${NC}"
        exit 1
    fi
    
    echo -e "${GREEN}✓ Prerequisites check passed${NC}"
}

build_utility() {
    echo -e "${BLUE}Building model conversion utility uber JAR...${NC}"
    
    cd "$PROJECT_ROOT"
    
    # BUILD THE ENTIRE PROJECT FROM ROOT - This is the key fix!
    echo -e "${YELLOW}Building entire project from root to resolve dependencies...${NC}"
    mvn clean install -DskipTests -q
    
    # Check if uber JAR was created
    UBER_JAR=$(find "$UTILITY_DIR/target" -name "*-uber.jar" 2>/dev/null | head -n 1)
    CLI_JAR=$(find "$UTILITY_DIR/target" -name "*-cli.jar" 2>/dev/null | head -n 1)
    
    if [ -f "$UBER_JAR" ]; then
        echo -e "${GREEN}✓ Model utility uber JAR built successfully${NC}"
        echo -e "${GREEN}✓ Uber JAR: $UBER_JAR${NC}"
        
        if [ -f "$CLI_JAR" ]; then
            echo -e "${GREEN}✓ CLI JAR: $CLI_JAR${NC}"
        fi
        
        # Make JAR executable if possible
        if command -v chmod &> /dev/null; then
            chmod +x "$UBER_JAR" 2>/dev/null || true
        fi
        
    else
        echo -e "${RED}Error: Failed to build model conversion utility uber JAR${NC}"
        echo -e "${RED}Expected JAR location: $UTILITY_DIR/target/*-uber.jar${NC}"
        echo -e "${YELLOW}Available files in target directory:${NC}"
        ls -la "$UTILITY_DIR/target/" 2>/dev/null || echo "Target directory does not exist"
        exit 1
    fi
    
    cd "$PROJECT_ROOT"
}

find_jar() {
    # Look for uber JAR first, then CLI JAR
    local uber_jar=$(find "$UTILITY_DIR/target" -name "*-uber.jar" 2>/dev/null | head -n 1)
    local cli_jar=$(find "$UTILITY_DIR/target" -name "*-cli.jar" 2>/dev/null | head -n 1)
    
    if [ -f "$uber_jar" ]; then
        echo "$uber_jar"
    elif [ -f "$cli_jar" ]; then
        echo "$cli_jar"
    else
        echo ""
    fi
}

run_utility() {
    local command="$1"
    shift
    local args=("$@")
    
    # Find the JAR file
    local jar_file=$(find_jar)
    if [ -z "$jar_file" ]; then
        echo -e "${YELLOW}Model utility JAR not found. Building first...${NC}"
        build_utility
        jar_file=$(find_jar)
        
        if [ -z "$jar_file" ]; then
            echo -e "${RED}Error: Could not find or build model utility JAR${NC}"
            exit 1
        fi
    fi
    
    echo -e "${BLUE}Running model utility: $command${NC}"
    echo -e "${YELLOW}Using JAR: $(basename "$jar_file")${NC}"
    echo -e "${YELLOW}Command: java -jar \"$jar_file\" $command ${args[*]}${NC}"
    echo
    
    # Execute the command
    java -jar "$jar_file" "$command" "${args[@]}"
}

show_status() {
    local output_dir="${1:-./anserini-models}"
    
    echo -e "${BLUE}Model Status${NC}"
    echo
    
    # Check if JAR exists
    local jar_file=$(find_jar)
    if [ -n "$jar_file" ]; then
        echo -e "${GREEN}✓ Model utility JAR: $(basename "$jar_file")${NC}"
    else
        echo -e "${YELLOW}⚠ Model utility JAR not built${NC}"
    fi
    
    # Check Anserini models
    if [ -d "$output_dir" ]; then
        echo -e "${BLUE}Anserini models in: $output_dir${NC}"
        local total=0
        local downloaded=0
        
        for model_dir in "$output_dir"/*; do
            if [ -d "$model_dir" ]; then
                local model_name=$(basename "$model_dir")
                local sd_file="$model_dir/$model_name.sd"
                local vocab_file="$model_dir/$model_name-vocab.txt"
                
                total=$((total + 1))
                
                if [ -f "$sd_file" ] && [ -f "$vocab_file" ]; then
                    local size=$(du -sh "$model_dir" 2>/dev/null | cut -f1 || echo "?MB")
                    echo -e "${GREEN}  ✓ $model_name${NC} ($size)"
                    downloaded=$((downloaded + 1))
                else
                    echo -e "${RED}  ✗ $model_name${NC} (incomplete)"
                fi
            fi
        done
        
        echo
        echo -e "${BLUE}Anserini models: $downloaded/$total ready${NC}"
    else
        echo -e "${YELLOW}No Anserini models directory found${NC}"
    fi
    
    # Check environment variables
    echo
    echo -e "${BLUE}Environment:${NC}"
    if [ -n "$GITHUB_TOKEN" ]; then
        echo -e "${GREEN}  ✓ GITHUB_TOKEN set${NC}"
    else
        echo -e "${YELLOW}  ⚠ GITHUB_TOKEN not set (upload disabled)${NC}"
    fi
    
    if [ -n "$KOMPILE_MODEL_CACHE_DIR" ]; then
        echo -e "${GREEN}  ✓ KOMPILE_MODEL_CACHE_DIR: $KOMPILE_MODEL_CACHE_DIR${NC}"
    else
        echo -e "${BLUE}  • KOMPILE_MODEL_CACHE_DIR: default (~/.kompile/models)${NC}"
    fi
}

clean_build() {
    echo -e "${BLUE}Cleaning build artifacts...${NC}"
    
    cd "$PROJECT_ROOT"
    
    # Clean the entire project from root
    echo -e "${YELLOW}Cleaning entire project...${NC}"
    mvn clean -q 2>/dev/null || true
    
    cd "$PROJECT_ROOT"
    
    echo -e "${GREEN}✓ Build artifacts cleaned${NC}"
}

# Parse command line arguments
COMMAND=""
CONFIG_FILE_ARG=""
MODEL_ID=""
OUTPUT_DIR=""
DRY_RUN=""
VERBOSE=""
SKIP_UPLOAD=""
PARALLEL=""
DENSE_ONLY=""
SPARSE_ONLY=""
LIST_MODELS=""

while [[ $# -gt 0 ]]; do
    case $1 in
        build|convert|convert-model|download-anserini|validate|list|clean|help|status)
            COMMAND="$1"
            shift
            ;;
        --config)
            CONFIG_FILE_ARG="$2"
            shift 2
            ;;
        --model-id|--model)
            MODEL_ID="$2"
            shift 2
            ;;
        --output-dir)
            OUTPUT_DIR="$2"
            shift 2
            ;;
        --dry-run)
            DRY_RUN="--dry-run"
            shift
            ;;
        --verbose)
            VERBOSE="--verbose"
            shift
            ;;
        --skip-upload)
            SKIP_UPLOAD="--skip-upload"
            shift
            ;;
        --parallel)
            PARALLEL="--parallel"
            shift
            ;;
        --dense-only)
            DENSE_ONLY="--dense-only"
            shift
            ;;
        --sparse-only)
            SPARSE_ONLY="--sparse-only"
            shift
            ;;
        --list)
            LIST_MODELS="--list"
            shift
            ;;
        *)
            echo -e "${RED}Error: Unknown option $1${NC}"
            print_usage
            exit 1
            ;;
    esac
done

# Main execution
print_header

case $COMMAND in
    "build")
        check_prerequisites
        build_utility
        echo -e "${GREEN}✓ Build completed successfully${NC}"
        ;;
    "convert")
        check_prerequisites
        args=()
        [ -n "$CONFIG_FILE_ARG" ] && args=("$CONFIG_FILE_ARG") || args=("$CONFIG_FILE")
        [ -n "$DRY_RUN" ] && args+=("$DRY_RUN")
        [ -n "$VERBOSE" ] && args+=("$VERBOSE")
        [ -n "$SKIP_UPLOAD" ] && args+=("$SKIP_UPLOAD")
        run_utility "convert" "${args[@]}"
        ;;
    "convert-model")
        if [ -z "$MODEL_ID" ]; then
            echo -e "${RED}Error: --model-id is required for convert-model command${NC}"
            exit 1
        fi
        check_prerequisites
        args=()
        [ -n "$CONFIG_FILE_ARG" ] && args=("$CONFIG_FILE_ARG") || args=("$CONFIG_FILE")
        args+=("--model" "$MODEL_ID")
        [ -n "$DRY_RUN" ] && args+=("$DRY_RUN")
        [ -n "$VERBOSE" ] && args+=("$VERBOSE")
        [ -n "$SKIP_UPLOAD" ] && args+=("$SKIP_UPLOAD")
        run_utility "convert" "${args[@]}"
        ;;
    "download-anserini")
        check_prerequisites
        args=()
        [ -n "$OUTPUT_DIR" ] && args+=("--output-dir" "$OUTPUT_DIR")
        [ -n "$MODEL_ID" ] && args+=("--model" "$MODEL_ID")
        [ -n "$PARALLEL" ] && args+=("$PARALLEL")
        [ -n "$DENSE_ONLY" ] && args+=("$DENSE_ONLY")
        [ -n "$SPARSE_ONLY" ] && args+=("$SPARSE_ONLY")
        [ -n "$LIST_MODELS" ] && args+=("$LIST_MODELS")
        [ -n "$VERBOSE" ] && args+=("$VERBOSE")
        run_utility "download-anserini" "${args[@]}"
        ;;
    "validate")
        check_prerequisites
        args=()
        [ -n "$CONFIG_FILE_ARG" ] && args=("$CONFIG_FILE_ARG") || args=("$CONFIG_FILE")
        run_utility "validate" "${args[@]}"
        ;;
    "list")
        check_prerequisites
        run_utility "list"
        ;;
    "status")
        show_status "$OUTPUT_DIR"
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
