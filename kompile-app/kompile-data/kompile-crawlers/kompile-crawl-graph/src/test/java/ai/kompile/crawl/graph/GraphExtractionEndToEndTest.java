/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.crawl.graph;

import ai.kompile.core.crawl.graph.*;
import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.graphrag.GraphConstructor;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.llm.chat.LLMChat;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.document.Document;

import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * End-to-end tests focused on graph extraction entity/relationship combinations.
 * Covers complex multi-document scenarios, diverse entity type mixes, relationship
 * chains, entity resolution across sources, and confidence-based filtering.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GraphExtractionEndToEndTest {

    @Mock private DocumentLoader loader;
    @Mock private LLMChat llmChat;
    @Mock private LLMChat.ChatClientRequestSpec requestSpec;
    @Mock private LLMChat.CallResponseSpec callResponseSpec;

    private UnifiedCrawlGraphServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = new UnifiedCrawlGraphServiceImpl();
        setField(service, "documentLoaders", List.of(loader));
        setField(service, "llmChat", llmChat);
        // retainResultGraph must be true so job.getResultGraph() is non-null after completion
        setField(service, "retainResultGraph", true);

        when(loader.supports(any())).thenReturn(true);
        when(loader.getName()).thenReturn("Test Loader");
        when(llmChat.prompt(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
    }

    // ──────────────────────────────────────────────────────────────────
    // 1. COMPLEX ENTITY TYPE COMBINATIONS
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Full knowledge graph: PERSON, ORGANIZATION, LOCATION, TECHNOLOGY, CONCEPT")
    void fullEntityTypeMix() throws Exception {
        when(loader.load(any(DocumentSourceDescriptor.class), any())).thenReturn(List.of(
                new Document("Alice at Google in Mountain View uses TensorFlow for deep learning research.", Map.of())
        ));

        when(callResponseSpec.content()).thenReturn(buildJson(
                List.of(
                        entity("e1", "Alice", "PERSON", "Researcher", 0.95),
                        entity("e2", "Google", "ORGANIZATION", "Tech company", 0.98),
                        entity("e3", "Mountain View", "LOCATION", "City in California", 0.9),
                        entity("e4", "TensorFlow", "TECHNOLOGY", "ML framework", 0.97),
                        entity("e5", "Deep Learning", "CONCEPT", "Subfield of ML", 0.88)
                ),
                List.of(
                        relation("e1", "e2", "WORKS_AT", "Alice works at Google", 0.92),
                        relation("e2", "e3", "HEADQUARTERED_IN", "Google HQ in Mountain View", 0.85),
                        relation("e1", "e4", "USES", "Alice uses TensorFlow", 0.9),
                        relation("e4", "e5", "IMPLEMENTS", "TF implements deep learning", 0.87),
                        relation("e1", "e5", "RESEARCHES", "Alice researches deep learning", 0.8)
                )
        ));

        UnifiedCrawlJob job = startJobWithGraph(List.of("PERSON", "ORGANIZATION", "LOCATION", "TECHNOLOGY", "CONCEPT"));
        awaitCompletion(job);

        assertEquals(5, job.getEntitiesExtracted().get());
        assertEquals(5, job.getRelationshipsExtracted().get());

        Set<String> types = job.getResultGraph().getEntities().stream()
                .map(e -> e.getType()).collect(Collectors.toSet());
        assertEquals(Set.of("PERSON", "ORGANIZATION", "LOCATION", "TECHNOLOGY", "CONCEPT"), types);

        // Verify relationship types
        Set<String> relTypes = job.getResultGraph().getRelationships().stream()
                .map(r -> r.getType()).collect(Collectors.toSet());
        assertTrue(relTypes.contains("WORKS_AT"));
        assertTrue(relTypes.contains("USES"));
        assertTrue(relTypes.contains("HEADQUARTERED_IN"));
    }

    @Test
    @DisplayName("Software domain: SERVICE, API, DATABASE, FRAMEWORK entities")
    void softwareDomainEntities() throws Exception {
        when(loader.load(any(DocumentSourceDescriptor.class), any())).thenReturn(List.of(
                new Document("The Auth Service exposes a REST API backed by PostgreSQL and built with Spring Boot.", Map.of())
        ));

        when(callResponseSpec.content()).thenReturn(buildJson(
                List.of(
                        entity("e1", "Auth Service", "SERVICE", "Authentication microservice", 0.95),
                        entity("e2", "REST API", "API", "HTTP endpoint interface", 0.9),
                        entity("e3", "PostgreSQL", "DATABASE", "Relational database", 0.92),
                        entity("e4", "Spring Boot", "FRAMEWORK", "Java web framework", 0.93)
                ),
                List.of(
                        relation("e1", "e2", "EXPOSES", "Auth Service exposes REST API", 0.9),
                        relation("e1", "e3", "STORES_DATA_IN", "Auth Service uses PostgreSQL", 0.88),
                        relation("e1", "e4", "BUILT_WITH", "Auth Service built with Spring Boot", 0.91)
                )
        ));

        UnifiedCrawlJob job = startJobWithGraph(List.of("SERVICE", "API", "DATABASE", "FRAMEWORK"));
        awaitCompletion(job);

        assertEquals(4, job.getEntitiesExtracted().get());
        assertEquals(3, job.getRelationshipsExtracted().get());
    }

    @Test
    @DisplayName("Legal/compliance domain: REGULATION, REQUIREMENT, DOCUMENT entities")
    void legalDomainEntities() throws Exception {
        when(loader.load(any(DocumentSourceDescriptor.class), any())).thenReturn(List.of(
                new Document("GDPR Article 17 requires data erasure. The Privacy Policy must comply with this.", Map.of())
        ));

        when(callResponseSpec.content()).thenReturn(buildJson(
                List.of(
                        entity("e1", "GDPR", "REGULATION", "EU data protection regulation", 0.98),
                        entity("e2", "Article 17", "REQUIREMENT", "Right to erasure", 0.95),
                        entity("e3", "Data Erasure", "CONCEPT", "Deletion of personal data", 0.9),
                        entity("e4", "Privacy Policy", "DOCUMENT", "Company privacy document", 0.88)
                ),
                List.of(
                        relation("e1", "e2", "CONTAINS", "GDPR contains Article 17", 0.95),
                        relation("e2", "e3", "REQUIRES", "Article 17 requires data erasure", 0.92),
                        relation("e4", "e1", "COMPLIES_WITH", "Policy complies with GDPR", 0.85)
                )
        ));

        UnifiedCrawlJob job = startJobWithGraph(List.of("REGULATION", "REQUIREMENT", "DOCUMENT", "CONCEPT"));
        awaitCompletion(job);

        assertEquals(4, job.getEntitiesExtracted().get());
        assertEquals(3, job.getRelationshipsExtracted().get());
    }

    // ──────────────────────────────────────────────────────────────────
    // 2. MULTI-DOCUMENT GRAPH MERGE
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Three documents produce merged graph with entity resolution")
    void multiDocument_entityResolutionMerge() throws Exception {
        when(loader.load(any(DocumentSourceDescriptor.class), any())).thenReturn(List.of(
                new Document("Alice works at Google.", Map.of()),
                new Document("Google is headquartered in Mountain View.", Map.of()),
                new Document("Alice presented at the Google Developer Conference.", Map.of())
        ));

        // Doc 1: Alice + Google
        String r1 = buildJson(
                List.of(entity("e1", "Alice", "PERSON", "Employee", 0.9),
                        entity("e2", "Google", "ORGANIZATION", "Tech company", 0.95)),
                List.of(relation("e1", "e2", "WORKS_AT", "works at", 0.85))
        );
        // Doc 2: Google + Mountain View
        String r2 = buildJson(
                List.of(entity("e3", "Google", "ORGANIZATION", "Tech giant", 0.92),
                        entity("e4", "Mountain View", "LOCATION", "City", 0.9)),
                List.of(relation("e3", "e4", "HEADQUARTERED_IN", "HQ in MV", 0.88))
        );
        // Doc 3: Alice + Google (duplicates)
        String r3 = buildJson(
                List.of(entity("e5", "Alice", "PERSON", "Presenter", 0.88),
                        entity("e6", "Google", "ORGANIZATION", "Conference host", 0.91)),
                List.of(relation("e5", "e6", "PRESENTED_AT", "Alice at Google conf", 0.82))
        );
        when(callResponseSpec.content()).thenReturn(r1, r2, r3);

        UnifiedCrawlJob job = service.startJob(UnifiedCrawlRequest.builder()
                .name("multi-doc merge")
                .sources(List.of(fileSource("docs")))
                .graphExtraction(GraphExtractionConfig.builder()
                        .enabled(true)
                        .entityResolution(true)
                        .minConfidence(0.0)
                        .build())
                .vectorIndex(VectorIndexConfig.builder().enabled(false).build())
                .build());

        awaitCompletion(job);

        // Alice appears 2x as PERSON → merged to 1
        // Google appears 3x as ORGANIZATION → merged to 1
        // Mountain View appears 1x → kept
        // Total unique: 3 entities
        assertEquals(3, job.getResultGraph().getEntities().size(),
                "Entity resolution should merge Alice(2) + Google(3) + MountainView(1) = 3 unique");

        long personCount = job.getResultGraph().getEntities().stream()
                .filter(e -> "PERSON".equals(e.getType())).count();
        long orgCount = job.getResultGraph().getEntities().stream()
                .filter(e -> "ORGANIZATION".equals(e.getType())).count();
        assertEquals(1, personCount, "Only one Alice after resolution");
        assertEquals(1, orgCount, "Only one Google after resolution");
    }

    @Test
    @DisplayName("Three documents without entity resolution keeps all entities")
    void multiDocument_noEntityResolution() throws Exception {
        when(loader.load(any(DocumentSourceDescriptor.class), any())).thenReturn(List.of(
                new Document("Alice at Google.", Map.of()),
                new Document("Alice presented at Google.", Map.of())
        ));

        String r1 = buildJson(
                List.of(entity("e1", "Alice", "PERSON", "Employee", 0.9)),
                List.of()
        );
        String r2 = buildJson(
                List.of(entity("e2", "Alice", "PERSON", "Presenter", 0.88)),
                List.of()
        );
        when(callResponseSpec.content()).thenReturn(r1, r2);

        UnifiedCrawlJob job = service.startJob(UnifiedCrawlRequest.builder()
                .name("no-resolution")
                .sources(List.of(fileSource("docs")))
                .graphExtraction(GraphExtractionConfig.builder()
                        .enabled(true)
                        .entityResolution(false)
                        .build())
                .vectorIndex(VectorIndexConfig.builder().enabled(false).build())
                .build());

        awaitCompletion(job);

        assertEquals(2, job.getResultGraph().getEntities().size(),
                "Without resolution, both Alice entities should remain");
    }

    @Test
    @DisplayName("Entity resolution is case-insensitive on title")
    void entityResolution_caseInsensitive() throws Exception {
        when(loader.load(any(DocumentSourceDescriptor.class), any())).thenReturn(List.of(
                new Document("tensorflow is great.", Map.of()),
                new Document("TensorFlow is a framework.", Map.of())
        ));

        String r1 = buildJson(
                List.of(entity("e1", "tensorflow", "TECHNOLOGY", "ML lib", 0.9)),
                List.of()
        );
        String r2 = buildJson(
                List.of(entity("e2", "TensorFlow", "TECHNOLOGY", "ML framework", 0.92)),
                List.of()
        );
        when(callResponseSpec.content()).thenReturn(r1, r2);

        UnifiedCrawlJob job = service.startJob(UnifiedCrawlRequest.builder()
                .name("case-insensitive test")
                .sources(List.of(fileSource("docs")))
                .graphExtraction(GraphExtractionConfig.builder()
                        .enabled(true)
                        .entityResolution(true)
                        .build())
                .vectorIndex(VectorIndexConfig.builder().enabled(false).build())
                .build());

        awaitCompletion(job);

        assertEquals(1, job.getResultGraph().getEntities().size(),
                "Case-insensitive resolution should merge tensorflow/TensorFlow");
    }

    @Test
    @DisplayName("Entity resolution requires matching type — same title different type kept separate")
    void entityResolution_typeMatters() throws Exception {
        when(loader.load(any(DocumentSourceDescriptor.class), any())).thenReturn(List.of(
                new Document("Java the island and Java the language.", Map.of()),
                new Document("Java programming is popular.", Map.of())
        ));

        // "Java" as LOCATION vs "Java" as TECHNOLOGY — different types, both kept
        String r1 = buildJson(
                List.of(entity("e1", "Java", "LOCATION", "Indonesian island", 0.85),
                        entity("e2", "Java", "TECHNOLOGY", "Programming language", 0.95)),
                List.of()
        );
        String r2 = buildJson(
                List.of(entity("e3", "Java", "TECHNOLOGY", "Language", 0.9)),
                List.of()
        );
        when(callResponseSpec.content()).thenReturn(r1, r2);

        UnifiedCrawlJob job = service.startJob(UnifiedCrawlRequest.builder()
                .name("type-matters test")
                .sources(List.of(fileSource("docs")))
                .graphExtraction(GraphExtractionConfig.builder()
                        .enabled(true)
                        .entityResolution(true)
                        .build())
                .vectorIndex(VectorIndexConfig.builder().enabled(false).build())
                .build());

        awaitCompletion(job);

        // Java(LOCATION) + Java(TECHNOLOGY, merged from 2) = 2 entities
        assertEquals(2, job.getResultGraph().getEntities().size(),
                "Same title but different type should NOT be merged");

        long techCount = job.getResultGraph().getEntities().stream()
                .filter(e -> "TECHNOLOGY".equals(e.getType())).count();
        long locCount = job.getResultGraph().getEntities().stream()
                .filter(e -> "LOCATION".equals(e.getType())).count();
        assertEquals(1, techCount);
        assertEquals(1, locCount);
    }

    // ──────────────────────────────────────────────────────────────────
    // 3. RELATIONSHIP CHAIN SCENARIOS
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Relationship chain: A→B→C→D creates connected graph")
    void relationshipChain_fourNodeChain() throws Exception {
        when(loader.load(any(DocumentSourceDescriptor.class), any())).thenReturn(List.of(
                new Document("The frontend calls the API gateway which routes to backend which queries the database.", Map.of())
        ));

        when(callResponseSpec.content()).thenReturn(buildJson(
                List.of(
                        entity("e1", "Frontend", "SERVICE", "UI", 0.9),
                        entity("e2", "API Gateway", "SERVICE", "Routing layer", 0.92),
                        entity("e3", "Backend", "SERVICE", "Business logic", 0.91),
                        entity("e4", "Database", "DATABASE", "Data store", 0.95)
                ),
                List.of(
                        relation("e1", "e2", "CALLS", "Frontend calls gateway", 0.88),
                        relation("e2", "e3", "ROUTES_TO", "Gateway routes to backend", 0.85),
                        relation("e3", "e4", "QUERIES", "Backend queries database", 0.9)
                )
        ));

        UnifiedCrawlJob job = startJobWithGraph(null);
        awaitCompletion(job);

        assertEquals(4, job.getEntitiesExtracted().get());
        assertEquals(3, job.getRelationshipsExtracted().get());
    }

    @Test
    @DisplayName("Bidirectional relationships between two entities")
    void bidirectionalRelationships() throws Exception {
        when(loader.load(any(DocumentSourceDescriptor.class), any())).thenReturn(List.of(
                new Document("Team A depends on Team B and Team B provides services to Team A.", Map.of())
        ));

        when(callResponseSpec.content()).thenReturn(buildJson(
                List.of(
                        entity("e1", "Team A", "ORGANIZATION", "Engineering team", 0.9),
                        entity("e2", "Team B", "ORGANIZATION", "Platform team", 0.9)
                ),
                List.of(
                        relation("e1", "e2", "DEPENDS_ON", "A depends on B", 0.85),
                        relation("e2", "e1", "PROVIDES_TO", "B provides to A", 0.82)
                )
        ));

        UnifiedCrawlJob job = startJobWithGraph(null);
        awaitCompletion(job);

        assertEquals(2, job.getEntitiesExtracted().get());
        assertEquals(2, job.getRelationshipsExtracted().get());
    }

    @Test
    @DisplayName("Self-referencing relationship (entity relates to itself)")
    void selfReferencingRelationship() throws Exception {
        when(loader.load(any(DocumentSourceDescriptor.class), any())).thenReturn(List.of(
                new Document("The recursive function calls itself.", Map.of())
        ));

        when(callResponseSpec.content()).thenReturn(buildJson(
                List.of(entity("e1", "Recursive Function", "CONCEPT", "A function that calls itself", 0.9)),
                List.of(relation("e1", "e1", "CALLS", "Calls itself", 0.85))
        ));

        UnifiedCrawlJob job = startJobWithGraph(null);
        awaitCompletion(job);

        assertEquals(1, job.getEntitiesExtracted().get());
        assertEquals(1, job.getRelationshipsExtracted().get());
    }

    // ──────────────────────────────────────────────────────────────────
    // 4. CONFIDENCE FILTERING ACROSS ENTITY TYPES
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Mixed confidence: some entities survive threshold, others filtered")
    void mixedConfidence_partialFilter() throws Exception {
        when(loader.load(any(DocumentSourceDescriptor.class), any())).thenReturn(List.of(
                new Document("Alice at Google might be related to TensorFlow. Maybe Bob too.", Map.of())
        ));

        when(callResponseSpec.content()).thenReturn(buildJson(
                List.of(
                        entity("e1", "Alice", "PERSON", "Certain person", 0.95),
                        entity("e2", "Google", "ORGANIZATION", "Certain org", 0.92),
                        entity("e3", "TensorFlow", "TECHNOLOGY", "Somewhat certain", 0.75),
                        entity("e4", "Bob", "PERSON", "Very uncertain", 0.3)
                ),
                List.of(
                        relation("e1", "e2", "WORKS_AT", "Certain rel", 0.9),
                        relation("e1", "e3", "USES", "Somewhat certain", 0.72),
                        relation("e4", "e2", "KNOWS", "Very uncertain", 0.2)
                )
        ));

        UnifiedCrawlJob job = service.startJob(UnifiedCrawlRequest.builder()
                .name("confidence filter")
                .sources(List.of(fileSource("docs")))
                .graphExtraction(GraphExtractionConfig.builder()
                        .enabled(true)
                        .minConfidence(0.7)
                        .build())
                .vectorIndex(VectorIndexConfig.builder().enabled(false).build())
                .build());

        awaitCompletion(job);

        // Alice(0.95), Google(0.92), TensorFlow(0.75) survive; Bob(0.3) filtered
        assertEquals(3, job.getResultGraph().getEntities().size());
        assertFalse(job.getResultGraph().getEntities().stream()
                .anyMatch(e -> "Bob".equals(e.getTitle())));

        // WORKS_AT(0.9), USES(0.72) survive; KNOWS(0.2) filtered
        assertEquals(2, job.getResultGraph().getRelationships().size());
    }

    @Test
    @DisplayName("Very high threshold (0.99) filters almost everything")
    void veryHighThreshold_almostAllFiltered() throws Exception {
        when(loader.load(any(DocumentSourceDescriptor.class), any())).thenReturn(List.of(
                new Document("Some text", Map.of())
        ));

        when(callResponseSpec.content()).thenReturn(buildJson(
                List.of(
                        entity("e1", "Alice", "PERSON", "Person", 0.95),
                        entity("e2", "Acme", "ORGANIZATION", "Company", 0.98)
                ),
                List.of(relation("e1", "e2", "WORKS_AT", "works at", 0.9))
        ));

        UnifiedCrawlJob job = service.startJob(UnifiedCrawlRequest.builder()
                .name("high threshold")
                .sources(List.of(fileSource("docs")))
                .graphExtraction(GraphExtractionConfig.builder()
                        .enabled(true)
                        .minConfidence(0.99)
                        .build())
                .vectorIndex(VectorIndexConfig.builder().enabled(false).build())
                .build());

        awaitCompletion(job);

        assertEquals(0, job.getResultGraph().getEntities().size());
        assertEquals(0, job.getResultGraph().getRelationships().size());
    }

    // ──────────────────────────────────────────────────────────────────
    // 5. EDGE CASES
    // ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Graph with entities but no relationships")
    void entitiesOnly_noRelationships() throws Exception {
        when(loader.load(any(DocumentSourceDescriptor.class), any())).thenReturn(List.of(
                new Document("Alice. Bob. Charlie.", Map.of())
        ));

        when(callResponseSpec.content()).thenReturn(buildJson(
                List.of(
                        entity("e1", "Alice", "PERSON", "Person", 0.9),
                        entity("e2", "Bob", "PERSON", "Person", 0.88),
                        entity("e3", "Charlie", "PERSON", "Person", 0.85)
                ),
                List.of()
        ));

        UnifiedCrawlJob job = startJobWithGraph(null);
        awaitCompletion(job);

        assertEquals(3, job.getEntitiesExtracted().get());
        assertEquals(0, job.getRelationshipsExtracted().get());
        assertTrue(job.getResultGraph().getRelationships().isEmpty());
    }

    @Test
    @DisplayName("LLM returns entities with special characters in names")
    void specialCharacterEntityNames() throws Exception {
        when(loader.load(any(DocumentSourceDescriptor.class), any())).thenReturn(List.of(
                new Document("C++ and .NET Framework are used at O'Reilly Media.", Map.of())
        ));

        // Manually build JSON to handle special chars
        String json = "{\"$schema\":\"kompile-graph-extraction/v1\",\"entities\":["
                + "{\"id\":\"e1\",\"name\":\"C++\",\"type\":\"TECHNOLOGY\",\"description\":\"Language\",\"confidence\":0.9},"
                + "{\"id\":\"e2\",\"name\":\".NET Framework\",\"type\":\"TECHNOLOGY\",\"description\":\"Runtime\",\"confidence\":0.88},"
                + "{\"id\":\"e3\",\"name\":\"O'Reilly Media\",\"type\":\"ORGANIZATION\",\"description\":\"Publisher\",\"confidence\":0.85}"
                + "],\"relations\":[]}";
        when(callResponseSpec.content()).thenReturn(json);

        UnifiedCrawlJob job = startJobWithGraph(null);
        awaitCompletion(job);

        assertEquals(3, job.getEntitiesExtracted().get());
    }

    @Test
    @DisplayName("Multiple documents with interleaved success and failures")
    void multiDoc_interleavedSuccessAndFailure() throws Exception {
        when(loader.load(any(DocumentSourceDescriptor.class), any())).thenReturn(List.of(
                new Document("Doc 1: Alice at Google.", Map.of()),
                new Document("Doc 2: broken text", Map.of()),
                new Document("Doc 3: Bob at Amazon.", Map.of()),
                new Document("Doc 4: also broken", Map.of()),
                new Document("Doc 5: Charlie at Meta.", Map.of())
        ));

        String good1 = buildJson(
                List.of(entity("e1", "Alice", "PERSON", "P", 0.9),
                        entity("e2", "Google", "ORGANIZATION", "O", 0.9)),
                List.of(relation("e1", "e2", "WORKS_AT", "w", 0.85))
        );
        String good3 = buildJson(
                List.of(entity("e3", "Bob", "PERSON", "P", 0.88),
                        entity("e4", "Amazon", "ORGANIZATION", "O", 0.9)),
                List.of(relation("e3", "e4", "WORKS_AT", "w", 0.82))
        );
        String good5 = buildJson(
                List.of(entity("e5", "Charlie", "PERSON", "P", 0.85),
                        entity("e6", "Meta", "ORGANIZATION", "O", 0.87)),
                List.of(relation("e5", "e6", "WORKS_AT", "w", 0.8))
        );

        // Good, bad, good, bad, good
        when(callResponseSpec.content())
                .thenReturn(good1)
                .thenReturn("Not valid JSON at all")
                .thenReturn(good3)
                .thenReturn(null)
                .thenReturn(good5);

        UnifiedCrawlJob job = startJobWithGraph(null);
        awaitCompletion(job);

        assertEquals(UnifiedCrawlJob.Status.COMPLETED, job.getStatus().get());
        // 3 successful docs × 2 entities each = 6
        assertEquals(6, job.getEntitiesExtracted().get());
        // 3 successful docs × 1 relationship each = 3
        assertEquals(3, job.getRelationshipsExtracted().get());
    }

    @Test
    @DisplayName("Large extraction with many entities from single document")
    void largeExtraction_manyEntities() throws Exception {
        when(loader.load(any(DocumentSourceDescriptor.class), any())).thenReturn(List.of(
                new Document("A document mentioning many entities.", Map.of())
        ));

        List<Map<String, Object>> entities = new ArrayList<>();
        List<Map<String, Object>> relations = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            entities.add(entity("e" + i, "Entity" + i, (i % 2 == 0 ? "PERSON" : "ORGANIZATION"), "Desc " + i, 0.8 + (i * 0.005)));
        }
        for (int i = 0; i < 19; i++) {
            relations.add(relation("e" + i, "e" + (i + 1), "RELATES_TO", "Rel " + i, 0.75 + (i * 0.005)));
        }

        when(callResponseSpec.content()).thenReturn(buildJson(entities, relations));

        UnifiedCrawlJob job = startJobWithGraph(null);
        awaitCompletion(job);

        assertEquals(20, job.getEntitiesExtracted().get());
        assertEquals(19, job.getRelationshipsExtracted().get());
    }

    @Test
    @DisplayName("Custom extraction prompt is used when specified")
    void customExtractionPrompt() throws Exception {
        when(loader.load(any(DocumentSourceDescriptor.class), any())).thenReturn(List.of(
                new Document("Some medical text about treatment.", Map.of())
        ));

        when(callResponseSpec.content()).thenReturn(buildJson(
                List.of(entity("e1", "Treatment X", "TREATMENT", "A medical treatment", 0.9)),
                List.of()
        ));

        UnifiedCrawlJob job = service.startJob(UnifiedCrawlRequest.builder()
                .name("custom prompt test")
                .sources(List.of(fileSource("docs")))
                .graphExtraction(GraphExtractionConfig.builder()
                        .enabled(true)
                        .entityTypes(List.of("TREATMENT", "DISEASE", "SYMPTOM"))
                        .customPrompt("Extract medical entities only. Ignore non-medical terms.")
                        .build())
                .vectorIndex(VectorIndexConfig.builder().enabled(false).build())
                .build());

        awaitCompletion(job);

        // Verify LLM was called with a prompt that includes custom instructions
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmChat).prompt(promptCaptor.capture());
        String prompt = promptCaptor.getValue();
        assertTrue(prompt.contains("TREATMENT"));
        assertTrue(prompt.contains("DISEASE"));
        assertTrue(prompt.contains("SYMPTOM"));
        assertTrue(prompt.contains("Extract medical entities only"));
    }

    @Test
    @DisplayName("Relationship types constraint is included in LLM prompt")
    void relationshipTypesInPrompt() throws Exception {
        when(loader.load(any(DocumentSourceDescriptor.class), any())).thenReturn(List.of(
                new Document("Some text", Map.of())
        ));
        when(callResponseSpec.content()).thenReturn(buildJson(List.of(), List.of()));

        service.startJob(UnifiedCrawlRequest.builder()
                .name("rel types test")
                .sources(List.of(fileSource("docs")))
                .graphExtraction(GraphExtractionConfig.builder()
                        .enabled(true)
                        .relationshipTypes(List.of("WORKS_AT", "MANAGES", "REPORTS_TO"))
                        .build())
                .vectorIndex(VectorIndexConfig.builder().enabled(false).build())
                .build());

        // Give async execution time to run
        Thread.sleep(500);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmChat).prompt(promptCaptor.capture());
        String prompt = promptCaptor.getValue();
        assertTrue(prompt.contains("WORKS_AT"));
        assertTrue(prompt.contains("MANAGES"));
        assertTrue(prompt.contains("REPORTS_TO"));
    }

    // ──────────────────────────────────────────────────────────────────
    // HELPERS
    // ──────────────────────────────────────────────────────────────────

    private UnifiedCrawlJob startJobWithGraph(List<String> entityTypes) {
        GraphExtractionConfig.GraphExtractionConfigBuilder gc = GraphExtractionConfig.builder().enabled(true);
        if (entityTypes != null) gc.entityTypes(entityTypes);

        return service.startJob(UnifiedCrawlRequest.builder()
                .name("test")
                .sources(List.of(fileSource("docs")))
                .graphExtraction(gc.build())
                .vectorIndex(VectorIndexConfig.builder().enabled(false).build())
                .build());
    }

    private static UnifiedCrawlSource fileSource(String label) {
        return UnifiedCrawlSource.builder()
                .label(label)
                .sourceType(DocumentSourceDescriptor.SourceType.FILE)
                .pathOrUrl("/data/" + label)
                .build();
    }

    private static Map<String, Object> entity(String id, String name, String type, String desc, double confidence) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("type", type);
        m.put("description", desc);
        m.put("confidence", confidence);
        return m;
    }

    private static Map<String, Object> relation(String source, String target, String type, String desc, double confidence) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", source);
        m.put("target", target);
        m.put("type", type);
        m.put("description", desc);
        m.put("confidence", confidence);
        return m;
    }

    private static String buildJson(List<Map<String, Object>> entities, List<Map<String, Object>> relations) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"$schema\":\"kompile-graph-extraction/v1\",\"entities\":[");
        for (int i = 0; i < entities.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(mapToJson(entities.get(i)));
        }
        sb.append("],\"relations\":[");
        for (int i = 0; i < relations.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(mapToJson(relations.get(i)));
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String mapToJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        int i = 0;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (i++ > 0) sb.append(",");
            sb.append("\"").append(entry.getKey()).append("\":");
            if (entry.getValue() instanceof Number) {
                sb.append(entry.getValue());
            } else {
                String val = entry.getValue().toString()
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"");
                sb.append("\"").append(val).append("\"");
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private static void awaitCompletion(UnifiedCrawlJob job) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            UnifiedCrawlJob.Status status = job.getStatus().get();
            if (status == UnifiedCrawlJob.Status.COMPLETED
                    || status == UnifiedCrawlJob.Status.FAILED
                    || status == UnifiedCrawlJob.Status.CANCELLED) {
                return;
            }
            Thread.sleep(50);
        }
        fail("Job did not complete within 10 seconds. Status: " + job.getStatus().get());
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
