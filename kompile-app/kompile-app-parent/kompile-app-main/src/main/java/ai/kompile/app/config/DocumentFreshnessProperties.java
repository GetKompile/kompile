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

package ai.kompile.app.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "kompile.freshness")
public class DocumentFreshnessProperties {

    private boolean enabled = false;

    /**
     * Half-life in days for freshness score exponential decay.
     */
    private int freshnessHalfLifeDays = 30;

    /**
     * TTL in days per file extension. Documents older than TTL are marked stale.
     */
    private Map<String, Integer> ttlByExtension = new HashMap<>(Map.of(
            "html", 7,
            "json", 14,
            "csv", 30,
            "pdf", 90,
            "docx", 90,
            "txt", 60
    ));

    /**
     * Default TTL in days for extensions not in the map.
     */
    private int defaultTtlDays = 90;

    /**
     * Weight of freshness score in retrieval scoring (0.0-1.0).
     * adjustedScore = (1 - freshnessWeight) * rawScore + freshnessWeight * freshnessScore
     */
    private double freshnessWeight = 0.1;
}
