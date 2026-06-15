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

import ai.kompile.core.crawler.CrawlChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Registry of all available {@link CrawlChannel} implementations.
 * Automatically collects all CrawlChannel beans registered in the Spring context.
 *
 * <p>Custom channels are discovered via Spring component scanning. Any
 * {@code @Component} implementing {@link CrawlChannel} is automatically
 * registered and becomes available for crawl requests with
 * {@code sourceType=CUSTOM}.</p>
 */
@Component
public class CrawlChannelRegistry {

    private static final Logger log = LoggerFactory.getLogger(CrawlChannelRegistry.class);

    private final Map<String, CrawlChannel> channelsByType;
    private final List<CrawlChannel> allChannels;

    public CrawlChannelRegistry(List<CrawlChannel> channels) {
        this.allChannels = channels != null ? channels : List.of();
        Map<String, CrawlChannel> map = new LinkedHashMap<>();
        for (CrawlChannel ch : this.allChannels) {
            String type = ch.getChannelType();
            if (type == null || type.isBlank()) {
                log.warn("Skipping CrawlChannel with null/blank channelType: {}", ch.getClass().getName());
                continue;
            }
            if (map.containsKey(type)) {
                log.warn("Duplicate CrawlChannel type '{}': {} vs {}",
                        type, map.get(type).getClass().getName(), ch.getClass().getName());
            }
            map.put(type, ch);
        }
        this.channelsByType = Collections.unmodifiableMap(map);
        if (!channelsByType.isEmpty()) {
            log.info("Registered {} custom crawl channel(s): {}", channelsByType.size(), channelsByType.keySet());
        }
    }

    /**
     * Get a channel by its unique type identifier.
     */
    public Optional<CrawlChannel> getChannel(String channelType) {
        return Optional.ofNullable(channelsByType.get(channelType));
    }

    /**
     * Get all registered channels.
     */
    public List<CrawlChannel> getAll() {
        return Collections.unmodifiableList(allChannels);
    }

    /**
     * Get all registered channel type identifiers.
     */
    public Set<String> getChannelTypes() {
        return channelsByType.keySet();
    }

    /**
     * Check if a channel type is registered.
     */
    public boolean hasChannel(String channelType) {
        return channelsByType.containsKey(channelType);
    }
}
