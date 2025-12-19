# KOMPILE CODEBASE ARCHITECTURE SUMMARY

## 1. OVERVIEW AND PROJECT STRUCTURE

Kompile is a comprehensive AI/ML application development and deployment platform built on Java/Maven ecosystem. It combines:
- Command-line interface (CLI) tools for model conversion and pipeline building
- Spring Boot-based RAG (Retrieval Augmented Generation) application framework
- Support for multiple embedding models and vector stores
- Pipelines framework for composing ML/AI workflows
- Native image compilation via GraalVM
- Rust tokenizer bindings via JavaCPP

**Current Version:** 0.1.0-SNAPSHOT  
**Java Target:** Java 17 (with GraalVM compatibility)

---

## 2. MODULE ORGANIZATION AND DEPENDENCIES

### A. ROOT MAVEN MODULES (pom.xml)

**Core Base Modules** (built first):
1. `kompile-cli-plugin-api` - Plugin API for CLI extensibility
2. `anserini` - Modified Anserini IR library fork
3. `kompile-model-importer-tensorflow` - TensorFlow model import
4. `kompile-model-importer-onnx` - ONNX model import  
5. `kompile-model-importer-keras` - Keras model import
6. `model-conversion-utility` - CLI utilities for model conversion

**Main Applications:**
7. `kompile-pipelines-framework` - Pipeline execution framework
8. `kompile-cli` - Command-line interface entry point
9. `kompile-app` - Spring Boot RAG application and plugins
10. `tokenizers-rust` - Native tokenizer bindings

### B. KOMPILE-APP SUBMODULES (Large ecosystem)

**Core Infrastructure:**
- `kompile-model-manager` - Model download/caching/management (uses `KompileModelManager`)
- `kompile-app-core` - Core interfaces (embeddings, vector stores, retrievers, LLM)
- `kompile-app-main` - Spring Boot entry point (`MainApplication`)

**Document Loaders:**
- `kompile-loader-pdf` - Basic PDF extraction
- `kompile-loader-pdf-extended` - Advanced PDF with layout analysis
- `kompile-loader-tika` - Apache Tika-based document parsing
- `kompile-loader-microsoft` - Microsoft Office document support
- `kompile-loader-mail` - Email document support
- `kompile-app-loaders-orchestrator` - Coordinates loader plugins

**Embedding Models (Multiple Implementations):**
- `kompile-embedding-anserini` - Uses Anserini SameDiffEncoder (Bge, ArcticEmbed, etc.)
- `kompile-embedding-samediff` - Direct SameDiff model loading
- `kompile-embedding-sentence-transformer` - Sentence-Transformers via Python
- `kompile-embedding-openai` - OpenAI embedding API
- `kompile-embedding-postgresml` - PostgresML vector embeddings

**Vector Stores:**
- `kompile-vectorstore-anserini` - Lucene-based dense vector index (HNSW support)
- `kompile-vectorstore-pgvector` - PostgreSQL pgvector backend
- `kompile-vectorstore-chroma` - Chroma vector database integration

**LLM Providers:**
- `kompile-app-openai-llm` - OpenAI chat models
- `kompile-app-anthropic-llm` - Anthropic Claude models
- `kompile-app-gemini-llm` - Google Gemini models
- `kompile-app-springai-llm` - Spring AI abstraction layer

**Text Chunking:**
- `kompile-chunker-token` - Token-based chunking
- `kompile-chunker-sentence` - Sentence-based chunking
- `kompile-chunker-recursivecharacter` - Recursive character splitting
- `kompile-chunker-markdown` - Markdown-aware chunking

**RAG and Tools:**
- `kompile-tool-rag` - RAG query tool (Spring AI Tool annotation)
- `kompile-tool-filesystem` - Filesystem access tool
- `kompile-graph-neo4j` - Neo4j graph RAG integration
- `kompile-app-pgml-indexer` - PostgresML indexing service

**Supporting Modules:**
- `kompile-postgres-common` - PostgreSQL common utilities
- `kompile-pipelines-app-llm` - Pipeline steps for LLM execution

