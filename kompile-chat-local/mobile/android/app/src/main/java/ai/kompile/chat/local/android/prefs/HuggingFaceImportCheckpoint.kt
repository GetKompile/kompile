package ai.kompile.chat.local.android.prefs

import ai.kompile.chat.local.android.diagnostics.ImportDiagnosticPolicy
import org.nd4j.dsp.model.HuggingFaceGgmlResolver
import java.util.Locale

/** Durable boundaries that are safe to resume after Android kills the app process. */
enum class HuggingFaceImportCheckpointStage {
    SELECTED,
    VERIFIED_DOWNLOAD
}

/**
 * Immutable model identity persisted independently from the active-model transaction.
 *
 * Download URLs, cache paths, validators, and byte counts are deliberately excluded. On resume the
 * app re-resolves [rawReference], proves the same commit/file identity, and lets the canonical
 * downloader rediscover validator-backed partial bytes or a verified final file.
 */
data class HuggingFaceImportCheckpoint(
    val rawReference: String,
    val repository: String,
    val resolvedRevision: String,
    val candidatePath: String,
    val expectedBytes: Long,
    val expectedSha256: String,
    val stage: HuggingFaceImportCheckpointStage,
    val observedStep: String = "",
    val observedMessage: String = "",
    val observedAttempt: Int = 1,
    val observedMaxAttempts: Int = 1,
    val observedResumedBytes: Long = 0L,
    val observedCompletedBytes: Long = 0L,
    val observedTotalBytes: Long = UNKNOWN_SIZE,
    val observedRetryWillResumeOrReuse: Boolean = false,
    val version: Int = CURRENT_VERSION
) {
    fun isStructurallyValid(): Boolean {
        if (version != CURRENT_VERSION || rawReference.isBlank() || candidatePath.isBlank()) {
            return false
        }
        if (!IMMUTABLE_REVISION.matches(resolvedRevision)) return false
        if (expectedBytes < UNKNOWN_SIZE) return false
        if (expectedSha256.isNotEmpty() && !SHA_256.matches(expectedSha256)) return false
        if (observedStep.isNotEmpty() && observedStep !in OBSERVED_STEPS) return false
        if (observedMessage.length > MAX_OBSERVED_MESSAGE_CHARS) return false
        if (observedAttempt < 1 || observedMaxAttempts < 1 || observedAttempt > observedMaxAttempts) {
            return false
        }
        if (observedResumedBytes < 0L || observedCompletedBytes < 0L) return false
        if (observedTotalBytes < UNKNOWN_SIZE) return false
        val parsedReference = runCatching { HuggingFaceGgmlResolver.parse(rawReference) }.getOrNull()
            ?: return false
        if (parsedReference.repository != repository) return false
        return runCatching { HuggingFaceGgmlResolver.requireRepositoryId(repository) }
            .getOrNull() == repository
    }

    /** Persist only bounded, credential-free UI progress; the downloader revalidates saved bytes. */
    fun withObservation(
        step: String,
        message: String,
        attempt: Int,
        maxAttempts: Int,
        resumedBytes: Long,
        completedBytes: Long,
        totalBytes: Long?,
        retryWillResumeOrReuse: Boolean
    ): HuggingFaceImportCheckpoint = copy(
        observedStep = step,
        observedMessage = ImportDiagnosticPolicy.sanitize(message)
            .take(MAX_OBSERVED_MESSAGE_CHARS),
        observedAttempt = attempt,
        observedMaxAttempts = maxAttempts,
        observedResumedBytes = resumedBytes,
        observedCompletedBytes = completedBytes,
        observedTotalBytes = totalBytes ?: UNKNOWN_SIZE,
        observedRetryWillResumeOrReuse = retryWillResumeOrReuse
    ).also {
        require(it.isStructurallyValid()) { "Invalid Hugging Face import observation" }
    }

    /** Return the exact re-resolved candidate, or null if repository state no longer matches. */
    fun matchingCandidate(
        discovery: HuggingFaceGgmlResolver.Discovery
    ): HuggingFaceGgmlResolver.Candidate? {
        if (!isStructurallyValid()) return null
        if (discovery.reference.repository != repository) return null
        if (!discovery.resolvedRevision.equals(resolvedRevision, ignoreCase = true)) return null
        return discovery.candidates.singleOrNull { candidate ->
            candidate.isCommitPinned &&
                candidate.path == candidatePath &&
                candidate.size == expectedBytes &&
                candidate.sha256.orEmpty().equals(expectedSha256, ignoreCase = true)
        }
    }

    companion object {
        const val CURRENT_VERSION = 1
        const val UNKNOWN_SIZE = -1L
        const val MAX_OBSERVED_MESSAGE_CHARS = 512
        private val IMMUTABLE_REVISION = Regex("^[0-9a-fA-F]{40,64}$")
        private val SHA_256 = Regex("^[0-9a-fA-F]{64}$")
        private val OBSERVED_STEPS = setOf(
            "RESOLVE",
            "PREFLIGHT",
            "CONNECT",
            "DOWNLOAD",
            "VERIFY",
            "TOKENIZER_ASSETS",
            "CONVERT_SDZ",
            "TARGET_CACHE",
            "SDX_LOAD",
            "SMOKE_DECODE",
            "ACTIVATE",
            "ACTIVE"
        )

        /** Create a durable checkpoint only for an immutable, currently resolved candidate. */
        fun createOrNull(
            rawReference: String,
            discovery: HuggingFaceGgmlResolver.Discovery,
            candidate: HuggingFaceGgmlResolver.Candidate,
            stage: HuggingFaceImportCheckpointStage
        ): HuggingFaceImportCheckpoint? {
            if (!candidate.isCommitPinned) return null
            val canonicalReference = runCatching {
                HuggingFaceGgmlResolver.parse(rawReference.trim()).canonicalReference
            }.getOrNull() ?: return null
            if (canonicalReference != discovery.reference.canonicalReference) return null
            val belongsToDiscovery = discovery.candidates.any { resolved ->
                resolved.path == candidate.path &&
                    resolved.size == candidate.size &&
                    resolved.downloadUri == candidate.downloadUri &&
                    resolved.sha256.orEmpty().equals(candidate.sha256.orEmpty(), ignoreCase = true)
            }
            if (!belongsToDiscovery) return null
            return HuggingFaceImportCheckpoint(
                rawReference = canonicalReference,
                repository = discovery.reference.repository,
                resolvedRevision = discovery.resolvedRevision.lowercase(Locale.ROOT),
                candidatePath = candidate.path,
                expectedBytes = candidate.size,
                expectedSha256 = candidate.sha256.orEmpty().lowercase(Locale.ROOT),
                stage = stage,
                observedStep = if (stage == HuggingFaceImportCheckpointStage.VERIFIED_DOWNLOAD) {
                    "CONVERT_SDZ"
                } else {
                    "PREFLIGHT"
                },
                observedMessage = if (stage == HuggingFaceImportCheckpointStage.VERIFIED_DOWNLOAD) {
                    "Verified model is ready for one-time SDZ conversion or cached accelerator reuse."
                } else {
                    "Selected immutable model is ready for preflight."
                },
                observedRetryWillResumeOrReuse =
                    stage == HuggingFaceImportCheckpointStage.VERIFIED_DOWNLOAD
            ).takeIf(HuggingFaceImportCheckpoint::isStructurallyValid)
        }
    }
}
