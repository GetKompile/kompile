package ai.kompile.chat.local.android.acquisition

import ai.kompile.chat.local.android.model.SdxRawGgufChatSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

class HuggingFaceGgmlAcquisitionTest {

    private val sha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

    @Test
    fun canonicalizesBlobPageWithoutRepositoryRequest() {
        val loaderCalled = AtomicBoolean(false)
        val discovery = HuggingFaceGgmlAcquisition.discover(
            " https://www.huggingface.co/acme/tiny-chat/blob/main/model-Q4_K_M.gguf "
        ) {
            loaderCalled.set(true)
            error("exact files must not query the repository API")
        }
        val uri = discovery.selectedCandidate().orElseThrow().downloadUri

        assertFalse(loaderCalled.get())
        assertEquals("https", uri.scheme)
        assertEquals("huggingface.co", uri.host)
        assertEquals("/acme/tiny-chat/resolve/main/model-Q4_K_M.gguf", uri.path)
        assertEquals("download=true", uri.query)
        assertNull(uri.fragment)
        assertFalse(discovery.selectedCandidate().orElseThrow().isCommitPinned)
        assertNull(HuggingFaceGgmlAcquisition.problem(uri.toASCIIString()))
    }

    @Test
    fun resolvesRepositoryNameToOnePinnedGguf() {
        var requestedPath: String? = null
        val discovery = HuggingFaceGgmlAcquisition.discover("acme/tiny-chat") { uri ->
            requestedPath = uri.path
            """{
              "sha":"$sha",
              "siblings":[
                {"rfilename":"config.json","size":42},
                {"rfilename":"model-Q4_K_M.gguf","lfs":{"size":1234}}
              ]
            }"""
        }

        assertEquals("/api/models/acme/tiny-chat/revision/main", requestedPath)
        assertFalse(discovery.requiresSelection())
        val candidate = discovery.selectedCandidate().orElseThrow()
        assertEquals("model-Q4_K_M.gguf", candidate.path)
        assertEquals(1234L, candidate.size)
        assertEquals("Q4_K_M", candidate.quantizationHint)
        assertEquals(
            "https://huggingface.co/acme/tiny-chat/resolve/$sha/model-Q4_K_M.gguf?download=true",
            candidate.downloadUri.toASCIIString()
        )
    }

    @Test
    fun preservesRepositoryFilenameWhenBuildingEncodedDownload() {
        val discovery = HuggingFaceGgmlAcquisition.discover("acme/tiny-chat") {
            """{
              "sha":"$sha",
              "siblings":[{"rfilename":" model Q4.gguf","size":1234}]
            }"""
        }

        val candidate = discovery.selectedCandidate().orElseThrow()
        assertEquals(" model Q4.gguf", candidate.path)
        assertEquals(
            "https://huggingface.co/acme/tiny-chat/resolve/$sha/" +
                "%20model%20Q4.gguf?download=true",
            candidate.downloadUri.toASCIIString()
        )
    }

    @Test
    fun returnsEveryQuantizationForExplicitSelection() {
        val discovery = HuggingFaceGgmlAcquisition.discover(
            "https://huggingface.co/acme/tiny-chat"
        ) {
            """{
              "sha":"$sha",
              "siblings":[
                {"rfilename":"z/model-Q8_0.gguf","size":300},
                {"rfilename":"a/model-Q4_K_M.gguf","size":200}
              ]
            }"""
        }

        assertTrue(discovery.requiresSelection())
        assertTrue(discovery.selectedCandidate().isEmpty)
        assertEquals(
            listOf("a/model-Q4_K_M.gguf", "z/model-Q8_0.gguf"),
            discovery.candidates.map { it.path }
        )
    }

    @Test
    fun treeUrlScopesRepositoryCandidates() {
        val discovery = HuggingFaceGgmlAcquisition.discover(
            "https://huggingface.co/acme/tiny-chat/tree/main/mobile"
        ) {
            """{
              "sha":"$sha",
              "siblings":[
                {"rfilename":"desktop/model.gguf","size":300},
                {"rfilename":"mobile/model.ggml","size":200}
              ]
            }"""
        }

        assertEquals(listOf("mobile/model.ggml"), discovery.candidates.map { it.path })
    }

