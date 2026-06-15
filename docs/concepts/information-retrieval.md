# Information Retrieval

Kompile surfaces information through multiple retrieval mechanisms that
can be used independently or combined. The core RAG pipeline composes
them automatically, but each is accessible on its own through MCP tools,
CLI commands, and REST endpoints.

## Retrieval mechanisms at a glance

| Mechanism | What it searches | Access |
|-----------|-----------------|--------|
| Dense vector search | Embedded document chunks | API, MCP, CLI |
| BM25 keyword search | Full-text index (Lucene) | API, MCP, CLI |
| Hybrid search | Dense + keyword combined | API, MCP, CLI |
| Graph RAG | Knowledge graph entities + communities | API, MCP, CLI |
| Code search | Parsed source code entities (ANTLR4) | API, MCP, CLI |
| Semantic memory | Agent conversation history (embedded) | MCP, CLI |
| Transcript search | Agent session transcripts (grep-style) | MCP, CLI |
| Fact sheet scoping | Partition queries by fact sheet | All |
| Cypher passthrough | Direct graph database queries | API, CLI |
| Cross-index search | Unified search across all backends | API, MCP |

## The RAG pipeline

When you send a chat message, the `KompileRagOrchestratorImpl` runs:

```
Query -> Conversation History -> Query Processor -> Embed
      -> Retrieve (Semantic + BM25) -> Merge + Dedup
      -> Build Context -> LLM Generation -> Update Memory
```

### Step 1: Query processing

`DefaultQueryProcessor` handles the query before retrieval:

**Intent detection** (regex-based):
- FOLLOW_UP: references prior conversation
- CLARIFICATION: asks for more detail
- COMPARISON: compares concepts
- SUMMARIZATION: asks for a summary

**Reference resolution**: Pronoun patterns trigger resolution from
conversation history -- "it", "that", "they" are resolved to specific
entities from prior turns.

### Step 2: Query transformation

Six transformation strategies are available. Set via
`kompile.query.transformer.type`:

| Strategy | What it does |
|----------|-------------|
| `passthrough` (default) | No transformation -- uses the query as-is |
| `compression` | Compresses verbose queries into their essential information need |
| `expansion` | Adds related terms and synonyms to broaden recall |
| `hyde` | Generates a hypothetical answer document, embeds that instead of the query |
| `multi-query` | Decomposes complex questions into 2-4 sub-questions, retrieves on each |
| `step-back` | Generates an abstract generalization of the question, retrieves on both |

Configuration:

```
kompile.query.transformer.type=hyde
kompile.query.transformer.enabled=true
kompile.query.transformer.maxQueries=3
kompile.query.transformer.includeOriginal=true
kompile.query.transformer.temperature=0.7
kompile.query.transformer.maxTokens=256
```

**HyDE** (Hypothetical Document Embedding): Instead of embedding the
question, an LLM generates a hypothetical answer, and that answer is
embedded. This often retrieves better results because the hypothetical
answer is more similar to actual documents than the question is.

**Multi-query**: For complex questions like "Compare X and Y", the
transformer generates sub-questions ("What is X?", "What is Y?",
"How do X and Y differ?") and retrieves on each independently.

**Step-back**: For specific questions like "Why did system X fail on
Tuesday?", generates a broader question ("What are common failure modes
of system X?") and retrieves on both.

### Step 3: Retrieval

The `HybridOptimizedRetriever` combines dense vector search and BM25
keyword search. Embeddings stay as `INDArray` through the entire hot path
for efficiency.

**Retrieval options:**

| Parameter | Default | Description |
|-----------|---------|-------------|
| `semanticK` | 5 | Number of results from vector search |
| `keywordK` | 5 | Number of results from keyword search |
| `similarityThreshold` | 0.5 | Minimum cosine similarity to include |
| `enableKeywordSearch` | true | Include BM25 results |
| `enableSemanticSearch` | true | Include vector results |
| `deduplicateResults` | true | Remove duplicate chunks |
| `metadataFilters` | {} | Filter by metadata key-value pairs |

Convenience constructors:
- `semanticOnly(k, threshold)` -- vector search only
- `keywordOnly(k)` -- BM25 only
- `hybrid(semanticK, keywordK, threshold)` -- both

### Step 4: Reranking

After retrieval, an optional reranking step re-scores results. Reranking
is disabled by default (`kompile.reranker.enabled=false`).

| Reranker type | Description |
|---------------|-------------|
| `RM3` | Relevance feedback with pseudo-relevance |
| `BM25_PRF` | BM25 pseudo-relevance feedback |
| `ROCCHIO` | Rocchio relevance feedback (alpha/beta/gamma weighting) |
| `AXIOM` | Axiomatic reranking |
| `SCORE_TIES_ADJUSTER` | Break score ties using secondary signals |
| `CROSS_ENCODER` | Neural cross-encoder reranking (SameDiff model) |
| `RRF` | Reciprocal Rank Fusion (merge multiple rankings) |
| `NORMALIZE` | Score normalization |
| `MMR` | Maximal Marginal Relevance (diversify results) |

