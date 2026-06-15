package ai.kompile.compute.graph.camel;

import ai.kompile.compute.graph.engine.DefaultGraphExecutor;
import ai.kompile.compute.graph.engine.ExecutionContext;
import ai.kompile.compute.graph.model.*;
import ai.kompile.compute.graph.store.InMemoryArtifactStore;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests that mirror real-world use cases for the Camel compute graph module.
 * Each test exercises a realistic pipeline that would be used in production:
 * email parsing, document routing, data transformation, graph extraction prep, etc.
 */
class CamelIntegrationUseCaseTest {

    private CamelContextManager contextManager;
    private CamelRouteParser routeParser;
    private CamelNodeExecutor executor;

    @BeforeEach
    void setUp() {
        contextManager = new CamelContextManager();
        routeParser = new CamelRouteParser();
        executor = new CamelNodeExecutor(contextManager, routeParser, 30000);
    }

    @AfterEach
    void tearDown() {
        contextManager.close();
    }

    // ---- Email Parsing → Metadata Extraction ----

    @Test
    void emailParsingExtractsStructuredFields() {
        // Simulates: raw email text arrives, Camel simple expressions extract
        // sender, recipients, subject, and body for downstream graph building
        ComputeNode parseEmail = ComputeNode.builder()
                .id("parse-email")
                .name("Parse Email Headers")
                .executionType(NodeExecutionType.CAMEL_ROUTE)
                .script("${header.subject} | from: ${header.sender} | to: ${header.recipients}")
                .build();

        Map<String, Object> inputs = Map.of(
                "sender", "alice@acme.com",
                "recipients", "bob@acme.com, carol@acme.com",
                "subject", "Q3 Revenue Report",
                "body", "Please find the Q3 revenue report attached..."
        );

        ExecutionResult result = executeNode(parseEmail, inputs);

        assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
        String output = (String) result.getOutputs().get("result");
        assertTrue(output.contains("Q3 Revenue Report"));
        assertTrue(output.contains("alice@acme.com"));
        assertTrue(output.contains("bob@acme.com, carol@acme.com"));
    }

    @Test
    void emailToGraphExtractionPipeline() {
        // Multi-node pipeline: email → parse metadata via XML → format for graph extraction
        // Node 1: XML route that extracts email fields into kompile_output_ headers
        // so they propagate as individual values to downstream nodes
        String extractXml = """
                <route id="kompile-node-extract-meta">
                  <from uri="direct:kompile-node-extract-meta"/>
                  <setHeader name="kompile_output_sender">
                    <simple>${header.sender}</simple>
                  </setHeader>
                  <setHeader name="kompile_output_subject">
                    <simple>${header.subject}</simple>
                  </setHeader>
                  <transform>
                    <simple>parsed: ${header.sender} - ${header.subject}</simple>
                  </transform>
                </route>
                """;

        ComputeNode extractMeta = ComputeNode.builder()
                .id("extract-meta")
                .name("Extract Email Metadata")
                .executionType(NodeExecutionType.CAMEL_ROUTE)
                .script(extractXml)
                .build();

        // Node 2: Format for graph extraction using the individual outputs from node 1
        ComputeNode formatForGraph = ComputeNode.builder()
                .id("format-graph")
                .name("Format for Graph Extraction")
                .executionType(NodeExecutionType.CAMEL_ROUTE)
                .script("ENTITY[Person:${header.sender}] RELATES_TO[sent] ENTITY[Document:${header.subject}]")
                .build();

        ComputeEdge edge = ComputeEdge.builder()
                .id("e1")
                .sourceNodeId("extract-meta")
                .targetNodeId("format-graph")
                .build();

        ComputeGraph graph = ComputeGraph.builder()
                .id("email-to-graph")
                .name("Email to Graph Pipeline")
                .nodes(List.of(extractMeta, formatForGraph))
                .edges(List.of(edge))
                .build();

        InMemoryArtifactStore store = new InMemoryArtifactStore();
        DefaultGraphExecutor graphExecutor = new DefaultGraphExecutor(List.of(executor), store);

        GraphExecutionResult result = graphExecutor.execute(graph, Map.of(
                "sender", "alice@acme.com",
                "subject", "Quarterly Review",
                "priority", "high",
                "body", "Meeting notes from the quarterly review..."
        ));

        assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
        assertEquals(2, result.getExecutionOrder().size());

        // Final output should contain the graph-ready format
        String graphOutput = (String) result.getFinalOutputs().get("result");
        assertTrue(graphOutput.contains("ENTITY[Person:alice@acme.com]"));
        assertTrue(graphOutput.contains("ENTITY[Document:Quarterly Review]"));
        assertTrue(graphOutput.contains("RELATES_TO[sent]"));
    }

