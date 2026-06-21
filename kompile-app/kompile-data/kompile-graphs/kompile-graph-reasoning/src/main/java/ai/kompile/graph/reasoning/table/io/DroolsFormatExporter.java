/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.table.io;

import ai.kompile.graph.reasoning.table.*;

import java.util.*;

/**
 * Exports Table → DRL text or CSV.
 * No KIE runtime. No Apache POI.
 *
 * TODO: XLS export needs Apache POI (CLIENT module concern, same as import).
 */
public final class DroolsFormatExporter {

    private DroolsFormatExporter() {}

    /**
     * Table(kind=DECISION) → DRL text.
     * Condition cells → when clause. Conclusion cells → then clause.
     */
    public static String toDrl(Table table) {
        return toDrl(table, table.metadata().getOrDefault("package", "ai.kompile.rules"));
    }

    public static String toDrl(Table table, String packageName) {
        if (table.kind() != TableKind.DECISION) {
            throw new IllegalArgumentException("DRL export requires a DECISION table; got " + table.kind());
        }

        List<TableColumn> condCols = table.columnsOfRole(ColumnRole.CONDITION);
        List<TableColumn> concCols = table.columnsOfRole(ColumnRole.CONCLUSION);

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(packageName).append("\n\n");

        for (TableRow row : table.rows()) {
            sb.append("rule \"").append(row.rowId()).append("\"\n");
            sb.append("  when\n");
            for (TableColumn col : condCols) {
                Object val = row.cells().get(col.name());
                if (val == null || val.toString().isBlank()) continue;
                sb.append("    ").append(val).append("\n");
            }
            sb.append("  then\n");
            for (TableColumn col : concCols) {
                Object val = row.cells().get(col.name());
                if (val == null || val.toString().isBlank()) continue;
                sb.append("    ").append(val).append("\n");
            }
            sb.append("end\n\n");
        }

        return sb.toString();
    }

    /**
     * Table → CSV (RFC 4180).
     * First row = column headers. Remaining rows = data.
     */
    public static String toCsv(Table table) {
        StringBuilder sb = new StringBuilder();

        // Header row
        List<TableColumn> cols = table.columns();
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(csvCell(cols.get(i).name()));
        }
        sb.append("\n");

        // Role row for DECISION tables
        if (table.kind() == TableKind.DECISION) {
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) sb.append(",");
                switch (cols.get(i).role()) {
                    case CONDITION:  sb.append("C"); break;
                    case CONCLUSION: sb.append("A"); break;
                    default:         sb.append("F"); break;
                }
            }
            sb.append("\n");
        }

        // Data rows
        for (TableRow row : table.rows()) {
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) sb.append(",");
                Object val = row.cells().get(cols.get(i).name());
                sb.append(csvCell(val == null ? "" : val.toString()));
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private static String csvCell(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
