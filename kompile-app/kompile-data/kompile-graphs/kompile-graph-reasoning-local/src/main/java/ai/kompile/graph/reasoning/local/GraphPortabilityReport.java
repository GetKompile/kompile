package ai.kompile.graph.reasoning.local;

import ai.kompile.graph.reasoning.lifecycle.UnifiedGraphReasoningLifecycle;
import ai.kompile.graph.reasoning.lifecycle.ConsensusTargetsArtifactCodec;
import ai.kompile.graph.reasoning.psl.GraphPslWeightsArtifactCodec;
import ai.kompile.graph.reasoning.psl.PslProgramArtifactCodec;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runtime activation inventory, not a promise that every ZIP entry is executable. */
final class GraphPortabilityReport {
    private GraphPortabilityReport() {}

    static Map<String, Object> inspect(LocalReasoningSession session) {
        UnifiedGraph graph = session.graph();
        List<Map<String, Object>> artifacts = new ArrayList<>();
        for (var entry : graph.artifacts().entrySet()) {
            String name = entry.getKey();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("bytes", entry.getValue().length);
            item.put("status", "INSPECTION_ONLY");
            item.put("reason", "Stored opaque artifact; no local execution contract registered");
            if (DatalogRulesBundle.ARTIFACT_KEY.equals(name)) {
                item.put("codec", "datalog-json");
                item.putAll(session.kbState().rulesActivation());
                item.put("reason", "Bounded Datalog materialization at load/mutation");
                // Preserve an activation error rather than replacing it with the happy-path text.
                if ("INVALID".equals(item.get("status"))) item.putAll(session.kbState().rulesActivation());
            } else if (UnifiedGraphReasoningLifecycle.MEBN_THEORY_JSON_ARTIFACT.equals(name)) {
                item.put("codec", "kompile-relational-mtheory.v1");
                item.putAll(InferenceHandlers.mebnActivation(graph));
            } else if ("models/kge.json".equals(name)) {
                item.put("codec", "kge-json-and-kvec");
                item.putAll(AnalyticsHandlers.kgeActivation(graph));
            } else if (PslProgramArtifactCodec.ARTIFACT.equals(name)) {
                item.put("codec", "kompile-psl-program.v1");
                item.putAll(PortablePslProgram.activation(graph));
            } else if (GraphPslWeightsArtifactCodec.ARTIFACT.equals(name)
                    || GraphPslWeightsArtifactCodec.LEGACY_ARTIFACT.equals(name)) {
                item.put("codec", GraphPslWeightsArtifactCodec.ARTIFACT.equals(name) ? "graph-psl-weights.v1" : "legacy-default-psl-weights");
                if (GraphPslWeightsArtifactCodec.LEGACY_ARTIFACT.equals(name)
                        && graph.artifactText(GraphPslWeightsArtifactCodec.ARTIFACT) != null) {
                    item.put("reason", "Superseded by canonical graph PSL parameters; not used independently");
                } else {
                    item.putAll(PortablePslRanking.activation(graph));
                }
            } else if (ConsensusTargetsArtifactCodec.ARTIFACT_NAME.equals(name)) {
                item.put("codec", "consensus-targets.v1");
                item.put("semantics", "training-targets");
                try {
                    var snapshot = ConsensusTargetsArtifactCodec.decode(graph.artifactText(name));
                    PortablePslRanking.requireFresh(graph);
                    item.put("targetCount", snapshot.targets().size());
                    item.put("entityAliasCount", snapshot.entityAliases().size());
                    item.put("reason", "Decoded training-target snapshot; not posteriors, verified facts or query-time consensus inference");
                } catch (RuntimeException e) {
                    item.put("status", "INVALID");
                    item.put("reason", String.valueOf(e.getMessage()));
                }
            } else if (name.endsWith(".bin")) {
                item.put("codec", "opaque-binary");
                item.put("reason", "Preserved for inspection; this local consumer does not activate bundled binary models");
            }
            artifacts.add(item);
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("version", 1);
        report.put("consumer", "kompile-graph-reasoning-local");
        report.put("storage", "materialized-in-memory");
        report.put("observedFacts", Map.of("status", "ACTIVE", "count", session.kbState().factStore().size(),
                "confidence", "min(weight, confidence), not promoted to 1.0"));
        report.put("artifacts", artifacts);
        report.put("vectorLayers", new ArrayList<>(graph.vectorLayers().keySet()));
        report.put("weightMaps", new ArrayList<>(graph.weightMaps().keySet()));
        report.put("externalRequirements", List.of(
                "MCP transport is supplied by the host; a graph file does not start an MCP server",
                "Chat/tokenizer/encoder model files and credentials are not supplied by this graph",
                "Stored vectors allow similarity, not text encoding without an encoder",
                "Compressed archive size is not a runtime RAM budget"));
        return report;
    }
}
