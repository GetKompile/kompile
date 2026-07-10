package ai.kompile.samples.fpna;

import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.quantitative.DimensionAliasResolver;
import ai.kompile.graph.reasoning.quantitative.GraphExecutableModelRetriever;
import ai.kompile.graph.reasoning.quantitative.GraphQuantitativeRuleCatalog;
import ai.kompile.graph.reasoning.quantitative.ModelRetrieval;
import ai.kompile.graph.reasoning.quantitative.QuantitativeMeasureSynthesizer;
import ai.kompile.graph.reasoning.quantitative.QuantitativeQuery;
import ai.kompile.graph.reasoning.quantitative.QuantitativeRule;
import ai.kompile.graph.reasoning.quantitative.QuantitativeScenarioEngine;
import ai.kompile.graph.reasoning.quantitative.ScenarioResult;
import ai.kompile.graph.reasoning.unified.MiniJson;
import ai.kompile.loader.excel.graph.ExcelFormulaGraphExtractor;
import ai.kompile.loader.excel.graph.SpreadsheetGraph;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Graph-only quantitative feasibility evaluation over the original FP&A workbooks.
 *
 * <p>Workbooks are used once to build the graph. Everything downstream — measure synthesis,
 * retrieval, scenario execution, goal seeking — receives only the resulting ReasoningGraph.</p>
 *
 * <p>Question phrasing notes: the user's colloquial "US" maps to this dataset's {@code AMER}
 * vocabulary (the AMER workbook is Northstar's US operation; bridging that colloquialism
 * automatically remains an embedding/alias concern). "Sales -16%" is modeled as a revenue scale
 * for Q2 and as the equivalent units scale for Q3, where volume must stay the live control.</p>
 */
public final class FpnaQuantitativeExperiment {

    /**
     * 04a (pre-triage AMER May26) is intentionally excluded: it duplicates 04c's grain and two
     * copies of the same SKU grid would correctly re-introduce cross-workbook ambiguity.
     */
    private static final List<String> WORKBOOKS = List.of(
            "04b_Group_3MoP&L_Output_May26.xlsx",
            "04c_AMER_Forecast_May26_post_triage.xlsx",
            "05a_AMER_Forecast_Q3_v3_FINAL_v2.xlsx",
            "05b_EMEA forecast Jun-Aug 2026.xlsx",
            "05c_APAC fcst FY27Q1.xlsx",
            "05d_FPA_Consolidation_Template.xlsx");

    private static final List<ProductChange> US_JULY_CHANGES = List.of(
            new ProductChange("Restful Bath Salt 16oz", -0.16),
            new ProductChange("Hydrate Daily Set", -0.20),
            new ProductChange("Restful Eye Mask", -0.31),
            new ProductChange("Grove Reed Diffuser", -0.23));

    private FpnaQuantitativeExperiment() {
    }

