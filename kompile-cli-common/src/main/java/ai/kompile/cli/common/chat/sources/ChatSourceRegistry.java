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

package ai.kompile.cli.common.chat.sources;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

public final class ChatSourceRegistry {

    private static final Logger log = LoggerFactory.getLogger(ChatSourceRegistry.class);

    private static volatile ChatSourceRegistry INSTANCE;

    private final Map<String, ChatSourceAdapter> adapters;

    private ChatSourceRegistry(Map<String, ChatSourceAdapter> adapters) {
        this.adapters = Collections.unmodifiableMap(adapters);
    }

    public static ChatSourceRegistry getInstance() {
        ChatSourceRegistry local = INSTANCE;
        if (local != null) return local;
        synchronized (ChatSourceRegistry.class) {
            if (INSTANCE == null) {
                INSTANCE = loadFromServiceLoader();
            }
            return INSTANCE;
        }
    }

    /**
     * Replace the loaded registry — primarily for tests or programmatic wiring
     * where the service loader isn't appropriate.
     */
    public static synchronized void setInstance(ChatSourceRegistry registry) {
        INSTANCE = registry;
    }

    public static ChatSourceRegistry of(Iterable<ChatSourceAdapter> adapters) {
        Map<String, ChatSourceAdapter> map = new LinkedHashMap<>();
        for (ChatSourceAdapter adapter : adapters) {
            map.put(adapter.id(), adapter);
        }
        return new ChatSourceRegistry(map);
    }

    private static ChatSourceRegistry loadFromServiceLoader() {
        Map<String, ChatSourceAdapter> map = new LinkedHashMap<>();
        try {
            ServiceLoader<ChatSourceAdapter> loader = ServiceLoader.load(ChatSourceAdapter.class);
            for (ChatSourceAdapter adapter : loader) {
                if (map.put(adapter.id(), adapter) != null) {
                    log.warn("Duplicate ChatSourceAdapter for id '{}' — last one wins", adapter.id());
                }
            }
        } catch (Throwable t) {
            log.warn("Failed to load ChatSourceAdapter instances via ServiceLoader: {}", t.getMessage());
        }
        log.info("Loaded {} chat source adapter(s): {}", map.size(), map.keySet());
        return new ChatSourceRegistry(map);
    }

    public Optional<ChatSourceAdapter> find(String id) {
        if (id == null) return Optional.empty();
        return Optional.ofNullable(adapters.get(id));
    }

    public ChatSourceAdapter require(String id) {
        ChatSourceAdapter adapter = adapters.get(id);
        if (adapter == null) {
            throw new IllegalArgumentException("Unknown chat source: " + id + " (known: " + adapters.keySet() + ")");
        }
        return adapter;
    }

    public List<ChatSourceAdapter> all() {
        return new ArrayList<>(adapters.values());
    }

    public List<String> ids() {
        return new ArrayList<>(adapters.keySet());
    }

    public int size() {
        return adapters.size();
    }
}
