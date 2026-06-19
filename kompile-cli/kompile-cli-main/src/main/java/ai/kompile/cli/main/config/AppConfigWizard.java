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

package ai.kompile.cli.main.config;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.utils.MapUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Interactive wizard for configuring kompile-app-main UI settings.
 * Edits the same JSON config files that the Angular UI manages through REST APIs,
 * allowing full configuration without running the web application.
 *
 * <p>Config files are stored in {@code ~/.kompile/config/} and are read by the
 * Spring Boot application on startup.</p>
 */
public class AppConfigWizard {

    private static final String RESET  = "\033[0m";
    private static final String BOLD   = "\033[1m";
    private static final String DIM    = "\033[2m";
    private static final String CYAN   = "\033[36m";
    private static final String GREEN  = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String RED    = "\033[31m";

    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    public static final String APP_INDEX_CONFIG = "app-index-config.json";
    public static final String MODEL_ROLES_CONFIG = "model-roles-config.json";
    public static final String FEATURE_FLAGS_CONFIG = "feature-flags-config.json";
    public static final String LLM_PROVIDER_CONFIG = "llm-provider-config.json";
    public static final String BACKUP_CONFIG = "backup-config.json";

    static final String[] SECTIONS = {
            "vectorstore", "embedding", "ingestion", "subprocess",
            "model-roles", "feature-flags", "llm-provider", "backup", "tool-gateway"
    };

    static final String[] SECTION_LABELS = {
            "Vector Store & Indexing",
            "Embedding Configuration",
            "Document Ingestion",
            "Subprocess Settings",
            "Model Roles",
            "Feature Flags",
            "LLM Provider (API Keys)",
            "Backup Configuration",
            "Tool Gateway (LLM Evaluation)"
    };

    private static Path configDir() {
        return Path.of(System.getProperty("user.home"), ".kompile", "config");
    }

    /**
     * Run the full interactive wizard. Returns true if any config was saved.
     */
    public static boolean run() {
        Terminal terminal = null;
        try {
            terminal = TerminalBuilder.builder().system(true).build();
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();

            printHeader("Kompile App Configuration");

            System.out.println("  " + DIM + "Config directory: " + configDir() + RESET);
            System.out.println();

            boolean modified = false;

            while (true) {
                int choice = showMainMenu(reader);
                switch (choice) {
                    case 1: modified |= editVectorStore(reader); break;
                    case 2: modified |= editEmbedding(reader); break;
                    case 3: modified |= editIngestion(reader); break;
                    case 4: modified |= editSubprocess(reader); break;
                    case 5: modified |= editModelRoles(reader); break;
                    case 6: modified |= editFeatureFlags(reader); break;
                    case 7: modified |= editLlmProvider(reader); break;
                    case 8: modified |= editBackup(reader); break;
                    case 9: modified |= editToolGateway(reader); break;
                    case 10: showAllConfig(); break;
                    case 0:
                        if (modified) {
                            System.out.println(GREEN + "  Configuration saved. Restart kompile-app for changes to take effect." + RESET);
                        }
                        System.out.println();
                        return modified;
                    case -1: return false;
                }
            }

        } catch (Exception e) {
            System.err.println("Wizard error: " + e.getMessage());
            return false;
        } finally {
            closeTerminal(terminal);
        }
    }

    // ── Main Menu ──────────────────────────────────────────────────────────

    private static int showMainMenu(LineReader reader) {
        System.out.println(BOLD + "  Select a configuration section to edit:" + RESET);
        System.out.println();
        for (int i = 0; i < SECTION_LABELS.length; i++) {
            System.out.printf("  " + CYAN + "%2d" + RESET + "  %s%n", i + 1, SECTION_LABELS[i]);
        }
        System.out.println();
        System.out.printf("  " + CYAN + "%2d" + RESET + "  Show All Configuration%n", SECTION_LABELS.length + 1);
        System.out.printf("  " + CYAN + " 0" + RESET + "  Save & Exit%n");
        System.out.println();

        int maxChoice = SECTION_LABELS.length + 1;
        String input = prompt(reader, "  Choice (0-" + maxChoice + "): ");
        if (input == null) return -1;
        try {
            int n = Integer.parseInt(input.trim());
            if (n >= 0 && n <= maxChoice) return n;
        } catch (NumberFormatException ignored) {}
        return -2; // invalid, loop again
    }

    // ── Section 1: Vector Store & Indexing ─────────────────────────────────

    private static boolean editVectorStore(LineReader reader) {
        printSection("Vector Store & Indexing");
        Map<String, Object> config = loadConfig(APP_INDEX_CONFIG);

        String currentType = MapUtils.getString(config, "vectorStoreType", "ANSERINI");
        String[] types = {"ANSERINI", "VESPA", "PGVECTOR", "CHROMA"};
        String[] typeDescs = {
                "ANSERINI — Lucene-backed HNSW indexing (default, embedded)",
                "VESPA    — Vespa vector search engine (external)",
                "PGVECTOR — PostgreSQL pgvector extension (external)",
                "CHROMA   — Chroma vector database (external)"
        };

        System.out.println("  " + DIM + "Current vector store: " + currentType + RESET);
        System.out.println();
        for (int i = 0; i < typeDescs.length; i++) {
            String marker = types[i].equals(currentType) ? GREEN + " *" + RESET : "  ";
            System.out.printf("  " + CYAN + "%2d" + RESET + "%s %s%n", i + 1, marker, typeDescs[i]);
        }
        System.out.println();

        String typeInput = prompt(reader, "  Vector store type (1-4, Enter to keep [" + currentType + "]): ");
        if (typeInput != null && !typeInput.trim().isEmpty()) {
            try {
                int idx = Integer.parseInt(typeInput.trim()) - 1;
                if (idx >= 0 && idx < types.length) {
                    config.put("vectorStoreType", types[idx]);
                }
            } catch (NumberFormatException ignored) {}
        }

        String selectedType = MapUtils.getString(config, "vectorStoreType", "ANSERINI");

        // Common path settings
        String defaultDataDir = System.getProperty("user.home") + "/.kompile";
        config.put("vectorStorePath", promptWithDefault(reader,
                "  Vector index path",
                MapUtils.getString(config, "vectorStorePath", defaultDataDir + "/anserini/indexes/vector_index")));
        config.put("keywordIndexPath", promptWithDefault(reader,
                "  Keyword index path",
                MapUtils.getString(config, "keywordIndexPath", defaultDataDir + "/anserini/indexes/default_index")));

        // Type-specific settings
        switch (selectedType) {
            case "ANSERINI":
                editAnseriniSettings(reader, config);
                break;
            case "VESPA":
                editVespaSettings(reader, config);
                break;
            case "PGVECTOR":
                editPgvectorSettings(reader, config);
                break;
            case "CHROMA":
                editChromaSettings(reader, config);
                break;
        }

        saveConfig(APP_INDEX_CONFIG, config);
        System.out.println(GREEN + "  Vector store configuration saved." + RESET);
        System.out.println();
        return true;
    }