    public static void main(String[] args) throws Exception {
        Path dataDir = dataDirectory();
        MutableReasoningGraph graph = loadGraph(dataDir);
        List<QuantitativeMeasureSynthesizer.SynthesizedMeasure> synthesized =
                new QuantitativeMeasureSynthesizer().synthesize(graph);
        List<QuantitativeRule> rules = new GraphQuantitativeRuleCatalog().rules(graph);

        System.out.printf(Locale.ROOT,
                "FP&A quantitative graph: %,d entities, %,d relations, %,d executable rules "
                        + "(%,d synthesized rollups)%n",
                graph.entityCount(), graph.relationCount(), rules.size(), synthesized.size());
        for (QuantitativeMeasureSynthesizer.SynthesizedMeasure measure : synthesized) {
            System.out.printf(Locale.ROOT, "  synthesized %-70s pairs=%d%n",
                    measure.label(), measure.pairCount());
        }
        System.out.println();

        // Colloquial vocabulary bridging: if a local model is serving, ask the questions in the
        // user's own words ("US") and let the resolver map them onto graph members ("AMER"). The
        // resolver only ever answers closed-set questions built from graph-derived vocabulary.
        System.out.println("Graph-derived region vocabulary (the closed set an alias resolver"
                + " chooses from): "
                + ai.kompile.graph.reasoning.quantitative.DimensionVocabulary.members(
                        graph, "region"));
        DimensionAliasResolver aliasResolver = StagingLlmAliasResolver.probe(
                System.getProperty("fpna.staging.url", "http://localhost:8091")).orElse(null);
        String region;
        GraphExecutableModelRetriever retriever;
        if (aliasResolver == null) {
            System.out.println("No local model serving detected; asking with graph vocabulary"
                    + " ('AMER'). Start the staging LLM to exercise the alias resolver with 'US'.");
            region = "AMER";
            retriever = new GraphExecutableModelRetriever();
        } else {
            System.out.println("Local model detected; asking with the user's vocabulary ('US')"
                    + " through the LLM alias resolver.");
            region = "US";
            retriever = new GraphExecutableModelRetriever().withAliasResolver(aliasResolver);
        }
        System.out.println();
        QuantitativeScenarioEngine engine = new QuantitativeScenarioEngine();
        int executable = 0;

        // ── Q1: APAC sales forecast +25% → 3-month P&L ─────────────────────────
        QuantitativeQuery q1 = QuantitativeQuery.scenario(
                new QuantitativeQuery.MeasureSelector(
                        null, "Total net revenue 3-mo total", null, null,
                        Map.of("entity", "Total")),
                List.of(new QuantitativeQuery.Intervention(
                        QuantitativeQuery.MeasureSelector.text("APAC net revenue"),
                        QuantitativeQuery.Operation.SCALE, 0.25)),
                Map.of());
        System.out.println("Q1 APAC +25% -> 3-month P&L (group net revenue line)");
        ModelRetrieval q1Retrieval = retriever.retrieve(graph, q1);
        printRetrieval(graph, q1Retrieval);
        if (q1Retrieval.status() == ModelRetrieval.Status.READY) {
            ScenarioResult result = engine.execute(graph, q1Retrieval);
            System.out.printf(Locale.ROOT,
                    "  execution=%s baseline=%,.0f scenario=%,.0f delta=%,.0f (USD, 3-mo)%n",
                    result.status(), result.baselineValue(), result.scenarioValue(),
                    result.delta());
            System.out.println("  note: gross profit / EBIT propagation still needs APAC cost"
                    + " behavior facts; the graph carries none, so only the revenue line moves.");
            if (result.status() != ScenarioResult.Status.FAILED) {
                executable++;
            }
        }
        System.out.println();

        // ── Q2: US July product sales changes → gross margin ──────────────────
        QuantitativeQuery q2 = QuantitativeQuery.scenario(
                QuantitativeQuery.MeasureSelector.text("Gross margin % Jul"),
                US_JULY_CHANGES.stream().map(change -> new QuantitativeQuery.Intervention(
                        QuantitativeQuery.MeasureSelector.text(change.product() + " Jul revenue"),
                        QuantitativeQuery.Operation.SCALE, change.scale())).toList(),
                Map.of("region", region));
        System.out.println("Q2 US(AMER) July product sales changes -> gross margin");
        ModelRetrieval q2Retrieval = retriever.retrieve(graph, q2);
        printRetrieval(graph, q2Retrieval);
        Double q2BaselineGrossProfit = null;
        if (q2Retrieval.status() == ModelRetrieval.Status.READY) {
            ScenarioResult result = engine.execute(graph, q2Retrieval);
            System.out.printf(Locale.ROOT,
                    "  execution=%s baselineGM=%.4f%% scenarioGM=%.4f%% delta=%+.1f bps%n",
                    result.status(), 100.0 * result.baselineValue(),
                    100.0 * result.scenarioValue(),
                    10_000.0 * (result.scenarioValue() - result.baselineValue()));
            if (result.status() != ScenarioResult.Status.FAILED) {
                executable++;
            }
        }
        // The margin *rate* barely moves (the cut products skew below the AMER blend), so the
        // dollars view completes the answer: the same interventions against the synthesized
        // gross-profit aggregate.
        QuantitativeQuery q2GrossProfit = QuantitativeQuery.scenario(
                QuantitativeQuery.MeasureSelector.text("Gross profit Jul"),
                q2.interventions(),
                Map.of("region", region));
        ModelRetrieval q2GrossProfitRetrieval = retriever.retrieve(graph, q2GrossProfit);
        if (q2GrossProfitRetrieval.status() == ModelRetrieval.Status.READY) {
            ScenarioResult result = engine.execute(graph, q2GrossProfitRetrieval);
            q2BaselineGrossProfit = result.baselineValue();
            System.out.printf(Locale.ROOT,
                    "  gross profit: baseline=%,.0f scenario=%,.0f delta=%,.0f USD%n",
                    result.baselineValue(), result.scenarioValue(), result.delta());
        }
        System.out.println();

        // ── Q3: DTC volume needed to restore the pre-change gross profit ──────
        // Restoring the margin *rate* would mean cutting high-margin DTC volume (the rate went
        // UP); what the cuts actually destroy is gross-profit dollars, so that is the goal.
        System.out.println("Q3 DTC volume needed to restore pre-change July gross profit"
                + " (two scenarios)");
        if (q2BaselineGrossProfit == null) {
            System.out.println("  skipped: Q2 baseline gross profit unavailable.");
        } else {
            QuantitativeQuery q3 = QuantitativeQuery.solveTarget(
                    QuantitativeQuery.MeasureSelector.text("Gross profit Jul"),
                    US_JULY_CHANGES.stream().map(change -> new QuantitativeQuery.Intervention(
                            QuantitativeQuery.MeasureSelector.text(change.product() + " Jul units"),
                            QuantitativeQuery.Operation.SCALE, change.scale())).toList(),
                    Map.of("region", region),
                    new QuantitativeQuery.Goal(
                            new QuantitativeQuery.MeasureSelector(
                                    null, "Jul units", null, null, Map.of("channel", "DTC")),
                            q2BaselineGrossProfit, 0.0, 500_000.0, 1.0e-4, 200));
            ModelRetrieval q3Retrieval = retriever.retrieve(graph, q3);
            printRetrieval(graph, q3Retrieval);
            if (q3Retrieval.status() == ModelRetrieval.Status.READY) {
                QuantitativeScenarioEngine.GoalSeekResult result =
                        engine.solveTarget(graph, q3Retrieval);
                System.out.printf(Locale.ROOT,
                        "  goalSeek=%s targetGrossProfit=%,.0f USD solvedControls=%d/%d%n",
                        result.status(), q2BaselineGrossProfit,
                        result.alternatives().stream().filter(alternative -> alternative.status()
                                == QuantitativeScenarioEngine.GoalSeekResult.Status.SOLVED).count(),
                        result.alternatives().size());
                int scenarios = 0;
                for (QuantitativeScenarioEngine.GoalSeekResult.Alternative alternative
                        : result.alternatives()) {
                    if (alternative.status()
                            != QuantitativeScenarioEngine.GoalSeekResult.Status.SOLVED
                            || scenarios == 2) {
                        continue;
                    }
                    scenarios++;
                    double baseline = alternative.baselineControlValue() == null
                            ? Double.NaN : alternative.baselineControlValue();
                    double postCut = postCutBaseline(
                            q3Retrieval, alternative.controlEntityId(), baseline);
                    System.out.printf(Locale.ROOT,
                            "  scenario %d: %s%n"
                                    + "    units: baseline=%,.0f post-cut=%,.0f required=%,.0f"
                                    + " (%+,.0f vs post-cut) achievedGrossProfit=%,.0f USD%n",
                            scenarios, alternative.controlLabel(),
                            baseline, postCut, alternative.controlValue(),
                            alternative.controlValue() - postCut,
                            alternative.achievedValue());
                }
                if (result.status() == QuantitativeScenarioEngine.GoalSeekResult.Status.SOLVED
                        && scenarios >= 2) {
                    executable++;
                }
            }
        }
        System.out.println();

        // ── Named-table entity demos ───────────────────────────────────────────
        System.out.println("Named tables -> typed entities");
        Map<String, Integer> memberTypeCounts = new LinkedHashMap<>();
        for (GraphEntity entity : graph.entities()) {
            if (Boolean.TRUE.equals(entity.attributes().get("tableMember"))) {
                memberTypeCounts.merge(entity.type(), 1, Integer::sum);
            }
        }
        System.out.println("  typed members: " + memberTypeCounts);

        GraphEntity diffuser = graph.entities().stream()
                .filter(entity -> "GRO-203".equals(entity.attributes().get("memberKey")))
                .findFirst().orElse(null);
        if (diffuser != null) {
            Map<String, Integer> appearances = new LinkedHashMap<>();
            for (GraphEntity entity : graph.entities()) {
                Object dimensions = entity.attributes().get("dimensions");
                if (!(dimensions instanceof Map<?, ?> map)) {
                    continue;
                }
                boolean references = map.values().stream().anyMatch(value ->
                        "GRO-203".equalsIgnoreCase(String.valueOf(value))
                                || diffuser.label().equalsIgnoreCase(String.valueOf(value)));
                if (references) {
                    Object workbook = map.get("workbook");
                    appearances.merge(
                            workbook == null ? "?" : String.valueOf(workbook), 1, Integer::sum);
                }
            }
            System.out.println("  identity: SKU GRO-203 = '" + diffuser.label()
                    + "' referenced by cells in: " + appearances);
        }

        // OWL schema integration: the TBox travels WITH the graph as Turtle (one axiom: table
        // SKU is a subclass of Product). The bridge loads it back, merges table-derived classes
        // and reference properties, runs OWL 2 RL, and materializes the closure. The "product"
        // dimension vocabulary then includes every SKU member through the schema, not tokens.
        List<String> productVocabularyBefore =
                ai.kompile.graph.reasoning.quantitative.DimensionVocabulary.members(
                        graph, "product");
        ai.kompile.graph.reasoning.mebn.type.owl.OwlOntology declaredTbox =
                ai.kompile.graph.reasoning.mebn.type.owl.OwlOntology.of("urn:kompile:schema")
                        .addClass(ai.kompile.graph.reasoning.mebn.type.owl.OwlClass
                                .of("urn:kompile:schema#Product").build())
                        .addClass(ai.kompile.graph.reasoning.mebn.type.owl.OwlClass
                                .of(ai.kompile.graph.reasoning.mebn.type.owl
                                        .TableMemberOntologyBridge.TABLE_CLASS_IRI_PREFIX + "SKU")
                                .subClassOf("urn:kompile:schema#Product").build())
                        .build();
        graph.addEntity(GraphEntity.builder("ontology:declared")
                .type("ONTOLOGY").label("Declared FP&A schema")
                .attribute(ai.kompile.graph.reasoning.mebn.type.owl.TableMemberOntologyBridge
                                .ONTOLOGY_TURTLE_ATTRIBUTE,
                        new ai.kompile.graph.reasoning.mebn.type.owl.OwlTurtleWriter()
                                .write(declaredTbox))
                .build());
        ai.kompile.graph.reasoning.mebn.type.owl.OwlOntology mergedOntology =
                ai.kompile.graph.reasoning.mebn.type.owl.TableMemberOntologyBridge
                        .ontologyFromGraph(graph);
        ai.kompile.graph.reasoning.mebn.type.owl.OwlRlResult owlResult =
                ai.kompile.graph.reasoning.mebn.type.owl.TableMemberOntologyBridge
                        .materializeInferredTypes(graph, mergedOntology);
        List<String> productVocabularyAfter =
                ai.kompile.graph.reasoning.quantitative.DimensionVocabulary.members(
                        graph, "product");
        long memberReferences = graph.relations().stream()
                .filter(relation -> Boolean.TRUE.equals(
                        relation.attributes().get("memberReference")))
                .count();
        System.out.printf(Locale.ROOT,
                "  OWL: Turtle-carried TBox (SKU subClassOf Product); merged ontology has %d"
                        + " classes, %d object properties; RL inferred %d type memberships (%s);"
                        + " member references=%d; 'product' vocabulary %d -> %d members%n",
                mergedOntology.classes().size(), mergedOntology.objectProperties().size(),
                owlResult.inferredTypeCount(),
                owlResult.isConsistent() ? "consistent" : "INCONSISTENT",
                memberReferences,
                productVocabularyBefore.size(), productVocabularyAfter.size());

        // Dimension scoping by CODE: the cells carry product="Grove Reed Diffuser"; the master
        // table's key<->name alias group makes {product: GRO-203} match them.
        QuantitativeQuery codeScoped = QuantitativeQuery.scenario(
                QuantitativeQuery.MeasureSelector.text("Gross margin % Jul"),
                List.of(new QuantitativeQuery.Intervention(
                        new QuantitativeQuery.MeasureSelector(
                                null, "Jul revenue", null, null, Map.of("product", "GRO-203")),
                        QuantitativeQuery.Operation.SCALE, -0.23)),
                Map.of("region", region));
        ModelRetrieval codeRetrieval = retriever.retrieve(graph, codeScoped);
        String codeResolved = codeRetrieval.resolutions().get("intervention[0]");
        System.out.println("  SKU-code scoped intervention {product: GRO-203} resolved -> "
                + (codeResolved == null ? "unresolved" : codeResolved));
        System.out.println();

        System.out.printf(Locale.ROOT,
                "End-to-end quantitative coverage: %d / 3 questions%n", executable);
    }

