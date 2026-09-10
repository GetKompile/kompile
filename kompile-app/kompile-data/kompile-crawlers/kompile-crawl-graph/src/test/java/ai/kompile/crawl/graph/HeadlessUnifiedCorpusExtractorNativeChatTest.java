/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.crawl.graph;

import ai.kompile.core.agent.CliAgentRunner;
import ai.kompile.core.crawl.graph.ExtractionMode;
import ai.kompile.core.crawl.graph.GraphExtractionConfig;
import ai.kompile.core.crawl.graph.LocalServingBackend;
import ai.kompile.core.crawl.graph.NativeChatCompletion;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.NodeType;
import ai.kompile.core.graphrag.model.schema.RelationshipType;
import ai.kompile.core.graphrag.model.schema.SchemaEnforcementMode;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HeadlessUnifiedCorpusExtractorNativeChatTest {
    @Test
    void nativeTextUsesProductionParserAndNeverBorrowsStructuredServingCapability() throws Exception {
        CliAgentRunner cli = mock(CliAgentRunner.class);
        LocalServingBackend serving = mock(LocalServingBackend.class);
        when(serving.supportsStructuredChat()).thenReturn(true);
        AtomicInteger calls = new AtomicInteger();
        NativeChatCompletion nativeChat = (provider, model, prompt, system, timeout) -> {
            assertEquals("custom", provider);
            assertEquals("exact-model", model);
            calls.incrementAndGet();
            return """
                    {"$schema":"kompile-graph-extraction/v1","entities":[
                      {"id":"acme","name":"Acme","type":"ORGANIZATION","confidence":0.95},
                      {"id":"initech","name":"Initech","type":"ORGANIZATION","confidence":0.92}
                    ],"relations":[
                      {"source":"acme","target":"initech","type":"ACQUIRED","confidence":0.9}
                    ]}
                    """;
        };
        try (HeadlessUnifiedCorpusExtractor extractor = new HeadlessUnifiedCorpusExtractor(
                cli, serving, nativeChat, null, null, 1, null, null)) {
            HeadlessUnifiedCorpusExtractor.Result result = extractor.extract(
                    List.of(new Document("Acme acquired Initech.")), config(), null, "native-graph", null);
            assertFalse(result.failed(), result.errors().toString());
            assertEquals(2, result.graph().getEntities().size());
            assertEquals(1, result.graph().getRelationships().size());
            assertEquals("ACQUIRED", result.graph().getRelationships().get(0).getType());
            assertTrue(calls.get() > 0);
            verifyNoInteractions(cli);
            // The native route forces the text protocol even when a structured serving lane exists.
            verify(serving, never()).generate(anyString());
            verify(serving, never()).generateChat(any(), anyInt());
        }
    }

    @Test
    void unsupportedNativeSchemaPrepassFailsBeforeLegacyBaselineFallback() {
        try (HeadlessUnifiedCorpusExtractor extractor = new HeadlessUnifiedCorpusExtractor(
                null, null, (provider, model, prompt, system, timeout) -> "{}",
                null, null, 1, null, null)) {
            IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                    extractor.extract(
                            List.of(new Document("schema-prepass")),
                            GraphExtractionConfig.builder()
                                    .llmProvider("chat:custom")
                                    .modelName("text-only-model")
                                    .schemaMode(SchemaEnforcementMode.LENIENT)
                                    .build(),
                            null, "unsupported-schema", null));
            assertTrue(failure.getMessage().contains("validated JSON-schema prepass"));
        }
    }

    @Test
    void explicitCallerSchemaTakesPrecedenceOverPersistedSeedWithoutStructuredFallback() {
        AtomicInteger structuredCalls = new AtomicInteger();
        AtomicInteger textCalls = new AtomicInteger();
        NativeChatCompletion bridge = new NativeChatCompletion() {
            @Override
            public boolean supportsStructuredChat(String provider, String model, String thinking) {
                return true;
            }

            @Override
            public String completeStructuredJson(
                    String provider, String model, String thinking, String prompt,
                    String system, java.util.Map<String, Object> schema,
                    java.time.Duration timeout) {
                structuredCalls.incrementAndGet();
                throw new AssertionError("authoritative caller schema must skip the schema prepass");
            }

            @Override
            public String complete(String provider, String model, String prompt,
                                   String system, java.time.Duration timeout) {
                textCalls.incrementAndGet();
                return """
                        {"$schema":"kompile-graph-extraction/v1","entities":[
                          {"id":"acme","name":"Acme","type":"ORGANIZATION","confidence":0.95},
                          {"id":"initech","name":"Initech","type":"ORGANIZATION","confidence":0.92}
                        ],"relations":[
                          {"source":"acme","target":"initech","type":"ACQUIRED","confidence":0.9}
                        ]}
                        """;
            }
        };
        GraphSchema persistedSeed = new GraphSchema(
                List.of(new NodeType("PERSISTED_ONLY", "Must not override the caller.", null)),
                List.of(new RelationshipType("PERSISTED_REL", "Must not override the caller.", null)),
                List.of());
        GraphExtractionConfig callerConfig = config();
        GraphSchema callerSchema = callerConfig.getStandardizedSchema();

        try (HeadlessUnifiedCorpusExtractor extractor = new HeadlessUnifiedCorpusExtractor(
                null, null, bridge, null, null, 1, null, null)) {
            HeadlessUnifiedCorpusExtractor.Result result = extractor.extract(
                    List.of(new Document("caller-schema", "Acme acquired Initech.", java.util.Map.of())),
                    callerConfig, null, null, 1, "caller-schema-precedence", null, persistedSeed);

            assertFalse(result.failed(), result.errors().toString());
            assertEquals(0, structuredCalls.get());
            assertTrue(textCalls.get() > 0);
            assertNotNull(result.canonicalGraphSchema());
            assertTrue(result.canonicalGraphSchema().getAllNodeLabels().contains("ORGANIZATION"));
            assertFalse(result.canonicalGraphSchema().getAllNodeLabels().contains("PERSISTED_ONLY"));
            assertFalse(result.canonicalGraphSchema().getAllRelationshipTypes().contains("PERSISTED_REL"));
            assertSame(callerSchema, callerConfig.getStandardizedSchema());
            assertEquals("ORGANIZATION", callerConfig.getStandardizedSchema().getNodeTypes().get(0).getLabel());
        }
    }

    @Test
    void invalidNativeOutputIsAProductionExtractionFailureNotASemanticGraph() {
        try (HeadlessUnifiedCorpusExtractor extractor = new HeadlessUnifiedCorpusExtractor(
                null, null, (provider, model, prompt, system, timeout) -> "not graph JSON",
                null, null, 1, null, null)) {
            HeadlessUnifiedCorpusExtractor.Result result = extractor.extract(
                    List.of(new Document("Acme acquired Initech.")), config(), null,
                    null, 0, "invalid-native-graph", null);
            assertTrue(result.failed(), "Parser/validation errors must not become successful extraction");
        }
    }

    private static GraphExtractionConfig config() {
        return GraphExtractionConfig.builder().llmProvider("chat:custom").modelName("exact-model")
                .extractionMode(ExtractionMode.SINGLE_PASS).entityResolution(false).minConfidence(0.0)
                .entityTypes(List.of("ORGANIZATION")).relationshipTypes(List.of("ACQUIRED"))
                .standardizedSchema(new GraphSchema(
                        List.of(new NodeType("ORGANIZATION", "An organization.", null)),
                        List.of(new RelationshipType("ACQUIRED", "Acquisition.", null)),
                        List.of("(ORGANIZATION)-[:ACQUIRED]->(ORGANIZATION)")))
                .schemaMode(SchemaEnforcementMode.STRICT).build();
    }
}
