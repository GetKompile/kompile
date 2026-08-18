package ai.kompile.chat.local.android.acquisition

import ai.kompile.chat.local.android.model.SdxGgufModelImporter
import org.nd4j.dsp.model.ResumableModelDownloader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

class HuggingFaceGgmlAcquisitionTest {

    private val sha = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val contentSha =
        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

    @Test
    fun exactBlobPageUsesRepositoryMetadataAndCanonicalAssets() {
        val loaderCalled = AtomicBoolean(false)
        val discovery = HuggingFaceGgmlAcquisition.discover(
            " https://www.huggingface.co/acme/tiny-chat/blob/main/model-Q4_K_M.gguf "
        ) { uri ->
            loaderCalled.set(true)
            assertEquals("/api/models/acme/tiny-chat/revision/main", uri.path)
            assertEquals("blobs=true", uri.rawQuery)
            """{
              "sha":"$sha",
              "siblings":[
                {"rfilename":"config.json","size":42},
                {"rfilename":"tokenizer.json","size":100},
                {"rfilename":"tokenizer_config.json","size":20},
                {"rfilename":"model-Q4_K_M.gguf","lfs":{"size":1234,"sha256":"$contentSha"}}
              ]
            }"""
        }
        val candidate = discovery.selectedCandidate().orElseThrow()
        val uri = candidate.downloadUri

        assertTrue(loaderCalled.get())
        assertEquals("https", uri.scheme)
        assertEquals("huggingface.co", uri.host)
        assertEquals("/acme/tiny-chat/resolve/$sha/model-Q4_K_M.gguf", uri.path)
        assertEquals("download=true", uri.query)
        assertNull(uri.fragment)
        assertTrue(candidate.isCommitPinned)
        assertEquals(
            listOf("tokenizer.json", "tokenizer_config.json", "config.json"),
            candidate.tokenizerAssets.map { it.name }
        )
        assertNull(HuggingFaceGgmlAcquisition.problem(uri.toASCIIString()))
    }

    @Test
    fun resolvesRepositoryNameToOnePinnedGguf() {
        var requestedPath: String? = null
        var requestedQuery: String? = null
        val discovery = HuggingFaceGgmlAcquisition.discover("acme/tiny-chat") { uri ->
            requestedPath = uri.path
            requestedQuery = uri.rawQuery
            """{
              "sha":"$sha",
              "siblings":[
                {"rfilename":"config.json","size":42},
                {"rfilename":"tokenizer.json","size":100},
                {"rfilename":"tokenizer_config.json","size":20},
                {"rfilename":"model-Q4_K_M.gguf","lfs":{"size":1234,"sha256":"$contentSha"}}
              ]
            }"""
        }

        assertEquals("/api/models/acme/tiny-chat/revision/main", requestedPath)
        assertEquals("blobs=true", requestedQuery)
        assertFalse(discovery.requiresSelection())
        val candidate = discovery.selectedCandidate().orElseThrow()
        assertEquals("model-Q4_K_M.gguf", candidate.path)
        assertEquals(1234L, candidate.size)
        assertEquals(contentSha, candidate.sha256)
        assertEquals("Q4_K_M", candidate.quantizationHint)
        assertEquals(
            listOf("tokenizer.json", "tokenizer_config.json", "config.json"),
            candidate.tokenizerAssets.map { it.name }
        )
        assertEquals(1396L, HuggingFaceGgmlAcquisition.expectedImportBytes(candidate))
        assertEquals(
            "https://huggingface.co/acme/tiny-chat/resolve/$sha/model-Q4_K_M.gguf?download=true",
            candidate.downloadUri.toASCIIString()
        )
    }

    @Test
    fun repositoryConfigurationIsResolvedOnceAndSharedAcrossModelChoices() {
        val discovery = HuggingFaceGgmlAcquisition.discover("acme/tiny-chat") {
            """{
              "sha":"$sha",
              "siblings":[
                {"rfilename":"a/model-Q4_K_M.gguf","size":200},
                {"rfilename":"z/model-Q8_0.gguf","size":300},
                {"rfilename":"tokenizer.json","size":100},
                {"rfilename":"tokenizer_config.json","size":20},
                {"rfilename":"config.json","size":42},
                {"rfilename":"added_tokens.json","size":11},
                {"rfilename":"generation_config.json","size":12},
                {"rfilename":"text-generation.json","size":13}
              ]
            }"""
        }

        val configuration =
            HuggingFaceGgmlAcquisition.resolveRepositoryConfiguration(discovery)
        val expectedAssets = listOf(
            "tokenizer.json", "tokenizer_config.json", "config.json",
            "added_tokens.json", "generation_config.json", "text-generation.json"
        )
        assertEquals("acme/tiny-chat", configuration.repository)
        assertEquals(sha, configuration.immutableRevision)
        assertEquals("acme/tiny-chat", configuration.assetSources.single().repository)
        assertEquals(sha, configuration.assetSources.single().resolvedRevision)
        assertEquals(
            listOf("a/model-Q4_K_M.gguf", "z/model-Q8_0.gguf"),
            configuration.modelCandidatePaths
        )
        assertEquals(expectedAssets, configuration.assetNames)
    }

