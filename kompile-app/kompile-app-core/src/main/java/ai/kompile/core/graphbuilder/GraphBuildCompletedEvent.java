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

import org.springframework.context.ApplicationEvent;

import java.util.Map;

/**
 * Published when a graph build job completes successfully.
 * Listeners (e.g., process discovery, data enrichment) can react to trigger downstream analysis.
 */
public class GraphBuildCompletedEvent extends ApplicationEvent {

    private final String jobId;
    private final int entitiesExtracted;
    private final int edgesCreated;
    private final Long factSheetId;
    private final Map<String, Long> entityTypeCounts;

    public GraphBuildCompletedEvent(Object source, String jobId, int entitiesExtracted, int edgesCreated) {
        this(source, jobId, entitiesExtracted, edgesCreated, null, null);
    }

    public GraphBuildCompletedEvent(Object source, String jobId, int entitiesExtracted, int edgesCreated,
                                    Long factSheetId, Map<String, Long> entityTypeCounts) {
        super(source);
        this.jobId = jobId;
        this.entitiesExtracted = entitiesExtracted;
        this.edgesCreated = edgesCreated;
        this.factSheetId = factSheetId;
        this.entityTypeCounts = entityTypeCounts;
    }

    public String getJobId() {
        return jobId;
    }

    public int getEntitiesExtracted() {
        return entitiesExtracted;
    }

    public int getEdgesCreated() {
        return edgesCreated;
    }

    public Long getFactSheetId() {
        return factSheetId;
    }

    public Map<String, Long> getEntityTypeCounts() {
        return entityTypeCounts;
    }

    @Override
    public String toString() {
        return "GraphBuildCompletedEvent{jobId='" + jobId + "', entities=" + entitiesExtracted +
                ", edges=" + edgesCreated + ", factSheetId=" + factSheetId + '}';
    }
}
