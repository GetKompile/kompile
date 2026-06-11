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
import ai.kompile.cli.main.graph.CliExtractionLlmClient;
import ai.kompile.cli.main.graph.CliGraphExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Callable;

@CommandLine.Command(
        name = "crawl",
        description = "Crawl and index content from web, file, and email sources.%n%n" +
                "Manages long-running crawl jobs with pause/resume/cancel support.%n%n" +
                "Examples:%n" +
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
                CrawlCommand.CleanupCmd.class,
                CrawlCommand.SourcesCmd.class,
                CrawlWizardCmd.class
        },
        mixinStandardHelpOptions = true
)
public class CrawlCommand implements Callable<Integer> {

    @CommandLine.Mixin
    private AppClientMixin app;

    @Override
    public Integer call() {
        new CommandLine(this).usage(System.out);
        return 0;
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
                description = "Enable knowledge graph extraction")
        private boolean graphExtraction;

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
                description = "Named schema preset to load entity/relationship types from (e.g., fpna-cpg-channel-v1)")
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

        @CommandLine.Option(names = {"--watch", "-w"},
                description = "Watch job progress until completion")
        private boolean watch;

        @Override
        public Integer call() {
            KompileHttpClient client = app.requireClient();
            if (client == null) return 1;

            // --vlm-model implies --multimodal
            if (vlmModel != null) multimodal = true;
            // --schema-preset implies --graph
            if (schemaPresetId != null) graphExtraction = true;
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

            // Graph extraction config
            if (graphExtraction) {
                Map<String, Object> graphConfig = new LinkedHashMap<>();
                // When local mode is active, disable server-side extraction
                graphConfig.put("enabled", !graphLocal);
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
            }

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
                    line = String.format("  %-12s  Loaded: %-6d  Indexed: %-6d  Entities: %-6d  Errors: %d",
                            status, loaded, indexed, entities, errors);
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

            // Fetch both standard and unified crawl jobs
            List<JsonNode> allJobs = new ArrayList<>();

            try {
                String crawlerResponse = client.getString("/api/crawlers/jobs" + suffix);
                JsonNode crawlerJobs = client.getObjectMapper().readTree(crawlerResponse);
                if (crawlerJobs.isArray()) {
                    for (JsonNode j : crawlerJobs) allJobs.add(j);
                }
            } catch (IOException ignored) {
                // Crawler endpoint may not be available
            }

            try {
                String unifiedResponse = client.getString("/api/unified-crawl/jobs" + suffix);
                JsonNode unifiedJobs = client.getObjectMapper().readTree(unifiedResponse);
                if (unifiedJobs.isArray()) {
                    for (JsonNode j : unifiedJobs) allJobs.add(j);
                }
            } catch (IOException ignored) {
                // Unified crawl endpoint may not be available
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
