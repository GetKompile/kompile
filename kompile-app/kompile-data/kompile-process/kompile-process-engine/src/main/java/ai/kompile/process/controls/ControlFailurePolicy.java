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

package ai.kompile.process.controls;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Defines what happens when a {@link ControlDefinition} fails evaluation.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ControlFailurePolicy {

    private ControlFailureAction action;
    /** Role or person to notify or escalate to when the control fails. */
    private String escalateTo;
    /** When true, a manual override requires sign-off from two separate approvers. */
    private boolean requireDualApprovalToOverride;
    /** Human-readable instructions for remediating the failure. */
    private String remediationInstructions;
}
