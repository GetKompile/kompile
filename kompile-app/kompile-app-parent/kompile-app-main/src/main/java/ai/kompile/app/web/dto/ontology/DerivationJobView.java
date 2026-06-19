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
package ai.kompile.app.web.dto.ontology;

import ai.kompile.process.ontology.OntologySchema;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * View of an asynchronous ontology-derivation job. The client starts a job, watches its live logs +
 * transcript via the {@code taskId} (on {@code /topic/ingest/{taskId}/logs} and the job-log REST API),
 * and polls this view until {@link #status} is {@code COMPLETED} (draft populated) or {@code FAILED}.
 *
 * @param jobId  opaque job id
 * @param taskId log stream id ({@code ontology-derive-{jobId}}) for the live log/transcript viewer
 * @param status {@code RUNNING} | {@code COMPLETED} | {@code FAILED}
 * @param draft  the derived (unsaved) ontology, present once {@code COMPLETED}
 * @param error  failure message, present when {@code FAILED}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DerivationJobView(
        String jobId,
        String taskId,
        String status,
        OntologySchema draft,
        String error
) {
}
