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

package ai.kompile.cli.main.codeindex;

import ai.kompile.cli.common.registry.ProjectAutoRegistrar;
import picocli.CommandLine;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Standalone local code indexer command. Indexes the current directory
 * (or a specified directory) without requiring a running kompile-app instance.
 *
 * <p>Usage:
 * <pre>
 *   kompile code-index                          # index CWD (incremental)
 *   kompile code-index /path/to/project         # index specific directory
 *   kompile code-index --force                  # force full re-index
 *   kompile code-index search "className"       # search the local index
 *   kompile code-index watch                    # watch for changes and re-index
 *   kompile code-index list                     # list indexed projects
 *   kompile code-index stats                    # show stats for default project
 * </pre>
 */
@CommandLine.Command(
        name = "code-index",
        description = "Index a codebase locally for code search (no server required)",
        subcommands = {
                CodeIndexCommand.SearchCmd.class,
                CodeIndexCommand.SpathCmd.class,
                CodeIndexCommand.FindCmd.class,
                CodeIndexCommand.ReplaceCmd.class,
                CodeIndexCommand.UsagesCmd.class,
                CodeIndexCommand.ListCmd.class,
                CodeIndexCommand.StatsCmd.class,
                CodeIndexCommand.WatchCmd.class,
                CodeIndexGraphCommand.class
        },
        mixinStandardHelpOptions = true
)
public class CodeIndexCommand implements Callable<Integer> {

    @CommandLine.Parameters(index = "0", arity = "0..1",
            description = "Directory to index (default: current directory)")
    private String directory;

    @CommandLine.Option(names = {"--project", "-p"},
            description = "Project identifier (default: derived from directory name)",
            defaultValue = "")
    private String project;

    @CommandLine.Option(names = {"--include"},
            description = "Include file patterns, comma-separated (e.g. '*.java,*.py')")
    private String include;

    @CommandLine.Option(names = {"--exclude"},
            description = "Exclude file patterns, comma-separated (e.g. '*Test.java,*.spec.ts')")
    private String exclude;

    @CommandLine.Option(names = {"--force", "-f"},
            description = "Force full re-index (ignore fingerprints)")
    private boolean force;

