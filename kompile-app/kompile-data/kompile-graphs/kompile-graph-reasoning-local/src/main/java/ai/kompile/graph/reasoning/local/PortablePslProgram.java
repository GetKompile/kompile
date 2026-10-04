package ai.kompile.graph.reasoning.local;

import ai.kompile.graph.reasoning.psl.AdmmHlMrfInference;
import ai.kompile.graph.reasoning.psl.PslProgram;
import ai.kompile.graph.reasoning.psl.PslProgramArtifactCodec;
import ai.kompile.graph.reasoning.unified.MiniJson;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Disposable, bounded Java HL-MRF inference; never restores binary programs or callbacks. */
final class PortablePslProgram {
    static final int MAX_ITERATIONS = 2_000;
    static final PslProgram.GroundingLimits LIMITS = new PslProgram.GroundingLimits(
            2_000, 10_000, 100_000, 200_000);
    private static final double HARD_TOLERANCE = 1e-4;

    private PortablePslProgram() {}

    static void register(LocalToolDispatcher.Builder builder) {
        Map<String, Object> properties = Map.of(
                "maxIterations", Map.of("type", "integer", "minimum", 1, "maximum", MAX_ITERATIONS,
                        "description", "Java ADMM iteration cap; default 2000. Non-convergence returns PARTIAL."),
                "evidence", Map.of("type", "object", "additionalProperties",
                        Map.of("type", "number", "minimum", 0, "maximum", 1),
                        "description", "Exact archived target atom keys mapped to soft truth in [0,1]. Cannot override observed atoms."));
        builder.handler("graph_psl", new LocalToolCatalog.Entry("graph_psl",
                "Infer the canonical portable PSL program archived in the graph, using bounded Java-only ADMM. "
                        + "Returns predicate truth assignments, not verified facts, Bayesian posteriors or consensus training targets. "
                        + "Missing, invalid, stale or over-budget programs fail explicitly; no binary fallback.",
                Map.of("type", "object", "properties", properties, "additionalProperties", false)),
                (session, args) -> infer(session.graph(), args));
    }

    static Map<String, Object> activation(UnifiedGraph graph) {
        try {
            PslProgram program = load(graph);
            var grounded = program.groundAll(LIMITS);
            return Map.of("status", "ACTIVE", "reason", "Bounded portable PSL inference via graph_psl; Java ADMM, no binary cache",
                    "semantics", "inference-program", "logicalRuleCount", grounded.logicalRules().size(),
                    "arithmeticRuleCount", grounded.arithmeticRules().size(), "groundingWork", grounded.work());
        } catch (RuntimeException e) {
            return Map.of("status", "INVALID", "reason", String.valueOf(e.getMessage()));
        }
    }

    private static PslProgram load(UnifiedGraph graph) {
        PortablePslRanking.requireFresh(graph);
        String json = graph.artifactText(PslProgramArtifactCodec.ARTIFACT);
        if (json == null) throw new IllegalArgumentException("Missing canonical portable PSL program: " + PslProgramArtifactCodec.ARTIFACT);
        return PslProgramArtifactCodec.decode(json);
    }

    static String infer(UnifiedGraph graph, Map<String, Object> args) {
        try {
            for (String key : args.keySet()) {
                if (!key.equals("maxIterations") && !key.equals("evidence")) {
                    throw new IllegalArgumentException("Unknown graph_psl argument: " + key);
                }
            }
            int iterations = MAX_ITERATIONS;
            if (args.containsKey("maxIterations")) {
                Object raw = args.get("maxIterations");
                if (!(raw instanceof Number number) || !Double.isFinite(number.doubleValue())
                        || number.doubleValue() != Math.rint(number.doubleValue())
                        || number.doubleValue() < 1 || number.doubleValue() > MAX_ITERATIONS) {
                    throw new IllegalArgumentException("maxIterations must be an integer in [1,2000]");
                }
                iterations = number.intValue();
            }
            PslProgram program = load(graph);
            Map<String, Double> evidence = new LinkedHashMap<>();
            if (args.containsKey("evidence")) {
                if (!(args.get("evidence") instanceof Map<?, ?> requested)) {
                    throw new IllegalArgumentException("evidence must be an object of exact target atom keys to numbers");
                }
                Map<String, ai.kompile.graph.reasoning.psl.PslAtom> targets = new LinkedHashMap<>();
                for (var atom : program.atomsSnapshot()) {
                    if (!program.isObserved(atom.key())) targets.put(atom.key(), atom);
                }
                for (var entry : requested.entrySet()) {
                    if (!(entry.getKey() instanceof String key) || !targets.containsKey(key)) {
                        throw new IllegalArgumentException("Evidence must name an exact archived target atom, not an observed/unknown atom: " + entry.getKey());
                    }
                    if (!(entry.getValue() instanceof Number value) || !Double.isFinite(value.doubleValue())
                            || value.doubleValue() < 0 || value.doubleValue() > 1) {
                        throw new IllegalArgumentException("Evidence truth must be a finite number in [0,1]");
                    }
                    evidence.put(key, value.doubleValue());
                    program.observe(targets.get(key), value.doubleValue());
                }
            }
            var grounded = program.groundAll(LIMITS);
            var solved = new AdmmHlMrfInference().solve(program, grounded.logicalRules(),
                    grounded.arithmeticRules(), iterations, 1e-6, 1e6);
            if (!Double.isFinite(solved.objective())) throw new IllegalArgumentException("Non-finite PSL objective");
            for (double value : solved.values().values()) {
                if (!Double.isFinite(value) || value < 0 || value > 1) {
                    throw new IllegalArgumentException("Invalid PSL truth assignment");
                }
            }
            double violation = 0;
            for (var rule : grounded.logicalRules()) {
                if (rule.hard()) violation = Math.max(violation, rule.distanceToSatisfaction(solved.values()));
            }
            for (var rule : grounded.arithmeticRules()) {
                if (rule.hard()) violation = Math.max(violation, rule.distanceToSatisfaction(solved.values()));
            }
            if (!Double.isFinite(violation)) throw new IllegalArgumentException("Non-finite hard constraint residual");
            boolean satisfied = violation <= HARD_TOLERANCE;
            Map<String, Double> targets = new LinkedHashMap<>();
            for (String key : program.targetKeys()) targets.put(key, solved.values().get(key));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", solved.converged() && satisfied ? "OK" : "PARTIAL");
            result.put("semantics", "HL-MRF soft truth; not verified facts, Bayesian posteriors or consensus inference");
            result.put("artifact", PslProgramArtifactCodec.ARTIFACT);
            result.put("solver", "ADMM_JAVA");
            result.put("binaryCacheUsed", false);
            result.put("converged", solved.converged());
            result.put("hardConstraintsSatisfied", satisfied);
            result.put("maxHardViolation", violation);
            result.put("iterations", solved.iterations());
            result.put("objective", solved.objective());
            result.put("logicalRuleCount", grounded.logicalRules().size());
            result.put("arithmeticRuleCount", grounded.arithmeticRules().size());
            result.put("groundingWork", grounded.work());
            result.put("values", solved.values());
            result.put("targets", targets);
            result.put("evidence", evidence);
            result.put("limits", Map.of("atoms", 2000, "groundRules", 10000, "incidences", 100000,
                    "groundingWork", 200000, "iterations", MAX_ITERATIONS));
            return MiniJson.write(result);
        } catch (RuntimeException e) {
            return MiniJson.write(Map.of("status", "ERROR", "message", String.valueOf(e.getMessage())));
        }
    }
}
