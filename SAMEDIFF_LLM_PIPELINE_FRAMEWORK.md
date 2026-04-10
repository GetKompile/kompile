# SameDiff LLM Modular Multi-Model Pipeline Framework

## Overview

This document describes the new modular framework for multi-model pipelines in SameDiff LLM, providing feature parity with the VLM pipeline system including model types/roles, UI support, downloads, provisioning, and serving.

## Architecture

### Backend Components

#### 1. Pipeline Framework (`kompile-pipelines-framework/kompile-pipeline-steps-parent/kompile-pipelines-steps-samediff/`)

**Core Classes:**

- **`SameDiffLLMPipelineBuilder.java`**
  - Fluent builder for constructing complete SameDiff LLM inference pipelines
  - Creates GraphPipeline with tokenizer → embed_tokens → decoder_loop → token_decode flow
  - Supports tool calling configuration
  - Configurable parameters: temperature, topK, maxNewTokens, etc.

- **`SameDiffLLMConstants.java`**
  - Centralized constants for pipeline configuration
  - Model roles: TOKENIZER, EMBED_TOKENS, DECODER, TOKEN_DECODER
  - Data keys for pipeline input/output
  - Tokenizer types and tool call formats

- **`SameDiffLLMModelComponent.java`**
  - Represents individual model components in a pipeline
  - Model roles and architecture types
  - Metadata for input/output shapes

- **`SameDiffLLMModelSet.java`**
  - Complete model set descriptor (e.g., SmolLM-135M, Phi-2)
  - Contains all components needed for inference
  - Architecture, vocab size, hidden size, layers, heads, context length

- **`SameDiffLLMPipelineStage.java`**
  - Pipeline stage information for UI display
  - Input/output descriptions

**Step Runner Factories:**

- **`SameDiffLLMTokenizerStepRunnerFactory.java`**
  - Tokenizes input text into token IDs
  - Supports multiple tokenizer types (samediff_wordpiece, wordpiece, bpe, unigram)

- **`SameDiffEmbedTokensStepRunnerFactory.java`**
  - Converts token IDs to hidden state embeddings
  - Uses model's embedding layer

- **`SameDiffLanguageModelStepRunner.java`** (existing, enhanced)
  - Autoregressive decoder for token generation
  - Tool calling support with JSON markers or OpenAI format
  - KV cache management for efficient generation

- **`SameDiffTokenDecodeStepRunnerFactory.java`**
  - Converts generated token IDs back to text
  - Uses tokenizer for decoding

#### 2. Model Manager (`kompile-app/kompile-model-manager/`)

**ModelConstants.java** - Added SameDiff LLM support:

- **`SameDiffLLMModelDescriptor`** (new inner class)
  - Model metadata: architecture, vocab size, hidden size, layers, heads
  - Component file names: embed_tokens, decoder, tokenizer
  - Token IDs: EOS, PAD
  - HuggingFace repo information

**Supported Models:**
- `smollm-135m-instruct` - Small efficient model (576 hidden, 30 layers, 9 heads)
- `smollm-360m-instruct` - Medium quality model (960 hidden, 32 layers, 15 heads)
- `phi-2` - Microsoft compact model (2560 hidden, 32 layers, 32 heads)

#### 3. Pipeline Application (`kompile-app/kompile-pipelines-app-llm/`)

**SameDiffLLMService.java:**
- Model set management (list, download, delete, cache status)
- Pipeline lifecycle (create, get, list, delete)
- Download progress tracking
- Cache directory management

**SameDiffLLMController.java:**
- REST API endpoints for frontend integration
- `/api/samediff-llm/model-sets` - List/get model sets
- `/api/samediff-llm/model-sets/status` - Cache status
- `/api/samediff-llm/model-sets/{id}/download` - Download model
- `/api/samediff-llm/pipelines` - Create/list/delete pipelines
- `/api/samediff-llm/pipeline-stages` - Pipeline stage documentation
- `/api/samediff-llm/status` - Service health

