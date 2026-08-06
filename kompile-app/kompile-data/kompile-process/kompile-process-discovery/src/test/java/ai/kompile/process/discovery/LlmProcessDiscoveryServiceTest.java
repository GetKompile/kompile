/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.process.discovery;

import ai.kompile.core.graphrag.agent.ExtractionLlmService;
import ai.kompile.core.graphrag.agent.ExtractionLlmServiceRegistry;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class LlmProcessDiscoveryServiceTest {

    @Mock KnowledgeGraphService knowledgeGraphService;
    @Mock ExtractionLlmServiceRegistry llmServiceRegistry;
    @Mock ExtractionLlmService llmService;

    LlmProcessDiscoveryService service;

    @BeforeEach
    void setup() {
        // Default: getChildren returns empty for any node (tests that need snippets override this)
        lenient().when(knowledgeGraphService.getChildren(anyString())).thenReturn(List.of());
        service = new LlmProcessDiscoveryService(knowledgeGraphService, llmServiceRegistry);
    }

    // ── Helper builders ─────────────────────────────────────────────────

    private GraphNode makeNode(String id, NodeLevel level, String title, String description, String entityType) {
        GraphNode node = new GraphNode();
        node.setNodeId(id);
        node.setNodeType(level);
        node.setTitle(title);
        node.setDescription(description);
        if (entityType != null) {
            node.setMetadataJson("{\"entity_type\":\"" + entityType + "\"}");
        }
        return node;
    }

    private GraphNode makeSnippet(String id, String title, String contentPreview, GraphNode parent) {
        GraphNode snippet = new GraphNode();
        snippet.setNodeId(id);
        snippet.setNodeType(NodeLevel.SNIPPET);
        snippet.setTitle(title);
        snippet.setContentPreview(contentPreview);
        snippet.setParentId(parent != null ? parent.getNodeId() : null);
        return snippet;
    }

    private GraphEdge makeEdge(GraphNode source, GraphNode target, String label) {
        GraphEdge edge = new GraphEdge();
        edge.setSourceNode(source);
        edge.setTargetNode(target);
        edge.setLabel(label);
        return edge;
    }

    // ── Tests ───────────────────────────────────────────────────────────

    @Nested
    class BasicProcessExtraction {

        @Test
        void discoversProcessFromLlmResponse() {
            // Setup graph context
            GraphNode emailNode = makeNode("email-1", NodeLevel.DOCUMENT, "Onboarding Instructions",
                    "Step 1: Fill out HR form. Step 2: IT sets up laptop. Step 3: Manager approves.", "EMAIL_MESSAGE");
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                    .thenReturn(List.of(emailNode));
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of());
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.TABLE), anyInt()))
                    .thenReturn(List.of());
            when(knowledgeGraphService.getEdgesForNode("email-1")).thenReturn(List.of());

            // Mock LLM
            when(llmServiceRegistry.getOrFallback(null)).thenReturn(llmService);
            when(llmService.getId()).thenReturn("test-llm");
            when(llmService.complete(anyString())).thenReturn("""
                    {
                      "processes": [
                        {
                          "name": "Employee Onboarding",
                          "description": "New employee onboarding workflow",
                          "confidence": 0.9,
                          "discoverySource": "LLM_ANALYSIS",
                          "evidence": ["Email describes 3-step onboarding process"],
                          "sourceNodeIds": ["email-1"],
                          "phases": [
                            {
                              "name": "Setup",
                              "description": "Initial setup phase",
                              "steps": [
                                {
                                  "name": "Fill out HR form",
                                  "stepType": "HUMAN",
                                  "description": "New employee fills out HR paperwork",
                                  "actor": "New Employee",
                                  "nodeId": "email-1"
                                },
                                {
                                  "name": "IT laptop setup",
                                  "stepType": "AUTO",
                                  "description": "IT department provisions laptop",
                                  "actor": "IT Department"
                                }
                              ]
                            },
                            {
                              "name": "Approval",
                              "description": "Manager approval phase",
                              "steps": [
                                {
                                  "name": "Manager approval",
                                  "stepType": "APPROVE",
                                  "description": "Manager approves onboarding completion",
                                  "actor": "Manager"
                                }
                              ]
                            }
                          ]
                        }
                      ]
                    }
                    """);

            List<ProcessSuggestion> suggestions = service.discoverProcesses(null, Map.of());

            assertEquals(1, suggestions.size());
            ProcessSuggestion s = suggestions.get(0);
            assertEquals("Employee Onboarding", s.getName());
            assertEquals("LLM_ANALYSIS", s.getDiscoverySource());
            assertEquals(0.9, s.getConfidence(), 0.01);
            assertEquals(2, s.getPhases().size());
            assertEquals("Setup", s.getPhases().get(0).getName());
            assertEquals(2, s.getPhases().get(0).getSteps().size());
            assertEquals("HUMAN", s.getPhases().get(0).getSteps().get(0).getStepType());
            assertEquals("AUTO", s.getPhases().get(0).getSteps().get(1).getStepType());
            assertEquals("Approval", s.getPhases().get(1).getName());
            assertEquals("APPROVE", s.getPhases().get(1).getSteps().get(0).getStepType());
            assertEquals(List.of("email-1"), s.getSourceGraphNodeIds());
            assertFalse(s.getEvidence().isEmpty());
        }

        @Test
        void productionSplitExtractsStepsBeforeGroupingAndMapsOnlyEngineOwnedIds() {
            GraphNode document = makeNode("doc-process", NodeLevel.DOCUMENT, "Procedure",
                    "Fill out HR form. Manager approves the request.", null);
            GraphNode manager = makeNode("actor-manager", NodeLevel.ENTITY, "Manager",
                    "Approval role", "PERSON");
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                    .thenReturn(List.of(document));
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(manager));
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.TABLE), anyInt()))
                    .thenReturn(List.of());
            when(knowledgeGraphService.getEdgesForNode("doc-process")).thenReturn(List.of());
            when(knowledgeGraphService.getEdgesForNode("actor-manager")).thenReturn(List.of());
            when(llmServiceRegistry.getOrFallback(null)).thenReturn(llmService);
            when(llmService.getId()).thenReturn("test-llm");
            when(llmService.complete(anyString())).thenReturn("""
                    {"steps":[
                      {"name":"Fill out HR form","description":"Complete the form",
                       "stepType":"HUMAN","evidence":"Fill out HR form","sourceNodeId":"doc-process"},
                      {"name":"Approve request","description":"Approve the request",
                       "stepType":"APPROVE","evidence":"Manager approves the request",
                       "sourceNodeId":"doc-process"}]}
                    """, """
                    {"processes":[{"name":"Request procedure","description":"A supported procedure",
                      "confidence":0.91,"phaseName":"Execution",
                      "orderedStepIds":["step-1","step-2"]}]}
                    """, """
                    {"assignment":{"actorOrdinal":null,"confidence":0.9,
                      "reason":"no actor is explicit"}}
                    """, """
                    {"assignment":{"actorOrdinal":1,"confidence":0.95,
                      "reason":"Manager is the grammatical subject"}}
                    """);

            List<ProcessSuggestion> suggestions = service.discoverProcesses(null, Map.of());

            assertEquals(1, suggestions.size());
            ProcessSuggestion suggestion = suggestions.get(0);
            assertEquals(1, suggestion.getPhases().size());
            assertEquals(List.of("Fill out HR form", "Approve request"),
                    suggestion.getPhases().get(0).getSteps().stream()
                            .map(ProcessSuggestion.SuggestedStep::getName).toList());
            assertEquals("Manager",
                    suggestion.getPhases().get(0).getSteps().get(1).getSuggestedAssignee());
            assertEquals(List.of("doc-process"), suggestion.getSourceGraphNodeIds());
            assertEquals(List.of("Fill out HR form", "Manager approves the request"),
                    suggestion.getEvidence());

            ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
            verify(llmService, times(4)).complete(prompts.capture());
            assertFalse(prompts.getAllValues().get(0).contains("ENGINE-FIXED ACTOR BALLOT"));
            assertTrue(prompts.getAllValues().get(0).contains("Do not name a process"));
            assertTrue(prompts.getAllValues().get(1).contains("orderedStepIds"));
            assertTrue(prompts.getAllValues().get(1).contains("copy \"step-2\""));
            assertTrue(prompts.getAllValues().get(1).contains("belongs to one coherent process"));
            assertFalse(prompts.getAllValues().get(1).contains("ENGINE-FIXED ACTOR BALLOT"));
            assertTrue(prompts.getAllValues().get(2).contains("ENGINE-FIXED ACTOR BALLOT"));
            assertTrue(prompts.getAllValues().get(2).contains("ordinal=1 | name=Manager"));
            assertTrue(prompts.getAllValues().get(2).contains("not the earlier co-mention"));
            assertTrue(prompts.getAllValues().get(3).contains("ENGINE LEXICAL CANDIDATE HINT"));
            assertTrue(prompts.getAllValues().get(3).contains("actorOrdinal must be 1"));
            assertFalse(prompts.getAllValues().get(1).contains("doc-process"),
                    "the grouping model receives fixed activities, not copyable graph IDs");
        }

        @Test
        void unambiguousActorHintGetsOneBoundedCorrectionInsteadOfAcceptingCopiedNulls() {
            GraphNode document = makeNode("doc-process", NodeLevel.DOCUMENT, "Procedure",
                    "Maya exports the forecast.", null);
            GraphNode maya = makeNode("actor-maya", NodeLevel.ENTITY, "Maya",
                    "Forecast analyst", "PERSON");
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                    .thenReturn(List.of(document));
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of(maya));
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.TABLE), anyInt()))
                    .thenReturn(List.of());
            when(knowledgeGraphService.getEdgesForNode(anyString())).thenReturn(List.of());
            when(llmServiceRegistry.getOrFallback(null)).thenReturn(llmService);
            when(llmService.getId()).thenReturn("test-llm");
            when(llmService.complete(anyString())).thenReturn("""
                    {"steps":[{"name":"Maya exports the forecast","description":"Export forecast",
                      "stepType":"HUMAN","evidence":"Maya exports the forecast.",
                      "sourceNodeId":"doc-process"}]}
                    """, """
                    {"processes":[{"name":"Forecast export","description":"Export workflow",
                      "confidence":0.9,"orderedStepIds":["step-1"]}]}
                    """, """
                    {"assignment":{"actorOrdinal":null,"confidence":null,"reason":null}}
                    """, """
                    {"assignment":{"actorOrdinal":1,"confidence":1.0,
                      "reason":"the fixed activity explicitly starts with Maya"}}
                    """);

            List<ProcessSuggestion> suggestions = service.discoverProcesses(null, Map.of());

            assertEquals("Maya", suggestions.get(0).getPhases().get(0).getSteps().get(0)
                    .getSuggestedAssignee());
            ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
            verify(llmService, times(4)).complete(prompts.capture());
            assertTrue(prompts.getAllValues().get(2).contains("SAFE SHAPE FOR THIS BALLOT"));
            assertTrue(prompts.getAllValues().get(3).contains("VALIDATION CORRECTION"));
            assertTrue(prompts.getAllValues().get(3).contains("do not copy null"));
        }

        @Test
        void fixedActivityNameOutranksAnEarlierActorInCoordinatedEvidence() {
            GraphNode erin = makeNode("actor-erin", NodeLevel.ENTITY, "Erin", null, "PERSON");
            GraphNode finance = makeNode("actor-finance", NodeLevel.ENTITY, "Finance", null, "TEAM");
            LlmProcessDiscoveryService.GraphContext context = new LlmProcessDiscoveryService.GraphContext(
                    List.of(erin, finance), List.of(), List.of());
            LlmProcessDiscoveryService.DiscoveredStep step =
                    new LlmProcessDiscoveryService.DiscoveredStep(
                            "step-4", "Finance publishes the forecast", "Publish forecast",
                            "HUMAN", "doc-process",
                            "Finally, Erin approves it and Finance publishes the forecast.");

            String prompt = service.buildActorAssignmentPrompt(context, step);

            assertTrue(prompt.contains("ordinal=2 | name=Finance"));
            assertTrue(prompt.contains("actorOrdinal must be 2"));
            assertFalse(prompt.contains("none or ambiguous"));
        }

        @Test
        void explicitEmptyStepBallotGetsOneGroundedValidationRetry() {
            GraphNode document = makeNode("doc-process", NodeLevel.DOCUMENT, "Forecast procedure",
                    "Maya exports the forecast.", null);
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                    .thenReturn(List.of(document));
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of());
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.TABLE), anyInt()))
                    .thenReturn(List.of());
            when(knowledgeGraphService.getEdgesForNode("doc-process")).thenReturn(List.of());
            when(llmServiceRegistry.getOrFallback(null)).thenReturn(llmService);
            when(llmService.getId()).thenReturn("test-llm");
            when(llmService.complete(anyString())).thenReturn("""
                    {"steps":[]}
                    """, """
                    {"steps":[{"name":"Export forecast","description":"Export the forecast",
                      "stepType":"HUMAN","evidence":"Maya exports the forecast.",
                      "sourceNodeId":"doc-process"}]}
                    """, """
                    {"processes":[{"name":"Forecast procedure","description":"Export workflow",
                      "confidence":0.9,"orderedStepIds":["step-1"]}]}
                    """);

            List<ProcessSuggestion> suggestions = service.discoverProcesses(null, Map.of());

            assertEquals(1, suggestions.size());
            assertEquals("Export forecast",
                    suggestions.get(0).getPhases().get(0).getSteps().get(0).getName());
            ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
            verify(llmService, times(3)).complete(prompts.capture());
            assertTrue(prompts.getAllValues().get(0).contains("Begin exactly with {\"steps\":["));
            assertTrue(prompts.getAllValues().get(0).contains("Coordinated action verbs are separate activities"));
            assertTrue(prompts.getAllValues().get(1).contains("VALIDATION RETRY"));
            assertTrue(prompts.getAllValues().get(1).contains("Maya exports the forecast"));
        }

        @Test
        void coordinatedClauseEvidenceIsRebasedToTheExactContainingSourceSentence() {
            String source = "Finally, Erin approves it and Finance publishes the consolidated forecast.";
            GraphNode snippet = makeNode("snippet-process", NodeLevel.SNIPPET, "Workflow", source, null);
            LlmProcessDiscoveryService.GraphContext context =
                    new LlmProcessDiscoveryService.GraphContext(List.of(), List.of(snippet), List.of());

            List<LlmProcessDiscoveryService.DiscoveredStep> steps =
                    service.parseStepExtractionResponse("""
                            {"steps":[
                              {"name":"Erin approves it","description":"Approve forecast",
                               "stepType":"APPROVE","evidence":"Finally, Erin approves it."},
                              {"name":"Finance publishes the consolidated forecast",
                               "description":"Publish forecast","stepType":"TOOL_CALL",
                               "evidence":"And Finance publishes the consolidated forecast."}]}
                            """, context);

            assertEquals(2, steps.size());
            assertEquals(List.of(source, source),
                    steps.stream().map(LlmProcessDiscoveryService.DiscoveredStep::evidence).toList());
        }

        @Test
        void organizationNormalizesOnlyUnambiguousEngineBallotOrdinals() {
            LlmProcessDiscoveryService.GraphContext context =
                    new LlmProcessDiscoveryService.GraphContext(List.of(), List.of(), List.of());
            List<LlmProcessDiscoveryService.DiscoveredStep> steps = List.of(
                    new LlmProcessDiscoveryService.DiscoveredStep(
                            "step-1", "Export", "Export", "HUMAN", null, "Export source"),
                    new LlmProcessDiscoveryService.DiscoveredStep(
                            "step-2", "Review", "Review", "HUMAN", null, "Review source"));

            List<ProcessSuggestion> suggestions = service.parseOrganizationResponse("""
                    {"processes":[{"name":"Workflow","description":"Ordered workflow",
                      "confidence":0.9,"orderedStepIds":[1,"2","invented"]}]}
                    """, context, steps);

            assertEquals(1, suggestions.size());
            assertEquals(List.of("Export", "Review"), suggestions.get(0).getPhases().get(0)
                    .getSteps().stream().map(ProcessSuggestion.SuggestedStep::getName).toList());
        }

        @Test
        void unsupportedStepEvidenceStopsBeforeTheOrganizationCall() {
            GraphNode document = makeNode("doc-process", NodeLevel.DOCUMENT, "Procedure",
                    "The source states one concrete action.", null);
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                    .thenReturn(List.of(document));
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of());
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.TABLE), anyInt()))
                    .thenReturn(List.of());
            when(knowledgeGraphService.getEdgesForNode("doc-process")).thenReturn(List.of());
            when(llmServiceRegistry.getOrFallback(null)).thenReturn(llmService);
            when(llmService.getId()).thenReturn("test-llm");
            when(llmService.complete(anyString())).thenReturn("""
                    {"steps":[{"name":"Invented action","description":"not in source",
                      "stepType":"HUMAN","evidence":"fabricated evidence"}]}
                    """);

            assertTrue(service.discoverProcesses(null, Map.of()).isEmpty());
            verify(llmService, times(1)).complete(anyString());
        }

        @Test
        void respectsMinConfidenceFilter() {
            GraphNode node = makeNode("doc-1", NodeLevel.DOCUMENT, "Notes", "Some notes", null);
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                    .thenReturn(List.of(node));
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of());
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.TABLE), anyInt()))
                    .thenReturn(List.of());
            when(knowledgeGraphService.getEdgesForNode("doc-1")).thenReturn(List.of());

            when(llmServiceRegistry.getOrFallback(null)).thenReturn(llmService);
            when(llmService.getId()).thenReturn("test-llm");
            when(llmService.complete(anyString())).thenReturn("""
                    {
                      "processes": [
                        {
                          "name": "Low Confidence Process",
                          "description": "Maybe a process",
                          "confidence": 0.3,
                          "phases": [{"name": "Main", "steps": [{"name": "Do thing", "stepType": "HUMAN", "description": "do it"}]}]
                        },
                        {
                          "name": "High Confidence Process",
                          "description": "Definitely a process",
                          "confidence": 0.9,
                          "phases": [{"name": "Main", "steps": [{"name": "Do thing", "stepType": "HUMAN", "description": "do it"}]}]
                        }
                      ]
                    }
                    """);

            List<ProcessSuggestion> suggestions = service.discoverProcesses(null,
                    Map.of("minConfidence", 0.5));

            assertEquals(1, suggestions.size());
            assertEquals("High Confidence Process", suggestions.get(0).getName());
        }
    }

    @Nested
    class HierarchicalProcesses {

        @Test
        void discoversParentChildProcesses() {
            GraphNode emailNode = makeNode("email-budget", NodeLevel.DOCUMENT, "Budget Review Instructions",
                    "Please fill out the attached budget spreadsheet and submit for approval", "EMAIL_MESSAGE");
            GraphNode spreadsheetNode = makeNode("budget-xlsx", NodeLevel.TABLE, "Q1 Budget.xlsx",
                    "Quarterly budget spreadsheet with formulas", "SPREADSHEET");

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                    .thenReturn(List.of(emailNode));
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of());
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.TABLE), anyInt()))
                    .thenReturn(List.of(spreadsheetNode));
            when(knowledgeGraphService.getEdgesForNode("email-budget"))
                    .thenReturn(List.of(makeEdge(emailNode, spreadsheetNode, "HAS_ATTACHMENT")));
            when(knowledgeGraphService.getEdgesForNode("budget-xlsx")).thenReturn(List.of());

            when(llmServiceRegistry.getOrFallback(null)).thenReturn(llmService);
            when(llmService.getId()).thenReturn("test-llm");
            when(llmService.complete(anyString())).thenReturn("""
                    {
                      "processes": [
                        {
                          "name": "Quarterly Budget Review",
                          "description": "End-to-end quarterly budget review process",
                          "confidence": 0.88,
                          "sourceNodeIds": ["email-budget", "budget-xlsx"],
                          "phases": [
                            {
                              "name": "Distribution",
                              "steps": [
                                {"name": "Send budget template", "stepType": "AUTO", "description": "Distribute spreadsheet to departments", "actor": "Finance"}
                              ]
                            },
                            {
                              "name": "Collection",
                              "steps": [
                                {"name": "Fill in figures", "stepType": "HUMAN", "description": "Department heads enter their budget data", "actor": "Department Heads"}
                              ]
                            },
                            {
                              "name": "Approval",
                              "steps": [
                                {"name": "CFO review", "stepType": "APPROVE", "description": "CFO reviews consolidated budget", "actor": "CFO"}
                              ]
                            }
                          ],
                          "childProcesses": [
                            {
                              "name": "Budget Spreadsheet Computation",
                              "description": "Calculate totals and variances in budget spreadsheet",
                              "confidence": 0.82,
                              "sourceNodeIds": ["budget-xlsx"],
                              "phases": [
                                {
                                  "name": "Compute",
                                  "steps": [
                                    {"name": "Enter department data", "stepType": "HUMAN", "description": "Input raw budget figures"},
                                    {"name": "Run formulas", "stepType": "EXCEL_COMPUTE", "description": "Spreadsheet calculates totals and variances", "nodeId": "budget-xlsx"},
                                    {"name": "Validate results", "stepType": "APPROVE", "description": "Review computed totals for accuracy"}
                                  ]
                                }
                              ]
                            }
                          ]
                        }
                      ]
                    }
                    """);

            List<ProcessSuggestion> suggestions = service.discoverProcesses(null, Map.of());

            assertEquals(1, suggestions.size());
            ProcessSuggestion parent = suggestions.get(0);
            assertEquals("Quarterly Budget Review", parent.getName());
            assertEquals(3, parent.getPhases().size());

            // Verify child process
            assertNotNull(parent.getChildSuggestions());
            assertEquals(1, parent.getChildSuggestions().size());
            ProcessSuggestion child = parent.getChildSuggestions().get(0);
            assertEquals("Budget Spreadsheet Computation", child.getName());
            assertEquals("Quarterly Budget Review", child.getParentSuggestionId());
            assertEquals(1, child.getPhases().size());
            assertEquals(3, child.getPhases().get(0).getSteps().size());
            assertEquals("EXCEL_COMPUTE", child.getPhases().get(0).getSteps().get(1).getStepType());
        }
    }

    @Nested
    class EdgeCases {

        @Test
        void returnsEmptyWhenNoLlmAvailable() {
            when(llmServiceRegistry.getOrFallback(null)).thenReturn(null);

            List<ProcessSuggestion> suggestions = service.discoverProcesses(null, Map.of());

            assertTrue(suggestions.isEmpty());
        }

        @Test
        void returnsEmptyWhenGraphIsEmpty() {
            when(llmServiceRegistry.getOrFallback(null)).thenReturn(llmService);
            when(knowledgeGraphService.searchNodes(eq(""), any(NodeLevel.class), anyInt()))
                    .thenReturn(List.of());

            List<ProcessSuggestion> suggestions = service.discoverProcesses(null, Map.of());

            assertTrue(suggestions.isEmpty());
            verify(llmService, never()).complete(anyString());
        }

        @Test
        void handlesLlmError() {
            GraphNode node = makeNode("doc-1", NodeLevel.DOCUMENT, "Test", "test", null);
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                    .thenReturn(List.of(node));
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of());
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.TABLE), anyInt()))
                    .thenReturn(List.of());
            when(knowledgeGraphService.getEdgesForNode("doc-1")).thenReturn(List.of());

            when(llmServiceRegistry.getOrFallback(null)).thenReturn(llmService);
            when(llmService.getId()).thenReturn("test-llm");
            when(llmService.complete(anyString())).thenThrow(new RuntimeException("LLM timeout"));

            List<ProcessSuggestion> suggestions = service.discoverProcesses(null, Map.of());

            assertTrue(suggestions.isEmpty());
        }

        @Test
        void handlesInvalidJsonResponse() {
            GraphNode node = makeNode("doc-1", NodeLevel.DOCUMENT, "Test", "test", null);
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                    .thenReturn(List.of(node));
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of());
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.TABLE), anyInt()))
                    .thenReturn(List.of());
            when(knowledgeGraphService.getEdgesForNode("doc-1")).thenReturn(List.of());

            when(llmServiceRegistry.getOrFallback(null)).thenReturn(llmService);
            when(llmService.getId()).thenReturn("test-llm");
            when(llmService.complete(anyString())).thenReturn("This is not JSON at all.");

            List<ProcessSuggestion> suggestions = service.discoverProcesses(null, Map.of());

            assertTrue(suggestions.isEmpty());
        }

        @Test
        void handlesMarkdownFencedResponse() {
            GraphNode node = makeNode("doc-1", NodeLevel.DOCUMENT, "Test", "test", null);
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                    .thenReturn(List.of(node));
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of());
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.TABLE), anyInt()))
                    .thenReturn(List.of());
            when(knowledgeGraphService.getEdgesForNode("doc-1")).thenReturn(List.of());

            when(llmServiceRegistry.getOrFallback(null)).thenReturn(llmService);
            when(llmService.getId()).thenReturn("test-llm");
            when(llmService.complete(anyString())).thenReturn("""
                    ```json
                    {
                      "processes": [
                        {
                          "name": "Simple Process",
                          "description": "A process",
                          "confidence": 0.7,
                          "phases": [{"name": "Main", "steps": [{"name": "Step 1", "stepType": "HUMAN", "description": "Do it"}]}]
                        }
                      ]
                    }
                    ```
                    """);

            List<ProcessSuggestion> suggestions = service.discoverProcesses(null, Map.of());

            assertEquals(1, suggestions.size());
            assertEquals("Simple Process", suggestions.get(0).getName());
        }
    }

    @Nested
    class PromptConstruction {

        @Test
        void outputContractContainsNoCopyableExampleProcess() {
            String instructions = LlmProcessDiscoveryService.getProcessDiscoveryPromptInstructions();

            assertTrue(instructions.contains("{\"processes\":[]}"));
            assertTrue(instructions.contains("Never copy a value from this output contract"));
            assertFalse(instructions.contains("Quarterly Budget Review"));
            assertFalse(instructions.contains("Distribute budget template"));
            assertFalse(instructions.contains("Department Heads"));
            assertFalse(instructions.contains("Spreadsheet Computation"));
            assertFalse(instructions.contains("node-id-1"));
        }

        @Test
        void includesNodeDetailsInPrompt() {
            GraphNode emailNode = makeNode("email-1", NodeLevel.DOCUMENT, "Budget Instructions",
                    "Please fill out the attached spreadsheet", "EMAIL_MESSAGE");
            emailNode.setContentPreview("Dear Team, attached is the Q1 budget template...");

            GraphNode spreadsheet = makeNode("sheet-1", NodeLevel.TABLE, "Q1 Budget.xlsx",
                    "Budget template", "SPREADSHEET");

            GraphEdge edge = makeEdge(emailNode, spreadsheet, "HAS_ATTACHMENT");

            when(knowledgeGraphService.getNode("email-1")).thenReturn(Optional.of(emailNode));
            when(knowledgeGraphService.getConnectedNodes("email-1", 1)).thenReturn(List.of(spreadsheet));
            when(knowledgeGraphService.getEdgesForNode("email-1")).thenReturn(List.of(edge));
            when(knowledgeGraphService.getEdgesForNode("sheet-1")).thenReturn(List.of());

            when(llmServiceRegistry.getOrFallback(null)).thenReturn(llmService);
            when(llmService.getId()).thenReturn("test-llm");
            when(llmService.complete(anyString())).thenReturn("{\"processes\":[]}");

            service.discoverProcesses(List.of("email-1"), Map.of());

            ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
            verify(llmService).complete(promptCaptor.capture());
            String prompt = promptCaptor.getValue();

            // Verify prompt contains node details
            assertTrue(prompt.contains("Budget Instructions"), "Prompt should contain node title");
            assertTrue(prompt.contains("EMAIL_MESSAGE"), "Prompt should contain entity type");
            assertTrue(prompt.contains("Q1 Budget.xlsx"), "Prompt should contain connected node title");
            assertTrue(prompt.contains("HAS_ATTACHMENT"), "Prompt should contain edge label");
            assertTrue(prompt.contains("KNOWLEDGE GRAPH NODES"), "Prompt should have nodes section");
            assertTrue(prompt.contains("KNOWLEDGE GRAPH EDGES"), "Prompt should have edges section");
        }

        @Test
        void includesSnippetChunkContentInPrompt() {
            // A document node with two snippet children containing process descriptions
            GraphNode docNode = makeNode("doc-onboarding", NodeLevel.DOCUMENT,
                    "Employee Onboarding Guide", "Guide for new employees", null);

            GraphNode chunk1 = makeSnippet("chunk-1", "Chunk 1",
                    "Step 1: Submit your employment documents to HR. Step 2: Complete the IT access request form.",
                    docNode);
            GraphNode chunk2 = makeSnippet("chunk-2", "Chunk 2",
                    "Step 3: Attend orientation session with your manager. Step 4: Complete compliance training.",
                    docNode);

            when(knowledgeGraphService.getNode("doc-onboarding")).thenReturn(Optional.of(docNode));
            when(knowledgeGraphService.getConnectedNodes("doc-onboarding", 1)).thenReturn(List.of());
            when(knowledgeGraphService.getChildren("doc-onboarding")).thenReturn(List.of(chunk1, chunk2));
            when(knowledgeGraphService.getEdgesForNode("doc-onboarding")).thenReturn(List.of());

            when(llmServiceRegistry.getOrFallback(null)).thenReturn(llmService);
            when(llmService.getId()).thenReturn("test-llm");
            when(llmService.complete(anyString())).thenReturn("{\"processes\":[]}");

            service.discoverProcesses(List.of("doc-onboarding"), Map.of());

            ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
            verify(llmService).complete(promptCaptor.capture());
            String prompt = promptCaptor.getValue();

            // Verify prompt includes the chunk section and actual chunk content
            assertTrue(prompt.contains("DOCUMENT CHUNKS"), "Prompt should have document chunks section");
            assertTrue(prompt.contains("Employee Onboarding Guide"),
                    "Prompt should identify parent document for chunks");
            assertTrue(prompt.contains("Submit your employment documents to HR"),
                    "Prompt should contain chunk 1 content");
            assertTrue(prompt.contains("Attend orientation session"),
                    "Prompt should contain chunk 2 content");
            assertTrue(prompt.contains("Complete compliance training"),
                    "Prompt should contain chunk 2 content");
        }

        @Test
        void includesSnippetsForUnScopedQueries() {
            // When graphNodeIds is null, snippets should be fetched for all DOCUMENT nodes
            GraphNode docNode = makeNode("doc-1", NodeLevel.DOCUMENT, "Procedure Manual", "Manual", null);
            GraphNode snippet = makeSnippet("snip-1", "Chunk 1",
                    "To request a refund: 1) Open ticket 2) Attach receipt 3) Submit for review",
                    docNode);

            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                    .thenReturn(List.of(docNode));
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of());
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.TABLE), anyInt()))
                    .thenReturn(List.of());
            when(knowledgeGraphService.getChildren("doc-1")).thenReturn(List.of(snippet));
            when(knowledgeGraphService.getEdgesForNode("doc-1")).thenReturn(List.of());

            when(llmServiceRegistry.getOrFallback(null)).thenReturn(llmService);
            when(llmService.getId()).thenReturn("test-llm");
            when(llmService.complete(anyString())).thenReturn("{\"processes\":[]}");

            service.discoverProcesses(null, Map.of());

            ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
            verify(llmService).complete(promptCaptor.capture());
            String prompt = promptCaptor.getValue();

            assertTrue(prompt.contains("request a refund"),
                    "Prompt should contain snippet content from un-scoped query");
            assertTrue(prompt.contains("Procedure Manual"),
                    "Prompt should show parent document name for snippets");
        }
    }

    @Nested
    class StepTypeNormalization {

        @Test
        void normalizesVariousStepTypeNames() {
            GraphNode node = makeNode("doc-1", NodeLevel.DOCUMENT, "Test", "test", null);
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                    .thenReturn(List.of(node));
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of());
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.TABLE), anyInt()))
                    .thenReturn(List.of());
            when(knowledgeGraphService.getEdgesForNode("doc-1")).thenReturn(List.of());

            when(llmServiceRegistry.getOrFallback(null)).thenReturn(llmService);
            when(llmService.getId()).thenReturn("test-llm");
            // LLM returns varied step type names that should be normalized
            when(llmService.complete(anyString())).thenReturn("""
                    {
                      "processes": [{
                        "name": "Mixed Types",
                        "description": "Process with various step types",
                        "confidence": 0.8,
                        "phases": [{
                          "name": "Main",
                          "steps": [
                            {"name": "s1", "stepType": "AUTOMATIC", "description": "auto step"},
                            {"name": "s2", "stepType": "MANUAL", "description": "manual step"},
                            {"name": "s3", "stepType": "REVIEW", "description": "review step"},
                            {"name": "s4", "stepType": "COMPUTE", "description": "compute step"},
                            {"name": "s5", "stepType": "API", "description": "api step"},
                            {"name": "s6", "stepType": "TRANSFORM", "description": "transform step"},
                            {"name": "s7", "stepType": "TOOL", "description": "tool step"}
                          ]
                        }]
                      }]
                    }
                    """);

            List<ProcessSuggestion> suggestions = service.discoverProcesses(null, Map.of());

            assertEquals(1, suggestions.size());
            List<ProcessSuggestion.SuggestedStep> steps = suggestions.get(0).getPhases().get(0).getSteps();
            assertEquals(7, steps.size());
            assertEquals("AUTO", steps.get(0).getStepType());
            assertEquals("HUMAN", steps.get(1).getStepType());
            assertEquals("APPROVE", steps.get(2).getStepType());
            assertEquals("EXCEL_COMPUTE", steps.get(3).getStepType());
            assertEquals("HTTP_CALL", steps.get(4).getStepType());
            assertEquals("SCRIPT", steps.get(5).getStepType());
            assertEquals("TOOL_CALL", steps.get(6).getStepType());
        }
    }

    @Nested
    class ProviderSelection {

        @Test
        void usesSpecifiedLlmProvider() {
            GraphNode node = makeNode("doc-1", NodeLevel.DOCUMENT, "Test", "test", null);
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.DOCUMENT), anyInt()))
                    .thenReturn(List.of(node));
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.ENTITY), anyInt()))
                    .thenReturn(List.of());
            when(knowledgeGraphService.searchNodes(eq(""), eq(NodeLevel.TABLE), anyInt()))
                    .thenReturn(List.of());
            when(knowledgeGraphService.getEdgesForNode("doc-1")).thenReturn(List.of());

            when(llmServiceRegistry.getOrFallback("claude-cli")).thenReturn(llmService);
            when(llmService.getId()).thenReturn("claude-cli");
            when(llmService.complete(anyString())).thenReturn("{\"processes\":[]}");

            service.discoverProcesses(null, Map.of("llmProvider", "claude-cli"));

            verify(llmServiceRegistry).getOrFallback("claude-cli");
        }
    }
}
