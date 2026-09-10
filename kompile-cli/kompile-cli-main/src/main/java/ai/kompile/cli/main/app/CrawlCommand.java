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
import ai.kompile.cli.common.logs.LogPaths;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.graph.CliExtractionLlmClient;
import ai.kompile.cli.main.graph.CliGraphExtractor;
import ai.kompile.utils.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.Callable;

@CommandLine.Command(
        name = "crawl",
        description = "Crawl and index content from web, file, and email sources.%n%n" +
                "Running `kompile app crawl` with no subcommand starts the interactive crawl%n" +
                "wizard (source selection, indexing, graph extraction, model and presets).%n%n" +
                "Examples:%n" +
                "  kompile app crawl                      # interactive wizard%n" +
                "  kompile app crawl start --url=https://docs.example.com --depth=2%n" +
                "  kompile app crawl status%n" +
                "  kompile app crawl pause <crawlId>%n" +
                "  kompile app crawl sources%n",
        subcommands = {
                CrawlCommand.StartCmd.class,
                CrawlCommand.StatusCmd.class,
                CrawlCommand.PauseCmd.class,
                CrawlCommand.ResumeCmd.class,
                CrawlCommand.CancelCmd.class,
                CrawlCommand.LogsCmd.class,
                CrawlCommand.TailCmd.class,
                CrawlCommand.CleanupCmd.class,
                CrawlCommand.SourcesCmd.class,
                CrawlWizardCmd.class
        },
        mixinStandardHelpOptions = true
)
public class CrawlCommand implements Callable<Integer> {

    private static final ObjectMapper JSON = JsonUtils.standardMapper();

    @CommandLine.Mixin
    private AppClientMixin app;

    @Override
    public Integer call() {
        return new CommandLine(new CrawlWizardCmd()).execute();
    }

    // -----------------------------------------------------------------------
    // crawl start
    // -----------------------------------------------------------------------

    @CommandLine.Command(name = "start",
            description = "Start crawling one or more sources (URLs, directories, files)",
            mixinStandardHelpOptions = true)
    static class StartCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @CommandLine.Parameters(arity = "1..*",
                description = "Source(s) to crawl: URLs, directory paths, or file paths")
        private List<String> sources;

        // --- crawl behavior ---

        @CommandLine.Option(names = {"--depth", "-d"}, defaultValue = "3",
                description = "Max crawl depth (default: ${DEFAULT-VALUE})")
        private int maxDepth;

        @CommandLine.Option(names = {"--max-docs", "-n"}, defaultValue = "0",
                description = "Max documents to process, 0 = unlimited (default: ${DEFAULT-VALUE})")
        private int maxDocuments;

        @CommandLine.Option(names = {"--same-domain"}, defaultValue = "true", negatable = true,
                description = "Restrict web crawl to the seed domain (default: true)")
        private boolean sameDomain;

        @CommandLine.Option(names = {"--robots"}, defaultValue = "true", negatable = true,
                description = "Respect robots.txt (default: true)")
        private boolean robots;

        @CommandLine.Option(names = {"--delay"}, defaultValue = "500",
                description = "Delay between requests in milliseconds (default: ${DEFAULT-VALUE})")
        private int delayMs;

        @CommandLine.Option(names = {"--timeout"}, defaultValue = "60",
                description = "Overall job timeout in minutes (default: ${DEFAULT-VALUE})")
        private int timeoutMin;

        // --- filtering ---

        @CommandLine.Option(names = {"--include"}, split = ",",
                description = "Include patterns (glob/regex, comma-separated)")
        private List<String> includePatterns;

        @CommandLine.Option(names = {"--exclude"}, split = ",",
                description = "Exclude patterns (glob/regex, comma-separated)")
        private List<String> excludePatterns;

        @CommandLine.Option(names = {"--content-types"}, split = ",",
                description = "Allowed MIME types (comma-separated)")
        private List<String> contentTypes;

        // --- processing ---

        @CommandLine.Option(names = {"--chunker"},
                description = "Text chunker to use")
        private String chunker;

        @CommandLine.Option(names = {"--loader"},
                description = "Document loader to use")
        private String loader;

        @CommandLine.Option(names = {"--collection"},
                description = "Vector store collection name")
        private String collection;

        // --- multimodal ---

        @CommandLine.Option(names = {"--multimodal", "--vlm"},
                description = "Enable multimodal processing: route PDFs/images to VLM, spreadsheets to table-aware pipeline")
        private boolean multimodal;

        @CommandLine.Option(names = {"--vlm-model"},
                description = "VLM model ID for visual content (implies --multimodal)")
        private String vlmModel;

        // --- graph extraction ---

        @CommandLine.Option(names = {"--graph"},
                description = "Compatibility flag; knowledge graph extraction is always enabled")
        private boolean graphExtraction = true;

        @CommandLine.Option(names = {"--graph-entities"}, split = ",",
                description = "Entity types for graph extraction (comma-separated)")
        private List<String> graphEntityTypes;

        @CommandLine.Option(names = {"--graph-relations"}, split = ",",
                description = "Relationship types for graph extraction (comma-separated)")
        private List<String> graphRelationTypes;

        @CommandLine.Option(names = {"--graph-model-provider"},
                description = "LLM provider for graph extraction (e.g., openai, anthropic)")
        private String graphModelProvider;

        @CommandLine.Option(names = {"--graph-model-name"},
                description = "LLM model for graph extraction (e.g., gpt-4, claude-3-5-sonnet)")
        private String graphModelName;

        @CommandLine.Option(names = {"--graph-temperature"},
                description = "LLM temperature for graph extraction (0.0-2.0)")
        private Double graphTemperature;

        @CommandLine.Option(names = {"--graph-min-confidence"},
                description = "Minimum confidence for extracted triples (0.0-1.0)")
        private Double graphMinConfidence;

        @CommandLine.Option(names = {"--graph-auto-accept"},
                description = "Auto-accept proposals above threshold")
        private Boolean graphAutoAccept;

        @CommandLine.Option(names = {"--graph-auto-accept-threshold"},
                description = "Auto-accept confidence threshold (0.0-1.0, default: 0.5)")
        private Double graphAutoAcceptThreshold;

        @CommandLine.Option(names = {"--graph-schema-mode"},
                description = "Schema enforcement: NONE, LENIENT, STRICT")
        private String graphSchemaMode;

        @CommandLine.Option(names = {"--schema-preset"},
                description = "Named schema preset used to load entity and relationship types")
        private String schemaPresetId;

        @CommandLine.Option(names = {"--graph-prompt"},
                description = "Custom extraction prompt for graph LLM")
        private String graphCustomPrompt;

        @CommandLine.Option(names = {"--graph-local"},
                description = "Run graph extraction locally using CLI LLM provider instead of kompile-app")
        private boolean graphLocal;

