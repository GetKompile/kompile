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

package ai.kompile.process.ontology;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * An immutable record of a single change to an ontology entity or field.
 * Constitutes the change history audit trail for an {@link EntityTypeDefinition}.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChangeRecord {

    private Instant timestamp;
    /** Who made the change, e.g., a person's ID or "system:run#142". */
    private String changedBy;
    /** Nature of the change: "added", "modified", "rule_updated", "confidence_adjusted". */
    private String changeType;
    private String description;
    private String previousValue;
    private String newValue;
}
