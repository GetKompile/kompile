# Knowledge Graphs

Kompile builds knowledge graphs from ingested documents. Entities and
relationships are extracted by an LLM, deduplicated via entity resolution,
organized into communities, and stored in a graph database (JPA/matrix store
or Neo4j). The graph augments vector search results during RAG queries,
provides causal reasoning via Bayesian networks, and supports direct
exploration through algorithms, Cypher queries, and interactive browsing.

## Architecture

The graph subsystem spans five modules:

| Module | Responsibility |
|--------|---------------|
| `kompile-graph-algorithms` | Pure-Java graph algorithms (Louvain, PageRank, betweenness, etc.) |
| `kompile-graph-neo4j` | Neo4j-backed graph storage, Cypher query engine |
| `kompile-knowledge-graph` | Entity resolution, KG embeddings, maintenance, multi-agent extraction, I/O |
| `kompile-graph-change-tracking` | Mutation recording, temporal queries, WebSocket broadcast |
| `kompile-event-attribution` | Bayesian networks, MEBN, causal reasoning |

Plus graph extraction during crawls (`kompile-crawl-graph`) and server-side
MCP tools (`kompile-tool-graph`).

## Graph structure

### Node types

Nodes are typed entities with properties. The core schema:

| Node level | Description |
|------------|-------------|
| `SOURCE` | A crawled data source (URL, file path, connection) |
| `DOCUMENT` | A single ingested document |
| `SNIPPET` | A chunk of a document (linked via CONTAINS edge) |
| `TABLE` | An extracted table |
| Entity nodes | Extracted entities (Person, Organization, Technology, etc.) |

Each node has: `id`, `type`, `externalId`, `title`, `description`,
`metadata` (JSON), `confidence`, `createdAt`, `updatedAt`.

### Edge types

Edges connect nodes with typed, weighted, directed relationships:

| Field | Description |
|-------|-------------|
| `type` | Relationship type (MENTIONS, WORKS_FOR, DEPENDS_ON, etc.) |
| `weight` | Numeric weight (0.0-3.0) |
| `description` | Human-readable description |
| `confidence` | Extraction confidence score |
| `provenance` | How the edge was created (LLM, rule, manual) |

### Named graphs

Graphs can be organized in a hierarchy:

```bash
kompile graph create-graph --name="Q2 Research" --ontology-type=knowledge_graph
kompile graph create-graph --name="ML Papers" --parent-graph-id=<parent>
kompile graph move-graph --graph-id=<id> --new-parent-id=<target>
kompile graph list-graphs
kompile graph delete-graph --graph-id=<id>
```

Ontology types: `domain_ontology`, `taxonomy`, `knowledge_graph`.

### Entity mentions

`EntityMention` nodes link SNIPPET nodes to entity nodes, tracking where
each entity was found in the source text. This provides full provenance:

```bash
# Show where an entity was mentioned
kompile graph source-chunks <nodeId>
# Returns: source text chunks, confidence, source -> document chain
```

## Graph extraction

### How extraction works

During ingestion, each batch of chunks is sent to an LLM with a structured
prompt that asks it to identify entities and the relationships between them.
The LLM returns JSON with typed entities and relations, each with a
confidence score.

The extraction pipeline:

```
Chunks -> LLM extraction -> Validation -> Entity resolution
       -> Edge computation -> Graph persistence
```

### Extraction configuration

```bash
# Show current config
kompile graph config show

# Set options
kompile graph config set --enabled=true
kompile graph config set --schema-mode=STRICT
kompile graph config set --model-provider=anthropic
kompile graph config set --model-name=claude-sonnet-4-20250514
kompile graph config set --temperature=0.0
kompile graph config set --max-tokens=4096
kompile graph config set --entity-types="Person,Organization,Technology"
kompile graph config set --relationship-types="WORKS_FOR,USES,DEPENDS_ON"
kompile graph config set --deduplication=true
kompile graph config set --similarity-threshold=0.85
kompile graph config set --neo4j-enabled=true
kompile graph config set --neo4j-uri=bolt://localhost:7687

# Toggle extraction on/off
kompile graph config toggle

# Reset to defaults
kompile graph config reset

# View status
kompile graph config status

# List available schema modes, entity types, providers
kompile graph config schema-modes
kompile graph config entity-types
kompile graph config relationship-types
kompile graph config providers
```

