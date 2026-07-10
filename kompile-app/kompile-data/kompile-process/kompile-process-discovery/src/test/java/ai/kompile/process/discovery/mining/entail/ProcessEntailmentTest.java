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

package ai.kompile.process.discovery.mining.entail;

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.process.discovery.mining.declare.DeclareConstraint;
import ai.kompile.process.discovery.mining.declare.DeclareMiner;
import ai.kompile.process.discovery.mining.dfg.DfgBuilder;
import ai.kompile.process.discovery.mining.dfg.DirectlyFollowsGraph;
import ai.kompile.process.discovery.mining.entail.ProcessEntailmentResult.EntailedPrecedence;
import ai.kompile.process.discovery.mining.log.Event;
import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.log.Trace;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates the process-entailment core: transitive derivation of unobserved orderings,
 * antisymmetry (cycle) suppression, NotCoExistence suppression, temporal refutation against the
 * log's valid time, and the fused-opinion aggregate.
 */
class ProcessEntailmentTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2024, 1, 1, 8, 0);

    /** n traces of A→B→C with sane timestamps. */
    private static EventLog sequenceLog(int n) {
        List<Trace> traces = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String c = "case-" + i;
            traces.add(new Trace(c, List.of(
                    Event.of(c, "Approve", BASE.plusMinutes(i * 30L), "a" + i),
                    Event.of(c, "Notify", BASE.plusMinutes(i * 30L + 5), "b" + i),
                    Event.of(c, "Close", BASE.plusMinutes(i * 30L + 10), "c" + i))));
        }
        return new EventLog(traces);
    }

    private static ProcessEntailmentResult entail(EventLog log) {
        DirectlyFollowsGraph dfg = DfgBuilder.build(log);
        List<DeclareConstraint> constraints = DeclareMiner.mine(log, 0.2, 0.66);
        return ProcessEntailment.entail(log, dfg, constraints);
    }

    private static Optional<EntailedPrecedence> pair(ProcessEntailmentResult result, String from, String to) {
        return result.precedences().stream()
                .filter(p -> p.from().equals(from) && p.to().equals(to))
                .findFirst();
    }

    @Test
    void transitivity_entailsUnobservedOrdering() {
        ProcessEntailmentResult result = entail(sequenceLog(5));

        assertTrue(result.transitivityApplied(), "3 activities must be within the transitivity cap");

        // Approve→Close is never a directly-follows arc — it must be derived by the rules.
        EntailedPrecedence ac = pair(result, "Approve", "Close").orElseThrow();
        assertFalse(ac.observed(), "Approve→Close has no DFG arc — it is entailed, not observed");
        assertTrue(ac.posterior() >= 0.5,
                "transitive entailment must accept Approve precedes Close, got " + ac.posterior());
        assertFalse(ac.temporallyRefuted(), "timestamps agree with the entailed order");
        assertTrue(ac.orderedEvidence() > 0 && ac.reversedEvidence() == 0,
                "all dated traces order Approve before Close");
        assertNotNull(ac.temporalOpinion());

        // It must arrive with provenance: supporting observed atoms and activated ground rules.
        assertFalse(ac.activatedRules().isEmpty(), "entailed pair must cite activated rules");

        // The suppressed reverse direction must lose to antisymmetry.
        EntailedPrecedence ca = pair(result, "Close", "Approve").orElseThrow();
        assertTrue(ca.posterior() < 0.5,
                "antisymmetry must suppress the reverse ordering, got " + ca.posterior());
        assertTrue(ac.posterior() > ca.posterior());

        // Observed adjacency stays accepted.
        EntailedPrecedence ab = pair(result, "Approve", "Notify").orElseThrow();
        assertTrue(ab.observed());
        assertTrue(ab.posterior() >= 0.5);

        // entailedOnly() = accepted pairs without a DFG arc.
        assertTrue(result.entailedOnly().stream()
                        .anyMatch(p -> p.from().equals("Approve") && p.to().equals("Close")),
                "Approve→Close must appear in entailedOnly()");
    }

    @Test
    void transitivity_pure_withoutDeclareConstraints() {
        // No Declare constraints at all: the ONLY route to Approve→Close is
        // Df(A,B) -> Precedes(A,B), Df(B,C) -> Precedes(B,C), then the transitive rule.
        EventLog log = sequenceLog(5);
        ProcessEntailmentResult result = ProcessEntailment.entail(log, DfgBuilder.build(log), List.of());

        EntailedPrecedence ac = pair(result, "Approve", "Close").orElseThrow();
        assertFalse(ac.observed());
        assertTrue(ac.posterior() >= 0.5,
                "pure transitive chaining must accept Approve precedes Close, got " + ac.posterior());
        EntailedPrecedence ca = pair(result, "Close", "Approve").orElseThrow();
        assertTrue(ca.posterior() < ac.posterior(), "reverse must score below forward");
    }

    @Test
    void temporalReversal_refutesPair_andExcludesFromAssertable() {
        // Majority of dated traces put Notify BEFORE Approve — but one noisy trace reverses them,
        // and a Declare Response(Approve→Notify)-style signal comes from that minority trace.
        List<Trace> traces = new ArrayList<>();
        // 1 noisy trace: Approve then Notify
        traces.add(new Trace("noisy", List.of(
                Event.of("noisy", "Approve", BASE, "n1"),
                Event.of("noisy", "Notify", BASE.plusMinutes(1), "n2"))));
        // 3 clean traces: Notify then Approve
        for (int i = 0; i < 3; i++) {
            String c = "clean-" + i;
            traces.add(new Trace(c, List.of(
                    Event.of(c, "Notify", BASE.plusHours(1 + i), "x" + i),
                    Event.of(c, "Approve", BASE.plusHours(1 + i).plusMinutes(5), "y" + i))));
        }
        EventLog log = new EventLog(traces);
        ProcessEntailmentResult result = entail(log);

        EntailedPrecedence approveFirst = pair(result, "Approve", "Notify").orElseThrow();
        assertTrue(approveFirst.temporallyRefuted(),
                "3 of 4 dated traces contradict Approve→Notify — must be temporally refuted");
        assertEquals(1, approveFirst.orderedEvidence());
        assertEquals(3, approveFirst.reversedEvidence());
        assertTrue(result.assertable(0.0).stream()
                        .noneMatch(p -> p.from().equals("Approve") && p.to().equals("Notify")),
                "temporally refuted pairs must never be assertable regardless of posterior");

        EntailedPrecedence notifyFirst = pair(result, "Notify", "Approve").orElseThrow();
        assertFalse(notifyFirst.temporallyRefuted(), "the majority order is temporally consistent");
    }

    @Test
    void recencyDecay_recentReversalsOutvoteStaleConfirmations() {
        // 3 OLD traces (a year before the log's newest event) order Approve→Notify; 2 RECENT
        // traces reverse it. Raw counts say 3 vs 2 (not refuted); with a 30-day half-life the
        // old votes decay to ~0.0002 weight each, so the recent reversals win: the process
        // CHANGED, and the verdict must say so.
        List<Trace> traces = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String c = "old-" + i;
            traces.add(new Trace(c, List.of(
                    Event.of(c, "Approve", BASE.plusMinutes(i), "o" + i),
                    Event.of(c, "Notify", BASE.plusMinutes(i).plusMinutes(5), "p" + i))));
        }
        for (int i = 0; i < 2; i++) {
            String c = "recent-" + i;
            traces.add(new Trace(c, List.of(
                    Event.of(c, "Notify", BASE.plusDays(365).plusMinutes(i), "q" + i),
                    Event.of(c, "Approve", BASE.plusDays(365).plusMinutes(i + 5), "r" + i))));
        }
        EventLog log = new EventLog(traces);
        DirectlyFollowsGraph dfg = DfgBuilder.build(log);

        ProcessEntailmentResult decayed = ProcessEntailment.entail(log, dfg, List.of(), 30.0);
        EntailedPrecedence pair = pair(decayed, "Approve", "Notify").orElseThrow();
        assertEquals(3, pair.orderedEvidence(), "raw counts stay undecayed for display");
        assertEquals(2, pair.reversedEvidence());
        assertTrue(pair.temporallyRefuted(),
                "with recency decay the 2 recent reversals must outvote 3 stale confirmations");

        // Decay disabled (<= 0): the raw majority stands and the pair is NOT refuted.
        ProcessEntailmentResult undecayed = ProcessEntailment.entail(log, dfg, List.of(), 0.0);
        assertFalse(pair(undecayed, "Approve", "Notify").orElseThrow().temporallyRefuted(),
                "without decay 3 ordered vs 2 reversed is a majority confirmation");
    }

    @Test
    void intervalOverlap_marksPairConcurrent_andWithholdsAssertion() {
        // Review and Deploy INTERLEAVE within every case (multi-event activities whose intervals
        // overlap): Review@0h, Deploy@1h, Review@2h, Deploy@3h. Point semantics would call this
        // ordered (first occurrences 0h vs 1h); interval semantics sees [0,2] ∩ [1,3] — the
        // Allen-relation upgrade this fixture exists to pin.
        List<Trace> traces = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String c = "case-" + i;
            LocalDateTime t0 = BASE.plusDays(i);
            traces.add(new Trace(c, List.of(
                    Event.of(c, "Review", t0, c + "-r1"),
                    Event.of(c, "Deploy", t0.plusHours(1), c + "-d1"),
                    Event.of(c, "Review", t0.plusHours(2), c + "-r2"),
                    Event.of(c, "Deploy", t0.plusHours(3), c + "-d2"))));
        }
        EventLog log = new EventLog(traces);
        ProcessEntailmentResult result = entail(log);

        EntailedPrecedence pair = pair(result, "Review", "Deploy").orElseThrow();
        assertEquals(4, pair.overlappedEvidence(), "every case's intervals overlap");
        assertEquals(0, pair.orderedEvidence());
        assertTrue(pair.concurrent(), "overlap outweighing both directions ⇒ concurrent");
        assertTrue(result.assertable(0.0).stream()
                        .noneMatch(p -> p.from().equals("Review") && p.to().equals("Deploy")),
                "precedes() must be withheld for concurrent pairs regardless of posterior");
        assertTrue(result.assertable(0.0).stream()
                        .noneMatch(p -> p.from().equals("Deploy") && p.to().equals("Review")),
                "…in both directions");
    }

    @Test
    void notCoExistence_suppressesOrderingBetweenDisjointActivities() {
        // Approve/Notify co-occur; Escalate lives only in separate traces — NCE(Approve,Escalate).
        List<Trace> traces = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String c = "main-" + i;
            traces.add(new Trace(c, List.of(
                    Event.of(c, "Approve", BASE.plusHours(i), "a" + i),
                    Event.of(c, "Notify", BASE.plusHours(i).plusMinutes(5), "b" + i))));
        }
        for (int i = 0; i < 3; i++) {
            String c = "esc-" + i;
            traces.add(new Trace(c, List.of(
                    Event.of(c, "Escalate", BASE.plusDays(1 + i), "e" + i),
                    Event.of(c, "Review", BASE.plusDays(1 + i).plusMinutes(5), "r" + i))));
        }
        EventLog log = new EventLog(traces);
        ProcessEntailmentResult result = entail(log);

        Optional<EntailedPrecedence> ae = pair(result, "Approve", "Escalate");
        Optional<EntailedPrecedence> ea = pair(result, "Escalate", "Approve");
        assertTrue(ae.isPresent() || ea.isPresent(),
                "NCE pairs must be evaluated so the suppression is visible");
        ae.ifPresent(p -> assertTrue(p.posterior() < 0.5,
                "NCE must suppress Approve→Escalate, got " + p.posterior()));
        ea.ifPresent(p -> assertTrue(p.posterior() < 0.5,
                "NCE must suppress Escalate→Approve, got " + p.posterior()));
    }

    @Test
    void semanticControlsAndValidates_entailDisconnectedOrdering() {
        EventLog log = new EventLog(List.of(
                new Trace("control-case", List.of(new Event("control-case", "Prepare Forecast", BASE,
                        "prep-1", Map.of("controlId", "C-04")))),
                new Trace("validate-case", List.of(new Event("validate-case", "Validate Forecast", BASE.plusMinutes(5),
                        "val-1", Map.of("relationType", "VALIDATES", "controlId", "C-04"))))));

        ProcessEntailmentResult result = ProcessEntailment.entail(log, DfgBuilder.build(log), List.of(),
                0.0, 0, 0);

        EntailedPrecedence inferred = pair(result, "Prepare Forecast", "Validate Forecast").orElseThrow();
        assertFalse(inferred.observed(), "the semantic edge must not masquerade as a DFG arc");
        assertTrue(inferred.posterior() >= 0.5,
                "Controls(C-04, A) + Validates(C-04, B) should entail A precedes B, got "
                        + inferred.posterior());
        assertTrue(inferred.activatedRules().stream().anyMatch(r -> r.contains("Controls") && r.contains("Validates")),
                "semantic control/validation rule must appear in provenance");
    }

    @Test
    void semanticRequiresApprovalAndApprovalAction_entailDisconnectedApprovalOrdering() {
        EventLog log = new EventLog(List.of(
                new Trace("submit-case", List.of(new Event("submit-case", "Submit Forecast", BASE,
                        "submit-1", Map.of("approvalPolicy", "regional-manager")))),
                new Trace("approval-case", List.of(new Event("approval-case", "Manager Approval", BASE.plusMinutes(5),
                        "approve-1", Map.of("action", "approve", "approver", "Regional Manager"))))));

        ProcessEntailmentResult result = ProcessEntailment.entail(log, DfgBuilder.build(log), List.of(),
                0.0, 0, 0);

        EntailedPrecedence inferred = pair(result, "Submit Forecast", "Manager Approval").orElseThrow();
        assertFalse(inferred.observed(), "approval ordering must come from semantic facts, not adjacency");
        assertTrue(inferred.posterior() >= 0.5,
                "RequiresApproval(A, P) + ApprovalAction(B) should entail A precedes B, got "
                        + inferred.posterior());
        assertTrue(inferred.activatedRules().stream().anyMatch(r -> r.contains("RequiresApproval")
                        && r.contains("ApprovalAction")),
                "semantic approval rule must appear in provenance");
    }

    @Test
    void fusedOpinion_isValidSimplex_andKbAtomKeyIsQuoted() {
        ProcessEntailmentResult result = entail(sequenceLog(4));
        Opinion fused = result.fusedOpinion();
        assertTrue(fused.belief() >= 0 && fused.disbelief() >= 0 && fused.uncertainty() >= 0);
        assertEquals(1.0, fused.belief() + fused.disbelief() + fused.uncertainty(), 1e-6,
                "opinion must stay on the simplex");
        assertTrue(fused.expectation() > 0.5, "a clean sequence log must fuse to a confident opinion");

        EntailedPrecedence ab = pair(result, "Approve", "Notify").orElseThrow();
        assertEquals("precedes(\"Approve\", \"Notify\")", ab.kbAtomKey());
    }

    @Test
    void emptyAndDegenerateLogs_returnEmpty() {
        assertTrue(ProcessEntailment.entail(new EventLog(List.of()),
                DfgBuilder.build(new EventLog(List.of())), List.of()).isEmpty());

        // Single activity: nothing to order.
        EventLog single = new EventLog(List.of(new Trace("c", List.of(
                Event.of("c", "Approve", BASE, "a0")))));
        assertTrue(ProcessEntailment.entail(single, DfgBuilder.build(single),
                DeclareMiner.mine(single, 0.2, 0.66)).isEmpty());
    }

    @Test
    void ruleTexts_arePersistedInResult() {
        ProcessEntailmentResult result = entail(sequenceLog(3));
        assertFalse(result.ruleTexts().isEmpty());
        assertTrue(result.ruleTexts().stream().anyMatch(r -> r.contains("Precedes(A, B) & Precedes(B, C)")),
                "the transitive rule must be part of the compiled program");
        assertFalse(result.runId().isBlank());
    }
}