    private static void editAnseriniSettings(LineReader reader, Map<String, Object> config) {
        System.out.println();
        System.out.println(BOLD + "  HNSW Index Settings:" + RESET);
        System.out.println("  " + DIM + "(Higher M and efConstruction = better recall, slower indexing)" + RESET);
        System.out.println();

        @SuppressWarnings("unchecked")
        Map<String, Object> hnsw = config.containsKey("hnsw")
                ? (Map<String, Object>) config.get("hnsw")
                : new LinkedHashMap<>();

        hnsw.put("enabled", promptBoolean(reader, "  HNSW enabled", MapUtils.getBoolean(hnsw, "enabled", true)));
        if (MapUtils.getBoolean(hnsw, "enabled", true)) {
            hnsw.put("m", promptInt(reader, "  HNSW M (connections per node, 8-32)", MapUtils.getInt(hnsw, "m", 16)));
            hnsw.put("efConstruction", promptInt(reader, "  HNSW efConstruction (50-500)", MapUtils.getInt(hnsw, "efConstruction", 100)));
        }
        config.put("hnsw", hnsw);
    }

    private static void editVespaSettings(LineReader reader, Map<String, Object> config) {
        System.out.println();
        System.out.println(BOLD + "  Vespa Settings:" + RESET);
        System.out.println();

        config.put("vespaEndpoint", promptWithDefault(reader,
                "  Vespa endpoint URL",
                MapUtils.getString(config, "vespaEndpoint", "http://localhost:8080")));
        config.put("vespaNamespace", promptWithDefault(reader,
                "  Vespa namespace",
                MapUtils.getString(config, "vespaNamespace", "kompile")));
        config.put("vespaDocumentType", promptWithDefault(reader,
                "  Vespa document type",
                MapUtils.getString(config, "vespaDocumentType", "document")));
        config.put("vespaVectorField", promptWithDefault(reader,
                "  Vespa vector field",
                MapUtils.getString(config, "vespaVectorField", "embedding")));
        config.put("vespaHybridSearchEnabled", promptBoolean(reader,
                "  Vespa hybrid search enabled",
                MapUtils.getBoolean(config, "vespaHybridSearchEnabled", false)));
        if (MapUtils.getBoolean(config, "vespaHybridSearchEnabled", false)) {
            config.put("vespaHybridVectorWeight", promptDouble(reader,
                    "  Vespa hybrid vector weight (0.0-1.0)",
                    MapUtils.getDouble(config, "vespaHybridVectorWeight", 0.7)));
        }
    }

    private static void editPgvectorSettings(LineReader reader, Map<String, Object> config) {
        System.out.println();
        System.out.println(BOLD + "  PostgreSQL pgvector Settings:" + RESET);
        System.out.println();

        config.put("pgvectorUrl", promptWithDefault(reader,
                "  JDBC URL",
                MapUtils.getString(config, "pgvectorUrl", "jdbc:postgresql://localhost:5432/kompile")));
        config.put("pgvectorUsername", promptWithDefault(reader,
                "  Username",
                MapUtils.getString(config, "pgvectorUsername", "kompile")));

        String currentPw = MapUtils.getString(config, "pgvectorPassword", "");
        String pwPrompt = currentPw.isEmpty() ? "  Password: " : "  Password (Enter to keep current): ";
        String pw = prompt(reader, pwPrompt);
        if (pw != null && !pw.trim().isEmpty()) {
            config.put("pgvectorPassword", pw.trim());
        }

        config.put("pgvectorTableName", promptWithDefault(reader,
                "  Table name",
                MapUtils.getString(config, "pgvectorTableName", "vector_store")));
    }

    private static void editChromaSettings(LineReader reader, Map<String, Object> config) {
        System.out.println();
        System.out.println(BOLD + "  Chroma Settings:" + RESET);
        System.out.println();

        config.put("chromaHost", promptWithDefault(reader,
                "  Chroma host",
                MapUtils.getString(config, "chromaHost", "localhost")));
        config.put("chromaPort", promptInt(reader,
                "  Chroma port",
                MapUtils.getInt(config, "chromaPort", 8000)));
        config.put("chromaCollectionName", promptWithDefault(reader,
                "  Collection name",
                MapUtils.getString(config, "chromaCollectionName", "kompile_documents")));
    }

    // ── Section 2: Embedding Configuration ─────────────────────────────────

    private static boolean editEmbedding(LineReader reader) {
        printSection("Embedding Configuration");
        Map<String, Object> config = loadConfig(APP_INDEX_CONFIG);

        String[] models = {
                "bge-base-en-v1.5",
                "arctic-embed-l",
                "cosdpr-distil",
                "bge-small-en-v1.5"
        };
        String[] modelDescs = {
                "bge-base-en-v1.5    — 768-dim, good balance of speed and quality (default)",
                "arctic-embed-l      — 1024-dim, high quality dense embeddings",
                "cosdpr-distil       — 768-dim, distilled dense passage retrieval",
                "bge-small-en-v1.5   — 384-dim, fast and compact",
                "Custom..."
        };

        String currentModel = MapUtils.getString(config, "embeddingModelId", "bge-base-en-v1.5");
        System.out.println("  " + DIM + "Current embedding model: " + currentModel + RESET);
        System.out.println();

        for (int i = 0; i < modelDescs.length; i++) {
            String marker = (i < models.length && models[i].equals(currentModel)) ? GREEN + " *" + RESET : "  ";
            System.out.printf("  " + CYAN + "%2d" + RESET + "%s %s%n", i + 1, marker, modelDescs[i]);
        }
        System.out.println();

        String modelInput = prompt(reader, "  Embedding model (1-" + modelDescs.length + ", Enter to keep [" + currentModel + "]): ");
        if (modelInput != null && !modelInput.trim().isEmpty()) {
            try {
                int idx = Integer.parseInt(modelInput.trim()) - 1;
                if (idx >= 0 && idx < models.length) {
                    config.put("embeddingModelId", models[idx]);
                } else if (idx == models.length) {
                    String custom = promptRequired(reader, "  Custom model identifier: ");
                    if (custom != null) config.put("embeddingModelId", custom);
                }
            } catch (NumberFormatException ignored) {}
        }

        System.out.println();
        System.out.println(BOLD + "  Batch Size Settings:" + RESET);
        System.out.println("  " + DIM + "(Controls how many documents are embedded per batch)" + RESET);
        System.out.println();

        config.put("embeddingTargetBatchSize", promptInt(reader,
                "  Target embedding batch size (16-256)",
                MapUtils.getInt(config, "embeddingTargetBatchSize", 64)));
        config.put("adaptiveBatchSize", promptBoolean(reader,
                "  Adaptive batch sizing (auto-adjusts based on memory)",
                MapUtils.getBoolean(config, "adaptiveBatchSize", true)));

        saveConfig(APP_INDEX_CONFIG, config);
        System.out.println(GREEN + "  Embedding configuration saved." + RESET);

        String selectedModel = MapUtils.getString(config, "embeddingModelId", "bge-base-en-v1.5");
        System.out.println();
        System.out.println("  " + DIM + "Also set in application.properties:" + RESET);
        System.out.println("    " + BOLD + "kompile.embedding.anserini.model-identifier=" + selectedModel + RESET);
        System.out.println();
        return true;
    }

