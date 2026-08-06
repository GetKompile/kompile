package ai.kompile.chat.local.android.viewmodel

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvenModelActivationTransactionTest {

    private data class Candidate(val file: File)
    private data class ActiveState(val file: File, val candidate: Candidate)

    @Test
    fun replacementClosesOldRuntimeBeforeCandidateOpenAndPromotesAfterPublication() {
        val events = mutableListOf<String>()

        transaction(
            events = events,
            decode = { events += "proof"; "ready" },
            publish = { file, candidate ->
                events += "publish"
                ActiveState(file, candidate)
            }
        ).execute(File("downloaded.gguf"))

        assertEquals(
            listOf("stage", "detach", "open", "proof", "publish", "promote"),
            events
        )
    }

    @Test
    fun decodeFailureDiscardsPendingAndReopensPreviousRuntime() {
        val previousFile = File("previous.gguf")
        val previous = ActiveState(previousFile, Candidate(previousFile))
        var active = previous
        var pending = false
        val events = mutableListOf<String>()

        val failure = runCatching {
            transaction(
                events = events,
                stage = { pending = true; events += "stage"; true },
                decode = { throw IllegalArgumentException("decode failed") },
                discard = { pending = false; events += "discard"; true },
                restoreRuntime = { active = previous; events += "restore" },
                publish = { file, candidate ->
                    ActiveState(file, candidate).also { active = it }
                }
            ).execute(File("downloaded.gguf"))
        }.exceptionOrNull()

        assertEquals("decode failed", failure?.message)
        assertFalse(pending)
        assertSame(previous, active)
        assertEquals(
            listOf("stage", "detach", "open", "close", "discard", "restore"),
            events
        )
    }

    @Test
    fun promotionFailureRestoresPersistedSelectionBeforeReopeningPreviousRuntime() {
        val previousFile = File("previous.gguf")
        val downloadedFile = File("downloaded.gguf")
        val previous = ActiveState(previousFile, Candidate(previousFile))
        var active = previous
        var persistedModel = previousFile
        var pending = false
        val events = mutableListOf<String>()

        val failure = runCatching {
            transaction(
                events = events,
                stage = { pending = true; events += "stage"; true },
                publish = { file, candidate ->
                    events += "publish"
                    ActiveState(file, candidate).also { active = it }
                },
                promote = {
                    persistedModel = downloadedFile
                    events += "promote"
                    false
                },
                rollback = { events += "rollback" },
                discard = { pending = false; events += "discard"; true },
                restoreSelection = {
                    persistedModel = previousFile
                    events += "restore-selection"
                    true
                },
                restoreRuntime = {
                    check(persistedModel == previousFile)
                    active = previous
                    events += "restore"
                }
            ).execute(downloadedFile)
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertFalse(pending)
        assertEquals(previousFile, persistedModel)
        assertSame(previous, active)
        assertEquals(
            listOf(
                "stage", "detach", "open", "proof", "publish", "promote",
                "rollback", "restore-selection", "discard", "restore"
            ),
            events
        )
    }

    @Test
    fun failedStageCommitDiscardsPossiblyMutatedPendingStateWithoutDetachingRuntime() {
        var pending = false
        val events = mutableListOf<String>()

        val failure = runCatching {
            transaction(
                events = events,
                stage = { pending = true; events += "stage"; false },
                discard = { pending = false; events += "discard"; true },
                publish = { file, candidate -> ActiveState(file, candidate) }
            ).execute(File("downloaded.gguf"))
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertFalse(pending)
        assertEquals(listOf("stage", "discard"), events)
    }

    @Test
    fun allocationErrorStillClosesCandidateDiscardsPendingAndRestoresPreviousRuntime() {
        var pending = false
        var restored = false
        val events = mutableListOf<String>()

        val failure = runCatching {
            transaction(
                events = events,
                stage = { pending = true; events += "stage"; true },
                decode = { throw OutOfMemoryError("simulated candidate allocation failure") },
                discard = { pending = false; events += "discard"; true },
                restoreRuntime = { restored = true; events += "restore" },
                publish = { file, candidate -> ActiveState(file, candidate) }
            ).execute(File("too-large.gguf"))
        }.exceptionOrNull()

        assertTrue(failure is OutOfMemoryError)
        assertFalse(pending)
        assertTrue(restored)
        assertEquals(
            listOf("stage", "detach", "open", "close", "discard", "restore"),
            events
        )
    }

    @Test
    fun discardFailureIsSuppressedWithoutMaskingTheActivationFailure() {
        val events = mutableListOf<String>()

        val failure = runCatching {
            transaction(
                events = events,
                decode = { throw IllegalArgumentException("decode failed") },
                discard = { throw IllegalStateException("journal unavailable") },
                publish = { file, candidate -> ActiveState(file, candidate) }
            ).execute(File("downloaded.gguf"))
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals("decode failed", failure?.message)
        assertTrue(failure?.suppressed?.any { it.message == "journal unavailable" } == true)
        assertEquals(listOf("stage", "detach", "open", "close", "restore"), events)
    }

    @Test
    fun exactDownloadedFileIsStagedOpenedPublishedAndActivated() {
        val downloaded = File("models/hugging-face/exact-download.gguf").absoluteFile
        var stagedFile: File? = null
        var openedFile: File? = null
        var publishedFile: File? = null
        var publishedCandidate: Candidate? = null

        val active = ProvenModelActivationTransaction<File, Candidate, ActiveState>(
            stagePendingSelection = { file -> stagedFile = file; true },
            detachPreviousRuntime = {},
            openCandidate = { file ->
                openedFile = file
                Candidate(file)
            },
            decodedGeneration = { "ready" },
            publish = { file, candidate ->
                publishedFile = file
                publishedCandidate = candidate
                ActiveState(file, candidate)
            },
            promotePendingSelection = { true },
            rollbackPublished = {},
            discardPendingSelection = { true },
            restorePreviousSelection = { true },
            restorePreviousRuntime = {},
            closeCandidate = {}
        ).execute(downloaded)

        assertSame(downloaded, stagedFile)
        assertSame(downloaded, openedFile)
        assertSame(downloaded, publishedFile)
        assertSame(downloaded, active.file)
        assertSame(publishedCandidate, active.candidate)
        assertSame(downloaded, active.candidate.file)
    }

    private fun transaction(
        events: MutableList<String>,
        stage: (File) -> Boolean = { events += "stage"; true },
        decode: (Candidate) -> String = { events += "proof"; "ready" },
        publish: (File, Candidate) -> ActiveState,
        promote: () -> Boolean = { events += "promote"; true },
        rollback: (ActiveState) -> Unit = { events += "rollback" },
        discard: () -> Boolean = { events += "discard"; true },
        restoreSelection: () -> Boolean = { events += "restore-selection"; true },
        restoreRuntime: () -> Unit = { events += "restore" }
    ): ProvenModelActivationTransaction<File, Candidate, ActiveState> =
        ProvenModelActivationTransaction(
            stagePendingSelection = stage,
            detachPreviousRuntime = { events += "detach" },
            openCandidate = { file -> events += "open"; Candidate(file) },
            decodedGeneration = decode,
            publish = publish,
            promotePendingSelection = promote,
            rollbackPublished = rollback,
            discardPendingSelection = discard,
            restorePreviousSelection = restoreSelection,
            restorePreviousRuntime = restoreRuntime,
            closeCandidate = { events += "close" }
        )
}
