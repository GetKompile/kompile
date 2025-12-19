# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Kompile is a comprehensive AI/ML platform combining:
- **CLI tools** for model conversion, pipeline building, and RAG app generation (Picocli-based)
- **Spring Boot RAG framework** with pluggable embeddings, vector stores, and LLMs
- **ML pipelines framework** for composing workflows with Python, DL4J, SameDiff, and ONNX steps
- **Rust tokenizer bindings** via JavaCPP for high-performance text processing
- **GraalVM native image** compilation for production deployments

The repository is organized into three main application areas:
1. **kompile-cli**: Command-line interface for developers
2. **kompile-app**: Spring Boot RAG application framework (40+ modules)
3. **kompile-pipelines-framework**: Pipeline execution engine

## Build Commands

### Prerequisites
- Java 17
- Maven 3.9+
- 10GB RAM minimum (16GB+ recommended for native builds)
- Docker (optional, for containerized builds)

### Standard Build
```bash
# Build entire project
mvn clean install

# Build without tests
mvn clean install -DskipTests

# Build specific module (e.g., CLI)
cd kompile-cli
mvn clean package

# Build RAG application (UI builds by default)
cd kompile-app/kompile-app-main
mvn clean package

# Build RAG application without UI (faster backend-only build)
cd kompile-app/kompile-app-main
mvn clean package -Dskip.ui

# Install/update frontend dependencies (first time or after package.json changes)
cd kompile-app/kompile-app-main
mvn clean install -Dui.deps
```

### Native Image Build (GraalVM)
```bash
# Build CLI as native image (requires 18GB+ heap)
cd kompile-cli
mvn clean package -Pnative

# Build RAG app with specific modules
./kompile build-rag-app \
  --instanceId=my-rag-app \
  --enableAnserini=true \
  --enableOpenAi=true
```

### Tokenizers Rust Library Build
```bash
# Build native tokenizers library
cd tokenizers-rust/libtokenizers
./buildnativetokenizers.sh

# Build for specific platform
JAVACPP_PLATFORM=linux-x86_64 ./buildnativetokenizers.sh
```

### Docker Builds
```bash
# Build container
docker build -f Dockerfile.rockylinux8 --ulimit nofile=98304:98304 -t konduitai/kompile:latest .

# Run CLI in container
docker run --rm -it konduitai/kompile

# Run interactively
docker run --ulimit nofile=98304:98304 --rm -it -v $(pwd):/mnt/:Z --entrypoint /bin/bash konduitai/kompile
```

## Testing

### Run All Tests
```bash
mvn test
```

### Run Tests for Specific Module
```bash
# CLI tests
cd kompile-cli
mvn test

# RAG app tests
cd kompile-app/kompile-app-main
mvn test

# Specific test class
mvn test -Dtest=YourTestClass

# Specific test method
mvn test -Dtest=YourTestClass#testMethod
```

### Integration Tests
```bash
# Run with Spring profile
cd kompile-app/kompile-app-main
mvn verify -Dspring.profiles.active=test
```

## CLI Usage Patterns

### Bootstrap & Setup
```bash
# Initialize Kompile directory (~/.kompile)
./kompile bootstrap

# Install all dependencies (GraalVM, Maven, Anaconda)
./kompile install all

# Install specific components
./kompile install graalvm
./kompile install python
```

### Model Conversion
```bash
# Convert TensorFlow model
./kompile model convert \
  --inputFile=model.pb \
  --outputFile=model.fb

# Convert Keras model
./kompile model convert \
  --inputFile=model.h5 \
  --outputFile=model.zip \
  --kerasNetworkType=sequential

# Convert ONNX model
./kompile model convert \
  --inputFile=model.onnx \
  --outputFile=model.fb
```

### Build RAG Applications
```bash
# Generate custom RAG app with selected modules
./kompile build-rag-app \
  --instanceId=myapp \
  --enableAnserini=true \
  --enableOpenAi=true \
  --enablePgvector=false

# Build hosted LLM RAG app (simplified)
./kompile build-hosted-llm-rag-app --instanceId=myapp

# Build SameDiff embedding app
./kompile build-samediff-app --instanceId=myapp
```

## Architecture

### Module Organization

