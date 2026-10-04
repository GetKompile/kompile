/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.project;

import ai.kompile.core.crawl.graph.SourceCredentialRedactor;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.auth.source.SourceCredentialResolver;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
import ai.kompile.core.loaders.FileDownloadingLoader;
import ai.kompile.loader.discord.DiscordLoaderImpl;
import ai.kompile.loader.email.EmailConnectionFactory;
import ai.kompile.loader.email.ImapPopDocumentLoader;
import ai.kompile.loader.gdocs.GoogleDocsLoaderImpl;
import ai.kompile.loader.gdrive.GoogleDriveLoaderImpl;
import ai.kompile.loader.gmail.GmailLoaderImpl;
import ai.kompile.loader.gworkspace.GWorkspaceLoaderImpl;
import ai.kompile.loader.onedrive.OneDriveLoaderImpl;
import ai.kompile.loader.slack.SlackHistoryLoaderImpl;
import ai.kompile.loader.slack.SlackLoaderImpl;
import ai.kompile.source.confluence.ConfluenceDocumentLoader;
import ai.kompile.source.jira.JiraDocumentLoader;
import ai.kompile.source.erp.CamelErpDocumentLoader;
import ai.kompile.source.erp.ErpSourceConfiguration;
import ai.kompile.source.notion.NotionDocumentLoader;
import ai.kompile.source.reddit.RedditDocumentLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Explicit, Spring-free connector catalog for folder-local crawl execution. */
public final class LocalExternalSourceLoaderRegistry {
    private static final Set<String> TYPES = Set.of(
            "JIRA", "REDDIT", "NOTION", "CONFLUENCE", "SLACK", "SLACK_HISTORY",
            "DISCORD", "DISCORD_HISTORY", "EMAIL", "IMAP", "POP3", "GMAIL", "GDOCS",
            "GDRIVE", "GOOGLE_WORKSPACE", "ONEDRIVE", "SAP_NETWEAVER", "ODATA", "DYNAMICS365", "NETSUITE", "ODOO", "SALESFORCE", "ORACLE_FUSION", "ORACLE_EBS", "JD_EDWARDS", "INFOR_MONGOOSE", "ACUMATICA");
    private static final Set<String> SENSITIVE_SUFFIXES = Set.of(
            "password", "token", "secret", "accesskey", "apikey", "privatekey",
            "authorization", "credential", "credentials");
    /** Types whose loaders write original attachment bytes beside their message text (C4). */
    private static final Set<String> ATTACHMENT_TYPES = Set.of(
            "EMAIL", "IMAP", "POP3", "GMAIL", "DISCORD", "DISCORD_HISTORY");
    /** Types that keep sidecar sync state so a re-crawl can fetch only what changed (F4). */
    private static final Set<String> INCREMENTAL_TYPES = Set.of(
            "SLACK", "SLACK_HISTORY", "DISCORD", "DISCORD_HISTORY",
            "EMAIL", "IMAP", "POP3", "GMAIL");
    /** Re-fetch window overlap so messages near the sync boundary are never missed. */
    private static final Duration SYNC_OVERLAP = Duration.ofMinutes(10);

    private LocalExternalSourceLoaderRegistry() {
    }

    public static boolean supports(String sourceType) {
        return sourceType != null && TYPES.contains(normalize(sourceType));
    }

    /**
     * Source types that deliver original files (PDF, Office exports, images, ...) and must be
     * routed through content-type pipelines rather than text materialization. Their
     * {@link #materializeFiles} call downloads the originals; processing is then a pipeline
     * decision exactly like any local file.
     */
    public static boolean downloadsOriginalFiles(String sourceType) {
        String canonical = normalize(sourceType);
        return "GDRIVE".equals(canonical) || "ONEDRIVE".equals(canonical);
    }

    /**
     * Downloads original files for a file-backed source type into {@code targetDirectory},
     * returning the written paths and any per-file warnings. Only valid for types where
     * {@link #downloadsOriginalFiles} is true. Credentials come from the caller's properties,
     * a named {@code properties.fromChannelConnection}, or a connected OAuth account (see
     * {@link SourceCredentialResolver}).
     *
     * <p>Downloads land in a staging directory sibling to {@code targetDirectory}, then swap in
     * atomically: a stale file from a since-deleted upstream item, or from a since-renamed one,
     * never lingers in {@code targetDirectory} after a successful re-crawl. When {@code
     * maxDocuments > 0}, the loader's own per-source limit key (e.g. {@code maxFiles}) and any
     * explicit id list are bounded before the download runs; if the loader still returns more
     * files than the cap, the extras are discarded and a warning is recorded. A per-file failure
     * is collected as a warning rather than failing the whole crawl; if every file fails, the
     * underlying {@link FileDownloadingLoader} throws using the first
     * failure (see its {@code downloadTo(descriptor, destination, warnings)}).</p>
     */
    public static MaterializedFiles materializeFiles(
            String sourceType,
            String locator,
            Map<String, Object> properties,
            Path targetDirectory,
            int maxDocuments) throws Exception {
        return materializeFiles(
                sourceType, locator, properties, targetDirectory, maxDocuments, SourceCredentialResolver.create());
    }

