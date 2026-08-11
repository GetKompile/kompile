/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.crawl.graph;

import ai.kompile.app.llm.pipeline.SameDiffLanguageModelImpl;
import ai.kompile.app.subprocess.SubprocessMemoryWatchdog;
import ai.kompile.core.graphrag.format.GraphExtractionSchema;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedEntity;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult;
import ai.kompile.crawl.graph.passes.ExtractionAdmissionComparator;
import ai.kompile.graph.reasoning.admission.AdmissionComparison;
import ai.kompile.graph.reasoning.admission.AdmissionDecision;
import ai.kompile.graph.reasoning.admission.AdmissionEvidence;
import ai.kompile.graph.reasoning.admission.AdmissionMode;
import ai.kompile.graph.reasoning.admission.GraphOperationalPolicy;
import ai.kompile.graph.reasoning.admission.GraphOperationalVerdict;
import ai.kompile.graph.reasoning.admission.OperationalDisposition;
import ai.kompile.graph.reasoning.hybrid.HybridReasoner;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.SimpleGraphRelation;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.deeplearning4j.llm.generation.ChatGenerationResult;
import org.eclipse.deeplearning4j.llm.tokenizer.ChatTemplate;
import org.junit.jupiter.api.Tag;
import org.nd4j.common.config.ND4JInferenceWeightDataType;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Domain tests for the graph-native admission shadow branch over the latest FP&A golden graph.
 *
 * <p>The golden contract deliberately omits schema types. This fixture therefore adds only a
 * deterministic key-prefix type projection needed by the production canonical-key adapter; entity
 * identity, accepted names, directed relations, and forbidden merges all come from the golden file.
 * The tests distinguish structural scoring from the predicate-aware admission trace. The structural
 * engine remains generic, while admission classifies FP&amp;A status, version, period, override, and
 * action paths into machine-readable evidence for downstream policy or a small model.</p>
 */
class FpnaGoldenGraphHybridAdmissionTest {

    private static final String GOLDEN_RESOURCE =
            "graph-quality/fpna-unified-corpus-loose-graph-golden-v1.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SMALLEST_INSTRUCT_MODEL_ID = "supra-50m-instruct";
    private static final Path SMALLEST_INSTRUCT_MODEL_DIRECTORY = Path.of(
            System.getProperty("user.home"), ".kompile", "models", "llm-ggmls",
            SMALLEST_INSTRUCT_MODEL_ID).toAbsolutePath().normalize();
    private static final Path SMALLEST_INSTRUCT_MODEL_FILE = Path.of(System.getProperty(
            "kompile.fpna.supra50m.model",
            SMALLEST_INSTRUCT_MODEL_DIRECTORY.resolve("Supra-50M-f16.gguf").toString()))
            .toAbsolutePath().normalize();
    private static final Path SMALLEST_INSTRUCT_TOKENIZER_FILE = Path.of(System.getProperty(
            "kompile.fpna.supra50m.tokenizer",
            SMALLEST_INSTRUCT_MODEL_DIRECTORY.resolve("tokenizer.json").toString()))
            .toAbsolutePath().normalize();
    private static final String LFM25_MODEL_ID = "lfm2.5-1.2b-instruct";
    private static final Path LFM25_MODEL_DIRECTORY = Path.of(System.getProperty(
            "kompile.fpna.lfm25.model.dir",
            Path.of(System.getProperty("user.home"), ".kompile", "models", "llm-ggmls",
                    LFM25_MODEL_ID).toString())).toAbsolutePath().normalize();
    private static final Path LFM25_MODEL_FILE = Path.of(System.getProperty(
            "kompile.fpna.lfm25.model",
            LFM25_MODEL_DIRECTORY.resolve("model.sdz").toString()))
            .toAbsolutePath().normalize();
    private static final Path LFM25_TOKENIZER_FILE = Path.of(System.getProperty(
            "kompile.fpna.lfm25.tokenizer",
            LFM25_MODEL_DIRECTORY.resolve("tokenizer.json").toString()))
            .toAbsolutePath().normalize();
    private static final List<OperationalRuleRow> FPNA_OPERATIONAL_RULES = List.of(
            new OperationalRuleRow("fpna.status.not-usable",
                    OperationalDisposition.DENY, 100,
                    "The workbook status is \"Do not use\"."),
            new OperationalRuleRow("fpna.version.superseded",
                    OperationalDisposition.DENY, 95,
                    "The workbook version has been superseded."),
            new OperationalRuleRow("fpna.status.reference-only",
                    OperationalDisposition.REVIEW, 90,
                    "The workbook is for reference only."),
            new OperationalRuleRow("fpna.missing-period",
                    OperationalDisposition.REVIEW, 80,
                    "The workbook is missing a required period."),
            new OperationalRuleRow("fpna.status.unclassified",
                    OperationalDisposition.REVIEW, 70,
                    "The workbook status requires review."),
            new OperationalRuleRow("fpna.status.authoritative",
                    OperationalDisposition.ALLOW, 10,
                    "The workbook status is authoritative."));
    private static final GraphOperationalPolicy FPNA_OPERATIONAL_POLICY =
            buildFpnaOperationalPolicy();

