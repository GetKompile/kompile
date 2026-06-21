package ai.kompile.compute.graph.camel;

import ai.kompile.compute.graph.engine.DefaultGraphExecutor;
import ai.kompile.compute.graph.engine.FolNodeExecutor;
import ai.kompile.compute.graph.model.*;
import ai.kompile.compute.graph.store.InMemoryArtifactStore;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.support.processor.idempotent.MemoryIdempotentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Workflow integration tests combining Apache Camel routes, Drools business rules,
 * and Enterprise Integration Patterns for real-world document processing,
 * graph building, and business process scenarios.
 */
class CamelWorkflowIntegrationTest {

    private CamelContextManager contextManager;
    private CamelRouteParser routeParser;
    private CamelNodeExecutor camelExecutor;
    private FolNodeExecutor droolsExecutor;

    @BeforeEach
    void setUp() {
        contextManager = new CamelContextManager();
        routeParser = new CamelRouteParser();
        camelExecutor = new CamelNodeExecutor(contextManager, routeParser, 30000);
        droolsExecutor = new FolNodeExecutor();
    }

    @AfterEach
    void tearDown() {
        contextManager.close();
    }

    // ========================================================================
    // Multi-Executor Pipelines: Camel + Drools
    // ========================================================================

    @Test
    void emailEntityClassificationPipeline() {
        // Real workflow: email arrives → Camel extracts structured fields →
        // Native Camel XML route classifies entity types (Person, Organization, Document) →
        // Camel formats the classified entities for knowledge graph storage.
        //
        // Rewritten from DRL to a native Camel XML choice/when node: the classification
        // rules (sender→PERSON, org→ORGANIZATION, subject→DOCUMENT) are expressed as
        // a Camel processor that sets kompile_output_* headers, replacing the DRL
        // NamedFact pattern that required the Drools KIE runtime.

        // Node 1 (Camel): Parse email into structured output headers
        String parseEmailXml = """
                <route id="kompile-node-parse-email">
                  <from uri="direct:kompile-node-parse-email"/>
                  <setHeader name="kompile_output_sender">
                    <simple>${header.from}</simple>
                  </setHeader>
                  <setHeader name="kompile_output_recipient">
                    <simple>${header.to}</simple>
                  </setHeader>
                  <setHeader name="kompile_output_subject">
                    <simple>${header.subject}</simple>
                  </setHeader>
                  <setHeader name="kompile_output_organization">
                    <simple>${header.org}</simple>
                  </setHeader>
                  <transform>
                    <simple>parsed</simple>
                  </transform>
                </route>
                """;

        ComputeNode parseNode = ComputeNode.builder()
                .id("parse-email")
                .name("Parse Email")
                .executionType(NodeExecutionType.CAMEL_ROUTE)
                .script(parseEmailXml)
                .build();

        // Node 2 (native Camel): Classify entities — replaces DRL rules.
        // Each "when" block mirrors one DRL rule:
        //   "classify-sender-as-person"  → always fires (sender header always present)
        //   "classify-org"               → always fires (org header always present)
        //   "classify-document"          → always fires (subject header always present)
        // A single XML route performs all three classifications sequentially.
        String classifyXml = """
                <route id="kompile-node-classify-entities">
                  <from uri="direct:kompile-node-classify-entities"/>
                  <setHeader name="kompile_output_senderType">
                    <constant>PERSON</constant>
                  </setHeader>
                  <setHeader name="kompile_output_senderName">
                    <simple>${header.sender}</simple>
                  </setHeader>
                  <setHeader name="kompile_output_orgType">
                    <constant>ORGANIZATION</constant>
                  </setHeader>
                  <setHeader name="kompile_output_orgName">
                    <simple>${header.organization}</simple>
                  </setHeader>
                  <setHeader name="kompile_output_docType">
                    <constant>DOCUMENT</constant>
                  </setHeader>
                  <setHeader name="kompile_output_docTitle">
                    <simple>${header.subject}</simple>
                  </setHeader>
                  <transform>
                    <simple>classified</simple>
                  </transform>
                </route>
                """;

        ComputeNode classifyNode = ComputeNode.builder()
                .id("classify-entities")
                .name("Classify Entities")
                .executionType(NodeExecutionType.CAMEL_ROUTE)
                .script(classifyXml)
                .build();

        // Node 3 (Camel): Format classified entities for graph storage
        ComputeNode formatNode = ComputeNode.builder()
                .id("format-graph")
                .name("Format Graph Output")
                .executionType(NodeExecutionType.CAMEL_ROUTE)
                .script("GRAPH: [${header.senderType}:${header.senderName}]-[sent]->[${header.docType}:${header.docTitle}] at [${header.orgType}:${header.orgName}]")
                .build();

        ComputeEdge parseToClassify = ComputeEdge.builder()
                .id("e1").sourceNodeId("parse-email").targetNodeId("classify-entities").build();
        ComputeEdge classifyToFormat = ComputeEdge.builder()
                .id("e2").sourceNodeId("classify-entities").targetNodeId("format-graph").build();

        ComputeGraph graph = ComputeGraph.builder()
                .id("email-classification")
                .name("Email Entity Classification")
                .nodes(List.of(parseNode, classifyNode, formatNode))
                .edges(List.of(parseToClassify, classifyToFormat))
                .build();

        DefaultGraphExecutor executor = new DefaultGraphExecutor(
                List.of(camelExecutor, droolsExecutor), new InMemoryArtifactStore());

        GraphExecutionResult result = executor.execute(graph, Map.of(
                "from", "alice@acme.com",
                "to", "bob@globex.com",
                "subject", "Q3 Revenue Analysis",
                "org", "ACME Corp",
                "body", "Please find the quarterly report attached."
        ));

        assertEquals(ExecutionStatus.COMPLETED, result.getStatus());
        assertEquals(3, result.getExecutionOrder().size());

        // Verify classification outputs propagated through the pipeline
        String graphOutput = (String) result.getFinalOutputs().get("result");
        assertTrue(graphOutput.contains("PERSON:alice@acme.com"), "Should classify sender as PERSON");
        assertTrue(graphOutput.contains("DOCUMENT:Q3 Revenue Analysis"), "Should classify subject as DOCUMENT");
        assertTrue(graphOutput.contains("ORGANIZATION:ACME Corp"), "Should classify org as ORGANIZATION");
    }

