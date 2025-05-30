#!/bin/bash

# Simple wrapper script for AnseriniModelDownloader
# This script makes it easy to download and convert all ONNX models for Anserini

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANSERINI_DIR="$SCRIPT_DIR/anserini"

print_header() {
    echo -e "${BLUE}================================================${NC}"
    echo -e "${BLUE}     Anserini Model Downloader Wrapper        ${NC}"
    echo -e "${BLUE}================================================${NC}"
    echo
}

print_usage() {
    echo "Usage: $0 [COMMAND] [OPTIONS]"
    echo
    echo "Commands:"
    echo "  download        Download and convert all models"
    echo "  download-dense  Download dense models only"
    echo "  download-sparse Download sparse models only"
    echo "  download-model  Download specific model"
    echo "  list           List available models"
    echo "  status         Show status of downloaded models"
    echo "  clean          Clean downloaded models"
    echo "  help           Show this help"
    echo
    echo "Options:"
    echo "  --model NAME    Specify model name (for download-model)"
    echo "  --output DIR    Specify output directory"
    echo "  --parallel      Use parallel processing"
    echo "  --verbose       Enable verbose output"
    echo
    echo "Examples:"
    echo "  $0 download"
    echo "  $0 download-dense --parallel"
    echo "  $0 download-model --model bge-base-en-v1.5"
    echo "  $0 list"
}

check_java() {
    if ! command -v java &> /dev/null; then
        echo -e "${RED}Error: Java is not installed or not in PATH${NC}"
        exit 1
    fi
    
    # Check if we can find the Anserini classes
    if [ ! -d "$ANSERINI_DIR/target/classes" ] && [ ! -f "$ANSERINI_DIR/target/anserini-*.jar" ]; then
        echo -e "${YELLOW}Warning: Anserini doesn't appear to be built${NC}"
        echo -e "${YELLOW}Building Anserini first...${NC}"
        build_anserini
    fi
}

build_anserini() {
    echo -e "${BLUE}Building Anserini...${NC}"
    cd "$ANSERINI_DIR"
    
    if command -v mvn &> /dev/null; then
        mvn clean compile -q
        echo -e "${GREEN}✓ Anserini built successfully${NC}"
    else
        echo -e "${RED}Error: Maven is not installed. Please build Anserini manually.${NC}"
        exit 1
    fi
    
    cd - > /dev/null
}

