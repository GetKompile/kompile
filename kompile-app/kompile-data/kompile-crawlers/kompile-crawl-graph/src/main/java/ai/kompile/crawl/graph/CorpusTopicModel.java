/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.crawl.graph;

import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.language.LanguageMetadata;
import ai.kompile.core.language.LanguageSupport;
import ai.kompile.crawl.graph.CrawlIndexTrackingCallback.CrawlCorpusPassage;
import ai.kompile.crawler.CrawlLanguageDetector;
import ai.kompile.graph.algorithms.LouvainCommunityDetection;
import ai.kompile.graph.algorithms.adjacency.AdjacencyView;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.NDArrayIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.text.BreakIterator;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Automatic BERTopic-style corpus analysis over Kompile-managed embeddings and graph algorithms.
 */
@Component
class CorpusTopicModel {

    static final String REQUIRED_MODEL_ID = "multilingual-e5-small";
    private static final Logger log = LoggerFactory.getLogger(CorpusTopicModel.class);
    private static final int K_NEIGHBORS = 8;
    private static final int SIMILARITY_BLOCK_ROWS = 256;
    private static final int MIN_TOPIC_SIZE = 2;
    private static final int MAX_REPRESENTATIVES = 3;
    private static final int MAX_TERMS_PER_LANGUAGE = 12;
    private static final double MIN_SIMILARITY = 0.35;
    private static final double EPSILON = 1e-12;

    @Autowired(required = false)
    private VectorIndexingHelper vectorIndexingHelper;

    @Autowired(required = false)
    private ObjectProvider<CrawlLanguageDetector> languageDetectors;

    CorpusTopicEvidence analyze(List<CrawlCorpusPassage> passages, UnifiedCrawlJob job) {
        if (passages == null || passages.size() < MIN_TOPIC_SIZE) {
            return CorpusTopicEvidence.empty();
        }
        if (vectorIndexingHelper == null) {
            throw new IllegalStateException("Corpus topic analysis requires the managed embedding lane");
        }

        EmbeddingModel model = vectorIndexingHelper.embeddingModel(REQUIRED_MODEL_ID);
        if (model == null) {
            log.warn("Corpus topic evidence unavailable: managed model '{}' is not registered as an embedding bean",
                    REQUIRED_MODEL_ID);
            return CorpusTopicEvidence.empty();
        }
        String modelId = model.getModelIdentifier();
        try {
            if (!vectorIndexingHelper.isEmbeddingModelReady(model)) {
                log.warn("Corpus topic evidence unavailable: managed model '{}' is not ready: {}",
                        REQUIRED_MODEL_ID, vectorIndexingHelper.embeddingModelNotReadyReason(model));
                return CorpusTopicEvidence.empty();
            }
        } catch (RuntimeException startupFailure) {
            log.warn("Corpus topic evidence unavailable: managed model '{}' failed readiness: {}",
                    REQUIRED_MODEL_ID, startupFailure.getMessage());
            return CorpusTopicEvidence.empty();
        }

        vectorIndexingHelper.setActiveCrawlJobId(job == null ? null : job.getJobId());
        try {
            List<String> texts = passages.stream().map(CrawlCorpusPassage::content).toList();
            float[][] embeddings = embedInBatches(model, texts);
            List<String> languages = passages.stream().map(this::language).toList();
            return analyzeEmbeddings(passages, embeddings, languages, modelId);
        } finally {
            vectorIndexingHelper.setActiveCrawlJobId(null);
        }
    }

    private static float[][] embedInBatches(EmbeddingModel model, List<String> texts) {
        int optimal = Math.max(1, model.getOptimalBatchSize());
        int maximum = Math.max(optimal, model.getMaxBatchSize());
        int batchSize = Math.min(optimal, maximum);
        float[][] result = new float[texts.size()][];
        for (int start = 0; start < texts.size(); start += batchSize) {
            int end = Math.min(texts.size(), start + batchSize);
            List<float[]> batch = model.embedBatch(texts.subList(start, end));
            if (batch == null || batch.size() != end - start) {
                throw new IllegalStateException("Managed embedding model returned "
                        + (batch == null ? -1 : batch.size()) + " vectors for " + (end - start)
                        + " corpus passages");
            }
            for (int i = 0; i < batch.size(); i++) {
                float[] vector = batch.get(i);
                if (!indexable(vector)) {
                    throw new IllegalStateException("Managed embedding model returned a non-finite or empty vector");
                }
                result[start + i] = vector;
            }
        }
        return result;
    }

