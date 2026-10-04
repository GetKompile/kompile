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

package ai.kompile.cli.insights;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A plain aligned table that reads the same in a terminal, a tool card and an agent's context:
 * the first column (and any named with {@link #left(int...)}) is left-aligned, the rest are
 * right-aligned, two spaces apart, with cells clamped to one line.
 */
public final class TextTable {

    private static final int DEFAULT_MAX_CELL = 40;

    private final String[] headers;
    private final List<String[]> rows = new ArrayList<>();
    private final Set<Integer> leftAligned = new HashSet<>(Set.of(0));
    private int maxCell = DEFAULT_MAX_CELL;

    public TextTable(String... headers) {
        this.headers = headers.clone();
    }

    public TextTable left(int... columns) {
        for (int column : columns) {
            leftAligned.add(column);
        }
        return this;
    }

    public TextTable maxCell(int maxCell) {
        this.maxCell = Math.max(4, maxCell);
        return this;
    }

    public TextTable row(Object... cells) {
        String[] row = new String[headers.length];
        for (int i = 0; i < row.length; i++) {
            row[i] = i < cells.length && cells[i] != null ? Format.clamp(String.valueOf(cells[i]), maxCell) : "";
        }
        rows.add(row);
        return this;
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    public int size() {
        return rows.size();
    }

    public String render() {
        int[] widths = new int[headers.length];
        for (int i = 0; i < headers.length; i++) {
            widths[i] = headers[i].length();
        }
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                widths[i] = Math.max(widths[i], row[i].length());
            }
        }
        StringBuilder out = new StringBuilder();
        appendRow(out, headers, widths);
        for (String[] row : rows) {
            appendRow(out, row, widths);
        }
        return out.toString();
    }

    private void appendRow(StringBuilder out, String[] cells, int[] widths) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                line.append("  ");
            }
            String pad = " ".repeat(widths[i] - cells[i].length());
            if (leftAligned.contains(i)) {
                line.append(cells[i]).append(pad);
            } else {
                line.append(pad).append(cells[i]);
            }
        }
        out.append(line.toString().stripTrailing()).append('\n');
    }
}