    // ---- Content-Based Routing ----

    @Test
    void contentBasedRoutingByDocumentType() {
        // Simulates routing different document types (pdf, email, csv) to
        // different processing paths using conditional edges
        ComputeNode classifier = ComputeNode.builder()
                .id("classify")
                .name("Classify Document")
                .executionType(NodeExecutionType.CAMEL_ROUTE)
                .script("${header.docType}")
                .build();

        ComputeNode pdfProcessor = ComputeNode.builder()
                .id("pdf-proc")
                .name("PDF Processor")
                .executionType(NodeExecutionType.CAMEL_ROUTE)
                .script("PDF extracted: ${header.content}")
                .build();

        ComputeNode emailProcessor = ComputeNode.builder()
                .id("email-proc")
                .name("Email Processor")
                .executionType(NodeExecutionType.CAMEL_ROUTE)
                .script("Email parsed: ${header.content}")
                .build();

        // Only route to PDF processor when docType is "pdf"
        ComputeEdge toPdf = ComputeEdge.builder()
                .id("e-pdf")
                .sourceNodeId("classify")
                .targetNodeId("pdf-proc")
                .condition("#result == 'pdf'")
                .build();

        // Only route to email processor when docType is "email"
        ComputeEdge toEmail = ComputeEdge.builder()
                .id("e-email")
                .sourceNodeId("classify")
                .targetNodeId("email-proc")
                .condition("#result == 'email'")
                .build();

        ComputeGraph graph = ComputeGraph.builder()
                .id("doc-router")
                .name("Document Router")
                .nodes(List.of(classifier, pdfProcessor, emailProcessor))
                .edges(List.of(toPdf, toEmail))
                .build();

        InMemoryArtifactStore store = new InMemoryArtifactStore();
        DefaultGraphExecutor graphExecutor = new DefaultGraphExecutor(List.of(executor), store);

        // Route an email document
        GraphExecutionResult result = graphExecutor.execute(graph, Map.of(
                "docType", "email",
                "content", "From: alice@acme.com Subject: Hello"
        ));

        assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
        // Email processor should have run, PDF should be skipped
        assertTrue(result.getSkippedNodes().contains("pdf-proc"));
        assertFalse(result.getSkippedNodes().contains("email-proc"));

        String output = (String) result.getFinalOutputs().get("result");
        assertTrue(output.contains("Email parsed:"));
    }

    // ---- Data Transformation Pipeline ----

    @Test
    void jsonDataTransformationChain() {
        // Simulates: raw data → normalize → enrich → format for indexing
        // Each node transforms data progressively
        ComputeNode normalize = ComputeNode.builder()
                .id("normalize")
                .name("Normalize Data")
                .executionType(NodeExecutionType.CAMEL_ROUTE)
                .script("${header.name} | ${header.email} | ${header.department}")
                .build();

        ComputeNode enrich = ComputeNode.builder()
                .id("enrich")
                .name("Enrich with Role")
                .executionType(NodeExecutionType.CAMEL_ROUTE)
                .script("${body} | role=EMPLOYEE | indexed=true")
                .build();

        ComputeEdge edge = ComputeEdge.builder()
                .id("e1")
                .sourceNodeId("normalize")
                .targetNodeId("enrich")
                .build();

        ComputeGraph graph = ComputeGraph.builder()
                .id("transform-chain")
                .name("Data Transformation Chain")
                .nodes(List.of(normalize, enrich))
                .edges(List.of(edge))
                .build();

        InMemoryArtifactStore store = new InMemoryArtifactStore();
        DefaultGraphExecutor graphExecutor = new DefaultGraphExecutor(List.of(executor), store);

        GraphExecutionResult result = graphExecutor.execute(graph, Map.of(
                "name", "Alice Smith",
                "email", "alice@acme.com",
                "department", "Engineering"
        ));

        assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
        String output = (String) result.getFinalOutputs().get("result");
        assertTrue(output.contains("Alice Smith"));
        assertTrue(output.contains("alice@acme.com"));
        assertTrue(output.contains("role=EMPLOYEE"));
        assertTrue(output.contains("indexed=true"));
    }

