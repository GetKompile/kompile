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

package ai.kompile.process.discovery.mining;

import ai.kompile.cli.common.config.ManagedJsonConfigManager;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * Kompile-managed configuration manager for the process-mining pipeline —
 * {@code process-mining-config.json} under the kompile config directory, hot-reloaded and
 * web-UI-editable. This is the ONLY source of these tunables (no Spring {@code @Value} bindings
 * in the consuming services). One instance of the shared {@link ManagedJsonConfigManager}
 * pattern; {@link ProcessMiningConfig} supplies the parse/serialize/keys hooks.
 */
@Component
public class ProcessMiningConfigManager extends ManagedJsonConfigManager<ProcessMiningConfig> {

    private static final String CONFIG_FILENAME = "process-mining-config.json";

    public ProcessMiningConfigManager() {
        super(CONFIG_FILENAME);
    }

    /** Test seam: point the manager at an explicit config file instead of the real ~/.kompile path. */
    public ProcessMiningConfigManager(Path configPath) {
        super(configPath);
    }

    @Override
    protected ProcessMiningConfig defaults() {
        return ProcessMiningConfig.defaults();
    }

    @Override
    protected ProcessMiningConfig parse(JsonNode root) {
        return ProcessMiningConfig.from(root);
    }

    @Override
    protected Map<String, Object> toMap(ProcessMiningConfig config) {
        return config.toMap();
    }

    @Override
    protected Set<String> ownedKeys() {
        return ProcessMiningConfig.keys();
    }
}
