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

/**
 * A manual field-level correction applied by an approver during an
 * {@link ApprovalAction#APPROVE_WITH_EDITS} response.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FieldEdit {

    /** Dot-path to the field being edited, e.g., "lineItems[3].amount". */
    private String fieldPath;
    private Object oldValue;
    private Object newValue;
    private String reason;
}
