/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.main.graph;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code kgraph}/{@code unified} format routes graph export/import to {@code /api/graph/unified}. */
class GraphUnifiedFormatRoutingTest {

    @Test
    void exportRecognizesTheNativeFormat() {
        assertTrue(GraphExportCommand.isUnifiedFormat("kgraph"));
        assertTrue(GraphExportCommand.isUnifiedFormat("unified"));
        assertTrue(GraphExportCommand.isUnifiedFormat("KGRAPH"));
        assertFalse(GraphExportCommand.isUnifiedFormat("json"));
        assertTrue(GraphExportCommand.SUPPORTED_FORMATS.contains("kgraph"));
    }

    @Test
    void importRecognizesTheNativeFormat() {
        assertTrue(GraphImportCommand.isUnifiedFormat("kgraph"));
        assertTrue(GraphImportCommand.isUnifiedFormat("unified"));
        assertFalse(GraphImportCommand.isUnifiedFormat("csv"));
        assertTrue(GraphImportCommand.SUPPORTED_FORMATS.contains("kgraph"));
    }
}
