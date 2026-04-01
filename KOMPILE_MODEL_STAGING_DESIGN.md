# Kompile Model Staging Service - Architecture Design

## Overview

A standalone Spring Boot application that runs **separately from the main RAG app**, handling:
1. Model conversion (ONNX/TF/Keras → SameDiff) with FP16 weight pre-casting
2. GGML/GGUF model import with metadata extraction (for LLM inference)
3. VLM multi-model pipeline staging (vision encoder + decoder + embeddings + tokenizer)
4. Model download from external registries (GitHub Releases, HuggingFace)
5. Staging → Verification → Benchmark validation → Promotion workflow
6. Manifest generation for the production model directory
7. Air-gap support via import/export

**Key Principle**: The main kompile-app only reads from a local model directory and manifest files. No network calls, no conversion logic, no heavy dependencies - GraalVM friendly.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    CONNECTED ENVIRONMENT (Staging)                       │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │               kompile-model-staging-service                       │   │
│  │                    (Spring Boot App)                              │   │
│  ├──────────────────────────────────────────────────────────────────┤   │
│  │                                                                   │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌───────────────┐  ┌───────┐│   │
│  │  │   Download  │  │  Conversion │  │  Graph        │  │Staging││   │
│  │  │   Service   │──│   Service   │──│  Optimizer    │──│  Dir  ││   │
│  │  │             │  │ (SameDiff)  │  │               │  │       ││   │
│  │  │ - GitHub    │  │             │  │ - Dead code   │  │~/.kom-││   │
│  │  │ - HuggingFace│  │ - ONNX     │  │ - Const fold  │  │pile/  ││   │
│  │  │ - HTTP/S3   │  │ - TensorFlow│  │ - Algebraic   │  │stag-  ││   │
│  │  └─────────────┘  │ - Keras     │  │ - Activation  │  │ing/   ││   │
│  │                   │ - GGML/GGUF │  │ - RMSNorm     │  │       ││   │
│  │                   └─────────────┘  │ - Attention   │  └───────┘│   │
│  │                                    │ - Linear      │            │   │
│  │                                    │ - CuDNN       │            │   │
│  │                                    └───────────────┘            │   │
│  │                                                                   │   │
│  │  ┌─────────────────────────────────────────────────────────────┐ │   │
│  │  │                    Promotion Service                         │ │   │
│  │  │                                                              │ │   │
│  │  │  1. Verify checksum                                          │ │   │
│  │  │  2. Validate model loads                                     │ │   │
│  │  │  3. Run inference test                                       │ │   │
│  │  │  4. Run benchmark validation (throughput, diversity, etc.)   │ │   │
│  │  │  5. Generate manifest                                        │ │   │
│  │  │  6. Copy to production directory                             │ │   │
│  │  └─────────────────────────────────────────────────────────────┘ │   │
│  │                          │                                        │   │
│  │                          ▼                                        │   │
│  │  ┌──────────────────────────────────────────────────────────────┐│   │
│  │  │              Production Model Directory                      ││   │
│  │  │                                                              ││   │
│  │  │  ~/.kompile/models/                                          ││   │
│  │  │   ├── encoders/                                              ││   │
│  │  │   │   ├── bge-base-en-v1.5/                                  ││   │
│  │  │   │   │   ├── model.sdz                                      ││   │
│  │  │   │   │   ├── vocab.txt                                      ││   │
│  │  │   │   │   └── manifest.json                                  ││   │
│  │  │   │   └── arctic-embed-l/                                    ││   │
│  │  │   │       └── ...                                            ││   │
│  │  │   ├── cross-encoders/                                        ││   │
│  │  │   │   └── ms-marco-MiniLM-L-6-v2/                            ││   │
│  │  │   │       └── ...                                            ││   │
│  │  │   ├── vlm/                                                   ││   │
│  │  │   │   └── smoldocling/                                       ││   │
│  │  │   │       └── ...                                            ││   │
│  │  │   └── registry.json  ← Master registry of all models        ││   │
│  │  └──────────────────────────────────────────────────────────────┘│   │
│  └───────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  ┌────────────────────────────────────────────────────────────────────┐ │
│  │                      Export Service                                 │ │
│  │   Creates portable bundle for air-gap transfer:                     │ │
│  │   kompile-models-bundle-2025-01-15.tar.gz                           │ │
│  └────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────┘

                              ▼ Air-gap transfer ▼

┌─────────────────────────────────────────────────────────────────────────┐
│                   AIR-GAPPED ENVIRONMENT (Production)                    │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ┌────────────────────────────────────────────────────────────────────┐ │
│  │                   Import Service (CLI)                              │ │
│  │   kompile model import kompile-models-bundle-2025-01-15.tar.gz      │ │
│  │   - Validates checksums                                             │ │
│  │   - Extracts to ~/.kompile/models/                                  │ │
│  │   - Updates registry.json                                           │ │
│  └────────────────────────────────────────────────────────────────────┘ │
│                                                                          │
│  ┌────────────────────────────────────────────────────────────────────┐ │
│  │           kompile-app (Main RAG Application)                        │ │
│  │                                                                     │ │
│  │   KompileModelManager (Simplified - Read-Only)                      │ │
│  │    ├── Reads ~/.kompile/models/registry.json                        │ │
│  │    ├── Loads models from local directory                            │ │
│  │    ├── NO network calls                                             │ │
│  │    └── NO conversion logic                                          │ │
│  │                                                                     │ │
│  │   GraalVM Native Image Compatible                                   │ │
│  └────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Core Components

### 1. Production Model Directory Structure

```
~/.kompile/models/
├── registry.json                    # Master registry of all available models
├── encoders/
│   └── {model-id}/
│       ├── model.sdz               # SameDiff model file
│       ├── vocab.txt               # Vocabulary file
│       ├── tokenizer.json          # Tokenizer configuration (optional)
│       └── manifest.json           # Model-specific manifest
├── cross-encoders/
│   └── {model-id}/
│       └── ...
├── rerankers/
│   └── {model-id}/
│       └── ...
├── llm-ggml/                        # GGML/GGUF LLM models
│   └── {model-id}/
│       ├── model.gguf              # GGML model file (not converted)
│       ├── manifest.json           # Model metadata from GGUF header
│       └── ggml-info.json          # Extracted GGML metadata
├── vlm/                             # VLM multi-model pipeline bundles
│   └── {model-id}/
│       ├── vision-encoder.sdz      # Vision encoder (SameDiff)
│       ├── decoder.sdz             # Autoregressive decoder (SameDiff)
│       ├── embed-tokens.sdz        # Token embedding (SameDiff)
│       ├── tokenizer.json          # HuggingFace tokenizer
│       ├── tokenizer-config.json   # Tokenizer configuration
│       └── manifest.json           # Pipeline manifest with component metadata
└── .staging/                        # Staging area (hidden, managed by staging service)
    ├── pending/
    ├── verified/
    └── failed/
```

### 2. Registry File Format (`registry.json`)

