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

import java.util.Map;

/**
 * A single item within an approval batch (e.g., one auto-correction to accept or reject).
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ApprovalItem {

    private String id;
    private String description;
    /** Triage pattern ID that produced this item. */
    private String patternId;
    /** State of the item before the proposed change. */
    private Map<String, Object> before;
    /** State of the item after the proposed change. */
    private Map<String, Object> after;
    /** Confidence score (0–1) that the proposed change is correct. */
    private double confidence;
    private ApprovalItemStatus status;
    private String rejectionReason;
}
