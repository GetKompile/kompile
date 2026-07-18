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
package ai.kompile.process.discovery.extraction;

import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedEntity;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedRelation;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionMetadata;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult;
import ai.kompile.core.graphrag.format.GraphExtractionValidator;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.knowledgegraph.unified.ExtractionToUnifiedGraph;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The checked-in crawl test corpus: a small procurement domain (three purchase-order summaries,
 * one process case each) with golden extractions, recorded per-model extraction outputs, and a
 * golden reasoning graph.
 *
 * <p>The corpus backs step-level crawl tests without the crawl stack: extraction quality scoring
 * (goldens + {@code kompile-evaluation} evaluators), extraction→graph projection
 * ({@link ExtractionToUnifiedGraph}), and business-process surfacing (the golden graph carries
 * three case-correlated REQUESTED_BY → APPROVED_BY → PAID_BY traces).</p>
 *
 * <p>Recorded model outputs live under {@code crawl-corpus/procurement/model-outputs/<model>/} —
 * one JSON per corpus document, in the canonical extraction schema. {@code precise} replays a
 * well-behaved extractor (stable entity ids across documents, correct types, case/timestamp
 * properties); {@code sloppy} replays a weak one (missed approver, mistyped people, a
 * hallucinated entity, document-scoped ids, no confidences or case properties). To record a NEW
 * model, run it over {@link #corpus()} through the production parse path
 * ({@code GraphExtractionValidator.fromJson}) and save one JSON per document to a sibling
 * directory.</p>
 */
public final class CrawlCorpusFixtures {

    /** The corpus document ids; each is one process case. */
    public static final List<String> DOCUMENT_IDS = List.of("po-7001", "po-7002", "po-7003");

    /** Recorded model directories available under {@code model-outputs/}. */
    public static final String MODEL_PRECISE = "precise";
    public static final String MODEL_SLOPPY = "sloppy";

    private static final String CORPUS_ROOT = "/crawl-corpus/procurement/";

    private CrawlCorpusFixtures() {
    }

    /** One corpus document: raw text plus its stable id. */
    public record CorpusDocument(String documentId, String text) {
    }

    /** The corpus documents, loaded from checked-in resources. */
    public static List<CorpusDocument> corpus() {
        List<CorpusDocument> documents = new ArrayList<>();
        for (String documentId : DOCUMENT_IDS) {
            documents.add(new CorpusDocument(documentId,
                    resourceText(CORPUS_ROOT + documentId + ".txt")));
        }
        return documents;
    }

    /**
     * The recorded extraction output of {@code model} for every corpus document, parsed through
     * the production {@link GraphExtractionValidator#fromJson} path.
     */
    public static List<ExtractionResult> modelOutputs(String model) {
        List<ExtractionResult> results = new ArrayList<>();
        for (String documentId : DOCUMENT_IDS) {
            String json = resourceText(CORPUS_ROOT + "model-outputs/" + model + "/" + documentId + ".json");
            try {
                results.add(GraphExtractionValidator.fromJson(json));
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "Recorded output for model '" + model + "' document '" + documentId
                                + "' is not valid extraction JSON", e);
            }
        }
        return results;
    }

    /** The golden (ground-truth) extraction for one corpus document. */
    public static ExtractionResult goldenExtraction(String documentId) {
        return switch (documentId) {
            case "po-7001" -> procurementCase("po-7001", "Engineering laptop refresh purchase order",
                    "dana-reyes", "Dana Reyes",
                    "2026-03-02T09:00:00Z", "2026-03-04T09:00:00Z", "2026-03-09T09:00:00Z");
            case "po-7002" -> procurementCase("po-7002", "Office chair replacement purchase order",
                    "gopal-rao", "Gopal Rao",
                    "2026-04-01T09:00:00Z", "2026-04-03T09:00:00Z", "2026-04-08T09:00:00Z");
            case "po-7003" -> procurementCase("po-7003", "GPU server expansion purchase order",
                    "hana-kim", "Hana Kim",
                    "2026-05-05T09:00:00Z", "2026-05-06T09:00:00Z", "2026-05-11T09:00:00Z");
            default -> throw new IllegalArgumentException("Unknown corpus document: " + documentId);
        };
    }

    /** The golden extraction for one document as a core {@link Graph} — evaluator ground truth. */
    public static Graph goldenGraph(String documentId) {
        return GraphExtractionValidator.toGraph(goldenExtraction(documentId));
    }

    /** All golden extractions merged into one reasoning-ready {@link UnifiedGraph}. */
    public static UnifiedGraph goldenUnifiedGraph() {
        List<ExtractionResult> goldens = new ArrayList<>();
        for (String documentId : DOCUMENT_IDS) {
            goldens.add(goldenExtraction(documentId));
        }
        return ExtractionToUnifiedGraph.toGraph(goldens);
    }

    /**
     * Write the golden graph as a portable {@code .kgraph} fixture (for import/API tests) and
     * return its path.
     */
    public static Path writeGoldenKgraph(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path file = directory.resolve("procurement-golden.kgraph");
        goldenUnifiedGraph().save(file);
        return file;
    }

    private static ExtractionResult procurementCase(String poId, String poDescription,
                                                    String requesterId, String requesterName,
                                                    String requestedAt, String approvedAt,
                                                    String paidAt) {
        String caseId = poId;
        return ExtractionResult.of(
                List.of(new ExtractedEntity(poId, poId.toUpperCase(java.util.Locale.ROOT),
                                "PURCHASE_ORDER", null, poDescription, 0.95, null),
                        new ExtractedEntity(requesterId, requesterName, "PERSON",
                                null, null, 0.9, null),
                        new ExtractedEntity("erin-wu", "Erin Wu", "PERSON",
                                null, "Procurement lead", 0.9, null),
                        new ExtractedEntity("initech-finance", "Initech Finance", "ORGANIZATION",
                                null, null, 0.9, null)),
                List.of(new ExtractedRelation(poId, requesterId, "REQUESTED_BY", null, 0.9,
                                Map.of("caseId", caseId), requestedAt),
                        new ExtractedRelation(poId, "erin-wu", "APPROVED_BY", null, 0.9,
                                Map.of("caseId", caseId), approvedAt),
                        new ExtractedRelation(poId, "initech-finance", "PAID_BY", null, 0.85,
                                Map.of("caseId", caseId), paidAt)),
                ExtractionMetadata.forChunk(poId + "-chunk-0", poId, "golden"));
    }

    private static String resourceText(String resourcePath) {
        try (InputStream in = CrawlCorpusFixtures.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Missing corpus resource: " + resourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading corpus resource: " + resourcePath, e);
        }
    }
}
