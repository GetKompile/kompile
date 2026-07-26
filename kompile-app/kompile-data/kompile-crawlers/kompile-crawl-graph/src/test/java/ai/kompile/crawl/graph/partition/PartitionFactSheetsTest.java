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

package ai.kompile.crawl.graph.partition;

import ai.kompile.core.graphrag.partition.EntityPartition;
import ai.kompile.core.graphrag.partition.PartitionKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("Which fact sheet a partition is scoped to")
class PartitionFactSheetsTest {

    private static EntityPartition partitionWithSnapshot(String snapshotId) {
        return EntityPartition.open(PartitionKey.forEntity("acme", "v1", snapshotId));
    }

    @Test
    void anExplicitPinNamesTheFactSheet() {
        EntityPartition partition = partitionWithSnapshot(null)
                .withPin(PartitionFactSheets.FACT_SHEET_PIN, "42");
        assertEquals(Long.valueOf(42L), PartitionFactSheets.resolve(partition));
    }

    @Test
    void theKeysOwnSnapshotIsUsedWhenNothingWasPinned() {
        // The key already declares which snapshot the partition was discovered against, and for a
        // kompile graph the snapshot is the fact sheet. Ignoring it would let the two disagree.
        assertEquals(Long.valueOf(7L), PartitionFactSheets.resolve(partitionWithSnapshot("7")));
    }

    @Test
    void thePinWinsWhenBothAreGiven() {
        EntityPartition partition = partitionWithSnapshot("7")
                .withPin(PartitionFactSheets.FACT_SHEET_PIN, "42");
        assertEquals(Long.valueOf(42L), PartitionFactSheets.resolve(partition));
    }

    @Test
    void aSnapshotHandleFromSomeOtherStoreSimplyDoesNotResolve() {
        assertNull(PartitionFactSheets.resolve(partitionWithSnapshot("2026-07-26T10:00:00Z")));
    }

    @Test
    void aPartitionThatNamesNoScopeResolvesToNothingRatherThanADefault() {
        // Guessing a fact sheet would walk a graph the partition never claimed to be about, and
        // the evidence would carry a coverage claim nobody made.
        assertNull(PartitionFactSheets.resolve(partitionWithSnapshot(null)));
        assertNull(PartitionFactSheets.resolve(null));
    }

    @Test
    void aBlankPinIsNotAScope() {
        EntityPartition partition = partitionWithSnapshot(null)
                .withPin(PartitionFactSheets.FACT_SHEET_PIN, "   ");
        assertNull(PartitionFactSheets.resolve(partition));
    }

    @Test
    void surroundingWhitespaceInAPinIsTolerated() {
        EntityPartition partition = partitionWithSnapshot(null)
                .withPin(PartitionFactSheets.FACT_SHEET_PIN, " 42 ");
        assertEquals(Long.valueOf(42L), PartitionFactSheets.resolve(partition));
    }

    @Test
    void theFixedResolverIgnoresWhateverThePartitionSays() {
        EntityPartition partition = partitionWithSnapshot("7")
                .withPin(PartitionFactSheets.FACT_SHEET_PIN, "42");
        assertEquals(Long.valueOf(9L), PartitionFactSheets.fixed(9L).apply(partition));
    }

    @Test
    void theDefaultResolverIsTheSameReadingAsResolve() {
        EntityPartition partition = partitionWithSnapshot("7");
        assertEquals(PartitionFactSheets.resolve(partition),
                PartitionFactSheets.fromPinOrSnapshot().apply(partition));
    }
}