    @Test
    void invoiceApprovalWorkflow() {
        // Real workflow: invoice data arrives → Camel extracts amount/vendor →
        // Native Camel XML choice/when applies approval business rules based on thresholds.
        //
        // Rewritten from DRL salience rules to a Camel XML choice/when node.
        // The three threshold bands (< 500 → APPROVED, 500-4999 → MANAGER_REVIEW,
        // >= 5000 → EXECUTIVE_REVIEW) are expressed as Camel choice conditions,
        // which is a direct structural equivalent of the DRL eval() guard pattern.

        // Node 1 (Camel): Parse invoice fields
        String parseInvoiceXml = """
                <route id="kompile-node-parse-invoice">
                  <from uri="direct:kompile-node-parse-invoice"/>
                  <setHeader name="kompile_output_amount">
                    <simple resultType="java.lang.Double">${header.amount}</simple>
                  </setHeader>
                  <setHeader name="kompile_output_vendor">
                    <simple>${header.vendor}</simple>
                  </setHeader>
                  <setHeader name="kompile_output_department">
                    <simple>${header.department}</simple>
                  </setHeader>
                  <transform>
                    <simple>Invoice from ${header.vendor}: $${header.amount}</simple>
                  </transform>
                </route>
                """;

        ComputeNode parseNode = ComputeNode.builder()
                .id("parse-invoice")
                .name("Parse Invoice")
                .executionType(NodeExecutionType.CAMEL_ROUTE)
                .script(parseInvoiceXml)
                .build();

        // Node 2 (native Camel XML): Apply approval business rules.
        // Mirrors the DRL salience-10/5/1 rules using Camel's choice/when:
        //   amount <  500     → APPROVED       / SYSTEM
        //   amount <  5000    → MANAGER_REVIEW / DEPARTMENT_HEAD
        //   otherwise         → EXECUTIVE_REVIEW / CFO
        String approvalXml = """
                <route id="kompile-node-approval-rules">
                  <from uri="direct:kompile-node-approval-rules"/>
                  <choice>
                    <when>
                      <simple>${header.amount} &lt; 500</simple>
                      <setHeader name="kompile_output_decision">
                        <constant>APPROVED</constant>
                      </setHeader>
                      <setHeader name="kompile_output_approver">
                        <constant>SYSTEM</constant>
                      </setHeader>
                      <setHeader name="kompile_output_reason">
                        <constant>Below auto-approval threshold</constant>
                      </setHeader>
                    </when>
                    <when>
                      <simple>${header.amount} &lt; 5000</simple>
                      <setHeader name="kompile_output_decision">
                        <constant>MANAGER_REVIEW</constant>
                      </setHeader>
                      <setHeader name="kompile_output_approver">
                        <constant>DEPARTMENT_HEAD</constant>
                      </setHeader>
                      <setHeader name="kompile_output_reason">
                        <constant>Requires manager approval</constant>
                      </setHeader>
                    </when>
                    <otherwise>
                      <setHeader name="kompile_output_decision">
                        <constant>EXECUTIVE_REVIEW</constant>
                      </setHeader>
                      <setHeader name="kompile_output_approver">
                        <constant>CFO</constant>
                      </setHeader>
                      <setHeader name="kompile_output_reason">
                        <constant>Exceeds department authority</constant>
                      </setHeader>
                    </otherwise>
                  </choice>
                  <transform>
                    <simple>${header.kompile_output_decision}</simple>
                  </transform>
                </route>
                """;

        ComputeNode rulesNode = ComputeNode.builder()
                .id("approval-rules")
                .name("Approval Rules")
                .executionType(NodeExecutionType.CAMEL_ROUTE)
                .script(approvalXml)
                .build();

        ComputeEdge edge = ComputeEdge.builder()
                .id("e1").sourceNodeId("parse-invoice").targetNodeId("approval-rules").build();

        ComputeGraph graph = ComputeGraph.builder()
                .id("invoice-approval")
                .name("Invoice Approval Workflow")
                .nodes(List.of(parseNode, rulesNode))
                .edges(List.of(edge))
                .build();

        DefaultGraphExecutor executor = new DefaultGraphExecutor(
                List.of(camelExecutor, droolsExecutor), new InMemoryArtifactStore());

        // Test small invoice → auto-approved
        GraphExecutionResult smallResult = executor.execute(graph, Map.of(
                "amount", 250.00, "vendor", "Office Supplies Inc", "department", "Engineering"
        ));
        assertEquals(ExecutionStatus.COMPLETED, smallResult.getStatus(),
                "Pipeline failed: " + smallResult.getError());
        assertEquals("APPROVED", smallResult.getFinalOutputs().get("decision"));
        assertEquals("SYSTEM", smallResult.getFinalOutputs().get("approver"));

        // Test medium invoice → manager review
        GraphExecutionResult medResult = executor.execute(graph, Map.of(
                "amount", 2500.00, "vendor", "Cloud Services Ltd", "department", "DevOps"
        ));
        assertEquals(ExecutionStatus.COMPLETED, medResult.getStatus());
        assertEquals("MANAGER_REVIEW", medResult.getFinalOutputs().get("decision"));
        assertEquals("DEPARTMENT_HEAD", medResult.getFinalOutputs().get("approver"));

        // Test large invoice → executive review
        GraphExecutionResult largeResult = executor.execute(graph, Map.of(
                "amount", 50000.00, "vendor", "Enterprise Software Corp", "department", "IT"
        ));
        assertEquals(ExecutionStatus.COMPLETED, largeResult.getStatus());
        assertEquals("EXECUTIVE_REVIEW", largeResult.getFinalOutputs().get("decision"));
        assertEquals("CFO", largeResult.getFinalOutputs().get("approver"));
    }

