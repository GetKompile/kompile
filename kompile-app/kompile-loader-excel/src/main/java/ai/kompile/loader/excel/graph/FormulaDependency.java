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

package ai.kompile.loader.excel.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a dependency edge between two cells in the formula graph.
 * Direction: source (the formula cell) DEPENDS_ON target (the referenced cell).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FormulaDependency {
    /**
     * The formula cell reference (e.g., "Sheet1!C1").
     */
    private String formulaCell;

    /**
     * The referenced cell reference (e.g., "Sheet1!A1").
     */
    private String referencedCell;

    /**
     * The type of dependency.
     */
    private DependencyType dependencyType;

    /**
     * Whether this is a cross-sheet reference.
     */
    private boolean crossSheet;

    /**
     * The formula that creates this dependency.
     */
    private String formula;

    /**
     * If the reference is to a range, this is the range string (e.g., "A1:A10").
     */
    private String rangeReference;

    public enum DependencyType {
        /** Direct cell reference: =A1 */
        CELL_REFERENCE,
        /** Range reference: =SUM(A1:A10) */
        RANGE_REFERENCE,
        /** Cross-sheet reference: =Sheet2!A1 */
        CROSS_SHEET_REFERENCE,
        /** Named range reference: =SUM(TotalSales) */
        NAMED_RANGE_REFERENCE
    }
}
