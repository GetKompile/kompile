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

package ai.kompile.cli.main;

import ai.kompile.cli.common.config.HardwareAutoConfigurator;
import ai.kompile.cli.main.config.AppConfigWizard;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared utility for bootstrapping the {@code ~/.kompile/} home directory
 * and writing default JSON configuration files.
 *
 * <p>Used by both {@code kompile bootstrap} and {@code kompile web} (global mode)
 * to ensure the kompile home directory is fully initialized before the
 * Spring Boot application starts.</p>
 */
public final class GlobalBootstrap {

    private GlobalBootstrap() {}

    /**
     * Ensure the {@code ~/.kompile/} directory and all expected subdirectories
     * exist. Safe to call multiple times — only creates directories that are missing.
     */
    public static void ensureHomeDirectory() {
        File home = Info.homeDirectory();
        String[] dirs = {
                "bin",
                "config",
                "components",
                "data/input_documents/uploads",
                "data/shared_files",
                "data/prompt-templates",
                "data/models/.staging",
                "data/logs",
                "data/tool-definitions",
                "data/folders",
                "data/mcp-servers",
                "data/mcp-bridges",
                "data/pids",
                "fact-sheets",
                "archives",
                "anserini/indexes"
        };
        for (String dir : dirs) {
            new File(home, dir).mkdirs();
        }
    }

    /**
     * Write default JSON config files to {@code ~/.kompile/config/} if they
     * don't already exist. Uses {@link HardwareAutoConfigurator} for
     * hardware-aware defaults.
     *
     * <p>Only writes files that are missing — existing configs are never overwritten,
     * so user customizations from the UI or {@code kompile config} are preserved.</p>
     *
     * @return true if any config files were written
     */
    public static boolean ensureConfigs() {
        String dataDir = Info.homeDirectory().getAbsolutePath();
        boolean wroteAny = false;

        HardwareAutoConfigurator.AutoConfigResult autoConfig =
                HardwareAutoConfigurator.autoConfigure(false);

        // --- app-index-config.json ---
        if (!configExists(AppConfigWizard.APP_INDEX_CONFIG)) {
            Map<String, Object> appIndexConfig = new LinkedHashMap<>();
            appIndexConfig.put("appTitle", "Kompile RAG Console");
            appIndexConfig.put("vectorStoreType", "ANSERINI");
            appIndexConfig.put("vectorStorePath", dataDir + "/anserini/indexes/vector_index");
            appIndexConfig.put("keywordIndexPath", dataDir + "/anserini/indexes/default_index");
            appIndexConfig.put("subprocessEnabled", true);
            appIndexConfig.put("subprocessHeapSize", autoConfig.subprocessConfig.get("heapSize"));
            appIndexConfig.put("indexBatchSize", 100);
            appIndexConfig.put("adaptiveBatchSize", true);
            appIndexConfig.put("embeddingTargetBatchSize",
                    autoConfig.pipelineConfig.get("defaultBatchSize"));
            AppConfigWizard.saveConfig(AppConfigWizard.APP_INDEX_CONFIG, appIndexConfig);
            System.out.println("  Bootstrapped: ~/.kompile/config/" + AppConfigWizard.APP_INDEX_CONFIG);
            wroteAny = true;
        }

        // --- pipeline-config.json ---
        if (!configExists("pipeline-config.json")) {
            AppConfigWizard.saveConfig("pipeline-config.json", autoConfig.pipelineConfig);
            System.out.println("  Bootstrapped: ~/.kompile/config/pipeline-config.json");
            wroteAny = true;
        }

        // --- subprocess-ingest-config.json ---
        if (!configExists("subprocess-ingest-config.json")) {
            AppConfigWizard.saveConfig("subprocess-ingest-config.json", autoConfig.subprocessConfig);
            System.out.println("  Bootstrapped: ~/.kompile/config/subprocess-ingest-config.json");
            wroteAny = true;
        }

        // --- nd4j-environment-config.json ---
        if (!configExists("nd4j-environment-config.json")) {
            AppConfigWizard.saveConfig("nd4j-environment-config.json", autoConfig.nd4jConfig);
            System.out.println("  Bootstrapped: ~/.kompile/config/nd4j-environment-config.json");
            wroteAny = true;
        }

        // --- feature-flags-config.json ---
        if (!configExists(AppConfigWizard.FEATURE_FLAGS_CONFIG)) {
            Map<String, Object> flags = new LinkedHashMap<>();
            flags.put("guardrails", false);
            flags.put("queryTransformation", false);
            flags.put("contextualRag", false);
            flags.put("toolGatewayEnabled", false);
            flags.put("kvcache", false);
            flags.put("graphRag", false);
            flags.put("multiModal", false);
            flags.put("sourceAttribution", false);
            AppConfigWizard.saveConfig(AppConfigWizard.FEATURE_FLAGS_CONFIG, flags);
            System.out.println("  Bootstrapped: ~/.kompile/config/" + AppConfigWizard.FEATURE_FLAGS_CONFIG);
            wroteAny = true;
        }

        // --- tool-gateway-config.json ---
        if (!configExists("tool-gateway-config.json")) {
            Map<String, Object> gwConfig = new LinkedHashMap<>();
            gwConfig.put("modelSource", "STAGING");
            gwConfig.put("failOpen", true);
            gwConfig.put("evaluationTimeoutMs", 10000);
            gwConfig.put("verboseLogging", false);
            gwConfig.put("hotReload", false);
            gwConfig.put("dryRun", false);
            gwConfig.put("judgeScoringEnabled", false);
            AppConfigWizard.saveConfig("tool-gateway-config.json", gwConfig);
            System.out.println("  Bootstrapped: ~/.kompile/config/tool-gateway-config.json");
            wroteAny = true;
        }

        return wroteAny;
    }

    private static boolean configExists(String filename) {
        return new File(Info.homeDirectory(), "config/" + filename).exists();
    }
}
