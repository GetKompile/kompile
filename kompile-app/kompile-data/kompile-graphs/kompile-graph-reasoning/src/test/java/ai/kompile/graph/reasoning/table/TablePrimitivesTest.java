/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.table;

import ai.kompile.graph.reasoning.table.io.DroolsFormatExporter;
import ai.kompile.graph.reasoning.table.io.DroolsFormatImporter;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

/**
 * Tests for Table primitives: Table, TableRow, TableColumn, TableDecisionCompiler,
 * TraceTableProjector, DroolsFormatImporter, DroolsFormatExporter.
 */
@DisplayName("Table primitives tests")
class TablePrimitivesTest {

    // ── Table record ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Table record")
    class TableRecordTests {

        @Test
        void decisionFactoryCreatesDecisionTable() {
            Table t = Table.decision("Rules", List.of(
                new TableColumn("condition", ColumnRole.CONDITION, ColumnType.STRING, ""),
                new TableColumn("action", ColumnRole.CONCLUSION, ColumnType.STRING, "")
            ));
            assertEquals(TableKind.DECISION, t.kind());
            assertEquals("Rules", t.name());
            assertEquals(2, t.columns().size());
        }

        @Test
        void withRowsAppendsRows() {
            Table base = Table.decision("Test", List.of(
                new TableColumn("col", ColumnRole.FIELD, ColumnType.STRING, "")
            ));
            TableRow row = new TableRow("r1", Map.of("col", "val"));
            Table withRow = base.withRows(List.of(row));
            assertEquals(1, withRow.rows().size());
            // base is unchanged (immutable)
            assertEquals(0, base.rows().size());
        }

        @Test
        void columnsOfRoleFilters() {
            Table t = Table.decision("t", List.of(
                new TableColumn("c1", ColumnRole.CONDITION, ColumnType.STRING, ""),
                new TableColumn("c2", ColumnRole.CONCLUSION, ColumnType.STRING, ""),
                new TableColumn("c3", ColumnRole.CONDITION, ColumnType.STRING, "")
            ));
            List<TableColumn> conds = t.columnsOfRole(ColumnRole.CONDITION);
            assertEquals(2, conds.size());
        }

        @Test
        void hitPolicyDefaultsToFirst() {
            Table t = Table.decision("t", List.of());
            assertEquals("FIRST", t.hitPolicy());
        }

        @Test
        void toJsonContainsKind() {
            Table t = Table.data("MyData", List.of(), List.of());
            String json = t.toJson();
            assertTrue(json.contains("\"kind\":\"DATA\""));
            assertTrue(json.contains("\"name\":\"MyData\""));
        }

        @Test
        void generatesIdWhenNull() {
            Table t = Table.decision(null, List.of());
            assertNotNull(t.id());
            assertFalse(t.id().isBlank());
        }

        @Test
        void kindRequiredNotNull() {
            assertThrows(NullPointerException.class, () ->
                new Table("id", "name", null, List.of(), List.of(), Map.of(), null));
        }
    }

    // ── TableRow ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("TableRow")
    class TableRowTests {

        @Test
        void getCastsCorrectly() {
            TableRow row = new TableRow("r1", Map.of("num", "42", "flag", "true"));
            assertEquals("42", row.get("num", String.class));
        }

        @Test
        void isBlankForNullValue() {
            TableRow row = new TableRow("r1", Map.of());
            assertTrue(row.isBlank("missing"));
        }

        @Test
        void isBlankForEmptyString() {
            TableRow row = new TableRow("r1", Map.of("col", "  "));
            assertTrue(row.isBlank("col"));
        }

        @Test
        void nullRowIdRejects() {
            assertThrows(NullPointerException.class, () -> new TableRow(null, Map.of()));
        }
    }

    // ── TableDecisionCompiler ─────────────────────────────────────────────────

    @Nested
    @DisplayName("TableDecisionCompiler")
    class CompilerTests {

        private TableDecisionCompiler compiler = new TableDecisionCompiler();

