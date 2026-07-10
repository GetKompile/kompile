package ai.kompile.samples.fpna;

import ai.kompile.graph.reasoning.embedding.learn.EmbeddingConfig;
import ai.kompile.graph.reasoning.embedding.learn.Node2VecLearner;
import ai.kompile.graph.reasoning.hybrid.HybridReasoner;
import ai.kompile.graph.reasoning.learning.HybridConsensusTrainer;
import ai.kompile.graph.reasoning.learning.PslWeightLearningService;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.graph.reasoning.psl.GraphPslProgramBuilder;
import ai.kompile.graph.reasoning.psl.PslProgram;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.graph.reasoning.unified.UnifiedGraphFormat;
import ai.kompile.process.discovery.mining.ReasoningGraphProcessGenerator;
import ai.kompile.process.discovery.mining.entail.ProcessEntailmentResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Runs production graph-only process generation and evaluates its output against the FP&A gold
 * process inventory. Gold concepts are used only after generation.
 */
public final class FpnaBusinessProcessGeneration {

    private static final String REPORT_ARTIFACT = "process-generation-report.md";
    private static final String COVERAGE_ARTIFACT = "process-generation-coverage.tsv";
    private static final String CANDIDATES_ARTIFACT = "process-generation-candidates.tsv";

    private FpnaBusinessProcessGeneration() {
    }

    public static void main(String[] args) throws Exception {
        UnifiedGraph graph = FpnaKGraphSample.buildGraph();

        // Both passes see only the graph. The gold rubric is constructed after generation.
        ReasoningGraphProcessGenerator.Result baseline =
                ReasoningGraphProcessGenerator.generate(graph);
        LearningRun learning = learnGraphContext(graph);
        ReasoningGraphProcessGenerator.Result generated =
                ReasoningGraphProcessGenerator.generate(graph, learning.reasoningGraph());
        List<GoldProcess> gold = goldProcesses();
        List<ProcessMatch> baselineMatches = assignUniqueMatches(gold, baseline.candidates(), graph);
        List<ProcessMatch> matches = assignUniqueMatches(gold, generated.candidates(), graph);

        String coverageTsv = renderCoverageTsv(matches);
        String candidatesTsv = renderCandidatesTsv(generated.candidates());
        String report = renderReport(generated, matches, baseline, baselineMatches, learning);

        generated.putArtifacts(graph);
        graph.putArtifactText(REPORT_ARTIFACT, report);
        graph.putArtifactText(COVERAGE_ARTIFACT, coverageTsv);
        graph.putArtifactText(CANDIDATES_ARTIFACT, candidatesTsv);

        Path output = outputPath(args);
        Files.createDirectories(output.toAbsolutePath().getParent());
        graph.save(output);
        Files.writeString(output.resolveSibling("fpna-process-generation-report.md"),
                report, StandardCharsets.UTF_8);
        Files.writeString(output.resolveSibling("fpna-process-generation-coverage.tsv"),
                coverageTsv, StandardCharsets.UTF_8);
        Files.writeString(output.resolveSibling("fpna-process-generation-candidates.tsv"),
                candidatesTsv, StandardCharsets.UTF_8);

        long spotted = matches.stream().filter(match -> match.status() == Status.SPOTTED).count();
        long partial = matches.stream().filter(match -> match.status() == Status.PARTIAL).count();
        long missed = matches.stream().filter(match -> match.status() == Status.MISSED).count();
        System.out.printf(
                "FP&A automatic process coverage: %d/%d spotted, %d partial, %d missed; "
                        + "%d ranked candidates from %d graph projections.%n",
                spotted, gold.size(), partial, missed,
                generated.candidates().size(), generated.projectionCount());
        System.out.printf(
                "Embedding learning: %d/%d primary entities -> %d/%d learned; semantic candidates "
                        + "%d/%d -> %d/%d; semantic gold matches %d/%d -> %d/%d.%n",
                learning.primaryEmbeddedEntities(), generated.graphEntityCount(),
                learning.learnedEmbeddedEntities(), generated.graphEntityCount(),
                semanticCandidates(baseline), baseline.candidates().size(),
                semanticCandidates(generated), generated.candidates().size(),
                semanticMatches(baselineMatches), baselineMatches.size(),
                semanticMatches(matches), matches.size());
        for (ProcessMatch match : matches) {
            System.out.printf("%-8s concepts=%5.1f%% order=%5.1f%% candidate=#%02d %s%n",
                    match.status(),
                    100.0 * match.conceptRecall(),
                    100.0 * match.orderRecall(),
                    match.candidate().rank(),
                    match.candidate().suggestion().getName());
        }
        System.out.println("Saved graph and evaluation to " + output.toAbsolutePath());
    }

