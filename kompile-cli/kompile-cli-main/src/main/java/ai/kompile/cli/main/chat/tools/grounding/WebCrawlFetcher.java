/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools.grounding;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Bounded, same-origin, breadth-first crawl from a WEB_CRAWL seed URL.
 *
 * <p>Pages are staged into a temporary sibling directory and atomically swapped into place so a
 * re-crawl drops stale pages instead of accumulating them (mirrors the staging + atomic replace
 * pattern used elsewhere for materialized connector directories). Every fetch (seed and
 * discovered links) goes through {@link RemoteFetchGuard}, so the same SSRF and redirect rules
 * that guard single-URL documents apply to every page this crawl visits.
 */
final class WebCrawlFetcher {

    /** Safety ceiling on how many links are ever enqueued, independent of maxDocuments. */
    private static final int MAX_ENQUEUED_MULTIPLIER = 20;

    private WebCrawlFetcher() {
    }

    record Page(String url, Path file) {
    }

    record Result(List<Page> pages, List<String> warnings) {
    }

    private record Staged(String url, String fileName) {
    }

    private record Frontier(URI uri, int depth) {
    }

    static Result crawl(String seedUrl,
                         int maxDepth,
                         int maxDocuments,
                         List<String> includePatterns,
                         List<String> excludePatterns,
                         Path targetDirectory,
                         boolean allowPrivateNetworkUrls) throws Exception {
        URI seed = URI.create(seedUrl);
        String seedScheme = seed.getScheme();
        if (seedScheme == null
                || !(seedScheme.equalsIgnoreCase("http") || seedScheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("WEB_CRAWL supports only http and https seed URLs: " + seedUrl);
        }
        int effectiveMaxDocuments = maxDocuments > 0 ? maxDocuments : 25;
        int effectiveMaxDepth = Math.max(0, maxDepth);
        String origin = origin(seed);
        int enqueueCap = Math.max(effectiveMaxDocuments * MAX_ENQUEUED_MULTIPLIER, effectiveMaxDocuments + 50);

        Path parent = targetDirectory.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new IOException("WEB_CRAWL target has no parent directory: " + targetDirectory);
        }
        Files.createDirectories(parent);
        Path staging = parent.resolve(targetDirectory.getFileName().toString() + ".staging-" + UUID.randomUUID());
        Files.createDirectories(staging);

        List<Staged> staged = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        try {
            Set<String> visited = new LinkedHashSet<>();
            Deque<Frontier> queue = new ArrayDeque<>();
            queue.add(new Frontier(stripFragment(seed), 0));
            int fileIndex = 0;

            while (!queue.isEmpty() && staged.size() < effectiveMaxDocuments) {
                Frontier current = queue.poll();
                if (!visited.add(current.uri().toString())) {
                    continue;
                }
                if (!origin.equals(origin(current.uri()))) {
                    continue;
                }
                if (!allowedByPatterns(current.uri(), includePatterns, excludePatterns)) {
                    continue;
                }

                RemoteFetchGuard.FetchResult fetched;
                try {
                    fetched = RemoteFetchGuard.fetch(current.uri(), target -> HttpRequest.newBuilder(target)
                            .timeout(Duration.ofSeconds(60))
                            .header("Accept", "text/html,application/xhtml+xml,text/plain,text/markdown,*/*;q=0.5")
                            .header("User-Agent", "Kompile-MCP/0.1")
                            .GET()
                            .build(), allowPrivateNetworkUrls);
                } catch (Exception fetchFailure) {
                    warnings.add("WEB_CRAWL skipped " + current.uri() + ": " + messageOf(fetchFailure));
                    continue;
                }

                HttpResponse<InputStream> response = fetched.response();
                URI finalUri = fetched.uri();
                visited.add(finalUri.toString());
                if (current.depth() == 0) {
                    // The seed itself may redirect (http->https, apex->www); adopt its final
                    // origin as the crawl's origin so links discovered on the redirected page
                    // are recognized as same-origin instead of silently dropped.
                    origin = origin(finalUri);
                } else if (!origin.equals(origin(finalUri))) {
                    // RemoteFetchGuard SSRF-validates every redirect hop but does not enforce
                    // same-origin, so a same-origin link that itself redirects off-origin must
                    // still be rejected here rather than saved under the crawl's origin.
                    response.body().close();
                    warnings.add("WEB_CRAWL skipped " + current.uri() + ": redirected off-origin to " + finalUri);
                    continue;
                }
                String contentType = response.headers().firstValue("Content-Type").orElse("");
                byte[] content;
                try {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        response.body().close();
                        throw new IOException("HTTP " + response.statusCode());
                    }
                    long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
                    if (declaredLength > LocalProjectCrawlBackend.MAX_REMOTE_SOURCE_BYTES) {
                        response.body().close();
                        throw new IOException("resource exceeds the 25 MiB in-process limit");
                    }
                    try (InputStream input = response.body()) {
                        content = input.readNBytes(LocalProjectCrawlBackend.MAX_REMOTE_SOURCE_BYTES + 1);
                    }
                    if (content.length > LocalProjectCrawlBackend.MAX_REMOTE_SOURCE_BYTES) {
                        throw new IOException("resource exceeds the 25 MiB in-process limit");
                    }
                    if (content.length == 0) {
                        throw new IOException("empty response");
                    }
                } catch (IOException fetchIssue) {
                    warnings.add("WEB_CRAWL skipped " + current.uri() + ": " + fetchIssue.getMessage());
                    continue;
                }

                String suffix = LocalProjectCrawlBackend.remoteSuffix(finalUri, contentType);
                String fileName = "page-" + (fileIndex++) + "-"
                        + Integer.toUnsignedString(finalUri.toString().hashCode(), 16) + suffix;
                Files.write(staging.resolve(fileName), content);
                staged.add(new Staged(finalUri.toString(), fileName));

                boolean htmlLike = contentType.toLowerCase(Locale.ROOT).contains("html");
                if (htmlLike && current.depth() < effectiveMaxDepth) {
                    enqueueLinks(content, finalUri, origin, current.depth(), visited, queue, enqueueCap, warnings);
                }
            }

            if (staged.isEmpty()) {
                throw new IOException("WEB_CRAWL produced no pages for " + seedUrl);
            }
            replaceDirectory(staging, targetDirectory);
        } catch (Exception failure) {
            LocalProjectCrawlBackend.deleteRecursively(staging);
            throw failure;
        }