### C. KOMPILE-PIPELINES-FRAMEWORK

**Structure:**
- `kompile-pipelines-framework-api` - Core interfaces (Configuration, StepConfig, Data types)
- `kompile-pipelines-framework-core` - Implementation base classes
- `kompile-pipelines-framework-runtime` - Pipeline execution engine (SequencePipeline)
- `kompile-pipeline-steps-parent` - Parent for step implementations
  - Python steps, SameDiff, DeepLearning4j, ONNX pipeline steps

---

## 3. KEY ARCHITECTURAL PATTERNS

### A. PLUGIN ARCHITECTURE

**CLI Plugin System:**
- `kompile-cli-plugin-api` defines `CliPlugin` interface
- Picocli-based command structure with `@CommandLine.Command` annotations
- Auto-discovery via ClassGraph for extending CLI subcommands

**Spring Boot Component Model:**
- Conditional bean registration via `@ConditionalOnProperty`
- Service interfaces allow swappable implementations
- Example: Multiple `EmbeddingModel` implementations auto-loaded based on properties

### B. FACTORY PATTERNS

**AnseriniEncoderFactory:**
```
EncoderType enum → {BGE, ARCTIC_EMBED, COS_DPR_DISTIL, SPLADE_PP_ED, GENERIC_DENSE}
  ↓
SameDiffEncoder<T> subclasses → BgeSameDiffEncoder, ArcticEmbedSameDiffEncoder, etc.
  ↓
Automatic model bundle management (model + vocab files)
```

**KompileModelManager:**
- Centralized model download/caching at `~/.kompile/models/`
- SHA256 checksum verification
- TAR.GZ extraction with automatic path resolution

### C. INTERFACE-BASED ABSTRACTION

**Core Service Interfaces** (kompile-app-core):
```java
EmbeddingModel {
  INDArray embed(String text)
  INDArray embed(List<String> texts)
  INDArray embedDocuments(List<Document> docs)
  int dimensions()
}

VectorStore {
  void add(List<Document> docs, List<List<Float>> embeddings)
  List<Document> similaritySearch(String query, int k)
  void delete(String documentId)
}

DocumentRetriever {
  List<String> retrieve(String query, int maxResults)
  List<RetrievedDoc> retrieveWithDetails(String query, int maxResults)
}

LanguageModel / ConversationalLanguageModel {
  String generate(String prompt)
  // Chat with message history
}

IndexerService {
  void indexDocuments(List<Document> docs, List<List<Float>> embeddings)
}
```

### D. SPRING AI INTEGRATION

**Tool Annotation Pattern:**
```java
@Tool(name = "rag_query", description = "...")
public Map<String, Object> executeRagQuery(RagQueryInput input)
```

Uses Spring AI's tool calling mechanism for LLM function execution.

---

## 4. EMBEDDING AND VECTOR STORE IMPLEMENTATIONS

### A. EMBEDDING MODEL HIERARCHY

```
EmbeddingModel (interface)
  ├── AnseriniEmbeddingModelImpl
  │     ├─ Uses: SameDiffEncoder + KompileModelManager
  │     ├─ Models: bge-base-en-v1.5, arctic-embed, cos-dpr, etc.
  │     └─ Returns: INDArray (ND4J)
  │
  ├── SameDiffEmbeddingModelImpl
  │     ├─ Direct SameDiff (.pb/.sdz file) loading
  │     ├─ Requires: modelUri, inputTensorName, outputTensorName
  │     └─ Placeholder tokenization (needs customization per model)
  │
  ├── SentenceTransformerEmbeddingModelImpl
  │     ├─ Python execution via subprocess
  │     └─ Hugging Face model support
  │
  ├── OpenAiEmbeddingModelImpl
  │     ├─ API call: OpenAI embeddings endpoint
  │     └─ External dependency
  │
  └── PostgresMlEmbeddingModelImpl
        ├─ SQL-based embedding: `pgml.embed()`
        └─ Database-backed
```

