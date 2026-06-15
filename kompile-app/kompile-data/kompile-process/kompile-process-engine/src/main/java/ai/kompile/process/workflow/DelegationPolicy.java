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

package ai.kompile.process.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Defines automatic delegation behaviour when the primary approver is unavailable.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DelegationPolicy {

    /** Seconds to wait for the primary approver before auto-delegating. */
    private int timeoutSeconds;
    /** Backup approver identifier to delegate to on timeout. */
    private String delegateTo;
    /** When true, automatically delegate if the primary approver has an OOO status. */
    private boolean allowOOOAutoDelegate;
    /** External calendar or system to check for out-of-office status. */
    private String oooCalendarSource;
}
