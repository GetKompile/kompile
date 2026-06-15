# Code Projects

Register source code repositories and index them for semantic code search. The code indexer parses source files into a graph of entities and relationships, enabling structural queries beyond simple text search.

## Indexing a codebase

```bash
# Index a directory (no server required)
kompile code-index --path=/path/to/project

# Watch for file changes and re-index
kompile code-index watch --path=/path/to/project

# Guided setup
kompile configure code-index
```

## Supported languages

Java, Python, Go, Rust, C++, plus configuration and script files.

## Entity types

The indexer extracts these entity types from source code:

`FILE`, `PACKAGE`, `CLASS`, `INTERFACE`, `METHOD`, `FUNCTION`, `FIELD`, `IMPORT`, `TYPE_ALIAS`, `RATIONALE`

**Rationale entities** are extracted from tagged comments (`NOTE:`, `WHY:`, `HACK:`, `TODO:`, etc.) to capture the reasoning behind code decisions.

## Search

```bash
# Search by keyword
kompile code-index search --query="authentication" --type=METHOD

# Find symbol paths
kompile code-index spath --symbol=UserService

# Find by regex
kompile code-index find --regex=".*Controller"

# Find usages of a symbol
kompile code-index usages --symbol=authenticate

# Replace by regex
kompile code-index replace --regex="oldName" --replacement="newName"

# List indexed projects
kompile code-index list

# Index statistics
kompile code-index stats
```

## Code graph

The code graph provides structural analysis:

```bash
# Build the code graph
kompile code-index graph build

# Find callers of a function
kompile code-index graph callers --symbol=processRequest

# Show relationships for a symbol
kompile code-index graph relations --symbol=UserService

# File-level analysis
kompile code-index graph file --path=src/main/java/Auth.java

# Impact analysis
kompile code-index graph impact --symbol=authenticate

# Dependency analysis
kompile code-index graph deps --symbol=UserService

# Component detection
kompile code-index graph component

# Generate a symbol dossier
kompile code-index graph dossier --symbol=UserService

# Export the graph
kompile code-index graph export --output=graph.json

# Test framework detection
kompile code-index graph test-frameworks

# Find tests for a symbol
kompile code-index graph tests-for --symbol=processRequest

# Code path analysis
kompile code-index graph code-paths --from=A --to=B
```

## Web UI

Code Projects screen in the web UI. Register repositories, trigger indexing, browse the code graph, and use code search to inform agent sessions.

## MCP tools

Code search is exposed as MCP tools (`code_search`, `code_graph`, `local_code_index`) so agents can query your codebase during chat sessions.
