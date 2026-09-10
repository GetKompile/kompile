/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.codeindex;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.query.GraphQueryEngine;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.graph.reasoning.unified.UnifiedGraphArchive;
import ai.kompile.project.KompileCodingProject;
import ai.kompile.project.KompileProjectManifest;
import ai.kompile.project.KompileProjectStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Resolves durable notes, structural index information, and a bounded projected-KGraph
 * neighborhood for one indexed source file. No lookup builds an index or graph.
 */
public final class FileContextService {

    public static final int DEFAULT_RENDER_CHARS = 6_000;
    public static final int MAX_RENDER_CHARS = 16_000;

    private static final String NOTE_SCHEMA = "kompile-file-notes/v1";
    private static final String NOTE_FILE = "file-notes.json";
    private static final int MAX_NOTE_STORE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_NOTES_PER_FILE = 12;
    private static final int MAX_NOTE_CHARS = 4_000;
    private static final int MAX_METADATA_NOTE_CHARS = 1_000;
    private static final int MAX_RETURNED_NOTES = 12;
    private static final int MAX_SYMBOLS = 24;
    private static final int MAX_INDEX_ENTITIES = 64;
    private static final int MAX_INDEX_RELATIONS = 32;
    private static final int MAX_RELATIONS = 32;
    private static final int MAX_GRAPH_NODES = 64;
    private static final int MAX_GRAPH_EDGES = 96;

    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();
    private static final Map<Path, ReentrantLock> NOTE_LOCKS = new ConcurrentHashMap<>();

    public ContextSnapshot lookup(Path file, Path workingDirectory) {
        Path normalized = file.toAbsolutePath().normalize();
        try {
            Descriptor descriptor = resolveIndexed(normalized, workingDirectory);
            return lookup(descriptor);
        } catch (Exception unavailable) {
            return ContextSnapshot.unavailable(normalized, concise(unavailable));
        }
    }

    public NoteMutation addNote(Path file, Path workingDirectory, String content,
                                String author, String sessionId) throws IOException {
        Descriptor descriptor = resolveIndexed(file, workingDirectory);
        String normalizedContent = content == null ? "" : content.trim();
        if (normalizedContent.isEmpty()) {
            throw new IOException("File note content must not be blank");
        }
        if (normalizedContent.length() > MAX_NOTE_CHARS) {
            throw new IOException("File note content has " + normalizedContent.length()
                    + " characters (max " + MAX_NOTE_CHARS + ")");
        }

        String noteId = UUID.randomUUID().toString();
        mutateNotes(descriptor, root -> {
            ArrayNode notes = notesArray(root, descriptor.relativePath(), true);
            if (notes.size() >= MAX_NOTES_PER_FILE) {
                throw new IOException("File already has the maximum " + MAX_NOTES_PER_FILE + " notes");
            }
            ObjectNode note = notes.addObject();
            note.put("id", noteId);
            note.put("content", normalizedContent);
            note.put("author", bounded(author, 120));
            note.put("sessionId", bounded(sessionId, 200));
            note.put("createdAt", Instant.now().toString());
        });
        return new NoteMutation(noteId, lookup(descriptor));
    }

    public NoteMutation deleteNote(Path file, Path workingDirectory, String noteId) throws IOException {
        Descriptor descriptor = resolveIndexed(file, workingDirectory);
        String requested = noteId == null ? "" : noteId.trim();
        if (requested.isEmpty()) throw new IOException("note_id is required");

        mutateNotes(descriptor, root -> {
            ArrayNode notes = notesArray(root, descriptor.relativePath(), false);
            if (notes == null) throw new IOException("File note not found: " + requested);
            for (int i = 0; i < notes.size(); i++) {
                if (requested.equals(notes.get(i).path("id").asText())) {
                    notes.remove(i);
                    ObjectNode files = (ObjectNode) root.path("files");
                    if (notes.isEmpty()) files.remove(descriptor.relativePath());
                    return;
                }
            }
            throw new IOException("File note not found: " + requested);
        });
        return new NoteMutation(requested, lookup(descriptor));
    }