```json
{
  "version": "1.0",
  "updated_at": "2025-01-15T10:30:00Z",
  "models": {
    "bge-base-en-v1.5": {
      "type": "encoder",
      "path": "encoders/bge-base-en-v1.5",
      "model_file": "model.sdz",
      "vocab_file": "vocab.txt",
      "checksum": "sha256:abc123...",
      "status": "active",
      "promoted_at": "2025-01-15T10:30:00Z",
      "metadata": {
        "embedding_dim": 768,
        "max_sequence_length": 512,
        "model_type": "dense",
        "framework": "samediff"
      },
      "tokenizer": {
        "do_lower_case": true,
        "add_special_tokens": true,
        "strip_accents": true,
        "max_length": 512
      }
    },
    "ms-marco-MiniLM-L-6-v2": {
      "type": "cross_encoder",
      "path": "cross-encoders/ms-marco-MiniLM-L-6-v2",
      "model_file": "model.sdz",
      "vocab_file": "vocab.txt",
      "checksum": "sha256:def456...",
      "status": "active",
      "promoted_at": "2025-01-15T10:25:00Z",
      "metadata": {
        "hidden_size": 384,
        "num_layers": 6,
        "max_sequence_length": 512,
        "output_type": "relevance_score"
      },
      "tokenizer": {
        "do_lower_case": true,
        "add_special_tokens": true,
        "strip_accents": true
      }
    }
  }
}
```

### 3. Model Manifest Format (`manifest.json` per model)

```json
{
  "model_id": "bge-base-en-v1.5",
  "version": "1.5.0",
  "type": "encoder",
  "source": {
    "origin": "huggingface",
    "repository": "BAAI/bge-base-en-v1.5",
    "original_format": "onnx",
    "conversion_date": "2025-01-15T10:00:00Z"
  },
  "files": {
    "model": {
      "name": "model.sdz",
      "size_bytes": 438000000,
      "checksum": "sha256:abc123..."
    },
    "vocab": {
      "name": "vocab.txt",
      "size_bytes": 231000,
      "checksum": "sha256:xyz789..."
    }
  },
  "runtime": {
    "framework": "samediff",
    "min_kompile_version": "1.0.0",
    "tested_kompile_version": "1.0.0"
  },
  "optimization": {
    "profile": "FULL",
    "iterations_run": 2,
    "total_optimizations_applied": 47,
    "ops_before": 312,
    "ops_after": 265,
    "report_file": "optimization-report.json"
  },
  "validation": {
    "validated_at": "2025-01-15T10:28:00Z",
    "inference_test_passed": true,
    "sample_output_checksum": "sha256:sample123..."
  }
}
```

---

## Module Structure

```
kompile-model-staging/
├── pom.xml
├── src/main/java/ai/kompile/staging/
│   ├── ModelStagingApplication.java           # Spring Boot main
│   │
│   ├── config/
│   │   ├── StagingConfig.java                 # Configuration properties
│   │   └── DirectoryConfig.java               # Path configurations
│   │
│   ├── registry/
│   │   ├── ModelRegistry.java                 # Registry read/write
│   │   ├── ModelEntry.java                    # Model entry POJO
│   │   └── RegistryService.java               # Registry operations
│   │
│   ├── download/
│   │   ├── DownloadService.java               # Generic download interface
│   │   ├── GitHubDownloader.java              # GitHub releases downloader
│   │   ├── HuggingFaceDownloader.java         # HuggingFace downloader
│   │   └── HttpDownloader.java                # Generic HTTP/S3 downloader
│   │
│   ├── conversion/
│   │   ├── ConversionService.java             # Main conversion orchestrator
│   │   ├── OnnxToSameDiffConverter.java       # ONNX → SameDiff
│   │   ├── ConversionResult.java              # Conversion result POJO
│   │   ├── ModelValidator.java                # Post-conversion validation
│   │   └── GraphOptimizationConfig.java       # Optimization pass configuration
│   │
│   ├── optimization/
│   │   ├── OptimizationService.java           # Graph optimization orchestrator
│   │   ├── OptimizationProfile.java           # Pre-defined optimization profiles
│   │   └── OptimizationReport.java            # Optimization results/stats
│   │
│   ├── staging/
│   │   ├── StagingService.java                # Staging workflow
│   │   ├── PromotionService.java              # Stage → Production promotion
│   │   └── StagingStatus.java                 # Status enum
│   │
│   ├── export/
│   │   ├── ExportService.java                 # Create portable bundles
│   │   ├── ImportService.java                 # Import bundles
│   │   └── BundleManifest.java                # Bundle metadata
│   │
│   ├── cli/
│   │   ├── ModelStagingCLI.java               # Picocli CLI wrapper
│   │   ├── DownloadCommand.java               # kompile-staging download
│   │   ├── ConvertCommand.java                # kompile-staging convert
│   │   ├── PromoteCommand.java                # kompile-staging promote
│   │   ├── ExportCommand.java                 # kompile-staging export
│   │   ├── ImportCommand.java                 # kompile-staging import
│   │   └── ListCommand.java                   # kompile-staging list
│   │
│   └── web/
│       ├── StagingController.java             # REST API (optional web UI)
│       └── dto/
│           └── ...
│
└── src/main/resources/
    ├── application.yml
    └── model-sources.yml                      # External registry sources
```

---

## Simplified KompileModelManager (for main app)

The existing `KompileModelManager` is refactored to be read-only:

```java
/**
 * Simplified, GraalVM-compatible model manager.
 *
 * ONLY reads from local directory - no network calls, no conversion.
 * All model management done by kompile-model-staging service.
 */
@Component
public class KompileModelManager {

    private static final Path DEFAULT_MODEL_DIR =
        Paths.get(System.getProperty("user.home"), ".kompile", "models");

    private final Path modelDir;
    private final ModelRegistry registry;

    public KompileModelManager() {
        this(DEFAULT_MODEL_DIR);
    }

    public KompileModelManager(Path modelDir) {
        this.modelDir = modelDir;
        this.registry = loadRegistry();
    }

    private ModelRegistry loadRegistry() {
        Path registryPath = modelDir.resolve("registry.json");
        if (!Files.exists(registryPath)) {
            return ModelRegistry.empty();
        }
        // Simple JSON read - no network, no heavy deps
        return JsonUtil.read(registryPath, ModelRegistry.class);
    }

    /**
     * Get an encoder model bundle if available locally.
     */
    public Optional<ModelBundle> getEncoderModel(String modelId) {
        ModelEntry entry = registry.getModel(modelId);
        if (entry == null || !entry.isActive()) {
            return Optional.empty();
        }

        Path modelPath = modelDir.resolve(entry.getPath())
                                  .resolve(entry.getModelFile());
        Path vocabPath = modelDir.resolve(entry.getPath())
                                  .resolve(entry.getVocabFile());

        if (!Files.exists(modelPath)) {
            return Optional.empty();
        }

        return Optional.of(new ModelBundle(
            modelId,
            modelPath,
            vocabPath,
            entry.getMetadata(),
            entry.getTokenizerConfig()
        ));
    }

    /**
     * List all available encoder models.
     */
    public List<String> listEncoderModels() {
        return registry.getModelsByType("encoder");
    }

    /**
     * Check if a model is available locally.
     */
    public boolean isModelAvailable(String modelId) {
        return getEncoderModel(modelId).isPresent();
    }
}
```

