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

package ai.kompile.core.crawler.pipeline;

import ai.kompile.core.crawler.CrawlItem;

/**
 * A crawl item paired with its routing decision — the pipeline that should process it.
 * Emitted by the crawler service after routing, consumed by the ingest orchestrator.
 */
public record RoutedCrawlItem(
    /** The discovered item */
    CrawlItem item,
    /** The pipeline selected by the router */
    IngestPipelineDefinition pipeline,
    /** The rule that matched (null if default pipeline was used) */
    ContentRouteRule matchedRule
) {}