        @Test
        void compilesDecisionTableToProgram() {
            Table t = Table.decision("CreditRules", List.of(
                new TableColumn("riskLevel", ColumnRole.CONDITION, ColumnType.STRING, ""),
                new TableColumn("decision", ColumnRole.CONCLUSION, ColumnType.STRING, "")
            )).withRows(List.of(
                new TableRow("row1", Map.of("riskLevel", "HIGH", "decision", "DENY")),
                new TableRow("row2", Map.of("riskLevel", "LOW", "decision", "APPROVE"))
            ));
            var program = compiler.compile(t);
            assertNotNull(program);
            assertEquals(2, program.rules().size());
        }

        @Test
        void rejectsNonDecisionTable() {
            Table t = Table.trace("T", List.of(), List.of());
            assertThrows(IllegalArgumentException.class, () -> compiler.compile(t));
        }

        @Test
        void skipsRowsWithEmptyConclusion() {
            Table t = Table.decision("Test", List.of(
                new TableColumn("c", ColumnRole.CONDITION, ColumnType.STRING, ""),
                new TableColumn("a", ColumnRole.CONCLUSION, ColumnType.STRING, "")
            )).withRows(List.of(
                new TableRow("r1", Map.of("c", "X", "a", "")),  // empty conclusion → skip
                new TableRow("r2", Map.of("c", "Y", "a", "APPROVE"))
            ));
            var program = compiler.compile(t);
            assertEquals(1, program.rules().size());
        }

        @Test
        void wildcardConditionOmittedFromBody() {
            Table t = Table.decision("Test", List.of(
                new TableColumn("c1", ColumnRole.CONDITION, ColumnType.STRING, ""),
                new TableColumn("c2", ColumnRole.CONDITION, ColumnType.STRING, ""),
                new TableColumn("a", ColumnRole.CONCLUSION, ColumnType.STRING, "")
            )).withRows(List.of(
                // c2 is blank → wildcard; body should only have c1 atom
                new TableRow("r1", Map.of("c1", "X", "c2", "", "a", "DO_IT"))
            ));
            var program = compiler.compile(t);
            assertEquals(1, program.rules().size());
            // body should have 1 atom (c1 only), not 2
            assertEquals(1, program.rules().get(0).body().size());
        }
    }

    // ── TraceTableProjector ───────────────────────────────────────────────────

    @Nested
    @DisplayName("TraceTableProjector")
    class ProjectorTests {

        @Test
        void fromEntailmentsProducesTraceTable() {
            var records = List.of(
                new ai.kompile.graph.reasoning.fol.EntailmentRecord(
                    "foo(bar)", 0.8,
                    List.of("ev1"), List.of("rule1"),
                    java.time.Instant.now(), "run-1")
            );
            Table t = TraceTableProjector.fromEntailments(records, "fs-1");
            assertEquals(TableKind.TRACE, t.kind());
            assertEquals(1, t.rows().size());
            assertEquals("foo(bar)", t.rows().get(0).cells().get("atomKey"));
            assertEquals("fs-1", t.metadata().get("factSheetId"));
        }

        @Test
        void fromEntailmentsNullListProducesEmptyTable() {
            Table t = TraceTableProjector.fromEntailments(null);
            assertEquals(0, t.rows().size());
        }

        @Test
        void fromInferenceStepsProducesTraceTable() {
            ai.kompile.graph.reasoning.domain.InferenceStep step =
                ai.kompile.graph.reasoning.domain.InferenceStep.builder()
                    .operation("REDUCE")
                    .eliminatedVariable("x1")
                    .build();
            Table t = TraceTableProjector.fromInferenceSteps(List.of(step), "trail-1");
            assertEquals(TableKind.TRACE, t.kind());
            assertEquals(1, t.rows().size());
            assertEquals("REDUCE", t.rows().get(0).cells().get("stepType"));
            assertEquals("x1", t.rows().get(0).cells().get("description"));
        }
    }

    // ── DroolsFormatImporter ──────────────────────────────────────────────────

    @Nested
    @DisplayName("DroolsFormatImporter")
    class ImporterTests {

