package ai.kompile.compute.graph.camel;

import ai.kompile.compute.graph.drools.DroolsNodeExecutor;
import ai.kompile.compute.graph.drools.DroolsRuleCompiler;
import ai.kompile.compute.graph.engine.DefaultGraphExecutor;
import ai.kompile.compute.graph.engine.ExecutionContext;
import ai.kompile.compute.graph.engine.NodeExecutor;
import ai.kompile.compute.graph.model.*;
import ai.kompile.compute.graph.store.InMemoryArtifactStore;
import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.attachment.AttachmentMessage;
import org.apache.camel.attachment.DefaultAttachment;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that exercise real email-with-attachments workflows: parsing multipart
 * emails, splitting out attachments, classifying them by type, applying business
 * rules for compliance and routing, extracting entities from email body and
 * attachment contents, and building knowledge graph relationships.
 *
 * Each test simulates the data structures that Apache Camel's mail component
 * produces when consuming from IMAP/POP3, using the {@code camel-attachments}
 * API ({@link AttachmentMessage}, {@link DefaultAttachment}).
 */
class CamelEmailAttachmentWorkflowTest {

    private CamelContextManager contextManager;
    private CamelRouteParser routeParser;
    private CamelNodeExecutor camelExecutor;
    private DroolsNodeExecutor droolsExecutor;

    @BeforeEach
    void setUp() {
        contextManager = new CamelContextManager();
        routeParser = new CamelRouteParser();
        camelExecutor = new CamelNodeExecutor(contextManager, routeParser, 30000);
        droolsExecutor = new DroolsNodeExecutor(new DroolsRuleCompiler());
    }

    @AfterEach
    void tearDown() {
        contextManager.close();
    }

    // ========================================================================
    // 1. Email arrival: parse headers, extract attachment inventory
    // ========================================================================