    // ── Section 3: Document Ingestion ──────────────────────────────────────

    private static boolean editIngestion(LineReader reader) {
        printSection("Document Ingestion");
        Map<String, Object> config = loadConfig(APP_INDEX_CONFIG);

        System.out.println(BOLD + "  Indexing Batch Size:" + RESET);
        System.out.println("  " + DIM + "(Documents indexed per batch, 25-500)" + RESET);
        System.out.println();

        config.put("indexBatchSize", promptInt(reader,
                "  Index batch size",
                MapUtils.getInt(config, "indexBatchSize", 100)));

        saveConfig(APP_INDEX_CONFIG, config);

        // Chunking settings (separate config concept, stored in pipeline-config.json)
        System.out.println();
        System.out.println(BOLD + "  Chunking Settings:" + RESET);
        System.out.println("  " + DIM + "(How documents are split before embedding)" + RESET);
        System.out.println();

        Map<String, Object> pipelineConfig = loadConfig("pipeline-config.json");

        String[] strategies = {"token", "sentence", "recursive", "markdown", "table-aware"};
        String[] strategyDescs = {
                "token       — Split by token count",
                "sentence    — Split at sentence boundaries",
                "recursive   — Recursively split by separators (recommended)",
                "markdown    — Split by markdown headers",
                "table-aware — Preserve table structure during splitting"
        };

        String currentStrategy = MapUtils.getString(pipelineConfig, "chunkingStrategy", "table-aware");
        System.out.println("  " + DIM + "Current strategy: " + currentStrategy + RESET);
        System.out.println();
        for (int i = 0; i < strategyDescs.length; i++) {
            String marker = strategies[i].equals(currentStrategy) ? GREEN + " *" + RESET : "  ";
            System.out.printf("  " + CYAN + "%2d" + RESET + "%s %s%n", i + 1, marker, strategyDescs[i]);
        }
        System.out.println();

        String stratInput = prompt(reader, "  Chunking strategy (1-" + strategies.length + ", Enter to keep [" + currentStrategy + "]): ");
        if (stratInput != null && !stratInput.trim().isEmpty()) {
            try {
                int idx = Integer.parseInt(stratInput.trim()) - 1;
                if (idx >= 0 && idx < strategies.length) {
                    pipelineConfig.put("chunkingStrategy", strategies[idx]);
                }
            } catch (NumberFormatException ignored) {}
        }

        pipelineConfig.put("chunkSize", promptInt(reader,
                "  Chunk size (characters)",
                MapUtils.getInt(pipelineConfig, "chunkSize", 2000)));
        pipelineConfig.put("chunkOverlap", promptInt(reader,
                "  Chunk overlap (characters)",
                MapUtils.getInt(pipelineConfig, "chunkOverlap", 200)));

        saveConfig("pipeline-config.json", pipelineConfig);
        System.out.println(GREEN + "  Document ingestion configuration saved." + RESET);
        System.out.println();
        return true;
    }

    // ── Section 4: Subprocess Settings ─────────────────────────────────────

    private static boolean editSubprocess(LineReader reader) {
        printSection("Subprocess Settings");
        System.out.println("  " + DIM + "Subprocesses run document ingestion and embedding in separate JVMs" + RESET);
        System.out.println("  " + DIM + "to isolate memory usage from the main application." + RESET);
        System.out.println();

        Map<String, Object> config = loadConfig(APP_INDEX_CONFIG);

        config.put("subprocessEnabled", promptBoolean(reader,
                "  Subprocess mode enabled",
                MapUtils.getBoolean(config, "subprocessEnabled", true)));

        if (MapUtils.getBoolean(config, "subprocessEnabled", true)) {
            String[] heapOptions = {"2g", "4g", "6g", "8g", "12g", "16g"};
            String currentHeap = MapUtils.getString(config, "subprocessHeapSize", "4g");
            System.out.println();
            System.out.println("  " + DIM + "Current heap: " + currentHeap + RESET);
            for (int i = 0; i < heapOptions.length; i++) {
                String marker = heapOptions[i].equals(currentHeap) ? GREEN + " *" + RESET : "  ";
                System.out.printf("  " + CYAN + "%2d" + RESET + "%s %s%n", i + 1, marker, heapOptions[i]);
            }
            System.out.println();

            String heapInput = prompt(reader, "  Subprocess heap size (1-" + heapOptions.length + ", Enter to keep [" + currentHeap + "]): ");
            if (heapInput != null && !heapInput.trim().isEmpty()) {
                try {
                    int idx = Integer.parseInt(heapInput.trim()) - 1;
                    if (idx >= 0 && idx < heapOptions.length) {
                        config.put("subprocessHeapSize", heapOptions[idx]);
                    }
                } catch (NumberFormatException ignored) {
                    // Allow manual entry like "10g"
                    if (heapInput.trim().matches("\\d+g")) {
                        config.put("subprocessHeapSize", heapInput.trim());
                    }
                }
            }
        }

        saveConfig(APP_INDEX_CONFIG, config);
        System.out.println(GREEN + "  Subprocess configuration saved." + RESET);
        System.out.println();
        return true;
    }

    // ── Section 5: Model Roles ─────────────────────────────────────────────

