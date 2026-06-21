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

import java.util.Objects;

public record TableColumn(String name, ColumnRole role, ColumnType type, String description) {
    public TableColumn {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(type, "type");
        if (description == null) description = "";
    }
}