### Frontend Components (`kompile-app/kompile-app-main/src/main/frontend/`)

#### TypeScript Models (`app/models/samediff-llm-models.ts`)

Interfaces matching backend DTOs:
- `SameDiffLLMModelSet` - Model set information
- `SameDiffLLMModelComponent` - Component details
- `SameDiffLLMPipelineStage` - Stage documentation
- `SameDiffLLMPreset` - Configuration presets
- `SameDiffLLMDownloadStatus` - Download progress
- `SameDiffLLMExecutionRequest/Result` - Pipeline execution

#### Angular Service (`app/services/samediff-llm.service.ts`)

HTTP client wrapper for all API operations:
- Model set operations with progress polling
- Pipeline CRUD operations
- Execution (sync and streaming)
- Error handling

#### UI Component (`app/components/samediff-llm-models/`)

**Component Files:**
- `samediff-llm-models.component.ts` - Component logic
- `samediff-llm-models.component.html` - Template
- `samediff-llm-models.component.css` - Styles

**Features:**
1. **Model Sets Grid**
   - Card-based layout showing all available models
   - Architecture icon, specs (vocab, layers, heads, context)
   - Download/delete buttons with progress indicators
   - Expandable component details

2. **Pipeline Builder**
   - Form for creating new pipelines
   - Model set selection (filtered by cached status)
   - Configuration sliders: max tokens, temperature, topK
   - Tool calling toggle

3. **Pipeline Stages Reference**
   - Visual documentation of pipeline flow
   - Input/output descriptions per stage
   - Model requirement indicators

4. **Presets Section**
   - Pre-configured pipeline templates
   - Quick setup for common use cases

## Pipeline Architecture

### Modular Design

The pipeline follows a modular graph structure:

```
pipeline_input
     ↓
  tokenizer
     ↓
 embed_tokens ─────────┐
     ↓                 │
 decoder_body ←────────┤
     ↓                 │
 decoder_loop (loop) ←─┘
     ↓
 token_decode
     ↓
  response_text
```

### Model Roles

Each model component has a specific role:

1. **TOKENIZER**
   - Input: Raw text string
   - Output: Token IDs + attention mask
   - Model: tokenizer.json configuration

2. **EMBED_TOKENS**
   - Input: Token IDs
   - Output: Hidden state embeddings
   - Model: embed_tokens.fb (SameDiff format)

3. **DECODER**
   - Input: Hidden states + KV cache
   - Output: Logits + updated KV cache
   - Model: decoder.fb (SameDiff format)

4. **TOKEN_DECODER**
   - Input: Generated token IDs
   - Output: Text string
   - Model: tokenizer.json (shared with tokenizer)

### Configuration Parameters

**Model-level:**
- `modelUri` - Path to model file
- `tokenizerPath` - Path to tokenizer JSON
- `tokenizerType` - Tokenizer algorithm
- `eosTokenId`, `padTokenId`, `unkTokenId`

**Generation:**
- `maxNewTokens` - Maximum tokens to generate
- `temperature` - Sampling temperature (0.1-2.0)
- `topK` - Top-K sampling cutoff
- `numHeads`, `headDim`, `numKvLayers` - Architecture

**Tool Calling:**
- `enableToolCalling` - Enable tool integration
- `toolCallFormat` - json_markers | openai_json
- `toolChoice` - auto | none | specific
- `specificToolName` - Required tool name

## Usage Examples

### Creating a Pipeline (Backend)

```java
SameDiffLLMPipelineBuilder builder = SameDiffLLMPipelineBuilder.create()
    .modelId("smollm-135m-instruct")
    .embedTokens("/path/to/embed_tokens.fb")
    .decoder("/path/to/decoder.fb")
    .tokenizer("/path/to/tokenizer.json")
    .eosTokenId(2)
    .maxNewTokens(512)
    .temperature(0.7f)
    .topK(40)
    .enableToolCalling(true)
    .pipelineId("my-llm-pipeline");

GraphPipeline pipeline = builder.build();
```