    public String render(ContextSnapshot context, int requestedMaxChars) {
        int maxChars = Math.max(512, Math.min(MAX_RENDER_CHARS,
                requestedMaxChars > 0 ? requestedMaxChars : DEFAULT_RENDER_CHARS));
        StringBuilder out = new StringBuilder();
        out.append("--- File context (not file contents) ---\n");
        if (!context.available()) {
            out.append("Unavailable: ").append(context.indexStatus()).append('\n');
            return cap(out.toString(), maxChars);
        }

        out.append("Identity: ").append(context.codeProjectId()).append(':')
                .append(context.filePath()).append('\n');
        out.append("Index: ").append(context.indexStatus())
                .append(" (entities ").append(context.indexedEntityCount())
                .append(", returned relations ").append(context.indexedRelationCount()).append(")\n");
        out.append("KGraph: ").append(context.graphStatus());
        if (context.graphPath() != null) out.append(" [").append(context.graphPath()).append(']');
        out.append('\n');

        if (!context.notes().isEmpty()) {
            out.append("Note ids (newest first):\n");
            for (FileNote note : context.notes()) {
                out.append("- ").append(note.id());
                if (!note.author().isBlank()) out.append(" — ").append(note.author());
                out.append('\n');
            }
            out.append("Note summaries:\n");
            for (FileNote note : context.notes()) {
                out.append("- [").append(note.id()).append("] ")
                        .append(singleLine(note.content(), 800)).append('\n');
            }
        }

        if (!context.symbols().isEmpty()) {
            out.append("Indexed symbols:\n");
            for (SymbolSummary symbol : context.symbols()) {
                out.append("- ").append(symbol.type()).append(' ')
                        .append(firstNonBlank(symbol.fullyQualifiedName(), symbol.name()));
                if (symbol.line() != null) out.append(" @").append(symbol.line());
                if (!symbol.signature().isBlank()) {
                    out.append(" — ").append(singleLine(symbol.signature(), 300));
                }
                out.append('\n');
            }
        }

        if (!context.relations().isEmpty()) {
            out.append("Related graph edges:\n");
            for (RelationSummary relation : context.relations()) {
                out.append("- [").append(relation.origin()).append("] ")
                        .append(singleLine(relation.source(), 180)).append(" -")
                        .append(relation.type()).append("-> ")
                        .append(singleLine(relation.target(), 180)).append('\n');
            }
        }

        for (String warning : context.warnings()) {
            out.append("Warning: ").append(singleLine(warning, 500)).append('\n');
        }
        return cap(out.toString(), maxChars);
    }