    private static void printRetrieval(MutableReasoningGraph graph, ModelRetrieval retrieval) {
        System.out.println("  retrieval=" + retrieval.status()
                + ", candidates=" + retrieval.candidates().size()
                + ", gaps=" + retrieval.gaps().size());
        String target = retrieval.resolutions().get("target");
        if (target != null) {
            System.out.println("  target=" + target
                    + graph.entity(target).map(entity -> " (" + entity.label() + ")").orElse(""));
        }
        String selectionNote = retrieval.resolutions().get("targetSelection");
        if (selectionNote != null) {
            System.out.println("  " + selectionNote);
        }
        retrieval.resolutions().forEach((key, value) -> {
            if (key.startsWith("alias:")) {
                System.out.println("  " + key + " " + value);
            }
        });
        if (retrieval.plan() != null) {
            System.out.println("  interventions=" + retrieval.plan().interventions().size()
                    + ", planRules=" + retrieval.plan().rules().size()
                    + ", goalControls=" + retrieval.plan().goalControlCandidateIds().size());
        }
        retrieval.gaps().forEach(gap -> System.out.println(
                "  gap " + gap.code() + ": " + gap.message()
                        + (gap.details().isEmpty() ? "" : " " + gap.details())));
    }

    /** Post-cut level of a goal control that is itself among the scenario interventions. */
    private static double postCutBaseline(
            ModelRetrieval retrieval, String controlEntityId, double baseline) {
        if (retrieval.plan() == null || Double.isNaN(baseline)) {
            return baseline;
        }
        for (ModelRetrieval.ResolvedIntervention intervention
                : retrieval.plan().interventions()) {
            if (intervention.entityId().equals(controlEntityId)
                    && intervention.operation() == QuantitativeQuery.Operation.SCALE) {
                return baseline * (1.0 + intervention.value());
            }
        }
        return baseline;
    }