    /** Package-private overload so tests can inject a fake credential resolver. */
    static MaterializedFiles materializeFiles(
            String sourceType,
            String locator,
            Map<String, Object> properties,
            Path targetDirectory,
            int maxDocuments,
            SourceCredentialResolver credentialResolver) throws Exception {
        String canonical = normalize(sourceType);
        DocumentSourceDescriptor.SourceType type = DocumentSourceDescriptor.SourceType.valueOf(canonical);
        return materializeFiles(sourceType, locator, properties, targetDirectory, maxDocuments,
                credentialResolver, loader(type, JsonUtils.standardMapper()));
    }

    /**
     * Package-private overload so tests can inject a fake {@link DocumentLoader} (a hand-written
     * {@link FileDownloadingLoader} double) and exercise the staging/cap/
     * warning-aggregation orchestration below without a real Google Drive or OneDrive client.
     */
    static MaterializedFiles materializeFiles(
            String sourceType,
            String locator,
            Map<String, Object> properties,
            Path targetDirectory,
            int maxDocuments,
            SourceCredentialResolver credentialResolver,
            DocumentLoader loader) throws Exception {
        String canonical = normalize(sourceType);
        if (!downloadsOriginalFiles(canonical)) {
            throw new IllegalArgumentException(
                    "Source type " + canonical + " does not download original files");
        }
        Map<String, Object> runtimeProperties = properties == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(properties);
        credentialResolver.resolveStoredCredentials(canonical, runtimeProperties);
        applyFileDownloadCap(canonical, maxDocuments, runtimeProperties);
        String runtimeLocator = boundExplicitIdentifiers(
                canonical, locator == null ? "" : locator.trim(), runtimeProperties, maxDocuments);
        if (!(loader instanceof FileDownloadingLoader downloader)) {
            throw new IllegalArgumentException(
                    "Loader for " + canonical + " does not support original-file download");
        }
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(DocumentSourceDescriptor.SourceType.valueOf(canonical))
                .pathOrUrl(runtimeLocator)
                .sourceId(identityKey(canonical, locator))
                .metadata(runtimeProperties)
                .build();

        Path absoluteTarget = targetDirectory.toAbsolutePath().normalize();
        Path parent = absoluteTarget.getParent();
        if (parent == null) throw new IOException("External source target has no parent: " + targetDirectory);
        Files.createDirectories(parent);
        Path staging = parent.resolve(absoluteTarget.getFileName() + ".staging-" + UUID.randomUUID());
        Files.createDirectories(staging);
        List<String> warnings = new ArrayList<>();
        try {
            List<Path> downloaded = downloader.downloadTo(descriptor, staging, warnings::add);
            if (downloaded.isEmpty()) {
                throw new IllegalStateException(loader.getName() + " downloaded no files"
                        + (warnings.isEmpty() ? "" : ": " + warnings.get(0)));
            }
            if (maxDocuments > 0 && downloaded.size() > maxDocuments) {
                for (Path extra : downloaded.subList(maxDocuments, downloaded.size())) {
                    Files.deleteIfExists(extra);
                }
                warnings.add("Downloaded " + downloaded.size() + " files, more than the requested cap of "
                        + maxDocuments + "; kept the first " + maxDocuments);
            }
            replaceDirectory(staging, absoluteTarget);
        } catch (Exception failure) {
            deleteRecursively(staging);
            throw failure;
        }
        return new MaterializedFiles(absoluteTarget, listFilesRecursively(absoluteTarget), List.copyOf(warnings));
    }

    private static void applyFileDownloadCap(String type, int maxDocuments, Map<String, Object> properties) {
        if (maxDocuments <= 0) return;
        String key = switch (type) {
            case "GDRIVE", "ONEDRIVE" -> "maxFiles";
            default -> null;
        };
        if (key == null) return;
        properties.put(key, bounded(properties.get(key), maxDocuments));
    }

    /**
     * Contract for loaders that deliver original files for pipeline processing instead of
     * text documents: {@link FileDownloadingLoader} (implemented by
     * file-backed providers such as Google Drive and OneDrive).
     */

