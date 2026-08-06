package ai.kompile.chat.local.android.viewmodel

import ai.kompile.chat.local.ChatEngine
import ai.kompile.chat.local.ChatModel
import ai.kompile.chat.local.GenOptions
import ai.kompile.chat.local.InferenceRouter
import ai.kompile.chat.local.Message
import ai.kompile.chat.local.android.prefs.HuggingFaceImportCheckpoint
import ai.kompile.chat.local.android.prefs.HuggingFaceImportCheckpointStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nd4j.dsp.model.HuggingFaceGgmlResolver
import java.io.File

/** Fast host contract for the user-visible model path every APK must preserve. */
class ModelImportAcceptanceTest {

    private data class ActivatedModel(val file: File, val model: ChatModel)

    @Test
    fun immutableHuggingFaceModelResumesActivatesAndAnswersChat() {
        val stages = mutableListOf(HuggingFaceImportStep.RESOLVE)
        val discovery = discovery()
        val selected = discovery.selectedCandidate().orElseThrow()
        val selectedCheckpoint = HuggingFaceImportCheckpoint.createOrNull(
            rawReference = REPOSITORY,
            discovery = discovery,
            candidate = selected,
            stage = HuggingFaceImportCheckpointStage.SELECTED
        )!!

        val interrupted = interruptedHuggingFaceImportState(selectedCheckpoint)
        assertEquals(HuggingFaceImportStep.PREFLIGHT, interrupted.step)
        assertEquals("Resume interrupted import", huggingFaceRetryButtonLabel(interrupted))
        val afterRestart = selectedCheckpoint.matchingCandidate(discovery())
        assertEquals(selected.path, afterRestart?.path)

        val storagePreflight = huggingFaceStoragePreflight(
            applicationId = "ai.kompile.chat.local.android.tensorg3.debug",
            destinationPath = "/data/user/0/ai.kompile.chat.local.android.tensorg3.debug/files/models/hugging-face/model-Q4_K_M.gguf",
            expectedBytes = 4_096L,
            usableBytes = 16_384L,
            reserveBytes = 1_024L,
            reusableBytes = 1_024L,
            reuse = HuggingFaceStorageReuse.VALIDATED_PARTIAL
        )
        assertTrue(storagePreflight.canProceed)
        assertEquals(3_072L, storagePreflight.additionalBytesRequired)
        val transferState = HuggingFaceImportUiState.Working(
            HuggingFaceImportProgress(
                step = HuggingFaceImportStep.DOWNLOAD,
                message = "Downloading selected immutable model",
                attempt = 2,
                maxAttempts = 4,
                resumedBytes = 1_024L,
                completedBytes = 2_048L,
                totalBytes = 4_096L,
                smoothedBytesPerSecond = 1_024.0,
                etaSeconds = 2L,
                retryWillResumeOrReuse = true,
                storagePreflight = storagePreflight
            )
        )
        val transferPresentation = huggingFaceStepPresentations(transferState)
        assertEquals(HuggingFaceImportStep.entries, transferPresentation.map { it.step })
        assertTrue(transferPresentation.all { it.resumeBehavior.isNotBlank() })
        assertEquals(
            HuggingFaceStepProgressMode.DETERMINATE,
            transferPresentation.single { it.step == HuggingFaceImportStep.DOWNLOAD }.progressMode
        )
        assertEquals(1, transferPresentation.count { it.actionLabel != null })

        stages += HuggingFaceImportStep.PREFLIGHT
        stages += HuggingFaceImportStep.CONNECT
        stages += HuggingFaceImportStep.DOWNLOAD
        stages += HuggingFaceImportStep.VERIFY
        stages += HuggingFaceImportStep.TOKENIZER_ASSETS
        val verifiedCheckpoint = HuggingFaceImportCheckpoint.createOrNull(
            rawReference = REPOSITORY,
            discovery = discovery,
            candidate = selected,
            stage = HuggingFaceImportCheckpointStage.VERIFIED_DOWNLOAD
        )!!
        assertEquals(
            selected.path,
            verifiedCheckpoint.matchingCandidate(discovery())?.path
        )

        val downloaded = File(
            "/data/user/0/ai.kompile.chat/files/models/hugging-face/model-Q4_K_M.gguf"
        )
        val localModel = object : ChatModel {
            override fun generate(messages: List<Message>, opts: GenOptions): String =
                "Hello from the imported SDX model"

            override fun isAvailable(): Boolean = true

            override fun modelId(): String = "sdx-local:${downloaded.name}"
        }
        val activated = ProvenModelActivationTransaction<File, ChatModel, ActivatedModel>(
            stagePendingSelection = { true },
            detachPreviousRuntime = {},
            openCandidate = {
                stages += HuggingFaceImportStep.CONVERT_SDZ
                stages += HuggingFaceImportStep.TARGET_CACHE
                stages += HuggingFaceImportStep.SDX_LOAD
                localModel
            },
            decodedGeneration = { model ->
                stages += HuggingFaceImportStep.SMOKE_DECODE
                model.generate(emptyList(), GenOptions.defaults())
            },
            publish = { file, model ->
                stages += HuggingFaceImportStep.ACTIVATE
                ActivatedModel(file, model)
            },
            promotePendingSelection = { true },
            rollbackPublished = {},
            discardPendingSelection = { true },
            restorePreviousSelection = { true },
            restorePreviousRuntime = {},
            closeCandidate = {}
        ).execute(downloaded)

        assertSame(downloaded, activated.file)
        stages += HuggingFaceImportStep.ACTIVE
        val activeState = HuggingFaceImportUiState.Active(
            artifactName = downloaded.name,
            route = "SDX_GGUF_AOT",
            storageLocation = downloaded.absolutePath,
            message = "Loaded, smoke-decoded, and activated for chat",
            storagePreflight = storagePreflight.copy(
                destinationPath = downloaded.absolutePath,
                reusableBytes = 4_096L,
                additionalBytesRequired = 0L,
                reuse = HuggingFaceStorageReuse.VERIFIED_MODEL
            )
        )
        val presentation = huggingFaceActivePresentation(activeState)
        val activeSteps = huggingFaceStepPresentations(activeState)
        assertEquals("Private app storage: ${downloaded.absolutePath}", presentation.storage)

        val engine = ChatEngine(InferenceRouter(activated.model, null), null, 1)
        val answer = engine.chat(mutableListOf(), "Say hello", GenOptions.defaults()).answer()

        assertEquals("Hello from the imported SDX model", answer)
        assertTrue(presentation.summary.contains("SDX_GGUF_AOT"))
        assertEquals(HuggingFaceImportStep.entries, stages)
        assertEquals(HuggingFaceImportStep.entries.size, activeSteps.size)
        assertTrue(activeSteps.all {
            it.progressMode == HuggingFaceStepProgressMode.COMPLETE && it.progressFraction == 1f
        })
        assertTrue(activeSteps.last().resumeBehavior.contains("active for chat"))
        assertEquals(downloaded.absolutePath, activeState.storagePreflight!!.destinationPath)
    }

    private fun discovery(): HuggingFaceGgmlResolver.Discovery =
        HuggingFaceGgmlResolver.resolve(
            HuggingFaceGgmlResolver.parse(REPOSITORY),
            COMMIT,
            listOf(
                HuggingFaceGgmlResolver.RepositoryFile(
                    "model-Q4_K_M.gguf",
                    4_096L,
                    CONTENT_SHA
                )
            )
        )

    private companion object {
        const val REPOSITORY = "acme/tiny-chat"
        val COMMIT = "a".repeat(40)
        val CONTENT_SHA = "b".repeat(64)
    }
}