    @Test
    fun blankInputHasNoInlineProblemButCannotResolve() {
        assertNull(HuggingFaceGgmlAcquisition.problem("   "))
        val failure = runCatching { HuggingFaceGgmlAcquisition.reference(" ") }
            .exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("repository or URL"))
    }

    @Test
    fun acceptsRepositoryAndTreeReferencesButRejectsNonGgmlBlob() {
        listOf(
            "acme/tiny-chat",
            "https://huggingface.co/acme/tiny-chat",
            "https://huggingface.co/acme/tiny-chat/tree/main"
        ).forEach { value ->
            assertNull(HuggingFaceGgmlAcquisition.problem(value))
        }
        assertTrue(
            HuggingFaceGgmlAcquisition.problem(
                "https://huggingface.co/acme/tiny-chat/blob/main/config.json"
            ) != null
        )
    }

    @Test
    fun rejectsUnsafeOrNonHuggingFaceReferences() {
        listOf(
            "acme/tiny-chat/extra",
            "http://huggingface.co/acme/tiny-chat",
            "https://token@huggingface.co/acme/tiny-chat",
            "https://huggingface.co:443/acme/tiny-chat",
            "https://huggingface.co/acme/tiny-chat?token=secret",
            "https://huggingface.co/acme/tiny-chat#files",
            "https://huggingface.co/acme/tiny-chat/blob/main%2Fmodel.gguf",
            "https://huggingface.co/acme/tiny-chat/blob/main/../model.gguf",
            "https://example.com/acme/tiny-chat"
        ).forEach { value ->
            assertTrue(HuggingFaceGgmlAcquisition.problem(value) != null)
        }
    }

    @Test
    fun rejectsMalformedOrEmptyRepositoryDescriptions() {
        listOf(
            "{}",
            """{"sha":"main","siblings":[{"rfilename":"model.gguf"}]}""",
            """{"sha":"$sha","siblings":[{"rfilename":"config.json"}]}""",
            """{"sha":"$sha","siblings":[null,{"rfilename":"model.gguf"}]}""",
            """{"sha":"$sha","siblings":[{}, {"rfilename":"model.gguf"}]}""",
            """{"sha":"$sha","siblings":[{"rfilename":42}]}""",
            """{"sha":"$sha","siblings":[{"rfilename":"model.gguf","size":"large"}]}""",
            """{"sha":"$sha","siblings":[{"rfilename":"model.gguf","lfs":"invalid"}]}"""
        ).forEach { response ->
            val failure = runCatching {
                HuggingFaceGgmlAcquisition.discover("acme/tiny-chat") { response }
            }.exceptionOrNull()
            assertTrue(failure != null)
        }
    }

    @Test
    fun rejectsStandaloneSplitGgufShards() {
        val failure = runCatching {
            HuggingFaceGgmlAcquisition.discover("acme/tiny-chat") {
                """{
                  "sha":"$sha",
                  "siblings":[
                    {"rfilename":"model-00001-of-00002.gguf","size":100},
                    {"rfilename":"model-00002-of-00002.gguf","size":100}
                  ]
                }"""
            }
        }.exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("Split GGUF shard"))
    }

    @Test
    fun preservesActionableRepositoryLoaderFailures() {
        val failure = runCatching {
            HuggingFaceGgmlAcquisition.discover("acme/tiny-chat") {
                throw IllegalArgumentException("repository was not found")
            }
        }.exceptionOrNull()

        assertEquals("repository was not found", failure?.message)
    }

    @Test
    fun streamsToTemporaryPathThenAtomicallyPublishesWithProgress() {
        val bytes = "gguf-test-payload".toByteArray()
        val candidate = candidate("models/tiny model.gguf", bytes.size.toLong())
        val directory = Files.createTempDirectory("hf-download-success")
        val temporary = directory.resolve("model.part")
        val destination = directory.resolve("tiny_model.gguf")
        val progress = mutableListOf<HuggingFaceGgmlAcquisition.DownloadProgress>()
        try {
            val result = HuggingFaceGgmlAcquisition.download(
                candidate,
                temporary,
                destination,
                1024,
                progress::add,
                { false }
            ) { FakeConnection(it, 200, bytes, bytes.size.toLong()) }

            assertFalse(Files.exists(temporary))
            assertTrue(Files.exists(destination))
            assertEquals(bytes.toList(), Files.readAllBytes(destination).toList())
            assertEquals(bytes.size.toLong(), result.downloadedBytes)
            assertEquals(bytes.size.toLong(), result.expectedBytes)
            assertEquals("tiny_model.gguf", result.safeFilename)
            assertEquals(bytes.size.toLong(), progress.last().downloadedBytes)
            assertEquals(candidate, progress.last().candidate)
        } finally {
            Files.deleteIfExists(temporary)
            Files.deleteIfExists(destination)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun rejectsRedirectOutsideStrictHttpsHuggingFaceHostsBeforeConnecting() {
        val candidate = candidate("model.gguf", 4)
        val directory = Files.createTempDirectory("hf-download-redirect")
        val temporary = directory.resolve("model.part")
        val destination = directory.resolve("model.gguf")
        var calls = 0
        try {
            val failure = runCatching {
                HuggingFaceGgmlAcquisition.download(
                    candidate, temporary, destination, 100, {}, { false }
                ) { uri ->
                    calls++
                    FakeConnection(
                        uri,
                        302,
                        ByteArray(0),
                        0,
                        mapOf("Location" to "https://example.com/stolen.gguf")
                    )
                }
            }.exceptionOrNull()

            assertTrue(failure?.message.orEmpty().contains("HTTPS Hugging Face hosts"))
            assertEquals(1, calls)
            assertFalse(Files.exists(temporary))
            assertFalse(Files.exists(destination))
        } finally {
            Files.deleteIfExists(temporary)
            Files.deleteIfExists(destination)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun rejectsDeclaredOrStreamingOversizeAndCleansTemporaryFile() {
        val candidate = candidate("model.gguf", -1)
        val directory = Files.createTempDirectory("hf-download-limit")
        val temporary = directory.resolve("model.part")
        val destination = directory.resolve("model.gguf")
        try {
            val failure = runCatching {
                HuggingFaceGgmlAcquisition.download(
                    candidate, temporary, destination, 3, {}, { false }
                ) { FakeConnection(it, 200, byteArrayOf(1, 2, 3, 4), -1) }
            }.exceptionOrNull()

            assertTrue(failure?.message.orEmpty().contains("download limit"))
            assertFalse(Files.exists(temporary))
            assertFalse(Files.exists(destination))
        } finally {
            Files.deleteIfExists(temporary)
            Files.deleteIfExists(destination)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun cancellationIsExplicitAndCleansTemporaryFile() {
        val candidate = candidate("folder/unsafe name.gguf", -1)
        val directory = Files.createTempDirectory("hf-download-cancel")
        val temporary = directory.resolve("model.part")
        val destination = directory.resolve("model.gguf")
        var checks = 0
        try {
            val failure = runCatching {
                HuggingFaceGgmlAcquisition.download(
                    candidate, temporary, destination, 100, {}, { ++checks > 2 }
                ) { FakeConnection(it, 200, byteArrayOf(1, 2, 3), 3) }
            }.exceptionOrNull()

            assertTrue(failure is java.io.InterruptedIOException)
            assertTrue(failure?.message.orEmpty().contains("cancelled"))
            assertEquals("unsafe_name.gguf", HuggingFaceGgmlAcquisition.safeFilename(candidate))
            assertFalse(Files.exists(temporary))
            assertFalse(Files.exists(destination))
        } finally {
            Files.deleteIfExists(temporary)
            Files.deleteIfExists(destination)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun rawSdxValidationAcceptsEveryDl4jGgufAndLegacyGgmlMagic() {
        listOf("GGUF", "ggml", "ggmf", "ggjt", "lmgg", "fmgg", "tjgg").forEach { magic ->
            assertTrue(
                "Expected DL4J-compatible magic $magic",
                SdxRawGgufChatSession.hasSupportedMagic(magic.toByteArray(Charsets.US_ASCII))
            )
        }
        assertFalse(
            SdxRawGgufChatSession.hasSupportedMagic("<htm".toByteArray(Charsets.US_ASCII))
        )
    }

    private fun candidate(path: String, size: Long) =
        HuggingFaceGgmlAcquisition.discover("acme/tiny-chat") {
            """{"sha":"$sha","siblings":[{"rfilename":"$path","size":$size}]}"""
        }.selectedCandidate().orElseThrow()

    private class FakeConnection(
        uri: URI,
        private val status: Int,
        private val body: ByteArray,
        private val declaredLength: Long,
        private val headers: Map<String, String> = emptyMap()
    ) : HttpURLConnection(URL(uri.toASCIIString())) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = status
        override fun getInputStream(): InputStream = ByteArrayInputStream(body)
        override fun getContentLengthLong(): Long = declaredLength
        override fun getHeaderField(name: String?): String? = headers[name]
    }
}
