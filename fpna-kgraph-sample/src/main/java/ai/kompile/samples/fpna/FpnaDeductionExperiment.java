package ai.kompile.samples.fpna;

import ai.kompile.graph.reasoning.fol.EntailmentRecord;
import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.fol.FactStore;
import ai.kompile.graph.reasoning.fol.InMemoryInferredFactStore;
import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.graph.reasoning.fol.grounding.AnnotatedResult;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine.DatalogRule;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine.Derivation;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine.FixpointResult;
import ai.kompile.graph.reasoning.fol.grounding.RecursiveQueryEngine.RuleAtom;
import ai.kompile.graph.reasoning.fol.grounding.WhyNotExplainer;
import ai.kompile.graph.reasoning.fol.materialization.FolDatalogAdapter;
import ai.kompile.graph.reasoning.fol.materialization.ForwardChainingMaterializer;
import ai.kompile.graph.reasoning.fol.materialization.ForwardChainingMaterializer.MaterializationResult;
import ai.kompile.graph.reasoning.fol.semiring.CountingSemiring;
import ai.kompile.graph.reasoning.fol.semiring.Proof;
import ai.kompile.graph.reasoning.fol.semiring.ProofFragility;
import ai.kompile.graph.reasoning.fol.semiring.ProofSet;
import ai.kompile.graph.reasoning.fol.semiring.TopKProofsSemiring;
import ai.kompile.graph.reasoning.fol.semiring.ViterbiSemiring;
import ai.kompile.graph.reasoning.hybrid.HybridReasoner;
import ai.kompile.graph.reasoning.hybrid.HybridReasoner.ScoredEntity;
import ai.kompile.graph.reasoning.discovery.RelationCandidateDiscovery.Candidate;
import ai.kompile.graph.reasoning.discovery.RelationCandidateDiscovery.RelationProfile;
import ai.kompile.graph.reasoning.discovery.RelationCandidateMebnEvaluator;
import ai.kompile.graph.reasoning.discovery.RelationCandidateMebnEvaluator.CandidatePosterior;
import ai.kompile.graph.reasoning.discovery.RelationNormalizer;
import ai.kompile.graph.reasoning.discovery.RelationProfileConfig;
import ai.kompile.graph.reasoning.discovery.RelationSchemaConfig;
import ai.kompile.graph.reasoning.explain.ReasoningTrace;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.graph.reasoning.tms.atms.Atms;
import ai.kompile.graph.reasoning.tms.atms.AtmsBuilder;
import ai.kompile.graph.reasoning.tms.atms.Environment;
import ai.kompile.graph.reasoning.tms.inconsistency.BelnapMarking;
import ai.kompile.graph.reasoning.tms.inconsistency.Mark;
import ai.kompile.graph.reasoning.tms.inconsistency.MarkingResult;
import ai.kompile.graph.reasoning.unified.MiniJson;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.graph.reasoning.unified.UnifiedGraphFormat;
import ai.kompile.process.discovery.ProcessDiscoveryServiceImpl;
import ai.kompile.process.discovery.ProcessSuggestion;
import ai.kompile.process.discovery.ProcessUnifiedGraphArtifacts;
import ai.kompile.process.discovery.mining.convert.ProcessTreeToSuggestion;
import ai.kompile.process.discovery.mining.declare.DeclareConstraint;
import ai.kompile.process.discovery.mining.dfg.DfgBuilder;
import ai.kompile.process.discovery.mining.dfg.DirectlyFollowsGraph;
import ai.kompile.process.discovery.mining.entail.ProcessEntailment;
import ai.kompile.process.discovery.mining.entail.ProcessEntailmentResult;
import ai.kompile.process.discovery.mining.entail.ProcessSemanticAtomExtractor;
import ai.kompile.process.discovery.mining.entail.ProcessStateEntailment;
import ai.kompile.process.discovery.mining.extract.ReasoningGraphEventLogExtractor;
import ai.kompile.process.discovery.mining.log.Event;
import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.log.Trace;
import ai.kompile.process.discovery.mining.miner.InductiveMiner;
import ai.kompile.process.discovery.mining.tree.ProcessTree;
import ai.kompile.process.workflow.ProcessDefinition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Runs the reasoning library's deduction-oriented algorithms over the hand-curated FPNA graph.
 */
public final class FpnaDeductionExperiment {

    private static final String SARAH_ATTACHMENT = atom("sentEmailWithAttachment",
            "person:sarah_chen", "spreadsheet:amer_forecast_q3_final_v2");
    private static final String J_PARK_ATTACHMENT = atom("sentEmailWithAttachment",
            "person:j_park", "spreadsheet:amer_forecast_q3_final_v2");
    private static final String INTAKE_TO_TB_TIE = atom("processPath",
            "close:regional_workbook_intake", "close:tb_tie_control");
    private static final String MEI_CONSOLIDATION_RECIPIENT = atom("consolidationRecipient", "person:mei_chen");
    private static final String J_PARK_CONSOLIDATION_RECIPIENT = atom("consolidationRecipient", "person:j_park");
    private static final String MEI_WORKBOOK_RECIPIENT = atom("workbookRecipient", "person:mei_chen");
    private static final String AMER_EMAIL_SUBMISSION = atom("emailSubmitsForecast",
            "email:amer_q3_forecast", "forecast:amer_q3_2026");
    private static final String AMER_SENT_TO_MEI = atom("forecastSentTo",
            "forecast:amer_q3_2026", "person:mei_chen");

    private static final String FPNA_DOMAIN_LAYER = "fpna-domain";
    private static final List<String> FPNA_DOMAIN_DIMENSIONS = List.of(
            "amer", "emea", "apac", "forecast", "email", "workbook", "close", "control", "taxonomy", "consolidation");
    private static final double[] CONSOLIDATION_QUERY = {1.0, 0.35, 0.25, 1.0, 0.75, 0.9, 0.65, 0.45, 0.15, 0.8};

    private static final Map<String, String> RELATION_PREDICATES = Map.ofEntries(
            Map.entry("SENT_BY", "sentBy"),
            Map.entry("SENT_TO", "sentTo"),
            Map.entry("HAS_ATTACHMENT", "hasAttachment"),
            Map.entry("SUBMITTED_BY", "submittedBy"),
            Map.entry("CONTAINS", "contains"),
            Map.entry("TRIGGERS", "triggers"),
            Map.entry("REFERENCES_TAXONOMY", "referencesTaxonomy"),
            Map.entry("VALIDATES", "validates"),
            Map.entry("APPROVED_BY", "approvedBy"),
            Map.entry("FEEDS_INTO", "feedsInto")
    );

    private FpnaDeductionExperiment() {
    }

    private record ProcessStepCoverage(String stepId,
                                       String name,
                                       String status,
                                       String graphEvidence,
                                       String gap) {
    }

    private record ControlCoverage(String controlId,
                                   String name,
                                   String expectedStep,
                                   String status,
                                   String graphEvidence,
                                   String gap) {
    }

    private record TriageCoverage(String pattern,
                                  String expectedAction,
                                  String status,
                                  String graphEvidence,
                                  String gap) {
    }

    private record ProcessAuditCounts(long representedSteps, long partialSteps, long missingSteps) {
    }

    private record ProcessSpottingEval(String name,
                                       String goldTarget,
                                       EventLog eventLog,
                                       DirectlyFollowsGraph dfg,
                                       ProcessEntailmentResult entailment,
                                       List<String> evidence,
                                       String verdict) {
        int acceptedPrecedenceCount() {
            return entailment.accepted().size();
        }

        int entailedOnlyCount() {
            return entailment.entailedOnly().size();
        }

        int processComponentCount() {
            return connectedComponents(entailment.assertable(0.50));
        }

        int activityCount() {
            return eventLog.activityNames().size();
        }
    }

    private record ProcessLibraryBundle(List<ProcessSuggestion> suggestions,
                                        List<ProcessDefinition> definitions,
                                        List<ProcessSemanticAtomExtractor.SemanticAtom> semanticAtoms,
                                        ProcessStateEntailment.Result stateEntailment) {
    }

    private record GraphOnlyProcessEvidence(List<CandidatePosterior> candidates,
                                            List<CandidatePosterior> promoted,
                                            List<EntailmentRecord> mebnRecords,
                                            List<ProcessSpottingEval> evals) {
        int candidateCount() {
            return candidates.size();
        }

        int promotedCount() {
            return promoted.size();
        }
    }

    public static void main(String[] args) throws Exception {
        Path output = args.length == 0 ? defaultOutputPath() : Path.of(args[0]);
        Files.createDirectories(output.toAbsolutePath().getParent());

        UnifiedGraph graph = FpnaKGraphSample.buildGraph();
        RelationNormalizer.Result relationNormalization = normalizeProcessRelations(graph);
        attachFpnaDomainEmbeddings(graph);
        attachFpnaRelationCandidateProfiles(graph);
        FactStore factStore = toObservedFactStore(graph);
        List<DatalogRule> rules = rules();

        InMemoryInferredFactStore inferredStore = new InMemoryInferredFactStore();
        MaterializationResult materialized = new ForwardChainingMaterializer(32, 100_000)
                .materialize(factStore, rules, inferredStore);

        FixpointResult fixpoint = RecursiveQueryEngine.evaluate(
                rules,
                FolDatalogAdapter.factStoreEdb(factStore),
                64,
                100_000,
                16);

        AnnotatedResult<Double> viterbi = RecursiveQueryEngine.evaluateAnnotated(
                rules,
                FolDatalogAdapter.factStoreEdb(factStore),
                ViterbiSemiring.INSTANCE,
                FolDatalogAdapter.factValueAnnotator(factStore),
                64,
                100_000,
                16);

        AnnotatedResult<Long> counts = RecursiveQueryEngine.evaluateAnnotated(
                rules,
                FolDatalogAdapter.factStoreEdb(factStore),
                CountingSemiring.INSTANCE,
                ignored -> 1L,
                64,
                100_000,
                16);

        TopKProofsSemiring topKSemiring = new TopKProofsSemiring(4);
        AnnotatedResult<ProofSet> topK = RecursiveQueryEngine.evaluateAnnotated(
                rules,
                FolDatalogAdapter.factStoreEdb(factStore),
                topKSemiring,
                proofAnnotator(factStore, topKSemiring.k()),
                64,
                100_000,
                16);

        Atms atms = AtmsBuilder.fromFixpoint(fixpoint, edbFactKeys(factStore), 64);
        WhyNotExplainer.WhyNotReport whyNot = whyNotReport(rules, inferredStore, factStore, J_PARK_ATTACHMENT);
        MarkingResult belnap = belnapReport(factStore, inferredStore);
        List<ScoredEntity> hybridRanking = runHybridReasoner(graph);
        GraphOnlyProcessEvidence graphOnlyEvidence = runGraphOnlyProcessEvidence(graph);
        List<ProcessSpottingEval> spottingEvals = new ArrayList<>(runProcessSpottingEval(graph));
        spottingEvals.addAll(graphOnlyEvidence.evals());
        ProcessLibraryBundle processLibrary = emitProcessLibraryArtifacts(graph, spottingEvals);
        String processGapReport = renderProcessDerivationGapReport(graph, fixpoint, spottingEvals,
                graphOnlyEvidence, relationNormalization);
        String processGeneratorAudit = renderProcessGeneratorMissingToolsAudit(relationNormalization);

        String report = renderReport(graph, factStore, rules, inferredStore, materialized, fixpoint,
                viterbi, counts, topK, atms, whyNot, belnap, hybridRanking, spottingEvals,
                graphOnlyEvidence, processLibrary, relationNormalization, processGapReport, output);

        graph.putArtifactText("deduction-report.md", report);
        graph.putArtifactText("hybrid-ranking.tsv", renderHybridRankingTsv(graph, hybridRanking));
        graph.putArtifactText("graph-only-process-improvements.tsv", renderGraphOnlyProcessEvidenceTsv(graphOnlyEvidence));
        graph.putArtifactText("process-derivation-gap-report.md", processGapReport);
        graph.putArtifactText("process-generator-missing-tools-audit.md", processGeneratorAudit);
        graph.putArtifactText("relation-normalization.tsv", renderRelationNormalizationTsv(relationNormalization));
        graph.putArtifactText("process-entailment-spotting-eval.tsv", renderProcessSpottingTsv(spottingEvals));
        graph.putArtifactText("process/state-entailment.jsonl",
                renderStateEntailmentJsonl(processLibrary.stateEntailment().states()));
        graph.putArtifactText("deduced-facts.jsonl", inferredStore.toJsonl());
        graph.putArtifactText("deduction-fact-base.txt", factStore.allFacts().stream()
                .map(Fact::atomKey)
                .sorted()
                .collect(Collectors.joining("\n", "", "\n")));
        graph.save(output);

        System.out.print(report);
    }