**kompile-cli** (Main Entry Point)
- `ai.kompile.cli.main.MainCommand`: Picocli root command
- Subcommands: Info, Bootstrap, Config, Build, Install, Uninstall
- `RagPomGenerator`: Generates custom RAG application POMs
- GraalVM native image configuration in `kompile-cli/pom.xml`

**kompile-app** (RAG Framework - 40+ Modules)

Core Interfaces:
- `kompile-app-core`: Interface definitions
  - `EmbeddingModel`: embed(text) → INDArray
  - `VectorStore`: add/search/delete documents
  - `DocumentRetriever`: retrieve(query, k) → List<String>
  - `LanguageModel`: generate(prompt)

- `kompile-model-manager`: Centralized model download/caching
  - Downloads to `~/.kompile/models/`
  - SHA256 verification
  - Model descriptors in `ModelConstants`

Embeddings:
- `kompile-embedding-anserini`: SameDiff encoders (bge-base-en-v1.5, arctic-embed, etc.)
- `kompile-embedding-openai`: OpenAI API client
- `kompile-embedding-postgresml`: PostgreSQL ML
- `kompile-embedding-sentence-transformer`: Python subprocess wrapper

Vector Stores:
- `kompile-vectorstore-anserini`: Lucene-backed HNSW indexing (primary)
- `kompile-vectorstore-pgvector`: PostgreSQL pgvector extension
- `kompile-vectorstore-chroma`: Chroma client

Loaders:
- `kompile-loader-pdf-extended`: Advanced PDF parsing
- `kompile-loader-office`: Microsoft Office formats
- `kompile-loader-tika`: Apache Tika multi-format
- `kompile-loaders-orchestrator`: Coordinates multiple loaders

Tools:
- `kompile-tool-rag`: RAG query tool (Spring AI @Tool annotation)
- `kompile-tool-filesystem`: File system operations
- `kompile-graph-neo4j`: Graph RAG with Neo4j

**kompile-pipelines-framework**
- `kompile-pipelines-framework-api`: Core types (Configuration, StepConfig, Data)
- `kompile-pipelines-framework-core`: Pipeline execution engine
- `kompile-pipelines-framework-runtime`: Runtime support
- Pipeline steps: Python, SameDiff, DL4J, ONNX

**tokenizers-rust**
- Rust tokenizer library wrapper (HuggingFace tokenizers)
- C++ bridge layer
- JavaCPP bindings for JNI
- Platform-specific natives: linux-x86_64, macosx-arm64, windows-x86_64

**anserini**
- Fork of Anserini (Lucene-based IR toolkit)
- Custom SameDiff encoders for dense retrieval
- Model downloader: `AnseriniModelDownloader`

### Key Design Patterns

**Plugin Architecture**
- Spring Boot conditional beans: `@ConditionalOnProperty`
- Picocli command discovery via ClassGraph
- Interface-based pluggability (swap embeddings/vector stores)

**Factory Patterns**
- `AnseriniEncoderFactory`: Maps model IDs → encoder instances
- `SameDiffEncoderFactory`: Creates tokenizer-aware encoders
- `KompileModelManager`: Model download/cache factory

**Spring Configuration**
- `application.properties`: Server port, Anserini paths, LLM API keys
- Conditional bean registration based on `kompile.embedding.type`, `kompile.vectorstore.type`
- Multiple LLM providers: OpenAI, Anthropic, Google Gemini (Spring AI)

**Resource Management**
- ND4J workspace cleanup on shutdown
- OpenBLAS thread pool termination via reflection
- Native library extraction to temp directories
- Interrupt handling in embeddings (check `Thread.isInterrupted()`)

### RAG Data Flow

```
Documents → Loaders → Chunks → Embeddings → Vector Index

Query → Embed Query → Vector Search → Retrieved Context → LLM → Response
```

**Indexing Pipeline:**
1. Load documents (PDF, Office, Email via specialized loaders)
2. Parse and extract text/metadata
3. Chunk using token/sentence/recursive/markdown splitters
4. Generate embeddings (batch processing with interrupt checks)
5. Index to vector store (Lucene HNSW, PostgreSQL, or Chroma)