---

## SameDiff Graph Optimizations

When converting models from ONNX/TensorFlow/Keras to SameDiff, the staging service applies graph optimizations to improve inference performance. These optimizations are provided by the SameDiff `GraphOptimizer` in deeplearning4j and run automatically during the conversion pipeline.

### Optimization Pipeline

```
ONNX Model → Import to SameDiff → Graph Optimization Passes → Serialize (.sdz)
                                         │                            │
                                         ├── Pass 1: Dead code elimination
                                         ├── Pass 2: Constant folding          Runtime:
                                         ├── Pass 3: Algebraic simplification     │
                                         ├── Pass 4: Identity removal             Load .sdz
                                         ├── Pass 5: Shape fusion                    │
                                         ├── Pass 6: Activation fusion               ▼
                                         ├── Pass 7: Normalization fusion     Triton JIT Compile
                                         ├── Pass 8: Linear fusion                   │
                                         ├── Pass 9: Attention fusion                ▼
                                         ├── Pass 10: Dead code elimination   CUDA Graph Capture
                                         ├── Pass 11: FP16 weight pre-cast           │
                                         └── Pass 12: CuDNN layout optimization      ▼
                                                                              Inference (Decode)
```

Optimization passes run up to 3 iterations (configurable via `nd4j.optimizer.maxIterations`), with early exit when no further optimizations apply.

### Available Optimization Passes

#### 1. UnusedFunctionOptimizations (Dead Code Elimination)
Runs twice in the pipeline (first and second-to-last) to clean up before and after other passes.

| Optimizer | Description |
|-----------|-------------|
| `RemoveUnusedConstants` | Removes constant variables not consumed by any operation. Stores arrays via recovery function for potential disk-based caching. |

#### 2. ConstantFunctionOptimizations (Constant Folding)
Pre-executes operations whose inputs are all constants at staging time.

| Optimizer | Description |
|-----------|-------------|
| `FoldConstantFunctions` | Evaluates ops with all-constant inputs and replaces with computed constant. Respects max output size limit (default 4MB, configurable via `optimizer.constants.function.max.output.size`). |

#### 3. AlgebraicOptimizations (Algebraic Simplification)
Eliminates trivial arithmetic operations. Inspired by the Luminal compiler.

| Optimizer | Pattern | Replacement |
|-----------|---------|-------------|
| `AddZero` | `x + 0` or `0 + x` | `x` |
| `SubtractZero` | `x - 0` | `x` |
| `MultiplyOne` | `x * 1` or `1 * x` | `x` |
| `MultiplyZero` | `x * 0` or `0 * x` | `0` |
| `SubtractSelf` | `x - x` | `0` |
| `DivideOne` | `x / 1` | `x` |

#### 4. IdentityFunctionOptimizations
Removes no-op operations.

| Optimizer | Description |
|-----------|-------------|
| `RemoveIdentityPermute` | Removes `permute([0, 1, 2, ..., rank-1])` operations (identity permutation). |
| `RemoveIdentityOps` | Removes explicit `identity(x)` operations. |

#### 5. ShapeFunctionOptimizations
Fuses chains of shape-manipulating operations.

| Optimizer | Description |
|-----------|-------------|
| `FuseChainedPermutes` | Fuses `permute(permute(x, p1), p2)` → `permute(x, composite(p1, p2))`. Removes all permutes if the composed result is identity. |
| `FuseChainedReshapes` | Fuses consecutive reshapes into a single reshape with the final target shape. |
| `FuseChainedConcatOps` | Flattens nested concatenations on the same dimension into a single concat with all inputs. |

#### 6. ActivationFusionOptimizations (LLM-focused)
Detects decomposed activation functions common in ONNX-exported transformer models.

| Optimizer | Pattern | Replacement |
|-----------|---------|-------------|
| `FuseSigmoidMulToSwish` | `sigmoid(x) * x` | `swish(x)` (SiLU activation) |
| `FuseSwiGLUPattern` | `swish(x) * y` | `swish_mul(x, y)` (SwiGLU gate, used in LLaMA/Mistral) |
| `FuseDecomposedSoftmax` | `reduce_max → sub → exp → reduce_sum → div` | `softmax` (ONNX decomposition pattern) |

#### 7. NormalizationFusionOptimizations (Transformer-focused)
Detects decomposed normalization patterns from ONNX exports.

| Optimizer | Pattern | Replacement |
|-----------|---------|-------------|
| `FuseRMSNormPattern` | **Pattern A (LLaMA):** `mul(x,x) → mean → add(eps) → rsqrt → mul(x,_) → mul(_,weight)` | `rms_norm(x, weight, eps)` |
| | **Pattern B (ONNX):** `pow(x,2) → reduce_mean → add(eps) → sqrt → div(x,_) → mul(_,weight)` | `rms_norm(x, weight, eps)` |

#### 8. LinearFusionOptimizations (Transformer Linear Layers)
Fuses matrix multiply + bias add patterns.

| Optimizer | Pattern | Replacement |
|-----------|---------|-------------|
| `FuseMatMulWithAdd` | `matmul(x, w) + bias` | `xw_plus_b(x, w, bias)` — preserves transpose flags |
| `FuseTensorMmulWithAdd` | `tensormmul(x, w) + bias` | `xw_plus_b(x, w, bias)` — handles ONNX MatMul imported as TensorMmul |
| ~~`FuseConsecutiveReshapes`~~ | *(disabled — bug in output shape computation)* | |

#### 9. AttentionFusionOptimizations (Transformer Attention)
Detects and fuses multi-step attention implementations into single fused ops.

| Optimizer | Pattern | Replacement |
|-----------|---------|-------------|
| `FuseManualAttentionPattern` | `matmul(Q, K^T) → scale → softmax → matmul(_, V)` | `dot_product_attention_v2(Q, K, V, scale, dropout_rate)` — traces backward to identify complete subgraph |
| `FuseAttentionWithCausalMask` | Attention pattern with causal mask applied | `dot_product_attention_v2(..., useCausalMask=true)` |
| `FuseAttentionWithProjection` | *(placeholder)* Attention + output linear projection | Future: fused attention+projection |

#### 12. CuDNNFunctionOptimizations (CUDA Backend)
Backend-specific layout transformations for GPU tensor core utilization.

| Optimizer | Description |
|-----------|-------------|
| `CudnnConv2dNCHWtoNHWCConversion` | Converts Conv2D from NCHW → NHWC activation layout and OIYX → OYXI weight format for cuDNN Tensor Core acceleration. Only applies when CUDA backend is active. |

#### 11. QuantizationOptimizations (FP16 Weight Pre-Casting)
Pre-casts FP32 weight constants to FP16 at import time, halving weight memory bandwidth. This is more efficient than runtime `dspFp16Compute` because it eliminates per-inference cast overhead — the cast happens once during model import rather than on every forward pass.

