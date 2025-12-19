# Anserini Model Downloader

A simple utility to download and convert all ONNX models from Pyserini/Anserini to SameDiff format for use with Kompile's neural retrieval encoders.

## Quick Start

### Option 1: Using the Shell Script (Recommended)

```bash
# Make script executable
chmod +x download-anserini-models.sh

# Download all models
./download-anserini-models.sh download

# Download with parallel processing
./download-anserini-models.sh download --parallel

# Download only dense models
./download-anserini-models.sh download-dense --parallel

# Download specific model
./download-anserini-models.sh download-model --model bge-base-en-v1.5

# List available models
./download-anserini-models.sh list

# Check status of downloaded models
./download-anserini-models.sh status
```

### Option 2: Using Java Directly

```bash
# Build Anserini first
cd anserini
mvn clean compile

# Download all models
java -cp "target/classes:target/lib/*" io.anserini.util.AnseriniModelDownloader

# Download with options
java -cp "target/classes:target/lib/*" io.anserini.util.AnseriniModelDownloader \
  --output-dir ./my-models --parallel --dense-only

# Download specific model
java -cp "target/classes:target/lib/*" io.anserini.util.AnseriniModelDownloader \
  --model bge-base-en-v1.5 --output-dir ./models

# List available models
java -cp "target/classes:target/lib/*" io.anserini.util.AnseriniModelDownloader --list
```

## Available Models

### Dense Models (Embedding-based)
- **bge-base-en-v1.5** - BGE Base English v1.5 (768d embeddings)
- **cosdpr-distil** - CosDPR Distilled (768d embeddings)  
- **arctic-embed-l** - Snowflake Arctic Embed Large (1024d embeddings)

### Sparse Models (Term-weighting based)
- **splade-pp-sd** - SPLADE++ Self-Distil (30K vocab)
- **splade-pp-ed** - SPLADE++ Ensemble-Distil (30K vocab)
- **unicoil** - UniCOIL (30K vocab)

## Output Structure

Models are downloaded to `./anserini-models/` by default:

```
anserini-models/
├── bge-base-en-v1.5/
│   ├── bge-base-en-v1.5.onnx      # Original ONNX model
│   ├── bge-base-en-v1.5.sd        # Converted SameDiff model
│   └── bge-base-en-v1.5-vocab.txt # Vocabulary file
├── splade-pp-sd/
│   ├── splade-pp-sd.onnx
│   ├── splade-pp-sd.sd
│   └── splade-pp-sd-vocab.txt
└── ...
```

## Usage with SameDiff Encoders

Once models are downloaded, you can use them with the SameDiff encoder factory:

```java
// Set the model cache directory to point to downloaded models
System.setProperty("KOMPILE_MODEL_CACHE_DIR", "./anserini-models");

// Create encoder factory
SameDiffEncoderFactory factory = new SameDiffEncoderFactory();

// Use the downloaded models
BgeSameDiffEncoder bgeEncoder = factory.createBgeEncoder("bge-base-en-v1.5");
float[] embedding = bgeEncoder.encode("What is machine learning?");
```

## Command Line Options

### Shell Script Options
- `download` - Download and convert all models
- `download-dense` - Download dense models only
- `download-sparse` - Download sparse models only  
- `download-model --model NAME` - Download specific model
- `list` - List available models
- `status` - Show download status
- `clean` - Clean downloaded models
- `--output DIR` - Specify output directory
- `--parallel` - Use parallel processing

### Java Class Options
- `--help` - Show help message
- `--list` - List available models
- `--output-dir DIR` - Set output directory
- `--model NAME` - Download specific model only
- `--parallel` - Use parallel processing
- `--dense-only` - Process dense models only
- `--sparse-only` - Process sparse models only

## Features

- **Automatic Download**: Downloads ONNX models and vocabulary files from Pyserini/Anserini
- **ONNX to SameDiff Conversion**: Converts models using the kompile-model-importer-onnx
- **Parallel Processing**: Download and convert multiple models simultaneously
- **Resume Support**: Skips already downloaded/converted models
- **Validation**: Validates converted models for basic integrity
- **Flexible Output**: Configurable output directory
- **Progress Tracking**: Shows download progress and file sizes

## Requirements

- Java 11 or higher
- Maven (for building)
- Internet connection for downloading models
- ~2-5 GB disk space for all models

## Troubleshooting

### Build Issues
```bash
# If Anserini is not built
cd anserini
mvn clean compile

# If missing dependencies
mvn dependency:copy-dependencies -DoutputDirectory=target/lib
```

### Download Issues
- Check internet connection
- Verify URLs in the source code are accessible
- Ensure sufficient disk space
- Check file permissions in output directory

### Conversion Issues
- Verify ONNX model file is complete and valid
- Check Java heap size: `java -Xmx4g -cp ...`
- Try converting models one at a time instead of parallel

## Integration with Anserini Search

After downloading models, you can use them in Anserini search applications:

```bash
# Example: Use BGE encoder for dense retrieval
java -cp anserini.jar io.anserini.search.SearchCollection \
  -encoder BgeSameDiff \
  -encoder.model ./anserini-models/bge-base-en-v1.5/bge-base-en-v1.5.sd \
  -encoder.vocab ./anserini-models/bge-base-en-v1.5/bge-base-en-v1.5-vocab.txt \
  ...

# Example: Use SPLADE++ for sparse retrieval  
java -cp anserini.jar io.anserini.search.SearchCollection \
  -encoder SpladePPSelfDistilSameDiff \
  -encoder.model ./anserini-models/splade-pp-sd/splade-pp-sd.sd \
  -encoder.vocab ./anserini-models/splade-pp-sd/splade-pp-sd-vocab.txt \
  ...
```

## Contributing

To add new models:

1. Edit `AnseriniModelDownloader.java`
2. Add model info to the `MODELS` map
3. Test the download and conversion
4. Update this README

The utility is designed to be simple and focused on the core Anserini/Pyserini model ecosystem.