**Query Pipeline:**
1. Embed query using same model as indexing
2. Vector similarity search (COSINE, EUCLIDEAN, DOT_PRODUCT)
3. Retrieve top-k documents with metadata
4. Pass context to LLM via `RagToolImpl`
5. LLM generates response with retrieved context

### Model Conversion Workflow

**Supported Formats:**
- Input: TensorFlow (.pb, checkpoints), ONNX (.onnx), Keras (.h5, .keras)
- Output: DeepLearning4j (.zip), SameDiff (.fb), ONNX Runtime

**Conversion Process:**
1. Importers: `TensorFlowImporter`, `OnnxImporter`, `KerasImporter`
2. Model graph transformation
3. Serialization to target format
4. Optional: Register in `ModelConstants` for auto-download

**Model Registry:**
- `ModelConstants`: Central catalog of supported models
- `ModelDescriptor`: URL, SHA256, cache path, dimensions, type
- `ModelBundle`: Container with model + vocabulary + tokenizer config

### Native Library Loading

**JavaCPP Integration:**
- Platform-specific natives in `org/bytedeco/.../[platform]/`
- Property: `org.bytedeco.javacpp.pathsFirst=true` (load temp libs first)
- Exclusions in Maven shade plugin for unused platforms

**Tokenizers C++ Wrapper:**
- Rust library compiled to static lib (.rlib)
- C++ wrapper (`libtokenizers_wrapper.so/dylib/dll`)
- JavaCPP generates JNI bindings
- Platform manifest: `manifest.properties` with build metadata

**Resource Extraction:**
- `NativeLibraryExtractor`: Copies natives from JAR to temp dir
- Platform detection: `PlatformDetector` (OS + architecture)
- Cleanup on shutdown via shutdown hooks

## Common Development Tasks

### Adding a New Embedding Model

1. Register model in `kompile-model-manager/src/main/java/ai/kompile/modelmanager/ModelConstants.java`:
```java
public static final ModelDescriptor BGE_SMALL = ModelDescriptor.builder()
    .modelId("bge-small-en-v1.5")
    .url("https://example.com/model.tar.gz")
    .checksum("sha256:...")
    .dimensions(384)
    .modelType(ModelType.DENSE_ENCODER)
    .build();
```

2. Create encoder in `anserini/src/main/java/io/anserini/encoder/samediff/`:
```java
public class BgeSmallSameDiffEncoder extends GenericDenseSameDiffEncoder {
    public BgeSmallSameDiffEncoder() {
        super(ModelConstants.BGE_SMALL);
    }
}
```

3. Update factory in `AnseriniEncoderFactory`:
```java
case "bge-small-en-v1.5":
    return new BgeSmallSameDiffEncoder();
```

### Adding a New Vector Store

1. Implement interface in new module:
```java
@Component
@ConditionalOnProperty(name = "kompile.vectorstore.type", havingValue = "myvectorstore")
public class MyVectorStoreImpl implements VectorStore {
    @Override
    public void add(List<Document> documents) { ... }

    @Override
    public List<Document> similaritySearch(String query, int k) { ... }
}
```

2. Add configuration in `application.properties`:
```properties
kompile.vectorstore.type=myvectorstore
kompile.myvectorstore.url=http://localhost:8080
```

3. Update parent POM dependency management

### Adding a New Document Loader

1. Implement Spring AI DocumentReader:
```java
@Component
public class MyDocumentLoader implements DocumentReader {
    @Override
    public List<Document> get() {
        // Parse documents
        return documents;
    }
}
```

2. Register in orchestrator if needed:
```java
@Autowired(required = false)
private MyDocumentLoader myLoader;
```

### Customizing RAG Behavior

**Modify chunking strategy** in `application.properties`:
```properties
kompile.chunker.type=recursive
kompile.chunker.chunkSize=500
kompile.chunker.chunkOverlap=50
```

**Adjust retrieval** in `RagToolImpl`:
```java
@Tool(description = "...")
public Map<String, Object> ragQuery(RagQueryInput input) {
    int maxResults = input.getMaxResults() != null ? input.getMaxResults() : 5;
    List<String> docs = documentRetriever.retrieve(input.getQuery(), maxResults);
    return Map.of("documents", docs);
}
```