        @Test
        void parsesSimpleDrl() {
            String drl = "package ai.test\n\n" +
                "rule \"approve-low-risk\"\n" +
                "  when\n" +
                "    RiskLevel(value == \"LOW\")\n" +
                "  then\n" +
                "    approve();\n" +
                "end\n";
            Table t = DroolsFormatImporter.fromDrl(drl);
            assertEquals(TableKind.DECISION, t.kind());
            assertEquals(1, t.rows().size());
            assertEquals("ai.test", t.metadata().get("package"));
        }

        @Test
        void emptyDrlReturnsEmptyTable() {
            Table t = DroolsFormatImporter.fromDrl(null);
            assertEquals(0, t.rows().size());
        }

        @Test
        void parsesSimpleCsv() {
            String csv = "category,action\nC,A\nHIGH,DENY\nLOW,APPROVE\n";
            Table t = DroolsFormatImporter.fromCsv(csv);
            assertEquals(2, t.rows().size());
            assertEquals(ColumnRole.CONDITION, t.columns().get(0).role());
            assertEquals(ColumnRole.CONCLUSION, t.columns().get(1).role());
        }

        @Test
        void csvWithoutRoleRowDefaultsToField() {
            String csv = "col1,col2\nA,B\n";
            Table t = DroolsFormatImporter.fromCsv(csv);
            // "A" is not a role tag prefix (can't be all role chars), so treated as data
            // Actually isRoleRow checks all cells are C/A/P/F — "A" alone IS a valid role tag
            // but "B" is not → not a role row → both become FIELD
            assertEquals(ColumnRole.FIELD, t.columns().get(0).role());
        }

        @Test
        void csvLineParserHandlesQuotes() {
            // parseCsv is public; use it to test quoted cell parsing
            var rows = DroolsFormatImporter.parseCsv("\"hello, world\",foo,\"a\"\"b\"");
            assertEquals(1, rows.size());
            String[] cells = rows.get(0);
            assertEquals(3, cells.length);
            assertEquals("hello, world", cells[0]);
            assertEquals("foo", cells[1]);
            assertEquals("a\"b", cells[2]);
        }
    }

    // ── DroolsFormatExporter ──────────────────────────────────────────────────

    @Nested
    @DisplayName("DroolsFormatExporter")
    class ExporterTests {

        @Test
        void exportsToDrl() {
            Table t = Table.decision("Rules", List.of(
                new TableColumn("RiskLevel", ColumnRole.CONDITION, ColumnType.STRING, ""),
                new TableColumn("action", ColumnRole.CONCLUSION, ColumnType.STRING, "")
            )).withRows(List.of(
                new TableRow("approve-low", Map.of("RiskLevel", "LOW", "action", "approve()"))
            ));
            String drl = DroolsFormatExporter.toDrl(t, "com.example");
            assertTrue(drl.contains("rule \"approve-low\""));
            assertTrue(drl.contains("LOW"));
            assertTrue(drl.contains("approve()"));
            assertTrue(drl.contains("package com.example"));
        }

        @Test
        void exportsToCsvWithRoleRow() {
            Table t = Table.decision("T", List.of(
                new TableColumn("c", ColumnRole.CONDITION, ColumnType.STRING, ""),
                new TableColumn("a", ColumnRole.CONCLUSION, ColumnType.STRING, "")
            )).withRows(List.of(
                new TableRow("r1", Map.of("c", "X", "a", "Y"))
            ));
            String csv = DroolsFormatExporter.toCsv(t);
            String[] lines = csv.split("\n");
            assertTrue(lines.length >= 3); // header + role + data
            assertEquals("c,a", lines[0]);
            assertEquals("C,A", lines[1]);
            assertEquals("X,Y", lines[2]);
        }

        @Test
        void drlExportRejectsNonDecisionTable() {
            Table t = Table.trace("T", List.of(), List.of());
            assertThrows(IllegalArgumentException.class, () -> DroolsFormatExporter.toDrl(t));
        }

        @Test
        void csvCellQuotesCommas() {
            Table t = Table.data("T", List.of(
                new TableColumn("v", ColumnRole.FIELD, ColumnType.STRING, "")
            ), List.of(
                new TableRow("r1", Map.of("v", "hello, world"))
            ));
            String csv = DroolsFormatExporter.toCsv(t);
            assertTrue(csv.contains("\"hello, world\""));
        }
    }

