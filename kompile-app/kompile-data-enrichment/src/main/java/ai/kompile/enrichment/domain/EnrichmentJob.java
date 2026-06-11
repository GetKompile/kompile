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
package ai.kompile.enrichment.domain;

import ai.kompile.enrichment.api.EnrichmentPhase;
import lombok.Data;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

@Data
public class EnrichmentJob {

    public enum Status {
        PENDING, RUNNING, COMPLETED, FAILED, CANCELLED
    }

    private final String jobId;
    private final Long factSheetId;
    private final AtomicReference<Status> status = new AtomicReference<>(Status.PENDING);
    private volatile EnrichmentPhase currentPhase;
    private volatile int progressPercent;
    private volatile EnrichmentResult result;
    private volatile String errorMessage;
    private final Instant createdAt = Instant.now();
    private volatile Instant completedAt;

    public EnrichmentJob(String jobId, Long factSheetId) {
        this.jobId = jobId;
        this.factSheetId = factSheetId;
    }

    public Status getStatusValue() {
        return status.get();
    }

    public void setStatusValue(Status newStatus) {
        status.set(newStatus);
        if (newStatus == Status.COMPLETED || newStatus == Status.FAILED || newStatus == Status.CANCELLED) {
            completedAt = Instant.now();
        }
    }
}
