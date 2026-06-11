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

package ai.kompile.app.services.pipeline;

import ai.kompile.app.services.pipeline.stages.GraphBuildingStage;
import ai.kompile.app.services.pipeline.stages.IndexingStage;
import ai.kompile.core.graphrag.GraphConstructor;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.SchemaEnforcementMode;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * End-to-end tests for PDF document content flowing through the graph building pipeline.
 * Simulates the full path: PDF text chunks -> GraphBuildingStage -> GraphConstructor -> Graph output.
 *
 * Uses mock GraphConstructor to simulate LLM-based entity extraction from PDF text content,
 * verifying that the pipeline correctly batches chunks, accumulates results, and reports metrics.
 */
@ExtendWith(MockitoExtension.class)
class PdfGraphExtractionEndToEndTest {

    @Mock
    private GraphConstructor graphConstructor;

    private GraphBuildingStage stage;

    @BeforeEach
    void setUp() {
        stage = new GraphBuildingStage(graphConstructor);
    }

    /**
     * Simulates extracting entities from a multi-page technical PDF about machine learning.
     * Verifies that entities from different pages/chunks are accumulated correctly.
     */
    @Test
    void multiPagePdfGraphExtraction() throws Exception {
        // Simulate chunks from a multi-page ML textbook PDF
        List<RetrievedDoc> pdfChunks = List.of(
                createChunk("chunk-1", "ml-textbook.pdf",
                        "Machine learning is a subset of artificial intelligence. " +
                        "Deep learning uses neural networks with multiple layers. " +
                        "Geoffrey Hinton at the University of Toronto pioneered backpropagation."),
                createChunk("chunk-2", "ml-textbook.pdf",
                        "Convolutional Neural Networks (CNNs) were developed by Yann LeCun " +
                        "at Bell Labs. They are widely used for image classification tasks. " +
                        "ResNet, developed by Microsoft Research, won ImageNet 2015."),
                createChunk("chunk-3", "ml-textbook.pdf",
                        "Transformers, introduced in the paper 'Attention Is All You Need' " +
                        "by Google Brain, revolutionized natural language processing. " +
                        "BERT and GPT are based on the transformer architecture.")
        );

        // Mock GraphConstructor responses for each batch
        when(graphConstructor.constructGraphFromDocs(anyList(), any(), any()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    List<RetrievedDoc> docs = invocation.getArgument(0);
                    return createGraphForPdfChunks(docs);
                });

        stage.setChunksToProcess(pdfChunks);
        IndexingStage.IndexingOutput indexingOutput = new IndexingStage.IndexingOutput(
                List.of("doc-1"), 3, 1, 50L, "bge-base", "recursive",
                "pdf-extended", "task-pdf-1", new HashMap<>());

        GraphBuildingStage.GraphBuildingOutput output = stage.process(indexingOutput);

        // Verify entities were extracted
        assertTrue(output.entitiesExtracted() > 0, "Should extract entities from PDF chunks");
        assertTrue(output.relationshipsExtracted() > 0, "Should extract relationships from PDF chunks");
        assertEquals(1, output.batchCount(), "3 chunks with default batch size 10 = 1 batch");
        assertTrue(output.graphBuildingTimeMs() >= 0);

        // Verify metadata
        assertNotNull(output.metadata());
        @SuppressWarnings("unchecked")
        Map<String, Long> entityTypes = (Map<String, Long>) output.metadata().get("entityTypes");
        assertNotNull(entityTypes);
        assertTrue(entityTypes.containsKey("PERSON") || entityTypes.containsKey("ORGANIZATION")
                        || entityTypes.containsKey("CONCEPT"),
                "Should extract standard entity types from PDF text");

        // Verify the GraphConstructor was called with the chunks
        verify(graphConstructor, times(1)).constructGraphFromDocs(anyList(), any(), any());
    }

