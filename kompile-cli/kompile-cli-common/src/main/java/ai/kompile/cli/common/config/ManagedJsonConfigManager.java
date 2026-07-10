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

package ai.kompile.cli.common.config;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * The kompile-managed configuration pattern, ONCE: a JSON file under
 * {@link KompileHome#configDirectory()}, hot-reloaded on mtime change (at most once per 5s),
 * merged web-UI/REST updates written back preserving foreign keys — the single source of a
 * subsystem's tunables with <b>no</b> {@code @Value} bindings in the consuming services.
 *
 * <p>Subclasses supply the filename plus four pure hooks ({@link #defaults()},
 * {@link #parse(JsonNode)}, {@link #toMap(Object)}, {@link #ownedKeys()}); this base owns the
 * file I/O, refresh gating, and merge semantics. {@code KbConfigManager} and
 * {@code ProcessMiningConfigManager} are instances of this pattern (the crawl lane's
 * {@code CrawlRuntimeConfigManager} predates it and carries extra quota-ledger responsibilities —
 * it deliberately stays bespoke).
 */
public abstract class ManagedJsonConfigManager<T> {

    private static final Logger log = LoggerFactory.getLogger(ManagedJsonConfigManager.class);
    private static final long REFRESH_INTERVAL_NANOS = 5_000_000_000L; // 5 seconds

    private final Path configPath;
    private final ObjectMapper mapper = JsonUtils.standardMapper();

    private volatile long lastModified = Long.MIN_VALUE;
    private volatile T config;
    private volatile long lastRefreshNanos = 0L;

    /** Resolve {@code filename} under the kompile config directory. */
    protected ManagedJsonConfigManager(String filename) {
        this.configPath = KompileHome.configDirectory().toPath().resolve(filename);
    }

    /** Test seam: point the manager at an explicit config file instead of the real ~/.kompile path. */
    protected ManagedJsonConfigManager(Path configPath) {
        this.configPath = configPath;
    }

    /** Factory defaults — served when the file is missing/unreadable. */
    protected abstract T defaults();

    /** Parse (and clamp) a config file's JSON root into the config object. */
    protected abstract T parse(JsonNode root);

    /** Serialize the effective config for the REST/UI surface. */
    protected abstract Map<String, Object> toMap(T config);

    /** The keys this config owns — a config push never clobbers other sections of a shared file. */
    protected abstract Set<String> ownedKeys();

    /** Current config, re-reading the file at most once per 5 seconds (mtime-gated). */
    public synchronized T current() {
        if (config == null) {
            config = defaults();
        }
        long now = System.nanoTime();
        if ((now - lastRefreshNanos) < REFRESH_INTERVAL_NANOS) {
            return config;
        }
        lastRefreshNanos = now;
        try {
            if (!Files.exists(configPath)) {
                lastModified = Long.MIN_VALUE;
                config = defaults();
                return config;
            }
            long mtime = Files.getLastModifiedTime(configPath).toMillis();
            if (mtime != lastModified) {
                JsonNode root = mapper.readTree(configPath.toFile());
                config = parse(root);
                lastModified = mtime;
                log.info("Loaded managed config from {}", configPath);
            }
        } catch (Exception e) {
            log.warn("Failed to read managed config from {}: {}", configPath, e.getMessage());
        }
        return config;
    }

    /** Snapshot of the effective config for the REST / web-UI surface. */
    public synchronized Map<String, Object> currentAsMap() {
        return toMap(current());
    }

    /**
     * Merge owned-key updates into the config file (preserving any other keys), then force an
     * immediate re-read so the change applies right away.
     */
    public synchronized Map<String, Object> update(Map<String, Object> updates) throws IOException {
        JsonNode existing = Files.exists(configPath) ? mapper.readTree(configPath.toFile()) : null;
        ObjectNode root = (existing instanceof ObjectNode on) ? on : mapper.createObjectNode();
        if (updates != null) {
            Set<String> owned = ownedKeys();
            for (Map.Entry<String, Object> e : updates.entrySet()) {
                if (owned.contains(e.getKey())) {
                    root.set(e.getKey(), mapper.valueToTree(e.getValue()));
                }
            }
        }
        Path parent = configPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(configPath.toFile(), root);
        lastModified = Long.MIN_VALUE;
        lastRefreshNanos = 0L;
        return currentAsMap();
    }
}