**Key Detail:** AnseriniEmbeddingModelImpl uses `ModelConstants.isEncoderModelAvailable(modelId)` to decide between:
1. Automatic bundle management (paired model + vocab files)
2. Legacy approach (manual path specification)

### B. VECTOR STORE IMPLEMENTATIONS

```
VectorStore (interface)
  ├── AnseriniVectorStoreImpl (Primary Implementation)
  │     ├─ Backend: Lucene with custom dense vector field
  │     ├─ Index: FSDirectory-based
  │     ├─ Features: 
  │     │   - HNSW indexing (configurable)
  │     │   - Similarity functions (COSINE, EUCLIDEAN, DOT_PRODUCT)
  │     │   - Flat + hierarchical search strategies
  │     └─ Config: AnseriniVectorStoreProperties
  │
  ├── PgvectorVectorStoreImpl
  │     ├─ Backend: PostgreSQL pgvector extension
  │     ├─ Operations: SQL-based CRUD + similarity search
  │     └─ Scalability: Database-backed (cluster-ready)
  │
  └── ChromaVectorStoreImpl
        ├─ Backend: Chroma Python server
        └─ Client-server architecture
```

**Integration Flow:**
1. Load embedding model (e.g., AnseriniEmbeddingModelImpl)
2. Create vector store pointing to embedding model
3. Index documents: document → embedding → vector storage
4. Query: query text → embedding → vector search → ranked results

---

## 5. MODEL CONVERSION AND IMPORT WORKFLOW

### A. MODEL IMPORTER MODULES

**Purpose:** Convert ML models from external frameworks to Java-friendly formats

**Supported Formats:**
- **TensorFlow** → `.pb` (SavedModel), `.tf` (checkpoint)
- **ONNX** → `.onnx` (standard format)
- **Keras** → `.h5`, `.keras`

**Conversion Targets:**
- DeepLearning4j format (`.zip`)
- SameDiff format (`.pb` or `.sdz`)
- Native executables (via ONNX Runtime, TensorFlow Runtime)

### B. MODEL CONSTANTS AND DESCRIPTORS

**ModelConstants.java** - Central registry of downloadable models:
```java
// Encoder models with automatic bundle management
bge-base-en-v1.5
arctic-embed-m
cos-dpr-distil-128
splade-pp-ed
splade-pp-sd
// Each model has ModelDescriptor with:
{
  modelId, 
  downloadUrl, 
  expectedCachePath, 
  checksum (SHA256), 
  metadata (dimensions, architecture, etc.),
  vocabDescriptor (paired vocabulary)
}
```

**ModelBundle.java** - Container returned by KompileModelManager:
```java
ModelBundle {
  String modelId,
  Path modelPath,      // Model artifact
  Path vocabularyPath, // Tokenizer vocab
  Map<String, Object> metadata,
  TokenizerConfig tokenizerConfig
}
```

### C. KOMPILE MODEL MANAGER

**Responsibilities:**
1. **Caching:** Download models once, cache at `~/.kompile/models/`
2. **Verification:** SHA256 checksum validation
3. **Extraction:** Automatic tar.gz decompression
4. **Bundle Management:** Pairing model files with vocabulary

**Key Methods:**
```java
ensureEncoderModelBundle(String modelId) → ModelBundle
ensureModelAvailable(ModelDescriptor descriptor) → Path
calculateSha256(Path path) → String
extractTarGz(Path tarGzPath, Path destinationDir) → void
```

---

## 6. RAG IMPLEMENTATION ARCHITECTURE

### A. RAG DATA FLOW

```
Document Sources
  ↓
Loaders (PDF, Tika, Office, Email) → List<Document>
  ↓
Chunkers (Token, Sentence, Recursive, Markdown) → Split chunks
  ↓
EmbeddingModel → INDArray embeddings
  ↓
VectorStore (Index) ← Documents + Embeddings
  
--- At Query Time ---

User Query
  ↓
EmbeddingModel.embed(query) → INDArray
  ↓
VectorStore.similaritySearch(embedding, k) → Top-k documents
  ↓
RagToolImpl.executeRagQuery() → Retrieved context
  ↓
LLM (with RAG context in prompt) → Final answer
```