Configuration:

```
kompile.reranker.enabled=true
kompile.reranker.type=rm3
kompile.reranker.fbDocs=10
kompile.reranker.fbTerms=10
kompile.reranker.originalQueryWeight=0.5
```

### Step 5: Freshness scoring

`DocumentFreshnessScorer` adjusts retrieval scores based on document age:

```
adjusted = (1 - freshnessWeight) * rawScore + freshnessWeight * freshnessScore
```

Newer documents get a boost. Configurable `freshnessWeight` controls how
much recency matters.

### Step 6: Context assembly

`ContextBuilder` formats retrieved chunks into LLM context, respecting
a token budget. Multi-vector documents (tables with both `full_table_content`
and text summaries) are handled specially.

### Step 7: LLM generation

The assembled context is sent to the configured LLM provider (OpenAI,
Anthropic, Gemini, or local SameDiff) along with the conversation history
and system prompt.

## Dense vector search

### Anserini/Lucene backend

The primary vector store uses Apache Lucene via Anserini:

| Setting | Default | Description |
|---------|---------|-------------|
| `indexPath` | auto | Path to the Lucene index |
| `similarityFunction` | COSINE | COSINE, EUCLIDEAN, or DOT_PRODUCT |
| `maxDimensions` | 4096 | Maximum embedding dimensions |
| `quantizeInt8` | false | Quantize to 8-bit integers |
| HNSW `enabled` | false | Use HNSW index (vs flat search) |
| HNSW `M` | 16 | HNSW max connections per node |
| HNSW `efConstruction` | 100 | HNSW construction search width |
| `memoryBufferSizeMb` | 512 | In-memory buffer size |
| `batchCommitInterval` | 10 | Commit every N batches |
| `maxDocumentsBeforeCommit` | 5000 | Force commit after N documents |

### Other vector store backends

| Backend | Config value | Description |
|---------|-------------|-------------|
| Anserini | `anserini` | Default; Lucene-backed with HNSW option |
| pgvector | `pgvector` | PostgreSQL pgvector extension |
| Chroma | `chroma` | Chroma vector database |
| Vespa | `vespa` | Vespa search engine |

Set via `kompile.vectorstore.type` in JSON config.

### Embedding models

Dense encoders (SameDiff, local):
- BGE base-en-v1.5 (768 dimensions)
- Arctic Embed L

Sparse encoders:
- CosDPR-distil
- SPLADE++ (ed, sd)

Cross-encoder rerankers:
- MS MARCO MiniLM L-6-v2
- MS MARCO MiniLM L-12-v2
- STSB TinyBERT L-4
- mMARCO mMiniLMv2 L12
- QNLI DistilRoBERTa

External embeddings:
- OpenAI embeddings API
- PostgresML
- Sentence-transformers (Python subprocess)

## BM25 keyword search

Lucene-based full-text search with BM25 scoring. Available as a standalone
retrieval mode or combined with vector search in hybrid mode.

The `AnseriniDocumentRetriever` provides keyword search. BM25 parameters
(`k1=0.9`, `b=0.4`) are tunable.

## Graph RAG

See [Knowledge Graphs](knowledge-graphs.md) for the full Graph RAG
documentation. Three search modes:

| Mode | What it does |
|------|-------------|
| LOCAL | Embed query -> find similar graph nodes -> expand neighborhood -> format context |
| GLOBAL | PageRank top-k -> connected components -> community summaries -> overview |
| HYBRID | Weighted combination: `vectorWeight * searchScore + (1-vectorWeight) * (1/hopDistance)` |

Graph RAG adds entity descriptions, relationship descriptions, and
community summaries to the context alongside vector search results.

## Contextual RAG

Anthropic-style chunk enrichment. When enabled, each chunk gets an
LLM-generated position description that situates it within the source
document ("This chunk is from the introduction of a paper about...").

Disabled by default (`enabled=false`). Config persisted to
`~/.kompile/config/contextual-rag-config.json`.

## Semantic memory

The `SemanticMemoryEngine` embeds and indexes agent conversation turns
for later retrieval. This allows agents to recall information from
previous sessions.

**How it works:**

1. Primary encoder: BGE base-en-v1.5 (768-dim dense) via SameDiff
2. Fallback: TF-IDF bag-of-words cosine similarity
3. Watches `~/.kompile/memory/` and `~/.claude/projects/` directories
4. Refreshes index every 60 seconds
5. Encoder loads asynchronously (non-blocking startup)

**MCP tool: `semantic_memory`**

| Action | Description |
|--------|-------------|
| `search` | Find relevant memory entries (`top_k=5`, `threshold=0.15`) |
| `stats` | Memory index statistics |
| `index_turn` | Index a new conversation turn |

