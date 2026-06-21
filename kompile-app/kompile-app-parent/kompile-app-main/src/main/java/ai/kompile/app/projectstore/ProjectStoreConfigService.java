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
package ai.kompile.app.projectstore;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Reads and writes the Kompile-managed project store pointer at
 * {@code <kompile-config-dir>/project-store.json}. The config directory is resolved through
 * {@link KompileHome#configDirectory()}, which honors {@code -Dkompile.data.dir} — so when the
 * app is launched against a project, the store URL is scoped to that project rather than living
 * in Spring's runtime properties. Mirrors the established {@code NoteSyncConfigService} pattern.
 */
@Service
public class ProjectStoreConfigService {

    private static final Logger log = LoggerFactory.getLogger(ProjectStoreConfigService.class);
    private static final String CONFIG_FILENAME = "project-store.json";

    private final ObjectMapper objectMapper = JsonUtils.standardMapper();
    private final Path configFilePath = KompileHome.configDirectory().toPath().resolve(CONFIG_FILENAME);
    private volatile ProjectStoreConfig current = new ProjectStoreConfig();

    @PostConstruct
    public void load() {
        if (!Files.exists(configFilePath)) {
            log.info("No project store config at {} — none configured yet", configFilePath);
            return;
        }
        try {
            current = objectMapper.readValue(Files.readString(configFilePath), ProjectStoreConfig.class);
            log.info("Loaded project store config: url={}, gitXet={}", current.getUrl(), current.isGitXet());
        } catch (IOException e) {
            log.warn("Failed to read project store config {}: {}. Ignoring.", configFilePath, e.getMessage());
        }
    }

    public ProjectStoreConfig getConfig() {
        return current;
    }

    public boolean isConfigured() {
        return current.isConfigured();
    }

    /** The configured base URL with any trailing slashes trimmed, or empty when unset. */
    public Optional<String> storeUrl() {
        if (!current.isConfigured()) {
            return Optional.empty();
        }
        String url = current.getUrl().trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return Optional.of(url);
    }

    public synchronized ProjectStoreConfig update(ProjectStoreConfig update) {
        ProjectStoreConfig merged = new ProjectStoreConfig();
        merged.setUrl(update.getUrl() != null ? update.getUrl().trim() : current.getUrl());
        merged.setGitXet(update.isGitXet());
        this.current = merged;
        persist();
        log.info("Updated project store config: url={}, gitXet={}", merged.getUrl(), merged.isGitXet());
        return current;
    }

    private void persist() {
        try {
            Files.createDirectories(configFilePath.getParent());
            Files.writeString(configFilePath, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(current));
        } catch (IOException e) {
            log.error("Failed to persist project store config {}: {}", configFilePath, e.getMessage());
        }
    }
}