### B. RAG TOOL IMPLEMENTATION

**RagToolImpl.java:**
```java
@Tool(name = "rag_query")
public Map<String, Object> executeRagQuery(RagQueryInput input) {
  // input = {query: String, maxResults: Integer}
  List<String> docs = documentRetriever.retrieve(query, maxDocs);
  return {
    "query": input.query(),
    "status": "Successfully retrieved documents",
    "retrieved_documents": docs
  };
}
```

**Bean Wiring:**
```java
@Autowired
public RagToolImpl(List<DocumentRetriever> retrievers, ObjectMapper mapper) {
  // Selects first non-NoOp retriever from Spring context
  // Falls back to NoOp if none found
}
```

### C. INDEXER SERVICE

**IndexerService Interface:**
```java
void indexDocuments(List<Document> docs, List<List<Float>> embeddings);
void deleteDocuments(List<String> docIds);
List<Document> searchSimilar(String query, int k);
```

**Implementations:**
- `AnseriniIndexerServiceImpl` - Lucene-based indexing
- `NoOpIndexerService` - Placeholder (no-op)

---

## 7. CLI AND BUILD SYSTEM

### A. CLI ENTRY POINT (MainCommand)

**Structure:**
```
MainCommand (picocli root)
  ├── Info - Show Kompile installation info
  ├── Bootstrap - Initialize SDK
  ├── ConfigMain - Configuration command generation
  ├── BuildMain - Build ML models/applications
  │     ├── BuildRagApp - Build RAG applications
  │     ├── BuildHostedLlmRagApp - Cloud-hosted RAG
  │     ├── BuildSameDiffApp - SameDiff-based apps
  │     └── KompileApplicationBuilder - Orchestrator
  ├── InstallMain - Install dependencies (GraalVM, Maven, Python)
  └── UnInstallMain - Uninstall cleanup
```

**Picocli Features:**
- Annotation-based command definition
- Auto-generated man pages and shell completion
- Subcommand composition
- Parameter validation

### B. RAG APPLICATION GENERATION

**RagPomGenerator.java:**
Generates Maven pom.xml for custom RAG instances with options:
```
--instanceGroupId          // Custom app group ID
--instanceArtifactId       // Custom app artifact ID
--databaseUrl             // PostgreSQL connection
--includeAppMain          // Include Spring Boot app
--includeLoadersOrchestrator // Document loading
--includeEmbeddingAnserini // Anserini embeddings
--includeVectorStoreAnserini // Lucene vector store
--includeLlmOpenAi        // OpenAI provider
--ragMcpVersion           // Kompile version to use
```

**Generates:** Customized Maven project ready for `mvn clean package`

### C. NATIVE IMAGE BUILD

**GraalVM Native Build Configuration:**
```
BuildArg: -J-Xmx32g       // Max heap for build
         --no-fallback     // Strict native image
         --allow-incomplete-classpath

Initialize at Build-Time:
  - org.slf4j.LoggerFactory
  - ch.qos.logback.classic
  
Initialize at Run-Time:
  - org.eclipse.deeplearning4j.linalg.factory.Nd4j
  - org.bytedeco.javacpp.Loader (JNI library loading)
  - org.eclipse.jgit (async file operations)
```

**Resource Inclusion:**
```
-H:IncludeResources=META-INF/services/.*
-H:IncludeResources=org/nd4j/.*
-H:IncludeResources=org/bytedeco/.*
```

---

## 8. SPRING BOOT APPLICATION STRUCTURE

### A. MainApplication Setup

**Key Responsibilities:**
1. ND4J initialization (thread limits, backend selection)
2. Native operations holder setup
3. Property configuration (file upload size limits)
4. Graceful shutdown handler for resource cleanup

**Thread Configuration:**
```java
// Prevent BLAS thread explosion
System.setProperty("org.nd4j.parallel.threads", "4");
System.setProperty("OMP_NUM_THREADS", "4");
System.setProperty("OPENBLAS_NUM_THREADS", "4");
System.setProperty("MKL_NUM_THREADS", "4");
```

