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

package ai.kompile.staging.web.dto;

import ai.kompile.staging.update.UpdateInfo;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response DTO for archive update checks.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArchiveUpdateResponse {

    /**
     * Whether any updates are available.
     */
    private boolean updatesAvailable;

    /**
     * Total number of installed archives.
     */
    private int totalInstalled;

    /**
     * Number of updates available.
     */
    private int updatesCount;

    /**
     * Number of major updates (possible breaking changes).
     */
    private int majorUpdates;

    /**
     * List of update information for each archive.
     */
    private List<UpdateInfo> updates;

    /**
     * Create from update summary.
     */
    public static ArchiveUpdateResponse fromSummary(
            int totalInstalled, int updatesAvailable, int majorUpdates, List<UpdateInfo> updates) {
        return ArchiveUpdateResponse.builder()
                .updatesAvailable(updatesAvailable > 0)
                .totalInstalled(totalInstalled)
                .updatesCount(updatesAvailable)
                .majorUpdates(majorUpdates)
                .updates(updates)
                .build();
    }
}
