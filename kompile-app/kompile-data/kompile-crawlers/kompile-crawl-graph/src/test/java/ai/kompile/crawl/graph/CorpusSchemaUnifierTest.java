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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.crawl.graph;

import ai.kompile.core.crawl.graph.GraphExtractionConfig;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.crawl.graph.UnifiedCrawlRequest;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.NodeType;
import ai.kompile.core.graphrag.model.schema.RelationshipType;
import ai.kompile.core.graphrag.model.schema.SchemaHierarchyVocabulary;
import ai.kompile.core.llm.StructuredChatLanguageModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CorpusSchemaUnifierTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void derivesDomainSchemaThroughRequiredStructuredTool() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-1");
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        stubTypePasses(
                dispatcher, job,
                nodeTypes("PUBLICATION", "PERSON"),
                relationshipTypes("AUTHORED_BY"),
                List.of(new WitnessSpec("a person", "authored", "Publication",
                        "Publication authored by a person.")),
                List.of(new ConsolidationSpec("AUTHORED_BY", "ATTRIBUTION", "authored")));

        GraphSchema schema = new CorpusSchemaUnifier().unify(
                Map.of(
                        "publication-window", "Publication authored by a person. Author: mei@example.com\nSubject: stellar observation",
                        "archive-window", "Archive record contains a stellar observation"),
                new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                null,
                job,
                "snapshot-1",
                dispatcher);

        assertTrue(schema.getAllNodeLabels().containsAll(
                SchemaHierarchyVocabulary.BASE_ENTITY_TYPES));
        assertTrue(schema.getAllNodeLabels().contains("PUBLICATION"));
        assertEquals("DOCUMENT",
                schema.getNodeTypeMap().get("PUBLICATION").getParentType());
        assertEquals("AUTHORED_BY", schema.getRelationshipTypes().get(0).getType());
        assertEquals("ATTRIBUTION",
                schema.getRelationshipTypes().get(0).getConnectionFamily());
        assertTrue(schema.getPatterns() != null && !schema.getPatterns().isEmpty());

        ArgumentCaptor<StructuredChatLanguageModel.Request> request =
                ArgumentCaptor.forClass(StructuredChatLanguageModel.Request.class);
        verify(dispatcher, times(6)).promptStructuredWithCapacityFallback(
                request.capture(),
                eq("llm"),
                same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class));
        List<StructuredChatLanguageModel.Request> typeRequests = request.getAllValues();
        assertEquals(List.of(
                        CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME,
                        CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME,
                        CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME,
                        CorpusSchemaUnifier.WITNESS_TOOL_NAME,
                        CorpusSchemaUnifier.RELATIONSHIP_TYPE_TOOL_NAME,
                        CorpusSchemaUnifier.ENDPOINT_SIGNATURE_TOOL_NAME),
                typeRequests.stream().map(value -> value.tools().get(0).name()).toList());
        assertTrue(typeRequests.stream().allMatch(value ->
                value.toolChoice() == StructuredChatLanguageModel.ToolChoice.REQUIRED));
        Map<String, Object> topProperties = asMap(
                typeRequests.get(1).tools().get(0).parameters().get("properties"));
        Map<String, Object> nodeType = asMap(
                asMap(topProperties.get("nodeTypes")).get("items"));
        assertEquals("object", nodeType.get("type"));
        Map<String, Object> nodeFields = asMap(nodeType.get("properties"));
        assertEquals(Set.of("label", "parentType", "evidence"), nodeFields.keySet());
        Map<String, Object> evidenceArray = asMap(nodeFields.get("evidence"));
        assertEquals(1, evidenceArray.get("minItems"));
        assertEquals(2, evidenceArray.get("maxItems"));
        Map<String, Object> evidenceFields = asMap(asMap(evidenceArray.get("items")).get("properties"));
        assertEquals(Set.of("sourceId", "quote"), evidenceFields.keySet());
        assertEquals(List.of("s1", "s2"), asMap(evidenceFields.get("sourceId")).get("enum"));
        assertEquals(1024, asMap(evidenceFields.get("quote")).get("maxLength"));
        Map<String, Object> consolidationNode = asMap(asMap(asMap(
                typeRequests.get(2).tools().get(0).parameters().get("properties")).get("nodeTypes")).get("items"));
        assertEquals(Set.of("label", "parentType"), asMap(consolidationNode.get("properties")).keySet());
        assertFalse(typeRequests.get(1).tools().get(0).description().contains("triples, ids"));
        String labelPattern = (String) asMap(nodeFields.get("label")).get("pattern");
        assertEquals("^[A-Z][A-Z0-9_]*(?![\\s\\S])", labelPattern);
        var identifier = java.util.regex.Pattern.compile(labelPattern);
        assertTrue(identifier.matcher("FORECAST_DOCUMENT").find());
        for (String invalid : List.of("PERSON\n", "PERSON\r\n", "PERSON\u2028", "PERSON x")) {
            assertFalse(identifier.matcher(invalid).find(), "Identifier must reject trailing characters");
        }
        assertEquals(48, asMap(nodeFields.get("label")).get("maxLength"));
        assertEquals(SchemaHierarchyVocabulary.BASE_ENTITY_TYPES,
                asMap(nodeFields.get("parentType")).get("enum"));
        assertEquals(true, asMap(topProperties.get("nodeTypes")).get("uniqueItems"));
        assertEquals(32, asMap(topProperties.get("nodeTypes")).get("maxItems"));
        assertEquals(java.util.Set.of("nodeTypes"), topProperties.keySet());
        Map<String, Object> relationshipProperties = asMap(
                typeRequests.get(4).tools().get(0).parameters().get("properties"));
        Map<String, Object> relationshipType = asMap(
                asMap(relationshipProperties.get("relationshipTypes")).get("items"));
        assertEquals("object", relationshipType.get("type"));
        Map<String, Object> relationFields = asMap(relationshipType.get("properties"));
        // Witness consolidation rows carry type + connectionFamily + witnessIds.
        assertEquals(Set.of("type", "connectionFamily", "witnessIds"), relationFields.keySet());
        assertEquals(48, asMap(relationFields.get("type")).get("maxLength"));
        assertEquals(java.util.Set.of("relationshipTypes"), relationshipProperties.keySet());
        assertTrue(typeRequests.get(1).messages().get(1).content().contains("publication-window"));
        // Witness discovery (request 3) is a per-batch windowed prompt over node discovery
        // windows; the archive chunk appears there as window content, not a chunk id.
        assertTrue(typeRequests.get(3).messages().get(1).content().contains("Archive record"),
                typeRequests.get(3).messages().get(1).content());
        assertTrue(typeRequests.get(2).messages().get(1).content().contains(
                "UNTRUSTED BATCH PROPOSALS"));
        // Consolidation prompt now carries the checked witness observations instead of raw
        // proposal batch support.
        assertTrue(typeRequests.get(4).messages().get(1).content().contains(
                "CHECKED RELATIONSHIP OBSERVATIONS"));
    }

    @Test
    void witnessRepetitionReceivesBoundedRepairAndRetainsDeduplicatedEvidence() {
        // Repetition = the CURRENT response re-emits the same observation (duplicate
        // majority), not merely shared predicate wording. Attempt 1 loops on one grounded
        // observation; bounded repair keeps the deduplicated evidence; consolidation then
        // admits the canonical predicate. Shared wording with distinct pairs is legitimate
        // and covered by predicateSingletonSurvivesUnrelatedPassages below.
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("witness-repetition");
        when(job.getRequest()).thenReturn(UnifiedCrawlRequest.builder().maxValidationRetries(1).build());
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        String window = "Sarah Chen submitted the June forecast.";
        List<Map<String, Object>> repeatedRows = java.util.stream.IntStream.rangeClosed(1, 6)
                .mapToObj(index -> Map.<String, Object>of(
                        "sourceId", "s1", "subject", "Sarah Chen", "predicateText", "submitted",
                        "object", "June forecast", "quote", window, "qualifier", ""))
                .toList();
        List<WitnessSpec> repaired = List.of(new WitnessSpec(
                "Sarah Chen", "submitted", "June forecast", window));
        java.util.concurrent.atomic.AtomicBoolean firstWitnessAttempt =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class), eq("llm"), same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenAnswer(invocation -> {
                    StructuredChatLanguageModel.Request request = invocation.getArgument(0);
                    String tool = request.tools().get(0).name();
                    if (CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME.equals(tool)) {
                        return structuredSchemaResponse(
                                CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME,
                                Map.of("classifications", List.of()));
                    }
                    if (CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME.equals(tool)) {
                        return withDiscoveryEvidence(request, nodeTypes());
                    }
                    if (CorpusSchemaUnifier.WITNESS_TOOL_NAME.equals(tool)) {
                        return firstWitnessAttempt.getAndSet(false)
                                ? structuredSchemaResponse(CorpusSchemaUnifier.WITNESS_TOOL_NAME,
                                Map.of("witnesses", repeatedRows))
                                : witnessDiscoveryResponse(request, repaired);
                    }
                    if (CorpusSchemaUnifier.RELATIONSHIP_TYPE_TOOL_NAME.equals(tool)) {
                        return witnessConsolidationResponse(request,
                                List.of(new ConsolidationSpec("SUBMITTED", "COMMUNICATION", "submitted")));
                    }
                    if (CorpusSchemaUnifier.ENDPOINT_SIGNATURE_TOOL_NAME.equals(tool)) {
                        return endpointSignatureResponse(request);
                    }
                    throw new AssertionError("Unexpected schema tool: " + tool);
                });

        GraphSchema schema = new CorpusSchemaUnifier().unify(
                Map.of("window", window),
                new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                null, job, "witness-repetition", dispatcher);

        assertTrue(schema.getAllRelationshipTypes().contains("SUBMITTED"),
                "Repaired deduplicated evidence must reach consolidation and freeze");
        ArgumentCaptor<StructuredChatLanguageModel.Request> requests =
                ArgumentCaptor.forClass(StructuredChatLanguageModel.Request.class);
        // classification + node discovery + witness attempt 1 (repetition)
        // + witness attempt 2 (repair) + consolidation + signatures = 6 calls
        // (node consolidation is skipped when node discovery committed directly).
        verify(dispatcher, times(6)).promptStructuredWithCapacityFallback(
                requests.capture(), eq("llm"), same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class));
        long witnessCalls = requests.getAllValues().stream()
                .filter(request -> CorpusSchemaUnifier.WITNESS_TOOL_NAME.equals(
                        request.tools().get(0).name())).count();
        assertEquals(2, witnessCalls,
                "Repetition receives exactly one bounded repair re-ask");
    }

    @Test
    void predicateSingletonSurvivesUnrelatedPassages() {
        // One clear witness is sufficient support; adding unrelated passages afterwards
        // must not invalidate it. The canonical label need not occur lexically in the source
        // ("sent by" canonicalizes to SENT_BY).
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("witness-singleton");
        when(job.getRequest()).thenReturn(UnifiedCrawlRequest.builder().maxValidationRetries(1).build());
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        stubTypePasses(dispatcher, job, nodeTypes(), null,
                List.of(new WitnessSpec("Sarah Chen", "sent by", "June forecast",
                        "The June forecast was sent by Sarah Chen.")),
                List.of(new ConsolidationSpec("SENT_BY", "ATTRIBUTION", "sent by")));
        GraphSchema schema = new CorpusSchemaUnifier().unify(
                Map.of("window", "The June forecast was sent by Sarah Chen."),
                new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                null, job, "witness-singleton", dispatcher);

        assertTrue(schema.getAllRelationshipTypes().contains("SENT_BY"),
                "A singleton witness must freeze its canonical predicate");
    }

    @Test
    void distinctRelationshipsBetweenSamePairAreNotRepetition() {
        // Shared endpoint pair with different predicates is legitimate: the two witnesses
        // both survive and both canonical predicates freeze.
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("witness-same-pair");
        when(job.getRequest()).thenReturn(UnifiedCrawlRequest.builder().maxValidationRetries(1).build());
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        stubTypePasses(dispatcher, job, nodeTypes(), null,
                List.of(new WitnessSpec("Mei Chen", "approved", "the close",
                        "Mei Chen approved the close."),
                        new WitnessSpec("Mei Chen", "validated", "the close",
                                "Mei Chen validated the close.")),
                List.of(new ConsolidationSpec("APPROVED", "ATTRIBUTION", "approved"),
                        new ConsolidationSpec("VALIDATED", "PARTICIPATION", "validated")));

        GraphSchema schema = new CorpusSchemaUnifier().unify(
                Map.of("window", "Mei Chen approved the close. Mei Chen validated the close."),
                new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                null, job, "witness-same-pair", dispatcher);

        assertTrue(schema.getAllRelationshipTypes().contains("APPROVED"));
        assertTrue(schema.getAllRelationshipTypes().contains("VALIDATED"));
    }

    @Test
    void validEmptyWitnessResponseIsNotRetriedAndFreezesClosedVocabulary() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("witness-valid-empty");
        when(job.getRequest()).thenReturn(UnifiedCrawlRequest.builder().maxValidationRetries(1).build());
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class), eq("llm"), same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenReturn(structuredSchemaResponse(CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME,
                        Map.of("classifications", List.of())))
                .thenAnswer(invocation -> withDiscoveryEvidence(invocation.getArgument(0), nodeTypes()))
                .thenReturn(structuredSchemaResponse(CorpusSchemaUnifier.WITNESS_TOOL_NAME,
                        Map.of("witnesses", List.of())));

        GraphSchema schema = new CorpusSchemaUnifier().unify(
                Map.of("window", "A quiet archive holds records."),
                new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                null, job, "witness-valid-empty", dispatcher);

        assertTrue(schema.getAllRelationshipTypes().isEmpty(),
                "Valid empty discovery must freeze an explicitly closed vocabulary");
        ArgumentCaptor<StructuredChatLanguageModel.Request> requests =
                ArgumentCaptor.forClass(StructuredChatLanguageModel.Request.class);
        verify(dispatcher, times(3)).promptStructuredWithCapacityFallback(
                requests.capture(), eq("llm"), same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class));
    }

    @Test
    void invalidGenericRelationshipDoesNotDiscardValidSibling() {
        // Under the witness pipeline this guarantee lives at consolidation: a rejected
        // generic/invalid predicate row must not discard its valid sibling in the same
        // response. maxValidationRetries=0 means the invalid row is simply dropped.
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("partial-relationship");
        when(job.getRequest()).thenReturn(UnifiedCrawlRequest.builder().maxValidationRetries(0).build());
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        String window = "The observation uses an instrument.";
        when(dispatcher.promptStructuredWithCapacityFallback(any(StructuredChatLanguageModel.Request.class),
                eq("llm"), same(job), any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenReturn(structuredSchemaResponse(CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME, Map.of("classifications",List.of())))
                .thenReturn(nodeTypes())
                .thenAnswer(invocation -> witnessDiscoveryResponse(invocation.getArgument(0),
                        List.of(new WitnessSpec("observation", "uses", "instrument", window))))
                .thenAnswer(invocation -> {
                    // One response carries a rejected generic row and the valid sibling;
                    // per-row isolation must retain USES_INSTRUMENT and drop ASSOCIATION
                    // (generic placeholder) without failing the batch.
                    List<Map<String, Object>> checked = checkedWitnesses(invocation.getArgument(0));
                    List<String> ids = checked.stream()
                            .map(witness -> String.valueOf(witness.get("witnessId")))
                            .toList();
                    return structuredSchemaResponse(CorpusSchemaUnifier.RELATIONSHIP_TYPE_TOOL_NAME,
                            Map.of("relationshipTypes", List.of(
                                    Map.of("type", "KEYWORD", "connectionFamily", "REFERENCE",
                                            "witnessIds", ids),
                                    Map.of("type", "USES_INSTRUMENT", "connectionFamily", "DEPENDENCY",
                                            "witnessIds", ids))));
                })
                .thenAnswer(invocation -> endpointSignatureResponse(invocation.getArgument(0)));
        var schema = new CorpusSchemaUnifier().unify(Map.of("window", window),
                new CorpusSchemaCandidates.Inventory(List.of(),List.of()),null,job,"partial-relationship",dispatcher);
        assertTrue(schema.getAllRelationshipTypes().contains("USES_INSTRUMENT"));
        assertFalse(schema.getAllRelationshipTypes().contains("KEYWORD"));
    }

    /** Extracts the checked witness rows from a consolidation prompt for citation building. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> checkedWitnesses(
            StructuredChatLanguageModel.Request request) {
        String prompt = request.messages().get(request.messages().size() - 1).content();
        String marker = "CHECKED RELATIONSHIP OBSERVATIONS";
        int start = prompt.indexOf(marker);
        assertTrue(start >= 0, prompt);
        String witnessJson = prompt.substring(start)
                .substring(prompt.substring(start).indexOf('['));
        int jsonEnd = witnessJson.indexOf("\n\n");
        try {
            return (List<Map<String, Object>>) (List<?>) MAPPER.readValue(
                    witnessJson.substring(0, jsonEnd < 0 ? witnessJson.length() : jsonEnd).trim(),
                    List.class);
        } catch (java.io.IOException failure) {
            throw new AssertionError("Unable to parse consolidation witness block", failure);
        }
    }

    @Test
    void nonLexicalAbstractionRequiresExplicitSemanticSelectionAndCannotFallback() {
        for (String decision : List.of("accept", "abstain", "invalid")) {
            CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
            UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
            when(job.getJobId()).thenReturn("abstraction-review");
            when(job.getRequest()).thenReturn(UnifiedCrawlRequest.builder().maxValidationRetries(0).build());
            when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
            Map<String,Object> definition = Map.of("label", "FINANCIAL_DOCUMENT", "parentType", "DOCUMENT");
            var review = decision.equals("abstain") ? nodeTypes()
                    : structuredSchemaResponse(CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME, Map.of("nodeTypes", List.of(
                            decision.equals("accept") ? definition : Map.of("label", "RENAMED_DOCUMENT", "parentType", "DOCUMENT"))));
            when(dispatcher.promptStructuredWithCapacityFallback(any(StructuredChatLanguageModel.Request.class),
                    eq("llm"), same(job), any(CrawlLlmDispatcher.LlmCallScope.class)))
                    .thenReturn(structuredSchemaResponse(CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME, Map.of("classifications", List.of())))
                    .thenReturn(structuredSchemaResponse(CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME, Map.of("nodeTypes", List.of(Map.of(
                            "label", "FINANCIAL_DOCUMENT", "parentType", "DOCUMENT", "evidence", List.of(Map.of(
                                    "sourceId", "s1", "quote", "The finance team submitted a forecast.")))))))
                    .thenAnswer(invocation -> {
                        StructuredChatLanguageModel.Request request = invocation.getArgument(0);
                        String prompt = request.messages().get(1).content();
                        assertTrue(prompt.contains("The finance team submitted a forecast."));
                        assertTrue(prompt.contains("\"labelWordsPresent\" : false"));
                        assertTrue(prompt.contains("Distinguish a category from a named instance"));
                        return review;
                    }).thenReturn(relationshipTypes());
            if (decision.equals("invalid")) {
                assertThrows(IllegalStateException.class, () -> new CorpusSchemaUnifier().unify(
                        Map.of("window", "The finance team submitted a forecast."), new CorpusSchemaCandidates.Inventory(List.of(),List.of()),
                        null, job, "abstraction-review", dispatcher));
            } else {
                var schema = new CorpusSchemaUnifier().unify(Map.of("window", "The finance team submitted a forecast."),
                        new CorpusSchemaCandidates.Inventory(List.of(),List.of()), null, job, "abstraction-review", dispatcher);
                assertEquals(decision.equals("accept"),schema.getAllNodeLabels().contains("FINANCIAL_DOCUMENT"));
            }
        }
    }

    @Test
    void retainsValidNovelCandidatesWhenSiblingFailsOrRetryOmitsThem() {
        for (boolean corrected : List.of(false, true)) {
            CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
            UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
            when(job.getJobId()).thenReturn("partial-novel");
            when(job.getRequest()).thenReturn(UnifiedCrawlRequest.builder().maxValidationRetries(corrected ? 1 : 0).build());
            when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
            var responses = when(dispatcher.promptStructuredWithCapacityFallback(any(StructuredChatLanguageModel.Request.class),
                    eq("llm"), same(job), any(CrawlLlmDispatcher.LlmCallScope.class)))
                    .thenReturn(structuredSchemaResponse(CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME,
                            Map.of("classifications", List.of())))
                    .thenReturn(structuredSchemaResponse(CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME, Map.of("nodeTypes", List.of(
                            Map.of("label", "OBSERVATION", "parentType", "ACTIVITY", "evidence",
                                    List.of(Map.of("sourceId", "s1", "quote", "observation"))),
                            Map.of("label", "PUBLICATION", "parentType", "DOCUMENT", "evidence",
                                    List.of(Map.of("sourceId", "s1", "quote", "invented")))))));
            if (corrected) responses = responses.thenReturn(structuredSchemaResponse(CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME,
                    Map.of("nodeTypes", List.of(Map.of("label", "PUBLICATION", "parentType", "DOCUMENT", "evidence",
                            List.of(Map.of("sourceId", "s1", "quote", "publication")))))));
            responses.thenReturn(corrected ? nodeTypes("OBSERVATION", "PUBLICATION") : nodeTypes("OBSERVATION"))
                    .thenAnswer(invocation -> witnessDiscoveryResponse(invocation.getArgument(0), List.of()));
            GraphSchema frozen = new CorpusSchemaUnifier().unify(Map.of("window", "An observation and publication were recorded."),
                    new CorpusSchemaCandidates.Inventory(List.of(), List.of()), null, job, "partial-novel", dispatcher);
            assertEquals("ACTIVITY", frozen.getNodeTypeMap().get("OBSERVATION").getParentType());
            assertEquals(corrected, frozen.getAllNodeLabels().contains("PUBLICATION"));
            // Actual protocol (traced): classification + node discovery (+1 repair when
            // INVALID) + node consolidation + witness discovery (abstain) = 4, or 5 when the
            // first node attempt is invalid and needs the bounded repair.
            verify(dispatcher, times(corrected ? 5 : 4)).promptStructuredWithCapacityFallback(any(StructuredChatLanguageModel.Request.class),
                    eq("llm"), same(job), any(CrawlLlmDispatcher.LlmCallScope.class));
        }
    }

    @Test
    void incompleteToolEnvelopeCannotContributeCandidates() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("incomplete-envelope");
        when(job.getRequest()).thenReturn(UnifiedCrawlRequest.builder().maxValidationRetries(0).build());
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        var complete = structuredSchemaResponse(CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME, Map.of("nodeTypes", List.of(
                Map.of("label", "OBSERVATION", "parentType", "ACTIVITY", "evidence",
                        List.of(Map.of("sourceId", "s1", "quote", "observation"))))));
        when(dispatcher.promptStructuredWithCapacityFallback(any(StructuredChatLanguageModel.Request.class),
                eq("llm"), same(job), any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenReturn(structuredSchemaResponse(CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME,
                        Map.of("classifications", List.of())))
                .thenReturn(new StructuredChatLanguageModel.Response("partial", "", complete.toolCalls(),
                        List.of("incomplete XML tool-call envelope")));
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new CorpusSchemaUnifier().unify(
                Map.of("window", "An observation was recorded."), new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                null, job, "incomplete-envelope", dispatcher));
        assertTrue(error.getMessage().contains("incomplete XML"));
    }

    @Test
    void authoritativeRestatementCannotOverwriteParentOrDiscardNovelType() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("baseline-noop");
        when(job.getRequest()).thenReturn(UnifiedCrawlRequest.builder().maxValidationRetries(0).build());
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        when(dispatcher.promptStructuredWithCapacityFallback(any(StructuredChatLanguageModel.Request.class),
                eq("llm"), same(job), any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenReturn(structuredSchemaResponse(CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME,
                        Map.of("classifications", List.of())))
                .thenReturn(structuredSchemaResponse(CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME, Map.of("nodeTypes", List.of(
                        Map.of("label", "DOCUMENT", "parentType", "PERSON", "evidence",
                                List.of(Map.of("sourceId", "missing", "quote", "fabricated"))),
                        Map.of("label", "OBSERVATION", "parentType", "ACTIVITY", "evidence",
                                List.of(Map.of("sourceId", "s1", "quote", "observation")))))))
                .thenReturn(nodeTypes("OBSERVATION"))
                .thenAnswer(invocation -> witnessDiscoveryResponse(invocation.getArgument(0), List.of()));
        GraphSchema frozen = new CorpusSchemaUnifier().unify(Map.of("window", "An observation was recorded."),
                new CorpusSchemaCandidates.Inventory(List.of(), List.of()), null, job, "baseline-noop", dispatcher);
        assertEquals("CREATIVE_WORK", frozen.getNodeTypeMap().get("DOCUMENT").getParentType());
        assertEquals("ACTIVITY", frozen.getNodeTypeMap().get("OBSERVATION").getParentType());
        verify(dispatcher, times(4)).promptStructuredWithCapacityFallback(any(StructuredChatLanguageModel.Request.class),
                eq("llm"), same(job), any(CrawlLlmDispatcher.LlmCallScope.class));
    }

    @Test
    void fabricatedEvidenceUsesExistingRepairBudgetBeforeConsolidationAndFreeze() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("evidence-repair");
        when(job.getRequest()).thenReturn(UnifiedCrawlRequest.builder().maxValidationRetries(1).build());
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        when(dispatcher.promptStructuredWithCapacityFallback(any(StructuredChatLanguageModel.Request.class),
                eq("llm"), same(job), any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenReturn(structuredSchemaResponse(CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME,
                        Map.of("classifications", List.of())))
                .thenReturn(structuredSchemaResponse(CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME, Map.of("nodeTypes", List.of(
                        Map.of("label", "OBSERVATION", "parentType", "ACTIVITY", "evidence",
                                List.of(Map.of("sourceId", "s1", "quote", "fabricated evidence")))))))
                .thenAnswer(invocation -> withDiscoveryEvidence(invocation.getArgument(0), nodeTypes("OBSERVATION")))
                .thenReturn(nodeTypes("OBSERVATION"))
                .thenAnswer(invocation -> witnessDiscoveryResponse(invocation.getArgument(0), List.of()));
        GraphSchema frozen = new CorpusSchemaUnifier().unify(Map.of("window", "An observation was recorded."),
                new CorpusSchemaCandidates.Inventory(List.of(), List.of()), null, job, "repair-evidence", dispatcher);
        assertEquals("ACTIVITY", frozen.getNodeTypeMap().get("OBSERVATION").getParentType());
        ArgumentCaptor<StructuredChatLanguageModel.Request> requests = ArgumentCaptor.forClass(StructuredChatLanguageModel.Request.class);
        // Actual protocol (traced): classification + node discovery + node consolidation +
        // witness discovery (abstain) = 4 for the repaired path; 5 when the first node
        // attempt is invalid and consumes a bounded repair.
        verify(dispatcher, times(5)).promptStructuredWithCapacityFallback(requests.capture(), eq("llm"), same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class));
        String repair = requests.getAllValues().get(2).messages().get(1).content();
        assertTrue(repair.contains("SCHEMA_NODE_EVIDENCE"));
        assertTrue(repair.contains("nodeTypes[0].evidence[0]"));
        assertTrue(repair.contains("label=\"OBSERVATION\""));
        assertTrue(repair.contains("quote=\"fabricated evidence\""));
        assertTrue(repair.contains("QUOTE_NOT_IN_CITED_WINDOW"));
        assertTrue(repair.contains("untrusted diagnostic data"));
        assertTrue(requests.getAllValues().get(3).messages().get(1).content().contains("UNTRUSTED BATCH PROPOSALS"));
        assertTrue(requests.getAllValues().get(4).messages().get(1).content().contains("OBSERVATION"));
    }

    @Test
    void repeatedEvidenceCountsOnceAndLexicalSupportIsVisibleToSemanticReview() {
        for (int supportCase : List.of(0, 1, 2)) {
            boolean supported = supportCase != 0;
            boolean topicException = supportCase == 2;
            CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
            UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
            when(job.getJobId()).thenReturn("duplicate-evidence");
            when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
            when(dispatcher.promptStructuredWithCapacityFallback(any(StructuredChatLanguageModel.Request.class),
                    eq("llm"), same(job), any(CrawlLlmDispatcher.LlmCallScope.class)))
                    .thenAnswer(invocation -> {
                        StructuredChatLanguageModel.Request request = invocation.getArgument(0);
                        if (request.tools().get(0).name().equals(CorpusSchemaUnifier.WITNESS_TOOL_NAME)) return witnessDiscoveryResponse(request, List.of());
                        if (request.tools().get(0).name().equals(CorpusSchemaUnifier.RELATIONSHIP_TYPE_TOOL_NAME)) return witnessConsolidationResponse(request, List.of());
                        if (request.tools().get(0).name().equals(CorpusSchemaUnifier.TOPIC_BINDING_TOOL_NAME)) {
                            // A valid zero binding is authoritative and skips discovery; this case
                            // deliberately exercises the existing unbound-topic discovery fallback.
                            return structuredSchemaResponse(CorpusSchemaUnifier.TOPIC_BINDING_TOOL_NAME,
                                    Map.of("b", "invalid-binding"));
                        }
                        String prompt = request.messages().get(1).content();
                        if (prompt.contains("UNTRUSTED BATCH PROPOSALS")) {
                            assertTrue(prompt.contains("\"labelWordsPresent\" : " + supported), prompt);
                            assertTrue(prompt.contains("UNTRUSTED SOURCE EXAMPLES"), prompt);
                            assertTrue(prompt.contains("\"batchSupport\" : 1"), prompt);
                            assertFalse(prompt.contains("\"batchSupport\" : 2"), prompt);
                            return nodeTypes("OBSERVATION");
                        }
                        var cited = withDiscoveryEvidence(request, nodeTypes("OBSERVATION"));
                        Map<String, Object> row = new LinkedHashMap<>(asMap(((List<?>) cited.toolCalls().get(0).arguments().get("nodeTypes")).get(0)));
                        Object span = ((List<?>) row.get("evidence")).get(0);
                        row.put("evidence", List.of(span, span));
                        return structuredSchemaResponse(CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME, Map.of("nodeTypes", List.of(row, row)));
                    });
            Map<String, String> passages = new LinkedHashMap<>();
            passages.put("a", "An observation was recorded.");
            passages.put("b", supported && !topicException ? "Another observation was recorded." : "A telescope was used.");
            passages.put("c", "The archive was opened.");
            passages.put("d", "A record was stored.");
            GraphSchema configured = new GraphSchema(List.of(new NodeType("ARCHIVE", "Configured archive", null)), null, null);
            CorpusTopicEvidence topics = topicException ? new CorpusTopicEvidence("test-encoder", 3, 0.5, 0,
                    List.of(new CorpusTopicEvidence.Topic("topic-1", List.of("doc"), List.of("a", "b", "c", "d"), List.of("a"),
                            Map.of("en", 1), Map.of("en", List.of("observation"))))) : CorpusTopicEvidence.empty();
            GraphSchema frozen = new CorpusSchemaUnifier().unify(passages,
                    new CorpusSchemaCandidates.Inventory(List.of(), List.of()), configured, null, topics, job, "duplicates", dispatcher);
            assertTrue(frozen.getAllNodeLabels().contains("OBSERVATION"), "Reviewer explicitly selected the source-backed category");
            assertEquals(Set.of("ARCHIVE"), configured.getAllNodeLabels());
            verify(dispatcher, times(topicException ? 5 : 4)).promptStructuredWithCapacityFallback(
                    any(StructuredChatLanguageModel.Request.class), eq("llm"), same(job), any(CrawlLlmDispatcher.LlmCallScope.class));
        }
    }

    @Test
    void ignoresRepeatedBaselineTypesBeforeValidatingNovelProposals() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-baseline-repetition");
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        StructuredChatLanguageModel.Response repeatedBaseline = structuredSchemaResponse(
                CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME,
                Map.of("nodeTypes", List.of(
                        Map.of("label", "PERSON", "parentType", "CONCEPT"),
                        Map.of("label", "EVENT", "parentType", "CREATIVE_WORK"),
                        Map.of("label", "OBSERVATION", "parentType", "ACTIVITY"))));
        when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class),
                eq("llm"), same(job), any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenReturn(structuredSchemaResponse(CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME,
                        Map.of("classifications", List.of())))
                .thenAnswer(invocation -> withDiscoveryEvidence(invocation.getArgument(0), repeatedBaseline))
                .thenReturn(nodeTypes("OBSERVATION"))
                .thenReturn(relationshipTypes());

        GraphSchema schema = new CorpusSchemaUnifier().unify(
                Map.of("window", "Astronomers recorded an observation with a telescope."),
                new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                null, job, "snapshot-baseline-repetition", dispatcher);

        assertEquals("ACTIVITY", schema.getNodeTypeMap().get("OBSERVATION").getParentType());
        assertEquals(null, schema.getNodeTypeMap().get("PERSON").getParentType());
        assertEquals(null, schema.getNodeTypeMap().get("EVENT").getParentType());
        verify(dispatcher, times(4)).promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class), eq("llm"), same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class));
    }

    @Test
    void reportsBaselineOnlyRepetitionWithoutClaimingDiscoveryOrRetrying() {
        assertDiscoveryOutcome(List.of(
                Map.of("label", "PERSON", "parentType", "PERSON"),
                Map.of("label", "ORGANIZATION", "parentType", "ORGANIZATION")),
                "AUTHORITATIVE_ONLY", 2, 0);
    }

    @Test
    void reportsExplicitEmptyAsLegitimateAbstention() {
        assertDiscoveryOutcome(List.of(), "EXPLICIT_EMPTY", 0, 0);
    }

    @Test
    void reportsMixedBaselineAndNovelCandidatesWithoutChangingCompatibility() {
        assertDiscoveryOutcome(List.of(
                Map.of("label", "PERSON", "parentType", "PERSON"),
                Map.of("label", "OBSERVATION", "parentType", "ACTIVITY")),
                "MIXED_PROPOSALS", 1, 1);
    }

    @Test
    void reportsNovelSelfParentAsInvalidRatherThanEmptyOrSuccessful() {
        assertDiscoveryOutcome(List.of(
                Map.of("label", "OBSERVATION", "parentType", "OBSERVATION")),
                "INVALID", 0, 1);
    }

    private void assertDiscoveryOutcome(
            List<Map<String, String>> responseTypes, String outcome, int repeated, int novel) {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-discovery-outcome");
        when(job.getRequest()).thenReturn(UnifiedCrawlRequest.builder()
                .maxValidationRetries(0).build());
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        var calls = when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class), eq("llm"), same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenReturn(structuredSchemaResponse(CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME,
                        Map.of("classifications", List.of())))
                .thenAnswer(invocation -> withDiscoveryEvidence(invocation.getArgument(0),
                        structuredSchemaResponse(CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME,
                                Map.of("nodeTypes", responseTypes))))
                .thenAnswer(invocation -> {
                    // Node consolidation for MIXED_PROPOSALS, then relationship witness
                    // discovery: both follow node discovery in the real call order.
                    StructuredChatLanguageModel.Request request = invocation.getArgument(0);
                    if (CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME.equals(
                            request.tools().get(0).name())) {
                        return nodeTypes(outcome.equals("MIXED_PROPOSALS") ? "OBSERVATION" : "");
                    }
                    if (CorpusSchemaUnifier.WITNESS_TOOL_NAME.equals(
                            request.tools().get(0).name())) {
                        return witnessDiscoveryResponse(request, List.of());
                    }
                    throw new AssertionError("Unexpected follow-up tool: "
                            + request.tools().get(0).name());
                });

        Logger logger = (Logger) LoggerFactory.getLogger(CorpusSchemaUnifier.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        try {
            GraphSchema configured = new GraphSchema(
                    List.of(new NodeType("ARCHIVE", "An archive.", null)), List.of(), null);
            java.util.function.Supplier<GraphSchema> unify = () -> new CorpusSchemaUnifier().unify(
                    Map.of("window", "An archive records an observation by a person at an organization."),
                    new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                    configured, job, "snapshot-outcome", dispatcher);
            if (outcome.equals("INVALID")) {
                IllegalStateException failure = assertThrows(IllegalStateException.class, unify::get);
                assertTrue(failure.getMessage().contains("SCHEMA_PARENT_CYCLE"));
            } else {
                GraphSchema schema = unify.get();
                assertTrue(schema.getAllNodeLabels().containsAll(SchemaHierarchyVocabulary.BASE_ENTITY_TYPES));
                assertTrue(schema.getAllNodeLabels().contains("ARCHIVE"));
                assertEquals(null, schema.getNodeTypeMap().get("PERSON").getParentType());
                assertEquals(null, schema.getNodeTypeMap().get("ORGANIZATION").getParentType());
                assertEquals(outcome.equals("MIXED_PROPOSALS"),
                        schema.getAllNodeLabels().contains("OBSERVATION"));
                Set<String> expectedLabels = new java.util.LinkedHashSet<>(
                        SchemaHierarchyVocabulary.BASE_ENTITY_TYPES);
                expectedLabels.add("ARCHIVE");
                if (outcome.equals("MIXED_PROPOSALS")) {
                    expectedLabels.add("OBSERVATION");
                    assertEquals("ACTIVITY", schema.getNodeTypeMap().get("OBSERVATION").getParentType());
                }
                assertEquals(expectedLabels, schema.getAllNodeLabels());
                assertTrue(schema.getRelationshipTypes().isEmpty());
            }
            List<String> diagnostics = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.contains("[SCHEMA_DISCOVERY_OUTCOME]"))
                    .toList();
            // Relationship design-discovery is superseded by the witness pipeline; only the
            // node pass emits SCHEMA_DISCOVERY_OUTCOME diagnostics now.
            assertEquals(1, diagnostics.size());
            String diagnostic = diagnostics.get(0);
            assertTrue(diagnostic.contains("pass=NODE_TYPES"), diagnostic);
            assertTrue(diagnostic.contains("snapshot=snapshot-outcome"), diagnostic);
            assertTrue(diagnostic.contains("batch=1 attempt=1 outcome=" + outcome + " "), diagnostic);
            assertTrue(diagnostic.contains("returned=" + responseTypes.size()
                    + " repeatedAuthoritative=" + repeated + " novelCandidates=" + novel
                    + " bootstrapCandidates="), diagnostic);
            assertEquals(!outcome.equals("INVALID"), diagnostic.endsWith("validationErrors=[]"));
            // Under the witness pipeline the node consolidation no longer needs a second
            // node-discovery-style call for relationship design; the witness discovery call
            // is the last schema pass here (valid empty abstain).
            verify(dispatcher, times(outcome.equals("INVALID") ? 2
                    : outcome.equals("MIXED_PROPOSALS") ? 4 : 3))
                    .promptStructuredWithCapacityFallback(any(StructuredChatLanguageModel.Request.class),
                            eq("llm"), same(job), any(CrawlLlmDispatcher.LlmCallScope.class));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }

    @Test
    void rejectsConnectionFamilyPredicatesAndRepairsTheDiscoveryResponse() {
        // Family validation now happens at witness consolidation: a predicate named after
        // its own family (CAUSATION/CAUSATION) is rejected per-row while the valid sibling
        // is retained, and the bounded repair carries the family diagnostic.
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-family-repetition");
        when(job.getRequest()).thenReturn(UnifiedCrawlRequest.builder()
                .maxValidationRetries(1).build());
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        String window = "The observation uses an instrument.";
        List<WitnessSpec> witnesses = List.of(new WitnessSpec(
                "observation", "uses", "instrument", window));
        when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class),
                eq("llm"), same(job), any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenReturn(structuredSchemaResponse(CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME,
                        Map.of("classifications", List.of())))
                .thenReturn(nodeTypes())
                .thenAnswer(invocation -> witnessDiscoveryResponse(invocation.getArgument(0), witnesses))
                .thenAnswer(invocation -> {
                    // Attempt 1: family-name-as-predicate row + valid sibling.
                    List<Map<String, Object>> checked = checkedWitnesses(invocation.getArgument(0));
                    List<String> ids = checked.stream()
                            .map(witness -> String.valueOf(witness.get("witnessId")))
                            .toList();
                    return structuredSchemaResponse(CorpusSchemaUnifier.RELATIONSHIP_TYPE_TOOL_NAME,
                            Map.of("relationshipTypes", List.of(
                                    Map.of("type", "CAUSATION", "connectionFamily", "CAUSATION",
                                            "witnessIds", ids),
                                    Map.of("type", "USES_INSTRUMENT", "connectionFamily", "DEPENDENCY",
                                            "witnessIds", ids))));
                })
                .thenAnswer(invocation -> witnessConsolidationResponse(invocation.getArgument(0),
                        List.of(new ConsolidationSpec("USES_INSTRUMENT", "DEPENDENCY", "uses"))))
                .thenAnswer(invocation -> endpointSignatureResponse(invocation.getArgument(0)));

        GraphSchema schema = new CorpusSchemaUnifier().unify(
                Map.of("window", window),
                new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                null, job, "snapshot-family-repetition", dispatcher);

        assertEquals(java.util.Set.of("USES_INSTRUMENT"), schema.getAllRelationshipTypes());
        ArgumentCaptor<StructuredChatLanguageModel.Request> requests =
                ArgumentCaptor.forClass(StructuredChatLanguageModel.Request.class);
        verify(dispatcher, times(6)).promptStructuredWithCapacityFallback(
                requests.capture(), eq("llm"), same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class));
        assertTrue(requests.getAllValues().stream()
                        .anyMatch(value -> value.messages().get(1).content()
                                .contains("CAUSATION")),
                "repair feedback must cite the family-as-predicate rejection");
    }

    @Test
    void invalidGenericModelSchemaFailsInsteadOfReturningExtractorCategories() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-2");
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        stubTypePasses(dispatcher, job, nodeTypes("KEYWORD"), relationshipTypes());

        CorpusSchemaCandidates.Inventory fallbackInventory =
                new CorpusSchemaCandidates.Inventory(
                        List.of(new CorpusSchemaCandidates.NodeCandidate(
                                "mei chen",
                                List.of("Mei Chen"),
                                List.of("PERSON"),
                                1,
                                0.95d,
                                List.of("window"),
                                List.of("Mei Chen authored the publication."))),
                        List.of());

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new CorpusSchemaUnifier().unify(
                        Map.of("window", "Mei Chen authored the publication."),
                        fallbackInventory,
                        null,
                        job,
                        "snapshot-2",
                        dispatcher));

        assertTrue(failure.getMessage().contains("SCHEMA_GENERIC_TYPE"));
    }

    @Test
    void invalidModelOverlayFailsInsteadOfReturningConfiguredSchema() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-configured-fallback");
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        stubTypePasses(dispatcher, job, nodeTypes("SOURCE_TYPE"), relationshipTypes());

        GraphSchema configured = new GraphSchema(
                List.of(
                        new NodeType("PERSON", "A named person.", null),
                        new NodeType("COMPANY", "A named company.", null)),
                List.of(new RelationshipType(
                        "WORKS_AT", "A person works at a company.", null)),
                List.of("(PERSON)-[:WORKS_AT]->(COMPANY)"));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new CorpusSchemaUnifier().unify(
                        Map.of("window", "Alice works at Acme."),
                        new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                        configured,
                        job,
                        "snapshot-configured-fallback",
                        dispatcher));

        assertTrue(failure.getMessage().contains("SCHEMA_GENERIC_TYPE"));
    }

    @Test
    void retriesInvalidNodeTypesWithValidatorFeedbackThenRunsRelationshipPass() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-schema-repair");
        when(job.getRequest()).thenReturn(UnifiedCrawlRequest.builder()
                .maxValidationRetries(2)
                .build());
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);

        when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class),
                eq("llm"),
                same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenAnswer(invocation -> {
                    StructuredChatLanguageModel.Request request = invocation.getArgument(0);
                    if (CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME.equals(
                            request.tools().get(0).name())) {
                        return structuredSchemaResponse(
                                CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME,
                                Map.of("classifications", List.of()));
                    }
                    return withDiscoveryEvidence(request, nodeTypes("KEYWORD"));
                })
                .thenAnswer(invocation -> withDiscoveryEvidence(invocation.getArgument(0), nodeTypes("KEYWORD")))
                .thenAnswer(invocation -> withDiscoveryEvidence(invocation.getArgument(0), nodeTypes("PUBLICATION", "PERSON")))
                .thenAnswer(invocation -> withDiscoveryEvidence(invocation.getArgument(0), nodeTypes("PUBLICATION", "PERSON")))
                .thenAnswer(invocation -> witnessDiscoveryResponse(invocation.getArgument(0),
                        List.of(new WitnessSpec("person Sarah Chen", "authored", "Publication",
                                "Publication authored by person Sarah Chen about a stellar observation."))))
                .thenAnswer(invocation -> witnessConsolidationResponse(invocation.getArgument(0),
                        List.of(new ConsolidationSpec("AUTHORED_BY", "ATTRIBUTION", "authored"))))
                .thenAnswer(invocation -> endpointSignatureResponse(invocation.getArgument(0)));

        GraphSchema schema = new CorpusSchemaUnifier().unify(
                Map.of("publication-window", "Publication authored by person Sarah Chen about a stellar observation."),
                new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                null,
                job,
                "snapshot-schema-repair",
                dispatcher);

        assertTrue(schema.getAllNodeLabels().containsAll(
                List.of("PUBLICATION", "PERSON")));
        assertEquals(java.util.Set.of("AUTHORED_BY"), schema.getAllRelationshipTypes());

        ArgumentCaptor<StructuredChatLanguageModel.Request> requests =
                ArgumentCaptor.forClass(StructuredChatLanguageModel.Request.class);
        ArgumentCaptor<CrawlLlmDispatcher.LlmCallScope> scopes =
                ArgumentCaptor.forClass(CrawlLlmDispatcher.LlmCallScope.class);
        verify(dispatcher, times(7)).promptStructuredWithCapacityFallback(
                requests.capture(), eq("llm"), same(job), scopes.capture());
        assertEquals(List.of(
                        "entity-classifications-1", "node-types-1", "node-types-1", "node-types-consolidation",
                        "relationship-witness-discovery-1", "relationship-witness-consolidation-1",
                        "relationship-signatures-1"),
                scopes.getAllValues().stream()
                .map(CrawlLlmDispatcher.LlmCallScope::passId).toList());
        assertEquals(List.of(1, 1, 2, 1, 1, 1, 1), scopes.getAllValues().stream()
                .map(CrawlLlmDispatcher.LlmCallScope::passInvocation).toList());
        assertEquals("job-schema-repair:corpus-schema:node-types:1",
                scopes.getAllValues().get(1).taskId());
        assertEquals("job-schema-repair:corpus-schema:node-types:1:attempt:2",
                scopes.getAllValues().get(2).taskId());
        String retryPrompt = requests.getAllValues().get(2).messages().get(1).content();
        assertTrue(retryPrompt.contains("TYPE-SCHEMA REPAIR REQUIRED (attempt 2 of 3)"));
        assertTrue(retryPrompt.contains("SCHEMA_GENERIC_TYPE"));
        assertTrue(retryPrompt.contains("KEYWORD"));
        assertTrue(retryPrompt.contains("Publication authored by person Sarah Chen"));
        assertFalse(retryPrompt.contains("sourceType"));
        assertFalse(retryPrompt.contains("targetType"));
    }

    @Test
    void exhaustsConfiguredNodeTypeValidationRetriesWithoutStartingRelations() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-schema-exhausted");
        when(job.getRequest()).thenReturn(UnifiedCrawlRequest.builder()
                .maxValidationRetries(2)
                .build());
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);

        when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class),
                eq("llm"),
                same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenAnswer(invocation -> {
                    StructuredChatLanguageModel.Request request = invocation.getArgument(0);
                    if (CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME.equals(
                            request.tools().get(0).name())) {
                        return structuredSchemaResponse(
                                CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME,
                                Map.of("classifications", List.of()));
                    }
                    return withDiscoveryEvidence(request, nodeTypes("KEYWORD"));
                });

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new CorpusSchemaUnifier().unify(
                        Map.of("publication-window", "The reader requested more context."),
                        new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                        null,
                        job,
                        "snapshot-schema-exhausted",
                        dispatcher));

        assertTrue(failure.getMessage().contains("SCHEMA_GENERIC_TYPE"));
        assertTrue(failure.getMessage().contains("KEYWORD"));
        // classification sample + 3 discovery attempts (maxValidationRetries=2)
        verify(dispatcher, times(4)).promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class),
                eq("llm"),
                same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class));
    }

    @Test
    void fallsBackToGroundedObservedProposalsAfterConsolidationVocabularyExhaustion() {
        // Under the witness pipeline, exhausted consolidation reports explicit FAILED status
        // (no silent fallback to unvalidated proposals) while preserving the checked witness
        // inventory. The guarantee retained: invented/hallucinated predicates never freeze.
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-consolidation-fallback");
        when(job.getRequest()).thenReturn(UnifiedCrawlRequest.builder()
                .maxValidationRetries(2)
                .build());
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        String window = "The astronomical observation uses an instrument.";
        List<WitnessSpec> witnesses = List.of(new WitnessSpec(
                "astronomical observation", "uses", "instrument", window));
        when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class),
                eq("llm"), same(job), any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenReturn(structuredSchemaResponse(CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME,
                        Map.of("classifications", List.of())))
                .thenReturn(nodeTypes())
                .thenAnswer(invocation -> witnessDiscoveryResponse(invocation.getArgument(0), witnesses))
                .thenAnswer(invocation -> {
                    // Every consolidation attempt proposes a predicate no witness supports;
                    // host citation validation rejects each row.
                    return structuredSchemaResponse(CorpusSchemaUnifier.RELATIONSHIP_TYPE_TOOL_NAME,
                            Map.of("relationshipTypes", List.of(
                                    Map.of("type", "INVENTED_ASSOCIATION", "connectionFamily", "REFERENCE",
                                            "witnessIds", List.of("nonexistent-witness")))));
                })
                .thenAnswer(invocation -> {
                    return structuredSchemaResponse(CorpusSchemaUnifier.RELATIONSHIP_TYPE_TOOL_NAME,
                            Map.of("relationshipTypes", List.of(
                                    Map.of("type", "UNOBSERVED_LINK", "connectionFamily", "REFERENCE",
                                            "witnessIds", List.of("nonexistent-witness")))));
                })
                .thenAnswer(invocation -> {
                    return structuredSchemaResponse(CorpusSchemaUnifier.RELATIONSHIP_TYPE_TOOL_NAME,
                            Map.of("relationshipTypes", List.of(
                                    Map.of("type", "HALLUCINATED_RELATION", "connectionFamily", "REFERENCE",
                                            "witnessIds", List.of("nonexistent-witness")))));
                });

        var result = new CorpusSchemaUnifier().unifyWithTopicBindings(
                Map.of("window", window),
                new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                null, null, CorpusTopicEvidence.empty(), job,
                "snapshot-consolidation-fallback", dispatcher);

        assertTrue(result.schema().getAllRelationshipTypes().isEmpty(),
                "No hallucinated predicate may freeze when consolidation exhausts");
        assertEquals(CorpusSchemaUnifier.RelationshipDiscoveryStatus.FAILED,
                result.relationshipDiscovery().status(),
                "Exhausted consolidation must be reported as failure, not valid emptiness");
        assertEquals(1, result.relationshipDiscovery().witnesses().size(),
                "Checked witness inventory must be preserved for diagnostics");

        ArgumentCaptor<StructuredChatLanguageModel.Request> requests =
                ArgumentCaptor.forClass(StructuredChatLanguageModel.Request.class);
        // Actual protocol: classification + node discovery + witness discovery + 3 failing
        // consolidation attempts (maxValidationRetries=2) = 6; no signature pass runs after
        // FAILED discovery and no node consolidation is needed for empty novel types.
        verify(dispatcher, times(6)).promptStructuredWithCapacityFallback(
                requests.capture(), eq("llm"), same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class));
        assertTrue(requests.getAllValues().stream()
                        .anyMatch(value -> value.messages().get(1).content()
                                .contains("WITNESS_UNKNOWN")),
                "consolidation repair must carry the unknown-witness diagnostic");
    }

    @Test
    void doesNotFallbackWhenVocabularyFailuresEndWithMalformedStructuredResponse() {
        // Every consolidation attempt fails (unresolvable citations, then a wrong-tool
        // response). Exhaustion must be reported as explicit FAILED discovery — no invented
        // fallback predicate, checked witnesses retained, configured types preserved.
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-consolidation-malformed");
        when(job.getRequest()).thenReturn(UnifiedCrawlRequest.builder()
                .maxValidationRetries(2)
                .build());
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        StructuredChatLanguageModel.Response wrongTool = new StructuredChatLanguageModel.Response(
                "<native-tool-call>", "",
                List.of(new StructuredChatLanguageModel.ToolCall(
                        "wrong-call", "submit_graph_delta", Map.of())),
                List.of());
        String window = "The astronomical observation uses an instrument.";
        List<WitnessSpec> witnesses = List.of(new WitnessSpec(
                "astronomical observation", "uses", "instrument", window));
        final java.util.List<Boolean> inventedSequence = new java.util.ArrayList<>(
                List.of(true, true));
        when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class),
                eq("llm"), same(job), any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenAnswer(invocation -> {
                    StructuredChatLanguageModel.Request request = invocation.getArgument(0);
                    String tool = request.tools().get(0).name();
                    if (CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME.equals(tool)) {
                        return structuredSchemaResponse(
                                CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME,
                                Map.of("classifications", List.of()));
                    }
                    if (CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME.equals(tool)) {
                        return nodeTypes();
                    }
                    if (CorpusSchemaUnifier.WITNESS_TOOL_NAME.equals(tool)) {
                        return witnessDiscoveryResponse(request, witnesses);
                    }
                    if (CorpusSchemaUnifier.RELATIONSHIP_TYPE_TOOL_NAME.equals(tool)) {
                        if (!inventedSequence.isEmpty()) {
                            inventedSequence.remove(0);
                            return structuredSchemaResponse(
                                    CorpusSchemaUnifier.RELATIONSHIP_TYPE_TOOL_NAME,
                                    Map.of("relationshipTypes", List.of(Map.of(
                                            "type", "INVENTED_ASSOCIATION",
                                            "connectionFamily", "REFERENCE",
                                            "witnessIds", List.of("nonexistent-witness")))));
                        }
                        return wrongTool;
                    }
                    throw new AssertionError("Unexpected schema tool: " + tool);
                });

        GraphSchema configured = new GraphSchema(
                List.of(new NodeType("ARCHIVE", "A configured archive type.", null)),
                null, null);
        var result = new CorpusSchemaUnifier().unifyWithTopicBindings(
                Map.of("window", window),
                new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                configured, null, CorpusTopicEvidence.empty(), job,
                "snapshot-consolidation-malformed", dispatcher);

        assertEquals(CorpusSchemaUnifier.RelationshipDiscoveryStatus.FAILED,
                result.relationshipDiscovery().status(),
                "Exhausted consolidation must be explicit failure");
        assertTrue(result.relationshipDiscovery().failures().stream()
                        .anyMatch(failure -> failure.contains("WITNESS_UNKNOWN")
                                || failure.contains("wrong tool") || failure.contains("exhausted")),
                "Diagnostics must be retained: "
                        + result.relationshipDiscovery().failures());
        assertTrue(result.schema().getAllRelationshipTypes().isEmpty(),
                "No invented fallback predicate may freeze");
        assertTrue(result.schema().getAllNodeLabels().contains("ARCHIVE"),
                "Configured types must remain usable after discovery failure");
        // classification + node discovery + witness discovery + 3 failing consolidation
        // attempts = 6 (maxValidationRetries=2 → 3 attempts). No signature pass after FAILED.
        verify(dispatcher, times(6)).promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class), eq("llm"), same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class));
    }

    @Test
    void deterministicFallbackDropsProposalThatCollidesWithFrozenOppositeType() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-consolidation-category-fallback");
        when(job.getRequest()).thenReturn(UnifiedCrawlRequest.builder()
                .maxValidationRetries(2)
                .build());
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class),
                eq("llm"), same(job), any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenAnswer(invocation -> {
                    StructuredChatLanguageModel.Request request = invocation.getArgument(0);
                    String tool = request.tools().get(0).name();
                    if (CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME.equals(tool)) {
                        return structuredSchemaResponse(
                                CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME,
                                Map.of("classifications", List.of()));
                    }
                    if (CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME.equals(tool)) {
                        return nodeTypes();
                    }
                    if (CorpusSchemaUnifier.WITNESS_TOOL_NAME.equals(tool)) {
                        return witnessDiscoveryResponse(request, List.of());
                    }
                    return witnessConsolidationResponse(request, List.of());
                });
        GraphSchema configured = new GraphSchema(
                List.of(new NodeType("OBSERVATION", "A frozen observation type.", null)),
                null, null);

        GraphSchema schema = new CorpusSchemaUnifier().unify(
                Map.of("window", "The observation was recorded by the telescope."),
                new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                configured, job, "snapshot-consolidation-category-fallback", dispatcher);

        assertTrue(schema.getAllNodeLabels().contains("OBSERVATION"));
        assertTrue(schema.getAllNodeLabels().containsAll(
                SchemaHierarchyVocabulary.BASE_ENTITY_TYPES));
        assertTrue(schema.getAllRelationshipTypes().isEmpty());
        // Actual protocol: classification + node discovery + witness discovery (abstain) = 3;
        // no consolidation call is needed because the stub's node response commits directly.
        verify(dispatcher, times(3)).promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class), eq("llm"), same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class));
    }

    @Test
    void successfulModelOverlayPreservesSeededAndDeterministicTypesInOneSchema() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-merged-schema");
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        stubTypePasses(
                dispatcher, job,
                nodeTypes("CELESTIAL_OBJECT"),
                relationshipTypes());

        CorpusSchemaCandidates.Inventory deterministicCandidates =
                new CorpusSchemaCandidates.Inventory(
                        List.of(new CorpusSchemaCandidates.NodeCandidate(
                                "reviewer", List.of("Reviewer"),
                                List.of("DETERMINISTIC_ACTOR"), 2, 0.96d,
                                List.of("window"), List.of("Reviewer approved the document."))),
                        List.of());
        GraphSchema seeded = new GraphSchema(
                List.of(new NodeType(
                        "SEEDED_DOCUMENT", "A configured document type.", null)),
                null,
                null);
        GraphSchema sourceNative = new GraphSchema(
                List.of(
                        new NodeType("EXTRACTOR_MESSAGE", "A source-native message.", null),
                        new NodeType("DETERMINISTIC_ACTOR", "A source-native actor.", null)),
                List.of(new RelationshipType(
                        "EMITTED_BY", "A message was emitted by an actor.", null)),
                List.of("(EXTRACTOR_MESSAGE)-[:EMITTED_BY]->(DETERMINISTIC_ACTOR)"));

        GraphSchema schema = new CorpusSchemaUnifier().unify(
                Map.of("window", "Reviewer described a celestial object in the monthly document."),
                deterministicCandidates,
                seeded,
                sourceNative,
                job,
                "snapshot-merged",
                dispatcher);

        assertTrue(schema.getAllNodeLabels().containsAll(List.of(
                "SEEDED_DOCUMENT", "EXTRACTOR_MESSAGE",
                "DETERMINISTIC_ACTOR", "CELESTIAL_OBJECT")));
        assertEquals(java.util.Set.of("EMITTED_BY"), schema.getAllRelationshipTypes());
        assertEquals(
                List.of("(EXTRACTOR_MESSAGE)-[:EMITTED_BY]->(DETERMINISTIC_ACTOR)"),
                schema.getPatterns());

        ArgumentCaptor<StructuredChatLanguageModel.Request> request =
                ArgumentCaptor.forClass(StructuredChatLanguageModel.Request.class);
        verify(dispatcher, times(4)).promptStructuredWithCapacityFallback(
                request.capture(), eq("llm"), same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class));
        assertTrue(request.getAllValues().stream().allMatch(value ->
                value.tools().get(0).name().equals(CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME)
                        || value.messages().get(1).content().contains("SEEDED_DOCUMENT")));
        assertTrue(request.getAllValues().stream().allMatch(value ->
                value.tools().get(0).name().equals(CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME)
                        || value.messages().get(1).content().contains("EXTRACTOR_MESSAGE")));
        assertTrue(request.getAllValues().stream().allMatch(value ->
                value.tools().get(0).name().equals(CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME)
                        || value.messages().get(1).content().contains("DETERMINISTIC_ACTOR")));
    }

    @Test
    void consolidatesUntrustedProposalsBeforeCommitAndRejectsNodeNounRelations() {
        // Behavioral guarantee (preserved under the witness pipeline): an invalid predicate
        // named after a node noun (OBSERVATION) is rejected at consolidation without
        // discarding its valid sibling (USES_INSTRUMENT).
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-consolidation");
        when(job.getRequest()).thenReturn(UnifiedCrawlRequest.builder()
                .maxValidationRetries(1)
                .build());
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        String window = "The astronomical observation uses an instrument such as a telescope.";
        List<WitnessSpec> witnesses = List.of(new WitnessSpec(
                "astronomical observation", "uses", "instrument", window));
        // Attempt 1 consolidation proposes the node-noun predicate alongside the valid one.
        List<ConsolidationSpec> invalidAttempt = List.of(
                new ConsolidationSpec("OBSERVATION", "REFERENCE", "uses"),
                new ConsolidationSpec("USES_INSTRUMENT", "DEPENDENCY", "uses"));
        // Repair keeps only the valid predicate.
        List<ConsolidationSpec> repaired = List.of(
                new ConsolidationSpec("USES_INSTRUMENT", "DEPENDENCY", "uses"));
        java.util.concurrent.atomic.AtomicBoolean attemptOneConsolidation =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        var calls = when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class),
                eq("llm"), same(job), any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenAnswer(invocation -> {
                    StructuredChatLanguageModel.Request request = invocation.getArgument(0);
                    String tool = request.tools().get(0).name();
                    String prompt = request.messages().get(1).content();
                    if (CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME.equals(tool)) {
                        return structuredSchemaResponse(
                                CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME,
                                Map.of("classifications", List.of()));
                    }
                    if (CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME.equals(tool)) {
                        if (prompt.contains("UNTRUSTED BATCH PROPOSALS")) {
                            return nodeTypes("OBSERVATION");
                        }
                        return withDiscoveryEvidence(request,
                                nodeTypes("ASTRONOMICAL_OBSERVATION_2024_FINAL", "OBSERVATION"));
                    }
                    if (CorpusSchemaUnifier.WITNESS_TOOL_NAME.equals(tool)) {
                        return witnessDiscoveryResponse(request, witnesses);
                    }
                    if (CorpusSchemaUnifier.RELATIONSHIP_TYPE_TOOL_NAME.equals(tool)) {
                        return attemptOneConsolidation.getAndSet(false)
                                ? witnessConsolidationResponse(request, invalidAttempt)
                                : witnessConsolidationResponse(request, repaired);
                    }
                    if (CorpusSchemaUnifier.ENDPOINT_SIGNATURE_TOOL_NAME.equals(tool)) {
                        return endpointSignatureResponse(request);
                    }
                    throw new AssertionError("Unexpected schema tool: " + tool);
                });

        GraphSchema schema = new CorpusSchemaUnifier().unify(
                Map.of("window", window),
                new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                null, job, "snapshot-consolidation", dispatcher);

        assertTrue(schema.getAllNodeLabels().contains("OBSERVATION"));
        assertEquals("ACTIVITY",
                schema.getNodeTypeMap().get("OBSERVATION").getParentType());
        assertEquals(java.util.Set.of("USES_INSTRUMENT"), schema.getAllRelationshipTypes());

        ArgumentCaptor<StructuredChatLanguageModel.Request> requests =
                ArgumentCaptor.forClass(StructuredChatLanguageModel.Request.class);
        ArgumentCaptor<CrawlLlmDispatcher.LlmCallScope> scopes =
                ArgumentCaptor.forClass(CrawlLlmDispatcher.LlmCallScope.class);
        // Actual protocol: classification + node discovery + witness discovery + consolidation
        // attempt 1 (invalid row) + repair attempt (valid) + signatures = 6; node consolidation
        // is skipped when node discovery committed directly.
        verify(dispatcher, times(6)).promptStructuredWithCapacityFallback(
                requests.capture(), eq("llm"), same(job), scopes.capture());
        long consolidationRepairs = requests.getAllValues().stream()
                .filter(value -> value.messages().get(1).content()
                        .contains("OBSERVATION"))
                .filter(value -> CorpusSchemaUnifier.RELATIONSHIP_TYPE_TOOL_NAME.equals(
                        value.tools().get(0).name()))
                .count();
        assertTrue(consolidationRepairs >= 1,
                "consolidation repair must carry the rejected predicate diagnostic");
        scopes.getAllValues().stream()
                .filter(scope -> scope.passId() != null
                        && scope.passId().contains("relationship-witness-consolidation"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "consolidation must run under its own witness scope identity"));
        assertEquals(CorpusSchemaUnifier.ENDPOINT_SIGNATURE_TOOL_NAME,
                requests.getAllValues().stream()
                        .reduce((first, second) -> second)
                        .orElseThrow()
                        .tools().get(0).name());
    }

    @Test
    void semanticPrepassBatchesEveryCorpusPassageThroughTheSmallModel() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-full-corpus");
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        stubTypePasses(dispatcher, job, nodeTypes(), relationshipTypes());

        Map<String, String> passages = new LinkedHashMap<>();
        for (int index = 0; index < 20; index++) {
            passages.put("window-" + index,
                    "UNIQUE_CORPUS_MARKER_" + index + " " + "x".repeat(900));
        }
        GraphSchema seeded = new GraphSchema(
                List.of(new NodeType("SEEDED_TYPE", "A configured seed.", null)),
                null,
                null);

        GraphSchema schema = new CorpusSchemaUnifier().unify(
                passages,
                new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                seeded,
                null,
                job,
                "snapshot-full-corpus",
                dispatcher);

        assertTrue(schema.getAllNodeLabels().contains("SEEDED_TYPE"));
        assertTrue(schema.getAllNodeLabels().containsAll(
                SchemaHierarchyVocabulary.BASE_ENTITY_TYPES));
        ArgumentCaptor<StructuredChatLanguageModel.Request> requests =
                ArgumentCaptor.forClass(StructuredChatLanguageModel.Request.class);
        verify(dispatcher, org.mockito.Mockito.atLeast(2))
                .promptStructuredWithCapacityFallback(
                        requests.capture(), eq("llm"), same(job),
                        any(CrawlLlmDispatcher.LlmCallScope.class));
        String allPrompts = requests.getAllValues().stream()
                .map(request -> request.messages().get(1).content())
                .reduce("", (left, right) -> left + "\n" + right);
        for (int index = 0; index < 20; index++) {
            assertTrue(allPrompts.contains("UNIQUE_CORPUS_MARKER_" + index),
                    "full-corpus prepass omitted passage " + index);
        }
    }

    @Test
    void semanticBatchesPreserveSentenceAssertionsAndEverySourceCharacter() {
        String assertion = "Mei Chen is a research leader.";
        String passage = "x".repeat(900) + ". " + assertion + " " + "y".repeat(1_500);

        List<Map<String, String>> batches = CorpusSchemaUnifier.modelPassageBatches(
                Map.of("long-window", passage));
        List<String> fragments = batches.stream()
                .flatMap(batch -> batch.values().stream())
                .toList();

        assertEquals(passage, String.join("", fragments));
        assertTrue(fragments.stream().allMatch(fragment -> fragment.length() <= 1_024));
        assertTrue(fragments.stream().anyMatch(fragment -> fragment.contains(assertion)),
                "the type assertion must not be split across semantic prepass windows");
    }

    @Test
    void rejectsBackendWithoutStructuredSchemaToolSupport() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-no-structured-tool");

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new CorpusSchemaUnifier().unify(
                        Map.of("window", "Mei Chen catalogued the specimen."),
                        new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                        null,
                        job,
                        "snapshot-no-structured-tool",
                        dispatcher));

        assertTrue(failure.getMessage().contains("structured-chat tool support"));
    }

    @Test
    void rejectsWrongOrMissingStructuredToolCalls() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-wrong-tool");
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class),
                eq("llm"),
                same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenAnswer(invocation -> {
                    StructuredChatLanguageModel.Request request = invocation.getArgument(0);
                    if (CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME.equals(
                            request.tools().get(0).name())) {
                        return structuredSchemaResponse(
                                CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME,
                                Map.of("classifications", List.of()));
                    }
                    return new StructuredChatLanguageModel.Response(
                            "<native-tool-call>",
                            "",
                            List.of(new StructuredChatLanguageModel.ToolCall(
                                    "wrong-call", "submit_graph_delta", Map.of())),
                            List.of());
                });

        IllegalStateException wrongTool = assertThrows(
                IllegalStateException.class,
                () -> new CorpusSchemaUnifier().unify(
                        Map.of("window", "Mei Chen catalogued the specimen."),
                        new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                        null,
                        job,
                        "snapshot-wrong-tool",
                        dispatcher));
        assertTrue(wrongTool.getMessage().contains("wrong tool"));

        when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class),
                eq("llm"),
                same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenAnswer(invocation -> {
                    StructuredChatLanguageModel.Request request = invocation.getArgument(0);
                    if (CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME.equals(
                            request.tools().get(0).name())) {
                        return structuredSchemaResponse(
                                CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME,
                                Map.of("classifications", List.of()));
                    }
                    return new StructuredChatLanguageModel.Response(
                            "{\"nodeTypes\":[\"PERSON\"],\"relationshipTypes\":[],\"patterns\":[]}",
                            "",
                            List.of(),
                            List.of());
                });
        IllegalStateException missingTool = assertThrows(
                IllegalStateException.class,
                () -> new CorpusSchemaUnifier().unify(
                        Map.of("window", "Mei Chen catalogued the specimen."),
                        new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                        null,
                        job,
                        "snapshot-missing-tool",
                        dispatcher));
        assertTrue(missingTool.getMessage().contains("did not call submit_node_types"));
    }

    @Test
    void rejectsIncompleteObjectDefinitionsEvenWhenBackendViolatesToolSchema() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-object-type");
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class),
                eq("llm"),
                same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenReturn(structuredSchemaResponse(
                        CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME,
                        Map.of("nodeTypes", List.of(Map.of(
                                "label", "PERSON",
                                "description", "An extracted person.")))));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new CorpusSchemaUnifier().unify(
                        Map.of("window", "Mei Chen catalogued the specimen."),
                        new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                        null,
                        job,
                        "snapshot-object-type",
                        dispatcher));

        assertTrue(failure.getMessage().contains("entries must contain exactly"));
    }

    @Test
    void validEmptyOverlayProducesAnExplicitFrozenBaselineSchema() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-empty-overlay");
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        stubTypePasses(dispatcher, job, nodeTypes(), relationshipTypes());

        GraphSchema schema = new CorpusSchemaUnifier().unify(
                Map.of("window", "A passage with no reusable domain vocabulary."),
                new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                null,
                job,
                "snapshot-empty-overlay",
                dispatcher);

        assertNotNull(schema);
        assertEquals(java.util.Set.copyOf(SchemaHierarchyVocabulary.BASE_ENTITY_TYPES),
                schema.getAllNodeLabels());
        assertTrue(schema.getAllRelationshipTypes().isEmpty());
        assertNotNull(schema.getRelationshipTypes(),
                "a frozen empty vocabulary must differ from an unspecified/open one");
        assertFalse(GraphExtractionOrchestrator.ontologyUpdatesAllowed(
                GraphExtractionConfig.builder().schemaMode(
                        ai.kompile.core.graphrag.model.schema.SchemaEnforcementMode.LENIENT).build(),
                schema));
    }

    @Test
    void bindsTopicTypesIntoEstablishedHierarchyWithTypedRelationshipSignature() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-topic-hierarchy-binding");
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class),
                eq("llm"), same(job), any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenAnswer(invocation -> {
                    StructuredChatLanguageModel.Request request = invocation.getArgument(0);
                    String tool = request.tools().get(0).name();
                    if (CorpusSchemaUnifier.TOPIC_BINDING_TOOL_NAME.equals(tool)) {
                        return structuredSchemaResponse(tool, Map.of("b",
                                bindingOptionId(request, "nodeIds", "CELESTIAL_OBJECT") + "|"
                                        + bindingOptionId(request, "parentIds", "SCIENTIFIC_OBJECT") + "|"
                                        + bindingOptionId(request, "evidenceIds", "celestial object") + "|"
                                        + bindingOptionId(request, "relationshipIds", "DETECTED") + "|"
                                        + bindingOptionId(request, "familyIds", "REFERENCE") + "|"
                                        + bindingOptionId(request, "endpointIds", "CONCEPT") + "|"
                                        + bindingOptionId(request, "endpointIds", "PRODUCT") + "|"
                                        + bindingOptionId(request, "evidenceIds", "detected")));
                    }
                    return CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME.equals(tool)
                            ? nodeTypes() : relationshipTypes();
                });
        CorpusTopicEvidence evidence = new CorpusTopicEvidence(
                "multilingual-e5-small", 384, 0.5, 0,
                List.of(new CorpusTopicEvidence.Topic(
                        "topic-0001", List.of("astronomy-doc"), List.of("astronomy-1"),
                        List.of("astronomy-1"), Map.of("en", 1),
                        Map.of("en", List.of("celestial object", "instrument", "detected")))));
        GraphSchema configured = new GraphSchema(
                List.of(new NodeType(
                        "SCIENTIFIC_OBJECT", "A scientific object.", null, "CONCEPT")),
                null, null);

        CorpusSchemaUnifier.UnificationResult result =
                new CorpusSchemaUnifier().unifyWithTopicBindings(
                        Map.of("astronomy-1",
                                "A celestial object was detected by the observatory instrument."),
                        new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                        configured, null, evidence, job,
                        "snapshot-topic-binding", dispatcher);

        GraphSchema schema = result.schema();
        assertEquals("SCIENTIFIC_OBJECT",
                schema.getNodeTypeMap().get("CELESTIAL_OBJECT").getParentType());
        assertEquals(List.of("SCIENTIFIC_OBJECT", "CONCEPT"),
                schema.getNodeTypeAncestors("CELESTIAL_OBJECT"));
        assertEquals("REFERENCE",
                schema.getRelationshipTypeMap().get("DETECTED").getConnectionFamily());
        assertTrue(schema.getPatterns().contains(
                "(CONCEPT)-[:DETECTED]->(PRODUCT)"));
        assertTrue(schema.isNodeTypeAssignableTo("CELESTIAL_OBJECT", "CONCEPT"));
        assertEquals("DETECTED",
                result.topicEvidence().bindings().get(0).relationshipTypes().get(0).type());
        assertEquals("celestial object",
                result.topicEvidence().bindings().get(0).nodeTypes().get(0).groundingPhrase());

        ArgumentCaptor<StructuredChatLanguageModel.Request> requests =
                ArgumentCaptor.forClass(StructuredChatLanguageModel.Request.class);
        verify(dispatcher, times(1)).promptStructuredWithCapacityFallback(
                requests.capture(), eq("llm"), same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class));
        StructuredChatLanguageModel.Request bindingRequest = requests.getAllValues().get(0);
        String bindingPrompt = bindingRequest.messages()
                .get(bindingRequest.messages().size() - 1).content();
        assertTrue(bindingPrompt.length() < 8_000,
                () -> "topic binding request was " + bindingPrompt.length());
        assertFalse(bindingPrompt.contains("BASE ENTITY TYPE HIERARCHY"), bindingPrompt);
        assertFalse(bindingPrompt.contains("Existing custom hierarchy types"), bindingPrompt);
        Map<String, Object> properties = asMap(
                bindingRequest.tools().get(0).parameters().get("properties"));
        assertEquals(Set.of("b"), properties.keySet());
        assertTrue(String.valueOf(asMap(properties.get("b")).get("description"))
                .contains("NODE_ID|PARENT_ID|NODE_EVIDENCE_ID"));
        assertEquals(15, asMap(properties.get("b")).get("minLength"));
        String packedPattern = String.valueOf(asMap(properties.get("b")).get("pattern"));
        assertTrue("0|0|0|0|0|0|0|0".matches(packedPattern), packedPattern);
        String partialRelationship = bindingOptionId(bindingRequest, "nodeIds", "CELESTIAL_OBJECT")
                + "|" + bindingOptionId(bindingRequest, "parentIds", "SCIENTIFIC_OBJECT")
                + "|" + bindingOptionId(bindingRequest, "evidenceIds", "celestial object")
                + "|" + bindingOptionId(bindingRequest, "relationshipIds", "DETECTED")
                + "|" + bindingOptionId(bindingRequest, "familyIds", "REFERENCE")
                + "|0|0|0";
        assertFalse(partialRelationship.matches(packedPattern), packedPattern);
        assertTrue(requests.getAllValues().stream().noneMatch(request ->
                        CorpusSchemaUnifier.RELATIONSHIP_TYPE_TOOL_NAME.equals(
                                request.tools().get(0).name())),
                "topic-enabled schema induction must not reintroduce untyped relationship proposals");
    }

    @Test
    void bindsEveryTopicAcrossBatchesAndTreatsExactTypeBindingsAsIdempotent() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-multi-topic-binding");
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class),
                eq("llm"), same(job), any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenAnswer(invocation -> {
                    StructuredChatLanguageModel.Request request = invocation.getArgument(0);
                    String tool = request.tools().get(0).name();
                    if (!CorpusSchemaUnifier.TOPIC_BINDING_TOOL_NAME.equals(tool)) {
                        return CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME.equals(tool)
                                ? nodeTypes() : relationshipTypes();
                    }
                    return structuredSchemaResponse(tool, Map.of("b",
                            bindingOptionId(request, "nodeIds", "DOMAIN_RECORD") + "|"
                                    + bindingOptionId(request, "parentIds", "DOCUMENT")
                                    + "|" + bindingOptionId(request, "evidenceIds", "domain record")
                                    + "|0|0|0|0|0"));
                });
        List<CorpusTopicEvidence.Topic> topics = java.util.stream.IntStream.rangeClosed(1, 5)
                .mapToObj(index -> new CorpusTopicEvidence.Topic(
                        "topic-000" + index,
                        List.of("document-" + index),
                        List.of("chunk-" + index),
                        List.of("chunk-" + index),
                        Map.of("en", 1), Map.of("en", List.of("domain record"))))
                .toList();
        Map<String, String> passages = java.util.stream.IntStream.rangeClosed(1, 5)
                .boxed().collect(java.util.stream.Collectors.toMap(
                        index -> "chunk-" + index,
                        index -> "A reusable domain record for topic " + index,
                        (left, ignored) -> left, LinkedHashMap::new));
        List<NodeType> configuredNodes = java.util.stream.IntStream.range(0, 80)
                .mapToObj(index -> new NodeType(
                        "CUSTOM_PARENT_" + index,
                        "Custom hierarchy parent " + index,
                        null, "CONCEPT"))
                .toList();

        CorpusSchemaUnifier.UnificationResult result =
                new CorpusSchemaUnifier().unifyWithTopicBindings(
                        passages,
                        new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                        new GraphSchema(configuredNodes, null, null), null,
                        new CorpusTopicEvidence(
                                "multilingual-e5-small", 384, 0.5, 0, topics),
                        job, "snapshot-multi-topic", dispatcher);

        assertEquals(1, result.schema().getNodeTypes().stream()
                .filter(node -> "DOMAIN_RECORD".equals(node.getLabel())).count());
        assertEquals(5, result.topicEvidence().bindings().size());
        ArgumentCaptor<StructuredChatLanguageModel.Request> requests =
                ArgumentCaptor.forClass(StructuredChatLanguageModel.Request.class);
        verify(dispatcher, times(5)).promptStructuredWithCapacityFallback(
                requests.capture(), eq("llm"), same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class));
        List<StructuredChatLanguageModel.Request> bindingRequests = requests.getAllValues().stream()
                .filter(request -> CorpusSchemaUnifier.TOPIC_BINDING_TOOL_NAME.equals(
                        request.tools().get(0).name())).toList();
        assertEquals(5, bindingRequests.size());
        for (StructuredChatLanguageModel.Request request : bindingRequests) {
            Map<String, Object> properties = asMap(
                    request.tools().get(0).parameters().get("properties"));
            assertEquals(Set.of("b"), properties.keySet());
        }
    }

    @Test
    void skipsUnbindableTopicInsteadOfAbortingCorpus() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-partial-topic-grounding");
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class),
                eq("llm"), same(job), any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenAnswer(invocation -> {
                    StructuredChatLanguageModel.Request request = invocation.getArgument(0);
                    String tool = request.tools().get(0).name();
                    if (CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME.equals(tool)) return nodeTypes();
                    if (CorpusSchemaUnifier.WITNESS_TOOL_NAME.equals(tool)) return witnessDiscoveryResponse(request, List.of());
                    if (CorpusSchemaUnifier.RELATIONSHIP_TYPE_TOOL_NAME.equals(tool)) {
                        return witnessConsolidationResponse(request, List.of());
                    }
                    if (CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME.equals(tool)) {
                        return structuredSchemaResponse(
                                CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME,
                                Map.of("classifications", List.of()));
                    }
                    return structuredSchemaResponse(
                            CorpusSchemaUnifier.TOPIC_BINDING_TOOL_NAME,
                            Map.of("b", bindingOptionId(request, "nodeIds", "INSTRUMENT") + "|"
                                    + bindingOptionId(request, "parentIds", "PRODUCT")
                                    + "|999|0|0|0|0|0"));
                });
        CorpusTopicEvidence evidence = new CorpusTopicEvidence(
                "multilingual-e5-small", 384, 0.5, 0,
                List.of(new CorpusTopicEvidence.Topic(
                        "topic-0001", List.of("doc-1"), List.of("chunk-1"),
                        List.of("chunk-1"), Map.of("en", 1),
                        Map.of("en", List.of("instrument")))));

        // An invalid topic binding (unknown evidence id) is skipped after retries;
        // it must NOT abort the corpus pre-pass with an exception.
        CorpusSchemaUnifier.UnificationResult result = new CorpusSchemaUnifier().unifyWithTopicBindings(
                Map.of("chunk-1", "The observatory instrument recorded data."),
                new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                null, null, evidence, job,
                "snapshot-partial-grounding", dispatcher);

        assertTrue(result.topicEvidence().bindings().isEmpty(),
                "a skipped topic must contribute no accepted bindings");
    }

    @Test
    void rejectsUnknownPackedOptionIds() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-packed-binding-validation");
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        CorpusTopicEvidence evidence = new CorpusTopicEvidence(
                "multilingual-e5-small", 384, 0.5, 0,
                List.of(new CorpusTopicEvidence.Topic(
                        "topic-0001", List.of("doc-1"), List.of("chunk-1"),
                        List.of("chunk-1"), Map.of("en", 1),
                        Map.of("en", List.of("astronomy", "telescope")))));
        when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class),
                eq("llm"), same(job), any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenAnswer(invocation -> {
                    StructuredChatLanguageModel.Request request = invocation.getArgument(0);
                    String tool = request.tools().get(0).name();
                    if (CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME.equals(tool)) return nodeTypes();
                    if (CorpusSchemaUnifier.WITNESS_TOOL_NAME.equals(tool)) return witnessDiscoveryResponse(request, List.of());
                    if (CorpusSchemaUnifier.RELATIONSHIP_TYPE_TOOL_NAME.equals(tool)) {
                        return witnessConsolidationResponse(request, List.of());
                    }
                    if (CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME.equals(tool)) {
                        return structuredSchemaResponse(
                                CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME,
                                Map.of("classifications", List.of()));
                    }
                    return structuredSchemaResponse(
                            CorpusSchemaUnifier.TOPIC_BINDING_TOOL_NAME,
                            Map.of("b", "999|"
                                    + bindingOptionId(request, "parentIds", "CONCEPT")
                                    + "|" + bindingOptionId(request, "evidenceIds", "astronomy")
                                    + "|0|0|0|0|0"));
                });
        // An invalid packed binding (unknown node option id) is skipped after retries;
        // it must NOT abort the corpus pre-pass with an exception.
        CorpusSchemaUnifier.UnificationResult result = new CorpusSchemaUnifier().unifyWithTopicBindings(
                Map.of("chunk-1", "An astronomy telescope observation."),
                new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                null, null, evidence, job, "snapshot-unknown-option", dispatcher);
        assertTrue(result.topicEvidence().bindings().isEmpty(),
                "a skipped topic must contribute no accepted bindings");
    }

    @Test
    void recoversOnlyCompletePackedBindingFromConstraintWrapperFailure() {
        IllegalStateException failure = new IllegalStateException(
                "Constraint rejected every candidate token after exact sequence decode "
                        + "emitted=\"<tool_call>\\n<function=bind_topics_to_schema>\\n"
                        + "<parameter=b>\\n\\\"1|2|9|3|6|37|24|8\\n\\ufe0\"");

        StructuredChatLanguageModel.Response recovered =
                CorpusSchemaUnifier.recoverCompletedPackedBinding(failure);
        StructuredChatLanguageModel.Response incomplete =
                CorpusSchemaUnifier.recoverCompletedPackedBinding(
                        new IllegalStateException("Constraint rejected every candidate token "
                                + "<parameter=b>\\n\\\"1|2|3"));
        StructuredChatLanguageModel.Response extraField =
                CorpusSchemaUnifier.recoverCompletedPackedBinding(
                        new IllegalStateException("Constraint rejected every candidate token "
                                + "<parameter=b>\\n\\\"1|2|9|3|6|37|24|8|5\\n"));

        assertNotNull(recovered);
        assertEquals("1|2|9|3|6|37|24|8",
                recovered.toolCalls().get(0).arguments().get("b"));
        assertEquals(null, incomplete);
        assertEquals(null, extraField);
    }

    @Test
    void multilingualTopicEvidenceCanGroundAnAsciiTypeForNonLatinPassages() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-cross-language-grounding");
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class),
                eq("llm"), same(job), any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenAnswer(invocation -> {
                    StructuredChatLanguageModel.Request request = invocation.getArgument(0);
                    String tool = request.tools().get(0).name();
                    if (CorpusSchemaUnifier.TOPIC_BINDING_TOOL_NAME.equals(tool)) {
                        return structuredSchemaResponse(tool, Map.of("b",
                                bindingOptionId(request, "nodeIds", "PERSON")
                                        + "|0|" + bindingOptionId(request, "evidenceIds", "顧客")
                                        + "|0|0|0|0|0"));
                    }
                    return nodeTypes();
                });
        CorpusTopicEvidence evidence = new CorpusTopicEvidence(
                "multilingual-e5-small", 384, 0.5, 0,
                List.of(new CorpusTopicEvidence.Topic(
                        "topic-0001", List.of("ja-1", "ja-2"), List.of("ja-1"),
                        Map.of("ja", 2), Map.of("ja", List.of("顧客", "契約")))));

        CorpusSchemaUnifier.UnificationResult result =
                new CorpusSchemaUnifier().unifyWithTopicBindings(
                Map.of("ja-1", "顧客が契約を承認した。", "ja-2", "顧客記録を更新した。"),
                new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                null, null, evidence, job, "snapshot-cross-language", dispatcher);

        GraphSchema schema = result.schema();
        assertTrue(schema.getAllNodeLabels().contains("PERSON"));
        assertEquals("顧客", result.topicEvidence().bindings().get(0)
                .nodeTypes().get(0).groundingPhrase());
        List<?> persistedBindings = (List<?>) result.topicEvidence()
                .persistenceView().get("bindings");
        Map<?, ?> persistedBinding = (Map<?, ?>) persistedBindings.get(0);
        Map<?, ?> persistedNode = (Map<?, ?>) ((List<?>)
                persistedBinding.get("nodeTypes")).get(0);
        assertEquals("顧客", persistedNode.get("groundingPhrase"));
    }

    @Test
    void deterministicFallbackDropsEquallySupportedConflictingClassifications() {
        CorpusSchemaUnifier.TypeProposal person =
                new CorpusSchemaUnifier.TypeProposal("CUSTOMER", "PERSON");
        CorpusSchemaUnifier.TypeProposal organization =
                new CorpusSchemaUnifier.TypeProposal("CUSTOMER", "ORGANIZATION");

        assertTrue(CorpusSchemaUnifier.unambiguousFallbackProposals(
                Map.of(person, 2, organization, 2)).isEmpty());
        assertEquals(Map.of(person, 3), CorpusSchemaUnifier.unambiguousFallbackProposals(
                Map.of(person, 3, organization, 2)));
    }

    @Test
    void topiclessRelationshipDiscoveryAddsMultipleGroundedSubtypeSignatures() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-endpoint-signatures");
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        GraphSchema configured = new GraphSchema(
                List.of(
                        new NodeType("SPECIAL_PERSON", "A specialized person.", null, "PERSON"),
                        new NodeType("ORGANIZATION", "An organization.", null)),
                null, null);
        when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class), eq("llm"), same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenAnswer(invocation -> {
                    StructuredChatLanguageModel.Request request = invocation.getArgument(0);
                    String tool = request.tools().get(0).name();
                    if (CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME.equals(tool)) {
                        return classificationSampleResponse(request);
                    }
                    if (CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME.equals(tool)) return nodeTypes();
                    if (CorpusSchemaUnifier.WITNESS_TOOL_NAME.equals(tool)) return witnessDiscoveryResponse(request,
                            List.of(new WitnessSpec("Alice", "works for", "Acme", "Alice works for Acme.")));
                    if (CorpusSchemaUnifier.RELATIONSHIP_TYPE_TOOL_NAME.equals(tool)) {
                        return witnessConsolidationResponse(request,
                                List.of(new ConsolidationSpec("WORKS_FOR", "AFFILIATION", "works for")));
                    }
                    String relation = bindingOptionId(request, "relationshipIds", "WORKS_FOR");
                    String subtype = bindingOptionId(request, "endpointIds", "SPECIAL_PERSON");
                    String person = bindingOptionId(request, "endpointIds", "PERSON");
                    String organization = bindingOptionId(request, "endpointIds", "ORGANIZATION");
                    String evidence = bindingOptionId(request, "evidenceIds", "Alice works for Acme.");
                    return structuredSchemaResponse(CorpusSchemaUnifier.ENDPOINT_SIGNATURE_TOOL_NAME,
                            Map.of("s", relation + "|" + subtype + "|" + organization + "|" + evidence
                                    + ";" + relation + "|" + person + "|" + organization + "|" + evidence));
                });

        GraphSchema schema = new CorpusSchemaUnifier().unify(
                Map.of("chunk", "Alice works for Acme."),
                new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                configured, job, "snapshot-endpoint-signatures", dispatcher);

        assertEquals("PERSON", schema.getNodeTypeMap().get("SPECIAL_PERSON").getParentType());
        assertEquals(java.util.Set.of("WORKS_FOR"), schema.getAllRelationshipTypes());
        assertTrue(schema.getPatterns().contains(
                "(SPECIAL_PERSON)-[:WORKS_FOR]->(ORGANIZATION)"));
        assertTrue(schema.getPatterns().contains(
                "(PERSON)-[:WORKS_FOR]->(ORGANIZATION)"));
    }

    @Test
    void endpointSignatureBatchesCoverLatePredicateTypesAndEvidenceWithOneAuthoritativeTable() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-endpoint-batches");
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);

        List<NodeType> configuredNodes = java.util.stream.IntStream.rangeClosed(1, 45)
                .mapToObj(index -> new NodeType(
                        "ENDPOINT_TYPE_" + index, "Endpoint type " + index + ".", null))
                .toList();
        GraphSchema configured = new GraphSchema(configuredNodes, null, null);
        Map<String, String> passages = new LinkedHashMap<>();
        for (int index = 1; index <= 49; index++) {
            passages.put("evidence-" + index,
                    "Evidence passage " + index + " states that a source points to a target.");
        }
        passages.put("evidence-48",
                "Evidence passage 48 states that a source points to a target. LATE_RELATION_MARKER.");
        passages.put("evidence-49",
                "Evidence passage 49 states that a source points to a target. LATE_RELATION_MARKER.");

        when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class), eq("llm"), same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenAnswer(invocation -> {
                    StructuredChatLanguageModel.Request request = invocation.getArgument(0);
                    String tool = request.tools().get(0).name();
                    String prompt = request.messages().get(request.messages().size() - 1).content();
                    if (CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME.equals(tool)) {
                        return classificationSampleResponse(request);
                    }
                    if (CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME.equals(tool)) {
                        return nodeTypes();
                    }
                    if (CorpusSchemaUnifier.WITNESS_TOOL_NAME.equals(tool)) {
                        // The late-relation windows each state one grounded witness; batches
                        // without that window legitimately abstain.
                        return witnessDiscoveryResponseLenient(request, List.of(new WitnessSpec(
                                "a source", "points to", "a target",
                                "Evidence passage 49 states that a source points to a target.")));
                    }
                    if (CorpusSchemaUnifier.RELATIONSHIP_TYPE_TOOL_NAME.equals(tool)) {
                        if (prompt.contains("LATE_RELATION_MARKER") || prompt.contains("points to")) {
                            return witnessConsolidationResponse(request, List.of(new ConsolidationSpec(
                                    "LATE_RELATION", "REFERENCE", "points to")));
                        }
                        return witnessConsolidationResponse(request, List.of());
                    }
                    if (CorpusSchemaUnifier.ENDPOINT_SIGNATURE_TOOL_NAME.equals(tool)) {
                        if (prompt.contains("ENDPOINT_TYPE_45")
                                && prompt.contains("ENDPOINT_TYPE_41")
                                && prompt.contains("Evidence passage 49")) {
                            return structuredSchemaResponse(
                                    CorpusSchemaUnifier.ENDPOINT_SIGNATURE_TOOL_NAME,
                                    Map.of("s", bindingOptionId(request,
                                                    "relationshipIds", "LATE_RELATION")
                                            + "|" + bindingOptionId(request,
                                                    "endpointIds", "ENDPOINT_TYPE_45")
                                            + "|" + bindingOptionId(request,
                                                    "endpointIds", "ENDPOINT_TYPE_41")
                                            + "|" + bindingOptionId(request,
                                                    "evidenceIds", "Evidence passage 49 states that a source points to a target. LATE_RELATION_MARKER.")));
                        }
                        return endpointSignatureResponse(request);
                    }
                    throw new AssertionError("Unexpected schema tool: " + tool);
                });

        GraphSchema schema = new CorpusSchemaUnifier().unify(
                passages,
                new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                configured, job, "snapshot-endpoint-batches", dispatcher);

        assertTrue(schema.getAllRelationshipTypes().contains("LATE_RELATION"));
        assertTrue(schema.getPatterns().contains(
                "(ENDPOINT_TYPE_45)-[:LATE_RELATION]->(ENDPOINT_TYPE_41)"),
                "source/target endpoint types from different bounded windows must share a table");
        ArgumentCaptor<StructuredChatLanguageModel.Request> requests =
                ArgumentCaptor.forClass(StructuredChatLanguageModel.Request.class);
        verify(dispatcher, org.mockito.Mockito.atLeastOnce()).promptStructuredWithCapacityFallback(
                requests.capture(), eq("llm"), same(job), any(CrawlLlmDispatcher.LlmCallScope.class));
        List<StructuredChatLanguageModel.Request> endpointRequests = requests.getAllValues().stream()
                .filter(request -> CorpusSchemaUnifier.ENDPOINT_SIGNATURE_TOOL_NAME.equals(
                        request.tools().get(0).name()))
                .toList();
        assertEquals(3, endpointRequests.size(),
                "49 evidence values require three 24-value pages; endpoint pages are zipped, not cross-producted");

        Set<String> endpointValues = new java.util.LinkedHashSet<>();
        Set<String> evidenceValues = new java.util.LinkedHashSet<>();
        for (StructuredChatLanguageModel.Request request : endpointRequests) {
            Map<String, Object> options = bindingOptions(request);
            List<?> endpoints = (List<?>) options.get("endpointIds");
            List<?> evidence = (List<?>) options.get("evidenceIds");
            assertTrue(endpoints.size() <= 40);
            assertTrue(evidence.size() <= 24);
            endpoints.forEach(value -> endpointValues.add(String.valueOf(value)));
            evidence.forEach(value -> evidenceValues.add(String.valueOf(value)));
            assertTrue(request.messages().get(request.messages().size() - 1).content()
                    .contains("LATE_RELATION"));
        }
        assertTrue(endpointValues.contains("ENDPOINT_TYPE_45"),
                "late endpoint type was not covered by a later authoritative option page");
        assertEquals(49, evidenceValues.size(),
                "all evidence passages must be covered, not dropped after the first 24");
        assertTrue(evidenceValues.stream().anyMatch(value -> value.contains("Evidence passage 49")));
        assertTrue(endpointRequests.get(endpointRequests.size() - 1).messages()
                .get(endpointRequests.get(endpointRequests.size() - 1).messages().size() - 1)
                .content().contains("Evidence passage 49"));
    }

    @Test
    void malformedOrReversedUnsupportedEndpointAbstainsNewRelationship() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-endpoint-abstain");
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class), eq("llm"), same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenAnswer(invocation -> {
                    StructuredChatLanguageModel.Request request = invocation.getArgument(0);
                    String tool = request.tools().get(0).name();
                    if (CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME.equals(tool)) {
                        return classificationSampleResponse(request);
                    }
                    if (CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME.equals(tool)) return nodeTypes();
                    if (CorpusSchemaUnifier.WITNESS_TOOL_NAME.equals(tool)) return witnessDiscoveryResponse(request,
                            List.of(new WitnessSpec("Alice", "works for", "Acme", "Alice works for Acme.")));
                    if (CorpusSchemaUnifier.RELATIONSHIP_TYPE_TOOL_NAME.equals(tool)) {
                        return witnessConsolidationResponse(request,
                                List.of(new ConsolidationSpec("WORKS_FOR", "AFFILIATION", "works for")));
                    }
                    return structuredSchemaResponse(CorpusSchemaUnifier.ENDPOINT_SIGNATURE_TOOL_NAME,
                            Map.of("s", bindingOptionId(request, "relationshipIds", "WORKS_FOR")
                                    + "|1|999|" + bindingOptionId(request, "evidenceIds", "Alice works for Acme.")));
                });

        GraphSchema schema = new CorpusSchemaUnifier().unify(
                Map.of("chunk", "Alice works for Acme."),
                new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                null, job, "snapshot-endpoint-abstain", dispatcher);

        assertTrue(schema.getAllRelationshipTypes().isEmpty());
        assertTrue(schema.getPatterns() == null || schema.getPatterns().isEmpty());
    }

    @Test
    void partialTopicBindingsRunFallbackOnlyForUnboundTopicText() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-mixed-topics");
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class), eq("llm"), same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenAnswer(invocation -> {
                    StructuredChatLanguageModel.Request request = invocation.getArgument(0);
                    String tool = request.tools().get(0).name();
                    if (CorpusSchemaUnifier.TOPIC_BINDING_TOOL_NAME.equals(tool)) {
                        String prompt = request.messages().get(request.messages().size() - 1).content();
                        if (prompt.contains("topic-1")) {
                            return structuredSchemaResponse(tool, Map.of("b",
                                    bindingOptionId(request, "nodeIds", "FIRST_RECORD") + "|"
                                            + bindingOptionId(request, "parentIds", "DOCUMENT") + "|"
                                            + bindingOptionId(request, "evidenceIds", "first record")
                                            + "|0|0|0|0|0"));
                        }
                        return structuredSchemaResponse(tool, Map.of("b",
                                bindingOptionId(request, "nodeIds", "SECOND_RECORD") + "|"
                                        + bindingOptionId(request, "parentIds", "DOCUMENT")
                                        + "|999|0|0|0|0|0"));
                    }
                    return CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME.equals(tool)
                            ? nodeTypes() : relationshipTypes();
                });
        CorpusTopicEvidence evidence = new CorpusTopicEvidence(
                "multilingual-e5-small", 384, 0.5, 0,
                List.of(
                        new CorpusTopicEvidence.Topic("topic-1", List.of("doc-1"),
                                List.of("chunk-1"), List.of("chunk-1"), Map.of("en", 1),
                                Map.of("en", List.of("first record"))),
                        new CorpusTopicEvidence.Topic("topic-2", List.of("doc-2"),
                                List.of("chunk-2"), List.of("chunk-2"), Map.of("en", 1),
                                Map.of("en", List.of("second record")))));
        CorpusSchemaUnifier.UnificationResult result = new CorpusSchemaUnifier()
                .unifyWithTopicBindings(
                        Map.of("chunk-1", "FIRST_TOPIC_MARKER first record.",
                                "chunk-2", "SECOND_TOPIC_MARKER second record."),
                        new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                        null, null, evidence, job, "snapshot-mixed-topics", dispatcher);

        assertEquals(1, result.topicEvidence().bindings().size());
        ArgumentCaptor<StructuredChatLanguageModel.Request> requests =
                ArgumentCaptor.forClass(StructuredChatLanguageModel.Request.class);
        verify(dispatcher, org.mockito.Mockito.atLeast(3)).promptStructuredWithCapacityFallback(
                requests.capture(), eq("llm"), same(job), any(CrawlLlmDispatcher.LlmCallScope.class));
        List<String> fallbackPrompts = requests.getAllValues().stream()
                .filter(request -> CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME.equals(
                        request.tools().get(0).name())
                        || CorpusSchemaUnifier.RELATIONSHIP_TYPE_TOOL_NAME.equals(
                        request.tools().get(0).name()))
                .map(request -> request.messages().get(1).content()).toList();
        assertTrue(fallbackPrompts.stream().anyMatch(prompt -> prompt.contains("SECOND_TOPIC_MARKER")));
        assertTrue(fallbackPrompts.stream().noneMatch(prompt -> prompt.contains("FIRST_TOPIC_MARKER")));
    }

    @Test
    void configuredUnconstrainedRelationshipContractIsNotRewrittenBySignaturePrepass() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-configured-contract");
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        stubTypePasses(dispatcher, job, nodeTypes(), relationshipTypes());
        GraphSchema configured = new GraphSchema(
                List.of(new NodeType("PERSON", "A person.", null)),
                List.of(new RelationshipType("WORKS_FOR", "A configured predicate.", null)),
                null);

        GraphSchema schema = new CorpusSchemaUnifier().unify(
                Map.of("chunk", "Alice works for Acme."),
                new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                configured, job, "snapshot-configured-contract", dispatcher);

        assertTrue(schema.getAllRelationshipTypes().contains("WORKS_FOR"));
        assertTrue(schema.getPatterns() == null || schema.getPatterns().isEmpty());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static StructuredChatLanguageModel.Response structuredSchemaResponse(
            String toolName, Map<String, Object> arguments) {
        return new StructuredChatLanguageModel.Response(
                "<native-tool-call>",
                "",
                List.of(new StructuredChatLanguageModel.ToolCall(
                        "schema-call", toolName, arguments)),
                List.of());
    }

    // Existing semantic-validator fixtures now supply provenance without changing their proposed
    // labels, parents or assertions. Consolidation deliberately remains the two-field response.
    static StructuredChatLanguageModel.Response withDiscoveryEvidence(
            StructuredChatLanguageModel.Request request, StructuredChatLanguageModel.Response response) {
        String prompt = request.messages().get(1).content();
        String marker = "UNTRUSTED_CORPUS_PASSAGES_JSON=";
        if (!prompt.contains(marker)) return response;
        try {
            String json = prompt.substring(prompt.indexOf(marker) + marker.length()).split("\\n", 2)[0];
            var window = MAPPER.readTree(json).get(0);
            List<?> rows = (List<?>) response.toolCalls().get(0).arguments().get("nodeTypes");
            List<Map<String, Object>> cited = new java.util.ArrayList<>();
            for (Object row : rows) {
                Map<String, Object> copy = new LinkedHashMap<>(asMap(row));
                if (!SchemaHierarchyVocabulary.isBaseEntityType(copy.get("label").toString())) {
                    copy.put("evidence", List.of(Map.of("sourceId", window.get("sourceId").asText(),
                            "quote", window.get("content").asText())));
                }
                cited.add(copy);
            }
            return structuredSchemaResponse(CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME, Map.of("nodeTypes", cited));
        } catch (Exception error) {
            throw new AssertionError("Unable to cite exact test request window", error);
        }
    }

    private static StructuredChatLanguageModel.Response nodeTypes(String... labels) {
        List<Map<String, Object>> definitions = java.util.Arrays.stream(labels)
                .filter(label -> !SchemaHierarchyVocabulary.isBaseEntityType(label))
                .map(label -> Map.<String, Object>of(
                        "label", label,
                        "parentType", parentTypeFor(label)))
                .toList();
        return structuredSchemaResponse(
                CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME,
                Map.of("nodeTypes", definitions));
    }

    private static StructuredChatLanguageModel.Response relationshipTypes(String... labels) {
        List<Map<String, Object>> definitions = java.util.Arrays.stream(labels)
                .map(label -> Map.<String, Object>of(
                        "type", label,
                        "connectionFamily", connectionFamilyFor(label)))
                .toList();
        return structuredSchemaResponse(
                CorpusSchemaUnifier.RELATIONSHIP_TYPE_TOOL_NAME,
                Map.of("relationshipTypes", definitions));
    }

    private static String parentTypeFor(String label) {
        return switch (label) {
            case "PUBLICATION" -> "DOCUMENT";
            case "CUSTOMER" -> "PERSON";
            case "OBSERVATION", "ASTRONOMICAL_OBSERVATION_2024_FINAL" -> "ACTIVITY";
            default -> "CONCEPT";
        };
    }

    private static String connectionFamilyFor(String label) {
        return switch (label) {
            case "AUTHORED_BY" -> "ATTRIBUTION";
            case "USES_INSTRUMENT" -> "DEPENDENCY";
            default -> "REFERENCE";
        };
    }

    /**
     * Explicit witness spec: the test states exactly which relationship the window asserts.
     * The quote must be an exact substring of the window text; witness ids are resolved
     * from the host's CHECKED RELATIONSHIP OBSERVATIONS block at consolidation time.
     */
    record WitnessSpec(String subject, String predicateText, String object, String quote) {
        Map<String, Object> row(String sourceId) {
            return Map.of("sourceId", sourceId, "subject", subject,
                    "predicateText", predicateText, "object", object,
                    "quote", quote, "qualifier", "");
        }
    }

    /** Canonicalization spec: explicit expected predicate label and trusted family. */
    record ConsolidationSpec(String type, String connectionFamily, String predicateText) {
        Map<String, Object> row(List<String> witnessIds) {
            return Map.of("type", type, "connectionFamily", connectionFamily,
                    "witnessIds", witnessIds);
        }
    }

    /**
     * Explicit witness discovery: emits exactly the witnesses the test declared, matched to
     * the prompt windows by quote containment. A declared witness whose quote is absent from
     * the prompt is a test bug and fails loudly.
     */
    @SuppressWarnings("unchecked")
    private static StructuredChatLanguageModel.Response witnessDiscoveryResponse(
            StructuredChatLanguageModel.Request request,
            List<WitnessSpec> specs) {
        String prompt = request.messages().get(request.messages().size() - 1).content();
        String marker = "UNTRUSTED_CORPUS_PASSAGES_JSON=";
        int start = prompt.indexOf(marker);
        if (start < 0) {
            // Not a witness discovery prompt; abstain so unrelated stub routing is untouched.
            return structuredSchemaResponse(CorpusSchemaUnifier.WITNESS_TOOL_NAME,
                    Map.of("witnesses", List.of()));
        }
        try {
            int jsonStart = start + marker.length();
            int jsonEnd = prompt.indexOf('\n', jsonStart);
            List<Map<String, String>> windows = (List<Map<String, String>>) (List<?>) MAPPER.readValue(
                    prompt.substring(jsonStart, jsonEnd < 0 ? prompt.length() : jsonEnd).trim(), List.class);
            List<Map<String, Object>> witnesses = new java.util.ArrayList<>();
            for (WitnessSpec spec : specs) {
                String sourceId = windows.stream()
                        .filter(window -> window.get("content").contains(spec.quote()))
                        .map(window -> window.get("sourceId"))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError(
                                "Witness quote is not an exact substring of any window: " + spec.quote()));
                witnesses.add(spec.row(sourceId));
            }
            return structuredSchemaResponse(CorpusSchemaUnifier.WITNESS_TOOL_NAME,
                    Map.of("witnesses", witnesses));
        } catch (java.io.IOException failure) {
            throw new AssertionError("Unable to parse witness discovery prompt", failure);
        }
    }

    /**
     * Multi-batch variant: a declared witness whose quote is absent from THIS request's
     * windows simply yields no witnesses for that batch (the model can only report what it
     * was shown), instead of failing the test. A quote that matches no window in ANY batch
     * still surfaces as an empty schema, which assertions catch.
     */
    private static StructuredChatLanguageModel.Response witnessDiscoveryResponseLenient(
            StructuredChatLanguageModel.Request request,
            List<WitnessSpec> specs) {
        String prompt = request.messages().get(request.messages().size() - 1).content();
        if (!prompt.contains("UNTRUSTED_CORPUS_PASSAGES_JSON=")) {
            return structuredSchemaResponse(CorpusSchemaUnifier.WITNESS_TOOL_NAME,
                    Map.of("witnesses", List.of()));
        }
        try {
            return witnessDiscoveryResponse(request, specs);
        } catch (AssertionError batchWithoutQuote) {
            return structuredSchemaResponse(CorpusSchemaUnifier.WITNESS_TOOL_NAME,
                    Map.of("witnesses", List.of()));
        }
    }

    /**
     * Explicit witness consolidation: emits exactly the canonicalization the test expects,
     * resolving witness ids from the host's CHECKED RELATIONSHIP OBSERVATIONS block by
     * predicateText. A spec citing wording no witness carries is a test bug and fails loudly.
     */
    @SuppressWarnings("unchecked")
    private static StructuredChatLanguageModel.Response witnessConsolidationResponse(
            StructuredChatLanguageModel.Request request,
            List<ConsolidationSpec> specs) {
        String prompt = request.messages().get(request.messages().size() - 1).content();
        String marker = "CHECKED RELATIONSHIP OBSERVATIONS";
        int start = prompt.indexOf(marker);
        if (start < 0) {
            // Not a witness consolidation prompt; abstain so unrelated stub routing is untouched.
            return structuredSchemaResponse(CorpusSchemaUnifier.RELATIONSHIP_TYPE_TOOL_NAME,
                    Map.of("relationshipTypes", List.of()));
        }
        try {
            String witnessJson = prompt.substring(start)
                    .substring(prompt.substring(start).indexOf('['));
            int jsonEnd = witnessJson.indexOf("\n\n");
            List<Map<String, Object>> witnesses = (List<Map<String, Object>>) (List<?>) MAPPER.readValue(
                    witnessJson.substring(0, jsonEnd < 0 ? witnessJson.length() : jsonEnd).trim(), List.class);
            List<Map<String, Object>> rows = new java.util.ArrayList<>();
            for (ConsolidationSpec spec : specs) {
                List<String> witnessIds = witnesses.stream()
                        .filter(witness -> String.valueOf(witness.get("predicateText"))
                                .equalsIgnoreCase(spec.predicateText()))
                        .map(witness -> String.valueOf(witness.get("witnessId")))
                        .toList();
                if (witnessIds.isEmpty()) {
                    throw new AssertionError("No checked witness carries predicateText '"
                            + spec.predicateText() + "' for consolidation spec " + spec.type());
                }
                rows.add(spec.row(witnessIds));
            }
            return structuredSchemaResponse(CorpusSchemaUnifier.RELATIONSHIP_TYPE_TOOL_NAME,
                    Map.of("relationshipTypes", rows));
        } catch (java.io.IOException failure) {
            throw new AssertionError("Unable to parse witness consolidation prompt", failure);
        }
    }

    private static void stubTypePasses(
            CrawlLlmDispatcher dispatcher,
            UnifiedCrawlJob job,
            StructuredChatLanguageModel.Response nodeResponse,
            StructuredChatLanguageModel.Response relationshipResponse) {
        stubTypePasses(dispatcher, job, nodeResponse, relationshipResponse,
                List.of(), List.of());
    }

    /** Witness-aware stub: discovery emits the declared witnesses; consolidation emits the
     * declared canonicalization with host-resolved witness citations. Empty specs mean the
     * model abstains (valid empty) at that stage. */
    private static void stubTypePasses(
            CrawlLlmDispatcher dispatcher,
            UnifiedCrawlJob job,
            StructuredChatLanguageModel.Response nodeResponse,
            StructuredChatLanguageModel.Response relationshipResponse,
            List<WitnessSpec> witnesses,
            List<ConsolidationSpec> consolidations) {
        when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class),
                eq("llm"), same(job), any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenAnswer(invocation -> {
                    StructuredChatLanguageModel.Request request = invocation.getArgument(0);
                    String tool = request.tools().get(0).name();
                    if (CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME.equals(tool)) {
                        return withDiscoveryEvidence(request, nodeResponse);
                    }
                    if (CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME.equals(tool)) {
                        return structuredSchemaResponse(
                                CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME,
                                Map.of("classifications", List.of()));
                    }
                    if (CorpusSchemaUnifier.WITNESS_TOOL_NAME.equals(tool)) {
                        return witnessDiscoveryResponse(request, witnesses);
                    }
                    if (CorpusSchemaUnifier.RELATIONSHIP_TYPE_TOOL_NAME.equals(tool)) {
                        return witnessConsolidationResponse(request, consolidations);
                    }
                    if (CorpusSchemaUnifier.TOPIC_BINDING_TOOL_NAME.equals(tool)) {
                        return structuredSchemaResponse(
                                CorpusSchemaUnifier.TOPIC_BINDING_TOOL_NAME,
                                Map.of("b", "0|0|0|0|0|0|0|0"));
                    }
                    if (CorpusSchemaUnifier.ENDPOINT_SIGNATURE_TOOL_NAME.equals(tool)) {
                        return endpointSignatureResponse(request);
                    }
                    throw new AssertionError("Unexpected schema type tool: " + tool);
                });
    }

    @SuppressWarnings("unchecked")
    private static StructuredChatLanguageModel.Response endpointSignatureResponse(
            StructuredChatLanguageModel.Request request) {
        String prompt = request.messages().get(request.messages().size() - 1).content();
        int start = prompt.lastIndexOf("BINDING_OPTION_IDS_JSON=");
        if (start < 0) {
            // Not an endpoint-signature prompt (e.g. a witness discovery retry): abstain.
            return structuredSchemaResponse(CorpusSchemaUnifier.ENDPOINT_SIGNATURE_TOOL_NAME,
                    Map.of("s", "0"));
        }
        try {
            Map<String, Object> root = MAPPER.readValue(
                    prompt.substring(start + "BINDING_OPTION_IDS_JSON=".length()).trim(), Map.class);
            List<?> relationships = (List<?>) root.get("relationshipIds");
            List<?> endpoints = (List<?>) root.get("endpointIds");
            List<?> evidence = (List<?>) root.get("evidenceIds");
            if (relationships == null || relationships.isEmpty()
                    || endpoints == null || endpoints.isEmpty()
                    || evidence == null || evidence.isEmpty()) {
                return structuredSchemaResponse(CorpusSchemaUnifier.ENDPOINT_SIGNATURE_TOOL_NAME,
                        Map.of("s", "0"));
            }
            int endpointTarget = endpoints.size() > 1 ? 2 : 1;
            String packed = java.util.stream.IntStream.rangeClosed(
                            1, Math.min(4, relationships.size()))
                    .mapToObj(index -> index + "|1|" + endpointTarget + "|1")
                    .collect(java.util.stream.Collectors.joining(";"));
            return structuredSchemaResponse(CorpusSchemaUnifier.ENDPOINT_SIGNATURE_TOOL_NAME,
                    Map.of("s", packed));
        } catch (java.io.IOException failure) {
            throw new AssertionError("Unable to parse endpoint signature options", failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bindingOptions(
            StructuredChatLanguageModel.Request request) {
        String prompt = request.messages().get(request.messages().size() - 1).content();
        String marker = "BINDING_OPTION_IDS_JSON=";
        int start = prompt.lastIndexOf(marker);
        assertTrue(start >= 0, prompt);
        try {
            return MAPPER.readValue(
                    prompt.substring(start + marker.length()).trim(), Map.class);
        } catch (java.io.IOException failure) {
            throw new AssertionError("Unable to parse binding options", failure);
        }
    }

    private static String bindingOptionId(
            StructuredChatLanguageModel.Request request,
            String group,
            String value) {
        String prompt = request.messages().get(request.messages().size() - 1).content();
        String marker = "BINDING_OPTION_IDS_JSON=";
        int start = prompt.lastIndexOf(marker);
        assertTrue(start >= 0, prompt);
        try {
            Map<String, Object> root = MAPPER.readValue(
                    prompt.substring(start + marker.length()).trim(), Map.class);
            List<?> options = (List<?>) root.get(group);
            int index = options.indexOf(value);
            if (index < 0) {
                throw new AssertionError(value + " not found in " + group + ": " + options);
            }
            return String.valueOf(index + 1);
        } catch (java.io.IOException e) {
            throw new AssertionError("Unable to parse binding options", e);
        }
    }

    @Test
    void compactOptionIdRangesRemainExactAtDecimalBoundaries() {
        for (int max : List.of(1, 9, 10, 19, 20, 40)) {
            String pattern = "^" + CorpusSchemaUnifier.compactPositiveOptionIdPattern(max) + "$";
            for (int id = 1; id <= max; id++) {
                assertTrue(String.valueOf(id).matches(pattern), pattern + " rejected " + id);
            }
            assertFalse("0".matches(pattern), pattern);
            assertFalse(String.valueOf(max + 1).matches(pattern), pattern);
        }
    }

    @Test
    void functionWordTokensAreNeverBindableSchemaCandidates() {
        // "THE" from the v26 incident: it passes SCHEMA_NAME_PATTERN (uppercase, 3+ letters)
        // but is a function word and must be excluded from candidate/option lists.
        assertFalse(CorpusSchemaUnifier.isBindableSchemaLabel("THE"));
        assertFalse(CorpusSchemaUnifier.isBindableSchemaLabel("AND"));
        assertFalse(CorpusSchemaUnifier.isBindableSchemaLabel("WITH"));
        assertTrue(CorpusSchemaUnifier.isBindableSchemaLabel("INSTRUMENT"));
        assertTrue(CorpusSchemaUnifier.isBindableSchemaLabel("ASTRONOMY"));
        // Pattern violations stay rejected as before.
        assertFalse(CorpusSchemaUnifier.isBindableSchemaLabel("the"));
        assertFalse(CorpusSchemaUnifier.isBindableSchemaLabel("OBSERVATORY-NAME"));
        assertFalse(CorpusSchemaUnifier.isBindableSchemaLabel(null));
    }

    /**
     * Extraction-side classification sample fixture: derive a valid classification row from the
     * entity marker in the request prompt, or answer with the caller's override response.
     */
    private static StructuredChatLanguageModel.Response classificationSampleResponse(
            StructuredChatLanguageModel.Request request,
            StructuredChatLanguageModel.Response... override) {
        if (override.length > 0) return override[0];
        String prompt = request.messages().get(request.messages().size() - 1).content();
        if (prompt.contains("Helios Dynamics")) {
            return structuredSchemaResponse(
                    CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME,
                    Map.of("classifications", List.of(
                            Map.of("name", "Helios Dynamics", "category", "Company",
                                    "parentType", "ORGANIZATION"),
                            Map.of("name", "Jordan Lee", "category", "Person",
                                    "parentType", "PERSON"))));
        }
        return structuredSchemaResponse(
                CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME,
                Map.of("classifications", List.of()));
    }

    @Test
    void classificationSampleProducesBootstrapCandidateThatSurvivesToConsolidation() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-bootstrap-company");
        when(job.getRequest()).thenReturn(UnifiedCrawlRequest.builder()
                .maxValidationRetries(1).build());
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        // The discovery call returns only baseline self-parents (the Gemma failure mode), so the
        // COMPANY bootstrap candidate must be the only source of the novel proposal.
        when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class), eq("llm"), same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenAnswer(invocation -> {
                    StructuredChatLanguageModel.Request request = invocation.getArgument(0);
                    String tool = request.tools().get(0).name();
                    if (CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME.equals(tool)) {
                        return classificationSampleResponse(request);
                    }
                    if (CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME.equals(tool)) {
                        return withDiscoveryEvidence(request, nodeTypes("PERSON"));
                    }
                    if (CorpusSchemaUnifier.WITNESS_TOOL_NAME.equals(tool)) {
                        return witnessDiscoveryResponse(request, List.of());
                    }
                    if (CorpusSchemaUnifier.RELATIONSHIP_TYPE_TOOL_NAME.equals(tool)) {
                        return witnessConsolidationResponse(request, List.of());
                    }
                    throw new AssertionError("Unexpected schema tool: " + tool);
                });

        Logger logger = (Logger) LoggerFactory.getLogger(CorpusSchemaUnifier.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        try {
        GraphSchema schema = new CorpusSchemaUnifier().unify(
                Map.of("window", "Helios Dynamics is a company founded by Jordan Lee."),
                new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                null, job, "snapshot-bootstrap-company", dispatcher);

            // Consolidation INPUT contains the bootstrap proposal pair (spec test a); the frozen
            // OUTPUT assertion lives in test (d).
            assertTrue(schema.getAllNodeLabels().containsAll(
                    SchemaHierarchyVocabulary.BASE_ENTITY_TYPES));
            // PERSON was a baseline duplicate and must be deduped, not proposed.
            assertEquals(null, schema.getNodeTypeMap().get("PERSON").getParentType());

            List<String> diagnostics = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.contains("[SCHEMA_DISCOVERY_OUTCOME]"))
                    .filter(message -> message.contains("pass=NODE_TYPES"))
                    .toList();
            assertEquals(1, diagnostics.size());
            assertTrue(diagnostics.get(0).contains("bootstrapCandidates=1"),
                    diagnostics.get(0));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }

    @Test
    void classificationSampleFailureFallsBackToDiscoveryOnlyPath() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-bootstrap-failure");
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class), eq("llm"), same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenAnswer(invocation -> {
                    StructuredChatLanguageModel.Request request = invocation.getArgument(0);
                    String tool = request.tools().get(0).name();
                    if (CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME.equals(tool)) {
                        throw new IllegalStateException(
                                "classification sample backend timed out");
                    }
                    if (CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME.equals(tool)) {
                        return withDiscoveryEvidence(request, nodeTypes("OBSERVATION"));
                    }
                    if (CorpusSchemaUnifier.WITNESS_TOOL_NAME.equals(tool)) {
                        return witnessDiscoveryResponse(request, List.of());
                    }
                    if (CorpusSchemaUnifier.RELATIONSHIP_TYPE_TOOL_NAME.equals(tool)) {
                        return witnessConsolidationResponse(request, List.of());
                    }
                    throw new AssertionError("Unexpected schema tool: " + tool);
                });

        Logger logger = (Logger) LoggerFactory.getLogger(CorpusSchemaUnifier.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
        try {
            GraphSchema schema = new CorpusSchemaUnifier().unify(
                    Map.of("window", "An observation was recorded by an astronomer."),
                    new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                    null, job, "snapshot-bootstrap-failure", dispatcher);

            // Discovery-only path unchanged: OBSERVATION still freezes normally.
            assertEquals("ACTIVITY", schema.getNodeTypeMap().get("OBSERVATION").getParentType());
            assertTrue(appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .anyMatch(message -> message.contains("continuing with schema discovery only")),
                    "the non-fatal classification failure must be logged");
            verify(dispatcher, times(4)).promptStructuredWithCapacityFallback(
                    any(StructuredChatLanguageModel.Request.class), eq("llm"), same(job),
                    any(CrawlLlmDispatcher.LlmCallScope.class));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }

    @Test
    void classificationSampleWithOnlyBaselineNounsProducesNoBootstrapCandidates() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-bootstrap-baseline-only");
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class), eq("llm"), same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenAnswer(invocation -> {
                    StructuredChatLanguageModel.Request request = invocation.getArgument(0);
                    String tool = request.tools().get(0).name();
                    if (CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME.equals(tool)) {
                        return structuredSchemaResponse(
                                CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME,
                                Map.of("classifications", List.of(
                                        Map.of("name", "Jordan Lee", "category", "PERSON",
                                                "parentType", "PERSON"),
                                        Map.of("name", "Acme", "category", "ORGANIZATION",
                                                "parentType", "ORGANIZATION"))));
                    }
                    if (CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME.equals(tool)) {
                        return withDiscoveryEvidence(request, nodeTypes("OBSERVATION"));
                    }
                    if (CorpusSchemaUnifier.WITNESS_TOOL_NAME.equals(tool)) {
                        return witnessDiscoveryResponse(request, List.of());
                    }
                    if (CorpusSchemaUnifier.RELATIONSHIP_TYPE_TOOL_NAME.equals(tool)) {
                        return witnessConsolidationResponse(request, List.of());
                    }
                    throw new AssertionError("Unexpected schema tool: " + tool);
                });

        Logger logger = (Logger) LoggerFactory.getLogger(CorpusSchemaUnifier.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        try {
            GraphSchema schema = new CorpusSchemaUnifier().unify(
                    Map.of("window", "Jordan Lee works at Acme recording an observation."),
                    new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                    null, job, "snapshot-bootstrap-baseline-only", dispatcher);

            Set<String> expected = new java.util.LinkedHashSet<>(
                    SchemaHierarchyVocabulary.BASE_ENTITY_TYPES);
            expected.add("OBSERVATION");
            assertEquals(expected, schema.getAllNodeLabels());
            List<String> diagnostics = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.contains("[SCHEMA_DISCOVERY_OUTCOME]"))
                    .toList();
            assertTrue(diagnostics.stream().allMatch(message ->
                            message.contains("bootstrapCandidates=0")),
                    diagnostics.toString());
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }

    @Test
    void consolidationReceivesBootstrapProposalAlongsideDiscoveryProposals() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("job-bootstrap-consolidation");
        when(job.getRequest()).thenReturn(UnifiedCrawlRequest.builder()
                .maxValidationRetries(1).build());
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);
        when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class), eq("llm"), same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenAnswer(invocation -> {
                    StructuredChatLanguageModel.Request request = invocation.getArgument(0);
                    String tool = request.tools().get(0).name();
                    if (CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME.equals(tool)) {
                        return classificationSampleResponse(request);
                    }
                    if (CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME.equals(tool)) {
                        String prompt = request.messages().get(1).content();
                        if (prompt.contains("UNTRUSTED BATCH PROPOSALS")) {
                            // Consolidation copies the exact proposal pair the batches supplied.
                            return structuredSchemaResponse(
                                    CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME,
                                    Map.of("nodeTypes", List.of(
                                            Map.of("label", "COMPANY",
                                                    "parentType", "ORGANIZATION"))));
                        }
                        // Batch discovery abstains (the Gemma failure mode).
                        return withDiscoveryEvidence(request, nodeTypes());
                    }
                    if (CorpusSchemaUnifier.WITNESS_TOOL_NAME.equals(tool)) {
                        return witnessDiscoveryResponse(request, List.of());
                    }
                    if (CorpusSchemaUnifier.RELATIONSHIP_TYPE_TOOL_NAME.equals(tool)) {
                        return witnessConsolidationResponse(request, List.of());
                    }
                    throw new AssertionError("Unexpected schema tool: " + tool);
                });

        GraphSchema schema = new CorpusSchemaUnifier().unify(
                Map.of("window", "Helios Dynamics is a company founded by Jordan Lee."),
                new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                null, job, "snapshot-bootstrap-consolidation", dispatcher);

        // Consolidation copied the proposal pair exactly, so COMPANY/ORGANIZATION freezes.
        assertEquals("ORGANIZATION", schema.getNodeTypeMap().get("COMPANY").getParentType());

        ArgumentCaptor<StructuredChatLanguageModel.Request> requests =
                ArgumentCaptor.forClass(StructuredChatLanguageModel.Request.class);
        verify(dispatcher, times(4)).promptStructuredWithCapacityFallback(
                requests.capture(), eq("llm"), same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class));
        List<StructuredChatLanguageModel.Request> consolidation = requests.getAllValues().stream()
                .filter(request -> request.messages().get(1).content()
                        .contains("UNTRUSTED BATCH PROPOSALS"))
                .toList();
        assertEquals(1, consolidation.size());
        String consolidationPrompt = consolidation.get(0).messages().get(1).content();
        assertTrue(consolidationPrompt.contains("COMPANY"),
                "consolidation input must contain the bootstrap COMPANY proposal: "
                        + consolidationPrompt);
        assertTrue(consolidationPrompt.contains("ORGANIZATION"));
    }
}
