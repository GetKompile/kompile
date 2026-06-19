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

package ai.kompile.core.graphrag.maintenance.model;

public record Contradiction(
    String entityIdA,
    String entityIdB,
    String existingFact,
    String newFact,
    String sourceDocExisting,
    String sourceDocNew,
    ContradictionType type,
    Resolution resolution
) {
    public enum ContradictionType {
        CONFLICTING_RELATIONSHIP,
        CONFLICTING_TYPE,
        CONFLICTING_PROPERTY,
        TEMPORAL_SUPERSESSION
    }

    public enum Resolution {
        AUTO_NEWER_WINS,
        AUTO_HIGHER_CONFIDENCE,
        NEEDS_REVIEW
    }
}
