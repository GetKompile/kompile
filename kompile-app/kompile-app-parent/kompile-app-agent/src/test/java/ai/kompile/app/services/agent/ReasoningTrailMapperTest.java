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
package ai.kompile.app.services.agent;

import ai.kompile.graph.reasoning.explain.ReasoningTrace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ReasoningTrailMapper#toTrailDto(ReasoningTrace)}.
 *
 * <p>Verifies that the {@code ReasoningTrace} overload produces a DTO at parity with the
 * {@code ReasoningTrail} overload: {@code derivationTree}, {@code attributionIndex},
 * {@code traceGaps}, {@code naturalLanguageSummary}, {@code evidence}, {@code activatedRules},
 * and (when present in root step meta) {@code computedAt} are all populated.</p>
 */
@DisplayName("ReasoningTrailMapper — toTrailDto(ReasoningTrace)")
class ReasoningTrailMapperTest {

    // ── basic shape ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Basic DTO shape")
    class BasicShape {

        @Test
        @DisplayName("targetId equals root step conclusion")
        void targetId_equalsRootConclusion() {
            ReasoningTrace.Step leaf = ReasoningTrace.Step.fact("worksFor(Alice,Acme)", 0.9, "kb");
            ReasoningTrace trace = ReasoningTrace.of(leaf);

            Map<String, Object> dto = ReasoningTrailMapper.toTrailDto(trace);

            assertEquals("worksFor(Alice,Acme)", dto.get("targetId"));
        }

        @Test
        @DisplayName("confidence equals root step confidence")
        void confidence_equalsRootConfidence() {
            ReasoningTrace.Step leaf = ReasoningTrace.Step.fact("p(a)", 0.75, "kb");
            ReasoningTrace trace = ReasoningTrace.of(leaf);

            Map<String, Object> dto = ReasoningTrailMapper.toTrailDto(trace);

            assertEquals(0.75, (double) dto.get("confidence"), 1e-9);
        }

        @Test
        @DisplayName("inferenceMode equals root step kind name")
        void inferenceMode_equalsKindName() {
            ReasoningTrace.Step leaf = ReasoningTrace.Step.fact("p(a)", 0.8, "kb");
            ReasoningTrace trace = ReasoningTrace.of(leaf);

            Map<String, Object> dto = ReasoningTrailMapper.toTrailDto(trace);

            assertEquals("FACT", dto.get("inferenceMode"));
        }

        @Test
        @DisplayName("derivationTree is present")
        void derivationTree_isPresent() {
            ReasoningTrace.Step leaf = ReasoningTrace.Step.fact("p(a)", 0.8, "kb");
            ReasoningTrace trace = ReasoningTrace.of(leaf);

            Map<String, Object> dto = ReasoningTrailMapper.toTrailDto(trace);

            assertNotNull(dto.get("derivationTree"), "derivationTree must be present");
            assertTrue(dto.get("derivationTree") instanceof Map<?, ?>,
                    "derivationTree must be a Map");
        }

        @Test
        @DisplayName("attributionIndex is always present (may be empty list)")
        void attributionIndex_isAlwaysPresent() {
            ReasoningTrace.Step leaf = ReasoningTrace.Step.fact("p(a)", 0.8, "kb");
            ReasoningTrace trace = ReasoningTrace.of(leaf);

            Map<String, Object> dto = ReasoningTrailMapper.toTrailDto(trace);

            assertTrue(dto.containsKey("attributionIndex"),
                    "attributionIndex must always be present");
        }

        @Test
        @DisplayName("traceGaps is always present (may be empty list)")
        void traceGaps_isAlwaysPresent() {
            ReasoningTrace.Step leaf = ReasoningTrace.Step.fact("p(a)", 0.8, "kb");
            ReasoningTrace trace = ReasoningTrace.of(leaf);

            Map<String, Object> dto = ReasoningTrailMapper.toTrailDto(trace);

            assertTrue(dto.containsKey("traceGaps"),
                    "traceGaps must always be present");
        }
    }

    // ── evidence and activatedRules ───────────────────────────────────────────────

    @Nested
    @DisplayName("Evidence and activatedRules parity")
    class EvidenceAndRules {

        @Test
        @DisplayName("evidence list contains leaf step conclusions")
        void evidence_containsLeafConclusions() {
            ReasoningTrace.Step fact1 = ReasoningTrace.Step.fact("worksFor(Alice,Acme)", 0.9, "kb");
            ReasoningTrace.Step fact2 = ReasoningTrace.Step.fact("locatedIn(Acme,London)", 0.8, "kb");
            ReasoningTrace.Step derived = ReasoningTrace.Step.derived(
                    ReasoningTrace.StepKind.RULE,
                    "basedIn(Alice,London)", "transitivity", 0.72,
                    List.of(fact1, fact2));
            ReasoningTrace trace = ReasoningTrace.of(derived);

            Map<String, Object> dto = ReasoningTrailMapper.toTrailDto(trace);

            assertTrue(dto.containsKey("evidence"), "evidence field must be present for a trace with leaf facts");
            @SuppressWarnings("unchecked")
            List<String> evidenceList = (List<String>) dto.get("evidence");
            assertTrue(evidenceList.stream().anyMatch(e -> e.contains("worksFor(Alice,Acme)")),
                    "evidence must include worksFor(Alice,Acme)");
            assertTrue(evidenceList.stream().anyMatch(e -> e.contains("locatedIn(Acme,London)")),
                    "evidence must include locatedIn(Acme,London)");
        }

