/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.algorithms.community;

import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommunitySummarizerTest {

    @Test
    void fallbackSummaryWhenLlmUnavailable() {
        KnowledgeGraphService kgs = Mockito.mock(KnowledgeGraphService.class);
        Mockito.when(kgs.getNode(Mockito.anyString())).thenReturn(Optional.empty());

        CommunitySummarizer summarizer = new CommunitySummarizer(null);

        Map<String, Integer> assignments = new LinkedHashMap<>();
        assignments.put("n1", 0);
        assignments.put("n2", 0);
        assignments.put("n3", 1);

        List<CommunitySummary> summaries = summarizer.summarize(assignments, kgs);
        assertEquals(2, summaries.size());
        for (CommunitySummary s : summaries) {
            assertNotNull(s.summary());
            assertTrue(s.summary().contains("LLM unavailable"),
                    "Expected fallback message, got: " + s.summary());
        }
    }

    @Test
    void preservesCommunityIdAndMembership() {
        KnowledgeGraphService kgs = Mockito.mock(KnowledgeGraphService.class);
        Mockito.when(kgs.getNode(Mockito.anyString())).thenReturn(Optional.empty());

        CommunitySummarizer summarizer = new CommunitySummarizer(null);

        Map<String, Integer> assignments = new LinkedHashMap<>();
        assignments.put("a", 7);
        assignments.put("b", 7);
        assignments.put("c", 9);

        List<CommunitySummary> summaries = summarizer.summarize(assignments, kgs);
        for (CommunitySummary s : summaries) {
            if (s.communityId() == 7) {
                assertEquals(2, s.nodeIds().size());
                assertTrue(s.nodeIds().containsAll(List.of("a", "b")));
            } else if (s.communityId() == 9) {
                assertEquals(List.of("c"), s.nodeIds());
            }
        }
    }

    @Test
    void honorsMaxNodesPerPromptForLargeCommunities() {
        KnowledgeGraphService kgs = Mockito.mock(KnowledgeGraphService.class);
        GraphNode dummy = Mockito.mock(GraphNode.class);
        Mockito.when(dummy.getTitle()).thenReturn("Title");
        Mockito.when(dummy.getDescription()).thenReturn("desc");
        Mockito.when(kgs.getNode(Mockito.anyString())).thenReturn(Optional.of(dummy));

        Map<String, Integer> assignments = new LinkedHashMap<>();
        for (int i = 0; i < 50; i++) assignments.put("n" + i, 0);

        CommunitySummarizer summarizer = new CommunitySummarizer(null);
        List<CommunitySummary> summaries = summarizer.summarize(assignments, kgs, 5);
        assertEquals(1, summaries.size());
        // All 50 members are recorded even though only 5 are sampled for the prompt.
        assertEquals(50, summaries.get(0).nodeIds().size());
    }
}
