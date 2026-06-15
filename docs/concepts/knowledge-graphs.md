# Knowledge Graphs

Kompile builds knowledge graphs from your ingested documents. Entities and relationships are extracted by an LLM, deduplicated via entity resolution, and stored in a graph database (Neo4j or native storage).

## How it works

During ingestion, each chunk is sent to an LLM that extracts structured entities (people, organizations, concepts, technologies, locations) and the relationships between them. Entity resolution then deduplicates entries using embedding similarity combined with string matching.

The resulting graph augments vector search results during RAG queries (Graph RAG), providing richer context about how concepts relate to each other.

## Graph structure

- **Nodes**: Entities with a type, name, and properties
- **Edges**: Relationships between entities with a type and optional properties
- **Named graphs**: Graphs can be nested in a hierarchy (create, move, reparent)
- **Communities**: Detected via community detection algorithms for summarization

## Schema modes

| Mode | Description |
|------|------------|
| Free-form | The LLM decides what entity and relationship types to extract |
| Schema-constrained | Only extract types from a predefined schema |

Schema presets provide ready-made type sets for common domains. You can also define custom entity types and relationship types.

## CLI usage

```bash
# Statistics
kompile graph stats

# Search
kompile graph search --query="machine learning"

# Traverse from a node
kompile graph traverse --start-node=<id> --depth=3

# Find shortest path
kompile graph path --from=<id> --to=<id>

# Run graph algorithms
kompile graph algorithm --type=pagerank

# Community detection
kompile graph communities

# Interactive Cypher REPL
kompile graph shell

# Browse interactively
kompile graph browse

# Import/export
kompile graph import --file=data.json --format=json
kompile graph export --output=graph.graphml --format=graphml
```

Supported import/export formats: JSON, JSON-LD, CSV, GraphML, Cypher.

## Graph extraction management

```bash
# Configure extraction
kompile graph config show
kompile graph config set --key=llmProvider --value=anthropic
kompile graph config apply-preset --name=technology

# Manage extraction builders and jobs
kompile graph builder list-builders
kompile graph builder start-job --builder=<id>
kompile graph builder jobs

# Review extraction proposals
kompile graph proposals list
kompile graph proposals accept --id=<id>
kompile graph proposals bulk-accept
```

## Graph maintenance

```bash
# Health check
kompile graph maintain health

# Prune orphaned nodes
kompile graph maintain prune

# Validate graph integrity
kompile graph maintain validate

# Relabel nodes
kompile graph maintain relabel

# Clean up edges
kompile graph maintain edge-cleanup
```

## Multi-agent extraction

```bash
# Extract from text, file, or fact sheet using multiple agents
kompile graph extract --source=file --path=/path/to/doc.pdf
```

## Graph RAG queries

When graph RAG is enabled, queries use both vector search and graph traversal:

1. Vector search finds relevant document chunks
2. Entity mentions in the query are matched to graph nodes
3. Related entities and relationships are traversed
4. Community summaries provide broader context
5. All context is combined and sent to the LLM

```bash
# Graph-augmented search via API
curl http://localhost:8080/api/graph-rag/search \
  -H "Content-Type: application/json" \
  -d '{"query": "How does X relate to Y?", "mode": "local"}'
```

Modes: `local` (entity neighborhood) or `global` (community summaries).

## RAG retrieval weights

Control how much weight each source gets in RAG retrieval:

```bash
kompile graph weights list
kompile graph weights set --source=<id> --weight=1.5
kompile graph weights preview --query="test"
kompile graph weights feedback --query="test" --helpful=true
```
