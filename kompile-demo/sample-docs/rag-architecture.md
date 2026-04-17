# RAG Architecture in Kompile

Retrieval-augmented generation in Kompile follows a classic two-phase shape: an offline
indexing pipeline that turns raw documents into a searchable vector store, and an online
query pipeline that retrieves relevant context and feeds it to a language model. Both
phases share the same embedding model so that index-time and query-time vectors live in
the same space.

## Indexing Pipeline

1. **Loading**: A document loader reads files from disk, URLs, or upstream systems.
   Kompile ships loaders for PDF (`kompile-loader-pdf-extended`), Office formats
   (`kompile-loader-office`), and a generic Tika fallback.
2. **Parsing**: Loaders produce `Document` objects with raw text and metadata
   (filename, page, source).
3. **Chunking**: A chunker splits long documents into bounded windows. Strategies
   include token-based, sentence-based, recursive, and markdown-aware splitters.
4. **Embedding**: An `EmbeddingModel` implementation turns each chunk into a dense
   vector. Anserini-backed SameDiff encoders are the default; the framework also
   supports OpenAI and PostgresML.
5. **Indexing**: The vectors and source chunks are written to a `VectorStore`
   implementation. Lucene HNSW (via Anserini) is the primary backend.

## Query Pipeline

1. **Embed query**: The same `EmbeddingModel` used at index time encodes the user
   question.
2. **Vector search**: The vector store performs a similarity search (cosine, euclidean,
   or dot product) and returns the top-k matches with metadata.
3. **Context assembly**: A `DocumentRetriever` deduplicates and trims the matches to
   fit the LLM context window.
4. **Generation**: The retrieved chunks and the original query are passed to the active
   `LanguageModel` (loaded from kompile-model-staging) which produces the final answer.
5. **Post-processing**: Optional guardrails, citation insertion, and tool-calling are
   applied before the response is returned to the caller.

The whole pipeline is composable: any of the five interfaces can be swapped at boot via
`@ConditionalOnProperty` settings in `application.properties`.
