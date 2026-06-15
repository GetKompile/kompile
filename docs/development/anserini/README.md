# Anserini

A custom fork of the [Anserini](https://github.com/castorini/anserini) information retrieval toolkit, extended with SameDiff dense encoders for neural retrieval.

## What it provides

- Lucene-based text and vector search (keyword BM25 + HNSW dense retrieval)
- SameDiff encoder integration for generating embeddings at index and query time
- Model downloader (`AnseriniModelDownloader`) for fetching pre-trained encoder weights

## Package structure

Under `src/main/java/io/anserini/`:

| Package | Purpose |
|---------|---------|
| `encoder/samediff/` | SameDiff dense encoder implementations |
| `index/` | Lucene indexing (text + vector) |
| `search/` | Search implementations (BM25, dense, hybrid) |
| `collection/` | Document collection readers |
| `analysis/` | Text analysis and tokenization |
| `fusion/` | Result fusion strategies (RRF, etc.) |
| `rerank/` | Cross-encoder reranking |
| `server/` | Search server |

## Encoder factory

`AnseriniEncoderFactory` maps model IDs to encoder instances. `SameDiffEncoderFactory` creates tokenizer-aware encoders.

## Adding a new embedding model

1. Register the model in `kompile-model-manager/.../ModelConstants.java` with URL, checksum, dimensions
2. Create an encoder class in `anserini/src/main/java/io/anserini/encoder/samediff/` extending `GenericDenseSameDiffEncoder`
3. Update `AnseriniEncoderFactory` to map the model ID to the new encoder

## Building

```bash
cd anserini
mvn clean install -DskipTests
```

Anserini depends on `kompile-model-manager`, so build `kompile-app` first if model manager has changed.
