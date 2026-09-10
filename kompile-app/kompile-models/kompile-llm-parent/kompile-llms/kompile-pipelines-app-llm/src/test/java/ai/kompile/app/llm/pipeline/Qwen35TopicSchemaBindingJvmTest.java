/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.app.llm.pipeline;

import ai.kompile.core.crawl.graph.ExtractionMode;
import ai.kompile.core.crawl.graph.GraphExtractionConfig;
import ai.kompile.core.crawl.graph.ProcessingRouteConfig;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.graphrag.model.schema.SchemaHierarchyVocabulary;
import ai.kompile.crawl.graph.HeadlessUnifiedCorpusExtractor;
import ai.kompile.embedding.anserini.AnseriniEncoderFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.concurrency.AffinityManager;
import org.nd4j.linalg.factory.Nd4j;
import org.springframework.ai.document.Document;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real, in-process E5 + Qwen qualification for topic-bound crawl schema induction. */
@Tag("integration")
class Qwen35TopicSchemaBindingJvmTest {
    private static final String ENABLED_PROPERTY = "kompile.test.qwenTopicInProcess";
    private static final String MODEL_PROPERTY = "kompile.test.qwenModel";
    private static final String TOKENIZER_PROPERTY = "kompile.test.qwenTokenizer";
    private static final String E5_MODEL_PROPERTY = "kompile.test.e5Model";
    private static final String E5_TOKENIZER_PROPERTY = "kompile.test.e5Tokenizer";
    private static final String MODEL_ID = "qwen35-2b";
    private static final int OUTPUT_TOKEN_BUDGET = 128;
    private static final Path DEFAULT_BUNDLE = Path.of(
            System.getProperty("user.home"), ".cache", "dl4j-llm-models", "qwen35-2B");

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void bindsMultilingualTopicsIntoTheRealSchemaHierarchy() throws Exception {
        assumeTrue(Boolean.getBoolean(ENABLED_PROPERTY), "Enable with -D" + ENABLED_PROPERTY + "=true");
        Path modelPath = requiredFile(MODEL_PROPERTY,
                DEFAULT_BUNDLE.resolve("Qwen3.5-2B-Q4_K_M.gguf"));
        Path tokenizerPath = requiredFile(TOKENIZER_PROPERTY,
                DEFAULT_BUNDLE.resolve("tokenizer.json"));
        Path e5Root = projectArtifact("data/models/multilingual-e5-small");
        Path e5Model = requiredFile(E5_MODEL_PROPERTY, e5Root.resolve("model.opt.sdz"));
        Path e5Tokenizer = requiredFile(E5_TOKENIZER_PROPERTY, e5Root.resolve("tokenizer.json"));

        List<Document> corpus = multilingualCorpus();
        EmbeddingModel embeddings = materializeE5(corpus, e5Model, e5Tokenizer);
        try (embeddings) {
            SameDiffLanguageModelImpl languageModel =
                    new SameDiffLanguageModelImpl(Optional.empty(), Optional.empty());
            languageModel.loadModel(MODEL_ID, modelPath, tokenizerPath, Map.of(
                    "maxNewTokens", OUTPUT_TOKEN_BUDGET,
                    "dspEnabled", true,
                    "temperature", 0.0d));
            try (HeadlessUnifiedCorpusExtractor extractor = new HeadlessUnifiedCorpusExtractor(
                    null, new SameDiffInProcessBackend(languageModel), embeddings, null,
                    1, null, null)) {
                HeadlessUnifiedCorpusExtractor.Result result = extractor.extract(
                        corpus, extractionConfig(), localRoute(), runtimeConfig(),
                        1, "qwen35-topic-schema-jvm-test", null);

                assertFalse(result.failed(), () -> "Direct model crawl failed: " + result.errors());
                assertTrue(result.parseFailures() == 0, () -> "Parse failures: " + result.errors());
                assertTrue(result.graph().getEntities().size() > 0, "No semantic entities were extracted");
                assertTrue(result.graph().getRelationships().size() > 0,
                        "No semantic relationships were extracted");

                Map<?, ?> evidence = boundTopicEvidence(result);
                assertEquals("multilingual-e5-small", evidence.get("embeddingModelId"));
                assertEquals(384, ((Number) evidence.get("embeddingDimension")).intValue());
                assertTrue(((List<?>) evidence.get("topics")).size() >= 2, evidence.toString());
                List<Map<?, ?>> topics = maps(evidence.get("topics"));
                assertTrue(hasTopicWithDocuments(topics, Set.of(
                        "astronomy-en.txt", "astronomy-es.txt", "astronomy-fr.txt")),
                        evidence.toString());
                assertTrue(hasTopicWithDocuments(topics, Set.of(
                        "ecology-en.txt", "ecology-de.txt", "ecology-es.txt")),
                        evidence.toString());
                List<Map<?, ?>> bindings = maps(evidence.get("bindings"));
                assertTrue(bindings.stream().map(binding -> (List<?>) binding.get("nodeTypes"))
                        .filter(java.util.Objects::nonNull).anyMatch(values -> !values.isEmpty()),
                        evidence.toString());

                Set<String> availableNodes = new LinkedHashSet<>(
                        SchemaHierarchyVocabulary.BASE_ENTITY_TYPES);
                bindings.stream().map(binding -> (List<?>) binding.get("nodeTypes"))
                        .filter(java.util.Objects::nonNull).flatMap(List::stream)
                        .map(Map.class::cast)
                        .forEach(node -> availableNodes.add(String.valueOf(node.get("label"))));

                List<Map<?, ?>> relationships = new ArrayList<>();
                bindings.forEach(binding -> relationships.addAll(
                        maps(binding.get("relationshipTypes"))));
                assertTrue(relationships.stream().anyMatch(relation ->
                                SchemaHierarchyVocabulary.isConnectionFamily(
                                        String.valueOf(relation.get("connectionFamily")))
                                        && availableNodes.contains(String.valueOf(relation.get("sourceType")))
                                        && availableNodes.contains(String.valueOf(relation.get("targetType")))),
                        evidence.toString());

                Set<String> boundPredicates = relationships.stream()
                        .map(relation -> String.valueOf(relation.get("type")))
                        .collect(java.util.stream.Collectors.toSet());
                assertTrue(result.graph().getRelationships().stream()
                                .anyMatch(relation -> boundPredicates.contains(relation.getType())),
                        () -> "No extracted edge used a topic-bound predicate: " + boundPredicates);
            } finally {
                languageModel.unloadModel();
                languageModel.shutdown();
            }
        }
    }

