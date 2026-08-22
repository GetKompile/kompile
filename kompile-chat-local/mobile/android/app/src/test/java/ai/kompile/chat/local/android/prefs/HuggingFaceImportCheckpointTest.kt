package ai.kompile.chat.local.android.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nd4j.dsp.model.HuggingFaceGgmlResolver

class HuggingFaceImportCheckpointTest {

    @Test
    fun immutableSelectionCanBeReResolvedAfterProcessDeath() {
        val firstDiscovery = discovery(COMMIT, "model-Q4_K_M.gguf", 4_096L, CONTENT_SHA)
        val firstCandidate = firstDiscovery.selectedCandidate().orElseThrow()
        val saved = HuggingFaceImportCheckpoint.createOrNull(
            rawReference = "acme/tiny-chat",
            discovery = firstDiscovery,
            candidate = firstCandidate,
            stage = HuggingFaceImportCheckpointStage.SELECTED
        )!!

        val afterRestart = discovery(COMMIT, "model-Q4_K_M.gguf", 4_096L, CONTENT_SHA)
        val resumed = saved.matchingCandidate(afterRestart)

        assertTrue(saved.isStructurallyValid())
        assertEquals("model-Q4_K_M.gguf", resumed?.path)
        assertTrue(resumed?.isCommitPinned == true)
    }

    @Test
    fun verifiedBoundaryRetainsOnlyImmutableIdentity() {
        val discovery = discovery(COMMIT, "weights/model.gguf", 8_192L, CONTENT_SHA)
        val checkpoint = HuggingFaceImportCheckpoint.createOrNull(
            rawReference = " https://huggingface.co/acme/tiny-chat ",
            discovery = discovery,
            candidate = discovery.selectedCandidate().orElseThrow(),
            stage = HuggingFaceImportCheckpointStage.VERIFIED_DOWNLOAD
        )!!

        assertEquals("https://huggingface.co/acme/tiny-chat", checkpoint.rawReference)
        assertEquals("acme/tiny-chat", checkpoint.repository)
        assertEquals(COMMIT, checkpoint.resolvedRevision)
        assertEquals(8_192L, checkpoint.expectedBytes)
        assertEquals(CONTENT_SHA, checkpoint.expectedSha256)
        assertEquals(HuggingFaceImportCheckpointStage.VERIFIED_DOWNLOAD, checkpoint.stage)
    }

    @Test
    fun observedStageAndProgressSurviveProcessDeathWithoutPersistingAUrl() {
        val discovery = discovery(COMMIT, "weights/model.gguf", 8_192L, CONTENT_SHA)
        val checkpoint = HuggingFaceImportCheckpoint.createOrNull(
            rawReference = "acme/tiny-chat",
            discovery = discovery,
            candidate = discovery.selectedCandidate().orElseThrow(),
            stage = HuggingFaceImportCheckpointStage.SELECTED
        )!!.withObservation(
            step = "VERIFY",
            message = "Verifying https://huggingface.co/acme/tiny-chat?token=hf_1234567890 for model.gguf",
            attempt = 3,
            maxAttempts = 4,
            resumedBytes = 2_048L,
            completedBytes = 8_192L,
            totalBytes = 8_192L,
            retryWillResumeOrReuse = true
        )

        assertTrue(checkpoint.isStructurallyValid())
        assertEquals("VERIFY", checkpoint.observedStep)
        assertEquals(3, checkpoint.observedAttempt)
        assertEquals(8_192L, checkpoint.observedCompletedBytes)
        assertEquals(8_192L, checkpoint.observedTotalBytes)
        assertTrue(checkpoint.observedRetryWillResumeOrReuse)
        assertFalse(checkpoint.observedMessage.contains("huggingface.co"))
        assertFalse(checkpoint.observedMessage.contains("hf_1234567890"))
        assertTrue(checkpoint.observedMessage.contains("[url]"))
    }

    @Test
    fun changedCommitPathSizeOrDigestCannotResume() {
        val original = discovery(COMMIT, "model.gguf", 1_024L, CONTENT_SHA)
        val checkpoint = HuggingFaceImportCheckpoint.createOrNull(
            "acme/tiny-chat",
            original,
            original.selectedCandidate().orElseThrow(),
            HuggingFaceImportCheckpointStage.SELECTED
        )!!

        assertNull(checkpoint.matchingCandidate(discovery(OTHER_COMMIT, "model.gguf", 1_024L, CONTENT_SHA)))
        assertNull(checkpoint.matchingCandidate(discovery(COMMIT, "other.gguf", 1_024L, CONTENT_SHA)))
        assertNull(checkpoint.matchingCandidate(discovery(COMMIT, "model.gguf", 2_048L, CONTENT_SHA)))
        assertNull(checkpoint.matchingCandidate(discovery(COMMIT, "model.gguf", 1_024L, OTHER_CONTENT_SHA)))
    }

    @Test
    fun persistedReferencesAreCanonicalAndCredentialBearingInputIsRejected() {
        val discovery = discovery(COMMIT, "model.gguf", 1_024L, CONTENT_SHA)
        val candidate = discovery.selectedCandidate().orElseThrow()

        assertEquals(
            discovery.reference.canonicalReference,
            canonicalHuggingFaceReferenceOrNull(" acme/tiny-chat ")
        )
        assertEquals("", canonicalHuggingFaceReferenceOrNull("   "))
        assertNull(
            canonicalHuggingFaceReferenceOrNull(
                "https://huggingface.co/acme/tiny-chat?token=hf_should_not_persist"
            )
        )
        assertNull(
            HuggingFaceImportCheckpoint.createOrNull(
                "https://huggingface.co/acme/tiny-chat?token=hf_should_not_persist",
                discovery,
                candidate,
                HuggingFaceImportCheckpointStage.SELECTED
            )
        )
    }

    @Test
    fun movingBranchReferenceIsNeverPersistedAsDurableResume() {
        val discovery = HuggingFaceGgmlResolver.exact(
            HuggingFaceGgmlResolver.parse(
                "https://huggingface.co/acme/tiny-chat/resolve/main/model.gguf"
            )
        )

        assertFalse(discovery.selectedCandidate().orElseThrow().isCommitPinned)
        assertNull(
            HuggingFaceImportCheckpoint.createOrNull(
                "https://huggingface.co/acme/tiny-chat/resolve/main/model.gguf",
                discovery,
                discovery.selectedCandidate().orElseThrow(),
                HuggingFaceImportCheckpointStage.SELECTED
            )
        )
    }

    @Test
    fun malformedPersistedIdentityIsRejected() {
        val malformed = HuggingFaceImportCheckpoint(
            rawReference = "https://huggingface.co/acme/tiny-chat?token=hf_should_not_persist",
            repository = "not-a-repository",
            resolvedRevision = "main",
            candidatePath = "model.gguf",
            expectedBytes = -2L,
            expectedSha256 = "not-a-digest",
            stage = HuggingFaceImportCheckpointStage.SELECTED
        )

        assertFalse(malformed.isStructurallyValid())
    }

    private fun discovery(
        revision: String,
        path: String,
        size: Long,
        sha256: String
    ): HuggingFaceGgmlResolver.Discovery = HuggingFaceGgmlResolver.resolve(
        HuggingFaceGgmlResolver.parse("acme/tiny-chat"),
        revision,
        listOf(HuggingFaceGgmlResolver.RepositoryFile(path, size, sha256))
    )

    private companion object {
        val COMMIT = "a".repeat(40)
        val OTHER_COMMIT = "c".repeat(40)
        val CONTENT_SHA = "b".repeat(64)
        val OTHER_CONTENT_SHA = "d".repeat(64)
    }
}
