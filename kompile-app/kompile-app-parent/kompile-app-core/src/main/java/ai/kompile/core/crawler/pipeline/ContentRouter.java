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
 * Routes a discovered crawl item to the appropriate ingest pipeline.
 *
 * <p>Implementations evaluate the item's content type, URL, file extension,
 * source type, and size against configured rules to select the correct
 * {@link IngestPipelineDefinition}.</p>
 */
public interface ContentRouter {

    /**
     * Determines which pipeline should process the given crawl item.
     *
     * @param item The discovered crawl item
     * @return The pipeline definition to use, never null (falls back to default)
     */
    IngestPipelineDefinition route(CrawlItem item);

    /**
     * Returns the default pipeline used when no rules match.
     */
    IngestPipelineDefinition getDefaultPipeline();
}
