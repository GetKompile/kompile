# PostgresML Embedding Provider

This module provides a PostgresML implementation for the Kompile embedding system, allowing you to use PostgresML's powerful embedding capabilities within your Spring Boot applications.

## Features

- **Spring AI Integration**: Built on top of Spring AI's PostgresML support
- **Multi-Provider Support**: Can be used alongside other embedding providers
- **Automatic Setup**: Automatically creates PostgresML extension if needed
- **Flexible Configuration**: Support for various PostgresML transformers and parameters
- **Health Monitoring**: Built-in status checking and validation

## Quick Start

### 1. Prerequisites

You need a PostgreSQL database with the PostgresML extension installed. The easiest way to get started is with Docker:

```bash
# Run PostgresML in Docker
docker run -d \
  --name postgresml \
  -p 5432:5432 \
  -v postgresml_data:/var/lib/postgresql \
  -e POSTGRES_PASSWORD=your_password \
  ghcr.io/postgresml/postgresml:2.10.0
```

### 2. Add Dependencies

Add this dependency to your project:

```xml
<dependency>
    <groupId>ai.kompile</groupId>
    <artifactId>kompile-embedding-postgresml</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### 3. Configuration

Add to your `application.properties`:

```properties
# Database connection
spring.datasource.url=jdbc:postgresql://localhost:5432/postgres
spring.datasource.username=postgres
spring.datasource.password=your_password

# Enable PostgresML embedding
spring.ai.postgresml.embedding.enabled=true
spring.ai.postgresml.embedding.transformer=distilbert-base-uncased
```

### 4. Usage

Inject the embedding model into your service:

```java
@Service
public class MyService {
    
    @Autowired
    @Qualifier("postgresMlEmbeddingModelImpl")
    private EmbeddingModel embeddingModel;
    
    public void generateEmbedding() {
        List<Float> embedding = embeddingModel.embed("Hello world");
        System.out.println("Embedding dimension: " + embedding.size());
    }
}
```

## Configuration Options

### Basic Configuration

```properties
# Enable/disable the provider
spring.ai.postgresml.embedding.enabled=true

# Choose the transformer model
spring.ai.postgresml.embedding.transformer=distilbert-base-uncased

# Vector type (PG_ARRAY or PG_VECTOR)
spring.ai.postgresml.embedding.vector-type=PG_ARRAY

# Model-specific parameters (JSON format)
spring.ai.postgresml.embedding.kwargs={}

# Metadata handling mode
spring.ai.postgresml.embedding.metadata-mode=EMBED
```

### Extension Management

```properties
# Automatically create PostgresML extension (requires superuser privileges)
spring.ai.postgresml.embedding.auto-create-extension=true

# Verify installation on startup
spring.ai.postgresml.embedding.verify-installation=true
```

### Transformer Options

#### Lightweight and Fast
```properties
spring.ai.postgresml.embedding.transformer=sentence-transformers/all-MiniLM-L6-v2
```

#### High Quality
```properties
spring.ai.postgresml.embedding.transformer=sentence-transformers/all-mpnet-base-v2
```

#### Optimized for Q&A
```properties
spring.ai.postgresml.embedding.transformer=sentence-transformers/multi-qa-MiniLM-L6-cos-v1
```

#### Multilingual
```properties
spring.ai.postgresml.embedding.transformer=sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2
```

### Vector Type Options

```properties
# Use PostgreSQL arrays (default, no additional extensions needed)
spring.ai.postgresml.embedding.vector-type=PG_ARRAY

# Use pgvector extension (requires pgvector to be installed)
spring.ai.postgresml.embedding.vector-type=PG_VECTOR
```

### Metadata Mode Options

```properties
# Include metadata in embeddings (default)
spring.ai.postgresml.embedding.metadata-mode=EMBED

# Don't include metadata
spring.ai.postgresml.embedding.metadata-mode=NONE

# Append metadata to text
spring.ai.postgresml.embedding.metadata-mode=APPEND
```

### Advanced Configuration

```properties
# Normalize embeddings to unit length
spring.ai.postgresml.embedding.kwargs={"normalize": true}

# Custom pooling strategy
spring.ai.postgresml.embedding.kwargs={"pooling": "mean"}

# Multiple options
spring.ai.postgresml.embedding.kwargs={"normalize": true, "pooling": "mean", "truncate": true}
```

## Multi-Provider Setup

You can use PostgresML alongside other embedding providers:

```properties
# PostgresML
spring.ai.postgresml.embedding.enabled=true
spring.ai.postgresml.embedding.transformer=distilbert-base-uncased