    // ---- XML DSL Route Execution ----

    @Test
    void xmlDslRouteProcessesDocument() {
        // Real XML DSL route that transforms document content
        String xmlRoute = """
                <route id="kompile-node-xml-test">
                  <from uri="direct:kompile-node-xml-test"/>
                  <setHeader name="kompile_output_processed">
                    <constant>true</constant>
                  </setHeader>
                  <setHeader name="kompile_output_format">
                    <constant>structured</constant>
                  </setHeader>
                  <transform>
                    <simple>Processed: ${body}</simple>
                  </transform>
                </route>
                """;

        ComputeNode node = ComputeNode.builder()
                .id("xml-test")
                .name("XML DSL Document Processor")
                .executionType(NodeExecutionType.CAMEL_ROUTE)
                .script(xmlRoute)
                .build();

        Map<String, Object> inputs = Map.of(
                "body", "Raw document content from email attachment"
        );

        ExecutionResult result = executeNode(node, inputs);

        assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
        assertEquals("Processed: Raw document content from email attachment",
                result.getOutputs().get("result"));
        // kompile_output_ headers become named outputs
        assertEquals("true", result.getOutputs().get("processed"));
        assertEquals("structured", result.getOutputs().get("format"));
    }

    @Test
    void xmlDslContentBasedRouter() {
        // XML route with choice/when for content-based routing within a single node
        String xmlRoute = """
                <route id="kompile-node-cbr-test">
                  <from uri="direct:kompile-node-cbr-test"/>
                  <choice>
                    <when>
                      <simple>${header.priority} == 'high'</simple>
                      <setHeader name="kompile_output_queue">
                        <constant>urgent</constant>
                      </setHeader>
                      <transform>
                        <simple>URGENT: ${body}</simple>
                      </transform>
                    </when>
                    <when>
                      <simple>${header.priority} == 'low'</simple>
                      <setHeader name="kompile_output_queue">
                        <constant>batch</constant>
                      </setHeader>
                      <transform>
                        <simple>BATCH: ${body}</simple>
                      </transform>
                    </when>
                    <otherwise>
                      <setHeader name="kompile_output_queue">
                        <constant>normal</constant>
                      </setHeader>
                      <transform>
                        <simple>NORMAL: ${body}</simple>
                      </transform>
                    </otherwise>
                  </choice>
                </route>
                """;

        ComputeNode node = ComputeNode.builder()
                .id("cbr-test")
                .name("Priority Router")
                .executionType(NodeExecutionType.CAMEL_ROUTE)
                .script(xmlRoute)
                .build();

        // Test high priority
        ExecutionResult highResult = executeNode(node, Map.of(
                "body", "Critical system alert",
                "priority", "high"
        ));
        assertEquals(ExecutionStatus.COMPLETED, highResult.getStatus());
        assertEquals("URGENT: Critical system alert", highResult.getOutputs().get("result"));
        assertEquals("urgent", highResult.getOutputs().get("queue"));

        // Test low priority
        ExecutionResult lowResult = executeNode(node, Map.of(
                "body", "Weekly digest",
                "priority", "low"
        ));
        assertEquals(ExecutionStatus.COMPLETED, lowResult.getStatus());
        assertEquals("BATCH: Weekly digest", lowResult.getOutputs().get("result"));
        assertEquals("batch", lowResult.getOutputs().get("queue"));

        // Test default priority
        ExecutionResult defaultResult = executeNode(node, Map.of(
                "body", "Regular update",
                "priority", "medium"
        ));
        assertEquals(ExecutionStatus.COMPLETED, defaultResult.getStatus());
        assertEquals("NORMAL: Regular update", defaultResult.getOutputs().get("result"));
        assertEquals("normal", defaultResult.getOutputs().get("queue"));
    }

