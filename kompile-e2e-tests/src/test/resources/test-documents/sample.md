# Kompile RAG Framework

## Overview

The Kompile RAG framework provides a complete pipeline for building
Retrieval-Augmented Generation applications.

## Components

### Embedding Models

Embedding models convert text into dense vector representations.
Supported implementations include:
- **OpenAI**: Cloud-based embeddings via API
- **SameDiff**: Local inference with bge-base-en-v1.5
- **Sentence Transformers**: Python subprocess wrapper

### Vector Stores

Vector stores index and search embeddings efficiently.
Available backends:
- **Lucene HNSW**: Local file-based indexing via Anserini
- **pgvector**: PostgreSQL extension for vector similarity
- **Chroma**: Dedicated vector database
- **Vespa**: Yahoo's search platform

### Document Loaders

Loaders extract text from various file formats:
- PDF documents (basic and extended parsing)
- Microsoft Office files (Word, Excel, PowerPoint)
- Email messages (IMAP, EML, MSG)
- Web pages (HTML scraping)

## Pipeline Flow

```
Documents -> Loaders -> Chunks -> Embeddings -> Vector Index
Query -> Embed -> Search -> Context -> LLM -> Response
```
