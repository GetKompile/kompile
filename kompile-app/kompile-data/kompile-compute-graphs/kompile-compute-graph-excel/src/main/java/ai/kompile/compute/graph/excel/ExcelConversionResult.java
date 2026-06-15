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

package ai.kompile.compute.graph.excel;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Result of converting an Excel spreadsheet's formulas to executable code.
 */
@Data
@Builder
public class ExcelConversionResult {
    /** The generated code (JavaScript or Python). */
    private String code;
    /** Target language: "javascript" or "python". */
    private String language;
    /** Name of the source workbook. */
    private String workbookName;
    /** Sanitized cell references that serve as inputs. */
    private List<String> inputCells;
    /** Sanitized cell references that are computed outputs. */
    private List<String> outputCells;
    /** Number of formula cells converted. */
    private int formulaCount;
    /** Number of dependency edges in the formula graph. */
    private int dependencyCount;
}