    private static MutableReasoningGraph loadGraph(Path dataDir) throws Exception {
        MutableReasoningGraph merged = new MutableReasoningGraph();
        int relationIndex = 0;
        for (String name : WORKBOOKS) {
            Path file = dataDir.resolve(name);
            if (!Files.isRegularFile(file)) {
                System.out.println("Skipping missing workbook: " + file);
                continue;
            }
            try (Workbook workbook = WorkbookFactory.create(file.toFile())) {
                SpreadsheetGraph spreadsheet =
                        new ExcelFormulaGraphExtractor(workbook).extract(workbook, name);
                Graph graph = spreadsheet.toGraph();
                for (Entity entity : graph.getEntities()) {
                    Map<String, Object> attributes = new LinkedHashMap<>();
                    if (entity.getMetadata() != null) {
                        attributes.putAll(entity.getMetadata());
                    }
                    if (entity.getDescription() != null) {
                        attributes.put("description", entity.getDescription());
                    }
                    merged.addEntity(GraphEntity.builder(entity.getId())
                            .type(entity.getType() == null ? "" : entity.getType())
                            .label(entity.getTitle() == null ? entity.getId() : entity.getTitle())
                            .confidence(entity.getConfidence() == null ? 1.0 : entity.getConfidence())
                            .attributes(attributes)
                            .build());
                }
                for (Relationship relationship : graph.getRelationships()) {
                    Map<String, Object> attributes = relationship.getMetadata() == null
                            ? Map.of() : relationship.getMetadata();
                    merged.addRelation(GraphRelation.builder(
                                    "fpna-rel:" + relationIndex++,
                                    relationship.getSource(), relationship.getTarget())
                            .type(relationship.getType() == null ? "" : relationship.getType())
                            .weight(relationship.getWeight() == null ? 1.0 : relationship.getWeight())
                            .confidence(relationship.getConfidence() == null
                                    ? 1.0 : relationship.getConfidence())
                            .attributes(attributes)
                            .build());
                }
            }
        }
        return merged;
    }

