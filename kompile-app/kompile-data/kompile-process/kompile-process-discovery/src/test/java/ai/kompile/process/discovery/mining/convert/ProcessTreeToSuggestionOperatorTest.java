/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.process.discovery.mining.convert;

import ai.kompile.process.discovery.ProcessSuggestion;
import ai.kompile.process.discovery.ProcessSuggestion.SuggestedStep;
import ai.kompile.process.discovery.mining.log.Event;
import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.log.Trace;
import ai.kompile.process.discovery.mining.miner.InductiveMiner;
import ai.kompile.process.discovery.mining.tree.ProcessTree;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the operator-semantics grounding pass: XOR branches get observed case shares +
 * default-TRUE SpEL routing stubs (decision-mining lite — events carry no data attributes, so
 * shares and editable stubs are what the log honestly supports), and AND blocks get PARALLEL
 * evidence whose cross-trace order instability corroborates real concurrency.
 */
class ProcessTreeToSuggestionOperatorTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2024, 5, 1, 9, 0);

    @Test
    void xorBranches_getConditionStubsAndObservedShares() {
        // 5 cases: Submit → (3× Approve | 2× Reject) → Close.
        List<Trace> traces = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            traces.add(trace("appr-" + i, i, "Submit", "Approve", "Close"));
        }
        for (int i = 0; i < 2; i++) {
            traces.add(trace("rej-" + i, 10 + i, "Submit", "Reject", "Close"));
        }
        EventLog log = new EventLog(traces);
        ProcessTree tree = new InductiveMiner(0.0).mine(log);

        ProcessSuggestion suggestion = ProcessTreeToSuggestion.convert(tree, log, "choice test");

        Map<String, SuggestedStep> steps = stepsByName(suggestion);
        SuggestedStep approve = steps.get("Approve");
        SuggestedStep reject = steps.get("Reject");
        assertEquals("#take_approve != false", approve.getConditionExpression(),
                "XOR branch steps carry a default-TRUE SpEL routing stub");
        assertTrue(approve.getConditionLabel().contains("3 of 5"),
                "the branch's observed case share rides the label: " + approve.getConditionLabel());
        assertEquals("#take_reject != false", reject.getConditionExpression());
        assertTrue(reject.getConditionLabel().contains("2 of 5"));
        // Activities OUTSIDE the choice must stay unconditioned.
        assertNull(steps.get("Submit").getConditionExpression());
        assertNull(steps.get("Close").getConditionExpression());

        List<ProcessSuggestion.StructuredEvidence> choice = evidence(suggestion, "CHOICE");
        assertEquals(1, choice.size(), "one CHOICE evidence entry per XOR node");
        assertTrue(choice.get(0).getDescription().contains("Approve (3/5)")
                        && choice.get(0).getDescription().contains("Reject (2/5)"),
                "both branch shares surface: " + choice.get(0).getDescription());
        assertEquals(0.6, choice.get(0).getScore(), 1e-9, "score = dominant branch share");
    }

    @Test
    void andBlock_getsParallelEvidenceWithOrderInstability() {
        // 4 cases: Submit → {Scan ∥ Log} → Close, with the middle order flipping 2/2 across cases.
        List<Trace> traces = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            traces.add(trace("sl-" + i, i, "Submit", "Scan", "Log", "Close"));
        }
        for (int i = 0; i < 2; i++) {
            traces.add(trace("ls-" + i, 10 + i, "Submit", "Log", "Scan", "Close"));
        }
        EventLog log = new EventLog(traces);
        ProcessTree tree = new InductiveMiner(0.0).mine(log);

        ProcessSuggestion suggestion = ProcessTreeToSuggestion.convert(tree, log, "parallel test");

        List<ProcessSuggestion.StructuredEvidence> parallel = evidence(suggestion, "PARALLEL");
        assertTrue(parallel.stream().anyMatch(ev ->
                        ev.getDescription().contains("∥")
                                && ev.getDescription().contains("Scan")
                                && ev.getDescription().contains("Log")),
                "the AND pair must surface as PARALLEL evidence, got " + parallel);
        ProcessSuggestion.StructuredEvidence pair = parallel.get(0);
        assertEquals(1.0, pair.getScore(), 1e-9,
                "a perfect 2/2 order flip is maximal instability — strongest concurrency corroboration");
        // Parallel steps stay mutually independent — no dependsOn between Scan and Log.
        Map<String, SuggestedStep> steps = stepsByName(suggestion);
        assertTrue(!steps.get("Scan").getDependsOn().contains("Log")
                        && !steps.get("Log").getDependsOn().contains("Scan"),
                "AND branches must never depend on each other");
    }

    @Test
    void xorGuards_minedFromNumericEventAttributes_nullAndTypeSafe() {
        // 8 cases: Submit → (Approve when amount small | Escalate when amount large) → Close.
        // amount rides the Submit event's attributes, the way EventLogExtractor lifts metadata.
        List<Trace> traces = new ArrayList<>();
        double[] smallAmounts = {100, 250, 400, 480};
        for (int i = 0; i < smallAmounts.length; i++) {
            traces.add(attributedTrace("small-" + i, i, Map.of("amount", smallAmounts[i]),
                    "Submit", "Approve", "Close"));
        }
        double[] largeAmounts = {5000, 7500, 9000, 12000};
        for (int i = 0; i < largeAmounts.length; i++) {
            traces.add(attributedTrace("large-" + i, 10 + i, Map.of("amount", largeAmounts[i]),
                    "Submit", "Escalate", "Close"));
        }
        EventLog log = new EventLog(traces);
        ProcessTree tree = new InductiveMiner(0.0).mine(log);

        ProcessSuggestion suggestion = ProcessTreeToSuggestion.convert(tree, log, "guard test");

        SuggestedStep approve = stepsByName(suggestion).get("Approve");
        assertTrue(approve.getConditionExpression().startsWith("#take_approve != false && ("),
                "manual override composes with the mined guard: " + approve.getConditionExpression());
        assertTrue(approve.getConditionExpression().contains("#amount == null")
                        && approve.getConditionExpression().contains("instanceof T(java.lang.Number)"),
                "guards must be null- AND type-safe (missing/non-numeric runData always runs): "
                        + approve.getConditionExpression());
        assertTrue(approve.getConditionExpression().contains("#amount <= "),
                "small-amount branch guards on an upper threshold: " + approve.getConditionExpression());
        assertTrue(approve.getConditionLabel().contains("mined guard: amount ≤"),
                "the label documents the mined guard: " + approve.getConditionLabel());
        assertTrue(approve.getConditionLabel().contains("separates 8 of 8"),
                "perfect separation over all 8 decision cases: " + approve.getConditionLabel());

        SuggestedStep escalate = stepsByName(suggestion).get("Escalate");
        assertTrue(escalate.getConditionExpression().contains("#amount >= "),
                "large-amount branch guards on a lower threshold: " + escalate.getConditionExpression());

        List<ProcessSuggestion.StructuredEvidence> choice = evidence(suggestion, "CHOICE");
        assertTrue(choice.get(0).getDescription().contains("when amount"),
                "the choice evidence mentions the mined guards: " + choice.get(0).getDescription());
    }

    @Test
    void graphPolicyAttributes_areLiftedOntoSuggestedSteps() {
        Trace trace = attributedTrace("policy-1", 0, Map.of(
                        "controlId", "C-04",
                        "requiredRoles", List.of("Forecast gate owner"),
                        "confidenceThreshold", 0.85,
                        "routingPolicy", "route low confidence variances to owner",
                        "actionType", "approval_gate"),
                "Review Forecast", "Close");
        EventLog log = new EventLog(List.of(trace));
        ProcessTree tree = new InductiveMiner(0.0).mine(log);

        ProcessSuggestion suggestion = ProcessTreeToSuggestion.convert(tree, log, "policy test");

        SuggestedStep review = stepsByName(suggestion).get("Review Forecast");
        assertTrue(review.getControlIds().contains("C-04"));
        assertTrue(review.getRequiredRoles().contains("Forecast gate owner"));
        assertEquals(0.85, ((Number) review.getMetadata().get("confidenceThreshold")).doubleValue(), 1e-9);
        assertTrue(review.getConditionExpression().contains("#confidence"));
        assertTrue(review.getConditionExpression().contains(">= 0.85"));
        assertTrue(review.getConditionLabel().contains("Policy:"));
        assertTrue(evidence(suggestion, "POLICY").stream()
                .anyMatch(ev -> ev.getDescription().contains("Review Forecast")));
    }

    @Test
    void graphStateEntailment_isLiftedOntoSuggestedSteps() {
        Trace trace = attributedTrace("state-1", 0, Map.of(
                        "controlId", "C-04",
                        "actionType", "VALIDATE",
                        "slaBreached", true,
                        "status", "OVERDUE",
                        "remediationAction", "REWORK_FORECAST"),
                "Validate Forecast", "Close");
        EventLog log = new EventLog(List.of(trace));
        ProcessTree tree = new InductiveMiner(0.0).mine(log);

        ProcessSuggestion suggestion = ProcessTreeToSuggestion.convert(tree, log, "state test");

        SuggestedStep validate = stepsByName(suggestion).get("Validate Forecast");
        assertTrue(validate.getControlIds().contains("C-04"));
        assertEquals(true, validate.getMetadata().get("slaBreached"));
        assertEquals(true, validate.getMetadata().get("processBlocked"));
        assertEquals("MEDIUM", validate.getMetadata().get("remediationSeverity"));
        assertTrue(validate.getMetadata().get("processStateTypes").toString().contains("CONTROL_EFFECT"));
        assertTrue(validate.getMetadata().get("processStateTypes").toString().contains("SLA_BREACH"));
        assertTrue(validate.getConditionLabel().contains("State: SLA breach"));
        assertTrue(evidence(suggestion, "STATE").stream()
                .anyMatch(ev -> ev.getDescription().contains("Validate Forecast")));
    }

    @Test
    void xorGuards_belowAccuracyOrNoAttributes_fallBackToPlainStubs() {
        // Attributes exist but do NOT separate the branches (same values both sides).
        List<Trace> traces = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            traces.add(attributedTrace("a-" + i, i, Map.of("region", "emea"), "Submit", "Approve", "Close"));
        }
        for (int i = 0; i < 3; i++) {
            traces.add(attributedTrace("r-" + i, 10 + i, Map.of("region", "emea"), "Submit", "Reject", "Close"));
        }
        EventLog log = new EventLog(traces);
        ProcessTree tree = new InductiveMiner(0.0).mine(log);

        ProcessSuggestion suggestion = ProcessTreeToSuggestion.convert(tree, log, "no-guard test");

        SuggestedStep approve = stepsByName(suggestion).get("Approve");
        assertEquals("#take_approve != false", approve.getConditionExpression(),
                "a non-separating attribute must never become a guard");
    }

    private static Trace attributedTrace(String caseId, int dayOffset, Map<String, Object> firstEventAttributes,
                                         String... activities) {
        List<Event> events = new ArrayList<>();
        for (int i = 0; i < activities.length; i++) {
            events.add(new Event(caseId, activities[i],
                    BASE.plusDays(dayOffset).plusMinutes(5L * i), caseId + "-n" + i,
                    i == 0 ? firstEventAttributes : Map.of()));
        }
        return new Trace(caseId, events);
    }

    private static Trace trace(String caseId, int dayOffset, String... activities) {
        List<Event> events = new ArrayList<>();
        for (int i = 0; i < activities.length; i++) {
            events.add(Event.of(caseId, activities[i],
                    BASE.plusDays(dayOffset).plusMinutes(5L * i), caseId + "-n" + i));
        }
        return new Trace(caseId, events);
    }

    private static Map<String, SuggestedStep> stepsByName(ProcessSuggestion suggestion) {
        return suggestion.getPhases().stream()
                .flatMap(p -> p.getSteps().stream())
                .collect(Collectors.toMap(SuggestedStep::getName, s -> s, (a, b) -> a));
    }

    private static List<ProcessSuggestion.StructuredEvidence> evidence(ProcessSuggestion suggestion, String type) {
        return suggestion.getStructuredEvidence().stream()
                .filter(ev -> type.equals(ev.getType()))
                .toList();
    }
}