**Switch LLM provider** in `application.properties`:
```properties
# OpenAI
spring.ai.openai.api-key=sk-...
spring.ai.openai.chat.options.model=gpt-4

# Anthropic
spring.ai.anthropic.api-key=sk-ant-...
spring.ai.anthropic.chat.options.model=claude-3-5-sonnet-20241022
```

### Building Custom CLI Commands

1. Create command class:
```java
@Command(name = "mycommand", description = "...")
public class MyCommand implements Callable<Integer> {
    @Option(names = "--option")
    private String option;

    @Override
    public Integer call() throws Exception {
        // Implementation
        return 0;
    }
}
```

2. Register in `MainCommand`:
```java
@Command(subcommands = {
    ...,
    MyCommand.class
})
public class MainCommand { ... }
```

### Working with Native Images

**Add resource pattern** in `kompile-cli/pom.xml`:
```xml
<buildArg>-H:IncludeResources=my/resources/.*</buildArg>
```

**Initialize at runtime** if needed:
```xml
<buildArg>--initialize-at-run-time=com.example.MyClass</buildArg>
```

**Reflection configuration** (auto-generated via Picocli, or manual in `reflect-config.json`):
```json
{
  "name": "com.example.MyClass",
  "allDeclaredConstructors": true,
  "allDeclaredMethods": true
}
```

## Memory Management

### Build Memory Requirements
- Standard build: 4GB heap
- Native image build: 18-32GB heap
- Docker recommended allocation: 16GB

### Runtime Configuration
```bash
# CLI with custom heap
java -Xmx4g -jar kompile-cli.jar

# Spring Boot RAG app
java -Xmx8g -jar kompile-app-main.jar
```

### ND4J Memory
- Automatic workspace cleanup on shutdown
- OpenBLAS thread limiting: `ND4J_NUM_BLAS_THREADS=4`
- No pointer GC: `-Dorg.bytedeco.javacpp.nopointergc=true`

## Important Notes

### Platform-Specific Builds
- Maven profiles auto-detect platform: `platform-linux-x86_64`, `platform-macosx-arm64`, etc.
- Native exclusions reduce JAR size (exclude unused platform binaries)
- JavaCPP extension for AVX2/AVX512: `-Dlibnd4j.extension=avx2`

### Model Caching
- Models cached at `~/.kompile/models/`
- SHA256 verification before use
- Automatic extraction of tar.gz archives
- Model bundles include: model file + vocabulary + tokenizer config

### Interrupt Handling
- Embeddings check `Thread.currentThread().isInterrupted()` during batch processing
- Returns empty INDArray if interrupted (graceful cancellation)
- Document loaders support cancellation

### Resource Cleanup
- Spring Boot shutdown hook: `Nd4jCleanupAndExitHandler`
- Destroys ND4J workspaces
- Terminates OpenBLAS threads via reflection
- Cleans up temp native libraries

### GraalVM Initialization
- **Build-time**: Logging (SLF4J, Logback), static configs
- **Run-time**: ND4J, JNI (JavaCPP, Bytedeco), JGit, Netty Epoll
- Deadlock watchdog: 30s interval with exit on timeout

### Common Pitfalls
- **Out of memory during build**: Increase Maven heap with `-Xmx` flag
- **Native library not found**: Check `JAVACPP_PLATFORM` environment variable
- **Model download fails**: Verify network access and SHA256 checksum
- **Vector search returns no results**: Ensure same embedding model for indexing and querying
- **Spring beans not found**: Check `@ConditionalOnProperty` matches `application.properties`

## Repository Structure