    private static Path dataDirectory() {
        String explicit = System.getProperty("fpna.data.dir");
        if (explicit != null && !explicit.isBlank()) {
            return Path.of(explicit).toAbsolutePath().normalize();
        }
        Path sample = Path.of(System.getProperty("fpna.sample.basedir", "."))
                .toAbsolutePath().normalize();
        Path repository = sample.getParent() == null ? sample : sample.getParent();
        return repository.resolve("FP&A workflow artifacts 2026-05");
    }

    private record ProductChange(String product, double scale) {
    }

    /**
     * {@link DimensionAliasResolver} backed by the local kompile serving lane
     * ({@code POST /api/llm/generate}). The prompt is a closed-set selection over graph-derived
     * members, which is exactly the shape a small local model answers reliably; anything that is
     * not one of the offered members is treated as abstention. Answers are cached per question.
     */
    private static final class StagingLlmAliasResolver implements DimensionAliasResolver {

        private final HttpClient http;
        private final String baseUrl;
        private final String modelDescription;
        private final Map<String, Optional<Resolution>> cache = new ConcurrentHashMap<>();

        private StagingLlmAliasResolver(HttpClient http, String baseUrl, String modelDescription) {
            this.http = http;
            this.baseUrl = baseUrl;
            this.modelDescription = modelDescription;
        }

