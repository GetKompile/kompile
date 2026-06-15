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

import ai.kompile.core.llm.chat.LLMChat;
import ai.kompile.graph.algorithms.LouvainCommunityDetection;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Generates natural-language summaries for graph communities, mirroring Microsoft GraphRAG's
 * hierarchical community summary pattern. Falls back to a deterministic placeholder summary
 * if no {@link LLMChat} bean is available, so the algorithm endpoints stay usable in
 * LLM-less deployments.
 */
@Service
public class CommunitySummarizer {

    private static final Logger log = LoggerFactory.getLogger(CommunitySummarizer.class);
    private static final int DEFAULT_MAX_NODES_PER_PROMPT = 25;

    private final LLMChat llmChat;

    @Autowired
    public CommunitySummarizer(@Autowired(required = false) LLMChat llmChat) {
        this.llmChat = llmChat;
    }

    public List<CommunitySummary> summarize(Map<String, Integer> assignments,
                                            KnowledgeGraphService kgs) {
        return summarize(assignments, kgs, DEFAULT_MAX_NODES_PER_PROMPT);
    }

    public List<CommunitySummary> summarize(Map<String, Integer> assignments,
                                            KnowledgeGraphService kgs,
                                            int maxNodesPerPrompt) {
        Map<Integer, List<String>> grouped = LouvainCommunityDetection.groupByCommunity(assignments);
        List<CommunitySummary> summaries = new ArrayList<>(grouped.size());

        for (Map.Entry<Integer, List<String>> entry : grouped.entrySet()) {
            int communityId = entry.getKey();
            List<String> members = entry.getValue();
            List<String> sample = members.size() > maxNodesPerPrompt
                    ? members.subList(0, maxNodesPerPrompt)
                    : members;

            String summary = generateSummary(communityId, members.size(), sample, kgs);
            summaries.add(new CommunitySummary(communityId, members, summary, Instant.now()));
        }
        return summaries;
    }

    private String generateSummary(int communityId, int totalSize, List<String> sample,
                                    KnowledgeGraphService kgs) {
        StringBuilder context = new StringBuilder();
        context.append("Community ").append(communityId)
                .append(" (").append(totalSize).append(" members):\n");
        for (String nodeId : sample) {
            Optional<GraphNode> node = kgs.getNode(nodeId);
            if (node.isEmpty()) continue;
            GraphNode n = node.get();
            context.append("- ").append(safe(n.getTitle()));
            if (n.getDescription() != null && !n.getDescription().isBlank()) {
                context.append(": ").append(safe(n.getDescription()));
            }
            context.append("\n");
        }

        if (llmChat == null) {
            return "LLM unavailable. " + totalSize + " nodes in community " + communityId + ".";
        }

        try {
            String prompt = "You are summarizing a community of related entities in a knowledge graph. "
                    + "Provide a concise (2-3 sentence) summary describing the main theme, key entities, "
                    + "and how they relate.\n\n" + context;
            String response = llmChat.prompt(prompt).call().content();
            if (response == null || response.isBlank()) {
                return "Community " + communityId + " (" + totalSize + " nodes); LLM returned empty response.";
            }
            return response.trim();
        } catch (Exception e) {
            log.warn("LLM summary failed for community {}: {}", communityId, e.getMessage());
            return "Community " + communityId + " (" + totalSize + " nodes); LLM error: " + e.getMessage();
        }
    }

    private static String safe(String s) {
        return s == null ? "(no title)" : s.replace('\n', ' ').replace('\r', ' ');
    }
}
