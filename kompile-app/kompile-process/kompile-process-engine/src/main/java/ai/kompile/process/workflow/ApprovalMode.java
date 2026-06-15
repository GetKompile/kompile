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

/**
 * Specifies how many approvers must act for an approval to be considered complete.
 */
public enum ApprovalMode {
    /** One approver from the pool is sufficient. */
    SINGLE,
    /** Two independent approvers must agree (four-eyes principle). */
    DUAL,
    /** A majority quorum of the approver pool must agree. */
    QUORUM
}
