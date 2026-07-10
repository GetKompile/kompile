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

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.PrintStream;
import ai.kompile.cli.main.chat.tools.SearchExclusions;

import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.StringJoiner;
import java.util.OptionalInt;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Standalone local code indexer that works without a running kompile-app.
 * Walks a directory tree, extracts code entities via regex, and stores
 * the index as per-file JSON shards under {@code ~/.kompile/code-index/<projectId>/}.
 *
 * <p>Supports incremental indexing: only re-parses files that have changed
 * since the last index run (based on mtime+size+SHA-256 fingerprinting).
 * Search is backed by SQLite FTS5 for fast full-text queries.</p>
 *
 * <p>Thread-safe: uses {@link IndexLockManager} for concurrent read/write
 * safety within and across processes.</p>
 */
public class LocalCodeIndexer {

    private static final Set<String> IGNORED_DIRS = Set.of(
            ".git", ".svn", ".hg", "node_modules", "__pycache__", ".gradle",
            "target", "build", "dist", "out", ".idea", ".vscode", ".settings",
            ".kompile", ".claude", ".codex", ".gemini", ".opencode", ".cursor",
            "vendor", ".tox", ".mypy_cache", ".pytest_cache", ".angular",
            ".next", ".nuxt", "coverage", ".cache", "bin", "obj"
    );

    private static final Map<String, String> EXTENSION_TO_LANG = new LinkedHashMap<>();

    static {
        // JVM
        EXTENSION_TO_LANG.put(".java", "java");
        EXTENSION_TO_LANG.put(".kt", "kotlin");
        EXTENSION_TO_LANG.put(".kts", "kotlin");
        EXTENSION_TO_LANG.put(".scala", "scala");
        EXTENSION_TO_LANG.put(".groovy", "groovy");
        EXTENSION_TO_LANG.put(".gradle", "groovy");
        // C-family
        EXTENSION_TO_LANG.put(".c", "c");
        EXTENSION_TO_LANG.put(".h", "c");
        EXTENSION_TO_LANG.put(".cpp", "cpp");
        EXTENSION_TO_LANG.put(".cc", "cpp");
        EXTENSION_TO_LANG.put(".cxx", "cpp");
        EXTENSION_TO_LANG.put(".hpp", "cpp");
        EXTENSION_TO_LANG.put(".cs", "csharp");
        // Web
        EXTENSION_TO_LANG.put(".js", "javascript");
        EXTENSION_TO_LANG.put(".jsx", "javascript");
        EXTENSION_TO_LANG.put(".mjs", "javascript");
        EXTENSION_TO_LANG.put(".ts", "typescript");
        EXTENSION_TO_LANG.put(".tsx", "typescript");
        // Python
        EXTENSION_TO_LANG.put(".py", "python");
        EXTENSION_TO_LANG.put(".pyi", "python");
        // Go / Rust / Swift
        EXTENSION_TO_LANG.put(".go", "go");
        EXTENSION_TO_LANG.put(".rs", "rust");
        EXTENSION_TO_LANG.put(".swift", "swift");
        // Ruby / PHP
        EXTENSION_TO_LANG.put(".rb", "ruby");
        EXTENSION_TO_LANG.put(".php", "php");
        // Shell
        EXTENSION_TO_LANG.put(".sh", "bash");
        EXTENSION_TO_LANG.put(".bash", "bash");
        // Config
        EXTENSION_TO_LANG.put(".sql", "sql");
        EXTENSION_TO_LANG.put(".json", "json");
        EXTENSION_TO_LANG.put(".yaml", "yaml");
        EXTENSION_TO_LANG.put(".yml", "yaml");
        EXTENSION_TO_LANG.put(".toml", "toml");
        EXTENSION_TO_LANG.put(".xml", "xml");
        EXTENSION_TO_LANG.put(".html", "html");
        EXTENSION_TO_LANG.put(".css", "css");
        // Markup
        EXTENSION_TO_LANG.put(".md", "markdown");
        EXTENSION_TO_LANG.put(".proto", "protobuf");
        EXTENSION_TO_LANG.put(".g4", "antlr");
        EXTENSION_TO_LANG.put(".splan", "splan");
    }

    // --- Regex patterns for entity extraction ---

    // Java / JVM
    private static final Pattern JAVA_PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern JAVA_IMPORT = Pattern.compile("^\\s*import\\s+(static\\s+)?([\\w.*]+)\\s*;");
    private static final Pattern JAVA_CLASS = Pattern.compile(
            "^\\s*(public|protected|private)?\\s*(static\\s+)?(abstract\\s+)?(final\\s+)?" +
                    "(class|interface|enum|record|@interface)\\s+(\\w+)(?:\\s+extends\\s+([\\w.]+))?" +
                    "(?:\\s+implements\\s+([\\w.,\\s]+))?");
    private static final Pattern JAVA_METHOD = Pattern.compile(
            "^\\s*(public|protected|private)?\\s*(static\\s+)?(abstract\\s+)?(?:synchronized\\s+)?" +
                    "((?:[\\w.<>\\[\\],?\\s]+)\\s+)(\\w+)\\s*\\(([^)]*)\\)");
    private static final Pattern JAVA_FIELD = Pattern.compile(
            "^\\s*(public|protected|private)?\\s*(static\\s+)?(final\\s+)?(\\w[\\w.<>\\[\\]]*\\s+)(\\w+)\\s*[=;]");

    // Spring Framework annotations
    private static final Pattern SPRING_COMPONENT = Pattern.compile(
            "^\\s*@(Component|Service|Repository|Controller|RestController|Configuration)(?:\\((.*)\\))?");
    private static final Pattern SPRING_AUTOWIRED = Pattern.compile(
            "^\\s*@(Autowired|Inject)(?:\\((.*)\\))?");
    private static final Pattern SPRING_CONDITIONAL = Pattern.compile(
            "^\\s*@ConditionalOn(Property|Class|Bean|MissingBean|Expression)\\((.*)\\)");
    private static final Pattern SPRING_PRIMARY = Pattern.compile("^\\s*@Primary");
    private static final Pattern SPRING_LAZY = Pattern.compile("^\\s*@Lazy");
    private static final Pattern SPRING_QUALIFIER = Pattern.compile(
            "^\\s*@Qualifier\\(\"?([\\w.-]+)\"?\\)");

    // Python
    private static final Pattern PY_CLASS = Pattern.compile("^\\s*class\\s+(\\w+)(?:\\s*\\(([^)]*)\\))?\\s*:");
    private static final Pattern PY_FUNC = Pattern.compile("^\\s*(async\\s+)?def\\s+(\\w+)\\s*\\(([^)]*)\\)");
    private static final Pattern PY_IMPORT = Pattern.compile("^\\s*(?:from\\s+(\\S+)\\s+)?import\\s+(.+)");