### B. Spring Boot Component Scanning

```
@SpringBootApplication(scanBasePackages = "ai.kompile")
```

Auto-discovers and initializes:
- `@Service` components (embedding models, vector stores)
- `@Component` beans (tools, retrievers)
- `@Tool`-annotated methods (for LLM function calling)

### C. Configuration Properties

**application.properties highlights:**
```properties
server.port=8080
spring.lifecycle.timeout-per-shutdown-phase=30s

# Anserini/Lucene indexing
anserini.indexPath=./data/index
anserini.corpusPath=./data/anserini_corpus_json_staging

# Document sources
app.document.sources=./data/input_documents/sample.txt,...
app.document.uploads-path=./data/input_documents/uploads

# LLM Providers (environment variable-based)
spring.ai.anthropic.api-key=${ANTHROPIC_API_KEY}
spring.ai.vertex.ai.project-id=${GOOGLE_CLOUD_PROJECT_ID}

# Filesystem tool root
mcp.filesystem.roots.default.path=./data/shared_files
```

---

## 9. PIPELINES FRAMEWORK

### A. FRAMEWORK DESIGN

**Core Interfaces:**
```java
Configuration       // Marker for serializable configs
StepConfig         // Step configuration with parameters
Data               // Abstract data type
PipelineStep       // Executable step
Pipeline           // Orchestrates steps
```

**Data Types:**
```
Data (base)
  ├── NDArray     // ND4J INDArray wrapper
  ├── Image       // Image data with metadata
  ├── Point, BoundingBox // Geometry
  └── ValueType enum {NDARRAY, IMAGE, TEXT, FILE, ...}
```

### B. PIPELINE EXECUTION

**SequencePipeline:**
- Executes steps in sequence
- Passes output from step N as input to step N+1
- Supports error handling and rollback

**Step Types:**
- Python execution (subprocess)
- SameDiff model inference
- DeepLearning4j model execution
- ONNX model inference
- Image processing operations

### C. STEP CONFIGURATION

```java
GenericStepConfig {
  String stepId,
  String stepClassName (FQCN),
  Map<String, Object> parameters,
  List<String> inputs,
  List<String> outputs
}
```

Serialized to/from JSON for pipeline definition files.

---

## 10. NATIVE BINDINGS AND TOKENIZERS

### A. TOKENIZERS-RUST MODULE

**Purpose:** High-performance text tokenization via Rust library bindings

**Architecture:**
```
Rust Library (tokenizers-rs crate)
  ↓
C++ Wrapper (encoding_wrapper.h, tokenizer_wrapper.h)
  ↓
JavaCPP Bindings (org.bytedeco.javacpp.*)
  ↓
Java API (ai.kompile.bindings.Tokenizer)
  ↓
kompile-embedding-anserini (SamediffBertTokenizer)
```

**Submodules:**
- `libtokenizers` - C++ wrapper + CMake build
- `tokenizers-native` - JavaCPP bindings
- `tokenizers-native-preset` - Pre-compiled natives + Java API

**Platform Support:** Linux x86_64, macOS (x86_64 + ARM64), Windows x86_64

### B. TOKENIZER PREPROCESSING

**SamediffBertTokenizerPreProcessor:**
```java
preprocess(String text) → List<String> tokens
  ├─ Lowercase (optional)
  ├─ Strip accents
  ├─ Punctuation handling
  ├─ WordPiece tokenization
  └─ Special token insertion (CLS, SEP)
```

**SamediffBertVocabulary:**
- Loads vocabulary file (.txt)
- Maps tokens ↔ IDs
- Handles unknown tokens (UNK)

---

## 11. IMPORTANT WORKFLOWS AND PATTERNS

### A. EMBEDDING MODEL INITIALIZATION

**Pattern 1: Automatic Bundle Management**
```java
// AnseriniEmbeddingModelImpl constructor
this.encoder = AnseriniEncoderFactory.createEncoder(modelId);
// Automatically downloads and pairs model + vocab files
```