    @Test
    void documentComplianceWorkflowWithConditionalRouting() {
        // Real workflow: document metadata is checked → native Camel compliance rules
        // evaluate → conditional edges route to approval or remediation paths.
        //
        // Rewritten from DRL to a native Camel XML choice/when node.
        // The three DRL rules are expressed as choice branches:
        //   "check-signature-required": classification==CONFIDENTIAL && signed!=true → non-compliant
        //   "check-retention": retentionYears < 3 → non-compliant
        //   "mark-compliant": neither violation fired → compliant
        // Conditional edges use the same SpEL expressions as before (#compliant == 'true'/false').

        // Node 1 (Camel): Extract metadata using XML route with output headers
        String extractXml = """
                <route id="kompile-node-complianceExtract">
                  <from uri="direct:kompile-node-complianceExtract"/>
                  <setHeader name="kompile_output_hasSignature">
                    <simple>${header.signed}</simple>
                  </setHeader>
                  <setHeader name="kompile_output_classification">
                    <simple>${header.classification}</simple>
                  </setHeader>
                  <setHeader name="kompile_output_retentionYears">
                    <simple resultType="java.lang.Integer">${header.retentionYears}</simple>
                  </setHeader>
                  <transform><simple>metadata extracted</simple></transform>
                </route>
                """;

        ComputeNode extractNode = ComputeNode.builder()
                .id("complianceExtract").name("Extract Metadata")
                .executionType(NodeExecutionType.CAMEL_ROUTE)
                .script(extractXml).build();

        // Node 2 (native Camel XML): Check compliance — replaces DRL.
        // Priority order matches original DRL salience (signature check fires first,
        // then retention, then fallthrough to compliant — using Camel choice first-match).
        String complianceXml = """
                <route id="kompile-node-compliance">
                  <from uri="direct:kompile-node-compliance"/>
                  <choice>
                    <when>
                      <simple>${header.classification} == 'CONFIDENTIAL' &amp;&amp; ${header.hasSignature} != 'true'</simple>
                      <setHeader name="kompile_output_compliant">
                        <constant>false</constant>
                      </setHeader>
                      <setHeader name="kompile_output_violation">
                        <constant>Confidential documents must be signed</constant>
                      </setHeader>
                    </when>
                    <when>
                      <simple>${header.retentionYears} &lt; 3</simple>
                      <setHeader name="kompile_output_compliant">
                        <constant>false</constant>
                      </setHeader>
                      <setHeader name="kompile_output_violation">
                        <constant>Minimum retention period is 3 years</constant>
                      </setHeader>
                    </when>
                    <otherwise>
                      <setHeader name="kompile_output_compliant">
                        <constant>true</constant>
                      </setHeader>
                      <setHeader name="kompile_output_status">
                        <constant>APPROVED</constant>
                      </setHeader>
                    </otherwise>
                  </choice>
                  <transform>
                    <simple>${header.kompile_output_compliant}</simple>
                  </transform>
                </route>
                """;

        ComputeNode complianceNode = ComputeNode.builder()
                .id("compliance").name("Compliance Check")
                .executionType(NodeExecutionType.CAMEL_ROUTE)
                .script(complianceXml).build();

        // Node 3a (Camel): Approval path
        ComputeNode approvedNode = ComputeNode.builder()
                .id("approved").name("Approved")
                .executionType(NodeExecutionType.CAMEL_ROUTE)
                .script("APPROVED: Document passed compliance").build();

        // Node 3b (Camel): Remediation path
        ComputeNode remediationNode = ComputeNode.builder()
                .id("remediation").name("Remediation")
                .executionType(NodeExecutionType.CAMEL_ROUTE)
                .script("REMEDIATION: ${header.violation}").build();

        ComputeEdge toCompliance = ComputeEdge.builder()
                .id("e1").sourceNodeId("complianceExtract").targetNodeId("compliance").build();
        ComputeEdge toApproved = ComputeEdge.builder()
                .id("e2").sourceNodeId("compliance").targetNodeId("approved")
                .condition("#compliant == 'true'").build();
        ComputeEdge toRemediation = ComputeEdge.builder()
                .id("e3").sourceNodeId("compliance").targetNodeId("remediation")
                .condition("#compliant == 'false'").build();

        ComputeGraph graph = ComputeGraph.builder()
                .id("compliance-workflow")
                .name("Document Compliance")
                .nodes(List.of(extractNode, complianceNode, approvedNode, remediationNode))
                .edges(List.of(toCompliance, toApproved, toRemediation))
                .build();

        DefaultGraphExecutor executor = new DefaultGraphExecutor(
                List.of(camelExecutor, droolsExecutor), new InMemoryArtifactStore());

        // Compliant document: CONFIDENTIAL + signed=true + retention=7
        GraphExecutionResult compliantResult = executor.execute(graph, Map.of(
                "signed", "true", "classification", "CONFIDENTIAL", "retentionYears", 7
        ));
        assertEquals(ExecutionStatus.COMPLETED, compliantResult.getStatus(),
                "Compliant pipeline failed: " + compliantResult.getError());
        assertTrue(compliantResult.getSkippedNodes().contains("remediation"),
                "Remediation node should be skipped for compliant doc");
        assertFalse(compliantResult.getSkippedNodes().contains("approved"),
                "Approved node should NOT be skipped for compliant doc");

        // Non-compliant: missing signature on confidential doc
        GraphExecutionResult nonCompliantResult = executor.execute(graph, Map.of(
                "signed", "false", "classification", "CONFIDENTIAL", "retentionYears", 7
        ));
        assertEquals(ExecutionStatus.COMPLETED, nonCompliantResult.getStatus(),
                "Non-compliant pipeline failed: " + nonCompliantResult.getError());
        assertTrue(nonCompliantResult.getSkippedNodes().contains("approved"),
                "Approved node should be skipped for non-compliant doc");
        assertFalse(nonCompliantResult.getSkippedNodes().contains("remediation"),
                "Remediation node should NOT be skipped for non-compliant doc");
    }

    // ========================================================================
    // Camel EIP Patterns for Document Processing
    // ========================================================================

