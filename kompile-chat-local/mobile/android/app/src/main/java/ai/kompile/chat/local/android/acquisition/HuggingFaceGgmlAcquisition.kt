package ai.kompile.chat.local.android.acquisition

import ai.kompile.graph.reasoning.unified.MiniJson
import ai.kompile.chat.local.android.model.SdxHashing
import org.nd4j.dsp.model.HuggingFaceGgmlResolver
import org.nd4j.dsp.model.ResumableModelDownloader
import java.io.InputStreamReader
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.util.Properties
import java.util.Locale

/**
 * Direct public Hugging Face GGUF/GGML acquisition. Every reference, including an
 * exact blob/resolve URL, is resolved through the Hugging Face model API. Weight-only
 * quantized repositories resolve canonical tokenizer/configuration assets through the
 * explicit cardData.base_model chain. Every contributing repository is independently pinned.
 * Kompile staging is never consulted.
 */
object HuggingFaceGgmlAcquisition {
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_RESPONSE_CHARS = 4 * 1024 * 1024
    private const val MAX_MODEL_CANDIDATES = 256
    private const val MAX_UPSTREAM_REPOSITORIES = 32
    private const val MAX_TOKENIZER_ASSET_BYTES = 256L * 1024L * 1024L
    private val REQUIRED_HUGGINGFACE_ASSETS = listOf(
        "tokenizer.json", "tokenizer_config.json", "config.json"
    )
    private val OPTIONAL_HUGGINGFACE_ASSETS = listOf(
        "special_tokens_map.json", "added_tokens.json", "chat_template.jinja",
        "generation_config.json", "text-generation.json"
    )
    private val SUPPORTED_HUGGINGFACE_ASSETS =
        (REQUIRED_HUGGINGFACE_ASSETS + OPTIONAL_HUGGINGFACE_ASSETS).toSet()
    private const val MAX_REDIRECTS = 4
    const val DEFAULT_MAX_DOWNLOAD_BYTES = 20L * 1024L * 1024L * 1024L
    const val DEFAULT_MAX_ATTEMPTS = 4
    const val CONNECT_TIMEOUT_SECONDS = 30L
    const val READ_IDLE_TIMEOUT_MINUTES = 10L
    const val TRANSFER_POLICY_SUMMARY =
        "4 attempts · 30s connect timeout · 10m no-data timeout · automatic retry resumes validator-backed saved bytes"
    private const val DOWNLOAD_BUFFER_BYTES = 128 * 1024
    private const val PARTIAL_METADATA_VERSION = "1"
    private val redirectCodes = setOf(301, 302, 303, 307, 308)
    private val supportedHosts = setOf("huggingface.co", "www.huggingface.co")

    data class DownloadMetadata(
        val candidate: HuggingFaceGgmlResolver.Candidate,
        val safeFilename: String,
        val expectedBytes: Long?,
        val downloadedBytes: Long,
        val sha256: String,
        val finalPath: Path
    )

    enum class DownloadEvent { CONNECT, RESUME, DOWNLOAD, RETRY, VERIFY, COMPLETE }

    data class DownloadProgress(
        val candidate: HuggingFaceGgmlResolver.Candidate,
        val safeFilename: String,
        val downloadedBytes: Long,
        val expectedBytes: Long?,
        val event: DownloadEvent = DownloadEvent.DOWNLOAD,
        val attempt: Int = 1,
        val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        val resumedBytes: Long = 0L,
        val smoothedBytesPerSecond: Double? = null,
        val estimatedRemainingMillis: Long? = null,
        val retryDelayMillis: Long? = null,
        val retryWillResume: Boolean = false,
        val message: String = "Downloading model"
    )

    data class TokenizerAssetsMetadata(
        val paths: Map<String, Path>,
        val reusedCount: Int
    )

    /**
     * One structurally resolved asset map shared by every GGUF/GGML candidate. Model files may
     * have many flavors; each tokenizer/configuration companion is resolved exactly once from the
     * nearest immutable repository in the explicit Hugging Face base-model chain.
     *
     * This mirrors the staging asset-map contract, but deliberately reflects the stricter
     * requirements of the mobile GGUF AOT importer today: tokenizer.json,
     * tokenizer_config.json, and config.json must all be present. No GGUF metadata or guessed
     * tokenizer configuration is substituted for a missing canonical Hugging Face asset.
     */
    data class ResolvedRepositoryConfiguration(
        val repository: String,
        val immutableRevision: String,
        val assetSources: List<HuggingFaceGgmlResolver.AssetSource>,
        val modelCandidatePaths: List<String>,
        val requiredAssets: Map<String, HuggingFaceGgmlResolver.TokenizerAsset>,
        val optionalAssets: Map<String, HuggingFaceGgmlResolver.TokenizerAsset>
    ) {
        val assets: List<HuggingFaceGgmlResolver.TokenizerAsset> =
            (requiredAssets.values + optionalAssets.values).toList()
        val assetNames: List<String> = assets.map { it.name }
    }

    private data class ValidatedCandidateConfiguration(
        val requiredAssets: Map<String, HuggingFaceGgmlResolver.TokenizerAsset>,
        val optionalAssets: Map<String, HuggingFaceGgmlResolver.TokenizerAsset>
    ) {
        val assets: List<HuggingFaceGgmlResolver.TokenizerAsset> =
            (requiredAssets.values + optionalAssets.values).toList()
    }

    private data class RepositoryDescription(
        val revision: String,
        val files: List<HuggingFaceGgmlResolver.RepositoryFile>,
        val baseModelRepositories: List<String>
    )

    private val sharedDownloadPolicy = ResumableModelDownloader.DownloadPolicy.builder()
        .maxAttempts(DEFAULT_MAX_ATTEMPTS)
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .readIdleTimeout(Duration.ofMinutes(READ_IDLE_TIMEOUT_MINUTES))
        .attemptTimeout(Duration.ZERO)
        .initialBackoff(Duration.ofSeconds(1))
        .maxBackoff(Duration.ofSeconds(30))
        .maxRetryAfter(Duration.ofMinutes(2))
        .progressInterval(Duration.ofMillis(500))
        .maxRedirects(5)
        .uriPolicy { uri, _, _ -> requireHuggingFaceDownloadUri(uri) }
        .build()

    /**
     * A commit-pinned cache uses two deterministic slots so a replacement download never
     * destroys the bytes backing the currently active model. [obsoletePathsAfterActivation]
     * may be removed only after the selected slot has loaded, decoded, and been published.
     */
    data class PinnedCachePlan(
        val finalPath: Path,
        val reusableDownload: DownloadMetadata?,
        val obsoletePathsAfterActivation: List<Path>
    )

    fun reference(raw: String): HuggingFaceGgmlResolver.Reference =
        HuggingFaceGgmlResolver.parse(raw)