**Pattern 2: Manual Path Specification**
```java
new AnseriniEmbeddingModelImpl(
  modelId, 
  modelPath,
  vocabPath,
  inputTensorNames,
  outputTensorName,
  doLowerCase,
  maxSeqLen,
  addSpecialTokens
);
```

### B. DOCUMENT INDEXING WORKFLOW

```
1. Load documents from sources (PDF, Office, Email)
   ↓
2. Create loaders (PdfLoader, TikaLoader, MicrosoftLoader)
   ↓
3. Parse documents → List<Document> with content + metadata
   ↓
4. Chunk documents (Token, Sentence, Recursive chunker)
   ↓
5. Generate embeddings via EmbeddingModel
   ↓
6. Index chunks via VectorStore.add()
   ├─ Lucene: Create IndexWriter → add Documents with KnnFloatVectorField
   ├─ PgVector: INSERT into vector table
   └─ Chroma: PUT to Chroma HTTP API
```

### C. QUERY EXECUTION WORKFLOW

```
User Query String
  ↓
RagToolImpl.executeRagQuery(RagQueryInput)
  ├─ Validate query (non-empty)
  ├─ Determine maxResults (1-10, default 3)
  └─ Call DocumentRetriever.retrieve(query, maxDocs)
      ├─ EmbeddingModel.embed(query) → Vector
      ├─ VectorStore.similaritySearch(vector, k) → Top-k
      └─ Return formatted document snippets
  ↓
Format results in Map<String, Object>
  ├─ "query": input query
  ├─ "status": retrieval status
  └─ "retrieved_documents": list of text snippets
  ↓
Spring AI passes to LLM with prompt context
  ↓
LLM generates answer using RAG context
```

### D. MODEL CONVERSION WORKFLOW

```
Input Model (TensorFlow, ONNX, Keras)
  ↓
Model Importer (TensorFlow/OnnxImporter/KerasImporter)
  ├─ Load model from file
  ├─ Parse architecture + weights
  └─ Extract tensor shapes/types
  ↓
Convert to Target Format
  ├─ DeepLearning4j: Create ComputationGraph/MultiLayerNetwork
  ├─ SameDiff: Build SameDiff.graph()
  └─ ONNX Runtime: Direct inference via JNI
  ↓
Save converted model
  ├─ DL4j: serialize to .zip
  ├─ SameDiff: save as .pb/.sdz
  └─ ONNX: save as .onnx
```

---

## 12. NOTABLE IMPLEMENTATION DETAILS

### A. INTERRUPT HANDLING IN EMBEDDINGS

**AnseriniEmbeddingModelImpl:**
```java
@Override
public INDArray embed(String text) {
  if (Thread.currentThread().isInterrupted()) {
    return Nd4j.empty(DataType.FLOAT);
  }
  // ... encoding logic with interrupt checks
}
```
Allows graceful shutdown of embedding operations.

### B. ND4J RESOURCE CLEANUP

**Nd4jCleanupAndExitHandler:**
```java
@PreDestroy
public void cleanupAndExit() {
  Nd4j.getWorkspaceManager().destroyAllWorkspacesForCurrentThread();
  Nd4j.getMemoryManager().releaseCurrentContext();
  // Force OpenBLAS thread pool shutdown via reflection
  // Trigger JVM exit via external process
}
```
Critical for preventing native memory leaks and thread hangs.

### C. CONDITIONAL BEAN REGISTRATION

**Pattern:**
```java
@Service("anseriniEmbeddingModelImpl")
@ConditionalOnProperty(
  value = "kompile.embedding.anserini.enabled",
  havingValue = "true",
  matchIfMissing = true
)
public class AnseriniEmbeddingModelImpl implements EmbeddingModel { ... }
```
Enables/disables embedding providers via application.properties.

### D. REFLECTION-BASED NATIVE LIBRARY LOADING

**JavaCPP Integration:**
```java
System.setProperty("org.bytedeco.javacpp.pathsFirst", "true");
// Forces JavaCPP to load native libs before JNI system libs
```
Critical for tokenizer-rust and BLAS library precedence.