    @Test
    void goldenProjectionCarriesTheFpnaIdentityRelationAndConstraintDomains() throws Exception {
        GoldenDomain domain = loadDomain();
        UnifiedGraph graph = domain.toGraph();
        Set<String> predicates = domain.relations().stream()
                .map(DomainRelation::predicate)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> types = domain.entities().values().stream()
                .map(entity -> typeFor(entity.key()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        ComponentStats components = componentStats(graph);

        assertEquals(12, domain.caseCount(), "the latest loose-relation golden has 12 source cases");
        assertEquals(domain.entities().size(), graph.entityCount());
        assertEquals(domain.relations().size() + domain.materializedForbiddenMergeCount(),
                graph.relationCount());
        assertTrue(domain.entities().size() >= 100, "the domain graph should carry substantial identity coverage");
        assertTrue(domain.relations().size() >= 100, "the domain graph should carry substantial relation coverage");
        assertTrue(domain.forbiddenMerges().size() >= 3, "identity exclusions are part of the golden contract");
        assertTrue(types.containsAll(Set.of(
                "WORKBOOK", "SHEET", "PERSON", "EMAIL", "CHANNEL", "PROCESS", "POLICY")));
        assertTrue(predicates.containsAll(Set.of(
                "CONTAINS_SHEET",
                "SUBMITS",
                "SUPERSEDES",
                "USES_FX_RATE_SET",
                "MAPS_TO",
                "USABLE_FOR_PERIOD",
                "EXCEPTION_TO",
                "REQUIRES_ACTION")));

        List<HybridReasoner.ScoredEntity> top = new HybridReasoner().rank(graph).stream()
                .limit(10)
                .toList();
        assertFalse(top.isEmpty());
        assertTrue(top.stream().allMatch(item ->
                Double.isFinite(item.score()) && item.score() >= 0.0 && item.score() <= 1.0));

        System.out.println("FPNA GOLDEN DOMAIN: cases=" + domain.caseCount()
                + " entities=" + domain.entities().size()
                + " directedRelations=" + domain.relations().size()
                + " forbiddenMerges=" + domain.forbiddenMerges().size()
                + " predicates=" + predicates.size()
                + " inferredTypes=" + types.size()
                + " components=" + components.componentCount()
                + " largestComponent=" + components.largestComponent()
                + " top=" + top);
    }

    @Test
    void knownFpnaAliasesAreCoherentlyReusedWhileTheLlmDecisionRemainsAuthoritative()
            throws Exception {
        GoldenDomain domain = loadDomain();
        UnifiedGraph graph = domain.toGraph();

        for (String key : List.of(
                "person-mei-chen",
                "person-j-park",
                "workbook-amer-q3",
                "channel-wholesale",
                "process-corporate-consolidation",
                "policy-channel-override")) {
            DomainEntity persisted = domain.entity(key);
            ExtractedEntity duplicate = new ExtractedEntity(
                    "probe-" + key,
                    persisted.name(),
                    typeFor(key),
                    persisted.aliases(),
                    "golden duplicate probe",
                    0.95,
                    Map.of("goldenKey", key));

            AdmissionComparison comparison = compare(graph, duplicate);

            assertEquals(AdmissionDecision.CREATE_PROVISIONAL, comparison.llmDecision(),
                    "the new source id remains a provisional LLM addition");
            assertEquals(AdmissionDecision.CREATE_PROVISIONAL, comparison.authoritativeDecision(),
                    "shadow mode must not overwrite the existing authority");
            assertEquals(AdmissionDecision.REUSE, comparison.graphResult().decision(),
                    "golden identity evidence should reuse " + key + ": " + comparison.graphResult());
            assertEquals(key, comparison.graphResult().reuseTargetId());
            assertTrue(comparison.graphResult().score() > 0.0);
            System.out.println("FPNA IDENTITY PROBE: key=" + key
                    + " graph=" + comparison.graphResult());
        }
    }

    @Test
    void novelFpnaConceptDefersBecauseTheGoldenGraphCannotSupplyMissingStructure()
            throws Exception {
        GoldenDomain domain = loadDomain();
        AdmissionComparison comparison = compare(
                domain.toGraph(),
                new ExtractedEntity(
                        "scenario-fy27-upside",
                        "FY27 upside scenario",
                        "SCENARIO",
                        List.of("Upside plan"),
                        "new planning scenario",
                        0.92,
                        Map.of()));

        assertEquals(AdmissionDecision.CREATE_PROVISIONAL, comparison.llmDecision());
        assertEquals(AdmissionDecision.CREATE_PROVISIONAL, comparison.authoritativeDecision());
        assertEquals(AdmissionDecision.DEFER, comparison.graphResult().decision());
        assertEquals("candidate is absent from frozen graph", comparison.graphResult().reason());
        assertNull(comparison.graphResult().reuseTargetId());
        assertTrue(comparison.graphResult().evidenceTrace()
                .hasKind(AdmissionEvidence.Kind.NO_MATCH));

        System.out.println("FPNA NOVEL ADDITION: " + comparison.graphResult());
    }

    @Test
    void ambiguousSummaryAdditionDoesNotCrossAForbiddenWorkbookBoundary() throws Exception {
        GoldenDomain domain = loadDomain();
        AdmissionComparison comparison = compare(
                domain.toGraph(),
                new ExtractedEntity(
                        "probe-unscoped-summary",
                        "Summary",
                        "SHEET",
                        List.of(),
                        "unscoped worksheet mention",
                        0.96,
                        Map.of()));

        assertEquals(AdmissionDecision.CREATE_PROVISIONAL, comparison.authoritativeDecision());
        assertEquals(AdmissionDecision.DEFER, comparison.graphResult().decision(),
                "the graph must not collapse identically named Summary sheets from distinct workbooks");
        assertNull(comparison.graphResult().reuseTargetId());
        assertTrue(comparison.graphResult().evidenceTrace()
                .hasKind(AdmissionEvidence.Kind.AMBIGUITY));

        System.out.println("FPNA FORBIDDEN-MERGE PROBE: " + comparison.graphResult());
    }

    @Test
    void supportedProspectiveAdditionOutranksTheSameIsolatedAddition() throws Exception {
        GoldenDomain domain = loadDomain();
        UnifiedGraph isolated = domain.toGraph();
        UnifiedGraph supported = domain.toGraph();

        addProspectiveScenario(isolated);
        addProspectiveScenario(supported);
        supported.addRelation(new SimpleGraphRelation(
                "probe-scenario-input",
                "scenario-fy27-upside",
                "process-corporate-consolidation",
                "INPUT_TO",
                0.95,
                1.0,
                true,
                Set.of("golden-domain-probe"),
                null,
                null,
                Map.of()));

        double isolatedScore = score(isolated, "scenario-fy27-upside");
        double supportedScore = score(supported, "scenario-fy27-upside");

        assertTrue(supportedScore > isolatedScore,
                "an FP&A scenario connected to corporate consolidation should gain structural support");
        System.out.println("FPNA STRUCTURAL ADDITION: isolated=" + isolatedScore
                + " supported=" + supportedScore);
    }

    @Test
    void structuralScoreRemainsGenericWhileAdmissionTraceInterpretsStatusPolarity()
            throws Exception {
        GoldenDomain domain = loadDomain();
        DomainRelation statusRelation = domain.relations().stream()
                .filter(relation -> "HAS_STATUS".equals(relation.predicate()))
                .filter(relation -> "status-do-not-use".equals(relation.target()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "golden graph must contain the APAC do-not-use status relation"));

        double domainPredicateScore = twoNodeSourceScore(statusRelation, statusRelation.predicate());
        double genericLinkScore = twoNodeSourceScore(statusRelation, "SUPPORTS");

        assertEquals(genericLinkScore, domainPredicateScore, 1e-9,
                "HybridReasoner currently treats non-conflict predicate labels as structural links; "
                        + "predicate polarity belongs to the admission semantics layer");

        AdmissionComparison comparison = compareExisting(domain, statusRelation.source());
        AdmissionEvidence caution = evidenceByRule(comparison, "fpna.status.not-usable");
        assertEquals(AdmissionEvidence.Kind.CAUTION, caution.kind());
        assertEquals(List.of(statusRelation.source(), statusRelation.target()),
                caution.entityPath());

        System.out.println("FPNA PREDICATE SEMANTICS: predicate=" + statusRelation.predicate()
                + " target=" + statusRelation.target()
                + " structuralScore=" + domainPredicateScore
                + " evidence=" + caution);
    }

    @Test
    void apacWorkbookTraceCarriesTheTwoHopDoNotUseWarning() throws Exception {
        GoldenDomain domain = loadDomain();
        AdmissionComparison comparison = compareExisting(domain, "workbook-apac-fy27q1");

        List<AdmissionEvidence> warnings = comparison.graphResult().evidenceTrace().items().stream()
                .filter(item -> "fpna.status.not-usable".equals(item.ruleId()))
                .filter(item -> item.entityPath().equals(List.of(
                        "workbook-apac-fy27q1", "sheet-summary", "status-do-not-use")))
                .toList();

        assertEquals(1, warnings.size(),
                "duplicate source relations should add corroboration without duplicating semantic evidence");
        AdmissionEvidence warning = warnings.get(0);

        assertEquals(AdmissionEvidence.Kind.CAUTION, warning.kind());
        assertEquals(List.of("CONTAINS_SHEET", "HAS_STATUS"), warning.predicatePath());
        String promptContext = comparison.graphResult().evidenceTrace().toPromptContext();
        assertTrue(promptContext.contains("kind=CAUTION rule=fpna.status.not-usable"));
        assertTrue(promptContext.contains("predicates=CONTAINS_SHEET>HAS_STATUS"));
        assertTrue(promptContext.contains(
                "entities=workbook-apac-fy27q1>sheet-summary>status-do-not-use"));

        GraphOperationalVerdict verdict = FPNA_OPERATIONAL_POLICY.evaluate(
                "workbook-apac-fy27q1", comparison.graphResult());
        assertEquals(AdmissionDecision.REUSE, comparison.graphResult().decision(),
                "identity reuse remains separate from operational usability");
        assertEquals(OperationalDisposition.DENY, verdict.disposition());
        assertEquals("fpna.status.not-usable", verdict.ruleId());
        assertEquals("DENY", verdict.toToolArguments().get("disposition"));
        assertFalse(verdict.toModelContext().contains(warning.summary()),
                "graph-authored prose must not be passed through to the small model");
        System.out.println("FPNA APAC SMALL-MODEL CONTEXT:\n" + verdict.toModelContext());
    }

    @Test
    @Tag("fpna-model")
    void supra50mCannotOwnGraphToolRoutingAndGraphVerdictRemainsDeterministic()
            throws Exception {
        assumeTrue(Boolean.parseBoolean(System.getProperty(
                        "kompile.fpna.supra50m.enabled", "false")),
                "Set -Dkompile.fpna.supra50m.enabled=true to run the Supra 50M test");
        assertTrue(Files.isRegularFile(SMALLEST_INSTRUCT_MODEL_FILE),
                "missing Supra 50M GGUF: " + SMALLEST_INSTRUCT_MODEL_FILE);
        assertTrue(Files.size(SMALLEST_INSTRUCT_MODEL_FILE) > 50_000_000L,
                "Supra 50M GGUF is empty or only a download pointer");
        assertTrue(Files.isRegularFile(SMALLEST_INSTRUCT_TOKENIZER_FILE),
                "missing Supra 50M tokenizer: " + SMALLEST_INSTRUCT_TOKENIZER_FILE);

        AdmissionComparison comparison = compareExisting(loadDomain(), "workbook-apac-fy27q1");
        String fullGraphEvidence = comparison.graphResult().evidenceTrace().toCompactPromptContext(8);
        assertTrue(fullGraphEvidence.contains("CAUTION|fpna.status.not-usable"),
                "the bounded trace must retain the operational status warning");

        GraphOperationalVerdict verdict = FPNA_OPERATIONAL_POLICY.evaluate(
                "workbook-apac-fy27q1", comparison.graphResult());
        assertEquals(OperationalDisposition.DENY, verdict.disposition());
        assertEquals("fpna.status.not-usable", verdict.ruleId());
        assertEquals(List.of("CONTAINS_SHEET", "HAS_STATUS"),
                verdict.decisiveEvidence().orElseThrow().predicatePath());
        String instruction = """
                %s
                Should the workbook be used?
                Answer only YES or NO.
                """.formatted(verdict.toModelContext()).trim();

        ChatTemplate.Tool verdictTool = ChatTemplate.Tool.function(
                "record_graph_verdict",
                "Record the graph policy verdict for a candidate.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "candidate_id", Map.of("type", "string"),
                                "disposition", Map.of(
                                        "type", "string",
                                        "enum", List.of("ALLOW", "DENY", "REVIEW"))),
                        "required", List.of("candidate_id", "disposition"),
                        "additionalProperties", false));
        ChatTemplate.Request toolRequest = ChatTemplate.Request.builder()
                .messages(List.of(ChatTemplate.Message.user("""
                        %s
                        Route this verdict by calling record_graph_verdict with candidate_id
                        "workbook-apac-fy27q1" and disposition "DENY".
                        Return only JSON with top-level keys "tool" and "arguments".
                        """.formatted(verdict.toModelContext()).trim())))
                .tools(List.of(verdictTool))
                .addGenerationPrompt(true)
                .build();

        SameDiffLanguageModelImpl model = new SameDiffLanguageModelImpl(
                Optional.empty(), Optional.empty());
        try {
            model.loadModel(
                    SMALLEST_INSTRUCT_MODEL_ID,
                    SMALLEST_INSTRUCT_MODEL_FILE,
                    SMALLEST_INSTRUCT_TOKENIZER_FILE,
                    Map.ofEntries(
                            Map.entry("maxNewTokens", 96),
                            Map.entry("maxPrefillLength", 1000),
                            Map.entry("maxKvCacheLength", 1024),
                            Map.entry("doSample", false),
                            Map.entry("topK", 1),
                            Map.entry("continuationEnabled", false),
                            Map.entry("graphOptimizerEnabled", true),
                            Map.entry("dspEnabled", true)));

            assertTrue(model.isLoaded());
            assertEquals(SMALLEST_INSTRUCT_MODEL_ID, model.getLoadedModelId());
            int promptTokens = model.countPromptTokens(instruction);
            int boundedTraceTokens = model.countPromptTokens(fullGraphEvidence);
            assertTrue(promptTokens <= 256,
                    "operational verdict is too large for a 50M model: " + promptTokens);
            assertTrue(promptTokens < boundedTraceTokens,
                    "operational verdict should be smaller than the bounded raw trace");

            ChatGenerationResult toolResult = model.generateChat(toolRequest, 72);
            assertFalse(toolResult.getRawText().isBlank(),
                    "the real model probe must produce a characterization sample");
            assertFalse(toolResult.hasToolCalls(),
                    "Supra 50M unexpectedly acquired structured tool-call capability; "
                            + "review the model boundary before allowing it to own routing");
            assertTrue(toolResult.getParseErrors().isEmpty(),
                    "non-call output must not be promoted into a malformed call: "
                            + toolResult.getParseErrors());

            ChatTemplate.ToolCall graphRoutedCall = ChatTemplate.ToolCall.function(
                    "graph-policy:" + verdict.candidateId(),
                    verdictTool.getName(),
                    verdict.toToolArguments());
            assertEquals("record_graph_verdict", graphRoutedCall.getName());
            assertEquals("workbook-apac-fy27q1",
                    graphRoutedCall.getArguments().get("candidate_id"));
            assertEquals("DENY", graphRoutedCall.getArguments().get("disposition"));
            assertEquals("fpna.status.not-usable",
                    graphRoutedCall.getArguments().get("rule_id"));
        } finally {
            if (model.isLoaded()) {
                model.unloadModel();
            }
        }
    }

