package ai.kompile.chat.local.android.viewmodel

/**
 * Owns the complete replacement lifecycle. The candidate selection is staged durably,
 * the previous runtime is detached before the candidate is opened, and staged persistence
 * is promoted only after the proven candidate has been published.
 */
internal class ProvenModelActivationTransaction<Artifact, Candidate, Activation>(
    private val stagePendingSelection: (Artifact) -> Boolean,
    private val detachPreviousRuntime: () -> Unit,
    private val openCandidate: (Artifact) -> Candidate,
    private val decodedGeneration: (Candidate) -> String,
    private val publish: (Artifact, Candidate) -> Activation,
    private val promotePendingSelection: () -> Boolean,
    private val rollbackPublished: (Activation) -> Unit,
    private val discardPendingSelection: () -> Boolean,
    private val restorePreviousSelection: () -> Boolean,
    private val restorePreviousRuntime: () -> Unit,
    private val closeCandidate: (Candidate) -> Unit
) {
    private inline fun preservePrimaryFailure(
        primary: Throwable,
        cleanup: () -> Unit
    ) {
        try {
            cleanup()
        } catch (cleanupFailure: Throwable) {
            primary.addSuppressed(cleanupFailure)
        }
    }

    private inline fun requireRollbackSuccess(
        primary: Throwable,
        failureMessage: String,
        rollback: () -> Boolean
    ) {
        preservePrimaryFailure(primary) {
            if (!rollback()) {
                primary.addSuppressed(IllegalStateException(failureMessage))
            }
        }
    }

    fun execute(artifact: Artifact): Activation {
        var candidate: Candidate? = null
        var activation: Activation? = null
        var pendingStageAttempted = false
        var previousDetached = false
        var published = false
        var promotionAttempted = false
        try {
            pendingStageAttempted = true
            check(stagePendingSelection(artifact)) {
                "Android could not stage the imported model selection."
            }

            detachPreviousRuntime()
            previousDetached = true

            val opened = openCandidate(artifact)
            candidate = opened
            check(decodedGeneration(opened).isNotBlank()) {
                "The SDX model decoded no tokens."
            }

            val publishedActivation = publish(artifact, opened)
            activation = publishedActivation
            published = true

            promotionAttempted = true
            check(promotePendingSelection()) {
                "Android could not promote the proven model selection."
            }
            return publishedActivation
        } catch (failure: Throwable) {
            if (published) {
                activation?.let { publishedActivation ->
                    preservePrimaryFailure(failure) {
                        rollbackPublished(publishedActivation)
                    }
                }
            } else {
                candidate?.let { opened ->
                    preservePrimaryFailure(failure) {
                        closeCandidate(opened)
                    }
                }
                candidate = null
            }
            if (promotionAttempted) {
                requireRollbackSuccess(
                    primary = failure,
                    failureMessage = "Android could not restore the previous active selection.",
                    rollback = restorePreviousSelection
                )
            }
            if (pendingStageAttempted) {
                requireRollbackSuccess(
                    primary = failure,
                    failureMessage = "Android could not discard pending selection.",
                    rollback = discardPendingSelection
                )
            }
            if (previousDetached) {
                preservePrimaryFailure(failure, restorePreviousRuntime)
            }
            throw failure
        }
    }
}

/** Lock-free admission gate used before a chat coroutine is launched. */
internal class AtomicSendGate {
    private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    fun tryAcquire(): Boolean = inFlight.compareAndSet(false, true)

    fun release() {
        check(inFlight.compareAndSet(true, false)) { "Send gate was not acquired." }
    }
}
