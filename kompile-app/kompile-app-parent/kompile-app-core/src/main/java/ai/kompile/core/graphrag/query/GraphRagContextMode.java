/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.core.graphrag.query;

/** Selects the factual context contract presented to the answer model. */
public enum GraphRagContextMode {
    /** Existing prose context with conversation-aware reference resolution. */
    LEGACY_TEXT,
    /** Stateless, bounded JSON graph with source and canonical reasoning-trace identifiers. */
    COMPACT_GRAPH
}