        static Optional<StagingLlmAliasResolver> probe(String baseUrl) {
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(1500)).build();
            try {
                HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                                URI.create(baseUrl + "/api/llm/status"))
                        .timeout(Duration.ofSeconds(3)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    return Optional.empty();
                }
                Map<String, Object> status = MiniJson.parseObject(response.body());
                if (!Boolean.TRUE.equals(status.get("loaded"))) {
                    return Optional.empty();
                }
                Object model = status.getOrDefault("modelName", status.get("modelId"));
                return Optional.of(new StagingLlmAliasResolver(
                        http, baseUrl, model == null ? "local-model" : String.valueOf(model)));
            } catch (Exception unavailable) {
                return Optional.empty();
            }
        }

        @Override
        public Optional<Resolution> resolve(Question question) {
            String key = question.dimensionKey() + "|" + question.requestedValue()
                    + "|" + question.members();
            return cache.computeIfAbsent(key, ignored -> ask(question));
        }

        private Optional<Resolution> ask(Question question) {
            String prompt = "You normalize business reporting vocabulary to a fixed list of"
                    + " known values.\n"
                    + "Dimension: " + question.dimensionKey() + "\n"
                    + "User wrote: \"" + question.requestedValue() + "\"\n"
                    + "Known values: " + String.join(", ", question.members()) + "\n"
                    + "Reply with exactly one known value, or NONE if none of them is what the"
                    + " user meant.\n"
                    + "Answer:";
            try {
                HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                                URI.create(baseUrl + "/api/llm/generate"))
                        .timeout(Duration.ofSeconds(60))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(MiniJson.write(Map.of(
                                "prompt", prompt,
                                "maxTokens", 16,
                                "temperature", 0.0,
                                "doSample", false,
                                "presetName", "greedy"))))
                        .build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    return Optional.empty();
                }
                Map<String, Object> body = MiniJson.parseObject(response.body());
                String finishReason = String.valueOf(body.getOrDefault("finishReason", ""));
                if (finishReason.toLowerCase(Locale.ROOT).startsWith("error")) {
                    return Optional.empty();
                }
                String generated = String.valueOf(body.getOrDefault("generatedText", ""));
                return selectMember(question, generated);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            } catch (Exception failure) {
                return Optional.empty();
            }
        }

        /** First offered member mentioned on the answer's first meaningful line, else abstain. */
        private Optional<Resolution> selectMember(Question question, String generated) {
            String answer = generated.strip();
            int newline = answer.indexOf('\n');
            if (newline > 0) {
                answer = answer.substring(0, newline);
            }
            String normalizedAnswer = " " + answer.toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9]+", " ").trim() + " ";
            if (normalizedAnswer.contains(" none ")) {
                return Optional.empty();
            }
            for (String member : question.members()) {
                String normalizedMember = " " + member.toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9]+", " ").trim() + " ";
                if (!normalizedMember.isBlank() && normalizedAnswer.contains(normalizedMember)) {
                    return Optional.of(new Resolution(
                            member, 0.85, "llm:" + modelDescription + "@" + baseUrl));
                }
            }
            return Optional.empty();
        }
    }
}