---

## 13. KEY DEPENDENCIES AND FRAMEWORKS

| Dependency | Version | Purpose |
|-----------|---------|---------|
| Spring Boot | 3.2.5 | Web framework + auto-configuration |
| Spring AI | 1.0.0 | LLM abstraction layer |
| Picocli | 4.7.6 | CLI command framework |
| ND4J | 1.0.0 | Numerical computing (BLAS backend) |
| Lucene | Latest | Text search + dense vector indexing |
| JavaCPP | 1.5.11 | JNI bindings generator |
| Lombok | 1.18.38 | Boilerplate reduction |
| Jackson | 2.15.3 | JSON/YAML serialization |
| JGit | 6.8.0 | Git operations |
| GraalVM SDK | 24.0.1 | Native image compilation |

---

## 14. DEPENDENCY GRAPH SUMMARY

```
kompile-cli (entry point)
  ├─ kompile-pipelines-framework
  ├─ kompile-cli-plugin-api
  └─ kompile-model-manager

kompile-app (Spring Boot app)
  ├─ kompile-app-core (interfaces)
  │   ├─ EmbeddingModel
  │   ├─ VectorStore
  │   ├─ DocumentRetriever
  │   └─ LanguageModel
  │
  ├─ kompile-embedding-* (implementations)
  │   └─ kompile-model-manager (for downloading)
  │
  ├─ kompile-vectorstore-* (implementations)
  │   └─ Spring AI / Lucene / PostgreSQL
  │
  ├─ kompile-loader-* (document parsing)
  ├─ kompile-tool-rag (RAG query tool)
  ├─ kompile-tool-filesystem (file access)
  └─ kompile-app-main (Spring Boot main)

anserini
  └─ kompile-model-manager (for models)
  └─ tokenizers-rust (for tokenization)

tokenizers-rust
  ├─ libtokenizers (C++ wrapper)
  ├─ tokenizers-native (JavaCPP bindings)
  └─ tokenizers-native-preset (presets)
```

---

## 15. COMMON DEVELOPER WORKFLOWS

### Building a Custom RAG Application

```bash
# 1. Generate RAG instance pom
kompile build rag-pom-generate \
  --instanceGroupId=com.example \
  --instanceArtifactId=my-rag-app \
  --includeEmbeddingAnserini \
  --includeVectorStoreAnserini \
  --includeLlmOpenAi \
  --outputFile=custom-pom.xml

# 2. Build with Maven
mvn clean package -f custom-pom.xml

# 3. Run RAG application
java -jar target/my-rag-app-0.1.0-SNAPSHOT.jar
```

### Adding Custom Embedding Model

1. Create descriptor in `ModelConstants.getAnseriniEncoderModelDescriptor()`
2. Provide model URL + vocab URL + checksums
3. Anserini automatically handles download + pairing via KompileModelManager
4. Configure via `application.properties`: `kompile.embedding.anserini.model-identifier=...`

### Implementing Custom Loader

1. Extend loader base class (or implement Document loading interface)
2. Implement `loadDocuments(InputStream) → List<Document>`
3. Register as Spring `@Component`
4. Auto-discovered by loaders orchestrator

---

## 16. CONCLUSION

Kompile is a sophisticated, modular architecture designed for:

1. **Flexibility:** Multiple embedding models, vector stores, LLM providers
2. **Scalability:** Support for distributed backends (PostgreSQL, Neo4j)
3. **Performance:** Native compilation via GraalVM, tokenizer acceleration via Rust
4. **Developer Experience:** CLI tools for model conversion, RAG app generation
5. **Production Readiness:** Graceful shutdown, resource cleanup, error handling

The codebase emphasizes **plugin architecture** (loaders, embeddings, tools) and **interface-based design** (pluggable implementations) for extensibility while maintaining a cohesive RAG platform.

Key architectural decision: **Anserini at core** - built on proven IR library with SameDiff encoder integration for dense retrieval.
