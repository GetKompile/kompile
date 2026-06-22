/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.confidence;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * Kompile-managed configuration manager for the KB confidence / evidence / learning model.
 *
 * <p>Loads {@link KbConfig} from {@code kb-confidence-config.json} under
 * {@code KompileHome.configDirectory()}, hot-reloads on mtime change (at most once per 5s), and
 * merges web-UI / REST updates back into the file. This is the ONLY source of these tunables —
 * there are no Spring {@code @Value} bindings and no hard-coded literals in the consuming services.
 * Mirrors {@code CrawlRuntimeConfigManager}.</p>
 */
@Component
public class KbConfigManager {

    private static final Logger log = LoggerFactory.getLogger(KbConfigManager.class);
    private static final String CONFIG_FILENAME = "kb-confidence-config.json";
    private static final long REFRESH_INTERVAL_NANOS = 5_000_000_000L; // 5 seconds

    private final Path configPath;
    private final ObjectMapper mapper = JsonUtils.standardMapper();

    private volatile long lastModified = Long.MIN_VALUE;
    private volatile KbConfig config = KbConfig.defaults();
    private volatile long lastRefreshNanos = 0L;

    public KbConfigManager() {
        this.configPath = KompileHome.configDirectory().toPath().resolve(CONFIG_FILENAME);
    }

    /** Test seam: point the manager at an explicit config file instead of the real ~/.kompile path. */
    public KbConfigManager(Path configPath) {
        this.configPath = configPath;
    }

    /** Current config, re-reading the file at most once per 5 seconds (mtime-gated). */
    public synchronized KbConfig current() {
        long now = System.nanoTime();
        if ((now - lastRefreshNanos) < REFRESH_INTERVAL_NANOS) {
            return config;
        }
        lastRefreshNanos = now;
        try {
            if (!Files.exists(configPath)) {
                lastModified = Long.MIN_VALUE;
                config = KbConfig.defaults();
                return config;
            }
            long mtime = Files.getLastModifiedTime(configPath).toMillis();
            if (mtime != lastModified) {
                JsonNode root = mapper.readTree(configPath.toFile());
                config = KbConfig.from(root);
                lastModified = mtime;
                log.info("Loaded KB confidence config from {}", configPath);
            }
        } catch (Exception e) {
            log.warn("Failed to read KB confidence config from {}: {}", configPath, e.getMessage());
        }
        return config;
    }

    /** Snapshot of the effective config for the REST / web-UI surface. */
    public synchronized Map<String, Object> currentAsMap() {
        return current().toMap();
    }

    /**
     * Merge {@code kb*} updates into the config file (preserving any other keys), then force an
     * immediate re-read so the change applies right away. Keys not owned by {@link KbConfig#keys()}
     * are ignored, so a config push can never clobber other sections of a shared file.
     */
    public synchronized Map<String, Object> update(Map<String, Object> updates) throws IOException {
        JsonNode existing = Files.exists(configPath) ? mapper.readTree(configPath.toFile()) : null;
        ObjectNode root = (existing instanceof ObjectNode on) ? on : mapper.createObjectNode();
        if (updates != null) {
            Set<String> owned = KbConfig.keys();
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
        return current().toMap();
    }
}