    @Test
    fun qwenWeightRepositoryResolvesCanonicalAssetsFromPinnedBaseModel() {
        val configurationSha = "cccccccccccccccccccccccccccccccccccccccc"
        val requestedPaths = mutableListOf<String>()
        val discovery = HuggingFaceGgmlAcquisition.discover(
            "unsloth/Qwen3.5-0.8B-GGUF"
        ) { uri ->
            requestedPaths += uri.path
            when (uri.path) {
                "/api/models/unsloth/Qwen3.5-0.8B-GGUF/revision/main" -> """{
                  "sha":"$sha",
                  "cardData":{"base_model":["Qwen/Qwen3.5-0.8B"]},
                  "siblings":[
                    {"rfilename":"README.md","size":10},
                    {"rfilename":"Qwen3.5-0.8B-Q4_K_M.gguf",
                     "lfs":{"size":1234,"sha256":"$contentSha"}}
                  ]
                }"""
                "/api/models/Qwen/Qwen3.5-0.8B/revision/main" -> """{
                  "sha":"$configurationSha",
                  "siblings":[
                    {"rfilename":"tokenizer.json","size":100},
                    {"rfilename":"tokenizer_config.json","size":20},
                    {"rfilename":"config.json","size":42},
                    {"rfilename":"chat_template.jinja","size":33}
                  ]
                }"""
                else -> error("Unexpected Hugging Face API request: $uri")
            }
        }

        val configuration =
            HuggingFaceGgmlAcquisition.resolveRepositoryConfiguration(discovery)
        val candidate = discovery.selectedCandidate().orElseThrow()
        assertEquals(
            listOf(
                "/api/models/unsloth/Qwen3.5-0.8B-GGUF/revision/main",
                "/api/models/Qwen/Qwen3.5-0.8B/revision/main"
            ),
            requestedPaths
        )
        assertEquals("unsloth/Qwen3.5-0.8B-GGUF", configuration.repository)
        assertEquals(sha, configuration.immutableRevision)
        assertEquals("Qwen/Qwen3.5-0.8B", configuration.assetSources.single().repository)
        assertEquals(configurationSha, configuration.assetSources.single().resolvedRevision)
        assertEquals(
            "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/$sha/" +
                "Qwen3.5-0.8B-Q4_K_M.gguf?download=true",
            candidate.downloadUri.toASCIIString()
        )
        assertEquals(
            "https://huggingface.co/Qwen/Qwen3.5-0.8B/resolve/$configurationSha/" +
                "tokenizer.json?download=true",
            candidate.tokenizerAssets.first().downloadUri.toASCIIString()
        )
        assertEquals(
            listOf("tokenizer.json", "tokenizer_config.json", "config.json", "chat_template.jinja"),
            configuration.assetNames
        )
    }

    @Test
    fun resolvesTokenizerAndCompanionAssetsAcrossThePinnedUpstreamChain() {
        val configurationSha = "cccccccccccccccccccccccccccccccccccccccc"
        val tokenizerSha = "dddddddddddddddddddddddddddddddddddddddd"
        val requestedPaths = mutableListOf<String>()
        val discovery = HuggingFaceGgmlAcquisition.discover("vendor/chat-GGUF") { uri ->
            requestedPaths += uri.path
            when (uri.path) {
                "/api/models/vendor/chat-GGUF/revision/main" -> """{
                  "sha":"$sha",
                  "cardData":{"base_model":"vendor/chat-config"},
                  "siblings":[
                    {"rfilename":"chat-Q4.gguf","size":1234},
                    {"rfilename":"generation_config.json","size":12}
                  ]
                }"""
                "/api/models/vendor/chat-config/revision/main" -> """{
                  "sha":"$configurationSha",
                  "cardData":{"base_model":["vendor/chat-tokenizer"]},
                  "siblings":[
                    {"rfilename":"config.json","size":42},
                    {"rfilename":"chat_template.jinja","size":20}
                  ]
                }"""
                "/api/models/vendor/chat-tokenizer/revision/main" -> """{
                  "sha":"$tokenizerSha",
                  "siblings":[
                    {"rfilename":"tokenizer.json","size":100},
                    {"rfilename":"tokenizer_config.json","size":30},
                    {"rfilename":"special_tokens_map.json","size":10},
                    {"rfilename":"added_tokens.json","size":11}
                  ]
                }"""
                else -> error("Unexpected Hugging Face API request: $uri")
            }
        }

        val configuration =
            HuggingFaceGgmlAcquisition.resolveRepositoryConfiguration(discovery)
        val assets = configuration.assets.associateBy { it.name }
        assertEquals(
            listOf(
                "/api/models/vendor/chat-GGUF/revision/main",
                "/api/models/vendor/chat-config/revision/main",
                "/api/models/vendor/chat-tokenizer/revision/main"
            ),
            requestedPaths
        )
        assertEquals(
            listOf("vendor/chat-GGUF", "vendor/chat-config", "vendor/chat-tokenizer"),
            configuration.assetSources.map { it.repository }
        )
        assertEquals("vendor/chat-tokenizer", assets.getValue("tokenizer.json").sourceRepository)
        assertEquals(tokenizerSha, assets.getValue("tokenizer.json").sourceRevision)
        assertEquals("vendor/chat-config", assets.getValue("config.json").sourceRepository)
        assertEquals(configurationSha, assets.getValue("chat_template.jinja").sourceRevision)
        assertEquals("vendor/chat-GGUF", assets.getValue("generation_config.json").sourceRepository)
        assertEquals(sha, assets.getValue("generation_config.json").sourceRevision)
    }

