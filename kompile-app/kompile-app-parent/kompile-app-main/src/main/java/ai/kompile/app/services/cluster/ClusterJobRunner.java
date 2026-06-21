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

package ai.kompile.app.services.cluster;

import java.util.Map;

/**
 * Worker-side extension point that makes a job type runnable on a remote peer. A worker can run only the
 * job types for which a {@code ClusterJobRunner} bean is present — those become its advertised
 * {@code supportedJobTypes}, so the orchestrator never routes work a peer can't actually execute. The
 * runner reconstructs the work from the serializable {@link ClusterJobSubmission} (e.g. for {@code crawl}
 * it deserializes {@code metadata.crawlRequestJson}) and runs it via the worker's local services.
 */
public interface ClusterJobRunner {

    /** The job type this runner handles (matches {@code ScheduledJob.jobType} / DeviceRouting SERVICE_*). */
    String jobType();

    /** Run the job to completion on this worker. Throwing is treated as a failed job. */
    Result run(ClusterJobSubmission job) throws Exception;

    /** Outcome reported back to the orchestrator. */
    record Result(boolean success, String message, Map<String, Object> resultData) {
        public static Result ok(String message, Map<String, Object> resultData) {
            return new Result(true, message, resultData == null ? Map.of() : resultData);
        }

        public static Result fail(String message) {
            return new Result(false, message, Map.of());
        }
    }
}
