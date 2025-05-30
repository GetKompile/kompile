# Kompile Model Manager

A standalone Java library for managing ML/NLP model downloads, caching, and organization. This module provides automatic model downloading, integrity verification, and local caching for various machine learning models used in Kompile applications.

## Features

- **Automatic Model Downloads**: Download models on-demand from configured URLs
- **Local Caching**: Models are cached locally to avoid repeated downloads
- **Integrity Verification**: SHA-256 checksum validation for downloaded models
- **Archive Support**: Automatic extraction of tar.gz archives
- **Configurable Cache Location**: Customize cache directory via environment variable
- **Minimal Dependencies**: Only requires SLF4J and Apache Commons Compress

## Usage

### Basic Usage

```java
import ai.kompile.modelmanager.*;

// Initialize model manager
KompileModelManager manager = new KompileModelManager();

// Download and cache an OpenNLP model
ModelDescriptor descriptor = ModelConstants.createOpenNLPSentenceModelDescriptor("en");
Path modelPath = manager.ensureModelAvailable(descriptor);

// Model is now available at the returned path
System.out.println("Model available at: " + modelPath);
```

### Custom Cache Directory

```java
// Via environment variable
System.setenv("KOMPILE_MODEL_CACHE_DIR", "/custom/cache/path");
KompileModelManager manager = new KompileModelManager();

// Or via constructor
Path customPath = Paths.get("/custom/cache/path");
KompileModelManager manager = new KompileModelManager(customPath);
```

### Working with Model Descriptors

```java
// Create a custom model descriptor
ModelDescriptor customModel = new ModelDescriptor(
    "my-model-id",
    ModelType.NLP_MODEL,
    "https://example.com/model.bin",
    "custom/models/my-model.bin",
    "1.0.0",
    "sha256-checksum-here",
    Map.of("description", "My custom model")
);

// Ensure model is available
Path modelPath = manager.ensureModelAvailable(customModel);
```

## Supported Models

### OpenNLP Sentence Detection Models

The library includes built-in support for OpenNLP sentence detection models in 32+ languages:

```java
// Get all supported languages
Set<String> languages = ModelConstants.getSupportedOpenNLPLanguages();

// Check if a language is supported
boolean supported = ModelConstants.isOpenNLPLanguageSupported("de");

// Create descriptor for German model
ModelDescriptor germanModel = ModelConstants.createOpenNLPSentenceModelDescriptor("de");
```

Supported languages include: bg, ca, cs, da, de, el, en, es, et, eu, fi, fr, hr, hy, is, it, ka, kk, ko, lv, nl, no, pl, pt, ro, ru, sk, sl, sr, sv, tr, uk

### Anserini Models

Built-in support for Anserini prebuilt indexes and encoder models:

```java
// Get Anserini index descriptor
ModelDescriptor msmarcoIndex = ModelConstants.getAnseriniIndexDescriptor("msmarco-passage-v1");

// Get encoder model descriptor
ModelDescriptor bgeEncoder = ModelConstants.getAnseriniEncoderModelDescriptor("bge-base-en-v1.5-onnx");
```

## Configuration

### Environment Variables

- `KOMPILE_MODEL_CACHE_DIR`: Override the default cache directory (default: `~/.kompile/models`)

### Cache Structure

Models are organized in the cache directory as follows:

```
~/.kompile/models/
├── opennlp/
│   └── sentence/
│       ├── en-sent.bin
│       ├── de-sent.bin
│       └── fr-sent.bin
├── anserini/
│   ├── indexes/
│   │   └── msmarco-passage-v1/
│   └── encoders/
│       └── onnx/
│           └── bge-base-en-v1.5/
│               └── model.onnx
```

## Dependencies

This module has minimal dependencies:

- **SLF4J API**: For logging
- **Apache Commons Compress**: For tar.gz extraction
- **JUnit 5**: For testing (test scope only)

## Integration

Add this module as a dependency to your Maven project:

```xml
<dependency>
    <groupId>ai.kompile</groupId>
    <artifactId>kompile-model-manager</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Thread Safety

The `KompileModelManager` is thread-safe. Multiple threads can safely call `ensureModelAvailable()` concurrently, though simultaneous downloads of the same model will be serialized to avoid conflicts.

## Error Handling

The library throws `IOException` for network, file system, or checksum validation errors. All errors include descriptive messages to help with debugging.

```java
try {
    Path modelPath = manager.ensureModelAvailable(descriptor);
} catch (IOException e) {
    System.err.println("Failed to download model: " + e.getMessage());
}
```

## Extending with Custom Models

To add support for new models, extend the `ModelConstants` class or create your own `ModelDescriptor` instances:

```java
public class MyModelConstants {
    public static ModelDescriptor createMyCustomModel() {
        return new ModelDescriptor(
            "my-custom-model",
            ModelType.NLP_MODEL,
            "https://my-server.com/model.tar.gz",
            "custom/my-model",
            "1.0.0",
            "sha256-checksum",
            Map.of("description", "My custom NLP model")
        );
    }
}
```