# OpenAI
spring.ai.openai.embedding.enabled=true
spring.ai.openai.api-key=your-openai-api-key

# Use different providers for different purposes
@Qualifier("postgresMlEmbeddingModelImpl") EmbeddingModel postgresml;
@Qualifier("openAiEmbeddingModelImpl") EmbeddingModel openai;
```

## Available Configuration Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `spring.ai.postgresml.embedding.enabled` | boolean | false | Enable/disable PostgresML embedding |
| `spring.ai.postgresml.embedding.transformer` | String | distilbert-base-uncased | Huggingface transformer model name |
| `spring.ai.postgresml.embedding.vector-type` | VectorType | PG_ARRAY | Vector storage type (PG_ARRAY, PG_VECTOR) |
| `spring.ai.postgresml.embedding.kwargs` | String | "{}" | JSON string with model-specific parameters |
| `spring.ai.postgresml.embedding.metadata-mode` | MetadataMode | EMBED | How to handle document metadata |
| `spring.ai.postgresml.embedding.auto-create-extension` | boolean | true | Auto-create PostgresML extension |
| `spring.ai.postgresml.embedding.verify-installation` | boolean | true | Verify installation on startup |

## Bean Configuration

This module creates the following Spring beans:

- **`postgresMlEmbeddingModel`**: The core Spring AI `EmbeddingModel` bean
- **`postgresMlEmbeddingModelImpl`**: The Kompile `EmbeddingModel` implementation that wraps the Spring AI model

The Spring AI model is automatically configured based on your properties and injected into the Kompile implementation.

## Installation Options

### Docker (Recommended for Development)

```bash
docker run -d \
  --name postgresml \
  -p 5432:5432 \
  -v postgresml_data:/var/lib/postgresql \
  -e POSTGRES_PASSWORD=your_password \
  ghcr.io/postgresml/postgresml:2.10.0
```

### Manual Installation

#### Ubuntu
```bash
sudo apt install postgresql-pgml-15
```

#### From Source
Follow the installation guide at https://postgresml.org/docs/installation

### Cloud (PostgresML Managed Service)
Sign up at https://postgresml.org/ for a fully managed PostgresML instance.

## Health Monitoring

The configuration includes health monitoring capabilities:

```java
@Autowired
private PostgresMlEmbeddingConfiguration config;

public void checkHealth() {
    PostgresMlStatus status = config.checkStatus(jdbcTemplate);
    System.out.println("Status: " + status.isWorking());
    System.out.println("Version: " + status.getVersion());
}
```

## Troubleshooting

### Extension Not Found
If you get "extension not found" errors:
1. Ensure PostgresML is properly installed on your PostgreSQL server
2. Set `spring.ai.postgresml.embedding.auto-create-extension=true` (requires superuser privileges)
3. Or manually run: `CREATE EXTENSION IF NOT EXISTS pgml;`

### Permission Denied
If you get permission errors:
1. Ensure your database user has the necessary privileges
2. For extension creation, you need superuser privileges
3. Consider creating the extension manually with a superuser account

### Model Download Issues
Transformers are downloaded automatically on first use. This might take time for larger models.

### Vector Type Issues
- If using `PG_VECTOR`, ensure the pgvector extension is installed
- `PG_ARRAY` works with standard PostgreSQL without additional extensions

### Connection Issues
Ensure your PostgreSQL connection is properly configured and the database is accessible.

## Performance Tips

1. **Batch Processing**: Use `embed(List<String>)` for multiple texts instead of calling `embed(String)` repeatedly
2. **Transformer Choice**: Choose lighter models like `sentence-transformers/all-MiniLM-L6-v2` for faster processing
3. **Vector Type**: `PG_ARRAY` is typically faster than `PG_VECTOR` for most operations
4. **Connection Pooling**: Use connection pooling for high-throughput applications
5. **Caching**: Consider caching embeddings for frequently used texts

## Security Considerations

1. **Database Access**: Limit database user privileges to what's necessary
2. **Network Security**: Secure your PostgreSQL connection (SSL, firewall rules)
3. **Model Security**: Be aware that PostgresML downloads models from the internet
4. **Data Privacy**: Ensure sensitive data handling complies with your security policies

## License

This project is licensed under the Apache License 2.0. See the LICENSE file for details.