    @Test
    void splitterForBatchDocumentProcessing() throws Exception {
        // EIP: Splitter — split a batch of documents (newline-delimited),
        // process each one, and collect results at a mock endpoint.
        CamelContext ctx = contextManager.getSharedContext();

        MockEndpoint mockProcessed = ctx.getEndpoint("mock:doc-processed", MockEndpoint.class);
        mockProcessed.expectedMessageCount(4);

        ctx.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:batch-ingest")
                        .routeId("batch-splitter")
                        .split(body().tokenize("\n"))
                            .transform(simple("indexed:${body}"))
                            .setHeader("kompile_docIndex", simple("${exchangeProperty.CamelSplitIndex}"))
                            .to("mock:doc-processed")
                        .end();
            }
        });

        try (ProducerTemplate producer = ctx.createProducerTemplate()) {
            producer.sendBody("direct:batch-ingest",
                    "contract_v2.pdf\nmeeting_notes.docx\nfinancial_q3.xlsx\ninvoice_1234.pdf");
        }

        mockProcessed.assertIsSatisfied(5, TimeUnit.SECONDS);

        // Verify each split document was processed
        List<Exchange> received = mockProcessed.getReceivedExchanges();
        assertEquals(4, received.size());
        assertEquals("indexed:contract_v2.pdf", received.get(0).getIn().getBody(String.class));
        assertEquals("indexed:invoice_1234.pdf", received.get(3).getIn().getBody(String.class));
    }

    @Test
    void wireTapAuditTrailForDocumentProcessing() throws Exception {
        // EIP: Wire Tap — main processing continues while an audit copy
        // is sent to a secondary endpoint. Using multicast to simulate the
        // wire tap pattern synchronously for reliable testing.
        CamelContext ctx = contextManager.getSharedContext();

        MockEndpoint mockAudit = ctx.getEndpoint("mock:audit-log", MockEndpoint.class);
        mockAudit.expectedMessageCount(1);

        MockEndpoint mockResult = ctx.getEndpoint("mock:process-result", MockEndpoint.class);
        mockResult.expectedMessageCount(1);

        ctx.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:process-with-audit")
                        .routeId("audit-wiretap")
                        .setHeader("auditAction", constant("DOCUMENT_PROCESSED"))
                        .setHeader("auditTimestamp", simple("${date:now:yyyy-MM-dd'T'HH:mm:ss}"))
                        .multicast()
                            .to("direct:audit-branch", "direct:main-branch");

                from("direct:audit-branch").routeId("audit-branch")
                        .to("mock:audit-log");

                from("direct:main-branch").routeId("main-branch")
                        .transform(simple("processed: ${body}"))
                        .to("mock:process-result");
            }
        });

        try (ProducerTemplate producer = ctx.createProducerTemplate()) {
            producer.sendBodyAndHeader("direct:process-with-audit",
                    "Confidential merger document", "docId", "DOC-9001");
        }

        mockResult.assertIsSatisfied(5, TimeUnit.SECONDS);
        mockAudit.assertIsSatisfied(5, TimeUnit.SECONDS);

        // Verify main processing continued with transformation
        Exchange resultExchange = mockResult.getReceivedExchanges().get(0);
        assertEquals("processed: Confidential merger document", resultExchange.getIn().getBody(String.class));

        // Verify audit trail captured the original document
        Exchange auditExchange = mockAudit.getReceivedExchanges().get(0);
        assertEquals("DOC-9001", auditExchange.getIn().getHeader("docId"));
        assertEquals("DOCUMENT_PROCESSED", auditExchange.getIn().getHeader("auditAction"));
    }

    @Test
    void multicastToParallelProcessors() throws Exception {
        // EIP: Multicast — same document sent to indexer, archiver, and
        // notification system simultaneously.
        CamelContext ctx = contextManager.getSharedContext();

        MockEndpoint mockIndex = ctx.getEndpoint("mock:indexer", MockEndpoint.class);
        MockEndpoint mockArchive = ctx.getEndpoint("mock:archiver", MockEndpoint.class);
        MockEndpoint mockNotify = ctx.getEndpoint("mock:notifier", MockEndpoint.class);

        mockIndex.expectedMessageCount(1);
        mockArchive.expectedMessageCount(1);
        mockNotify.expectedMessageCount(1);

        ctx.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:doc-multicast")
                        .routeId("doc-multicast")
                        .multicast()
                        .to("direct:index", "direct:archive", "direct:notify");

                from("direct:index").routeId("index-step")
                        .transform(simple("INDEXED: ${body}"))
                        .to("mock:indexer");

                from("direct:archive").routeId("archive-step")
                        .transform(simple("ARCHIVED: ${body}"))
                        .to("mock:archiver");

                from("direct:notify").routeId("notify-step")
                        .transform(simple("NOTIFY: New document: ${body}"))
                        .to("mock:notifier");
            }
        });

        try (ProducerTemplate producer = ctx.createProducerTemplate()) {
            producer.sendBodyAndHeader("direct:doc-multicast",
                    "patent_application_2025.pdf", "category", "legal");
        }

        mockIndex.assertIsSatisfied(5, TimeUnit.SECONDS);
        mockArchive.assertIsSatisfied(5, TimeUnit.SECONDS);
        mockNotify.assertIsSatisfied(5, TimeUnit.SECONDS);

        assertEquals("INDEXED: patent_application_2025.pdf",
                mockIndex.getReceivedExchanges().get(0).getIn().getBody(String.class));
        assertEquals("ARCHIVED: patent_application_2025.pdf",
                mockArchive.getReceivedExchanges().get(0).getIn().getBody(String.class));
        assertTrue(mockNotify.getReceivedExchanges().get(0).getIn().getBody(String.class)
                .contains("patent_application_2025.pdf"));
    }

    @Test
    void filterByRelevanceScore() throws Exception {
        // EIP: Filter — only documents with a relevance score above a
        // threshold are forwarded for graph extraction.
        CamelContext ctx = contextManager.getSharedContext();

        MockEndpoint mockAccepted = ctx.getEndpoint("mock:high-relevance", MockEndpoint.class);
        mockAccepted.expectedMessageCount(2);

        MockEndpoint mockAll = ctx.getEndpoint("mock:all-docs", MockEndpoint.class);
        mockAll.expectedMessageCount(4);

        ctx.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:relevance-filter")
                        .routeId("relevance-filter")
                        .to("mock:all-docs")
                        .filter(simple("${header.score} > 70"))
                            .to("mock:high-relevance");
            }
        });

        try (ProducerTemplate producer = ctx.createProducerTemplate()) {
            producer.sendBodyAndHeader("direct:relevance-filter", "Doc A", "score", 90);
            producer.sendBodyAndHeader("direct:relevance-filter", "Doc B", "score", 40);
            producer.sendBodyAndHeader("direct:relevance-filter", "Doc C", "score", 85);
            producer.sendBodyAndHeader("direct:relevance-filter", "Doc D", "score", 20);
        }

        mockAll.assertIsSatisfied(5, TimeUnit.SECONDS);
        mockAccepted.assertIsSatisfied(5, TimeUnit.SECONDS);

        // Only 2 of 4 docs passed the relevance threshold
        List<Exchange> accepted = mockAccepted.getReceivedExchanges();
        assertEquals("Doc A", accepted.get(0).getIn().getBody(String.class));
        assertEquals("Doc C", accepted.get(1).getIn().getBody(String.class));
    }

    @Test
    void contentBasedRouterForDocumentTypes() throws Exception {
        // EIP: Content-Based Router — XML route dispatches documents to
        // different processors based on their MIME type.
        CamelContext ctx = contextManager.getSharedContext();

        MockEndpoint mockPdf = ctx.getEndpoint("mock:pdf-pipeline", MockEndpoint.class);
        MockEndpoint mockEmail = ctx.getEndpoint("mock:email-pipeline", MockEndpoint.class);
        MockEndpoint mockSpreadsheet = ctx.getEndpoint("mock:spreadsheet-pipeline", MockEndpoint.class);
        MockEndpoint mockUnknown = ctx.getEndpoint("mock:unknown-pipeline", MockEndpoint.class);

        mockPdf.expectedMessageCount(1);
        mockEmail.expectedMessageCount(1);
        mockSpreadsheet.expectedMessageCount(1);
        mockUnknown.expectedMessageCount(1);

        ctx.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:doc-router")
                        .routeId("doc-router")
                        .choice()
                            .when(simple("${header.mimeType} == 'application/pdf'"))
                                .setHeader("pipeline", constant("pdf"))
                                .to("mock:pdf-pipeline")
                            .when(simple("${header.mimeType} == 'message/rfc822'"))
                                .setHeader("pipeline", constant("email"))
                                .to("mock:email-pipeline")
                            .when(simple("${header.mimeType} contains 'spreadsheet'"))
                                .setHeader("pipeline", constant("spreadsheet"))
                                .to("mock:spreadsheet-pipeline")
                            .otherwise()
                                .setHeader("pipeline", constant("generic"))
                                .to("mock:unknown-pipeline")
                        .end();
            }
        });

        try (ProducerTemplate producer = ctx.createProducerTemplate()) {
            producer.sendBodyAndHeader("direct:doc-router", "report.pdf", "mimeType", "application/pdf");
            producer.sendBodyAndHeader("direct:doc-router", "inbox.eml", "mimeType", "message/rfc822");
            producer.sendBodyAndHeader("direct:doc-router", "data.xlsx", "mimeType", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            producer.sendBodyAndHeader("direct:doc-router", "readme.txt", "mimeType", "text/plain");
        }

        mockPdf.assertIsSatisfied(5, TimeUnit.SECONDS);
        mockEmail.assertIsSatisfied(5, TimeUnit.SECONDS);
        mockSpreadsheet.assertIsSatisfied(5, TimeUnit.SECONDS);
        mockUnknown.assertIsSatisfied(5, TimeUnit.SECONDS);
    }

    @Test
    void deadLetterChannelForFailedProcessing() throws Exception {
        // EIP: Dead Letter Channel — documents that fail processing are
        // routed to a DLC instead of crashing the pipeline.
        CamelContext ctx = contextManager.getSharedContext();

        MockEndpoint mockDlc = ctx.getEndpoint("mock:dead-letter", MockEndpoint.class);
        mockDlc.expectedMessageCount(1);

        MockEndpoint mockSuccess = ctx.getEndpoint("mock:success", MockEndpoint.class);
        mockSuccess.expectedMessageCount(1);

        ctx.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                errorHandler(deadLetterChannel("mock:dead-letter")
                        .maximumRedeliveries(2)
                        .redeliveryDelay(0)
                        .useOriginalMessage());

                from("direct:process-risky")
                        .routeId("risky-processor")
                        .process(exchange -> {
                            String body = exchange.getIn().getBody(String.class);
                            if (body.contains("CORRUPT")) {
                                throw new RuntimeException("Corrupt document detected: " + body);
                            }
                        })
                        .transform(simple("OK: ${body}"))
                        .to("mock:success");
            }
        });

        try (ProducerTemplate producer = ctx.createProducerTemplate()) {
            producer.sendBody("direct:process-risky", "valid_document.pdf");
            producer.sendBody("direct:process-risky", "CORRUPT_file.dat");
        }

        mockSuccess.assertIsSatisfied(5, TimeUnit.SECONDS);
        mockDlc.assertIsSatisfied(5, TimeUnit.SECONDS);

        // Verify the valid document went through
        assertEquals("OK: valid_document.pdf",
                mockSuccess.getReceivedExchanges().get(0).getIn().getBody(String.class));

        // Verify the corrupt document landed in the DLC with original body
        assertEquals("CORRUPT_file.dat",
                mockDlc.getReceivedExchanges().get(0).getIn().getBody(String.class));
    }

    // ========================================================================
    // Component Integration Tests
    // ========================================================================

    @Test
    void beanComponentForEntityExtraction() throws Exception {
        // Bean component — invoke a Java class within a Camel route for
        // structured entity extraction from unstructured text.
        CamelContext ctx = contextManager.getSharedContext();

        // Register a bean that extracts entities from text
        ctx.getRegistry().bind("entityExtractor", new EntityExtractorBean());

        ctx.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:extract-entities")
                        .routeId("entity-extractor")
                        .bean("entityExtractor", "extract");
            }
        });

        try (ProducerTemplate producer = ctx.createProducerTemplate()) {
            Exchange result = producer.request("direct:extract-entities", exchange -> {
                exchange.getIn().setBody("Alice from ACME Corp sent the Q3 report to Bob at Globex.");
            });

            assertNull(result.getException());
            @SuppressWarnings("unchecked")
            Map<String, Object> entities = result.getMessage().getBody(Map.class);
            assertNotNull(entities);
            assertTrue(((List<?>) entities.get("persons")).contains("Alice"));
            assertTrue(((List<?>) entities.get("persons")).contains("Bob"));
            assertTrue(((List<?>) entities.get("organizations")).contains("ACME Corp"));
            assertTrue(((List<?>) entities.get("organizations")).contains("Globex"));
        }
    }

    @Test
    void multiStagePipelineWithSplitter() throws Exception {
        // Multi-stage document pipeline: split → normalize → index,
        // each stage as a separate route connected via direct endpoints.
        CamelContext ctx = contextManager.getSharedContext();

        MockEndpoint mockIndexed = ctx.getEndpoint("mock:indexed-docs", MockEndpoint.class);
        mockIndexed.expectedMessageCount(3);

        ctx.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                // Stage 1: accept batch and split into individual docs
                from("direct:submit-docs")
                        .routeId("doc-submitter")
                        .split(body().tokenize("\n"))
                        .to("direct:normalize-doc");

                // Stage 2: normalize each document
                from("direct:normalize-doc")
                        .routeId("doc-normalizer")
                        .setHeader("normalizedAt", simple("${date:now:HH:mm:ss}"))
                        .to("direct:index-doc");

                // Stage 3: index each document
                from("direct:index-doc")
                        .routeId("doc-indexer")
                        .transform(simple("indexed:${body}"))
                        .to("mock:indexed-docs");
            }
        });

        try (ProducerTemplate producer = ctx.createProducerTemplate()) {
            producer.sendBody("direct:submit-docs", "report.pdf\nnotes.docx\ndata.csv");
        }

        mockIndexed.assertIsSatisfied(5, TimeUnit.SECONDS);

        List<Exchange> indexed = mockIndexed.getReceivedExchanges();
        assertEquals(3, indexed.size());
        assertEquals("indexed:report.pdf", indexed.get(0).getIn().getBody(String.class));
        assertEquals("indexed:notes.docx", indexed.get(1).getIn().getBody(String.class));
        assertEquals("indexed:data.csv", indexed.get(2).getIn().getBody(String.class));
    }

    @Test
    void jacksonJsonMarshalingInRoute() throws Exception {
        // Jackson data format — marshal Java Map to JSON and back within
        // a Camel route, simulating JSON-based document exchange.
        CamelContext ctx = contextManager.getSharedContext();

        ctx.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:json-roundtrip")
                        .routeId("json-roundtrip")
                        .marshal().json()
                        .setHeader("kompile_contentType", constant("application/json"))
                        .unmarshal().json(Map.class)
                        .process(exchange -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> doc = exchange.getIn().getBody(Map.class);
                            // Enrich the deserialized document
                            doc.put("enriched", true);
                            doc.put("wordCount", doc.getOrDefault("text", "").toString().split("\\s+").length);
                            exchange.getIn().setBody(doc);
                        });
            }
        });

        try (ProducerTemplate producer = ctx.createProducerTemplate()) {
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("title", "Architecture Decision Record");
            document.put("author", "alice@acme.com");
            document.put("text", "We decided to use Apache Camel for enterprise integration");

            Exchange result = producer.request("direct:json-roundtrip", exchange -> {
                exchange.getIn().setBody(document);
            });

            assertNull(result.getException());
            @SuppressWarnings("unchecked")
            Map<String, Object> output = result.getMessage().getBody(Map.class);
            assertEquals("Architecture Decision Record", output.get("title"));
            assertEquals("alice@acme.com", output.get("author"));
            assertEquals(true, output.get("enriched"));
            assertEquals(9, output.get("wordCount"));
        }
    }

    // ========================================================================
    // Advanced Workflow Patterns
    // ========================================================================

    @Test
    void enricherPatternForContextAugmentation() throws Exception {
        // EIP: Content Enricher — augment a document with additional context
        // fetched from another route (simulating a metadata lookup service).
        CamelContext ctx = contextManager.getSharedContext();

        ctx.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                // Main processing route
                from("direct:enrich-doc")
                        .routeId("doc-enricher")
                        .enrich("direct:metadata-lookup", (original, lookup) -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> metadata = lookup.getIn().getBody(Map.class);
                            original.getIn().setHeader("department", metadata.get("department"));
                            original.getIn().setHeader("classification", metadata.get("classification"));
                            original.getIn().setHeader("retentionYears", metadata.get("retentionYears"));
                            return original;
                        });

                // Metadata lookup service (simulated)
                from("direct:metadata-lookup")
                        .routeId("metadata-lookup")
                        .process(exchange -> {
                            String docId = exchange.getIn().getHeader("docId", String.class);
                            Map<String, Object> metadata = new HashMap<>();
                            if (docId != null && docId.startsWith("FIN-")) {
                                metadata.put("department", "Finance");
                                metadata.put("classification", "CONFIDENTIAL");
                                metadata.put("retentionYears", 7);
                            } else {
                                metadata.put("department", "General");
                                metadata.put("classification", "INTERNAL");
                                metadata.put("retentionYears", 3);
                            }
                            exchange.getIn().setBody(metadata);
                        });
            }
        });

        try (ProducerTemplate producer = ctx.createProducerTemplate()) {
            // Financial document
            Exchange finResult = producer.request("direct:enrich-doc", exchange -> {
                exchange.getIn().setBody("Financial report Q3 2025");
                exchange.getIn().setHeader("docId", "FIN-2025-Q3-001");
            });
            assertEquals("Finance", finResult.getMessage().getHeader("department"));
            assertEquals("CONFIDENTIAL", finResult.getMessage().getHeader("classification"));
            assertEquals(7, finResult.getMessage().getHeader("retentionYears"));

            // General document
            Exchange genResult = producer.request("direct:enrich-doc", exchange -> {
                exchange.getIn().setBody("Team meeting notes");
                exchange.getIn().setHeader("docId", "GEN-2025-001");
            });
            assertEquals("General", genResult.getMessage().getHeader("department"));
            assertEquals("INTERNAL", genResult.getMessage().getHeader("classification"));
        }
    }

    @Test
    void recipientListForDynamicRouting() throws Exception {
        // EIP: Recipient List — dynamically route a document to multiple
        // endpoints based on metadata (e.g., notify all relevant teams).
        CamelContext ctx = contextManager.getSharedContext();

        MockEndpoint mockEng = ctx.getEndpoint("mock:team-engineering", MockEndpoint.class);
        MockEndpoint mockLegal = ctx.getEndpoint("mock:team-legal", MockEndpoint.class);
        MockEndpoint mockFinance = ctx.getEndpoint("mock:team-finance", MockEndpoint.class);

        mockEng.expectedMessageCount(1);
        mockLegal.expectedMessageCount(1);
        mockFinance.expectedMessageCount(0);  // not in the recipient list

        ctx.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:notify-teams")
                        .routeId("team-notifier")
                        .recipientList(header("notifyTeams").tokenize(","));
            }
        });

        try (ProducerTemplate producer = ctx.createProducerTemplate()) {
            producer.sendBodyAndHeader("direct:notify-teams",
                    "Patent filing submitted for review",
                    "notifyTeams", "mock:team-engineering,mock:team-legal");
        }

        mockEng.assertIsSatisfied(5, TimeUnit.SECONDS);
        mockLegal.assertIsSatisfied(5, TimeUnit.SECONDS);
        mockFinance.assertIsSatisfied(5, TimeUnit.SECONDS);
    }

    @Test
    void idempotentConsumerForDocumentDeduplication() throws Exception {
        // EIP: Idempotent Consumer — prevent duplicate documents from
        // being processed twice (e.g., same email ingested from multiple sources).
        CamelContext ctx = contextManager.getSharedContext();

        MockEndpoint mockProcessed = ctx.getEndpoint("mock:deduplicated", MockEndpoint.class);
        mockProcessed.expectedMessageCount(3);  // 5 sent, 2 are duplicates

        ctx.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:dedup-ingest")
                        .routeId("dedup-ingest")
                        .idempotentConsumer(header("documentHash"),
                                MemoryIdempotentRepository.memoryIdempotentRepository(200))
                            .to("mock:deduplicated");
            }
        });

        try (ProducerTemplate producer = ctx.createProducerTemplate()) {
            producer.sendBodyAndHeader("direct:dedup-ingest", "Contract v1", "documentHash", "abc123");
            producer.sendBodyAndHeader("direct:dedup-ingest", "Invoice 001", "documentHash", "def456");
            producer.sendBodyAndHeader("direct:dedup-ingest", "Contract v1 copy", "documentHash", "abc123"); // dup
            producer.sendBodyAndHeader("direct:dedup-ingest", "Report Q3", "documentHash", "ghi789");
            producer.sendBodyAndHeader("direct:dedup-ingest", "Invoice 001 resend", "documentHash", "def456"); // dup
        }

        mockProcessed.assertIsSatisfied(5, TimeUnit.SECONDS);

        List<Exchange> processed = mockProcessed.getReceivedExchanges();
        assertEquals("Contract v1", processed.get(0).getIn().getBody(String.class));
        assertEquals("Invoice 001", processed.get(1).getIn().getBody(String.class));
        assertEquals("Report Q3", processed.get(2).getIn().getBody(String.class));
    }

    // ========================================================================
    // Full End-to-End Pipeline
    // ========================================================================

    @Test
    void fullDocumentToKnowledgeGraphPipeline() {
        // Complete real-world pipeline:
        // 1. Camel: Parse raw document (extract title, author, body)
        // 2. Native Camel XML: Classify document and determine entity types
        // 3. Native Camel XML: Apply business rules for priority scoring
        // 4. Camel: Format final graph-ready output
        //
        // Rewritten from two DRL nodes to two native Camel XML choice/when nodes.
        // Node 2 (classify): author→PERSON:, docCategory+title→CATEGORY:title, relationship=AUTHORED_BY
        // Node 3 (score): FINANCIAL→HIGH/review=true, TECHNICAL→MEDIUM/review=false, else→NORMAL/review=false

        // Node 1: Parse document metadata
        String parseXml = """
                <route id="kompile-node-doc-parse">
                  <from uri="direct:kompile-node-doc-parse"/>
                  <setHeader name="kompile_output_title">
                    <simple>${header.title}</simple>
                  </setHeader>
                  <setHeader name="kompile_output_author">
                    <simple>${header.author}</simple>
                  </setHeader>
                  <setHeader name="kompile_output_docCategory">
                    <simple>${header.category}</simple>
                  </setHeader>
                  <setHeader name="kompile_output_wordCount">
                    <simple resultType="java.lang.Integer">${header.wordCount}</simple>
                  </setHeader>
                  <transform><simple>parsed</simple></transform>
                </route>
                """;

        ComputeNode parseNode = ComputeNode.builder()
                .id("doc-parse").name("Parse Document")
                .executionType(NodeExecutionType.CAMEL_ROUTE)
                .script(parseXml).build();

        // Node 2: Classify entities — replaces DRL rules.
        // Mirrors: identify-author-entity + identify-document-entity + determine-relationship.
        // All three rules always fire (no conditional guard other than "field exists"),
        // so we emit all three outputs unconditionally.
        String classifyXml = """
                <route id="kompile-node-classify">
                  <from uri="direct:kompile-node-classify"/>
                  <setHeader name="kompile_output_authorEntity">
                    <simple>PERSON:${header.author}</simple>
                  </setHeader>
                  <setHeader name="kompile_output_documentEntity">
                    <simple>${header.docCategory}:${header.title}</simple>
                  </setHeader>
                  <setHeader name="kompile_output_relationship">
                    <constant>AUTHORED_BY</constant>
                  </setHeader>
                  <transform><simple>classified</simple></transform>
                </route>
                """;

        ComputeNode classifyNode = ComputeNode.builder()
                .id("classify").name("Classify Entities")
                .executionType(NodeExecutionType.CAMEL_ROUTE)
                .script(classifyXml).build();

        // Node 3: Score priority — replaces DRL rules.
        // Mirrors salience-10 high-priority-financial, salience-5 medium-priority-technical,
        // salience-1 normal-priority-default using Camel choice/when first-match semantics.
        String scoreXml = """
                <route id="kompile-node-score">
                  <from uri="direct:kompile-node-score"/>
                  <choice>
                    <when>
                      <simple>${header.docCategory} == 'FINANCIAL'</simple>
                      <setHeader name="kompile_output_priority">
                        <constant>HIGH</constant>
                      </setHeader>
                      <setHeader name="kompile_output_reviewRequired">
                        <constant>true</constant>
                      </setHeader>
                    </when>
                    <when>
                      <simple>${header.docCategory} == 'TECHNICAL'</simple>
                      <setHeader name="kompile_output_priority">
                        <constant>MEDIUM</constant>
                      </setHeader>
                      <setHeader name="kompile_output_reviewRequired">
                        <constant>false</constant>
                      </setHeader>
                    </when>
                    <otherwise>
                      <setHeader name="kompile_output_priority">
                        <constant>NORMAL</constant>
                      </setHeader>
                      <setHeader name="kompile_output_reviewRequired">
                        <constant>false</constant>
                      </setHeader>
                    </otherwise>
                  </choice>
                  <transform>
                    <simple>${header.kompile_output_priority}</simple>
                  </transform>
                </route>
                """;

        ComputeNode scoreNode = ComputeNode.builder()
                .id("score").name("Priority Scoring")
                .executionType(NodeExecutionType.CAMEL_ROUTE)
                .script(scoreXml).build();

        // Node 4: Format graph output (receives from both classify and score)
        ComputeNode formatNode = ComputeNode.builder()
                .id("format").name("Format Graph")
                .executionType(NodeExecutionType.CAMEL_ROUTE)
                .script("[${header.authorEntity}]-[${header.relationship}]->[${header.documentEntity}] priority=${header.priority} review=${header.reviewRequired}")
                .build();

        // Fan-out from parse: both classify and score receive parse outputs
        // Fan-in to format: both classify and score feed into format
        ComputeEdge e1 = ComputeEdge.builder().id("e1").sourceNodeId("doc-parse").targetNodeId("classify").build();
        ComputeEdge e2 = ComputeEdge.builder().id("e2").sourceNodeId("doc-parse").targetNodeId("score").build();
        ComputeEdge e3 = ComputeEdge.builder().id("e3").sourceNodeId("classify").targetNodeId("format").build();
        ComputeEdge e4 = ComputeEdge.builder().id("e4").sourceNodeId("score").targetNodeId("format").build();

        ComputeGraph graph = ComputeGraph.builder()
                .id("full-pipeline")
                .name("Document to Knowledge Graph")
                .nodes(List.of(parseNode, classifyNode, scoreNode, formatNode))
                .edges(List.of(e1, e2, e3, e4))
                .build();

        DefaultGraphExecutor executor = new DefaultGraphExecutor(
                List.of(camelExecutor, droolsExecutor), new InMemoryArtifactStore());

        // Test with a financial document
        GraphExecutionResult finResult = executor.execute(graph, Map.of(
                "title", "Q3 Revenue Analysis",
                "author", "alice@acme.com",
                "category", "FINANCIAL",
                "wordCount", "3500"
        ));

        assertEquals(ExecutionStatus.COMPLETED, finResult.getStatus(),
                "Pipeline failed: " + finResult.getError());
        assertEquals(4, finResult.getExecutionOrder().size());

        String finOutput = (String) finResult.getFinalOutputs().get("result");
        assertTrue(finOutput.contains("PERSON:alice@acme.com"), "Should have author entity. Actual: " + finOutput);
        assertTrue(finOutput.contains("FINANCIAL:Q3 Revenue Analysis"), "Should have document entity");
        assertTrue(finOutput.contains("AUTHORED_BY"), "Should have relationship");
        assertTrue(finOutput.contains("priority=HIGH"), "Financial docs should be HIGH priority");
        assertTrue(finOutput.contains("review=true"), "Financial docs should require review");

        // Test with a technical document
        GraphExecutionResult techResult = executor.execute(graph, Map.of(
                "title", "API Design Guidelines",
                "author", "bob@acme.com",
                "category", "TECHNICAL",
                "wordCount", "1200"
        ));

        assertEquals(ExecutionStatus.COMPLETED, techResult.getStatus());
        String techOutput = (String) techResult.getFinalOutputs().get("result");
        assertTrue(techOutput.contains("PERSON:bob@acme.com"));
        assertTrue(techOutput.contains("TECHNICAL:API Design Guidelines"));
        assertTrue(techOutput.contains("priority=MEDIUM"));
        assertTrue(techOutput.contains("review=false"));
    }

    // ========================================================================
    // Helper Bean for Entity Extraction
    // ========================================================================

    /**
     * Simulates a simple named-entity extractor that identifies persons
     * and organizations from unstructured text using keyword matching.
     * In production this would be backed by an NLP model or LLM.
     */
    public static class EntityExtractorBean {
        private static final List<String> KNOWN_ORGS = List.of(
                "ACME Corp", "Globex", "Initech", "Umbrella Corp", "Stark Industries");

        @SuppressWarnings("unused")
        public Map<String, Object> extract(String text) {
            Map<String, Object> result = new HashMap<>();
            List<String> persons = new ArrayList<>();
            List<String> organizations = new ArrayList<>();

            // Simple heuristic: capitalized words before "from" or "to" are persons
            String[] words = text.split("\\s+");
            for (int i = 0; i < words.length; i++) {
                String word = words[i].replaceAll("[^a-zA-Z]", "");
                if (!word.isEmpty() && Character.isUpperCase(word.charAt(0))
                        && !isStopWord(word) && word.length() > 1) {
                    // Check if this starts a known org name
                    boolean isOrg = false;
                    for (String org : KNOWN_ORGS) {
                        if (text.contains(org) && org.startsWith(word)) {
                            if (!organizations.contains(org)) {
                                organizations.add(org);
                            }
                            isOrg = true;
                            break;
                        }
                    }
                    if (!isOrg && !persons.contains(word)) {
                        persons.add(word);
                    }
                }
            }

            // Remove persons that are part of org names
            persons.removeIf(p -> organizations.stream().anyMatch(o -> o.contains(p)));

            result.put("persons", persons);
            result.put("organizations", organizations);
            result.put("entityCount", persons.size() + organizations.size());
            return result;
        }

        private boolean isStopWord(String word) {
            return Set.of("The", "This", "That", "From", "With", "And", "For", "But", "Not")
                    .contains(word);
        }
    }
}
