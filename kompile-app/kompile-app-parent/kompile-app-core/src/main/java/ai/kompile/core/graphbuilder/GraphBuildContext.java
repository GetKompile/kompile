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
 * Context for a graph building operation.
 *
 * @param jobId the extraction job ID
 * @param factSheetId the fact sheet ID this graph belongs to
 * @param storageType the target storage type ("jpa" or "neo4j")
 */
public record GraphBuildContext(
        String jobId,
        Long factSheetId,
        String storageType
) {
    /**
     * Create a context with default JPA storage.
     */
    public static GraphBuildContext forFactSheet(Long factSheetId) {
        return new GraphBuildContext(null, factSheetId, "jpa");
    }

    /**
     * Create a context with a specific job ID.
     */
    public GraphBuildContext withJobId(String jobId) {
        return new GraphBuildContext(jobId, factSheetId, storageType);
    }
}
