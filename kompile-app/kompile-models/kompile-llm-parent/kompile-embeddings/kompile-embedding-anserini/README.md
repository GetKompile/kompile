# Kompile Embedding - Anserini

This module provides an Anserini-based implementation of the Kompile `EmbeddingModel` interface, utilizing the SameDiff encoders from the Anserini fork for generating text embeddings.

## Overview

The Anserini embedding module leverages the powerful dense retrieval encoders available in the Anserini fork, including:

- **BGE (BAAI General Embedding)** - High-quality multilingual embeddings
- **Arctic Embed** - Specialized embeddings with instruction prefixes
- **CosDPR Distil** - Distilled dense passage retrieval model
- **Generic Dense** - Flexible generic dense encoder

## Features

- **Multiple Encoder Types**: Automatic detection and usage of appropriate encoder based on model identifier
- **Model Management Integration**: Seamless integration with Kompile's model management system for automatic model downloading and caching
- **Flexible Configuration**: Support for both auto-managed and explicit path configurations
- **Batch Processing**: Efficient bulk encoding capabilities
- **Spring Boot Integration**: Auto-configuration and dependency injection support
- **Comprehensive Testing**: Full test coverage with configurable test execution

## Configuration

### Auto-Configuration (Recommended)

Enable Anserini embedding and specify a model identifier:

```properties
# Enable Anserini embedding
kompile.embedding.anserini.enabled=true

# Specify model identifier (auto-downloaded and managed)
kompile.embedding.anserini.model-identifier=bge-base-en-v1.5-onnx
```

### Manual Configuration

For explicit control over model paths:

```properties
kompile.embedding.anserini.enabled=true
kompile.embedding.anserini.model-identifier=bge-base-en-v1.5-onnx
kompile.embedding.anserini.model-path=/path/to/model.onnx
kompile.embedding.anserini.vocab-path=/path/to/vocab.txt

# Tokenization settings
kompile.embedding.anserini.do-lower-case=true
kompile.embedding.anserini.max-sequence-length=512
kompile.embedding.anserini.add-special-tokens=true
kompile.embedding.anserini.normalize-output=true

# Model tensor configuration
kompile.embedding.anserini.input-tensor-names[0]=input_ids
kompile.embedding.anserini.input-tensor-names[1]=attention_mask
kompile.embedding.anserini.input-tensor-names[2]=token_type_ids
kompile.embedding.anserini.output-tensor-name=last_hidden_state
```

## Supported Models

### BGE Models
- `bge-base-en-v1.5-onnx` - BGE Base English v1.5
- `bge-large-en-v1.5-onnx` - BGE Large English v1.5
- `bge-small-en-v1.5-onnx` - BGE Small English v1.5

### Arctic Embed Models
- `arctic-embed-base-onnx` - Arctic Embed Base
- `arctic-embed-large-onnx` - Arctic Embed Large

### Generic Models
- Any ONNX model following the BERT-like architecture

## Usage

### Basic Usage

```java
@Autowired
private EmbeddingModel embeddingModel;

// Single text embedding
List<Float> embedding = embeddingModel.embed("Your text here");

// Multiple texts
List<String> texts = Arrays.asList("Text 1", "Text 2", "Text 3");
List<List<Float>> embeddings = embeddingModel.embed(texts);

// Document embedding
List<Document> documents = // ... your documents
List<List<Float>> docEmbeddings = embeddingModel.embedDocuments(documents);

// Get embedding dimensions
int dimensions = embeddingModel.dimensions();
```

### Programmatic Usage

```java
// Auto-managed model
AnseriniEmbeddingModelImpl model = new AnseriniEmbeddingModelImpl("bge-base-en-v1.5-onnx");

// Explicit paths
AnseriniEmbeddingModelImpl model = new AnseriniEmbeddingModelImpl(
    "bge-base-en-v1.5-onnx",
    "/path/to/model.onnx",
    "/path/to/vocab.txt",
    Arrays.asList("input_ids", "attention_mask", "token_type_ids"),
    "last_hidden_state",
    true,  // doLowerCase
    512,   // maxSequenceLength
    true,  // addSpecialTokens
    true   // normalizeOutput
);

// Use the model
List<Float> embedding = model.embed("Your text");
model.cleanup(); // Important: cleanup resources
```

## Architecture

The module consists of several key components:

- **`AnseriniEmbeddingModelImpl`**: Main implementation of the `EmbeddingModel` interface
- **`AnseriniEncoderFactory`**: Factory for creating appropriate encoder instances
- **`AnseriniEmbeddingConfiguration`**: Spring Boot auto-configuration
- **Encoder Type Detection**: Automatic detection of encoder type based on model identifier

## Dependencies

- **Anserini Fork**: Custom Anserini implementation with SameDiff encoders
- **Kompile Model Manager**: For automatic model downloading and management
- **Spring AI Commons**: For Document class support
- **ND4J/SameDiff**: For ONNX model execution

## Testing

Tests are conditionally enabled to avoid requiring actual model files during CI:

```bash
# Enable tests with actual models
mvn test -Dtest.anserini.enabled=true
```

## Model Management

The module integrates with Kompile's model management system to automatically download and cache models. Models are specified by identifier and are automatically resolved to the appropriate ONNX model and vocabulary files.

### Model Storage

Models are cached in the Kompile model cache directory, typically:
- Linux/Mac: `~/.kompile/models/`
- Windows: `%USERPROFILE%\.kompile\models\`

### Adding New Models

To add support for new models, update the `ModelConstants.getAnseriniEncoderModelDescriptor()` method in the model manager to include the new model descriptor with download URLs and metadata.

## Performance Considerations

- **Model Loading**: Models are loaded once during initialization and cached in memory
- **Batch Processing**: Use batch methods for multiple texts to improve throughput
- **Memory Usage**: Large models (e.g., BGE Large) require significant memory
- **Thread Safety**: The implementation is thread-safe for embedding operations

## Error Handling

The module provides robust error handling:
- **Model Loading Errors**: Clear error messages for missing or corrupted models
- **Input Validation**: Proper handling of null/empty inputs
- **Resource Cleanup**: Automatic cleanup of model resources on shutdown

## Troubleshooting

### Common Issues

1. **Model Not Found**: Ensure the model identifier is correct and the model is available
2. **Memory Issues**: Large models may require increasing JVM heap size
3. **Slow Performance**: Consider using smaller models or enabling batch processing

### Debugging

Enable debug logging to troubleshoot issues:

```properties
logging.level.ai.kompile.embedding.anserini=DEBUG
logging.level.io.anserini.encoder.samediff=DEBUG
```

## Contributing

When contributing to this module:

1. Ensure all tests pass with `mvn test -Dtest.anserini.enabled=true`
2. Add appropriate logging and error handling
3. Update documentation for new features
4. Follow the existing code style and patterns

## License

This module is licensed under the Apache License, Version 2.0.
