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

package ai.kompile.crawler.sql;

import ai.kompile.core.crawler.CrawlConfig;
import ai.kompile.core.crawler.CrawlEventListener;
import ai.kompile.core.crawler.CrawlState;
import ai.kompile.crawler.AbstractCrawlJob;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Crawl job state for the SQL database crawler.
 * Tracks processed rows and tables for incremental crawls.
 */
public class SqlCrawlJob extends AbstractCrawlJob {

    /** Table+rowId keys already processed */
    final Set<String> visitedRowKeys = ConcurrentHashMap.newKeySet();

    /** Table name -> max observed primary key or row count for incremental tracking */
    final Map<String, Long> tableHighWaterMarks = new ConcurrentHashMap<>();

    SqlCrawlJob(String jobId, CrawlConfig config, CrawlEventListener listener) {
        super(jobId, config, listener);

        // Initialize from previous state for incremental crawling
        CrawlState prev = config.getPreviousState();
        if (prev != null && prev.getVisitedUrls() != null) {
            visitedRowKeys.addAll(prev.getVisitedUrls());
        }
        if (prev != null && prev.getLastModifiedTimes() != null) {
            tableHighWaterMarks.putAll(prev.getLastModifiedTimes());
        }
    }

    @Override
    public CrawlState checkpoint() {
        return CrawlState.builder()
                .timestamp(Instant.now())
                .visitedUrls(Collections.unmodifiableSet(new HashSet<>(visitedRowKeys)))
                .lastModifiedTimes(Collections.unmodifiableMap(new HashMap<>(tableHighWaterMarks)))
                .build();
    }
}