    private static LearningRun learnGraphContext(UnifiedGraph graph) {
        int primaryEmbedded = (int) graph.entities().stream().filter(GraphEntity::hasEmbedding).count();
        GraphPslProgramBuilder builder = new GraphPslProgramBuilder();
        PslProgram program = builder.build(graph);
        Map<String, Double> observed = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : builder.entityIdToConstant().entrySet()) {
            GraphEntity entity = graph.entity(entry.getKey()).orElseThrow();
            observed.put(GraphPslProgramBuilder.STATE + "(" + entry.getValue() + ")",
                    clamp(entity.confidence()));
        }

        EmbeddingConfig embeddingConfig = new EmbeddingConfig(
                32, 12, 4, 4, 3, 1.0, 1.0, 2, 0.025, 1234L);
        HybridConsensusTrainer.Plan plan = new HybridConsensusTrainer.Plan(
                new PslWeightLearningService(), 1,
                false, null, null, null, 0,
                true, new Node2VecLearner(), embeddingConfig,
                new HybridReasoner(), 0.35, 1,
                builder.constantToEntityId(), null);
        HybridConsensusTrainer.Result result = HybridConsensusTrainer.train(
                graph, program, observed, plan);
        ReasoningGraph reasoningGraph = result.embeddingLayer() == null
                ? graph : graph.withEmbeddingLayer(result.embeddingLayer());
        int learnedEmbedded = (int) reasoningGraph.entities().stream()
                .filter(GraphEntity::hasEmbedding)
                .count();
        graph.meta("processLearning.embeddingLayer", result.embeddingLayer());
        graph.meta("processLearning.semanticConsensus", result.semanticConsensus());
        graph.meta("processLearning.semanticAnchors", result.semanticAnchorCount());
        graph.meta("processLearning.modelsTrained", result.modelsTrained());
        return new LearningRun(reasoningGraph, result, primaryEmbedded, learnedEmbedded,
                embeddingConfig);
    }

    private static Path outputPath(String[] args) {
        if (args.length > 0 && !args[0].isBlank()) {
            return Path.of(args[0]);
        }
        String baseDir = System.getProperty("fpna.sample.basedir", System.getProperty("user.dir"));
        return Path.of(baseDir, "target",
                "fpna-business-process-generation" + UnifiedGraphFormat.EXTENSION);
    }

    private static List<ProcessMatch> assignUniqueMatches(
            List<GoldProcess> gold,
            List<ReasoningGraphProcessGenerator.Candidate> candidates,
            UnifiedGraph graph) {
        List<ProcessMatch> scored = new ArrayList<>();
        for (GoldProcess expected : gold) {
            for (ReasoningGraphProcessGenerator.Candidate candidate : candidates) {
                scored.add(score(expected, candidate, graph));
            }
        }
        scored.sort(Comparator.comparingDouble(ProcessMatch::evaluationScore).reversed()
                .thenComparing(Comparator.comparingDouble(ProcessMatch::conceptRecall).reversed())
                .thenComparingInt(match -> match.candidate().rank())
                .thenComparing(match -> match.gold().name()));

        Map<String, ProcessMatch> assigned = new LinkedHashMap<>();
        Set<String> usedCandidates = new HashSet<>();
        for (ProcessMatch match : scored) {
            if (!assigned.containsKey(match.gold().name())
                    && usedCandidates.add(match.candidate().id())) {
                assigned.put(match.gold().name(), match);
            }
        }

        List<ProcessMatch> ordered = new ArrayList<>();
        for (GoldProcess expected : gold) {
            ProcessMatch match = assigned.get(expected.name());
            if (match == null) {
                throw new IllegalStateException("No candidate available for " + expected.name());
            }
            ordered.add(match);
        }
        return List.copyOf(ordered);
    }

    private static ProcessMatch score(
            GoldProcess gold,
            ReasoningGraphProcessGenerator.Candidate candidate,
            UnifiedGraph graph) {
        CandidateCorpus corpus = corpus(candidate, graph);
        Set<String> matched = gold.concepts().stream()
                .filter(concept -> matchesConcept(corpus.texts(), concept))
                .map(Concept::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        double conceptRecall = ratio(matched.size(), gold.concepts().size());

        Set<String> matchedOrders = new LinkedHashSet<>();
        Set<String> entailedOnlyOrders = new LinkedHashSet<>();
        List<ProcessEntailmentResult.EntailedPrecedence> accepted =
                candidate.entailment().assertable(0.5);
        for (OrderExpectation order : gold.orders()) {
            Concept from = gold.concept(order.fromConcept());
            Concept to = gold.concept(order.toConcept());
            for (ProcessEntailmentResult.EntailedPrecedence precedence : accepted) {
                if (matchesConcept(List.of(precedence.from()), from)
                        && matchesConcept(List.of(precedence.to()), to)) {
                    String key = order.fromConcept() + "->" + order.toConcept();
                    matchedOrders.add(key);
                    if (!precedence.observed()) {
                        entailedOnlyOrders.add(key);
                    }
                    break;
                }
            }
        }
        double orderRecall = gold.orders().isEmpty()
                ? 1.0 : ratio(matchedOrders.size(), gold.orders().size());

        double scopeLimit = Math.max(2.0, gold.concepts().size() * 1.75);
        double scopePenalty = candidate.activityCount() <= scopeLimit
                ? 0.0
                : (candidate.activityCount() - scopeLimit) / candidate.activityCount();
        double hybrid = candidate.hybridActivation().meanHybrid();
        double entailmentExpectation = candidate.entailment().isEmpty()
                ? 0.0 : candidate.entailment().fusedOpinion().expectation();

        double evaluationScore;
        if (gold.orders().isEmpty()) {
            evaluationScore = 0.70 * conceptRecall
                    + 0.15 * candidate.score()
                    + 0.15 * hybrid
                    - 0.20 * scopePenalty;
        } else {
            evaluationScore = 0.55 * conceptRecall
                    + 0.25 * orderRecall
                    + 0.10 * candidate.score()
                    + 0.10 * hybrid
                    - 0.15 * scopePenalty;
        }
        evaluationScore = clamp(evaluationScore);

        boolean entailmentPresent = !accepted.isEmpty() && entailmentExpectation >= 0.50;
        boolean orderSufficient = gold.orders().isEmpty() || orderRecall >= 0.40;
        Status status;
        if (conceptRecall >= 0.60 && orderSufficient && scopePenalty <= 0.55
                && entailmentPresent) {
            status = Status.SPOTTED;
        } else if (conceptRecall >= 0.30 || evaluationScore >= 0.45) {
            status = Status.PARTIAL;
        } else {
            status = Status.MISSED;
        }

        List<String> missing = gold.concepts().stream()
                .filter(concept -> !matched.contains(concept.id()))
                .map(Concept::label)
                .toList();
        return new ProcessMatch(gold, candidate, status, conceptRecall, orderRecall,
                scopePenalty, evaluationScore, entailmentExpectation,
                List.copyOf(matched), List.copyOf(matchedOrders),
                List.copyOf(entailedOnlyOrders), missing);
    }

    private static CandidateCorpus corpus(
            ReasoningGraphProcessGenerator.Candidate candidate,
            UnifiedGraph graph) {
        Map<String, GraphRelation> relations = graph.relations().stream()
                .collect(Collectors.toMap(GraphRelation::id, Function.identity()));
        List<String> texts = new ArrayList<>();
        texts.add(candidate.suggestion().getName());
        texts.addAll(candidate.eventLog().activityNames());

        for (String entityId : candidate.evidenceEntityIds()) {
            graph.entity(entityId).ifPresent(entity ->
                    texts.add(entity.label() + " " + entity.type() + " "
                            + entity.tags() + " " + entity.attributes()));
        }
        for (String relationId : candidate.evidenceRelationIds()) {
            GraphRelation relation = relations.get(relationId);
            if (relation == null) {
                continue;
            }
            String source = graph.entity(relation.sourceId()).map(GraphEntity::label).orElse("");
            String target = graph.entity(relation.targetId()).map(GraphEntity::label).orElse("");
            texts.add(source + " " + relation.type() + " " + target + " "
                    + relation.tags() + " " + relation.attributes());
        }
        return new CandidateCorpus(texts.stream()
                .filter(text -> text != null && !text.isBlank())
                .map(FpnaBusinessProcessGeneration::normalize)
                .toList());
    }

    private static boolean matchesConcept(List<String> normalizedTexts, Concept concept) {
        for (String alias : concept.aliases()) {
            Set<String> expectedTokens = tokens(alias);
            if (expectedTokens.isEmpty()) {
                continue;
            }
            for (String text : normalizedTexts) {
                if (tokens(text).containsAll(expectedTokens)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Set<String> tokens(String text) {
        String normalized = normalize(text);
        if (normalized.isBlank()) {
            return Set.of();
        }
        return new LinkedHashSet<>(java.util.Arrays.asList(normalized.split(" ")));
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT)
                .replace("&", " and ")
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll(" +", " ");
    }

    private static String renderReport(
            ReasoningGraphProcessGenerator.Result generated,
            List<ProcessMatch> matches,
            ReasoningGraphProcessGenerator.Result baseline,
            List<ProcessMatch> baselineMatches,
            LearningRun learning) {
        long spotted = matches.stream().filter(match -> match.status() == Status.SPOTTED).count();
        long partial = matches.stream().filter(match -> match.status() == Status.PARTIAL).count();
        long missed = matches.stream().filter(match -> match.status() == Status.MISSED).count();
        long baselineSpotted = baselineMatches.stream()
                .filter(match -> match.status() == Status.SPOTTED).count();
        long derivedOrders = matches.stream().mapToLong(match -> match.entailedOnlyOrders().size()).sum();

        StringBuilder out = new StringBuilder();
        out.append("# FP&A Graph-Only Business Process Generation Evaluation\n\n");
        out.append("Generation ran before the nine-process gold rubric was created. The production ")
                .append("generator received only the UnifiedGraph and used topology projection, relation-event ")
                .append("projection, joint PSL/embedding learning, process entailment, Inductive Miner, ")
                .append("causal analysis, and HybridReasoner. ")
                .append("Gold concepts are sample-only post-generation evaluation data.\n\n");
        out.append("## Summary\n\n");
        out.append("- Graph: ").append(generated.graphEntityCount()).append(" entities, ")
                .append(generated.graphRelationCount()).append(" relations\n");
        out.append("- Projections: ").append(generated.projectionCount())
                .append(" attempted, ").append(generated.rejectedProjectionCount()).append(" rejected\n");
        out.append("- Ranked generated candidates: ").append(generated.candidates().size()).append("\n");
        out.append("- Primary graph embedding coverage: ")
                .append(learning.primaryEmbeddedEntities()).append("/")
                .append(generated.graphEntityCount()).append(" entities\n");
        out.append("- Learned layer `").append(learning.result().embeddingLayer()).append("`: ")
                .append(learning.learnedEmbeddedEntities()).append("/")
                .append(generated.graphEntityCount()).append(" entities, dimension ")
                .append(learning.embeddingConfig().dim()).append(", semantic anchors ")
                .append(learning.result().semanticAnchorCount()).append("\n");
        out.append("- Joint models trained: ").append(learning.result().modelsTrained())
                .append(" (PSL + graph embeddings; semantic consensus=")
                .append(learning.result().semanticConsensus()).append(")\n");
        out.append("- Semantic candidate engagement: **")
                .append(semanticCandidates(baseline)).append("/")
                .append(baseline.candidates().size()).append(" baseline -> ")
                .append(semanticCandidates(generated)).append("/")
                .append(generated.candidates().size()).append(" learned**\n");
        out.append("- Semantic engagement among gold matches: **")
                .append(semanticMatches(baselineMatches)).append("/")
                .append(baselineMatches.size()).append(" baseline -> ")
                .append(semanticMatches(matches)).append("/")
                .append(matches.size()).append(" learned**\n");
        out.append("- Baseline gold process recall: ")
                .append(percent(ratio(baselineSpotted, baselineMatches.size()))).append("\n");
        out.append("- Gold process coverage using accepted entailment: **")
                .append(spotted).append("/").append(matches.size()).append(" spotted**, ")
                .append(partial).append(" partial, ").append(missed).append(" missed\n");
        out.append("- Process recall: **").append(percent(ratio(spotted, matches.size()))).append("**\n");
        out.append("- Gold order expectations supported only by derived precedence: ")
                .append(derivedOrders).append("\n\n");

        out.append("A process is 'SPOTTED' only when a uniquely assigned generated candidate covers at ")
                .append("least 60% of its gold concepts, has an accepted entailment model, is not an oversized ")
                .append("catch-all, and supports at least 40% of expected order constraints when the gold process ")
                .append("is sequential. 'PARTIAL' requires at least 30% concept recall or a 0.45 fused eval score.\n\n");

        out.append("## Coverage\n\n");
        out.append("| Gold process | Result | Generated candidate | View | Concepts | Entailed order | ")
                .append("Accepted / derived | Hybrid | Mode | Embedded | Source | Eval |\n");
        out.append("|---|---:|---|---|---:|---:|---:|---:|---|---:|---|---:|\n");
        for (ProcessMatch match : matches) {
            ReasoningGraphProcessGenerator.Candidate candidate = match.candidate();
            out.append("| ").append(match.gold().name())
                    .append(" | ").append(match.status())
                    .append(" | #").append(candidate.rank()).append(" ")
                    .append(escapeMarkdown(candidate.suggestion().getName()))
                    .append(" | ").append(candidate.projection()).append("/")
                    .append(candidate.family())
                    .append(" | ").append(percent(match.conceptRecall()))
                    .append(" | ").append(percent(match.orderRecall()))
                    .append(" | ").append(candidate.entailment().accepted().size())
                    .append(" / ").append(candidate.entailedOnlyCount())
                    .append(" | ").append(decimal(candidate.hybridActivation().meanHybrid()))
                    .append(" | ").append(hybridMode(candidate))
                    .append(" | ").append(candidate.hybridActivation().embeddedActivityCount())
                    .append("/").append(candidate.activityCount())
                    .append(" | ").append(embeddingSource(candidate))
                    .append(" | ").append(decimal(match.evaluationScore()))
                    .append(" |\n");
        }

        out.append("\n## Remaining Gaps\n\n");
        for (ProcessMatch match : matches) {
            if (match.status() == Status.SPOTTED) {
                continue;
            }
            out.append("- **").append(match.gold().name()).append("**: ")
                    .append(match.status()).append("; missing concepts: ")
                    .append(match.missingConcepts().isEmpty()
                            ? "none, but candidate scope/order was insufficient"
                            : String.join(", ", match.missingConcepts()))
                    .append(".\n");
        }
        if (partial == 0 && missed == 0) {
            out.append("- None under this rubric. Candidate precision and duplicate granularity still need ")
                    .append("separate evaluation before production acceptance.\n");
        }

        out.append("\n## Ranked Candidates\n\n");
        out.append("| Rank | Score | Name | Projection | Family | Traces | Activities | Accepted | Derived | Hybrid | Mode | Embedded | Direct / resolved |\n");
        out.append("|---:|---:|---|---|---|---:|---:|---:|---:|---:|---|---:|---:|\n");
        for (ReasoningGraphProcessGenerator.Candidate candidate : generated.candidates()) {
            out.append("| ").append(candidate.rank())
                    .append(" | ").append(decimal(candidate.score()))
                    .append(" | ").append(escapeMarkdown(candidate.suggestion().getName()))
                    .append(" | ").append(candidate.projection())
                    .append(" | ").append(candidate.family())
                    .append(" | ").append(candidate.traceCount())
                    .append(" | ").append(candidate.activityCount())
                    .append(" | ").append(candidate.entailment().accepted().size())
                    .append(" | ").append(candidate.entailedOnlyCount())
                    .append(" | ").append(decimal(candidate.hybridActivation().meanHybrid()))
                    .append(" | ").append(hybridMode(candidate))
                    .append(" | ").append(candidate.hybridActivation().embeddedActivityCount())
                    .append("/").append(candidate.activityCount())
                    .append(" | ").append(directEmbeddingCount(candidate)).append(" / ")
                    .append(inferredEmbeddingCount(candidate))
                    .append(" |\n");
        }
        return out.toString();
    }

    private static String renderCoverageTsv(List<ProcessMatch> matches) {
        StringBuilder out = new StringBuilder();
        out.append("gold_process\tstatus\tcandidate_rank\tcandidate_name\tprojection\tfamily")
                .append("\tconcept_recall\torder_recall\tscope_penalty\tevaluation_score")
                .append("\tentailment_expectation\taccepted_precedence\tentailed_only_precedence")
                .append("\thybrid\thybrid_mode\tembedded_activities\tembedding_source")
                .append("\tdirect_embeddings\tresolved_embeddings")
                .append("\tmatched_concepts\tmatched_orders\tentailed_only_orders\tmissing_concepts\n");
        for (ProcessMatch match : matches) {
            ReasoningGraphProcessGenerator.Candidate candidate = match.candidate();
            out.append(tsv(match.gold().name())).append('\t')
                    .append(match.status()).append('\t')
                    .append(candidate.rank()).append('\t')
                    .append(tsv(candidate.suggestion().getName())).append('\t')
                    .append(candidate.projection()).append('\t')
                    .append(candidate.family()).append('\t')
                    .append(decimal(match.conceptRecall())).append('\t')
                    .append(decimal(match.orderRecall())).append('\t')
                    .append(decimal(match.scopePenalty())).append('\t')
                    .append(decimal(match.evaluationScore())).append('\t')
                    .append(decimal(match.entailmentExpectation())).append('\t')
                    .append(candidate.entailment().accepted().size()).append('\t')
                    .append(candidate.entailedOnlyCount()).append('\t')
                    .append(decimal(candidate.hybridActivation().meanHybrid())).append('\t')
                    .append(hybridMode(candidate)).append('\t')
                    .append(candidate.hybridActivation().embeddedActivityCount()).append('\t')
                    .append(tsv(embeddingSource(candidate))).append('\t')
                    .append(directEmbeddingCount(candidate)).append('\t')
                    .append(inferredEmbeddingCount(candidate)).append('\t')
                    .append(tsv(String.join("|", match.matchedConcepts()))).append('\t')
                    .append(tsv(String.join("|", match.matchedOrders()))).append('\t')
                    .append(tsv(String.join("|", match.entailedOnlyOrders()))).append('\t')
                    .append(tsv(String.join("|", match.missingConcepts()))).append('\n');
        }
        return out.toString();
    }

    private static String renderCandidatesTsv(
            List<ReasoningGraphProcessGenerator.Candidate> candidates) {
        StringBuilder out = new StringBuilder();
        out.append("rank\tid\tname\tscore\tprojection\tfamily\ttraces\tactivities")
                .append("\taccepted_precedence\tentailed_only_precedence\thybrid")
                .append("\tpsl\tbayesian\tsemantic\thybrid_mode\tembedded_activities")
                .append("\tembedding_source\tdirect_embeddings\tresolved_embeddings\tactivity_labels")
                .append("\tevidence_entities\tevidence_relations\n");
        for (ReasoningGraphProcessGenerator.Candidate candidate : candidates) {
            out.append(candidate.rank()).append('\t')
                    .append(tsv(candidate.id())).append('\t')
                    .append(tsv(candidate.suggestion().getName())).append('\t')
                    .append(decimal(candidate.score())).append('\t')
                    .append(candidate.projection()).append('\t')
                    .append(candidate.family()).append('\t')
                    .append(candidate.traceCount()).append('\t')
                    .append(candidate.activityCount()).append('\t')
                    .append(candidate.entailment().accepted().size()).append('\t')
                    .append(candidate.entailedOnlyCount()).append('\t')
                    .append(decimal(candidate.hybridActivation().meanHybrid())).append('\t')
                    .append(decimal(candidate.hybridActivation().meanPsl())).append('\t')
                    .append(decimal(candidate.hybridActivation().meanBayesian())).append('\t')
                    .append(decimal(candidate.hybridActivation().meanSemantic())).append('\t')
                    .append(hybridMode(candidate)).append('\t')
                    .append(candidate.hybridActivation().embeddedActivityCount()).append('\t')
                    .append(tsv(embeddingSource(candidate))).append('\t')
                    .append(directEmbeddingCount(candidate)).append('\t')
                    .append(inferredEmbeddingCount(candidate)).append('\t')
                    .append(tsv(candidate.eventLog().activityNames().stream()
                            .sorted().collect(Collectors.joining(" | ")))).append('\t')
                    .append(tsv(String.join(" | ", candidate.evidenceEntityIds()))).append('\t')
                    .append(tsv(String.join(" | ", candidate.evidenceRelationIds()))).append('\n');
        }
        return out.toString();
    }

    private static String hybridMode(ReasoningGraphProcessGenerator.Candidate candidate) {
        return candidate.hybridActivation().semanticEngaged() ? "SEMANTIC_CENTROID" : "STRUCTURAL_ONLY";
    }

    private static long semanticCandidates(ReasoningGraphProcessGenerator.Result generated) {
        return generated.candidates().stream()
                .filter(candidate -> candidate.hybridActivation().semanticEngaged())
                .count();
    }

    private static long semanticMatches(List<ProcessMatch> matches) {
        return matches.stream()
                .filter(match -> match.candidate().hybridActivation().semanticEngaged())
                .count();
    }

    private static String embeddingSource(ReasoningGraphProcessGenerator.Candidate candidate) {
        String source = candidate.suggestion().getHybridReasoning() == null
                ? null : candidate.suggestion().getHybridReasoning().getEmbeddingSource();
        return source == null ? "NONE" : source;
    }

    private static int directEmbeddingCount(ReasoningGraphProcessGenerator.Candidate candidate) {
        return candidate.suggestion().getHybridReasoning() == null ? 0
                : candidate.suggestion().getHybridReasoning().getDirectlyEmbeddedActivityCount();
    }

    private static int inferredEmbeddingCount(ReasoningGraphProcessGenerator.Candidate candidate) {
        return candidate.suggestion().getHybridReasoning() == null ? 0
                : candidate.suggestion().getHybridReasoning().getInferredEmbeddingActivityCount();
    }

    private static List<GoldProcess> goldProcesses() {
        return List.of(
                gold("Monthly FP&A Close & 3-Month P&L Projection",
                        List.of(
                                concept("trial_balance", "netsuite trial balance", "trial balance ar ap"),
                                concept("channel_revenue", "shopify amazon channel revenue", "channel revenue"),
                                concept("fx_curve", "fx rates forward curve", "fx forward curve"),
                                concept("regional_workbooks", "regional forecast workbooks", "regional forecast"),
                                concept("validate_triage", "validate forecast triage variances", "forecast triage"),
                                concept("pipeline_reconcile", "reconcile forecast salesforce pipeline", "pipeline coverage"),
                                concept("fx_translate", "fx translate usd", "translate forecast usd"),
                                concept("project", "project cogs opex", "3 mo p l projection"),
                                concept("consolidate", "consolidate group p l", "monthly close 3 mo p l"),
                                concept("commentary", "draft variance commentary", "variance commentary"),
                                concept("cfo_signoff", "cfo sign off", "projection sign off"),
                                concept("publish", "publish dashboard", "looker dashboard")),
                        List.of(
                                order("trial_balance", "validate_triage"),
                                order("regional_workbooks", "validate_triage"),
                                order("validate_triage", "pipeline_reconcile"),
                                order("pipeline_reconcile", "fx_translate"),
                                order("fx_translate", "project"),
                                order("project", "consolidate"),
                                order("consolidate", "commentary"),
                                order("commentary", "cfo_signoff"),
                                order("cfo_signoff", "publish"))),
                gold("Variance Triage & Auto-Correction",
                        List.of(
                                concept("validation", "validate forecast triage", "tie check"),
                                concept("miscoded_sku", "miscoded sku pattern", "sku mapping triage"),
                                concept("channel_mismatch", "channel mismatch pattern", "channel mismatch triage"),
                                concept("currency_tag", "currency tag mismatch", "currency tag triage"),
                                concept("calendar_shift", "calendar shift pattern"),
                                concept("unknown", "other unknown pattern", "unknown variance"),
                                concept("auto_fix", "auto fix batch", "auto correction batch"),
                                concept("escalation", "escalated to", "escalate channel mismatch")),
                        List.of(
                                order("validation", "miscoded_sku"),
                                order("validation", "channel_mismatch"),
                                order("validation", "currency_tag"),
                                order("validation", "calendar_shift"))),
                gold("Forecast Control Validation Suite (C-01 through C-07)",
                        List.of(
                                concept("c01", "c 01 fx consistency", "c 01 tb tie"),
                                concept("c02", "c 02 regional total"),
                                concept("c03", "c 03 pipeline coverage"),
                                concept("c04", "c 04 sku mapping"),
                                concept("c05", "c 05 channel gm band"),
                                concept("c06", "c 06 sku margin discipline"),
                                concept("c07", "c 07 statement reconciliation")),
                        List.of()),
                gold("Regional Forecast Submission & Normalization",
                        List.of(
                                concept("amer", "amer forecast workbook", "amer forecast"),
                                concept("emea", "emea forecast workbook", "emea forecast"),
                                concept("apac", "apac forecast workbook", "apac forecast"),
                                concept("submission", "submits forecast", "submitted by regional forecast"),
                                concept("attachment", "has attachment spreadsheet", "forecast workbook attachment"),
                                concept("channel_alias", "channel taxonomy", "channel aliases"),
                                concept("sku_translation", "translate sku codes", "sku master"),
                                concept("currency_normalization", "normalize currency tags", "currency registry")),
                        List.of()),
                gold("End-to-End Accounting Close Cycle",
                        List.of(
                                concept("purchase_orders", "procurement po creation", "purchase orders"),
                                concept("goods_receipt", "goods receipt 3pl warehouse", "goods receipts"),
                                concept("sales", "sales transactions shopify amazon edi"),
                                concept("payroll", "payroll cycle workday bank file"),
                                concept("three_way", "three way match po gr invoice"),
                                concept("revenue_close", "revenue close cutoff deferred"),
                                concept("ap_close", "ap close accruals cutoff"),
                                concept("inventory", "inventory close cycle counts wac"),
                                concept("fx_revalue", "fx revaluation treasury oci"),
                                concept("monthly_close", "monthly close 3 mo p l projection"),
                                concept("commentary", "variance commentary auto draft cfo edit"),
                                concept("board_pack", "board pack quarterly"),
                                concept("tax", "tax provision quarterly true up"),
                                concept("external", "external reporting 10 q ir")),
                        List.of(
                                order("purchase_orders", "three_way"),
                                order("goods_receipt", "three_way"),
                                order("three_way", "ap_close"),
                                order("sales", "revenue_close"),
                                order("revenue_close", "monthly_close"),
                                order("ap_close", "monthly_close"),
                                order("inventory", "monthly_close"),
                                order("fx_revalue", "monthly_close"),
                                order("payroll", "monthly_close"),
                                order("monthly_close", "commentary"),
                                order("monthly_close", "board_pack"),
                                order("monthly_close", "tax"),
                                order("commentary", "external"),
                                order("board_pack", "external"))),
                gold("Process Automation Delivery Stack (L0-L5)",
                        List.of(
                                concept("l0", "l0 engagement baseline"),
                                concept("l1", "l1 discovery"),
                                concept("l2", "l2 ontology"),
                                concept("l3", "l3 process model"),
                                concept("l4", "l4 integrations"),
                                concept("l5", "l5 agents")),
                        List.of(
                                order("l0", "l1"),
                                order("l1", "l2"),
                                order("l2", "l3"),
                                order("l3", "l4"),
                                order("l4", "l5"),
                                order("l5", "l2"))),
                gold("Ontology Evolution & Continuous Improvement",
                        List.of(
                                concept("triage_monitor", "triage hit rate drift monitor"),
                                concept("commentary_monitor", "commentary edit rate drift monitor"),
                                concept("rediscovery", "erp migration re discovery trigger", "focused re discovery"),
                                concept("alias_promotion", "auto promote confirmed channel aliases"),
                                concept("exception_rule", "codify recurring marketplace exception", "recurring channel rule"),
                                concept("enum_tighten", "tighten lifecycle status enum"),
                                concept("parent_sku", "promote parent sku formal field"),
                                concept("ontology_patch", "applies adjustment channel taxonomy", "patch ontology")),
                        List.of()),
                gold("HITL Approval Chain for Monthly Projection",
                        List.of(
                                concept("batch", "hitl approve auto correction batch"),
                                concept("opex", "hitl opex attrition assumption review"),
                                concept("mechanics", "hitl close mechanics sign off"),
                                concept("commentary", "hitl variance commentary edit"),
                                concept("projection", "hitl projection sign off")),
                        List.of(
                                order("batch", "opex"),
                                order("opex", "mechanics"),
                                order("mechanics", "commentary"),
                                order("commentary", "projection"))),
                gold("Data Quality Monitoring & Compliance Retention",
                        List.of(
                                concept("auto_fix_budget", "auto fix budget 4 per workbook"),
                                concept("currency_budget", "currency symbol budget 6 per workbook"),
                                concept("price_spread", "cross channel price spread 12 flag"),
                                concept("retention", "audit retention 7 year object lock"),
                                concept("sox", "sox j sox in scope tagging"),
                                concept("pii", "recording consent pii redaction"),
                                concept("liability", "agent liability boundaries")),
                        List.of()));
    }

    private static GoldProcess gold(
            String name, List<Concept> concepts, List<OrderExpectation> orders) {
        return new GoldProcess(name, concepts, orders);
    }

    private static Concept concept(String id, String... aliases) {
        return new Concept(id, id.replace('_', ' '), List.of(aliases));
    }

    private static OrderExpectation order(String from, String to) {
        return new OrderExpectation(from, to);
    }

    private static double ratio(long numerator, long denominator) {
        return denominator == 0 ? 1.0 : (double) numerator / denominator;
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String percent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", 100.0 * value);
    }

    private static String tsv(String value) {
        return value == null ? "" : value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private static String escapeMarkdown(String value) {
        return value == null ? "" : value.replace("|", "\\|");
    }

    private record LearningRun(ReasoningGraph reasoningGraph,
                               HybridConsensusTrainer.Result result,
                               int primaryEmbeddedEntities,
                               int learnedEmbeddedEntities,
                               EmbeddingConfig embeddingConfig) {
    }

    private enum Status {
        SPOTTED,
        PARTIAL,
        MISSED
    }

    private record Concept(String id, String label, List<String> aliases) {
    }

    private record OrderExpectation(String fromConcept, String toConcept) {
    }

    private record GoldProcess(
            String name, List<Concept> concepts, List<OrderExpectation> orders) {

        private Concept concept(String id) {
            return concepts.stream()
                    .filter(concept -> concept.id().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown concept " + id));
        }
    }

    private record CandidateCorpus(List<String> texts) {
    }

    private record ProcessMatch(
            GoldProcess gold,
            ReasoningGraphProcessGenerator.Candidate candidate,
            Status status,
            double conceptRecall,
            double orderRecall,
            double scopePenalty,
            double evaluationScore,
            double entailmentExpectation,
            List<String> matchedConcepts,
            List<String> matchedOrders,
            List<String> entailedOnlyOrders,
            List<String> missingConcepts) {
    }
}
