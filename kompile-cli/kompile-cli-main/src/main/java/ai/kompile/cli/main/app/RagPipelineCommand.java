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

package ai.kompile.cli.main.app;

import ai.kompile.cli.common.http.KompileHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * CLI command for managing RAG pipelines on a running kompile-app instance.
 * Provides CRUD operations, model status checks, and pipeline execution.
 * <p>
 * Pipelines define the full RAG flow: embedding → retrieval → reranking → LLM generation.
 * The same pipeline definitions are used by both the CLI and the web UI.
 */
@CommandLine.Command(
        name = "rag-pipeline",
        description = "Manage RAG pipelines: create, configure, and execute retrieval-augmented generation flows.%n%n" +
                "Pipelines define the full RAG flow: embedding, retrieval, reranking, and LLM generation.%n%n" +
                "Examples:%n" +
                "  kompile app rag-pipeline list%n" +
                "  kompile app rag-pipeline templates%n" +
                "  kompile app rag-pipeline create --template=default --name=my-pipeline%n" +
                "  kompile app rag-pipeline execute --pipeline=my-pipeline --query='What is...'%n" +
                "  kompile app rag-pipeline use my-pipeline%n",
        subcommands = {
                RagPipelineCommand.ListCmd.class,
                RagPipelineCommand.TemplatesCmd.class,
                RagPipelineCommand.ShowCmd.class,
                RagPipelineCommand.CreateCmd.class,
                RagPipelineCommand.DeleteCmd.class,
                RagPipelineCommand.StatusCmd.class,
                RagPipelineCommand.ExecuteCmd.class,
                RagPipelineCommand.UseCmd.class
        },
        mixinStandardHelpOptions = true
)
public class RagPipelineCommand implements Callable<Integer> {

    @CommandLine.Mixin
    private AppClientMixin app;

    @Override
    public Integer call() {
        new CommandLine(this).usage(System.out);
        return 0;
    }

    // ── list ──────────────────────────────────────────────────────────

