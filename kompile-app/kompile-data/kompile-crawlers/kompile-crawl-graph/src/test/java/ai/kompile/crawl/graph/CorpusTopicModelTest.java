/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.crawl.graph;

import ai.kompile.crawl.graph.CrawlIndexTrackingCallback.CrawlCorpusPassage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorpusTopicModelTest {

    @Test
    void buildsDeterministicMultilingualTopicsFromManagedEmbeddings() {
        List<CrawlCorpusPassage> passages = List.of(
                passage("en-1", 0, "revenue forecast growth margin", "en"),
                passage("es-1", 1, "ingresos previsión crecimiento margen", "es"),
                passage("en-2", 2, "revenue planning forecast outlook", "en"),
                passage("de-1", 3, "rechnung zahlung lieferant kosten", "de"),
                passage("fr-1", 4, "facture paiement fournisseur coûts", "fr"),
                passage("de-2", 5, "rechnung kosten zahlung budget", "de"));
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
        assertTrue(first.termsByLanguage().get("en").contains("revenue"));
        assertTrue(first.termsByLanguage().get("es").contains("ingresos"));
        assertTrue(first.representativeChunkIds().size() >= 2,
                "representatives should cover multiple languages when available");

        CorpusTopicEvidence.Topic second = evidence.topics().get(1);
        assertEquals(List.of("de-1", "fr-1", "de-2"), second.memberChunkIds());
        assertTrue(second.termsByLanguage().get("de").contains("rechnung"));
        assertTrue(second.termsByLanguage().get("fr").contains("facture"));
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

    private static CrawlCorpusPassage passage(
            String id, int index, String text, String language) {
        return new CrawlCorpusPassage(
                id, index, text, "hash-" + id, Map.of("language", language), true);
    }
}