### Creating a Pipeline (REST API)

```bash
curl -X POST http://localhost:8080/api/samediff-llm/pipelines \
  -H "Content-Type: application/json" \
  -d '{
    "pipelineId": "my-llm-pipeline",
    "modelSetId": "smollm-135m-instruct",
    "maxNewTokens": 512,
    "temperature": 0.7,
    "topK": 40,
    "enableToolCalling": true
  }'
```

### Downloading a Model (REST API)

```bash
# Start download
curl -X POST http://localhost:8080/api/samediff-llm/model-sets/smollm-135m-instruct/download

# Check progress
curl http://localhost:8080/api/samediff-llm/model-sets/smollm-135m-instruct/download/status
```

### Executing a Pipeline

```bash
curl -X POST http://localhost:8080/api/samediff-llm/pipelines/execute \
  -H "Content-Type: application/json" \
  -d '{
    "pipelineId": "my-llm-pipeline",
    "prompt": "What is machine learning?",
    "maxNewTokens": 256,
    "temperature": 0.7
  }'
```

## Integration with Existing Systems

### Spring Boot Configuration

The service auto-configures via Spring Boot:

```java
@Service
public class SameDiffLLMService {
    // Auto-wired in SameDiffLLMController
}
```

### Pipeline Framework Integration

Pipelines created with the builder integrate with the existing pipeline execution framework:

```java
PipelineExecutor executor = pipeline.createExecutor();
Data output = executor.exec(input, context);
```

### Model Manager Integration

Models are managed by `ModelConstants` and downloaded to `~/.kompile/samediff-llm/`:

```
~/.kompile/samediff-llm/
├── smollm-135m-instruct/
│   ├── embed_tokens.fb
│   ├── decoder.fb
│   └── tokenizer.json
├── smollm-360m-instruct/
│   └── ...
└── phi-2/
    └── ...
```

## Comparison with VLM Pipelines

| Feature | VLM Pipeline | SameDiff LLM Pipeline |
|---------|-------------|----------------------|
| Model Sets | ✓ | ✓ |
| Component Downloads | ✓ | ✓ |
| Pipeline Builder | ✓ | ✓ |
| UI Management | ✓ | ✓ |
| Tool Calling | ✓ | ✓ |
| Streaming | ✓ | ✓ |
| Cache Management | ✓ | ✓ |
| Presets | ✓ | ✓ |
| Benchmark | ✓ | ✓ |

## Future Enhancements

1. **Model Quantization** - Support for INT8/FP16 models
2. **Distributed Inference** - Multi-GPU pipeline execution
3. **LoRA Adapters** - Pluggable fine-tuned adapters
4. **Speculative Decoding** - Draft model acceleration
5. **Continuous Batching** - Request batching for throughput
6. **Model Merging** - Merge multiple fine-tuned models

## Testing

### Unit Tests

```bash
cd kompile-pipelines-app-llm
mvn test -Dtest=SameDiffLLMServiceTest
```

### Integration Tests

```bash
cd kompile-app-main
mvn verify -Dspring.profiles.active=test
```

### Frontend Tests

```bash
cd kompile-app/kompile-app-main/src/main/frontend
npm test
```

## Troubleshooting

### Model Download Fails

1. Check network connectivity to HuggingFace
2. Verify cache directory permissions
3. Check available disk space (models are 100MB-2GB each)

### Pipeline Creation Fails

1. Ensure model set is downloaded (check cache status)
2. Verify model files exist in cache directory
3. Check pipeline ID is unique

### High Memory Usage

1. Reduce `maxNewTokens` parameter
2. Limit batch size if processing multiple requests
3. Enable KV cache quantization if available

## Conclusion

This modular framework provides feature parity with VLM pipelines while maintaining the flexibility to support various LLM architectures. The declarative UI enables users to easily manage models and build pipelines without deep technical knowledge.