### Schema modes

| Mode | Behavior |
|------|----------|
| `NONE` | No schema enforcement -- LLM extracts any types it finds |
| `LENIENT` | Schema-guided: the prompt includes the schema types, but novel types are allowed |
| `STRICT` | Only schema-defined types accepted; everything else is discarded |

### Schema presets

Presets are JSON files stored at `~/.kompile/config/schema-presets/` that
define entity types and relationship types for a domain. Built-in presets
are seeded from the classpath on first run.

```bash
# List presets
kompile graph config presets

# View a preset
kompile graph config preset-detail --id=technology

# Apply a preset (sets entity + relationship types)
kompile graph config apply-preset --id=technology
```

Custom presets are JSON files with `nodeTypes` and `relationshipTypes` arrays.
No hardcoded enums -- types are pure data.

### Multi-agent extraction

Multiple extraction agents can work on the same text with different
strategies, then their results are merged:

```bash
kompile graph extract \
  --file=/path/to/document.pdf \
  --agents=2 \
  --strategy=HIGHEST_CONFIDENCE \
  --min-confidence=0.6 \
  --persist
```

| Merge strategy | Description |
|----------------|-------------|
| `UNION` | Keep all entities and relations from all agents |
| `INTERSECTION` | Keep only entities/relations found by all agents |
| `HIGHEST_CONFIDENCE` | For duplicate entities, keep the highest-confidence version |
| `FIRST_WINS` | First agent's output takes priority |

Agent types:

- **LLM extraction agent** (`LlmRelationExtractionAgent`): Uses an LLM to extract entities and relations
- **Pattern extraction agent** (`PatternRelationExtractionAgent`): Uses regex/rule-based extraction

Local extraction (no server needed):

```bash
kompile graph extract --text="OpenAI released GPT-4 in March 2023" --local
```

The CLI-side `CliGraphExtractor` uses paragraph-boundary chunking at 4000
characters and auto-detects the best available LLM.

### Extraction builders

Builders are long-running extraction jobs that process a fact sheet's
documents:

```bash
# List available builders
kompile graph builder list

# Start an extraction job
kompile graph builder start \
  --fact-sheet-id=<id> \
  --builder=llm \
  --model-provider=anthropic \
  --auto-accept \
  --auto-accept-threshold=0.8

# Monitor jobs
kompile graph builder jobs
kompile graph builder status --job-id=<id>
kompile graph builder logs --job-id=<id> --verbose
kompile graph builder stats

# Cancel a job
kompile graph builder cancel --job-id=<id>

# List available storage backends
kompile graph builder storage-types
```

Builder storage strategies: `JpaGraphStorage` (default database), or
pluggable alternatives via `GraphStorageRegistry`.

### Extraction proposals

Extracted triples go through a proposal workflow before being committed:

```bash
# List pending proposals
kompile graph proposals list --status=PENDING

# Show proposal details
kompile graph proposals show <id>

# Accept or reject
kompile graph proposals accept <id>
kompile graph proposals reject <id>

# Bulk operations
kompile graph proposals bulk-accept --proposal-ids=id1,id2,id3
kompile graph proposals bulk-reject --proposal-ids=id1,id2,id3

# Manually create a triple
kompile graph proposals manual \
  --fact-sheet-id=<id> \
  --subject="OpenAI" \
  --subject-type=Organization \
  --predicate="CREATED" \
  --object="GPT-4" \
  --object-type=Technology
```

When `--auto-accept` is set with a confidence threshold, proposals above
the threshold are committed automatically.

## Entity resolution

Entity resolution deduplicates entities across documents and chunks. The
system provides two resolution engines:

### String-based resolution (default)

Uses `EntityResolutionService`:

1. **Normalize**: lowercase, strip corporate suffixes (Inc, Corp, Ltd, LLC), collapse whitespace
2. **Similarity**: Levenshtein distance with threshold 0.85
3. **Alias matching**: Check if one name is a known alias of another
4. **Merge**: Combine duplicate entities, preserving the highest-confidence version
5. **Remap relations**: Update all edges pointing to merged entities

### Probabilistic resolution (MEBN-based)

Uses `ProbabilisticEntityResolution` for higher-accuracy deduplication:

Instead of a hard Levenshtein threshold, this engine builds a Multi-Entity
Bayesian Network (MEBN) with three evidence fragments:

| MFrag | Observes | Measures |
|-------|----------|----------|
| `NameSimilarity` | Entity names | String similarity |
| `PropertyOverlap` | Entity properties/metadata | Shared attribute count |
| `TypeCompatibility` | Entity types | Same type probability |

The three evidence variables feed into an `EntityIdentity` random variable.
Variable Elimination computes `P(isSameEntity = TRUE | observed similarities)`.
This gives a probability rather than a binary match, allowing for tunable
precision/recall.

See the [Bayesian Networks](#bayesian-networks) section for the full
probabilistic reasoning framework.

## Graph algorithms

All algorithms are pure-Java implementations with no external dependencies:

### Community detection (Louvain)

Detects communities of densely connected nodes using Louvain modularity
maximization:

```bash
# Detect communities
kompile graph communities

# With options
kompile graph communities \
  --algorithm=louvain \
  --fact-sheet-id=<id> \
  --summarize \
  --max-nodes-per-prompt=50
```

The algorithm treats edges as undirected, builds symmetric weighted
adjacency, and iterates up to 20 rounds (configurable). Returns community
IDs, modularity score, and grouped membership.

When `--summarize` is set, an LLM generates a natural-language summary of
each community (`CommunitySummarizer`). These summaries are used by Graph
RAG global search.

### Centrality and ranking

```bash
# PageRank
kompile graph algorithm --type=pagerank --limit=20

# Betweenness centrality
kompile graph algorithm --type=betweenness --limit=20

# Degree centrality
kompile graph algorithm --type=degree --limit=20
```

| Algorithm | What it measures |
|-----------|-----------------|
| PageRank | Overall importance based on link structure |
| Betweenness | How often a node lies on shortest paths between other nodes |
| Degree | Number of connections (in/out/total) |

### Similarity and components

```bash
# Jaccard similarity between nodes
kompile graph algorithm --type=jaccard --limit=20

# Weakly connected components
kompile graph algorithm --type=wcc
```

### Traversal and pathfinding

```bash
# BFS traversal from a node
kompile graph traverse --start-node=<id> --depth=3

# Shortest path (Dijkstra)
kompile graph path --from=<id> --to=<id> --weighted

# Node hierarchy
kompile graph hierarchy --node-id=<id> --depth=5 --format=tree
```

### Algorithmic parameters

All algorithms accept `--params` (JSON) and `--fact-sheet-id` for scoping:

```bash
kompile graph algorithm --type=pagerank \
  --params='{"dampingFactor": 0.85, "maxIterations": 100}' \
  --fact-sheet-id=<id>
```

## KG embeddings

Knowledge graph embeddings map entities and relationships into continuous
vector spaces, enabling link prediction and entity similarity:

### TransE

TransE (Bordes et al., 2013): models relationships as translations in
embedding space. For a triple (h, r, t): **h + r ≈ t**.

Uses ND4J `INDArray` for efficient vectorized computation.

### RotatE

RotatE: models relationships as rotations in complex embedding space.
For a triple (h, r, t): **h ∘ r ≈ t** (element-wise complex multiplication).

### Training and usage

Training uses negative sampling with configurable embedding dimensions.
The `KGEmbeddingJobService` manages training jobs, and
`KGEmbeddingStorageService` persists trained embeddings.

`KGEmbeddingRetriever` uses trained embeddings for:
- Entity similarity search
- Link prediction (predict missing relationships)
- Anomaly detection (triples with low plausibility scores)

REST API: `/api/kg-embeddings/` endpoints for training, querying, and
managing embedding models.

## Bayesian networks

The `kompile-event-attribution` module provides a full Bayesian network
framework built on the knowledge graph:

### Core Bayesian network

A directed acyclic graph (DAG) of `BayesianNode`s with conditional
probability tables (CPTs):

| Component | Description |
|-----------|-------------|
| `BayesianNetwork` | DAG structure with nodes, edges, topological ordering |
| `BayesianNode` | Random variable with states and CPT |
| `Factor` | Probability distribution over variable combinations |
| `NoisyOrCpt` | Noisy-OR conditional probability table (efficient for many parents) |
| `VariableElimination` | Exact inference algorithm |

The `BayesianNetworkBuilder` constructs networks from knowledge graph
subgraphs, mapping entity types and relationships to probabilistic
variables and dependencies.

**Capabilities:**

```
addNode(variable) -> addEdge(parent, child) -> topologicalOrder()
                                            -> getMarkovBlanket(node)
                                            -> getAllFactors()
                                            -> getStatistics()
```

Cycle detection prevents invalid DAG construction. `getMarkovBlanket(node)`
returns the node's parents, children, and children's other parents -- the
minimal set of nodes that makes the target node conditionally independent
of all other nodes.

### Variable Elimination inference

Exact probabilistic inference by systematically eliminating hidden variables:

1. Build factors from all CPTs
2. For each hidden variable (not query, not evidence):
   - Multiply all factors containing that variable
   - Sum out the variable
3. Multiply remaining factors
4. Normalize to get posterior probabilities

### Multi-Entity Bayesian Networks (MEBN)

MEBN extends standard Bayesian networks to handle first-order logic with
uncertainty. The `mebn/` package implements:

| Class | Description |
|-------|-------------|
| `MTheory` | Collection of MFrags (the MEBN theory) |
| `MFrag` | Template for a Bayesian network fragment with typed random variables |
| `RandomVariable` | Parameterized variable (resident, input, or context) |
| `EntityType` | Typed entity placeholder |
| `SSBNGenerator` | Situation-Specific Bayesian Network generator |

**MFrag templates built from the knowledge graph:**

| Template | What it models |
|----------|---------------|
| `EntityRelevance` | How relevant an entity is to a query |
| `CausalInfluence` | Causal chains between events |
| `InformationFlow` | How information propagates through the graph |
| `RiskPropagation` | How risks cascade through dependencies |

`KgMTheoryBuilder` builds an MTheory from a live knowledge graph subgraph
via BFS traversal (max depth 3, max 100 nodes). It maps entity types to
MEBN entity types and relationships to probabilistic dependencies.

**SSBN generation**: When a specific query arrives, the `SSBNGenerator`
instantiates the MFrag templates for the relevant entities, creating a
concrete Bayesian network that can be solved by Variable Elimination.

### MEBN-based entity resolution

`ProbabilisticEntityResolution` replaces hard string-matching thresholds
with probabilistic inference:

```
MFrag: NameSimilarity     --\
MFrag: PropertyOverlap    ----> P(isSameEntity = TRUE | evidence)
MFrag: TypeCompatibility  --/
```

The `MebnProbabilisticScorer` computes per-pair probabilities, enabling
soft entity matching that accounts for multiple evidence types
simultaneously.

### Causal reasoning and event attribution

The event attribution framework builds on Bayesian networks:

| Component | What it does |
|-----------|-------------|
| `CausalTraversal` | Traverse causal chains in the knowledge graph |
| `InfluencePropagation` | Propagate influence scores through the graph |
| `TemporalChainExtractor` | Extract temporal causal sequences |
| `EventAttributionService` | Attribute events to causes using Bayesian inference |
| `AttributionLlmService` | LLM-augmented causal reasoning |

**Inference results:**

| Result type | Description |
|-------------|-------------|
| `BayesianInferenceResult` | Posterior probabilities from Variable Elimination |
| `MpeResult` | Most Probable Explanation -- the most likely assignment of all variables |
| `CounterfactualResult` | What would have happened if a variable had a different value |
| `PredictionResult` | Predicted future events based on current evidence |
| `SensitivityResult` | How sensitive conclusions are to changes in evidence |

**REST API:** `/api/bayesian-network/` and `/api/event-attribution/` endpoints.

### Knowledge base and logic

The MEBN package includes a logic layer:

| Class | Description |
|-------|-------------|
| `KnowledgeBase` | Interface for querying facts |
| `GraphKnowledgeBase` | Knowledge base backed by the knowledge graph |
| `Constraints` | Constraint definitions for MEBN variables |
| `LogicalConstraint` | Logical constraints (type checks, range checks) |

## Graph change tracking

The `kompile-graph-change-tracking` module records every mutation to the
knowledge graph:

### Mutation recording

Every node creation, update, deletion, and every edge mutation is recorded
as a `GraphMutationRecord`. The `GraphMutationRecordingListener` intercepts
all graph operations.

Event types: `NodeMutationEvent`, `EdgeMutationEvent`,
`GraphChangesetCompletedEvent`, `GraphPipelineTriggeredEvent`.

### Temporal queries

`TemporalGraphQueryService` reconstructs the graph state at any point in
time by replaying mutation records. This enables:

- "What did the graph look like last Tuesday?"
- Diff between two points in time
- Audit trail for compliance

### Real-time broadcast

`GraphChangeWebSocketBroadcaster` pushes mutations to connected clients
in real-time via WebSocket. The web UI uses this for live graph updates.

### Update hooks and pipelines

`GraphUpdateHook` (interface) lets you register custom callbacks that fire
on graph mutations. `ConfigDrivenGraphUpdateHook` reads rules from config
files. The `GraphUpdatePipelineConfigService` manages update pipeline
definitions.

## Graph maintenance

```bash
# Health check -- overall graph statistics and issues
kompile graph maintain health

# Prune low-confidence and orphaned nodes
kompile graph maintain prune \
  --fact-sheet-id=<id> \
  --confidence-threshold=0.3 \
  --dry-run

# Validate graph integrity
kompile graph maintain validate

# Relabel nodes by pattern
kompile graph maintain relabel \
  --from="ML" --to="Machine Learning" \
  --title-pattern="^ML$" \
  --dry-run

# List all node labels/types
kompile graph maintain labels

# Bulk delete by criteria
kompile graph maintain bulk-delete \
  --node-type=ENTITY \
  --entity-type=Person \
  --max-confidence=0.3 \
  --orphans-only \
  --dry-run

# Clean up edges
kompile graph maintain edge-cleanup \
  --min-weight=0.2 \
  --no-dangling \
  --no-duplicates \
  --dry-run

# Apply patch rules from a file
kompile graph maintain patch --rule-file=fixes.json --dry-run
```

### Automated maintenance

The `MaintenanceScheduler` runs periodic maintenance tasks:

| Task | What it does |
|------|-------------|
| TTL sweep | Remove nodes/edges past their time-to-live |
| Orphan pruning | Remove nodes with no edges |
| Confidence pruning | Remove low-confidence entities |
| Component pruning | Remove small disconnected components |
| Contradiction detection | Find conflicting facts in the graph |
| Provenance validation | Verify that source documents still exist |
| Snapshot management | Create periodic snapshots for rollback |

### Confidence decay

`ConfidenceDecayCalculator` reduces entity confidence scores over time.
Old, unverified entities gradually lose confidence and are eventually
pruned. Configured via `ConfidenceDecayPolicy`.

## Import and export

Kompile supports 9 graph formats:

| Format | Import | Export | Description |
|--------|--------|--------|-------------|
| JSON | yes | yes | Portable graph with nodes and edges |
| JSON-LD | yes | yes | Linked Data format |
| CSV | yes | yes | Separate files for nodes and edges |
| GraphML | -- | yes | XML graph format |
| Cypher | yes | yes | Neo4j Cypher statements |
| SVG | -- | yes | Vector graphics visualization |
| HTML | -- | yes | Interactive HTML visualization |
| Obsidian Vault | -- | yes | Obsidian-compatible Markdown files |
| Wiki | -- | yes | Wiki-formatted pages |

```bash
# Export
kompile graph export --format=json --output=graph.json
kompile graph export --format=graphml --output=graph.graphml --fact-sheet-id=<id>

# Import
kompile graph import graph.json --format=json
kompile graph import data.csv --format=csv --edges-file=edges.csv
kompile graph import dump.cypher --format=cypher --fact-sheet-id=<id>
```

### Graph merging

Local graph merge (CLI-side) using Levenshtein similarity with threshold
0.85 for entity deduplication:

```bash
kompile graph merge graph1.json graph2.json --output=merged.json
```

## Source weighting

Control how much weight each source gets in Graph RAG retrieval:

```bash
# List current weights
kompile graph weights list

# Set weight for a source (0.0-3.0)
kompile graph weights set --source-id=<id> --weight=1.5 --topic=technology

# Remove a weight override
kompile graph weights remove --source-id=<id>

# Preview how weights affect retrieval
kompile graph weights preview --query="machine learning" --max-results=10

# Provide feedback on retrieval quality
kompile graph weights feedback --source-id=<id> --helpful=true
```

## Graph RAG

Graph RAG augments standard vector search with knowledge graph context.
Three search modes:

### Local search

Embeds the query, finds the most relevant graph nodes by embedding
similarity, then expands their neighborhood:

1. Embed the query
2. Find similar nodes in the graph (vector similarity)
3. Expand to neighbors up to `hopDepth` (default 2, max `maxTraversalNodes` 50)
4. Format entity and relationship context
5. Send combined vector + graph context to the LLM

### Global search

Uses graph algorithms to identify the most important structures:

1. Run PageRank to find top-k important nodes
2. Find connected components
3. Retrieve community summaries (from Louvain detection)
4. Format an overview with entity/relationship/component counts
5. Send to the LLM for synthesis

### Hybrid search

Combines local neighborhood context with vector similarity:

```
score = vectorWeight * searchScore + (1 - vectorWeight) * (1 / hopDistance)
```

Default `vectorWeight=0.5`.

### Three Graph RAG backends

| Backend | Condition | Features |
|---------|-----------|----------|
| Matrix graph | Default (no external deps) | JPA-backed, PageRank, components, embedding similarity |
| JPA graph | `kompile.knowledgegraph.type=jpa` | Hybrid scoring, full JPA queries |
| Neo4j | When `Driver.class` present | Cypher vector search, native graph operations |

```bash
# Graph-augmented search via API
curl -X POST http://localhost:8080/api/graph-rag/search \
  -H "Content-Type: application/json" \
  -d '{
    "query": "How does X relate to Y?",
    "searchType": "LOCAL",
    "k": 10,
    "hopDepth": 2,
    "includeCommunities": true,
    "factSheetId": "fs-001"
  }'
```

### Graph RAG result structure

A Graph RAG result includes:
- Matched entities with types, descriptions, confidence
- Relationships between matched entities
- Community summaries (if communities are enabled)
- Source chunks that mention the matched entities
- Combined context sent to the LLM

## Cypher queries

Direct Cypher queries against the Neo4j backend:

```bash
# Run a query
kompile graph query "MATCH (n:Person)-[r]->(m) RETURN n, r, m LIMIT 10"

# From a file
kompile graph query @complex-query.cypher

# With parameters
kompile graph query "MATCH (n {name: \$name}) RETURN n" --params='{"name": "OpenAI"}'

# Read-only mode (safer)
kompile graph query "MATCH (n) RETURN count(n)" --read-only

# Interactive REPL
kompile graph shell
```

The Cypher shell supports meta-commands: `:help`, `:history`, `:params`,
`:source` (load file), `:sysinfo`, `:exit`.

## Interactive browsing

```bash
# Browse interactively -- shows root SOURCE nodes, navigate through connections
kompile graph browse --start-node=<id>

# Generate a report
kompile graph report --output=GRAPH_REPORT.md --type=summary
kompile graph report --type=communities
kompile graph report --type=entities --fact-sheet-id=<id>
```

## Graph linking

Link source documents to graph concepts:

```bash
# Link sources to concepts by similarity
kompile graph link-sources --fact-sheet-id=<id> --mode=concepts

# Link by embedding similarity
kompile graph link-sources --mode=similarity

# Both
kompile graph link-sources --mode=all

# Show connectivity stats
kompile graph link-sources --connectivity
kompile graph link-sources --isolated
kompile graph link-sources --most-connected --limit=20
```

## Complete CLI reference

| Command | Description |
|---------|-------------|
| `graph stats` | Graph statistics |
| `graph search` | Search nodes by query |
| `graph add-node` | Add a node |
| `graph get-node` | Get node details |
| `graph delete-node` | Delete a node |
| `graph list-nodes` | List nodes by type |
| `graph add-edge` | Add an edge |
| `graph delete-edge` | Delete an edge |
| `graph traverse` | BFS traversal from a node |
| `graph path` | Shortest path between nodes |
| `graph algorithm` | Run graph algorithms |
| `graph communities` | Community detection |
| `graph browse` | Interactive graph browsing |
| `graph shell` | Interactive Cypher REPL |
| `graph query` | Run Cypher queries |
| `graph hierarchy` | Show node hierarchy |
| `graph source-chunks` | Show entity provenance |
| `graph create-graph` | Create a named graph |
| `graph delete-graph` | Delete a named graph |
| `graph list-graphs` | List named graphs |
| `graph move-graph` | Move a graph in the hierarchy |
| `graph import` | Import graph data |
| `graph export` | Export graph data |
| `graph merge` | Merge multiple graph files |
| `graph extract` | Multi-agent extraction |
| `graph build` | Build graph from a fact sheet |
| `graph config` | Extraction configuration |
| `graph builder` | Manage extraction builders/jobs |
| `graph proposals` | Review extraction proposals |
| `graph weights` | Source weighting for retrieval |
| `graph link-sources` | Link sources to concepts |
| `graph maintain` | Graph maintenance operations |
| `graph report` | Generate graph reports |

## MCP tools

The graph is exposed to AI agents through MCP tools:

| Tool | Description |
|------|-------------|
| `knowledge_graph` | Full CRUD, algorithms, traversal, extraction, building |
| `graph_search` | Search nodes and edges |
| `graph_traversal` | Traverse the graph |
| `graph_algorithms` | Run algorithms (PageRank, etc.) |
| `graph_community` | Community detection and summarization |
| `graph_mutation` | Create/update/delete nodes and edges |
| `graph_label` | Manage node labels |
| `named_graph` | Named graph operations |

## Related concepts

- **[Crawl Jobs](crawl-jobs.md)** — how graph extraction is triggered
  during crawls, including `--graph`, `--graph-schema-mode`, schema
  presets, and the full extraction configuration
- **[Information Retrieval](information-retrieval.md)** — how Graph RAG
  adds entity and community context to vector search results during queries
- **[Fact Sheets](fact-sheets.md)** — how `graph build --fact-sheet-id`
  builds graphs from scoped documents, and how fact sheet scoping affects
  graph queries
- **[Agents](agents.md)** — how agents access the graph through MCP tools
  (`knowledge_graph`, `graph_search`, `graph_traversal`, etc.)
- **[Ingestion Pipeline](ingestion-pipeline.md)** — the GRAPH_EXTRACTION
  and ENTITY_RESOLUTION phases in the pipeline