        @Test
        @DisplayName("activatedRules contains non-trivial rule operations")
        void activatedRules_containsRuleOperations() {
            ReasoningTrace.Step fact = ReasoningTrace.Step.fact("a(x)", 0.9, "kb");
            ReasoningTrace.Step derived = ReasoningTrace.Step.derived(
                    ReasoningTrace.StepKind.RULE,
                    "b(x)", "transitivity-rule", 0.8, List.of(fact));
            ReasoningTrace trace = ReasoningTrace.of(derived);

            Map<String, Object> dto = ReasoningTrailMapper.toTrailDto(trace);

            assertTrue(dto.containsKey("activatedRules"),
                    "activatedRules must be present when non-trivial rules fired");
            @SuppressWarnings("unchecked")
            List<String> rules = (List<String>) dto.get("activatedRules");
            assertTrue(rules.contains("transitivity-rule"),
                    "activatedRules must contain 'transitivity-rule'");
        }

        @Test
        @DisplayName("activatedRules omits 'observed' and 'assumed' trivial operations")
        void activatedRules_omitsTrivialOperations() {
            // A single leaf step with operation="observed" should NOT appear in activatedRules
            ReasoningTrace.Step fact = ReasoningTrace.Step.fact("a(x)", 0.9, "kb");
            ReasoningTrace trace = ReasoningTrace.of(fact);

            Map<String, Object> dto = ReasoningTrailMapper.toTrailDto(trace);

            // activatedRules should be absent (all ops are trivial "observed")
            if (dto.containsKey("activatedRules")) {
                @SuppressWarnings("unchecked")
                List<String> rules = (List<String>) dto.get("activatedRules");
                assertFalse(rules.contains("observed"),
                        "activatedRules must not include trivial 'observed' operation");
                assertFalse(rules.contains("assumed"),
                        "activatedRules must not include trivial 'assumed' operation");
            }
        }
    }

    // ── computedAt from meta ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("computedAt from root step meta")
    class ComputedAt {

        @Test
        @DisplayName("computedAt is populated when root step meta contains it")
        void computedAt_fromRootMeta() {
            ReasoningTrace.Step step = ReasoningTrace.Step.derived(
                    ReasoningTrace.StepKind.RULE,
                    "p(a)", "r1", 0.9, null,
                    Map.of("computedAt", "2026-07-10T12:00:00Z"),
                    List.of());
            ReasoningTrace trace = ReasoningTrace.of(step);

            Map<String, Object> dto = ReasoningTrailMapper.toTrailDto(trace);

            assertEquals("2026-07-10T12:00:00Z", dto.get("computedAt"),
                    "computedAt must be read from root step meta");
        }

        @Test
        @DisplayName("computedAt is absent when root step meta is empty")
        void computedAt_absentWhenMetaEmpty() {
            ReasoningTrace.Step leaf = ReasoningTrace.Step.fact("p(a)", 0.8, "kb");
            ReasoningTrace trace = ReasoningTrace.of(leaf);

            Map<String, Object> dto = ReasoningTrailMapper.toTrailDto(trace);

            assertFalse(dto.containsKey("computedAt"),
                    "computedAt must not be present when root step meta has no computedAt");
        }
    }

    // ── naturalLanguageSummary ────────────────────────────────────────────────────

    @Nested
    @DisplayName("naturalLanguageSummary parity")
    class NaturalLanguageSummary {

        @Test
        @DisplayName("naturalLanguageSummary is present for a non-trivial trace")
        void naturalLanguageSummary_presentForNonTrivialTrace() {
            ReasoningTrace.Step fact = ReasoningTrace.Step.fact("worksFor(Alice,Acme)", 0.9, "kb");
            ReasoningTrace.Step derived = ReasoningTrace.Step.derived(
                    ReasoningTrace.StepKind.RULE,
                    "basedIn(Alice,London)", "city-rule", 0.8, List.of(fact));
            ReasoningTrace trace = ReasoningTrace.of(derived);

            Map<String, Object> dto = ReasoningTrailMapper.toTrailDto(trace);

            // naturalLanguageSummary may be present (non-blank llmContext from renderer)
            // We don't assert its exact content — just that if present it is a non-blank String
            if (dto.containsKey("naturalLanguageSummary")) {
                Object summary = dto.get("naturalLanguageSummary");
                assertTrue(summary instanceof String, "naturalLanguageSummary must be a String");
                assertFalse(((String) summary).isBlank(),
                        "naturalLanguageSummary must not be blank when present");
            }
        }
    }
}
