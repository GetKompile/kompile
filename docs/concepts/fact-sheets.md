# Fact Sheets

A fact sheet is a named collection of key-value assertions that ground agent responses with verified facts. Think of them as curated knowledge notebooks.

## How they work

Facts are short, structured assertions (e.g., "Company founded: 2024", "Max context length: 128K tokens"). One fact sheet is "active" at a time, and its facts are injected into the RAG context during queries, ensuring the LLM has access to verified information alongside retrieved documents.

## Creating and managing fact sheets

### Web UI

Knowledge tab > Fact Sheets. Create, edit, activate/deactivate, and organize facts with a notebook-style interface.

### REST API

```bash
# List fact sheets
curl http://localhost:8080/api/fact-sheets

# Create a fact sheet
curl -X POST http://localhost:8080/api/fact-sheets \
  -H "Content-Type: application/json" \
  -d '{"name": "Product Knowledge", "facts": [{"key": "Version", "value": "2.0"}]}'

# Derive facts automatically from content
curl -X POST http://localhost:8080/api/fact-sheets/{id}/derive
```

## Features

- **Manual curation**: Add, edit, delete individual facts
- **Auto-derivation**: Generate facts automatically from indexed content using an LLM
- **Copy/move facts**: Transfer facts between sheets
- **Per-sheet embedding config**: Each fact sheet can use its own embedding model
- **Indexing stats**: Track how many facts are indexed and detect when reindexing is needed
- **Activation**: Only one sheet is active at a time; its facts augment all RAG queries

## Building knowledge graphs from fact sheets

Fact sheets can be used as the source for knowledge graph construction:

```bash
kompile graph build --fact-sheet-id=<id>
```

This extracts entities and relationships from the fact sheet's indexed documents.