    @Test
    void parseEmailWithAttachmentsAndBuildInventory() throws Exception {
        // Simulates: a multipart email arrives from the mail component,
        // a route extracts the header fields, counts attachments, and
        // builds an inventory map of filename → mimeType.
        CamelContext ctx = contextManager.getSharedContext();

        ctx.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:email-arrival")
                        .routeId("email-inventory")
                        .process(exchange -> {
                            AttachmentMessage msg = exchange.getIn(AttachmentMessage.class);
                            Map<String, Object> inventory = new LinkedHashMap<>();

                            // Email metadata
                            inventory.put("from", exchange.getIn().getHeader("From"));
                            inventory.put("to", exchange.getIn().getHeader("To"));
                            inventory.put("subject", exchange.getIn().getHeader("Subject"));
                            inventory.put("bodyPreview", truncate(exchange.getIn().getBody(String.class), 100));

                            // Attachment inventory
                            List<Map<String, Object>> attachments = new ArrayList<>();
                            if (msg.hasAttachments()) {
                                for (String name : msg.getAttachmentNames()) {
                                    DefaultAttachment att = (DefaultAttachment) msg.getAttachmentObject(name);
                                    Map<String, Object> meta = new LinkedHashMap<>();
                                    meta.put("filename", name);
                                    meta.put("contentType", att.getDataHandler().getContentType());
                                    meta.put("disposition", att.getHeader("Content-Disposition"));
                                    attachments.add(meta);
                                }
                            }
                            inventory.put("attachments", attachments);
                            inventory.put("attachmentCount", attachments.size());
                            exchange.getIn().setBody(inventory);
                        });
            }
        });

        try (ProducerTemplate producer = ctx.createProducerTemplate()) {
            Exchange result = producer.request("direct:email-arrival", exchange -> {
                exchange.getIn().setHeader("From", "alice@acme.com");
                exchange.getIn().setHeader("To", "legal-team@acme.com");
                exchange.getIn().setHeader("Subject", "M&A Due Diligence - CONFIDENTIAL");
                exchange.getIn().setBody("Please find the due diligence documents attached.\n"
                        + "The financial model and legal memo are for internal review only.");

                addAttachment(exchange, "financial_model_q3.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "Revenue projections and valuation model");
                addAttachment(exchange, "legal_memo_nda.pdf",
                        "application/pdf",
                        "Non-disclosure agreement and legal review");
                addAttachment(exchange, "target_org_chart.png",
                        "image/png",
                        "<binary image data>");
            });

            assertNull(result.getException());
            @SuppressWarnings("unchecked")
            Map<String, Object> inventory = result.getMessage().getBody(Map.class);

            assertEquals("alice@acme.com", inventory.get("from"));
            assertEquals("M&A Due Diligence - CONFIDENTIAL", inventory.get("subject"));
            assertEquals(3, inventory.get("attachmentCount"));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> attachments = (List<Map<String, Object>>) inventory.get("attachments");
            assertEquals("financial_model_q3.xlsx", attachments.get(0).get("filename"));
            assertEquals("legal_memo_nda.pdf", attachments.get(1).get("filename"));
            assertEquals("target_org_chart.png", attachments.get(2).get("filename"));
        }
    }

    // ========================================================================
    // 2. Split attachments, route each by type
    // ========================================================================

    @Test
    void splitAttachmentsAndRouteByMimeType() throws Exception {
        // Simulates: email arrives → split into individual attachments →
        // content-based router sends each to the appropriate processor
        // (PDF pipeline, spreadsheet pipeline, image pipeline, etc.)
        CamelContext ctx = contextManager.getSharedContext();

        MockEndpoint mockPdf = ctx.getEndpoint("mock:pdf-attachments", MockEndpoint.class);
        MockEndpoint mockSpreadsheet = ctx.getEndpoint("mock:spreadsheet-attachments", MockEndpoint.class);
        MockEndpoint mockImage = ctx.getEndpoint("mock:image-attachments", MockEndpoint.class);
        MockEndpoint mockOther = ctx.getEndpoint("mock:other-attachments", MockEndpoint.class);

        mockPdf.expectedMessageCount(2);
        mockSpreadsheet.expectedMessageCount(1);
        mockImage.expectedMessageCount(1);
        mockOther.expectedMessageCount(1);

        ctx.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:split-attachments")
                        .routeId("attachment-splitter")
                        .process(exchange -> {
                            // Extract attachments into a List the splitter can iterate
                            AttachmentMessage msg = exchange.getIn(AttachmentMessage.class);
                            List<Map<String, Object>> attachmentList = new ArrayList<>();
                            if (msg.hasAttachments()) {
                                for (String name : msg.getAttachmentNames()) {
                                    DefaultAttachment att = (DefaultAttachment) msg.getAttachmentObject(name);
                                    Map<String, Object> item = new LinkedHashMap<>();
                                    item.put("filename", name);
                                    item.put("contentType", att.getDataHandler().getContentType());
                                    item.put("content", readContent(att));
                                    item.put("emailFrom", exchange.getIn().getHeader("From"));
                                    item.put("emailSubject", exchange.getIn().getHeader("Subject"));
                                    attachmentList.add(item);
                                }
                            }
                            exchange.getIn().setBody(attachmentList);
                        })
                        .split(body())
                            .process(exchange -> {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> att = exchange.getIn().getBody(Map.class);
                                exchange.getIn().setHeader("attachmentFile", att.get("filename"));
                                exchange.getIn().setHeader("attachmentType", att.get("contentType"));
                                exchange.getIn().setHeader("emailFrom", att.get("emailFrom"));
                            })
                            .choice()
                                .when(simple("${header.attachmentType} contains 'pdf'"))
                                    .to("mock:pdf-attachments")
                                .when(simple("${header.attachmentType} contains 'spreadsheet' "
                                        + "|| ${header.attachmentType} contains 'excel'"))
                                    .to("mock:spreadsheet-attachments")
                                .when(simple("${header.attachmentType} starts with 'image/'"))
                                    .to("mock:image-attachments")
                                .otherwise()
                                    .to("mock:other-attachments")
                            .end()
                        .end();
            }
        });

        try (ProducerTemplate producer = ctx.createProducerTemplate()) {
            producer.request("direct:split-attachments", exchange -> {
                exchange.getIn().setHeader("From", "cfo@megacorp.com");
                exchange.getIn().setHeader("Subject", "Board Meeting Materials");
                exchange.getIn().setBody("Board meeting materials for next Thursday.");

                addAttachment(exchange, "agenda.pdf", "application/pdf", "Meeting agenda");
                addAttachment(exchange, "financials.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "Financial summary");
                addAttachment(exchange, "slides_cover.png", "image/png", "<image>");
                addAttachment(exchange, "minutes_draft.pdf", "application/pdf", "Draft minutes");
                addAttachment(exchange, "notes.txt", "text/plain", "Additional notes");
            });
        }

        mockPdf.assertIsSatisfied(5, TimeUnit.SECONDS);
        mockSpreadsheet.assertIsSatisfied(5, TimeUnit.SECONDS);
        mockImage.assertIsSatisfied(5, TimeUnit.SECONDS);
        mockOther.assertIsSatisfied(5, TimeUnit.SECONDS);

        // Verify PDF attachments
        assertEquals("agenda.pdf",
                mockPdf.getReceivedExchanges().get(0).getIn().getHeader("attachmentFile"));
        assertEquals("minutes_draft.pdf",
                mockPdf.getReceivedExchanges().get(1).getIn().getHeader("attachmentFile"));
        // All attachments carry the source email metadata
        assertEquals("cfo@megacorp.com",
                mockSpreadsheet.getReceivedExchanges().get(0).getIn().getHeader("emailFrom"));
    }

    // ========================================================================
    // 3. Drools compliance rules on email attachments
    // ========================================================================

    @Test
    void attachmentComplianceCheckWithDrools() {
        // Multi-node pipeline:
        // Node 1 (Camel): Parse email and build attachment metadata
        // Node 2 (Drools): Apply compliance rules:
        //   - Confidential emails must not have image attachments
        //   - Attachments over 10MB require manager approval
        //   - PDFs in legal emails must be digitally signed
        //   - Executable attachments (.exe, .bat) are always blocked

        String parseXml = """
                <route id="kompile-node-complianceParse">
                  <from uri="direct:kompile-node-complianceParse"/>
                  <setHeader name="kompile_output_subject">
                    <simple>${header.subject}</simple>
                  </setHeader>
                  <setHeader name="kompile_output_sender">
                    <simple>${header.sender}</simple>
                  </setHeader>
                  <setHeader name="kompile_output_classification">
                    <simple>${header.classification}</simple>
                  </setHeader>
                  <setHeader name="kompile_output_attachmentName">
                    <simple>${header.attachmentName}</simple>
                  </setHeader>
                  <setHeader name="kompile_output_attachmentType">
                    <simple>${header.attachmentType}</simple>
                  </setHeader>
                  <setHeader name="kompile_output_attachmentSizeMB">
                    <simple resultType="java.lang.Double">${header.attachmentSizeMB}</simple>
                  </setHeader>
                  <setHeader name="kompile_output_recipientDept">
                    <simple>${header.recipientDept}</simple>
                  </setHeader>
                  <transform><simple>parsed</simple></transform>
                </route>
                """;

        ComputeNode parseNode = ComputeNode.builder()
                .id("complianceParse").name("Parse Attachment Metadata")
                .executionType(NodeExecutionType.CAMEL_ROUTE)
                .script(parseXml).build();

        String complianceDrl = """
                rule "block-executable-attachments"
                  salience 100
                  when
                    $facts : NodeFacts()
                    $name : NamedFact(name == "attachmentName")
                    eval(((String)$name.getValue()).endsWith(".exe")
                      || ((String)$name.getValue()).endsWith(".bat")
                      || ((String)$name.getValue()).endsWith(".sh"))
                  then
                    $facts.setOutput("decision", "BLOCKED");
                    $facts.setOutput("reason", "Executable attachments are prohibited");
                    $facts.setOutput("severity", "CRITICAL");
                end

                rule "block-images-in-confidential"
                  salience 90
                  when
                    $facts : NodeFacts()
                    $class : NamedFact(name == "classification")
                    $type : NamedFact(name == "attachmentType")
                    eval("CONFIDENTIAL".equals($class.getValue())
                      && ((String)$type.getValue()).startsWith("image/"))
                  then
                    $facts.setOutput("decision", "BLOCKED");
                    $facts.setOutput("reason", "Image attachments not allowed in confidential emails");
                    $facts.setOutput("severity", "HIGH");
                end

                rule "require-approval-large-files"
                  salience 80
                  when
                    $facts : NodeFacts()
                    $size : NamedFact(name == "attachmentSizeMB")
                    eval(((Number)$size.getValue()).doubleValue() > 10.0)
                  then
                    $facts.setOutput("decision", "NEEDS_APPROVAL");
                    $facts.setOutput("reason", "Attachment exceeds 10MB size limit");
                    $facts.setOutput("severity", "MEDIUM");
                end

                rule "flag-pdf-in-legal-unsigned"
                  salience 70
                  when
                    $facts : NodeFacts()
                    $dept : NamedFact(name == "recipientDept")
                    $type : NamedFact(name == "attachmentType")
                    eval("legal".equalsIgnoreCase((String)$dept.getValue())
                      && ((String)$type.getValue()).contains("pdf"))
                  then
                    $facts.setOutput("decision", "FLAGGED");
                    $facts.setOutput("reason", "PDFs sent to Legal should be digitally signed");
                    $facts.setOutput("severity", "LOW");
                end

                rule "approve-compliant"
                  salience -10
                  when
                    $facts : NodeFacts()
                    not (eval($facts.getOutputs().containsKey("decision")))
                  then
                    $facts.setOutput("decision", "APPROVED");
                    $facts.setOutput("reason", "Attachment passes all compliance checks");
                    $facts.setOutput("severity", "NONE");
                end
                """;

        ComputeNode complianceNode = ComputeNode.builder()
                .id("complianceRules").name("Compliance Rules")
                .executionType(NodeExecutionType.DROOLS_RULE)
                .script(complianceDrl).build();

        ComputeEdge edge = ComputeEdge.builder()
                .id("e1").sourceNodeId("complianceParse").targetNodeId("complianceRules").build();

        ComputeGraph graph = ComputeGraph.builder()
                .id("attachment-compliance")
                .name("Attachment Compliance Check")
                .nodes(List.of(parseNode, complianceNode))
                .edges(List.of(edge))
                .build();

        DefaultGraphExecutor executor = new DefaultGraphExecutor(
                List.of(camelExecutor, droolsExecutor), new InMemoryArtifactStore());

        // Test 1: executable attachment → BLOCKED
        GraphExecutionResult exeResult = executor.execute(graph, Map.of(
                "subject", "Tools Update", "sender", "dev@acme.com",
                "classification", "INTERNAL", "recipientDept", "engineering",
                "attachmentName", "installer.exe", "attachmentType", "application/octet-stream",
                "attachmentSizeMB", "5.0"
        ));
        assertEquals("BLOCKED", exeResult.getFinalOutputs().get("decision"));
        assertEquals("CRITICAL", exeResult.getFinalOutputs().get("severity"));

        // Test 2: image in confidential email → BLOCKED
        GraphExecutionResult imgResult = executor.execute(graph, Map.of(
                "subject", "Board Plans", "sender", "ceo@acme.com",
                "classification", "CONFIDENTIAL", "recipientDept", "executive",
                "attachmentName", "whiteboard_photo.jpg", "attachmentType", "image/jpeg",
                "attachmentSizeMB", "2.5"
        ));
        assertEquals("BLOCKED", imgResult.getFinalOutputs().get("decision"));
        assertEquals("HIGH", imgResult.getFinalOutputs().get("severity"));

        // Test 3: oversized file → NEEDS_APPROVAL
        GraphExecutionResult largeResult = executor.execute(graph, Map.of(
                "subject", "Data Export", "sender", "analyst@acme.com",
                "classification", "INTERNAL", "recipientDept", "data",
                "attachmentName", "full_export.csv", "attachmentType", "text/csv",
                "attachmentSizeMB", "25.0"
        ));
        assertEquals("NEEDS_APPROVAL", largeResult.getFinalOutputs().get("decision"));

        // Test 4: PDF to legal department → FLAGGED
        GraphExecutionResult legalResult = executor.execute(graph, Map.of(
                "subject", "Contract Review", "sender", "procurement@acme.com",
                "classification", "INTERNAL", "recipientDept", "legal",
                "attachmentName", "vendor_contract.pdf", "attachmentType", "application/pdf",
                "attachmentSizeMB", "1.5"
        ));
        assertEquals("FLAGGED", legalResult.getFinalOutputs().get("decision"));
        assertTrue(((String) legalResult.getFinalOutputs().get("reason")).contains("digitally signed"));

        // Test 5: normal attachment → APPROVED
        GraphExecutionResult okResult = executor.execute(graph, Map.of(
                "subject", "Meeting Notes", "sender", "pm@acme.com",
                "classification", "INTERNAL", "recipientDept", "engineering",
                "attachmentName", "notes.docx", "attachmentType", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "attachmentSizeMB", "0.5"
        ));
        assertEquals("APPROVED", okResult.getFinalOutputs().get("decision"));
    }

    // ========================================================================
    // 4. Full email → attachment → entity extraction → graph building
    // ========================================================================

    @Test
    void emailAttachmentEntityExtractionToGraphPipeline() throws Exception {
        // End-to-end pipeline processing a real email structure:
        // 1. Camel route: parse email, extract per-attachment records
        // 2. For each attachment: extract entities from the content
        // 3. Build graph relationships:
        //    Person -[SENT]-> Email -[HAS_ATTACHMENT]-> Attachment
        //    Attachment -[MENTIONS]-> Entity
        CamelContext ctx = contextManager.getSharedContext();

        MockEndpoint mockGraph = ctx.getEndpoint("mock:graph-output", MockEndpoint.class);
        mockGraph.expectedMinimumMessageCount(1);

        ctx.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:email-to-graph")
                        .routeId("email-graph-builder")
                        // Step 1: Parse email structure
                        .process(exchange -> {
                            String from = exchange.getIn().getHeader("From", String.class);
                            String to = exchange.getIn().getHeader("To", String.class);
                            String subject = exchange.getIn().getHeader("Subject", String.class);
                            String body = exchange.getIn().getBody(String.class);

                            List<Map<String, Object>> graphTriples = new ArrayList<>();

                            // Person → Email relationship
                            graphTriples.add(triple(from, "SENT", "email:" + subject));
                            graphTriples.add(triple("email:" + subject, "SENT_TO", to));

                            // Extract entities from body
                            for (String entity : extractEntities(body)) {
                                graphTriples.add(triple("email:" + subject, "MENTIONS", entity));
                            }

                            // Process attachments
                            AttachmentMessage msg = exchange.getIn(AttachmentMessage.class);
                            if (msg.hasAttachments()) {
                                for (String name : msg.getAttachmentNames()) {
                                    DefaultAttachment att = (DefaultAttachment) msg.getAttachmentObject(name);
                                    String content = readContent(att);

                                    // Email → Attachment relationship
                                    graphTriples.add(triple("email:" + subject, "HAS_ATTACHMENT", "file:" + name));
                                    graphTriples.add(Map.of(
                                            "subject", "file:" + name,
                                            "predicate", "HAS_TYPE",
                                            "object", att.getDataHandler().getContentType()
                                    ));

                                    // Extract entities from attachment content
                                    for (String entity : extractEntities(content)) {
                                        graphTriples.add(triple("file:" + name, "MENTIONS", entity));
                                    }
                                }
                            }

                            exchange.getIn().setBody(graphTriples);
                            exchange.getIn().setHeader("tripleCount", graphTriples.size());
                            exchange.getIn().setHeader("emailId", "email:" + subject);
                        })
                        .to("mock:graph-output");
            }
        });

        try (ProducerTemplate producer = ctx.createProducerTemplate()) {
            producer.request("direct:email-to-graph", exchange -> {
                exchange.getIn().setHeader("From", "alice@acme.com");
                exchange.getIn().setHeader("To", "bob@globex.com");
                exchange.getIn().setHeader("Subject", "Partnership Agreement");
                exchange.getIn().setBody("Hi Bob,\nPlease review the partnership agreement "
                        + "between ACME Corp and Globex Corporation.\nRegards, Alice");

                addAttachment(exchange, "partnership_agreement_v3.pdf",
                        "application/pdf",
                        "Partnership Agreement between ACME Corp and Globex Corporation. "
                                + "Effective date: January 2026. Signed by Alice Smith (ACME) "
                                + "and Bob Johnson (Globex).");
                addAttachment(exchange, "financial_terms.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "Revenue sharing: ACME Corp receives 60%, Globex Corporation 40%. "
                                + "Total projected value: $5M over 3 years.");
            });
        }

        mockGraph.assertIsSatisfied(5, TimeUnit.SECONDS);

        Exchange graphExchange = mockGraph.getReceivedExchanges().get(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> triples = graphExchange.getIn().getBody(List.class);
        int tripleCount = graphExchange.getIn().getHeader("tripleCount", Integer.class);
        assertTrue(tripleCount >= 8, "Should produce at least 8 triples, got " + tripleCount);

        // Verify key relationships exist
        assertTrue(containsTriple(triples, "alice@acme.com", "SENT", "email:Partnership Agreement"));
        assertTrue(containsTriple(triples, "email:Partnership Agreement", "SENT_TO", "bob@globex.com"));
        assertTrue(containsTriple(triples, "email:Partnership Agreement", "HAS_ATTACHMENT", "file:partnership_agreement_v3.pdf"));
        assertTrue(containsTriple(triples, "email:Partnership Agreement", "HAS_ATTACHMENT", "file:financial_terms.xlsx"));

        // Verify entity extraction from attachment content
        assertTrue(containsTriple(triples, "file:partnership_agreement_v3.pdf", "MENTIONS", "ACME Corp"),
                "Should extract ACME Corp from PDF content");
        assertTrue(containsTriple(triples, "file:financial_terms.xlsx", "MENTIONS", "Globex Corporation"),
                "Should extract Globex from spreadsheet content");
    }

    // ========================================================================
    // 5. Batch email processing: multiple emails, each with attachments
    // ========================================================================

    @Test
    void batchEmailProcessingWithAttachmentClassification() throws Exception {
        // Simulates polling a mailbox: multiple emails arrive in a batch,
        // each with different attachments. Route classifies the emails by
        // urgency based on attachment content, then counts and reports.
        CamelContext ctx = contextManager.getSharedContext();

        MockEndpoint mockUrgent = ctx.getEndpoint("mock:urgent-emails", MockEndpoint.class);
        MockEndpoint mockNormal = ctx.getEndpoint("mock:normal-emails", MockEndpoint.class);

        mockUrgent.expectedMessageCount(1);
        mockNormal.expectedMessageCount(2);

        ctx.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:batch-emails")
                        .routeId("batch-processor")
                        .split(body())
                            .process(exchange -> {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> email = exchange.getIn().getBody(Map.class);
                                exchange.getIn().setHeader("subject", email.get("subject"));
                                exchange.getIn().setHeader("from", email.get("from"));
                                exchange.getIn().setHeader("attachmentCount",
                                        ((List<?>) email.get("attachments")).size());

                                // Classify urgency based on attachment names and types
                                @SuppressWarnings("unchecked")
                                List<Map<String, String>> attachments =
                                        (List<Map<String, String>>) email.get("attachments");
                                boolean urgent = attachments.stream().anyMatch(a ->
                                        a.get("filename").contains("URGENT")
                                                || a.get("filename").contains("legal")
                                                || a.get("contentType").contains("pdf"));
                                exchange.getIn().setHeader("urgency",
                                        urgent ? "URGENT" : "NORMAL");
                            })
                            .choice()
                                .when(simple("${header.urgency} == 'URGENT'"))
                                    .to("mock:urgent-emails")
                                .otherwise()
                                    .to("mock:normal-emails")
                            .end()
                        .end();
            }
        });

        // Build a batch of 3 emails
        List<Map<String, Object>> emailBatch = List.of(
                buildEmailMap("cfo@acme.com", "Quarterly Financials",
                        List.of(att("Q3_report.pdf", "application/pdf"),
                                att("projections.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))),
                buildEmailMap("intern@acme.com", "Lunch Options Survey",
                        List.of(att("survey.txt", "text/plain"))),
                buildEmailMap("pm@acme.com", "Sprint Retrospective",
                        List.of(att("retro_notes.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                                att("action_items.txt", "text/plain")))
        );

        try (ProducerTemplate producer = ctx.createProducerTemplate()) {
            producer.sendBody("direct:batch-emails", emailBatch);
        }

        mockUrgent.assertIsSatisfied(5, TimeUnit.SECONDS);
        mockNormal.assertIsSatisfied(5, TimeUnit.SECONDS);

        // The CFO email has a PDF → classified as URGENT
        Exchange urgentEmail = mockUrgent.getReceivedExchanges().get(0);
        assertEquals("Quarterly Financials", urgentEmail.getIn().getHeader("subject"));
        assertEquals(2, urgentEmail.getIn().getHeader("attachmentCount"));

        // The other two are normal
        List<Exchange> normalEmails = mockNormal.getReceivedExchanges();
        assertTrue(normalEmails.stream().anyMatch(e ->
                "Lunch Options Survey".equals(e.getIn().getHeader("subject"))));
    }

    // ========================================================================
    // 6. Attachment content extraction → Drools entity classification
    // ========================================================================

    @Test
    void attachmentContentClassificationWithDrools() {
        // Two-node pipeline: Camel extracts attachment content fields →
        // Drools rules classify the attachment as containing PII,
        // financial data, legal terms, or technical content.

        ComputeNode extractNode = ComputeNode.builder()
                .id("extractContent").name("Extract Content")
                .executionType(NodeExecutionType.CAMEL_ROUTE)
                .script("${header.content}")
                .build();

        String classifyDrl = """
                rule "detect-pii"
                  salience 100
                  when
                    $facts : NodeFacts()
                    $content : NamedFact(name == "result")
                    eval(((String)$content.getValue()).matches("(?i).*\\\\b(ssn|social security|date of birth|passport)\\\\b.*"))
                  then
                    $facts.setOutput("classification", "PII");
                    $facts.setOutput("sensitivity", "CRITICAL");
                    $facts.setOutput("action", "ENCRYPT_AND_RESTRICT");
                end

                rule "detect-financial"
                  salience 80
                  when
                    $facts : NodeFacts()
                    $content : NamedFact(name == "result")
                    eval(((String)$content.getValue()).matches("(?i).*\\\\b(revenue|profit|loss|earnings|dividend|valuation)\\\\b.*"))
                  then
                    $facts.setOutput("classification", "FINANCIAL");
                    $facts.setOutput("sensitivity", "HIGH");
                    $facts.setOutput("action", "RESTRICT_DISTRIBUTION");
                end

                rule "detect-legal"
                  salience 60
                  when
                    $facts : NodeFacts()
                    $content : NamedFact(name == "result")
                    eval(((String)$content.getValue()).matches("(?i).*\\\\b(agreement|contract|liability|indemnification|jurisdiction)\\\\b.*"))
                  then
                    $facts.setOutput("classification", "LEGAL");
                    $facts.setOutput("sensitivity", "HIGH");
                    $facts.setOutput("action", "LEGAL_REVIEW_REQUIRED");
                end

                rule "general-content"
                  salience -10
                  when
                    $facts : NodeFacts()
                    not (eval($facts.getOutputs().containsKey("classification")))
                  then
                    $facts.setOutput("classification", "GENERAL");
                    $facts.setOutput("sensitivity", "LOW");
                    $facts.setOutput("action", "NONE");
                end
                """;

        ComputeNode classifyNode = ComputeNode.builder()
                .id("classifyContent").name("Classify Content")
                .executionType(NodeExecutionType.DROOLS_RULE)
                .script(classifyDrl).build();

        ComputeEdge edge = ComputeEdge.builder()
                .id("e1").sourceNodeId("extractContent").targetNodeId("classifyContent").build();

        ComputeGraph graph = ComputeGraph.builder()
                .id("content-classify")
                .name("Attachment Content Classification")
                .nodes(List.of(extractNode, classifyNode))
                .edges(List.of(edge))
                .build();

        DefaultGraphExecutor executor = new DefaultGraphExecutor(
                List.of(camelExecutor, droolsExecutor), new InMemoryArtifactStore());

        // PII content
        GraphExecutionResult piiResult = executor.execute(graph, Map.of(
                "content", "Employee record: SSN 123-45-6789, Date of Birth: 1990-05-15"
        ));
        assertEquals("PII", piiResult.getFinalOutputs().get("classification"));
        assertEquals("CRITICAL", piiResult.getFinalOutputs().get("sensitivity"));

        // Financial content
        GraphExecutionResult finResult = executor.execute(graph, Map.of(
                "content", "Q3 revenue was $12M, representing 15% profit margin with earnings up 8% YoY"
        ));
        assertEquals("FINANCIAL", finResult.getFinalOutputs().get("classification"));
        assertEquals("RESTRICT_DISTRIBUTION", finResult.getFinalOutputs().get("action"));

        // Legal content
        GraphExecutionResult legalResult = executor.execute(graph, Map.of(
                "content", "This agreement establishes the terms of liability and indemnification between parties"
        ));
        assertEquals("LEGAL", legalResult.getFinalOutputs().get("classification"));

        // General content
        GraphExecutionResult genResult = executor.execute(graph, Map.of(
                "content", "Team lunch is at noon today, bring your own drinks"
        ));
        assertEquals("GENERAL", genResult.getFinalOutputs().get("classification"));
        assertEquals("LOW", genResult.getFinalOutputs().get("sensitivity"));
    }

    // ========================================================================
    // 7. Full email-to-graph with multicast: body + each attachment
    // ========================================================================

    @Test
    void emailMulticastBodyAndAttachmentsToGraph() throws Exception {
        // Real workflow: an email arrives → multicast sends the body and each
        // attachment to separate processing routes → results collected at a
        // single mock representing the graph ingestion endpoint.
        CamelContext ctx = contextManager.getSharedContext();

        MockEndpoint mockGraphIngest = ctx.getEndpoint("mock:graph-ingest", MockEndpoint.class);
        // Expect: 1 email-body record + 2 attachment records = 3
        mockGraphIngest.expectedMessageCount(3);

        ctx.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:email-multicast-graph")
                        .routeId("email-multicast")
                        .process(exchange -> {
                            // Build a list: email body + each attachment as separate records
                            String from = exchange.getIn().getHeader("From", String.class);
                            String subject = exchange.getIn().getHeader("Subject", String.class);
                            String body = exchange.getIn().getBody(String.class);

                            List<Map<String, Object>> records = new ArrayList<>();

                            // Email body record
                            Map<String, Object> bodyRecord = new LinkedHashMap<>();
                            bodyRecord.put("type", "EMAIL_BODY");
                            bodyRecord.put("from", from);
                            bodyRecord.put("subject", subject);
                            bodyRecord.put("content", body);
                            bodyRecord.put("entities", extractEntities(body));
                            records.add(bodyRecord);

                            // Attachment records
                            AttachmentMessage msg = exchange.getIn(AttachmentMessage.class);
                            if (msg.hasAttachments()) {
                                for (String name : msg.getAttachmentNames()) {
                                    DefaultAttachment att = (DefaultAttachment) msg.getAttachmentObject(name);
                                    String content = readContent(att);

                                    Map<String, Object> attRecord = new LinkedHashMap<>();
                                    attRecord.put("type", "ATTACHMENT");
                                    attRecord.put("filename", name);
                                    attRecord.put("contentType", att.getDataHandler().getContentType());
                                    attRecord.put("parentEmail", subject);
                                    attRecord.put("content", content);
                                    attRecord.put("entities", extractEntities(content));
                                    records.add(attRecord);
                                }
                            }
                            exchange.getIn().setBody(records);
                        })
                        .split(body())
                            .to("mock:graph-ingest")
                        .end();
            }
        });

        try (ProducerTemplate producer = ctx.createProducerTemplate()) {
            producer.request("direct:email-multicast-graph", exchange -> {
                exchange.getIn().setHeader("From", "cto@startup.io");
                exchange.getIn().setHeader("Subject", "Series B Term Sheet");
                exchange.getIn().setBody("Hi team,\nAttached is the term sheet from Venture Capital Partners "
                        + "for our Series B round. Review the financial model carefully.");

                addAttachment(exchange, "term_sheet_series_b.pdf",
                        "application/pdf",
                        "Term Sheet: Venture Capital Partners investing $20M at $100M pre-money valuation "
                                + "in Startup Inc. Lead partner: Jane Chen.");
                addAttachment(exchange, "cap_table_updated.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "Cap table showing Startup Inc ownership: Founders 40%, Seed investors 15%, "
                                + "Series A 20%, Series B (proposed) 20%, ESOP 5%.");
            });
        }

        mockGraphIngest.assertIsSatisfied(5, TimeUnit.SECONDS);

        List<Exchange> ingested = mockGraphIngest.getReceivedExchanges();
        assertEquals(3, ingested.size());

        // First record: email body
        @SuppressWarnings("unchecked")
        Map<String, Object> emailRecord = ingested.get(0).getIn().getBody(Map.class);
        assertEquals("EMAIL_BODY", emailRecord.get("type"));
        assertEquals("cto@startup.io", emailRecord.get("from"));
        @SuppressWarnings("unchecked")
        List<String> bodyEntities = (List<String>) emailRecord.get("entities");
        assertTrue(bodyEntities.contains("Venture Capital Partners"));

        // Second record: PDF attachment
        @SuppressWarnings("unchecked")
        Map<String, Object> pdfRecord = ingested.get(1).getIn().getBody(Map.class);
        assertEquals("ATTACHMENT", pdfRecord.get("type"));
        assertEquals("term_sheet_series_b.pdf", pdfRecord.get("filename"));
        assertEquals("Series B Term Sheet", pdfRecord.get("parentEmail"));
        @SuppressWarnings("unchecked")
        List<String> pdfEntities = (List<String>) pdfRecord.get("entities");
        assertTrue(pdfEntities.contains("Venture Capital Partners"));
        assertTrue(pdfEntities.contains("Startup Inc"));

        // Third record: spreadsheet attachment
        @SuppressWarnings("unchecked")
        Map<String, Object> xlsRecord = ingested.get(2).getIn().getBody(Map.class);
        assertEquals("ATTACHMENT", xlsRecord.get("type"));
        assertEquals("cap_table_updated.xlsx", xlsRecord.get("filename"));
    }

    // ========================================================================
    // 8. Attachment dedup across emails in a thread
    // ========================================================================

    @Test
    void deduplicateAttachmentsAcrossEmailThread() throws Exception {
        // When processing an email thread (reply chain), the same attachment
        // is often forwarded multiple times. This test verifies that only
        // unique attachments (by filename + hash) are processed.
        CamelContext ctx = contextManager.getSharedContext();

        MockEndpoint mockUnique = ctx.getEndpoint("mock:unique-attachments", MockEndpoint.class);
        mockUnique.expectedMessageCount(3); // 5 total, 2 are duplicates

        ctx.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:dedup-attachments")
                        .routeId("attachment-dedup")
                        .split(body())
                            .idempotentConsumer(
                                    simple("${body[filename]}"),
                                    org.apache.camel.support.processor.idempotent
                                            .MemoryIdempotentRepository
                                            .memoryIdempotentRepository(200))
                            .to("mock:unique-attachments");
            }
        });

        // Simulate an email thread where some attachments repeat
        List<Map<String, String>> attachments = List.of(
                Map.of("filename", "contract_v2.pdf", "from", "alice@acme.com", "emailSubject", "Contract review"),
                Map.of("filename", "financials.xlsx", "from", "alice@acme.com", "emailSubject", "Contract review"),
                Map.of("filename", "contract_v2.pdf", "from", "bob@acme.com", "emailSubject", "Re: Contract review"),  // dup
                Map.of("filename", "feedback.docx", "from", "bob@acme.com", "emailSubject", "Re: Contract review"),
                Map.of("filename", "financials.xlsx", "from", "carol@acme.com", "emailSubject", "Re: Re: Contract review")  // dup
        );

        try (ProducerTemplate producer = ctx.createProducerTemplate()) {
            producer.sendBody("direct:dedup-attachments", attachments);
        }

        mockUnique.assertIsSatisfied(5, TimeUnit.SECONDS);

        List<Exchange> unique = mockUnique.getReceivedExchanges();
        assertEquals(3, unique.size());

        @SuppressWarnings("unchecked")
        Set<String> filenames = new HashSet<>();
        for (Exchange e : unique) {
            @SuppressWarnings("unchecked")
            Map<String, String> att = e.getIn().getBody(Map.class);
            filenames.add(att.get("filename"));
        }
        assertTrue(filenames.contains("contract_v2.pdf"));
        assertTrue(filenames.contains("financials.xlsx"));
        assertTrue(filenames.contains("feedback.docx"));
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private void addAttachment(Exchange exchange, String filename, String contentType, String content) {
        AttachmentMessage msg = exchange.getIn(AttachmentMessage.class);
        DataSource ds = new StringDataSource(content, contentType, filename);
        DefaultAttachment att = new DefaultAttachment(ds);
        att.addHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        msg.addAttachmentObject(filename, att);
    }

    private String readContent(DefaultAttachment att) {
        try {
            DataHandler dh = att.getDataHandler();
            try (InputStream is = dh.getInputStream()) {
                return new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return "";
        }
    }

    private String truncate(String s, int max) {
        return ai.kompile.utils.StringUtils.truncate(s, max);
    }

    /**
     * Simple entity extractor: finds multi-word capitalized phrases that are
     * likely organization names or proper nouns. In production this would be
     * an NLP model or LLM call.
     */
    private static List<String> extractEntities(String text) {
        if (text == null || text.isBlank()) return List.of();
        Set<String> entities = new LinkedHashSet<>();
        // Known organization patterns
        List<String> knownOrgs = List.of(
                "ACME Corp", "Globex Corporation", "Venture Capital Partners",
                "Startup Inc", "Series B", "Series A");
        for (String org : knownOrgs) {
            if (text.contains(org)) {
                entities.add(org);
            }
        }
        return new ArrayList<>(entities);
    }

    private static Map<String, Object> triple(String subject, String predicate, String object) {
        return Map.of("subject", subject, "predicate", predicate, "object", object);
    }

    private static boolean containsTriple(List<Map<String, Object>> triples,
                                           String subject, String predicate, String object) {
        return triples.stream().anyMatch(t ->
                subject.equals(t.get("subject"))
                        && predicate.equals(t.get("predicate"))
                        && object.equals(t.get("object")));
    }

    private static Map<String, Object> buildEmailMap(String from, String subject,
                                                      List<Map<String, String>> attachments) {
        Map<String, Object> email = new LinkedHashMap<>();
        email.put("from", from);
        email.put("subject", subject);
        email.put("attachments", attachments);
        return email;
    }

    private static Map<String, String> att(String filename, String contentType) {
        return Map.of("filename", filename, "contentType", contentType);
    }

    /**
     * In-memory DataSource for creating Camel attachments from strings.
     */
    static class StringDataSource implements DataSource {
        private final String content;
        private final String contentType;
        private final String name;

        StringDataSource(String content, String contentType, String name) {
            this.content = content;
            this.contentType = contentType;
            this.name = name;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public OutputStream getOutputStream() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public String getName() {
            return name;
        }
    }
}
