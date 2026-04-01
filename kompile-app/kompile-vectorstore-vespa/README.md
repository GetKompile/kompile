# Kompile VectorStore - Vespa

Vespa implementation of the Kompile VectorStore interface, providing distributed vector search with hybrid BM25+vector capabilities.

## Overview

[Vespa](https://vespa.ai/) is a distributed search and vector database that supports:
- **Hybrid search**: Combine BM25 text matching with vector similarity in a single query
- **HNSW indexing**: Approximate nearest neighbor search for fast vector retrieval
- **Horizontal scaling**: Distribute data across multiple nodes
- **Built-in ML**: Support for embedding generation and model serving

**Note**: Unlike the Anserini vector store which is embedded, Vespa requires a separate server process (Docker or Vespa Cloud).

## Quick Start

### 1. Start Vespa with Docker

```bash
cd kompile-app/kompile-vectorstore-vespa
docker-compose up -d

# Wait for Vespa to be ready (1-2 minutes)
curl -s http://localhost:19071/state/v1/health
```

### 2. Deploy the Application Package

Using the Vespa CLI:
```bash
vespa deploy --wait 300 src/main/resources/vespa-app
```

Or using curl:
```bash
(cd src/main/resources/vespa-app && zip -r - .) | \
  curl --header "Content-Type: application/zip" \
       --data-binary @- \
       http://localhost:19071/application/v2/tenant/default/prepareandactivate
```

### 3. Configure Kompile

Add to your `application.properties`:

```properties
# Enable Vespa VectorStore
kompile.vectorstore.vespa.enabled=true
kompile.vectorstore.vespa.endpoint=http://localhost:8080

# Configure dimensions to match your embedding model
kompile.vectorstore.vespa.dimensions=768

# Optional: Enable hybrid search
kompile.vectorstore.vespa.hybrid-search.enabled=true
kompile.vectorstore.vespa.hybrid-search.vector-weight=0.7
```

## Configuration Properties

| Property | Default | Description |
|----------|---------|-------------|
| `kompile.vectorstore.vespa.enabled` | `false` | Enable Vespa VectorStore |
| `kompile.vectorstore.vespa.endpoint` | `http://localhost:8080` | Vespa endpoint URL |
| `kompile.vectorstore.vespa.namespace` | `default` | Document namespace |
| `kompile.vectorstore.vespa.document-type` | `document` | Document type name |
| `kompile.vectorstore.vespa.vector-field` | `embedding` | Vector field name in schema |
| `kompile.vectorstore.vespa.dimensions` | `768` | Vector dimensions |
| `kompile.vectorstore.vespa.distance-metric` | `angular` | Distance metric (angular, euclidean, innerproduct) |
| `kompile.vectorstore.vespa.target-hits` | `100` | ANN search target hits |

### Connection Settings

| Property | Default | Description |
|----------|---------|-------------|
| `kompile.vectorstore.vespa.connection.connection-timeout-ms` | `5000` | Connection timeout |
| `kompile.vectorstore.vespa.connection.request-timeout-ms` | `30000` | Request timeout |
| `kompile.vectorstore.vespa.connection.max-connections` | `8` | Max connections per endpoint |
| `kompile.vectorstore.vespa.connection.max-retries` | `3` | Max retry attempts |

### Hybrid Search

| Property | Default | Description |
|----------|---------|-------------|
| `kompile.vectorstore.vespa.hybrid-search.enabled` | `false` | Enable hybrid search |
| `kompile.vectorstore.vespa.hybrid-search.vector-weight` | `0.7` | Weight for vector similarity (0.0-1.0) |
| `kompile.vectorstore.vespa.hybrid-search.ranking-profile` | `hybrid` | Vespa ranking profile to use |

### TLS/SSL (for Vespa Cloud)

| Property | Default | Description |
|----------|---------|-------------|
| `kompile.vectorstore.vespa.tls.enabled` | `false` | Enable TLS |
| `kompile.vectorstore.vespa.tls.ca-certificate-path` | - | CA certificate path |
| `kompile.vectorstore.vespa.tls.client-certificate-path` | - | Client certificate path |
| `kompile.vectorstore.vespa.tls.client-key-path` | - | Client private key path |

## Schema Customization

The default schema (`document.sd`) supports 768-dimensional vectors. To use a different embedding model, modify the schema:

```sd
# For OpenAI text-embedding-3-small (1536 dimensions)
field embedding type tensor<float>(x[1536]) {
    indexing: attribute | index
    attribute {
        distance-metric: angular
    }
    index {
        hnsw {
            max-links-per-node: 16
            neighbors-to-explore-at-insert: 200
        }
    }
}
```

Remember to also update `kompile.vectorstore.vespa.dimensions` in your configuration.

## Ranking Profiles

The schema includes several ranking profiles:

- **default**: Pure vector similarity (closeness)
- **hybrid**: Combined vector + BM25 with configurable weights
- **bm25**: Pure text matching
- **vector**: Alias for default

Use the `kompile.vectorstore.vespa.hybrid-search.ranking-profile` property to select.

## Comparison with Other Vector Stores

| Feature | Vespa | Anserini | pgvector | Chroma |
|---------|-------|----------|----------|--------|
| Embedded (in-process) | No | **Yes** | No | No |
| Horizontal scaling | **Yes** | No | Limited | Yes |
| Hybrid search | **Yes** | Separate | No | No |
| HNSW indexing | Yes | Yes | Yes | Yes |
| Built-in embedders | **Yes** | No | No | No |
| Production ready | **Yes** | Yes | Yes | Yes |

## Vespa Cloud

For production deployments, consider [Vespa Cloud](https://cloud.vespa.ai/):

1. Create an account at https://console.vespa-cloud.com
2. Create an application and generate certificates
3. Configure TLS properties:

```properties
kompile.vectorstore.vespa.endpoint=https://your-app.your-tenant.aws-us-east-1c.z.vespa-app.cloud
kompile.vectorstore.vespa.tls.enabled=true
kompile.vectorstore.vespa.tls.ca-certificate-path=/path/to/ca-cert.pem
kompile.vectorstore.vespa.tls.client-certificate-path=/path/to/client-cert.pem
kompile.vectorstore.vespa.tls.client-key-path=/path/to/client-key.pem
```

## Resources

- [Vespa Documentation](https://docs.vespa.ai/)
- [Vespa Vector Search](https://docs.vespa.ai/en/nearest-neighbor-search.html)
- [Vespa Hybrid Search](https://docs.vespa.ai/en/tutorials/hybrid-search.html)
- [Vespa Cloud](https://cloud.vespa.ai/)
- [LangChain4j Vespa](https://github.com/langchain4j/langchain4j)
