package ai.kompile.samples.fpna;

import ai.kompile.graph.reasoning.query.GraphQueryEngine;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;

/**
 * Demonstrates the compact query contract over the graph-only FPNA fixture.
 */
public final class FpnaQueryExperience {

    private FpnaQueryExperience() {
    }

    public static void main(String[] args) {
        UnifiedGraph graph = FpnaKGraphSample.buildGraph();
        GraphQueryEngine engine = new GraphQueryEngine();

        print("What can I ask?", engine.query(graph, GraphQueryEngine.Query.capabilities()));
        print("What is in this graph?", engine.query(graph, GraphQueryEngine.Query.overview()));
        print("Which types and predicates exist?", engine.query(graph, GraphQueryEngine.Query.schema()));
        print("Which graph entity matches Sarah Chen?",
                engine.query(graph, GraphQueryEngine.Query.search("Sarah Chen")));
        print("What do we know about Sarah Chen?",
                engine.query(graph, GraphQueryEngine.Query.describe("Sarah Chen")));
        print("Which relations mention the AMER forecast email?",
                engine.query(graph, GraphQueryEngine.Query.relations("AMER forecast Q3 email", null)));
        print("What is directly connected to the AMER forecast email?",
                engine.query(graph, GraphQueryEngine.Query.neighbors("AMER forecast Q3 email")));
        print("How is Sarah Chen connected to the submitted workbook?",
                engine.query(graph, GraphQueryEngine.Query.path(
                        "Sarah Chen", "AMER_Forecast_Q3_v3_FINAL_v2.xlsx")));
        print("What is the event timeline around Sarah Chen?",
                engine.query(graph, GraphQueryEngine.Query.timeline("Sarah Chen")));
        print("Which attachment-sending facts exist?",
                engine.query(graph, GraphQueryEngine.Query.facts("SENT_EMAIL_WITH_ATTACHMENT")));
        print("Which entities are similar to Sarah Chen?",
                engine.query(graph, GraphQueryEngine.Query.similar("Sarah Chen")));
        print("Did Sarah Chen send the submitted workbook as an attachment?",
                engine.query(graph, GraphQueryEngine.Query.claim(
                        GraphQueryEngine.Intent.VERIFY,
                        "Sarah Chen",
                        "sent email with attachment",
                        "AMER_Forecast_Q3_v3_FINAL_v2.xlsx")));
        print("Why do we believe Sarah Chen sent the submitted workbook?",
                engine.query(graph, GraphQueryEngine.Query.claim(
                        GraphQueryEngine.Intent.WHY,
                        "Sarah Chen",
                        "sent email with attachment",
                        "AMER_Forecast_Q3_v3_FINAL_v2.xlsx")));
        print("Why can't we say Sarah Chen directly sent to Mei Chen?",
                engine.query(graph, GraphQueryEngine.Query.claim(
                        GraphQueryEngine.Intent.WHY_NOT,
                        "Sarah Chen",
                        "sent to",
                        "Mei Chen")));
        print("Which graph entities are structurally most important?",
                engine.query(graph, GraphQueryEngine.Query.rank(5)));
        print("Which analysis assets are attached to Sarah Chen?",
                engine.query(graph, GraphQueryEngine.Query.assets("Sarah Chen", "manualRuleWeights")));
        print("What source notes were bundled with the graph?",
                engine.query(graph, GraphQueryEngine.Query.artifact("source-notes.md")));
    }

    private static void print(String question, GraphQueryEngine.Result result) {
        System.out.println();
        System.out.println("QUESTION: " + question);
        System.out.println("STATUS: " + result.status());
        System.out.println("ANSWER: " + result.summary());
        if (!result.resolutions().isEmpty()) {
            System.out.println("RESOLUTIONS:");
            result.resolutions().forEach(resolution -> System.out.printf(
                    "  - %s '%s' -> %s (%s, score=%.3f, candidates=%d)%n",
                    resolution.role(), resolution.input(), resolution.resolvedId(),
                    resolution.resolvedLabel(), resolution.score(), resolution.candidates().size()));
        }
        if (!result.entities().isEmpty()) {
            System.out.println("ENTITIES:");
            result.entities().forEach(entity -> System.out.printf(
                    "  - %s [%s] id=%s score=%.3f%n",
                    entity.label(), entity.type(), entity.id(), entity.score()));
        }
        if (!result.relations().isEmpty()) {
            System.out.println("EVIDENCE:");
            result.relations().forEach(relation -> System.out.printf(
                    "  - %s --%s--> %s (weight=%.3f, confidence=%.3f)%n",
                    relation.sourceLabel(), relation.type(), relation.targetLabel(),
                    relation.weight(), relation.confidence()));
        }
        if (!result.path().isEmpty()) {
            System.out.println("PATH: " + result.path().stream()
                    .map(step -> step.entity().label())
                    .reduce((left, right) -> left + " -> " + right)
                    .orElse(""));
        }
        if (!result.data().isEmpty()) {
            System.out.println("DATA FIELDS: " + String.join(", ", result.data().keySet()));
        }
        if (result.trace() != null) {
            System.out.printf("TRACE: %d step(s), depth=%d, root=%s%n",
                    result.trace().size(), result.trace().depth(), result.trace().conclusion().kind());
        }
        if (!result.guidance().isEmpty()) {
            System.out.println("NEXT: " + String.join(" ", result.guidance()));
        }
    }
}
