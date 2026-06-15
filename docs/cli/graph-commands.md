# Graph Commands

`kompile graph` manages knowledge graphs. Requires a running kompile-server with graph support enabled.

## Basic operations

```bash
kompile graph stats                               # Graph statistics
kompile graph search --query="machine learning"   # Search nodes/edges
kompile graph traverse --start=<id> --depth=3     # Graph traversal
kompile graph path --from=<id> --to=<id>          # Shortest path
kompile graph algorithm --type=pagerank           # Run graph algorithms
kompile graph communities                         # Community detection
```

## Interactive tools

```bash
kompile graph shell                               # Cypher REPL
kompile graph browse                              # Interactive graph browser
kompile graph query "MATCH (n) RETURN n LIMIT 10" # Run a Cypher query
```

## Import and export

```bash
# Supported formats: JSON, JSON-LD, CSV, GraphML, Cypher
kompile graph import --file=data.json --format=json
kompile graph export --output=graph.graphml --format=graphml
```

## Graph management

```bash
# Named graph containers
kompile graph create-graph --name=my-graph
kompile graph list-graphs
kompile graph delete-graph --name=my-graph
kompile graph move-graph --name=child --parent=parent
kompile graph hierarchy                           # View graph tree
kompile graph merge --files=a.json,b.json         # Merge with dedup
```

## Extraction

```bash
# Multi-agent extraction
kompile graph extract --source=file --path=doc.pdf

# Build from a fact sheet
kompile graph build --fact-sheet-id=<id>

# Link sources via shared concepts
kompile graph link-sources

# View source text that produced an entity
kompile graph source-chunks --entity=<id>

# Generate a summary report
kompile graph report
```

## Extraction configuration

```bash
kompile graph config show
kompile graph config set --key=llmProvider --value=anthropic
kompile graph config toggle --key=piiRedaction
kompile graph config reset

# Schema
kompile graph config schema-modes
kompile graph config entity-types
kompile graph config relationship-types
kompile graph config providers

# Presets
kompile graph config presets
kompile graph config preset-detail --name=technology
kompile graph config apply-preset --name=technology
```

## Extraction builders and jobs

```bash
kompile graph builder list-builders
kompile graph builder start-job --builder=<id>
kompile graph builder jobs
kompile graph builder status --job=<id>
kompile graph builder cancel --job=<id>
kompile graph builder logs --job=<id>
kompile graph builder stats
kompile graph builder storage-types
```

## Proposals

Review LLM-extracted entities before adding them to the graph:

```bash
kompile graph proposals list
kompile graph proposals show --id=<id>
kompile graph proposals accept --id=<id>
kompile graph proposals reject --id=<id>
kompile graph proposals bulk-accept
kompile graph proposals bulk-reject
kompile graph proposals manual                    # Manually add a proposal
```

## RAG retrieval weights

```bash
kompile graph weights list
kompile graph weights set --source=<id> --weight=1.5
kompile graph weights remove --source=<id>
kompile graph weights preview --query="test"
kompile graph weights feedback --query="test" --helpful=true
```

## Maintenance

```bash
kompile graph maintain health                     # Health check
kompile graph maintain prune                      # Remove orphaned nodes
kompile graph maintain validate                   # Integrity validation
kompile graph maintain relabel                    # Relabel nodes
kompile graph maintain labels                     # List all labels
kompile graph maintain bulk-delete                # Bulk delete by criteria
kompile graph maintain edge-cleanup               # Clean up stale edges
kompile graph maintain patch                      # Apply patches
```
