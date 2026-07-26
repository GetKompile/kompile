package ai.kompile.chat.local.android.acquisition

import ai.kompile.graph.reasoning.unified.MiniJson
import org.nd4j.dsp.model.HuggingFaceGgmlResolver
import java.io.InputStreamReader
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Locale

/**
 * Direct public Hugging Face GGUF/GGML acquisition. Repository IDs and canonical
 * repository/tree URLs are resolved through the Hugging Face model API; exact blob or
 * resolve URLs remain a zero-discovery fast path. Kompile staging is never consulted.
 */
object HuggingFaceGgmlAcquisition {
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_RESPONSE_CHARS = 4 * 1024 * 1024
    private const val MAX_MODEL_CANDIDATES = 256
    private const val MAX_REDIRECTS = 4
    const val DEFAULT_MAX_DOWNLOAD_BYTES = 20L * 1024L * 1024L * 1024L
    private const val DOWNLOAD_BUFFER_BYTES = 128 * 1024
    private val redirectCodes = setOf(301, 302, 303, 307, 308)
    private val supportedHosts = setOf("huggingface.co", "www.huggingface.co")

    data class DownloadMetadata(
        val candidate: HuggingFaceGgmlResolver.Candidate,
        val safeFilename: String,
        val expectedBytes: Long?,
        val downloadedBytes: Long,
        val finalPath: Path
    )

    data class DownloadProgress(
        val candidate: HuggingFaceGgmlResolver.Candidate,
        val safeFilename: String,
        val downloadedBytes: Long,
        val expectedBytes: Long?
    )

    fun reference(raw: String): HuggingFaceGgmlResolver.Reference =
        HuggingFaceGgmlResolver.parse(raw)

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
        if (parsed.isExactModel) {
            return HuggingFaceGgmlResolver.exact(parsed)
        }

        val document = repositoryJson(HuggingFaceGgmlResolver.apiUri(parsed))
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
            HuggingFaceGgmlResolver.RepositoryFile(path, directSize ?: lfsSize ?: -1L)
        }
        val discovery = HuggingFaceGgmlResolver.resolve(parsed, revision, files)
        require(discovery.candidates.size <= MAX_MODEL_CANDIDATES) {
            "Hugging Face repository exposes too many GGUF/GGML files. " +
                "Use a tree URL to narrow discovery to one directory."
        }
        return discovery
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
    ): DownloadMetadata = download(
        candidate, temporaryPath, finalPath, maxBytes, onProgress, isCancelled
    ) { uri -> uri.toURL().openConnection() as HttpURLConnection }

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
        val temporary = temporaryPath.toAbsolutePath().normalize()
        val destination = finalPath.toAbsolutePath().normalize()
        require(temporary != destination) { "Temporary and final model paths must differ." }
        require(temporary.parent == destination.parent) {
            "Temporary and final model paths must share one app-owned directory."
        }
        require(!Files.exists(temporary)) { "Temporary model file already exists: $temporary" }
        require(!Files.exists(destination)) { "Final model file already exists: $destination" }
        val safeFilename = safeFilename(candidate)
        val candidateSize = candidate.size.takeIf { it >= 0L }
        require(candidateSize == null || candidateSize <= maxBytes) {
            "Selected Hugging Face model is $candidateSize bytes, exceeding the $maxBytes-byte limit."
        }
        try {
            var current = requireHuggingFaceDownloadUri(candidate.downloadUri)
            for (redirect in 0..MAX_REDIRECTS) {
                checkCancellation(isCancelled)
                val connection = connectionFactory(current)
                try {
                    connection.requestMethod = "GET"
                    connection.connectTimeout = CONNECT_TIMEOUT_MS
                    connection.readTimeout = READ_TIMEOUT_MS
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("Accept", "application/octet-stream")
                    connection.setRequestProperty(
                        "User-Agent", "Kompile-Chat-Local/HuggingFace-GGML-Downloader"
                    )
                    val status = connection.responseCode
                    if (status in redirectCodes) {
                        val location = connection.getHeaderField("Location")
                            ?: throw IllegalStateException(
                                "Hugging Face model download redirected without a location."
                            )
                        current = requireHuggingFaceDownloadUri(current.resolve(location))
                        continue
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
                    val contentLength = connection.contentLengthLong.takeIf { it >= 0L }
                    require(contentLength == null || contentLength <= maxBytes) {
                        "Hugging Face model response is $contentLength bytes, exceeding the " +
                            "$maxBytes-byte limit."
                    }
                    require(candidateSize == null || contentLength == null ||
                        candidateSize == contentLength) {
                        "Hugging Face model size changed: discovery reported $candidateSize bytes " +
                            "but the download reports $contentLength bytes."
                    }
                    val expectedBytes = contentLength ?: candidateSize
                    var downloaded = 0L
                    connection.inputStream.use { input ->
                        Files.newOutputStream(
                            temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE
                        ).use { output ->
                            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                            while (true) {
                                checkCancellation(isCancelled)
                                val count = input.read(buffer)
                                if (count < 0) break
                                downloaded += count
                                require(downloaded <= maxBytes) {
                                    "Hugging Face model exceeded the $maxBytes-byte download limit."
                                }
                                require(expectedBytes == null || downloaded <= expectedBytes) {
                                    "Hugging Face model response exceeded its declared size of " +
                                        "$expectedBytes bytes."
                                }
                                output.write(buffer, 0, count)
                                onProgress(DownloadProgress(
                                    candidate, safeFilename, downloaded, expectedBytes
                                ))
                            }
                        }
                    }
                    require(expectedBytes == null || downloaded == expectedBytes) {
                        "Hugging Face model download ended at $downloaded of $expectedBytes bytes."
                    }
                    checkCancellation(isCancelled)
                    try {
                        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE)
                    } catch (failure: AtomicMoveNotSupportedException) {
                        throw IllegalStateException(
                            "The app-owned model directory does not support atomic completion.",
                            failure
                        )
                    }
                    return DownloadMetadata(
                        candidate, safeFilename, expectedBytes, downloaded, destination
                    )
                } finally {
                    connection.disconnect()
                }
            }
            throw IllegalStateException("Hugging Face model download redirected too many times.")
        } catch (failure: Throwable) {
            try {
                Files.deleteIfExists(temporary)
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
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

    private fun malformedSibling(index: Int, detail: String): Nothing {
        throw IllegalArgumentException(
            "Hugging Face repository response contains malformed file entry " +
                "#${index + 1}: $detail."
        )
    }
}