    private static boolean editModelRoles(LineReader reader) {
        printSection("Model Roles");
        System.out.println("  " + DIM + "Assign models to RAG pipeline stages." + RESET);
        System.out.println("  " + DIM + "These override application.properties at startup." + RESET);
        System.out.println();

        Map<String, Object> config = loadConfig(MODEL_ROLES_CONFIG);

        String[] denseModels = {"bge-base-en-v1.5", "arctic-embed-l", "cosdpr-distil", "bge-small-en-v1.5"};
        String currentDense = MapUtils.getString(config, "denseRetrievalModel", "bge-base-en-v1.5");
        System.out.println(BOLD + "  Dense Retrieval Model:" + RESET);
        System.out.println("  " + DIM + "Current: " + currentDense + RESET);
        for (int i = 0; i < denseModels.length; i++) {
            String marker = denseModels[i].equals(currentDense) ? GREEN + " *" + RESET : "  ";
            System.out.printf("  " + CYAN + "%2d" + RESET + "%s %s%n", i + 1, marker, denseModels[i]);
        }
        System.out.printf("  " + CYAN + "%2d" + RESET + "   Custom...%n", denseModels.length + 1);
        System.out.println();

        String denseInput = prompt(reader, "  Dense model (1-" + (denseModels.length + 1) + ", Enter to keep): ");
        if (denseInput != null && !denseInput.trim().isEmpty()) {
            try {
                int idx = Integer.parseInt(denseInput.trim()) - 1;
                if (idx >= 0 && idx < denseModels.length) {
                    config.put("denseRetrievalModel", denseModels[idx]);
                } else if (idx == denseModels.length) {
                    String custom = promptRequired(reader, "  Custom model ID: ");
                    if (custom != null) config.put("denseRetrievalModel", custom);
                }
            } catch (NumberFormatException ignored) {}
        }

        System.out.println();
        String sparseModel = promptWithDefault(reader,
                "  Sparse retrieval model (blank to disable)",
                MapUtils.getString(config, "sparseRetrievalModel", ""));
        config.put("sparseRetrievalModel", sparseModel);

        String[] rerankModels = {"ms-marco-MiniLM-L-6-v2", "ms-marco-MiniLM-L-12-v2"};
        String currentRerank = MapUtils.getString(config, "rerankingModel", "ms-marco-MiniLM-L-6-v2");
        System.out.println();
        System.out.println(BOLD + "  Reranking Model:" + RESET);
        System.out.println("  " + DIM + "Current: " + currentRerank + RESET);
        for (int i = 0; i < rerankModels.length; i++) {
            String marker = rerankModels[i].equals(currentRerank) ? GREEN + " *" + RESET : "  ";
            System.out.printf("  " + CYAN + "%2d" + RESET + "%s %s%n", i + 1, marker, rerankModels[i]);
        }
        System.out.printf("  " + CYAN + "%2d" + RESET + "   Disable reranking%n", rerankModels.length + 1);
        System.out.println();

        String rerankInput = prompt(reader, "  Reranking model (1-" + (rerankModels.length + 1) + ", Enter to keep): ");
        if (rerankInput != null && !rerankInput.trim().isEmpty()) {
            try {
                int idx = Integer.parseInt(rerankInput.trim()) - 1;
                if (idx >= 0 && idx < rerankModels.length) {
                    config.put("rerankingModel", rerankModels[idx]);
                    config.put("rerankingEnabled", true);
                } else if (idx == rerankModels.length) {
                    config.put("rerankingEnabled", false);
                }
            } catch (NumberFormatException ignored) {}
        }

        System.out.println();
        config.put("hybridEnabled", promptBoolean(reader,
                "  Hybrid retrieval (dense + sparse)",
                MapUtils.getBoolean(config, "hybridEnabled", false)));

        if (MapUtils.getBoolean(config, "hybridEnabled", false)) {
            config.put("hybridDenseWeight", promptDouble(reader,
                    "  Dense weight in hybrid mode (0.0-1.0)",
                    MapUtils.getDouble(config, "hybridDenseWeight", 0.7)));
        }

        config.put("rerankingTopK", promptInt(reader,
                "  Reranking top-K (0 = all retrieved)",
                MapUtils.getInt(config, "rerankingTopK", 50)));

        saveConfig(MODEL_ROLES_CONFIG, config);
        System.out.println(GREEN + "  Model roles configuration saved." + RESET);

        // Print property equivalents
        System.out.println();
        System.out.println("  " + DIM + "Equivalent application.properties:" + RESET);
        System.out.println("    kompile.models.roles.dense-retrieval=" + MapUtils.getString(config, "denseRetrievalModel", "bge-base-en-v1.5"));
        String sparse = MapUtils.getString(config, "sparseRetrievalModel", "");
        if (!sparse.isEmpty()) {
            System.out.println("    kompile.models.roles.sparse-retrieval=" + sparse);
        }
        if (MapUtils.getBoolean(config, "rerankingEnabled", true)) {
            System.out.println("    kompile.models.roles.reranking=" + MapUtils.getString(config, "rerankingModel", "ms-marco-MiniLM-L-6-v2"));
        }
        System.out.println("    kompile.models.reranking.enabled=" + MapUtils.getBoolean(config, "rerankingEnabled", true));
        System.out.println("    kompile.models.hybrid.enabled=" + MapUtils.getBoolean(config, "hybridEnabled", false));
        System.out.println();
        return true;
    }

    // ── Section 6: Feature Flags ───────────────────────────────────────────

