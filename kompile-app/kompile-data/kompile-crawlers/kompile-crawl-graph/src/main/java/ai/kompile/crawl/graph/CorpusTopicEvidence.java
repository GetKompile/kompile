/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.crawl.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable, corpus-grounded topic evidence used to constrain schema induction.
 * Topic identifiers and terms are evidence only; they are never promoted directly to schema types.
 */
record CorpusTopicEvidence(
        String embeddingModelId,
        int embeddingDimension,
        double modularity,
        int outlierCount,
        List<Topic> topics) {

    CorpusTopicEvidence {
        embeddingModelId = embeddingModelId == null ? "unknown" : embeddingModelId;
        topics = topics == null ? List.of() : List.copyOf(topics);
    }

    static CorpusTopicEvidence empty() {
        return new CorpusTopicEvidence("unavailable", 0, 0.0, 0, List.of());
    }

    boolean isEmpty() {
        return topics.isEmpty();
    }

    /** A bounded representation suitable for an LLM prompt. */
    Map<String, Object> promptView() {
        List<Map<String, Object>> topicViews = new ArrayList<>();
        for (Topic topic : topics.stream().limit(12).toList()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("topicId", topic.topicId());
            view.put("passageCount", topic.memberChunkIds().size());
            view.put("representativeChunkIds", topic.representativeChunkIds());
            view.put("languageDistribution", topic.languageDistribution());
            view.put("termsByLanguage", topic.termsByLanguage());
            topicViews.add(view);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("embeddingModelId", embeddingModelId);
        result.put("embeddingDimension", embeddingDimension);
        result.put("modularity", modularity);
        result.put("outlierCount", outlierCount);
        result.put("topics", topicViews);
        return result;
    }

    record Topic(
            String topicId,
            List<String> memberChunkIds,
            List<String> representativeChunkIds,
            Map<String, Integer> languageDistribution,
            Map<String, List<String>> termsByLanguage) {

        Topic {
            memberChunkIds = memberChunkIds == null ? List.of() : List.copyOf(memberChunkIds);
            representativeChunkIds = representativeChunkIds == null
                    ? List.of() : List.copyOf(representativeChunkIds);
            languageDistribution = languageDistribution == null
                    ? Map.of() : Map.copyOf(new LinkedHashMap<>(languageDistribution));
            if (termsByLanguage == null) {
                termsByLanguage = Map.of();
            } else {
                Map<String, List<String>> copied = new LinkedHashMap<>();
                termsByLanguage.forEach((language, terms) ->
                        copied.put(language, terms == null ? List.of() : List.copyOf(terms)));
                termsByLanguage = Map.copyOf(copied);
            }
        }
    }
}