    private static Path defaultOutputPath() {
        String baseDir = System.getProperty("fpna.sample.basedir", System.getProperty("user.dir"));
        return Path.of(baseDir, "target", "fpna-deduction-results" + UnifiedGraphFormat.EXTENSION);
    }

    private static RelationNormalizer.Result normalizeProcessRelations(UnifiedGraph graph) {
        attachFpnaRelationNormalizationSchemas(graph);
        return RelationSchemaConfig.fromArtifact(graph).materialize(graph);
    }

    private static void attachFpnaRelationNormalizationSchemas(UnifiedGraph graph) {
        new RelationSchemaConfig(List.of(
                RelationNormalizer.RelationSchema.of("SUBMITTED_BY",
                        List.of("PERSON"), List.of("REGIONAL_FORECAST"), "SUBMITTED_BY", "SUBMITS"),
                RelationNormalizer.RelationSchema.of("VALIDATES",
                        List.of("CONTROL_ASSERTION"), List.of("CLOSE_STEP"), "VALIDATES"),
                RelationNormalizer.RelationSchema.of("APPROVED_BY",
                        List.of("PERSON", "APPROVAL_ROLE"), List.of("CLOSE_STEP"), "APPROVED_BY", "APPROVES"),
                RelationNormalizer.RelationSchema.of("ESCALATED_TO",
                        List.of("VARIANCE_TRIAGE", "DATA_QUALITY_FLAG"), List.of("PERSON", "APPROVAL_ROLE"), "ESCALATED_TO")))
                .putArtifact(graph);
    }

    private static FactStore toObservedFactStore(UnifiedGraph graph) {
        FactStore store = new FactStore();
        for (GraphEntity entity : graph.entities()) {
            store.assertFact(Fact.soft(atom("entityType", entity.id(), normalizeType(entity.type())),
                    clamp(entity.confidence()), "graph:entity:" + entity.id()));
        }
        for (GraphRelation relation : graph.relations()) {
            if (relation.id().startsWith("derived:")) {
                continue;
            }
            String predicate = RELATION_PREDICATES.getOrDefault(relation.type(), lowerCamel(relation.type()));
            store.assertFact(Fact.soft(atom(predicate, relation.sourceId(), relation.targetId()),
                    clamp(relation.confidence()), relation.id()));
        }
        return store;
    }

    private static List<DatalogRule> rules() {
        return List.of(
                rule("personSentEmail", List.of("?P", "?E"),
                        pos("sentBy", "?E", "?P")),
                rule("sentEmailWithAttachment", List.of("?P", "?W"),
                        pos("sentBy", "?E", "?P"),
                        pos("hasAttachment", "?E", "?W")),
                rule("emailSubmitsForecast", List.of("?E", "?F"),
                        pos("sentBy", "?E", "?P"),
                        pos("submittedBy", "?P", "?F")),
                rule("forecastSentTo", List.of("?F", "?R"),
                        pos("submittedBy", "?P", "?F"),
                        pos("sentBy", "?E", "?P"),
                        pos("sentTo", "?E", "?R")),
                rule("recipientOfAttachedForecast", List.of("?R", "?F"),
                        pos("sentTo", "?E", "?R"),
                        pos("hasAttachment", "?E", "?W"),
                        pos("sentBy", "?E", "?P"),
                        pos("submittedBy", "?P", "?F")),
                rule("workbookRecipient", List.of("?R"),
                        pos("sentTo", "?E", "?R"),
                        pos("hasAttachment", "?E", "?W")),
                rule("consolidationRecipient", List.of("?R"),
                        pos("sentTo", "email:amer_q3_forecast", "?R"),
                        pos("sentTo", "email:emea_q3_forecast", "?R"),
                        pos("sentTo", "email:apac_forecast", "?R")),
                rule("workbookTable", List.of("?W", "?T"),
                        pos("contains", "?W", "?T")),
                rule("forecastUsesChannel", List.of("?F", "?C"),
                        pos("referencesTaxonomy", "?F", "?C")),
                rule("controlledStep", List.of("?S"),
                        pos("validates", "?C", "?S"),
                        pos("entityType", "?S", "closeStep")),
                rule("approvedCloseStep", List.of("?S"),
                        pos("approvedBy", "?P", "?S"),
                        pos("entityType", "?S", "closeStep")),
                rule("personApprovedCloseStep", List.of("?P", "?S"),
                        pos("approvedBy", "?P", "?S"),
                        pos("entityType", "?P", "person"),
                        pos("entityType", "?S", "closeStep")),
                rule("processPath", List.of("?X", "?Y"),
                        pos("feedsInto", "?X", "?Y")),
                rule("processPath", List.of("?X", "?Z"),
                        pos("processPath", "?X", "?Y"),
                        pos("feedsInto", "?Y", "?Z"))
        );
    }

    private static WhyNotExplainer.WhyNotReport whyNotReport(List<DatalogRule> rules,
                                                             InMemoryInferredFactStore inferredStore,
                                                             FactStore factStore,
                                                             String claimAtom) {
        List<WhyNotExplainer.RuleNf> normalizedRules = rules.stream()
                .map(WhyNotExplainer::fromDatalogRule)
                .toList();
        WhyNotExplainer explainer = new WhyNotExplainer(normalizedRules, inferredStore, factStore);
        return explainer.explain(claimAtom);
    }

    private static MarkingResult belnapReport(FactStore factStore, InMemoryInferredFactStore inferredStore) {
        List<Fact> belnapFacts = new ArrayList<>(factStore.allFacts());
        for (InferredFact fact : inferredStore.allLatest()) {
            belnapFacts.add(Fact.soft(fact.atomKey(), fact.confidence(), "deduction:" + fact.runId()));
        }
        belnapFacts.add(Fact.soft("NOT_" + SARAH_ATTACHMENT, 0.92, "manual-refutation:fpna-test"));
        return BelnapMarking.mark(belnapFacts);
    }

    private static void attachFpnaDomainEmbeddings(UnifiedGraph graph) {
        for (GraphEntity entity : graph.entities()) {
            graph.putEntityVector(FPNA_DOMAIN_LAYER, entity.id(), domainVector(entity));
        }
        graph.putArtifactText("hybrid-vector-layer.md", "# FPNA Domain Vector Layer\n\n"
                + "Layer: `" + FPNA_DOMAIN_LAYER + "`\n\n"
                + "Dimensions: " + FPNA_DOMAIN_DIMENSIONS + "\n\n"
                + "Query vector for the hybrid run: " + formatVector(CONSOLIDATION_QUERY) + "\n\n"
                + "These vectors are deterministic sample features, not learned embeddings. They exist "
                + "only to exercise HybridReasoner over this throwaway FPNA graph.\n");
    }

    private static List<ScoredEntity> runHybridReasoner(UnifiedGraph graph) {
        ReasoningGraph hybridGraph = graph.withEmbeddingLayer(FPNA_DOMAIN_LAYER);
        return new HybridReasoner()
                .structural(HybridReasoner.Structural.PSL)
                .structuralWeight(0.55)
                .semanticWeight(0.45)
                .rank(hybridGraph, CONSOLIDATION_QUERY);
    }

    private static double[] domainVector(GraphEntity entity) {
        double[] v = new double[FPNA_DOMAIN_DIMENSIONS.size()];
        String id = entity.id().toLowerCase();
        String text = graphText(entity).toLowerCase();
        String type = entity.type().toUpperCase();

        if (containsAny(text, "amer", "america", "us")) v[0] = 1.0;
        if (containsAny(text, "emea", "gbp", "eur", "europe")) v[1] = 1.0;
        if (containsAny(text, "apac", "jp", "au", "sg", "asia")) v[2] = 1.0;

        if (type.contains("FORECAST") || containsAny(text, "forecast", "fcst", "pipeline", "revenue")) {
            v[3] = Math.max(v[3], 1.0);
        }
        if (type.contains("EMAIL") || containsAny(text, "email", "sent", "recipient", "attachment")) {
            v[3] = Math.max(v[3], 0.75);
            v[4] = Math.max(v[4], 1.0);
            v[5] = Math.max(v[5], 0.70);
        }
        if (type.contains("SPREADSHEET") || type.contains("TABLE")
                || containsAny(text, "workbook", "spreadsheet", "subtotal", "sheet")) {
            v[3] = Math.max(v[3], 0.60);
            v[5] = Math.max(v[5], 1.0);
        }
        if (type.contains("CLOSE") || containsAny(text, "close", "validate", "triage", "fx", "trial balance", "gate")) {
            v[6] = Math.max(v[6], 1.0);
        }
        if (type.contains("CONTROL") || containsAny(text, "control", "validates", "assertion", "sox", "tie", "coverage", "mapping", "gm band")) {
            v[6] = Math.max(v[6], 0.80);
            v[7] = Math.max(v[7], 1.0);
        }
        if (type.contains("TAXONOMY") || type.contains("CURRENCY") || type.contains("SKU")
                || containsAny(text, "sku", "channel", "currency", "taxonomy", "mapping", "fx", "amazon", "dtc", "wholesale")) {
            v[8] = Math.max(v[8], 1.0);
        }
        if (type.contains("DATA_QUALITY") || containsAny(text, "quality", "stale", "drift", "mismatch", "variance")) {
            v[3] = Math.max(v[3], 0.50);
            v[5] = Math.max(v[5], 0.70);
        }
        if (containsAny(text, "consolidation", "consolidate", "rollup", "roll up", "group p&l")) {
            v[9] = Math.max(v[9], 1.0);
        }

        switch (id) {
            case "person:mei_chen" -> {
                v[0] = Math.max(v[0], 0.85);
                v[1] = Math.max(v[1], 0.85);
                v[2] = Math.max(v[2], 0.85);
                v[3] = Math.max(v[3], 0.55);
                v[4] = Math.max(v[4], 0.60);
                v[5] = Math.max(v[5], 0.45);
                v[6] = Math.max(v[6], 0.35);
                v[9] = Math.max(v[9], 1.0);
            }
            case "person:sarah_chen" -> {
                v[0] = Math.max(v[0], 1.0);
                v[3] = Math.max(v[3], 0.85);
                v[4] = Math.max(v[4], 0.75);
                v[5] = Math.max(v[5], 0.70);
                v[9] = Math.max(v[9], 0.25);
            }
            case "person:francois_vasseur" -> {
                v[1] = Math.max(v[1], 1.0);
                v[3] = Math.max(v[3], 0.85);
                v[4] = Math.max(v[4], 0.75);
                v[5] = Math.max(v[5], 0.70);
                v[9] = Math.max(v[9], 0.25);
            }
            case "person:ayako_tanaka" -> {
                v[2] = Math.max(v[2], 1.0);
                v[3] = Math.max(v[3], 0.85);
                v[4] = Math.max(v[4], 0.75);
                v[5] = Math.max(v[5], 0.70);
                v[9] = Math.max(v[9], 0.25);
            }
            case "person:j_park", "person:s_reyes" -> {
                v[0] = Math.max(v[0], 0.65);
                v[6] = Math.max(v[6], 0.80);
                v[7] = Math.max(v[7], 0.85);
            }
            default -> {
                if (type.contains("PERSON")) {
                    v[3] = Math.max(v[3], 0.20);
                    v[9] = Math.max(v[9], 0.10);
                }
            }
        }
        return v;
    }

