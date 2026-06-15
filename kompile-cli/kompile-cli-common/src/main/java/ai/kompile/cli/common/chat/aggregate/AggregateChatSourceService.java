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

package ai.kompile.cli.common.chat.aggregate;

import ai.kompile.cli.common.chat.sources.ChatSessionSummary;
import ai.kompile.cli.common.chat.sources.ChatSourceAdapter;
import ai.kompile.cli.common.chat.sources.ChatSourceRegistry;
import ai.kompile.cli.common.chat.sources.SourceInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read-only facade over {@link ChatSourceRegistry} that provides cross-source
 * operations: unified session listing, counts, and discovery aggregation.
 * Stateless; safe to share as a singleton bean.
 */
public class AggregateChatSourceService {

    private static final Logger log = LoggerFactory.getLogger(AggregateChatSourceService.class);

    private final ChatSourceRegistry registry;

    public AggregateChatSourceService(ChatSourceRegistry registry) {
        this.registry = registry;
    }

    public AggregateChatSourceService() {
        this(ChatSourceRegistry.getInstance());
    }

    public ChatSourceRegistry registry() {
        return registry;
    }

    public List<SourceInfo> discoverAll() {
        List<SourceInfo> out = new ArrayList<>();
        for (ChatSourceAdapter adapter : registry.all()) {
            try {
                out.add(adapter.discover());
            } catch (Exception e) {
                log.warn("Adapter {} discover() failed: {}", adapter.id(), e.getMessage());
                out.add(SourceInfo.unavailable(adapter.id(), adapter.displayName(), "", e.getMessage()));
            }
        }
        return out;
    }

    public List<ChatSessionSummary> listAll() {
        return listFiltered(null);
    }

    public List<ChatSessionSummary> listFiltered(Collection<String> sourceIds) {
        List<ChatSessionSummary> out = new ArrayList<>();
        for (ChatSourceAdapter adapter : registry.all()) {
            if (sourceIds != null && !sourceIds.isEmpty() && !sourceIds.contains(adapter.id())) continue;
            try {
                out.addAll(adapter.list());
            } catch (Exception e) {
                log.warn("Adapter {} list() failed: {}", adapter.id(), e.getMessage());
            }
        }
        out.sort((a, b) -> Long.compare(b.lastModifiedMillis(), a.lastModifiedMillis()));
        return out;
    }

    public Optional<ChatSourceAdapter> findAdapter(String sourceId) {
        return registry.find(sourceId);
    }

    public Map<String, Integer> countsBySource() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (ChatSourceAdapter adapter : registry.all()) {
            try {
                out.put(adapter.id(), adapter.list().size());
            } catch (IOException e) {
                log.warn("Adapter {} list() failed while counting: {}", adapter.id(), e.getMessage());
                out.put(adapter.id(), 0);
            }
        }
        return out;
    }

    public Map<String, SourceInfo> discoveryBySource() {
        Map<String, SourceInfo> out = new LinkedHashMap<>();
        for (SourceInfo info : discoverAll()) {
            out.put(info.source(), info);
        }
        return out;
    }

    public List<String> knownSourceIds() {
        return Collections.unmodifiableList(registry.ids());
    }
}
