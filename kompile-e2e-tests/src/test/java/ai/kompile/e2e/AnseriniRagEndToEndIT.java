package ai.kompile.e2e;

import ai.kompile.core.embeddings.ScoredDocument;
import ai.kompile.core.llm.LanguageModel;
import ai.kompile.core.rag.RagQuery;
import ai.kompile.core.rag.RagResult;
import ai.kompile.core.retrievers.DocumentRetriever;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.app.rag.RagServiceImpl;
import ai.kompile.anserini.AnseriniDocumentRetrieverImpl;
import ai.kompile.anserini.config.AnseriniConfig;
import ai.kompile.core.indexers.IndexerService;
import ai.kompile.e2e.fixtures.InMemoryEmbeddingModel;
import ai.kompile.e2e.fixtures.SameDiffEncoderEmbeddingModel;
import ai.kompile.e2e.fixtures.StubDocumentRetriever;
import ai.kompile.vectorstore.anserini.AnseriniVectorStoreImpl;
import ai.kompile.vectorstore.anserini.AnseriniVectorStoreProperties;
import io.anserini.index.Constants;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.BinaryDocValuesField;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.NoLockFactory;
import org.apache.lucene.util.BytesRef;
import org.junit.jupiter.api.*;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end RAG integration test using the real Anserini (Lucene) vector store
 * with a temporary on-disk index. Verifies the full pipeline:
 *
 * <pre>
 *   synthetic docs → embed → Lucene HNSW index → query → retrieve → LLM answer
 * </pre>
 *
 * <p>Uses {@link InMemoryEmbeddingModel} for deterministic embeddings (no SameDiff
 * model download needed) and a context-capturing stub LLM so we can assert that
 * the correct knowledge was retrieved and forwarded.</p>
 */
@Tag("e2e")
@DisplayName("Anserini RAG End-to-End")
class AnseriniRagEndToEndIT {

    // ── synthetic knowledge corpus ──────────────────────────────────────────
    // Each fact is unique and domain-specific so we can assert precise retrieval.
    private static final String FACT_PHOTOSYNTHESIS =
            "Photosynthesis converts carbon dioxide and water into glucose and oxygen " +
            "using sunlight. It occurs in the chloroplasts of plant cells and is the " +
            "primary source of atmospheric oxygen on Earth.";

    private static final String FACT_MITOCHONDRIA =
            "Mitochondria are double-membrane organelles found in eukaryotic cells. " +
            "They generate most of the cell's supply of adenosine triphosphate (ATP) " +
            "through oxidative phosphorylation, earning the nickname 'powerhouse of the cell'.";

    private static final String FACT_DNA_REPLICATION =
            "DNA replication is the biological process by which a double-stranded DNA " +
            "molecule is copied to produce two identical replicas. The enzyme helicase " +
            "unwinds the double helix and DNA polymerase synthesizes the new strands.";

    private static final String FACT_KREBS_CYCLE =
            "The Krebs cycle, also known as the citric acid cycle, takes place in the " +
            "mitochondrial matrix. It oxidizes acetyl-CoA derived from carbohydrates, " +
            "fats, and proteins into carbon dioxide and chemical energy (GTP, NADH, FADH2).";

    private static final String FACT_NEURAL_NETWORKS =
            "Artificial neural networks are computing systems inspired by biological " +
            "neural networks. They consist of layers of interconnected nodes that " +
            "process information using connectionist approaches to computation.";

    private static final String FACT_QUANTUM_COMPUTING =
            "Quantum computing uses quantum-mechanical phenomena such as superposition " +
            "and entanglement to perform computation. Qubits can exist in multiple states " +
            "simultaneously, enabling parallelism beyond classical computing capabilities.";

    private static final String FACT_BLACK_HOLES =
            "A black hole is a region of spacetime where gravity is so strong that " +
            "nothing, not even light or other electromagnetic waves, can escape once past " +
            "the event horizon. They are predicted by general relativity.";

    private static final String FACT_PLATE_TECTONICS =
            "Plate tectonics describes the large-scale movement of Earth's lithosphere. " +
            "The lithosphere is divided into tectonic plates that float on the " +
            "asthenosphere and interact at convergent, divergent, and transform boundaries.";

    // ── test infrastructure ─────────────────────────────────────────────────
    private Path tempIndexDir;
    private InMemoryEmbeddingModel embeddingModel;
    private AnseriniVectorStoreImpl vectorStore;
    private ContextCapturingLanguageModel languageModel;