    @Test
    fun missingCanonicalConfigurationFailsBeforeModelTransferPlanning() {
        val discovery = HuggingFaceGgmlAcquisition.discover("acme/tiny-chat") {
            """{
              "sha":"$sha",
              "siblings":[
                {"rfilename":"model.gguf","size":1234},
                {"rfilename":"tokenizer.json","size":100}
              ]
            }"""
        }

        val failure = runCatching {
            HuggingFaceGgmlAcquisition.resolveRepositoryConfiguration(discovery)
        }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("tokenizer_config.json"))
        assertTrue(failure?.message.orEmpty().contains("config.json"))
    }

    @Test
    fun ambiguousNestedTokenizerConfigurationFailsDuringRepositoryResolution() {
        val failure = runCatching {
            HuggingFaceGgmlAcquisition.discover("acme/tiny-chat") {
                """{
                  "sha":"$sha",
                  "siblings":[
                    {"rfilename":"model.gguf","size":1234},
                    {"rfilename":"mobile/tokenizer.json","size":100},
                    {"rfilename":"desktop/tokenizer.json","size":101}
                  ]
                }"""
            }
        }.exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("ambiguous tokenizer.json"))
    }

    @Test
    fun tokenizerAssetsUseTheModelCacheIdentityPrefix() {
        val directory = Files.createTempDirectory("hf-tokenizer-path")
        try {
            val model = directory.resolve("model-slot.gguf")
            assertEquals(
                "model-slot.gguf.tokenizer.json",
                HuggingFaceGgmlAcquisition.tokenizerAssetPath(model, "tokenizer.json").fileName.toString()
            )
            assertEquals(
                "model-slot.gguf.tokenizer_config.json",
                HuggingFaceGgmlAcquisition.tokenizerAssetPath(model, "tokenizer_config.json").fileName.toString()
            )
            assertEquals(
                "model-slot.gguf.chat_template.jinja",
                HuggingFaceGgmlAcquisition.tokenizerAssetPath(model, "chat_template.jinja").fileName.toString()
            )
        } finally {
            Files.deleteIfExists(directory)
        }
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
    fun pinnedStorageNamesAreStableAndSeparateEqualLeavesAcrossRepositories() {
        val first = candidate("models/model-Q4_K_M.gguf", 4)
        val same = candidate("models/model-Q4_K_M.gguf", 4)
        val otherRepository = HuggingFaceGgmlAcquisition.discover("other/tiny-chat") {
            """{"sha":"$sha","siblings":[{"rfilename":"models/model-Q4_K_M.gguf","size":4}]}"""
        }.selectedCandidate().orElseThrow()

        val firstName = HuggingFaceGgmlAcquisition.pinnedStorageFilename(first)
        assertEquals(firstName, HuggingFaceGgmlAcquisition.pinnedStorageFilename(same))
        assertTrue(firstName.startsWith("model-Q4_K_M-"))
        assertTrue(firstName.endsWith(".gguf"))
        val slots = HuggingFaceGgmlAcquisition.pinnedStorageFilenames(first)
        assertEquals(2, slots.distinct().size)
        assertEquals(firstName, slots.first())
        assertTrue(slots.last().endsWith("-alternate.gguf"))
        assertFalse(
            firstName == HuggingFaceGgmlAcquisition.pinnedStorageFilename(otherRepository)
        )
    }

    @Test
    fun reusesOnlyMatchingCommitPinnedPublishedDownloads() {
        val bytes = "GGUF".toByteArray()
        val pinned = candidate("models/model.gguf", bytes.size.toLong(), sha256(bytes))
        val unpinned = org.nd4j.dsp.model.HuggingFaceGgmlResolver.exact(
            org.nd4j.dsp.model.HuggingFaceGgmlResolver.parse(
                "https://huggingface.co/acme/tiny-chat/resolve/main/model.gguf"
            )
        ).selectedCandidate().orElseThrow()
        val directory = Files.createTempDirectory("hf-pinned-reuse")
        val published = directory.resolve(
            HuggingFaceGgmlAcquisition.pinnedStorageFilename(pinned)
        )
        try {
            Files.write(published, bytes)

            val reused = HuggingFaceGgmlAcquisition.reusablePinnedDownload(
                pinned,
                published,
                1024
            )
            assertEquals(published.toAbsolutePath().normalize(), reused?.finalPath)
            assertEquals(bytes.size.toLong(), reused?.downloadedBytes)
            assertEquals(sha256(bytes), reused?.sha256)
            assertNull(
                HuggingFaceGgmlAcquisition.reusablePinnedDownload(
                    unpinned,
                    published,
                    1024
                )
            )
        } finally {
            Files.deleteIfExists(published)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun sameLengthCorruptPinnedCacheIsNotReusedAndCanBeReplaced() {
        val expectedBytes = "GGU1".toByteArray()
        val pinned = candidate("models/model.gguf", expectedBytes.size.toLong(), sha256(expectedBytes))
        val directory = Files.createTempDirectory("hf-pinned-corrupt")
        val published = directory.resolve(
            HuggingFaceGgmlAcquisition.pinnedStorageFilename(pinned)
        )
        try {
            Files.write(published, "GGUF".toByteArray())

            assertNull(
                HuggingFaceGgmlAcquisition.reusablePinnedDownload(
                    pinned,
                    published,
                    1024
                )
            )
            assertTrue(Files.isRegularFile(published))
        } finally {
            Files.deleteIfExists(published)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun replacementPlanningNeverDeletesTheActiveCorruptCacheSlot() {
        val expectedBytes = "GGU1".toByteArray()
        val corruptBytes = "GGUF".toByteArray()
        val pinned = candidate("models/model.gguf", expectedBytes.size.toLong(), sha256(expectedBytes))
        val directory = Files.createTempDirectory("hf-pinned-active-replacement")
        val slots = HuggingFaceGgmlAcquisition.pinnedStorageFilenames(pinned)
            .map(directory::resolve)
        val active = slots.first()
        val alternate = slots.last()
        try {
            Files.write(active, corruptBytes)

            val plan = HuggingFaceGgmlAcquisition.planPinnedDownload(
                candidate = pinned,
                directory = directory,
                activeModelPath = active,
                maxBytes = 1024
            )

            assertEquals(alternate.toAbsolutePath().normalize(), plan.finalPath)
            assertNull(plan.reusableDownload)
            assertEquals(listOf(active.toAbsolutePath().normalize()), plan.obsoletePathsAfterActivation)
            assertEquals("GGUF", String(Files.readAllBytes(active)))
            assertFalse(Files.exists(alternate))
        } finally {
            slots.forEach { Files.deleteIfExists(it) }
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun validAlternateCanReplaceAnActiveCorruptSlotWithoutTouchingIt() {
        val expectedBytes = "GGU1".toByteArray()
        val pinned = candidate("models/model.gguf", expectedBytes.size.toLong(), sha256(expectedBytes))
        val directory = Files.createTempDirectory("hf-pinned-active-reuse")
        val slots = HuggingFaceGgmlAcquisition.pinnedStorageFilenames(pinned)
            .map(directory::resolve)
        val active = slots.first()
        val alternate = slots.last()
        try {
            Files.write(active, "GGUF".toByteArray())
            Files.write(alternate, expectedBytes)

            val plan = HuggingFaceGgmlAcquisition.planPinnedDownload(
                candidate = pinned,
                directory = directory,
                activeModelPath = active,
                maxBytes = 1024
            )

            assertEquals(alternate.toAbsolutePath().normalize(), plan.finalPath)
            assertEquals(alternate.toAbsolutePath().normalize(), plan.reusableDownload?.finalPath)
            assertEquals(listOf(active.toAbsolutePath().normalize()), plan.obsoletePathsAfterActivation)
            assertEquals("GGUF", String(Files.readAllBytes(active)))
        } finally {
            slots.forEach { Files.deleteIfExists(it) }
            Files.deleteIfExists(directory)
        }
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
        val candidate = candidate(
            "models/tiny model.gguf",
            bytes.size.toLong(),
            sha256(bytes)
        )
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
            assertEquals(sha256(bytes), result.sha256)
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
    fun productionAdapterUsesSharedDownloaderAndPublishesObservableStages() {
        val bytes = "shared-sdx-downloader".toByteArray()
        val candidate = candidate("model.gguf", bytes.size.toLong(), sha256(bytes))
        val directory = Files.createTempDirectory("hf-shared-downloader")
        val destination = directory.resolve("model.gguf")
        val connection = FakeConnection(
            candidate.downloadUri,
            200,
            bytes,
            bytes.size.toLong(),
            mapOf("ETag" to "\"shared-v1\"")
        )
        val downloader = ResumableModelDownloader(
            { connection },
            object : ResumableModelDownloader.MonotonicClock {
                override fun nanoTime(): Long = System.nanoTime()
                override fun currentTimeMillis(): Long = System.currentTimeMillis()
            },
            { _, _ -> Unit },
            { delay, _ -> delay }
        )
        val progress = mutableListOf<HuggingFaceGgmlAcquisition.DownloadProgress>()
        try {
            val result = HuggingFaceGgmlAcquisition.downloadWithSharedUtility(
                candidate,
                destination,
                1024,
                progress::add,
                HuggingFaceGgmlAcquisition.newDownloadCancellation(),
                downloader
            )

            assertEquals(bytes.toList(), Files.readAllBytes(destination).toList())
            assertEquals(sha256(bytes), result.sha256)
            assertEquals(HuggingFaceGgmlAcquisition.DEFAULT_MAX_ATTEMPTS, progress.last().maxAttempts)
            assertTrue(progress.any { it.event == HuggingFaceGgmlAcquisition.DownloadEvent.CONNECT })
            val verification = progress.filter {
                it.event == HuggingFaceGgmlAcquisition.DownloadEvent.VERIFY
            }
            assertTrue(verification.size >= 2)
            assertEquals(0L, verification.first().downloadedBytes)
            assertEquals(bytes.size.toLong(), verification.last().downloadedBytes)
            assertTrue(verification.zipWithNext().all { (left, right) ->
                right.downloadedBytes >= left.downloadedBytes
            })
            assertEquals(HuggingFaceGgmlAcquisition.DownloadEvent.COMPLETE, progress.last().event)
            assertEquals(30_000, connection.connectTimeout)
            assertTrue(connection.readTimeout > 30_000)
        } finally {
            Files.deleteIfExists(destination)
            Files.deleteIfExists(destination.resolveSibling("model.gguf.partial"))
            Files.deleteIfExists(destination.resolveSibling("model.gguf.partial.metadata"))
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun productionAdapterCannotReturnFromVerifyToDownloadAfterTransferCompletes() {
        val bytes = "verify-is-terminal-for-transfer".toByteArray()
        val candidate = candidate("model.gguf", bytes.size.toLong(), sha256(bytes))
        val directory = Files.createTempDirectory("hf-verify-terminal")
        val destination = directory.resolve("model.gguf")
        val metadata = destination.resolveSibling("model.gguf.partial.metadata")
        val marker = metadata.resolve("keep")
        var openCount = 0
        val downloader = ResumableModelDownloader(
            { uri ->
                openCount++
                check(openCount == 1) { "Verification reopened the HTTP transfer" }
                FakeConnection(
                    uri,
                    200,
                    bytes,
                    bytes.size.toLong(),
                    mapOf("ETag" to "\"verify-terminal-v1\"")
                )
            },
            object : ResumableModelDownloader.MonotonicClock {
                override fun nanoTime(): Long = System.nanoTime()
                override fun currentTimeMillis(): Long = System.currentTimeMillis()
            },
            { _, _ -> Unit },
            { delay, _ -> delay }
        )
        val poisonedCleanup = AtomicBoolean(false)
        val progress = mutableListOf<HuggingFaceGgmlAcquisition.DownloadProgress>()
        try {
            val result = HuggingFaceGgmlAcquisition.downloadWithSharedUtility(
                candidate,
                destination,
                1024,
                { update ->
                    progress.add(update)
                    if (
                        update.event == HuggingFaceGgmlAcquisition.DownloadEvent.VERIFY &&
                        update.downloadedBytes == bytes.size.toLong() &&
                        poisonedCleanup.compareAndSet(false, true)
                    ) {
                        Files.delete(metadata)
                        Files.createDirectory(metadata)
                        Files.write(marker, byteArrayOf(1))
                    }
                },
                HuggingFaceGgmlAcquisition.newDownloadCancellation(),
                downloader
            )

            assertEquals(1, openCount)
            assertEquals(bytes.toList(), Files.readAllBytes(destination).toList())
            assertEquals(sha256(bytes), result.sha256)
            assertTrue(poisonedCleanup.get())
            assertFalse(progress.any { it.event == HuggingFaceGgmlAcquisition.DownloadEvent.RETRY })
            assertEquals(HuggingFaceGgmlAcquisition.DownloadEvent.COMPLETE, progress.last().event)
            assertTrue(progress.last().message.contains("cleanup deferred"))
        } finally {
            Files.deleteIfExists(marker)
            Files.deleteIfExists(metadata)
            Files.deleteIfExists(destination)
            Files.deleteIfExists(destination.resolveSibling("model.gguf.partial"))
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun tokenizerAssetsDownloadWithAggregateProgressAndAreReusedIndividually() {
        val tokenizerBytes = "tok".toByteArray()
        val tokenizerConfigBytes = "{}".toByteArray()
        val modelConfigBytes = "{}".toByteArray()
        val candidate = HuggingFaceGgmlAcquisition.discover("acme/tiny-chat") {
            """{
              "sha":"$sha",
              "siblings":[
                {"rfilename":"model.gguf","size":4},
                {"rfilename":"tokenizer.json","size":${tokenizerBytes.size}},
                {"rfilename":"tokenizer_config.json","size":${tokenizerConfigBytes.size}},
                {"rfilename":"config.json","size":${modelConfigBytes.size}}
              ]
            }"""
        }.selectedCandidate().orElseThrow()
        val directory = Files.createTempDirectory("hf-tokenizer-assets")
        val model = directory.resolve("model-cache.gguf")
        Files.write(model, byteArrayOf(0x47, 0x47, 0x55, 0x46))
        val progress = mutableListOf<HuggingFaceGgmlAcquisition.DownloadProgress>()
        val downloader = ResumableModelDownloader(
            { uri ->
                val body = when {
                    uri.path.endsWith("/tokenizer.json") -> tokenizerBytes
                    uri.path.endsWith("/tokenizer_config.json") -> tokenizerConfigBytes
                    uri.path.endsWith("/config.json") -> modelConfigBytes
                    else -> error("Unexpected asset URI: $uri")
                }
                FakeConnection(uri, 200, body, body.size.toLong(), mapOf("ETag" to "\"asset\""))
            },
            object : ResumableModelDownloader.MonotonicClock {
                override fun nanoTime(): Long = System.nanoTime()
                override fun currentTimeMillis(): Long = System.currentTimeMillis()
            },
            { _, _ -> Unit },
            { delay, _ -> delay }
        )
        try {
            val first = HuggingFaceGgmlAcquisition.ensureTokenizerAssets(
                candidate,
                model,
                progress::add,
                HuggingFaceGgmlAcquisition.newDownloadCancellation(),
                downloader
            )

            assertEquals(3, first.paths.size)
            assertEquals(0, first.reusedCount)
            assertEquals(tokenizerBytes.toList(), Files.readAllBytes(first.paths.getValue("tokenizer.json")).toList())
            assertEquals(
                tokenizerConfigBytes.toList(),
                Files.readAllBytes(first.paths.getValue("tokenizer_config.json")).toList()
            )
            assertEquals(
                modelConfigBytes.toList(),
                Files.readAllBytes(first.paths.getValue("config.json")).toList()
            )
            assertEquals(
                (tokenizerBytes.size + tokenizerConfigBytes.size + modelConfigBytes.size).toLong(),
                progress.last().expectedBytes
            )
            assertEquals(progress.last().expectedBytes, progress.last().downloadedBytes)
            assertTrue(progress.zipWithNext().all { (left, right) ->
                right.downloadedBytes >= left.downloadedBytes
            })

            val reused = HuggingFaceGgmlAcquisition.ensureTokenizerAssets(
                candidate,
                model,
                {},
                HuggingFaceGgmlAcquisition.newDownloadCancellation(),
                ResumableModelDownloader(
                    { error("Verified tokenizer assets must not reconnect") },
                    object : ResumableModelDownloader.MonotonicClock {
                        override fun nanoTime(): Long = System.nanoTime()
                        override fun currentTimeMillis(): Long = System.currentTimeMillis()
                    },
                    { _, _ -> Unit },
                    { delay, _ -> delay }
                )
            )
            assertEquals(3, reused.reusedCount)
        } finally {
            HuggingFaceGgmlAcquisition.tokenizerAssetPathsForModel(model).forEach { asset ->
                Files.deleteIfExists(asset)
                Files.deleteIfExists(asset.resolveSibling("${asset.fileName}.partial"))
                Files.deleteIfExists(asset.resolveSibling("${asset.fileName}.partial.metadata"))
            }
            Files.deleteIfExists(model)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun sharedAdapterRetriesSocketAbortAndResumesValidatorBackedBytes() {
        val bytes = "validator-backed Android socket retry".toByteArray()
        val prefixLength = 11
        val candidate = candidate("retry.gguf", bytes.size.toLong(), sha256(bytes))
        val directory = Files.createTempDirectory("hf-shared-retry")
        val destination = directory.resolve("retry.gguf")
        val interrupted = FakeConnection(
            candidate.downloadUri,
            200,
            bytes,
            bytes.size.toLong(),
            mapOf("ETag" to "\"stable\""),
            SocketAbortInputStream(bytes, prefixLength)
        )
        val suffix = bytes.copyOfRange(prefixLength, bytes.size)
        val resumed = FakeConnection(
            candidate.downloadUri,
            206,
            suffix,
            suffix.size.toLong(),
            mapOf(
                "ETag" to "\"stable\"",
                "Content-Range" to "bytes $prefixLength-${bytes.lastIndex}/${bytes.size}"
            )
        )
        var connectionCount = 0
        val sleeps = mutableListOf<Long>()
        val downloader = ResumableModelDownloader(
            {
                when (++connectionCount) {
                    1 -> interrupted
                    2 -> resumed
                    else -> error("Unexpected download attempt $connectionCount")
                }
            },
            object : ResumableModelDownloader.MonotonicClock {
                override fun nanoTime(): Long = System.nanoTime()
                override fun currentTimeMillis(): Long = System.currentTimeMillis()
            },
            { delay, _ -> sleeps.add(delay) },
            { delay, _ -> delay }
        )
        val progress = mutableListOf<HuggingFaceGgmlAcquisition.DownloadProgress>()
        try {
            val result = HuggingFaceGgmlAcquisition.downloadWithSharedUtility(
                candidate,
                destination,
                1024,
                progress::add,
                HuggingFaceGgmlAcquisition.newDownloadCancellation(),
                downloader
            )

            assertEquals(bytes.toList(), Files.readAllBytes(destination).toList())
            assertEquals(sha256(bytes), result.sha256)
            assertEquals(2, connectionCount)
            assertEquals(listOf(1_000L), sleeps)
            val retry = progress.single {
                it.event == HuggingFaceGgmlAcquisition.DownloadEvent.RETRY
            }
            assertEquals(1, retry.attempt)
            assertEquals(HuggingFaceGgmlAcquisition.DEFAULT_MAX_ATTEMPTS, retry.maxAttempts)
            assertEquals(prefixLength.toLong(), retry.downloadedBytes)
            assertEquals(bytes.size.toLong(), retry.expectedBytes)
            assertEquals(prefixLength.toLong(), retry.resumedBytes)
            assertEquals(1_000L, retry.retryDelayMillis)
            assertTrue(retry.retryWillResume)
            assertTrue(retry.message.contains("Software caused connection abort"))
            val resume = progress.first {
                it.event == HuggingFaceGgmlAcquisition.DownloadEvent.RESUME
            }
            assertEquals(2, resume.attempt)
            assertEquals(prefixLength.toLong(), resume.resumedBytes)
            assertEquals("bytes=$prefixLength-", resumed.requestHeader("Range"))
            assertEquals("\"stable\"", resumed.requestHeader("If-Range"))
            assertEquals(HuggingFaceGgmlAcquisition.DownloadEvent.COMPLETE, progress.last().event)
            assertTrue(HuggingFaceGgmlAcquisition.TRANSFER_POLICY_SUMMARY.contains("4 attempts"))
            assertTrue(HuggingFaceGgmlAcquisition.TRANSFER_POLICY_SUMMARY.contains("10m no-data timeout"))
        } finally {
            Files.deleteIfExists(destination)
            Files.deleteIfExists(destination.resolveSibling("retry.gguf.partial"))
            Files.deleteIfExists(destination.resolveSibling("retry.gguf.partial.metadata"))
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun resumesVerifiedPinnedPartialWithRangeAndIfRange() {
        val bytes = "GGUF-resumable-payload".toByteArray()
        val prefixLength = 7
        val candidate = candidate("model.gguf", bytes.size.toLong(), sha256(bytes))
        val directory = Files.createTempDirectory("hf-download-resume")
        val suppliedTemporary = directory.resolve("ignored.pending")
        val destination = directory.resolve("model.gguf")
        val connections = mutableListOf<FakeConnection>()
        try {
            val firstFailure = runCatching {
                HuggingFaceGgmlAcquisition.download(
                    candidate, suppliedTemporary, destination, 1024, {}, { false }
                ) { uri ->
                    FakeConnection(
                        uri, 200, bytes, bytes.size.toLong(), mapOf("ETag" to "\"v1\""),
                        FailingInputStream(bytes, prefixLength)
                    ).also(connections::add)
                }
            }.exceptionOrNull()
            assertTrue(firstFailure is IOException)
            val partial = HuggingFaceGgmlAcquisition.partialPath(destination)
            assertEquals(bytes.take(prefixLength), Files.readAllBytes(partial).toList())

            val suffix = bytes.copyOfRange(prefixLength, bytes.size)
            val result = HuggingFaceGgmlAcquisition.download(
                candidate, suppliedTemporary, destination, 1024, {}, { false }
            ) { uri ->
                FakeConnection(
                    uri,
                    206,
                    suffix,
                    suffix.size.toLong(),
                    mapOf(
                        "ETag" to "\"v1\"",
                        "Content-Range" to "bytes $prefixLength-${bytes.lastIndex}/${bytes.size}"
                    )
                ).also(connections::add)
            }

            assertEquals("bytes=$prefixLength-", connections.last().requestHeader("Range"))
            assertEquals("\"v1\"", connections.last().requestHeader("If-Range"))
            assertEquals(bytes.toList(), Files.readAllBytes(destination).toList())
            assertEquals(bytes.size.toLong(), result.downloadedBytes)
            assertFalse(Files.exists(partial))
            assertFalse(Files.exists(partial.resolveSibling("${partial.fileName}.metadata")))
        } finally {
            deleteDownloadFiles(directory, destination)
        }
    }

    @Test
    fun storagePreflightCountsOnlyAnExactValidatorBackedPartial() {
        val bytes = "GGUF-partial-capacity".toByteArray()
        val prefixLength = 7
        val candidate = candidate("model.gguf", bytes.size.toLong(), sha256(bytes))
        val changedCandidate = candidate("other.gguf", bytes.size.toLong(), sha256(bytes))
        val directory = Files.createTempDirectory("hf-partial-capacity")
        val destination = directory.resolve("model.gguf")
        try {
            createInterruptedPartial(candidate, destination, bytes, prefixLength)

            assertEquals(
                prefixLength.toLong(),
                HuggingFaceGgmlAcquisition.resumablePartialBytes(candidate, destination)
            )
            assertEquals(
                0L,
                HuggingFaceGgmlAcquisition.resumablePartialBytes(changedCandidate, destination)
            )
        } finally {
            deleteDownloadFiles(directory, destination)
        }
    }

    @Test
    fun ignoredRangeRestartsFromComplete200BodyWithoutAppending() {
        val bytes = "GGUF-range-ignored".toByteArray()
        val prefixLength = 5
        val candidate = candidate("model.gguf", bytes.size.toLong(), sha256(bytes))
        val directory = Files.createTempDirectory("hf-download-range-ignored")
        val destination = directory.resolve("model.gguf")
        try {
            createInterruptedPartial(candidate, destination, bytes, prefixLength)
            lateinit var resumed: FakeConnection

            HuggingFaceGgmlAcquisition.download(
                candidate, directory.resolve("unused.pending"), destination, 1024, {}, { false }
            ) { uri ->
                FakeConnection(
                    uri, 200, bytes, bytes.size.toLong(), mapOf("ETag" to "\"v2\"")
                ).also { resumed = it }
            }

            assertEquals("bytes=$prefixLength-", resumed.requestHeader("Range"))
            assertEquals(bytes.toList(), Files.readAllBytes(destination).toList())
        } finally {
            deleteDownloadFiles(directory, destination)
        }
    }

    @Test
    fun malformedOrMismatchedContentRangeIsDiscardedAndSafelyRestarted() {
        listOf(
            "bytes nope" to "malformed",
            "bytes 2-9/10" to "wrong-start",
            "bytes 5-8/99" to "wrong-total"
        ).forEach { (contentRange, label) ->
            val bytes = "GGUF-range".toByteArray()
            val prefixLength = 5
            val candidate = candidate("$label.gguf", bytes.size.toLong(), sha256(bytes))
            val directory = Files.createTempDirectory("hf-download-$label")
            val destination = directory.resolve("model.gguf")
            var call = 0
            val connections = mutableListOf<FakeConnection>()
            try {
                createInterruptedPartial(candidate, destination, bytes, prefixLength)
                HuggingFaceGgmlAcquisition.download(
                    candidate, directory.resolve("unused.pending"), destination, 1024, {}, { false }
                ) { uri ->
                    call++
                    if (call == 1) {
                        FakeConnection(
                            uri, 206, bytes.copyOfRange(prefixLength, bytes.size),
                            (bytes.size - prefixLength).toLong(),
                            mapOf("ETag" to "\"v1\"", "Content-Range" to contentRange)
                        )
                    } else {
                        FakeConnection(
                            uri, 200, bytes, bytes.size.toLong(), mapOf("ETag" to "\"v2\"")
                        )
                    }.also(connections::add)
                }

                assertEquals(2, call)
                assertEquals("bytes=$prefixLength-", connections.first().requestHeader("Range"))
                assertNull(connections.last().requestHeader("Range"))
                assertEquals(bytes.toList(), Files.readAllBytes(destination).toList())
            } finally {
                deleteDownloadFiles(directory, destination)
            }
        }
    }

    @Test
    fun cancellationPreservesVerifiedPartialAndRetryResumesIt() {
        val bytes = "GGUF-cancel-and-retry".toByteArray()
        val prefixLength = 6
        val candidate = candidate("cancel.gguf", bytes.size.toLong(), sha256(bytes))
        val directory = Files.createTempDirectory("hf-download-cancel-retry")
        val destination = directory.resolve("model.gguf")
        var cancellationChecks = 0
        try {
            val cancellation = runCatching {
                HuggingFaceGgmlAcquisition.download(
                    candidate, directory.resolve("unused.pending"), destination, 1024, {},
                    { ++cancellationChecks > 3 }
                ) { uri ->
                    FakeConnection(
                        uri, 200, bytes, bytes.size.toLong(), mapOf("ETag" to "\"stable\""),
                        ChunkedInputStream(bytes, prefixLength)
                    )
                }
            }.exceptionOrNull()
            assertTrue(cancellation is java.io.InterruptedIOException)
            val partial = HuggingFaceGgmlAcquisition.partialPath(destination)
            assertEquals(bytes.take(prefixLength), Files.readAllBytes(partial).toList())

            lateinit var retry: FakeConnection
            HuggingFaceGgmlAcquisition.download(
                candidate, directory.resolve("unused-again.pending"), destination, 1024, {}, { false }
            ) { uri ->
                val suffix = bytes.copyOfRange(prefixLength, bytes.size)
                FakeConnection(
                    uri, 206, suffix, suffix.size.toLong(),
                    mapOf(
                        "ETag" to "\"stable\"",
                        "Content-Range" to "bytes $prefixLength-${bytes.lastIndex}/${bytes.size}"
                    )
                ).also { retry = it }
            }

            assertEquals("bytes=$prefixLength-", retry.requestHeader("Range"))
            assertEquals(bytes.toList(), Files.readAllBytes(destination).toList())
        } finally {
            deleteDownloadFiles(directory, destination)
        }
    }

    @Test
    fun immutableIdentityChangeDiscardsOldPartialBeforeRequest() {
        val firstBytes = "GGUF-first-model".toByteArray()
        val secondBytes = "GGUF-other-model".toByteArray()
        assertEquals(firstBytes.size, secondBytes.size)
        val first = candidate("first.gguf", firstBytes.size.toLong(), sha256(firstBytes))
        val second = candidate("second.gguf", secondBytes.size.toLong(), sha256(secondBytes))
        val directory = Files.createTempDirectory("hf-download-identity-change")
        val destination = directory.resolve("model.gguf")
        try {
            createInterruptedPartial(first, destination, firstBytes, 5)
            lateinit var request: FakeConnection
            HuggingFaceGgmlAcquisition.download(
                second, directory.resolve("unused.pending"), destination, 1024, {}, { false }
            ) { uri ->
                FakeConnection(
                    uri, 200, secondBytes, secondBytes.size.toLong(),
                    mapOf("ETag" to "\"second\"")
                ).also { request = it }
            }

            assertNull(request.requestHeader("Range"))
            assertNull(request.requestHeader("If-Range"))
            assertEquals(secondBytes.toList(), Files.readAllBytes(destination).toList())
        } finally {
            deleteDownloadFiles(directory, destination)
        }
    }

    @Test
    fun rejectsRepositoryDigestMismatchBeforePublishing() {
        val bytes = "GGUF".toByteArray()
        val expected = "GGU1".toByteArray()
        val candidate = candidate("model.gguf", bytes.size.toLong(), sha256(expected))
        val directory = Files.createTempDirectory("hf-download-digest")
        val temporary = directory.resolve("model.part")
        val destination = directory.resolve("model.gguf")
        try {
            val failure = runCatching {
                HuggingFaceGgmlAcquisition.download(
                    candidate, temporary, destination, 100, {}, { false }
                ) { FakeConnection(it, 200, bytes, bytes.size.toLong()) }
            }.exceptionOrNull()

            assertTrue(failure?.message.orEmpty().contains("SHA-256 mismatch"))
            assertFalse(Files.exists(temporary))
            assertFalse(Files.exists(destination))
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
    fun ggufImporterAcceptsEveryDl4jGgufAndLegacyGgmlMagic() {
        listOf("GGUF", "ggml", "ggmf", "ggjt", "lmgg", "fmgg", "tjgg").forEach { magic ->
            assertTrue(
                "Expected DL4J-compatible magic $magic",
                SdxGgufModelImporter.hasSupportedMagic(magic.toByteArray(Charsets.US_ASCII))
            )
        }
        assertFalse(
            SdxGgufModelImporter.hasSupportedMagic("<htm".toByteArray(Charsets.US_ASCII))
        )
    }

    private fun candidate(path: String, size: Long, contentSha256: String? = null) =
        HuggingFaceGgmlAcquisition.discover("acme/tiny-chat") {
            val lfs = contentSha256?.let {
                ",\"lfs\":{\"size\":$size,\"sha256\":\"$it\"}"
            }.orEmpty()
            """{"sha":"$sha","siblings":[
              {"rfilename":"$path","size":$size$lfs},
              {"rfilename":"tokenizer.json","size":1},
              {"rfilename":"tokenizer_config.json","size":1},
              {"rfilename":"config.json","size":1}
            ]}"""
        }.selectedCandidate().orElseThrow()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun createInterruptedPartial(
        candidate: org.nd4j.dsp.model.HuggingFaceGgmlResolver.Candidate,
        destination: java.nio.file.Path,
        bytes: ByteArray,
        prefixLength: Int
    ) {
        val failure = runCatching {
            HuggingFaceGgmlAcquisition.download(
                candidate, destination.resolveSibling("unused.pending"), destination,
                1024, {}, { false }
            ) { uri ->
                FakeConnection(
                    uri, 200, bytes, bytes.size.toLong(), mapOf("ETag" to "\"v1\""),
                    FailingInputStream(bytes, prefixLength)
                )
            }
        }.exceptionOrNull()
        assertTrue(failure is IOException)
    }

    private fun deleteDownloadFiles(directory: java.nio.file.Path, destination: java.nio.file.Path) {
        val partial = HuggingFaceGgmlAcquisition.partialPath(destination)
        Files.deleteIfExists(destination)
        Files.deleteIfExists(partial)
        Files.deleteIfExists(partial.resolveSibling("${partial.fileName}.metadata"))
        Files.deleteIfExists(partial.resolveSibling("${partial.fileName}.metadata.tmp"))
        Files.deleteIfExists(directory.resolve("unused.pending"))
        Files.deleteIfExists(directory.resolve("unused-again.pending"))
        Files.deleteIfExists(directory.resolve("ignored.pending"))
        Files.deleteIfExists(directory)
    }

    private class FailingInputStream(
        private val bytes: ByteArray,
        private val prefixLength: Int
    ) : InputStream() {
        private var position = 0

        override fun read(): Int {
            if (position >= prefixLength) throw IOException("simulated connection loss")
            return bytes[position++].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= prefixLength) throw IOException("simulated connection loss")
            val count = minOf(length, prefixLength - position)
            bytes.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }
    }

    private class SocketAbortInputStream(
        private val bytes: ByteArray,
        private val prefixLength: Int
    ) : InputStream() {
        private var position = 0

        override fun read(): Int {
            if (position >= prefixLength) throw SocketException("Software caused connection abort")
            return bytes[position++].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= prefixLength) throw SocketException("Software caused connection abort")
            val count = minOf(length, prefixLength - position)
            bytes.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }
    }

    private class ChunkedInputStream(
        private val bytes: ByteArray,
        private val chunkSize: Int
    ) : InputStream() {
        private var position = 0

        override fun read(): Int = if (position >= bytes.size) -1 else bytes[position++].toInt() and 0xff

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(length, chunkSize, bytes.size - position)
            bytes.copyInto(buffer, offset, position, position + count)
            position += count
            return count
        }
    }

    private class FakeConnection(
        uri: URI,
        private val status: Int,
        private val body: ByteArray,
        private val declaredLength: Long,
        private val headers: Map<String, String> = emptyMap(),
        private val suppliedInput: InputStream? = null
    ) : HttpURLConnection(URL(uri.toASCIIString())) {
        private val requestHeaders = linkedMapOf<String, String>()

        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = status
        override fun getInputStream(): InputStream = suppliedInput ?: ByteArrayInputStream(body)
        override fun getContentLengthLong(): Long = declaredLength
        override fun getHeaderField(name: String?): String? = headers[name]
        override fun setRequestProperty(key: String, value: String) {
            requestHeaders[key] = value
        }

        fun requestHeader(name: String): String? = requestHeaders[name]
    }
}
