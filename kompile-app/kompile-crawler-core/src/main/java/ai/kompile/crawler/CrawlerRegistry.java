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

package ai.kompile.crawler;

import ai.kompile.core.crawler.Crawler;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Registry of all available {@link Crawler} implementations.
 * Automatically collects all Crawler beans registered in the Spring context.
 */
@Component
public class CrawlerRegistry {

    private final Map<String, Crawler> crawlersById;
    private final List<Crawler> allCrawlers;

    public CrawlerRegistry(List<Crawler> crawlers) {
        this.allCrawlers = crawlers != null ? crawlers : List.of();
        Map<String, Crawler> map = new LinkedHashMap<>();
        for (Crawler c : this.allCrawlers) {
            map.put(c.getId(), c);
        }
        this.crawlersById = Collections.unmodifiableMap(map);
    }

    /** Get a crawler by its unique ID */
    public Optional<Crawler> getCrawler(String id) {
        return Optional.ofNullable(crawlersById.get(id));
    }

    /** Get all registered crawlers */
    public List<Crawler> getAll() {
        return Collections.unmodifiableList(allCrawlers);
    }

    /** Find crawlers that support a given source type */
    public List<Crawler> findBySourceType(DocumentSourceDescriptor.SourceType sourceType) {
        List<Crawler> matching = new ArrayList<>();
        for (Crawler c : allCrawlers) {
            if (c.supports(sourceType)) {
                matching.add(c);
            }
        }
        return matching;
    }

    /** Get all registered crawler IDs */
    public Set<String> getCrawlerIds() {
        return crawlersById.keySet();
    }
}