| Optimizer | Description |
|-----------|-------------|
| `FP16WeightPreCast` | Scans all `CONSTANT` variables with FLOAT dtype and casts them to HALF (FP16) at import time. The ND4J `MmulHelper` mixed-type path handles HALF×FLOAT matmul natively, so downstream ops work without modification. Controlled by `nd4j.optimizer.fp16` (default: `true`). |

**Why optimizer FP16 beats runtime FP16:**
- **Optimizer FP16 (`nd4j.optimizer.fp16`)**: 1 cast at import → weights stored as FP16 → zero runtime cast overhead
- **Runtime FP16 (`dspFp16Compute`)**: 2 casts per matmul (input FLOAT→HALF before, output HALF→FLOAT after) on every inference

**Note:** INT8/INT4 quantization passes are planned for future implementation.

### Optimization Profiles

The staging service offers pre-defined optimization profiles for different use cases:

```java
public enum OptimizationProfile {
    /**
     * All default optimizations. Best for production deployment.
     * Passes: all 12 optimization sets
     */
    FULL,

    /**
     * Skip attention/activation/normalization fusion.
     * Use for non-transformer models (CNNs, simple encoders).
     */
    BASIC,

    /**
     * Transformer-specific fusions only.
     * Algebraic + Identity + Activation + Normalization + Linear + Attention.
     */
    TRANSFORMER,

    /**
     * GPU-optimized: Full + CuDNN layout conversions.
     * Use when target deployment is CUDA.
     */
    GPU,

    /**
     * No optimizations. Import model as-is.
     */
    NONE
}
```

### Configuration

**System Properties:**

| Property | Default | Description |
|----------|---------|-------------|
| `nd4j.optimizer.maxIterations` | `3` | Maximum number of optimization loop iterations |
| `nd4j.optimizer.logApplied` | `false` | Log each applied optimization to stdout |
| `nd4j.optimizer.disabledSets` | `""` | Comma-separated optimizer set class names to disable (e.g., `AttentionFusionOptimizations,CuDNNFunctionOptimizations`) |
| `nd4j.optimizer.fp16` | `true` | Pre-cast FP32 weight constants to FP16 at import time (halves memory bandwidth) |
| `nd4j.optimizer.enabled` | `true` | Enable/disable GraphOptimizer entirely |
| `optimizer.constants.function.max.output.size` | `4194304` (4MB) | Maximum output size in bytes for constant folding |
| `libnd4j.triton` | `OFF` | Enable Triton kernel compilation (`ON`/`OFF`) |

**Staging Service Configuration (`application.yml`):**

```yaml
kompile:
  staging:
    optimization:
      profile: FULL                    # FULL, BASIC, TRANSFORMER, GPU, NONE
      max-iterations: 3
      log-applied: false
      disabled-sets: []                # Explicitly disable specific sets
      constant-fold-max-size: 4194304  # 4MB max for constant folding
```

**CLI Usage:**

```bash
# Convert with default (FULL) optimizations
kompile-staging convert \
  --input=model.onnx \
  --output=model.sdz \
  --type=encoder

# Convert with specific profile
kompile-staging convert \
  --input=model.onnx \
  --output=model.sdz \
  --type=encoder \
  --optimization-profile=TRANSFORMER

# Convert with no optimizations
kompile-staging convert \
  --input=model.onnx \
  --output=model.sdz \
  --optimization-profile=NONE

# Convert with specific optimizations disabled
kompile-staging convert \
  --input=model.onnx \
  --output=model.sdz \
  --disable-optimizations=CuDNNFunctionOptimizations,QuantizationOptimizations

# Convert and log applied optimizations
kompile-staging convert \
  --input=model.onnx \
  --output=model.sdz \
  --log-optimizations
```

### Optimization Report

After conversion, the staging service generates an optimization report stored alongside the model:

```json
{
  "model_id": "bge-base-en-v1.5",
  "optimization_profile": "FULL",
  "iterations_run": 2,
  "total_optimizations_applied": 47,
  "duration_ms": 1250,
  "before": {
    "num_ops": 312,
    "num_variables": 489,
    "num_constants": 98
  },
  "after": {
    "num_ops": 265,
    "num_variables": 421,
    "num_constants": 91
  },
  "passes_applied": {
    "RemoveUnusedConstants": 7,
    "FoldConstantFunctions": 12,
    "AddZero": 3,
    "MultiplyOne": 2,
    "RemoveIdentityOps": 8,
    "FuseChainedReshapes": 2,
    "FuseDecomposedSoftmax": 6,
    "FuseMatMulWithAdd": 5,
    "FuseRMSNormPattern": 0,
    "FuseManualAttentionPattern": 2
  },
  "disabled_sets": []
}
```

This report is included in the model manifest for traceability:

```json
{
  "model_id": "bge-base-en-v1.5",
  "source": {
    "origin": "huggingface",
    "original_format": "onnx",
    "conversion_date": "2025-01-15T10:00:00Z"
  },
  "optimization": {
    "profile": "FULL",
    "ops_removed": 47,
    "report_file": "optimization-report.json"
  }
}
```

---

## Triton Runtime Compilation

After graph-level optimization and serialization to `.sdz`, production models can leverage **Triton JIT compilation** for GPU kernel generation. This is a runtime capability — Triton kernels are compiled when the model is first loaded for inference, then cached and replayed via CUDA graphs.

### What Triton Integration Provides

SameDiff ops are JIT-compiled into optimized Triton GPU kernels at model load time. This replaces the default CUDA C++ kernel dispatch with fused, auto-tuned Triton kernels that reduce kernel launch overhead and improve memory locality.

### Compilable Op Types

| Op Category | Examples | Notes |
|-------------|----------|-------|
| `ELEMENTWISE` | add, mul, sigmoid, swish, gelu | Fuses chains of elementwise ops into single kernels |
| `REDUCTION` | reduce_mean, reduce_sum, reduce_max | Thread-block reductions |
| `NORMALIZATION` | rms_norm, layer_norm | Fused normalize + scale |
| `GATHER` | gather, embedding_lookup | Index-based memory access patterns |
| `STACK` | stack, concat along new axis | Memory layout transformations |
| `CONCAT` | concat along existing axis | Contiguous memory assembly |
| `SPLIT` | split, chunk | View-based zero-copy where possible |
| `CONST_GEN` | zeros, ones, fill | Constant tensor generation |
| `ATTENTION` | dot_product_attention_v2 | Fused Q·K^T·V with causal mask |

**MATMUL is excluded** — cuBLAS/cuBLASLt is ~2.8x faster than Triton for dense matmul on current hardware. Triton handles everything around the matmul (pre/post processing, attention scaffolding).

### Section Fusion

Multiple consecutive ops are fused into single Triton kernels when their access patterns are compatible. For example:
- `matmul → add_bias → rms_norm → swish` becomes 3 kernels: cuBLAS matmul, then a fused `add+rms_norm+swish` Triton kernel
- Fusion scoring (`fusionMinScore`) controls the aggressiveness of fusion decisions

