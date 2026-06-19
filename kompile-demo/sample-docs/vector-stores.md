# Vector Stores in Kompile

Kompile defines a single `VectorStore` interface and ships several backends behind it.
Each backend is a separate module that is activated by setting
`kompile.vectorstore.type=<name>` in `application.properties`. Spring Boot's conditional
bean machinery wires the chosen implementation at startup so that only one backend is
active per process.

## Anserini (Lucene HNSW) — Default

`kompile-vectorstore-anserini` is the primary backend. It builds on Anserini, a
Lucene-based information retrieval toolkit, and stores dense vectors in an HNSW index
inside a Lucene segment. Strengths:

- Embedded — no external service to operate.
- Combines dense vector search with lexical search (BM25) for hybrid retrieval.
- Indices are plain directories under `~/.kompile/anserini-vector-index/`, easy to
  back up and ship.
- Supports cosine, euclidean, and dot-product similarity.

## pgvector

`kompile-vectorstore-pgvector` stores vectors as `vector(N)` columns in PostgreSQL using
the pgvector extension. This is a good fit when documents already live in a relational
schema and you want to colocate filtering, joins, and vector search in one query. It
requires a reachable PostgreSQL instance with `CREATE EXTENSION vector` enabled, and the
embedding dimension must match the column definition.

## Chroma

`kompile-vectorstore-chroma` is a thin client for the Chroma vector database. It is
useful when a team has already standardized on Chroma for prototyping or when running
Kompile alongside Python-based ingestion pipelines that write to the same Chroma
instance.

## Choosing a Backend

For a single-machine demo or development workflow, Anserini is the simplest option since
it requires no external services. For multi-tenant production deployments with strict
operational requirements, pgvector is usually preferred because it inherits Postgres'
backup, replication, and access-control story. Chroma fits in between and is most useful
when interoperating with non-JVM tooling.