    @Test
    @Tag("fpna-lfm25-tool")
    void lfm25RoutesTheDeterministicGraphVerdictThroughItsNativeToolProtocol()
            throws Exception {
        assumeTrue(Boolean.parseBoolean(System.getProperty(
                        "kompile.fpna.lfm25.enabled", "false")),
                "Set -Dkompile.fpna.lfm25.enabled=true to run the LFM2.5 1.2B test");
        assertTrue(Files.isRegularFile(LFM25_MODEL_FILE),
                "missing staged LFM2.5 model: " + LFM25_MODEL_FILE);
        assertTrue(Files.size(LFM25_MODEL_FILE) > 100_000_000L,
                "staged LFM2.5 model is empty or only a download pointer");
        assertTrue(Files.isRegularFile(LFM25_TOKENIZER_FILE),
                "missing LFM2.5 tokenizer: " + LFM25_TOKENIZER_FILE);

        AdmissionComparison comparison = compareExisting(loadDomain(), "workbook-apac-fy27q1");
        GraphOperationalVerdict verdict = FPNA_OPERATIONAL_POLICY.evaluate(
                "workbook-apac-fy27q1", comparison.graphResult());
        assertEquals(AdmissionDecision.REUSE, comparison.graphResult().decision());
        assertEquals(OperationalDisposition.DENY, verdict.disposition());
        assertEquals("fpna.status.not-usable", verdict.ruleId());
        assertEquals("The workbook status is \"Do not use\".", verdict.statement());

        ChatTemplate.Tool verdictTool = ChatTemplate.Tool.function(
                "record_graph_verdict",
                "Record a graph-policy verdict without changing it.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "candidate_id", Map.of(
                                        "type", "string",
                                        "description", "Candidate identifier."),
                                "disposition", Map.of(
                                        "type", "string",
                                        "enum", List.of("ALLOW", "DENY", "REVIEW")),
                                "rule_id", Map.of(
                                        "type", "string",
                                        "description", "Graph-policy rule identifier."),
                                "statement", Map.of(
                                        "type", "string",
                                        "description", "Policy-authored verdict statement.")),
                        "required", List.of(
                                "candidate_id", "disposition", "rule_id", "statement"),
                        "additionalProperties", false));
        ChatTemplate.Request request = ChatTemplate.Request.builder()
                .messages(List.of(
                        ChatTemplate.Message.system("""
                                Use exactly one declared function and output no explanation.
                                Copy every supplied value exactly as direct named arguments.
                                Do not nest arguments in properties, parameters, args, or arguments.
                                """.trim()),
                        ChatTemplate.Message.user("""
                                The graph policy has already made the final decision. All four
                                required values are present:
                                candidate_id: %s
                                disposition: %s
                                rule_id: %s
                                statement: %s
                                Call record_graph_verdict now using the required structured call.
                                """.formatted(
                                verdict.candidateId(),
                                verdict.disposition().name(),
                                verdict.ruleId(),
                                verdict.statement()).trim())))
                .tools(List.of(verdictTool))
                .toolChoice(ChatTemplate.ToolChoice.REQUIRED)
                .addGenerationPrompt(true)
                .build();

        SameDiffLanguageModelImpl model = new SameDiffLanguageModelImpl(
                Optional.empty(), Optional.empty());
        try {
            model.loadModel(
                    LFM25_MODEL_ID,
                    LFM25_MODEL_FILE,
                    LFM25_TOKENIZER_FILE,
                    Map.ofEntries(
                            Map.entry("maxNewTokens", 80),
                            Map.entry("maxPrefillLength", 1536),
                            Map.entry("maxKvCacheLength", 2048),
                            Map.entry("temperature", 0.0d),
                            Map.entry("doSample", false),
                            Map.entry("topK", 1),
                            Map.entry("continuationEnabled", false),
                            Map.entry("graphOptimizerEnabled", true),
                            Map.entry("dspEnabled", true)));

            assertTrue(model.isLoaded());
            assertEquals(LFM25_MODEL_ID, model.getLoadedModelId());
            assertTrue(model.countPromptTokens(verdict.toModelContext()) <= 64,
                    "deterministic graph verdict should remain small-model sized");

            ChatGenerationResult generated = model.generateChat(request, 80);
            System.out.println("LFM2.5 FPNA graph-verdict raw response: "
                    + generated.getRawText());
            assertTrue(generated.getParseErrors().isEmpty(),
                    "LFM2.5 emitted an invalid native tool call: "
                            + generated.getParseErrors());
            assertEquals(1, generated.getToolCalls().size(),
                    "LFM2.5 must route exactly one graph verdict");
            ChatTemplate.ToolCall call = generated.getToolCalls().get(0);
            assertEquals(verdictTool.getName(), call.getName());
            assertFalse(call.getArguments().containsKey("properties"),
                    "tool-schema metadata leaked into the argument envelope");
            assertEquals(verdict.toToolArguments(), call.getArguments(),
                    "LFM2.5 changed the graph-authoritative verdict while routing it");
        } finally {
            if (model.isLoaded()) {
                model.unloadModel();
            }
        }
        assertFalse(model.isLoaded(), "LFM2.5 remains loaded after the tool-call test");
    }

    @Test
    @Tag("fpna-lfm25-reasoning")
    void lfm25SelectsTheWinningOperationalRuleFromFpnaGraphEvidence()
            throws Exception {
        assumeTrue(Boolean.parseBoolean(System.getProperty(
                        "kompile.fpna.lfm25.reasoning.enabled", "false")),
                "Set -Dkompile.fpna.lfm25.reasoning.enabled=true to run the prompted reasoning test");
        assertTrue(Files.isRegularFile(LFM25_MODEL_FILE),
                "missing staged LFM2.5 model: " + LFM25_MODEL_FILE);
        assertTrue(Files.size(LFM25_MODEL_FILE) > 100_000_000L,
                "staged LFM2.5 model is empty or only a download pointer");
        assertTrue(Files.isRegularFile(LFM25_TOKENIZER_FILE),
                "missing LFM2.5 tokenizer: " + LFM25_TOKENIZER_FILE);
        assertEquals(ND4JInferenceWeightDataType.FLOAT16,
                ND4JInferenceWeightDataType.resolve(),
                "the real FP&A matrix must exercise the FP16 optimizer policy");
        System.out.println("LFM2.5 FPNA inference weight dtype: "
                + ND4JInferenceWeightDataType.resolve());

        GoldenDomain domain = loadDomain();
        Map<String, String> expectedRules = new LinkedHashMap<>();
        expectedRules.put("issue-august-duplicated-into-july", "fpna.status.not-usable");
        expectedRules.put("version-v1", "fpna.status.not-usable");
        expectedRules.put("version-v2", "fpna.status.authoritative");
        expectedRules.put("fx-spot-reference", "fpna.status.reference-only");

        Map<String, AdmissionComparison> comparisons = new LinkedHashMap<>();
        Map<String, GraphOperationalVerdict> expectedVerdicts = new LinkedHashMap<>();
        expectedRules.forEach((candidateId, expectedRuleId) -> {
            AdmissionComparison comparison = compareExisting(domain, candidateId);
            GraphOperationalVerdict expected = FPNA_OPERATIONAL_POLICY.evaluate(
                    candidateId, comparison.graphResult());
            assertEquals(AdmissionDecision.REUSE, comparison.graphResult().decision(),
                    "each matrix candidate must exercise existing-identity admission");
            assertTrue(expected.policyMatched(),
                    "each matrix candidate must have graph-matched operational evidence");
            assertEquals(expectedRuleId, expected.ruleId(),
                    "the deterministic graph policy changed for " + candidateId);
            comparisons.put(candidateId, comparison);
            expectedVerdicts.put(candidateId, expected);
        });

        assertEquals(Set.of(
                        OperationalDisposition.DENY,
                        OperationalDisposition.REVIEW,
                        OperationalDisposition.ALLOW),
                expectedVerdicts.values().stream()
                        .map(GraphOperationalVerdict::disposition)
                        .collect(Collectors.toSet()),
                "the matrix must span every operational disposition");
        assertEquals(List.of(
                        "fpna.status.not-usable",
                        "fpna.version.superseded",
                        "fpna.status.authoritative"),
                expectedVerdicts.get("issue-august-duplicated-into-july")
                        .supportingRuleIds(),
                "the conflict case must retain all three competing graph rules");

        try (SubprocessMemoryWatchdog watchdog = new SubprocessMemoryWatchdog(
                80, 90, 95, 2_000L, 75, 85, 92)) {
            watchdog.setModelId(LFM25_MODEL_ID);
            watchdog.start();
            SameDiffLanguageModelImpl model = new SameDiffLanguageModelImpl(
                    Optional.empty(), Optional.empty());
            try {
                model.loadModel(
                        LFM25_MODEL_ID,
                        LFM25_MODEL_FILE,
                        LFM25_TOKENIZER_FILE,
                        Map.ofEntries(
                                Map.entry("maxNewTokens", 80),
                                // The fully templated domain prompt is 531 tokens. Keep bounded headroom
                                // without retaining a 1536-token DSP prefill plan for every decode.
                                Map.entry("maxPrefillLength", 640),
                                Map.entry("maxKvCacheLength", 720),
                                Map.entry("temperature", 0.0d),
                                Map.entry("doSample", false),
                                Map.entry("topK", 1),
                                Map.entry("continuationEnabled", false),
                                Map.entry("graphOptimizerEnabled", true),
                                Map.entry("dspEnabled", true)));

                assertTrue(model.isLoaded());
                assertEquals(LFM25_MODEL_ID, model.getLoadedModelId());

                for (Map.Entry<String, GraphOperationalVerdict> entry
                        : expectedVerdicts.entrySet()) {
                    String candidateId = entry.getKey();
                    GraphOperationalVerdict expected = entry.getValue();
                    ChatTemplate.Tool verdictTool = graphVerdictTool(expected);
                    String reasoningPrompt = graphPolicySelectionPrompt(
                            comparisons.get(candidateId), expected);
                    assertTrue(model.countPromptTokens(reasoningPrompt) <= 512,
                            "bounded FP&A evidence and decision table exceed the reasoning budget for "
                                    + candidateId);

                    ChatGenerationResult generated = model.generateChat(
                            graphPolicySelectionRequest(reasoningPrompt, verdictTool), 80);
                    System.out.println("LFM2.5 FPNA prompted-reasoning candidate="
                            + candidateId + " raw response: " + generated.getRawText());
                    assertPromptedGraphVerdict(generated, verdictTool, expected);
                }
                System.out.println("LFM2.5 FPNA watchdog snapshot while loaded: "
                        + watchdog.getLastSnapshot());
                assertFalse(watchdog.shouldKill(),
                        "the FP16 model crossed a watchdog kill threshold");
            } finally {
                if (model.isLoaded()) {
                    model.unloadModel();
                }
            }
            assertFalse(model.isLoaded(),
                    "LFM2.5 remains loaded after the prompted reasoning test");
        }
    }

    @Test
    @Tag("fpna-lfm25-accuracy")
    void lfm25MeasuresGraphAdmissionAccuracyAcrossTheGoldenOperationalDomain()
            throws Exception {
        assumeTrue(Boolean.parseBoolean(System.getProperty(
                        "kompile.fpna.lfm25.accuracy.enabled", "false")),
                "Set -Dkompile.fpna.lfm25.accuracy.enabled=true to run the accuracy test");
        assertTrue(Files.isRegularFile(LFM25_MODEL_FILE),
                "missing staged LFM2.5 model: " + LFM25_MODEL_FILE);
        assertTrue(Files.size(LFM25_MODEL_FILE) > 100_000_000L,
                "staged LFM2.5 model is empty or only a download pointer");
        assertTrue(Files.isRegularFile(LFM25_TOKENIZER_FILE),
                "missing LFM2.5 tokenizer: " + LFM25_TOKENIZER_FILE);
        assertEquals(ND4JInferenceWeightDataType.FLOAT16,
                ND4JInferenceWeightDataType.resolve(),
                "the real FP&A accuracy run must exercise the FP16 optimizer policy");

        GoldenDomain domain = loadDomain();
        Map<String, AdmissionComparison> comparisons = new LinkedHashMap<>();
        Map<String, GraphOperationalVerdict> expectedVerdicts = new LinkedHashMap<>();
        domain.entities().keySet().stream().sorted().forEach(candidateId -> {
            AdmissionComparison comparison = compareExisting(domain, candidateId);
            GraphOperationalVerdict expected = FPNA_OPERATIONAL_POLICY.evaluate(
                    candidateId, comparison.graphResult());
            if (expected.policyMatched()) {
                comparisons.put(candidateId, comparison);
                expectedVerdicts.put(candidateId, expected);
            }
        });

        assertTrue(expectedVerdicts.size() >= 19,
                "the golden graph no longer exposes the full operational accuracy domain");
        assertEquals(Set.of(
                        "fpna.missing-period",
                        "fpna.status.reference-only",
                        "fpna.status.not-usable",
                        "fpna.version.superseded",
                        "fpna.status.authoritative"),
                expectedVerdicts.values().stream()
                        .map(GraphOperationalVerdict::ruleId)
                        .collect(Collectors.toSet()),
                "the accuracy domain must retain every graph-derived decisive rule family");
        assertEquals(Set.of(
                        OperationalDisposition.DENY,
                        OperationalDisposition.REVIEW,
                        OperationalDisposition.ALLOW),
                expectedVerdicts.values().stream()
                        .map(GraphOperationalVerdict::disposition)
                        .collect(Collectors.toSet()),
                "the accuracy domain must span every operational disposition");

        Map<String, GraphOperationalVerdict> competingVerdicts =
                expectedVerdicts.entrySet().stream()
                        .filter(entry ->
                                entry.getValue().supportingRuleIds().size() > 1)
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                Map.Entry::getValue,
                                (left, right) -> left,
                                LinkedHashMap::new));
        assertEquals(4, competingVerdicts.size(),
                "the accuracy challenge must retain every multi-rule golden candidate");

        boolean onlyCompetingRules = Boolean.parseBoolean(System.getProperty(
                "kompile.fpna.lfm25.accuracy.onlyCompetingRules", "false"));
        Map<String, GraphOperationalVerdict> routingVerdicts =
                onlyCompetingRules ? competingVerdicts : expectedVerdicts;
        System.out.println("LFM2.5 FPNA accuracy slices: arbitration="
                + competingVerdicts.keySet() + " graph-carried-routing="
                + routingVerdicts.keySet());

        List<VerdictAccuracySample> arbitrationSamples = new ArrayList<>();
        List<VerdictAccuracySample> routingSamples = new ArrayList<>();

        try (SubprocessMemoryWatchdog watchdog = new SubprocessMemoryWatchdog(
                80, 90, 95, 2_000L, 75, 85, 92)) {
            watchdog.setModelId(LFM25_MODEL_ID);
            watchdog.start();
            SameDiffLanguageModelImpl model = new SameDiffLanguageModelImpl(
                    Optional.empty(), Optional.empty());
            try {
                model.loadModel(
                        LFM25_MODEL_ID,
                        LFM25_MODEL_FILE,
                        LFM25_TOKENIZER_FILE,
                        Map.ofEntries(
                                Map.entry("maxNewTokens", 80),
                                Map.entry("maxPrefillLength", 640),
                                Map.entry("maxKvCacheLength", 720),
                                Map.entry("temperature", 0.0d),
                                Map.entry("doSample", false),
                                Map.entry("topK", 1),
                                Map.entry("continuationEnabled", false),
                                Map.entry("graphOptimizerEnabled", true),
                                Map.entry("dspEnabled", true)));

                assertTrue(model.isLoaded());
                assertEquals(LFM25_MODEL_ID, model.getLoadedModelId());

                for (Map.Entry<String, GraphOperationalVerdict> entry
                        : competingVerdicts.entrySet()) {
                    String candidateId = entry.getKey();
                    GraphOperationalVerdict expected = entry.getValue();
                    List<String> promptRuleOrder =
                            new ArrayList<>(expected.supportingRuleIds());
                    java.util.Collections.reverse(promptRuleOrder);
                    ChatTemplate.Tool verdictTool =
                            graphVerdictTool(expected, promptRuleOrder);
                    String reasoningPrompt = graphPolicySelectionPrompt(
                            comparisons.get(candidateId), expected, promptRuleOrder);
                    assertTrue(model.countPromptTokens(reasoningPrompt) <= 512,
                            "bounded FP&A arbitration prompt exceeds the reasoning budget for "
                                    + candidateId);

                    ChatGenerationResult generated = model.generateChat(
                            graphPolicySelectionRequest(reasoningPrompt, verdictTool), 80);
                    VerdictAccuracySample sample =
                            scorePromptedGraphVerdict(generated, verdictTool, expected);
                    arbitrationSamples.add(sample);
                    System.out.println("LFM2.5 FPNA arbitration sample: " + sample
                            + " raw=" + generated.getRawText());

                    assertFalse(watchdog.shouldKill(),
                            "the FP16 model crossed a watchdog kill threshold after arbitration "
                                    + candidateId + ": " + watchdog.getLastSnapshot());
                }

                for (Map.Entry<String, GraphOperationalVerdict> entry
                        : routingVerdicts.entrySet()) {
                    String candidateId = entry.getKey();
                    GraphOperationalVerdict expected = entry.getValue();
                    ChatTemplate.Tool verdictTool =
                            graphCarriedVerdictTool(expected);
                    String routingPrompt = graphCarriedVerdictPrompt(expected);
                    assertTrue(model.countPromptTokens(routingPrompt) <= 256,
                            "graph-carried FP&A verdict exceeds the routing budget for "
                                    + candidateId);

                    ChatGenerationResult generated = model.generateChat(
                            graphPolicySelectionRequest(routingPrompt, verdictTool), 80);
                    VerdictAccuracySample sample =
                            scorePromptedGraphVerdict(generated, verdictTool, expected);
                    routingSamples.add(sample);
                    System.out.println("LFM2.5 FPNA graph-carried routing sample: " + sample
                            + " raw=" + generated.getRawText());

                    assertFalse(watchdog.shouldKill(),
                            "the FP16 model crossed a watchdog kill threshold after routing "
                                    + candidateId + ": " + watchdog.getLastSnapshot());
                }
                System.out.println("LFM2.5 FPNA accuracy watchdog snapshot while loaded: "
                        + watchdog.getLastSnapshot());
            } finally {
                if (model.isLoaded()) {
                    model.unloadModel();
                }
            }
            assertFalse(model.isLoaded(),
                    "LFM2.5 remains loaded after the accuracy evaluation");
        }

        VerdictAccuracySummary arbitrationSummary =
                VerdictAccuracySummary.from(arbitrationSamples);
        VerdictAccuracySummary routingSummary =
                VerdictAccuracySummary.from(routingSamples);
        System.out.println("LFM2.5 FPNA ARBITRATION ACCURACY SUMMARY: "
                + arbitrationSummary);
        System.out.println("LFM2.5 FPNA GRAPH-CARRIED ROUTING ACCURACY SUMMARY: "
                + routingSummary);

        assertTrue(arbitrationSummary.structuredCallRate() >= 0.90,
                arbitrationSummary.failureMessage(
                        "multi-row arbitration structured tool call"));
        assertTrue(arbitrationSummary.parseRate() >= 0.90,
                arbitrationSummary.failureMessage(
                        "multi-row arbitration native tool parsing"));
        assertTrue(arbitrationSummary.schemaRate() >= 0.90,
                arbitrationSummary.failureMessage(
                        "multi-row arbitration argument schema"));
        assertTrue(arbitrationSummary.candidateRate() >= 0.95,
                arbitrationSummary.failureMessage(
                        "multi-row arbitration candidate fidelity"));
        assertTrue(arbitrationSummary.rowCoherenceRate() >= 0.85,
                arbitrationSummary.failureMessage(
                        "multi-row arbitration selected-row coherence"));

        assertTrue(routingSummary.structuredCallRate() >= accuracyFloor(
                        "kompile.fpna.lfm25.accuracy.minStructuredCall", 0.90),
                routingSummary.failureMessage("graph-carried structured tool call"));
        assertTrue(routingSummary.parseRate() >= accuracyFloor(
                        "kompile.fpna.lfm25.accuracy.minParse", 0.90),
                routingSummary.failureMessage("graph-carried native tool parsing"));
        assertTrue(routingSummary.schemaRate() >= accuracyFloor(
                        "kompile.fpna.lfm25.accuracy.minSchema", 0.90),
                routingSummary.failureMessage("graph-carried tool argument schema"));
        assertTrue(routingSummary.candidateRate() >= accuracyFloor(
                        "kompile.fpna.lfm25.accuracy.minCandidate", 0.95),
                routingSummary.failureMessage("graph-carried candidate fidelity"));
        assertTrue(routingSummary.dispositionRate() >= accuracyFloor(
                        "kompile.fpna.lfm25.accuracy.minDisposition", 0.80),
                routingSummary.failureMessage("graph-carried disposition accuracy"));
        assertTrue(routingSummary.ruleRate() >= accuracyFloor(
                        "kompile.fpna.lfm25.accuracy.minRule", 0.75),
                routingSummary.failureMessage("graph-carried winning-rule accuracy"));
        assertTrue(routingSummary.statementRate() >= accuracyFloor(
                        "kompile.fpna.lfm25.accuracy.minStatement", 0.80),
                routingSummary.failureMessage(
                        "graph-carried evidence-statement accuracy"));
        assertTrue(routingSummary.rowCoherenceRate() >= accuracyFloor(
                        "kompile.fpna.lfm25.accuracy.minRowCoherence", 0.85),
                routingSummary.failureMessage("graph-carried selected-row coherence"));
        assertTrue(routingSummary.exactVerdictRate() >= accuracyFloor(
                        "kompile.fpna.lfm25.accuracy.minExactVerdict", 0.75),
                routingSummary.failureMessage("exact graph-carried verdict"));
        assertTrue(routingSummary.competingRuleAccuracy() >= accuracyFloor(
                        "kompile.fpna.lfm25.accuracy.minCompetingRule", 0.50),
                routingSummary.failureMessage(
                        "graph-carried competing-rule routing"));
        assertTrue(routingSummary.macroRuleAccuracy() >= accuracyFloor(
                        "kompile.fpna.lfm25.accuracy.minMacroRule", 0.65),
                routingSummary.failureMessage("graph-carried macro rule accuracy"));
    }

    @Test
    void versionTraceDistinguishesAuthoritativeAndSupersededWorkbookVersions()
            throws Exception {
        GoldenDomain domain = loadDomain();
        AdmissionComparison current = compareExisting(domain, "version-v2");
        AdmissionComparison obsolete = compareExisting(domain, "version-v1");

        assertEquals(AdmissionEvidence.Kind.AFFIRMING_RELATION,
                evidenceByRule(current, "fpna.status.authoritative").kind());
        assertEquals(AdmissionEvidence.Kind.AFFIRMING_RELATION,
                evidenceByRule(current, "fpna.version.supersedes").kind());
        assertEquals(AdmissionEvidence.Kind.CAUTION,
                evidenceByRule(obsolete, "fpna.version.superseded").kind());
        assertEquals(AdmissionEvidence.Kind.CAUTION,
                evidenceByRule(obsolete, "fpna.status.not-usable").kind());

        System.out.println("FPNA VERSION TRACE: current="
                + current.graphResult().evidenceTrace()
                + " obsolete=" + obsolete.graphResult().evidenceTrace());
    }

    @Test
    void operationalPolicySpansDistinctVerdictsAcrossTheGoldenDomain() throws Exception {
        GoldenDomain domain = loadDomain();
        Map<String, GraphOperationalVerdict> verdicts = new LinkedHashMap<>();
        Map<String, List<String>> candidatesByRule = new LinkedHashMap<>();
        Map<String, List<String>> competingRulesByCandidate = new LinkedHashMap<>();

        domain.entities().keySet().stream().sorted().forEach(candidateId -> {
            AdmissionComparison comparison = compareExisting(domain, candidateId);
            GraphOperationalVerdict verdict = FPNA_OPERATIONAL_POLICY.evaluate(
                    candidateId, comparison.graphResult());
            verdicts.put(candidateId, verdict);
            candidatesByRule.computeIfAbsent(verdict.ruleId(), ignored -> new ArrayList<>())
                    .add(candidateId);
            if (verdict.supportingRuleIds().size() > 1) {
                competingRulesByCandidate.put(candidateId, verdict.supportingRuleIds());
            }
        });

        assertTrue(verdicts.values().stream().anyMatch(
                verdict -> verdict.disposition() == OperationalDisposition.DENY),
                "the golden domain must exercise a graph-derived denial");
        assertTrue(verdicts.values().stream().anyMatch(
                verdict -> verdict.disposition() == OperationalDisposition.REVIEW),
                "the golden domain must exercise a graph-derived review");
        assertTrue(verdicts.values().stream().anyMatch(
                verdict -> verdict.disposition() == OperationalDisposition.ALLOW),
                "the golden domain must exercise a graph-derived allowance");
        assertEquals("fpna.status.not-usable", verdicts.get("version-v1").ruleId());
        assertEquals("fpna.status.authoritative", verdicts.get("version-v2").ruleId());

        System.out.println("FPNA OPERATIONAL COVERAGE: decisive=" + candidatesByRule
                + " competing=" + competingRulesByCandidate);
    }

    @Test
    void apacPeriodTraceSeparatesUsableJuneFromMissingJulyAndAugust() throws Exception {
        GoldenDomain domain = loadDomain();
        AdmissionComparison comparison = compareExisting(domain, "sheet-jp");

        AdmissionEvidence usable = evidenceByRule(comparison, "fpna.usable-for-period");
        List<AdmissionEvidence> missing = comparison.graphResult().evidenceTrace().items().stream()
                .filter(item -> "fpna.missing-period".equals(item.ruleId()))
                .toList();

        assertEquals(AdmissionEvidence.Kind.REQUIREMENT, usable.kind());
        assertEquals("period-june-2026", usable.entityPath().get(1));
        assertEquals(2, missing.size());
        assertTrue(missing.stream().allMatch(
                item -> item.kind() == AdmissionEvidence.Kind.CAUTION));
        assertEquals(Set.of("period-july-2026", "period-august-2026"),
                missing.stream().map(item -> item.entityPath().get(1)).collect(Collectors.toSet()));

        System.out.println("FPNA PERIOD TRACE: usable=" + usable + " missing=" + missing);
    }

    @Test
    void channelOverrideTraceCarriesPriorityActionAndEscalationRequirements()
            throws Exception {
        GoldenDomain domain = loadDomain();
        AdmissionComparison comparison = compareExisting(domain, "policy-channel-override");

        assertEquals(AdmissionEvidence.Kind.OVERRIDE,
                evidenceByRule(comparison, "fpna.overrides").kind());
        assertEquals(AdmissionEvidence.Kind.REQUIREMENT,
                evidenceByRule(comparison, "fpna.requires-action").kind());
        assertEquals(AdmissionEvidence.Kind.REQUIREMENT,
                evidenceByRule(comparison, "fpna.requires-escalation-to").kind());

        System.out.println("FPNA OVERRIDE TRACE: "
                + comparison.graphResult().evidenceTrace());
    }

    private static GraphOperationalPolicy buildFpnaOperationalPolicy() {
        GraphOperationalPolicy.Builder builder = GraphOperationalPolicy.builder();
        FPNA_OPERATIONAL_RULES.forEach(row -> builder.rule(
                row.ruleId(), row.disposition(), row.priority(), row.statement()));
        return builder.build();
    }

    private static ChatTemplate.Tool graphVerdictTool(
            GraphOperationalVerdict expected) {
        return graphVerdictTool(expected, expected.supportingRuleIds());
    }

    private static ChatTemplate.Tool graphVerdictTool(
            GraphOperationalVerdict expected, List<String> ruleOrder) {
        if (!new LinkedHashSet<>(expected.supportingRuleIds())
                .equals(new LinkedHashSet<>(ruleOrder))) {
            throw new IllegalArgumentException(
                    "tool rule order must contain exactly the graph-matched rules");
        }
        List<OperationalRuleRow> matchedRows = ruleOrder.stream()
                .map(ruleId -> FPNA_OPERATIONAL_RULES.stream()
                        .filter(row -> ruleId.equals(row.ruleId()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError(
                                "missing tool-schema policy row for " + ruleId)))
                .toList();
        List<String> dispositions = matchedRows.stream()
                .map(row -> row.disposition().name())
                .distinct()
                .toList();
        List<String> statements = matchedRows.stream()
                .map(OperationalRuleRow::statement)
                .distinct()
                .toList();

        return ChatTemplate.Tool.function(
                "record_graph_verdict",
                "Choose the graph-matched row with the greatest numeric priority; enum order is not priority.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "candidate_id", Map.of(
                                        "type", "string",
                                        "enum", List.of(expected.candidateId()),
                                        "description", "Current candidate identifier."),
                                "disposition", Map.of(
                                        "type", "string",
                                        "enum", dispositions),
                                "rule_id", Map.of(
                                        "type", "string",
                                        "enum", ruleOrder,
                                        "description", "One matched policy rule."),
                                "statement", Map.of(
                                        "type", "string",
                                        "enum", statements,
                                        "description", "Statement from the same matched row.")),
                        "required", List.of(
                                "candidate_id", "disposition", "rule_id", "statement")));
    }

    private static ChatTemplate.Tool graphCarriedVerdictTool(
            GraphOperationalVerdict expected) {
        return ChatTemplate.Tool.function(
                "record_graph_verdict",
                "Route the graph-selected verdict exactly; do not infer or change it.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "candidate_id", Map.of(
                                        "type", "string",
                                        "enum", List.of(expected.candidateId())),
                                "disposition", Map.of(
                                        "type", "string",
                                        "enum", List.of(expected.disposition().name())),
                                "rule_id", Map.of(
                                        "type", "string",
                                        "enum", List.of(expected.ruleId())),
                                "statement", Map.of(
                                        "type", "string",
                                        "enum", List.of(expected.statement()))),
                        "required", List.of(
                                "candidate_id", "disposition", "rule_id", "statement")));
    }

    private static String graphCarriedVerdictPrompt(
            GraphOperationalVerdict expected) {
        return """
                The graph policy has already selected the authoritative operational verdict.
                Do not compare alternatives, reinterpret it, or substitute another rule.
                Copy these four graph-owned fields exactly:
                candidate_id: %s
                disposition: %s
                rule_id: %s
                statement: %s

                Call record_graph_verdict exactly once with those four direct named arguments.
                Return no explanation.
                """.formatted(
                        expected.candidateId(),
                        expected.disposition(),
                        expected.ruleId(),
                        expected.statement()).trim();
    }

    private static String graphPolicySelectionPrompt(
            AdmissionComparison comparison, GraphOperationalVerdict expected) {
        return graphPolicySelectionPrompt(
                comparison, expected, expected.supportingRuleIds());
    }

    private static String graphPolicySelectionPrompt(
            AdmissionComparison comparison,
            GraphOperationalVerdict expected,
            List<String> ruleOrder) {
        if (!new LinkedHashSet<>(expected.supportingRuleIds())
                .equals(new LinkedHashSet<>(ruleOrder))) {
            throw new IllegalArgumentException(
                    "prompt rule order must contain exactly the graph-matched rules");
        }
        StringBuilder matches = new StringBuilder();
        for (int index = 0; index < ruleOrder.size(); index++) {
            String ruleId = ruleOrder.get(index);
            OperationalRuleRow row = FPNA_OPERATIONAL_RULES.stream()
                    .filter(candidate -> ruleId.equals(candidate.ruleId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "missing prompt policy row for graph rule " + ruleId));
            matches.append("match ")
                    .append((char) ('A' + index))
                    .append(": priority=").append(row.priority())
                    .append("; rule_id=").append(row.ruleId())
                    .append("; disposition=").append(row.disposition())
                    .append(";\n         statement=").append(row.statement())
                    .append('\n');
        }

        String selectionChecklist = ruleOrder.size() == 1
                ? """
                  
                  One-row selection checklist:
                  - The sole matched row is the winner.
                  - Return that row through the declared function; do not narrate the selection.
                  """.stripTrailing()
                : """
                  
                  Unsorted conflict-selection checklist:
                  - The match letters and display order carry zero priority.
                  - The rows are deliberately unsorted; the winning row may appear last.
                  - Read every priority=N field, then compute max(N) numerically.
                  - Select only the row whose priority equals max(N).
                  - Never choose a smaller N merely because its row or enum appears first.
                  - Copy disposition, rule_id, and statement from that same maximum-priority row.
                  """.stripTrailing();

        return """
                Candidate:
                candidate_id: %s
                identity_admission_decision: %s

                The graph produced exactly these operational policy matches:
                %s
                FP&A decision rule:
                - Identity admission is not an operational disposition.
                - Select the one matched row with the greatest numeric priority.
                - Priority selects a row but is not an output field.
                - Copy candidate_id, rule_id, disposition, and statement from their named fields.

                Structured output contract:
                - Call record_graph_verdict exactly once with direct named arguments.
                - Return no explanation.
                %s
                """.formatted(
                        expected.candidateId(),
                        comparison.graphResult().decision(),
                        matches.toString().stripTrailing(),
                        selectionChecklist).trim();
    }

    private static ChatTemplate.Request graphPolicySelectionRequest(
            String reasoningPrompt, ChatTemplate.Tool verdictTool) {
        return ChatTemplate.Request.builder()
                .messages(List.of(
                        ChatTemplate.Message.system("""
                                Select among the graph-matched FP&A rows silently and call exactly one
                                declared function. When multiple rows exist, they and their enum values
                                are deliberately unsorted: inspect every priority=N and select the
                                numerically greatest N, regardless of display order. Copy the selected
                                candidate_id, disposition, rule_id, and statement into direct named
                                arguments and return no explanation.
                                """.trim()),
                        ChatTemplate.Message.user(reasoningPrompt)))
                .tools(List.of(verdictTool))
                .toolChoice(ChatTemplate.ToolChoice.REQUIRED)
                .addGenerationPrompt(true)
                .build();
    }

    private static void assertPromptedGraphVerdict(
            ChatGenerationResult generated,
            ChatTemplate.Tool verdictTool,
            GraphOperationalVerdict expected) {
        String candidateId = expected.candidateId();
        assertTrue(generated.getParseErrors().isEmpty(),
                "LFM2.5 emitted an invalid native tool call for " + candidateId + ": "
                        + generated.getParseErrors());
        assertEquals(1, generated.getToolCalls().size(),
                "LFM2.5 must select exactly one operational verdict for " + candidateId);

        ChatTemplate.ToolCall call = generated.getToolCalls().get(0);
        assertEquals(verdictTool.getName(), call.getName());
        Map<String, Object> actualArguments = call.getArguments();
        assertFalse(actualArguments.keySet().stream().anyMatch(
                        Set.of("parameters", "properties", "additionalProperties")::contains),
                "tool-schema metadata leaked into the argument envelope for " + candidateId);

        Map<String, Object> expectedArguments = expected.toToolArguments();
        assertEquals(expectedArguments.keySet(), actualArguments.keySet(),
                "LFM2.5 emitted unexpected verdict fields for " + candidateId);
        assertEquals(expectedArguments.get("candidate_id"),
                actualArguments.get("candidate_id"));
        assertEquals(expectedArguments.get("disposition"),
                actualArguments.get("disposition"),
                "LFM2.5 selected the wrong disposition for " + candidateId);
        assertEquals(expectedArguments.get("rule_id"), actualArguments.get("rule_id"),
                "LFM2.5 selected the wrong graph policy match for " + candidateId);
        assertTrue(actualArguments.get("statement") instanceof String,
                "LFM2.5 omitted the graph policy statement for " + candidateId);
        String actualStatement = (String) actualArguments.get("statement");
        assertEquals(expected.statement().replaceFirst("[.!?]+$", ""),
                actualStatement.replaceFirst("[.!?]+$", ""),
                "LFM2.5 changed the winning graph evidence statement for " + candidateId);
    }

    private static VerdictAccuracySample scorePromptedGraphVerdict(
            ChatGenerationResult generated,
            ChatTemplate.Tool verdictTool,
            GraphOperationalVerdict expected) {
        boolean parsed = generated.getParseErrors().isEmpty()
                && generated.getToolCalls().size() == 1
                && verdictTool.getName().equals(generated.getToolCalls().get(0).getName());

        Map<String, Object> actualArguments = parsed
                ? generated.getToolCalls().get(0).getArguments()
                : Map.of();
        Map<String, Object> expectedArguments = expected.toToolArguments();
        boolean schemaClean = parsed
                && expectedArguments.keySet().equals(actualArguments.keySet())
                && actualArguments.keySet().stream().noneMatch(
                        Set.of("parameters", "properties", "additionalProperties")::contains);

        String actualCandidate = Objects.toString(
                actualArguments.get("candidate_id"), "");
        String actualDisposition = Objects.toString(
                actualArguments.get("disposition"), "");
        String actualRule = Objects.toString(
                actualArguments.get("rule_id"), "");
        String actualStatement = Objects.toString(
                actualArguments.get("statement"), "");

        boolean candidateCorrect = expected.candidateId().equals(actualCandidate);
        boolean dispositionCorrect = expected.disposition().name().equals(actualDisposition);
        boolean ruleCorrect = expected.ruleId().equals(actualRule);
        double statementSimilarity =
                statementSimilarity(expected.statement(), actualStatement);
        boolean statementCorrect = statementSimilarity >= 0.80;

        OperationalRuleRow selectedRow = FPNA_OPERATIONAL_RULES.stream()
                .filter(row -> row.ruleId().equals(actualRule))
                .findFirst()
                .orElse(null);
        boolean rowCoherent = selectedRow != null
                && expected.supportingRuleIds().contains(actualRule)
                && selectedRow.disposition().name().equals(actualDisposition)
                && statementSimilarity(selectedRow.statement(), actualStatement) >= 0.80;
        boolean exactVerdict = parsed
                && schemaClean
                && candidateCorrect
                && dispositionCorrect
                && ruleCorrect
                && statementCorrect;

        return new VerdictAccuracySample(
                expected.candidateId(),
                expected.ruleId(),
                expected.disposition(),
                expected.supportingRuleIds().size() > 1,
                parsed,
                parsed,
                schemaClean,
                candidateCorrect,
                dispositionCorrect,
                ruleCorrect,
                statementCorrect,
                statementSimilarity,
                rowCoherent,
                exactVerdict,
                actualCandidate,
                actualDisposition,
                actualRule,
                generated.getParseErrors().toString());
    }

    private static double statementSimilarity(String expected, String actual) {
        Set<String> expectedTokens = normalizedStatementTokens(expected);
        Set<String> actualTokens = normalizedStatementTokens(actual);
        if (expectedTokens.isEmpty()) {
            return actualTokens.isEmpty() ? 1.0 : 0.0;
        }
        long overlap = expectedTokens.stream().filter(actualTokens::contains).count();
        return (double) overlap / expectedTokens.size();
    }

    private static Set<String> normalizedStatementTokens(String statement) {
        if (statement == null || statement.isBlank()) {
            return Set.of();
        }
        return java.util.Arrays.stream(statement.toLowerCase(Locale.ROOT)
                        .split("[^\\p{L}\\p{N}]+"))
                .map(String::trim)
                .filter(token -> !token.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static double accuracyFloor(String property, double defaultValue) {
        double value = Double.parseDouble(
                System.getProperty(property, Double.toString(defaultValue)));
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(
                    property + " must be a finite value from 0.0 through 1.0: " + value);
        }
        return value;
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 1.0 : (double) numerator / denominator;
    }

    private static AdmissionComparison compare(UnifiedGraph graph, ExtractedEntity entity) {
        ExtractionResult extraction = new ExtractionResult(
                GraphExtractionSchema.SCHEMA_VERSION,
                List.of(entity),
                List.of(),
                new GraphExtractionSchema.ExtractionMetadata(
                        "fpna-golden-probe", "fpna-golden", "none", "now"));
        List<AdmissionComparison> comparisons = new ArrayList<>();
        ExtractionAdmissionComparator.compare(
                extraction,
                null,
                graph,
                "fpna-golden-v1",
                AdmissionMode.SHADOW_COMPARE,
                comparisons::add);
        assertEquals(1, comparisons.size());
        return comparisons.get(0);
    }

    private static AdmissionComparison compareExisting(GoldenDomain domain, String key) {
        DomainEntity entity = domain.entity(key);
        return compare(
                domain.toGraph(),
                new ExtractedEntity(
                        key,
                        entity.name(),
                        typeFor(key),
                        entity.aliases(),
                        "golden existing-entity probe",
                        0.95,
                        Map.of("goldenKey", key)));
    }

    private static AdmissionEvidence evidenceByRule(AdmissionComparison comparison,
                                                    String ruleId) {
        return comparison.graphResult().evidenceTrace().items().stream()
                .filter(item -> ruleId.equals(item.ruleId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "missing admission evidence rule " + ruleId + ": "
                                + comparison.graphResult().evidenceTrace()));
    }

    private static void addProspectiveScenario(UnifiedGraph graph) {
        graph.addEntity(GraphEntity.builder("scenario-fy27-upside")
                .type("SCENARIO")
                .label("FY27 upside scenario")
                .weight(0.35)
                .confidence(0.35)
                .attribute("canonicalKey", canonicalKey(
                        "FY27 upside scenario", "SCENARIO", List.of("Upside plan")))
                .build());
    }

    private static double score(UnifiedGraph graph, String entityId) {
        return new HybridReasoner().rank(graph).stream()
                .filter(item -> entityId.equals(item.entityId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing hybrid score for " + entityId))
                .score();
    }

    private static double twoNodeSourceScore(DomainRelation relation, String predicate) {
        UnifiedGraph graph = new UnifiedGraph().graphId("fpna-predicate-boundary");
        graph.addEntity(GraphEntity.builder(relation.source())
                .type("SHEET")
                .label(relation.source())
                .weight(0.80)
                .confidence(0.80)
                .build());
        graph.addEntity(GraphEntity.builder(relation.target())
                .type("STATUS")
                .label(relation.target())
                .weight(0.80)
                .confidence(0.80)
                .build());
        graph.addRelation(SimpleGraphRelation.directed(
                "domain-predicate", relation.source(), relation.target(), predicate, 0.95));
        return score(graph, relation.source());
    }

    private static GoldenDomain loadDomain() throws Exception {
        ClassLoader loader = FpnaGoldenGraphHybridAdmissionTest.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(GOLDEN_RESOURCE)) {
            assertNotNull(input, "missing golden resource " + GOLDEN_RESOURCE);
            JsonNode root = MAPPER.readTree(input);
            Map<String, DomainEntityAccumulator> entities = new LinkedHashMap<>();
            List<DomainRelation> relations = new ArrayList<>();
            List<ForbiddenMerge> forbiddenMerges = new ArrayList<>();
            int caseCount = 0;

            JsonNode cases = root.path("cases");
            assertTrue(cases.isArray(), "golden cases must be an array");
            for (JsonNode caseNode : cases) {
                caseCount++;
                String caseId = requiredText(caseNode, "id");
                for (JsonNode entityNode : caseNode.path("expectedEntities")) {
                    String key = requiredText(entityNode, "key");
                    String name = requiredText(entityNode, "name");
                    LinkedHashSet<String> acceptedNames = new LinkedHashSet<>();
                    acceptedNames.add(name);
                    for (JsonNode accepted : entityNode.path("acceptedNames")) {
                        if (accepted.isTextual() && !accepted.asText().isBlank()) {
                            acceptedNames.add(accepted.asText().trim());
                        }
                    }
                    entities.computeIfAbsent(key, ignored -> new DomainEntityAccumulator(key, name))
                            .merge(acceptedNames, caseId, entityNode.path("critical").asBoolean(false));
                }

                int relationIndex = 0;
                for (JsonNode relationNode : caseNode.path("expectedRelations")) {
                    relations.add(new DomainRelation(
                            caseId + ":" + relationIndex++,
                            caseId,
                            requiredText(relationNode, "source"),
                            requiredText(relationNode, "target"),
                            requiredText(relationNode, "canonicalPredicate"),
                            relationNode.path("critical").asBoolean(false)));
                }
                readForbiddenMerges(caseNode.path("forbiddenMerges"), caseId, forbiddenMerges);
            }
            readForbiddenMerges(root.path("forbiddenMerges"), "suite", forbiddenMerges);

            Map<String, DomainEntity> frozenEntities = new LinkedHashMap<>();
            entities.forEach((key, value) -> frozenEntities.put(key, value.freeze()));
            return new GoldenDomain(
                    caseCount,
                    Map.copyOf(frozenEntities),
                    List.copyOf(relations),
                    List.copyOf(forbiddenMerges));
        }
    }

    private static void readForbiddenMerges(JsonNode node,
                                            String scope,
                                            List<ForbiddenMerge> output) {
        if (!node.isArray()) {
            return;
        }
        for (JsonNode item : node) {
            output.add(new ForbiddenMerge(
                    scope,
                    requiredText(item, "left"),
                    requiredText(item, "right"),
                    item.path("reason").asText("forbidden merge")));
        }
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value == null || value.isBlank()) {
            throw new AssertionError("missing golden field '" + field + "' in " + node);
        }
        return value.trim();
    }

    private static String typeFor(String key) {
        int separator = key.indexOf('-');
        String prefix = separator < 0 ? key : key.substring(0, separator);
        return switch (prefix) {
            case "workbook" -> "WORKBOOK";
            case "sheet" -> "SHEET";
            case "person" -> "PERSON";
            case "email" -> "EMAIL";
            case "version" -> "VERSION";
            case "status" -> "STATUS";
            case "issue" -> "ISSUE";
            case "period" -> "PERIOD";
            case "currency" -> "CURRENCY";
            case "scale" -> "VALUE_SCALE";
            case "tax" -> "TAX_BASIS";
            case "process" -> "PROCESS";
            case "fx" -> "FX_RATE_SET";
            case "channel" -> "CHANNEL";
            case "sku" -> "SKU_MAPPING";
            case "assumption" -> "ASSUMPTION";
            case "risk" -> "RISK";
            case "document" -> "DOCUMENT";
            case "policy" -> "POLICY";
            case "threshold" -> "THRESHOLD";
            case "action" -> "ACTION";
            case "pattern" -> "PATTERN";
            case "archive" -> "ARCHIVE";
            case "storage" -> "STORAGE";
            case "retention" -> "RETENTION";
            case "region" -> "REGION";
            default -> "CONCEPT";
        };
    }

    private static String canonicalKey(String name, String type, List<String> aliases) {
        String normalizedAliases = aliases == null ? "" : aliases.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.joining("|"));
        return name.trim().toLowerCase(Locale.ROOT)
                + "\u0000" + type.trim().toLowerCase(Locale.ROOT)
                + (normalizedAliases.isBlank() ? "" : "\u0000" + normalizedAliases);
    }

    private static ComponentStats componentStats(UnifiedGraph graph) {
        Map<String, Set<String>> adjacency = new HashMap<>();
        graph.entities().forEach(entity -> adjacency.put(entity.id(), new HashSet<>()));
        for (GraphRelation relation : graph.relations()) {
            adjacency.computeIfAbsent(relation.sourceId(), ignored -> new HashSet<>())
                    .add(relation.targetId());
            adjacency.computeIfAbsent(relation.targetId(), ignored -> new HashSet<>())
                    .add(relation.sourceId());
        }

        Set<String> visited = new HashSet<>();
        int components = 0;
        int largest = 0;
        for (String start : adjacency.keySet()) {
            if (!visited.add(start)) {
                continue;
            }
            components++;
            int size = 0;
            ArrayDeque<String> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                String current = queue.removeFirst();
                size++;
                for (String next : adjacency.getOrDefault(current, Set.of())) {
                    if (visited.add(next)) {
                        queue.addLast(next);
                    }
                }
            }
            largest = Math.max(largest, size);
        }
        return new ComponentStats(components, largest);
    }

    private record VerdictAccuracySample(
            String candidateId,
            String expectedRule,
            OperationalDisposition expectedDisposition,
            boolean competingRules,
            boolean structuredCall,
            boolean parsed,
            boolean schemaClean,
            boolean candidateCorrect,
            boolean dispositionCorrect,
            boolean ruleCorrect,
            boolean statementCorrect,
            double statementSimilarity,
            boolean rowCoherent,
            boolean exactVerdict,
            String actualCandidate,
            String actualDisposition,
            String actualRule,
            String parseErrors) {

        private String failureDetail() {
            return candidateId
                    + "{expected=" + expectedDisposition + "/" + expectedRule
                    + ", actual=" + actualDisposition + "/" + actualRule
                    + ", actualCandidate=" + actualCandidate
                    + ", envelope=" + structuredCall
                    + ", parsed=" + parsed
                    + ", schema=" + schemaClean
                    + ", statement=" + statementSimilarity
                    + ", rowCoherent=" + rowCoherent
                    + ", parseErrors=" + parseErrors + "}";
        }
    }

    private record VerdictAccuracySummary(
            int total,
            int competingTotal,
            double structuredCallRate,
            double parseRate,
            double schemaRate,
            double candidateRate,
            double dispositionRate,
            double ruleRate,
            double statementRate,
            double rowCoherenceRate,
            double exactVerdictRate,
            double competingRuleAccuracy,
            double macroRuleAccuracy,
            Map<String, Double> perRuleAccuracy,
            List<String> failures) {

        private static VerdictAccuracySummary from(
                List<VerdictAccuracySample> samples) {
            if (samples.isEmpty()) {
                throw new IllegalArgumentException(
                        "at least one accuracy sample is required");
            }

            int structuredCall = 0;
            int parsed = 0;
            int schema = 0;
            int candidate = 0;
            int disposition = 0;
            int rule = 0;
            int statement = 0;
            int rowCoherent = 0;
            int exact = 0;
            int competing = 0;
            int competingRuleCorrect = 0;
            Map<String, int[]> perRuleCounts = new LinkedHashMap<>();
            List<String> failures = new ArrayList<>();

            for (VerdictAccuracySample sample : samples) {
                structuredCall += sample.structuredCall() ? 1 : 0;
                parsed += sample.parsed() ? 1 : 0;
                schema += sample.schemaClean() ? 1 : 0;
                candidate += sample.candidateCorrect() ? 1 : 0;
                disposition += sample.dispositionCorrect() ? 1 : 0;
                rule += sample.ruleCorrect() ? 1 : 0;
                statement += sample.statementCorrect() ? 1 : 0;
                rowCoherent += sample.rowCoherent() ? 1 : 0;
                exact += sample.exactVerdict() ? 1 : 0;
                if (sample.competingRules()) {
                    competing++;
                    competingRuleCorrect += sample.ruleCorrect() ? 1 : 0;
                }
                int[] counts = perRuleCounts.computeIfAbsent(
                        sample.expectedRule(), ignored -> new int[2]);
                counts[1]++;
                counts[0] += sample.ruleCorrect() ? 1 : 0;
                if (!sample.exactVerdict()) {
                    failures.add(sample.failureDetail());
                }
            }

            Map<String, Double> perRuleAccuracy = new LinkedHashMap<>();
            perRuleCounts.forEach((ruleId, counts) ->
                    perRuleAccuracy.put(ruleId, ratio(counts[0], counts[1])));
            double macroRuleAccuracy = perRuleAccuracy.values().stream()
                    .mapToDouble(Double::doubleValue)
                    .average()
                    .orElse(1.0);
            int total = samples.size();
            return new VerdictAccuracySummary(
                    total,
                    competing,
                    ratio(structuredCall, total),
                    ratio(parsed, total),
                    ratio(schema, total),
                    ratio(candidate, total),
                    ratio(disposition, total),
                    ratio(rule, total),
                    ratio(statement, total),
                    ratio(rowCoherent, total),
                    ratio(exact, total),
                    ratio(competingRuleCorrect, competing),
                    macroRuleAccuracy,
                    Map.copyOf(perRuleAccuracy),
                    List.copyOf(failures));
        }

        private String failureMessage(String metric) {
            return metric + " fell below its configured floor: " + this;
        }
    }

    private record OperationalRuleRow(
            String ruleId,
            OperationalDisposition disposition,
            int priority,
            String statement) {
    }

    private record ComponentStats(int componentCount, int largestComponent) {
    }

    private record DomainEntity(
            String key,
            String name,
            Set<String> acceptedNames,
            Set<String> caseIds,
            boolean critical) {

        private List<String> aliases() {
            return acceptedNames.stream()
                    .filter(value -> !name.equalsIgnoreCase(value))
                    .sorted()
                    .toList();
        }
    }

    private static final class DomainEntityAccumulator {
        private final String key;
        private final String name;
        private final Set<String> acceptedNames = new LinkedHashSet<>();
        private final Set<String> caseIds = new LinkedHashSet<>();
        private boolean critical;

        private DomainEntityAccumulator(String key, String name) {
            this.key = key;
            this.name = name;
        }

        private void merge(Set<String> names, String caseId, boolean required) {
            acceptedNames.addAll(names);
            caseIds.add(caseId);
            critical |= required;
        }

        private DomainEntity freeze() {
            return new DomainEntity(
                    key,
                    name,
                    Set.copyOf(acceptedNames),
                    Set.copyOf(caseIds),
                    critical);
        }
    }

    private record DomainRelation(
            String id,
            String caseId,
            String source,
            String target,
            String predicate,
            boolean critical) {
    }

    private record ForbiddenMerge(String scope, String left, String right, String reason) {
    }

    private record GoldenDomain(
            int caseCount,
            Map<String, DomainEntity> entities,
            List<DomainRelation> relations,
            List<ForbiddenMerge> forbiddenMerges) {

        private DomainEntity entity(String key) {
            DomainEntity entity = entities.get(key);
            if (entity == null) {
                throw new AssertionError("golden entity is absent: " + key);
            }
            return entity;
        }

        private int materializedForbiddenMergeCount() {
            return (int) forbiddenMerges.stream()
                    .filter(item -> entities.containsKey(item.left()) && entities.containsKey(item.right()))
                    .count();
        }

        private UnifiedGraph toGraph() {
            UnifiedGraph graph = new UnifiedGraph().graphId("fpna-golden-domain-v1");
            entities.values().stream()
                    .sorted(Comparator.comparing(DomainEntity::key))
                    .forEach(entity -> {
                        String type = typeFor(entity.key());
                        List<String> aliases = entity.aliases();
                        graph.addEntity(GraphEntity.builder(entity.key())
                                .type(type)
                                .label(entity.name())
                                .weight(entity.critical() ? 1.0 : 0.80)
                                .confidence(entity.critical() ? 1.0 : 0.80)
                                .attribute("canonicalKey", canonicalKey(entity.name(), type, aliases))
                                .attribute("goldenKey", entity.key())
                                .attribute("acceptedNames", List.copyOf(entity.acceptedNames()))
                                .attribute("caseIds", List.copyOf(entity.caseIds()))
                                .build());
                    });

            for (DomainRelation relation : relations) {
                assertTrue(entities.containsKey(relation.source()),
                        "golden relation source is missing: " + relation);
                assertTrue(entities.containsKey(relation.target()),
                        "golden relation target is missing: " + relation);
                graph.addRelation(new SimpleGraphRelation(
                        "golden:" + relation.id(),
                        relation.source(),
                        relation.target(),
                        relation.predicate(),
                        1.0,
                        relation.critical() ? 1.0 : 0.80,
                        true,
                        Set.of("golden", relation.caseId()),
                        null,
                        null,
                        Map.of("caseId", relation.caseId())));
            }

            int index = 0;
            for (ForbiddenMerge forbidden : forbiddenMerges) {
                if (!entities.containsKey(forbidden.left()) || !entities.containsKey(forbidden.right())) {
                    continue;
                }
                graph.addRelation(new SimpleGraphRelation(
                        "golden:forbidden:" + index++,
                        forbidden.left(),
                        forbidden.right(),
                        "DIFFERENT_FROM",
                        1.0,
                        1.0,
                        false,
                        Set.of("golden", "forbidden-merge"),
                        null,
                        null,
                        Map.of("scope", forbidden.scope(), "reason", forbidden.reason())));
            }
            return graph;
        }
    }
}
