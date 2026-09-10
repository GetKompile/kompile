/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.gateway.core.gateway.channel;

import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class ChannelManager {

    private final Map<String, ChannelAdapter> adapters = new ConcurrentHashMap<>();

    public void registerAdapter(ChannelAdapter adapter) {
        registerAdapter(adapter.getChannelName(), adapter);
    }

    /** Register one runtime by its persistent connection name. */
    public synchronized void registerAdapter(String connectionName, ChannelAdapter adapter) {
        Objects.requireNonNull(connectionName, "connectionName");
        Objects.requireNonNull(adapter, "adapter");
        ChannelAdapter previous = adapters.get(connectionName);
        if (previous != null && previous != adapter) {
            try {
                previous.stop();
            } catch (RuntimeException e) {
                log.warn("Failed to stop previous adapter for {}: {}", connectionName, e.getMessage());
            }
        }
        adapters.put(connectionName, adapter);
        log.info("Registered {} channel adapter for connection {}",
                adapter.getChannelName(), connectionName);
    }

    public synchronized void unregisterAdapter(String channelName) {
        ChannelAdapter adapter = adapters.remove(channelName);
        if (adapter != null) {
            try {
                adapter.stop();
            } catch (RuntimeException e) {
                log.warn("Failed to stop adapter for {}: {}", channelName, e.getMessage());
            }
            log.info("Unregistered channel adapter: {}", channelName);
        }
    }

    public void startAll() {
        for (ChannelAdapter adapter : adapters.values()) {
            try {
                adapter.start();
            } catch (Exception e) {
                log.error("Failed to start adapter: {}", adapter.getChannelName(), e);
            }
        }
    }

    public void stopAll() {
        for (ChannelAdapter adapter : adapters.values()) {
            try {
                adapter.stop();
            } catch (Exception e) {
                log.error("Failed to stop adapter: {}", adapter.getChannelName(), e);
            }
        }
    }

    public void startChannel(String channelName) {
        ChannelAdapter adapter = adapters.get(channelName);
        if (adapter != null) {
            adapter.start();
        }
    }

    public void stopChannel(String channelName) {
        ChannelAdapter adapter = adapters.get(channelName);
        if (adapter != null) {
            adapter.stop();
        }
    }

    public Optional<ChannelAdapter> getAdapter(String channelName) {
        ChannelAdapter exact = adapters.get(channelName);
        if (exact != null) {
            return Optional.of(exact);
        }
        // Provider ids remain convenient when exactly one named connection uses that provider.
        List<ChannelAdapter> providerMatches = adapters.values().stream()
                .filter(adapter -> adapter.getChannelName().equals(channelName))
                .toList();
        return providerMatches.size() == 1 ? Optional.of(providerMatches.get(0)) : Optional.empty();
    }

    /** Exact named lookup; unlike {@link #getAdapter(String)}, never falls back to provider id. */
    public Optional<ChannelAdapter> getConnectionAdapter(String connectionName) {
        return Optional.ofNullable(adapters.get(connectionName));
    }

    public List<ChannelAdapter> getAdaptersByProvider(String providerId) {
        return adapters.values().stream()
                .filter(adapter -> adapter.getChannelName().equals(providerId))
                .toList();
    }

    /** Named provider runtimes for ingress routers that must preserve connection ownership. */
    public Map<String, ChannelAdapter> getConnectionAdaptersByProvider(String providerId) {
        Map<String, ChannelAdapter> matches = new LinkedHashMap<>();
        adapters.entrySet().stream()
                .filter(entry -> entry.getValue().getChannelName().equals(providerId))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> matches.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(matches);
    }

    public List<ChannelAdapter> getAllAdapters() {
        return new ArrayList<>(adapters.values());
    }

    public List<String> getChannelNames() {
        return new ArrayList<>(adapters.keySet());
    }

    public List<ChannelStatus> getStatus() {
        return adapters.entrySet().stream()
                .map(entry -> toStatus(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(ChannelStatus::channelName))
                .toList();
    }

    private ChannelStatus toStatus(String connectionName, ChannelAdapter adapter) {
        return new ChannelStatus(
                connectionName,
                adapter.isRunning(),
                adapter.getAdapterConfig()
        );
    }

    public record ChannelStatus(
            String channelName,
            boolean running,
            ChannelAdapter.AdapterConfig config
    ) {}
}