    private static boolean editFeatureFlags(LineReader reader) {
        printSection("Feature Flags");
        System.out.println("  " + DIM + "Toggle optional features on/off. Enter a number to toggle." + RESET);
        System.out.println();

        Map<String, Object> config = loadConfig(FEATURE_FLAGS_CONFIG);

        String[] flagKeys = {
                "guardrailsInputEnabled", "guardrailsOutputEnabled",
                "queryTransformationEnabled", "toolGatewayEnabled",
                "evaluationEnabled", "kvCacheEnabled",
                "contextualRagEnabled", "graphExtractionEnabled"
        };
        String[] flagLabels = {
                "Input Guardrails          — Filter/validate user inputs",
                "Output Guardrails         — Filter/validate LLM outputs",
                "Query Transformation      — Rewrite queries for better retrieval",
                "Tool Gateway              — LLM-based tool call filtering",
                "RAG Evaluation            — Evaluate retrieval quality",
                "KV Cache                  — Key-value cache for inference",
                "Contextual RAG            — Add context to document chunks",
                "Knowledge Graph Extraction — Extract entities and relations"
        };
        boolean[] defaults = {false, false, false, false, false, false, false, false};

        String CHECK   = GREEN + "[x]" + RESET;
        String UNCHECK_STR = DIM + "[ ]" + RESET;

        while (true) {
            for (int i = 0; i < flagKeys.length; i++) {
                boolean on = MapUtils.getBoolean(config, flagKeys[i], defaults[i]);
                String checkbox = on ? CHECK : UNCHECK_STR;
                System.out.printf("  " + CYAN + "%2d" + RESET + "  %s  %s%n", i + 1, checkbox, flagLabels[i]);
            }
            System.out.println();

            int enabledCount = 0;
            for (String key : flagKeys) if (MapUtils.getBoolean(config, key, false)) enabledCount++;
            System.out.println("  " + DIM + "Enabled: " + enabledCount + "/" + flagKeys.length + RESET);
            System.out.println();

            String input = prompt(reader, "  Toggle (1-" + flagKeys.length + "), [a]ll on, [n]one, [c]onfirm: ");
            if (input == null) return false;
            String trimmed = input.trim().toLowerCase();

            if (trimmed.equals("c") || trimmed.equals("confirm") || trimmed.equals("done")) break;
            if (trimmed.equals("a") || trimmed.equals("all")) {
                for (String key : flagKeys) config.put(key, true);
                continue;
            }
            if (trimmed.equals("n") || trimmed.equals("none")) {
                for (String key : flagKeys) config.put(key, false);
                continue;
            }

            try {
                int n = Integer.parseInt(trimmed);
                if (n >= 1 && n <= flagKeys.length) {
                    String key = flagKeys[n - 1];
                    config.put(key, !MapUtils.getBoolean(config, key, defaults[n - 1]));
                }
            } catch (NumberFormatException ignored) {}
        }

        saveConfig(FEATURE_FLAGS_CONFIG, config);
        System.out.println(GREEN + "  Feature flags saved." + RESET);

        // Print property equivalents
        System.out.println();
        System.out.println("  " + DIM + "Equivalent application.properties:" + RESET);
        System.out.println("    kompile.guardrails.input.enabled=" + MapUtils.getBoolean(config, "guardrailsInputEnabled", false));
        System.out.println("    kompile.guardrails.output.enabled=" + MapUtils.getBoolean(config, "guardrailsOutputEnabled", false));
        System.out.println("    kompile.query.transformer.enabled=" + MapUtils.getBoolean(config, "queryTransformationEnabled", false));
        System.out.println("    kompile.tool-gateway.enabled=" + MapUtils.getBoolean(config, "toolGatewayEnabled", false));
        System.out.println("    kompile.evaluation.enabled=" + MapUtils.getBoolean(config, "evaluationEnabled", false));
        System.out.println("    kompile.kvcache.enabled=" + MapUtils.getBoolean(config, "kvCacheEnabled", false));
        System.out.println();
        return true;
    }

    // ── Section 7: LLM Provider ────────────────────────────────────────────

    private static boolean editLlmProvider(LineReader reader) {
        printSection("LLM Provider (API Keys)");
        System.out.println("  " + DIM + "Configure the LLM used for RAG responses." + RESET);
        System.out.println();

        Map<String, Object> config = loadConfig(LLM_PROVIDER_CONFIG);

        String[] providers = {"openai", "anthropic", "gemini", "ollama", "custom"};
        String[] providerDescs = {
                "OpenAI       — GPT-4, GPT-4o, etc.",
                "Anthropic    — Claude 4.6, Claude 4.5, etc.",
                "Google Gemini — Gemini 2.5 Pro, Flash, etc.",
                "Ollama       — Local models (Llama, Mistral, etc.)",
                "Custom       — OpenAI-compatible endpoint"
        };

        String currentProvider = MapUtils.getString(config, "provider", "");
        System.out.println("  " + DIM + "Current provider: " + (currentProvider.isEmpty() ? "(not configured)" : currentProvider) + RESET);
        System.out.println();

        for (int i = 0; i < providerDescs.length; i++) {
            String marker = providers[i].equals(currentProvider) ? GREEN + " *" + RESET : "  ";
            System.out.printf("  " + CYAN + "%2d" + RESET + "%s %s%n", i + 1, marker, providerDescs[i]);
        }
        System.out.println();

        String provInput = prompt(reader, "  Provider (1-" + providers.length + ", Enter to keep): ");
        if (provInput != null && !provInput.trim().isEmpty()) {
            try {
                int idx = Integer.parseInt(provInput.trim()) - 1;
                if (idx >= 0 && idx < providers.length) {
                    config.put("provider", providers[idx]);
                }
            } catch (NumberFormatException ignored) {}
        }

        String selectedProvider = MapUtils.getString(config, "provider", "");
        if (selectedProvider.isEmpty()) {
            System.out.println(YELLOW + "  No provider selected. Skipping." + RESET);
            System.out.println();
            return false;
        }

        // API key
        if (!"ollama".equals(selectedProvider)) {
            String envVar = getEnvVarForProvider(selectedProvider);
            String envValue = envVar != null ? System.getenv(envVar) : null;

            if (envValue != null && !envValue.isBlank()) {
                System.out.println("  Found " + envVar + " in environment: " + DIM + maskKey(envValue) + RESET);
                String use = prompt(reader, "  Use environment variable? [Y/n]: ");
                if (use == null || use.isBlank() || use.toLowerCase().startsWith("y")) {
                    config.put("apiKeySource", "env:" + envVar);
                } else {
                    String key = prompt(reader, "  API key: ");
                    if (key != null && !key.trim().isEmpty()) {
                        config.put("apiKey", key.trim());
                        config.put("apiKeySource", "config");
                    }
                }
            } else {
                String currentKey = MapUtils.getString(config, "apiKey", "");
                String keyPrompt = currentKey.isEmpty() ? "  API key: " : "  API key (Enter to keep current): ";
                String key = prompt(reader, keyPrompt);
                if (key != null && !key.trim().isEmpty()) {
                    config.put("apiKey", key.trim());
                    config.put("apiKeySource", "config");
                }
            }
        }

        // Model selection
        String[][] modelsByProvider = {
                {"gpt-4o", "gpt-4o-mini", "gpt-4.1", "o4-mini"},                 // openai
                {"claude-opus-4-6", "claude-sonnet-4-6", "claude-haiku-4-5-20251001"}, // anthropic
                {"gemini-2.5-pro", "gemini-2.5-flash", "gemini-2.0-flash"},        // gemini
                {},                                                                  // ollama
                {}                                                                   // custom
        };

        int provIdx = Arrays.asList(providers).indexOf(selectedProvider);
        String[] modelOptions = provIdx >= 0 ? modelsByProvider[provIdx] : new String[]{};

        if (modelOptions.length > 0) {
            String currentModel = MapUtils.getString(config, "model", "");
            System.out.println();
            System.out.println(BOLD + "  Select Model:" + RESET);
            if (!currentModel.isEmpty()) {
                System.out.println("  " + DIM + "Current: " + currentModel + RESET);
            }
            for (int i = 0; i < modelOptions.length; i++) {
                String marker = modelOptions[i].equals(currentModel) ? GREEN + " *" + RESET : "  ";
                String rec = (i == 0) ? " (recommended)" : "";
                System.out.printf("  " + CYAN + "%2d" + RESET + "%s %s%s%n", i + 1, marker, modelOptions[i], rec);
            }
            System.out.printf("  " + CYAN + "%2d" + RESET + "   Custom...%n", modelOptions.length + 1);
            System.out.println();

            String modelInput = prompt(reader, "  Model (1-" + (modelOptions.length + 1) + ", Enter to keep): ");
            if (modelInput != null && !modelInput.trim().isEmpty()) {
                try {
                    int idx = Integer.parseInt(modelInput.trim()) - 1;
                    if (idx >= 0 && idx < modelOptions.length) {
                        config.put("model", modelOptions[idx]);
                    } else if (idx == modelOptions.length) {
                        String custom = promptRequired(reader, "  Custom model name: ");
                        if (custom != null) config.put("model", custom);
                    }
                } catch (NumberFormatException ignored) {}
            }
        } else {
            config.put("model", promptWithDefault(reader, "  Model name",
                    MapUtils.getString(config, "model", "")));
        }

        // Base URL for ollama/custom
        if ("ollama".equals(selectedProvider) || "custom".equals(selectedProvider)) {
            String defaultUrl = "ollama".equals(selectedProvider) ? "http://localhost:11434" : "";
            config.put("baseUrl", promptWithDefault(reader, "  Base URL",
                    MapUtils.getString(config, "baseUrl", defaultUrl)));
        }

        saveConfig(LLM_PROVIDER_CONFIG, config);
        System.out.println(GREEN + "  LLM provider configuration saved." + RESET);

        // Print property equivalents
        System.out.println();
        System.out.println("  " + DIM + "Equivalent application.properties:" + RESET);
        switch (selectedProvider) {
            case "openai":
                System.out.println("    spring.ai.openai.api-key=<your-key>");
                System.out.println("    spring.ai.openai.chat.options.model=" + MapUtils.getString(config, "model", "gpt-4o"));
                break;
            case "anthropic":
                System.out.println("    spring.ai.anthropic.api-key=<your-key>");
                System.out.println("    spring.ai.anthropic.chat.options.model=" + MapUtils.getString(config, "model", "claude-opus-4-6"));
                break;
            case "gemini":
                System.out.println("    spring.ai.vertex.ai.gemini.chat.options.model=" + MapUtils.getString(config, "model", "gemini-2.5-pro"));
                break;
            case "ollama":
                System.out.println("    spring.ai.ollama.base-url=" + MapUtils.getString(config, "baseUrl", "http://localhost:11434"));
                System.out.println("    spring.ai.ollama.chat.options.model=" + MapUtils.getString(config, "model", "llama3"));
                break;
            case "custom":
                System.out.println("    spring.ai.openai.base-url=" + MapUtils.getString(config, "baseUrl", ""));
                System.out.println("    spring.ai.openai.chat.options.model=" + MapUtils.getString(config, "model", ""));
                break;
        }
        System.out.println();
        return true;
    }