    // Go
    private static final Pattern GO_FUNC = Pattern.compile("^func\\s+(?:\\(\\w+\\s+\\*?\\w+\\)\\s+)?(\\w+)\\s*\\(([^)]*)\\)");
    private static final Pattern GO_TYPE = Pattern.compile("^type\\s+(\\w+)\\s+(struct|interface)");
    private static final Pattern GO_PACKAGE = Pattern.compile("^package\\s+(\\w+)");

    // Rust
    private static final Pattern RUST_FN = Pattern.compile("^\\s*(pub\\s+)?(?:async\\s+)?fn\\s+(\\w+)");
    private static final Pattern RUST_STRUCT = Pattern.compile("^\\s*(pub\\s+)?struct\\s+(\\w+)");
    private static final Pattern RUST_ENUM = Pattern.compile("^\\s*(pub\\s+)?enum\\s+(\\w+)");
    private static final Pattern RUST_TRAIT = Pattern.compile("^\\s*(pub\\s+)?trait\\s+(\\w+)");
    private static final Pattern RUST_IMPL = Pattern.compile(
            "^\\s*impl(?:<[^>]*>)?\\s+(?:([\\w:]+(?:<[^>]*)?)\\s+for\\s+)?(\\w+)");

    // TypeScript/JavaScript
    private static final Pattern TS_CLASS = Pattern.compile(
            "^\\s*(export\\s+)?(abstract\\s+)?class\\s+(\\w+)" +
            "(?:\\s+extends\\s+([\\w.<>]+))?" +
            "(?:\\s+implements\\s+([\\w,\\s<>]+))?");
    private static final Pattern TS_FUNC = Pattern.compile("^\\s*(export\\s+)?(async\\s+)?function\\s+(\\w+)");
    private static final Pattern TS_INTERFACE = Pattern.compile(
            "^\\s*(export\\s+)?interface\\s+(\\w+)" +
            "(?:\\s+extends\\s+([\\w,\\s<>]+))?");

    // C/C++ class/struct with inheritance
    private static final Pattern C_CLASS = Pattern.compile(
            "^\\s*(class|struct)\\s+(\\w+)" +
            "(?:\\s*:\\s*((?:(?:public|protected|private)\\s+)?[\\w:]+(?:\\s*,\\s*(?:(?:public|protected|private)\\s+)?[\\w:]+)*))?");

    private final ObjectMapper objectMapper;

    public LocalCodeIndexer() {
        this.objectMapper = JsonUtils.standardMapper();
    }

    /**
     * Index a directory incrementally. Only re-parses files that have changed
     * since the last index run. Use {@code forceReindex=true} to re-parse everything.
     */
    public IndexResult index(Path rootDir, String projectId, String includes,
                             String excludes, PrintStream out) throws IOException {
        return index(rootDir, projectId, includes, excludes, false, out);
    }

    /**
     * Index a directory with optional force re-index.
     */
    public IndexResult index(Path rootDir, String projectId, String includes,
                             String excludes, boolean forceReindex,
                             PrintStream out) throws IOException {
        Path absRoot = rootDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(absRoot)) {
            throw new IOException("Not a directory: " + absRoot);
        }

        Set<String> includeSet = parsePatterns(includes);
        Set<String> excludeSet = parsePatterns(excludes);

        Path indexDir = getIndexDir(projectId);
        Files.createDirectories(indexDir);
        IndexFileStore store = new IndexFileStore(indexDir, objectMapper);