    /**
     * Tests PDF content with structured sections (like a research paper) produces
     * proper batching when chunk count exceeds batch size.
     */
    @Test
    void largePdfBatchingAcrossChunks() throws Exception {
        // Simulate 15 chunks from a research paper PDF
        List<RetrievedDoc> chunks = new ArrayList<>();
        String[] sections = {
                "Abstract: This paper presents a novel approach to graph neural networks.",
                "Introduction: Graph neural networks have gained significant attention.",
                "Related Work: Previous approaches include GCN by Kipf and Welling.",
                "The Graph Attention Network (GAT) was proposed by Velickovic et al.",
                "Method: We propose Multi-Scale Graph Transformer (MSGT).",
                "Our architecture consists of three main components.",
                "The attention mechanism operates at multiple scales.",
                "Experiments: We evaluate on Cora, Citeseer, and PubMed datasets.",
                "Table 1 shows comparison with baseline methods.",
                "Our method achieves 85.3% accuracy on Cora dataset.",
                "Discussion: The multi-scale attention captures local and global patterns.",
                "Ablation study shows each component contributes to performance.",
                "Future work includes extending to heterogeneous graphs.",
                "Conclusion: MSGT achieves state-of-the-art results.",
                "References: Kipf and Welling, 2017. Velickovic et al., 2018."
        };
        for (int i = 0; i < sections.length; i++) {
            chunks.add(createChunk("chunk-" + i, "research-paper.pdf", sections[i]));
        }

        // Set batch size to 5
        stage.configure(Map.of("batchSize", 5));

        Graph batchGraph = new Graph();
        Entity concept = new Entity();
        concept.setId("c1");
        concept.setTitle("Graph Neural Network");
        concept.setType("CONCEPT");
        batchGraph.setEntities(List.of(concept));
        batchGraph.setRelationships(List.of());

        when(graphConstructor.constructGraphFromDocs(anyList(), any(), any()))
                .thenReturn(batchGraph);

        stage.setChunksToProcess(chunks);
        IndexingStage.IndexingOutput indexingOutput = new IndexingStage.IndexingOutput(
                List.of("doc-1"), 15, 1, 100L, "bge-base", "recursive",
                "pdf-extended", "task-pdf-2", new HashMap<>());

        GraphBuildingStage.GraphBuildingOutput output = stage.process(indexingOutput);

        // 15 chunks / 5 per batch = 3 batches
        assertEquals(3, output.batchCount());
        // 3 batches * 1 entity each = 3 entities
        assertEquals(3, output.entitiesExtracted());

        // Verify batch sizes
        ArgumentCaptor<List<RetrievedDoc>> batchCaptor = ArgumentCaptor.forClass(List.class);
        verify(graphConstructor, times(3)).constructGraphFromDocs(batchCaptor.capture(), any(), any());

        List<List<RetrievedDoc>> batches = batchCaptor.getAllValues();
        assertEquals(5, batches.get(0).size(), "First batch should have 5 chunks");
        assertEquals(5, batches.get(1).size(), "Second batch should have 5 chunks");
        assertEquals(5, batches.get(2).size(), "Third batch should have 5 chunks");
    }

