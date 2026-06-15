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

/**
 * The decision an approver takes on an {@link ApprovalRequest}.
 */
public enum ApprovalAction {
    /** Accept all items as proposed. */
    APPROVE,
    /** Accept all items but with manual field corrections applied. */
    APPROVE_WITH_EDITS,
    /** Reject the entire batch; workflow is halted or rerouted. */
    REJECT,
    /** Escalate to a higher authority without accepting or rejecting. */
    ESCALATE,
    /** Hand off to another person or role. */
    DELEGATE,
    /** Request additional information before deciding. */
    REQUEST_INFO
}