### CUDA Graph Capture

After Triton compilation, the entire decode loop can be captured as a CUDA graph:
1. **Warm-up**: Run one decode step to compile all Triton kernels
2. **Capture**: Record the full decode step as a CUDA graph
3. **Replay**: Subsequent decode steps replay the graph with updated arguments via a consolidated arg table

The **consolidated arg table** with **dirty tracking** ensures only modified kernel arguments are updated between graph replays, minimizing CPU-side overhead.

### Execution Modes

| Mode | Description | Use Case |
|------|-------------|----------|
| `SLOT_BY_SLOT` | Default SameDiff execution, no Triton | Baseline, debugging, CPU inference |
| `CUDA_GRAPHS` | CUDA graph capture without Triton | GPU inference without Triton compilation |
| `TRITON_DSP` | Full Triton compilation + CUDA graphs | Production GPU inference |

### Tuning Parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `numWarps` | `4` | Number of warps per thread block (4 is optimal for most VLM decode) |
| `numStages` | `1` | Pipeline stages for memory prefetching |
| `numCTAs` | `1` | Cooperative thread arrays (multi-SM kernels) |
| `blockN` | `128` | Block size along N dimension |
| `fusionMinScore` | `0.5` | Minimum score for section fusion (0.0–1.0) |
| `tf32` | `true` | Enable TF32 mode for Ampere+ GPUs (cuBLAS) |

### Performance Profiles

| Profile | Description | Target |
|---------|-------------|--------|
| `DEBUG_FAST` | Minimal compilation, no fusion | Quick iteration during development |
| `BALANCED` | Moderate fusion, standard tuning | General-purpose inference |
| `MAX_PERF` | Aggressive fusion, all optimizations | Maximum throughput benchmarking |
| `OPTIMAL` | Best-known configuration for target hardware | Production deployment |

**Best-known configuration** (OPTIMAL for VLM decode):
- `compileAll=true` + ATTENTION + NORMALIZATION op compilation
- CUDA graph capture enabled
- Consolidated arg table with dirty tracking
- `batchedGemm=true`, `warps=4`, `stages=1`, `tf32=true`
- Target: 100+ tok/s for VLM decode on modern GPUs

---

## VLM Multi-Model Pipeline Support

Vision-Language Models (VLMs) require coordinated staging of multiple model components as a single pipeline bundle, rather than individual model staging.

### VLM Pipeline Structure

A VLM pipeline consists of four coordinated artifacts:

| Component | Description | Format |
|-----------|-------------|--------|
| `vision_encoder` | Processes image tiles into visual embeddings | `.sdz` (SameDiff) |
| `decoder` | Autoregressive transformer for text generation | `.sdz` (SameDiff) |
| `embed_tokens` | Token embedding lookup table | `.sdz` (SameDiff) |
| `tokenizer` | Text tokenization (HuggingFace-compatible) | `tokenizer.json` + `tokenizer-config.json` |

### Model Directory Layout

```
~/.kompile/models/
├── vlm/
│   └── smoldocling/
│       ├── vision-encoder.sdz
│       ├── decoder.sdz
│       ├── embed-tokens.sdz
│       ├── tokenizer.json
│       ├── tokenizer-config.json
│       └── manifest.json
├── encoders/
│   └── ...
└── registry.json
```

### Pipeline Context

The full VLM staging pipeline:

```
Download (ONNX)
    │
    ▼
ONNX Import (with OnnxModelCache)
    │  ├── vision_encoder.onnx → vision-encoder.sdz
    │  ├── decoder.onnx → decoder.sdz
    │  └── embed_tokens.onnx → embed-tokens.sdz
    │
    ▼
Graph Optimization (per-component)
    │  ├── FP16 weight pre-casting
    │  ├── Attention fusion (decoder)
    │  ├── Normalization fusion (all components)
    │  └── Activation fusion (SwiGLU in decoder)
    │
    ▼
Serialize (.sdz per component)
    │
    ▼
Runtime: Load → Vision Preprocessing → Embedding Merge → Triton-Compiled Decode
```

### OnnxModelCache Integration

`OnnxModelCache` provides import-time caching with `.sdz` serialization. For VLM pipelines, `importAllWithCache()` imports all components in a single call:

```java
// Multi-model import with caching
Map<String, SameDiff> models = OnnxModelCache.importAllWithCache(
    Map.of(
        "vision_encoder", visionEncoderOnnxPath,
        "decoder", decoderOnnxPath,
        "embed_tokens", embedTokensOnnxPath
    ),
    cacheDir,
    optimizerConfig  // GraphOptimizer applied during import
);
```

On subsequent loads, `.sdz` files are loaded directly from cache, skipping ONNX import and optimization entirely.

### VLM Manifest Metadata

```json
{
  "model_id": "smoldocling",
  "type": "vlm_pipeline",
  "version": "1.0.0",
  "components": {
    "vision_encoder": {
      "file": "vision-encoder.sdz",
      "ops": 245,
      "hidden_size": 1152
    },
    "decoder": {
      "file": "decoder.sdz",
      "ops": 1847,
      "hidden_size": 2048,
      "vocab_size": 49152,
      "num_layers": 24
    },
    "embed_tokens": {
      "file": "embed-tokens.sdz",
      "ops": 3,
      "vocab_size": 49152,
      "hidden_size": 2048
    }
  },
  "vision": {
    "max_frames": 768,
    "tile_size": 384,
    "image_size": [3, 384, 384]
  },
  "tokenizer": {
    "file": "tokenizer.json",
    "config_file": "tokenizer-config.json"
  }
}
```

### Updated ModelType Enum

The `ModelType` enum is extended for VLM support:

```java
public enum ModelType {
    // Existing types
    DENSE_ENCODER,       // Dense embedding encoder (e.g., bge-base-en-v1.5)
    SPARSE_ENCODER,      // Sparse encoder (e.g., SPLADE)
    CROSS_ENCODER,       // Cross-encoder for reranking
    RERANKER,            // Standalone reranker

    // GGML/GGUF types
    LLM_GGML,           // GGML/GGUF language model

    // VLM types
    VLM_VISION_ENCODER,  // Vision encoder component of a VLM pipeline
    VLM_DECODER,         // Autoregressive decoder component
    VLM_EMBED_TOKENS,    // Token embedding component
    VLM_PIPELINE         // Complete VLM pipeline bundle (references component models)
}
```

---

## DSP Runtime Optimization Flags

In addition to graph-level optimizations applied during staging, SameDiff's DSP (Data-Stream Processor) layer provides runtime flags that tune execution behavior. These complement the staging-time optimizations and are configured at deployment time.

| Flag | Description | Default |
|------|-------------|---------|
| `dspBatchedGemm` | Batch multiple GEMM operations for improved GPU utilization | `false` |
| `dspCastElimination` | Remove redundant cast operations in the execution stream | `false` |
| `dspFp16Compute` | Runtime FP16 computation (less efficient than optimizer FP16 pre-cast — adds 2 casts per matmul) | `false` |
| `dspBatchZero` | Batch zero-fill operations to reduce kernel launch overhead | `false` |
| `dspBatchZeroKernel` | Use a single kernel for batched zero-fill (vs. individual memsets) | `false` |
| `dspCastSinkMatmul` | Sink cast ops into matmul for fused execution (reduces intermediate buffers) | `false` |
| `cublasTf32` | Enable TF32 mode for Ampere+ GPUs (19-bit mantissa, ~2x throughput vs FP32) | `false` |

