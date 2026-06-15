# Fact Sheets

A fact sheet is a named collection of key-value assertions that ground
agent responses with verified facts. Think of them as curated knowledge
notebooks that partition your knowledge base into distinct domains.

## Why fact sheets matter

Without fact sheets, every query searches your entire document index.
That's fine when you have one knowledge domain. When you have multiple —
engineering docs, financial reports, legal policies — you want to scope
queries to the relevant domain and inject domain-specific facts into
the LLM context.

Fact sheets give you:

- **Scoped retrieval**: Only documents associated with the active fact
  sheet are searched. See [Information Retrieval](information-retrieval.md)
  for how this affects the query pipeline.
- **Curated facts**: Verified key-value assertions injected into every
  RAG query, ensuring the LLM has access to ground truth.
- **Graph scoping**: Build knowledge graphs from a specific fact sheet's
  documents. See [Knowledge Graphs](knowledge-graphs.md) for graph
  building.
- **Crawl scoping**: Crawl jobs can target a specific fact sheet, keeping
  different data sources organized. See [Crawl Jobs](crawl-jobs.md) for
  the `--fact-sheet` option.

## How they work

Facts are short, structured assertions:

```
Company founded: 2024
Max context length: 128K tokens
Primary language: Python
Deployment target: AWS us-east-1
```

One fact sheet is "active" at a time. Its facts are injected into the RAG
context during queries, alongside retrieved document chunks and graph
context.

## Creating and managing fact sheets

### Web UI

Knowledge tab > Fact Sheets. Create, edit, activate/deactivate, and
organize facts with a notebook-style interface.

### CLI

```bash
# Manage via the web UI or REST API
# CLI access is through the MCP tool or REST calls
```

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

### MCP tools (for agents)

Agents access fact sheets through these tools:

| Tool action | Description |
|-------------|-------------|
| `list_fact_sheets` | List all fact sheets |
| `get_active_fact_sheet` | Get the currently active fact sheet |
| `get_fact_sheet` | Get a specific fact sheet by ID |
| `create_fact_sheet` | Create a new fact sheet |
| `activate_fact_sheet` | Set the active fact sheet |
| `delete_fact_sheet` | Delete a fact sheet |
| `update_fact_sheet` | Update fact sheet content |
| `derive_fact_sheet` | Auto-derive facts from indexed content |

## Features

- **Manual curation**: Add, edit, delete individual facts
- **Auto-derivation**: Generate facts automatically from indexed content
  using an LLM
- **Copy/move facts**: Transfer facts between sheets
- **Per-sheet embedding config**: Each fact sheet can use its own embedding
  model
- **Indexing stats**: Track how many facts are indexed and detect when
  reindexing is needed
- **Activation**: Only one sheet is active at a time; its facts augment
  all RAG queries

## Building knowledge graphs from fact sheets

Fact sheets connect directly to the knowledge graph system:

```bash
kompile graph build --fact-sheet-id=<id>
```

This extracts entities and relationships from the fact sheet's indexed
documents. You can also scope graph exploration to a fact sheet:

```bash
kompile graph search "query" --fact-sheet-id=<id>
kompile graph communities --fact-sheet-id=<id> --summarize
kompile graph algorithm --type=pagerank --fact-sheet-id=<id>
```

See [Knowledge Graphs](knowledge-graphs.md) for the full graph system.

## How fact sheets connect to other features

| Feature | How fact sheets interact |
|---------|------------------------|
| [Crawl jobs](crawl-jobs.md) | `--fact-sheet` scopes a crawl to a specific fact sheet |
| [Knowledge graphs](knowledge-graphs.md) | `graph build --fact-sheet-id` builds a graph from a fact sheet |
| [Information retrieval](information-retrieval.md) | Active fact sheet scopes vector search and injects facts into context |
| [Agents](agents.md) | Agents use MCP tools to query and manage fact sheets |