    /** Resolve and validate one canonical repository configuration before any model transfer. */
    fun resolveRepositoryConfiguration(
        discovery: HuggingFaceGgmlResolver.Discovery
    ): ResolvedRepositoryConfiguration {
        require(discovery.candidates.isNotEmpty()) {
            "Hugging Face repository resolution contains no model candidates."
        }
        val firstCandidate = discovery.candidates.first()
        val first = validateCandidateConfiguration(firstCandidate)
        val expectedAssets = first.assets.map(::assetIdentity)
        discovery.candidates.drop(1).forEach { candidate ->
            val actualAssets = validateCandidateConfiguration(candidate).assets.map(::assetIdentity)
            require(actualAssets == expectedAssets) {
                "Hugging Face repository assigned different tokenizer/configuration assets to " +
                    "${firstCandidate.path} and ${candidate.path}. Repository configuration must be shared."
            }
        }
        return ResolvedRepositoryConfiguration(
            repository = discovery.reference.repository,
            immutableRevision = discovery.resolvedRevision,
            assetSources = discovery.assetSources,
            modelCandidatePaths = discovery.candidates.map { it.path },
            requiredAssets = first.requiredAssets,
            optionalAssets = first.optionalAssets
        )
    }

    private fun validateCandidateConfiguration(
        candidate: HuggingFaceGgmlResolver.Candidate
    ): ValidatedCandidateConfiguration {
        require(candidate.isCommitPinned) {
            "Hugging Face model ${candidate.path} is not pinned to an immutable repository commit."
        }
        requireHuggingFaceDownloadUri(candidate.downloadUri)

        val duplicateNames = candidate.tokenizerAssets
            .groupingBy { it.name }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        require(duplicateNames.isEmpty()) {
            "Hugging Face model ${candidate.path} resolved duplicate configuration assets: " +
                duplicateNames.sorted().joinToString(", ")
        }

        candidate.tokenizerAssets.forEach { asset ->
            require(asset.name in SUPPORTED_HUGGINGFACE_ASSETS) {
                "Unsupported Hugging Face configuration asset: ${asset.name}"
            }
            require(asset.size < 0L || asset.size <= MAX_TOKENIZER_ASSET_BYTES) {
                "${asset.name} exceeds the ${MAX_TOKENIZER_ASSET_BYTES}-byte configuration asset limit."
            }
            requireHuggingFaceDownloadUri(asset.downloadUri)
        }

        val byName = candidate.tokenizerAssets.associateBy { it.name }
        val missingNames = REQUIRED_HUGGINGFACE_ASSETS.filterNot(byName::containsKey)
        require(missingNames.isEmpty()) {
            "Hugging Face repository is missing canonical configuration assets: " +
                missingNames.joinToString(", ") +
                ". Mobile GGUF import does not infer or reconstruct missing tokenizer/model configuration."
        }

        val required = linkedMapOf<String, HuggingFaceGgmlResolver.TokenizerAsset>()
        REQUIRED_HUGGINGFACE_ASSETS.forEach { name -> required[name] = byName.getValue(name) }
        val optional = linkedMapOf<String, HuggingFaceGgmlResolver.TokenizerAsset>()
        OPTIONAL_HUGGINGFACE_ASSETS.forEach { name ->
            byName[name]?.let { optional[name] = it }
        }
        return ValidatedCandidateConfiguration(required.toMap(), optional.toMap())
    }

    private fun assetIdentity(asset: HuggingFaceGgmlResolver.TokenizerAsset): List<Any?> = listOf(
        asset.name,
        asset.path,
        asset.size,
        asset.sha256,
        asset.downloadUri.toASCIIString()
    )

    /**
     * Resolve a repository reference to its GGUF/GGML candidates. The loader parameter
     * keeps parsing and selection deterministic in tests while production uses only the
     * public huggingface.co model API.
     */
    fun discover(
        raw: String,
        repositoryJson: (URI) -> String = ::fetchRepositoryJson
    ): HuggingFaceGgmlResolver.Discovery {
        val parsed = reference(raw)
        // Even exact blob/resolve URLs use the repository API. GGUF repositories frequently
        // contain only quantized weights, while the explicit cardData.base_model chain carries
        // their canonical tokenizer/configuration assets.
        val modelDescription = repositoryDescription(
            repositoryJson(HuggingFaceGgmlResolver.apiUri(parsed))
        )
        val snapshots = mutableListOf(
            HuggingFaceGgmlResolver.RepositorySnapshot(
                parsed.repository,
                modelDescription.revision,
                modelDescription.files
            )
        )
        val visitedRepositories = linkedSetOf(parsed.repository)
        var currentRepository = parsed.repository
        var currentDescription = modelDescription
        var discovery = HuggingFaceGgmlResolver.resolve(
            parsed,
            modelDescription.revision,
            modelDescription.files,
            snapshots
        )
        while (missingSupportedAssets(discovery.candidates.first()).isNotEmpty()) {
            val baseModels = currentDescription.baseModelRepositories
            if (baseModels.isEmpty()) {
                break
            }
            val missingRequired = missingRequiredAssets(discovery.candidates.first())
            if (baseModels.size != 1) {
                require(missingRequired.isEmpty()) {
                    "Hugging Face repository $currentRepository is missing canonical configuration " +
                        "assets (${missingRequired.joinToString(", ")}) and declares multiple " +
                        "cardData.base_model repositories: ${baseModels.joinToString(", ")}. " +
                        "An unambiguous upstream repository is required; no repository is guessed."
                }
                break
            }
            val upstreamRepository = baseModels.single()
            require(visitedRepositories.add(upstreamRepository)) {
                "Hugging Face cardData.base_model contains a repository cycle: " +
                    (visitedRepositories + upstreamRepository).joinToString(" -> ")
            }
            require(visitedRepositories.size <= MAX_UPSTREAM_REPOSITORIES) {
                "Hugging Face cardData.base_model chain exceeds $MAX_UPSTREAM_REPOSITORIES repositories."
            }
            val upstreamReference = HuggingFaceGgmlResolver.parse(upstreamRepository)
            val upstreamDescription = repositoryDescription(
                repositoryJson(HuggingFaceGgmlResolver.apiUri(upstreamReference))
            )
            snapshots += HuggingFaceGgmlResolver.RepositorySnapshot(
                upstreamRepository,
                upstreamDescription.revision,
                upstreamDescription.files
            )
            currentRepository = upstreamRepository
            currentDescription = upstreamDescription
            discovery = HuggingFaceGgmlResolver.resolve(
                parsed,
                modelDescription.revision,
                modelDescription.files,
                snapshots
            )
        }
        require(discovery.candidates.size <= MAX_MODEL_CANDIDATES) {
            "Hugging Face repository exposes too many GGUF/GGML files. " +
                "Use a tree URL to narrow discovery to one directory."
        }
        return discovery
    }