### Interaction with Staging-Time Optimization

```
Staging Time                          Runtime
─────────────                         ───────
GraphOptimizer passes                 DSP flags
├── FP16 weight pre-cast ──────────── (makes dspFp16Compute unnecessary)
├── Attention fusion ──────────────── dspBatchedGemm (batches fused attention matmuls)
├── Dead code elimination              dspCastElimination (removes remaining casts)
└── Constant folding                   cublasTf32 (hardware acceleration)
```

**Recommendation**: Use staging-time FP16 pre-casting (`nd4j.optimizer.fp16=true`) instead of runtime `dspFp16Compute`. Enable `dspBatchedGemm` and `cublasTf32` for production GPU deployments.

---

## Benchmark Validation in Promotion Service

The Promotion Service is extended to include performance benchmarking as part of the staging → production promotion workflow. This uses a `BenchmarkConfig`-based validation approach.

### Promotion Workflow (Updated)

```
Staging Model (.sdz)
    │
    ▼
1. Verify checksum
    │
    ▼
2. Validate model loads (SameDiff.load)
    │
    ▼
3. Run inference test (basic correctness)
    │
    ▼
4. Run benchmark validation  ← NEW
    │  ├── Throughput target (e.g., ≥90 tok/s for VLM decode)
    │  ├── Token diversity check (≥50% unique tokens)
    │  ├── Structural correctness (e.g., DocTags validation for VLM)
    │  └── Performance regression detection vs. baseline
    │
    ▼
5. Generate manifest (includes benchmark results)
    │
    ▼
6. Copy to production directory
```

### BenchmarkConfig

```java
BenchmarkConfig config = BenchmarkConfig.builder()
    .minThroughput(90.0)           // Minimum tokens/second
    .maxLatencyP99(50.0)           // Maximum P99 latency (ms per token)
    .minTokenDiversity(0.5)        // At least 50% unique tokens in output
    .validateStructure(true)       // Validate output structure (e.g., DocTags)
    .baselineModelId("smoldocling-v1.0")  // Compare against baseline
    .maxRegressionPercent(5.0)     // Allow max 5% regression from baseline
    .warmupIterations(3)           // Warmup before measurement
    .measurementIterations(10)     // Measurement runs
    .build();
```

### Benchmark Results in Manifest

```json
{
  "model_id": "smoldocling",
  "validation": {
    "validated_at": "2025-06-15T14:00:00Z",
    "inference_test_passed": true,
    "benchmark": {
      "throughput_tok_s": 105.3,
      "latency_p99_ms": 38.2,
      "token_diversity": 0.72,
      "structure_valid": true,
      "baseline_comparison": {
        "baseline_model": "smoldocling-v1.0",
        "throughput_delta_percent": 12.5,
        "regression": false
      }
    }
  }
}
```

---

## CLI Commands

```bash
# Download a model from external source
kompile-staging download \
  --source=huggingface \
  --repo=BAAI/bge-base-en-v1.5 \
  --format=onnx

# Convert downloaded model to SameDiff
kompile-staging convert \
  --input=~/.kompile/staging/pending/bge-base-en-v1.5/model.onnx \
  --output=~/.kompile/staging/pending/bge-base-en-v1.5/model.sdz \
  --type=encoder

# Process a GGML/GGUF model (auto-detects format)
kompile-staging convert \
  --input=~/Downloads/llama-2-7b-chat.Q4_K_M.gguf \
  --output=~/.kompile/staging/pending/llama-2-7b-chat/model.gguf

# Process GGML with explicit format
kompile-staging convert \
  --input=~/Downloads/mistral-7b.bin \
  --output=~/.kompile/staging/pending/mistral-7b/model.gguf \
  --format=ggml

# Validate and promote to production
kompile-staging promote \
  --model=bge-base-en-v1.5 \
  --run-inference-test

# Export models for air-gap transfer
kompile-staging export \
  --models=bge-base-en-v1.5,ms-marco-MiniLM-L-6-v2 \
  --output=kompile-models-2025-01-15.tar.gz

# Import bundle in air-gapped environment
kompile-staging import \
  --bundle=kompile-models-2025-01-15.tar.gz \
  --verify-checksums

# List available models
kompile-staging list
kompile-staging list --status=staged
kompile-staging list --type=encoder

# Full pipeline: download, convert, validate, promote
kompile-staging pipeline \
  --source=huggingface \
  --repo=BAAI/bge-base-en-v1.5 \
  --type=encoder
```

---

## Model Sources Configuration (`model-sources.yml`)

```yaml
sources:
  huggingface:
    base_url: https://huggingface.co
    enabled: true

  github:
    base_url: https://github.com/GetKompile/kompile/releases/download
    enabled: true

  custom:
    base_url: ${KOMPILE_MODEL_MIRROR_URL:}
    enabled: ${KOMPILE_MODEL_MIRROR_ENABLED:false}

model_catalog:
  encoders:
    - id: bge-base-en-v1.5
      source: huggingface
      repo: BAAI/bge-base-en-v1.5
      format: onnx
      files:
        model: onnx/model.onnx
        vocab: vocab.txt
      metadata:
        embedding_dim: 768
        max_sequence_length: 512

    - id: arctic-embed-l
      source: huggingface
      repo: Snowflake/arctic-embed-l
      format: onnx
      files:
        model: onnx/model.onnx
        vocab: tokenizer.json
      metadata:
        embedding_dim: 1024
        max_sequence_length: 8192

  cross_encoders:
    - id: ms-marco-MiniLM-L-6-v2
      source: huggingface
      repo: cross-encoder/ms-marco-MiniLM-L6-v2
      format: onnx
      files:
        model: onnx/model.onnx
        vocab: vocab.txt
      metadata:
        hidden_size: 384
        num_layers: 6
```

---

## Key Benefits

### 1. Separation of Concerns
- **Staging Service**: Heavy dependencies (ONNX import, TensorFlow, etc.)
- **Main App**: Lightweight, just reads files - GraalVM native image compatible

### 2. Air-Gap Support
- Export/import bundles with checksums
- No runtime network dependencies in main app
- Transfer models via approved channels

### 3. Version Control & Reproducibility
- Registry tracks model versions, promotion dates, checksums
- Manifest files provide full provenance
- Easy rollback by updating registry

### 4. External Registry Support
- HuggingFace, GitHub Releases, S3, custom mirrors
- Model catalog in YAML - easy to update without code changes
- No hardcoded URLs in main application