    private static Map<?, ?> boundTopicEvidence(HeadlessUnifiedCorpusExtractor.Result result) {
        Map<?, ?> payload = result.traceEvents().stream()
                .filter(event -> "CORPUS_TOPIC_EVIDENCE".equals(event.get("eventType")))
                .map(event -> (Map<?, ?>) event.get("payload"))
                .filter(java.util.Objects::nonNull)
                .reduce((ignored, latest) -> latest)
                .orElseThrow(() -> new AssertionError("No corpus topic evidence trace"));
        assertNotNull(payload.get("bindings"), payload.toString());
        return payload;
    }

    private static List<Map<?, ?>> maps(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        List<Map<?, ?>> result = new ArrayList<>(values.size());
        for (Object item : values) result.add((Map<?, ?>) item);
        return List.copyOf(result);
    }

    private static boolean hasTopicWithDocuments(
            List<Map<?, ?>> topics, Set<String> expectedDocuments) {
        return topics.stream().anyMatch(topic -> {
            Object value = topic.get("memberDocumentIds");
            if (!(value instanceof List<?> documents)) return false;
            return documents.stream().map(String::valueOf)
                    .collect(java.util.stream.Collectors.toSet())
                    .containsAll(expectedDocuments);
        });
    }

    private static EmbeddingModel materializeE5(
            List<Document> corpus, Path modelPath, Path tokenizerPath) throws Exception {
        List<String> texts = corpus.stream().map(Document::getText).toList();
        List<float[]> vectors;
        AffinityManager affinity = Nd4j.getAffinityManager();
        int originalDevice = affinity.getDeviceForCurrentThread();
        List<Integer> devices = affinity.getAvailableDeviceIds();
        int encoderDevice = devices.size() > 1 ? devices.get(devices.size() - 1) : originalDevice;
        affinity.setDeviceForCurrentThread(encoderDevice);
        try {
            try (EmbeddingModel e5 = AnseriniEncoderFactory.createInProcessEmbeddingModel(
                    "multilingual-e5-small", modelPath, tokenizerPath,
                    AnseriniEncoderFactory.InProcessEncoderConfig.multilingualE5Small())) {
                assertEquals("multilingual-e5-small", e5.getModelIdentifier());
                assertEquals(384, e5.dimensions());
                List<float[]> materialized = new ArrayList<>(texts.size());
                for (String text : texts) {
                    try (INDArray vector = e5.embed(text)) {
                        materialized.add(vector.data().asFloat());
                    }
                }
                vectors = List.copyOf(materialized);
            }
        } finally {
            affinity.setDeviceForCurrentThread(originalDevice);
        }
        return new MaterializedEmbeddingModel(texts, vectors);
    }