    private static String renderHybridRankingTsv(UnifiedGraph graph, List<ScoredEntity> ranking) {
        Map<String, GraphEntity> entities = entitiesById(graph);
        StringBuilder out = new StringBuilder("rank\tentityId\tlabel\ttype\tscore\tstructuralScore\tsemanticScore\n");
        for (int i = 0; i < ranking.size(); i++) {
            ScoredEntity scored = ranking.get(i);
            GraphEntity entity = entities.get(scored.entityId());
            out.append(i + 1).append('\t')
                    .append(scored.entityId()).append('\t')
                    .append(entity == null ? "" : entity.label()).append('\t')
                    .append(entity == null ? "" : entity.type()).append('\t')
                    .append(String.format("%.6f", scored.score())).append('\t')
                    .append(String.format("%.6f", scored.structuralScore())).append('\t')
                    .append(String.format("%.6f", scored.semanticScore())).append('\n');
        }
        return out.toString();
    }

    private static List<ProcessSpottingEval> runProcessSpottingEval(UnifiedGraph graph) {
        List<ProcessSpottingEval> evals = new ArrayList<>();
        evals.add(spotProcess(
                "main-close-chain",
                "Monthly FP&A Close main process",
                new EventLog(List.of(trace("close-2026-06",
                        List.of("Receive regional forecast workbooks",
                                "Version Assertion Gate",
                                "Validate forecast workbook and triage variances",
                                "FX-translate forecast lines to USD",
                                "Trial Balance Tie Control"),
                        List.of("close:regional_workbook_intake",
                                "close:version_assertion_gate",
                                "close:validate_triage_variances",
                                "close:fx_translate_forecast",
                                "close:tb_tie_control")))),
                List.of("4 observed FEEDS_INTO edges over 5 CLOSE_STEP entities"),
                "partial: finds one local close process component from the original sparse graph"));
        EventLog crawlerExpectedClose = crawlerExpectedCloseEventLog(graph);
        if (!crawlerExpectedClose.isEmpty()) {
            evals.add(spotProcess(
                    "crawler-expected-monthly-close-dag",
                    "Monthly FP&A Close crawler-expected topology",
                    crawlerExpectedClose,
                    List.of("Graph-derived EventLog from crawler-expected CLOSE_STEP nodes with processName=Monthly FP&A Close and stepOrder metadata"),
                    "complete for audited 14-node crawl output: ProcessEntailment sees one ordered Monthly FP&A Close component; expected-graph step 3.2 is still collapsed into the crawl d0_s10 projection node"));
        }
        evals.add(spotProcess(
                "regional-forecast-submission",
                "Regional Forecast Submission & Normalization subprocess",
                new EventLog(List.of(
                        trace("amer-submission", regionalSubmissionActivities(),
                                List.of("email:amer_q3_forecast", "spreadsheet:amer_forecast_q3_final_v2",
                                        "forecast:amer_q3_2026", "close:regional_workbook_intake")),
                        trace("emea-submission", regionalSubmissionActivities(),
                                List.of("email:emea_q3_forecast", "spreadsheet:emea_forecast_jun_aug_2026",
                                        "forecast:emea_q3_2026", "close:regional_workbook_intake")),
                        trace("apac-submission", regionalSubmissionActivities(),
                                List.of("email:apac_forecast", "spreadsheet:apac_fcst_fy27q1",
                                        "forecast:apac_fy27q1", "close:regional_workbook_intake")))),
                List.of("3 regional SENT_BY/SENT_TO/HAS_ATTACHMENT/SUBMITTED_BY patterns"),
                "partial: submission is visible; generic SLA/remediation hints now appear elsewhere in relation events, but normalization and format-validator details are still thin"));
        evals.add(spotProcess(
                "observed-relation-events",
                "Graph-observed relation event processes",
                ReasoningGraphEventLogExtractor.relationEvents().extract(graph),
                List.of("Generic ReasoningGraphEventLogExtractor projected event-like GraphRelation instances into cases and activities"),
                "partial relation-event view: observed relation events are visible without hand-picked traces, including generic crawl-derived SLA/remediation state hints; normalization and format-validator detail is still incomplete"));
        evals.add(spotProcess(
                "variance-triage",
                "Variance Triage & Auto-Correction subprocess",
                new EventLog(List.of(trace("amer-dq-triage",
                        List.of("Detect stale subtotal data quality flag",
                                "Trigger workbook recheck",
                                "Validate forecast workbook and triage variances"),
                        List.of("dq:amer_stale_subtotals",
                                "spreadsheet:amer_forecast_q3_final_v2",
                                "close:validate_triage_variances")))),
                List.of("1 TRIGGERS edge from data quality flag to workbook plus the validation close step"),
                "weak partial: one generic stale-data route, not the five gold variance patterns"));
        evals.add(spotProcess(
                "control-validation-suite",
                "Forecast Control Validation Suite subprocess",
                new EventLog(List.of(trace("c01-validation",
                        List.of("Assert C-01 TB Tie control", "Validate Trial Balance Tie Control"),
                        List.of("control:c01_tb_tie", "close:tb_tie_control")))),
                List.of("1 VALIDATES edge from control:c01_tb_tie to close:tb_tie_control"),
                "weak partial: spots a control-check component, but C-01 semantics mismatch the gold FX Consistency control"));
        return evals;
    }

    private static GraphOnlyProcessEvidence runGraphOnlyProcessEvidence(UnifiedGraph graph) {
        RelationProfile validationProfile = relationProfileFromGraph(graph, "VALIDATES");
        ReasoningGraph scoringGraph = graph.withEmbeddingLayer(FPNA_DOMAIN_LAYER);
        RelationCandidateMebnEvaluator.Result result = RelationCandidateMebnEvaluator.evaluate(scoringGraph, validationProfile);
        result.putArtifact(graph);
        if (result.candidates().isEmpty()) {
            return new GraphOnlyProcessEvidence(List.of(), List.of(), result.mebnRecords(), List.of());
        }

        List<CandidatePosterior> scored = result.candidates();
        List<CandidatePosterior> promoted = result.promoted(0.55, 0.30);
        List<ProcessSpottingEval> evals = promoted.isEmpty()
                ? List.of()
                : List.of(spotProcess(
                "graph-only-relation-candidate-mebn-control-validation",
                "Forecast Control Validation Suite subprocess",
                controlValidationEventLog(promoted),
                List.of("Graph-only relation candidate discovery profile for VALIDATES(CONTROL_ASSERTION, CLOSE_STEP)",
                        "Generic RelationCandidateMebnEvaluator soft findings over an augmented candidate-edge graph; no gold facts used as evidence"),
                "weak partial: improves control-validation spotting, but candidates remain probabilistic and source-system steps are still absent"));
        return new GraphOnlyProcessEvidence(scored, promoted, result.mebnRecords(), evals);
    }

    private static void attachFpnaRelationCandidateProfiles(UnifiedGraph graph) {
        RelationProfile validatesProfile = RelationProfile.builder("VALIDATES")
                .sourceTypes("CONTROL_ASSERTION")
                .targetTypes("CLOSE_STEP")
                .relationTerms("validate", "validates", "validation", "reconcile", "control", "triage", "check")
                .boost("pipeline-to-forecast-validation", 0.35,
                        List.of("pipeline"), List.of("forecast", "reconcile", "validate", "triage"))
                .boost("sku-mapping-to-workbook-validation", 0.65,
                        List.of("sku", "mapping"), List.of("workbook", "triage", "validate", "taxonomy"))
                .boost("channel-gm-to-variance-validation", 0.65,
                        List.of("channel", "gm"), List.of("variance", "triage", "validate", "forecast"))
                .boost("fx-control-to-fx-step", 0.55,
                        List.of("fx", "currency"), List.of("fx", "currency", "usd"))
                .boost("tie-control-to-tie-step", 0.55,
                        List.of("tie", "trial balance"), List.of("tie", "trial balance"))
                .penalizeTarget("generic-intake-or-version-gate", 0.25,
                        List.of("version assertion", "version gate", "workbook intake", "receive regional forecast"))
                .penalizeTargetUnlessSource("fx-step-without-fx-control", 0.20,
                        List.of("fx", "currency", "usd"), List.of("fx", "currency"))
                .penalizeTargetUnlessSource("tie-step-without-tie-control", 0.15,
                        List.of("tie", "trial balance"), List.of("tie", "trial balance"))
                .weights(0.25, 0.10, 0.55, 0.10)
                .minScore(0.30)
                .topKPerSource(1)
                .skipSourcesWithExistingRelation(true)
                .build();
        new RelationProfileConfig(List.of(validatesProfile)).putArtifact(graph);
    }

    private static RelationProfile relationProfileFromGraph(UnifiedGraph graph, String relationType) {
        return RelationProfileConfig.fromArtifact(graph)
                .profile(relationType)
                .orElseThrow(() -> new IllegalStateException("Missing graph relation profile: " + relationType));
    }

    private static EventLog controlValidationEventLog(List<CandidatePosterior> promoted) {
        List<Trace> traces = new ArrayList<>();
        for (CandidatePosterior evidence : promoted) {
            Candidate candidate = evidence.candidate();
            traces.add(trace("candidate-" + sanitizeId(candidate.sourceId()),
                    List.of("Assert " + candidate.sourceLabel() + " control",
                            "Validate " + candidate.targetLabel()),
                    List.of(candidate.sourceId(), candidate.targetId())));
        }
        return new EventLog(traces);
    }

