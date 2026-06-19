# kompile-fpna

A kompile RAG application generated with `kompile init-project`.

## Quick Start

```bash
# Build the project
cd kompile-fpna
mvn clean package -DskipTests

# Run the application
java -jar target/kompile-fpna-0.1.0-SNAPSHOT.jar
```

Or run directly with Maven:

```bash
mvn spring-boot:run
```

The application will start on port 8080. Open http://localhost:8080/ in your browser.

## Preset

This project was generated with the `cli-agent-rag` preset.

## Enabled Modules

- **CORE**: app-main, app-core, loaders-orchestrator, app-anserini, chat-history, pipelines-llm
- **LLM**: llm-cli-agent
- **EMBEDDING**: embedding-anserini
- **VECTORSTORE**: vectorstore-anserini
- **LOADER**: loader-pdf-extended, loader-tika
- **CHUNKER**: chunker-sentence
- **TOOL**: tool-filesystem, tool-rag, tool-model-staging, tool-workflow, tool-gateway, tool-crawler
- **ENTERPRISE**: kvcache, model-manager

## Project Structure

```
kompile-fpna/
  pom.xml                              # Maven project descriptor
  src/main/resources/
    application.properties             # Application configuration
  data/
    input_documents/                   # Documents for RAG ingestion
    input_documents/uploads/            # Uploaded documents
    shared_files/                      # MCP shared filesystem root
    prompt-templates/                  # Prompt template definitions
    mcp-config.json                    # MCP server configuration
```

## Configuration

Edit `src/main/resources/application.properties` to configure:
- LLM API keys (OpenAI, Anthropic, Gemini)
- Embedding model selection
- Vector store configuration
- Server port and other runtime settings

See commented-out template sections at the bottom of `application.properties` for provider-specific examples.
