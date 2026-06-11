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
package ai.kompile.kclaw.gateway.channel;

import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class ChannelManager {

    private final Map<String, ChannelAdapter> adapters = new ConcurrentHashMap<>();

    public void registerAdapter(ChannelAdapter adapter) {
        adapters.put(adapter.getChannelName(), adapter);
        log.info("Registered channel adapter: {}", adapter.getChannelName());
    }

    public void unregisterAdapter(String channelName) {
        ChannelAdapter adapter = adapters.remove(channelName);
        if (adapter != null) {
            adapter.stop();
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
        return Optional.ofNullable(adapters.get(channelName));
    }

    public List<ChannelAdapter> getAllAdapters() {
        return new ArrayList<>(adapters.values());
    }

    public List<String> getChannelNames() {
        return new ArrayList<>(adapters.keySet());
    }

    public List<ChannelStatus> getStatus() {
        return adapters.values().stream()
                .map(this::toStatus)
                .toList();
    }

    private ChannelStatus toStatus(ChannelAdapter adapter) {
        return new ChannelStatus(
                adapter.getChannelName(),
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