        @CommandLine.Option(names = {"--graph-auto-start"},
                description = "Auto-start a local model server for graph extraction if no provider available (implies --graph-local)")
        private boolean graphAutoStart;

        // --- directory crawl options ---

        @CommandLine.Option(names = {"--follow-links"},
                description = "Follow href links in HTML files (directory crawl only)")
        private boolean followLinks;

        @CommandLine.Option(names = {"--include-hidden"},
                description = "Include hidden files and directories")
        private boolean includeHidden;

        // --- source type override ---

        @CommandLine.Option(names = {"--type"},
                description = "Force source type: web, directory, file, excel")
        private String sourceType;

        // --- output / UX ---

        @CommandLine.Option(names = {"--name"},
                description = "Human-readable job name")
        private String jobName;

        @CommandLine.Option(names = {"--fact-sheet"},
                description = "Name of the fact sheet to register crawled documents in")
        private String factSheetName;

        @CommandLine.Option(names = {"--language-detection"},
                description = "Enable language detection preprocessing and persist canonical language metadata")
        private boolean languageDetection;

        @CommandLine.Option(names = {"--translate-to"},
                description = "Translate detected non-target-language documents to this target language during preprocessing")
        private String translateToLanguage;

        @CommandLine.Option(names = {"--translation-dual-index"},
                description = "Keep original-language documents alongside translated documents")
        private boolean translationDualIndex;

        @CommandLine.Option(names = {"--watch", "-w"},
                description = "Watch job progress until completion")
        private boolean watch;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;

            // --vlm-model implies --multimodal
            if (vlmModel != null) multimodal = true;
            graphExtraction = true;
            // --graph-auto-start implies --graph-local
            if (graphAutoStart) graphLocal = true;

