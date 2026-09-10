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

import java.nio.charset.CharacterCodingException;
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

    private static final int RELATION_EXTRACTION_VERSION = 2;

    private static final Set<String> IGNORED_DIRS = Set.of(
            ".git", ".svn", ".hg", "node_modules", "__pycache__", ".gradle",
            "target", "build", "dist", "out", ".idea", ".vscode", ".settings",
            ".kompile", ".claude", ".codex", ".gemini", ".opencode", ".cursor",
            "vendor", "venv", ".venv", ".tox", ".mypy_cache", ".pytest_cache", ".angular",
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
    private static final Pattern JAVA_PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;?");
    private static final Pattern JAVA_IMPORT = Pattern.compile("^\\s*import\\s+(static\\s+)?([\\w.*]+)\\s*;?");
    private static final Pattern JAVA_CLASS = Pattern.compile(
            "^\\s*(public|protected|private)?\\s*(static\\s+)?(abstract\\s+)?(final\\s+)?" +
                    "(class|interface|enum|record|@interface)\\s+(\\w+)(?:<[^>]*>)?" +
                    "(?:\\(.*\\))?(?:\\s+extends\\s+([\\w.<>,\\s]+?))?" +
                    "(?:\\s+implements\\s+([\\w.<>,\\s]+?))?(?:\\s+permits\\s+[\\w.<>,\\s]+)?" +
                    "\\s*(?:\\{|$)", Pattern.DOTALL);
    private static final Pattern JVM_CLASS_PREFIX = Pattern.compile(
            "^\\s*(public|protected|private)?\\s*(static\\s+)?(abstract\\s+)?(final\\s+)?" +
                    "(class|interface|enum|record|@interface)\\s+(\\w+)(?:\\s+extends\\s+([\\w.]+))?" +
                    "(?:\\s+implements\\s+([\\w.,\\s]+))?");
    private static final Pattern JAVA_METHOD = Pattern.compile(
            "^\\s*(public|protected|private)?\\s*(static\\s+)?(abstract\\s+)?(?:synchronized\\s+)?" +
                    "(?:(?:final|native|default)\\s+)*(?:<[^>]+>\\s+)?" +
                    "([?@\\w$][\\w$<>\\[\\].,?]*(?:\\s+[?@\\w$][\\w$<>\\[\\].,?]*)*)\\s+" +
                    "(\\w+)\\s*\\(([^)]*)\\)\\s*(?:throws\\s+[\\w$.,<>?\\s]+)?\\s*(?:\\{|;|$)");
    private static final Pattern JVM_METHOD_PREFIX = Pattern.compile(
            "^\\s*(public|protected|private)?\\s*(static\\s+)?(abstract\\s+)?(?:synchronized\\s+)?" +
                    "((?:[\\w.<>\\[\\],?\\s]+)\\s+)(\\w+)\\s*\\(([^)]*)\\)");
    private static final Pattern JAVA_CONSTRUCTOR = Pattern.compile(
            "^\\s*(public|protected|private)?\\s*(\\w+)\\s*\\(([^)]*)\\)\\s*" +
                    "(?:throws\\s+[\\w$.,<>?\\s]+)?\\s*\\{");
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
            Map<String, Object> priorMetadata = store.loadMetadata();
            // The periodic background pass must check the DB even when source fingerprints are clean.
            // A marker survives crashes between SQLite commit and JSON publication.
            Path pendingUpdate = indexDir.resolve("update.pending");
            IndexMaintenance.Result maintenance = IndexMaintenance.checkLocked(indexDir, priorMetadata, forceReindex);
            forceReindex |= maintenance.reindexRequired() || Files.exists(pendingUpdate);
            // Unchanged file fingerprints cannot validate relationships produced by an older parser.
            forceReindex |= !Integer.valueOf(RELATION_EXTRACTION_VERSION)
                    .equals(priorMetadata.get("relationExtractionVersion"));

            out.println("Indexing: " + absRoot + (forceReindex ? " (full re-index)" : " (incremental)"));
            out.println("Project: " + projectId);

            // Load existing fingerprints
            Map<String, IndexFileStore.FileFingerprint> oldFingerprints =
                    store.loadFingerprints();

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
                if (!forceReindex && old != null && old.lastModified() == file.mtime() && old.size() == file.size()) {
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

            // Fast path after throttled integrity maintenance: avoid entity scans and index rewrites.
            // The auto-refresher runs this method before read actions every 30s;
            // without this, every clean pass still paid a DB open, two COUNT
            // scans and a full fingerprints+metadata rewrite.
            if (!forceReindex && toReparse.isEmpty() && deleted.isEmpty()) {
                Map<String, Object> priorMeta = priorMetadata;
                if (!priorMeta.isEmpty()) {
                    String requestedIncludes = includes == null || includes.isBlank() ? null : includes;
                    String requestedExcludes = excludes == null || excludes.isBlank() ? null : excludes;
                    boolean scopeChanged = !Objects.equals(requestedIncludes,
                            stringMetadata(priorMeta.get("includePatterns")))
                            || !Objects.equals(requestedExcludes,
                            stringMetadata(priorMeta.get("excludePatterns")));
                    boolean clearPriorErrors = priorMeta.get("errors") instanceof Number n
                            && n.intValue() > 0;
                    if (fingerprintDriftOnly) {
                        store.saveFingerprints(oldFingerprints);
                    }
                    if (scopeChanged || clearPriorErrors) {
                        if (requestedIncludes == null) priorMeta.remove("includePatterns");
                        else priorMeta.put("includePatterns", requestedIncludes);
                        if (requestedExcludes == null) priorMeta.remove("excludePatterns");
                        else priorMeta.put("excludePatterns", requestedExcludes);
                        // Membership is unchanged: preserve the committed SQLite generation.
                        priorMeta.put("errors", 0);
                        priorMeta.put("filesReindexed", 0);
                        store.saveMetadata(priorMeta);
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
            Map<String, IndexFileStore.FileFingerprint> newFingerprints = forceReindex
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(oldFingerprints);
            AtomicInteger errorCount = new AtomicInteger(0);
            Map<String, Integer> langCounts = new TreeMap<>();
            int totalEntities = 0;

            // Capture a single timestamp for the entire indexing run
            String indexTimestamp = Instant.now().toString();

            // A parsed file ready for publication by the single writer.
            record ParsedFile(String relPath, String lang, List<Map<String, Object>> entities,
                              List<Map<String, Object>> relations,
                              IndexFileStore.FileFingerprint fingerprint) {}

            // Parse workers only read and extract entities/relations. The writer publishes
            // shards under the project lock, so cancelled workers cannot overwrite a newer run.
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

                    // Workers are side-effect free: failed/cancelled runs cannot publish shards
                    // after the writer releases the project lock.
                    return new ParsedFile(file.relPath(), lang, fileEntities, relations, fp);
                } catch (Exception e) {
                    int n = errorCount.incrementAndGet();
                    if (n <= 5) {
                        out.println(formatIndexingError(file, e));
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
            Files.writeString(pendingUpdate, indexTimestamp);
            try (IndexDatabase db = IndexDatabase.open(indexDir)) {
                db.beginTransaction();
                try {
                    // Full rebuilds clear unknown/stale membership too, within the same transaction.
                    if (forceReindex) db.clearIndex();
                    else db.deleteFiles(deleted);
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

                            store.writeFileShard(pf.relPath(), pf.fingerprint(), pf.entities());
                            newFingerprints.put(pf.relPath(), pf.fingerprint());
                            writtenPaths.add(pf.relPath());

                            processed++;
                            if (processed % 200 == 0) {
                                out.print("  Indexed " + processed + "/" + toReparse.size() + " files\r");
                                out.flush();
                            }
                        } catch (Exception e) {
                            // A batch may already have changed metadata but not FTS. Continuing
                            // and committing here creates logical SQLITE_CORRUPT_VTAB damage.
                            throw new IOException("Error writing file " + pf.relPath() + ": " + e.getMessage(), e);
                        }
                    }

                    // Resolve against the final declaration set BEFORE committing its generation.
                    // Otherwise readers could observe two different graphs with the same token.
                    if (!writtenPaths.isEmpty() || !deleted.isEmpty()) {
                        int resolved = db.ensureConnectivity(
                                writtenPaths.size() == sourceFiles.size() ? null : writtenPaths);
                        if (resolved > 0) {
                            out.println("  Graph: updated " + resolved + " cross-file relation targets");
                        }
                    }
                    String committedGeneration = forceReindex || !writtenPaths.isEmpty() || !deleted.isEmpty()
                            ? indexTimestamp
                            : stringMetadata(priorMetadata.get("indexedAt"));
                    db.setIndexGeneration(committedGeneration);
                    db.commit();
                    totalEntities = db.getEntityCount();

                    // Merge language counts from DB for full picture
                    if (toReparse.size() < sourceFiles.size()) {
                        langCounts = db.getLanguageCounts();
                    }

                } catch (Exception e) {
                    db.rollback();
                    IndexMaintenance.invalidate(indexDir);
                    throw new IOException("Index update failed: " + e.getMessage(), e);
                }
            } catch (java.sql.SQLException e) {
                IndexMaintenance.invalidate(indexDir);
                throw new IOException("Database error: " + e.getMessage(), e);
            } finally {
                if (parsePool != null) parsePool.shutdownNow();
            }

            // Save fingerprints
            store.saveFingerprints(newFingerprints);

            // Save metadata
            int successfulReindexed = Math.max(0, toReparse.size() - errorCount.get());
            Object previousGeneration = priorMetadata.get("indexedAt");
            String committedGeneration = forceReindex || !writtenPaths.isEmpty() || !deleted.isEmpty()
                    ? indexTimestamp
                    : previousGeneration == null ? null : previousGeneration.toString();
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("projectId", projectId);
            metadata.put("rootPath", absRoot.toString());
            if (committedGeneration != null) metadata.put("indexedAt", committedGeneration);
            metadata.put("filesProcessed", currentRelPaths.size());
            metadata.put("entitiesFound", totalEntities);
            metadata.put("errors", errorCount.get());
            metadata.put("languageCounts", langCounts);
            metadata.put("filesSkipped", skipped);
            metadata.put("filesDeleted", deleted.size());
            metadata.put("filesReindexed", successfulReindexed);
            metadata.put("relationExtractionVersion", RELATION_EXTRACTION_VERSION);
            if (includes != null && !includes.isBlank()) metadata.put("includePatterns", includes);
            if (excludes != null && !excludes.isBlank()) metadata.put("excludePatterns", excludes);
            store.saveMetadata(metadata);
            Files.deleteIfExists(pendingUpdate);
            if (forceReindex) IndexMaintenance.invalidate(indexDir);

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
     * Return structural entities declared in one indexed file.
     */
    public List<Map<String, Object>> entitiesForFile(String projectId, String filePath,
                                                      int maxResults) throws IOException {
        Path indexDir = getIndexDir(projectId);
        if (!Files.isRegularFile(indexDir.resolve("index.db"))) return List.of();
        try (IndexLockManager.LockToken ignored = IndexLockManager.acquireReadLock(projectId);
             IndexDatabase db = IndexDatabase.open(indexDir)) {
            List<Map<String, Object>> entities = db.getEntitiesForFile(filePath);
            return entities.size() <= maxResults
                    ? entities : new ArrayList<>(entities.subList(0, maxResults));
        } catch (java.sql.SQLException e) {
            throw new IOException("Unable to list entities for " + filePath, e);
        }
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
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> meta =
                                objectMapper.readValue(metaFile.toFile(), Map.class);
                        projects.add(meta);
                    } catch (IOException malformedProjectMetadata) {
                        // One stale/partial index must not disable root discovery for
                        // every healthy project in the shared local registry.
                    }
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

    public IndexFileWatcher createWatcher(Path rootDir, String projectId,
                                          String includes, String excludes, PrintStream out) {
        return new IndexFileWatcher(rootDir, projectId, this, includes, excludes, out);
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
        Deque<JvmTypeScope> typeScopes = new ArrayDeque<>();
        StringBuilder docBuffer = new StringBuilder();
        boolean inDocComment = false;
        // Spring annotation state — tracks annotations on the NEXT class/field/method
        List<String> pendingAnnotations = new ArrayList<>();
        boolean javaOrGroovy = "java".equals(lang) || "groovy".equals(lang);

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineNum = i + 1;
            while (!typeScopes.isEmpty() && i > typeScopes.peek().endIndex()) {
                typeScopes.pop();
            }
            String currentClass = typeScopes.isEmpty() ? null : typeScopes.peek().fqn();

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
            JvmDeclaration typeDeclaration = collectJvmDeclaration(lines, i);
            m = (javaOrGroovy ? JAVA_CLASS : JVM_CLASS_PREFIX)
                    .matcher(declarationForMatching(typeDeclaration.text()));
            if (m.find()) {
                String visibility = m.group(1);
                String kind = m.group(5);
                String name = m.group(6);
                String extendsClass = m.group(7);
                String implementsStr = m.group(8);
                String fqn = currentClass != null
                        ? currentClass + "." + name
                        : packageName != null ? packageName + "." + name : name;
                int endIndex = findJvmBlockEnd(lines, i, "groovy".equals(lang));
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
                        filePath, lang, lineNum, endIndex + 1, line.trim(), doc);
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
                typeScopes.push(new JvmTypeScope(fqn, endIndex));
                i = typeDeclaration.endIndex();
                continue;
            }

            JvmDeclaration declaration = collectJvmDeclaration(lines, i);
            String declarationText = declaration.text();
            String declarationMatchText = declarationForMatching(declarationText);

            // Constructor (kept as METHOD in the lightweight local schema).
            m = JAVA_CONSTRUCTOR.matcher(declarationMatchText);
            if (m.find() && currentClass != null
                    && m.group(2).equals(simpleName(currentClass))) {
                String visibility = m.group(1);
                String constructorName = m.group(2);
                String params = m.group(3);
                String fqn = currentClass + "." + constructorName;
                String sig = constructorName + "(" + (params != null ? params.trim() : "") + ")";
                String doc = docBuffer.length() > 0 ? docBuffer.toString() : null;
                docBuffer.setLength(0);
                int bodyEnd = declarationHasBody(declarationText)
                        ? findJvmBlockEnd(lines, i, "groovy".equals(lang)) : declaration.endIndex();
                Map<String, Object> entity = makeEntity(projectId, "METHOD", constructorName, fqn,
                        filePath, lang, lineNum, bodyEnd + 1, sig, doc);
                if (visibility != null) entity.put("visibility", visibility);
                if (!pendingAnnotations.isEmpty()) {
                    entity.put("annotations", String.join(", ", pendingAnnotations));
                    pendingAnnotations.clear();
                }
                entities.add(entity);
                i = bodyEnd;
                continue;
            }

            // Method. Collect a bounded multiline declaration before matching so
            // ordinary Java formatting does not make definitions disappear.
            m = (javaOrGroovy ? JAVA_METHOD : JVM_METHOD_PREFIX).matcher(declarationMatchText);
            if (m.find() && !declarationMatchText.substring(0, m.end()).contains("new ")
                    && !line.trim().startsWith("//")) {
                String visibility = m.group(1);
                String methodName = m.group(5);
                String params = m.group(6);
                if (Set.of("if", "for", "while", "switch", "catch", "return", "throw").contains(methodName)) continue;
                String fqn = currentClass != null ? currentClass + "." + methodName : methodName;
                String sig = methodName + "(" + (params != null ? params.trim() : "") + ")";
                String doc = docBuffer.length() > 0 ? docBuffer.toString() : null;
                docBuffer.setLength(0);
                int bodyEnd = declarationHasBody(declarationText)
                        ? findJvmBlockEnd(lines, i, "groovy".equals(lang)) : declaration.endIndex();
                Map<String, Object> entity = makeEntity(projectId, "METHOD", methodName, fqn,
                        filePath, lang, lineNum, bodyEnd + 1, sig, doc);
                if (visibility != null) entity.put("visibility", visibility);
                if (!pendingAnnotations.isEmpty()) {
                    entity.put("annotations", String.join(", ", pendingAnnotations));
                    pendingAnnotations.clear();
                }
                entities.add(entity);
                // Call sites are extracted separately from the recorded callable bounds.
                i = bodyEnd;
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

    private static String stringMetadata(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private static String formatIndexingError(SourceFile file, Exception error) {
        Throwable cause = error;
        while (cause != null && !(cause instanceof CharacterCodingException)) {
            cause = cause.getCause();
        }
        String detail;
        if (cause instanceof CharacterCodingException) {
            detail = "invalid UTF-8";
        } else {
            detail = error.getMessage();
            if (detail == null || detail.isBlank()) detail = error.getClass().getSimpleName();
        }
        return "  Error indexing " + file.relPath() + ": " + detail;
    }

    private record JvmTypeScope(String fqn, int endIndex) {}

    private record JvmDeclaration(String text, int endIndex) {}

    private static JvmDeclaration collectJvmDeclaration(String[] lines, int startIndex) {
        StringBuilder declaration = new StringBuilder(lines[startIndex].trim());
        int balance = parenthesisBalance(lines[startIndex]);
        int endIndex = startIndex;
        int limit = Math.min(lines.length, startIndex + 64);
        while (balance > 0 && endIndex + 1 < limit) {
            endIndex++;
            String next = lines[endIndex].trim();
            if (!next.isEmpty()) declaration.append('\n').append(next);
            balance += parenthesisBalance(lines[endIndex]);
        }
        boolean inContinuation = declarationContinues(declaration.toString());
        while (balance <= 0 && endIndex + 1 < limit
                && !declarationEndsHeader(declaration.toString())) {
            String next = lines[endIndex + 1].trim();
            if (next.isEmpty()) {
                endIndex++;
                continue;
            }
            boolean startsContinuation = next.startsWith("throws ") || next.startsWith("extends ")
                    || next.startsWith("implements ") || next.startsWith("permits ")
                    || next.startsWith("{");
            if (!inContinuation && !startsContinuation) break;
            endIndex++;
            declaration.append('\n').append(next);
            balance += parenthesisBalance(next);
            inContinuation = true;
        }
        return new JvmDeclaration(declaration.toString(), endIndex);
    }

    private static boolean declarationEndsHeader(String declaration) {
        String trimmed = declaration.trim();
        return trimmed.endsWith(";") || declarationHasBody(declaration);
    }

    private static boolean declarationContinues(String declaration) {
        int closingParenthesis = declaration.lastIndexOf(')');
        if (closingParenthesis >= 0) {
            String tail = declaration.substring(closingParenthesis + 1).trim();
            return tail.matches("^(?:throws|extends|implements|permits)\\b.*");
        }
        return declaration.matches("(?s).*\\b(?:extends|implements|permits)\\b[^;{]*$");
    }

    private static boolean declarationHasBody(String declaration) {
        boolean inString = false;
        boolean inChar = false;
        boolean inBlockComment = false;
        boolean escaped = false;
        int parentheses = 0;
        for (int i = 0; i < declaration.length(); i++) {
            char c = declaration.charAt(i);
            char next = i + 1 < declaration.length() ? declaration.charAt(i + 1) : 0;
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (!inString && !inChar && c == '/' && next == '/') {
                int newline = declaration.indexOf("\n", i + 2);
                if (newline < 0) return false;
                i = newline;
                continue;
            }
            if (!inString && !inChar && c == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }
            if (escaped) {
                escaped = false;
                continue;
            }
            if ((inString || inChar) && c == '\\') {
                escaped = true;
                continue;
            }
            if (!inChar && c == '"') {
                inString = !inString;
                continue;
            }
            if (!inString && c == '\'') {
                inChar = !inChar;
                continue;
            }
            if (!inString && !inChar) {
                if (c == '(') parentheses++;
                else if (c == ')' && parentheses > 0) parentheses--;
                else if (parentheses == 0 && c == ';') return false;
                else if (parentheses == 0 && c == '{') return true;
            }
        }
        return false;
    }

    private static String declarationForMatching(String declaration) {
        StringBuilder result = new StringBuilder(declaration.length());
        boolean inString = false;
        boolean inChar = false;
        boolean inBlockComment = false;
        boolean inLineComment = false;
        boolean escaped = false;
        for (int i = 0; i < declaration.length(); i++) {
            char c = declaration.charAt(i);
            char next = i + 1 < declaration.length() ? declaration.charAt(i + 1) : 0;
            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                    result.append(c);
                }
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    result.append(' ');
                    i++;
                }
                continue;
            }
            if (!inString && !inChar && c == '/' && next == '/') {
                inLineComment = true;
                i++;
                continue;
            }
            if (!inString && !inChar && c == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }
            result.append(c);
            if (escaped) {
                escaped = false;
                continue;
            }
            if ((inString || inChar) && c == '\\') {
                escaped = true;
            } else if (!inChar && c == '"') {
                inString = !inString;
            } else if (!inString && c == '\'') {
                inChar = !inChar;
            }
        }
        return result.toString();
    }

    private static int parenthesisBalance(String line) {
        int balance = 0;
        boolean inString = false;
        boolean inChar = false;
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if ((inString || inChar) && c == '\\') {
                escaped = true;
                continue;
            }
            if (!inChar && c == '"') {
                inString = !inString;
                continue;
            }
            if (!inString && c == '\'') {
                inChar = !inChar;
                continue;
            }
            if (!inString && !inChar) {
                if (c == '(') balance++;
                else if (c == ')') balance--;
            }
        }
        return balance;
    }

    private static String simpleName(String fqn) {
        int separator = fqn.lastIndexOf('.');
        return separator >= 0 ? fqn.substring(separator + 1) : fqn;
    }

    private static int findJvmBlockEnd(String[] lines, int startIndex) {
        return findJvmBlockEnd(lines, startIndex, false);
    }

    private static int findJvmBlockEnd(String[] lines, int startIndex, boolean groovy) {
        int braces = 0;
        int parentheses = 0;
        boolean foundOpen = false;
        boolean inBlockComment = false;
        boolean inTextBlock = false;
        boolean inGroovyTripleSingle = false;
        boolean inGroovyDollarSlashy = false;
        boolean inGroovySlashy = false;
        for (int i = startIndex; i < lines.length; i++) {
            String line = lines[i];
            boolean inString = false;
            boolean inChar = false;
            boolean escaped = false;
            for (int j = 0; j < line.length(); j++) {
                char c = line.charAt(j);
                char next = j + 1 < line.length() ? line.charAt(j + 1) : 0;
                boolean tripleQuote = c == '"' && j + 2 < line.length()
                        && line.charAt(j + 1) == '"' && line.charAt(j + 2) == '"'
                        && !isEscapedQuote(line, j);
                boolean tripleSingle = c == '\'' && j + 2 < line.length()
                        && line.charAt(j + 1) == '\'' && line.charAt(j + 2) == '\''
                        && !isEscapedQuote(line, j);
                if (inBlockComment) {
                    if (c == '*' && next == '/') {
                        inBlockComment = false;
                        j++;
                    }
                    continue;
                }
                if (inGroovyTripleSingle) {
                    if (tripleSingle) {
                        inGroovyTripleSingle = false;
                        j += 2;
                    }
                    continue;
                }
                if (inGroovyDollarSlashy) {
                    if (c == '/' && next == '$') {
                        inGroovyDollarSlashy = false;
                        j++;
                    }
                    continue;
                }
                if (inGroovySlashy) {
                    if (c == '\\') j++;
                    else if (c == '/') inGroovySlashy = false;
                    continue;
                }
                if (groovy && !inString && !inChar && !inTextBlock && tripleSingle) {
                    inGroovyTripleSingle = true;
                    j += 2;
                    continue;
                }
                if (groovy && !inString && !inChar && !inTextBlock && c == '$' && next == '/') {
                    inGroovyDollarSlashy = true;
                    j++;
                    continue;
                }
                if (!inString && !inChar && tripleQuote) {
                    inTextBlock = !inTextBlock;
                    j += 2;
                    continue;
                }
                if (inTextBlock) continue;
                if (!inString && !inChar && c == '/' && next == '/') break;
                if (!inString && !inChar && c == '/' && next == '*') {
                    inBlockComment = true;
                    j++;
                    continue;
                }
                if (groovy && !inString && !inChar && c == '/'
                        && isLikelyGroovySlashyStart(line, j)) {
                    inGroovySlashy = true;
                    continue;
                }
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if ((inString || inChar) && c == '\\') {
                    escaped = true;
                    continue;
                }
                if (!inChar && c == '"') {
                    inString = !inString;
                    continue;
                }
                if (!inString && c == '\'') {
                    inChar = !inChar;
                    continue;
                }
                if (!inString && !inChar) {
                    if (!foundOpen && c == '(') {
                        parentheses++;
                    } else if (!foundOpen && c == ')' && parentheses > 0) {
                        parentheses--;
                    } else if (c == '{' && (foundOpen || parentheses == 0)) {
                        braces++;
                        foundOpen = true;
                    } else if (c == '}' && foundOpen && --braces == 0) {
                        return i;
                    }
                }
            }
        }
        return startIndex;
    }

    private static boolean isEscapedQuote(String line, int quoteIndex) {
        int backslashes = 0;
        for (int i = quoteIndex - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        return (backslashes & 1) == 1;
    }

    private static boolean isLikelyGroovySlashyStart(String line, int slashIndex) {
        char next = slashIndex + 1 < line.length() ? line.charAt(slashIndex + 1) : 0;
        if (next == 0 || next == '/' || next == '*') return false;
        String prefix = line.substring(0, slashIndex).trim();
        if (prefix.isEmpty() || prefix.endsWith("return") || prefix.endsWith("case")) return true;
        char previous = prefix.charAt(prefix.length() - 1);
        return "=(:,[!&|?{;~+-*%^<>".indexOf(previous) >= 0;
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
                String relative = normalizeRelativePath(root.relativize(dir));
                if (relative.equals("data/crawls") || relative.startsWith("data/crawls/")
                        || relative.equals("data/code-projects") || relative.startsWith("data/code-projects/")
                        || relative.equals("data/graph") || relative.startsWith("data/graph/")
                        || relative.equals("data/markdown") || relative.startsWith("data/markdown/")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
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
                if ("kompile.project.json".equals(fileName)) return FileVisitResult.CONTINUE;
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

    private static String normalizeRelativePath(Path path) {
        return path.toString().replace('\\', '/');
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
        if (!isSafeProjectId(projectId)) {
            throw new IllegalArgumentException("Invalid code-index project id: " + projectId);
        }
        Path base = getBaseIndexDir().toAbsolutePath().normalize();
        Path resolved = base.resolve(projectId).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("Code-index project id escapes index root: " + projectId);
        }
        return resolved;
    }

    static boolean isSafeProjectId(String projectId) {
        if (projectId == null || projectId.isBlank()
                || ".".equals(projectId) || "..".equals(projectId)
                || projectId.contains("/") || projectId.contains("\\")) {
            return false;
        }
        try {
            Path path = Path.of(projectId);
            return !path.isAbsolute() && path.getNameCount() == 1;
        } catch (RuntimeException invalidPath) {
            return false;
        }
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