    private fun repositoryDescription(document: String): RepositoryDescription {
        val root = try {
            MiniJson.parseObject(document)
        } catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException(
                "Hugging Face returned an invalid repository description.",
                failure
            )
        }
        val revision = (root["sha"] as? String).orEmpty().trim()
        val siblings = root["siblings"] as? List<*>
            ?: throw IllegalArgumentException(
                "Hugging Face repository response omitted its file list."
            )
        val files = siblings.mapIndexed { index, sibling ->
            val entry = sibling as? Map<*, *>
                ?: malformedSibling(index, "entry is not an object")
            val path = (entry["rfilename"] as? String)
                ?.takeIf(String::isNotEmpty)
                ?: malformedSibling(index, "rfilename is missing or invalid")
            val directSize = optionalSize(entry, "size", index)
            val lfs = when (val value = entry["lfs"]) {
                null -> null
                is Map<*, *> -> value
                else -> malformedSibling(index, "lfs is not an object")
            }
            val lfsSize = lfs?.let { optionalSize(it, "size", index) }
            val lfsSha256 = lfs?.let { optionalSha256(it, index) }
            HuggingFaceGgmlResolver.RepositoryFile(
                path,
                directSize ?: lfsSize ?: -1L,
                lfsSha256
            )
        }
        val cardData = when (val value = root["cardData"]) {
            null -> null
            is Map<*, *> -> value
            else -> throw IllegalArgumentException(
                "Hugging Face repository cardData is not an object."
            )
        }
        val baseModels = when (val value = cardData?.get("base_model")) {
            null -> emptyList()
            is String -> listOf(value)
            is List<*> -> value.mapIndexed { index, item ->
                item as? String ?: throw IllegalArgumentException(
                    "Hugging Face cardData.base_model[$index] is not a repository identifier."
                )
            }
            else -> throw IllegalArgumentException(
                "Hugging Face cardData.base_model is not a repository identifier or list."
            )
        }.map { repository ->
            HuggingFaceGgmlResolver.requireRepositoryId(repository.trim())
        }.distinct()
        return RepositoryDescription(revision, files, baseModels)
    }

    private fun missingRequiredAssets(
        candidate: HuggingFaceGgmlResolver.Candidate
    ): List<String> {
        val names = candidate.tokenizerAssets.mapTo(mutableSetOf()) { it.name }
        return REQUIRED_HUGGINGFACE_ASSETS.filterNot(names::contains)
    }

    private fun missingSupportedAssets(
        candidate: HuggingFaceGgmlResolver.Candidate
    ): List<String> {
        val names = candidate.tokenizerAssets.mapTo(mutableSetOf()) { it.name }
        return SUPPORTED_HUGGINGFACE_ASSETS.filterNot(names::contains)
    }

    /** Retained exact-file helper for callers that already hold a blob/resolve URL. */
    fun downloadUri(raw: String): URI =
        HuggingFaceGgmlResolver.exact(reference(raw))
            .selectedCandidate()
            .orElseThrow()
            .downloadUri

    fun download(
        candidate: HuggingFaceGgmlResolver.Candidate,
        temporaryPath: Path,
        finalPath: Path,
        maxBytes: Long = DEFAULT_MAX_DOWNLOAD_BYTES,
        onProgress: (DownloadProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): DownloadMetadata {
        val suppliedTemporary = temporaryPath.toAbsolutePath().normalize()
        val destination = finalPath.toAbsolutePath().normalize()
        require(suppliedTemporary != destination && suppliedTemporary.parent == destination.parent) {
            "Temporary and final model paths must differ and share one app-owned directory."
        }
        val cancellation = newDownloadCancellation()
        return download(candidate, destination, maxBytes, { progress ->
            if (isCancelled()) cancellation.cancel()
            onProgress(progress)
        }, cancellation)
    }

    fun newDownloadCancellation(): ResumableModelDownloader.CancellationHandle =
        ResumableModelDownloader.CancellationHandle()

    /** Production transfer path: the Android app delegates all HTTP/retry/resume work to SDX. */
    fun download(
        candidate: HuggingFaceGgmlResolver.Candidate,
        finalPath: Path,
        maxBytes: Long = DEFAULT_MAX_DOWNLOAD_BYTES,
        onProgress: (DownloadProgress) -> Unit = {},
        cancellation: ResumableModelDownloader.CancellationHandle = newDownloadCancellation()
    ): DownloadMetadata = downloadWithSharedUtility(
        candidate, finalPath, maxBytes, onProgress, cancellation, ResumableModelDownloader()
    )

    internal fun downloadWithSharedUtility(
        candidate: HuggingFaceGgmlResolver.Candidate,
        finalPath: Path,
        maxBytes: Long,
        onProgress: (DownloadProgress) -> Unit,
        cancellation: ResumableModelDownloader.CancellationHandle,
        downloader: ResumableModelDownloader
    ): DownloadMetadata {
        require(maxBytes > 0L) { "The GGUF/GGML download size limit must be positive." }
        val destination = finalPath.toAbsolutePath().normalize()
        require(!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            "Final model file already exists: $destination"
        }
        val safeFilename = safeFilename(candidate)
        var resumedBytes = 0L
        var downloadedBytes = 0L
        var expectedBytes = candidate.size.takeIf { it >= 0L }
        var smoothedRate: Double? = null
        var rateStage: DownloadEvent? = null
        var retryWillResume = false
        val request = sharedDownloadRequest(candidate, destination, maxBytes)
        val result = downloader.download(
            request,
            sharedDownloadPolicy,
            { event ->
                val mapped = when (event.type) {
                    ResumableModelDownloader.EventType.ATTEMPT -> DownloadEvent.CONNECT
                    ResumableModelDownloader.EventType.RESUME -> DownloadEvent.RESUME
                    ResumableModelDownloader.EventType.PROGRESS -> DownloadEvent.DOWNLOAD
                    ResumableModelDownloader.EventType.RETRY -> DownloadEvent.RETRY
                    ResumableModelDownloader.EventType.VERIFY -> DownloadEvent.VERIFY
                    ResumableModelDownloader.EventType.COMPLETE -> DownloadEvent.COMPLETE
                }
                if (event.type == ResumableModelDownloader.EventType.ATTEMPT) {
                    resumedBytes = 0L
                    retryWillResume = false
                }
                downloadedBytes = event.bytesDownloaded.coerceAtLeast(0L)
                event.totalBytes.takeIf { it >= 0L }?.let { expectedBytes = it }
                if (event.type == ResumableModelDownloader.EventType.RESUME ||
                    event.type == ResumableModelDownloader.EventType.RETRY) {
                    resumedBytes = event.bytesDownloaded.coerceAtLeast(0L)
                    retryWillResume = resumedBytes > 0L
                }
                if (mapped != rateStage) {
                    smoothedRate = null
                    rateStage = mapped
                }
                if (event.bytesPerSecond > 0.0) {
                    smoothedRate = smoothedRate?.let { previous ->
                        previous * 0.75 + event.bytesPerSecond * 0.25
                    } ?: event.bytesPerSecond
                }
                onProgress(
                    DownloadProgress(
                        candidate = candidate,
                        safeFilename = safeFilename,
                        downloadedBytes = downloadedBytes,
                        expectedBytes = expectedBytes,
                        event = mapped,
                        attempt = event.attempt,
                        maxAttempts = sharedDownloadPolicy.maxAttempts,
                        resumedBytes = resumedBytes,
                        smoothedBytesPerSecond = smoothedRate,
                        estimatedRemainingMillis = event.estimatedRemainingMillis
                            .takeIf { it >= 0L },
                        retryDelayMillis = event.delayMillis.takeIf { mapped == DownloadEvent.RETRY },
                        retryWillResume = retryWillResume,
                        message = event.message
                    )
                )
            },
            cancellation
        )
        return DownloadMetadata(
            candidate = candidate,
            safeFilename = safeFilename,
            expectedBytes = expectedBytes,
            downloadedBytes = result.bytes,
            sha256 = result.sha256,
            finalPath = result.path
        )
    }

    private fun sharedDownloadRequest(
        candidate: HuggingFaceGgmlResolver.Candidate,
        destination: Path,
        maxBytes: Long
    ): ResumableModelDownloader.DownloadRequest =
        ResumableModelDownloader.DownloadRequest.builder(
            requireHuggingFaceDownloadUri(candidate.downloadUri), destination
        )
            .maxBytes(maxBytes)
            .expectedLength(candidate.size.takeIf { it >= 0L } ?: -1L)
            .expectedSha256(candidate.sha256)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "Kompile-Chat-Local/HuggingFace-GGML-Downloader")
            .build()

    /**
     * Materialize the tokenizer/config files discovered beside the selected model. Each file
     * uses the same bounded, validator-backed resumable downloader as the model itself. Files
     * are identity-prefixed so two cached GGUFs can never consume each other's chat template.
     */
    fun ensureTokenizerAssets(
        candidate: HuggingFaceGgmlResolver.Candidate,
        modelPath: Path,
        onProgress: (DownloadProgress) -> Unit = {},
        cancellation: ResumableModelDownloader.CancellationHandle = newDownloadCancellation()
    ): TokenizerAssetsMetadata = ensureTokenizerAssets(
        candidate, modelPath, onProgress, cancellation, ResumableModelDownloader()
    )

    internal fun ensureTokenizerAssets(
        candidate: HuggingFaceGgmlResolver.Candidate,
        modelPath: Path,
        onProgress: (DownloadProgress) -> Unit,
        cancellation: ResumableModelDownloader.CancellationHandle,
        downloader: ResumableModelDownloader
    ): TokenizerAssetsMetadata {
        val model = modelPath.toAbsolutePath().normalize()
        require(Files.isRegularFile(model, LinkOption.NOFOLLOW_LINKS)) {
            "Verified model is unavailable while preparing tokenizer assets: $model"
        }
        val assets = validateCandidateConfiguration(candidate).assets

        val paths = linkedMapOf<String, Path>()
        var reused = 0
        var completedAssetBytes = 0L
        val expectedAssetBytes = if (assets.all { it.size >= 0L }) {
            assets.sumOf { it.size }
        } else null
        assets.forEachIndexed { index, asset ->
            require(asset.size < 0L || asset.size <= MAX_TOKENIZER_ASSET_BYTES) {
                "${asset.name} exceeds the ${MAX_TOKENIZER_ASSET_BYTES}-byte tokenizer asset limit."
            }
            val destination = tokenizerAssetPath(model, asset.name)
            if (isReusableTokenizerAsset(asset, destination)) {
                reused++
                paths[asset.name] = destination
                val assetBytes = Files.size(destination)
                completedAssetBytes += assetBytes
                onProgress(
                    DownloadProgress(
                        candidate = candidate,
                        safeFilename = asset.name,
                        downloadedBytes = completedAssetBytes,
                        expectedBytes = expectedAssetBytes,
                        event = DownloadEvent.COMPLETE,
                        attempt = 1,
                        maxAttempts = sharedDownloadPolicy.maxAttempts,
                        resumedBytes = completedAssetBytes,
                        retryWillResume = true,
                        message = "Reused verified ${asset.name} (${index + 1}/${assets.size})"
                    )
                )
                return@forEachIndexed
            }
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                require(Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) {
                    "Tokenizer asset destination is not a regular file: $destination"
                }
                Files.delete(destination)
            }
            val request = tokenizerAssetDownloadRequest(asset, destination)
            var resumedCurrentBytes = 0L
            var retryWillResume = false
            var smoothedRate: Double? = null
            var rateStage: DownloadEvent? = null
            downloader.download(
                request,
                sharedDownloadPolicy,
                { event ->
                    val mapped = when (event.type) {
                        ResumableModelDownloader.EventType.ATTEMPT -> DownloadEvent.CONNECT
                        ResumableModelDownloader.EventType.RESUME -> DownloadEvent.RESUME
                        ResumableModelDownloader.EventType.PROGRESS -> DownloadEvent.DOWNLOAD
                        ResumableModelDownloader.EventType.RETRY -> DownloadEvent.RETRY
                        ResumableModelDownloader.EventType.VERIFY -> DownloadEvent.VERIFY
                        ResumableModelDownloader.EventType.COMPLETE -> DownloadEvent.COMPLETE
                    }
                    if (event.type == ResumableModelDownloader.EventType.ATTEMPT) {
                        resumedCurrentBytes = 0L
                        retryWillResume = false
                    } else if (event.type == ResumableModelDownloader.EventType.RESUME ||
                        event.type == ResumableModelDownloader.EventType.RETRY) {
                        resumedCurrentBytes = event.bytesDownloaded.coerceAtLeast(0L)
                        retryWillResume = resumedCurrentBytes > 0L
                    }
                    if (mapped != rateStage) {
                        smoothedRate = null
                        rateStage = mapped
                    }
                    if (event.bytesPerSecond > 0.0) {
                        smoothedRate = smoothedRate?.let { previous ->
                            previous * 0.75 + event.bytesPerSecond * 0.25
                        } ?: event.bytesPerSecond
                    }
                    onProgress(
                        DownloadProgress(
                            candidate = candidate,
                            safeFilename = asset.name,
                            downloadedBytes = completedAssetBytes +
                                event.bytesDownloaded.coerceAtLeast(0L),
                            expectedBytes = expectedAssetBytes,
                            event = mapped,
                            attempt = event.attempt,
                            maxAttempts = sharedDownloadPolicy.maxAttempts,
                            resumedBytes = completedAssetBytes + resumedCurrentBytes,
                            smoothedBytesPerSecond = smoothedRate,
                            estimatedRemainingMillis = expectedAssetBytes?.let { expected ->
                                smoothedRate?.takeIf { it > 0.0 }?.let { rate ->
                                    (((expected - completedAssetBytes -
                                        event.bytesDownloaded.coerceAtLeast(0L)).coerceAtLeast(0L) /
                                        rate) * 1000.0).toLong()
                                }
                            } ?: event.estimatedRemainingMillis.takeIf { it >= 0L },
                            retryDelayMillis = event.delayMillis.takeIf { mapped == DownloadEvent.RETRY },
                            retryWillResume = retryWillResume,
                            message = "${event.message}: ${asset.name} (${index + 1}/${assets.size})"
                        )
                    )
                },
                cancellation
            )
            paths[asset.name] = destination
            completedAssetBytes += Files.size(destination)
        }
        return TokenizerAssetsMetadata(paths, reused)
    }

    internal fun tokenizerAssetPath(modelPath: Path, assetName: String): Path {
        require(assetName in SUPPORTED_HUGGINGFACE_ASSETS) {
            "Unsupported tokenizer/configuration asset name: $assetName"
        }
        val model = modelPath.toAbsolutePath().normalize()
        return model.resolveSibling("${model.fileName}.$assetName").normalize().also { path ->
            require(path.parent == model.parent) { "Invalid tokenizer asset destination: $path" }
        }
    }

    /**
     * Recover the canonical tokenizer for a retained GGUF/GGML. Hugging Face acquisition stores
     * identity-prefixed sidecars so several model files can safely share one directory; manually
     * retained models may instead provide the conventional plain sibling name.
     */
    internal fun existingTokenizerJsonPath(modelPath: Path): Path? {
        val model = modelPath.toAbsolutePath().normalize()
        val candidates = listOf(
            tokenizerAssetPath(model, "tokenizer.json"),
            model.resolveSibling("tokenizer.json").normalize(),
        ).distinct()
        return candidates.firstOrNull { candidate ->
            candidate.parent == model.parent &&
                Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS) &&
                Files.size(candidate) in 1..MAX_TOKENIZER_ASSET_BYTES
        }
    }

    private fun isReusableTokenizerAsset(
        asset: HuggingFaceGgmlResolver.TokenizerAsset,
        destination: Path
    ): Boolean {
        if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) return false
        val size = Files.size(destination)
        if (size <= 0L || size > MAX_TOKENIZER_ASSET_BYTES) return false
        if (asset.size >= 0L && size != asset.size) return false
        return asset.sha256?.let { SdxHashing.sha256Hex(destination) == it } ?: true
    }

    fun tokenizerAssetPathsForModel(modelPath: Path): List<Path> =
        SUPPORTED_HUGGINGFACE_ASSETS.map { tokenizerAssetPath(modelPath, it) }

    /** Total bytes for the model plus every pinned tokenizer/config companion, when all are known. */
    fun expectedImportBytes(candidate: HuggingFaceGgmlResolver.Candidate): Long? {
        var total = candidate.size
        if (total < 0L) return null
        validateCandidateConfiguration(candidate).assets.forEach { asset ->
            if (asset.size < 0L) return null
            require(asset.size <= MAX_TOKENIZER_ASSET_BYTES) {
                "${asset.name} exceeds the ${MAX_TOKENIZER_ASSET_BYTES}-byte tokenizer asset limit."
            }
            total = try {
                Math.addExact(total, asset.size)
            } catch (overflow: ArithmeticException) {
                throw IllegalArgumentException("Resolved Hugging Face import size exceeds Long.MAX_VALUE.", overflow)
            }
        }
        return total
    }

    /** Count verified files and validator-backed partial sidecars already allocated in app storage. */
    fun reusableTokenizerAssetBytes(
        candidate: HuggingFaceGgmlResolver.Candidate,
        modelPath: Path
    ): Long {
        val model = modelPath.toAbsolutePath().normalize()
        val downloader = ResumableModelDownloader()
        var total = 0L
        validateCandidateConfiguration(candidate).assets.forEach { asset ->
            val destination = tokenizerAssetPath(model, asset.name)
            val reusable = if (isReusableTokenizerAsset(asset, destination)) {
                Files.size(destination)
            } else {
                downloader.resumableBytes(tokenizerAssetDownloadRequest(asset, destination))
            }.coerceAtLeast(0L)
            total = if (Long.MAX_VALUE - total < reusable) Long.MAX_VALUE else total + reusable
        }
        return total
    }

    private fun tokenizerAssetDownloadRequest(
        asset: HuggingFaceGgmlResolver.TokenizerAsset,
        destination: Path
    ): ResumableModelDownloader.DownloadRequest =
        ResumableModelDownloader.DownloadRequest.builder(
            requireHuggingFaceDownloadUri(asset.downloadUri), destination
        )
            .maxBytes(MAX_TOKENIZER_ASSET_BYTES)
            .expectedLength(asset.size.takeIf { it >= 0L } ?: -1L)
            .expectedSha256(asset.sha256)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "Kompile-Chat-Local/HuggingFace-GGML-Downloader")
            .build()

    internal fun download(
        candidate: HuggingFaceGgmlResolver.Candidate,
        temporaryPath: Path,
        finalPath: Path,
        maxBytes: Long,
        onProgress: (DownloadProgress) -> Unit,
        isCancelled: () -> Boolean,
        connectionFactory: (URI) -> HttpURLConnection
    ): DownloadMetadata {
        require(maxBytes > 0L) { "The GGUF/GGML download size limit must be positive." }
        val destination = finalPath.toAbsolutePath().normalize()
        val suppliedTemporary = temporaryPath.toAbsolutePath().normalize()
        val temporary = if (candidate.isCommitPinned) partialPath(destination) else suppliedTemporary
        require(temporary != destination) { "Temporary and final model paths must differ." }
        require(temporary.parent == destination.parent) {
            "Temporary and final model paths must share one app-owned directory."
        }
        require(!Files.exists(destination)) { "Final model file already exists: $destination" }
        val safeFilename = safeFilename(candidate)
        val candidateSize = candidate.size.takeIf { it >= 0L }
        require(candidateSize == null || candidateSize <= maxBytes) {
            "Selected Hugging Face model is $candidateSize bytes, exceeding the $maxBytes-byte limit."
        }
        val metadataPath = partialMetadataPath(temporary)
        var partial = resumablePartial(candidate, temporary, metadataPath, maxBytes, candidateSize)
        if (partial == null && Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
            discardPartial(temporary, metadataPath)
        }
        var restartedAfterInvalidRange = false
        try {
            while (true) {
                checkCancellation(isCancelled)
                val resumeOffset = partial?.let { Files.size(temporary) } ?: 0L
                var current = requireHuggingFaceDownloadUri(candidate.downloadUri)
                var restartFresh = false
                for (redirect in 0..MAX_REDIRECTS) {
                    checkCancellation(isCancelled)
                    val connection = connectionFactory(current)
                    try {
                        configureDownloadConnection(connection, resumeOffset, partial)
                        val status = connection.responseCode
                        if (status in redirectCodes) {
                            val location = connection.getHeaderField("Location")
                                ?: throw IllegalStateException(
                                    "Hugging Face model download redirected without a location."
                                )
                            current = requireHuggingFaceDownloadUri(current.resolve(location))
                            continue
                        }
                        if (status == HttpURLConnection.HTTP_PARTIAL && resumeOffset > 0L) {
                            val range = parseContentRange(connection.getHeaderField("Content-Range"))
                            val contentLength = connection.contentLengthLong.takeIf { it >= 0L }
                            if (range == null || range.start != resumeOffset ||
                                range.end < range.start || range.total <= range.end ||
                                contentLength != null && contentLength != range.end - range.start + 1L ||
                                candidateSize != null && candidateSize != range.total ||
                                range.total > maxBytes || !validatorStillMatches(partial!!, connection)) {
                                discardPartial(temporary, metadataPath)
                                partial = null
                                restartFresh = true
                                break
                            }
                            return streamAndPublish(
                                candidate, safeFilename, temporary, metadataPath, destination,
                                maxBytes, range.total, resumeOffset, append = true,
                                onProgress, isCancelled, connection
                            )
                        }
                        when (status) {
                            HttpURLConnection.HTTP_OK -> Unit
                            HttpURLConnection.HTTP_NOT_FOUND -> throw IllegalArgumentException(
                                "Hugging Face model file was not found."
                            )
                            HttpURLConnection.HTTP_UNAUTHORIZED,
                            HttpURLConnection.HTTP_FORBIDDEN -> throw IllegalArgumentException(
                                "Hugging Face model file is not public."
                            )
                            429 -> throw IllegalStateException(
                                "Hugging Face rate-limited the model download. Try again later."
                            )
                            else -> throw IllegalStateException(
                                "Hugging Face model download failed with HTTP $status."
                            )
                        }

                        // HTTP 200 to a range request means If-Range failed or Range was ignored.
                        // Its body is a complete representation, so replace rather than append.
                        if (resumeOffset > 0L) {
                            discardPartial(temporary, metadataPath)
                            partial = null
                        }
                        val contentLength = connection.contentLengthLong.takeIf { it >= 0L }
                        poisonUnless(contentLength == null || contentLength <= maxBytes) {
                            "Hugging Face model response is $contentLength bytes, exceeding the " +
                                "$maxBytes-byte limit."
                        }
                        poisonUnless(candidateSize == null || contentLength == null ||
                            candidateSize == contentLength) {
                            "Hugging Face model size changed: discovery reported $candidateSize bytes " +
                                "but the download reports $contentLength bytes."
                        }
                        val expectedBytes = contentLength ?: candidateSize
                        partial = responsePartialMetadata(candidate, connection)
                        partial?.let { writePartialMetadata(metadataPath, it) }
                            ?: Files.deleteIfExists(metadataPath)
                        return streamAndPublish(
                            candidate, safeFilename, temporary, metadataPath, destination,
                            maxBytes, expectedBytes, 0L, append = false,
                            onProgress, isCancelled, connection
                        )
                    } finally {
                        connection.disconnect()
                    }
                }
                if (restartFresh && !restartedAfterInvalidRange) {
                    restartedAfterInvalidRange = true
                    continue
                }
                if (restartFresh) {
                    throw IllegalStateException(
                        "Hugging Face returned an invalid partial response after a safe restart."
                    )
                }
                throw IllegalStateException("Hugging Face model download redirected too many times.")
            }
        } catch (poisoned: PoisonedPartialException) {
            discardPartial(temporary, metadataPath, poisoned)
            throw IllegalArgumentException(poisoned.message, poisoned)
        } catch (failure: Throwable) {
            if (partial == null) {
                discardPartial(temporary, metadataPath, failure)
            }
            throw failure
        }
    }

    internal fun partialPath(finalPath: Path): Path {
        val destination = finalPath.toAbsolutePath().normalize()
        return destination.resolveSibling("${destination.fileName}.partial")
    }

    /** Bytes that an exact, validator-backed partial can contribute to the final atomic move. */
    internal fun resumablePartialBytes(
        candidate: HuggingFaceGgmlResolver.Candidate,
        finalPath: Path,
        maxBytes: Long = DEFAULT_MAX_DOWNLOAD_BYTES
    ): Long {
        if (maxBytes <= 0L) return 0L
        return ResumableModelDownloader().resumableBytes(
            sharedDownloadRequest(candidate, finalPath.toAbsolutePath().normalize(), maxBytes)
        )
    }

    private data class PartialMetadata(
        val identity: String,
        val expectedBytes: Long?,
        val expectedSha256: String?,
        val validatorKind: String,
        val validator: String
    )

    private data class ContentRange(val start: Long, val end: Long, val total: Long)

    private class PoisonedPartialException(message: String) : IllegalStateException(message)

    private inline fun poisonUnless(condition: Boolean, message: () -> String) {
        if (!condition) throw PoisonedPartialException(message())
    }

    private fun configureDownloadConnection(
        connection: HttpURLConnection,
        resumeOffset: Long,
        partial: PartialMetadata?
    ) {
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/octet-stream")
        connection.setRequestProperty(
            "User-Agent", "Kompile-Chat-Local/HuggingFace-GGML-Downloader"
        )
        if (resumeOffset > 0L && partial != null) {
            connection.setRequestProperty("Range", "bytes=$resumeOffset-")
            connection.setRequestProperty("If-Range", partial.validator)
        }
    }

    private fun streamAndPublish(
        candidate: HuggingFaceGgmlResolver.Candidate,
        safeFilename: String,
        temporary: Path,
        metadataPath: Path,
        destination: Path,
        maxBytes: Long,
        expectedBytes: Long?,
        initialBytes: Long,
        append: Boolean,
        onProgress: (DownloadProgress) -> Unit,
        isCancelled: () -> Boolean,
        connection: HttpURLConnection
    ): DownloadMetadata {
        val digest = MessageDigest.getInstance("SHA-256")
        if (append) updateDigest(digest, temporary)
        var downloaded = initialBytes
        connection.inputStream.use { input ->
            val options = if (append) {
                arrayOf(StandardOpenOption.WRITE, StandardOpenOption.APPEND)
            } else {
                arrayOf(
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
                )
            }
            Files.newOutputStream(temporary, *options).use { output ->
                val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                while (true) {
                    checkCancellation(isCancelled)
                    val count = input.read(buffer)
                    if (count < 0) break
                    downloaded += count
                    poisonUnless(downloaded <= maxBytes) {
                        "Hugging Face model exceeded the $maxBytes-byte download limit."
                    }
                    poisonUnless(expectedBytes == null || downloaded <= expectedBytes) {
                        "Hugging Face model response exceeded its declared size of " +
                            "$expectedBytes bytes."
                    }
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    onProgress(DownloadProgress(
                        candidate, safeFilename, downloaded, expectedBytes
                    ))
                }
            }
        }
        poisonUnless(expectedBytes == null || downloaded == expectedBytes) {
            "Hugging Face model download ended at $downloaded of $expectedBytes bytes."
        }
        val downloadedSha256 = digest.digest().toHex()
        candidate.sha256?.let { expectedSha256 ->
            poisonUnless(downloadedSha256 == expectedSha256) {
                "Hugging Face model SHA-256 mismatch: expected $expectedSha256 but " +
                    "downloaded $downloadedSha256."
            }
        }
        checkCancellation(isCancelled)
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE)
        } catch (failure: AtomicMoveNotSupportedException) {
            throw IllegalStateException(
                "The app-owned model directory does not support atomic completion.", failure
            )
        }
        Files.deleteIfExists(metadataPath)
        return DownloadMetadata(
            candidate, safeFilename, expectedBytes, downloaded, downloadedSha256, destination
        )
    }

    private fun updateDigest(digest: MessageDigest, path: Path) {
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
    }

    private fun partialMetadataPath(partial: Path): Path =
        partial.resolveSibling("${partial.fileName}.metadata")

    private fun candidateIdentity(candidate: HuggingFaceGgmlResolver.Candidate): String =
        candidate.downloadUri.normalize().toASCIIString()

    private fun resumablePartial(
        candidate: HuggingFaceGgmlResolver.Candidate,
        partialPath: Path,
        metadataPath: Path,
        maxBytes: Long,
        candidateSize: Long?
    ): PartialMetadata? {
        if (!candidate.isCommitPinned ||
            !Files.exists(partialPath, LinkOption.NOFOLLOW_LINKS) ||
            !Files.exists(metadataPath, LinkOption.NOFOLLOW_LINKS)) return null
        if (!Files.isRegularFile(partialPath, LinkOption.NOFOLLOW_LINKS) ||
            !Files.isRegularFile(metadataPath, LinkOption.NOFOLLOW_LINKS)) return null
        val size = Files.size(partialPath)
        if (size <= 0L || size > maxBytes || candidateSize != null && size >= candidateSize) return null
        val metadata = readPartialMetadata(metadataPath)
        return metadata.takeIf {
            it.identity == candidateIdentity(candidate) &&
                it.expectedBytes == candidateSize &&
                it.expectedSha256 == candidate.sha256
        }
    }

    private fun responsePartialMetadata(
        candidate: HuggingFaceGgmlResolver.Candidate,
        connection: HttpURLConnection
    ): PartialMetadata? {
        if (!candidate.isCommitPinned) return null
        val etag = connection.getHeaderField("ETag")?.trim()
            ?.takeIf { it.isNotEmpty() && !it.startsWith("W/", ignoreCase = true) }
        val lastModified = connection.getHeaderField("Last-Modified")?.trim()?.takeIf(String::isNotEmpty)
        val kind = if (etag != null) "etag" else if (lastModified != null) "last-modified" else return null
        return PartialMetadata(
            candidateIdentity(candidate), candidate.size.takeIf { it >= 0L }, candidate.sha256,
            kind, etag ?: lastModified!!
        )
    }

    private fun validatorStillMatches(
        metadata: PartialMetadata,
        connection: HttpURLConnection
    ): Boolean {
        val returned = when (metadata.validatorKind) {
            "etag" -> connection.getHeaderField("ETag")
            "last-modified" -> connection.getHeaderField("Last-Modified")
            else -> return false
        }?.trim()
        return returned == null || returned == metadata.validator
    }

    private fun parseContentRange(raw: String?): ContentRange? {
        val match = Regex("^bytes ([0-9]+)-([0-9]+)/([0-9]+)$").matchEntire(raw?.trim().orEmpty())
            ?: return null
        return ContentRange(
            match.groupValues[1].toLong(),
            match.groupValues[2].toLong(),
            match.groupValues[3].toLong()
        )
    }

    private fun writePartialMetadata(path: Path, metadata: PartialMetadata) {
        val properties = Properties().apply {
            setProperty("version", PARTIAL_METADATA_VERSION)
            setProperty("identity", metadata.identity)
            setProperty("expectedBytes", metadata.expectedBytes?.toString().orEmpty())
            setProperty("expectedSha256", metadata.expectedSha256.orEmpty())
            setProperty("validatorKind", metadata.validatorKind)
            setProperty("validator", metadata.validator)
        }
        val staging = path.resolveSibling("${path.fileName}.tmp")
        Files.newOutputStream(
            staging, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        ).use { properties.store(it, null) }
        try {
            Files.move(
                staging, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(staging, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun readPartialMetadata(path: Path): PartialMetadata {
        val properties = Properties()
        Files.newInputStream(path).use(properties::load)
        require(properties.getProperty("version") == PARTIAL_METADATA_VERSION)
        val identity = properties.getProperty("identity")?.takeIf(String::isNotEmpty)
            ?: throw IllegalArgumentException("Partial identity is missing.")
        val expectedBytes = properties.getProperty("expectedBytes").orEmpty()
            .takeIf(String::isNotEmpty)?.toLong()
        val expectedSha256 = properties.getProperty("expectedSha256").orEmpty()
            .takeIf(String::isNotEmpty)
        val validatorKind = properties.getProperty("validatorKind")
        require(validatorKind == "etag" || validatorKind == "last-modified")
        val validator = properties.getProperty("validator")?.takeIf(String::isNotEmpty)
            ?: throw IllegalArgumentException("Partial validator is missing.")
        return PartialMetadata(identity, expectedBytes, expectedSha256, validatorKind, validator)
    }

    private fun discardPartial(partial: Path, metadata: Path, failure: Throwable? = null) {
        try {
            Files.deleteIfExists(partial)
            Files.deleteIfExists(metadata)
            Files.deleteIfExists(metadata.resolveSibling("${metadata.fileName}.tmp"))
        } catch (cleanupFailure: Throwable) {
            if (failure != null) failure.addSuppressed(cleanupFailure) else throw cleanupFailure
        }
    }

    fun safeFilename(candidate: HuggingFaceGgmlResolver.Candidate): String {
        val leaf = candidate.path.substringAfterLast('/').substringAfterLast('\\')
        val extension = leaf.substringAfterLast('.', "").lowercase(Locale.ROOT)
            .takeIf { it in setOf("gguf", "ggml") } ?: "gguf"
        val stem = leaf.substringBeforeLast('.', leaf).map { character ->
            if (character.isAsciiLetterOrDigit() || character in setOf('.', '-', '_')) {
                character
            } else {
                '_'
            }
        }.joinToString("").trim('.', '_').take(120).ifEmpty { "model" }
        return "$stem.$extension"
    }

    /**
     * Content-identity filename for an immutable, commit-pinned candidate. This lets a newer
     * APK retry SDX activation without downloading the same multi-gigabyte model again while
     * keeping equal leaf names from different repositories/revisions isolated.
     */
    fun pinnedStorageFilename(candidate: HuggingFaceGgmlResolver.Candidate): String {
        require(candidate.isCommitPinned) {
            "Stable model caching requires a commit-pinned Hugging Face candidate."
        }
        val displayName = safeFilename(candidate)
        val extension = displayName.substringAfterLast('.', "gguf")
        val stem = displayName.substringBeforeLast('.', displayName).take(96)
        val identity = candidate.downloadUri.normalize().toASCIIString()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(StandardCharsets.UTF_8))
            .take(12)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "$stem-$digest.$extension"
    }

    /** Primary and alternate content-identity slots for transactional cache replacement. */
    fun pinnedStorageFilenames(candidate: HuggingFaceGgmlResolver.Candidate): List<String> {
        val primary = pinnedStorageFilename(candidate)
        val extension = primary.substringAfterLast('.', "gguf")
        val stem = primary.substringBeforeLast('.', primary)
        return listOf(primary, "$stem-alternate.$extension")
    }

    /**
     * Select a reusable pinned download or an inactive destination for its replacement.
     * Existing active bytes are never deleted here. Invalid bytes in the selected inactive
     * slot may be removed before download; every other slot remains until activation succeeds.
     */
    fun planPinnedDownload(
        candidate: HuggingFaceGgmlResolver.Candidate,
        directory: Path,
        activeModelPath: Path?,
        maxBytes: Long = DEFAULT_MAX_DOWNLOAD_BYTES
    ): PinnedCachePlan {
        require(candidate.isCommitPinned) {
            "Pinned cache planning requires a commit-pinned Hugging Face candidate."
        }
        require(maxBytes > 0L) { "The GGUF/GGML cache size limit must be positive." }
        val root = directory.toAbsolutePath().normalize()
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            "Hugging Face model cache is not an app-owned directory: $root"
        }
        val slots = pinnedStorageFilenames(candidate).map { filename ->
            root.resolve(filename).normalize().also { slot ->
                require(slot.parent == root) { "Invalid Hugging Face cache slot: $slot" }
            }
        }
        slots.filter { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }.forEach { slot ->
            require(Files.isRegularFile(slot, LinkOption.NOFOLLOW_LINKS)) {
                "Cached Hugging Face model is not a regular file: $slot"
            }
        }

        var reusable: DownloadMetadata? = null
        for (slot in slots) {
            reusable = reusablePinnedDownload(candidate, slot, maxBytes)
            if (reusable != null) break
        }
        val active = activeModelPath?.toAbsolutePath()?.normalize()
        val destination = reusable?.finalPath ?: slots.firstOrNull { it != active }
            ?: throw IllegalStateException("No inactive Hugging Face cache slot is available.")

        if (reusable == null && Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            // destination cannot be the normalized active path by construction.
            Files.delete(destination)
            tokenizerAssetPathsForModel(destination).forEach(Files::deleteIfExists)
        }
        val obsolete = slots.filter { slot ->
            slot != destination && Files.exists(slot, LinkOption.NOFOLLOW_LINKS)
        }
        return PinnedCachePlan(destination, reusable, obsolete)
    }

    /**
     * Return metadata for an already-published immutable download only when its byte length and
     * repository-supplied LFS SHA-256 still match. Unpinned or unhashed candidates are never
     * reused because their bytes cannot be proven without another repository request.
     */
    fun reusablePinnedDownload(
        candidate: HuggingFaceGgmlResolver.Candidate,
        finalPath: Path,
        maxBytes: Long = DEFAULT_MAX_DOWNLOAD_BYTES
    ): DownloadMetadata? {
        if (!candidate.isCommitPinned) return null
        val expectedSha256 = candidate.sha256 ?: return null
        require(maxBytes > 0L) { "The GGUF/GGML cache size limit must be positive." }
        val published = finalPath.toAbsolutePath().normalize()
        if (!Files.exists(published, LinkOption.NOFOLLOW_LINKS)) return null
        require(Files.isRegularFile(published, LinkOption.NOFOLLOW_LINKS)) {
            "Cached Hugging Face model is not a regular file: $published"
        }
        val actualBytes = Files.size(published)
        if (actualBytes !in 1..maxBytes) return null
        val expectedBytes = candidate.size.takeIf { it >= 0L }
        if (expectedBytes != null && actualBytes != expectedBytes) return null
        val actualSha256 = SdxHashing.sha256Hex(published)
        if (actualSha256 != expectedSha256) return null
        return DownloadMetadata(
            candidate = candidate,
            safeFilename = safeFilename(candidate),
            expectedBytes = expectedBytes,
            downloadedBytes = actualBytes,
            sha256 = actualSha256,
            finalPath = published
        )
    }


    /** Lowercase hex for an already-computed digest, matching SdxHashing's output format. */
    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun Char.isAsciiLetterOrDigit(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    private fun checkCancellation(isCancelled: () -> Boolean) {
        if (isCancelled() || Thread.currentThread().isInterrupted) {
            throw InterruptedIOException("Hugging Face model download was cancelled.")
        }
    }

    private fun requireHuggingFaceDownloadUri(uri: URI): URI {
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        val trustedHost = host == "huggingface.co" ||
            host.endsWith(".huggingface.co") ||
            host == "hf.co" ||
            host.endsWith(".hf.co")
        require(uri.scheme.equals("https", ignoreCase = true) &&
            trustedHost && uri.port == -1 && uri.rawUserInfo == null &&
            uri.rawFragment == null) {
            "Model downloads and redirects are restricted to HTTPS Hugging Face hosts."
        }
        return uri
    }

    fun problem(raw: String): String? {
        if (raw.isBlank()) {
            return null
        }
        return runCatching { reference(raw) }
            .exceptionOrNull()
            ?.message
    }

    private fun fetchRepositoryJson(initialUri: URI): String {
        var current = requireHuggingFaceApiUri(initialUri)
        for (redirect in 0..MAX_REDIRECTS) {
            val connection = current.toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty(
                    "User-Agent",
                    "Kompile-Chat-Local/HuggingFace-GGML-Resolver"
                )
                val status = connection.responseCode
                if (status in redirectCodes) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IllegalStateException(
                            "Hugging Face repository discovery redirected without a location."
                        )
                    current = requireHuggingFaceApiUri(current.resolve(location))
                    continue
                }
                when (status) {
                    HttpURLConnection.HTTP_OK -> Unit
                    HttpURLConnection.HTTP_NOT_FOUND -> throw IllegalArgumentException(
                        "Hugging Face repository or revision was not found."
                    )
                    HttpURLConnection.HTTP_UNAUTHORIZED,
                    HttpURLConnection.HTTP_FORBIDDEN -> throw IllegalArgumentException(
                        "Hugging Face repository is not public. This APK does not collect access tokens."
                    )
                    429 -> throw IllegalStateException(
                        "Hugging Face rate-limited repository discovery. Try again later."
                    )
                    else -> throw IllegalStateException(
                        "Hugging Face repository discovery failed with HTTP $status."
                    )
                }
                val length = connection.contentLengthLong
                require(length < 0L || length <= MAX_RESPONSE_CHARS.toLong()) {
                    "Hugging Face repository response is unreasonably large."
                }
                return InputStreamReader(
                    connection.inputStream,
                    StandardCharsets.UTF_8
                ).use(::readBounded)
            } finally {
                connection.disconnect()
            }
        }
        throw IllegalStateException("Hugging Face repository discovery redirected too many times.")
    }

    private fun readBounded(reader: InputStreamReader): String {
        val output = StringBuilder()
        val buffer = CharArray(8192)
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) {
                return output.toString()
            }
            require(output.length + count <= MAX_RESPONSE_CHARS) {
                "Hugging Face repository response is unreasonably large."
            }
            output.append(buffer, 0, count)
        }
    }

    private fun requireHuggingFaceApiUri(uri: URI): URI {
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        require(
            uri.scheme.equals("https", ignoreCase = true) &&
                host in supportedHosts &&
                uri.port == -1 &&
                uri.rawUserInfo == null &&
                uri.rawFragment == null &&
                uri.path.startsWith("/api/models/")
        ) {
            "Repository discovery is restricted to the public huggingface.co model API."
        }
        return uri
    }

    private fun optionalSize(entry: Map<*, *>, key: String, index: Int): Long? {
        if (!entry.containsKey(key) || entry[key] == null) {
            return null
        }
        return (entry[key] as? Number)?.toLong()
            ?: malformedSibling(index, "$key is not numeric")
    }

    private fun optionalSha256(entry: Map<*, *>, index: Int): String? {
        if (!entry.containsKey("sha256") || entry["sha256"] == null) {
            return null
        }
        return (entry["sha256"] as? String)?.trim()
            ?: malformedSibling(index, "lfs.sha256 is not a string")
    }

    private fun malformedSibling(index: Int, detail: String): Nothing {
        throw IllegalArgumentException(
            "Hugging Face repository response contains malformed file entry " +
                "#${index + 1}: $detail."
        )
    }
}
