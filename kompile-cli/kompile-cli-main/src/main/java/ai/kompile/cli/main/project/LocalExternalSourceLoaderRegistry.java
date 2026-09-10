/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.project;

import ai.kompile.core.crawl.graph.SourceCredentialRedactor;
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.core.loaders.DocumentLoader;
import ai.kompile.core.loaders.DocumentSourceDescriptor;
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
import ai.kompile.source.notion.NotionDocumentLoader;
import ai.kompile.source.reddit.RedditDocumentLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
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
            "GDRIVE", "GOOGLE_WORKSPACE", "ONEDRIVE");
    private static final Set<String> SENSITIVE_SUFFIXES = Set.of(
            "password", "token", "secret", "accesskey", "apikey", "privatekey",
            "authorization", "credential", "credentials");

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
     * returning the written paths. Only valid for types where {@link #downloadsOriginalFiles}
     * is true. Credentials come from the caller's properties (e.g. a bridged accessToken or a
     * connected OAuth store via the loader's own resolution).
     */
    public static List<Path> materializeFiles(
            String sourceType,
            String locator,
            Map<String, Object> properties,
            Path targetDirectory) throws Exception {
        String canonical = normalize(sourceType);
        if (!downloadsOriginalFiles(canonical)) {
            throw new IllegalArgumentException(
                    "Source type " + canonical + " does not download original files");
        }
        Map<String, Object> runtimeProperties = properties == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(properties);
        DocumentSourceDescriptor.SourceType type = DocumentSourceDescriptor.SourceType.valueOf(canonical);
        DocumentLoader loader = loader(type, JsonUtils.standardMapper());
        if (!(loader instanceof ai.kompile.core.loaders.FileDownloadingLoader downloader)) {
            throw new IllegalArgumentException(
                    "Loader for " + canonical + " does not support original-file download");
        }
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(type)
                .pathOrUrl(locator == null ? "" : locator.trim())
                .sourceId(identityKey(canonical, locator))
                .metadata(runtimeProperties)
                .build();
        return downloader.downloadTo(descriptor, targetDirectory);
    }

    /**
     * Contract for loaders that deliver original files for pipeline processing instead of
     * text documents: {@link ai.kompile.core.loaders.FileDownloadingLoader} (implemented by
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
        String canonical = normalize(sourceType);
        if (!TYPES.contains(canonical)) {
            throw new IllegalArgumentException("Unsupported project-local external source type: " + sourceType);
        }
        Map<String, Object> runtimeProperties = properties == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(properties);
        String trimmedLocator = locator == null ? "" : locator.trim();
        if (trimmedLocator.isEmpty() && !identityWithoutLocator(canonical, runtimeProperties)) {
            throw new IllegalArgumentException(canonical + " requires pathOrUrl/path/url");
        }
        applyUnifiedLimit(canonical, maxDocuments, runtimeProperties);
        String runtimeLocator = boundExplicitIdentifiers(
                canonical, trimmedLocator, runtimeProperties, maxDocuments);
        DocumentSourceDescriptor.SourceType type = DocumentSourceDescriptor.SourceType.valueOf(canonical);
        DocumentLoader loader = loader(type, mapper);
        DocumentSourceDescriptor descriptor = DocumentSourceDescriptor.builder()
                .type(type)
                .pathOrUrl(runtimeLocator)
                .sourceId(identityKey(canonical, locator))
                .metadata(runtimeProperties)
                .build();
        if (!loader.supports(descriptor)) {
            throw new IllegalStateException(loader.getName() + " does not support " + canonical);
        }
        List<Document> loaded = loader.load(descriptor);
        if (maxDocuments > 0 && loaded.size() > maxDocuments) {
            loaded = loaded.subList(0, maxDocuments);
        }

        Path parent = targetDirectory.toAbsolutePath().normalize().getParent();
        if (parent == null) throw new IOException("External source target has no parent: " + targetDirectory);
        Files.createDirectories(parent);
        Path staging = parent.resolve(targetDirectory.getFileName() + ".staging-" + UUID.randomUUID());
        Files.createDirectories(staging);
        List<Path> relativeFiles = new ArrayList<>();
        int index = 0;
        try {
            for (Document document : loaded) {
                if (document == null || document.getText() == null || document.getText().isBlank()) continue;
                Map<String, Object> metadata = sanitizeMetadata(document.getMetadata());
                String title = firstText(metadata, "title", "fileName", "file_name", "subject", "name");
                if (title == null) title = canonical + " document " + (index + 1);
                String identity = firstText(metadata, "notion.pageId", "source_path", "issueKey",
                        "postId", "messageId", "threadId", "id", "source");
                if (identity == null) identity = identityKey(canonical, locator) + "\n" + index;
                String fileName = digest(identity) + ".md";
                Path output = staging.resolve(fileName).normalize();
                if (!output.startsWith(staging)) throw new IOException("Materialized source escaped staging directory");
                StringBuilder markdown = new StringBuilder();
                markdown.append("<!-- kompile-source-type: ").append(canonical).append(" -->\n");
                markdown.append("<!-- kompile-source-metadata: ")
                        .append(mapper.writeValueAsString(metadata).replace("-->", "--\\u003e"))
                        .append(" -->\n\n");
                markdown.append(document.getText().trim()).append('\n');
                Files.writeString(output, markdown.toString(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                relativeFiles.add(staging.relativize(output));
                index++;
            }
            if (relativeFiles.isEmpty()) {
                throw new IllegalStateException(loader.getName() + " returned no readable documents");
            }
            replaceDirectory(staging, targetDirectory.toAbsolutePath().normalize());
        } catch (Exception failure) {
            deleteRecursively(staging);
            throw failure;
        }
        List<Path> files = relativeFiles.stream().map(targetDirectory::resolve).toList();
        return new MaterializedSource(targetDirectory, files, loader.getName());
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
            default -> throw new IllegalArgumentException("No local external loader for " + type);
        };
    }

    private static void applyUnifiedLimit(String type, int maxDocuments, Map<String, Object> properties) {
        if (maxDocuments <= 0) return;
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
        Object configured = properties.get(key);
        int existing;
        try {
            existing = configured instanceof Number number ? number.intValue()
                    : configured == null ? maxDocuments : Integer.parseInt(configured.toString());
        } catch (NumberFormatException ignored) {
            existing = maxDocuments;
        }
        properties.put(key, Math.min(Math.max(1, existing), maxDocuments));
    }

    private static int bounded(Object configured, int limit) {
        if (configured == null) return limit;
        try {
            int value = configured instanceof Number number
                    ? number.intValue() : Integer.parseInt(configured.toString());
            return Math.max(1, Math.min(limit, value));
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
        if (value == null) return List.of();
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
     * Whether this source type can be identified without a locator: either its loader never
     * reads the locator ({@link DocumentSourceDescriptor#locatorOptional(String)}) or the
     * caller supplied the item identifiers as loader metadata (drive file ids, Notion page
     * ids, Discord guild id). Mirrors the loaders' own validation so the loaders' precise
     * errors surface instead of a generic gate.
     */
    public static boolean identityWithoutLocator(String type, Map<String, Object> properties) {
        if (DocumentSourceDescriptor.locatorOptional(type)) return true;
        for (String key : identifierMetadataKeys(type)) {
            Object value = properties == null ? null : properties.get(key);
            if (value != null && !identifiers(value).isEmpty()) return true;
        }
        return false;
    }

    private static List<String> identifierMetadataKeys(String type) {
        return switch (type) {
            case "GDRIVE" -> List.of("fileIds", "folderId");
            case "ONEDRIVE" -> List.of("itemIds", "folderId");
            case "NOTION" -> List.of("pageIds", "databaseIds");
            case "DISCORD", "DISCORD_HISTORY" -> List.of("guildId");
            default -> List.of();
        };
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

    public record MaterializedSource(Path directory, List<Path> files, String loaderName) {
    }
}
