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

package ai.kompile.app.sync.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Summary of a single sync cycle: how many notes were pushed, pulled,
 * conflicted, or skipped.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncRunResult {
    private Long connectionId;
    @Builder.Default private int pushed = 0;
    @Builder.Default private int pulled = 0;
    @Builder.Default private int conflicts = 0;
    @Builder.Default private int skipped = 0;
    @Builder.Default private int errors = 0;
    @Builder.Default private Instant timestamp = Instant.now();

    public static SyncRunResult skipped(Long connectionId) {
        return SyncRunResult.builder()
                .connectionId(connectionId)
                .skipped(1)
                .build();
    }
}
