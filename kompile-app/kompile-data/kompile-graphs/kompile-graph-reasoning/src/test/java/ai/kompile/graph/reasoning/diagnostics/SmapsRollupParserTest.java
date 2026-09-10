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
package ai.kompile.graph.reasoning.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmapsRollupParserTest {

    @Test
    void parsesRollupFieldsAndIgnoresMappingHeaderAndOtherFields() {
        Fullgraphproc016MemoryDiagnosticTest.SmapsRollup result =
                Fullgraphproc016MemoryDiagnosticTest.parseSmapsRollup(List.of(
                        "7f6c0000-7f6c1000 r--p 00000000 00:00 0                          [rollup]",
                        "Rss:                123456 kB",
                        "Pss:                  654321 kB",
                        "Private_Dirty:         23456 kB",
                        "Anonymous:             34567 kB",
                        "VmFlags: rd ex mr mw me dw"
                ));

        assertEquals(123456L, result.rssKb());
        assertEquals(23456L, result.privateDirtyKb());
        assertEquals(34567L, result.anonymousKb());
        assertNull(result.unavailableReason());
    }

    @Test
    void reportsUnavailableFieldsInsteadOfSilentlyReturningCompleteMetrics() {
        Fullgraphproc016MemoryDiagnosticTest.SmapsRollup result =
                Fullgraphproc016MemoryDiagnosticTest.parseSmapsRollup(List.of(
                        "Rss: not-a-number kB",
                        "Private_Dirty: 23456 kB"
                ));

        assertEquals(-1L, result.rssKb());
        assertEquals(23456L, result.privateDirtyKb());
        assertEquals(-1L, result.anonymousKb());
        assertTrue(result.unavailableReason().contains("Rss"));
        assertTrue(result.unavailableReason().contains("Anonymous"));
    }
}
