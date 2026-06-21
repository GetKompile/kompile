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

import ai.kompile.graph.reasoning.fol.EntailmentRecord;
import ai.kompile.graph.reasoning.domain.InferenceStep;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Projects ReasoningTrail / EntailmentRecord / InferenceStep lists → Table(kind=TRACE).
 * This is PROJECTION only: source records are unchanged (their contracts are preserved).
 *
 * Design decision (tables-first-class-drools-removal-design.md §5.1):
 * Records PROJECT to Table, they do NOT become Table. This is zero-cost at inference time.
 */
public final class TraceTableProjector {

    private TraceTableProjector() {}

    // ─── EntailmentRecord → Table ──────────────────────────────────────────────

    /**
     * List<EntailmentRecord> → Table(kind=TRACE).
     * Columns: atomKey, posterior, supportingFindingKeys, activatedRules, computedAt, inferenceRunId
     */
    public static Table fromEntailments(List<EntailmentRecord> records) {
        return fromEntailments(records, null);
    }

    public static Table fromEntailments(List<EntailmentRecord> records, String factSheetId) {
        List<TableColumn> cols = List.of(
            new TableColumn("atomKey",               ColumnRole.FIELD, ColumnType.STRING,  "Grounded atom key"),
            new TableColumn("posterior",             ColumnRole.FIELD, ColumnType.DOUBLE,  "Posterior probability or soft-truth"),
            new TableColumn("supportingFindingKeys", ColumnRole.FIELD, ColumnType.STRING,  "Supporting finding keys (joined)"),
            new TableColumn("activatedRules",        ColumnRole.FIELD, ColumnType.STRING,  "Activated rule names (joined)"),
            new TableColumn("computedAt",            ColumnRole.FIELD, ColumnType.INSTANT, "When computed"),
            new TableColumn("inferenceRunId",        ColumnRole.FIELD, ColumnType.STRING,  "Inference run id")
        );

        List<TableRow> rows = new ArrayList<>();
        if (records != null) {
            for (EntailmentRecord rec : records) {
                Map<String, Object> cells = new LinkedHashMap<>();
                cells.put("atomKey",               rec.groundedRvOrAtomKey());
                cells.put("posterior",             String.valueOf(rec.posterior()));
                cells.put("supportingFindingKeys", String.join("|", rec.supportingFindingKeys()));
                cells.put("activatedRules",        String.join("|", rec.activatedRules()));
                cells.put("computedAt",            rec.computedAt().toString());
                cells.put("inferenceRunId",        rec.inferenceRunId());
                rows.add(new TableRow(UUID.randomUUID().toString(), cells));
            }
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("sourceType", "ENTAILMENTS");
        if (factSheetId != null) meta.put("factSheetId", factSheetId);

        return new Table(null, "EntailmentTrace", TableKind.TRACE, cols, rows, meta, Instant.now());
    }

    // ─── InferenceStep → Table ─────────────────────────────────────────────────

    /**
     * List<InferenceStep> → Table(kind=TRACE).
     * Columns: stepType (operation), description (eliminatedVariable), stepIndex.
     * InferenceStep uses Lombok @Data so accessors are getOperation()/getEliminatedVariable().
     */
    public static Table fromInferenceSteps(List<InferenceStep> steps, String trailId) {
        List<TableColumn> cols = List.of(
            new TableColumn("stepType",    ColumnRole.FIELD, ColumnType.STRING, "Step type (operation)"),
            new TableColumn("description", ColumnRole.FIELD, ColumnType.STRING, "Eliminated variable / description"),
            new TableColumn("stepIndex",   ColumnRole.FIELD, ColumnType.DOUBLE, "Step index (0-based)")
        );

        List<TableRow> rows = new ArrayList<>();
        if (steps != null) {
            for (int i = 0; i < steps.size(); i++) {
                InferenceStep step = steps.get(i);
                Map<String, Object> cells = new LinkedHashMap<>();
                cells.put("stepType",    step.getOperation() != null ? step.getOperation() : "");
                cells.put("description", step.getEliminatedVariable() != null ? step.getEliminatedVariable() : "");
                cells.put("stepIndex",   String.valueOf(i));
                rows.add(new TableRow(UUID.randomUUID().toString(), cells));
            }
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("sourceType", "INFERENCE_STEPS");
        if (trailId != null) meta.put("trailId", trailId);

        return new Table(null, "InferenceStepsTrace", TableKind.TRACE, cols, rows, meta, Instant.now());
    }
}
