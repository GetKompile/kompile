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

import ai.kompile.graph.reasoning.psl.PslAtom;
import ai.kompile.graph.reasoning.psl.PslProgram;
import ai.kompile.graph.reasoning.psl.PslRule;

import java.util.ArrayList;
import java.util.List;

/**
 * Compiles a DECISION Table to a PslProgram or DatalogRule list.
 *
 * Algorithm per row:
 *   CONDITION cells → PSL body atoms (equality → crisp, range → arithmetic)
 *   CONCLUSION cells → PSL head atoms
 *   Blank CONDITION cell → wildcard (universally satisfied, omit from body)
 *   Numeric range condition → PSL ArithmeticRule (soft rule)
 *   Crisp equality → hard rule when all conditions are crisp
 *
 * Hit policy (from table.hitPolicy()):
 *   FIRST — rows ordered by index; first match wins (modeled by rule ordering, weight decay)
 *   ALL   — all matching rows fire simultaneously
 *   PRIORITY — sort by metadata "priority" column, highest weight wins
 *
 * Output: PslProgram directly consumable by HlMrfMapInference.solve(program, factStore).
 *
 * TODO: XLS import path (Apache POI) is a CLIENT-MODULE concern due to lib dep constraints.
 *       See tables-first-class-drools-removal-design.md §6.1 Fork (iii). Implement in
 *       kompile-knowledge-graph or kompile-app-core where POI is already available.
 */
public class TableDecisionCompiler {

    private static final double DEFAULT_HARD_WEIGHT = 1e6;
    private static final double DEFAULT_SOFT_WEIGHT = 2.0;
    private static final double FIRST_HIT_WEIGHT_DECAY = 0.9; // each subsequent row gets 10% less weight

    /**
     * Compile a DECISION table to a PslProgram.
     * @throws IllegalArgumentException if table.kind() != DECISION
     */
    public PslProgram compile(Table table) {
        if (table.kind() != TableKind.DECISION) {
            throw new IllegalArgumentException("TableDecisionCompiler requires a DECISION table; got " + table.kind());
        }

        List<TableColumn> condCols = table.columnsOfRole(ColumnRole.CONDITION);
        List<TableColumn> concCols = table.columnsOfRole(ColumnRole.CONCLUSION);
        String hitPolicy = table.hitPolicy();

        PslProgram program = new PslProgram();
        double weight = extractBaseWeight(table);
        int rowIndex = 0;

        for (TableRow row : table.rows()) {
            List<PslAtom> body = buildBody(row, condCols);
            List<PslAtom> head = buildHead(row, concCols);

            if (head.isEmpty()) { rowIndex++; continue; } // skip empty rows

            boolean allCrisp = isAllCrisp(row, condCols);
            double ruleWeight = allCrisp ? DEFAULT_HARD_WEIGHT : weight;

            if ("FIRST".equals(hitPolicy)) {
                ruleWeight = ruleWeight * Math.pow(FIRST_HIT_WEIGHT_DECAY, rowIndex);
            } else if ("PRIORITY".equals(hitPolicy)) {
                Object prio = row.cells().get("priority");
                if (prio != null) {
                    try { ruleWeight = Double.parseDouble(prio.toString()); } catch (NumberFormatException ignored) {}
                }
            }

            PslRule rule = new PslRule(ruleWeight, allCrisp, true, body, head, List.of());
            program.addRule(rule);
            rowIndex++;
        }

        return program;
    }

    private List<PslAtom> buildBody(TableRow row, List<TableColumn> condCols) {
        List<PslAtom> atoms = new ArrayList<>();
        for (TableColumn col : condCols) {
            Object cellVal = row.cells().get(col.name());
            if (cellVal == null || cellVal.toString().isBlank()) continue; // wildcard
            String predicate = conditionPredicate(col.name(), cellVal.toString());
            atoms.add(new PslAtom(predicate, List.of(), false));
        }
        return atoms;
    }

    private List<PslAtom> buildHead(TableRow row, List<TableColumn> concCols) {
        List<PslAtom> atoms = new ArrayList<>();
        for (TableColumn col : concCols) {
            Object cellVal = row.cells().get(col.name());
            if (cellVal == null || cellVal.toString().isBlank()) continue;
            String predicate = conclusionPredicate(col.name(), cellVal.toString());
            atoms.add(new PslAtom(predicate, List.of(), false));
        }
        return atoms;
    }

    /**
     * Convert a condition column+value to a PSL predicate name.
     * Crisp equality: "category_HIGH" for column="category", value="HIGH"
     * Numeric range: stored verbatim as a predicate, client must supply EDB truth value
     */
    private String conditionPredicate(String columnName, String value) {
        // Normalize: replace spaces and special chars
        String col = columnName.replaceAll("[^a-zA-Z0-9_]", "_");
        String val = value.trim().replaceAll("[^a-zA-Z0-9_.\\-]", "_");
        return col + "_" + val;
    }

    private String conclusionPredicate(String columnName, String value) {
        String col = columnName.replaceAll("[^a-zA-Z0-9_]", "_");
        String val = value.trim().replaceAll("[^a-zA-Z0-9_.\\-]", "_");
        return col + "_" + val;
    }

    private boolean isAllCrisp(TableRow row, List<TableColumn> condCols) {
        for (TableColumn col : condCols) {
            Object val = row.cells().get(col.name());
            if (val == null) continue;
            String sv = val.toString().trim();
            // Numeric range indicators: >, <, >=, <=, ..
            if (sv.startsWith(">") || sv.startsWith("<") || sv.contains("..") || sv.contains("-")) {
                return false;
            }
        }
        return true;
    }

    private double extractBaseWeight(Table table) {
        String wStr = table.metadata().get("weight");
        if (wStr != null) {
            try { return Double.parseDouble(wStr); } catch (NumberFormatException ignored) {}
        }
        return DEFAULT_SOFT_WEIGHT;
    }
}