### 5. Staging Workflow
- Download → Convert → **Optimize** → Validate → Promote
- Graph optimizations applied automatically during conversion (12 pass types, including FP16 pre-casting)
- Optimization profiles: FULL, BASIC, TRANSFORMER, GPU, NONE
- Failed conversions don't affect production
- Validation includes inference tests
- Optimization reports track ops removed, passes applied, and performance impact

### 6. SameDiff Only
- Single output format eliminates runtime complexity
- No ONNX runtime, no TensorFlow in main app
- Consistent inference behavior

### 7. Graph Optimization at Staging Time
- All optimization happens during staging, not at runtime
- Production models are pre-optimized — zero optimization overhead at inference
- Transformer-specific fusions (attention, RMSNorm, SwiGLU) reduce op count significantly
- FP16 weight pre-casting halves memory bandwidth with zero runtime overhead
- Optimization reports provide full traceability of what was applied
- Backend-specific optimizations (CuDNN layout) applied when targeting GPU deployment

### 8. Triton-Compiled Inference
- Production models leverage Triton JIT compilation for GPU kernels
- CUDA graph capture enables decode-loop replay with minimal CPU overhead
- Section fusion combines multiple ops into single Triton kernels
- Consolidated arg table with dirty tracking minimizes kernel argument updates
- Target: 100+ tok/s for VLM decode on modern GPUs

### 9. VLM Multi-Model Pipeline Support
- Stage coordinated multi-model bundles (vision encoder + decoder + embeddings + tokenizer)
- OnnxModelCache provides import-time caching with `.sdz` serialization
- Pipeline-level manifest tracks all components and their relationships
- Benchmark validation ensures pipeline-level performance targets are met

---

## Integration with Model Debug Component

The existing `model-debug` component in kompile-app-main already has infrastructure for displaying model information. It will be extended to read from the model staging registry.

### Current Model Debug Architecture

```typescript
// Current interfaces in model-debug.component.ts
interface CrossEncoderModelInfo {
  modelId: string;
  description: string;
  hiddenSize: number;
  numLayers: number;
  maxSequenceLength: number;
  framework: string;      // Will always be 'samediff'
  trainingData: string;
  cached: boolean;        // True if in ~/.kompile/models
  cachedPath?: string;
  isDefault?: boolean;
}

interface CrossEncoderModelsResponse {
  totalModels: number;
  cachedCount: number;
  defaultModel: string;
  models: CrossEncoderModelInfo[];
}
```

### Updated Model Registry Interface

Extend the existing interfaces to support the full registry:

```typescript
// New interfaces for model registry (api-models.ts)

export type ModelType = 'encoder' | 'cross_encoder' | 'reranker' | 'vlm_vision_encoder' | 'vlm_decoder' | 'vlm_embed_tokens' | 'vlm_pipeline';
export type ModelStatus = 'active' | 'staged' | 'pending' | 'failed';

export interface ModelMetadata {
  embeddingDim?: number;
  hiddenSize?: number;
  numLayers?: number;
  maxSequenceLength: number;
  modelType?: string;  // 'dense' | 'sparse'
  framework: string;   // Always 'samediff'
  trainingData?: string;
  sourceOrigin?: string;  // 'huggingface' | 'github' | 'custom'
  sourceRepository?: string;
  originalFormat?: string;  // 'onnx' | 'tensorflow' | 'keras'
  conversionDate?: string;
}

export interface TokenizerConfig {
  doLowerCase: boolean;
  addSpecialTokens: boolean;
  stripAccents: boolean;
  maxLength: number;
}

export interface ModelRegistryEntry {
  modelId: string;
  type: ModelType;
  path: string;
  modelFile: string;
  vocabFile: string;
  checksum: string;
  status: ModelStatus;
  promotedAt?: string;
  metadata: ModelMetadata;
  tokenizer: TokenizerConfig;
}

export interface ModelRegistry {
  version: string;
  updatedAt: string;
  models: { [modelId: string]: ModelRegistryEntry };
}

// Staging-specific (only when staging service is reachable)
export interface StagingModelInfo {
  modelId: string;
  status: 'pending' | 'converting' | 'validating' | 'ready' | 'failed';
  progress?: number;
  error?: string;
  source: string;
  startedAt: string;
}

export interface StagingStatusResponse {
  connected: boolean;
  stagingServiceUrl?: string;
  modelsInStaging: StagingModelInfo[];
  lastSync?: string;
}
```

### Backend API Endpoints

Add new endpoints to the main app that read from the registry:

```java
@RestController
@RequestMapping("/api/models")
public class ModelRegistryController {

    private final KompileModelManager modelManager;

    /**
     * Get all models from the local registry.
     * Read-only - just reads ~/.kompile/models/registry.json
     */
    @GetMapping("/registry")
    public ModelRegistry getRegistry() {
        return modelManager.getRegistry();
    }

    /**
     * Get models by type (encoder, cross_encoder, reranker).
     */
    @GetMapping("/registry/{type}")
    public List<ModelRegistryEntry> getModelsByType(@PathVariable String type) {
        return modelManager.getModelsByType(ModelType.valueOf(type.toUpperCase()));
    }

    /**
     * Get a specific model's info.
     */
    @GetMapping("/registry/{type}/{modelId}")
    public ModelRegistryEntry getModel(
            @PathVariable String type,
            @PathVariable String modelId) {
        return modelManager.getModel(modelId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /**
     * Check staging service status (optional - may not be running).
     */
    @GetMapping("/staging/status")
    public StagingStatusResponse getStagingStatus() {
        // Try to connect to staging service if configured
        // Returns connected=false if staging service not available
        return stagingClient.getStatus();
    }
}
```

### Model Debug Component Updates

Update the model-debug component to use the registry:

```typescript
// model-debug.component.ts additions

// State
modelRegistry: ModelRegistry | null = null;
stagingStatus: StagingStatusResponse | null = null;
registryLoading = false;

// Load registry on init
loadModelRegistry(): void {
  this.registryLoading = true;
  this.http.get<ModelRegistry>(`${this.backendUrl}/models/registry`).subscribe({
    next: (response) => {
      this.modelRegistry = response;
      this.registryLoading = false;
      this.cdr.detectChanges();
    },
    error: (err) => {
      console.error('Failed to load model registry:', err);
      this.registryLoading = false;
    }
  });
}

// Get encoder models from registry
getEncoderModels(): ModelRegistryEntry[] {
  if (!this.modelRegistry?.models) return [];
  return Object.values(this.modelRegistry.models)
    .filter(m => m.type === 'encoder' && m.status === 'active');
}

// Get cross-encoder models from registry (replaces current implementation)
getCrossEncoderModelsFromRegistry(): ModelRegistryEntry[] {
  if (!this.modelRegistry?.models) return [];
  return Object.values(this.modelRegistry.models)
    .filter(m => m.type === 'cross_encoder' && m.status === 'active');
}

// Check if staging service is available
checkStagingService(): void {
  this.http.get<StagingStatusResponse>(`${this.backendUrl}/models/staging/status`).subscribe({
    next: (response) => {
      this.stagingStatus = response;
    },
    error: () => {
      this.stagingStatus = { connected: false, modelsInStaging: [] };
    }
  });
}
```

