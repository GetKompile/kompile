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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Discovers all {@link ClusterJobRunner} beans on this node — the set of job types this worker can actually
 * run. The capability advertiser intersects this with the configured {@code clusterSupportedJobTypes} so a
 * worker never advertises work it has no runner for.
 */
@Service
public class ClusterJobRunnerRegistry {

    private static final Logger log = LoggerFactory.getLogger(ClusterJobRunnerRegistry.class);

    private final Map<String, ClusterJobRunner> runners = new LinkedHashMap<>();

    public ClusterJobRunnerRegistry(List<ClusterJobRunner> runnerBeans) {
        for (ClusterJobRunner r : runnerBeans) {
            runners.put(r.jobType(), r);
        }
        if (!runners.isEmpty()) {
            log.info("Cluster job runners registered for types: {}", runners.keySet());
        }
    }

    public Optional<ClusterJobRunner> forType(String jobType) {
        return Optional.ofNullable(runners.get(jobType));
    }

    /** Job types this worker can run (one per registered runner bean). */
    public Set<String> supportedJobTypes() {
        return runners.keySet();
    }
}