    public static Set<String> sourceTypes() {
        return TYPES;
    }

    public static MaterializedSource materialize(
            String sourceType,
            String locator,
            Map<String, Object> properties,
            Path targetDirectory,
            int maxDocuments,
            ObjectMapper mapper) throws Exception {
        return materialize(
                sourceType, locator, properties, targetDirectory, maxDocuments, mapper,
                SourceCredentialResolver.create());
    }

    /** Package-private overload so tests can inject a fake credential resolver. */
    static MaterializedSource materialize(
            String sourceType,
            String locator,
            Map<String, Object> properties,
            Path targetDirectory,
            int maxDocuments,
            ObjectMapper mapper,
            SourceCredentialResolver credentialResolver) throws Exception {
        String canonical = normalize(sourceType);
        if (!TYPES.contains(canonical)) {
            throw new IllegalArgumentException("Unsupported project-local external source type: " + sourceType);
        }
        DocumentSourceDescriptor.SourceType type = DocumentSourceDescriptor.SourceType.valueOf(canonical);
        return materialize(sourceType, locator, properties, targetDirectory, maxDocuments, mapper,
                credentialResolver, loader(type, mapper));
    }

    /**
     * Package-private overload so tests can inject a fake {@link DocumentLoader} and exercise the
     * incremental-sync, attachment-rewrite, and staging/merge orchestration below without a real
     * network-backed loader (mirrors the {@link #materializeFiles} loader-injection seam).
     */
    static MaterializedSource materialize(
            String sourceType,
            String locator,
            Map<String, Object> properties,
            Path targetDirectory,
            int maxDocuments,
            ObjectMapper mapper,
            SourceCredentialResolver credentialResolver,
            DocumentLoader loader) throws Exception {
        String canonical = normalize(sourceType);
        if (!TYPES.contains(canonical)) {
            throw new IllegalArgumentException("Unsupported project-local external source type: " + sourceType);
        }
        Map<String, Object> runtimeProperties = properties == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(properties);
        if (ErpSourceConfiguration.isErp(canonical) && locator != null && !locator.isBlank()) {
            runtimeProperties.putIfAbsent("serviceRoot", locator.trim());
        }
        credentialResolver.resolveStoredCredentials(canonical, runtimeProperties);
        String trimmedLocator = locator == null ? "" : locator.trim();
        if (trimmedLocator.isEmpty() && !identityWithoutLocator(canonical, runtimeProperties)) {
            throw new IllegalArgumentException(canonical + " requires pathOrUrl/path/url");
        }
        applyUnifiedLimit(canonical, maxDocuments, runtimeProperties);
        String runtimeLocator = boundExplicitIdentifiers(
                canonical, trimmedLocator, runtimeProperties, maxDocuments);

        boolean fullResyncRequested = isTrue(runtimeProperties.remove("fullResync"));
        Path absoluteTarget = targetDirectory.toAbsolutePath().normalize();
        Path syncFile = syncStateFile(absoluteTarget);
        boolean callerProvidedSince = hasNonBlank(runtimeProperties, "since");
        SyncState priorState = INCREMENTAL_TYPES.contains(canonical) ? readSyncState(syncFile, mapper) : null;
        boolean incremental = priorState != null && !callerProvidedSince && !fullResyncRequested
                && hasAnyRegularFile(absoluteTarget);
        Instant runStartedAt = Instant.now();
        if (incremental) {
            runtimeProperties.put("since", priorState.lastSyncStartedAt().minus(SYNC_OVERLAP).toString());
        }

        boolean attachmentsEnabled = ATTACHMENT_TYPES.contains(canonical)
                && !isExplicitFalse(runtimeProperties.get("includeAttachments"));

        DocumentSourceDescriptor.SourceType type = DocumentSourceDescriptor.SourceType.valueOf(canonical);

        Path parent = absoluteTarget.getParent();
        if (parent == null) throw new IOException("External source target has no parent: " + targetDirectory);
        Files.createDirectories(parent);
        Path staging = parent.resolve(absoluteTarget.getFileName() + ".staging-" + UUID.randomUUID());
        Files.createDirectories(staging);

        if (attachmentsEnabled) {
            runtimeProperties.put("includeAttachments", true);
            runtimeProperties.put("attachmentDirectory", staging.resolve("attachments").toAbsolutePath().toString());
        }
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(type)
                .pathOrUrl(runtimeLocator)
                .sourceId(identityKey(canonical, locator))
                .metadata(runtimeProperties)
                .build();

        List<Path> files;
        int fetchedDocuments;
        try {
            if (!loader.supports(descriptor)) {
                throw new IllegalStateException(loader.getName() + " does not support " + canonical);
            }
            List<Document> loaded = loader.load(descriptor);
            if (incremental && anyMissingSourcePath(loaded)) {
                System.err.println("Warning: " + canonical + " incremental sync returned document(s) without "
                        + "source_path; falling back to a full fetch");
                runtimeProperties.remove("since");
                // Wipe staging before the full reload: the discarded incremental pass may already
                // have written attachment bytes for the same messages under loader-chosen names,
                // and we cannot assume those names are stable across the two load() calls.
                deleteRecursively(staging);
                Files.createDirectories(staging);
                loaded = loader.load(descriptor);
                incremental = false;
            }
            if (maxDocuments > 0 && loaded.size() > maxDocuments) {
                loaded = loaded.subList(0, maxDocuments);
            }
            fetchedDocuments = loaded.size();
            if (attachmentsEnabled) {
                rewriteAttachmentPaths(loaded, staging.resolve("attachments"));
            }
            List<Path> relativeFiles = writeDocuments(loaded, canonical, locator, staging, mapper);
            if (incremental) {
                if (relativeFiles.isEmpty()) {
                    deleteRecursively(staging);
                } else {
                    mergeDirectory(staging, absoluteTarget);
                }
            } else {
                if (relativeFiles.isEmpty()) {
                    throw new IllegalStateException(loader.getName() + " returned no readable documents");
                }
                replaceDirectory(staging, absoluteTarget);
            }
            if (attachmentsEnabled) {
                pruneEmptyDirectories(absoluteTarget.resolve("attachments"));
            }
            files = listFilesRecursively(absoluteTarget);
        } catch (Exception failure) {
            deleteRecursively(staging);
            throw failure;
        }
        if (INCREMENTAL_TYPES.contains(canonical)) {
            writeSyncState(syncFile, runStartedAt, canonical, identityKey(canonical, locator), mapper);
        }
        return new MaterializedSource(absoluteTarget, files, loader.getName(), incremental, fetchedDocuments);
    }