    private static String graphText(GraphEntity entity) {
        StringBuilder text = new StringBuilder();
        text.append(entity.id()).append(' ')
                .append(entity.type()).append(' ')
                .append(entity.label()).append(' ');
        for (String tag : entity.tags()) {
            text.append(tag).append(' ');
        }
        entity.attributes().forEach((key, value) -> text.append(key).append(' ').append(value).append(' '));
        return text.toString();
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String sanitizeId(String id) {
        return id.replaceAll("[^A-Za-z0-9]+", "_");
    }

    private static ProcessSpottingEval spotProcess(String name,
                                                   String goldTarget,
                                                   EventLog eventLog,
                                                   List<String> evidence,
                                                   String verdict) {
        DirectlyFollowsGraph dfg = DfgBuilder.build(eventLog);
        ProcessEntailmentResult entailment = ProcessEntailment.entail(
                eventLog, dfg, List.<DeclareConstraint>of(), 0.0, 0, 0);
        return new ProcessSpottingEval(name, goldTarget, eventLog, dfg, entailment, evidence, verdict);
    }

    private static ProcessLibraryBundle emitProcessLibraryArtifacts(UnifiedGraph graph,
                                                                    List<ProcessSpottingEval> evals) {
        List<ProcessSuggestion> suggestions = new ArrayList<>();
        List<ProcessDefinition> definitions = new ArrayList<>();
        List<ProcessSemanticAtomExtractor.SemanticAtom> semanticAtoms = new ArrayList<>();
        ProcessDiscoveryServiceImpl acceptor = new ProcessDiscoveryServiceImpl(
                (ai.kompile.knowledgegraph.service.KnowledgeGraphService) null);
        for (ProcessSpottingEval eval : evals) {
            if (eval.eventLog().size() == 0 || eval.activityCount() == 0) {
                continue;
            }
            ProcessTree tree = new InductiveMiner(0.0).mine(eval.eventLog());
            ProcessSuggestion suggestion = ProcessTreeToSuggestion.convert(tree, eval.eventLog(), eval.goldTarget());
            String suggestionId = "fpna-process-" + sanitizeId(eval.name());
            suggestion.setId(suggestionId);
            suggestion.setProcessKey("fpna-" + sanitizeId(eval.goldTarget()).toLowerCase(Locale.ROOT));
            suggestion.setEvidence(new ArrayList<>(eval.evidence()));
            suggestion.setReasoningTraceId("trace:" + suggestionId);
            suggestion.setReasoningTraceArtifactName(ProcessUnifiedGraphArtifacts.traceArtifactName(suggestionId));
            List<ProcessSemanticAtomExtractor.SemanticAtom> atoms = ProcessSemanticAtomExtractor.extract(eval.eventLog());
            semanticAtoms.addAll(atoms);
            ProcessDefinition definition = acceptor.acceptSuggestion(suggestion);
            definitions.add(definition);
            suggestions.add(suggestion);
            ProcessUnifiedGraphArtifacts.putTrace(graph, suggestionId, processReasoningTrace(eval, suggestion, atoms));
        }
        ProcessStateEntailment.Result stateEntailment = ProcessStateEntailment.entail(semanticAtoms);
        ProcessUnifiedGraphArtifacts.putArtifacts(graph, suggestions, definitions);
        graph.putArtifactText("process/semantic-atoms.jsonl", renderSemanticAtomsJsonl(semanticAtoms));
        graph.putArtifactText("process/state-entailment.jsonl", renderStateEntailmentJsonl(stateEntailment.states()));
        return new ProcessLibraryBundle(List.copyOf(suggestions), List.copyOf(definitions),
                List.copyOf(semanticAtoms), stateEntailment);
    }

    private static ReasoningTrace processReasoningTrace(ProcessSpottingEval eval,
                                                        ProcessSuggestion suggestion,
                                                        List<ProcessSemanticAtomExtractor.SemanticAtom> atoms) {
        List<ReasoningTrace.Step> premises = new ArrayList<>();
        for (String evidence : eval.evidence()) {
            premises.add(ReasoningTrace.Step.fact(evidence, 1.0, "process-eval:" + eval.name()));
        }
        eval.entailment().assertable(0.50).stream().limit(12).forEach(precedence ->
                premises.add(ReasoningTrace.Step.fact(precedence.kbAtomKey(), precedence.posterior(),
                        "ProcessEntailment:" + eval.entailment().runId())));
        atoms.stream().limit(12).forEach(atom ->
                premises.add(ReasoningTrace.Step.fact(atom.atomKey(), atom.confidence(), atom.sourceEventId())));
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("eval", eval.name());
        meta.put("goldTarget", eval.goldTarget());
        meta.put("traces", String.valueOf(eval.eventLog().size()));
        meta.put("activities", String.valueOf(eval.activityCount()));
        meta.put("semanticAtoms", String.valueOf(atoms.size()));
        ReasoningTrace.Step root = new ReasoningTrace.Step(ReasoningTrace.StepKind.INFERENCE,
                "processSuggestion(\"" + suggestion.getName() + "\")",
                "InductiveMiner + ProcessEntailment + semantic atom extraction",
                suggestion.getConfidence(), eval.entailment().runId(), premises, null, meta);
        return ReasoningTrace.of(root);
    }

    private static String renderSemanticAtomsJsonl(List<ProcessSemanticAtomExtractor.SemanticAtom> atoms) {
        StringBuilder out = new StringBuilder();
        Set<String> seen = new LinkedHashSet<>();
        for (ProcessSemanticAtomExtractor.SemanticAtom atom : atoms) {
            if (!seen.add(atom.atomKey())) {
                continue;
            }
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("atomKey", atom.atomKey());
            json.put("type", atom.type());
            json.put("activity", atom.activity());
            json.put("object", atom.object());
            json.put("value", atom.value());
            json.put("sourceEventId", atom.sourceEventId());
            json.put("confidence", atom.confidence());
            json.put("attributes", atom.attributes());
            out.append(MiniJson.write(json)).append('\n');
        }
        return out.toString();
    }

    private static String renderStateEntailmentJsonl(List<ProcessStateEntailment.EntailedState> states) {
        StringBuilder out = new StringBuilder();
        for (ProcessStateEntailment.EntailedState state : states) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("activity", state.activity());
            json.put("stateType", state.stateType().name());
            json.put("object", state.object());
            json.put("value", state.value());
            json.put("confidence", state.confidence());
            json.put("supportingAtomKeys", state.supportingAtomKeys());
            json.put("attributes", state.attributes());
            out.append(MiniJson.write(json)).append('\n');
        }
        return out.toString();
    }

    private static List<String> regionalSubmissionActivities() {
        return List.of("Send regional forecast email",
                "Attach regional forecast workbook",
                "Submit regional forecast",
                "Receive regional forecast workbooks");
    }

    private static EventLog crawlerExpectedCloseEventLog(UnifiedGraph graph) {
        List<GraphEntity> steps = graph.entities().stream()
                .filter(e -> "CLOSE_STEP".equalsIgnoreCase(e.type()))
                .filter(e -> Boolean.TRUE.equals(e.attributes().get("crawlExpected")))
                .filter(e -> "Monthly FP&A Close".equals(String.valueOf(e.attributes().get("processName"))))
                .sorted(Comparator.comparingInt(FpnaDeductionExperiment::stepOrder)
                        .thenComparing(GraphEntity::id))
                .toList();
        if (steps.isEmpty()) {
            return new EventLog(List.of());
        }
        return new EventLog(List.of(trace("monthly-close-crawler-expected",
                steps.stream().map(GraphEntity::label).toList(),
                steps.stream().map(GraphEntity::id).toList())));
    }