    /**
     * Tests that PDF text with tables (as markdown) produces proper entities.
     */
    @Test
    void pdfWithTabularContent() throws Exception {
        // Simulate a PDF that was loaded with table content converted to markdown
        List<RetrievedDoc> chunks = List.of(
                createChunk("chunk-tab-1", "financial-report.pdf",
                        "Annual Financial Report 2024\n\n" +
                        "| Quarter | Revenue | Expenses | Profit |\n" +
                        "|---------|---------|----------|--------|\n" +
                        "| Q1      | $1.2M   | $0.8M    | $0.4M  |\n" +
                        "| Q2      | $1.5M   | $0.9M    | $0.6M  |\n" +
                        "| Q3      | $1.8M   | $1.0M    | $0.8M  |\n" +
                        "| Q4      | $2.1M   | $1.1M    | $1.0M  |\n"),
                createChunk("chunk-tab-2", "financial-report.pdf",
                        "The company Acme Corporation reported total annual revenue of $6.6M. " +
                        "CEO John Smith attributed the growth to expansion into European markets. " +
                        "CFO Jane Doe projected 20% growth for 2025.")
        );

        Graph financialGraph = new Graph();

        Entity company = new Entity();
        company.setId("e1");
        company.setTitle("Acme Corporation");
        company.setType("ORGANIZATION");
        company.setDescription("Company reporting annual financials");

        Entity ceo = new Entity();
        ceo.setId("e2");
        ceo.setTitle("John Smith");
        ceo.setType("PERSON");
        ceo.setDescription("CEO of Acme Corporation");

        Entity cfo = new Entity();
        cfo.setId("e3");
        cfo.setTitle("Jane Doe");
        cfo.setType("PERSON");
        cfo.setDescription("CFO of Acme Corporation");

        Entity revenue = new Entity();
        revenue.setId("e4");
        revenue.setTitle("$6.6M Annual Revenue");
        revenue.setType("FINANCIAL_METRIC");
        revenue.setDescription("Total annual revenue for 2024");

        financialGraph.setEntities(List.of(company, ceo, cfo, revenue));

        Relationship ceoRel = new Relationship();
        ceoRel.setSource("e2");
        ceoRel.setTarget("e1");
        ceoRel.setType("CEO_OF");

        Relationship cfoRel = new Relationship();
        cfoRel.setSource("e3");
        cfoRel.setTarget("e1");
        cfoRel.setType("CFO_OF");

        Relationship revRel = new Relationship();
        revRel.setSource("e1");
        revRel.setTarget("e4");
        revRel.setType("REPORTED");

        financialGraph.setRelationships(List.of(ceoRel, cfoRel, revRel));

        when(graphConstructor.constructGraphFromDocs(anyList(), any(), any()))
                .thenReturn(financialGraph);

        stage.setChunksToProcess(chunks);
        IndexingStage.IndexingOutput indexingOutput = new IndexingStage.IndexingOutput(
                List.of("doc-fin"), 2, 1, 30L, "bge-base", "recursive",
                "pdf-extended", "task-pdf-fin", new HashMap<>());

        GraphBuildingStage.GraphBuildingOutput output = stage.process(indexingOutput);

        assertEquals(4, output.entitiesExtracted());
        assertEquals(3, output.relationshipsExtracted());
        assertEquals(7, output.totalGraphElements());

        @SuppressWarnings("unchecked")
        Map<String, Long> entityTypes = (Map<String, Long>) output.metadata().get("entityTypes");
        assertEquals(1L, entityTypes.get("ORGANIZATION"));
        assertEquals(2L, entityTypes.get("PERSON"));
        assertEquals(1L, entityTypes.get("FINANCIAL_METRIC"));
    }

    /**
     * Tests error resilience: if one batch of PDF chunks fails LLM extraction,
     * remaining batches still produce results.
     */
    @Test
    void partialFailureInPdfExtraction() throws Exception {
        List<RetrievedDoc> chunks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            chunks.add(createChunk("chunk-" + i, "manual.pdf",
                    "Section " + (i + 1) + " of the user manual."));
        }

        stage.configure(Map.of("batchSize", 3));

        Graph successGraph = new Graph();
        Entity entity = new Entity();
        entity.setId("e1");
        entity.setTitle("User Manual");
        entity.setType("DOCUMENT");
        successGraph.setEntities(List.of(entity));
        successGraph.setRelationships(List.of());

        // First batch fails, second succeeds
        when(graphConstructor.constructGraphFromDocs(anyList(), any(), any()))
                .thenThrow(new RuntimeException("LLM rate limit exceeded"))
                .thenReturn(successGraph);

        stage.setChunksToProcess(chunks);
        IndexingStage.IndexingOutput indexingOutput = new IndexingStage.IndexingOutput(
                List.of("doc-m"), 6, 1, 40L, "bge-base", "recursive",
                "pdf-extended", "task-pdf-m", new HashMap<>());

