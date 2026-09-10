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
        List<Topic> topics,
        List<TopicBinding> bindings) {

    private static final int MAX_PROMPT_TOPICS = 4;
    private static final int MAX_PROMPT_REPRESENTATIVES = 2;
    private static final int MAX_PROMPT_LANGUAGES = 2;
    private static final int MAX_PROMPT_TERMS = 4;
    private static final int MAX_PROMPT_TEXT_CHARS = 64;
    private static final int MAX_PROMPT_EVIDENCE_CHARS = 80;

    CorpusTopicEvidence {
        embeddingModelId = embeddingModelId == null ? "unknown" : embeddingModelId;
        topics = topics == null ? List.of() : List.copyOf(topics);
        bindings = bindings == null ? List.of() : List.copyOf(bindings);
    }

    CorpusTopicEvidence(
            String embeddingModelId,
            int embeddingDimension,
            double modularity,
            int outlierCount,
            List<Topic> topics) {
        this(embeddingModelId, embeddingDimension, modularity, outlierCount, topics, List.of());
    }

    static CorpusTopicEvidence empty() {
        return new CorpusTopicEvidence("unavailable", 0, 0.0, 0, List.of(), List.of());
    }

    boolean isEmpty() {
        return topics.isEmpty();
    }

    /** A bounded representation suitable for an LLM prompt. */
    Map<String, Object> promptView() {
        List<Map<String, Object>> views = promptViews();
        return views.isEmpty() ? metadataView(List.of(), List.of()) : views.get(0);
    }

    /** Bounded topic batches; every discovered topic appears in exactly one binding prompt. */
    List<Map<String, Object>> promptViews() {
        if (topics.isEmpty()) return List.of();
        List<Map<String, Object>> views = new ArrayList<>();
        for (int start = 0; start < topics.size(); start += MAX_PROMPT_TOPICS) {
            int end = Math.min(topics.size(), start + MAX_PROMPT_TOPICS);
            List<Topic> batch = topics.subList(start, end);
            java.util.Set<String> topicIds = batch.stream().map(Topic::topicId)
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            List<TopicBinding> batchBindings = bindings.stream()
                    .filter(binding -> topicIds.contains(binding.topicId())).toList();
            views.add(metadataView(batch, batchBindings));
        }
        return List.copyOf(views);
    }

    CorpusTopicEvidence withBindings(List<TopicBinding> acceptedBindings) {
        return new CorpusTopicEvidence(
                embeddingModelId, embeddingDimension, modularity, outlierCount,
                topics, acceptedBindings);
    }

    CorpusTopicEvidence topicBatch(int startInclusive, int endExclusive) {
        return new CorpusTopicEvidence(
                embeddingModelId, embeddingDimension, modularity, outlierCount,
                topics.subList(startInclusive, endExclusive), List.of());
    }

    /** Exact evidence strings exposed to one topic-binding prompt. */
    static List<String> promptGroundingTexts(
            Topic topic, Map<String, String> passageTexts) {
        return List.copyOf(promptGroundingEvidence(topic, passageTexts).values());
    }

    /** Stable ids let the model select evidence without regenerating free-form text. */
    static Map<String, String> promptGroundingEvidence(
            Topic topic, Map<String, String> passageTexts) {
        if (topic == null) return Map.of();
        java.util.LinkedHashSet<String> visible = new java.util.LinkedHashSet<>();
        topic.termsByLanguage().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .limit(MAX_PROMPT_LANGUAGES)
                .flatMap(entry -> entry.getValue().stream().limit(MAX_PROMPT_TERMS))
                .filter(value -> value != null && !value.isBlank())
                .map(CorpusTopicEvidence::boundedPromptEvidence)
                .forEach(visible::add);
        Map<String, String> texts = passageTexts == null ? Map.of() : passageTexts;
        topic.representativeChunkIds().stream()
                .limit(MAX_PROMPT_REPRESENTATIVES)
                .map(texts::get)
                .filter(value -> value != null && !value.isBlank())
                .map(CorpusTopicEvidence::boundedPromptEvidence)
                .forEach(visible::add);
        Map<String, String> indexed = new LinkedHashMap<>();
        int index = 1;
        for (String value : visible) indexed.put("E" + index++, value);
        return java.util.Collections.unmodifiableMap(indexed);
    }

    private static String boundedPromptEvidence(String value) {
        String normalized = value.trim();
        return normalized.length() <= MAX_PROMPT_EVIDENCE_CHARS
                ? normalized : normalized.substring(0, MAX_PROMPT_EVIDENCE_CHARS);
    }

    private Map<String, Object> metadataView(
            List<Topic> selectedTopics, List<TopicBinding> selectedBindings) {
        List<Map<String, Object>> topicViews = new ArrayList<>();
        for (Topic topic : selectedTopics) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("topicId", bounded(topic.topicId()));
            view.put("documentCount", topic.memberDocumentIds().size());
            view.put("passageCount", topic.memberChunkIds().size());
            view.put("representativeChunkIds", topic.representativeChunkIds().stream()
                    .limit(MAX_PROMPT_REPRESENTATIVES).map(CorpusTopicEvidence::bounded).toList());
            view.put("languageDistribution", boundedLanguages(topic.languageDistribution()));
            view.put("termsByLanguage", boundedTerms(topic.termsByLanguage()));
            topicViews.add(view);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("embeddingModelId", bounded(embeddingModelId));
        result.put("embeddingDimension", embeddingDimension);
        result.put("modularity", modularity);
        result.put("outlierCount", outlierCount);
        result.put("topics", topicViews);
        result.put("bindings", selectedBindings.stream()
                .map(CorpusTopicEvidence::bindingView).toList());
        return result;
    }

    /** Full evidence retained in graph metadata and traces; unlike promptView(), no languages are omitted. */
    Map<String, Object> persistenceView() {
        List<Map<String, Object>> topicViews = new ArrayList<>();
        for (Topic topic : topics) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("topicId", topic.topicId());
            view.put("memberDocumentIds", List.copyOf(topic.memberDocumentIds()));
            view.put("memberChunkIds", List.copyOf(topic.memberChunkIds()));
            view.put("representativeChunkIds", List.copyOf(topic.representativeChunkIds()));
            view.put("languageDistribution", new LinkedHashMap<>(topic.languageDistribution()));
            Map<String, List<String>> terms = new LinkedHashMap<>();
            topic.termsByLanguage().forEach((language, values) ->
                    terms.put(language, values == null ? List.of() : List.copyOf(values)));
            view.put("termsByLanguage", terms);
            topicViews.add(view);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("embeddingModelId", embeddingModelId);
        result.put("embeddingDimension", embeddingDimension);
        result.put("modularity", modularity);
        result.put("outlierCount", outlierCount);
        result.put("topics", topicViews);
        result.put("bindings", bindings.stream()
                .map(CorpusTopicEvidence::bindingView).toList());
        return result;
    }

    private static Map<String, Object> bindingView(TopicBinding binding) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("topicId", binding.topicId());
        view.put("nodeTypes", binding.nodeTypes().stream().map(node -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("label", node.label());
            value.put("parentType", node.parentType());
            value.put("groundingPhrase", node.groundingPhrase());
            return Map.copyOf(value);
        }).toList());
        view.put("relationshipTypes", binding.relationshipTypes().stream().map(relation -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("type", relation.type());
            value.put("connectionFamily", relation.connectionFamily());
            value.put("sourceType", relation.sourceType());
            value.put("targetType", relation.targetType());
            value.put("groundingPhrase", relation.groundingPhrase());
            return Map.copyOf(value);
        }).toList());
        return view;
    }

    private static Map<String, Integer> boundedLanguages(Map<String, Integer> values) {
        Map<String, Integer> bounded = new LinkedHashMap<>();
        values.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .limit(MAX_PROMPT_LANGUAGES)
                .forEach(entry -> bounded.put(bounded(entry.getKey()), entry.getValue()));
        return bounded;
    }

    private static Map<String, List<String>> boundedTerms(Map<String, List<String>> values) {
        Map<String, List<String>> bounded = new LinkedHashMap<>();
        values.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .limit(MAX_PROMPT_LANGUAGES).forEach(entry ->
                bounded.put(bounded(entry.getKey()), entry.getValue().stream()
                        .limit(MAX_PROMPT_TERMS).map(CorpusTopicEvidence::bounded).toList()));
        return bounded;
    }

    private static String bounded(String value) {
        if (value == null) return "";
        return value.length() <= MAX_PROMPT_TEXT_CHARS
                ? value : value.substring(0, MAX_PROMPT_TEXT_CHARS);
    }

    record Topic(
            String topicId,
            List<String> memberDocumentIds,
            List<String> memberChunkIds,
            List<String> representativeChunkIds,
            Map<String, Integer> languageDistribution,
            Map<String, List<String>> termsByLanguage) {

        Topic {
            memberDocumentIds = memberDocumentIds == null
                    ? List.of() : List.copyOf(memberDocumentIds);
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

        Topic(
                String topicId,
                List<String> memberChunkIds,
                List<String> representativeChunkIds,
                Map<String, Integer> languageDistribution,
                Map<String, List<String>> termsByLanguage) {
            this(topicId, memberChunkIds, memberChunkIds, representativeChunkIds,
                    languageDistribution, termsByLanguage);
        }
    }

    record TopicBinding(
            String topicId,
            List<NodeBinding> nodeTypes,
            List<RelationshipBinding> relationshipTypes) {
        TopicBinding {
            nodeTypes = nodeTypes == null ? List.of() : List.copyOf(nodeTypes);
            relationshipTypes = relationshipTypes == null
                    ? List.of() : List.copyOf(relationshipTypes);
        }
    }

    record NodeBinding(String label, String parentType, String groundingPhrase) {
        NodeBinding(String label, String parentType) {
            this(label, parentType, "");
        }
    }

    record RelationshipBinding(
            String type,
            String connectionFamily,
            String sourceType,
            String targetType,
            String groundingPhrase) {
        RelationshipBinding(
                String type, String connectionFamily, String sourceType, String targetType) {
            this(type, connectionFamily, sourceType, targetType, "");
        }
    }
}
