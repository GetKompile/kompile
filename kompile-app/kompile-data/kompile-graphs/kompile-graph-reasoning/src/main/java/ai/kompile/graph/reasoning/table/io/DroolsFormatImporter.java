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
import java.util.regex.*;

/**
 * Imports DRL text and CSV into Table(kind=DECISION).
 * No KIE runtime dependency. No Apache POI (XLS import is a CLIENT concern — see TODO below).
 *
 * TODO: XLS/XLSX import requires Apache POI which is NOT a lib dependency.
 *       Implement XlsDecisionTableImporter in kompile-knowledge-graph or kompile-app-core
 *       where POI is already available (tables-first-class-drools-removal-design.md §6.1,
 *       Fork iii recommendation: hand-roll with POI in the client module).
 */
public final class DroolsFormatImporter {

    private DroolsFormatImporter() {}

    // ── DRL → Table ──────────────────────────────────────────────────────────

    private static final Pattern RULE_PATTERN = Pattern.compile(
        "rule\\s+\"([^\"]+)\"[\\s\\S]*?when([\\s\\S]*?)then([\\s\\S]*?)end",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("^package\\s+(\\S+)", Pattern.MULTILINE);

    /**
     * Parse DRL text → Table(kind=DECISION).
     * Each rule block → one TableRow.
     * 'when' clause → CONDITION cells (predicate name as column).
     * 'then' clause → CONCLUSION cells (action text stored verbatim).
     * Non-rule DRL (package, import, global) → metadata.
     */
    public static Table fromDrl(String drl) {
        if (drl == null || drl.isBlank()) {
            return new Table(null, "empty", TableKind.DECISION, List.of(), List.of(), Map.of(), null);
        }

        Map<String, String> meta = new LinkedHashMap<>();
        Matcher pkgM = PACKAGE_PATTERN.matcher(drl);
        if (pkgM.find()) meta.put("package", pkgM.group(1));

        // Collect all condition + conclusion column names from all rules
        Set<String> condCols = new LinkedHashSet<>();
        Set<String> concCols = new LinkedHashSet<>();
        List<Map<String, String>> rawRows = new ArrayList<>();

        Matcher ruleMatcher = RULE_PATTERN.matcher(drl);
        while (ruleMatcher.find()) {
            String ruleName = ruleMatcher.group(1).trim();
            String whenClause = ruleMatcher.group(2).trim();
            String thenClause = ruleMatcher.group(3).trim();

            Map<String, String> cells = new LinkedHashMap<>();
            cells.put("_ruleName", ruleName);

            // Parse when clause: each non-blank line is a condition
            for (String line : whenClause.split("\\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                // Extract predicate name: "SomeFact(value > 100)" → "SomeFact"
                String predName = extractPredicateName(line);
                condCols.add(predName);
                cells.put("COND_" + predName, line);
            }

            // Then clause: store verbatim as "action"
            String concKey = "action";
            concCols.add(concKey);
            cells.put("CONC_" + concKey, thenClause.trim());

            rawRows.add(cells);
        }

        // Build column list
        List<TableColumn> columns = new ArrayList<>();
        for (String c : condCols) {
            columns.add(new TableColumn(c, ColumnRole.CONDITION, ColumnType.STRING, "Condition: " + c));
        }
        for (String c : concCols) {
            columns.add(new TableColumn(c, ColumnRole.CONCLUSION, ColumnType.STRING, "Conclusion: " + c));
        }

        // Build rows
        List<TableRow> rows = new ArrayList<>();
        for (Map<String, String> raw : rawRows) {
            Map<String, Object> cells = new LinkedHashMap<>();
            String rowId = raw.getOrDefault("_ruleName", UUID.randomUUID().toString());
            for (String col : condCols) {
                cells.put(col, raw.getOrDefault("COND_" + col, ""));
            }
            for (String col : concCols) {
                cells.put(col, raw.getOrDefault("CONC_" + col, ""));
            }
            rows.add(new TableRow(rowId, cells));
        }

        return new Table(null, "DrlImport", TableKind.DECISION, columns, rows, meta, null);
    }

    private static String extractPredicateName(String line) {
        int paren = line.indexOf('(');
        if (paren > 0) {
            // Handle binding: "$f : SomeFact(...)" → "SomeFact"
            String before = line.substring(0, paren).trim();
            int colon = before.lastIndexOf(':');
            if (colon >= 0) before = before.substring(colon + 1).trim();
            return before.replaceAll("[^a-zA-Z0-9_]", "_");
        }
        return line.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    // ── CSV → Table ───────────────────────────────────────────────────────────

    /**
     * Parse RFC 4180 CSV → Table(kind=DECISION).
     * First row = column headers.
     * Second row (optional) = column roles: C (CONDITION), A (CONCLUSION/action), P (priority), F (FIELD).
     * Remaining rows = data rows.
     */
    public static Table fromCsv(String csv) {
        return fromCsv(csv, TableKind.DECISION);
    }

    public static Table fromCsv(String csv, TableKind kind) {
        if (csv == null || csv.isBlank()) {
            return new Table(null, "empty", kind, List.of(), List.of(), Map.of(), null);
        }

        List<String[]> parsedRows = parseCsv(csv);
        if (parsedRows.isEmpty()) {
            return new Table(null, "empty", kind, List.of(), List.of(), Map.of(), null);
        }

        String[] headers = parsedRows.get(0);
        int dataStart = 1;
        String[] roleRow = null;

        // Check if row 2 contains role tags (C/A/P/F)
        if (parsedRows.size() > 1) {
            String[] candidate = parsedRows.get(1);
            if (isRoleRow(candidate)) {
                roleRow = candidate;
                dataStart = 2;
            }
        }

        List<TableColumn> columns = new ArrayList<>();
        for (int i = 0; i < headers.length; i++) {
            String h = headers[i].trim();
            ColumnRole role = ColumnRole.FIELD;
            if (roleRow != null && i < roleRow.length) {
                role = parseRole(roleRow[i].trim());
            }
            columns.add(new TableColumn(h, role, ColumnType.STRING, ""));
        }

        List<TableRow> rows = new ArrayList<>();
        for (int r = dataStart; r < parsedRows.size(); r++) {
            String[] cells = parsedRows.get(r);
            Map<String, Object> cellMap = new LinkedHashMap<>();
            for (int i = 0; i < headers.length; i++) {
                String val = i < cells.length ? cells[i].trim() : "";
                cellMap.put(headers[i].trim(), val);
            }
            rows.add(new TableRow(UUID.randomUUID().toString(), cellMap));
        }

        return new Table(null, "CsvImport", kind, columns, rows, Map.of(), null);
    }

    private static boolean isRoleRow(String[] row) {
        for (String cell : row) {
            String t = cell.trim().toUpperCase();
            if (!t.isEmpty() && !t.equals("C") && !t.equals("A") && !t.equals("P") && !t.equals("F")) {
                return false;
            }
        }
        return true;
    }

    private static ColumnRole parseRole(String tag) {
        switch (tag.toUpperCase()) {
            case "C": return ColumnRole.CONDITION;
            case "A": return ColumnRole.CONCLUSION;
            default:  return ColumnRole.FIELD;
        }
    }

    /** Minimal RFC 4180 CSV parser (no quoted-newline support). */
    public static List<String[]> parseCsv(String csv) {
        List<String[]> result = new ArrayList<>();
        for (String line : csv.split("\\r?\\n")) {
            if (line.isBlank()) continue;
            result.add(splitCsvLine(line));
        }
        return result;
    }

    public static String[] splitCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder cell = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"'); i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        cells.add(cell.toString());
        return cells.toArray(new String[0]);
    }
}