        List<Page> pages = new ArrayList<>();
        for (Staged page : staged) {
            pages.add(new Page(page.url(), targetDirectory.resolve(page.fileName())));
        }
        return new Result(pages, warnings);
    }

    private static void enqueueLinks(byte[] content,
                                      URI pageUri,
                                      String origin,
                                      int depth,
                                      Set<String> visited,
                                      Deque<Frontier> queue,
                                      int enqueueCap,
                                      List<String> warnings) {
        Document parsed;
        try {
            parsed = Jsoup.parse(new String(content, StandardCharsets.UTF_8), pageUri.toString());
        } catch (Exception malformedHtml) {
            warnings.add("WEB_CRAWL could not parse links from " + pageUri + ": " + messageOf(malformedHtml));
            return;
        }
        for (Element link : parsed.select("a[href]")) {
            if (visited.size() + queue.size() >= enqueueCap) {
                break;
            }
            String href = link.attr("abs:href");
            if (href.isBlank() || !(href.startsWith("http://") || href.startsWith("https://"))) {
                continue;
            }
            URI candidate;
            try {
                candidate = stripFragment(new URI(href));
            } catch (URISyntaxException malformed) {
                continue;
            }
            if (!origin.equals(origin(candidate)) || visited.contains(candidate.toString())) {
                continue;
            }
            queue.add(new Frontier(candidate, depth + 1));
        }
    }

    private static String messageOf(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static String origin(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        if (port < 0) {
            port = "https".equals(scheme) ? 443 : 80;
        }
        return scheme + "://" + host + ":" + port;
    }

    private static URI stripFragment(URI uri) {
        if (uri.getFragment() == null) {
            return uri;
        }
        try {
            return new URI(uri.getScheme(), uri.getSchemeSpecificPart(), null);
        } catch (URISyntaxException malformed) {
            return uri;
        }
    }

    private static boolean allowedByPatterns(URI uri, List<String> includePatterns, List<String> excludePatterns) {
        String rawPath = uri.getPath();
        String relative = rawPath == null ? "" : rawPath.replaceFirst("^/+", "");
        Path asPath = Path.of(relative.isBlank() ? "." : relative);
        if (!excludePatterns.isEmpty() && matchesAny(asPath, excludePatterns)) {
            return false;
        }
        return includePatterns.isEmpty() || matchesAny(asPath, includePatterns);
    }

    /**
     * Mirrors {@code ProjectCrawlCommand}'s {@code matchesAny}/{@code matchesGlob} glob
     * semantics (JDK {@link PathMatcher}, with a basename fallback for {@code **}/-prefixed
     * patterns) so WEB_CRAWL include/exclude patterns behave the same way local document
     * patterns do. Duplicated locally because the reference implementation is a private method
     * in a class this backend does not own.
     */
    private static boolean matchesAny(Path path, List<String> patterns) {
        for (String pattern : patterns) {
            if (matchesGlob(path, pattern)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesGlob(Path path, String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return false;
        }
        String normalizedPattern = pattern.replace('\\', '/');
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + normalizedPattern);
        if (matcher.matches(path) || path.getFileName() != null && matcher.matches(path.getFileName())) {
            return true;
        }
        if (normalizedPattern.startsWith("**/")) {
            PathMatcher basenameMatcher =
                    FileSystems.getDefault().getPathMatcher("glob:" + normalizedPattern.substring(3));
            return basenameMatcher.matches(path)
                    || path.getFileName() != null && basenameMatcher.matches(path.getFileName());
        }
        return false;
    }

    private static void replaceDirectory(Path staging, Path target) throws IOException {
        Path backup = null;
        if (Files.exists(target)) {
            backup = target.resolveSibling(target.getFileName().toString() + ".backup-" + UUID.randomUUID());
            moveAtomicOrFallback(target, backup);
        }
        try {
            moveAtomicOrFallback(staging, target);
        } catch (IOException failure) {
            if (backup != null && !Files.exists(target)) {
                moveAtomicOrFallback(backup, target);
            }
            throw failure;
        }
        if (backup != null) {
            LocalProjectCrawlBackend.deleteRecursively(backup);
        }
    }

    private static void moveAtomicOrFallback(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target);
        }
    }
}