        GraphBuildingStage.GraphBuildingOutput output = stage.process(indexingOutput);

        // Second batch should still succeed
        assertEquals(1, output.entitiesExtracted());
        assertEquals(2, output.batchCount());
    }

    /**
     * Tests PDF content with mixed entity types produces a well-formed graph.
     */
    @Test
    void mixedEntityTypesFromPdf() throws Exception {
        List<RetrievedDoc> chunks = List.of(
                createChunk("chunk-1", "geography.pdf",
                        "The Amazon River flows through Brazil, Peru, and Colombia. " +
                        "The Amazon Rainforest covers approximately 5.5 million square kilometers. " +
                        "In 2023, deforestation rates decreased by 22% according to INPE."),
                createChunk("chunk-2", "geography.pdf",
                        "The Nile River, flowing through Egypt and Sudan, is the longest river " +
                        "in the world at 6,650 kilometers. The Aswan High Dam was completed in 1970.")
        );

        Graph geoGraph = new Graph();

        Entity amazon = new Entity();
        amazon.setId("e1");
        amazon.setTitle("Amazon River");
        amazon.setType("LOCATION");

        Entity brazil = new Entity();
        brazil.setId("e2");
        brazil.setTitle("Brazil");
        brazil.setType("LOCATION");

        Entity nile = new Entity();
        nile.setId("e3");
        nile.setTitle("Nile River");
        nile.setType("LOCATION");

        Entity dam = new Entity();
        dam.setId("e4");
        dam.setTitle("Aswan High Dam");
        dam.setType("INFRASTRUCTURE");

        Entity deforestationEvent = new Entity();
        deforestationEvent.setId("e5");
        deforestationEvent.setTitle("Deforestation Rate Decrease 2023");
        deforestationEvent.setType("EVENT");

        Entity inpe = new Entity();
        inpe.setId("e6");
        inpe.setTitle("INPE");
        inpe.setType("ORGANIZATION");

        geoGraph.setEntities(List.of(amazon, brazil, nile, dam, deforestationEvent, inpe));

        Relationship flowsThrough = new Relationship();
        flowsThrough.setSource("e1");
        flowsThrough.setTarget("e2");
        flowsThrough.setType("FLOWS_THROUGH");

        Relationship locatedOn = new Relationship();
        locatedOn.setSource("e4");
        locatedOn.setTarget("e3");
        locatedOn.setType("LOCATED_ON");

        Relationship reported = new Relationship();
        reported.setSource("e6");
        reported.setTarget("e5");
        reported.setType("REPORTED");

        geoGraph.setRelationships(List.of(flowsThrough, locatedOn, reported));

        when(graphConstructor.constructGraphFromDocs(anyList(), any(), any()))
                .thenReturn(geoGraph);

        stage.setChunksToProcess(chunks);
        IndexingStage.IndexingOutput indexingOutput = new IndexingStage.IndexingOutput(
                List.of("doc-geo"), 2, 1, 25L, "bge-base", "recursive",
                "pdf-extended", "task-pdf-geo", new HashMap<>());

        GraphBuildingStage.GraphBuildingOutput output = stage.process(indexingOutput);

        assertEquals(6, output.entitiesExtracted());
        assertEquals(3, output.relationshipsExtracted());
        assertEquals(9, output.totalGraphElements());

        @SuppressWarnings("unchecked")
        Map<String, Long> entityTypes = (Map<String, Long>) output.metadata().get("entityTypes");
        assertEquals(3L, entityTypes.get("LOCATION"));
        assertEquals(1L, entityTypes.get("INFRASTRUCTURE"));
        assertEquals(1L, entityTypes.get("EVENT"));
        assertEquals(1L, entityTypes.get("ORGANIZATION"));
    }

    /**
     * Tests that source document metadata from PDF is preserved in pipeline output.
     */
    @Test
    void pdfSourceMetadataPreservedInOutput() throws Exception {
        RetrievedDoc chunk = createChunk("chunk-1", "report.pdf", "Test content");
        chunk.getMetadata().put("pageNumber", 5);
        chunk.getMetadata().put("totalPages", 20);
        chunk.getMetadata().put("loader", "PDF Extended Loader");

        Graph emptyGraph = new Graph();
        emptyGraph.setEntities(List.of());
        emptyGraph.setRelationships(List.of());

        when(graphConstructor.constructGraphFromDocs(anyList(), any(), any()))
                .thenReturn(emptyGraph);

        stage.setChunksToProcess(List.of(chunk));
        IndexingStage.IndexingOutput indexingOutput = new IndexingStage.IndexingOutput(
                List.of("doc-r"), 1, 1, 10L, "bge-base", "recursive",
                "pdf-extended", "task-pdf-r", new HashMap<>());

        GraphBuildingStage.GraphBuildingOutput output = stage.process(indexingOutput);

        // Pipeline metadata should be preserved
        assertEquals("pdf-extended", output.loaderUsed());
        assertEquals("task-pdf-r", output.taskId());
        assertEquals("bge-base", output.embeddingModelUsed());
    }

    // --- Helper methods ---

    private RetrievedDoc createChunk(String id, String source, String text) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", source);
        metadata.put("source_filename", source);
        metadata.put("loader", "PDF Extended Loader");
        return new RetrievedDoc(id, text, metadata);
    }

    /**
     * Creates a realistic Graph response simulating LLM extraction from PDF chunks.
     */
    private Graph createGraphForPdfChunks(List<RetrievedDoc> chunks) {
        Graph graph = new Graph();
        List<Entity> entities = new ArrayList<>();
        List<Relationship> relationships = new ArrayList<>();

        int entityIdx = 0;
        for (RetrievedDoc chunk : chunks) {
            String text = chunk.getText().toLowerCase();

            if (text.contains("geoffrey hinton")) {
                Entity person = new Entity();
                person.setId("e" + (++entityIdx));
                person.setTitle("Geoffrey Hinton");
                person.setType("PERSON");
                person.setDescription("Pioneer of backpropagation");
                entities.add(person);

                Entity org = new Entity();
                org.setId("e" + (++entityIdx));
                org.setTitle("University of Toronto");
                org.setType("ORGANIZATION");
                entities.add(org);

                Relationship rel = new Relationship();
                rel.setSource(person.getId());
                rel.setTarget(org.getId());
                rel.setType("AFFILIATED_WITH");
                relationships.add(rel);
            }

            if (text.contains("yann lecun")) {
                Entity person = new Entity();
                person.setId("e" + (++entityIdx));
                person.setTitle("Yann LeCun");
                person.setType("PERSON");
                entities.add(person);

                Entity concept = new Entity();
                concept.setId("e" + (++entityIdx));
                concept.setTitle("CNN");
                concept.setType("CONCEPT");
                entities.add(concept);

                Relationship rel = new Relationship();
                rel.setSource(person.getId());
                rel.setTarget(concept.getId());
                rel.setType("DEVELOPED");
                relationships.add(rel);
            }

            if (text.contains("transformer")) {
                Entity concept = new Entity();
                concept.setId("e" + (++entityIdx));
                concept.setTitle("Transformer");
                concept.setType("CONCEPT");
                entities.add(concept);

                Entity org = new Entity();
                org.setId("e" + (++entityIdx));
                org.setTitle("Google Brain");
                org.setType("ORGANIZATION");
                entities.add(org);

                Relationship rel = new Relationship();
                rel.setSource(org.getId());
                rel.setTarget(concept.getId());
                rel.setType("INTRODUCED");
                relationships.add(rel);
            }
        }

        graph.setEntities(entities);
        graph.setRelationships(relationships);
        return graph;
    }
}