    // ---- Graph Extraction Preparation ----

    @Test
    void graphExtractorProcessorWithTextInput() {
        // Test the GraphExtractorProcessor directly — when no extraction service
        // is configured, it should provide extraction prompt instructions
        GraphExtractorProcessor processor = new GraphExtractorProcessor(null);

        CamelContext ctx = contextManager.getSharedContext();
        try (ProducerTemplate producer = ctx.createProducerTemplate()) {
            // Add a route that uses the processor
            ctx.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:graph-extract-test")
                            .routeId("graph-extract-test")
                            .process(processor);
                }
            });

            Exchange result = producer.request("direct:graph-extract-test", exchange -> {
                exchange.getIn().setBody("Alice from Engineering sent the Q3 report to Bob in Finance. "
                        + "The report covers revenue data for ACME Corp.");
            });

            assertNull(result.getException());

            // Without an extraction service, it should flag for LLM extraction
            @SuppressWarnings("unchecked")
            Map<String, Object> body = result.getMessage().getBody(Map.class);
            assertNotNull(body);
            assertEquals(true, body.get("needsLlmExtraction"));
            assertNotNull(body.get("extractionPrompt"));
            assertTrue(((String) body.get("text")).contains("Alice from Engineering"));
            assertEquals(false, result.getMessage().getHeader("kompile_graphExtracted"));
            assertEquals(true, result.getMessage().getHeader("kompile_needsLlmExtraction"));
        } catch (Exception e) {
            fail("Graph extractor test failed: " + e.getMessage());
        }
    }

    @Test
    void graphExtractorSkipsEmptyInput() {
        GraphExtractorProcessor processor = new GraphExtractorProcessor(null);

        CamelContext ctx = contextManager.getSharedContext();
        try (ProducerTemplate producer = ctx.createProducerTemplate()) {
            ctx.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:graph-extract-empty")
                            .routeId("graph-extract-empty")
                            .process(processor);
                }
            });

            Exchange result = producer.request("direct:graph-extract-empty", exchange -> {
                exchange.getIn().setBody("   ");
            });

            assertNull(result.getException());
            assertEquals(false, result.getMessage().getHeader("kompile_graphExtracted"));
        } catch (Exception e) {
            fail("Empty input test failed: " + e.getMessage());
        }
    }

    @Test
    void graphExtractorAcceptsMapInput() {
        GraphExtractorProcessor processor = new GraphExtractorProcessor(null);

        CamelContext ctx = contextManager.getSharedContext();
        try (ProducerTemplate producer = ctx.createProducerTemplate()) {
            ctx.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:graph-extract-map")
                            .routeId("graph-extract-map")
                            .process(processor);
                }
            });

            Exchange result = producer.request("direct:graph-extract-map", exchange -> {
                Map<String, Object> docMap = new HashMap<>();
                docMap.put("text", "Bob manages the sales team at ACME Corp.");
                docMap.put("source", "email");
                exchange.getIn().setBody(docMap);
            });

            assertNull(result.getException());

            @SuppressWarnings("unchecked")
            Map<String, Object> body = result.getMessage().getBody(Map.class);
            assertEquals(true, body.get("needsLlmExtraction"));
            assertTrue(((String) body.get("text")).contains("Bob manages"));
        } catch (Exception e) {
            fail("Map input test failed: " + e.getMessage());
        }
    }

    // ---- Persistent Route Lifecycle ----

    @Test
    void routeRegistryPersistAndRetrieve() {
        // Simulates saving an email-processing route, deploying it, checking
        // status, and then undeploying it — the full route management lifecycle
        CamelRouteRegistry registry = new CamelRouteRegistry();

        String routeScript = "${header.sender}: ${header.subject}";
        CamelRouteRegistry.RouteDefinitionRecord record = CamelRouteRegistry.RouteDefinitionRecord.builder()
                .name("Email Summary Route")
                .description("Extracts sender and subject from email headers")
                .script(routeScript)
                .format("SIMPLE_EXPRESSION")
                .enabled(true)
                .metadata(Map.of("category", "email", "version", "1.0"))
                .build();

        // Save
        CamelRouteRegistry.RouteDefinitionRecord saved = registry.save(record);
        assertNotNull(saved.getId());
        assertNotNull(saved.getCreatedAt());
        assertNotNull(saved.getUpdatedAt());
        assertTrue(saved.isEnabled());

        // Retrieve
        Optional<CamelRouteRegistry.RouteDefinitionRecord> retrieved = registry.get(saved.getId());
        assertTrue(retrieved.isPresent());
        assertEquals("Email Summary Route", retrieved.get().getName());
        assertEquals(routeScript, retrieved.get().getScript());
        assertEquals("email", retrieved.get().getMetadata().get("category"));

        // Deploy to context manager
        try {
            String routeId = contextManager.deployRoute(saved.getId(), saved.getScript());
            assertNotNull(routeId);
            assertTrue(contextManager.isRouteDeployed(saved.getId()));

            // Check status
            List<CamelContextManager.RouteStatus> statuses = contextManager.getDeployedRouteStatuses();
            assertFalse(statuses.isEmpty());
            CamelContextManager.RouteStatus status = statuses.stream()
                    .filter(s -> s.registryId.equals(saved.getId()))
                    .findFirst()
                    .orElseThrow();
            assertEquals("Started", status.status);

            // Undeploy
            assertTrue(contextManager.undeployRoute(saved.getId()));
            assertFalse(contextManager.isRouteDeployed(saved.getId()));
        } catch (Exception e) {
            fail("Route lifecycle failed: " + e.getMessage());
        }

        // Clean up
        registry.delete(saved.getId());
        assertFalse(registry.get(saved.getId()).isPresent());
    }

    @Test
    void deployAllEnabledRoutesFromRegistry() throws Exception {
        CamelRouteRegistry registry = new CamelRouteRegistry();

        // Save two routes, one enabled, one disabled
        CamelRouteRegistry.RouteDefinitionRecord enabled = registry.save(
                CamelRouteRegistry.RouteDefinitionRecord.builder()
                        .name("Active Route")
                        .script("${body} processed")
                        .format("SIMPLE_EXPRESSION")
                        .enabled(true)
                        .build()
        );

        CamelRouteRegistry.RouteDefinitionRecord disabled = registry.save(
                CamelRouteRegistry.RouteDefinitionRecord.builder()
                        .name("Inactive Route")
                        .script("${body} inactive")
                        .format("SIMPLE_EXPRESSION")
                        .enabled(false)
                        .build()
        );

        // Deploy all enabled
        contextManager.deployAllFromRegistry(registry);

        assertTrue(contextManager.isRouteDeployed(enabled.getId()));
        assertFalse(contextManager.isRouteDeployed(disabled.getId()));

        // Clean up
        contextManager.undeployRoute(enabled.getId());
        registry.delete(enabled.getId());
        registry.delete(disabled.getId());
    }

    @Test
    void routeStopStartReloadLifecycle() throws Exception {
        String routeScript = "${header.name} v1";
        String routeId = contextManager.deployRoute("lifecycle-test", routeScript);
        assertTrue(contextManager.isRouteDeployed("lifecycle-test"));

        // Stop
        assertTrue(contextManager.stopRoute("lifecycle-test"));

        // Start again
        assertTrue(contextManager.startRoute("lifecycle-test"));

        // Reload with new version
        String updatedScript = "${header.name} v2";
        String newRouteId = contextManager.reloadRoute("lifecycle-test", updatedScript);
        assertNotNull(newRouteId);
        assertTrue(contextManager.isRouteDeployed("lifecycle-test"));

        // Clean up
        contextManager.undeployRoute("lifecycle-test");
    }

    // ---- Multi-Step Document Processing Pipeline ----

    @Test
    void documentIngestionPipeline() {
        // Simulates a document ingestion pipeline:
        // 1. Parse document metadata (source, type, timestamp)
        // 2. Clean/normalize the text
        // 3. Prepare chunking metadata for downstream embedding
        ComputeNode parseMeta = ComputeNode.builder()
                .id("parse-meta")
                .name("Parse Document Metadata")
                .executionType(NodeExecutionType.CAMEL_ROUTE)
                .script("source=${header.source}, type=${header.docType}, title=${header.title}")
                .build();

        ComputeNode cleanText = ComputeNode.builder()
                .id("clean-text")
                .name("Clean Text")
                .executionType(NodeExecutionType.CAMEL_ROUTE)
                .script("${header.body}")
                .build();

        ComputeNode prepareChunk = ComputeNode.builder()
                .id("prepare-chunk")
                .name("Prepare for Chunking")
                .executionType(NodeExecutionType.CAMEL_ROUTE)
                .script("chunk_ready: metadata=[${header.result}] text=[${header.body}]")
                .build();

        // parseMeta and cleanText run in parallel (both root nodes),
        // then prepareChunk combines their results
        ComputeEdge metaToChunk = ComputeEdge.builder()
                .id("e1")
                .sourceNodeId("parse-meta")
                .targetNodeId("prepare-chunk")
                .build();

        ComputeEdge textToChunk = ComputeEdge.builder()
                .id("e2")
                .sourceNodeId("clean-text")
                .targetNodeId("prepare-chunk")
                .build();

        ComputeGraph graph = ComputeGraph.builder()
                .id("ingest-pipeline")
                .name("Document Ingestion Pipeline")
                .nodes(List.of(parseMeta, cleanText, prepareChunk))
                .edges(List.of(metaToChunk, textToChunk))
                .build();

        InMemoryArtifactStore store = new InMemoryArtifactStore();
        DefaultGraphExecutor graphExecutor = new DefaultGraphExecutor(List.of(executor), store);

        GraphExecutionResult result = graphExecutor.execute(graph, Map.of(
                "source", "confluence",
                "docType", "wiki-page",
                "title", "Architecture Decision Records",
                "body", "This document describes the ADR process for team decisions."
        ));

        assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
        assertEquals(3, result.getExecutionOrder().size());
        assertTrue(result.getSkippedNodes().isEmpty());

        // The final node should have combined outputs
        String finalOutput = (String) result.getFinalOutputs().get("result");
        assertNotNull(finalOutput);
        assertTrue(finalOutput.startsWith("chunk_ready:"));
    }

    // ---- XML Route with Multi-Step Processing ----

    @Test
    void xmlRouteMultiStepTransformation() {
        // XML route that does multiple sequential transformations,
        // similar to an ETL pipeline within a single Camel node
        String xmlRoute = """
                <route id="kompile-node-etl-test">
                  <from uri="direct:kompile-node-etl-test"/>
                  <setHeader name="kompile_output_stage">
                    <constant>normalized</constant>
                  </setHeader>
                  <setHeader name="kompile_output_recordCount">
                    <simple>${header.recordCount}</simple>
                  </setHeader>
                  <transform>
                    <simple>dataset=${header.dataset} | records=${header.recordCount} | status=indexed</simple>
                  </transform>
                </route>
                """;

        ComputeNode etlNode = ComputeNode.builder()
                .id("etl-test")
                .name("ETL Transform")
                .executionType(NodeExecutionType.CAMEL_ROUTE)
                .script(xmlRoute)
                .build();

        ExecutionResult result = executeNode(etlNode, Map.of(
                "dataset", "customer_emails",
                "recordCount", "1250",
                "body", "raw data"
        ));

        assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
        String output = (String) result.getOutputs().get("result");
        assertTrue(output.contains("dataset=customer_emails"));
        assertTrue(output.contains("records=1250"));
        assertTrue(output.contains("status=indexed"));
        assertEquals("normalized", result.getOutputs().get("stage"));
        assertEquals("1250", result.getOutputs().get("recordCount"));
    }

    // ---- Error Handling ----

    @Test
    void xmlRouteWithErrorHandling() {
        // Route that uses Camel's onException to handle failures gracefully
        String xmlRoute = """
                <route id="kompile-node-err-test">
                  <from uri="direct:kompile-node-err-test"/>
                  <choice>
                    <when>
                      <simple>${header.simulateError} == 'true'</simple>
                      <setHeader name="kompile_output_error">
                        <constant>processing_failed</constant>
                      </setHeader>
                      <transform>
                        <simple>ERROR: Failed to process ${header.docId}</simple>
                      </transform>
                    </when>
                    <otherwise>
                      <setHeader name="kompile_output_status">
                        <constant>success</constant>
                      </setHeader>
                      <transform>
                        <simple>OK: Processed ${header.docId}</simple>
                      </transform>
                    </otherwise>
                  </choice>
                </route>
                """;

        ComputeNode node = ComputeNode.builder()
                .id("err-test")
                .name("Error Handler")
                .executionType(NodeExecutionType.CAMEL_ROUTE)
                .script(xmlRoute)
                .build();

        // Success path
        ExecutionResult okResult = executeNode(node, Map.of(
                "docId", "DOC-001",
                "simulateError", "false"
        ));
        assertEquals(ExecutionStatus.COMPLETED, okResult.getStatus());
        assertEquals("OK: Processed DOC-001", okResult.getOutputs().get("result"));
        assertEquals("success", okResult.getOutputs().get("status"));

        // Error path
        ExecutionResult errResult = executeNode(node, Map.of(
                "docId", "DOC-002",
                "simulateError", "true"
        ));
        assertEquals(ExecutionStatus.COMPLETED, errResult.getStatus());
        assertEquals("ERROR: Failed to process DOC-002", errResult.getOutputs().get("result"));
        assertEquals("processing_failed", errResult.getOutputs().get("error"));
    }

    // ---- Concurrent / Isolated Context Execution ----

    @Test
    void isolatedContextsPreventRouteCollisions() {
        // Two executions with the same route ID should not interfere
        String executionId1 = UUID.randomUUID().toString();
        String executionId2 = UUID.randomUUID().toString();

        CamelContext ctx1 = contextManager.getOrCreateIsolatedContext(executionId1);
        CamelContext ctx2 = contextManager.getOrCreateIsolatedContext(executionId2);

        assertNotSame(ctx1, ctx2);
        assertNotEquals(ctx1.getName(), ctx2.getName());

        // Both contexts should accept routes with the same ID
        try {
            routeParser.parseAndAddRoute(ctx1, "${body} from ctx1", "shared-route");
            routeParser.parseAndAddRoute(ctx2, "${body} from ctx2", "shared-route");

            // Both should have the route
            assertNotNull(ctx1.getRoute("kompile-node-shared-route"));
            assertNotNull(ctx2.getRoute("kompile-node-shared-route"));
        } catch (Exception e) {
            fail("Isolated contexts should allow duplicate route IDs: " + e.getMessage());
        }

        // Clean up
        contextManager.destroyIsolatedContext(executionId1);
        contextManager.destroyIsolatedContext(executionId2);
    }

    // ---- Output Header Bindings ----

    @Test
    void outputBindingsCollectNamedHeaders() {
        // Route that produces specific kompile_output_ headers, simulating
        // structured output from a processing step
        String xmlRoute = """
                <route id="kompile-node-binding-test">
                  <from uri="direct:kompile-node-binding-test"/>
                  <setHeader name="kompile_output_entityCount">
                    <simple>${header.entities}</simple>
                  </setHeader>
                  <setHeader name="kompile_output_relationshipCount">
                    <simple>${header.relationships}</simple>
                  </setHeader>
                  <setHeader name="kompile_output_graphId">
                    <simple>graph-${header.docId}</simple>
                  </setHeader>
                  <transform>
                    <simple>Extracted ${header.entities} entities and ${header.relationships} relationships from ${header.docId}</simple>
                  </transform>
                </route>
                """;

        ComputeNode node = ComputeNode.builder()
                .id("binding-test")
                .name("Graph Extraction Summary")
                .executionType(NodeExecutionType.CAMEL_ROUTE)
                .script(xmlRoute)
                .build();

        ExecutionResult result = executeNode(node, Map.of(
                "docId", "DOC-100",
                "entities", "15",
                "relationships", "23"
        ));

        assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
        assertEquals("15", result.getOutputs().get("entityCount"));
        assertEquals("23", result.getOutputs().get("relationshipCount"));
        assertEquals("graph-DOC-100", result.getOutputs().get("graphId"));
        assertTrue(((String) result.getOutputs().get("result")).contains("15 entities"));
    }

    // ---- Graph Validation ----

    @Test
    void graphValidationDetectsInvalidRoutes() {
        InMemoryArtifactStore store = new InMemoryArtifactStore();
        DefaultGraphExecutor graphExecutor = new DefaultGraphExecutor(List.of(executor), store);

        ComputeNode valid = ComputeNode.builder()
                .id("valid")
                .name("Valid Node")
                .executionType(NodeExecutionType.CAMEL_ROUTE)
                .script("${body}")
                .build();

        ComputeNode invalid = ComputeNode.builder()
                .id("invalid")
                .name("Invalid Node")
                .executionType(NodeExecutionType.CAMEL_ROUTE)
                .script("")
                .build();

        ComputeGraph graph = ComputeGraph.builder()
                .id("validation-test")
                .name("Validation Test")
                .nodes(List.of(valid, invalid))
                .edges(List.of())
                .build();

        String error = graphExecutor.validate(graph);
        assertNotNull(error);
        assertTrue(error.contains("empty"));
    }

    // ---- Programmatic Route with Custom Processor ----

    @Test
    void programmaticRouteWithInlineProcessor() throws Exception {
        // Test deploying a programmatic route (RouteBuilder) directly to the
        // shared context and sending messages through it
        CamelContext ctx = contextManager.getSharedContext();

        ctx.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:email-enricher")
                        .routeId("email-enricher")
                        .process(exchange -> {
                            String sender = exchange.getIn().getHeader("sender", String.class);
                            String subject = exchange.getIn().getHeader("subject", String.class);
                            String body = exchange.getIn().getBody(String.class);

                            // Simulate email entity extraction
                            Map<String, Object> result = new HashMap<>();
                            result.put("sender", sender);
                            result.put("subject", subject);
                            result.put("wordCount", body != null ? body.split("\\s+").length : 0);
                            result.put("hasAttachment", body != null && body.contains("attached"));
                            result.put("entities", List.of(sender, subject));

                            exchange.getIn().setBody(result);
                        });
            }
        });

        try (ProducerTemplate producer = ctx.createProducerTemplate()) {
            Exchange result = producer.request("direct:email-enricher", exchange -> {
                exchange.getIn().setHeader("sender", "alice@acme.com");
                exchange.getIn().setHeader("subject", "Q3 Report");
                exchange.getIn().setBody("Please review the Q3 report attached to this email.");
            });

            assertNull(result.getException());

            @SuppressWarnings("unchecked")
            Map<String, Object> body = result.getMessage().getBody(Map.class);
            assertEquals("alice@acme.com", body.get("sender"));
            assertEquals("Q3 Report", body.get("subject"));
            assertEquals(9, body.get("wordCount"));
            assertEquals(true, body.get("hasAttachment"));

            @SuppressWarnings("unchecked")
            List<String> entities = (List<String>) body.get("entities");
            assertTrue(entities.contains("alice@acme.com"));
            assertTrue(entities.contains("Q3 Report"));
        }

        // Clean up
        ctx.getRouteController().stopRoute("email-enricher");
        ctx.removeRoute("email-enricher");
    }

    // ---- Helper ----

    private ExecutionResult executeNode(ComputeNode node, Map<String, Object> inputs) {
        ComputeGraph graph = ComputeGraph.builder()
                .id("test-graph")
                .name("Test Graph")
                .nodes(List.of(node))
                .edges(List.of())
                .build();

        ExecutionContext context = new ExecutionContext(
                UUID.randomUUID().toString(), graph, new InMemoryArtifactStore());

        return executor.execute(node, inputs, context);
    }
}