        // Acquire write lock (blocks other indexers, not readers)
        try (IndexLockManager.LockToken ignored = IndexLockManager.acquireWriteLock(projectId, indexDir)) {
            // Migrate from legacy flat entities.json if needed
            if (store.hasLegacyIndex()) {
                out.println("Migrating legacy index to incremental format...");
                migrateFromLegacy(store, projectId, absRoot, out);
                out.println("Migration complete.");
            }

            out.println("Indexing: " + absRoot + (forceReindex ? " (full re-index)" : " (incremental)"));
            out.println("Project: " + projectId);

            // Load existing fingerprints
            Map<String, IndexFileStore.FileFingerprint> oldFingerprints =
                    forceReindex ? new LinkedHashMap<>() : store.loadFingerprints();

            // Collect current source files (walk captures mtime+size — no re-stat later)
            List<SourceFile> sourceFiles = collectSourceFiles(absRoot, includeSet, excludeSet);
            Set<String> currentRelPaths = new LinkedHashSet<>();
            for (SourceFile f : sourceFiles) {
                currentRelPaths.add(f.relPath());
            }

            out.println("Found " + sourceFiles.size() + " source files");

            // Compute diff
            Set<String> deleted = new LinkedHashSet<>(oldFingerprints.keySet());
            deleted.removeAll(currentRelPaths);

            List<SourceFile> toReparse = new ArrayList<>();
            // Cache SHA-256 computed during diff phase to avoid recomputing in parse phase
            Map<String, String> precomputedSha = new HashMap<>();
            int skipped = 0;
            boolean fingerprintDriftOnly = false;

            for (SourceFile file : sourceFiles) {
                IndexFileStore.FileFingerprint old = oldFingerprints.get(file.relPath());
                if (old != null && old.lastModified() == file.mtime() && old.size() == file.size()) {
                    skipped++;
                    continue; // mtime+size unchanged — skip
                }

                // mtime or size changed — compute SHA-256 to confirm
                if (old != null && !forceReindex) {
                    String sha = IndexFileStore.sha256File(file.path());
                    if (sha.equals(old.sha256())) {
                        // Content unchanged, just timestamp drift — update fingerprint only
                        oldFingerprints.put(file.relPath(),
                                new IndexFileStore.FileFingerprint(file.mtime(), file.size(), sha));
                        fingerprintDriftOnly = true;
                        skipped++;
                        continue;
                    }
                    // SHA was computed and content differs — cache it for the parse phase
                    precomputedSha.put(file.relPath(), sha);
                }

                toReparse.add(file);
            }

            out.println("  Skipped (unchanged): " + skipped);
            out.println("  To re-index: " + toReparse.size());
            out.println("  Deleted: " + deleted.size());

            // Fast path: clean tree — don't open the DB or rewrite index files.
            // The auto-refresher runs this method before read actions every 30s;
            // without this, every clean pass still paid a DB open, two COUNT
            // scans and a full fingerprints+metadata rewrite.
            if (toReparse.isEmpty() && deleted.isEmpty()) {
                Map<String, Object> priorMeta = store.loadMetadata();
                if (!priorMeta.isEmpty()) {
                    if (fingerprintDriftOnly) {
                        store.saveFingerprints(oldFingerprints);
                    }
                    Map<String, Integer> storedLangCounts = new TreeMap<>();
                    if (priorMeta.get("languageCounts") instanceof Map<?, ?> lc) {
                        for (Map.Entry<?, ?> e : lc.entrySet()) {
                            if (e.getValue() instanceof Number n) {
                                storedLangCounts.put(String.valueOf(e.getKey()), n.intValue());
                            }
                        }
                    }
                    int knownEntities = priorMeta.get("entitiesFound") instanceof Number n
                            ? n.intValue() : 0;
                    out.println("  No changes — index is up to date");
                    return new IndexResult(projectId, absRoot.toString(), currentRelPaths.size(),
                            knownEntities, 0, storedLangCounts, skipped, 0);
                }
            }

            // Open DB and perform incremental update
            Map<String, IndexFileStore.FileFingerprint> newFingerprints = new LinkedHashMap<>(oldFingerprints);
            AtomicInteger errorCount = new AtomicInteger(0);
            Map<String, Integer> langCounts = new TreeMap<>();
            int totalEntities = 0;

            // Capture a single timestamp for the entire indexing run
            String indexTimestamp = Instant.now().toString();

            // A fully processed file: entities + relations extracted, shard written.
            record ParsedFile(String relPath, String lang, List<Map<String, Object>> entities,
                              List<Map<String, Object>> relations,
                              IndexFileStore.FileFingerprint fingerprint) {}

            // Everything per-file and DB-free runs on the parse pool: read,
            // entity parse, relation extraction, shard write. (Relation
            // extraction and shard writes used to run on the single DB-writer
            // thread, and every parsed file's full line array was retained
            // until that phase — slow and memory-heavy on big passes.)
            store.ensureFilesDir();
            java.util.function.Function<SourceFile, ParsedFile> parseOne = file -> {
                try {
                    String lang = detectLanguage(file.path());
                    if (lang == null) return null;

                    String content = Files.readString(file.path());
                    String[] lines = content.split("\n", -1);

                    List<Map<String, Object>> fileEntities = new ArrayList<>();

                    // FILE entity
                    Map<String, Object> fileEntity = new LinkedHashMap<>();
                    fileEntity.put("projectId", projectId);
                    fileEntity.put("entityType", "FILE");
                    fileEntity.put("name", file.path().getFileName().toString());
                    fileEntity.put("fullyQualifiedName", file.relPath());
                    fileEntity.put("filePath", file.relPath());
                    fileEntity.put("language", lang);
                    fileEntity.put("startLine", 1);
                    fileEntity.put("endLine", lines.length);
                    fileEntity.put("indexedAt", indexTimestamp);
                    fileEntities.add(fileEntity);

                    // Parse entities from content
                    fileEntities.addAll(parseEntities(lines, file.relPath(), projectId, lang));

                    List<Map<String, Object>> relations = LocalRelationExtractor.extract(
                            file.relPath(), projectId, fileEntities, lines, lang);

                    // Fingerprint from walk-time stat — reuse SHA from diff phase if available
                    String sha = precomputedSha.get(file.relPath());
                    if (sha == null) sha = IndexFileStore.sha256File(file.path());
                    IndexFileStore.FileFingerprint fp = new IndexFileStore.FileFingerprint(
                            file.mtime(), file.size(), sha);

                    store.writeFileShard(file.relPath(), fp, fileEntities);

                    return new ParsedFile(file.relPath(), lang, fileEntities, relations, fp);
                } catch (Exception e) {
                    int n = errorCount.incrementAndGet();
                    if (n <= 5) {
                        out.println("  Error indexing file: " + e.getMessage());
                    } else if (n == 6) {
                        out.println("  (suppressing further error details)");
                    }
                    return null;
                }
            };

            int parallelism = Math.min(Runtime.getRuntime().availableProcessors(),
                    Math.max(1, toReparse.size()));
            boolean parallel = parallelism > 1 && toReparse.size() > 10;
            ExecutorService parsePool = parallel ? Executors.newFixedThreadPool(parallelism) : null;

            // Parsed results are drained in completion order and written to the
            // DB as they arrive, so parsing and DB writes overlap instead of
            // running as strict phases, and peak memory stays bounded by the
            // in-flight files instead of the whole change set.
            List<String> writtenPaths = new ArrayList<>(toReparse.size());
            try (IndexDatabase db = IndexDatabase.open(indexDir)) {
                db.beginTransaction();
                try {
                    // Remove deleted files (bulk)
                    db.deleteFiles(deleted);
                    for (String delPath : deleted) {
                        store.deleteFileShard(delPath);
                        newFingerprints.remove(delPath);
                    }

                    Iterator<ParsedFile> results;
                    if (parallel) {
                        CompletionService<ParsedFile> completion =
                                new ExecutorCompletionService<>(parsePool);
                        for (SourceFile file : toReparse) {
                            completion.submit(() -> parseOne.apply(file));
                        }
                        int total = toReparse.size();
                        results = new Iterator<>() {
                            private int received = 0;

                            @Override
                            public boolean hasNext() {
                                return received < total;
                            }

                            @Override
                            public ParsedFile next() {
                                received++;
                                try {
                                    return completion.take().get();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new RuntimeException(e);
                                } catch (ExecutionException e) {
                                    // parseOne handles its own errors; belt and braces
                                    errorCount.incrementAndGet();
                                    return null;
                                }
                            }
                        };
                    } else {
                        results = toReparse.stream().map(parseOne).iterator();
                    }

                    int processed = 0;
                    while (results.hasNext()) {
                        ParsedFile pf = results.next();
                        if (pf == null) continue;
                        try {
                            langCounts.merge(pf.lang(), 1, Integer::sum);

                            db.deleteFile(pf.relPath());
                            db.insertEntities(pf.relPath(), pf.entities());
                            db.insertRelations(pf.relPath(), pf.relations());
                            db.upsertFile(pf.relPath(), IndexFileStore.shardName(pf.relPath()),
                                    pf.fingerprint());

                            newFingerprints.put(pf.relPath(), pf.fingerprint());
                            writtenPaths.add(pf.relPath());

                            processed++;
                            if (processed % 200 == 0) {
                                out.print("  Indexed " + processed + "/" + toReparse.size() + " files\r");
                                out.flush();
                            }
                        } catch (Exception e) {
                            int n = errorCount.incrementAndGet();
                            if (n <= 5) {
                                out.println("  Error writing file: " + e.getMessage());
                            }
                        }
                    }

                    db.commit();
                    totalEntities = db.getEntityCount();

                    // Merge language counts from DB for full picture
                    if (toReparse.size() < sourceFiles.size()) {
                        langCounts = db.getLanguageCounts();
                    }

                    // Post-commit: resolve cross-file FQN targets in relations.
                    // Incremental passes only resolve the changed scope; a full
                    // pass (everything re-parsed) resolves across the index.
                    if (!writtenPaths.isEmpty()) {
                        try {
                            db.beginTransaction();
                            int resolved = db.ensureConnectivity(
                                    writtenPaths.size() == sourceFiles.size() ? null : writtenPaths);
                            db.commit();
                            if (resolved > 0) {
                                out.println("  Graph: resolved " + resolved + " cross-file relation targets");
                            }
                        } catch (Exception ce) {
                            db.rollback();
                            // Non-fatal — index is still usable
                            out.println("  Warning: connectivity pass failed: " + ce.getMessage());
                        }
                    }
                } catch (Exception e) {
                    db.rollback();
                    throw new IOException("Index update failed: " + e.getMessage(), e);
                }
            } catch (java.sql.SQLException e) {
                throw new IOException("Database error: " + e.getMessage(), e);
            } finally {
                if (parsePool != null) parsePool.shutdown();
            }

            // Save fingerprints
            store.saveFingerprints(newFingerprints);

            // Save metadata
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("projectId", projectId);
            metadata.put("rootPath", absRoot.toString());
            metadata.put("indexedAt", Instant.now().toString());
            metadata.put("filesProcessed", currentRelPaths.size());
            metadata.put("entitiesFound", totalEntities);
            metadata.put("errors", errorCount.get());
            metadata.put("languageCounts", langCounts);
            metadata.put("filesSkipped", skipped);
            metadata.put("filesDeleted", deleted.size());
            metadata.put("filesReindexed", toReparse.size());
            store.saveMetadata(metadata);

            out.println("  Processed " + toReparse.size() + " files, " +
                    totalEntities + " entities total, " + errorCount.get() + " errors");
            out.println("Index saved to: " + indexDir);

            return new IndexResult(projectId, absRoot.toString(), currentRelPaths.size(),
                    totalEntities, errorCount.get(), langCounts, skipped, deleted.size());
        }
    }

    /**
     * Search the local index for entities matching a query.
     */
    public List<Map<String, Object>> search(String projectId, String query, String entityType,
                                             int maxResults) throws IOException {
        Path indexDir = getIndexDir(projectId);

        // Check for DB-backed index first
        if (Files.exists(indexDir.resolve("index.db"))) {
            try (IndexLockManager.LockToken ignored = IndexLockManager.acquireReadLock(projectId);
                 IndexDatabase db = IndexDatabase.open(indexDir)) {
                return db.search(query, entityType, maxResults);
            } catch (java.sql.SQLException e) {
                // Fall through to legacy search
            }
        }

        // Legacy fallback: flat entities.json
        return legacySearch(projectId, query, entityType, maxResults);
    }

    /**
     * List all locally indexed projects.
     */
    public List<Map<String, Object>> listProjects() throws IOException {
        Path baseDir = getBaseIndexDir();
        List<Map<String, Object>> projects = new ArrayList<>();
        if (!Files.isDirectory(baseDir)) return projects;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(baseDir)) {
            for (Path dir : stream) {
                if (!Files.isDirectory(dir)) continue;
                Path metaFile = dir.resolve("metadata.json");
                if (Files.exists(metaFile)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> meta = objectMapper.readValue(metaFile.toFile(), Map.class);
                    projects.add(meta);
                }
            }
        }
        return projects;
    }

    /**
     * Get statistics for a local index.
     */
    public Map<String, Object> getStats(String projectId) throws IOException {
        Path indexDir = getIndexDir(projectId);
        IndexFileStore store = new IndexFileStore(indexDir, objectMapper);
        Map<String, Object> meta = store.loadMetadata();
        if (meta.isEmpty()) {
            throw new IOException("No index found for project '" + projectId + "'");
        }

        // Enrich with live DB stats if available
        if (Files.exists(indexDir.resolve("index.db"))) {
            try (IndexDatabase db = IndexDatabase.open(indexDir)) {
                meta.put("entitiesFound", db.getEntityCount());
                meta.put("filesIndexed", db.getFileCount());
                meta.put("entityCountsByType", db.getEntityCountsByType());
                meta.put("graphStats", db.getGraphStats());
            } catch (java.sql.SQLException ignored) {}
        }

        return meta;
    }

    /**
     * Create a file watcher for this project that triggers incremental
     * re-indexing when source files change.
     */
    public IndexFileWatcher createWatcher(Path rootDir, String projectId, PrintStream out) {
        return new IndexFileWatcher(rootDir, projectId, this, out);
    }

    // -----------------------------------------------------------------------
    // Legacy migration
    // -----------------------------------------------------------------------

    /**
     * Migrate a legacy flat entities.json index to the incremental format.
     * Builds real fingerprints from files on disk so the subsequent
     * incremental pass recognizes them as already indexed and skips them.
     */
    private void migrateFromLegacy(IndexFileStore store, String projectId,
                                    Path rootDir, PrintStream out) throws IOException {
        List<Map<String, Object>> allEntities = store.readLegacyEntities();
        if (allEntities.isEmpty()) return;

        // Group entities by filePath
        Map<String, List<Map<String, Object>>> byFile = new LinkedHashMap<>();
        for (Map<String, Object> entity : allEntities) {
            String filePath = (String) entity.getOrDefault("filePath", "");
            byFile.computeIfAbsent(filePath, k -> new ArrayList<>()).add(entity);
        }

        // Build real fingerprints from files on disk
        Map<String, IndexFileStore.FileFingerprint> fingerprints = new LinkedHashMap<>();
        Path indexDir = store.getIndexDir();

        try (IndexDatabase db = IndexDatabase.open(indexDir)) {
            db.beginTransaction();
            try {
                int migrated = 0;
                for (Map.Entry<String, List<Map<String, Object>>> entry : byFile.entrySet()) {
                    String relPath = entry.getKey();
                    List<Map<String, Object>> entities = entry.getValue();

                    // Compute real fingerprint from the file on disk
                    Path filePath = rootDir.resolve(relPath);
                    IndexFileStore.FileFingerprint fp;
                    if (Files.exists(filePath)) {
                        BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
                        String sha = IndexFileStore.sha256File(filePath);
                        fp = new IndexFileStore.FileFingerprint(
                                attrs.lastModifiedTime().toMillis(), attrs.size(), sha);
                        fingerprints.put(relPath, fp);
                    } else {
                        // File no longer exists — migrate entities but with dummy fingerprint
                        // (will be cleaned up as "deleted" on next incremental pass)
                        fp = new IndexFileStore.FileFingerprint(0, 0, "deleted");
                    }

                    store.writeFileShard(relPath, fp, entities);
                    db.insertEntities(relPath, entities);
                    db.upsertFile(relPath, IndexFileStore.shardName(relPath), fp);

                    migrated++;
                    if (migrated % 500 == 0) {
                        out.print("  Migrated " + migrated + "/" + byFile.size() + " files\r");
                        out.flush();
                    }
                }
                db.commit();
                out.println("  Migrated " + migrated + " files with " + allEntities.size() + " entities");
            } catch (Exception e) {
                db.rollback();
                throw new IOException("Migration failed: " + e.getMessage(), e);
            }
        } catch (java.sql.SQLException e) {
            throw new IOException("Migration DB error: " + e.getMessage(), e);
        }

        // Save real fingerprints so incremental pass skips already-indexed files
        store.saveFingerprints(fingerprints);
        store.archiveLegacyEntities();
    }

    // -----------------------------------------------------------------------
    // Legacy search fallback
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> legacySearch(String projectId, String query,
                                                    String entityType, int maxResults)
            throws IOException {
        Path entitiesFile = getIndexDir(projectId).resolve("entities.json");
        if (!Files.exists(entitiesFile)) {
            throw new IOException("No index found for project '" + projectId +
                    "'. Run 'kompile code-index' first.");
        }

        List<Map<String, Object>> allEntities = objectMapper.readValue(
                entitiesFile.toFile(), List.class);

        String lowerQuery = query.toLowerCase();
        List<Map<String, Object>> results = new ArrayList<>();

        for (Map<String, Object> entity : allEntities) {
            if (entityType != null && !entityType.isEmpty()) {
                String type = (String) entity.get("entityType");
                if (!entityType.equalsIgnoreCase(type)) continue;
            }

            String name = (String) entity.getOrDefault("name", "");
            String fqn = (String) entity.getOrDefault("fullyQualifiedName", "");
            String sig = (String) entity.getOrDefault("signature", "");
            String doc = (String) entity.getOrDefault("docComment", "");

            if (name.toLowerCase().contains(lowerQuery) ||
                    fqn.toLowerCase().contains(lowerQuery) ||
                    (sig != null && sig.toLowerCase().contains(lowerQuery)) ||
                    (doc != null && doc.toLowerCase().contains(lowerQuery))) {
                results.add(entity);
                if (results.size() >= maxResults) break;
            }
        }

        return results;
    }

    // -----------------------------------------------------------------------
    // Extraction
    // -----------------------------------------------------------------------

    private List<Map<String, Object>> parseEntities(String[] lines, String filePath,
                                                     String projectId, String language) {
        return switch (language) {
            case "java", "kotlin", "scala", "groovy" -> parseJvm(lines, filePath, projectId, language);
            case "python" -> parsePython(lines, filePath, projectId, language);
            case "go" -> parseGo(lines, filePath, projectId, language);
            case "rust" -> parseRust(lines, filePath, projectId, language);
            case "javascript", "typescript" -> parseTypeScript(lines, filePath, projectId, language);
            case "c", "cpp", "csharp" -> parseCFamily(lines, filePath, projectId, language);
            case "splan" -> parseSplan(lines, filePath, projectId, language);
            default -> List.of();
        };
    }

    private List<Map<String, Object>> parseJvm(String[] lines, String filePath,
                                                String projectId, String lang) {
        List<Map<String, Object>> entities = new ArrayList<>();
        String packageName = null;
        String currentClass = null;
        StringBuilder docBuffer = new StringBuilder();
        boolean inDocComment = false;
        // Spring annotation state — tracks annotations on the NEXT class/field/method
        List<String> pendingAnnotations = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineNum = i + 1;

            // Doc comments
            if (line.trim().startsWith("/**")) {
                inDocComment = true;
                docBuffer.setLength(0);
                docBuffer.append(line.trim());
            }
            if (inDocComment) {
                if (!line.trim().startsWith("/**")) docBuffer.append("\n").append(line.trim());
                if (line.contains("*/")) inDocComment = false;
                continue;
            }

            // Package
            Matcher m = JAVA_PACKAGE.matcher(line);
            if (m.find()) {
                packageName = m.group(1);
                entities.add(makeEntity(projectId, "PACKAGE", packageName, packageName,
                        filePath, lang, lineNum, lineNum, null, null));
                continue;
            }

            // Import
            m = JAVA_IMPORT.matcher(line);
            if (m.find()) {
                String imported = m.group(2);
                entities.add(makeEntity(projectId, "IMPORT", imported, imported,
                        filePath, lang, lineNum, lineNum, null, null));
                continue;
            }

            // Spring annotation scanning — collect annotations for next entity
            Matcher mSpringComp = SPRING_COMPONENT.matcher(line);
            if (mSpringComp.find()) {
                String annotationType = mSpringComp.group(1);
                String value = mSpringComp.group(2);
                pendingAnnotations.add("@" + annotationType + (value != null ? "(" + value + ")" : ""));
            }
            Matcher mSpringPrimary = SPRING_PRIMARY.matcher(line);
            if (mSpringPrimary.find()) {
                pendingAnnotations.add("@Primary");
            }
            Matcher mSpringLazy = SPRING_LAZY.matcher(line);
            if (mSpringLazy.find()) {
                pendingAnnotations.add("@Lazy");
            }
            Matcher mSpringCond = SPRING_CONDITIONAL.matcher(line);
            if (mSpringCond.find()) {
                String condType = mSpringCond.group(1);
                String condValue = mSpringCond.group(2);
                pendingAnnotations.add("@ConditionalOn" + condType + "(" + condValue + ")");
            }
            Matcher mSpringQualifier = SPRING_QUALIFIER.matcher(line);
            if (mSpringQualifier.find()) {
                pendingAnnotations.add("@Qualifier(\"" + mSpringQualifier.group(1) + "\")");
            }
            Matcher mSpringAutowired = SPRING_AUTOWIRED.matcher(line);
            if (mSpringAutowired.find()) {
                String annotationType = mSpringAutowired.group(1);
                pendingAnnotations.add("@" + annotationType);
            }

            // Class / Interface / Enum / Record
            m = JAVA_CLASS.matcher(line);
            if (m.find()) {
                String visibility = m.group(1);
                String kind = m.group(5);
                String name = m.group(6);
                String extendsClass = m.group(7);
                String implementsStr = m.group(8);
                String fqn = packageName != null ? packageName + "." + name : name;
                currentClass = fqn;
                String entityType = switch (kind) {
                    case "interface" -> "INTERFACE";
                    case "enum" -> "ENUM";
                    case "record" -> "RECORD";
                    case "@interface" -> "ANNOTATION";
                    default -> "CLASS";
                };
                String doc = docBuffer.length() > 0 ? docBuffer.toString() : null;
                docBuffer.setLength(0);
                Map<String, Object> entity = makeEntity(projectId, entityType, name, fqn,
                        filePath, lang, lineNum, lineNum, line.trim(), doc);
                if (visibility != null) entity.put("visibility", visibility);
                if (extendsClass != null && !extendsClass.isBlank())
                    entity.put("inheritedFrom", extendsClass.trim());
                if (implementsStr != null && !implementsStr.isBlank()) {
                    String cleaned = cleanTypeList(implementsStr);
                    if (!cleaned.isEmpty()) entity.put("implementsList", cleaned);
                }
                if (!pendingAnnotations.isEmpty()) {
                    entity.put("annotations", String.join(", ", pendingAnnotations));
                    pendingAnnotations.clear();
                }
                entities.add(entity);
                continue;
            }

            // Method
            m = JAVA_METHOD.matcher(line);
            if (m.find() && !line.contains("new ") && !line.trim().startsWith("//")) {
                String visibility = m.group(1);
                String methodName = m.group(5);
                String params = m.group(6);
                if (Set.of("if", "for", "while", "switch", "catch", "return", "throw").contains(methodName)) continue;
                String fqn = currentClass != null ? currentClass + "." + methodName : methodName;
                String sig = methodName + "(" + (params != null ? params.trim() : "") + ")";
                String doc = docBuffer.length() > 0 ? docBuffer.toString() : null;
                docBuffer.setLength(0);
                Map<String, Object> entity = makeEntity(projectId, "METHOD", methodName, fqn,
                        filePath, lang, lineNum, lineNum, sig, doc);
                if (visibility != null) entity.put("visibility", visibility);
                if (!pendingAnnotations.isEmpty()) {
                    entity.put("annotations", String.join(", ", pendingAnnotations));
                    pendingAnnotations.clear();
                }
                entities.add(entity);
                continue;
            }

            // Field
            Matcher mField = JAVA_FIELD.matcher(line);
            if (mField.find() && !line.trim().startsWith("//")) {
                String visibility = mField.group(1);
                String fieldName = mField.group(5);
                String fqn = currentClass != null ? currentClass + "." + fieldName : fieldName;
                String doc = docBuffer.length() > 0 ? docBuffer.toString() : null;
                docBuffer.setLength(0);
                Map<String, Object> entity = makeEntity(projectId, "FIELD", fieldName, fqn,
                        filePath, lang, lineNum, lineNum, line.trim(), doc);
                if (visibility != null) entity.put("visibility", visibility);
                if (!pendingAnnotations.isEmpty()) {
                    entity.put("annotations", String.join(", ", pendingAnnotations));
                    pendingAnnotations.clear();
                }
                entities.add(entity);
                continue;
            }

            // Clear pending annotations on blank lines (annotations must be immediately before the entity)
            if (line.trim().isEmpty()) {
                pendingAnnotations.clear();
            }

            docBuffer.setLength(0);
        }
        return entities;
    }

    private List<Map<String, Object>> parsePython(String[] lines, String filePath,
                                                   String projectId, String lang) {
        List<Map<String, Object>> entities = new ArrayList<>();
        String currentClass = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineNum = i + 1;

            Matcher m = PY_IMPORT.matcher(line);
            if (m.find()) {
                String module = m.group(1) != null ? m.group(1) : m.group(2).trim();
                entities.add(makeEntity(projectId, "IMPORT", module, module,
                        filePath, lang, lineNum, lineNum, null, null));
                continue;
            }

            m = PY_CLASS.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String basesStr = m.group(2);
                currentClass = name;
                Map<String, Object> entity = makeEntity(projectId, "CLASS", name, name,
                        filePath, lang, lineNum, lineNum, line.trim(), null);
                if (basesStr != null && !basesStr.isBlank()) {
                    String cleaned = cleanTypeList(basesStr);
                    if (!cleaned.isEmpty()) entity.put("inheritedFrom", cleaned);
                }
                entities.add(entity);
                continue;
            }

            m = PY_FUNC.matcher(line);
            if (m.find()) {
                String name = m.group(2);
                String params = m.group(3);
                String fqn = currentClass != null && line.startsWith("    ") ?
                        currentClass + "." + name : name;
                String entityType = currentClass != null && line.startsWith("    ") ? "METHOD" : "FUNCTION";
                String sig = name + "(" + (params != null ? params.trim() : "") + ")";
                entities.add(makeEntity(projectId, entityType, name, fqn,
                        filePath, lang, lineNum, lineNum, sig, null));
                continue;
            }

            if (!line.startsWith(" ") && !line.startsWith("\t") && !line.trim().isEmpty()) {
                if (!line.trim().startsWith("#") && !line.trim().startsWith("@")) {
                    currentClass = null;
                }
            }
        }
        return entities;
    }

    private List<Map<String, Object>> parseGo(String[] lines, String filePath,
                                               String projectId, String lang) {
        List<Map<String, Object>> entities = new ArrayList<>();
        String packageName = null;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineNum = i + 1;

            Matcher m = GO_PACKAGE.matcher(line);
            if (m.find()) {
                packageName = m.group(1);
                entities.add(makeEntity(projectId, "PACKAGE", packageName, packageName,
                        filePath, lang, lineNum, lineNum, null, null));
                continue;
            }

            m = GO_TYPE.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String kind = m.group(2);
                String entityType = "interface".equals(kind) ? "INTERFACE" : "CLASS";
                String fqn = packageName != null ? packageName + "." + name : name;
                entities.add(makeEntity(projectId, entityType, name, fqn,
                        filePath, lang, lineNum, lineNum, line.trim(), null));
                continue;
            }

            m = GO_FUNC.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                String params = m.group(2);
                String fqn = packageName != null ? packageName + "." + name : name;
                String sig = name + "(" + (params != null ? params.trim() : "") + ")";
                entities.add(makeEntity(projectId, "FUNCTION", name, fqn,
                        filePath, lang, lineNum, lineNum, sig, null));
            }
        }
        return entities;
    }

    private List<Map<String, Object>> parseRust(String[] lines, String filePath,
                                                 String projectId, String lang) {
        List<Map<String, Object>> entities = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineNum = i + 1;

            Matcher m = RUST_STRUCT.matcher(line);
            if (m.find()) {
                String name = m.group(2);
                String vis = m.group(1) != null ? "public" : null;
                Map<String, Object> e = makeEntity(projectId, "CLASS", name, name,
                        filePath, lang, lineNum, lineNum, line.trim(), null);
                if (vis != null) e.put("visibility", vis);
                entities.add(e);
                continue;
            }

            m = RUST_ENUM.matcher(line);
            if (m.find()) {
                String name = m.group(2);
                entities.add(makeEntity(projectId, "ENUM", name, name,
                        filePath, lang, lineNum, lineNum, line.trim(), null));
                continue;
            }

            m = RUST_TRAIT.matcher(line);
            if (m.find()) {
                String name = m.group(2);
                entities.add(makeEntity(projectId, "INTERFACE", name, name,
                        filePath, lang, lineNum, lineNum, line.trim(), null));
                continue;
            }

            m = RUST_IMPL.matcher(line);
            if (m.find()) {
                String traitName = m.group(1); // null for inherent impl
                String typeName = m.group(2);
                if (traitName != null) {
                    // impl Trait for Type — record the trait implementation
                    Map<String, Object> e = makeEntity(projectId, "CLASS", typeName, typeName,
                            filePath, lang, lineNum, lineNum, line.trim(), null);
                    e.put("implementsList", traitName.trim());
                    entities.add(e);
                }
                continue;
            }

            m = RUST_FN.matcher(line);
            if (m.find()) {
                String name = m.group(2);
                String vis = m.group(1) != null ? "public" : null;
                Map<String, Object> e = makeEntity(projectId, "FUNCTION", name, name,
                        filePath, lang, lineNum, lineNum, line.trim(), null);
                if (vis != null) e.put("visibility", vis);
                entities.add(e);
            }
        }
        return entities;
    }

    private List<Map<String, Object>> parseTypeScript(String[] lines, String filePath,
                                                       String projectId, String lang) {
        List<Map<String, Object>> entities = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineNum = i + 1;

            Matcher m = TS_INTERFACE.matcher(line);
            if (m.find()) {
                String name = m.group(2);
                String extendsStr = m.group(3);
                Map<String, Object> entity = makeEntity(projectId, "INTERFACE", name, name,
                        filePath, lang, lineNum, lineNum, line.trim(), null);
                if (extendsStr != null && !extendsStr.isBlank()) {
                    String cleaned = cleanTypeList(extendsStr);
                    if (!cleaned.isEmpty()) entity.put("implementsList", cleaned);
                }
                entities.add(entity);
                continue;
            }

            m = TS_CLASS.matcher(line);
            if (m.find()) {
                String name = m.group(3);
                String extendsClass = m.group(4);
                String implementsStr = m.group(5);
                Map<String, Object> entity = makeEntity(projectId, "CLASS", name, name,
                        filePath, lang, lineNum, lineNum, line.trim(), null);
                if (extendsClass != null && !extendsClass.isBlank())
                    entity.put("inheritedFrom", extendsClass.trim());
                if (implementsStr != null && !implementsStr.isBlank()) {
                    String cleaned = cleanTypeList(implementsStr);
                    if (!cleaned.isEmpty()) entity.put("implementsList", cleaned);
                }
                entities.add(entity);
                continue;
            }

            m = TS_FUNC.matcher(line);
            if (m.find()) {
                String name = m.group(3);
                entities.add(makeEntity(projectId, "FUNCTION", name, name,
                        filePath, lang, lineNum, lineNum, line.trim(), null));
            }
        }
        return entities;
    }

    private List<Map<String, Object>> parseCFamily(String[] lines, String filePath,
                                                    String projectId, String lang) {
        List<Map<String, Object>> entities = new ArrayList<>();
        Pattern funcPattern = Pattern.compile("^\\s*(?:static\\s+|virtual\\s+|inline\\s+)*" +
                "(?:[\\w:*&<>]+\\s+)+(\\w+)\\s*\\([^;]*\\)\\s*\\{?\\s*$");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineNum = i + 1;

            Matcher m = C_CLASS.matcher(line);
            if (m.find()) {
                String name = m.group(2);
                String basesStr = m.group(3);
                Map<String, Object> entity = makeEntity(projectId, "CLASS", name, name,
                        filePath, lang, lineNum, lineNum, line.trim(), null);
                if (basesStr != null && !basesStr.isBlank()) {
                    // Strip access specifiers: "public Base, private Mixin" → "Base, Mixin"
                    String cleaned = Arrays.stream(basesStr.split(","))
                            .map(b -> b.trim().replaceFirst("^(public|protected|private)\\s+", "").trim())
                            .filter(b -> !b.isEmpty())
                            .collect(Collectors.joining(", "));
                    if (!cleaned.isEmpty()) entity.put("inheritedFrom", cleaned);
                }
                entities.add(entity);
                continue;
            }

            m = funcPattern.matcher(line);
            if (m.find()) {
                String name = m.group(1);
                if (!Set.of("if", "for", "while", "switch", "catch", "return").contains(name)) {
                    entities.add(makeEntity(projectId, "FUNCTION", name, name,
                            filePath, lang, lineNum, lineNum, line.trim(), null));
                }
            }
        }
        return entities;
    }

    // -----------------------------------------------------------------------
    // Splan parsing — mirrors server-side SplanLanguageParser
    // -----------------------------------------------------------------------

    private static final int SPLAN_CONTENT_PREVIEW_MAX = 200;

    /**
     * Parse splan files into structured entities matching the server-side
     * {@code SplanLanguageParser} entity mapping:
     * <ul>
     *   <li>Section → MODULE (named section-0, section-1, …)</li>
     *   <li>Declaration → CONSTANT (named after declaration key)</li>
     *   <li>Operation → FUNCTION (command as name, argument types in signature)</li>
     *   <li>ContentBlock → FIELD (content preview + delimiter metadata)</li>
     * </ul>
     */
    private List<Map<String, Object>> parseSplan(String[] lines, String filePath,
                                                  String projectId, String language) {
        List<Map<String, Object>> entities = new ArrayList<>();

        String fullText = String.join("\n", lines);
        SplanPlanParser.Plan plan = SplanPlanParser.parse(fullText);

        int runningLine = 1;

        for (SplanPlanParser.Section section : plan.sections()) {
            String sectionName = "section-" + section.index();
            String sectionFqn = filePath + ":" + sectionName;

            // Estimate section start line
            int sectionStartLine = estimateSplanSectionStart(section, runningLine);

            // Section → MODULE
            Map<String, Object> sectionEntity = makeEntity(projectId, "MODULE", sectionName, sectionFqn,
                    filePath, language, sectionStartLine, sectionStartLine, null, null);
            sectionEntity.put("parentFqn", filePath);
            entities.add(sectionEntity);

            // Declarations → CONSTANT
            int declLine = sectionStartLine;
            for (Map.Entry<String, String> entry : section.declarations().entrySet()) {
                String declName = entry.getKey();
                String declContent = entry.getValue();
                String declFqn = filePath + ":" + declName;

                String preview = declContent != null && declContent.length() > SPLAN_CONTENT_PREVIEW_MAX
                        ? declContent.substring(0, SPLAN_CONTENT_PREVIEW_MAX)
                        : declContent;

                Map<String, Object> declEntity = makeEntity(projectId, "CONSTANT", declName, declFqn,
                        filePath, language, declLine, declLine, null, null);
                declEntity.put("parentFqn", sectionFqn);
                if (preview != null) declEntity.put("contentPreview", preview);
                entities.add(declEntity);

                declLine++;
            }

            // Operations → FUNCTION (+ ContentBlock → FIELD)
            for (SplanPlanParser.Operation op : section.operations()) {
                String opName = op.command();
                String opFqn = filePath + ":" + sectionName + ":" + opName + "@" + op.lineNumber();

                String signature = buildSplanSignature(op);

                Map<String, Object> opEntity = makeEntity(projectId, "FUNCTION", opName, opFqn,
                        filePath, language, op.lineNumber(), op.lineNumber(), signature, null);
                opEntity.put("parentFqn", sectionFqn);
                entities.add(opEntity);

                // Process arguments
                int argIndex = 0;
                for (SplanPlanParser.Argument arg : op.arguments()) {
                    if (arg instanceof SplanPlanParser.Argument.DeclRef ref) {
                        // Track dependency in entity metadata
                        String declFqn = filePath + ":" + ref.name();
                        opEntity.computeIfAbsent("dependsOn", k -> new ArrayList<String>());
                        @SuppressWarnings("unchecked")
                        List<String> deps = (List<String>) opEntity.get("dependsOn");
                        deps.add(declFqn);
                    } else if (arg instanceof SplanPlanParser.Argument.ContentBlock block) {
                        // ContentBlock → FIELD
                        String blockName = opName + "-block-" + argIndex;
                        String blockFqn = opFqn + ":" + blockName;

                        String blockPreview = block.content().length() > SPLAN_CONTENT_PREVIEW_MAX
                                ? block.content().substring(0, SPLAN_CONTENT_PREVIEW_MAX)
                                : block.content();

                        Map<String, Object> blockEntity = makeEntity(projectId, "FIELD", blockName, blockFqn,
                                filePath, language, op.lineNumber(), op.lineNumber(), null, null);
                        blockEntity.put("parentFqn", opFqn);
                        blockEntity.put("contentPreview", blockPreview);
                        blockEntity.put("metadataJson", "{\"delimiter\":\"" + block.delimiter() + "\"}");
                        entities.add(blockEntity);
                    }
                    argIndex++;
                }

                runningLine = Math.max(runningLine, op.lineNumber());
            }

            runningLine++;
        }

        return entities;
    }

    /**
     * Build a human-readable signature for a splan operation, e.g.:
     * {@code "write (token token declRef:name contentBlock[:::])"}
     */
    private String buildSplanSignature(SplanPlanParser.Operation op) {
        if (op.arguments().isEmpty()) {
            return op.command() + "()";
        }
        StringJoiner argTypes = new StringJoiner(" ");
        for (SplanPlanParser.Argument arg : op.arguments()) {
            if (arg instanceof SplanPlanParser.Argument.Token) {
                argTypes.add("token");
            } else if (arg instanceof SplanPlanParser.Argument.DeclRef r) {
                argTypes.add("declRef:" + r.name());
            } else if (arg instanceof SplanPlanParser.Argument.ContentBlock b) {
                argTypes.add("contentBlock[" + b.delimiter() + "]");
            }
        }
        return op.command() + " (" + argTypes + ")";
    }

    /**
     * Estimate the 1-based start line of a splan section.
     */
    private int estimateSplanSectionStart(SplanPlanParser.Section section, int runningLine) {
        OptionalInt minLine = section.operations().stream()
                .mapToInt(SplanPlanParser.Operation::lineNumber)
                .min();
        if (minLine.isPresent()) {
            return Math.max(1, minLine.getAsInt() - section.declarations().size());
        }
        return runningLine;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private Map<String, Object> makeEntity(String projectId, String entityType, String name,
                                            String fqn, String filePath, String lang,
                                            int startLine, int endLine, String signature,
                                            String docComment) {
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("projectId", projectId);
        entity.put("entityType", entityType);
        entity.put("name", name);
        entity.put("fullyQualifiedName", fqn);
        entity.put("filePath", filePath);
        entity.put("language", lang);
        entity.put("startLine", startLine);
        entity.put("endLine", endLine);
        if (signature != null) entity.put("signature", signature);
        if (docComment != null) entity.put("docComment", docComment);
        // indexedAt is set by the caller at batch level, not per-entity
        return entity;
    }

    /**
     * Normalize a comma-separated type list: trim whitespace, remove empty segments.
     */
    private static String cleanTypeList(String raw) {
        if (raw == null || raw.isBlank()) return "";
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining(", "));
    }

    private String detectLanguage(Path filePath) {
        String fileName = filePath.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0) {
            String ext = fileName.substring(dot);
            return EXTENSION_TO_LANG.get(ext);
        }
        return null;
    }

    /**
     * A source file discovered during the walk, carrying the stat data the
     * visitor already had — the diff and fingerprint steps reuse it instead
     * of re-statting every file (previously each file was statted up to three
     * times per pass).
     */
    private record SourceFile(Path path, String relPath, long mtime, long size) {}

    private List<SourceFile> collectSourceFiles(Path root, Set<String> includes,
                                                Set<String> excludes) throws IOException {
        List<SourceFile> files = new ArrayList<>();
        SearchExclusions.GitignoreDirFilter gitFilter = SearchExclusions.loadGitignoreDirFilter(root);
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String dirName = dir.getFileName().toString();
                if (IGNORED_DIRS.contains(dirName)) return FileVisitResult.SKIP_SUBTREE;
                // Project-specific git-ignored data directories (no hard-coded names).
                if (gitFilter.isIgnoredDir(root.relativize(dir).toString(), dirName)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!excludes.isEmpty() && matchesAny(dir.toString(), excludes)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.size() > 1_000_000) return FileVisitResult.CONTINUE;
                if (detectLanguage(file) == null) return FileVisitResult.CONTINUE;

                String fileName = file.getFileName().toString();
                if (!includes.isEmpty() && !matchesAny(fileName, includes)) {
                    return FileVisitResult.CONTINUE;
                }
                if (!excludes.isEmpty() && matchesAny(fileName, excludes)) {
                    return FileVisitResult.CONTINUE;
                }
                files.add(new SourceFile(file, root.relativize(file).toString(),
                        attrs.lastModifiedTime().toMillis(), attrs.size()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    private boolean matchesAny(String value, Set<String> patterns) {
        for (String pattern : patterns) {
            if (pattern.startsWith("*") && value.endsWith(pattern.substring(1))) return true;
            if (pattern.endsWith("*") && value.startsWith(pattern.substring(0, pattern.length() - 1))) return true;
            if (value.contains(pattern)) return true;
        }
        return false;
    }

    private Set<String> parsePatterns(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) return Set.of();
        return Set.of(commaSeparated.split(","));
    }

    public static Path getBaseIndexDir() {
        return Path.of(System.getProperty("user.home"), ".kompile", "code-index");
    }

    public static Path getIndexDir(String projectId) {
        return getBaseIndexDir().resolve(projectId);
    }

    // -----------------------------------------------------------------------
    // Result type
    // -----------------------------------------------------------------------

    public record IndexResult(
            String projectId,
            String rootPath,
            int filesProcessed,
            int entitiesFound,
            int errors,
            Map<String, Integer> languageCounts,
            int filesSkipped,
            int filesDeleted
    ) {}
}