    // ── CreationTimeView ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("CreationTimeView")
    class CreationTimeViewTests {

        private ai.kompile.graph.reasoning.model.MutableReasoningGraph buildGraph() {
            ai.kompile.graph.reasoning.model.MutableReasoningGraph g =
                new ai.kompile.graph.reasoning.model.MutableReasoningGraph();
            // Entity with _extractedAt = 2025-01-15
            g.addEntity(ai.kompile.graph.reasoning.model.GraphEntity.builder("e1")
                .type("DOC").label("Doc1")
                .attribute(ai.kompile.graph.reasoning.model.CreationTimeView.EXTRACTED_AT_KEY,
                    "2025-01-15T00:00:00Z")
                .build());
            // Entity with _extractedAt = 2025-06-01
            g.addEntity(ai.kompile.graph.reasoning.model.GraphEntity.builder("e2")
                .type("DOC").label("Doc2")
                .attribute(ai.kompile.graph.reasoning.model.CreationTimeView.EXTRACTED_AT_KEY,
                    "2025-06-01T00:00:00Z")
                .build());
            // Timeless entity
            g.addEntity(ai.kompile.graph.reasoning.model.GraphEntity.builder("e3")
                .type("DOC").label("Doc3")
                .build());
            return g;
        }

        @Test
        void asOfExcludesLaterElements() {
            var graph = buildGraph();
            var view = ai.kompile.graph.reasoning.model.CreationTimeView.asOf(
                graph, java.time.Instant.parse("2025-03-01T00:00:00Z"));
            // e1 (Jan) passes, e2 (June) fails, e3 (timeless) passes
            var ids = view.entities().stream()
                .map(ai.kompile.graph.reasoning.model.GraphEntity::id)
                .collect(java.util.stream.Collectors.toSet());
            assertTrue(ids.contains("e1"));
            assertFalse(ids.contains("e2"));
            assertTrue(ids.contains("e3"));
        }

        @Test
        void betweenFiltersCorrectly() {
            var graph = buildGraph();
            var view = ai.kompile.graph.reasoning.model.CreationTimeView.between(
                graph,
                java.time.Instant.parse("2025-01-01T00:00:00Z"),
                java.time.Instant.parse("2025-04-01T00:00:00Z"));
            var ids = view.entities().stream()
                .map(ai.kompile.graph.reasoning.model.GraphEntity::id)
                .collect(java.util.stream.Collectors.toSet());
            assertTrue(ids.contains("e1"));
            assertFalse(ids.contains("e2"));
            assertTrue(ids.contains("e3")); // timeless always included
        }

        @Test
        void byCrawlRunFilters() {
            ai.kompile.graph.reasoning.model.MutableReasoningGraph g =
                new ai.kompile.graph.reasoning.model.MutableReasoningGraph();
            g.addEntity(ai.kompile.graph.reasoning.model.GraphEntity.builder("e1")
                .type("DOC").label("D1")
                .attribute(ai.kompile.graph.reasoning.model.CreationTimeView.CRAWL_RUN_ID_KEY, "run-42")
                .build());
            g.addEntity(ai.kompile.graph.reasoning.model.GraphEntity.builder("e2")
                .type("DOC").label("D2")
                .attribute(ai.kompile.graph.reasoning.model.CreationTimeView.CRAWL_RUN_ID_KEY, "run-99")
                .build());
            var view = ai.kompile.graph.reasoning.model.CreationTimeView.byCrawlRun(g, "run-42");
            var ids = view.entities().stream()
                .map(ai.kompile.graph.reasoning.model.GraphEntity::id)
                .collect(java.util.stream.Collectors.toSet());
            assertTrue(ids.contains("e1"));
            assertFalse(ids.contains("e2"));
        }
    }

    // ── PlattCalibrator ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("PlattCalibrator")
    class CalibratorTests {

        @Test
        void refutedAlwaysReturnsZero() {
            var cal = new ai.kompile.graph.reasoning.fol.grounding.PlattCalibrator();
            var refuted = ai.kompile.graph.reasoning.fol.grounding.VerifyResult.refuted(0.9, List.of());
            assertEquals(0.0, cal.calibrate(0.99,
                ai.kompile.graph.reasoning.fol.grounding.StrengthCalibrator.SignalType.OBSERVED,
                refuted), 1e-9);
        }

