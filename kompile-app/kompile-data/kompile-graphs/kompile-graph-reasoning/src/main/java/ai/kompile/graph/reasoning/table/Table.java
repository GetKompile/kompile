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

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A first-class Table primitive: columns with ColumnRole, typed rows, TableKind metadata.
 * No Spring, no JPA, no Jackson runtime — hand-rolled JSON serialization.
 *
 * Hit policy stored in metadata.get("hitPolicy"): FIRST (default), ALL, PRIORITY.
 */
public record Table(
    String id,
    String name,
    TableKind kind,
    List<TableColumn> columns,
    List<TableRow> rows,
    Map<String, String> metadata,
    Instant createdAt
) {
    public Table {
        Objects.requireNonNull(kind, "kind");
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
        if (name == null) name = "";
        columns = columns == null ? List.of() : List.copyOf(columns);
        rows = rows == null ? List.of() : List.copyOf(rows);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        if (createdAt == null) createdAt = Instant.now();
    }

    /** Factory for a DECISION table. */
    public static Table decision(String name, List<TableColumn> columns) {
        return new Table(null, name, TableKind.DECISION, columns, List.of(), Map.of(), null);
    }

    /** Factory for a TRACE table. */
    public static Table trace(String name, List<TableColumn> columns, List<TableRow> rows) {
        return new Table(null, name, TableKind.TRACE, columns, rows, Map.of(), null);
    }

    /** Factory for a DATA table. */
    public static Table data(String name, List<TableColumn> columns, List<TableRow> rows) {
        return new Table(null, name, TableKind.DATA, columns, rows, Map.of(), null);
    }

    /** Add rows to produce a new Table (records are immutable). */
    public Table withRows(List<TableRow> newRows) {
        List<TableRow> merged = new ArrayList<>(rows);
        merged.addAll(newRows);
        return new Table(id, name, kind, columns, merged, metadata, createdAt);
    }

    /** Return columns matching a role. */
    public List<TableColumn> columnsOfRole(ColumnRole role) {
        return columns.stream().filter(c -> c.role() == role).collect(Collectors.toList());
    }

    /** Hit policy (FIRST/ALL/PRIORITY), default FIRST. */
    public String hitPolicy() {
        return metadata.getOrDefault("hitPolicy", "FIRST");
    }

    // ─── JSON ─────────────────────────────────────────────────────────────────

    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"id\":\"").append(escape(id)).append("\",");
        sb.append("\"name\":\"").append(escape(name)).append("\",");
        sb.append("\"kind\":\"").append(kind.name()).append("\",");
        sb.append("\"createdAt\":\"").append(createdAt.toString()).append("\",");
        // columns
        sb.append("\"columns\":[");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sb.append(",");
            TableColumn c = columns.get(i);
            sb.append("{\"name\":\"").append(escape(c.name())).append("\",");
            sb.append("\"role\":\"").append(c.role().name()).append("\",");
            sb.append("\"type\":\"").append(c.type().name()).append("\",");
            sb.append("\"description\":\"").append(escape(c.description())).append("\"}");
        }
        sb.append("],");
        // rows
        sb.append("\"rows\":[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) sb.append(",");
            TableRow r = rows.get(i);
            sb.append("{\"rowId\":\"").append(escape(r.rowId())).append("\",\"cells\":{");
            boolean first = true;
            for (Map.Entry<String, Object> e : r.cells().entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(escape(e.getKey())).append("\":\"").append(escape(String.valueOf(e.getValue()))).append("\"");
            }
            sb.append("}}");
        }
        sb.append("],");
        // metadata
        sb.append("\"metadata\":{");
        boolean first = true;
        for (Map.Entry<String, String> e : metadata.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escape(e.getKey())).append("\":\"").append(escape(e.getValue())).append("\"");
        }
        sb.append("}}");
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