## Transcript search

Cross-agent transcript search that works across 11 agent providers:
Claude Code, Cline, Cursor, Aider, Codex, Continue, Gemini, Qwen, Pi,
OpenCode, and Kompile itself.

**MCP tool: `transcript_search`**

| Action | Description |
|--------|-------------|
| `list` | List all available transcripts |
| `read` | Read a specific session by ID |
| `search` | Grep-style search across transcripts |
| `recent` | Show recent sessions |

Search supports regex/literal matching, case sensitivity, inverted
matching, context lines (before/after), agent filter, session ID filter,
`files_with_matches` mode, and result limits.

## Code search

ANTLR4-based semantic code search that understands source code structure:

**Supported languages:** Java, Python, C++, Go, Kotlin, Rust, C#, SPlan

**Entity types:** CLASS, METHOD, FUNCTION, INTERFACE, FILE, IMPORT,
FIELD, ENUM, RECORD

```bash
# Search code
kompile code-index search "handleRequest"

# Find entities
kompile code-index find --type=CLASS --name="*Service"

# Find usages
kompile code-index usages "MyClass.myMethod"

# Watch for changes
kompile code-index watch
```

**Code graph analysis:**

```bash
# Find callers of a method
kompile code-index callers "processDocument"

# Impact analysis
kompile code-index impact "VectorStore"

# Dependency graph
kompile code-index deps "RagService"

# Component detection
kompile code-index components
```

**MCP tools:** `code_search`, `code_graph`, `local_code_index`

## Fact sheet scoping

Fact sheets partition your knowledge base into named topics. When a
`factSheetId` is set on a query, retrieval is scoped to documents
associated with that fact sheet.

```bash
kompile fact-sheets list
kompile fact-sheets activate <id>
```

All retrieval mechanisms (vector search, Graph RAG, code search) respect
the fact sheet scope.

## Unified knowledge search

The `UnifiedKnowledgeTool` MCP tool (`knowledge_search`) provides a single
entry point that fans out across all available backends:

1. Parallel `DocumentRetriever` futures (vector + keyword)
2. Graph RAG LOCAL search (if graph is available)
3. Merge and deduplicate by chunk ID (highest score wins)
4. Sort by score

`knowledge_status` reports which backends are active and their node counts.

## Cross-index search

The REST endpoint `POST /api/search/cross-index` provides unified search
across the entire indexed corpus. The CLI's `rag_search` MCP tool calls
this endpoint.

Parameters: `query`, `max_results` (default 5),
`search_type` (`semantic`/`keyword`/`hybrid`),
`similarity_threshold` (default 0.0).

## RAG tool for agents

The `RagToolImpl` Spring AI tool exposes RAG search to agents. Features:

- Multi-vector support (tables have both `full_table_content` and text summary)
- Truncation with `result_id` + `fetch_result` pattern for large results
- Configurable `ragMaxDocs` and `ragMaxContentChars` via `McpOptimizationConfig`

## Note embedding

Fact sheet notes are embedded using the same `EmbeddingModel` as source
documents. Embeddings are stored as comma-separated float strings on the
`Note` entity. Embedding happens asynchronously with automatic retry for
pending notes.

## Summary: choosing a retrieval strategy

| Use case | Recommended approach |
|----------|---------------------|
| General document Q&A | Hybrid search (default) |
| Precise keyword lookup | `keywordOnly` mode |
| Conceptual similarity | `semanticOnly` mode |
| Relationship questions | Graph RAG LOCAL |
| Big-picture summaries | Graph RAG GLOBAL |
| Complex multi-part questions | Multi-query transformer + hybrid |
| Specific factual questions | Step-back transformer + hybrid |
| Code navigation | Code search + code graph |
| Cross-session context | Semantic memory |
| Finding past conversations | Transcript search |
| Direct graph exploration | Cypher queries |

## Related concepts

- **[Crawl Jobs](crawl-jobs.md)** — how data enters the system and becomes
  searchable, including source types, pipeline routing, and adaptive batching
- **[Knowledge Graphs](knowledge-graphs.md)** — the graph system that
  powers Graph RAG, including entity extraction, Bayesian networks, graph
  algorithms, and community detection
- **[Fact Sheets](fact-sheets.md)** — how active fact sheets scope retrieval
  and inject curated facts into the context
- **[Agents](agents.md)** — how agents access retrieval through MCP tools
  (`rag_search`, `knowledge_search`, `graph_search`, `code_search`,
  `semantic_memory`, `transcript_search`)
- **[Guardrails and Evaluation](guardrails-and-evaluation.md)** — input/output
  filters that wrap the retrieval pipeline, and the evaluation harness that
  measures retrieval quality
- **[Configuration](../configuration/README.md)** — `model-roles-config.json`
  for embedding and reranking models, `feature-flags-config.json` for
  toggling retrieval features