        @Test
        void unknownCapsAtCeiling() {
            var cal = new ai.kompile.graph.reasoning.fol.grounding.PlattCalibrator();
            var unknown = ai.kompile.graph.reasoning.fol.grounding.VerifyResult.unknown();
            double result = cal.calibrate(1.0,
                ai.kompile.graph.reasoning.fol.grounding.StrengthCalibrator.SignalType.OBSERVED,
                unknown);
            assertTrue(result <= 0.31); // ceiling is 0.3
        }

        @Test
        void supportedPassesThroughSigmoid() {
            var cal = new ai.kompile.graph.reasoning.fol.grounding.PlattCalibrator();
            var supported = ai.kompile.graph.reasoning.fol.grounding.VerifyResult.supported(0.8, List.of());
            double result = cal.calibrate(0.5,
                ai.kompile.graph.reasoning.fol.grounding.StrengthCalibrator.SignalType.PSL_SOFT_TRUTH,
                supported);
            // sigmoid(1.0 * 0.5 + 0.0) = sigmoid(0.5) ≈ 0.622
            assertEquals(0.622, result, 0.002);
        }

        @Test
        void updateFromLabeledBatchConverges() {
            var cal = new ai.kompile.graph.reasoning.fol.grounding.PlattCalibrator();
            var batch = List.of(
                new ai.kompile.graph.reasoning.fol.grounding.StrengthCalibrator.LabeledScore(0.9, 1.0),
                new ai.kompile.graph.reasoning.fol.grounding.StrengthCalibrator.LabeledScore(0.1, 0.0),
                new ai.kompile.graph.reasoning.fol.grounding.StrengthCalibrator.LabeledScore(0.8, 1.0),
                new ai.kompile.graph.reasoning.fol.grounding.StrengthCalibrator.LabeledScore(0.2, 0.0)
            );
            cal.updateFromLabeledBatch(
                ai.kompile.graph.reasoning.fol.grounding.StrengthCalibrator.SignalType.PSL_SOFT_TRUTH,
                batch);
            double[] params = cal.getParams(
                ai.kompile.graph.reasoning.fol.grounding.StrengthCalibrator.SignalType.PSL_SOFT_TRUTH);
            // After fitting: slope should be positive (higher raw → higher calibrated)
            assertTrue(params[0] > 0);
        }
    }

    // ── GroundedElement ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("GroundedElement")
    class GroundedElementTests {

        @Test
        void isVerifiedWhenSupported() {
            var vr = ai.kompile.graph.reasoning.fol.grounding.VerifyResult.supported(0.9, List.of("ev1"));
            var ge = new ai.kompile.graph.reasoning.fol.grounding.GroundedElement<>(
                "process-step-X", vr, 0.85,
                ai.kompile.graph.reasoning.confidence.StrengthBand.ESTABLISHED,
                "active(process-step-X)", null, null, "INDUCTIVE_MINER");
            assertTrue(ge.isVerified());
            assertFalse(ge.isRefuted());
            assertEquals(List.of("ev1"), ge.evidence());
        }

        @Test
        void isRefutedWhenRefuted() {
            var vr = ai.kompile.graph.reasoning.fol.grounding.VerifyResult.refuted(0.9, List.of());
            var ge = new ai.kompile.graph.reasoning.fol.grounding.GroundedElement<>(
                "dummy", vr, 0.0,
                ai.kompile.graph.reasoning.confidence.StrengthBand.SUPPRESSED,
                "key", null, null, "TEST");
            assertTrue(ge.isRefuted());
            assertFalse(ge.isVerified());
        }

        @Test
        void derivationTreeOptIsEmpty() {
            var vr = ai.kompile.graph.reasoning.fol.grounding.VerifyResult.unknown();
            var ge = new ai.kompile.graph.reasoning.fol.grounding.GroundedElement<>(
                "x", vr, 0.0,
                ai.kompile.graph.reasoning.confidence.StrengthBand.SUPPRESSED,
                "k", null, null, "G");
            assertFalse(ge.hasDerivationTree());
            assertTrue(ge.derivationTreeOpt().isEmpty());
        }