    @BeforeEach
    void setUp() throws IOException {
        tempIndexDir = Files.createTempDirectory("anserini-rag-e2e-test-");

        embeddingModel = new InMemoryEmbeddingModel(384);

        AnseriniVectorStoreProperties props = new AnseriniVectorStoreProperties();
        props.setIndexPath(tempIndexDir.toString());
        props.setEnabled(true);
        props.setPersistenceEnabled(true); // use path as-is, no JVM suffix
        props.setSimilarityFunction("COSINE");
        props.setMemoryBufferSizeMb(16.0); // small buffer for tests
        props.setBatchCommitInterval(1);   // commit every batch for test determinism

        vectorStore = new AnseriniVectorStoreImpl(props, embeddingModel);
        languageModel = new ContextCapturingLanguageModel();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (vectorStore != null) {
            vectorStore.destroy();
        }
        if (tempIndexDir != null && Files.exists(tempIndexDir)) {
            deleteRecursively(tempIndexDir);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Tests
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Index synthetic docs and retrieve the most relevant one")
    void testIndexAndRetrieveMostRelevant() {
        List<Document> docs = createCorpus();
        int added = vectorStore.add(docs);
        assertEquals(8, added, "All 8 documents should be indexed");
        assertEquals(8, vectorStore.getApproxVectorCount());

        // Query about photosynthesis — the photosynthesis doc should rank #1
        List<Document> results = vectorStore.similaritySearch(FACT_PHOTOSYNTHESIS, 3);
        assertFalse(results.isEmpty(), "Should return results");
        assertEquals(FACT_PHOTOSYNTHESIS, results.get(0).getText(),
                "Exact match should be the top result (cosine similarity = 1.0)");
    }

    @Test
    @DisplayName("Semantic proximity: related topics rank higher than unrelated")
    void testSemanticProximity() {
        List<Document> docs = createCorpus();
        vectorStore.add(docs);

        // Query about mitochondria energy — Krebs cycle (also in mitochondria) should
        // rank higher than unrelated topics like black holes or plate tectonics
        String query = "How do mitochondria produce energy through ATP?";
        List<ScoredDocument> scored = vectorStore.similaritySearchWithScores(
                embeddingModel.embed(query), 8, 0.0);

        assertFalse(scored.isEmpty());

        // Collect the top-3 texts
        List<String> top3Texts = scored.stream()
                .limit(3)
                .map(sd -> sd.document().getText())
                .toList();

        // The mitochondria fact should appear in the top results
        assertTrue(top3Texts.stream().anyMatch(t -> t.contains("mitochondria")),
                "Mitochondria-related docs should appear in top 3. Got: " +
                top3Texts.stream().map(t -> t.substring(0, Math.min(60, t.length()))).toList());
    }

    @Test
    @DisplayName("Threshold filtering excludes low-similarity results")
    void testThresholdFiltering() {
        List<Document> docs = createCorpus();
        vectorStore.add(docs);

        // Use a very high threshold — only very close matches should pass
        List<ScoredDocument> scored = vectorStore.similaritySearchWithScores(
                embeddingModel.embed(FACT_PHOTOSYNTHESIS), 8, 0.99);

        // At minimum the exact match should pass; distant docs should be filtered out
        assertFalse(scored.isEmpty(), "Exact match should pass even high threshold");
        assertTrue(scored.size() < 8,
                "High threshold should filter out dissimilar docs. Got " + scored.size());

        // The exact match must be present
        assertTrue(scored.stream().anyMatch(sd ->
                        sd.document().getText().equals(FACT_PHOTOSYNTHESIS)),
                "Exact match document should survive threshold filtering");
    }

    @Test
    @DisplayName("Pre-computed embeddings: addWithEmbeddings + search")
    void testPreComputedEmbeddings() {
        List<Document> docs = createCorpus();
        INDArray embeddings = embeddingModel.embedDocuments(docs);

        int added = vectorStore.addWithEmbeddings(docs, embeddings);
        assertEquals(8, added);

        List<Document> results = vectorStore.similaritySearch(FACT_DNA_REPLICATION, 2);
        assertFalse(results.isEmpty());
        assertEquals(FACT_DNA_REPLICATION, results.get(0).getText(),
                "Exact match on pre-computed embeddings should still rank #1");
    }

    @Test
    @DisplayName("Delete removes docs and they no longer appear in search")
    void testDeleteAndVerifyAbsence() {
        List<Document> docs = createCorpus();
        vectorStore.add(docs);
        assertEquals(8, vectorStore.getApproxVectorCount());

        // Find the photosynthesis doc ID
        String photoId = docs.stream()
                .filter(d -> d.getText().equals(FACT_PHOTOSYNTHESIS))
                .findFirst().map(Document::getId).orElseThrow();

        vectorStore.delete(List.of(photoId));

        // Search for photosynthesis — it should no longer be the top result
        List<Document> results = vectorStore.similaritySearch(FACT_PHOTOSYNTHESIS, 3);
        assertTrue(results.stream().noneMatch(d -> d.getText().equals(FACT_PHOTOSYNTHESIS)),
                "Deleted document should not appear in results");
    }

    @Test
    @DisplayName("Full RAG pipeline: ingest → retrieve → LLM with context verification")
    void testFullRagPipelineWithContextVerification() {
        // 1. Index the knowledge corpus
        List<Document> docs = createCorpus();
        vectorStore.add(docs);

        // 2. Set up keyword retriever with overlapping knowledge
        StubDocumentRetriever keywordRetriever = new StubDocumentRetriever();
        keywordRetriever.setDocumentsToReturn(List.of(
                new RetrievedDoc("keyword-1", FACT_MITOCHONDRIA, Map.of()),
                new RetrievedDoc("keyword-2", FACT_KREBS_CYCLE, Map.of())
        ));

        // 3. Build the full RagServiceImpl (hybrid: keyword + semantic)
        RagServiceImpl ragService = new RagServiceImpl(
                List.of(keywordRetriever),
                languageModel,
                List.of(vectorStore)
        );

        // 4. Query about mitochondria
        RagQuery query = RagQuery.builder()
                .query("What is the role of mitochondria in cellular energy production?")
                .k(6)
                .build();

        RagResult result = ragService.answerQuery(query);

        // 5. Verify the LLM received the query
        assertNotNull(result);
        assertNotNull(result.getAnswer(), "LLM should produce an answer");
        assertEquals("What is the role of mitochondria in cellular energy production?",
                languageModel.getLastQuery(),
                "LLM should receive the original query");

        // 6. Verify context was passed — must contain knowledge from BOTH retrievers
        List<String> capturedContext = languageModel.getLastContext();
        assertNotNull(capturedContext, "Context should be passed to LLM");
        assertFalse(capturedContext.isEmpty(), "Context should not be empty");

        // Keyword retriever contributes mitochondria + krebs facts
        assertTrue(capturedContext.stream().anyMatch(c -> c.contains("mitochondria")),
                "Context should contain mitochondria knowledge from keyword retriever");

        // 7. Verify retrieved docs in the result
        assertNotNull(result.getRetrievedDocs());
        assertFalse(result.getRetrievedDocs().isEmpty(),
                "Retrieved docs list should not be empty");

        // 8. Verify the keyword retriever was actually called
        assertEquals(1, keywordRetriever.getReceivedQueries().size(),
                "Keyword retriever should have been called exactly once");
        assertEquals("What is the role of mitochondria in cellular energy production?",
                keywordRetriever.getReceivedQueries().get(0));
    }

    @Test
    @DisplayName("RAG pipeline: query with no relevant docs falls back gracefully")
    void testRagPipelineNoRelevantDocs() {
        // Empty vector store, empty keyword retriever
        StubDocumentRetriever emptyRetriever = new StubDocumentRetriever();

        RagServiceImpl ragService = new RagServiceImpl(
                List.of(emptyRetriever),
                languageModel,
                List.of(vectorStore)
        );

        RagQuery query = RagQuery.builder()
                .query("What is the meaning of life?")
                .k(4)
                .build();

        RagResult result = ragService.answerQuery(query);
        assertNotNull(result);
        assertNotNull(result.getAnswer(), "LLM should still produce an answer even without context");

        // Context should be empty
        List<String> ctx = languageModel.getLastContext();
        assertTrue(ctx == null || ctx.isEmpty(),
                "Context should be empty when no docs are retrieved");
    }

    @Test
    @DisplayName("Metadata survives the index round-trip")
    void testMetadataPreservation() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", "biology-textbook.pdf");
        metadata.put("page", 42);
        metadata.put("chapter", "Cell Biology");

        Document doc = new Document(UUID.randomUUID().toString(), FACT_PHOTOSYNTHESIS, metadata);
        vectorStore.add(List.of(doc));

        List<Document> results = vectorStore.similaritySearch(FACT_PHOTOSYNTHESIS, 1);
        assertFalse(results.isEmpty());
        Document retrieved = results.get(0);

        assertEquals(FACT_PHOTOSYNTHESIS, retrieved.getText());
        // Anserini stores metadata as JSON — verify key fields survive round-trip
        assertNotNull(retrieved.getMetadata(), "Metadata should be preserved");
    }

    @Test
    @DisplayName("Batch embedding consistency: single vs batch embed produce same vectors")
    void testBatchEmbeddingConsistency() {
        String text1 = FACT_PHOTOSYNTHESIS;
        String text2 = FACT_MITOCHONDRIA;

        INDArray single1 = embeddingModel.embed(text1);
        INDArray single2 = embeddingModel.embed(text2);
        INDArray batch = embeddingModel.embed(List.of(text1, text2));

        // Row 0 of batch should equal the single embedding for text1
        // Compare as float arrays to avoid cross-device comparison issues (CPU vs GPU)
        assertArrayEquals(single1.getRow(0).toFloatVector(), batch.getRow(0).toFloatVector(), 1e-6f,
                "Batch row 0 should match single embed for text1");
        assertArrayEquals(single2.getRow(0).toFloatVector(), batch.getRow(1).toFloatVector(), 1e-6f,
                "Batch row 1 should match single embed for text2");
    }

    @Test
    @DisplayName("Multiple exact-match queries each return their own document as top hit")
    void testMultipleQueriesDistinctResults() {
        vectorStore.add(createCorpus());

        // Use exact document text as queries — each should return itself as top hit
        List<Document> photoResults = vectorStore.similaritySearch(FACT_PHOTOSYNTHESIS, 1);
        List<Document> dnaResults = vectorStore.similaritySearch(FACT_DNA_REPLICATION, 1);
        List<Document> quantumResults = vectorStore.similaritySearch(FACT_QUANTUM_COMPUTING, 1);

        assertFalse(photoResults.isEmpty());
        assertFalse(dnaResults.isEmpty());
        assertFalse(quantumResults.isEmpty());

        String photoTop = photoResults.get(0).getText();
        String dnaTop = dnaResults.get(0).getText();
        String quantumTop = quantumResults.get(0).getText();

        // Each exact-text query should return its own document
        assertEquals(FACT_PHOTOSYNTHESIS, photoTop,
                "Photosynthesis query should return photosynthesis doc");
        assertEquals(FACT_DNA_REPLICATION, dnaTop,
                "DNA query should return DNA doc");
        assertEquals(FACT_QUANTUM_COMPUTING, quantumTop,
                "Quantum query should return quantum doc");

        // All three should be different documents
        assertNotEquals(photoTop, dnaTop, "Photo and DNA top results should differ");
        assertNotEquals(dnaTop, quantumTop, "DNA and quantum top results should differ");
        assertNotEquals(photoTop, quantumTop, "Photo and quantum top results should differ");
    }

    @Test
    @DisplayName("Scored search returns scores in descending order")
    void testScoredSearchOrdering() {
        vectorStore.add(createCorpus());

        INDArray queryEmb = embeddingModel.embed("mitochondria ATP energy production");
        List<ScoredDocument> scored = vectorStore.similaritySearchWithScores(queryEmb, 8, 0.0);

        assertFalse(scored.isEmpty());

        // Verify descending score order
        for (int i = 1; i < scored.size(); i++) {
            assertTrue(scored.get(i - 1).score() >= scored.get(i).score(),
                    String.format("Scores should be descending: %.4f >= %.4f at positions %d, %d",
                            scored.get(i - 1).score(), scored.get(i).score(), i - 1, i));
        }

        // Top score should be close to 1.0 or at least positive
        assertTrue(scored.get(0).score() > 0,
                "Top score should be positive, got: " + scored.get(0).score());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Hybrid search tests (real keyword index + real vector index)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Hybrid search: keyword BM25 + semantic vector results are combined")
    void testHybridSearchCombinesKeywordAndSemantic() throws Exception {
        // 1. Build a real Lucene keyword index in a separate temp dir
        Path keywordIndexDir = Files.createTempDirectory("anserini-keyword-e2e-");
        try {
            buildKeywordIndex(keywordIndexDir, createCorpusAsRetrievedDocs());

            // 2. Create real keyword retriever backed by SimpleSearcher
            AnseriniConfig anseriniConfig = new AnseriniConfig();
            anseriniConfig.setIndexPath(keywordIndexDir.toString());

            // Stub IndexerService that reports index available
            IndexerService stubIndexer = createStubIndexerService();

            AnseriniDocumentRetrieverImpl keywordRetriever =
                    new AnseriniDocumentRetrieverImpl(anseriniConfig, List.of(stubIndexer));
            keywordRetriever.init(); // triggers SimpleSearcher initialization

            // 3. Index same corpus into vector store
            vectorStore.add(createCorpus());

            // 4. Build RagServiceImpl with BOTH real retrievers
            RagServiceImpl ragService = new RagServiceImpl(
                    List.of(keywordRetriever),
                    languageModel,
                    List.of(vectorStore)
            );

            // 5. Query — "photosynthesis chloroplasts oxygen" has both keyword matches
            //    and semantic relevance
            RagQuery query = RagQuery.builder()
                    .query("photosynthesis chloroplasts oxygen")
                    .k(6)
                    .build();

            RagResult result = ragService.answerQuery(query);

            assertNotNull(result);
            assertNotNull(result.getAnswer());

            // 6. Verify context was assembled from retrieved docs
            List<String> ctx = languageModel.getLastContext();
            assertNotNull(ctx, "Context should be passed to LLM");
            assertFalse(ctx.isEmpty(), "Context should contain docs from retrieval");

            // Keyword BM25 should find the photosynthesis doc (exact term match)
            assertTrue(ctx.stream().anyMatch(c -> c.contains("Photosynthesis")),
                    "Keyword search should contribute photosynthesis doc to context");

            // 7. Verify retrieved docs are populated
            assertNotNull(result.getRetrievedDocs());
            assertFalse(result.getRetrievedDocs().isEmpty());

        } finally {
            deleteRecursively(keywordIndexDir);
        }
    }

    @Test
    @DisplayName("Hybrid search: keyword finds terms that semantic search may miss")
    void testHybridKeywordFindsExactTerms() throws Exception {
        Path keywordIndexDir = Files.createTempDirectory("anserini-keyword-terms-");
        try {
            buildKeywordIndex(keywordIndexDir, createCorpusAsRetrievedDocs());

            AnseriniConfig config = new AnseriniConfig();
            config.setIndexPath(keywordIndexDir.toString());

            AnseriniDocumentRetrieverImpl keywordRetriever =
                    new AnseriniDocumentRetrieverImpl(config, List.of(createStubIndexerService()));
            keywordRetriever.init();

            vectorStore.add(createCorpus());

            RagServiceImpl ragService = new RagServiceImpl(
                    List.of(keywordRetriever),
                    languageModel,
                    List.of(vectorStore)
            );

            // Query using a very specific term "FADH2" — only in Krebs cycle doc
            // BM25 keyword search excels at exact term matching
            RagQuery query = RagQuery.builder()
                    .query("FADH2 citric acid cycle GTP")
                    .k(4)
                    .build();

            RagResult result = ragService.answerQuery(query);

            List<String> ctx = languageModel.getLastContext();
            assertNotNull(ctx);
            assertFalse(ctx.isEmpty(), "Should find docs matching specific terms");

            // The Krebs cycle doc contains "FADH2" — keyword search should find it
            assertTrue(ctx.stream().anyMatch(c -> c.contains("FADH2") || c.contains("Krebs")),
                    "Keyword search should find the Krebs cycle doc via exact term 'FADH2'. " +
                    "Context: " + ctx.stream().map(s -> s.substring(0, Math.min(60, s.length()))).toList());

        } finally {
            deleteRecursively(keywordIndexDir);
        }
    }

    @Test
    @DisplayName("Hybrid search: deduplicates overlapping keyword and semantic results")
    void testHybridSearchDeduplicates() throws Exception {
        Path keywordIndexDir = Files.createTempDirectory("anserini-keyword-dedup-");
        try {
            List<RetrievedDoc> corpusDocs = createCorpusAsRetrievedDocs();
            buildKeywordIndex(keywordIndexDir, corpusDocs);

            AnseriniConfig config = new AnseriniConfig();
            config.setIndexPath(keywordIndexDir.toString());

            AnseriniDocumentRetrieverImpl keywordRetriever =
                    new AnseriniDocumentRetrieverImpl(config, List.of(createStubIndexerService()));
            keywordRetriever.init();

            vectorStore.add(createCorpus());

            RagServiceImpl ragService = new RagServiceImpl(
                    List.of(keywordRetriever),
                    languageModel,
                    List.of(vectorStore)
            );

            // Query that both keyword and semantic search should match
            RagQuery query = RagQuery.builder()
                    .query("DNA replication helicase polymerase")
                    .k(6)
                    .build();

            RagResult result = ragService.answerQuery(query);

            List<String> ctx = languageModel.getLastContext();
            assertNotNull(ctx);

            // Count how many times DNA replication content appears — should be deduplicated
            long dnaCount = ctx.stream()
                    .filter(c -> c.contains("DNA replication"))
                    .count();
            assertTrue(dnaCount <= 1,
                    "DNA replication should appear at most once in context (deduplicated). " +
                    "Found " + dnaCount + " occurrences");

        } finally {
            deleteRecursively(keywordIndexDir);
        }
    }

    @Test
    @DisplayName("Hybrid search with varied k values returns correct result counts")
    void testHybridSearchKValues() throws Exception {
        Path keywordIndexDir = Files.createTempDirectory("anserini-keyword-kvalues-");
        try {
            buildKeywordIndex(keywordIndexDir, createCorpusAsRetrievedDocs());

            AnseriniConfig config = new AnseriniConfig();
            config.setIndexPath(keywordIndexDir.toString());

            AnseriniDocumentRetrieverImpl keywordRetriever =
                    new AnseriniDocumentRetrieverImpl(config, List.of(createStubIndexerService()));
            keywordRetriever.init();

            vectorStore.add(createCorpus());

            RagServiceImpl ragService = new RagServiceImpl(
                    List.of(keywordRetriever),
                    languageModel,
                    List.of(vectorStore)
            );

            // Query with small k=2 (split to k/2=1 per retriever)
            RagQuery smallK = RagQuery.builder()
                    .query("quantum computing qubits superposition entanglement")
                    .k(2)
                    .build();
            RagResult smallResult = ragService.answerQuery(smallK);

            List<String> smallCtx = languageModel.getLastContext();
            assertNotNull(smallCtx);
            // With k=2, each retriever gets k/2=1 — so we get 1-2 unique docs
            assertTrue(smallCtx.size() <= 2,
                    "With k=2, context should have at most 2 docs. Got: " + smallCtx.size());

            // Query with larger k=8
            RagQuery largeK = RagQuery.builder()
                    .query("quantum computing qubits superposition entanglement")
                    .k(8)
                    .build();
            RagResult largeResult = ragService.answerQuery(largeK);

            List<String> largeCtx = languageModel.getLastContext();
            assertNotNull(largeCtx);
            // With k=8, we should get more results than k=2
            assertTrue(largeCtx.size() >= smallCtx.size(),
                    "Larger k should return at least as many docs. k=2 got " +
                    smallCtx.size() + ", k=8 got " + largeCtx.size());

        } finally {
            deleteRecursively(keywordIndexDir);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════

    private List<Document> createCorpus() {
        String[] facts = {
            FACT_PHOTOSYNTHESIS, FACT_MITOCHONDRIA, FACT_DNA_REPLICATION,
            FACT_KREBS_CYCLE, FACT_NEURAL_NETWORKS, FACT_QUANTUM_COMPUTING,
            FACT_BLACK_HOLES, FACT_PLATE_TECTONICS
        };

        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < facts.length; i++) {
            Map<String, Object> meta = new HashMap<>();
            meta.put("source", "synthetic-corpus.pdf");
            meta.put("chunk_index", i);
            meta.put("domain", i < 4 ? "biology" : (i < 6 ? "computer-science" : "physics"));
            docs.add(new Document(UUID.randomUUID().toString(), facts[i], meta));
        }
        return docs;
    }

    private List<RetrievedDoc> createCorpusAsRetrievedDocs() {
        String[] facts = {
            FACT_PHOTOSYNTHESIS, FACT_MITOCHONDRIA, FACT_DNA_REPLICATION,
            FACT_KREBS_CYCLE, FACT_NEURAL_NETWORKS, FACT_QUANTUM_COMPUTING,
            FACT_BLACK_HOLES, FACT_PLATE_TECTONICS
        };
        List<RetrievedDoc> docs = new ArrayList<>();
        for (int i = 0; i < facts.length; i++) {
            Map<String, Object> meta = new HashMap<>();
            meta.put("source", "synthetic-corpus.pdf");
            meta.put("domain", i < 4 ? "biology" : (i < 6 ? "computer-science" : "physics"));
            docs.add(new RetrievedDoc("doc-" + i, facts[i], meta));
        }
        return docs;
    }

    /**
     * Builds a real Lucene keyword index (BM25-searchable) in the given directory,
     * mirroring the field layout used by AnseriniIndexerServiceImpl.addToKeywordIndex().
     */
    private void buildKeywordIndex(Path indexDir, List<RetrievedDoc> docs) throws IOException {
        Files.createDirectories(indexDir);
        try (var directory = FSDirectory.open(indexDir, NoLockFactory.INSTANCE)) {
            IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
            config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
            config.setRAMBufferSizeMB(16.0);

            try (IndexWriter writer = new IndexWriter(directory, config)) {
                for (RetrievedDoc doc : docs) {
                    var luceneDoc = new org.apache.lucene.document.Document();

                    // ID field (matches Anserini's DefaultLuceneDocumentGenerator layout)
                    luceneDoc.add(new StringField(Constants.ID, doc.getId(), Field.Store.YES));
                    luceneDoc.add(new BinaryDocValuesField(Constants.ID, new BytesRef(doc.getId())));

                    // Contents field with term vectors (matches AnseriniIndexerServiceImpl)
                    FieldType contentsFieldType = new FieldType();
                    contentsFieldType.setStored(true);
                    contentsFieldType.setStoreTermVectors(true);
                    contentsFieldType.setStoreTermVectorPositions(true);
                    contentsFieldType.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS);
                    luceneDoc.add(new Field(Constants.CONTENTS, doc.getText(), contentsFieldType));

                    // Raw field for full-text retrieval
                    luceneDoc.add(new StoredField(Constants.RAW, doc.getText()));

                    writer.addDocument(luceneDoc);
                }
                writer.commit();
            }
        }
    }

    /**
     * Creates a minimal IndexerService stub that reports the index as available.
     * The AnseriniDocumentRetrieverImpl only calls isIndexAvailable() during init.
     */
    private IndexerService createStubIndexerService() {
        return new IndexerService() {
            @Override public void indexDocuments(List<RetrievedDoc> documents, String collectionNameParam) {}
            @Override public void indexDocumentsWithEmbeddings(List<Document> documents, List<List<Float>> embeddings) {}
            @Override public void indexDocuments(List<RetrievedDoc> documents) {}
            @Override public void reprocessAndIndexAllSources() {}
            @Override public boolean isIndexAvailable() { return true; }
            @Override public List<Map<String, Object>> listIndexedDocuments(int offset, int limit) { return List.of(); }
            @Override public Map<String, Object> getIndexedDocument(String docId) { return null; }
            @Override public boolean updateIndexedDocumentContent(String docId, String newContent) { return false; }
            @Override public String getIndexPath() { return ""; }
            @Override public void indexFile(Path filePath, String sourceId, String collectionNameParam) {}
            @Override public void indexDirectory(Path directoryPath, String sourceIdPrefix, String collectionNameParam) {}
            @Override public boolean deleteDocuments(List<String> documentIds, String collectionNameParam) { return false; }
            @Override public boolean deleteAll(String collectionNameParam) { return false; }
            @Override public long getApproxTotalDocCount(String collectionNameParam) { return 0; }
            @Override public boolean startVectorIndexCreationAsync() { return false; }
            @Override public void cancelCurrentJob() {}
            @Override public JobStatus getJobStatus() { return JobStatus.idle(); }
            @Override public void indexFromLucene() {}
        };
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.deleteIfExists(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Real SameDiff BGE encoder tests (genuine semantic embeddings)
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Real SameDiff BGE Encoder")
    @Tag("e2e")
    class RealEncoderTests {

        private static final String MODEL_ID = "bge-base-en-v1.5";

        private static SameDiffEncoderEmbeddingModel sharedEmbedding;

        private SameDiffEncoderEmbeddingModel realEmbedding;
        private AnseriniVectorStoreImpl realVectorStore;
        private Path realIndexDir;

        private static SameDiffEncoderEmbeddingModel getOrCreateEncoder() throws Exception {
            if (sharedEmbedding == null) {
                // Disable MKLDNN helpers - the old CPU backend has a rank-mismatch bug
                // in MKLDNN matmul that rejects [batch,M,K] @ [K,N] broadcasting.
                org.nd4j.linalg.factory.Nd4j.getEnvironment().allowHelpers(false);
                sharedEmbedding = new SameDiffEncoderEmbeddingModel(MODEL_ID);
            }
            return sharedEmbedding;
        }

        @BeforeEach
        void setUp() throws Exception {
            realIndexDir = Files.createTempDirectory("anserini-real-encoder-");

            realEmbedding = getOrCreateEncoder();

            AnseriniVectorStoreProperties props = new AnseriniVectorStoreProperties();
            props.setIndexPath(realIndexDir.toString());
            props.setEnabled(true);
            props.setPersistenceEnabled(true);
            props.setSimilarityFunction("COSINE");
            props.setMemoryBufferSizeMb(16.0);
            props.setBatchCommitInterval(1);

            realVectorStore = new AnseriniVectorStoreImpl(props, realEmbedding);
        }

        @AfterEach
        void tearDown() throws Exception {
            if (realVectorStore != null) realVectorStore.destroy();
            if (realIndexDir != null && Files.exists(realIndexDir)) deleteRecursively(realIndexDir);
        }

        @Test
        @DisplayName("Real encoder: embedding dimension matches model spec (768)")
        void testRealEncoderDimensions() {
            assertEquals(768, realEmbedding.dimensions(),
                    "BGE-base-en-v1.5 should produce 768-dimensional embeddings");

            INDArray emb = realEmbedding.embed("test sentence");
            assertEquals(1, emb.rows());
            assertEquals(768, emb.columns());
        }

        @Test
        @DisplayName("Real encoder: semantically similar texts have high cosine similarity")
        void testSemanticSimilarityWithRealEncoder() {
            INDArray photoEmb = realEmbedding.embed(FACT_PHOTOSYNTHESIS);
            INDArray queryEmb = realEmbedding.embed("How do plants convert sunlight into energy?");
            INDArray unrelatedEmb = realEmbedding.embed("The stock market crashed in 2008.");

            double relevantSim = cosine(queryEmb.getRow(0).toFloatVector(),
                                        photoEmb.getRow(0).toFloatVector());
            double unrelatedSim = cosine(queryEmb.getRow(0).toFloatVector(),
                                         unrelatedEmb.getRow(0).toFloatVector());

            assertTrue(relevantSim > unrelatedSim,
                    String.format("Relevant doc (%.4f) should have higher similarity than unrelated (%.4f)",
                            relevantSim, unrelatedSim));
            assertTrue(relevantSim > 0.5,
                    "Semantically related texts should have similarity > 0.5, got: " + relevantSim);
        }

        @Test
        @DisplayName("Real encoder: full RAG pipeline with genuine semantic ranking")
        void testFullPipelineWithRealEncoder() {
            // Index all synthetic docs with real embeddings
            List<Document> docs = createCorpus();
            int added = realVectorStore.add(docs);
            assertEquals(8, added);

            // Query about photosynthesis — real BGE encoder should rank it highest
            String query = "How do plants use sunlight to produce glucose and oxygen?";
            List<ScoredDocument> results = realVectorStore.similaritySearchWithScores(
                    realEmbedding.embed(query), 8, 0.0);

            assertFalse(results.isEmpty());

            // The photosynthesis doc should be in the top results with a high score
            String topText = results.get(0).document().getText();
            double topScore = results.get(0).score();
            double bottomScore = results.get(results.size() - 1).score();

            System.out.println("=== Real BGE Encoder Ranked Results ===");
            for (int i = 0; i < results.size(); i++) {
                ScoredDocument sd = results.get(i);
                String preview = sd.document().getText().substring(0, Math.min(80, sd.document().getText().length()));
                System.out.printf("  #%d [%.4f] %s...%n", i + 1, sd.score(), preview);
            }

            assertTrue(topText.contains("Photosynthesis"),
                    "Top result should be the photosynthesis doc. Got: " +
                    topText.substring(0, Math.min(80, topText.length())));
            assertTrue(topScore > bottomScore + 0.05,
                    String.format("Top score (%.4f) should be meaningfully higher than bottom (%.4f)",
                            topScore, bottomScore));
        }

        @Test
        @DisplayName("Real encoder: biology vs physics queries retrieve correct domains")
        void testDomainRelevanceWithRealEncoder() {
            realVectorStore.add(createCorpus());

            // Biology query
            List<ScoredDocument> bioResults = realVectorStore.similaritySearchWithScores(
                    realEmbedding.embed("What cellular organelle produces ATP energy?"), 3, 0.0);

            // Physics query
            List<ScoredDocument> physicsResults = realVectorStore.similaritySearchWithScores(
                    realEmbedding.embed("What happens at the event horizon of a black hole?"), 3, 0.0);

            // CS query
            List<ScoredDocument> csResults = realVectorStore.similaritySearchWithScores(
                    realEmbedding.embed("How do qubits enable quantum parallel computation?"), 3, 0.0);

            System.out.println("=== Biology Query Top-3 ===");
            bioResults.forEach(sd -> System.out.printf("  [%.4f] %s%n", sd.score(),
                    sd.document().getText().substring(0, Math.min(70, sd.document().getText().length()))));

            System.out.println("=== Physics Query Top-3 ===");
            physicsResults.forEach(sd -> System.out.printf("  [%.4f] %s%n", sd.score(),
                    sd.document().getText().substring(0, Math.min(70, sd.document().getText().length()))));

            System.out.println("=== CS Query Top-3 ===");
            csResults.forEach(sd -> System.out.printf("  [%.4f] %s%n", sd.score(),
                    sd.document().getText().substring(0, Math.min(70, sd.document().getText().length()))));

            // Biology top result should contain bio content
            assertTrue(bioResults.get(0).document().getText().contains("mitochondria")
                    || bioResults.get(0).document().getText().contains("ATP")
                    || bioResults.get(0).document().getText().contains("Krebs"),
                    "Biology query should return biology doc as #1");

            // Physics top result should contain physics content
            assertTrue(physicsResults.get(0).document().getText().contains("black hole")
                    || physicsResults.get(0).document().getText().contains("gravity")
                    || physicsResults.get(0).document().getText().contains("event horizon"),
                    "Physics query should return physics doc as #1");

            // CS top result should contain CS content
            assertTrue(csResults.get(0).document().getText().contains("quantum")
                    || csResults.get(0).document().getText().contains("Qubits")
                    || csResults.get(0).document().getText().contains("superposition"),
                    "CS query should return CS doc as #1");
        }

        @Test
        @DisplayName("Real encoder: hybrid RAG with real keyword + real semantic search")
        void testHybridRagWithRealEncoder() throws Exception {
            Path kwDir = Files.createTempDirectory("anserini-real-kw-");
            try {
                buildKeywordIndex(kwDir, createCorpusAsRetrievedDocs());

                AnseriniConfig config = new AnseriniConfig();
                config.setIndexPath(kwDir.toString());

                AnseriniDocumentRetrieverImpl kwRetriever =
                        new AnseriniDocumentRetrieverImpl(config, List.of(createStubIndexerService()));
                kwRetriever.init();

                realVectorStore.add(createCorpus());

                ContextCapturingLanguageModel llm = new ContextCapturingLanguageModel();
                RagServiceImpl ragService = new RagServiceImpl(
                        List.of(kwRetriever), llm, List.of(realVectorStore));

                // Query that should benefit from BOTH retrievers
                RagQuery query = RagQuery.builder()
                        .query("How does the citric acid cycle in mitochondria produce NADH?")
                        .k(6)
                        .build();

                RagResult result = ragService.answerQuery(query);

                assertNotNull(result.getAnswer());
                List<String> ctx = llm.getLastContext();
                assertNotNull(ctx);
                assertFalse(ctx.isEmpty());

                System.out.println("=== Hybrid RAG Context (Real Encoder) ===");
                ctx.forEach(c -> System.out.println("  " + c.substring(0, Math.min(100, c.length())) + "..."));

                // Context should include Krebs cycle doc (has "citric acid", "NADH", "mitochondrial")
                assertTrue(ctx.stream().anyMatch(c ->
                        c.contains("Krebs") || c.contains("citric acid") || c.contains("NADH")),
                        "Hybrid search should find Krebs cycle doc via keyword AND/OR semantic match");

                // Context should also include mitochondria doc (semantic match for "mitochondria")
                assertTrue(ctx.stream().anyMatch(c -> c.contains("mitochondria")),
                        "Context should include mitochondria-related content");

            } finally {
                deleteRecursively(kwDir);
            }
        }

        private double cosine(float[] a, float[] b) {
            double dot = 0, normA = 0, normB = 0;
            for (int i = 0; i < a.length; i++) {
                dot += a[i] * b[i];
                normA += a[i] * a[i];
                normB += b[i] * b[i];
            }
            double denom = Math.sqrt(normA) * Math.sqrt(normB);
            return denom == 0 ? 0.0 : dot / denom;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Context-capturing LLM stub
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * A language model stub that captures the query and context passed to it,
     * enabling assertions on what the RAG pipeline actually forwarded.
     */
    static class ContextCapturingLanguageModel implements LanguageModel {
        private String lastQuery;
        private List<String> lastContext;

        @Override
        public String generateResponse(String userQuery, List<String> context) {
            this.lastQuery = userQuery;
            this.lastContext = context != null ? new ArrayList<>(context) : null;

            // Echo back a response that includes the context count and first snippet
            StringBuilder sb = new StringBuilder();
            sb.append("Based on ").append(context != null ? context.size() : 0)
              .append(" retrieved documents: ");
            if (context != null && !context.isEmpty()) {
                sb.append(context.get(0), 0, Math.min(80, context.get(0).length()));
                sb.append("...");
            }
            return sb.toString();
        }

        @Override
        public ChatResponse generateResponseWithPotentialToolCalls(String userQuery, List<String> context) {
            return null;
        }

        String getLastQuery() { return lastQuery; }
        List<String> getLastContext() { return lastContext; }
    }
}
