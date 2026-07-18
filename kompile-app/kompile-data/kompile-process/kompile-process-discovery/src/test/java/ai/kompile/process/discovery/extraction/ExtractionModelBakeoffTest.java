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

import ai.kompile.core.evaluation.GraphEvaluationContext;
import ai.kompile.core.evaluation.GraphEvaluationResult;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult;
import ai.kompile.core.graphrag.format.GraphExtractionValidator;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.evaluation.EntityPresenceEvaluator;
import ai.kompile.evaluation.EntityTypeAccuracyEvaluator;
import ai.kompile.evaluation.EvaluationProperties;
import ai.kompile.evaluation.RelationshipPresenceEvaluator;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.knowledgegraph.unified.ExtractionToUnifiedGraph;
import ai.kompile.process.discovery.mining.ReasoningGraphProcessGenerator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Model bake-off over the procurement corpus, REPLAYED from recorded extraction outputs — the
 * Java-level crawl-step harness with zero crawl tooling: no Spring, no matrix store, no LLM.
 *
 * <p>Pipeline per model: recorded JSON → production parse/validate
 * ({@link GraphExtractionValidator}) → core {@link Graph} → scored against the corpus goldens
 * with the kompile-evaluation evaluators → merged into a {@link UnifiedGraph}
 * ({@link ExtractionToUnifiedGraph}) → business-process surfacing
 * ({@link ReasoningGraphProcessGenerator}). The "precise" and "sloppy" recordings simulate a
 * strong and a weak extraction model over the same documents.</p>
 *
 * <p>To bake off a LIVE model instead of a recording: run
 * {@code GraphExtractionPreviewService.previewDetailed} (crawl-graph) against
 * {@link CrawlCorpusFixtures#corpus()} with a {@code ProcessingRouteConfig} pointing at the
 * model (CLI agent name or OpenAI-compatible endpoint), save the raw per-document results to
 * {@code model-outputs/<model>/}, and re-run this harness.</p>
 */
class ExtractionModelBakeoffTest {

    private static final Set<String> PROCESS_ACTIVITY_MARKERS =
            Set.of("requested by", "approved by", "paid by");

    /** Mean per-document extraction scores for one model. */
    private record ModelScore(String model,
                              double entityF1, double entityRecall,
                              double relationF1, double relationRecall,
                              double typeAccuracy) {
    }

    // ── 1. recorded outputs must survive the production validation gate ─────────

    @Test
    void recordedModelOutputsSurviveProductionValidation() {
        for (String model : List.of(CrawlCorpusFixtures.MODEL_PRECISE, CrawlCorpusFixtures.MODEL_SLOPPY)) {
            for (ExtractionResult result : CrawlCorpusFixtures.modelOutputs(model)) {
                var validation = GraphExtractionValidator.validate(result);
                assertTrue(validation.valid(), "model '" + model + "' recording invalid: "
                        + validation.errors());
            }
        }
    }

    // ── 2. extraction quality: precise strictly beats sloppy on every dimension ──

    @Test
    void preciseModelOutscoresSloppyOnEveryExtractionDimension() {
        ModelScore precise = score(CrawlCorpusFixtures.MODEL_PRECISE);
        ModelScore sloppy = score(CrawlCorpusFixtures.MODEL_SLOPPY);
        for (ModelScore score : List.of(precise, sloppy)) {
            System.out.printf(Locale.ROOT,
                    "bakeoff %-8s entityF1=%.3f entityRecall=%.3f relationF1=%.3f relationRecall=%.3f typeAccuracy=%.3f%n",
                    score.model(), score.entityF1(), score.entityRecall(),
                    score.relationF1(), score.relationRecall(), score.typeAccuracy());
        }

        assertTrue(precise.entityF1() > 0.99, "precise recording matches the golden entities");
        assertTrue(precise.relationF1() > 0.99, "precise recording matches the golden relations");

        assertTrue(sloppy.entityRecall() < 0.75,
                "sloppy misses the approver entity across the corpus");
        assertTrue(sloppy.relationRecall() < 0.6,
                "sloppy misses approval (and one payment) relation");

        assertTrue(precise.entityF1() > sloppy.entityF1());
        assertTrue(precise.relationF1() > sloppy.relationF1());
        assertTrue(precise.typeAccuracy() > sloppy.typeAccuracy(),
                "sloppy mistypes people as organizations");
    }

    // ── 3. merged graph shape exposes entity-identity discipline ────────────────

    @Test
    void mergedGraphsExposeEntityIdentityDiscipline() {
        UnifiedGraph precise = ExtractionToUnifiedGraph.toGraph(
                CrawlCorpusFixtures.modelOutputs(CrawlCorpusFixtures.MODEL_PRECISE));
        UnifiedGraph sloppy = ExtractionToUnifiedGraph.toGraph(
                CrawlCorpusFixtures.modelOutputs(CrawlCorpusFixtures.MODEL_SLOPPY));

        assertEquals(8, precise.entityCount(),
                "stable ids merge Erin Wu and Initech Finance across the three documents");
        assertEquals(9, precise.relationCount());
        Set<String> preciseLabels = labels(precise);
        assertEquals(precise.entityCount(), preciseLabels.size(), "no duplicate entities");
        assertTrue(preciseLabels.contains("Erin Wu"));

        assertEquals(9, sloppy.entityCount());
        assertEquals(5, sloppy.relationCount());
        Set<String> sloppyLabels = labels(sloppy);
        assertTrue(sloppyLabels.size() < sloppy.entityCount(),
                "document-scoped ids leave duplicate Initech Finance nodes unmerged");
        assertFalse(sloppyLabels.contains("Erin Wu"), "the approver was never extracted");
    }

    // ── 4. the precise graph surfaces the full procurement process ──────────────

    @Test
    void preciseGraphSurfacesTheFullProcurementProcess() {
        UnifiedGraph graph = ExtractionToUnifiedGraph.toGraph(
                CrawlCorpusFixtures.modelOutputs(CrawlCorpusFixtures.MODEL_PRECISE));

        ReasoningGraphProcessGenerator.Candidate procurement =
                procurementCandidate(ReasoningGraphProcessGenerator.generate(graph));

        assertEquals(3, procurement.activityCount(),
                "request, approval and payment all surface as activities");
        assertEquals(3, procurement.traceCount(), "one trace per purchase-order case");
        assertTrue(procurement.score() > 0.0);
        assertFalse(procurement.entailment().accepted().isEmpty()
                        && procurement.entailment().entailedOnly().isEmpty(),
                "the case-consistent ordering produces precedence entailment");
    }

    // ── 5. the sloppy graph cannot surface the approval step ────────────────────

    @Test
    void sloppyGraphCannotSurfaceTheApprovalStep() {
        UnifiedGraph graph = ExtractionToUnifiedGraph.toGraph(
                CrawlCorpusFixtures.modelOutputs(CrawlCorpusFixtures.MODEL_SLOPPY));

        ReasoningGraphProcessGenerator.Result result = ReasoningGraphProcessGenerator.generate(graph);
        assertTrue(result.candidates().stream()
                        .flatMap(candidate -> candidate.eventLog().activityNames().stream())
                        .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains("approved")),
                "an unextracted APPROVED_BY relation is unminable — no process can contain it");
    }

    // ── 6. the golden .kgraph fixture round-trips and re-derives the process ────

    @Test
    void goldenKgraphFixtureRoundTripsAndRederivesTheProcess(@TempDir Path directory) throws IOException {
        Path fixture = CrawlCorpusFixtures.writeGoldenKgraph(directory);
        UnifiedGraph reloaded = UnifiedGraph.load(fixture);

        assertEquals(8, reloaded.entityCount());
        assertEquals(9, reloaded.relationCount());

        Set<String> inMemory = procurementCandidate(
                ReasoningGraphProcessGenerator.generate(CrawlCorpusFixtures.goldenUnifiedGraph()))
                .eventLog().activityNames();
        Set<String> fromFixture = procurementCandidate(
                ReasoningGraphProcessGenerator.generate(reloaded))
                .eventLog().activityNames();
        assertEquals(inMemory, fromFixture,
                "the saved fixture surfaces the identical process activities");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** The candidate whose activities cover request, approval and payment. */
    private static ReasoningGraphProcessGenerator.Candidate procurementCandidate(
            ReasoningGraphProcessGenerator.Result result) {
        return result.candidates().stream()
                .filter(candidate -> {
                    String joined = candidate.eventLog().activityNames().stream()
                            .map(name -> name.toLowerCase(Locale.ROOT))
                            .collect(Collectors.joining(" | "));
                    return PROCESS_ACTIVITY_MARKERS.stream().allMatch(joined::contains);
                })
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no candidate covers request/approval/payment; candidates: "
                                + result.candidates().stream()
                                .map(candidate -> candidate.eventLog().activityNames().toString())
                                .collect(Collectors.joining(", "))));
    }

    private static Set<String> labels(UnifiedGraph graph) {
        return graph.entities().stream().map(GraphEntity::label).collect(Collectors.toSet());
    }

    private static ModelScore score(String model) {
        EvaluationProperties properties = new EvaluationProperties(null);
        properties.getEntityPresence().setThreshold(0.5);
        properties.getRelationshipPresence().setThreshold(0.5);
        properties.getEntityTypeAccuracy().setThreshold(0.5);
        EntityPresenceEvaluator entityEvaluator = new EntityPresenceEvaluator(properties);
        RelationshipPresenceEvaluator relationEvaluator = new RelationshipPresenceEvaluator(properties);
        EntityTypeAccuracyEvaluator typeEvaluator = new EntityTypeAccuracyEvaluator(properties);

        List<ExtractionResult> outputs = CrawlCorpusFixtures.modelOutputs(model);
        double entityF1 = 0, entityRecall = 0, relationF1 = 0, relationRecall = 0, typeAccuracy = 0;
        for (int i = 0; i < CrawlCorpusFixtures.DOCUMENT_IDS.size(); i++) {
            String documentId = CrawlCorpusFixtures.DOCUMENT_IDS.get(i);
            Graph extracted = GraphExtractionValidator.toGraph(outputs.get(i));
            GraphEvaluationContext context = GraphEvaluationContext.builder()
                    .groundTruth(CrawlCorpusFixtures.goldenGraph(documentId))
                    .build();

            GraphEvaluationResult entities = entityEvaluator.evaluate(extracted, context);
            GraphEvaluationResult relations = relationEvaluator.evaluate(extracted, context);
            GraphEvaluationResult types = typeEvaluator.evaluate(extracted, context);
            entityF1 += entities.getF1();
            entityRecall += entities.getRecall();
            relationF1 += relations.getF1();
            relationRecall += relations.getRecall();
            typeAccuracy += types.getScore();
        }
        int documents = CrawlCorpusFixtures.DOCUMENT_IDS.size();
        return new ModelScore(model,
                entityF1 / documents, entityRecall / documents,
                relationF1 / documents, relationRecall / documents,
                typeAccuracy / documents);
    }
}