    // ── Section 8: Backup ──────────────────────────────────────────────────

    private static boolean editBackup(LineReader reader) {
        printSection("Backup Configuration");

        Map<String, Object> config = loadConfig(BACKUP_CONFIG);

        config.put("enabled", promptBoolean(reader,
                "  Backup enabled",
                MapUtils.getBoolean(config, "enabled", true)));

        if (MapUtils.getBoolean(config, "enabled", true)) {
            config.put("backupPath", promptWithDefault(reader,
                    "  Backup path",
                    MapUtils.getString(config, "backupPath", System.getProperty("user.home") + "/.kompile/backups")));
            config.put("retentionDays", promptInt(reader,
                    "  Retention period (days)",
                    MapUtils.getInt(config, "retentionDays", 7)));

            String[] intervals = {"3600000", "21600000", "43200000", "86400000"};
            String[] intervalDescs = {"1 hour", "6 hours (default)", "12 hours", "24 hours"};
            String currentInterval = MapUtils.getString(config, "fixedRateMs", "21600000");

            System.out.println();
            System.out.println(BOLD + "  Backup Interval:" + RESET);
            for (int i = 0; i < intervalDescs.length; i++) {
                String marker = intervals[i].equals(currentInterval) ? GREEN + " *" + RESET : "  ";
                System.out.printf("  " + CYAN + "%2d" + RESET + "%s %s%n", i + 1, marker, intervalDescs[i]);
            }
            System.out.println();

            String intInput = prompt(reader, "  Interval (1-4, Enter to keep): ");
            if (intInput != null && !intInput.trim().isEmpty()) {
                try {
                    int idx = Integer.parseInt(intInput.trim()) - 1;
                    if (idx >= 0 && idx < intervals.length) {
                        config.put("fixedRateMs", intervals[idx]);
                    }
                } catch (NumberFormatException ignored) {}
            }

            config.put("includeDatabase", promptBoolean(reader,
                    "  Include databases in backup",
                    MapUtils.getBoolean(config, "includeDatabase", true)));
            config.put("includeIndexes", promptBoolean(reader,
                    "  Include indexes in backup",
                    MapUtils.getBoolean(config, "includeIndexes", true)));

            String[] formats = {"COMPRESSED", "DIRECTORY"};
            config.put("format", promptBoolean(reader, "  Compressed format (tar.gz)",
                    "COMPRESSED".equals(MapUtils.getString(config, "format", "COMPRESSED")))
                    ? "COMPRESSED" : "DIRECTORY");
        }

        saveConfig(BACKUP_CONFIG, config);
        System.out.println(GREEN + "  Backup configuration saved." + RESET);
        System.out.println();
        return true;
    }

    // ── Tool Gateway Configuration ──────────────────────────────────────────

