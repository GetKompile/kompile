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

import java.util.Map;
import java.util.Objects;

public record TableRow(String rowId, Map<String, Object> cells) {
    public TableRow {
        Objects.requireNonNull(rowId, "rowId");
        cells = cells == null ? Map.of() : Map.copyOf(cells);
    }

    /** Get a typed cell value. */
    @SuppressWarnings("unchecked")
    public <T> T get(String column, Class<T> type) {
        Object val = cells.get(column);
        if (val == null) return null;
        return type.cast(val);
    }

    /** Check if a condition cell is blank (wildcard). */
    public boolean isBlank(String column) {
        Object val = cells.get(column);
        return val == null || val.toString().isBlank();
    }
}