```
kompile/
├── kompile-cli/                    # CLI application (Picocli)
│   └── src/main/java/ai/kompile/cli/main/
│       ├── MainCommand.java        # Entry point
│       ├── build/                  # Build commands
│       │   ├── RagPomGenerator.java
│       │   └── BuildRagApp.java
│       ├── install/                # Install commands
│       └── models/                 # Model conversion
├── kompile-app/                    # RAG framework (Spring Boot)
│   ├── kompile-app-core/           # Core interfaces
│   ├── kompile-app-main/           # Main application + web UI
│   ├── kompile-model-manager/      # Model download/cache
│   ├── kompile-embedding-*/        # Embedding implementations
│   ├── kompile-vectorstore-*/      # Vector store implementations
│   ├── kompile-loader-*/           # Document loaders
│   ├── kompile-tool-*/             # MCP/Spring AI tools
│   └── kompile-graph-neo4j/        # Graph RAG
├── kompile-pipelines-framework/    # Pipeline execution engine
│   ├── kompile-pipelines-framework-api/
│   ├── kompile-pipelines-framework-core/
│   └── kompile-pipeline-steps-parent/
│       ├── kompile-pipelines-steps-samediff/
│       ├── kompile-pipelines-steps-python/
│       └── kompile-pipelines-steps-onnx/
├── anserini/                       # Anserini (Lucene IR toolkit)
│   └── src/main/java/io/anserini/
│       ├── encoder/samediff/       # SameDiff encoders
│       ├── index/                  # Indexing
│       └── search/                 # Search
├── tokenizers-rust/                # Rust tokenizer bindings
│   ├── libtokenizers/              # C++ wrapper + build scripts
│   │   ├── buildnativetokenizers.sh
│   │   └── tokenizers/             # HuggingFace tokenizers (external)
│   ├── tokenizers-native/          # JavaCPP bindings
│   └── tokenizers-native-preset/   # Precompiled natives
├── model-conversion-utility/       # Standalone model converter
├── kompile-model-importer-*/       # Model importers (TensorFlow, ONNX, Keras)
└── pom.xml                         # Parent POM
```

## Key Dependencies

- **Java**: 17
- **Spring Boot**: 3.2.5
- **Spring AI**: 1.0.0
- **Picocli**: 4.7.6 (CLI framework)
- **ND4J**: 1.0.0-SNAPSHOT (BLAS compute)
- **Lucene**: Via Anserini (text + vector search)
- **JavaCPP**: 1.5.11 (JNI bindings)
- **GraalVM SDK**: 24.0.1 (native compilation)
- **Jackson**: 2.15.3 (JSON/YAML)
- **Lombok**: 1.18.38 (boilerplate reduction)

## Links & Resources

- Main docs: `./docs/` (HTML generated from AsciiDoc)
- Community: https://community.konduit.ai
- Eclipse DeepLearning4j: https://github.com/deeplearning4j/deeplearning4j
- Konduit Serving: https://github.com/KonduitAI/konduit-serving

## Lessons Learned & Best Practices

### Frontend-Backend Integration

**Problem**: Hard-coded backend port (8080) in Angular frontend caused API failures when running on different ports.

**Solution**: Dynamic API URL detection using `window.location` at runtime.

**Implementation**:
```typescript
// src/main/frontend/src/environments/environment.ts
function getApiUrl(): string {
  if (typeof window !== 'undefined' && window.location) {
    const protocol = window.location.protocol;
    const hostname = window.location.hostname;
    const port = window.location.port;
    return `${protocol}//${hostname}${port ? ':' + port : ''}/api`;
  }
  return '/api';
}

export const environment = {
  production: false,
  apiUrl: getApiUrl()
};
```

**Key Learnings**:
1. **Never hard-code ports** in frontend code - use dynamic detection
2. **Centralize API configuration** in a single environment file
3. **All services should inherit** from a base service that uses the environment config
4. **Spring Boot caches static files** at startup - must restart after frontend rebuilds
5. **Clean old build artifacts** - Angular may generate multiple versions of JS files with different hashes

**Files Modified**:
- `kompile-app/kompile-app-main/src/main/frontend/src/environments/environment.ts`
- `kompile-app/kompile-app-main/src/main/frontend/src/app/services/base.service.ts`
- `kompile-app/kompile-app-main/src/main/frontend/src/app/components/document-manager/document-debugger/document-debugger.component.ts`

### Maven Profile Best Practices

**Problem**: UI builds were always executed, slowing down backend-only development cycles.

**Solution**: Profile-based builds inspired by the campaign-builder project.

**Implementation**:
```xml
<!-- Build UI by default, but allow skipping with -Dskip.ui -->
<profile>
    <id>Build the UI</id>
    <activation>
        <property>
            <name>!skip.ui</name>
        </property>
    </activation>
    <!-- frontend-maven-plugin executions here -->
