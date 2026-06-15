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

package ai.kompile.core.graphrag.model.schema;

public enum SchemaEnforcementMode {
    /**
     * Do not enforce the schema. The graph will be created as extracted by the LLM,
     * with minimal cleaning.
     */
    NONE,
    /**
     * Leniently enforce the schema. Unknown types are logged as warnings
     * but still allowed in the graph.
     */
    LENIENT,
    /**
     * Strictly enforce the schema. Any entities, relationships, or properties
     * not conforming to the schema will be removed or modified before being
     * written to the database.
     */
    STRICT
}