### Updated Model Debug HTML

```html
<!-- Model Registry Section (replaces current Cross-Encoder section) -->
<div class="model-registry-section" *ngIf="modelRegistry">
  <h2 class="section-title">
    <mat-icon>storage</mat-icon>
    Local Model Registry
    <span class="registry-version">v{{ modelRegistry.version }}</span>
  </h2>
  <p class="section-description">
    SameDiff models available in ~/.kompile/models
    <span class="last-updated">Updated: {{ modelRegistry.updatedAt | date:'medium' }}</span>
  </p>

  <!-- Encoder Models -->
  <div class="model-category" *ngIf="getEncoderModels().length > 0">
    <h3>
      <mat-icon>psychology</mat-icon>
      Embedding Encoders ({{ getEncoderModels().length }})
    </h3>
    <table class="registry-table">
      <thead>
        <tr>
          <th>Model</th>
          <th>Dimensions</th>
          <th>Max Length</th>
          <th>Source</th>
          <th>Status</th>
        </tr>
      </thead>
      <tbody>
        <tr *ngFor="let model of getEncoderModels()">
          <td class="model-id-cell">
            <mat-icon>check_circle</mat-icon>
            {{ model.modelId }}
          </td>
          <td>{{ model.metadata.embeddingDim }}</td>
          <td>{{ model.metadata.maxSequenceLength }}</td>
          <td>{{ model.metadata.sourceOrigin || 'unknown' }}</td>
          <td>
            <span class="status-badge active">{{ model.status }}</span>
          </td>
        </tr>
      </tbody>
    </table>
  </div>

  <!-- Cross-Encoder Models -->
  <div class="model-category" *ngIf="getCrossEncoderModelsFromRegistry().length > 0">
    <h3>
      <mat-icon>compare_arrows</mat-icon>
      Cross-Encoders ({{ getCrossEncoderModelsFromRegistry().length }})
    </h3>
    <table class="registry-table">
      <thead>
        <tr>
          <th>Model</th>
          <th>Hidden Size</th>
          <th>Layers</th>
          <th>Max Length</th>
          <th>Status</th>
        </tr>
      </thead>
      <tbody>
        <tr *ngFor="let model of getCrossEncoderModelsFromRegistry()">
          <td class="model-id-cell">
            <mat-icon>check_circle</mat-icon>
            {{ model.modelId }}
          </td>
          <td>{{ model.metadata.hiddenSize }}</td>
          <td>{{ model.metadata.numLayers }}</td>
          <td>{{ model.metadata.maxSequenceLength }}</td>
          <td>
            <span class="status-badge active">{{ model.status }}</span>
          </td>
        </tr>
      </tbody>
    </table>
  </div>

  <!-- Empty State -->
  <div class="no-models-info" *ngIf="getEncoderModels().length === 0 && getCrossEncoderModelsFromRegistry().length === 0">
    <mat-icon>info_outline</mat-icon>
    <span>No models in registry. Use kompile-staging to download and convert models.</span>
  </div>
</div>

<!-- Staging Service Status (optional, if staging service is running) -->
<div class="staging-status-section" *ngIf="stagingStatus?.connected">
  <h2 class="section-title">
    <mat-icon>sync</mat-icon>
    Staging Service
    <span class="connection-badge connected">Connected</span>
  </h2>
  <p class="section-description">
    Models being prepared by kompile-staging service
  </p>

  <div class="staging-models-list" *ngIf="stagingStatus.modelsInStaging.length > 0">
    <div *ngFor="let model of stagingStatus.modelsInStaging" class="staging-model-item"
         [class.pending]="model.status === 'pending'"
         [class.converting]="model.status === 'converting'"
         [class.ready]="model.status === 'ready'"
         [class.failed]="model.status === 'failed'">
      <mat-icon>{{ getStagingIcon(model.status) }}</mat-icon>
      <div class="staging-model-info">
        <span class="staging-model-id">{{ model.modelId }}</span>
        <span class="staging-model-status">{{ model.status }}</span>
        <mat-progress-bar *ngIf="model.progress" mode="determinate" [value]="model.progress"></mat-progress-bar>
        <span class="staging-model-error" *ngIf="model.error">{{ model.error }}</span>
      </div>
    </div>
  </div>

  <div class="no-staging-models" *ngIf="stagingStatus.modelsInStaging.length === 0">
    <mat-icon>check_circle</mat-icon>
    <span>No models in staging queue</span>
  </div>
</div>
```

### Communication Flow

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        User Interface (model-debug)                      │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │  GET /api/models/registry                                         │   │
│  │  ↓                                                                 │   │
│  │  KompileModelManager reads ~/.kompile/models/registry.json        │   │
│  │  ↓                                                                 │   │
│  │  Display active encoder models                                     │   │
│  │  Display active cross-encoder models                               │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │  GET /api/models/staging/status (optional)                        │   │
│  │  ↓                                                                 │   │
│  │  If staging service running: show staging queue                    │   │
│  │  If not running: hide staging section                              │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘

                           Main App (read-only)
                                   ↑
                                   │ reads
                                   │
                    ┌──────────────┴──────────────┐
                    │                              │
                    │  ~/.kompile/models/          │
                    │   ├── registry.json          │
                    │   ├── encoders/              │
                    │   └── cross-encoders/        │
                    │                              │
                    └──────────────┬──────────────┘
                                   │
                                   │ writes
                                   ↓
                    ┌──────────────────────────────┐
                    │                              │
                    │   kompile-staging service    │
                    │   (separate process)         │
                    │                              │
                    │   - Downloads models         │
                    │   - Converts to SameDiff     │
                    │   - Validates inference      │
                    │   - Updates registry.json    │
                    │                              │
                    └──────────────────────────────┘
```

---

## Migration Path

1. **Phase 1**: Create `kompile-model-staging` module with basic download/convert/export
2. **Phase 2**: Implement registry.json and simplified KompileModelManager
3. **Phase 3**: Update model-debug component to read from registry
4. **Phase 4**: Remove hardcoded ModelConstants from main app
5. **Phase 5**: Add optional staging service status display
6. **Phase 6**: CI/CD integration for automated model updates

---

## Dependencies

**kompile-model-staging** (runs separately):
```xml
<dependencies>
    <!-- Heavy ML dependencies - OK here -->
    <dependency>
        <groupId>org.nd4j</groupId>
        <artifactId>samediff-import-onnx</artifactId>
    </dependency>
    <dependency>
        <groupId>org.nd4j</groupId>
        <artifactId>samediff-import-tensorflow</artifactId>
    </dependency>
    <!-- Spring Boot for REST API / CLI -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <!-- CLI -->
    <dependency>
        <groupId>info.picocli</groupId>
        <artifactId>picocli-spring-boot-starter</artifactId>
    </dependency>
</dependencies>
```

**kompile-model-manager** (in main app - simplified):
```xml
<dependencies>
    <!-- Minimal dependencies - GraalVM friendly -->
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
    </dependency>
    <!-- NO: samediff-import-*, tensorflow, onnx-runtime -->
</dependencies>
```