    @CommandLine.Command(name = "list", description = "List all pipelines (built-in and custom)", mixinStandardHelpOptions = true)
    static class ListCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String json = client.getString("/api/rag-pipelines");
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(json);
                } else {
                    JsonNode arr = client.getObjectMapper().readTree(json);
                    if (!arr.isArray() || arr.isEmpty()) {
                        System.out.println("No pipelines found.");
                        return 0;
                    }
                    System.out.println("RAG Pipelines:\n");
                    OutputFormatter.printTable(arr, "id", "name", "builtin", "enabled");
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ── templates ────────────────────────────────────────────────────

    @CommandLine.Command(name = "templates", description = "List built-in pipeline templates", mixinStandardHelpOptions = true)
    static class TemplatesCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String json = client.getString("/api/rag-pipelines/templates");
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(json);
                } else {
                    JsonNode arr = client.getObjectMapper().readTree(json);
                    if (!arr.isArray() || arr.isEmpty()) {
                        System.out.println("No templates found.");
                        return 0;
                    }
                    System.out.println("Built-in Pipeline Templates:\n");
                    for (JsonNode p : arr) {
                        printPipelineSummary(p);
                    }
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ── show ─────────────────────────────────────────────────────────

    @CommandLine.Command(name = "show", description = "Show details of a pipeline", mixinStandardHelpOptions = true)
    static class ShowCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @CommandLine.Parameters(index = "0", description = "Pipeline ID")
        private String pipelineId;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String json = client.getString("/api/rag-pipelines/" + pipelineId);
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(json);
                } else {
                    JsonNode p = client.getObjectMapper().readTree(json);
                    printPipelineDetail(p);
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ── create ───────────────────────────────────────────────────────

    @CommandLine.Command(name = "create", description = "Create a custom pipeline", mixinStandardHelpOptions = true)
    static class CreateCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @CommandLine.Option(names = "--name", required = true, description = "Pipeline name")
        private String name;

        @CommandLine.Option(names = "--description", description = "Pipeline description")
        private String description;

        @CommandLine.Option(names = "--embedding-model", description = "Embedding model ID (e.g. bge-base-en-v1.5, none)", defaultValue = "bge-base-en-v1.5")
        private String embeddingModel;

        @CommandLine.Option(names = "--embedding-source", description = "Embedding model source", defaultValue = "default")
        private String embeddingSource;

        @CommandLine.Option(names = "--retrieval-strategy", description = "Retrieval strategy: HYBRID, SEMANTIC, KEYWORD", defaultValue = "HYBRID")
        private String retrievalStrategy;

        @CommandLine.Option(names = "--retrieval-top-k", description = "Retrieval top-k", defaultValue = "10")
        private int retrievalTopK;

        @CommandLine.Option(names = "--similarity-threshold", description = "Similarity threshold (0.0-1.0)", defaultValue = "0.0")
        private double similarityThreshold;

        @CommandLine.Option(names = "--reranking-enabled", description = "Enable reranking", defaultValue = "false")
        private boolean rerankingEnabled;

        @CommandLine.Option(names = "--reranker-type", description = "Reranker type: cross_encoder, rrf, mmr, rm3, bm25prf", defaultValue = "cross_encoder")
        private String rerankerType;

        @CommandLine.Option(names = "--cross-encoder-model", description = "Cross-encoder model ID", defaultValue = "ms-marco-MiniLM-L-6-v2")
        private String crossEncoderModel;

        @CommandLine.Option(names = "--rerank-top-k", description = "Rerank top-k", defaultValue = "100")
        private int rerankTopK;

        @CommandLine.Option(names = "--llm-provider", description = "LLM provider: OPENAI, ANTHROPIC, GEMINI, LOCAL_SAMEDIFF", defaultValue = "OPENAI")
        private String llmProvider;

        @CommandLine.Option(names = "--llm-model", description = "LLM model name", defaultValue = "gpt-4")
        private String llmModel;

        @CommandLine.Option(names = "--system-prompt", description = "System prompt for LLM")
        private String systemPrompt;

        @CommandLine.Option(names = "--temperature", description = "LLM temperature", defaultValue = "0.7")
        private double temperature;

        @CommandLine.Option(names = "--max-tokens", description = "LLM max tokens", defaultValue = "1024")
        private int maxTokens;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                ObjectNode def = client.getObjectMapper().createObjectNode();
                def.put("name", name);
                if (description != null) def.put("description", description);

                ObjectNode embedding = def.putObject("embedding");
                embedding.put("modelId", embeddingModel);
                embedding.put("modelSource", embeddingSource);

                ObjectNode retrieval = def.putObject("retrieval");
                retrieval.put("strategy", retrievalStrategy.toUpperCase());
                retrieval.put("topK", retrievalTopK);
                retrieval.put("similarityThreshold", similarityThreshold);

                ObjectNode reranking = def.putObject("reranking");
                reranking.put("enabled", rerankingEnabled);
                reranking.put("rerankerType", rerankingEnabled ? rerankerType : "none");
                if (rerankingEnabled && "cross_encoder".equals(rerankerType)) {
                    reranking.put("crossEncoderModel", crossEncoderModel);
                }
                reranking.put("topK", rerankTopK);

                ObjectNode llm = def.putObject("llm");
                llm.put("provider", llmProvider.toUpperCase());
                llm.put("model", llmModel);
                if (systemPrompt != null) llm.put("systemPrompt", systemPrompt);
                llm.put("temperature", temperature);
                llm.put("maxTokens", maxTokens);

                String response = client.postString("/api/rag-pipelines", def);
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                } else {
                    JsonNode created = client.getObjectMapper().readTree(response);
                    System.out.println("Pipeline created:");
                    OutputFormatter.printKv("ID", created.path("id").asText());
                    OutputFormatter.printKv("Name", created.path("name").asText());
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ── delete ───────────────────────────────────────────────────────

    @CommandLine.Command(name = "delete", description = "Delete a custom pipeline", mixinStandardHelpOptions = true)
    static class DeleteCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @CommandLine.Parameters(index = "0", description = "Pipeline ID to delete")
        private String pipelineId;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                client.delete("/api/rag-pipelines/" + pipelineId);
                System.out.println("Pipeline '" + pipelineId + "' deleted.");
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ── status ───────────────────────────────────────────────────────

    @CommandLine.Command(name = "status", description = "Show model readiness status for a pipeline", mixinStandardHelpOptions = true)
    static class StatusCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @CommandLine.Parameters(index = "0", description = "Pipeline ID")
        private String pipelineId;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String json = client.getString("/api/rag-pipelines/" + pipelineId + "/model-status");
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(json);
                } else {
                    JsonNode status = client.getObjectMapper().readTree(json);
                    boolean allReady = status.path("allModelsReady").asBoolean();
                    System.out.println("Pipeline: " + pipelineId);
                    System.out.println("Overall:  " + (allReady ? "✓ All models ready" : "✗ Models not ready"));
                    System.out.println();

                    JsonNode reqs = status.path("requirements");
                    if (reqs.isArray()) {
                        for (JsonNode req : reqs) {
                            String icon = "ready".equals(req.path("status").asText()) ? "✓" : "✗";
                            OutputFormatter.printKv(
                                    icon + " " + req.path("stage").asText(),
                                    req.path("modelId").asText() + " (" + req.path("status").asText() + ")"
                            );
                        }
                    }
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ── execute ──────────────────────────────────────────────────────

    @CommandLine.Command(name = "execute", description = "Execute a RAG pipeline with a query", mixinStandardHelpOptions = true)
    static class ExecuteCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @CommandLine.Parameters(index = "0", description = "Pipeline ID")
        private String pipelineId;

        @CommandLine.Option(names = {"-q", "--query"}, required = true, description = "Query to execute")
        private String query;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String response = client.postString(
                        "/api/rag-pipelines/" + pipelineId + "/execute",
                        Map.of("query", query)
                );
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                } else {
                    JsonNode result = client.getObjectMapper().readTree(response);
                    String status = result.path("status").asText("unknown");
                    if ("error".equals(status)) {
                        System.err.println("Pipeline execution failed: " + result.path("errorMessage").asText());
                        return 1;
                    }
                    System.out.println("Pipeline: " + result.path("pipelineName").asText());
                    System.out.println("Documents: " + result.path("documentCount").asInt());
                    System.out.println("Duration: " + result.path("durationMs").asLong() + "ms");
                    System.out.println();
                    String resp = result.path("response").asText("");
                    if (!resp.isEmpty()) {
                        System.out.println(resp);
                    }
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ── use ──────────────────────────────────────────────────────────

    @CommandLine.Command(name = "use", description = "Set a pipeline as the active pipeline for chat", mixinStandardHelpOptions = true)
    static class UseCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @CommandLine.Parameters(index = "0", description = "Pipeline ID to activate")
        private String pipelineId;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                // Verify the pipeline exists
                String json = client.getString("/api/rag-pipelines/" + pipelineId);
                JsonNode pipeline = client.getObjectMapper().readTree(json);

                // Store the active pipeline ID in the user's kompile config
                java.nio.file.Path configDir = java.nio.file.Path.of(
                        System.getProperty("user.home"), ".kompile");
                java.nio.file.Files.createDirectories(configDir);
                java.nio.file.Path pipelineFile = configDir.resolve("active-pipeline");
                java.nio.file.Files.writeString(pipelineFile, pipelineId);

                System.out.println("Active pipeline set to: " + pipeline.path("name").asText());
                System.out.println("Pipeline ID: " + pipelineId);
                System.out.println("This pipeline will be used for 'kompile chat --pipeline active'.");
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    // ── formatting helpers ───────────────────────────────────────────

    static void printPipelineSummary(JsonNode p) {
        String id = p.path("id").asText("-");
        String name = p.path("name").asText("-");
        String desc = p.path("description").asText("");
        boolean builtin = p.path("builtin").asBoolean();

        System.out.println("  " + name + (builtin ? " [built-in]" : " [custom]"));
        System.out.println("    ID: " + id);
        if (!desc.isEmpty()) {
            System.out.println("    " + desc);
        }

        // Stage summary line
        StringBuilder stages = new StringBuilder("    Stages: ");
        JsonNode embed = p.path("embedding");
        if (!embed.isMissingNode()) {
            stages.append("embed(").append(embed.path("modelId").asText("?")).append(") → ");
        }
        JsonNode retrieval = p.path("retrieval");
        if (!retrieval.isMissingNode()) {
            stages.append(retrieval.path("strategy").asText("?").toLowerCase())
                    .append("(k=").append(retrieval.path("topK").asInt(10)).append(") → ");
        }
        JsonNode reranking = p.path("reranking");
        if (!reranking.isMissingNode() && reranking.path("enabled").asBoolean()) {
            stages.append("rerank(").append(reranking.path("rerankerType").asText("?")).append(") → ");
        }
        JsonNode llm = p.path("llm");
        if (!llm.isMissingNode()) {
            stages.append(llm.path("provider").asText("?").toLowerCase())
                    .append("/").append(llm.path("model").asText("?"));
        }
        System.out.println(stages);
        System.out.println();
    }

    static void printPipelineDetail(JsonNode p) {
        OutputFormatter.printKv("ID", p.path("id").asText());
        OutputFormatter.printKv("Name", p.path("name").asText());
        OutputFormatter.printKv("Description", p.path("description").asText("-"));
        OutputFormatter.printKv("Built-in", p.path("builtin").asBoolean());
        OutputFormatter.printKv("Enabled", p.path("enabled").asBoolean());
        System.out.println();

        JsonNode embed = p.path("embedding");
        if (!embed.isMissingNode()) {
            System.out.println("  Embedding:");
            OutputFormatter.printKv("    Model", embed.path("modelId").asText());
            OutputFormatter.printKv("    Source", embed.path("modelSource").asText("default"));
        }

        JsonNode retrieval = p.path("retrieval");
        if (!retrieval.isMissingNode()) {
            System.out.println("  Retrieval:");
            OutputFormatter.printKv("    Strategy", retrieval.path("strategy").asText());
            OutputFormatter.printKv("    Top-K", retrieval.path("topK").asInt());
            OutputFormatter.printKv("    Threshold", retrieval.path("similarityThreshold").asDouble());
        }

        JsonNode reranking = p.path("reranking");
        if (!reranking.isMissingNode()) {
            System.out.println("  Reranking:");
            OutputFormatter.printKv("    Enabled", reranking.path("enabled").asBoolean());
            if (reranking.path("enabled").asBoolean()) {
                OutputFormatter.printKv("    Type", reranking.path("rerankerType").asText());
                if ("cross_encoder".equals(reranking.path("rerankerType").asText())) {
                    OutputFormatter.printKv("    Model", reranking.path("crossEncoderModel").asText());
                }
                OutputFormatter.printKv("    Top-K", reranking.path("topK").asInt());
            }
        }

        JsonNode llm = p.path("llm");
        if (!llm.isMissingNode()) {
            System.out.println("  LLM Generation:");
            OutputFormatter.printKv("    Provider", llm.path("provider").asText());
            OutputFormatter.printKv("    Model", llm.path("model").asText());
            OutputFormatter.printKv("    Temperature", llm.path("temperature").asDouble());
            OutputFormatter.printKv("    Max Tokens", llm.path("maxTokens").asInt());
            String prompt = llm.path("systemPrompt").asText("");
            if (!prompt.isEmpty()) {
                OutputFormatter.printKv("    System Prompt", prompt.length() > 60
                        ? prompt.substring(0, 57) + "..." : prompt);
            }
        }
    }
}
