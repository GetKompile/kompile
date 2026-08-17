package ai.kompile.chat.local.android.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelUiStateTest {

    @Test
    fun blankSelectionRequiresImportWithoutTouchingStorage() {
        var checkedStorage = false

        val state = initialModelState("   ") {
            checkedStorage = true
            false
        }

        assertSame(ModelUiState.Missing, state)
        assertFalse(state.startsNativeRuntime())
        assertFalse(checkedStorage)
    }

    @Test
    fun staleSelectionIsReportedAsLoadFailure() {
        val path = "/data/user/0/ai.kompile/files/models/missing.sdz"

        val state = initialModelState(path) { false }

        assertTrue(state is ModelUiState.Failed)
        state as ModelUiState.Failed
        assertEquals(path, state.path)
        assertTrue(state.message.contains("no longer available"))
        assertFalse(state.startsNativeRuntime())
    }

    @Test
    fun existingSelectionAdvancesToNativeStartup() {
        val path = "/data/user/0/ai.kompile/files/models/model.sdz"

        val state = initialModelState(path) { candidate -> candidate == path }

        assertSame(ModelUiState.Checking, state)
        assertTrue(state.startsNativeRuntime())
    }

    @Test
    fun staleGraphSelectionFallsBackToBundledGraph() {
        assertTrue(shouldActivateBundledGraph("/missing/graph.kgraph") { false })
    }

    @Test
    fun existingGraphSelectionIsPreserved() {
        val path = "/data/user/0/ai.kompile/files/graphs/custom.kgraph"
        assertFalse(shouldActivateBundledGraph(path) { candidate -> candidate == path })
    }

    @Test
    fun graphSelectionWaitsForModelWithoutReportingFailure() {
        val path = "/data/user/0/ai.kompile/files/graphs/custom.kgraph"

        val outcome = graphImportOutcome(path, GraphUiState.WaitingForModel)

        assertTrue(outcome is GraphImportOutcome.Deferred)
        outcome as GraphImportOutcome.Deferred
        assertEquals(path, outcome.path)
        assertTrue(outcome.message.contains("Import a local model"))
    }

    @Test
    fun matchingGraphSelectionReportsActive() {
        val path = "/data/user/0/ai.kompile/files/graphs/custom.kgraph"

        assertEquals(
            GraphImportOutcome.Active(path),
            graphImportOutcome(path, GraphUiState.Ready(path))
        )
    }

    @Test
    fun mismatchedActiveGraphFailsClosed() {
        val imported = "/data/user/0/ai.kompile/files/graphs/imported.kgraph"
        val active = "/data/user/0/ai.kompile/files/graphs/other.kgraph"

        assertTrue(graphImportOutcome(imported, GraphUiState.Ready(active)) is GraphImportOutcome.Failed)
    }

    @Test
    fun graphActivationFailureIsPreserved() {
        val message = "Native graph format validation failed"
        val original = IllegalStateException(message)

        val outcome = graphImportOutcome(
            "/graphs/imported.kgraph",
            GraphUiState.Failed(null, message, original.stackTraceToString())
        )

        assertTrue(outcome is GraphImportOutcome.Failed)
        outcome as GraphImportOutcome.Failed
        assertEquals(message, outcome.message)
        assertEquals(original.stackTraceToString(), outcome.stackTrace)
    }

    @Test
    fun projectImportFailureKeepsItsActionablePhase() {
        val failure = ProjectImportOutcome.Failed(
            ProjectImportPhase.MODEL_TARGET,
            "No embedded cache for android-arm64-nnapi-accelerator"
        )

        assertEquals(
            "Model target: No embedded cache for android-arm64-nnapi-accelerator",
            failure.displayMessage
        )
    }

    @Test
    fun activeProjectOutcomeKeepsModelGraphAndSourcesTogether() {
        val active = ProjectImportOutcome.Active(
            projectId = "research",
            projectName = "Research",
            revision = "abc123",
            modelPath = "/projects/research/model.sdz",
            graphPath = "/projects/research/project.kgraph",
            sourcesPath = "/projects/research/data/markdown",
            sourceCount = 3
        )

        assertEquals("/projects/research/model.sdz", active.modelPath)
        assertEquals("/projects/research/project.kgraph", active.graphPath)
        assertEquals(3, active.sourceCount)
    }

    @Test
    fun tensorG3CapsGenerationAtTheSmokeProvenKvWindow() {
        assertEquals(
            TENSOR_G3_MAX_GENERATION_TOKENS,
            effectiveMaxTokensForTarget(1024, TENSOR_G3_TARGET_PROFILE)
        )
        assertEquals(64, effectiveMaxTokensForTarget(64, TENSOR_G3_TARGET_PROFILE))
        assertEquals(
            TENSOR_G3_MAX_GENERATION_TOKENS,
            maxGenerationTokensForTarget(TENSOR_G3_TARGET_PROFILE)
        )
    }

    @Test
    fun otherAcceleratorsKeepTheirConfiguredGenerationBudget() {
        assertEquals(1024, effectiveMaxTokensForTarget(1024, "android-arm64-vulkan"))
        assertEquals(4096, maxGenerationTokensForTarget("android-arm64-vulkan"))
    }

    @Test
    fun modelLoadingIncludesStartupAndModelProducingImportsOnly() {
        assertTrue(isModelLoading(ModelUiState.Checking, ImportOperationKind.NONE))
        assertTrue(isModelLoading(ModelUiState.Missing, ImportOperationKind.HUGGING_FACE))
        assertTrue(isModelLoading(ModelUiState.Missing, ImportOperationKind.MODEL_ARCHIVE))
        assertFalse(isModelLoading(ModelUiState.Missing, ImportOperationKind.GRAPH))
        assertFalse(
            isModelLoading(
                ModelUiState.Ready("/models/model.sdz", "LOCAL_TENSOR_G3_NNAPI"),
                ImportOperationKind.NONE
            )
        )
    }
}
