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
package ai.kompile.graph.reasoning.explain;

import ai.kompile.graph.reasoning.fol.EntailmentRecord;
import ai.kompile.graph.reasoning.fol.grounding.DerivationTree;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReasoningTraceRendererTest {

    @Test
    void rendersSingleModeTrailForLlmContext() {
        EntailmentRecord entailment = new EntailmentRecord(
                "risk(account_1)", 0.82, List.of("overdue(account_1)"), List.of("risk_rule"),
                Instant.parse("2026-07-07T00:00:00Z"), "run-1");
        ReasoningTrail trail = ReasoningTrail.builder("risk(account_1)")
                .question("Why is the account risky?")
                .inferenceMode("PSL")
                .confidence(0.81)
                .breakdown(ConfidenceBreakdown.ofPsl(0.82, 0.18))
                .naturalLanguageSummary("The account is risky because it is overdue.")
                .evidence(List.of("invoice 12 is overdue", "payment history is deteriorating"))
                .activatedRules(List.of("risk_rule"))
                .entailments(List.of(entailment))
                .atomKeyToTitle(Map.of(
                        "risk(account_1)", "Account Risk",
                        "overdue(account_1)", "Account Overdue"))
                .ruleToHumanized(Map.of("risk_rule", "High risk rule"))
                .build();

        String context = ReasoningTraceRenderer.toLlmContext(trail, 20);

        assertTrue(context.contains("Reasoning trace (PSL): id=trace.root; target=Account Risk [risk(account_1)]; confidence=0.810"));
        assertTrue(context.contains("Question: Why is the account risky?"));
        assertTrue(context.contains("Confidence breakdown: psl=0.820, distanceToSatisfaction=0.180"));
        assertTrue(context.contains("Evidence:"));
        assertTrue(context.contains("- [trace.evidence.0] invoice 12 is overdue"));
        assertTrue(context.contains("Rules:"));
        assertTrue(context.contains("- [trace.rule.0] High risk rule [risk_rule]"));
        assertTrue(context.contains("Entailments:"));
        assertTrue(context.contains("[trace.entailment.0] Account Risk [risk(account_1)] (posterior 0.820) via High risk rule [risk_rule]"));
        List<ReasoningTraceRenderer.AttributionStep> attributions = ReasoningTraceRenderer.attributionIndex(trail);
        assertTrue(attributions.stream()
                .anyMatch(step -> step.stepId().equals("trace.entailment.0")
                        && step.conclusion().equals("risk(account_1)")));
        assertTrue(attributions.stream()
                .filter(step -> step.stepId().equals("trace.entailment.0"))
                .flatMap(step -> step.evidenceRefs().stream())
                .anyMatch(ref -> "overdue(account_1)".equals(ref.findingKey())
                        && "run-1".equals(ref.runId())));
        assertTrue(attributions.stream()
                .filter(step -> step.stepId().equals("trace.entailment.0"))
                .flatMap(step -> step.evidenceRefs().stream())
                .anyMatch(ref -> "risk_rule".equals(ref.ruleId())));
    }

    @Test
    void rendersDerivationTreeWithDisplayTitles() {
        DerivationTree tree = new DerivationTree("basedIn(alice, nyc)", 0.95, "basedIn-rule", "fol",
                List.of(new DerivationTree("worksFor(alice, acme)", 1.0, null, "graph", List.of()),
                        new DerivationTree("locatedIn(acme, nyc)", 1.0, null, "graph", List.of())));
        ReasoningTrail trail = ReasoningTrail.builder("basedIn(alice, nyc)")
                .inferenceMode("GROUNDING")
                .confidence(0.95)
                .derivationTree(tree)
                .atomKeyToTitle(Map.of("basedIn(alice, nyc)", "Alice based in NYC"))
                .ruleToHumanized(Map.of("basedIn-rule", "Residency rule"))
                .build();

        String context = ReasoningTraceRenderer.toLlmContext(trail, 12);

        assertTrue(context.contains("Derivation:"));
        assertTrue(context.contains("[trace.derivation.root] [RULE confidence=0.950] Alice based in NYC [basedIn(alice, nyc)] via Residency rule [basedIn-rule] source=fol"));
        assertTrue(context.lines().anyMatch(line -> line.startsWith("  - [trace.derivation.root.0] [FACT confidence=1.000] worksFor(alice, acme)")));
        assertTrue(ReasoningTraceRenderer.attributionIndex(trail).stream()
                .anyMatch(step -> step.stepId().equals("trace.derivation.root.1")
                        && step.conclusion().equals("locatedIn(acme, nyc)")));
    }

    @Test
    void rendersCanonicalTraceAsTree() {
        ReasoningTrace.Step worksFor = ReasoningTrace.Step.fact("worksFor(alice, acme)", 1.0, "graph node:works_for_1");
        ReasoningTrace.Step locatedIn = ReasoningTrace.Step.fact("locatedIn(acme, nyc)", 1.0, "graph");
        ReasoningTrace trace = ReasoningTrace.of(ReasoningTrace.Step.derived(
                ReasoningTrace.StepKind.RULE,
                "basedIn(alice, nyc)",
                "basedIn-rule",
                0.95,
                worksFor,
                locatedIn));

        String context = ReasoningTraceRenderer.toLlmContext(trace, 6);

        assertTrue(context.contains("Reasoning trace (RULE): id=trace.root; conclusion=basedIn(alice, nyc); confidence=0.950"));
        assertTrue(context.contains("- [trace.root] [RULE confidence=0.950] basedIn(alice, nyc) via basedIn-rule"));
        assertTrue(context.lines().anyMatch(line -> line.startsWith("  - [trace.root.0] [FACT confidence=1.000] worksFor(alice, acme)")));
        List<ReasoningTraceRenderer.AttributionStep> attributions = ReasoningTraceRenderer.attributionIndex(trace);
        assertTrue(attributions.stream()
                .anyMatch(step -> step.stepId().equals("trace.root.1")
                        && step.conclusion().equals("locatedIn(acme, nyc)")));
        assertTrue(attributions.stream()
                .filter(step -> step.stepId().equals("trace.root.0"))
                .flatMap(step -> step.evidenceRefs().stream())
                .anyMatch(ref -> "works_for_1".equals(ref.nodeId())
                        && "graph node:works_for_1".equals(ref.sourceId())));
    }

    @Test
    void rendersCompositeTrailAndHonorsLineBudget() {
        CompositeReasoningTrail trail = new CompositeReasoningTrail(
                "alice", "Why active?",
                List.of(ModalityEvidence.of(ModalityKind.PSL, 0.9, "PSL fired", List.of("rule R1", "rule R2")),
                        ModalityEvidence.of(ModalityKind.GRAPH_RAG, 0.7, "RAG found support", List.of("chunk-1"))),
                0.8, "Alice is active.", Instant.parse("2026-07-07T00:00:00Z"), "run-x");

        String context = ReasoningTraceRenderer.toLlmContext(trail, 7);

        assertTrue(context.contains("Reasoning trace (FUSION): id=trace.root; target=alice; confidence=0.800; modalities=2"));
        assertTrue(context.contains("Answer: Alice is active."));
        assertTrue(context.contains("- [trace.modality.0] PSL confidence=0.900: PSL fired"));
        assertTrue(context.contains("  - [trace.modality.0.detail.0] rule R1"));
        assertTrue(context.lines().count() <= 7);
        assertEquals("", ReasoningTraceRenderer.toLlmContext(trail, 0));
        List<ReasoningTraceRenderer.AttributionStep> attributions = ReasoningTraceRenderer.attributionIndex(trail);
        assertTrue(attributions.stream()
                .anyMatch(step -> step.stepId().equals("trace.modality.1.detail.0")
                        && step.conclusion().equals("chunk-1")));
        assertTrue(attributions.stream()
                .filter(step -> step.stepId().equals("trace.modality.1.detail.0"))
                .flatMap(step -> step.evidenceRefs().stream())
                .anyMatch(ref -> "GRAPH_RAG".equals(ref.modality())
                        && "chunk-1".equals(ref.raw())));
    }
}