    private static final class MaterializedEmbeddingModel implements EmbeddingModel {
        private final Map<String, float[]> vectors;

        private MaterializedEmbeddingModel(List<String> texts, List<float[]> embeddings) {
            if (texts.size() != embeddings.size()) {
                throw new IllegalArgumentException("text and embedding counts differ");
            }
            vectors = new LinkedHashMap<>();
            for (int i = 0; i < texts.size(); i++) {
                vectors.put(texts.get(i), embeddings.get(i).clone());
            }
        }

        @Override
        public INDArray embed(String text) {
            return Nd4j.create(requireVector(text));
        }

        @Override
        public INDArray embed(List<String> texts) {
            return Nd4j.create(embedBatch(texts).toArray(float[][]::new));
        }

        @Override
        public INDArray embedDocuments(List<Document> documents) {
            return embed(documents.stream().map(Document::getText).toList());
        }

        @Override
        public List<float[]> embedBatch(List<String> texts) {
            return texts.stream().map(this::requireVector).map(float[]::clone).toList();
        }

        @Override
        public int dimensions() {
            return 384;
        }

        @Override
        public String getModelName() {
            return "multilingual-e5-small";
        }

        @Override
        public String getModelIdentifier() {
            return "multilingual-e5-small";
        }

        @Override
        public boolean isInitialized() {
            return true;
        }

        private float[] requireVector(String text) {
            float[] vector = vectors.get(text);
            if (vector == null) throw new IllegalArgumentException(
                    "No materialized embedding for text: " + text);
            return vector;
        }
    }

    private static GraphExtractionConfig extractionConfig() {
        return GraphExtractionConfig.builder()
                .llmProvider("serving").modelName(MODEL_ID).temperature(0.0d)
                .maxTokens(OUTPUT_TOKEN_BUDGET).minConfidence(0.5d).entityResolution(false)
                .extractionMode(ExtractionMode.SINGLE_PASS).build();
    }

    private static ProcessingRouteConfig localRoute() {
        return ProcessingRouteConfig.builder()
                .fallbackEnabled(false).servingLaneEnabled(true)
                .backends(List.of(ProcessingRouteConfig.ProcessingBackend.builder()
                        .id("qwen-in-process").displayName("In-process Qwen 3.5")
                        .type(ProcessingRouteConfig.ProcessingBackendType.LOCAL_MODEL)
                        .agentName("serving").modelName(MODEL_ID).priority(1).maxConcurrent(1)
                        .capabilities(List.of("llm")).enabled(true).build()))
                .build();
    }

    private static UnifiedCrawlRequest.RuntimeConfig runtimeConfig() {
        return UnifiedCrawlRequest.RuntimeConfig.builder()
                .graphExtractionParallelism(1).graphExtractionRemoteParallelism(1)
                .graphExtractionBatchSize(2).graphExtractionTargetCharsPerBatch(2_000)
                .graphExtractionMaxItemsPerBatch(4).graphExtractionBatchTimeoutSeconds(1_800)
                .llmCallTimeoutSeconds(1_800).costSortChunks(true)
                .build();
    }

    private static List<Document> multilingualCorpus() {
        return List.of(
                topicDocument("astronomy-en.txt", "The observatory telescope detects the Andromeda galaxy and records its orbit."),
                topicDocument("astronomy-es.txt", "El telescopio del observatorio detecta la galaxia Andrómeda y registra su órbita."),
                topicDocument("astronomy-fr.txt", "Le télescope de l'observatoire détecte la galaxie Andromède et mesure son orbite."),
                topicDocument("ecology-en.txt", "The monarch butterfly inhabits the forest habitat and depends on native plants."),
                topicDocument("ecology-de.txt", "Der Monarchfalter bewohnt den Waldlebensraum und ist von heimischen Pflanzen abhängig."),
                topicDocument("ecology-es.txt", "La mariposa monarca habita el bosque y depende de las plantas nativas."));
    }

    private static Document topicDocument(String sourcePath, String text) {
        return new Document(sourcePath, text, Map.of(GraphConstants.META_SOURCE_PATH, sourcePath));
    }

    private static Path requiredFile(String property, Path fallback) {
        String configured = System.getProperty(property);
        Path path = configured == null || configured.isBlank() ? fallback : Path.of(configured);
        path = path.toAbsolutePath().normalize();
        assumeTrue(Files.isRegularFile(path), "Missing model artifact: " + path);
        return path;
    }

    private static Path projectArtifact(String relativePath) {
        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (cursor != null) {
            Path candidate = cursor.resolve(relativePath);
            if (Files.exists(candidate)) return candidate;
            cursor = cursor.getParent();
        }
        return Path.of(relativePath).toAbsolutePath().normalize();
    }
}