        @Test
        void invalidConfidenceRejects() {
            var vr = ai.kompile.graph.reasoning.fol.grounding.VerifyResult.unknown();
            assertThrows(IllegalArgumentException.class, () ->
                new ai.kompile.graph.reasoning.fol.grounding.GroundedElement<>(
                    "x", vr, 1.5,
                    ai.kompile.graph.reasoning.confidence.StrengthBand.SUPPRESSED,
                    "k", null, null, "G"));
        }
    }

    // ── ReasoningTrail ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ReasoningTrail builder")
    class TrailTests {

        @Test
        void builderProducesTrail() {
            var trail = ai.kompile.graph.reasoning.explain.ReasoningTrail.builder("target-1")
                .question("Why did X happen?")
                .confidence(0.85)
                .inferenceMode("PSL")
                .runId("run-42")
                .evidence(List.of("ev1", "ev2"))
                .activatedRules(List.of("rule1"))
                .build();
            assertEquals("target-1", trail.targetId());
            assertEquals("Why did X happen?", trail.question());
            assertEquals(0.85, trail.confidence(), 1e-9);
            assertEquals("PSL", trail.inferenceMode());
            assertEquals(2, trail.evidence().size());
            assertFalse(trail.hasDerivationTree());
            assertFalse(trail.hasEntailments());
        }

        @Test
        void nullTargetIdRejects() {
            assertThrows(NullPointerException.class, () ->
                ai.kompile.graph.reasoning.explain.ReasoningTrail.builder(null).build());
        }

        @Test
        void defaultsArePopulated() {
            var trail = ai.kompile.graph.reasoning.explain.ReasoningTrail.builder("t").build();
            assertEquals("UNKNOWN", trail.inferenceMode());
            assertEquals("", trail.runId());
            assertNotNull(trail.computedAt());
            assertNotNull(trail.breakdown());
        }
    }

    // ── ConfidenceBreakdown ───────────────────────────────────────────────────

    @Nested
    @DisplayName("ConfidenceBreakdown factories")
    class BreakdownTests {

        @Test
        void emptyHasNaN() {
            var b = ai.kompile.graph.reasoning.explain.ConfidenceBreakdown.empty();
            assertTrue(Double.isNaN(b.groundingConfidence()));
            assertTrue(Double.isNaN(b.pslSoftTruth()));
        }

        @Test
        void ofPslSetsFields() {
            var b = ai.kompile.graph.reasoning.explain.ConfidenceBreakdown.ofPsl(0.7, 0.1);
            assertEquals(0.7, b.pslSoftTruth(), 1e-9);
            assertEquals(0.1, b.distanceToSatisfaction(), 1e-9);
            assertTrue(Double.isNaN(b.mebnPosterior()));
        }

        @Test
        void ofHybridSetsStructuralAndSemantic() {
            var b = ai.kompile.graph.reasoning.explain.ConfidenceBreakdown.ofHybrid(0.6, 0.8, 0.4, 0.6);
            assertEquals(0.6, b.structuralScore(), 1e-9);
            assertEquals(0.8, b.semanticScore(), 1e-9);
        }
    }

    // ── AttributionConfidence bridge ──────────────────────────────────────────

    @Nested
    @DisplayName("AttributionConfidence bridge")
    class AttributionBridgeTests {

        @Test
        void toStrengthBandBridgeWorks() {
            var ac = ai.kompile.graph.reasoning.domain.AttributionConfidence.DEFINITIVE;
            assertEquals(ai.kompile.graph.reasoning.confidence.StrengthBand.ESTABLISHED, ac.toStrengthBand());
        }

        @Test
        void insufficientMapsSuppressed() {
            var ac = ai.kompile.graph.reasoning.domain.AttributionConfidence.INSUFFICIENT;
            assertEquals(ai.kompile.graph.reasoning.confidence.StrengthBand.SUPPRESSED, ac.toStrengthBand());
        }
    }
}
