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

package ai.kompile.process.hitl;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Marks a specific range, tab, or cell as excluded from processing.
 * Derived from pre-read notes such as "ignore Summary tab" or "GRAND TOTAL is stale".
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CellExclusion {

    private String workbookId;
    private String sheet;
    /** Cell or range reference, e.g., "A60:F60". Null indicates the entire sheet is excluded. */
    private String range;
    private String reason;
    /** Person or system that asserted this exclusion. */
    private String assertedBy;
    private Instant assertedAt;
}