    private static boolean editToolGateway(LineReader reader) {
        printSection("Tool Gateway (LLM Evaluation)");

        // Toggle enabled in feature flags
        Map<String, Object> flags = loadConfig(FEATURE_FLAGS_CONFIG);
        boolean enabled = Boolean.TRUE.equals(flags.get("toolGatewayEnabled"));
        System.out.println("  Current status: " + (enabled ? GREEN + "ENABLED" : RED + "DISABLED") + RESET);
        String toggle = prompt(reader, "  Enable tool gateway? [" + (enabled ? "Y/n" : "y/N") + "]: ");
        if (!toggle.isBlank()) {
            enabled = toggle.toLowerCase().startsWith("y");
            flags.put("toolGatewayEnabled", enabled);
            saveConfig(FEATURE_FLAGS_CONFIG, flags);
        }

        if (!enabled) {
            System.out.println(DIM + "  Gateway disabled — skipping remaining settings." + RESET);
            System.out.println();
            return true;
        }

        // Load gateway config
        Map<String, Object> config = loadConfig("tool-gateway-config.json");

        // Model source
        String currentSource = config.getOrDefault("modelSource", "STAGING").toString();
        System.out.println("  Model source: " + BOLD + currentSource + RESET);
        System.out.println("    STAGING = kompile-model-staging (local inference)");
        System.out.println("    GLOBAL  = application's configured LLM provider");
        String source = prompt(reader, "  Model source [" + currentSource + "]: ");
        if (!source.isBlank()) {
            config.put("modelSource", source.toUpperCase());
        }

        // Behavior settings
        boolean failOpen = Boolean.TRUE.equals(config.getOrDefault("failOpen", true));
        String fo = prompt(reader, "  Fail open (allow on error)? [" + (failOpen ? "Y/n" : "y/N") + "]: ");
        if (!fo.isBlank()) config.put("failOpen", fo.toLowerCase().startsWith("y"));

        boolean dryRun = Boolean.TRUE.equals(config.getOrDefault("dryRun", false));
        String dr = prompt(reader, "  Dry-run mode (log only, no blocking)? [" + (dryRun ? "Y/n" : "y/N") + "]: ");
        if (!dr.isBlank()) config.put("dryRun", dr.toLowerCase().startsWith("y"));

        boolean verbose = Boolean.TRUE.equals(config.getOrDefault("verboseLogging", false));
        String vl = prompt(reader, "  Verbose logging? [" + (verbose ? "Y/n" : "y/N") + "]: ");
        if (!vl.isBlank()) config.put("verboseLogging", vl.toLowerCase().startsWith("y"));

        boolean judgeScoring = Boolean.TRUE.equals(config.getOrDefault("judgeScoringEnabled", false));
        String js = prompt(reader, "  Judge quality scoring? [" + (judgeScoring ? "Y/n" : "y/N") + "]: ");
        if (!js.isBlank()) config.put("judgeScoringEnabled", js.toLowerCase().startsWith("y"));

        saveConfig("tool-gateway-config.json", config);
        System.out.println(GREEN + "  Tool gateway configuration saved." + RESET);
        System.out.println();
        return true;
    }

    // ── Show All Configuration ─────────────────────────────────────────────

    static void showAllConfig() {
        System.out.println();
        printHeader("Current Configuration");

        showSection("Vector Store & Indexing", APP_INDEX_CONFIG, new String[]{
                "vectorStoreType", "vectorStorePath", "keywordIndexPath",
                "subprocessEnabled", "subprocessHeapSize",
                "indexBatchSize", "embeddingTargetBatchSize", "adaptiveBatchSize",
                "embeddingModelId"
        });

        showSection("Model Roles", MODEL_ROLES_CONFIG, new String[]{
                "denseRetrievalModel", "sparseRetrievalModel", "rerankingModel",
                "rerankingEnabled", "hybridEnabled", "hybridDenseWeight", "rerankingTopK"
        });

        showSection("Feature Flags", FEATURE_FLAGS_CONFIG, new String[]{
                "guardrailsInputEnabled", "guardrailsOutputEnabled",
                "queryTransformationEnabled", "toolGatewayEnabled",
                "evaluationEnabled", "kvCacheEnabled",
                "contextualRagEnabled", "graphExtractionEnabled"
        });

        showSection("LLM Provider", LLM_PROVIDER_CONFIG, new String[]{
                "provider", "model", "baseUrl", "apiKeySource"
        });

        showSection("Backup", BACKUP_CONFIG, new String[]{
                "enabled", "backupPath", "retentionDays", "fixedRateMs",
                "includeDatabase", "includeIndexes", "format"
        });

        showSection("Tool Gateway", "tool-gateway-config.json", new String[]{
                "modelSource", "failOpen", "evaluationTimeoutMs",
                "verboseLogging", "hotReload", "dryRun", "judgeScoringEnabled"
        });

        Map<String, Object> pipelineConfig = loadConfig("pipeline-config.json");
        if (!pipelineConfig.isEmpty()) {
            System.out.println(BOLD + "  Document Processing:" + RESET);
            System.out.println("    chunkingStrategy   = " + pipelineConfig.getOrDefault("chunkingStrategy", DIM + "(default)" + RESET));
            System.out.println("    chunkSize          = " + pipelineConfig.getOrDefault("chunkSize", DIM + "(default)" + RESET));
            System.out.println("    chunkOverlap       = " + pipelineConfig.getOrDefault("chunkOverlap", DIM + "(default)" + RESET));
            System.out.println();
        }

        System.out.println("  " + DIM + "Config directory: " + configDir() + RESET);
        System.out.println();
    }