get_classpath() {
    local classpath=""
    
    # Add compiled classes
    if [ -d "$ANSERINI_DIR/target/classes" ]; then
        classpath="$ANSERINI_DIR/target/classes"
    fi
    
    # Add JAR if available
    local jar_file=$(find "$ANSERINI_DIR/target" -name "anserini-*.jar" 2>/dev/null | head -n 1)
    if [ -n "$jar_file" ]; then
        if [ -n "$classpath" ]; then
            classpath="$classpath:$jar_file"
        else
            classpath="$jar_file"
        fi
    fi
    
    # Add dependencies
    if [ -d "$ANSERINI_DIR/target/lib" ]; then
        for jar in "$ANSERINI_DIR/target/lib"/*.jar; do
            if [ -f "$jar" ]; then
                classpath="$classpath:$jar"
            fi
        done
    fi
    
    # Try Maven classpath if available
    if command -v mvn &> /dev/null && [ -d "$ANSERINI_DIR/pom.xml" ]; then
        cd "$ANSERINI_DIR"
        local maven_cp=$(mvn dependency:build-classpath -q -Dmdep.outputFile=/dev/stdout 2>/dev/null | tail -n 1)
        if [ -n "$maven_cp" ] && [ "$maven_cp" != "[INFO]"* ]; then
            classpath="$classpath:$maven_cp"
        fi
        cd - > /dev/null
    fi
    
    echo "$classpath"
}

run_downloader() {
    local args=("$@")
    local classpath=$(get_classpath)
    
    if [ -z "$classpath" ]; then
        echo -e "${RED}Error: Could not determine classpath. Please build Anserini first.${NC}"
        exit 1
    fi
    
    echo -e "${BLUE}Running AnseriniModelDownloader...${NC}"
    echo -e "${YELLOW}Command: java -cp \"$classpath\" io.anserini.util.AnseriniModelDownloader ${args[*]}${NC}"
    echo
    
    java -cp "$classpath" io.anserini.util.AnseriniModelDownloader "${args[@]}"
}

show_status() {
    local output_dir="${1:-./anserini-models}"
    
    echo -e "${BLUE}Model Status in: $output_dir${NC}"
    echo
    
    if [ ! -d "$output_dir" ]; then
        echo -e "${YELLOW}No models directory found${NC}"
        return
    fi
    
    local total=0
    local downloaded=0
    
    for model_dir in "$output_dir"/*; do
        if [ -d "$model_dir" ]; then
            local model_name=$(basename "$model_dir")
            local sd_file="$model_dir/$model_name.sd"
            local vocab_file="$model_dir/$model_name-vocab.txt"
            
            total=$((total + 1))
            
            if [ -f "$sd_file" ] && [ -f "$vocab_file" ]; then
                local size=$(du -sh "$model_dir" | cut -f1)
                echo -e "${GREEN}✓ $model_name${NC} ($size)"
                downloaded=$((downloaded + 1))
            else
                echo -e "${RED}✗ $model_name${NC} (incomplete)"
            fi
        fi
    done
    
    echo
    echo -e "${BLUE}Summary: $downloaded/$total models ready${NC}"
}

clean_models() {
    local output_dir="${1:-./anserini-models}"
    
    if [ ! -d "$output_dir" ]; then
        echo -e "${YELLOW}No models directory to clean${NC}"
        return
    fi
    
    echo -e "${YELLOW}Cleaning models in: $output_dir${NC}"
    echo -e "${RED}This will delete all downloaded models. Continue? (y/N)${NC}"
    read -r response
    
    if [[ "$response" =~ ^[Yy]$ ]]; then
        rm -rf "$output_dir"
        echo -e "${GREEN}✓ Models cleaned${NC}"
    else
        echo -e "${YELLOW}Cancelled${NC}"
    fi
}

# Parse arguments
COMMAND=""
MODEL_NAME=""
OUTPUT_DIR=""
PARALLEL=""
VERBOSE=""

while [[ $# -gt 0 ]]; do
    case $1 in
        download|download-dense|download-sparse|download-model|list|status|clean|help)
            COMMAND="$1"
            shift
            ;;
        --model)
            MODEL_NAME="$2"
            shift 2
            ;;
        --output)
            OUTPUT_DIR="$2"
            shift 2
            ;;
        --parallel)
            PARALLEL="--parallel"
            shift
            ;;
        --verbose)
            VERBOSE="--verbose"
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
    "download")
        check_java
        args=()
        [ -n "$OUTPUT_DIR" ] && args+=("--output-dir" "$OUTPUT_DIR")
        [ -n "$PARALLEL" ] && args+=("$PARALLEL")
        run_downloader "${args[@]}"
        ;;
    "download-dense")
        check_java
        args=("--dense-only")
        [ -n "$OUTPUT_DIR" ] && args+=("--output-dir" "$OUTPUT_DIR")
        [ -n "$PARALLEL" ] && args+=("$PARALLEL")
        run_downloader "${args[@]}"
        ;;
    "download-sparse")
        check_java
        args=("--sparse-only")
        [ -n "$OUTPUT_DIR" ] && args+=("--output-dir" "$OUTPUT_DIR")
        [ -n "$PARALLEL" ] && args+=("$PARALLEL")
        run_downloader "${args[@]}"
        ;;
    "download-model")
        if [ -z "$MODEL_NAME" ]; then
            echo -e "${RED}Error: --model is required for download-model command${NC}"
            exit 1
        fi
        check_java
        args=("--model" "$MODEL_NAME")
        [ -n "$OUTPUT_DIR" ] && args+=("--output-dir" "$OUTPUT_DIR")
        run_downloader "${args[@]}"
        ;;
    "list")
        check_java
        run_downloader "--list"
        ;;
    "status")
        show_status "$OUTPUT_DIR"
        ;;
    "clean")
        clean_models "$OUTPUT_DIR"
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