    @Override
    public Integer call() {
        try {
            Path dir = directory != null ?
                    Paths.get(directory).toAbsolutePath() :
                    Paths.get(System.getProperty("user.dir"));

            String projectId = (project != null && !project.isEmpty()) ?
                    project : dir.getFileName().toString();

            LocalCodeIndexer indexer = new LocalCodeIndexer();
            LocalCodeIndexer.IndexResult result = indexer.index(
                    dir, projectId, include, exclude, force, System.out);

            System.out.println();
            System.out.println("Index Summary:");
            System.out.println("  Project:              " + result.projectId());
            System.out.println("  Root:                 " + result.rootPath());
            System.out.println("  Files (total):        " + result.filesProcessed());
            System.out.println("  Files (skipped):      " + result.filesSkipped());
            System.out.println("  Files (re-indexed):   " + (result.filesProcessed() - result.filesSkipped() - result.filesDeleted()));
            System.out.println("  Files (deleted):      " + result.filesDeleted());
            System.out.println("  Entities:             " + result.entitiesFound());
            if (result.errors() > 0) {
                System.out.println("  Errors:               " + result.errors());
            }
            System.out.println("  Languages:");
            result.languageCounts().forEach((lang, count) ->
                    System.out.println("    " + lang + ": " + count));

            System.out.println();
            System.out.println("Search with: kompile code-index search \"query\" --project=" + result.projectId());

            // Auto-register this project with any running kompile-app instance
            ProjectAutoRegistrar.registerAsync(dir, result.projectId(), null);

            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    @CommandLine.Command(name = "search", description = "Search the local code index",
            mixinStandardHelpOptions = true)
    static class SearchCmd implements Callable<Integer> {

        @CommandLine.Parameters(index = "0", description = "Search query")
        private String query;

        @CommandLine.Option(names = {"--project", "-p"},
                description = "Project to search (default: derived from CWD name)")
        private String project;

        @CommandLine.Option(names = {"--type", "-t"},
                description = "Filter by entity type: CLASS, METHOD, FUNCTION, INTERFACE, FILE, IMPORT, ENUM, etc.")
        private String entityType;

        @CommandLine.Option(names = {"--language", "-l"},
                description = "Filter by language: java, python, go, rust, typescript, splan, etc.")
        private String language;

        @CommandLine.Option(names = {"--max", "-m"}, description = "Max results (default: 20)",
                defaultValue = "20")
        private int maxResults;

        @Override
        public Integer call() {
            try {
                String projectId = (project != null && !project.isEmpty()) ?
                        project : Paths.get(System.getProperty("user.dir")).getFileName().toString();

                LocalCodeIndexer indexer = new LocalCodeIndexer();
                List<Map<String, Object>> results = indexer.search(projectId, query, entityType, maxResults);

                // Apply language filter if specified
                if (language != null && !language.isEmpty()) {
                    results = results.stream()
                            .filter(e -> language.equalsIgnoreCase((String) e.get("language")))
                            .toList();
                }

                if (results.isEmpty()) {
                    System.out.println("No results found for: " + query);
                    return 0;
                }

                System.out.println("Found " + results.size() + " results for \"" + query + "\":\n");
                int i = 0;
                for (Map<String, Object> entity : results) {
                    i++;
                    String type = ((String) entity.getOrDefault("entityType", "?")).toLowerCase();
                    String name = (String) entity.getOrDefault("name", "?");
                    String file = (String) entity.getOrDefault("filePath", "");
                    Object startLine = entity.get("startLine");
                    String sig = (String) entity.get("signature");

                    System.out.print(i + ". [" + type + "] " + name);
                    if (sig != null) System.out.print("  " + sig);
                    System.out.println();
                    System.out.print("   " + file);
                    if (startLine != null) System.out.print(":" + startLine);
                    System.out.println();

                    // Inheritance info
                    String inheritedFrom = (String) entity.get("inheritedFrom");
                    String implementsList = (String) entity.get("implementsList");
                    if (inheritedFrom != null || implementsList != null) {
                        StringBuilder inh = new StringBuilder("   ");
                        if (inheritedFrom != null) inh.append("extends ").append(inheritedFrom);
                        if (implementsList != null) {
                            if (inheritedFrom != null) inh.append(", ");
                            inh.append("implements ").append(implementsList);
                        }
                        System.out.println(inh);
                    }

                    String doc = (String) entity.get("docComment");
                    if (doc != null) {
                        String truncated = doc.length() > 120 ?
                                doc.substring(0, 120).replaceAll("\\n", " ") + "..." :
                                doc.replaceAll("\\n", " ");
                        System.out.println("   " + truncated);
                    }
                    System.out.println();
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "spath",
            description = "Search the code index using spath (semantic path) queries. " +
                    "Addresses code by meaning, not filesystem location.\n\n" +
                    "Examples:\n" +
                    "  pkg.ClassName                 — exact class match\n" +
                    "  pkg.Class.method              — method in class\n" +
                    "  pkg.*                         — direct children of package\n" +
                    "  pkg.**                        — all descendants of package\n" +
                    "  pkg.*Handler                  — suffix wildcard match\n" +
                    "  pkg[File.java].method         — scoped to specific file\n" +
                    "  pkg.Class/imports             — imports in file containing Class\n" +
                    "  internal/service.Handler      — Go-style path\n" +
                    "  internal/...                  — all subpackages (Go-style)\n",
            mixinStandardHelpOptions = true)
    static class SpathCmd implements Callable<Integer> {

        @CommandLine.Parameters(index = "0", description = "Spath query (semantic path)")
        private String spathQuery;

        @CommandLine.Option(names = {"--project", "-p"},
                description = "Project to search (default: derived from CWD name)")
        private String project;

        @CommandLine.Option(names = {"--max", "-m"}, description = "Max results (default: 50)",
                defaultValue = "50")
        private int maxResults;

        @CommandLine.Option(names = {"--verbose", "-v"},
                description = "Show full details including signatures and doc comments")
        private boolean verbose;

        @Override
        public Integer call() {
            try {
                String projectId = (project != null && !project.isEmpty()) ?
                        project : Paths.get(System.getProperty("user.dir")).getFileName().toString();

                SpathResolver resolver = new SpathResolver(projectId);
                SpathResolver.SpathResult result = resolver.resolve(spathQuery, maxResults);

                if (result.matches().isEmpty()) {
                    System.out.println("No matches for spath: " + spathQuery);
                    System.out.println();
                    System.out.println("Hint: Make sure the project is indexed first:");
                    System.out.println("  kompile code-index --project=" + projectId);
                    return 0;
                }

                System.out.println("spath: " + spathQuery);
                if (result.resolvedPackage() != null && !result.resolvedPackage().isEmpty()) {
                    System.out.print("  package: " + result.resolvedPackage());
                    if (result.resolvedSymbol() != null) {
                        System.out.print("  symbol: " + result.resolvedSymbol());
                    }
                    System.out.println();
                }
                System.out.println("  " + result.totalMatches() + " match(es)\n");

                int idx = 0;
                String lastFile = null;
                for (SpathResolver.SpathMatch match : result.matches()) {
                    idx++;
                    // Group by file
                    if (!match.filePath().equals(lastFile)) {
                        if (lastFile != null) System.out.println();
                        System.out.println("\u001b[1m" + match.filePath() + "\u001b[0m");
                        lastFile = match.filePath();
                    }

                    String type = match.entityType().toLowerCase();
                    System.out.print("  " + idx + ". [" + type + "] \u001b[32m" + match.name() + "\u001b[0m");
                    if (match.startLine() > 0) {
                        System.out.print(":" + match.startLine());
                    }
                    System.out.println();

                    // FQN
                    if (match.fullyQualifiedName() != null && !match.fullyQualifiedName().equals(match.name())) {
                        System.out.println("     fqn: " + match.fullyQualifiedName());
                    }

                    // Inheritance info
                    if (match.inheritedFrom() != null || match.implementsList() != null) {
                        StringBuilder inheritance = new StringBuilder("     ");
                        if (match.inheritedFrom() != null) inheritance.append("extends ").append(match.inheritedFrom());
                        if (match.implementsList() != null) {
                            if (match.inheritedFrom() != null) inheritance.append(", ");
                            inheritance.append("implements ").append(match.implementsList());
                        }
                        System.out.println(inheritance);
                    }

                    if (verbose) {
                        if (match.signature() != null) {
                            System.out.println("     sig: " + match.signature());
                        }
                        if (match.visibility() != null) {
                            System.out.println("     vis: " + match.visibility());
                        }
                        if (match.docComment() != null) {
                            String doc = match.docComment().length() > 120 ?
                                    match.docComment().substring(0, 120).replaceAll("\\n", " ") + "..." :
                                    match.docComment().replaceAll("\\n", " ");
                            System.out.println("     doc: " + doc);
                        }
                    }
                }
                return 0;
            } catch (IllegalArgumentException e) {
                System.err.println("Invalid spath query: " + e.getMessage());
                return 1;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "find",
            description = "Find text or regex pattern in indexed source files",
            mixinStandardHelpOptions = true)
    static class FindCmd implements Callable<Integer> {

        @CommandLine.Parameters(index = "0", description = "Search query (text or regex)")
        private String query;

        @CommandLine.Option(names = {"--project", "-p"},
                description = "Project to search (default: derived from CWD name)")
        private String project;

        @CommandLine.Option(names = {"--regex", "-r"},
                description = "Treat query as a regex pattern")
        private boolean regex;

        @CommandLine.Option(names = {"--ignore-case", "-i"},
                description = "Case-insensitive search")
        private boolean ignoreCase;

        @CommandLine.Option(names = {"--whole-word", "-w"},
                description = "Match whole words only")
        private boolean wholeWord;

        @CommandLine.Option(names = {"--file-pattern", "-g"},
                description = "Filter files by glob pattern (e.g. '*.java')")
        private String filePattern;

        @CommandLine.Option(names = {"--context", "-C"},
                description = "Lines of context around each match (default: 2)",
                defaultValue = "2")
        private int context;

        @CommandLine.Option(names = {"--max", "-m"},
                description = "Max results (default: 200)", defaultValue = "200")
        private int maxResults;

        @Override
        public Integer call() {
            try {
                String projectId = (project != null && !project.isEmpty()) ?
                        project : Paths.get(System.getProperty("user.dir")).getFileName().toString();

                LocalCodeIndexer indexer = new LocalCodeIndexer();
                CodeSearchEngine engine = new CodeSearchEngine(indexer);

                CodeSearchEngine.FindOptions opts = CodeSearchEngine.FindOptions.defaults()
                        .withRegex(regex)
                        .withCaseSensitive(!ignoreCase)
                        .withWholeWord(wholeWord)
                        .withFilePattern(filePattern)
                        .withContextLines(context)
                        .withMaxResults(maxResults);

                CodeSearchEngine.FindResult result = engine.findInFiles(projectId, query, opts);

                if (result.matches().isEmpty()) {
                    System.out.println("No matches found for: " + query);
                    return 0;
                }

                System.out.println("Found " + result.totalMatches() + " match(es) in " +
                        result.filesWithMatches() + " file(s)" +
                        (result.truncated() ? " (truncated at " + maxResults + ")" : "") + ":\n");

                String lastFile = null;
                for (CodeSearchEngine.FileMatch match : result.matches()) {
                    if (!match.filePath().equals(lastFile)) {
                        if (lastFile != null) System.out.println();
                        System.out.println("\u001b[1m" + match.filePath() + "\u001b[0m");
                        lastFile = match.filePath();
                    }
                    for (String ctx : match.contextBefore()) {
                        System.out.println("  \u001b[2m" + ctx + "\u001b[0m");
                    }
                    System.out.println("  \u001b[32m" + match.lineNumber() + ":\u001b[0m " + match.lineContent());
                    for (String ctx : match.contextAfter()) {
                        System.out.println("  \u001b[2m" + ctx + "\u001b[0m");
                    }
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "replace",
            description = "Find and replace text across indexed source files",
            mixinStandardHelpOptions = true)
    static class ReplaceCmd implements Callable<Integer> {

        @CommandLine.Parameters(index = "0", description = "Search query (text or regex)")
        private String query;

        @CommandLine.Parameters(index = "1", description = "Replacement text")
        private String replacement;

        @CommandLine.Option(names = {"--project", "-p"},
                description = "Project to search (default: derived from CWD name)")
        private String project;

        @CommandLine.Option(names = {"--regex", "-r"},
                description = "Treat query as a regex pattern")
        private boolean regex;

        @CommandLine.Option(names = {"--ignore-case", "-i"},
                description = "Case-insensitive search")
        private boolean ignoreCase;

        @CommandLine.Option(names = {"--whole-word", "-w"},
                description = "Match whole words only")
        private boolean wholeWord;

        @CommandLine.Option(names = {"--file-pattern", "-g"},
                description = "Filter files by glob pattern (e.g. '*.java')")
        private String filePattern;

        @CommandLine.Option(names = {"--dry-run", "-n"},
                description = "Preview changes without modifying files")
        private boolean dryRun;

        @Override
        public Integer call() {
            try {
                String projectId = (project != null && !project.isEmpty()) ?
                        project : Paths.get(System.getProperty("user.dir")).getFileName().toString();

                LocalCodeIndexer indexer = new LocalCodeIndexer();
                CodeSearchEngine engine = new CodeSearchEngine(indexer);

                CodeSearchEngine.FindOptions opts = CodeSearchEngine.FindOptions.defaults()
                        .withRegex(regex)
                        .withCaseSensitive(!ignoreCase)
                        .withWholeWord(wholeWord)
                        .withFilePattern(filePattern);

                CodeSearchEngine.ReplaceResult result = engine.findAndReplace(
                        projectId, query, replacement, opts, dryRun, System.out);

                if (result.replacements().isEmpty()) {
                    System.out.println("No matches found for: " + query);
                    return 0;
                }

                System.out.println((dryRun ? "[DRY RUN] " : "") +
                        result.totalReplacements() + " replacement(s) in " +
                        result.filesModified() + " file(s):\n");

                String lastFile = null;
                for (CodeSearchEngine.Replacement r : result.replacements()) {
                    if (!r.filePath().equals(lastFile)) {
                        if (lastFile != null) System.out.println();
                        System.out.println("\u001b[1m" + r.filePath() + "\u001b[0m");
                        lastFile = r.filePath();
                    }
                    System.out.println("  \u001b[31m- " + r.lineNumber() + ": " + r.originalLine() + "\u001b[0m");
                    System.out.println("  \u001b[32m+ " + r.lineNumber() + ": " + r.replacedLine() + "\u001b[0m");
                }

                if (!dryRun && result.indexResult() != null) {
                    System.out.println("\nIndex updated: " + result.indexResult().entitiesFound() + " entities");
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "usages",
            description = "Find all usages of a symbol (class, method, function)",
            mixinStandardHelpOptions = true)
    static class UsagesCmd implements Callable<Integer> {

        @CommandLine.Parameters(index = "0", description = "Symbol name to find usages of")
        private String symbolName;

        @CommandLine.Option(names = {"--project", "-p"},
                description = "Project to search (default: derived from CWD name)")
        private String project;

        @CommandLine.Option(names = {"--type", "-t"},
                description = "Symbol type filter: CLASS, METHOD, FUNCTION, INTERFACE, etc.")
        private String entityType;

        @CommandLine.Option(names = {"--max", "-m"},
                description = "Max results (default: 100)", defaultValue = "100")
        private int maxResults;

        @Override
        public Integer call() {
            try {
                String projectId = (project != null && !project.isEmpty()) ?
                        project : Paths.get(System.getProperty("user.dir")).getFileName().toString();

                LocalCodeIndexer indexer = new LocalCodeIndexer();
                CodeSearchEngine engine = new CodeSearchEngine(indexer);

                CodeSearchEngine.UsagesResult result = engine.findUsages(
                        projectId, symbolName, entityType, maxResults);

                if (result.usages().isEmpty()) {
                    System.out.println("No usages found for: " + symbolName);
                    return 0;
                }

                System.out.println("Usages of " + result.symbolType().toLowerCase() + " '" +
                        result.symbolName() + "' (" + result.totalUsages() + " total):\n");

                if (result.definitionFile() != null) {
                    System.out.println("  Defined at: " + result.definitionFile() + ":" + result.definitionLine());
                    System.out.println();
                }

                // Print usage breakdown
                if (!result.usagesByKind().isEmpty()) {
                    System.out.println("  Usage breakdown:");
                    result.usagesByKind().forEach((kind, count) ->
                            System.out.println("    " + kind.name().toLowerCase().replace('_', ' ') + ": " + count));
                    System.out.println();
                }

                // Print usages grouped by file
                String lastFile = null;
                for (CodeSearchEngine.Usage usage : result.usages()) {
                    if (!usage.filePath().equals(lastFile)) {
                        if (lastFile != null) System.out.println();
                        System.out.println("\u001b[1m" + usage.filePath() + "\u001b[0m");
                        lastFile = usage.filePath();
                    }
                    String kindLabel = usage.kind().name().toLowerCase().replace('_', ' ');
                    String ctx = usage.context() != null ? " (in " + usage.context() + ")" : "";
                    System.out.println("  \u001b[32m" + usage.lineNumber() + ":\u001b[0m [" +
                            kindLabel + "] " + usage.lineContent() + ctx);
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "watch",
            description = "Watch for file changes and re-index automatically",
            mixinStandardHelpOptions = true)
    static class WatchCmd implements Callable<Integer> {

        @CommandLine.Parameters(index = "0", arity = "0..1",
                description = "Directory to watch (default: current directory)")
        private String directory;

        @CommandLine.Option(names = {"--project", "-p"},
                description = "Project identifier (default: derived from directory name)")
        private String project;

        @Override
        public Integer call() {
            try {
                Path dir = directory != null ?
                        Paths.get(directory).toAbsolutePath() :
                        Paths.get(System.getProperty("user.dir"));

                String projectId = (project != null && !project.isEmpty()) ?
                        project : dir.getFileName().toString();

                LocalCodeIndexer indexer = new LocalCodeIndexer();

                // Do an initial index first
                System.out.println("Running initial index...");
                indexer.index(dir, projectId, null, null, System.out);

                // Start watching
                IndexFileWatcher watcher = indexer.createWatcher(dir, projectId, System.out);
                watcher.setListener(new IndexFileWatcher.WatchListener() {
                    @Override
                    public void onFilesChanged(java.util.Set<String> changedPaths) {
                        System.out.println("[watch] Detected " + changedPaths.size() + " changed file(s)");
                    }

                    @Override
                    public void onIndexUpdated(LocalCodeIndexer.IndexResult result) {
                        // Output is handled by the watcher itself
                    }

                    @Override
                    public void onError(String message, Exception e) {
                        System.err.println("[watch] " + message + ": " + e.getMessage());
                    }
                });
                watcher.start();

                System.out.println("Watching for changes. Press Ctrl+C to stop.");
                System.out.println();

                // Block until interrupted
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    watcher.stop();
                    System.out.println("Watcher stopped.");
                }));

                // Sleep in main thread until interrupted
                try {
                    Thread.currentThread().join();
                } catch (InterruptedException e) {
                    watcher.stop();
                }

                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "list", description = "List locally indexed projects",
            mixinStandardHelpOptions = true)
    static class ListCmd implements Callable<Integer> {
        @Override
        public Integer call() {
            try {
                LocalCodeIndexer indexer = new LocalCodeIndexer();
                List<Map<String, Object>> projects = indexer.listProjects();

                if (projects.isEmpty()) {
                    System.out.println("No locally indexed projects found.");
                    System.out.println("Run 'kompile code-index' in a project directory to create one.");
                    return 0;
                }

                System.out.println("Locally indexed projects:\n");
                for (Map<String, Object> meta : projects) {
                    System.out.println("  " + meta.getOrDefault("projectId", "?"));
                    System.out.println("    Path:     " + meta.getOrDefault("rootPath", "?"));
                    System.out.println("    Files:    " + meta.getOrDefault("filesProcessed", "?"));
                    System.out.println("    Entities: " + meta.getOrDefault("entitiesFound", "?"));
                    System.out.println("    Indexed:  " + meta.getOrDefault("indexedAt", "?"));
                    System.out.println();
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    @CommandLine.Command(name = "stats", description = "Show statistics for a local index",
            mixinStandardHelpOptions = true)
    static class StatsCmd implements Callable<Integer> {

        @CommandLine.Option(names = {"--project", "-p"},
                description = "Project ID (default: derived from CWD name)")
        private String project;

        @Override
        public Integer call() {
            try {
                String projectId = (project != null && !project.isEmpty()) ?
                        project : Paths.get(System.getProperty("user.dir")).getFileName().toString();

                LocalCodeIndexer indexer = new LocalCodeIndexer();
                Map<String, Object> stats = indexer.getStats(projectId);

                System.out.println("Index Statistics for: " + projectId);
                System.out.println();
                System.out.println("  Root:       " + stats.getOrDefault("rootPath", "?"));
                System.out.println("  Files:      " + stats.getOrDefault("filesProcessed", "?"));
                System.out.println("  Entities:   " + stats.getOrDefault("entitiesFound", "?"));
                System.out.println("  Errors:     " + stats.getOrDefault("errors", 0));
                System.out.println("  Indexed at: " + stats.getOrDefault("indexedAt", "?"));

                @SuppressWarnings("unchecked")
                Map<String, Object> langCounts = (Map<String, Object>) stats.get("languageCounts");
                if (langCounts != null && !langCounts.isEmpty()) {
                    System.out.println("  Languages:");
                    langCounts.forEach((lang, count) ->
                            System.out.println("    " + lang + ": " + count));
                }

                @SuppressWarnings("unchecked")
                Map<String, Object> typeCounts = (Map<String, Object>) stats.get("entityCountsByType");
                if (typeCounts != null && !typeCounts.isEmpty()) {
                    System.out.println("  Entity types:");
                    typeCounts.forEach((type, count) ->
                            System.out.println("    " + type + ": " + count));
                }
                return 0;
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }
}