            try {
                int result = startUnifiedCrawl(client);
                // If graph extraction is local, run it post-crawl
                if (result == 0 && graphExtraction && graphLocal) {
                    return runLocalGraphExtraction(client);
                }
                return result;
            } catch (IOException e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 1;
            }
        }

        private int runLocalGraphExtraction(KompileHttpClient client) {
            try (CliExtractionLlmClient llm = CliExtractionLlmClient.resolve(
                    graphModelProvider, graphModelName, null, graphAutoStart)) {
                if (llm == null) {
                    System.err.println("Warning: No LLM available for local graph extraction.");
                    System.err.println("Configure a provider with: kompile chat setup");
                    System.err.println("Or use --graph-auto-start to download and run a local model.");
                    return 1;
                }

                System.out.println();
                System.out.println("Running local graph extraction...");
                System.out.println("LLM: " + llm.getResolvedFrom());

                CliGraphExtractor extractor = new CliGraphExtractor(llm);
                if (graphEntityTypes != null) extractor.setEntityTypes(graphEntityTypes);
                if (graphRelationTypes != null) extractor.setRelationshipTypes(graphRelationTypes);
                if (graphMinConfidence != null) extractor.setMinConfidence(graphMinConfidence);
                if (graphCustomPrompt != null) extractor.setCustomPrompt(graphCustomPrompt);

                // Extract from local file sources
                int totalEntities = 0;
                int totalRelations = 0;
                for (String source : sources) {
                    Path path = Paths.get(source);
                    if (Files.isRegularFile(path)) {
                        System.out.println("  Extracting from: " + path.getFileName());
                        String text = Files.readString(path, StandardCharsets.UTF_8);
                        // Chunk large files
                        List<String> chunks = chunkText(text, 4000);
                        for (String chunk : chunks) {
                            CliGraphExtractor.ExtractionResult result = extractor.extract(chunk);
                            totalEntities += result.entityCount();
                            totalRelations += result.relationCount();

                            // Persist to server if available
                            if (!result.hasError() && (result.entityCount() > 0 || result.relationCount() > 0)) {
                                try {
                                    extractor.extractAndPersist(chunk, client);
                                } catch (Exception e) {
                                    System.err.println("  Warning: Could not persist chunk: " + e.getMessage());
                                }
                            }
                        }
                    } else if (Files.isDirectory(path)) {
                        // For directories, process text files
                        try (var walker = Files.walk(path)) {
                            var files = walker.filter(Files::isRegularFile)
                                    .filter(p -> isTextFile(p.toString()))
                                    .toList();
                            for (Path f : files) {
                                System.out.println("  Extracting from: " + f.getFileName());
                                String text = Files.readString(f, StandardCharsets.UTF_8);
                                List<String> chunks = chunkText(text, 4000);
                                for (String chunk : chunks) {
                                    CliGraphExtractor.ExtractionResult result = extractor.extract(chunk);
                                    totalEntities += result.entityCount();
                                    totalRelations += result.relationCount();

                                    if (!result.hasError() && (result.entityCount() > 0 || result.relationCount() > 0)) {
                                        try {
                                            extractor.extractAndPersist(chunk, client);
                                        } catch (Exception e) {
                                            // Silently continue — server may not be available
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // Web sources handled server-side; local extraction only for files
                }

                System.out.println();
                System.out.println("Local graph extraction complete.");
                OutputFormatter.printKv("Total entities", totalEntities);
                OutputFormatter.printKv("Total relationships", totalRelations);
                return 0;
            } catch (Exception e) {
                System.err.println("Error during local graph extraction: " + e.getMessage());
                return 1;
            }
        }

        private static List<String> chunkText(String text, int maxChunkSize) {
            List<String> chunks = new ArrayList<>();
            if (text.length() <= maxChunkSize) {
                chunks.add(text);
                return chunks;
            }
            // Simple paragraph-boundary chunking
            String[] paragraphs = text.split("\n\n+");
            StringBuilder current = new StringBuilder();
            for (String para : paragraphs) {
                if (current.length() + para.length() + 2 > maxChunkSize && current.length() > 0) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }
                if (current.length() > 0) current.append("\n\n");
                current.append(para);
            }
            if (current.length() > 0) chunks.add(current.toString());
            return chunks;
        }

        private static boolean isTextFile(String path) {
            String lower = path.toLowerCase();
            return lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".html")
                    || lower.endsWith(".htm") || lower.endsWith(".json") || lower.endsWith(".xml")
                    || lower.endsWith(".csv") || lower.endsWith(".log") || lower.endsWith(".rst")
                    || lower.endsWith(".adoc") || lower.endsWith(".tex");
        }

        private int startSingleCrawl(KompileHttpClient client, String source) throws IOException, InterruptedException {
            Map<String, Object> config = new LinkedHashMap<>();
            String detectedType = detectSourceType(source);

            // Set crawler ID based on detected type
            if (detectedType.equals("WEB_CRAWL")) {
                config.put("crawlerId", "web");
            } else if (detectedType.equals("EXCEL") || isExcelSource(source)) {
                config.put("crawlerId", "excel");
            } else {
                config.put("crawlerId", "html-file");
            }

            config.put("seed", source);
            config.put("maxDepth", maxDepth);
            if (maxDocuments > 0) config.put("maxDocuments", maxDocuments);
            config.put("requestDelay", Duration.ofMillis(delayMs).toString());
            config.put("timeout", Duration.ofMinutes(timeoutMin).toString());
            config.put("sameDomainOnly", sameDomain);
            config.put("respectRobotsTxt", robots);

            if (includePatterns != null && !includePatterns.isEmpty()) config.put("includePatterns", includePatterns);
            if (excludePatterns != null && !excludePatterns.isEmpty()) config.put("excludePatterns", excludePatterns);
            if (contentTypes != null && !contentTypes.isEmpty()) config.put("allowedContentTypes", contentTypes);
            if (loader != null) config.put("loaderName", loader);
            if (chunker != null) config.put("chunkerName", chunker);
            if (collection != null) config.put("collectionName", collection);

            // Directory crawl properties
            if (!detectedType.equals("WEB_CRAWL")) {
                Map<String, Object> properties = new LinkedHashMap<>();
                properties.put("followLinks", followLinks);
                properties.put("includeHidden", includeHidden);
                properties.put("extractMetadata", true);
                config.put("properties", properties);
            }

            // Multimodal pipeline setup
            if (multimodal) {
                config.put("pipelines", buildMultimodalPipelines());
                config.put("routeRules", buildMultimodalRouteRules());
                config.put("defaultPipelineId", "text");
            }

            if (factSheetName != null && !factSheetName.isBlank()) {
                config.put("factSheetName", factSheetName);
            }

            OutputFormatter.info("Starting crawl: " + source);
            OutputFormatter.info("Type: " + detectedType + (multimodal ? " (multimodal)" : ""));

            String response = client.postString("/api/crawlers/start", config);
            return handleStartResponse(client, response, false);
        }

        private int startUnifiedCrawl(KompileHttpClient client) throws IOException, InterruptedException {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("name", jobName != null ? jobName : "CLI crawl - " + sources.size() + " source(s)");

            // Build source list
            List<Map<String, Object>> sourceList = new ArrayList<>();
            for (int i = 0; i < sources.size(); i++) {
                String source = sources.get(i);
                String detectedType = detectSourceType(source);
                String type = "EXCEL".equals(detectedType) ? "FILE" : detectedType;
                Map<String, Object> srcConfig = new LinkedHashMap<>();
                srcConfig.put("label", labelForSource(source, i));
                srcConfig.put("sourceType", type);
                srcConfig.put("pathOrUrl", source);
                srcConfig.put("maxDepth", maxDepth);
                if (maxDocuments > 0) srcConfig.put("maxDocuments", maxDocuments);
                if (includePatterns != null && !includePatterns.isEmpty()) srcConfig.put("includePatterns", includePatterns);
                if (excludePatterns != null && !excludePatterns.isEmpty()) srcConfig.put("excludePatterns", excludePatterns);
                if (contentTypes != null && !contentTypes.isEmpty()) srcConfig.put("allowedContentTypes", contentTypes);

                // Directory-specific properties
                if (!type.equals("WEB_CRAWL") && !type.equals("URL")) {
                    Map<String, Object> props = new LinkedHashMap<>();
                    props.put("followLinks", followLinks);
                    props.put("includeHidden", includeHidden);
                    if ("EXCEL".equals(detectedType)) {
                        props.put("crawlerId", "excel");
                        props.put("preferredCrawlerId", "excel");
                    }
                    srcConfig.put("properties", props);
                }

                sourceList.add(srcConfig);
            }
            request.put("sources", sourceList);

            Map<String, Object> preprocessingConfig = buildPreprocessingConfig();
            if (preprocessingConfig != null) {
                request.put("preprocessing", preprocessingConfig);
            }

            // Graph extraction config. Graph extraction is mandatory; local mode may add a
            // post-crawl local pass but never disables server-side graph construction.
            Map<String, Object> graphConfig = new LinkedHashMap<>();
            graphConfig.put("enabled", true);
            if (schemaPresetId != null) graphConfig.put("schemaPresetId", schemaPresetId);
            if (graphEntityTypes != null && !graphEntityTypes.isEmpty()) graphConfig.put("entityTypes", graphEntityTypes);
            if (graphRelationTypes != null && !graphRelationTypes.isEmpty()) graphConfig.put("relationshipTypes", graphRelationTypes);
            if (graphModelProvider != null) graphConfig.put("llmProvider", graphModelProvider);
            if (graphModelName != null) graphConfig.put("modelName", graphModelName);
            if (graphTemperature != null) graphConfig.put("temperature", graphTemperature);
            if (graphMinConfidence != null) graphConfig.put("minConfidence", graphMinConfidence);
            if (graphAutoAccept != null) graphConfig.put("autoAccept", graphAutoAccept);
            if (graphAutoAcceptThreshold != null) graphConfig.put("autoAcceptThreshold", graphAutoAcceptThreshold);
            if (graphSchemaMode != null) graphConfig.put("schemaMode", graphSchemaMode);
            if (graphCustomPrompt != null) graphConfig.put("customPrompt", graphCustomPrompt);
            request.put("graphExtraction", graphConfig);

            // Vector index config
            Map<String, Object> vectorConfig = new LinkedHashMap<>();
            vectorConfig.put("enabled", true);
            if (collection != null) vectorConfig.put("collectionName", collection);
            if (chunker != null) vectorConfig.put("chunkerName", chunker);
            request.put("vectorIndex", vectorConfig);

            if (multimodal) {
                Map<String, Object> routeConfig = new LinkedHashMap<>();
                routeConfig.put("pdfRoutingMode", "AUTO");
                routeConfig.put("extractTablesFromTextPdfs", true);
                routeConfig.put("textThresholdCharsPerPage", 50);
                if (vlmModel != null) routeConfig.put("vlmModelId", vlmModel);
                request.put("processingRoute", routeConfig);
            }

            // Fact sheet scoping
            if (factSheetName != null && !factSheetName.isBlank()) {
                request.put("factSheetName", factSheetName);
            }

            OutputFormatter.info("Starting unified crawl across " + sources.size() + " source(s)"
                    + (graphExtraction ? " with graph extraction" : "")
                    + (multimodal ? " (multimodal)" : ""));
            for (String source : sources) {
                OutputFormatter.info("  " + detectSourceType(source) + ": " + source);
            }

            String response = client.postString("/api/unified-crawl/start", request);
            return handleStartResponse(client, response, true);
        }

        private Map<String, Object> buildPreprocessingConfig() {
            boolean translationEnabled = translateToLanguage != null && !translateToLanguage.isBlank();
            if (!languageDetection && !translationEnabled) {
                return null;
            }

            Map<String, Object> preprocessing = new LinkedHashMap<>();
            preprocessing.put("enabled", true);

            Map<String, Object> languageConfig = new LinkedHashMap<>();
            languageConfig.put("enabled", true);
            preprocessing.put("languageDetection", languageConfig);

            Map<String, Object> translationConfig = new LinkedHashMap<>();
            translationConfig.put("enabled", translationEnabled);
            translationConfig.put("targetLanguage", translationEnabled ? translateToLanguage.trim() : "en");
            translationConfig.put("preserveOriginal", true);
            translationConfig.put("dualIndex", translationDualIndex);
            preprocessing.put("translation", translationConfig);

            return preprocessing;
        }

        private int handleStartResponse(KompileHttpClient client, String response, boolean unified)
                throws IOException, InterruptedException {
            if (app.isJsonOutput()) {
                OutputFormatter.printJson(response);
                return 0;
            }

            JsonNode node = client.getObjectMapper().readTree(response);
            String jobId = node.path("jobId").asText(null);
            if (jobId == null || jobId.isEmpty()) {
                // Try alternate field names
                jobId = node.path("id").asText(null);
            }

            if (jobId != null) {
                System.out.println("Crawl job started.");
                OutputFormatter.printKv("Job ID", jobId);
                if (node.has("status")) OutputFormatter.printKv("Status", node.get("status").asText());
                if (node.has("name")) OutputFormatter.printKv("Name", node.get("name").asText());
            } else {
                System.out.println("Response:");
                OutputFormatter.printJson(response);
            }

            if (watch && jobId != null) {
                System.out.println();
                return watchJob(client, jobId, unified);
            }

            return 0;
        }

        private int watchJob(KompileHttpClient client, String jobId, boolean unified)
                throws IOException, InterruptedException {
            String endpoint = unified
                    ? "/api/unified-crawl/jobs/" + jobId
                    : "/api/crawlers/jobs/" + jobId;
            String lastLine = "";

            while (true) {
                String response = client.getString(endpoint);
                JsonNode job = client.getObjectMapper().readTree(response);
                String status = job.path("status").asText("UNKNOWN");

                // Build progress line
                String line;
                if (unified) {
                    int loaded = job.path("documentsLoaded").asInt(0);
                    int indexed = job.path("documentsIndexed").asInt(0);
                    int entities = job.path("entitiesExtracted").asInt(0);
                    int errors = job.path("errorCount").asInt(0);
                    String phase = StringUtils.truncateToLength(formatUnifiedPhase(job), 28);
                    String activeStep = StringUtils.truncateToLength(activePipelineStepMessage(job), 72);
                    line = String.format("  %-12s  Phase: %-28s  Loaded: %-6d  Indexed: %-6d  Entities: %-6d  Errors: %d%s",
                            status, phase, loaded, indexed, entities, errors,
                            activeStep.isBlank() ? "" : "  " + activeStep);
                } else {
                    int discovered = job.path("discovered").asInt(
                            job.path("progress").path("discovered").asInt(0));
                    int processed = job.path("processed").asInt(
                            job.path("progress").path("processed").asInt(0));
                    int failed = job.path("failed").asInt(
                            job.path("progress").path("failed").asInt(0));
                    int skipped = job.path("skipped").asInt(
                            job.path("progress").path("skipped").asInt(0));
                    int depth = job.path("currentDepth").asInt(
                            job.path("progress").path("currentDepth").asInt(0));
                    line = String.format("  %-12s  Discovered: %-6d  Processed: %-6d  Failed: %-4d  Skipped: %-4d  Depth: %d",
                            status, discovered, processed, failed, skipped, depth);
                }

                // Only print if changed to avoid flooding
                if (!line.equals(lastLine)) {
                    System.out.print("\r" + line);
                    System.out.flush();
                    lastLine = line;
                }

                if (isTerminalStatus(status)) {
                    System.out.println(); // newline after carriage return
                    System.out.println();
                    if ("COMPLETED".equals(status)) {
                        System.out.println("Crawl completed successfully.");
                    } else {
                        System.out.println("Crawl ended with status: " + status);
                        if (job.has("error")) {
                            OutputFormatter.printKv("Error", job.path("error").asText());
                        }
                    }
                    return "COMPLETED".equals(status) ? 0 : 1;
                }

                Thread.sleep(2000);
            }
        }

        private String detectSourceType(String source) {
            // Explicit override
            if (sourceType != null) {
                switch (sourceType.toLowerCase()) {
                    case "web": return "WEB_CRAWL";
                    case "url": return "URL";
                    case "directory": case "dir": return "DIRECTORY";
                    case "file": return "FILE";
                    case "excel": case "spreadsheet": return "EXCEL";
                    default: return sourceType.toUpperCase();
                }
            }

            // Auto-detect
            if (source.startsWith("http://") || source.startsWith("https://")) {
                return "WEB_CRAWL";
            }

            // Check if it's a single spreadsheet file
            if (isExcelSource(source)) {
                return "EXCEL";
            }

            Path path = Paths.get(source);
            if (Files.isDirectory(path)) {
                return "DIRECTORY";
            }
            if (Files.isRegularFile(path)) {
                return "FILE";
            }

            // If it looks like a URL without scheme, treat as web
            if (source.contains(".") && !source.contains("/") || source.startsWith("www.")) {
                return "WEB_CRAWL";
            }

            // Default to directory (might be a path that doesn't exist yet on the client but does on the server)
            return "DIRECTORY";
        }

        private static final Set<String> EXCEL_EXTENSIONS = Set.of(
                ".xls", ".xlsx", ".xlsm", ".xlsb", ".ods", ".csv", ".tsv"
        );

        private boolean isExcelSource(String source) {
            String lower = source.toLowerCase();
            for (String ext : EXCEL_EXTENSIONS) {
                if (lower.endsWith(ext)) return true;
            }
            return false;
        }

        private String labelForSource(String source, int index) {
            if (source.startsWith("http://") || source.startsWith("https://")) {
                try {
                    java.net.URI uri = java.net.URI.create(source);
                    return uri.getHost();
                } catch (Exception e) {
                    // fall through
                }
            }

            Path path = Paths.get(source);
            String name = path.getFileName() != null ? path.getFileName().toString() : source;
            if (name.length() > 30) name = name.substring(0, 30);
            return name;
        }

        private List<Map<String, Object>> buildMultimodalPipelines() {
            List<Map<String, Object>> pipelines = new ArrayList<>();

            // Standard text pipeline (default for HTML, plain text, code, etc.)
            Map<String, Object> textPipeline = new LinkedHashMap<>();
            textPipeline.put("pipelineId", "text");
            textPipeline.put("displayName", "Standard Text Pipeline");
            textPipeline.put("pipelineType", "STANDARD_TEXT");
            if (chunker != null) textPipeline.put("chunkerName", chunker);
            pipelines.add(textPipeline);

            // VLM pipeline for PDFs, images, and scanned documents
            Map<String, Object> vlmPipeline = new LinkedHashMap<>();
            vlmPipeline.put("pipelineId", "visual");
            vlmPipeline.put("displayName", "Vision/OCR Pipeline");
            vlmPipeline.put("pipelineType", "VLM");
            vlmPipeline.put("enableVlm", true);
            if (vlmModel != null) {
                Map<String, Object> opts = new LinkedHashMap<>();
                opts.put("vlmModel", vlmModel);
                vlmPipeline.put("options", opts);
            }
            pipelines.add(vlmPipeline);

            // Table-aware pipeline for spreadsheets and CSV
            Map<String, Object> tablePipeline = new LinkedHashMap<>();
            tablePipeline.put("pipelineId", "tables");
            tablePipeline.put("displayName", "Table-Aware Pipeline");
            tablePipeline.put("pipelineType", "TABLE_AWARE");
            pipelines.add(tablePipeline);

            // Email/messaging pipeline for structured communications
            Map<String, Object> emailPipeline = new LinkedHashMap<>();
            emailPipeline.put("pipelineId", "email");
            emailPipeline.put("displayName", "Email & Messaging Pipeline");
            emailPipeline.put("pipelineType", "STANDARD_TEXT");
            if (chunker != null) emailPipeline.put("chunkerName", chunker);
            pipelines.add(emailPipeline);

            return pipelines;
        }

        private List<Map<String, Object>> buildMultimodalRouteRules() {
            List<Map<String, Object>> rules = new ArrayList<>();

            // Route PDFs and images to VLM pipeline
            Map<String, Object> visualRule = new LinkedHashMap<>();
            visualRule.put("pipelineId", "visual");
            visualRule.put("priority", 10);
            visualRule.put("contentTypes", List.of(
                    "application/pdf",
                    "image/png", "image/jpeg", "image/gif",
                    "image/webp", "image/tiff", "image/bmp", "image/svg+xml"
            ));
            visualRule.put("fileExtensions", List.of(
                    ".pdf", ".png", ".jpg", ".jpeg", ".gif",
                    ".bmp", ".tiff", ".tif", ".webp", ".svg"
            ));
            rules.add(visualRule);

            // Route spreadsheets and CSV to table-aware pipeline
            Map<String, Object> tableRule = new LinkedHashMap<>();
            tableRule.put("pipelineId", "tables");
            tableRule.put("priority", 20);
            tableRule.put("contentTypes", List.of(
                    "application/vnd.ms-excel",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "text/csv"
            ));
            tableRule.put("fileExtensions", List.of(".xls", ".xlsx", ".csv"));
            rules.add(tableRule);

            // Route email formats to email pipeline
            Map<String, Object> emailRule = new LinkedHashMap<>();
            emailRule.put("pipelineId", "email");
            emailRule.put("priority", 30);
            emailRule.put("contentTypes", List.of(
                    "message/rfc822", "application/mbox",
                    "application/vnd.ms-outlook"
            ));
            emailRule.put("fileExtensions", List.of(
                    ".eml", ".msg", ".mbox", ".emlx", ".pst"
            ));
            rules.add(emailRule);

            return rules;
        }

        private static boolean isTerminalStatus(String status) {
            return "COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
        }
    }

    private static String formatUnifiedPhase(JsonNode job) {
        String phase = text(job, "currentPhase");
        JsonNode activeStep = activePipelineStep(job);
        if (activeStep != null && phase.equals(text(activeStep, "stepId"))) {
            String displayName = firstNonBlank(text(activeStep, "displayName"), text(activeStep, "name"));
            if (displayName != null) {
                return displayName;
            }
        }
        return displayPhase(phase);
    }

    private static String activePipelineStepMessage(JsonNode job) {
        JsonNode activeStep = activePipelineStep(job);
        if (activeStep == null) {
            return "";
        }
        String stepName = firstNonBlank(
                text(activeStep, "displayName"),
                text(activeStep, "name"),
                displayPhase(text(activeStep, "stepId")));
        String message = firstNonBlank(text(activeStep, "message"), text(activeStep, "currentItem"));
        return message == null ? stepName : stepName + ": " + message;
    }

    private static JsonNode activePipelineStep(JsonNode job) {
        JsonNode steps = job.path("pipelineSteps");
        if (!steps.isArray()) {
            return null;
        }
        String currentPhase = text(job, "currentPhase");
        JsonNode currentPhaseStep = null;
        JsonNode runningStep = null;
        for (JsonNode step : steps) {
            String stepId = text(step, "stepId");
            String status = text(step, "status");
            boolean activeStatus = "RUNNING".equals(status) || "BACKPRESSURE".equals(status);
            if (currentPhase.equals(stepId)) {
                currentPhaseStep = step;
                if (activeStatus) {
                    return step;
                }
            }
            if (activeStatus && runningStep == null) {
                runningStep = step;
            }
        }
        return runningStep != null ? runningStep : currentPhaseStep;
    }

    private static String displayPhase(String phase) {
        if (phase == null || phase.isBlank()) {
            return "Starting";
        }
        return switch (phase) {
            case "QUEUED" -> "Queued";
            case "DISCOVERING" -> "Discovering documents";
            case "LOADING" -> "Loading documents";
            case "OCR_PROCESSING" -> "OCR processing";
            case "CONVERTING" -> "Converting documents";
            case "PREPROCESSING" -> "Document preprocessing";
            case "ROUTING" -> "Routing documents";
            case "GRAPH_PREP" -> "Preparing graph extraction";
            case "CHUNKING" -> "Chunking documents";
            case "GRAPH_EXTRACTION" -> "Extracting graph";
            case "SURFACING" -> "Publishing crawl surface";
            case "ENTITY_RESOLUTION" -> "Resolving entities";
            case "EDGE_COMPUTATION" -> "Graph edge cleanup";
            case "EMBEDDING", "INDEXING", "VECTOR_INDEXING" -> "Embedding & vector indexing";
            case "ENTITY_PARTITIONS" -> "Entity partition coverage";
            case "ENRICHMENT" -> "Post-Crawl Enrichment";
            case "LEARNING" -> "KGE Training (Learning)";
            case "COMPLETED" -> "Completed";
            case "FAILED" -> "Failed";
            case "CANCELLED" -> "Cancelled";
            case "PENDING" -> "Pending";
            case "RUNNING" -> "Running";
            case "PAUSED" -> "Paused";
            default -> titleCasePhase(phase);
        };
    }

    private static String titleCasePhase(String phase) {
        String[] parts = phase.replace('_', ' ').toLowerCase(Locale.ROOT).split(" ");
        StringBuilder builder = new StringBuilder(phase.length());
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.toString();
    }

    private static String text(JsonNode node, String field) {
        if (node == null || field == null || !node.hasNonNull(field)) {
            return "";
        }
        return node.get(field).asText("").trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static JsonNode parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private static long modifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static long sizeBytes(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String logJobId(Path file) {
        String name = file.getFileName() != null ? file.getFileName().toString() : file.toString();
        return name.endsWith(".log") ? name.substring(0, name.length() - 4) : name;
    }

    private static String shortTime(String value) {
        if (value == null || value.isBlank()) {
            return LocalTime.now().withNano(0).toString();
        }
        String text = value.trim();
        int t = text.indexOf('T');
        if (t >= 0 && text.length() >= t + 9) {
            return text.substring(t + 1, t + 9);
        }
        if (text.length() >= 19 && text.charAt(10) == ' ') {
            return text.substring(11, 19);
        }
        return StringUtils.truncateToLength(text, 19);
    }

    private static void printCrawlLogLine(String line) {
        JsonNode record = parseJson(line);
        if (record == null) {
            System.out.println(line);
            return;
        }
        String timestamp = shortTime(text(record, "timestamp"));
        String level = firstNonBlank(text(record, "level"), "INFO");
        String phase = displayPhase(text(record, "phase"));
        String message = firstNonBlank(text(record, "message"), "");
        String details = text(record, "details");
        if (!details.isBlank()) {
            message = message.isBlank() ? details : message + "  " + details;
        }
        System.out.printf("[%s] %-5s %-28s %s%n",
                timestamp,
                StringUtils.truncateToLength(level, 5),
                StringUtils.truncateToLength(phase, 28),
                message);
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String crawlProgressSummary(JsonNode snapshot) {
        if (snapshot == null || snapshot.isMissingNode() || snapshot.isNull()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        appendNumber(parts, snapshot, "documentsLoaded", "loaded");
        appendNumber(parts, snapshot, "documentsIndexed", "indexed");
        appendNumber(parts, snapshot, "entitiesExtracted", "entities");
        appendNumber(parts, snapshot, "relationshipsExtracted", "relations");
        appendNumber(parts, snapshot, "errorCount", "errors");
        return parts.isEmpty() ? "" : "  " + String.join(" ", parts);
    }

    private static void appendNumber(List<String> parts, JsonNode node, String field, String label) {
        if (node != null && node.has(field) && node.get(field).canConvertToLong()) {
            parts.add(label + "=" + node.get(field).asLong());
        }
    }

    // -----------------------------------------------------------------------
    // crawl status [jobId]
    // -----------------------------------------------------------------------

    @CommandLine.Command(name = "status",
            description = "Show crawl job status (or list all jobs if no ID given)",
            mixinStandardHelpOptions = true)
    static class StatusCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @CommandLine.Parameters(index = "0", arity = "0..1",
                description = "Job ID (omit to list all)")
        private String jobId;

        @CommandLine.Option(names = {"--unified", "-u"},
                description = "Query unified crawl jobs instead of standard crawler jobs")
        private boolean unified;

        @CommandLine.Option(names = {"--active"},
                description = "Show only active (running/paused) jobs")
        private boolean activeOnly;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                if (jobId != null) {
                    return showJobDetail(client);
                } else {
                    return listJobs(client);
                }
            } catch (IOException e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 1;
            }
        }

        private int showJobDetail(KompileHttpClient client) throws IOException, InterruptedException {
            // Try standard crawler endpoint first, fall back to unified
            String response = null;
            boolean isUnified = unified;

            if (!isUnified) {
                try {
                    response = client.getString("/api/crawlers/jobs/" + jobId);
                } catch (IOException e) {
                    // May be a unified crawl job, try that endpoint
                    isUnified = true;
                }
            }
            if (isUnified || response == null) {
                response = client.getString("/api/unified-crawl/jobs/" + jobId);
            }

            if (app.isJsonOutput()) {
                OutputFormatter.printJson(response);
                return 0;
            }

            JsonNode job = client.getObjectMapper().readTree(response);
            System.out.println("Crawl Job: " + jobId);
            OutputFormatter.printKv("Status", job.path("status").asText());
            if (job.has("name")) OutputFormatter.printKv("Name", job.path("name").asText());

            if (isUnified) {
                OutputFormatter.printKv("Phase", formatUnifiedPhase(job));
                String activeStep = activePipelineStepMessage(job);
                if (!activeStep.isBlank()) {
                    OutputFormatter.printKv("Active Step", activeStep);
                }
                OutputFormatter.printKv("Docs Loaded", job.path("documentsLoaded"));
                OutputFormatter.printKv("Docs Indexed", job.path("documentsIndexed"));
                OutputFormatter.printKv("Entities", job.path("entitiesExtracted"));
                OutputFormatter.printKv("Relationships", job.path("relationshipsExtracted"));
                OutputFormatter.printKv("Errors", job.path("errorCount"));
            } else {
                JsonNode progress = job.has("progress") ? job.get("progress") : job;
                OutputFormatter.printKv("Discovered", progress.path("discovered"));
                OutputFormatter.printKv("Processed", progress.path("processed"));
                OutputFormatter.printKv("Failed", progress.path("failed"));
                OutputFormatter.printKv("Skipped", progress.path("skipped"));
                OutputFormatter.printKv("Current Depth", progress.path("currentDepth"));
                if (progress.has("currentItem")) {
                    OutputFormatter.printKv("Current Item", progress.path("currentItem").asText());
                }
            }

            if (job.has("startTime")) OutputFormatter.printKv("Started", job.path("startTime").asText());
            if (job.has("endTime") && !job.path("endTime").isNull()) {
                OutputFormatter.printKv("Ended", job.path("endTime").asText());
            }
            if (job.has("error") && !job.path("error").isNull()) {
                OutputFormatter.printKv("Error", job.path("error").asText());
            }

            // Show per-source progress for unified crawls
            if (job.has("sourceProgress") && job.get("sourceProgress").isArray()) {
                System.out.println();
                System.out.println("Per-source progress:");
                OutputFormatter.printTable(job.get("sourceProgress"),
                        "label", "sourceType", "status", "documentsLoaded", "errorCount");
            }

            return 0;
        }

        private int listJobs(KompileHttpClient client) throws IOException, InterruptedException {
            String suffix = activeOnly ? "/active" : "";

            // Two job registries, either of which may legitimately be empty or absent, so one
            // failing is tolerated. Remember which refused, though: since crawl moved onto its own
            // service, ALL of them refusing means this server has no crawl API at all — usually
            // --url pinned at the admin console. Reporting that as "no crawl jobs" hides the
            // mistake behind a plausible answer, which is the worst way to fail.
            List<String> endpoints = List.of("/api/crawlers/jobs" + suffix,
                    "/api/unified-crawl/jobs" + suffix);
            List<JsonNode> allJobs = new ArrayList<>();
            Map<String, String> refused = new LinkedHashMap<>();

            for (String path : endpoints) {
                try {
                    JsonNode jobs = client.getObjectMapper().readTree(client.getString(path));
                    if (jobs.isArray()) {
                        for (JsonNode j : jobs) allJobs.add(j);
                    }
                } catch (IOException e) {
                    refused.put(path, e.getMessage());
                }
            }

            if (refused.size() == endpoints.size()) {
                System.err.println("Error: this server does not serve the crawl API.");
                for (Map.Entry<String, String> failure : refused.entrySet()) {
                    System.err.println("  " + client.urlFor(failure.getKey()) + " -> " + failure.getValue());
                }
                System.err.println("Crawl and indexing live on kompile-app-crawl-manager (default :8082); "
                        + "kompile-app-main serves only the admin API.");
                return 1;
            }

            if (app.isJsonOutput()) {
                OutputFormatter.printJson(client.getObjectMapper()
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(allJobs));
                return 0;
            }

            if (allJobs.isEmpty()) {
                System.out.println(activeOnly ? "No active crawl jobs." : "No crawl jobs found.");
                return 0;
            }

            System.out.println("Crawl Jobs:");
            JsonNode array = client.getObjectMapper().valueToTree(allJobs);
            OutputFormatter.printTable(array, "jobId", "status", "name", "startTime");
            return 0;
        }
    }

    // -----------------------------------------------------------------------
    // crawl logs [jobId]
    // -----------------------------------------------------------------------

    @CommandLine.Command(name = "logs",
            aliases = {"log"},
            description = "Read retained crawl JSONL logs from ~/.kompile/logs/crawls",
            mixinStandardHelpOptions = true)
    static class LogsCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @CommandLine.Parameters(index = "0", arity = "0..1",
                description = "Job ID (omit to list local crawl log files)")
        private String jobId;

        @CommandLine.Option(names = {"-n", "--lines"}, defaultValue = "50",
                description = "Number of recent lines to show (default: ${DEFAULT-VALUE})")
        private int lines;

        @CommandLine.Option(names = "--all",
                description = "Show all retained log lines for the job")
        private boolean all;

        @CommandLine.Option(names = "--raw",
                description = "Print raw JSONL instead of formatted log lines")
        private boolean raw;

        @Override
        public Integer call() {
            try {
                if (jobId == null || jobId.isBlank()) {
                    return listLocalLogs();
                }
                return showLocalLog();
            } catch (IOException e) {
                System.err.println("Error reading crawl logs: " + e.getMessage());
                return 1;
            }
        }

        private int listLocalLogs() throws IOException {
            Path dir = LogPaths.crawlsRoot().toPath();
            if (!Files.isDirectory(dir)) {
                System.out.println("No crawl logs found at " + dir);
                return 0;
            }

            List<Path> files;
            try (var stream = Files.list(dir)) {
                files = stream
                        .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".log"))
                        .sorted(Comparator.comparingLong(CrawlCommand::modifiedMillis).reversed())
                        .toList();
            }

            if (app.isJsonOutput()) {
                ArrayNode array = JSON.createArrayNode();
                for (Path file : files) {
                    ObjectNode node = JSON.createObjectNode();
                    node.put("jobId", logJobId(file));
                    node.put("path", file.toString());
                    node.put("sizeBytes", sizeBytes(file));
                    node.put("modifiedAt", Instant.ofEpochMilli(modifiedMillis(file)).toString());
                    array.add(node);
                }
                System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(array));
                return 0;
            }

            if (files.isEmpty()) {
                System.out.println("No crawl logs found at " + dir);
                return 0;
            }

            System.out.printf("%-44s %10s %s%n", "JOB ID", "BYTES", "MODIFIED");
            for (Path file : files) {
                System.out.printf("%-44s %10d %s%n",
                        StringUtils.truncateToLength(logJobId(file), 44),
                        sizeBytes(file),
                        Instant.ofEpochMilli(modifiedMillis(file)));
            }
            return 0;
        }

        private int showLocalLog() throws IOException {
            Path file = LogPaths.crawlLogFile(jobId).toPath();
            if (!Files.isRegularFile(file)) {
                System.err.println("No crawl log found for job '" + jobId + "' at " + file);
                return 1;
            }

            List<String> allLines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int start = all ? 0 : Math.max(0, allLines.size() - Math.max(0, lines));
            List<String> selected = allLines.subList(start, allLines.size());

            if (app.isJsonOutput()) {
                ArrayNode array = JSON.createArrayNode();
                for (String line : selected) {
                    JsonNode parsed = parseJson(line);
                    if (parsed != null) {
                        array.add(parsed);
                    } else {
                        array.add(line);
                    }
                }
                System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(array));
                return 0;
            }

            if (selected.isEmpty()) {
                System.out.println("Crawl log is empty: " + file);
                return 0;
            }
            for (String line : selected) {
                if (raw) {
                    System.out.println(line);
                } else {
                    printCrawlLogLine(line);
                }
            }
            if (!all && allLines.size() > selected.size()) {
                System.out.printf("%n... showing last %d of %d lines (%s)%n",
                        selected.size(), allLines.size(), file);
            }
            return 0;
        }
    }

    // -----------------------------------------------------------------------
    // crawl tail [jobId]
    // -----------------------------------------------------------------------

    @CommandLine.Command(name = "tail",
            description = "Tail live crawl progress events via SSE",
            mixinStandardHelpOptions = true)
    static class TailCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @CommandLine.Parameters(index = "0", arity = "0..1",
                description = "Job ID to tail (omit for all crawl jobs)")
        private String jobId;

        @CommandLine.Option(names = "--raw",
                description = "Print raw SSE data payloads")
        private boolean raw;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;

            String streamPath = (jobId == null || jobId.isBlank())
                    ? "/api/crawl-events/stream"
                    : "/api/crawl-events/stream/" + encodePathSegment(jobId);
            // urlFor, not getBaseUrl: crawl events are served by kompile-app-crawl-manager, and a
            // routed client only knows that from the path.
            String url = client.urlFor(streamPath);

            if (!app.isJsonOutput()) {
                System.out.println("Tailing crawl events from " + url + " (Ctrl+C to stop)...");
                System.out.println();
            }

            try {
                HttpClient httpClient = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Accept", "text/event-stream")
                        .GET()
                        .build();
                HttpResponse<java.io.InputStream> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() != 200) {
                    System.err.println("HTTP " + response.statusCode() + " from " + url);
                    return 1;
                }

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    String eventName = "";
                    StringBuilder data = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("event:")) {
                            eventName = line.substring(6).trim();
                        } else if (line.startsWith("data:")) {
                            if (data.length() > 0) {
                                data.append('\n');
                            }
                            data.append(line.substring(5).trim());
                        } else if (line.isBlank()) {
                            if (data.length() > 0) {
                                renderSseEvent(eventName, data.toString());
                            }
                            eventName = "";
                            data.setLength(0);
                        }
                    }
                }
                return 0;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 1;
            } catch (Exception e) {
                System.err.println("Error tailing crawl events: " + e.getMessage());
                return 1;
            }
        }

        private void renderSseEvent(String eventName, String data) throws IOException {
            if ("heartbeat".equals(eventName)) {
                return;
            }
            if (raw) {
                System.out.println(data);
                return;
            }

            JsonNode payload = parseJson(data);
            if (app.isJsonOutput()) {
                ObjectNode node = JSON.createObjectNode();
                node.put("event", eventName);
                if (payload != null) {
                    node.set("data", payload);
                } else {
                    node.put("data", data);
                }
                System.out.println(JSON.writeValueAsString(node));
                return;
            }

            if (payload == null) {
                System.out.printf("[%s] %-18s %s%n", shortTime(null), eventName, StringUtils.truncateToLength(data, 120));
                return;
            }

            JsonNode snapshot = payload.path("snapshot");
            String effectiveEvent = firstNonBlank(text(payload, "eventType"), eventName, "event");
            String effectiveJobId = firstNonBlank(text(payload, "jobId"), text(snapshot, "jobId"), text(snapshot, "id"));
            String phase = firstNonBlank(text(payload, "phase"), text(snapshot, "currentPhase"), text(snapshot, "phase"));
            String message = firstNonBlank(text(payload, "message"), text(snapshot, "message"), "");
            String progress = crawlProgressSummary(snapshot);
            String jobPart = effectiveJobId == null ? "" : " job=" + StringUtils.truncateToLength(effectiveJobId, 24);
            String phasePart = phase == null ? "" : " phase=" + StringUtils.truncateToLength(displayPhase(phase), 28);
            String messagePart = message.isBlank() ? "" : "  " + StringUtils.truncateToLength(message, 100);

            System.out.printf("[%s] %-18s%s%s%s%s%n",
                    shortTime(text(payload, "timestamp")),
                    StringUtils.truncateToLength(effectiveEvent, 18),
                    jobPart,
                    phasePart,
                    progress,
                    messagePart);
        }
    }

    // -----------------------------------------------------------------------
    // crawl pause <jobId>
    // -----------------------------------------------------------------------

    @CommandLine.Command(name = "pause", description = "Pause a running crawl job",
            mixinStandardHelpOptions = true)
    static class PauseCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @CommandLine.Parameters(index = "0", description = "Job ID to pause")
        private String jobId;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String response = client.postEmpty("/api/crawlers/jobs/" + jobId + "/pause");
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                } else {
                    System.out.println("Paused crawl job: " + jobId);
                }
                return 0;
            } catch (IOException e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 1;
            }
        }
    }

    // -----------------------------------------------------------------------
    // crawl resume <jobId>
    // -----------------------------------------------------------------------

    @CommandLine.Command(name = "resume", description = "Resume a paused crawl job",
            mixinStandardHelpOptions = true)
    static class ResumeCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @CommandLine.Parameters(index = "0", description = "Job ID to resume")
        private String jobId;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String response = client.postEmpty("/api/crawlers/jobs/" + jobId + "/resume");
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                } else {
                    System.out.println("Resumed crawl job: " + jobId);
                }
                return 0;
            } catch (IOException e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 1;
            }
        }
    }

    // -----------------------------------------------------------------------
    // crawl cancel <jobId>
    // -----------------------------------------------------------------------

    @CommandLine.Command(name = "cancel", description = "Cancel a crawl job",
            mixinStandardHelpOptions = true)
    static class CancelCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @CommandLine.Parameters(index = "0", description = "Job ID to cancel")
        private String jobId;

        @CommandLine.Option(names = {"--unified", "-u"},
                description = "Cancel a unified crawl job")
        private boolean unified;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String endpoint = unified
                        ? "/api/unified-crawl/jobs/" + jobId + "/cancel"
                        : "/api/crawlers/jobs/" + jobId + "/cancel";
                String response = client.postEmpty(endpoint);
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                } else {
                    System.out.println("Cancelled crawl job: " + jobId);
                }
                return 0;
            } catch (IOException e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 1;
            }
        }
    }

    // -----------------------------------------------------------------------
    // crawl cleanup
    // -----------------------------------------------------------------------

    @CommandLine.Command(name = "cleanup", description = "Remove completed crawl jobs",
            mixinStandardHelpOptions = true)
    static class CleanupCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                client.postEmpty("/api/crawlers/jobs/cleanup");
                try {
                    client.postEmpty("/api/unified-crawl/jobs/cleanup");
                } catch (IOException ignored) {
                    // unified crawl endpoint may not be available
                }
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson("{\"status\": \"cleaned\"}");
                } else {
                    System.out.println("Cleaned up completed crawl jobs.");
                }
                return 0;
            } catch (IOException e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 1;
            }
        }
    }

    // -----------------------------------------------------------------------
    // crawl sources
    // -----------------------------------------------------------------------

    @CommandLine.Command(name = "sources",
            description = "List available crawler types and their supported source types",
            mixinStandardHelpOptions = true)
    static class SourcesCmd implements Callable<Integer> {
        @CommandLine.Mixin
        private AppClientMixin app;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;
            try {
                String response = client.getString("/api/crawlers");
                if (app.isJsonOutput()) {
                    OutputFormatter.printJson(response);
                    return 0;
                }

                JsonNode array = client.getObjectMapper().readTree(response);
                if (!array.isArray() || array.isEmpty()) {
                    System.out.println("No crawlers registered.");
                    return 0;
                }

                System.out.println("Available Crawlers:");
                System.out.println();
                for (JsonNode crawler : array) {
                    String id = crawler.path("id").asText(crawler.path("crawlerId").asText("-"));
                    String name = crawler.path("name").asText("-");
                    String desc = crawler.path("description").asText("");
                    System.out.println("  " + id + " - " + name);
                    if (!desc.isEmpty()) {
                        System.out.println("    " + desc);
                    }
                    JsonNode sourceTypes = crawler.path("supportedSourceTypes");
                    if (sourceTypes.isArray() && !sourceTypes.isEmpty()) {
                        List<String> types = new ArrayList<>();
                        for (JsonNode t : sourceTypes) types.add(t.asText());
                        System.out.println("    Source types: " + String.join(", ", types));
                    }
                    System.out.println();
                }

                // Also show unified crawl source types if available
                try {
                    String unifiedResponse = client.getString("/api/unified-crawl/source-types");
                    JsonNode unifiedTypes = client.getObjectMapper().readTree(unifiedResponse);
                    if (unifiedTypes.isArray() && !unifiedTypes.isEmpty()) {
                        System.out.println("Unified Crawl Source Types:");
                        System.out.println();
                        for (JsonNode st : unifiedTypes) {
                            String typeName = st.path("sourceType").asText(st.path("name").asText("-"));
                            System.out.println("  " + typeName);
                            JsonNode required = st.path("requiredProperties");
                            if (required.isArray() && !required.isEmpty()) {
                                List<String> props = new ArrayList<>();
                                for (JsonNode p : required) props.add(p.asText());
                                System.out.println("    Required: " + String.join(", ", props));
                            }
                        }
                    }
                } catch (IOException ignored) {
                    // unified crawl not available
                }

                return 0;
            } catch (IOException e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 1;
            }
        }
    }
}