    private static int stepOrder(GraphEntity entity) {
        Object raw = entity.attributes().get("stepOrder");
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw != null) {
            try {
                return Integer.parseInt(String.valueOf(raw));
            } catch (NumberFormatException ignored) {
                return Integer.MAX_VALUE;
            }
        }
        return Integer.MAX_VALUE;
    }

    private static Trace trace(String caseId, List<String> activities, List<String> graphNodeIds) {
        LocalDateTime base = LocalDateTime.of(2026, 6, 30, 9, 0);
        List<Event> events = new ArrayList<>(activities.size());
        for (int i = 0; i < activities.size(); i++) {
            String nodeId = i < graphNodeIds.size() ? graphNodeIds.get(i) : null;
            events.add(Event.of(caseId, activities.get(i), base.plusMinutes(i * 15L), nodeId));
        }
        return new Trace(caseId, events);
    }


    private static String renderGraphOnlyProcessEvidenceTsv(GraphOnlyProcessEvidence evidence) {
        StringBuilder out = new StringBuilder("relationType\tsourceId\tsourceLabel\ttargetId\ttargetLabel\tsemanticScore\tlexicalScore\ttermScore\tgraphScore\tmebnPosterior\tmatchedRules\tpromoted\n");
        for (CandidatePosterior item : evidence.candidates()) {
            Candidate candidate = item.candidate();
            out.append(candidate.relationType()).append('\t')
                    .append(candidate.sourceId()).append('\t')
                    .append(candidate.sourceLabel()).append('\t')
                    .append(candidate.targetId()).append('\t')
                    .append(candidate.targetLabel()).append('\t')
                    .append(String.format("%.6f", candidate.semanticScore())).append('\t')
                    .append(String.format("%.6f", candidate.lexicalScore())).append('\t')
                    .append(String.format("%.6f", candidate.termScore())).append('\t')
                    .append(String.format("%.6f", candidate.score())).append('\t')
                    .append(String.format("%.6f", item.posterior())).append('\t')
                    .append(String.join(",", candidate.matchedRules()).replace('\t', ' ')).append('\t')
                    .append(evidence.promoted().contains(item)).append('\n');
        }
        return out.toString();
    }

    private static void appendGraphOnlyProcessEvidence(StringBuilder out, GraphOnlyProcessEvidence evidence) {
        out.append("Candidate source: generic `RelationCandidateDiscovery` over graph entity IDs/types/labels/tags/attributes plus deterministic vectors, driven by graph artifact `").append(RelationProfileConfig.DEFAULT_ARTIFACT).append("`. `RelationCandidateMebnEvaluator` then adds bounded candidate edges in an augmented in-memory graph and scores the requested relation with MEBN soft evidence. Gold labels are used only later for evaluation.\n\n");
        out.append("- Candidate control-validation pairs: ").append(evidence.candidateCount()).append('\n');
        out.append("- Promoted by graph score plus MEBN posterior: ").append(evidence.promotedCount()).append('\n');
        out.append("- MEBN entailment records: ").append(evidence.mebnRecords().size()).append('\n');
        out.append("- Posterior artifact: `").append(RelationCandidateMebnEvaluator.DEFAULT_EVIDENCE_ARTIFACT).append("`\n");
        out.append("- Dedicated artifact: `graph-only-process-improvements.tsv`\n\n");
        if (evidence.candidates().isEmpty()) {
            out.append("No missing control-validation candidates were supported by the graph.\n\n");
            return;
        }
        out.append("| Source | Candidate target | Semantic | Lexical | Term | Graph score | MEBN posterior | Rules | Promoted |\n");
        out.append("|---|---|---:|---:|---:|---:|---:|---|---|\n");
        for (CandidatePosterior item : evidence.candidates()) {
            Candidate candidate = item.candidate();
            out.append("| `").append(candidate.sourceId()).append("` ").append(escapeCell(candidate.sourceLabel()))
                    .append(" | `").append(candidate.targetId()).append("` ").append(escapeCell(candidate.targetLabel()))
                    .append(" | ").append(String.format("%.3f", candidate.semanticScore()))
                    .append(" | ").append(String.format("%.3f", candidate.lexicalScore()))
                    .append(" | ").append(String.format("%.3f", candidate.termScore()))
                    .append(" | ").append(String.format("%.3f", candidate.score()))
                    .append(" | ").append(String.format("%.3f", item.posterior()))
                    .append(" | ").append(escapeCell(String.join(", ", candidate.matchedRules())))
                    .append(" | ").append(evidence.promoted().contains(item) ? "yes" : "no")
                    .append(" |\n");
        }
        out.append("\nMEBN gap closed for this class: missing relation discovery is now a reusable candidate-edge stage before MEBN scoring. Remaining gaps are relation-profile coverage, live crawler emission of canonical relation/type metadata, and process-library matching for source-system activities not represented in the graph.\n\n");
    }

    private static String renderProcessSpottingTsv(List<ProcessSpottingEval> evals) {
        StringBuilder out = new StringBuilder("candidate\tgoldTarget\ttraces\tactivities\tdfgArcs\tacceptedPrecedence\tentailedOnly\tcomponents\tverdict\n");
        for (ProcessSpottingEval eval : evals) {
            out.append(eval.name()).append('\t')
                    .append(eval.goldTarget()).append('\t')
                    .append(eval.eventLog().size()).append('\t')
                    .append(eval.activityCount()).append('\t')
                    .append(eval.dfg().arcs().size()).append('\t')
                    .append(eval.acceptedPrecedenceCount()).append('\t')
                    .append(eval.entailedOnlyCount()).append('\t')
                    .append(eval.processComponentCount()).append('\t')
                    .append(eval.verdict().replace('\t', ' ')).append('\n');
        }
        return out.toString();
    }

    private static int connectedComponents(List<ProcessEntailmentResult.EntailedPrecedence> precedences) {
        Map<String, Set<String>> adjacency = new LinkedHashMap<>();
        for (ProcessEntailmentResult.EntailedPrecedence precedence : precedences) {
            adjacency.computeIfAbsent(precedence.from(), ignored -> new LinkedHashSet<>()).add(precedence.to());
            adjacency.computeIfAbsent(precedence.to(), ignored -> new LinkedHashSet<>()).add(precedence.from());
        }
        int components = 0;
        Set<String> seen = new LinkedHashSet<>();
        for (String node : adjacency.keySet()) {
            if (!seen.add(node)) {
                continue;
            }
            components++;
            List<String> frontier = new ArrayList<>(List.of(node));
            for (int i = 0; i < frontier.size(); i++) {
                for (String next : adjacency.getOrDefault(frontier.get(i), Set.of())) {
                    if (seen.add(next)) {
                        frontier.add(next);
                    }
                }
            }
        }
        return components;
    }

    private static String renderProcessDerivationGapReport(UnifiedGraph graph,
                                                           FixpointResult fixpoint,
                                                           List<ProcessSpottingEval> spottingEvals,
                                                           GraphOnlyProcessEvidence graphOnlyEvidence,
                                                           RelationNormalizer.Result relationNormalization) {
        StringBuilder out = new StringBuilder(24_000);
        List<ProcessStepCoverage> steps = goldStepCoverage(graph);
        ProcessAuditCounts counts = auditCounts(steps);
        List<GraphRelation> currentFeedsInto = relationsOfType(graph, "FEEDS_INTO");
        boolean derivedFullLocalPath = !fixpoint.derivations(INTAKE_TO_TB_TIE).isEmpty();
        long spottedComponents = spottingEvals.stream().mapToInt(ProcessSpottingEval::processComponentCount).sum();
        long completeGoldProcesses = completeGoldProcessCount(spottingEvals);
        long partialWeakProcesses = spottingEvals.size() - completeGoldProcesses;

        out.append("# FPNA Process Derivation Gap Report\n\n");
        out.append("Gold sources:\n");
        out.append("- `kompile-fpna-v4/project/src/test/resources/fpna-expected-graph.html`, sections 4 through 7\n");
        out.append("- `kompile-fpna-v4/project/src/test/resources/fpna-business-process-crawl-results.html`\n\n");
        out.append("Question: can this hand-built reasoning graph auto-derive the full Monthly FP&A Close process?\n\n");
        if (counts.missingSteps() == 0 && completeGoldProcesses > 0) {
            out.append("Answer: with the audited crawler-expected facts added, yes for the documented crawl topology. `ProcessEntailment` spots a complete Monthly FP&A Close component from graph facts alone, and documented step coverage is ")
                    .append(counts.representedSteps()).append(" represented, ")
                    .append(counts.partialSteps()).append(" partial, and ")
                    .append(counts.missingSteps()).append(" missing. This is not yet raw-document auto-discovery; the remaining production gap is making crawler ontology/type resolution emit these facts automatically.\n\n");
        } else {
            out.append("Answer: not yet. It can derive a coherent local workbook-close chain, and the graph-only embedding+MEBN probe can promote ")
                    .append(graphOnlyEvidence.promotedCount())
                    .append(" additional missing control-validation candidate(s). Current coverage is ")
                    .append(counts.representedSteps()).append(" represented, ")
                    .append(counts.partialSteps()).append(" partial, and ")
                    .append(counts.missingSteps()).append(" missing documented steps.\n\n");
        }
        out.append("Entailment spotting eval: ").append(spottedComponents)
                .append(" process component(s) across ").append(spottingEvals.size())
                .append(" graph-derived candidate logs. Strictly complete gold/crawl processes spotted: ")
                .append(completeGoldProcesses).append(". Partial/weak candidates spotted: ")
                .append(partialWeakProcesses).append(".\n");
        out.append("Relation normalization materialized ").append(relationNormalization.normalizations().size())
                .append(" canonical process relation(s), including ")
                .append(relationNormalization.flippedCount())
                .append(" flipped direction rewrite(s).\n\n");

        out.append("## What The Current Graph Can Answer\n\n");
        out.append("- Regional forecast emails resolve to canonical submitters/recipients and attachments.\n");
        out.append("- `consolidationRecipient(person:mei_chen)` is derived only when the same recipient is on AMER, EMEA, and APAC submissions.\n");
        out.append("- `sentEmailWithAttachment(person:sarah_chen, spreadsheet:amer_forecast_q3_final_v2)` is derived from `SENT_BY` plus `HAS_ATTACHMENT`.\n");
        out.append("- `processPath(close:regional_workbook_intake, close:tb_tie_control)` derived: ")
                .append(derivedFullLocalPath).append(".\n");
        out.append("- The hybrid reasoner ranks AMER forecast/workbook actors and artifacts coherently for the consolidation query.\n\n");

        out.append("## Process Spotting With Entailment\n\n");
        out.append("| Candidate | Gold target | Traces | Activities | DFG arcs | Accepted precedence | Entailed only | Components | Verdict |\n");
        out.append("|---|---|---:|---:|---:|---:|---:|---:|---|\n");
        for (ProcessSpottingEval eval : spottingEvals) {
            out.append("| ").append(eval.name())
                    .append(" | ").append(escapeCell(eval.goldTarget()))
                    .append(" | ").append(eval.eventLog().size())
                    .append(" | ").append(eval.activityCount())
                    .append(" | ").append(eval.dfg().arcs().size())
                    .append(" | ").append(eval.acceptedPrecedenceCount())
                    .append(" | ").append(eval.entailedOnlyCount())
                    .append(" | ").append(eval.processComponentCount())
                    .append(" | ").append(escapeCell(eval.verdict()))
                    .append(" |\n");
        }
        out.append("\nThe component count comes from accepted, non-refuted `Precedes` pairs returned by `ProcessEntailment`. The relation-event candidate projects actual `GraphRelation` IDs into events, while the other candidates project object/step activities. The close and regional-submission candidates also produce entailed-only transitive precedence, so the library is doing more than replaying direct edges.\n\n");

        out.append("## Graph-Only Embedding + MEBN Probe\n\n");
        appendGraphOnlyProcessEvidence(out, graphOnlyEvidence);

        out.append("## Current Process Facts\n\n");
        appendCurrentCloseSteps(out, graph);
        appendRelations(out, graph, "FEEDS_INTO", "Current FEEDS_INTO chain");
        appendRelations(out, graph, "VALIDATES", "Current control validation edges");
        appendRelations(out, graph, "APPROVED_BY", "Current approval edges");
        appendRelations(out, graph, "TRIGGERS", "Current triage/trigger edges");
        long crawlerExpectedFeedsInto = currentFeedsInto.stream().filter(FpnaDeductionExperiment::isCrawlerExpected).count();
        out.append("The audited crawl output expects 14 core `FEEDS_INTO` edges across a DAG. This graph has ")
                .append(currentFeedsInto.size())
                .append(" `FEEDS_INTO` edges total, including ")
                .append(crawlerExpectedFeedsInto)
                .append(" crawler-expected process edges. The original sparse hand-built chain is still present separately for comparison.\n\n");

        out.append("## Gold Step Coverage\n\n");
        out.append("| Step | Gold name | Status | Graph evidence | Gap |\n");
        out.append("|---|---|---|---|---|\n");
        for (ProcessStepCoverage step : steps) {
            out.append("| ").append(step.stepId())
                    .append(" | ").append(escapeCell(step.name()))
                    .append(" | ").append(step.status())
                    .append(" | ").append(escapeCell(step.graphEvidence()))
                    .append(" | ").append(escapeCell(step.gap()))
                    .append(" |\n");
        }
        out.append('\n');

        out.append("## Control Coverage\n\n");
        out.append("| Control | Gold name | Expected step | Status | Graph evidence | Gap |\n");
        out.append("|---|---|---|---|---|---|\n");
        for (ControlCoverage control : goldControlCoverage(graph)) {
            out.append("| ").append(control.controlId())
                    .append(" | ").append(escapeCell(control.name()))
                    .append(" | ").append(escapeCell(control.expectedStep()))
                    .append(" | ").append(control.status())
                    .append(" | ").append(escapeCell(control.graphEvidence()))
                    .append(" | ").append(escapeCell(control.gap()))
                    .append(" |\n");
        }
        out.append('\n');

        out.append("## Triage Coverage\n\n");
        out.append("| Pattern | Gold action | Status | Graph evidence | Gap |\n");
        out.append("|---|---|---|---|---|\n");
        for (TriageCoverage triage : goldTriageCoverage(graph)) {
            out.append("| ").append(escapeCell(triage.pattern()))
                    .append(" | ").append(escapeCell(triage.expectedAction()))
                    .append(" | ").append(triage.status())
                    .append(" | ").append(escapeCell(triage.graphEvidence()))
                    .append(" | ").append(escapeCell(triage.gap()))
                    .append(" |\n");
        }
        out.append('\n');

        out.append("## Main Gaps To Auto-Derive Everything\n\n");
        out.append("1. The enriched graph now contains crawler-expected source systems for NetSuite, Shopify, Amazon, FX, Workday, and Salesforce. The remaining gap is live crawler emission of those facts with stable ontology types, not hand-added audit facts.\n");
        out.append("2. Process identity is mostly normalized through `goldStepId`, `stepOrder`, `processName`, and `caseId`; the notable fixture gap is that the crawl output collapses expected step 3.2 into the projection node `crawl:d0_s10`. A resolver needs aliases/splits for that class of drift.\n");
        out.append("3. Step metadata is still shallow. The graph has phase, order, mode, provenance, controls, and some source dependencies, but not full triggers, inputs, outputs, SLA windows, executable approval policies, or process-library `ProcessStep` objects.\n");
        out.append("4. Event-log structure is improved but not production-grade. Relation events and step-order event logs now work, but most facts still lack real timestamps, users, artifact versions, and process-instance IDs from source systems.\n");
        out.append("5. Relation direction is now normalized in this experiment through generic relation schemas. Production still needs ontology/importer emission of those schemas so crawl `APPROVED_BY`, `SUBMITTED_BY`, and aliases are canonical before process generation.\n");
        out.append("6. Gold control nodes and `VALIDATES` edges are now present as crawler-expected facts. Production still needs control-ID disambiguation so old sample `control:c01_tb_tie` cannot collide with gold `C-01 FX Consistency`.\n");
        out.append("7. Triage routes are now represented as entities plus `TRIGGERS`/`ESCALATED_TO` relations; relation events preserve policy attributes; and semantic atom extraction emits threshold, routing, escalation, and action facts. The next reasoning gap is consuming those atoms in reusable semantic process rules.\n");
        out.append("8. Business-process artifacts are now bundled into this throwaway graph through the existing process library artifact names: `process/suggestions.json`, `process/definitions.json`, `process/semantic-atoms.jsonl`, and `trace:process:*`.\n\n");

        out.append("## Business Process Library Bridge\n\n");
        out.append("The library now has the downstream machinery needed for this sample path: `EventLogExtractor` converts production `GraphNode`/`GraphEdge` sets into cases and events; `ReasoningGraphEventLogExtractor` gives the same relation-as-event bridge to `ReasoningGraph`/`UnifiedGraph`; `DirectlyFollowsGraph` stores activity arcs; `ProcessEntailment` settles `Precedes` with PSL/Declare evidence and traceable support; `ProcessSemanticAtomExtractor` emits graph-derived policy/control atoms; `ProcessSuggestion` carries phases, suggested steps, roles, dependencies, evidence, and lineage; `ProcessDefinition` is the accepted executable shape; and `ProcessUnifiedGraphArtifacts` writes the portable artifacts.\n\n");
        out.append("The reason relation-events were weak before is that the sample owned a hand-picked relation trace instead of using a graph adapter. The reusable adapter now projects event-like relations such as `SENT_BY`, `HAS_ATTACHMENT`, `SUBMITTED_BY`, `VALIDATES`, and `FEEDS_INTO` as timestamped event observations with source/target objects as attributes, while avoiding case collapse through shared actor/resource nodes.\n\n");

        out.append("## Recommended Next Experiment\n\n");
        out.append("1. Replace the manual crawler-expected enrichment with actual crawl output and run the same graph-sensitive coverage/eval tables unchanged.\n");
        out.append("2. Make crawlers consistently emit explicit case/thread/process-instance metadata, source-system event IDs, timestamps, users, and artifact versions.\n");
        out.append("3. Feed ontology relation signatures and control-ID mappings into the generic normalizer so crawl fixtures do not need hand-written schema lists.\n");
        out.append("4. Add semantic process rules that consume control/policy atoms to infer blocked close, required approval, escalation, and control-effect conclusions.\n");
        return out.toString();
    }

    private static ProcessAuditCounts auditCounts(List<ProcessStepCoverage> steps) {
        return new ProcessAuditCounts(
                steps.stream().filter(s -> s.status().equals("represented")).count(),
                steps.stream().filter(s -> s.status().equals("partial")).count(),
                steps.stream().filter(s -> s.status().equals("missing")).count());
    }

    private static List<ProcessStepCoverage> goldStepCoverage() {
        return List.of(
                new ProcessStepCoverage("1.1", "Pull NetSuite trial balance & AR/AP", "missing", "-",
                        "No NetSuite, trial balance, AR, or AP source artifact or close step."),
                new ProcessStepCoverage("1.2", "Pull Shopify + Amazon channel revenue", "missing", "-",
                        "No Shopify/Amazon source artifacts or channel revenue intake step."),
                new ProcessStepCoverage("1.3", "Pull FX rates & forward curve", "missing", "-",
                        "No FX rate/forward-curve source artifact; FX only appears as a later close step."),
                new ProcessStepCoverage("1.4", "Receive 3 regional forecast workbooks", "represented",
                        "`close:regional_workbook_intake`, regional forecast emails, attachments, submitters, and Mei recipient.",
                        "Mostly present; still lacks due-date/BD+4 intake SLA and hourly scan evidence."),
                new ProcessStepCoverage("1.5", "Pull Workday roster & Salesforce pipeline", "missing", "-",
                        "No Workday roster, Salesforce snapshot, headcount, or pipeline source entities."),
                new ProcessStepCoverage("1.6", "Receive marketing & OpEx plan", "missing", "-",
                        "No marketing plan or OpEx plan artifact."),
                new ProcessStepCoverage("2.1", "Validate forecast workbook & triage variances", "partial",
                        "`close:validate_triage_variances`; one `dq:amer_stale_subtotals` trigger.",
                        "No full variance taxonomy, auto-correction threshold, correction budget, or routed escalation facts."),
                new ProcessStepCoverage("2.2", "Reconcile forecast to Salesforce pipeline", "missing",
                        "`control:c03_pipeline_coverage` exists as a control node only.",
                        "No Salesforce pipeline source, reconcile step, stale-deal rule, or `VALIDATES` edge."),
                new ProcessStepCoverage("2.3", "FX-translate forecast lines to USD", "partial",
                        "`close:fx_translate_forecast` and `FEEDS_INTO` from triage.",
                        "Gold C-01 should be FX Consistency; current C-01 is modeled as TB Tie and validates a different step."),
                new ProcessStepCoverage("3.1", "Project COGS at standard cost", "missing", "-",
                        "No COGS projection step or C-06 SKU Margin control."),
                new ProcessStepCoverage("3.2", "Roll forward OpEx and headcount comp", "missing", "-",
                        "No OpEx/headcount roll-forward step or M. Chen attrition approval."),
                new ProcessStepCoverage("3.3", "Consolidate & eliminate intercompany", "missing", "-",
                        "No group P&L consolidation/intercompany elimination step, L. Okafor adjustment, or C-02 control."),
                new ProcessStepCoverage("4.1", "Variance commentary & CFO pack", "missing", "-",
                        "No CFO pack/commentary step, edit/review actors, or supporting document artifact."),
                new ProcessStepCoverage("4.2", "Controller & CFO sign-off", "missing", "-",
                        "No controller/CFO sign-off step, L. Okafor/M. Sato approvals, or C-07 Statement Recon control."),
                new ProcessStepCoverage("4.3", "Publish & archive", "missing", "-",
                        "No publish/archive step or Looker/archive audit trail."));
    }

    private static List<ControlCoverage> goldControlCoverage() {
        return List.of(
                new ControlCoverage("C-01", "FX Consistency", "2.3 FX-translate forecast lines to USD", "mismatched",
                        "`control:c01_tb_tie` -> `close:tb_tie_control`.",
                        "Control ID collides with gold C-01 but means TB Tie, not FX Consistency."),
                new ControlCoverage("C-02", "Regional Total", "3.3 Consolidate & eliminate intercompany", "missing", "-",
                        "No C-02 entity or consolidation validation edge."),
                new ControlCoverage("C-03", "Pipeline Coverage", "2.2 Reconcile forecast to Salesforce pipeline", "partial",
                        "`control:c03_pipeline_coverage` exists.",
                        "No reconcile step or `VALIDATES(control:c03_pipeline_coverage, close:<pipeline step>)` edge."),
                new ControlCoverage("C-04", "SKU Mapping", "2.1 Validate forecast workbook & triage variances", "partial",
                        "`control:c04_sku_mapping` exists.",
                        "No validation edge to `close:validate_triage_variances` and no active-SKU gate details."),
                new ControlCoverage("C-05", "Channel GM Band", "2.1 Validate forecast workbook & triage variances", "partial",
                        "`control:c05_channel_gm` exists.",
                        "No validation edge or GM band threshold/commentary facts."),
                new ControlCoverage("C-06", "SKU Margin Discipline", "3.1 Project COGS at standard cost", "missing", "-",
                        "No C-06 entity or projection validation edge."),
                new ControlCoverage("C-07", "Statement Reconciliation", "4.2 Controller & CFO sign-off", "missing", "-",
                        "No C-07 entity or sign-off validation edge."));
    }

    private static List<TriageCoverage> goldTriageCoverage() {
        return List.of(
                new TriageCoverage("ChannelMismatch", "ALWAYS ESCALATE to M. Chen", "missing", "-",
                        "No channel-mismatch variance entity or escalation edge."),
                new TriageCoverage("CurrencySymbolDrift", "AUTO-CORRECT >= 0.85; J. Park below threshold", "missing", "-",
                        "No currency-symbol drift entity, confidence threshold, or auto-correction fact."),
                new TriageCoverage("SKUTypoVariant", "AUTO-CORRECT >= 0.85; escalate below threshold", "missing", "-",
                        "No SKU typo variance entity or threshold routing."),
                new TriageCoverage("GMOutOfBand", "COMMENTARY REQUIRED; analyst to M. Chen", "missing", "-",
                        "No GM out-of-band variance entity, commentary requirement, or escalation chain."),
                new TriageCoverage("StaleFXRow", "QUARANTINE; FX re-pull; >72h blocks close", "partial",
                        "`dq:amer_stale_subtotals` exists as a generic stale data-quality trigger.",
                        "Not specifically stale FX, no quarantine/re-pull/block-close policy."));
    }

    private static List<ProcessStepCoverage> goldStepCoverage(UnifiedGraph graph) {
        return goldStepCoverage().stream().map(step -> {
            GraphEntity matched = firstCrawlerExpectedEntity(graph, "goldStepId", step.stepId());
            if (matched != null) {
                return new ProcessStepCoverage(step.stepId(), step.name(), "represented",
                        "`" + matched.id() + "` " + matched.label(),
                        "Crawler-expected fact is present; production gap is making the crawler/type resolver emit it automatically.");
            }
            GraphEntity alias = firstCrawlerExpectedEntity(graph, "goldStepAliases", step.stepId());
            if (alias != null) {
                return new ProcessStepCoverage(step.stepId(), step.name(), "partial",
                        "`" + alias.id() + "` " + alias.label() + " aliases this expected step.",
                        "The crawl output collapses this gold step into another node; a production process ontology should split or explicitly alias the step.");
            }
            return step;
        }).toList();
    }

    private static List<ControlCoverage> goldControlCoverage(UnifiedGraph graph) {
        return goldControlCoverage().stream().map(control -> {
            GraphEntity matched = firstCrawlerExpectedEntity(graph, "goldControlId", control.controlId());
            if (matched == null) {
                return control;
            }
            String expectedStepId = String.valueOf(matched.attributes().getOrDefault("expectedStepId", ""));
            GraphEntity expectedStep = firstCrawlerExpectedEntity(graph, "goldStepId", expectedStepId);
            boolean validates = expectedStep != null && hasRelation(graph, matched.id(), "VALIDATES", expectedStep.id());
            return new ControlCoverage(control.controlId(), control.name(), control.expectedStep(),
                    validates ? "represented" : "partial",
                    expectedStep == null
                            ? "`" + matched.id() + "` exists but expected step " + expectedStepId + " is not represented."
                            : "`" + matched.id() + "` -> `" + expectedStep.id() + "` VALIDATES=" + validates,
                    validates
                            ? "Crawler-expected validation edge is present; production gap is automatic extraction and ontology normalization."
                            : "Control entity is present, but no crawler-expected validation edge reaches the expected step.");
        }).toList();
    }

    private static List<TriageCoverage> goldTriageCoverage(UnifiedGraph graph) {
        return goldTriageCoverage().stream().map(triage -> {
            GraphEntity matched = firstCrawlerExpectedEntity(graph, "goldPattern", triage.pattern());
            if (matched == null) {
                return triage;
            }
            boolean triggered = hasIncomingRelation(graph, matched.id(), "TRIGGERS");
            boolean escalationNeeded = containsAny(triage.expectedAction().toLowerCase(), "escalate", "j. park", "m. chen", "analyst");
            boolean escalated = hasOutgoingRelation(graph, matched.id(), "ESCALATED_TO");
            boolean represented = triggered && (!escalationNeeded || escalated);
            return new TriageCoverage(triage.pattern(), triage.expectedAction(), represented ? "represented" : "partial",
                    "`" + matched.id() + "` triggered=" + triggered + ", escalated=" + escalated,
                    represented
                            ? "Crawler-expected triage route is present; production gap is automatic extraction of thresholds/actions."
                            : "Triage pattern is present, but trigger or routing evidence is incomplete.");
        }).toList();
    }

    private static GraphEntity firstCrawlerExpectedEntity(UnifiedGraph graph, String attribute, String expectedValue) {
        for (GraphEntity entity : graph.entities()) {
            if (!Boolean.TRUE.equals(entity.attributes().get("crawlExpected"))) {
                continue;
            }
            if (attributeMatches(entity.attributes().get(attribute), expectedValue)) {
                return entity;
            }
        }
        return null;
    }

    private static boolean attributeMatches(Object raw, String expectedValue) {
        if (raw instanceof Iterable<?> values) {
            for (Object value : values) {
                if (expectedValue.equals(String.valueOf(value))) {
                    return true;
                }
            }
            return false;
        }
        return expectedValue.equals(String.valueOf(raw));
    }

    private static boolean hasRelation(UnifiedGraph graph, String sourceId, String relationType, String targetId) {
        return graph.relations().stream()
                .anyMatch(r -> sourceId.equals(r.sourceId())
                        && relationType.equalsIgnoreCase(r.type())
                        && targetId.equals(r.targetId()));
    }

    private static boolean hasIncomingRelation(UnifiedGraph graph, String targetId, String relationType) {
        return graph.relations().stream()
                .anyMatch(r -> targetId.equals(r.targetId()) && relationType.equalsIgnoreCase(r.type()));
    }

    private static boolean hasOutgoingRelation(UnifiedGraph graph, String sourceId, String relationType) {
        return graph.relations().stream()
                .anyMatch(r -> sourceId.equals(r.sourceId()) && relationType.equalsIgnoreCase(r.type()));
    }

    private static boolean isCrawlerExpected(GraphRelation relation) {
        return Boolean.TRUE.equals(relation.attributes().get("crawlExpected")) || relation.hasTag("crawler-expected");
    }

    private static long completeGoldProcessCount(List<ProcessSpottingEval> spottingEvals) {
        return spottingEvals.stream()
                .filter(eval -> eval.verdict().toLowerCase().startsWith("complete"))
                .count();
    }

    private static void appendCurrentCloseSteps(StringBuilder out, UnifiedGraph graph) {
        out.append("Current close-step entities:\n");
        graph.entities().stream()
                .filter(e -> "CLOSE_STEP".equalsIgnoreCase(e.type()))
                .sorted(Comparator.comparing(GraphEntity::id))
                .forEach(e -> out.append("- `").append(e.id()).append("` ")
                        .append(e.label()).append(" confidence=")
                        .append(String.format("%.2f", e.confidence()))
                        .append('\n'));
        out.append("\nExtra/misaligned close-step notes:\n");
        out.append("- `close:version_assertion_gate` is a useful approval/version gate, but it is not a numbered gold close step.\n");
        out.append("- `close:tb_tie_control` is modeled as a close step, while the gold process treats controls separately from numbered process steps.\n\n");
    }

    private static void appendRelations(StringBuilder out, UnifiedGraph graph, String relationType, String title) {
        out.append(title).append(":\n");
        List<GraphRelation> relations = relationsOfType(graph, relationType);
        if (relations.isEmpty()) {
            out.append("- none\n\n");
            return;
        }
        relations.stream()
                .sorted(Comparator.comparing(GraphRelation::sourceId).thenComparing(GraphRelation::targetId))
                .forEach(r -> out.append("- `").append(r.sourceId()).append("` -> `")
                        .append(r.targetId()).append("` confidence=")
                        .append(String.format("%.2f", r.confidence()))
                        .append('\n'));
        out.append('\n');
    }

    private static List<GraphRelation> relationsOfType(UnifiedGraph graph, String relationType) {
        return graph.relations().stream()
                .filter(r -> relationType.equalsIgnoreCase(r.type()))
                .toList();
    }

    private static String renderRelationNormalizationTsv(RelationNormalizer.Result result) {
        StringBuilder out = new StringBuilder("schema\taction\toriginalId\toriginalType\toriginalSource\toriginalTarget\tnormalizedId\tnormalizedType\tnormalizedSource\tnormalizedTarget\tconfidence\n");
        for (RelationNormalizer.NormalizedRelation item : result.normalizations()) {
            GraphRelation original = item.original();
            GraphRelation normalized = item.relation();
            out.append(tsv(item.schema().name())).append('\t')
                    .append(item.action()).append('\t')
                    .append(tsv(original.id())).append('\t')
                    .append(tsv(original.type())).append('\t')
                    .append(tsv(original.sourceId())).append('\t')
                    .append(tsv(original.targetId())).append('\t')
                    .append(tsv(normalized.id())).append('\t')
                    .append(tsv(normalized.type())).append('\t')
                    .append(tsv(normalized.sourceId())).append('\t')
                    .append(tsv(normalized.targetId())).append('\t')
                    .append(String.format("%.6f", normalized.confidence()))
                    .append('\n');
        }
        return out.toString();
    }

    private static String renderProcessGeneratorMissingToolsAudit(RelationNormalizer.Result relationNormalization) {
        StringBuilder out = new StringBuilder(4096);
        out.append("# Process Generator Missing Tools Audit\n\n");
        out.append("Fixed in this run:\n");
        out.append("- Generic `RelationNormalizer` can materialize canonical relation facts from observed relation type aliases and swapped endpoint signatures.\n");
        out.append("- `RelationSchemaConfig` stores normalizer schemas as a graph artifact (`").append(RelationSchemaConfig.DEFAULT_ARTIFACT).append("`), and `OntologyRelationSchemaCompiler` compiles ontology relationship declarations into the same schema shape.\n");
        out.append("- `ReasoningGraphEventLogExtractor` preserves relation attributes as `relation.*`, endpoint attributes as `source.*`/`target.*`, selected policy/process keys unprefixed, stable source-system event IDs, crawler `entity_type`/`entityType` type memberships, and canonical relation metadata when crawlers provide them.\n");
        out.append("- `ProcessAtoms`, `ProcessSemanticAtomExtractor`, and `ProcessEntailment` now consume graph-derived controls, validations, approval requirements, approvers, escalation targets, thresholds, routing policies, actions, SLA metadata, remediation metadata, and ontology process profiles as PSL facts/rules instead of only emitting passive atoms.\n");
        out.append("- `ProcessStateEntailment` derives approval, escalation, routing, threshold, SLA, remediation, role, and control-state facts from those semantic atoms for process generation beyond precedence ordering.\n");
        out.append("- `ProcessTreeToSuggestion` now lifts graph policy attributes into suggested-step `controlIds`, `requiredRoles`, `requiredPermissions`, metadata, and null-safe threshold conditions; `acceptSuggestion` carries those fields into executable `ProcessStep`s.\n");
        out.append("- `ProcessUnifiedGraphArtifacts` exposes static writers, and this sample emits `process/suggestions.json`, `process/definitions.json`, `process/semantic-atoms.jsonl`, `process/state-entailment.jsonl`, and `trace:process:*` artifacts from the same graph-derived evidence.\n");
        out.append("- FPNA experiment now runs relation normalization from the graph schema artifact before FOL fact-store projection and process entailment. Materialized ")
                .append(relationNormalization.normalizations().size()).append(" relation(s), including ")
                .append(relationNormalization.flippedCount()).append(" flipped direction rewrite(s).\n\n");
        out.append("Still missing as generalized product tools:\n");
        out.append("1. Live crawler extraction depth: the common metadata normalizer now stamps process keys, event IDs, actor aliases, artifact versions, and canonical relation aliases, but individual crawlers still need to extract richer domain policy/action/SLA/remediation attributes from source content.\n");
        out.append("2. Ontology bundle population: relationship definitions can now carry aliases/inverses, control signatures, action categories, and policy metadata, but real crawl ontology bundles still need automated population and versioned distribution.\n");
        out.append("3. Domain-level state rules: `ProcessStateEntailment` emits generic state facts; blocked-close, SLA-breach, control-effect, and remediation-severity conclusions still need domain rule packs or learned thresholds.\n");
        return out.toString();
    }

    private static String tsv(Object raw) {
        return raw == null ? "" : String.valueOf(raw).replace('\t', ' ').replace('\n', ' ');
    }

    private static String escapeCell(String raw) {
        return raw == null ? "" : raw.replace("|", "\\|").replace("\n", " ");
    }

    private static Function<String, ProofSet> proofAnnotator(FactStore factStore, int k) {
        return atomKey -> ProofSet.singleton(Proof.leaf(atomKey, factScore(factStore, atomKey)), k);
    }

    private static Set<String> edbFactKeys(FactStore factStore) {
        return factStore.allFacts().stream()
                .map(Fact::atomKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String renderReport(UnifiedGraph graph,
                                       FactStore factStore,
                                       List<DatalogRule> rules,
                                       InMemoryInferredFactStore inferredStore,
                                       MaterializationResult materialized,
                                       FixpointResult fixpoint,
                                       AnnotatedResult<Double> viterbi,
                                       AnnotatedResult<Long> counts,
                                       AnnotatedResult<ProofSet> topK,
                                       Atms atms,
                                       WhyNotExplainer.WhyNotReport whyNot,
                                       MarkingResult belnap,
                                       List<ScoredEntity> hybridRanking,
                                       List<ProcessSpottingEval> spottingEvals,
                                       GraphOnlyProcessEvidence graphOnlyEvidence,
                                       ProcessLibraryBundle processLibrary,
                                       RelationNormalizer.Result relationNormalization,
                                       String processGapReport,
                                       Path output) {
        StringBuilder out = new StringBuilder(16_384);
        out.append("# FPNA Deduction Experiment\n\n");
        out.append("Output graph: `").append(output.toAbsolutePath()).append("`\n\n");
        out.append("## Inputs\n\n");
        out.append("- Graph entities: ").append(graph.entities().size()).append('\n');
        out.append("- Graph relations: ").append(graph.relations().size()).append('\n');
        out.append("- Relation normalizations materialized: ")
                .append(relationNormalization.normalizations().size())
                .append(" total, ").append(relationNormalization.flippedCount()).append(" flipped\n");
        out.append("- Observed fact base: ").append(factStore.size())
                .append(" facts (entity types plus non-derived graph relations)\n");
        out.append("- Datalog rules: ").append(rules.size()).append("\n\n");

        out.append("## Forward Chaining Materializer\n\n");
        out.append("- Derived facts stored: ").append(materialized.derivedFactCount()).append('\n');
        out.append("- Rounds completed: ").append(materialized.roundsCompleted()).append('\n');
        out.append("- Reached fixpoint: ").append(materialized.reachedFixpoint()).append('\n');
        if (!materialized.terminationReason().isBlank()) {
            out.append("- Termination reason: ").append(materialized.terminationReason()).append('\n');
        }
        out.append('\n');
        appendSelectedFact(out, inferredStore, SARAH_ATTACHMENT);
        appendSelectedFact(out, inferredStore, AMER_EMAIL_SUBMISSION);
        appendSelectedFact(out, inferredStore, AMER_SENT_TO_MEI);
        appendSelectedFact(out, inferredStore, INTAKE_TO_TB_TIE);
        appendSelectedFact(out, inferredStore, MEI_CONSOLIDATION_RECIPIENT);
        appendSelectedFact(out, inferredStore, J_PARK_CONSOLIDATION_RECIPIENT);
        appendSelectedFact(out, inferredStore, MEI_WORKBOOK_RECIPIENT);

        out.append("\n## Recursive Fixpoint\n\n");
        out.append("- Derived predicates: ").append(fixpoint.derivedFacts().keySet()).append('\n');
        out.append("- Rounds completed: ").append(fixpoint.roundsCompleted()).append('\n');
        out.append("- Complete: ").append(fixpoint.isComplete()).append('\n');
        out.append("- Dropped derivations due to cap: ").append(fixpoint.derivationDropped()).append("\n\n");
        appendDerivations(out, fixpoint, INTAKE_TO_TB_TIE, 6);
        appendDerivations(out, fixpoint, MEI_CONSOLIDATION_RECIPIENT, 6);
        appendDerivations(out, fixpoint, MEI_WORKBOOK_RECIPIENT, 6);

        out.append("\n## Semiring Annotations\n\n");
        appendAnnotation(out, "Viterbi confidence", viterbi.annotation(SARAH_ATTACHMENT), SARAH_ATTACHMENT);
        appendAnnotation(out, "Viterbi confidence", viterbi.annotation(INTAKE_TO_TB_TIE), INTAKE_TO_TB_TIE);
        appendAnnotation(out, "Viterbi confidence", viterbi.annotation(MEI_CONSOLIDATION_RECIPIENT), MEI_CONSOLIDATION_RECIPIENT);
        appendAnnotation(out, "Counting proofs", counts.annotation(MEI_WORKBOOK_RECIPIENT), MEI_WORKBOOK_RECIPIENT);
        appendAnnotation(out, "Counting proofs", counts.annotation(MEI_CONSOLIDATION_RECIPIENT), MEI_CONSOLIDATION_RECIPIENT);
        appendAnnotation(out, "Counting proofs", counts.annotation(INTAKE_TO_TB_TIE), INTAKE_TO_TB_TIE);

        out.append("\n## Top-K Proofs\n\n");
        appendProofs(out, topK, MEI_WORKBOOK_RECIPIENT, 4);
        appendProofs(out, topK, MEI_CONSOLIDATION_RECIPIENT, 4);
        appendProofs(out, topK, INTAKE_TO_TB_TIE, 4);

        out.append("\n## ATMS Labels\n\n");
        appendEnvironments(out, atms, MEI_WORKBOOK_RECIPIENT, 6);
        appendEnvironments(out, atms, MEI_CONSOLIDATION_RECIPIENT, 6);
        appendEnvironments(out, atms, INTAKE_TO_TB_TIE, 6);
        out.append("- Truncated labels: ").append(atms.truncatedLabels()).append("\n");

        out.append("\n## Hybrid Reasoner Ranking\n\n");
        appendHybridRanking(out, graph, hybridRanking, 12);

        out.append("\n## Graph-Only Embedding + MEBN Probe\n\n");
        appendGraphOnlyProcessEvidence(out, graphOnlyEvidence);

        out.append("\n## Process Derivation Audit\n\n");
        ProcessAuditCounts processCounts = auditCounts(goldStepCoverage(graph));
        long spottedComponents = spottingEvals.stream().mapToInt(ProcessSpottingEval::processComponentCount).sum();
        long completeGoldProcesses = completeGoldProcessCount(spottingEvals);
        long partialWeakProcesses = spottingEvals.size() - completeGoldProcesses;
        out.append("- Dedicated artifact: `process-derivation-gap-report.md`\n");
        out.append("- Process generator missing-tools audit: `process-generator-missing-tools-audit.md`\n");
        out.append("- Relation normalization TSV: `relation-normalization.tsv`\n");
        out.append("- Entailment eval TSV: `process-entailment-spotting-eval.tsv`\n");
        out.append("- Process library suggestions artifact: `").append(ProcessUnifiedGraphArtifacts.SUGGESTIONS_JSON)
                .append("` (").append(processLibrary.suggestions().size()).append(" suggestion(s))\n");
        out.append("- Process library definitions artifact: `").append(ProcessUnifiedGraphArtifacts.DEFINITIONS_JSON)
                .append("` (").append(processLibrary.definitions().size()).append(" definition(s))\n");
        out.append("- Process semantic atoms artifact: `process/semantic-atoms.jsonl` (")
                .append(processLibrary.semanticAtoms().size()).append(" extracted atom(s))\n");
        out.append("- Process state entailment artifact: `process/state-entailment.jsonl` (")
                .append(processLibrary.stateEntailment().states().size()).append(" state fact(s); ")
                .append(processLibrary.stateEntailment().byType(ProcessStateEntailment.StateType.CONTROLLED).size())
                .append(" controlled, ")
                .append(processLibrary.stateEntailment().byType(ProcessStateEntailment.StateType.VALIDATED).size())
                .append(" validated, ")
                .append(processLibrary.stateEntailment().byType(ProcessStateEntailment.StateType.APPROVAL_REQUIRED).size())
                .append(" approval-required, ")
                .append(processLibrary.stateEntailment().byType(ProcessStateEntailment.StateType.APPROVED).size())
                .append(" approved, ")
                .append(processLibrary.stateEntailment().byType(ProcessStateEntailment.StateType.ESCALATION_REQUIRED).size())
                .append(" escalation, ")
                .append(processLibrary.stateEntailment().byType(ProcessStateEntailment.StateType.SLA_GOVERNED).size())
                .append(" SLA, ")
                .append(processLibrary.stateEntailment().byType(ProcessStateEntailment.StateType.SLA_BREACH).size())
                .append(" SLA-breach, ")
                .append(processLibrary.stateEntailment().byType(ProcessStateEntailment.StateType.REMEDIATION_REQUIRED).size())
                .append(" remediation, ")
                .append(processLibrary.stateEntailment().byType(ProcessStateEntailment.StateType.REMEDIATION_SEVERITY).size())
                .append(" remediation-severity, ")
                .append(processLibrary.stateEntailment().byType(ProcessStateEntailment.StateType.BLOCKED).size())
                .append(" blocked, ")
                .append(processLibrary.stateEntailment().byType(ProcessStateEntailment.StateType.READY).size())
                .append(" ready, ")
                .append(processLibrary.stateEntailment().byType(ProcessStateEntailment.StateType.CONTROL_EFFECT).size())
                .append(" control-effect)\n");
        out.append("- Process reasoning trace models: `").append(ProcessUnifiedGraphArtifacts.TRACE_MODEL_PREFIX)
                .append("*` (one per emitted suggestion)\n");
        out.append("- Process components spotted with `ProcessEntailment`: ").append(spottedComponents)
                .append(" across ").append(spottingEvals.size()).append(" graph-derived candidate logs\n");
        out.append("- Graph-only embedding+MEBN validation candidates promoted: ")
                .append(graphOnlyEvidence.promotedCount()).append(" of ")
                .append(graphOnlyEvidence.candidateCount()).append('\n');
        out.append("- Canonical relation rewrites materialized before entailment: ")
                .append(relationNormalization.normalizations().size())
                .append(" total, ").append(relationNormalization.flippedCount()).append(" flipped\n");
        out.append("- Documented close-step coverage: ")
                .append(processCounts.representedSteps()).append(" represented, ")
                .append(processCounts.partialSteps()).append(" partial, ")
                .append(processCounts.missingSteps()).append(" missing steps\n");
        out.append("- Strictly complete gold/crawl processes spotted: ").append(completeGoldProcesses)
                .append("; partial/weak candidates spotted: ").append(partialWeakProcesses).append('\n');
        out.append("- Process audit artifact size: ").append(processGapReport.length()).append(" characters\n");

        out.append("\n## Why-Not Explanation\n\n");
        out.append("Claim: `").append(whyNot.claimAtom()).append("`\n\n");
        if (whyNot.nearMisses().isEmpty()) {
            out.append("No near misses found.\n");
        } else {
            int i = 1;
            for (WhyNotExplainer.NearMiss nearMiss : whyNot.nearMisses().stream().limit(4).toList()) {
                out.append(i++).append(". missing=").append(nearMiss.missingAtoms())
                        .append(", satisfied=").append(nearMiss.satisfiedAtoms())
                        .append(", completingFact=").append(nearMiss.completingFact())
                        .append(", closeness=").append(String.format("%.3f", nearMiss.closeness()))
                        .append('\n');
            }
            out.append("Suggestions: ").append(whyNot.suggestions()).append("\n");
        }

        out.append("\n## Belnap Inconsistency Marking\n\n");
        out.append("Injected a manual refutation for `").append(SARAH_ATTACHMENT)
                .append("` to exercise paraconsistent marking.\n\n");
        out.append("- Strong evidence atoms: ").append(belnap.strongEvidenceCount()).append('\n');
        out.append("- B-marked conflicts: ").append(belnap.bCount()).append('\n');
        belnap.byCanonicalAtom().entrySet().stream()
                .filter(e -> e.getValue() == Mark.B)
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> out.append("- ").append(e.getKey()).append(" -> ").append(e.getValue()).append('\n'));

        out.append("\n## First Derived Facts\n\n");
        inferredStore.allLatest().stream()
                .sorted(Comparator.comparing(InferredFact::atomKey))
                .limit(30)
                .forEach(f -> out.append("- `").append(f.atomKey()).append("` confidence=")
                        .append(String.format("%.4f", f.confidence()))
                        .append(" supports=").append(f.supportingFactKeys())
                        .append('\n'));
        return out.toString();
    }

    private static void appendHybridRanking(StringBuilder out,
                                            UnifiedGraph graph,
                                            List<ScoredEntity> ranking,
                                            int limit) {
        Map<String, GraphEntity> entities = entitiesById(graph);
        out.append("- Class: `HybridReasoner`\n");
        out.append("- Structural engine: `PSL`\n");
        out.append("- Embedding layer: `").append(FPNA_DOMAIN_LAYER).append("`\n");
        out.append("- Weights: structural=0.55, semantic=0.45\n");
        out.append("- Dimensions: ").append(FPNA_DOMAIN_DIMENSIONS).append('\n');
        out.append("- Query vector: ").append(formatVector(CONSOLIDATION_QUERY)).append("\n\n");
        out.append("Top entities:\n");
        for (int i = 0; i < Math.min(limit, ranking.size()); i++) {
            ScoredEntity scored = ranking.get(i);
            GraphEntity entity = entities.get(scored.entityId());
            out.append("- #").append(i + 1)
                    .append(" `").append(scored.entityId()).append("`");
            if (entity != null) {
                out.append(" ").append(entity.label()).append(" [").append(entity.type()).append(']');
            }
            out.append(" score=").append(String.format("%.4f", scored.score()))
                    .append(" structural=").append(String.format("%.4f", scored.structuralScore()))
                    .append(" semantic=").append(String.format("%.4f", scored.semanticScore()))
                    .append('\n');
        }
    }

    private static Map<String, GraphEntity> entitiesById(UnifiedGraph graph) {
        Map<String, GraphEntity> entities = new LinkedHashMap<>();
        for (GraphEntity entity : graph.entities()) {
            entities.put(entity.id(), entity);
        }
        return entities;
    }

    private static String formatVector(double[] vector) {
        List<String> values = new ArrayList<>(vector.length);
        for (double value : vector) {
            values.add(String.format("%.2f", value));
        }
        return values.toString();
    }

    private static void appendSelectedFact(StringBuilder out, InMemoryInferredFactStore store, String atomKey) {
        out.append("- `").append(atomKey).append("`: ");
        store.latest(atomKey).ifPresentOrElse(
                fact -> out.append("confidence=").append(String.format("%.4f", fact.confidence()))
                        .append(", supports=").append(fact.supportingFactKeys())
                        .append(", rules=").append(fact.supportingRuleIds()),
                () -> out.append("not materialized"));
        out.append('\n');
    }

    private static void appendDerivations(StringBuilder out, FixpointResult fixpoint, String atomKey, int limit) {
        out.append("Derivations for `").append(atomKey).append("`:\n");
        List<Derivation> derivations = fixpoint.derivations(atomKey);
        if (derivations.isEmpty()) {
            out.append("- none\n");
            return;
        }
        derivations.stream().limit(limit).forEach(d -> out.append("- ")
                .append(d.ruleDisplay())
                .append(" supports ")
                .append(d.parentAtomKeys())
                .append('\n'));
    }

    private static <T> void appendAnnotation(StringBuilder out, String label, T value, String atomKey) {
        out.append("- ").append(label).append(" for `").append(atomKey).append("`: ").append(value).append('\n');
    }

    private static void appendProofs(StringBuilder out, AnnotatedResult<ProofSet> topK, String atomKey, int limit) {
        List<Proof> proofs = topK.proofs(atomKey, limit);
        out.append("Proofs for `").append(atomKey).append("` (fragility ratio=")
                .append(String.format("%.3f", ProofFragility.secondBestRatio(proofs)))
                .append("):\n");
        if (proofs.isEmpty()) {
            out.append("- none\n");
            return;
        }
        int i = 1;
        for (Proof proof : proofs) {
            out.append("- #").append(i++)
                    .append(" score=").append(String.format("%.4f", proof.score()))
                    .append(" leaves=").append(proof.leafFactKeys())
                    .append('\n');
        }
    }

    private static void appendEnvironments(StringBuilder out, Atms atms, String atomKey, int limit) {
        Set<Environment> label = atms.label(atomKey);
        out.append("Minimal support environments for `").append(atomKey).append("`: ")
                .append(label.size()).append('\n');
        if (label.isEmpty()) {
            out.append("- none\n");
            return;
        }
        label.stream()
                .sorted(Comparator.comparingInt(Environment::size).thenComparing(Environment::toString))
                .limit(limit)
                .forEach(env -> out.append("- ").append(env.assumptions()).append('\n'));
    }

    private static double factScore(FactStore factStore, String atomKey) {
        return factStore.factFor(atomKey).map(Fact::value).orElse(1.0);
    }

    private static DatalogRule rule(String head, List<String> headArgs, RuleAtom... body) {
        return new DatalogRule(head, headArgs, List.of(body));
    }

    private static RuleAtom pos(String predicate, String... args) {
        return RuleAtom.pos(predicate, args);
    }

    private static String atom(String predicate, String... args) {
        if (args.length == 0) {
            return predicate;
        }
        return predicate + "(" + String.join(", ", args) + ")";
    }

    private static double clamp(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static String normalizeType(String raw) {
        return lowerCamel(raw == null || raw.isBlank() ? "unknown" : raw);
    }

    private static String lowerCamel(String raw) {
        String[] parts = raw.toLowerCase().split("[^a-z0-9]+");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isBlank()) {
                continue;
            }
            if (out.isEmpty()) {
                out.append(part);
            } else {
                out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return out.isEmpty() ? "unknown" : out.toString();
    }
}