</profile>
```

**Key Learnings**:
1. **Use negative property activation** (`!skip.ui`) to make profiles active by default
2. **Separate concerns** - node install vs. UI build should be different profiles
3. **Use `prepare-package` phase** for resource copying (more reliable than `process-resources`)
4. **Document profile usage** clearly in comments and separate BUILD.md
5. **Test both scenarios** - with and without the profile active

**Performance Impact**:
- With UI: ~10-15 seconds
- Without UI (`-Dskip.ui`): ~2-3 seconds
- **5x speedup** for backend-only builds

### Angular Build Optimization

**Key Learnings**:
1. **Clear Angular cache** before rebuilding: `rm -rf .angular/cache dist/`
2. **Bundle size warnings** are informational - don't block the build
3. **Source maps** (`.map` files) may contain old code references but don't affect runtime
4. **Check the actual JS files** (not source maps) when verifying code changes
5. **Maven copies frontend assets** to both `target/classes/static/` and `src/main/resources/static/`

### Spring Boot Static Resource Serving

**Key Learnings**:
1. **Static resources are loaded at startup** - changes require application restart
2. **Multiple old JS files** can accumulate in `target/classes/static/` - clean them manually if needed
3. **`index.html` references** must match the actual JS filename (Angular changes hash on each build)
4. **Spring DevTools** doesn't always reload static resources properly - use manual restart
5. **Running with `spring-boot:run`** uses classes from `target/classes/`, not the JAR

### Debugging Frontend Issues

**Troubleshooting Steps**:
1. **Check what's actually served**: `curl http://localhost:PORT/main-*.js | grep "problem_string"`
2. **Verify build artifacts**: Check both `dist/`, `target/classes/static/`, and `src/main/resources/static/`
3. **Compare timestamps**: Ensure running instance uses the latest build
4. **Clear browser cache**: Hard refresh (Ctrl+Shift+R) or use incognito mode
5. **Check active profiles**: `mvn help:active-profiles` to verify build configuration
6. **Restart the application**: Spring Boot caches static files in memory

### Code Organization Patterns

**Centralized Configuration**:
```
environment.ts (defines apiUrl)
    ↓
base.service.ts (exports backendUrl from environment)
    ↓
document.service.ts, rag.service.ts, etc. (extend BaseService)
```

**Benefits**:
- Single source of truth for API configuration
- Easy to update across all services
- Testable and mockable
- Type-safe with TypeScript

### Build System Recommendations

**For New Developers**:
1. Always run `mvn clean install -Dui.deps` on first checkout
2. Use `mvn spring-boot:run` for development (includes UI build by default)
3. Use `mvn spring-boot:run -Dskip.ui` for faster backend iterations
4. Run full `mvn clean package` before committing to ensure everything works

**For CI/CD**:
1. Always build with UI: `mvn clean package` (no `-Dskip.ui`)
2. Cache `node_modules/` between builds for faster CI times
3. Verify static files are included in the final JAR
4. Test on the actual deployment port to catch any remaining hard-coded URLs

### Common Pitfalls to Avoid

1. ❌ **Don't hard-code localhost or ports** in frontend code
2. ❌ **Don't assume port 8080** - make it configurable
3. ❌ **Don't commit `target/` or `dist/`** to version control
4. ❌ **Don't forget to restart** Spring Boot after frontend changes
5. ❌ **Don't skip cleaning old builds** - use `mvn clean` regularly
6. ❌ **Don't use fallback URLs** like `|| 'http://localhost:8080/api'` - they hide problems
7. ❌ **Don't test only on port 8080** - verify on other ports too

### Testing Checklist

Before considering frontend changes complete:
- [ ] Test on default port (8080)
- [ ] Test on custom port (e.g., 9090)
- [ ] Verify no hard-coded URLs remain: `grep -r "8080" src/main/frontend/src`
- [ ] Check built JS files don't contain hard-coded ports
- [ ] Test document upload functionality
- [ ] Test all API endpoints
- [ ] Verify browser console shows no errors
- [ ] Test with Angular dev server + Spring Boot backend separately
- [ ] Build and run from JAR to verify packaging