    static void showSection(String sectionName, String configFile) {
        Map<String, Object> config = loadConfig(configFile);
        if (config.isEmpty()) {
            System.out.println(BOLD + "  " + sectionName + ":" + RESET);
            System.out.println("    " + DIM + "(not configured)" + RESET);
            System.out.println();
            return;
        }
        System.out.println(BOLD + "  " + sectionName + ":" + RESET);
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key.equals("apiKey")) {
                value = maskKey(String.valueOf(value));
            }
            if (value instanceof Map) {
                System.out.println("    " + key + ":");
                @SuppressWarnings("unchecked")
                Map<String, Object> nested = (Map<String, Object>) value;
                for (Map.Entry<String, Object> ne : nested.entrySet()) {
                    System.out.println("      " + ne.getKey() + " = " + ne.getValue());
                }
            } else {
                System.out.println("    " + key + " = " + value);
            }
        }
        System.out.println();
    }

    private static void showSection(String sectionName, String configFile, String[] keys) {
        Map<String, Object> config = loadConfig(configFile);
        if (config.isEmpty()) {
            System.out.println(BOLD + "  " + sectionName + ":" + RESET);
            System.out.println("    " + DIM + "(not configured)" + RESET);
            System.out.println();
            return;
        }
        System.out.println(BOLD + "  " + sectionName + ":" + RESET);
        for (String key : keys) {
            Object value = config.get(key);
            if (value == null) continue;
            if (key.equals("apiKey")) value = maskKey(String.valueOf(value));
            System.out.printf("    %-26s = %s%n", key, value);
        }
        // Show any nested maps (like hnsw)
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            if (entry.getValue() instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nested = (Map<String, Object>) entry.getValue();
                System.out.println("    " + entry.getKey() + ":");
                for (Map.Entry<String, Object> ne : nested.entrySet()) {
                    System.out.printf("      %-22s = %s%n", ne.getKey(), ne.getValue());
                }
            }
        }
        System.out.println();
    }

    // ── Config file I/O ────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    static Map<String, Object> loadConfig(String filename) {
        Path path = configDir().resolve(filename);
        if (!Files.exists(path)) return new LinkedHashMap<>();
        try {
            return MAPPER.readValue(path.toFile(), Map.class);
        } catch (Exception e) {
            System.err.println("  " + RED + "Warning: could not parse " + filename + ": " + e.getMessage() + RESET);
            return new LinkedHashMap<>();
        }
    }

    public static void saveConfig(String filename, Map<String, Object> config) {
        Path path = configDir().resolve(filename);
        try {
            Files.createDirectories(path.getParent());
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), config);
        } catch (IOException e) {
            System.err.println("  " + RED + "Error saving " + filename + ": " + e.getMessage() + RESET);
        }
    }

    /**
     * Save a config file to a specific directory (for per-project configs).
     */
    public static void saveConfigTo(Path configDirectory, String filename, Map<String, Object> config) {
        Path path = configDirectory.resolve(filename);
        try {
            Files.createDirectories(path.getParent());
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), config);
        } catch (IOException e) {
            System.err.println("  " + RED + "Error saving " + filename + ": " + e.getMessage() + RESET);
        }
    }

    static void resetConfig(String filename) {
        Path path = configDir().resolve(filename);
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            System.err.println("  " + RED + "Error deleting " + filename + ": " + e.getMessage() + RESET);
        }
    }

    // ── Prompt helpers ─────────────────────────────────────────────────────

    private static String prompt(LineReader reader, String text) {
        try {
            return reader.readLine(text);
        } catch (Exception e) {
            return null;
        }
    }

    private static String promptRequired(LineReader reader, String text) {
        while (true) {
            String input = prompt(reader, text);
            if (input == null) return null;
            if (!input.trim().isEmpty()) return input.trim();
            System.out.println("  " + YELLOW + "This field is required." + RESET);
        }
    }

    private static String promptWithDefault(LineReader reader, String label, String defaultValue) {
        String display = (defaultValue == null || defaultValue.isEmpty()) ? "" : defaultValue;
        String input = prompt(reader, "  " + label + (display.isEmpty() ? ": " : " [" + display + "]: "));
        if (input == null || input.trim().isEmpty()) return defaultValue != null ? defaultValue : "";
        return input.trim();
    }

    private static boolean promptBoolean(LineReader reader, String label, boolean defaultValue) {
        String defStr = defaultValue ? "Y/n" : "y/N";
        String input = prompt(reader, "  " + label + " [" + defStr + "]: ");
        if (input == null || input.trim().isEmpty()) return defaultValue;
        return input.trim().toLowerCase().startsWith("y");
    }

    private static int promptInt(LineReader reader, String label, int defaultValue) {
        String input = prompt(reader, "  " + label + " [" + defaultValue + "]: ");
        if (input == null || input.trim().isEmpty()) return defaultValue;
        try {
            return Integer.parseInt(input.trim());
        } catch (NumberFormatException e) {
            System.out.println("  " + YELLOW + "Invalid number, using default: " + defaultValue + RESET);
            return defaultValue;
        }
    }

    private static double promptDouble(LineReader reader, String label, double defaultValue) {
        String input = prompt(reader, "  " + label + " [" + defaultValue + "]: ");
        if (input == null || input.trim().isEmpty()) return defaultValue;
        try {
            return Double.parseDouble(input.trim());
        } catch (NumberFormatException e) {
            System.out.println("  " + YELLOW + "Invalid number, using default: " + defaultValue + RESET);
            return defaultValue;
        }
    }

    // ── Display helpers ────────────────────────────────────────────────────

    static void printHeader(String title) {
        System.out.println();
        System.out.println(BOLD + CYAN + "  ╭──────────────────────────────────────╮" + RESET);
        System.out.printf( BOLD + CYAN + "  │  %-36s│%n" + RESET, title);
        System.out.println(BOLD + CYAN + "  ╰──────────────────────────────────────╯" + RESET);
        System.out.println();
    }

    private static void printSection(String title) {
        System.out.println();
        System.out.println(BOLD + "  ── " + title + " ──" + RESET);
        System.out.println();
    }

    private static void closeTerminal(Terminal terminal) {
        if (terminal != null) {
            try { terminal.close(); } catch (Exception ignored) {}
        }
    }

    private static String getEnvVarForProvider(String provider) {
        return switch (provider) {
            case "openai" -> "OPENAI_API_KEY";
            case "anthropic" -> "ANTHROPIC_API_KEY";
            case "gemini" -> "GOOGLE_API_KEY";
            case "custom" -> "OPENAI_API_KEY";
            default -> null;
        };
    }

    private static String maskKey(String key) {
        if (key == null || key.length() <= 8) return "****";
        return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
    }

    /**
     * Maps a section name to its config file name.
     */
    static String sectionToConfigFile(String section) {
        return switch (section.toLowerCase()) {
            case "vectorstore", "embedding", "ingestion", "subprocess" -> APP_INDEX_CONFIG;
            case "model-roles" -> MODEL_ROLES_CONFIG;
            case "feature-flags" -> FEATURE_FLAGS_CONFIG;
            case "llm-provider" -> LLM_PROVIDER_CONFIG;
            case "backup" -> BACKUP_CONFIG;
            case "tool-gateway" -> "tool-gateway-config.json";
            case "pipeline" -> "pipeline-config.json";
            case "embedding-restart" -> "embedding-restart-config.json";
            default -> null;
        };
    }

    /**
     * Maps a dot-path key to the config file and JSON key. Used by --set.
     * Format: "section.key" e.g. "vectorstore.vectorStoreType"
     */
    static String[] resolveSetKey(String dotPath) {
        int dot = dotPath.indexOf('.');
        if (dot < 0) return null;
        String section = dotPath.substring(0, dot);
        String key = dotPath.substring(dot + 1);
        String file = sectionToConfigFile(section);
        if (file == null) return null;
        return new String[]{file, key};
    }
}