    /** Sidecar sync-state path, kept beside (not inside) the materialized directory (F4). */
    private static Path syncStateFile(Path target) {
        return target.resolveSibling(target.getFileName() + ".sync.json");
    }

    private record SyncState(Instant lastSyncStartedAt) {
    }

    /** Missing or unparsable sync state is treated as "no prior sync" (fall back to full). */
    private static SyncState readSyncState(Path syncFile, ObjectMapper mapper) {
        if (!Files.isRegularFile(syncFile)) return null;
        try {
            JsonNode node = mapper.readTree(syncFile.toFile());
            String value = node.path("lastSyncStartedAt").asText(null);
            if (value == null || value.isBlank()) return null;
            return new SyncState(Instant.parse(value));
        } catch (Exception malformed) {
            return null;
        }
    }

    /**
     * Records when this run started (not when it finished) so the next run's overlap window
     * covers anything that arrived while this fetch was in flight. Best-effort: a failure to
     * write the sidecar only means the next run falls back to a full fetch, so it never fails an
     * otherwise-successful crawl.
     */
    private static void writeSyncState(
            Path syncFile, Instant runStartedAt, String sourceType, String identityKey, ObjectMapper mapper) {
        try {
            ObjectNode node = mapper.createObjectNode();
            node.put("sourceType", sourceType);
            node.put("identityKey", identityKey);
            node.put("lastSyncStartedAt", runStartedAt.toString());
            Files.writeString(syncFile, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException failure) {
            System.err.println("Warning: failed to write sync state for " + sourceType
                    + ": " + failure.getMessage());
        }
    }

    private static boolean hasAnyRegularFile(Path directory) {
        if (!Files.isDirectory(directory)) return false;
        try (var walk = Files.walk(directory)) {
            return walk.anyMatch(Files::isRegularFile);
        } catch (IOException failure) {
            return false;
        }
    }

    /**
     * An incremental (since-bounded) fetch is only safe to merge when every returned document
     * carries {@code source_path}: that is the identity {@link #writeDocuments} and the caller
     * rely on to tell "update an existing file" apart from "write a new one" across runs. A
     * loader that omits it forces a full fetch+replace instead, matching the {@code source_path}
     * identity contract every other connector already follows.
     */
    private static boolean anyMissingSourcePath(List<Document> documents) {
        for (Document document : documents) {
            if (document == null) continue;
            Map<String, Object> metadata = document.getMetadata();
            Object sourcePath = metadata == null ? null : metadata.get("source_path");
            if (sourcePath == null || sourcePath.toString().isBlank()) return true;
        }
        return false;
    }

    /**
     * Folds newly staged files into an existing materialized directory instead of replacing it,
     * so documents outside this run's {@code since} window are left untouched. A file already
     * present under the same identity digest is overwritten (an edited message re-fetched inside
     * the overlap window should win over the stale copy).
     */
    private static void mergeDirectory(Path staging, Path target) throws IOException {
        Files.createDirectories(target);
        try (var walk = Files.walk(staging)) {
            for (Path source : walk.filter(Files::isRegularFile).toList()) {
                Path relative = staging.relativize(source);
                Path destination = target.resolve(relative);
                Files.createDirectories(destination.getParent());
                Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        deleteRecursively(staging);
    }

    /** Bottom-up empty-directory cleanup, e.g. an attachments tree left empty this run (F5). */
    private static void pruneEmptyDirectories(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return;
        try (var walk = Files.walk(directory)) {
            for (Path candidate : walk.sorted(Comparator.reverseOrder()).toList()) {
                if (Files.isDirectory(candidate) && isEmptyDirectory(candidate)) {
                    Files.delete(candidate);
                }
            }
        }
    }

    private static boolean isEmptyDirectory(Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            return entries.findAny().isEmpty();
        }
    }

    /**
     * Rewrites {@code attachments[].path} (or a bare string entry) from wherever the loader
     * wrote it — an absolute path under {@code attachmentDirectory}, or already relative to it —
     * into a path relative to the materialized root, i.e. {@code attachments/<...>}, before
     * {@link #writeDocuments} serializes the metadata into markdown. Rebuilds the attachment
     * entries rather than mutating them in place: a loader may hand back an immutable map.
     */
    @SuppressWarnings("unchecked")
    private static void rewriteAttachmentPaths(List<Document> documents, Path attachmentDirectory) {
        for (Document document : documents) {
            if (document == null || document.getMetadata() == null) continue;
            Object rawAttachments = document.getMetadata().get("attachments");
            if (!(rawAttachments instanceof List<?> attachments) || attachments.isEmpty()) continue;
            List<Object> rewritten = new ArrayList<>();
            for (Object entry : attachments) {
                if (entry instanceof Map<?, ?> attachment) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    attachment.forEach((key, value) -> copy.put(String.valueOf(key), value));
                    Object rawPath = copy.get("path");
                    if (rawPath != null && !rawPath.toString().isBlank()) {
                        copy.put("path", relativeAttachmentPath(rawPath.toString(), attachmentDirectory));
                    }
                    rewritten.add(copy);
                } else if (entry instanceof String rawPath && !rawPath.isBlank()) {
                    rewritten.add(relativeAttachmentPath(rawPath, attachmentDirectory));
                } else {
                    rewritten.add(entry);
                }
            }
            document.getMetadata().put("attachments", rewritten);
        }
    }

    private static String relativeAttachmentPath(String rawPath, Path attachmentDirectory) {
        Path path = Path.of(rawPath);
        if (path.isAbsolute()) {
            Path normalizedRoot = attachmentDirectory.toAbsolutePath().normalize();
            Path normalizedPath = path.toAbsolutePath().normalize();
            if (normalizedPath.startsWith(normalizedRoot)) {
                String relative = normalizedRoot.relativize(normalizedPath).toString().replace('\\', '/');
                return relative.isBlank() ? "attachments" : "attachments/" + relative;
            }
            // Outside the attachment directory entirely (shouldn't happen): keep only the file
            // name rather than leaking a local filesystem layout into committed metadata.
            return "attachments/" + path.getFileName();
        }
        String normalized = rawPath.replace('\\', '/').replaceFirst("^\\./", "");
        return normalized.startsWith("attachments/") ? normalized : "attachments/" + normalized;
    }

    private static boolean hasNonBlank(Map<String, Object> properties, String key) {
        Object value = properties.get(key);
        return value != null && !value.toString().isBlank();
    }

    private static boolean isExplicitFalse(Object value) {
        if (value instanceof Boolean bool) return !bool;
        if (value == null) return false;
        String text = value.toString().trim();
        return !text.isEmpty() && "false".equalsIgnoreCase(text);
    }

    /**
     * Writes each document to {@code staging} as a markdown file named by the digest of its
     * identity metadata. Package-private so tests can feed synthetic documents through the
     * write loop without a real loader.
     *
     * <p>Two documents can legitimately compute the same identity (e.g. a source whose
     * metadata carries no per-message identifier, so every document falls back to the same
     * "source" value). Without a safety net, distinct documents would collapse onto the same
     * filename and silently overwrite one another. This method only drops a document's write
     * when it is a true duplicate of one already written this run (same identity AND the same
     * text); otherwise it disambiguates the filename by folding the loop index into the
     * identity so every distinct document keeps its own file.</p>
     */
    static List<Path> writeDocuments(
            List<Document> documents, String canonical, String locator, Path staging, ObjectMapper mapper)
            throws IOException {
        List<Path> relativeFiles = new ArrayList<>();
        // Keyed by raw identity (not by output filename), so a duplicate is recognised against
        // the original AND every disambiguated copy of that identity, not just the first text
        // written. Holds text digests rather than full text to avoid doubling memory on large
        // exports.
        Map<String, Set<String>> textDigestsByIdentity = new LinkedHashMap<>();
        int index = 0;
        int collisions = 0;
        for (Document document : documents) {
            if (document == null || document.getText() == null || document.getText().isBlank()) continue;
            Map<String, Object> metadata = sanitizeMetadata(document.getMetadata());
            String title = firstText(metadata, "title", "fileName", "file_name", "subject", "name");
            if (title == null) title = canonical + " document " + (index + 1);
            String identity = firstText(metadata, "notion.pageId", "source_path", "issueKey",
                    "postId", "messageId", "threadId", "id", "source");
            if (identity == null) identity = identityKey(canonical, locator) + "\n" + index;
            String text = document.getText().trim();
            String textDigest = digest(text);
            Set<String> seenTextDigests = textDigestsByIdentity.get(identity);
            if (seenTextDigests != null && seenTextDigests.contains(textDigest)) {
                // Same identity, same text as one already written this run (the original or any
                // disambiguated copy): a true duplicate.
                index++;
                continue;
            }
            String fileName;
            if (seenTextDigests == null) {
                fileName = digest(identity) + ".md";
                seenTextDigests = new HashSet<>();
                textDigestsByIdentity.put(identity, seenTextDigests);
            } else {
                // Same identity, different text: a genuine collision. Disambiguate instead of
                // silently truncating the document that was already written under this name.
                collisions++;
                fileName = digest(identity + "\n" + index) + ".md";
            }
            seenTextDigests.add(textDigest);
            Path output = staging.resolve(fileName).normalize();
            if (!output.startsWith(staging)) throw new IOException("Materialized source escaped staging directory");
            StringBuilder markdown = new StringBuilder();
            markdown.append("<!-- kompile-source-type: ").append(canonical).append(" -->\n");
            markdown.append("<!-- kompile-source-metadata: ")
                    .append(mapper.writeValueAsString(metadata).replace("-->", "--\\u003e"))
                    .append(" -->\n\n");
            markdown.append(text).append('\n');
            Files.writeString(output, markdown.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            relativeFiles.add(staging.relativize(output));
            index++;
        }
        if (collisions > 0) {
            System.err.println("Warning: " + collisions + " " + canonical
                    + " document(s) collided on the same filename and were disambiguated by index");
        }
        return relativeFiles;
    }

    private static DocumentLoader loader(DocumentSourceDescriptor.SourceType type, ObjectMapper mapper) {
        return switch (type) {
            case JIRA -> new JiraDocumentLoader(null, mapper);
            case REDDIT -> new RedditDocumentLoader(null, mapper);
            case NOTION -> new NotionDocumentLoader(null, mapper);
            case CONFLUENCE -> new ConfluenceDocumentLoader();
            case SLACK -> new SlackLoaderImpl();
            case SLACK_HISTORY -> new SlackHistoryLoaderImpl();
            case DISCORD, DISCORD_HISTORY -> new DiscordLoaderImpl();
            case EMAIL, IMAP, POP3 -> new ImapPopDocumentLoader(new EmailConnectionFactory());
            case GMAIL -> new GmailLoaderImpl();
            case GDOCS -> new GoogleDocsLoaderImpl();
            case GDRIVE -> new GoogleDriveLoaderImpl(null);
            case GOOGLE_WORKSPACE -> new GWorkspaceLoaderImpl();
            case ONEDRIVE -> new OneDriveLoaderImpl(null);
            case SAP_NETWEAVER, ODATA, DYNAMICS365, NETSUITE, ODOO, SALESFORCE, ORACLE_FUSION, ORACLE_EBS, JD_EDWARDS, INFOR_MONGOOSE, ACUMATICA -> new CamelErpDocumentLoader();
            default -> throw new IllegalArgumentException("No local external loader for " + type);
        };
    }

    private static void applyUnifiedLimit(String type, int maxDocuments, Map<String, Object> properties) {
        if (maxDocuments <= 0) return;
        if (ErpSourceConfiguration.isErp(type)) {
            ErpSourceConfiguration.applyLimit(properties, maxDocuments);
            return;
        }
        if ("GDOCS".equals(type)) properties.put("maxDocuments", maxDocuments);
        if ("GOOGLE_WORKSPACE".equals(type)) {
            properties.put("gmailMaxMessages", bounded(properties.get("gmailMaxMessages"), maxDocuments));
            properties.put("driveMaxFiles", bounded(properties.get("driveMaxFiles"), maxDocuments));
            properties.put("calendarMaxEvents", bounded(properties.get("calendarMaxEvents"), maxDocuments));
        }
        String key = switch (type) {
            case "JIRA" -> "maxIssues";
            case "REDDIT" -> "postLimit";
            case "NOTION" -> "maxPages";
            case "CONFLUENCE" -> "maxDocuments";
            case "SLACK" -> "limit";
            case "SLACK_HISTORY", "DISCORD", "DISCORD_HISTORY", "EMAIL", "IMAP", "POP3", "GMAIL" ->
                    "maxMessages";
            default -> null;
        };
        if (key == null) return;
        properties.put(key, bounded(properties.get(key), maxDocuments));
    }

    /**
     * Bounds a configured per-source limit to {@code limit}. A configured value that is absent,
     * unparsable, or {@code <= 0} counts as unset (the caller's cap applies as-is) rather than
     * being floored to 1 — an explicit limit of 0 must not turn into a limit of 1.
     */
    private static int bounded(Object configured, int limit) {
        if (configured == null) return limit;
        try {
            int value = configured instanceof Number number
                    ? number.intValue() : Integer.parseInt(configured.toString());
            return value <= 0 ? limit : Math.min(limit, value);
        } catch (NumberFormatException ignored) {
            return limit;
        }
    }

    private static String boundExplicitIdentifiers(
            String type, String locator, Map<String, Object> properties, int limit) {
        String metadataKey = switch (type) {
            case "GDOCS" -> "documentIds";
            case "GDRIVE" -> "fileIds";
            case "ONEDRIVE" -> "itemIds";
            default -> null;
        };
        if (metadataKey == null) return locator;
        Object configured = properties.get(metadataKey);
        List<String> ids = identifiers(configured == null ? locator : configured);
        if (ids.isEmpty() && configured != null) ids = identifiers(locator);
        if (limit > 0 && ids.size() > limit) ids = ids.subList(0, limit);
        if (configured != null || "GDOCS".equals(type)) {
            properties.put(metadataKey, List.copyOf(ids));
            return locator;
        }
        return String.join(",", ids);
    }

    private static List<String> identifiers(Object value) {
        // A bare boolean is never an identifier list; without this, Boolean.FALSE would fall
        // through to value.toString().split(",") and produce a bogus single identifier "false".
        if (value == null || Boolean.FALSE.equals(value)) return List.of();
        List<String> result = new ArrayList<>();
        if (value instanceof Collection<?> values) {
            for (Object item : values) {
                if (item != null && !item.toString().isBlank()) result.add(item.toString().trim());
            }
        } else {
            for (String item : value.toString().split(",")) {
                if (!item.isBlank()) result.add(item.trim());
            }
        }
        return result;
    }

    /**
     * Whether this source type can be identified without a locator. Locator-optional types
     * (mailboxes, Discord, Slack history; see
     * {@link DocumentSourceDescriptor#locatorOptional(String)}) pass here unconditionally
     * because their own loaders validate their own identity metadata (e.g. {@code
     * SlackHistoryLoaderImpl} requires a channel, name, or {@code loadAllChannels=true} and
     * throws its own precise error otherwise); the remaining locator-centric-by-default types
     * (Google Drive, OneDrive, Notion) pass when the caller supplied the item identifiers as
     * loader metadata instead of a locator. Mirrors the loaders' own validation so the loaders'
     * precise errors surface instead of a generic gate. {@code type} is a raw, possibly
     * un-normalized caller-supplied string (e.g. {@code "gdrive"}) and may be {@code null}.
     */
    public static boolean identityWithoutLocator(String type, Map<String, Object> properties) {
        String canonical = type == null ? null : normalize(type);
        if (DocumentSourceDescriptor.locatorOptional(canonical)) return true;
        if (canonical == null) return false;
        for (String key : identifierMetadataKeys(canonical)) {
            Object value = properties == null ? null : properties.get(key);
            if (value != null && !identifiers(value).isEmpty()) return true;
        }
        return false;
    }

    private static boolean isTrue(Object value) {
        if (value instanceof Boolean bool) return bool;
        return value != null && Boolean.parseBoolean(value.toString().trim());
    }

    private static List<String> identifierMetadataKeys(String type) {
        return switch (type) {
            case "GDRIVE" -> List.of("fileIds", "folderId");
            case "ONEDRIVE" -> List.of("itemIds", "folderId");
            case "NOTION" -> List.of("pageIds", "databaseIds");
            case "SAP_NETWEAVER", "ODATA", "DYNAMICS365", "NETSUITE", "ODOO", "SALESFORCE", "ORACLE_FUSION", "ORACLE_EBS", "JD_EDWARDS", "INFOR_MONGOOSE", "ACUMATICA" -> List.of("serviceRoot");
            default -> List.of();
        };
    }

    /** ERP snapshots must distinguish service, account, entity collection and query scope. */
    public static String identityKey(String sourceType, String locator, Map<String, Object> properties) {
        String type = normalize(sourceType);
        return ErpSourceConfiguration.isErp(type)
                ? ErpSourceConfiguration.scopeIdentity(type, locator, properties == null ? Map.of() : properties)
                : identityKey(type, locator);
    }

    public static String identityKey(String sourceType, String locator) {
        String type = normalize(sourceType);
        String canonicalLocator = locator == null ? "" : locator.trim();
        if ("NOTION".equals(type)) {
            List<String> ids = new ArrayList<>();
            for (String value : canonicalLocator.split(",")) {
                String compact = value.replaceAll("[?#].*$", "")
                        .replaceAll("[^A-Fa-f0-9]", "");
                ids.add(compact.length() >= 32
                        ? compact.substring(compact.length() - 32).toLowerCase(Locale.ROOT)
                        : value.trim().toLowerCase(Locale.ROOT));
            }
            canonicalLocator = String.join(",", ids);
        } else if ("JIRA".equals(type) || "CONFLUENCE".equals(type)) {
            canonicalLocator = canonicalLocator.toLowerCase(Locale.ROOT).replaceAll("/+$", "");
        } else if ("REDDIT".equals(type)) {
            canonicalLocator = canonicalLocator.toLowerCase(Locale.ROOT)
                    .replaceFirst("^https?://(www\\.)?reddit\\.com/", "")
                    .replaceFirst("^/?r/", "").replaceAll("/+$", "");
        }
        return type.toLowerCase(Locale.ROOT) + ":" + canonicalLocator;
    }

    public static Map<String, Object> sanitizeMetadata(Map<?, ?> source) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        if (source == null) return sanitized;
        source.forEach((rawKey, value) -> {
            String key = String.valueOf(rawKey);
            if (!sensitive(key)) sanitized.put(key, sanitizeValue(value));
        });
        return sanitized;
    }

    private static Object sanitizeValue(Object value) {
        if (value instanceof Map<?, ?> map) return sanitizeMetadata(map);
        if (value instanceof Collection<?> values) return values.stream()
                .map(LocalExternalSourceLoaderRegistry::sanitizeValue).toList();
        if (value instanceof String text) return SourceCredentialRedactor.redact(text);
        return value;
    }

    private static boolean sensitive(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return SENSITIVE_SUFFIXES.stream().anyMatch(normalized::endsWith);
    }

    private static void replaceDirectory(Path staging, Path target) throws IOException {
        Path backup = null;
        if (Files.exists(target)) {
            backup = target.resolveSibling(target.getFileName() + ".backup-" + UUID.randomUUID());
            move(target, backup);
        }
        try {
            move(staging, target);
        } catch (IOException failure) {
            if (backup != null && !Files.exists(target)) move(backup, target);
            throw failure;
        }
        if (backup != null) {
            try {
                deleteRecursively(backup);
            } catch (IOException ignored) {
                // The new snapshot is already live; a stale backup is safe to prune later.
            }
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target);
        }
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (directory == null || !Files.exists(directory)) return;
        try (var walk = Files.walk(directory)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static List<Path> listFilesRecursively(Path root) throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        }
    }

    private static String digest(String identity) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8))).substring(0, 20);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String normalize(String value) {
        return value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private static String firstText(Map<String, Object> metadata, String... keys) {
        for (String key : keys) {
            Object value = metadata.get(key);
            if (value != null && !value.toString().isBlank()) return value.toString().trim();
        }
        return null;
    }

    /**
     * @param incremental whether this run merged into an existing snapshot using a {@code since}
     *                    window (F4) rather than fully replacing it
     * @param fetchedDocuments number of documents the loader returned this run (before any were
     *                         dropped as duplicates in {@link #writeDocuments})
     */
    public record MaterializedSource(
            Path directory, List<Path> files, String loaderName, boolean incremental, int fetchedDocuments) {
    }

    public record MaterializedFiles(Path directory, List<Path> files, List<String> warnings) {
    }
}