    private ContextSnapshot lookup(Descriptor descriptor) {
        List<String> warnings = new ArrayList<>();
        List<FileNote> notes = readNotes(descriptor, warnings);
        List<SymbolSummary> symbols = new ArrayList<>();
        LinkedHashMap<String, RelationSummary> relations = new LinkedHashMap<>();
        List<String> seedIds = new ArrayList<>();
        int entityCount = 0;
        int indexRelationCount = 0;
        String indexStatus = "AVAILABLE";
        String snapshotGeneration = "";

        try (IndexLockManager.LockToken ignored = IndexLockManager.acquireReadLock(descriptor.codeProjectId());
             IndexDatabase database = IndexDatabase.openReadOnly(descriptor.indexDirectory())) {
            database.beginTransaction();
            Map<String, Object> fileGraph;
            try {
                fileGraph = database.getFileGraph(
                        descriptor.relativePath(), MAX_INDEX_ENTITIES, MAX_INDEX_RELATIONS);
                snapshotGeneration = text(database.getIndexGeneration());
                database.commit();
            } catch (Exception failure) {
                database.rollback();
                throw failure;
            }
            List<Map<String, Object>> entities = maps(fileGraph.get("entities"));
            entityCount = number(fileGraph.get("entityCount"));
            for (Map<String, Object> entity : entities) {
                String type = text(entity.get("entityType"));
                String fqn = text(entity.get("fullyQualifiedName"));
                String signature = text(entity.get("signature"));
                if (!type.isBlank() && !fqn.isBlank() && seedIds.size() < MAX_GRAPH_NODES) {
                    seedIds.add(CodeGraphIdentity.entityId(
                            descriptor.codeProjectId(), type, fqn, signature));
                }
                if (symbols.size() < MAX_SYMBOLS
                        && !Set.of("FILE", "PACKAGE", "IMPORT").contains(type)) {
                    symbols.add(new SymbolSummary(type, text(entity.get("name")), fqn, signature,
                            integer(entity.get("startLine"))));
                }
            }
            indexRelationCount += mergeIndexRelations(
                    maps(fileGraph.get("outgoingRelations")), relations);
            indexRelationCount += mergeIndexRelations(
                    maps(fileGraph.get("incomingRelations")), relations);
        } catch (Exception indexFailure) {
            indexStatus = "ERROR";
            warnings.add("Code index context failed: " + concise(indexFailure));
        }

        GraphLookup graph = "AVAILABLE".equals(indexStatus)
                ? graphContext(descriptor, seedIds, warnings, snapshotGeneration)
                : GraphLookup.unavailable("INDEX_UNAVAILABLE", descriptor.graphPath());
        for (RelationSummary relation : graph.relations()) mergeRelation(relations, relation);
        List<RelationSummary> boundedRelations = relations.values().stream()
                .limit(MAX_RELATIONS).toList();

        return new ContextSnapshot(true, descriptor.file().toString(), descriptor.projectRoot().toString(),
                descriptor.codeProjectId(), descriptor.relativePath(), indexStatus,
                graph.status(), graph.path(), graph.fresh(), notes, List.copyOf(symbols),
                boundedRelations, entityCount, indexRelationCount, graph.nodes(), graph.edges(),
                List.copyOf(warnings));
    }

