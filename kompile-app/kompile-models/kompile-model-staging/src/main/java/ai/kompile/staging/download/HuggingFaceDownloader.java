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

package ai.kompile.staging.download;

import ai.kompile.staging.config.StagingAssetLimits;
import ai.kompile.staging.http.SafeHttpTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;
import java.util.function.Consumer;

/**
 * Download service for HuggingFace models.
 * Downloads ONNX models and associated vocabulary files.
 */
@Component
public class HuggingFaceDownloader implements DownloadService {

    private static final Logger log = LoggerFactory.getLogger(HuggingFaceDownloader.class);
    private static final URI DEFAULT_BASE_URI = URI.create("https://huggingface.co/");
    private static final Pattern REPOSITORY_ID = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._-]{0,95}/[A-Za-z0-9][A-Za-z0-9._-]{0,95}");
    private static final Pattern SAFE_REVISION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,255}");
    private static final Pattern IMMUTABLE_REVISION = Pattern.compile("(?i)[0-9a-f]{40,64}");
    private static final Pattern QUANTIZATION_HINT = Pattern.compile(
            "(?i)(IQ[1-4](?:_[A-Z0-9]+)+|Q[2-8](?:_[A-Z0-9]+)*|BF16|FP16|F16|INT8)");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int BUFFER_SIZE = 8192;
    private static final int CONNECTION_TIMEOUT = 30000;
    private static final int READ_TIMEOUT = 600000; // 10 minutes — large model files (e.g. 2.3GB model.onnx_data)

    private final URI baseUri;
    private final StagingAssetLimits limits;
    private final boolean allowHttpLoopback;
    private final SafeHttpTransport httpTransport;

    public HuggingFaceDownloader() {
        this(new StagingAssetLimits(), new SafeHttpTransport());
    }

    public HuggingFaceDownloader(StagingAssetLimits limits) {
        this(limits, new SafeHttpTransport());
    }

    @Autowired
    public HuggingFaceDownloader(
            StagingAssetLimits limits, SafeHttpTransport httpTransport) {
        this(DEFAULT_BASE_URI, limits, false, httpTransport);
    }

    HuggingFaceDownloader(URI baseUri, StagingAssetLimits limits, boolean allowHttpLoopback) {
        this(
                baseUri,
                limits,
                allowHttpLoopback,
                allowHttpLoopback
                        ? SafeHttpTransport.loopbackForTests()
                        : new SafeHttpTransport());
    }

    private HuggingFaceDownloader(
            URI baseUri,
            StagingAssetLimits limits,
            boolean allowHttpLoopback,
            SafeHttpTransport httpTransport) {
        this.baseUri = requireBaseUri(baseUri, allowHttpLoopback);
        this.limits = limits == null ? new StagingAssetLimits() : limits;
        this.allowHttpLoopback = allowHttpLoopback;
        this.httpTransport = java.util.Objects.requireNonNull(
                httpTransport, "httpTransport");
    }

    @Override
    public String getSourceName() {
        return "huggingface";
    }

    @Override
    public boolean canHandle(String source) {
        return "huggingface".equalsIgnoreCase(source) || "hf".equalsIgnoreCase(source);
    }

    @Override
    public DownloadResult download(DownloadRequest request, Path destination) {
        return download(request, destination, progress -> {});
    }

    @Override
    public DownloadResult download(DownloadRequest request, Path destination,
                                   Consumer<DownloadProgress> progressCallback) {
        return download(request, destination, progressCallback, StagingCancellation.NONE);
    }

    @Override
    public DownloadResult download(
            DownloadRequest request,
            Path destination,
            Consumer<DownloadProgress> progressCallback,
            StagingCancellation cancellation) {
        StagingCancellation signal = cancellation == null
                ? StagingCancellation.NONE
                : cancellation;
        long startTime = System.currentTimeMillis();
        Map<String, Path> downloadedFiles = new LinkedHashMap<>();
        long totalBytes = 0;

        try {
            signal.checkpoint();
            boolean componentSource = ComponentUrlDownloader.isComponentSource(
                    request == null ? null : request.getSource());
            String repositoryId = null;
            if (componentSource) {
                prepareComponentRequest(request);
                progressCallback.accept(DownloadProgress.initializing(
                        "Preparing complete public HTTPS component bundle"));
            } else {
                prepareResolvedRequest(request);
                repositoryId = requireRepositoryId(
                        request == null ? null : request.getRepository());
                progressCallback.accept(DownloadProgress.initializing(
                        "Preparing pinned Hugging Face download: " + repositoryId
                                + "@" + request.getRevision()));
            }

            // Create destination directory
            Files.createDirectories(destination);

            // Determine files to download. URL assets are typed separately and
            // override discovery without creating another model format.
            Map<String, String> files = new LinkedHashMap<>(filesForRequest(request));
            Map<String, String> urlOverrides = request.getTextAssetUrls() == null
                    ? Map.of()
                    : request.getTextAssetUrls().toUrlMap();
            for (String key : urlOverrides.keySet()) {
                files.putIfAbsent(key, canonicalAssetFileName(key, request.getFormat(), urlOverrides.get(key)));
            }

            // Download each file
            for (Map.Entry<String, String> entry : files.entrySet()) {
                String fileKey = entry.getKey();
                String filePath = entry.getValue();

                signal.checkpoint();
                String explicitUrl = urlOverrides.get(fileKey);
                URI uri;
                if (explicitUrl != null) {
                    uri = requirePublicComponentUri(explicitUrl);
                } else if (componentSource) {
                    throw new IOException(
                            "HTTPS component bundle omitted a URL for " + fileKey);
                } else {
                    uri = buildDownloadUri(repositoryId, filePath, request.getRevision());
                }
                String fileName = canonicalAssetFileName(fileKey, request.getFormat(), filePath);
                Path targetPath = destination.toAbsolutePath().normalize()
                        .resolve(fileName).normalize();
                if (!targetPath.startsWith(destination.toAbsolutePath().normalize())) {
                    throw new IOException("Hugging Face asset escapes the destination");
                }

                progressCallback.accept(DownloadProgress.initializing(
                        "Downloading " + fileName + " from the pinned source"));

                long fileBytes;
                try {
                    long remainingTotal = limits.getTotalBytes() - totalBytes;
                    if (remainingTotal <= 0L) {
                        throw new IOException("Hugging Face bundle exceeds the total-byte limit");
                    }
                    // Repository-defined component keys (for example a VLM vision encoder)
                    // are not limited to the text-model key vocabulary. Classify the
                    // actual source file so every weight-bearing ONNX/safetensors asset
                    // receives the model limit while tokenizer/config sidecars retain
                    // their narrower limits.
                    fileBytes = downloadFile(
                            uri,
                            targetPath,
                            componentSource ? null : request.getAuthToken(),
                            Math.min(limits.maxBytesForFileName(filePath), remainingTotal),
                            signal,
                            progressCallback);
                } catch (IOException e) {
                    if (isOptionalAuxiliaryFile(request, fileKey)) {
                        log.warn("Optional {} file '{}' was not available for {}: {}",
                                fileKey, filePath, request.getModelId(), e.getMessage());
                        continue;
                    }
                    throw e;
                }

                downloadedFiles.put(fileKey, targetPath);
                totalBytes += fileBytes;

                log.info("Downloaded {} ({} bytes)", fileName, fileBytes);
            }

            if (requiresCompleteTextModel(request)
                    && !downloadedFiles.containsKey(TextModelAssetMap.TOKENIZER_CONFIG)
                    && !downloadedFiles.containsKey(TextModelAssetMap.CHAT_TEMPLATE)) {
                throw new IOException("Runnable chat staging requires tokenizer_config.json or chat_template.jinja");
            }
            signal.checkpoint();

            // Calculate checksum of model file
            Path modelPath = downloadedFiles.get("model");
            String checksum = modelPath != null ? calculateSha256(modelPath) : null;

            progressCallback.accept(DownloadProgress.completed(
                    "Downloaded " + downloadedFiles.size() + " files from "
                            + (componentSource
                                    ? "the public HTTPS component bundle"
                                    : request.getRepository())));

            long duration = System.currentTimeMillis() - startTime;

            return DownloadResult.builder()
                    .success(true)
                    .modelPath(downloadedFiles.get("model"))
                    .vocabPath(downloadedFiles.get("vocab"))
                    .tokenizerConfigPath(downloadedFiles.get("tokenizer_config"))
                    .downloadedFiles(downloadedFiles)
                    .checksum(checksum)
                    .totalBytes(totalBytes)
                    .durationMs(duration)
                    .build();

        } catch (CancellationException cancelled) {
            cleanupDownloadedFiles(downloadedFiles);
            throw cancelled;
        } catch (Exception e) {
            cleanupDownloadedFiles(downloadedFiles);
            String source = request != null ? request.getSourceReference() : null;
            log.error("Failed to download staged text-model source {} ({})",
                    source, e.getClass().getSimpleName());
            log.debug("Text-model download failure details for {}", source, e);
            progressCallback.accept(DownloadProgress.failed(e.getMessage()));
            return DownloadResult.failure("Download failed: " + e.getMessage());
        }
    }

    @Override
    public boolean isAvailable(DownloadRequest request) {
        try {
            URI uri;
            if (ComponentUrlDownloader.isComponentSource(
                    request == null ? null : request.getSource())) {
                TextModelAssetUrlMap urls = request.getTextAssetUrls();
                uri = requirePublicComponentUri(urls == null ? null : urls.getModel());
            } else {
                uri = buildDownloadUri(
                        requireRepositoryId(request == null ? null : request.getRepository()),
                        TextModelAssetMap.MODEL_CONFIG_FILE,
                        request == null ? null : request.getRevision());
            }
            try (SafeHttpTransport.Response response =
                         openFollowingRedirects(uri, null, "HEAD")) {
                return response.statusCode() == 200;
            }
        } catch (Exception e) {
            log.debug("Hugging Face model is not available: {}",
                    request != null ? request.getRepository() : null);
            return false;
        }
    }

    /**
     * Resolve a pasted repository/tree/blob/resolve reference through the Hugging
     * Face API. The returned revision is always an immutable commit and no model
     * candidate is silently selected when the repository contains more than one.
     */
    public HuggingFaceDiscovery discover(
            String reference,
            String revision,
            String authToken) throws IOException {
        HuggingFaceReference parsed = HuggingFaceReference.parse(reference, revision);
        URI apiUri = baseUri.resolve(
                "api/models/" + parsed.repository() + "/revision/"
                        + encodePathSegment(parsed.requestedRevision()));
        JsonNode model = fetchJson(apiUri, authToken);
        String resolvedRevision = model.path("sha").asText("").trim();
        if (!IMMUTABLE_REVISION.matcher(resolvedRevision).matches()) {
            throw new IOException("Hugging Face API did not return an immutable commit SHA");
        }

        JsonNode siblings = model.path("siblings");
        if (!siblings.isArray()) {
            throw new IOException("Hugging Face API response omitted repository files");
        }
        List<RepositoryFile> repositoryFiles = new ArrayList<>();
        for (JsonNode sibling : siblings) {
            String path = sibling.path("rfilename").asText("").trim();
            if (path.isEmpty()) {
                continue;
            }
            requireRepositoryRelativePath(path, "repository file");
            long size = sibling.path("size").asLong(-1L);
            if (size < 0L) {
                size = sibling.path("lfs").path("size").asLong(-1L);
            }
            repositoryFiles.add(new RepositoryFile(path, size));
            if (repositoryFiles.size() > 100_000) {
                throw new IOException("Hugging Face repository file list is unreasonably large");
            }
        }

        String scope = parsed.kind() == HuggingFaceReference.Kind.TREE
                ? parsed.requestedPath()
                : null;
        List<HuggingFaceDiscovery.ModelCandidate> candidates = repositoryFiles.stream()
                .filter(file -> withinScope(file.path(), scope))
                .filter(file -> isGgufOrGgml(file.path()))
                .map(file -> HuggingFaceDiscovery.ModelCandidate.builder()
                        .path(file.path())
                        .size(file.size())
                        .format(formatFor(file.path()))
                        .quantizationHint(quantizationHint(file.path()))
                        .build())
                .sorted(Comparator.comparing(HuggingFaceDiscovery.ModelCandidate::getPath))
                .toList();

        String selectedModel = null;
        if (parsed.requestedPathIsModel()) {
            selectedModel = parsed.requestedPath();
            String expected = selectedModel;
            if (repositoryFiles.stream().noneMatch(file -> file.path().equals(expected))) {
                throw new IOException("Hugging Face URL model path is absent at the resolved commit");
            }
        } else if (candidates.size() == 1) {
            selectedModel = candidates.get(0).getPath();
        }

        TextModelAssetMap assets = TextModelAssetMap.builder()
                .model(selectedModel)
                .tokenizer(findUnique(repositoryFiles, TextModelAssetMap.TOKENIZER_FILE))
                .tokenizerConfig(findUnique(repositoryFiles, TextModelAssetMap.TOKENIZER_CONFIG_FILE))
                .specialTokensMap(findUnique(repositoryFiles, TextModelAssetMap.SPECIAL_TOKENS_MAP_FILE))
                .addedTokens(findUnique(repositoryFiles, TextModelAssetMap.ADDED_TOKENS_FILE))
                .chatTemplate(findUnique(repositoryFiles, TextModelAssetMap.CHAT_TEMPLATE_FILE))
                .generationConfig(findUnique(repositoryFiles, TextModelAssetMap.GENERATION_CONFIG_FILE))
                .modelConfig(findUnique(repositoryFiles, TextModelAssetMap.MODEL_CONFIG_FILE))
                .textGeneration(findUnique(repositoryFiles, TextModelAssetMap.TEXT_GENERATION_FILE))
                .build();

        return HuggingFaceDiscovery.builder()
                .repository(parsed.repository())
                .requestedRevision(parsed.requestedRevision())
                .resolvedRevision(resolvedRevision)
                .requestedPath(parsed.requestedPath())
                .referenceType(parsed.kind().name().toLowerCase(Locale.ROOT))
                .discoveredAssets(assets)
                .modelCandidates(candidates)
                .requiresModelSelection(selectedModel == null && candidates.size() > 1)
                .build();
    }

    private void prepareResolvedRequest(DownloadRequest request) throws IOException {
        if (request == null) {
            throw new IllegalArgumentException("Hugging Face download request is required");
        }
        String input = request.getRepository();
        boolean resolve = requiresCompleteTextModel(request)
                || (input != null && input.contains("://"))
                || (request.getRevision() != null && !request.getRevision().isBlank());
        if (!resolve) {
            request.setRepository(requireRepositoryId(input));
            return;
        }

        HuggingFaceReference parsed = HuggingFaceReference.parse(input, request.getRevision());
        HuggingFaceDiscovery discovery = discover(input, request.getRevision(), request.getAuthToken());
        Map<String, String> resolvedAssets =
                new LinkedHashMap<>(discovery.getDiscoveredAssets().toFileMap());
        resolvedAssets.putAll(request.effectiveTextAssets().toFileMap());

        Map<String, String> urlOverrides = request.getTextAssetUrls() == null
                ? Map.of()
                : request.getTextAssetUrls().toUrlMap();
        for (Map.Entry<String, String> override : urlOverrides.entrySet()) {
            requirePublicComponentUri(override.getValue());
            resolvedAssets.put(
                    override.getKey(),
                    canonicalAssetFileName(
                            override.getKey(), request.getFormat(), override.getValue()));
        }

        TextModelAssetMap assets = TextModelAssetMap.fromFileMap(resolvedAssets);
        if (requiresCompleteTextModel(request)) {
            if (assets.getModel() == null && discovery.isRequiresModelSelection()) {
                throw new IllegalArgumentException(
                        "Hugging Face repository contains multiple GGUF/GGML models. "
                                + "Discover the repository and select one exact model path.");
            }
            List<String> missing = assets.missingRunnableChatAssets();
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException(
                        "Runnable Hugging Face text staging is missing: "
                                + String.join(", ", missing)
                                + ". Use discovery or provide explicit component URLs.");
            }
        }

        request.setRepository(discovery.getRepository());
        request.setRequestedRevision(discovery.getRequestedRevision());
        request.setRevision(discovery.getResolvedRevision());
        request.setSourceReference(
                "https://huggingface.co/" + discovery.getRepository()
                        + "/tree/" + discovery.getResolvedRevision());
        request.setTextAssets(assets);
        if (assets.getModel() != null) {
            request.setFormat(formatFor(assets.getModel()));
        }

        Map<String, String> provenance = new LinkedHashMap<>();
        for (Map.Entry<String, String> asset : assets.toFileMap().entrySet()) {
            String explicit = urlOverrides.get(asset.getKey());
            provenance.put(
                    asset.getKey(),
                    explicit == null
                            ? "hf://" + discovery.getRepository() + "@"
                                    + discovery.getResolvedRevision() + "/" + asset.getValue()
                            : sanitizedPublicUri(explicit));
        }
        request.setSourceAssetProvenance(provenance);
    }

    private void prepareComponentRequest(DownloadRequest request) throws IOException {
        if (request == null) {
            throw new IllegalArgumentException("HTTPS component download request is required");
        }
        if (!ComponentUrlDownloader.isComponentSource(request.getSource())) {
            throw new IllegalArgumentException("Invalid HTTPS component source");
        }
        if (request.getRepository() != null && !request.getRepository().isBlank()) {
            throw new IllegalArgumentException(
                    "HTTPS component staging is repository-free; do not provide repository");
        }
        if ((request.getRevision() != null && !request.getRevision().isBlank())
                || (request.getRequestedRevision() != null
                    && !request.getRequestedRevision().isBlank())) {
            throw new IllegalArgumentException(
                    "HTTPS component staging does not accept a repository revision");
        }
        if (request.getAuthToken() != null && !request.getAuthToken().isBlank()) {
            throw new IllegalArgumentException(
                    "HTTPS component staging accepts only public URLs and no auth token");
        }
        if (!request.effectiveFiles().isEmpty()) {
            throw new IllegalArgumentException(
                    "HTTPS component staging accepts URLs only, not repository-relative paths");
        }

        TextModelAssetUrlMap urls = request.getTextAssetUrls();
        if (urls == null) {
            throw new IllegalArgumentException(
                    "HTTPS component staging requires a complete textAssetUrls bundle");
        }
        List<String> missing = urls.missingRunnableChatAssets();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "Runnable HTTPS component staging is missing: "
                            + String.join(", ", missing) + ".");
        }

        Map<String, String> provenance = new LinkedHashMap<>();
        Map<String, String> pinnedUrls = new LinkedHashMap<>();
        Map<String, String> pinnedRevisions = new LinkedHashMap<>();
        for (Map.Entry<String, String> asset : urls.toUrlMap().entrySet()) {
            URI uri = requirePublicComponentUri(asset.getValue());
            URI pinned = pinHuggingFaceComponentUri(uri, pinnedRevisions);
            String sanitized = sanitizedPublicUri(pinned.toString());
            pinnedUrls.put(asset.getKey(), sanitized);
            provenance.put(asset.getKey(), sanitized);
        }
        TextModelAssetUrlMap resolvedUrls = TextModelAssetUrlMap.fromUrlMap(pinnedUrls);
        URI modelUri = requirePublicComponentUri(resolvedUrls.getModel());
        if (!isGgufOrGgml(modelUri.getPath())) {
            throw new IllegalArgumentException(
                    "HTTPS component model URL must end in .gguf or .ggml");
        }

        request.setFormat(formatFor(modelUri.getPath()));
        request.setTextAssetUrls(resolvedUrls);
        request.setSourceReference(ComponentUrlDownloader.SOURCE);
        request.setSourceAssetProvenance(provenance);
    }

    private URI pinHuggingFaceComponentUri(
            URI uri,
            Map<String, String> pinnedRevisions) throws IOException {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!"huggingface.co".equals(host) && !"www.huggingface.co".equals(host)) {
            return uri;
        }

        HuggingFaceReference reference;
        try {
            reference = HuggingFaceReference.parse(uri.toString(), null);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid canonical Hugging Face component URL", invalid);
        }
        if (reference.requestedPath() == null
                || (reference.kind() != HuggingFaceReference.Kind.BLOB
                    && reference.kind() != HuggingFaceReference.Kind.RESOLVE)) {
            throw new IOException(
                    "Hugging Face component URLs must identify an exact blob/resolve file");
        }

        String revisionKey = reference.repository() + "@" + reference.requestedRevision();
        String pinnedRevision = pinnedRevisions.get(revisionKey);
        if (pinnedRevision == null) {
            if (IMMUTABLE_REVISION.matcher(reference.requestedRevision()).matches()) {
                pinnedRevision = reference.requestedRevision();
            } else {
                pinnedRevision = discover(
                        reference.canonicalReference(), null, null).getResolvedRevision();
            }
            pinnedRevisions.put(revisionKey, pinnedRevision);
        }
        return buildDownloadUri(
                reference.repository(), reference.requestedPath(), pinnedRevision);
    }

    private JsonNode fetchJson(URI uri, String authToken) throws IOException {
        try (SafeHttpTransport.Response response =
                     openFollowingRedirects(uri, authToken, "GET")) {
            int responseCode = response.statusCode();
            if (responseCode != 200) {
                throw new IOException("Hugging Face API returned HTTP " + responseCode);
            }
            long maximumBytes = limits.getConfigBytes();
            long declared = response.contentLength();
            if (declared > maximumBytes) {
                throw new IOException("Hugging Face API response exceeds the configured byte limit");
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream input = new BufferedInputStream(response.body())) {
                byte[] buffer = new byte[BUFFER_SIZE];
                long total = 0L;
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    total = Math.addExact(total, read);
                    if (total > maximumBytes) {
                        throw new IOException(
                                "Hugging Face API response exceeded the configured byte limit");
                    }
                    output.write(buffer, 0, read);
                }
            } catch (ArithmeticException overflow) {
                throw new IOException("Hugging Face API response byte count overflowed", overflow);
            }
            return OBJECT_MAPPER.readTree(output.toByteArray());
        }
    }

    private static String findUnique(List<RepositoryFile> files, String basename)
            throws IOException {
        List<String> matches = files.stream()
                .map(RepositoryFile::path)
                .filter(path -> path.equals(basename) || path.endsWith("/" + basename))
                .sorted()
                .toList();
        if (matches.contains(basename)) {
            return basename;
        }
        if (matches.size() > 1) {
            throw new IOException(
                    "Hugging Face repository contains ambiguous " + basename + " files: "
                            + String.join(", ", matches));
        }
        return matches.isEmpty() ? null : matches.get(0);
    }

    private static boolean withinScope(String path, String scope) {
        return scope == null || scope.isBlank()
                || path.equals(scope)
                || path.startsWith(scope.endsWith("/") ? scope : scope + "/");
    }

    private static boolean isGgufOrGgml(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".gguf") || lower.endsWith(".ggml");
    }

    private static String formatFor(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".gguf")) {
            return "gguf";
        }
        if (lower.endsWith(".ggml")) {
            return "ggml";
        }
        if (lower.endsWith(".sdz")) {
            return "samediff";
        }
        if (lower.endsWith(".onnx")) {
            return "onnx";
        }
        return "gguf";
    }

    private static String quantizationHint(String path) {
        Matcher matcher = QUANTIZATION_HINT.matcher(getFileName(path));
        return matcher.find() ? matcher.group(1).toUpperCase(Locale.ROOT) : null;
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private URI requirePublicComponentUri(String value) throws IOException {
        if (value == null || value.isBlank()) {
            throw new IOException("Text-model component URL is blank");
        }
        URI uri;
        try {
            uri = URI.create(value.trim());
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid text-model component URL", invalid);
        }
        if (uri.getQuery() != null) {
            throw new IOException(
                    "Text-model component URLs cannot contain query parameters; use the Hugging Face token field for authentication");
        }
        return validateRemoteUri(uri);
    }

    private static String sanitizedPublicUri(String value) {
        URI uri = URI.create(value.trim());
        try {
            return new URI(
                    uri.getScheme(),
                    null,
                    uri.getHost(),
                    uri.getPort(),
                    uri.getPath(),
                    null,
                    null).toString();
        } catch (URISyntaxException impossible) {
            throw new IllegalArgumentException("Invalid component URL", impossible);
        }
    }

    private static String canonicalAssetFileName(String key, String format, String source) {
        if (TextModelAssetMap.MODEL.equals(key)) {
            String sourceName = getFileName(source == null ? "" : source);
            String lower = sourceName.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".gguf") || lower.endsWith(".ggml")
                    || lower.endsWith(".sdz") || lower.endsWith(".onnx")) {
                return sourceName;
            }
            return "model." + ("ggml".equalsIgnoreCase(format) ? "ggml"
                    : "samediff".equalsIgnoreCase(format) ? "sdz"
                    : "onnx".equalsIgnoreCase(format) ? "onnx" : "gguf");
        }
        return switch (key) {
            case TextModelAssetMap.TOKENIZER -> TextModelAssetMap.TOKENIZER_FILE;
            case TextModelAssetMap.TOKENIZER_CONFIG -> TextModelAssetMap.TOKENIZER_CONFIG_FILE;
            case TextModelAssetMap.SPECIAL_TOKENS_MAP -> TextModelAssetMap.SPECIAL_TOKENS_MAP_FILE;
            case TextModelAssetMap.ADDED_TOKENS -> TextModelAssetMap.ADDED_TOKENS_FILE;
            case TextModelAssetMap.CHAT_TEMPLATE -> TextModelAssetMap.CHAT_TEMPLATE_FILE;
            case TextModelAssetMap.GENERATION_CONFIG -> TextModelAssetMap.GENERATION_CONFIG_FILE;
            case TextModelAssetMap.MODEL_CONFIG -> TextModelAssetMap.MODEL_CONFIG_FILE;
            case TextModelAssetMap.TEXT_GENERATION -> TextModelAssetMap.TEXT_GENERATION_FILE;
            default -> getFileName(source);
        };
    }

    private record RepositoryFile(String path, long size) {
    }

    private URI buildDownloadUri(String repo, String filePath, String revision) {
        String repositoryId = requireRepositoryId(repo);
        String safePath = requireRepositoryRelativePath(filePath, "asset path");
        String rev = revision == null || revision.isBlank() ? "main" : revision.trim();
        if (!SAFE_REVISION.matcher(rev).matches()
                || rev.contains("//")
                || hasDotSegment(rev)) {
            throw new IllegalArgumentException("Invalid Hugging Face revision");
        }
        return baseUri.resolve(repositoryId + "/resolve/" + rev + "/" + safePath);
    }

    /**
     * Resolve the complete Hugging Face file set for a request. Offline chat projects need
     * more than graph weights: the native tokenizer, its chat template, and the model metadata
     * used to derive the strict SDX text-generation graph contract all travel through staging.
     */
    static Map<String, String> filesForRequest(DownloadRequest request) {
        if (request == null) {
            return Map.of();
        }
        if (ComponentUrlDownloader.isComponentSource(request.getSource())) {
            TextModelAssetUrlMap urls = request.getTextAssetUrls();
            if (urls == null) {
                throw new IllegalArgumentException(
                        "HTTPS component staging requires textAssetUrls");
            }
            List<String> missing = urls.missingRunnableChatAssets();
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException(
                        "Runnable HTTPS component staging is missing: "
                                + String.join(", ", missing) + ".");
            }
            Map<String, String> files = new LinkedHashMap<>();
            for (Map.Entry<String, String> asset : urls.toUrlMap().entrySet()) {
                files.put(
                        asset.getKey(),
                        canonicalAssetFileName(
                                asset.getKey(), request.getFormat(), asset.getValue()));
            }
            return files;
        }
        Map<String, String> files = new LinkedHashMap<>(request.effectiveFiles());
        if (requiresCompleteTextModel(request)) {
            TextModelAssetMap assets = request.effectiveTextAssets().withHuggingFaceDefaults();
            List<String> missing = assets.missingRunnableChatAssets();
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException(
                        "Runnable Hugging Face text staging is missing: "
                                + String.join(", ", missing)
                                + ". Select the exact model file and complete tokenizer/config assets.");
            }
            files.putAll(assets.toFileMap());
            return files;
        }
        if (files.isEmpty()) {
            files.put(TextModelAssetMap.MODEL, "onnx/model.onnx");
            files.put("vocab", "vocab.txt");
            files.put(TextModelAssetMap.TOKENIZER_CONFIG, TextModelAssetMap.TOKENIZER_CONFIG_FILE);
        }
        return files;
    }

    private static boolean requiresCompleteTextModel(DownloadRequest request) {
        if (request == null) {
            return false;
        }
        boolean projectOutput = "kproject".equalsIgnoreCase(request.getOutputFormat());
        boolean llm = request.getModelType() != null && request.getModelType().isLlm();
        return projectOutput || llm;
    }

    static boolean isOptionalAuxiliaryFile(DownloadRequest request, String fileKey) {
        if (request == null || fileKey == null) {
            return false;
        }
        if (TextModelAssetMap.GENERATION_CONFIG.equals(fileKey)
                || TextModelAssetMap.SPECIAL_TOKENS_MAP.equals(fileKey)
                || TextModelAssetMap.ADDED_TOKENS.equals(fileKey)
                || TextModelAssetMap.CHAT_TEMPLATE.equals(fileKey)) {
            return true;
        }
        if (requiresCompleteTextModel(request)) {
            return TextModelAssetMap.TOKENIZER_CONFIG.equals(fileKey);
        }
        String format = request.getFormat() != null ? request.getFormat().trim().toLowerCase() : "";
        if (!"gguf".equals(format) && !"ggml".equals(format)) {
            return false;
        }
        return "vocab".equals(fileKey)
                || "tokenizer".equals(fileKey)
                || "tokenizer_config".equals(fileKey);
    }

    private static String getFileName(String path) {
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    private long downloadFile(
            URI uri,
            Path destination,
            String authToken,
            long maximumBytes,
            StagingCancellation cancellation,
            Consumer<DownloadProgress> progressCallback) throws IOException {
        cancellation.checkpoint();
        Path pending = destination.resolveSibling(
                "." + destination.getFileName() + ".part-" + UUID.randomUUID());
        try (SafeHttpTransport.Response response =
                     openFollowingRedirects(uri, authToken, "GET")) {
            int responseCode = response.statusCode();
            if (responseCode != 200) {
                throw new IOException("Hugging Face returned HTTP " + responseCode);
            }
            long contentLength = response.contentLength();
            if (contentLength > maximumBytes) {
                throw new IOException("Hugging Face asset exceeds its configured byte limit");
            }

            Files.createDirectories(destination.getParent());
            String fileName = destination.getFileName().toString();
            long bytesDownloaded = 0L;
            long lastProgressUpdate = System.currentTimeMillis();
            long bytesAtLastUpdate = 0L;
            try (InputStream input = new BufferedInputStream(response.body());
                 OutputStream output = new BufferedOutputStream(Files.newOutputStream(pending))) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                while ((bytesRead = input.read(buffer)) != -1) {
                    cancellation.checkpoint();
                    bytesDownloaded = Math.addExact(bytesDownloaded, bytesRead);
                    if (bytesDownloaded > maximumBytes) {
                        throw new IOException("Hugging Face asset exceeded its configured byte limit");
                    }
                    output.write(buffer, 0, bytesRead);

                    long now = System.currentTimeMillis();
                    if (now - lastProgressUpdate >= 500L) {
                        long elapsed = now - lastProgressUpdate;
                        long bytesDelta = bytesDownloaded - bytesAtLastUpdate;
                        long bytesPerSecond = elapsed > 0L
                                ? (bytesDelta * 1000L) / elapsed
                                : 0L;
                        progressCallback.accept(DownloadProgress.downloading(
                                fileName,
                                bytesDownloaded,
                                contentLength,
                                bytesPerSecond));
                        lastProgressUpdate = now;
                        bytesAtLastUpdate = bytesDownloaded;
                    }
                }
            }
            cancellation.checkpoint();
            try {
                Files.move(pending, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(pending, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            return bytesDownloaded;
        } catch (ArithmeticException overflow) {
            throw new IOException("Hugging Face asset byte count overflowed", overflow);
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    private SafeHttpTransport.Response openFollowingRedirects(
            URI initial,
            String authToken,
            String requestMethod) throws IOException {
        URI current = validateRemoteUri(initial);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", "Kompile-Model-Staging/1.0");
        if (authToken != null && !authToken.isBlank()
                && SafeHttpTransport.sameOrigin(baseUri, current)) {
            headers.put("Authorization", "Bearer " + authToken);
        }
        return httpTransport.execute(
                current,
                requestMethod,
                headers,
                CONNECTION_TIMEOUT,
                READ_TIMEOUT,
                limits.getMaxRedirects());
    }

    private URI validateRemoteUri(URI uri) throws IOException {
        final URI normalized;
        try {
            normalized = SafeHttpTransport.validateRemoteUri(uri);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid Hugging Face download URI", invalid);
        }
        String scheme = normalized.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme)
                && !(allowHttpLoopback
                    && "http".equals(scheme)
                    && isLoopbackHost(normalized.getHost()))) {
            throw new IOException("Hugging Face downloads require HTTPS");
        }
        return normalized;
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return "localhost".equals(normalized)
                || "127.0.0.1".equals(normalized)
                || "::1".equals(normalized)
                || "0:0:0:0:0:0:0:1".equals(normalized);
    }

    public static String requireRepositoryId(String repository) {
        if (repository == null || !REPOSITORY_ID.matcher(repository).matches()) {
            throw new IllegalArgumentException(
                    "Hugging Face repository must be an owner/repository identifier");
        }
        return repository;
    }

    private static String requireRepositoryRelativePath(String value, String label) {
        if (value == null || value.isBlank() || value.indexOf('\\') >= 0
                || value.indexOf('?') >= 0 || value.indexOf('#') >= 0
                || value.indexOf('%') >= 0) {
            throw new IllegalArgumentException("Invalid Hugging Face " + label);
        }
        Path path = Paths.get(value);
        if (path.isAbsolute() || hasDotSegment(value)) {
            throw new IllegalArgumentException(
                    "Hugging Face " + label + " must be repository-relative");
        }
        return value;
    }

    private static boolean hasDotSegment(String value) {
        for (String segment : value.split("/", -1)) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private static URI requireBaseUri(URI baseUri, boolean allowHttpLoopback) {
        URI normalized = baseUri == null ? DEFAULT_BASE_URI : baseUri.normalize();
        String scheme = normalized.getScheme();
        if (normalized.getHost() == null
                || normalized.getUserInfo() != null
                || normalized.getQuery() != null
                || normalized.getFragment() != null
                || !(("https".equalsIgnoreCase(scheme))
                    || (allowHttpLoopback && "http".equalsIgnoreCase(scheme)
                        && isLoopbackHost(normalized.getHost())))) {
            throw new IllegalArgumentException("Invalid Hugging Face base URI");
        }
        String text = normalized.toString();
        return URI.create(text.endsWith("/") ? text : text + "/");
    }

    private void cleanupDownloadedFiles(Map<String, Path> downloadedFiles) {
        for (Path path : downloadedFiles.values()) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException cleanupFailure) {
                log.warn("Could not remove partial Hugging Face asset {}", path.getFileName());
            }
        }
        downloadedFiles.clear();
    }

    private String calculateSha256(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream is = new BufferedInputStream(Files.newInputStream(file))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }
        byte[] hashBytes = digest.digest();
        StringBuilder sb = new StringBuilder("sha256:");
        for (byte b : hashBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
