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

/**
 * Cardinality of a directed relationship from a source entity type to a target entity type.
 */
public enum Cardinality {
    /** Each source has at most one target, and each target has at most one source. */
    ONE_TO_ONE,
    /** Each source may have many targets; each target has at most one source. */
    ONE_TO_MANY,
    /** Each source has at most one target; each target may have many sources. */
    MANY_TO_ONE,
    /** Each source may have many targets, and each target may have many sources. */
    MANY_TO_MANY
}