    private GraphLookup graphContext(Descriptor descriptor, List<String> seedIds,
                                     List<String> warnings, String indexGeneration) {
        Path graphPath = descriptor.graphPath();
        if (graphPath == null || !Files.isRegularFile(graphPath)) {
            return GraphLookup.unavailable("NOT_PROJECTED", graphPath);
        }
        try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(graphPath)) {
            String graphGeneration = graphGeneration(archive, descriptor.codeProjectId());
            if (indexGeneration.isBlank() || graphGeneration.isBlank()) {
                warnings.add("Index or projected KGraph has no transactional generation; "
                        + "run a force re-index before using graph context");
                return GraphLookup.unavailable("GENERATION_UNVERIFIED", graphPath);
            }
            if (!indexGeneration.equals(graphGeneration)) {
                warnings.add("Projected KGraph is stale for code project " + descriptor.codeProjectId());
                return GraphLookup.unavailable("STALE", graphPath);
            }
            if (!archive.hasCompactTopology()) {
                warnings.add("Projected KGraph uses a legacy unbounded topology; run code index projection to upgrade it");
                return GraphLookup.unavailable("LEGACY_NOT_QUERIED", graphPath);
            }
            if (seedIds.isEmpty()) {
                return new GraphLookup("AVAILABLE_NO_FILE_ENTITIES",
                        graphPath.toString(), true, 0, 0, List.of());
            }

            UnifiedGraph neighborhood = archive.materializeNeighborhood(
                    seedIds, seedIds, GraphQueryEngine.Direction.BOTH, 1,
                    MAX_GRAPH_NODES, MAX_GRAPH_EDGES);
            Set<String> seeds = Set.copyOf(seedIds);
            Map<String, GraphEntity> entities = new LinkedHashMap<>();
            for (GraphEntity entity : neighborhood.entities()) entities.put(entity.id(), entity);
            List<RelationSummary> result = new ArrayList<>();
            for (GraphRelation relation : neighborhood.relations()) {
                if (!seeds.contains(relation.sourceId()) && !seeds.contains(relation.targetId())) continue;
                GraphEntity source = entities.get(relation.sourceId());
                GraphEntity target = entities.get(relation.targetId());
                result.add(new RelationSummary(relation.type(),
                        source != null ? source.label() : relation.sourceId(),
                        target != null ? target.label() : relation.targetId(), "kgraph"));
                if (result.size() >= MAX_RELATIONS) break;
            }
            return new GraphLookup("AVAILABLE", graphPath.toString(), true,
                    neighborhood.entityCount(), neighborhood.relationCount(), List.copyOf(result));
        } catch (Exception graphFailure) {
            warnings.add("Projected KGraph context failed: " + concise(graphFailure));
            return GraphLookup.unavailable("ERROR", graphPath);
        }
    }

    private Descriptor resolveIndexed(Path requested, Path workingDirectory) throws IOException {
        Path file = requested.toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) throw new IOException("Not a regular file: " + file);
        file = file.toRealPath();

        ProjectIdResolver.Resolution resolution;
        try {
            resolution = ProjectIdResolver.resolve(null, file.getParent());
        } catch (RuntimeException invalidProject) {
            throw new IOException("Cannot resolve code-index project: " + invalidProject.getMessage(), invalidProject);
        }
        String projectId = resolution.projectId();
        Path indexDirectory = LocalCodeIndexer.getIndexDir(projectId);
        if (!Files.isRegularFile(indexDirectory.resolve("index.db"))) {
            throw new IOException("No local code index contains this file; index the project first");
        }

        Map<String, Object> stats = new IndexFileStore(indexDirectory, MAPPER).loadMetadata();
        if (stats.isEmpty()) throw new IOException("Code index metadata is unavailable");
        String rootText = text(stats.get("rootPath"));
        if (rootText.isBlank()) throw new IOException("Code index has no recorded root path");
        Path codeRoot = Path.of(rootText).toAbsolutePath().normalize();
        if (!Files.isDirectory(codeRoot)) throw new IOException("Indexed root is unavailable: " + codeRoot);
        codeRoot = codeRoot.toRealPath();
        if (!file.startsWith(codeRoot)) {
            throw new IOException("Resolved index project does not contain file: " + projectId);
        }
        String relativePath = normalizePath(codeRoot.relativize(file).toString());

        KompileProjectStore store = new KompileProjectStore();
        ProjectOwner owner = resolveProjectOwner(
                store, workingDirectory, codeRoot, file, projectId);
        Path projectRoot = owner.root();
        KompileCodingProject codingProject = owner.codeProject();

        Path metadataDirectory = projectRoot.resolve("data/code-projects")
                .resolve(projectId).resolve("metadata");
        if (codingProject != null && codingProject.getMetadataPath() != null
                && !codingProject.getMetadataPath().isBlank()) {
            Path configured = Path.of(codingProject.getMetadataPath());
            metadataDirectory = configured.isAbsolute() ? configured : projectRoot.resolve(configured);
        }
        metadataDirectory = safeProjectPath(projectRoot, metadataDirectory, false);
        Path notePath = metadataDirectory.resolve(NOTE_FILE).normalize();

        Path graphPath = resolveGraphPath(projectRoot, codingProject);
        return new Descriptor(file, codeRoot, projectRoot, projectId, relativePath,
                indexDirectory, notePath, graphPath);
    }

    private ProjectOwner resolveProjectOwner(KompileProjectStore store, Path workingDirectory,
                                             Path codeRoot, Path file, String projectId)
            throws IOException {
        LinkedHashSet<Path> candidates = new LinkedHashSet<>();
        store.findProjectRoot(workingDirectory).ifPresent(candidates::add);
        store.findProjectRoot(codeRoot).ifPresent(candidates::add);
        for (Path candidate : candidates) {
            Path root = candidate.toRealPath();
            KompileCodingProject registered = registeredCodeProject(
                    store, root, projectId, file);
            if (registered != null) return new ProjectOwner(root, registered);
        }

        Path fallback = store.findProjectRoot(codeRoot).orElse(codeRoot).toRealPath();
        return new ProjectOwner(fallback,
                registeredCodeProject(store, fallback, projectId, file));
    }

    private KompileCodingProject registeredCodeProject(KompileProjectStore store, Path projectRoot,
                                                        String projectId, Path file) {
        Path manifestPath = projectRoot.resolve(KompileProjectStore.MANIFEST_FILE);
        if (!Files.isRegularFile(manifestPath)) return null;
        KompileProjectManifest manifest = store.load(projectRoot);
        for (KompileCodingProject project : manifest.getCodingProjects()) {
            if (!projectId.equals(firstNonBlank(project.getCodeProjectId(), project.getId()))) continue;
            String configuredRoot = project.getRootPath();
            if (configuredRoot == null || configuredRoot.isBlank()) continue;
            try {
                Path root = Path.of(configuredRoot);
                if (!root.isAbsolute()) root = projectRoot.resolve(root);
                if (file.startsWith(root.toRealPath())) return project;
            } catch (Exception ignored) {
                // An invalid registration cannot own this file; try another candidate.
            }
        }
        return null;
    }

    private Path resolveGraphPath(Path projectRoot, KompileCodingProject project) throws IOException {
        if (project == null) return null;
        String configured = project.getMetadata().get("graphPath");
        if (configured == null || configured.isBlank()) {
            String knowledgeBase = project.getMetadata().get("knowledgeBaseId");
            if (knowledgeBase == null || knowledgeBase.isBlank()
                    || knowledgeBase.contains("/") || knowledgeBase.contains("\\")) return null;
            configured = "data/crawls/" + knowledgeBase + "/graph.kgraph";
        }
        Path path = Path.of(configured);
        if (!path.isAbsolute()) path = projectRoot.resolve(path);
        return safeProjectPath(projectRoot, path, true);
    }

    private List<FileNote> readNotes(Descriptor descriptor, List<String> warnings) {
        Path notePath = descriptor.notePath();
        Path configuredParent = notePath.getParent();
        if (!Files.isDirectory(configuredParent)) return List.of();
        try {
            Path parent = safeProjectPath(
                    descriptor.projectRoot(), configuredParent, true).toRealPath();
            Object parentFileKey = fileKey(parent);
            ObjectNode root;
            try (SecureDirectoryStream<Path> directory =
                         openSecureDirectory(configuredParent, parent, parentFileKey)) {
                try {
                    root = readStore(directory, notePath.getFileName());
                } catch (NoSuchFileException missing) {
                    return List.of();
                }
            }
            ArrayNode notes = notesArray(root, descriptor.relativePath(), false);
            if (notes == null || notes.isEmpty()) return List.of();
            List<FileNote> result = new ArrayList<>();
            for (int i = notes.size() - 1; i >= 0 && result.size() < MAX_RETURNED_NOTES; i--) {
                JsonNode note = notes.get(i);
                result.add(new FileNote(note.path("id").asText(), note.path("content").asText(),
                        note.path("author").asText(), note.path("sessionId").asText(),
                        note.path("createdAt").asText()));
            }
            return List.copyOf(result);
        } catch (Exception invalidStore) {
            warnings.add("File notes unavailable: " + concise(invalidStore));
            return List.of();
        }
    }

    private void mutateNotes(Descriptor descriptor, StoreMutation mutation) throws IOException {
        Path notePath = descriptor.notePath().toAbsolutePath().normalize();
        Path configuredParent = notePath.getParent();
        safeProjectPath(descriptor.projectRoot(), configuredParent, true);
        Files.createDirectories(configuredParent);
        Path realParent = safeProjectPath(
                descriptor.projectRoot(), configuredParent, true).toRealPath();
        Object parentFileKey = fileKey(realParent);
        Path effectiveNotePath = realParent.resolve(notePath.getFileName());
        Path processKey = effectiveNotePath;
        ReentrantLock processLock = NOTE_LOCKS.computeIfAbsent(processKey, ignored -> new ReentrantLock());
        processLock.lock();
        try {
            try (SecureDirectoryStream<Path> directory =
                         openSecureDirectory(configuredParent, realParent, parentFileKey);
                 SeekableByteChannel rawLock = directory.newByteChannel(
                         Path.of("." + NOTE_FILE + ".lock"),
                         Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                                 LinkOption.NOFOLLOW_LINKS))) {
                if (!(rawLock instanceof FileChannel lockChannel)) {
                    throw new IOException("Filesystem cannot provide a lockable secure file-note handle");
                }
                try (FileLock ignored = lockChannel.lock()) {
                    requireStableParent(configuredParent, realParent, parentFileKey);
                    ObjectNode root;
                    try {
                        root = readStore(directory, notePath.getFileName());
                    } catch (NoSuchFileException missing) {
                        root = emptyStore();
                    }
                    mutation.apply(root);
                    root.put("updatedAt", Instant.now().toString());
                    requireStableParent(configuredParent, realParent, parentFileKey);
                    writeAtomic(directory, notePath.getFileName(), root);
                }
            }
        } finally {
            processLock.unlock();
        }
    }

    private ObjectNode readStore(SecureDirectoryStream<Path> directory, Path fileName)
            throws IOException {
        try (SeekableByteChannel channel = directory.newByteChannel(fileName,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            long size = channel.size();
            if (size > MAX_NOTE_STORE_BYTES) {
                throw new IOException("File note store is too large: " + size + " bytes");
            }
            JsonNode parsed;
            try (InputStream input = Channels.newInputStream(channel)) {
                parsed = MAPPER.readTree(input);
            }
            if (!(parsed instanceof ObjectNode root)) {
                throw new IOException("File note store must be a JSON object");
            }
            if (!NOTE_SCHEMA.equals(root.path("schema").asText())) {
                throw new IOException("Unsupported file note schema: "
                        + root.path("schema").asText("missing"));
            }
            if (!root.path("files").isObject()) {
                throw new IOException("File note store is missing files object");
            }
            return root;
        }
    }

    private static ObjectNode emptyStore() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schema", NOTE_SCHEMA);
        root.put("updatedAt", Instant.now().toString());
        root.putObject("files");
        return root;
    }

    private static ArrayNode notesArray(ObjectNode root, String relativePath, boolean create)
            throws IOException {
        JsonNode filesNode = root.path("files");
        ObjectNode files;
        if (filesNode instanceof ObjectNode existingFiles) {
            files = existingFiles;
        } else {
            if (!create) return null;
            files = root.putObject("files");
        }
        JsonNode existing = files.get(relativePath);
        if (existing == null) return create ? files.putArray(relativePath) : null;
        if (!(existing instanceof ArrayNode notes)) {
            throw new IOException("File note entry must be an array: " + relativePath);
        }
        return notes;
    }

    private static void writeAtomic(SecureDirectoryStream<Path> directory,
                                    Path targetName, JsonNode value) throws IOException {
        byte[] serialized = (MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(value) + "\n").getBytes(StandardCharsets.UTF_8);
        if (serialized.length > MAX_NOTE_STORE_BYTES) {
            throw new IOException("File note store would exceed " + MAX_NOTE_STORE_BYTES + " bytes");
        }
        Path temporary = Path.of("." + NOTE_FILE + "." + UUID.randomUUID() + ".tmp");
        try {
            try (SeekableByteChannel output = directory.newByteChannel(temporary,
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS))) {
                ByteBuffer bytes = ByteBuffer.wrap(serialized);
                while (bytes.hasRemaining()) output.write(bytes);
                if (output instanceof FileChannel fileChannel) fileChannel.force(true);
            }
            directory.move(temporary, directory, targetName);
        } finally {
            try {
                directory.deleteFile(temporary);
            } catch (NoSuchFileException ignored) {
                // A successful secure move consumed the temporary name.
            }
        }
    }

    private static SecureDirectoryStream<Path> openSecureDirectory(
            Path configuredParent, Path realParent, Object expectedFileKey) throws IOException {
        DirectoryStream<Path> opened = Files.newDirectoryStream(configuredParent);
        if (!(opened instanceof SecureDirectoryStream<?> rawSecure)) {
            opened.close();
            throw new IOException("Filesystem does not support secure file-note directory handles");
        }
        @SuppressWarnings("unchecked")
        SecureDirectoryStream<Path> secure = (SecureDirectoryStream<Path>) rawSecure;
        try {
            requireStableParent(configuredParent, realParent, expectedFileKey);
            return secure;
        } catch (IOException failure) {
            secure.close();
            throw failure;
        }
    }

    private static Object fileKey(Path directory) throws IOException {
        return Files.readAttributes(directory, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).fileKey();
    }

    private static void requireStableParent(Path configuredParent, Path realParent,
                                            Object expectedFileKey) throws IOException {
        Path current = configuredParent.toRealPath();
        Object currentFileKey = fileKey(current);
        if (!current.equals(realParent) || !Objects.equals(expectedFileKey, currentFileKey)) {
            throw new IOException("File note metadata directory changed during mutation");
        }
    }

    private static Path safeProjectPath(Path projectRoot, Path candidate, boolean allowMissingFile)
            throws IOException {
        Path root = projectRoot.toAbsolutePath().normalize().toRealPath();
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) throw new IOException("Project metadata path escapes project root");
        Path existing = normalized;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null || !existing.toRealPath().startsWith(root)) {
            throw new IOException("Project metadata path escapes through a symbolic link");
        }
        if (!allowMissingFile && Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Expected project metadata directory: " + normalized);
        }
        if (allowMissingFile && Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            Path real = normalized.toRealPath();
            if (!real.startsWith(root)) throw new IOException("Graph path escapes project root");
            return real;
        }
        return normalized;
    }

    private static String graphGeneration(UnifiedGraphArchive archive, String projectId) {
        Object rawMeta = archive.manifest().get("meta");
        if (!(rawMeta instanceof Map<?, ?> meta)) return "";
        return text(meta.get("codeIndexGeneration." + projectId));
    }

    private static int mergeIndexRelations(List<Map<String, Object>> source,
                                           LinkedHashMap<String, RelationSummary> target) {
        int count = 0;
        for (Map<String, Object> relation : source) {
            String type = firstNonBlank(text(relation.get("relationType")), "RELATED_TO");
            String from = firstNonBlank(text(relation.get("sourceFqn")), "unknown");
            String to = firstNonBlank(text(relation.get("targetFqn")),
                    text(relation.get("targetName")), "unknown");
            mergeRelation(target, new RelationSummary(type, from, to, "index"));
            count++;
        }
        return count;
    }

    private static void mergeRelation(LinkedHashMap<String, RelationSummary> target,
                                      RelationSummary incoming) {
        String key = incoming.source() + "\n" + incoming.type() + "\n" + incoming.target();
        RelationSummary existing = target.get(key);
        if (existing == null) {
            target.put(key, incoming);
        } else if (!existing.origin().equals(incoming.origin())) {
            target.put(key, new RelationSummary(existing.type(), existing.source(),
                    existing.target(), "index+kgraph"));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((key, entry) -> {
                    if (key != null) copy.put(String.valueOf(key), entry);
                });
                result.add(copy);
            }
        }
        return result;
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String bounded(String value, int max) {
        String text = value == null ? "" : value.trim();
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String normalizePath(String value) {
        return value.replace('\\', '/');
    }

    private static String singleLine(String value, int max) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max) + "…";
    }

    private static String cap(String value, int maxChars) {
        if (value.length() <= maxChars) return value;
        String marker = "\n... file context truncated\n";
        return value.substring(0, Math.max(0, maxChars - marker.length())) + marker;
    }

    private static String concise(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName() : message;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private record Descriptor(Path file, Path codeRoot, Path projectRoot, String codeProjectId,
                              String relativePath, Path indexDirectory, Path notePath,
                              Path graphPath) {
    }

    private record ProjectOwner(Path root, KompileCodingProject codeProject) {
    }

    private record GraphLookup(String status, String path, boolean fresh, int nodes, int edges,
                               List<RelationSummary> relations) {
        static GraphLookup unavailable(String status, Path path) {
            return new GraphLookup(status, path == null ? null : path.toString(), false,
                    0, 0, List.of());
        }
    }

    @FunctionalInterface
    private interface StoreMutation {
        void apply(ObjectNode root) throws IOException;
    }

    public record FileNote(String id, String content, String author, String sessionId,
                           String createdAt) {
        public Map<String, Object> metadata() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", id);
            result.put("content", bounded(content, MAX_METADATA_NOTE_CHARS));
            result.put("contentTruncated", content != null
                    && content.length() > MAX_METADATA_NOTE_CHARS);
            result.put("author", singleLine(author, 120));
            result.put("sessionId", singleLine(sessionId, 200));
            result.put("createdAt", singleLine(createdAt, 80));
            return result;
        }
    }

    public record SymbolSummary(String type, String name, String fullyQualifiedName,
                                String signature, Integer line) {
        public Map<String, Object> metadata() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("type", singleLine(type, 80));
            result.put("name", singleLine(name, 240));
            result.put("fullyQualifiedName", singleLine(fullyQualifiedName, 600));
            result.put("signature", singleLine(signature, 600));
            if (line != null) result.put("line", line);
            return result;
        }
    }

    public record RelationSummary(String type, String source, String target, String origin) {
        public Map<String, Object> metadata() {
            return Map.of(
                    "type", singleLine(type, 100),
                    "source", singleLine(source, 600),
                    "target", singleLine(target, 600),
                    "origin", singleLine(origin, 40));
        }
    }

    public record ContextSnapshot(boolean available, String absolutePath, String projectRoot,
                                  String codeProjectId, String filePath, String indexStatus,
                                  String graphStatus, String graphPath, boolean graphFresh,
                                  List<FileNote> notes, List<SymbolSummary> symbols,
                                  List<RelationSummary> relations, int indexedEntityCount,
                                  int indexedRelationCount, int graphNodeCount,
                                  int graphRelationCount, List<String> warnings) {
        static ContextSnapshot unavailable(Path file, String reason) {
            return new ContextSnapshot(false, file.toString(), null, null, file.toString(),
                    reason, "UNAVAILABLE", null, false, List.of(), List.of(), List.of(),
                    0, 0, 0, 0, List.of());
        }

        public Map<String, Object> metadata() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("available", available);
            result.put("absolutePath", singleLine(absolutePath, 2_000));
            if (projectRoot != null) result.put("projectRoot", singleLine(projectRoot, 2_000));
            if (codeProjectId != null) result.put("codeProjectId", codeProjectId);
            result.put("filePath", singleLine(filePath, 2_000));
            result.put("indexStatus", indexStatus);
            result.put("graphStatus", graphStatus);
            if (graphPath != null) result.put("graphPath", singleLine(graphPath, 2_000));
            result.put("graphFresh", graphFresh);
            result.put("indexedEntityCount", indexedEntityCount);
            result.put("indexedRelationCount", indexedRelationCount);
            result.put("graphNodeCount", graphNodeCount);
            result.put("graphRelationCount", graphRelationCount);
            result.put("notes", notes.stream().map(FileNote::metadata).toList());
            result.put("symbols", symbols.stream().map(SymbolSummary::metadata).toList());
            result.put("relations", relations.stream().map(RelationSummary::metadata).toList());
            result.put("warnings", warnings.stream().map(value -> singleLine(value, 500)).toList());
            return Collections.unmodifiableMap(result);
        }

        public Map<String, Object> summaryMetadata() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("available", available);
            if (codeProjectId != null) result.put("codeProjectId", codeProjectId);
            result.put("filePath", singleLine(filePath, 2_000));
            result.put("indexStatus", indexStatus);
            result.put("graphStatus", graphStatus);
            result.put("graphFresh", graphFresh);
            result.put("noteCount", notes.size());
            result.put("symbolCount", symbols.size());
            result.put("relationCount", relations.size());
            result.put("warnings", warnings.stream().map(value -> singleLine(value, 500)).toList());
            return Collections.unmodifiableMap(result);
        }
    }

    public record NoteMutation(String noteId, ContextSnapshot context) {
    }
}
