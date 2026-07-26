/*
 * Copyright 2025 Kompile Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.kompile.staging.download;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Safely parsed canonical Hugging Face model reference.
 */
public record HuggingFaceReference(
        String repository,
        String requestedRevision,
        String requestedPath,
        Kind kind,
        String canonicalReference) {

    public enum Kind {
        REPOSITORY,
        TREE,
        BLOB,
        RESOLVE
    }

    private static final Pattern REPOSITORY_ID = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._-]{0,95}/[A-Za-z0-9][A-Za-z0-9._-]{0,95}");
    private static final Pattern REVISION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,255}");

    public static HuggingFaceReference parse(String value, String revisionOverride) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Hugging Face repository or URL is required");
        }
        String input = value.trim();
        if (!input.contains("://")) {
            String repository = requireRepositoryId(input);
            String revision = requireRevision(defaultRevision(revisionOverride));
            return new HuggingFaceReference(
                    repository,
                    revision,
                    null,
                    Kind.REPOSITORY,
                    "https://huggingface.co/" + repository);
        }

        URI uri;
        try {
            uri = URI.create(input);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid Hugging Face URL", invalid);
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || (!"huggingface.co".equals(host) && !"www.huggingface.co".equals(host))
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || uri.getRawPath() == null
                || uri.getRawPath().indexOf('%') >= 0
                || uri.getRawPath().indexOf('\\') >= 0) {
            throw new IllegalArgumentException(
                    "Hugging Face URLs must be public canonical HTTPS URLs without credentials, query, fragment, or encoded path segments");
        }

        String[] segments = uri.getRawPath().replaceFirst("^/+", "").split("/", -1);
        if (segments.length < 2) {
            throw new IllegalArgumentException("Hugging Face URL must identify an owner and repository");
        }
        String repository = requireRepositoryId(segments[0] + "/" + segments[1]);
        if (segments.length == 2) {
            String revision = requireRevision(defaultRevision(revisionOverride));
            return new HuggingFaceReference(
                    repository,
                    revision,
                    null,
                    Kind.REPOSITORY,
                    "https://huggingface.co/" + repository);
        }
        if (segments.length < 4) {
            throw new IllegalArgumentException("Hugging Face tree/blob/resolve URL is incomplete");
        }

        Kind kind;
        try {
            kind = Kind.valueOf(segments[2].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unsupported) {
            throw new IllegalArgumentException(
                    "Supported Hugging Face URLs are repository, tree, blob, or resolve URLs");
        }
        String urlRevision = requireRevision(segments[3]);
        if (revisionOverride != null && !revisionOverride.isBlank()
                && !urlRevision.equals(revisionOverride.trim())) {
            throw new IllegalArgumentException(
                    "Hugging Face URL revision conflicts with the explicit revision");
        }
        String path = joinPath(segments, 4);
        if ((kind == Kind.BLOB || kind == Kind.RESOLVE) && path == null) {
            throw new IllegalArgumentException("Hugging Face blob/resolve URL must identify a file");
        }
        String canonical = "https://huggingface.co/" + repository + "/"
                + kind.name().toLowerCase(Locale.ROOT) + "/" + urlRevision
                + (path == null ? "" : "/" + path);
        return new HuggingFaceReference(repository, urlRevision, path, kind, canonical);
    }

    public static String requireRepositoryId(String repository) {
        if (repository == null || !REPOSITORY_ID.matcher(repository).matches()) {
            throw new IllegalArgumentException(
                    "Hugging Face repository must be an owner/repository identifier");
        }
        return repository;
    }

    public static String requireRevision(String revision) {
        if (revision == null || !REVISION.matcher(revision).matches()
                || revision.contains("//") || hasDotSegment(revision)) {
            throw new IllegalArgumentException("Invalid Hugging Face revision");
        }
        return revision;
    }

    public boolean requestedPathIsModel() {
        if (requestedPath == null) {
            return false;
        }
        String lower = requestedPath.toLowerCase(Locale.ROOT);
        return lower.endsWith(".gguf") || lower.endsWith(".ggml");
    }

    private static String defaultRevision(String revision) {
        return revision == null || revision.isBlank() ? "main" : revision.trim();
    }

    private static String joinPath(String[] segments, int start) {
        if (segments.length <= start) {
            return null;
        }
        StringBuilder path = new StringBuilder();
        for (int i = start; i < segments.length; i++) {
            String segment = segments[i];
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("Invalid Hugging Face repository path");
            }
            if (path.length() > 0) {
                path.append('/');
            }
            path.append(segment);
        }
        return path.toString();
    }

    private static boolean hasDotSegment(String value) {
        for (String segment : value.split("/", -1)) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                return true;
            }
        }
        return false;
    }
}