    private String language(CrawlCorpusPassage passage) {
        String language = LanguageMetadata.canonicalLanguage(passage.metadata());
        if (language != null && !LanguageSupport.UNDETERMINED_LANGUAGE.equals(language)) {
            return language;
        }
        CrawlLanguageDetector detector = languageDetectors == null ? null : languageDetectors.getIfAvailable();
        if (detector != null) {
            language = detector.detectLanguage(passage.content());
        }
        return language == null ? LanguageSupport.UNDETERMINED_LANGUAGE : language;
    }

    static CorpusTopicEvidence analyzeEmbeddings(
            List<CrawlCorpusPassage> passages,
            float[][] embeddings,
            List<String> languages,
            String modelId) {
        validateInputs(passages, embeddings, languages);
        int count = passages.size();
        int dimension = embeddings[0].length;
        INDArray normalized = null;
        INDArray rawNorms = null;
        INDArray norms = null;
        try {
            normalized = Nd4j.create(embeddings);
            rawNorms = normalized.norm2(1);
            norms = rawNorms.reshape(count, 1).dup().addi(EPSILON);
            normalized.diviColumnVector(norms);

            AdjacencyView graph = buildKnnGraph(passages, normalized);
            Map<String, Integer> assignment = LouvainCommunityDetection.compute(graph);
            double modularity = LouvainCommunityDetection.modularity(graph, assignment);
            Map<Integer, List<String>> grouped = LouvainCommunityDetection.groupByCommunity(assignment);
            Map<String, Integer> passageIndex = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                passageIndex.put(passages.get(i).chunkId(), i);
            }

            List<List<Integer>> communities = new ArrayList<>();
            for (List<String> members : grouped.values()) {
                List<Integer> indices = members.stream()
                        .map(passageIndex::get)
                        .filter(java.util.Objects::nonNull)
                        .sorted()
                        .toList();
                if (indices.size() >= MIN_TOPIC_SIZE) {
                    communities.add(indices);
                }
            }
            communities.sort(Comparator.comparingInt(indices -> indices.get(0)));

            Map<Integer, Map<String, Map<String, Integer>>> termCounts =
                    communityTermCounts(communities, passages, languages);
            Map<String, Map<String, Integer>> corpusTermFrequency = corpusTermFrequency(termCounts);
            Map<String, Double> averageClassLength = averageClassLength(termCounts);

            List<CorpusTopicEvidence.Topic> topics = new ArrayList<>();
            int includedPassages = 0;
            for (int topicIndex = 0; topicIndex < communities.size(); topicIndex++) {
                List<Integer> members = communities.get(topicIndex);
                includedPassages += members.size();
                List<String> memberIds = members.stream().map(i -> passages.get(i).chunkId()).toList();
                List<String> representatives = representatives(members, passages, languages, normalized);
                Map<String, Integer> languageDistribution = new LinkedHashMap<>();
                for (int member : members) {
                    languageDistribution.merge(languages.get(member), 1, Integer::sum);
                }
                Map<String, List<String>> terms = scoredTerms(
                        termCounts.get(topicIndex), corpusTermFrequency, averageClassLength);
                topics.add(new CorpusTopicEvidence.Topic(
                        String.format(Locale.ROOT, "topic-%04d", topicIndex + 1),
                        memberIds, representatives, languageDistribution, terms));
            }

            return new CorpusTopicEvidence(
                    modelId, dimension, modularity, count - includedPassages, topics);
        } finally {
            if (norms != null) norms.close();
            if (rawNorms != null) rawNorms.close();
            if (normalized != null) normalized.close();
        }
    }

    private static AdjacencyView buildKnnGraph(
            List<CrawlCorpusPassage> passages, INDArray normalized) {
        int rows = Math.toIntExact(normalized.size(0));
        int k = Math.min(K_NEIGHBORS, Math.max(1, rows - 1));
        AdjacencyView.Builder graph = AdjacencyView.builder();
        passages.forEach(passage -> graph.addNode(passage.chunkId()));

        INDArray transposed = normalized.transpose();
        try {
            for (int start = 0; start < rows; start += SIMILARITY_BLOCK_ROWS) {
                int end = Math.min(rows, start + SIMILARITY_BLOCK_ROWS);
                INDArray blockRows = normalized.get(
                        NDArrayIndex.interval(start, end), NDArrayIndex.all());
                INDArray block = null;
                try {
                    block = blockRows.mmul(transposed);
                    float[] scores = block.data().asFloat();
                    for (int localRow = 0; localRow < end - start; localRow++) {
                        int globalRow = start + localRow;
                        PriorityQueue<Neighbor> nearest = new PriorityQueue<>(
                                Comparator.comparingDouble(Neighbor::score)
                                        .thenComparing(Neighbor::chunkId, Comparator.reverseOrder()));
                        int offset = localRow * rows;
                        for (int column = 0; column < rows; column++) {
                            if (column == globalRow) continue;
                            double score = scores[offset + column];
                            if (!Double.isFinite(score) || score < MIN_SIMILARITY) continue;
                            Neighbor candidate = new Neighbor(
                                    passages.get(column).chunkId(), score);
                            nearest.offer(candidate);
                            if (nearest.size() > k) nearest.poll();
                        }
                        for (Neighbor neighbor : nearest) {
                            graph.addEdge(passages.get(globalRow).chunkId(),
                                    neighbor.chunkId(), neighbor.score());
                        }
                    }
                } finally {
                    if (block != null) block.close();
                    blockRows.close();
                }
            }
        } finally {
            transposed.close();
        }
        return graph.build();
    }

    private static List<String> representatives(
            List<Integer> members,
            List<CrawlCorpusPassage> passages,
            List<String> languages,
            INDArray normalized) {
        int[] rows = members.stream().mapToInt(Integer::intValue).toArray();
        INDArray memberMatrix = normalized.getRows(rows);
        INDArray centroid = null;
        INDArray centroidColumn = null;
        INDArray scoreArray = null;
        float[] scores;
        try {
            centroid = memberMatrix.mean(0);
            double norm = centroid.norm2Number().doubleValue();
            if (norm > EPSILON) centroid.divi(norm);
            centroidColumn = centroid.reshape(centroid.length(), 1).dup();
            scoreArray = memberMatrix.mmul(centroidColumn);
            scores = scoreArray.data().asFloat();
        } finally {
            if (scoreArray != null) scoreArray.close();
            if (centroidColumn != null) centroidColumn.close();
            if (centroid != null) centroid.close();
            memberMatrix.close();
        }

        List<Representative> ranked = new ArrayList<>();
        for (int i = 0; i < members.size(); i++) {
            int passageIndex = members.get(i);
            ranked.add(new Representative(passageIndex, languages.get(passageIndex), scores[i]));
        }
        ranked.sort(Comparator.comparingDouble(Representative::score).reversed()
                .thenComparing(rep -> passages.get(rep.passageIndex()).chunkId()));

        LinkedHashSet<Integer> selected = new LinkedHashSet<>();
        Set<String> representedLanguages = new HashSet<>();
        for (Representative representative : ranked) {
            if (representedLanguages.add(representative.language())) {
                selected.add(representative.passageIndex());
                if (selected.size() == MAX_REPRESENTATIVES) break;
            }
        }
        for (Representative representative : ranked) {
            if (selected.size() == MAX_REPRESENTATIVES) break;
            selected.add(representative.passageIndex());
        }
        return selected.stream().map(i -> passages.get(i).chunkId()).toList();
    }

    private static Map<Integer, Map<String, Map<String, Integer>>> communityTermCounts(
            List<List<Integer>> communities,
            List<CrawlCorpusPassage> passages,
            List<String> languages) {
        Map<Integer, Map<String, Map<String, Integer>>> result = new LinkedHashMap<>();
        for (int topic = 0; topic < communities.size(); topic++) {
            Map<String, Map<String, Integer>> byLanguage = new LinkedHashMap<>();
            for (int passageIndex : communities.get(topic)) {
                Map<String, Integer> counts = byLanguage.computeIfAbsent(
                        languages.get(passageIndex), ignored -> new LinkedHashMap<>());
                for (String term : tokenize(
                        passages.get(passageIndex).content(), languages.get(passageIndex))) {
                    counts.merge(term, 1, Integer::sum);
                }
            }
            result.put(topic, byLanguage);
        }
        return result;
    }

    private static Map<String, Map<String, Integer>> corpusTermFrequency(
            Map<Integer, Map<String, Map<String, Integer>>> termCounts) {
        Map<String, Map<String, Integer>> result = new LinkedHashMap<>();
        for (Map<String, Map<String, Integer>> byLanguage : termCounts.values()) {
            byLanguage.forEach((language, counts) -> counts.forEach((term, count) ->
                    result.computeIfAbsent(language, ignored -> new HashMap<>())
                            .merge(term, count, Integer::sum)));
        }
        return result;
    }

    private static Map<String, Double> averageClassLength(
            Map<Integer, Map<String, Map<String, Integer>>> termCounts) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        Map<String, Integer> classes = new LinkedHashMap<>();
        termCounts.values().forEach(byLanguage -> byLanguage.forEach((language, counts) -> {
            totals.merge(language, counts.values().stream().mapToInt(Integer::intValue).sum(), Integer::sum);
            classes.merge(language, 1, Integer::sum);
        }));
        Map<String, Double> result = new LinkedHashMap<>();
        totals.forEach((language, total) ->
                result.put(language, total / (double) Math.max(1, classes.getOrDefault(language, 1))));
        return result;
    }

    private static Map<String, List<String>> scoredTerms(
            Map<String, Map<String, Integer>> byLanguage,
            Map<String, Map<String, Integer>> corpusTermFrequency,
            Map<String, Double> averageClassLength) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (byLanguage == null) return result;
        byLanguage.forEach((language, counts) -> {
            int total = counts.values().stream().mapToInt(Integer::intValue).sum();
            double averageLength = averageClassLength.getOrDefault(language, (double) total);
            Map<String, Integer> frequency = corpusTermFrequency.getOrDefault(language, Map.of());
            List<TermScore> ranked = counts.entrySet().stream().map(entry -> {
                double tf = total == 0 ? 0.0 : entry.getValue() / (double) total;
                double idf = Math.log(1.0 + averageLength
                        / Math.max(1.0, frequency.getOrDefault(entry.getKey(), 0)));
                return new TermScore(entry.getKey(), tf * idf);
            }).sorted(Comparator.comparingDouble(TermScore::score).reversed()
                    .thenComparing(TermScore::term))
                    .limit(MAX_TERMS_PER_LANGUAGE).toList();
            result.put(language, ranked.stream().map(TermScore::term).toList());
        });
        return result;
    }

    private static List<String> tokenize(String text, String language) {
        Locale locale = language == null || language.isBlank()
                ? Locale.ROOT : Locale.forLanguageTag(language);
        BreakIterator iterator = BreakIterator.getWordInstance(locale);
        String content = text == null ? "" : text;
        iterator.setText(content);
        List<String> terms = new ArrayList<>();
        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            String token = content.substring(start, end).trim()
                    .toLowerCase(Locale.ROOT);
            if (token.codePointCount(0, token.length()) < 2
                    || token.codePointCount(0, token.length()) > 48
                    || token.codePoints().noneMatch(Character::isLetter)) {
                continue;
            }
            terms.add(token);
        }
        return terms;
    }

    private static void validateInputs(
            List<CrawlCorpusPassage> passages, float[][] embeddings, List<String> languages) {
        if (passages == null || embeddings == null || languages == null
                || passages.size() != embeddings.length || passages.size() != languages.size()
                || passages.size() < MIN_TOPIC_SIZE) {
            throw new IllegalArgumentException("Passages, embeddings, and languages must align");
        }
        int dimension = embeddings[0] == null ? 0 : embeddings[0].length;
        if (dimension == 0) throw new IllegalArgumentException("Embedding dimension must be positive");
        for (float[] embedding : embeddings) {
            if (embedding == null || embedding.length != dimension || !indexable(embedding)) {
                throw new IllegalArgumentException("Embedding vectors must be finite and have equal dimensions");
            }
        }
    }

    private static boolean indexable(float[] vector) {
        if (vector == null || vector.length == 0) return false;
        double magnitude = 0.0;
        for (float value : vector) {
            if (!Float.isFinite(value)) return false;
            magnitude += (double) value * value;
        }
        return magnitude > EPSILON;
    }

    private record Neighbor(String chunkId, double score) {}
    private record Representative(int passageIndex, String language, double score) {}
    private record TermScore(String term, double score) {}
}
