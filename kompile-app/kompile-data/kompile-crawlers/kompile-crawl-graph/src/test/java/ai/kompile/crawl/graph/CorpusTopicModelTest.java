/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.crawl.graph;

import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.crawl.graph.CrawlIndexTrackingCallback.CrawlCorpusPassage;
import org.junit.jupiter.api.Test;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorpusTopicModelTest {

    @Test
    void buildsDeterministicMultilingualTopicsFromManagedEmbeddings() {
        List<CrawlCorpusPassage> passages = List.of(
                passage("en-1", 0, "astronomy telescope galaxy orbit", "en"),
                passage("es-1", 1, "astronomía telescopio galaxia órbita", "es"),
                passage("en-2", 2, "astronomy observatory telescope stars", "en"),
                passage("de-1", 3, "wald lebensraum arten ökologie", "de"),
                passage("fr-1", 4, "forêt habitat espèces écologie", "fr"),
                passage("de-2", 5, "wald arten lebensraum natur", "de"));
        float[][] embeddings = {
                {1.0f, 0.0f}, {0.99f, 0.08f}, {0.98f, -0.08f},
                {0.0f, 1.0f}, {0.08f, 0.99f}, {-0.08f, 0.98f}
        };

        CorpusTopicEvidence evidence = CorpusTopicModel.analyzeEmbeddings(
                passages, embeddings, List.of("en", "es", "en", "de", "fr", "de"),
                "multilingual-e5-small");

        assertEquals("multilingual-e5-small", evidence.embeddingModelId());
        assertEquals(2, evidence.embeddingDimension());
        assertEquals(2, evidence.topics().size());
        assertEquals(0, evidence.outlierCount());
        assertTrue(evidence.modularity() > 0.0);

        CorpusTopicEvidence.Topic first = evidence.topics().get(0);
        assertEquals(List.of("en-1", "es-1", "en-2"), first.memberChunkIds());
        assertEquals(Map.of("en", 2, "es", 1), first.languageDistribution());
        assertTrue(first.termsByLanguage().get("en").contains("astronomy"));
        assertTrue(first.termsByLanguage().get("es").contains("astronomía"));
        assertTrue(first.representativeChunkIds().size() >= 2,
                "representatives should cover multiple languages when available");

        CorpusTopicEvidence.Topic second = evidence.topics().get(1);
        assertEquals(List.of("de-1", "fr-1", "de-2"), second.memberChunkIds());
        assertTrue(second.termsByLanguage().get("de").contains("wald"));
        assertTrue(second.termsByLanguage().get("fr").contains("forêt"));
    }

    @Test
    void disconnectedSingletonsRemainOutliers() {
        List<CrawlCorpusPassage> passages = List.of(
                passage("a", 0, "alpha", "en"),
                passage("b", 1, "beta", "en"));

        CorpusTopicEvidence evidence = CorpusTopicModel.analyzeEmbeddings(
                passages, new float[][]{{1, 0}, {0, 1}}, List.of("en", "en"), "model");

        assertTrue(evidence.topics().isEmpty());
        assertEquals(2, evidence.outlierCount());
    }

    @Test
    void smallCorpusKnnRemainsSparseWhenAllCosineSimilaritiesArePositive() {
        List<CrawlCorpusPassage> passages = List.of(
                passage("space-en", 0, "astronomy telescope galaxy", "en"),
                passage("space-es", 1, "astronomía telescopio galaxia", "es"),
                passage("space-fr", 2, "astronomie télescope galaxie", "fr"),
                passage("eco-en", 3, "ecology forest habitat", "en"),
                passage("eco-es", 4, "ecología bosque hábitat", "es"),
                passage("eco-de", 5, "ökologie wald lebensraum", "de"));
        // The shared third dimension models the positive multilingual-embedding baseline.
        // Within-domain neighbours are strongest, but every cross-domain pair still clears
        // MIN_SIMILARITY. A complete kNN graph collapses this corpus into one topic.
        float[][] embeddings = {
                {1.00f, 0.02f, 1.00f}, {0.98f, 0.04f, 1.00f}, {1.02f, 0.01f, 1.00f},
                {0.02f, 1.00f, 1.00f}, {0.04f, 0.98f, 1.00f}, {0.01f, 1.02f, 1.00f}
        };

        CorpusTopicEvidence evidence = CorpusTopicModel.analyzeEmbeddings(
                passages, embeddings, List.of("en", "es", "fr", "en", "es", "de"),
                "multilingual-e5-small");

        assertEquals(2, evidence.topics().size());
        assertEquals(List.of("space-en", "space-es", "space-fr"),
                evidence.topics().get(0).memberChunkIds());
        assertEquals(List.of("eco-en", "eco-es", "eco-de"),
                evidence.topics().get(1).memberChunkIds());
    }

    @Test
    void clustersSourceDocumentsOnceWhileRetainingAllChunkEvidence() {
        List<CrawlCorpusPassage> passages = List.of(
                documentPassage("space-a-boilerplate", 0, "generic boilerplate", "space-a", "en"),
                documentPassage("space-a-1", 1, "astronomy telescope", "space-a", "en"),
                documentPassage("space-a-2", 2, "galaxy orbit", "space-a", "en"),
                documentPassage("space-b-1", 0, "astronomía telescopio", "space-b", "es"),
                documentPassage("space-b-2", 1, "galaxia órbita", "space-b", "es"),
                documentPassage("eco-a-1", 0, "ecology forest", "eco-a", "en"),
                documentPassage("eco-a-2", 1, "habitat species", "eco-a", "en"),
                documentPassage("eco-b-1", 0, "ökologie wald", "eco-b", "de"),
                documentPassage("eco-b-2", 1, "lebensraum arten", "eco-b", "de"));

        CorpusTopicEvidence evidence = new CorpusTopicModel(
                fixedDocumentEmbeddings(), text -> "en").analyze(passages, null);

        assertEquals(2, evidence.topics().size());
        assertEquals(List.of("space-a", "space-b"),
                evidence.topics().get(0).memberDocumentIds());
        assertEquals(List.of("space-a-boilerplate", "space-a-1", "space-a-2",
                        "space-b-1", "space-b-2"),
                evidence.topics().get(0).memberChunkIds());
        assertFalse(evidence.topics().get(0).representativeChunkIds()
                .contains("space-a-boilerplate"));
        assertEquals(List.of("eco-a", "eco-b"),
                evidence.topics().get(1).memberDocumentIds());
        assertEquals(0, evidence.outlierCount(),
                "outliers count source documents, not chunks");
    }

    @Test
    void headlessTopicAnalysisClosesItsEmbeddingLeaseBeforeReturning() {
        AtomicBoolean closed = new AtomicBoolean();
        EmbeddingModel embeddings = new EmbeddingModel() {
            @Override
            public INDArray embed(String text) {
                return Nd4j.create(vector(text));
            }

            @Override
            public INDArray embed(List<String> texts) {
                return Nd4j.create(texts.stream().map(this::vector).toArray(float[][]::new));
            }

            @Override
            public INDArray embedDocuments(List<Document> documents) {
                return embed(documents.stream().map(Document::getText).toList());
            }

            @Override
            public List<float[]> embedBatch(List<String> texts) {
                return texts.stream().map(this::vector).toList();
            }

            @Override
            public int dimensions() {
                return 2;
            }

            @Override
            public String getModelIdentifier() {
                return "multilingual-e5-small";
            }

            @Override
            public void close() {
                closed.set(true);
            }

            private float[] vector(String text) {
                return text.contains("astronom") || text.contains("telescope")
                        ? new float[]{1.0f, 0.02f}
                        : new float[]{0.02f, 1.0f};
            }
        };
        List<CrawlCorpusPassage> passages = List.of(
                documentPassage("space-1", 0, "astronomy telescope", "space-1-doc", "en"),
                documentPassage("space-2", 0, "astronomy orbit", "space-2-doc", "en"),
                documentPassage("eco-1", 0, "ecology forest", "eco-1-doc", "en"),
                documentPassage("eco-2", 0, "ecology habitat", "eco-2-doc", "en"));

        CorpusTopicEvidence evidence = new CorpusTopicModel(embeddings, text -> "en")
                .analyzeAndReleaseRuntimeModel(passages, null);

        assertFalse(evidence.topics().isEmpty());
        assertTrue(closed.get(),
                "headless embedding lease must be closed before schema LLM binding starts");
    }

    @Test
    void largeCorpusSamplingIsDeterministicAndCoversBothEnds() {
        List<CrawlCorpusPassage> passages = java.util.stream.IntStream.range(0, 10)
                .mapToObj(index -> passage("p-" + index, index, "text " + index, "en"))
                .toList();

        List<CrawlCorpusPassage> bounded = CorpusTopicModel.boundedPassages(passages, 4);

        assertEquals(List.of("p-0", "p-3", "p-6", "p-9"),
                bounded.stream().map(CrawlCorpusPassage::chunkId).toList());
    }

    private static CrawlCorpusPassage passage(
            String id, int index, String text, String language) {
        return new CrawlCorpusPassage(
                id, index, text, "hash-" + id, Map.of("language", language), true);
    }

    private static CrawlCorpusPassage documentPassage(
            String id, int index, String text, String documentId, String language) {
        return new CrawlCorpusPassage(id, index, text, "hash-" + id,
                Map.of("language", language,
                        "original_document_id", documentId), true);
    }

    private static EmbeddingModel fixedDocumentEmbeddings() {
        return new EmbeddingModel() {
            @Override
            public INDArray embed(String text) {
                return Nd4j.create(vector(text));
            }

            @Override
            public INDArray embed(List<String> texts) {
                return Nd4j.create(texts.stream().map(this::vector).toArray(float[][]::new));
            }

            @Override
            public INDArray embedDocuments(List<Document> documents) {
                return embed(documents.stream().map(Document::getText).toList());
            }

            @Override
            public List<float[]> embedBatch(List<String> texts) {
                return texts.stream().map(this::vector).toList();
            }

            @Override
            public int dimensions() {
                return 2;
            }

            @Override
            public String getModelIdentifier() {
                return "multilingual-e5-small";
            }

            private float[] vector(String text) {
                String lower = text.toLowerCase();
                if (lower.contains("boilerplate")) return new float[]{0.7f, 0.7f};
                boolean astronomy = lower.contains("astronom") || lower.contains("telescope")
                        || lower.contains("galaxy") || lower.contains("galaxia")
                        || lower.contains("orbit") || lower.contains("órbita");
                return astronomy ? new float[]{1.0f, 0.02f} : new float[]{0.02f, 1.0f};
            }
        };
    }
}
