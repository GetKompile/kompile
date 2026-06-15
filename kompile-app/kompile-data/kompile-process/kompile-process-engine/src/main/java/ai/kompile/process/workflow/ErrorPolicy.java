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
 * Defines how the engine responds to an unexpected error in a {@link ProcessStep}.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ErrorPolicy {

    private ErrorAction onError;
    /** Maximum number of automatic retries before the policy falls through. */
    private int maxRetries;
    /** Initial backoff in milliseconds (may be multiplied exponentially). */
    private int retryBackoffMs;
    /** Target step ID to route to when action is {@link ErrorAction#ROUTE_TO_BRANCH}. */
    private String errorBranchStepId;
    /** Role or person to escalate to when action is {@link ErrorAction#ESCALATE}. */
    private String escalateTo;
}
