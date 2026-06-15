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
package ai.kompile.core.graphbuilder;

/**
 * Information about a knowledge graph builder for API responses.
 *
 * @param id unique identifier for the builder
 * @param displayName human-readable name
 * @param description description of the builder's capabilities
 * @param type the builder type
 * @param supportsExtractionLog whether the builder provides full LLM logs
 */
public record GraphBuilderInfo(
        String id,
        String displayName,
        String description,
        GraphBuilderType type,
        boolean supportsExtractionLog
) {
}
